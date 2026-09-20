package com.folio.reader.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the backfill's memory decisions: the batch derived from the model, and the slice rule that is
 * left once the batch owns the peak.
 *
 * ### The failure these numbers come from
 *
 * The reader's report was **"even when the indexing does happen, it happens too slowly and crashes
 * things"**. On device that is a hard kill rather than a crash: the process is named by ColorOS's
 * low-memory watcher —
 *
 * ```
 * LOWMEM_WATCHER_EVENT: abnormal_pid: 30549, abnormal_size: 1960252,
 *                      total: 7612880, free: 11904, available: 880232
 * ```
 *
 * — and `ActivityManager` records `Process com.folio.reader has died: prcp FGS` with an **empty
 * crash buffer**.
 *
 * ### Peak footprint is `batch x seq^2`, and the batch was not connected
 *
 * The dominant allocation is the per-layer attention score matrix, so the term is quadratic in the
 * model's token window. That part was understood; what was not is that **the batch never reached the
 * encoder at all**. Both callers grouped chunks per *chapter* and drained when the accumulated count
 * reached the batch, but a chapter is ~44 chunks and arrives as one indivisible group — so every pass
 * ran at 44–62 rows whether the batch said 8 or 32. Pinning the batch to 8 changed nothing on device,
 * which is how the defect presented from the outside: a memory knob that measured as inert.
 *
 * The fix moved the bound to `EmbeddingIndexer.flush`, which sub-batches the embedding call while
 * keeping each chapter's storage atomic. The numbers below are what that bound now computes to.
 *
 * ### Where the constants come from
 *
 * One instrumented forward pass, read from inside the process because `/proc/<pid>/smaps` is
 * unreadable for the shell UID on this device:
 *
 * ```
 * OnnxMem: session created: pss 287MB -> 400MB (delta 142MB)
 * OnnxMem: run #1:          pss=4380MB batch=62 seq=512 out=12189696 floats
 * ```
 *
 * 3980 MB for 62 rows x 512 tokens implies ~245 bytes per row per squared token, against a 320 MB
 * budget. The pair of measurements that used to calibrate this (implying k ≈ 500) were taken with
 * the ONNX arena silently still on, so they were arena high-water marks rather than the cost of a
 * pass, and they have been discarded.
 */
class EmbeddingBackfillSliceSizeTest {

    private val ceiling = EmbeddingBackfillWorker.CHAPTERS_PER_SLICE

    // --- The batch is derived from the model's token ceiling -------------------------------------

    @Test
    fun `a wide-window model gets a smaller batch than a narrow one`() {
        // The whole point of deriving rather than fixing: Arctic-S's 512-token window costs four
        // times what MiniLM's 256-token window costs *per row*, because the term is quadratic. A
        // single batch size for both is either unsafe on Arctic or needlessly slow on MiniLM.
        val arctic = com.folio.reader.ml.EmbeddingIndexer.batchFor(512)
        val miniLm = com.folio.reader.ml.EmbeddingIndexer.batchFor(256)
        assertTrue(
            arctic < miniLm,
            "the 512-token model must get a smaller batch than the 256-token one, got $arctic vs $miniLm",
        )
    }

    @Test
    fun `the derived batch keeps one pass inside the native budget`() {
        // The property that matters, stated as the inequality it is: batch x k x tokens^2 must not
        // exceed the budget — *except* where a single row already does, in which case the one-row
        // floor is the best available answer and the overshoot is documented rather than hidden by
        // rounding the batch to zero.
        val budget = com.folio.reader.ml.EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES
        val k = com.folio.reader.ml.EmbeddingIndexer.BYTES_PER_ROW_PER_TOKEN_SQUARED
        for (tokens in listOf(64, 128, 256, 384, 512, 768)) {
            val batch = com.folio.reader.ml.EmbeddingIndexer.batchFor(tokens)
            val peak = batch.toLong() * k * tokens.toLong() * tokens.toLong()
            assertTrue(
                peak <= budget || batch == 1,
                "batch $batch at $tokens tokens needs ${peak / (1024 * 1024)} MB, over the " +
                    "${budget / (1024 * 1024)} MB budget, and is not the one-row floor",
            )
        }
        // For the models actually shipping, the budget is honoured with room to spare — these are
        // the two cases that decide whether a sweep coexists with the rest of the phone.
        for (tokens in listOf(256, 512)) {
            val batch = com.folio.reader.ml.EmbeddingIndexer.batchFor(tokens)
            val peak = batch.toLong() * k * tokens.toLong() * tokens.toLong()
            assertTrue(
                peak <= budget,
                "a shipping model at $tokens tokens must fit the budget, needs " +
                    "${peak / (1024 * 1024)} MB",
            )
            assertTrue(batch > 1, "a shipping model must batch more than one row, got $batch")
        }
    }

    @Test
    fun `a window wider than the budget allows still embeds one row`() {
        // 245 x 1170^2 is ~320 MB, so past ~1170 tokens one row exceeds the entire budget. That must
        // not yield a batch of zero: the backfill selects chapters with no vectors, so a zero batch
        // would pick the same chapter on every slice and never advance — a stall, not a reduction.
        val k = com.folio.reader.ml.EmbeddingIndexer.BYTES_PER_ROW_PER_TOKEN_SQUARED
        val budget = com.folio.reader.ml.EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES
        val crossover = kotlin.math.sqrt(budget.toDouble() / k).toInt()
        assertEquals(
            1,
            com.folio.reader.ml.EmbeddingIndexer.batchFor(crossover + 64),
            "a window wider than the budget must fall to the one-row floor",
        )
    }

    @Test
    fun `the derived batch is never zero, however wide the window`() {
        // A model whose single row exceeds the budget must still be able to embed one row, or its
        // index could never be built at all — the backfill would select the same chapter forever.
        for (tokens in listOf(512, 1024, 2048, 8192)) {
            val batch = com.folio.reader.ml.EmbeddingIndexer.batchFor(tokens)
            assertTrue(batch >= 1, "batch must never be zero, got $batch at $tokens tokens")
        }
    }

    @Test
    fun `the derived batch never exceeds the ceiling`() {
        // A tiny-window model could afford an enormous batch arithmetically; the ceiling is the
        // throughput-tested value and is not raised just because the memory would allow it.
        val tiny = com.folio.reader.ml.EmbeddingIndexer.batchFor(64)
        assertTrue(
            tiny <= com.folio.reader.ml.EmbeddingIndexer.MAX_BATCH,
            "batch must not exceed the ceiling, got $tiny",
        )
    }

    @Test
    fun `the derived batch is monotonic in the token ceiling`() {
        val tokens = listOf(64, 128, 256, 384, 512, 768, 1024)
        val batches = tokens.map { com.folio.reader.ml.EmbeddingIndexer.batchFor(it) }
        assertEquals(
            batches.sortedDescending(),
            batches,
            "a wider window must never buy a larger batch: $batches",
        )
    }

    @Test
    fun `the shipping models get the batches the measurement implies`() {
        // Pinned as literals because these are the two numbers that decide whether a sweep coexists
        // with the rest of the phone, and they should not move without a device measurement behind
        // the change.
        //
        // Arctic-S: 5 rows x 245 x 512^2 = 321 MB, against a 320 MB budget — i.e. the budget is
        // saturated rather than approximated. MiniLM's 256-token window is a quarter the per-row
        // cost, so it gets 20.
        assertEquals(5, com.folio.reader.ml.EmbeddingIndexer.batchFor(512), "Arctic-S (512 tokens)")
        assertEquals(20, com.folio.reader.ml.EmbeddingIndexer.batchFor(256), "MiniLM-L6-v2 (256 tokens)")
    }

    @Test
    fun `the worker's ceiling matches the indexer's`() {
        // The two drifting apart is the defect this whole file exists to catch. The worker now
        // budgets against the indexer's *budget* rather than a batch size, so the ceiling is the
        // only number left to keep in step.
        assertEquals(
            com.folio.reader.ml.EmbeddingIndexer.MAX_BATCH,
            EmbeddingBackfillWorker.maxBatchChunksForTest(),
            "the worker must not hold its own copy of the indexer's ceiling",
        )
    }

    @Test
    fun `one batch's budget is the indexer's budget, not a smaller estimate`() {
        // A peak-footprint budget that is too small is worse than none, because it makes every gate
        // derived from it decorative — which is exactly how a 1.5 GB peak passed a 55 MB budget.
        assertEquals(
            com.folio.reader.ml.EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES,
            EmbeddingBackfillWorker.batchPeakBytesForTest(),
            "the worker must budget the indexer's actual peak",
        )
        assertTrue(
            EmbeddingBackfillWorker.batchPeakBytesForTest() > 20L * 1024 * 1024,
            "a batch budget under 20 MB cannot describe a transformer pass",
        )
    }

    @Test
    fun `the constrained batch is a quarter of whatever the model's batch is`() {
        // Expressed as a fraction, because the normal batch is per model. A fixed constant would be
        // *larger* than normal on Arctic-S (batch 5) and a no-op on MiniLM (batch 20) — the same
        // defect shape as the byte estimate this file replaced.
        //
        // The reduction is `<=` rather than `<` at a normal batch of 1: there is no smaller positive
        // batch, and returning 0 would mean "embed nothing", which is a stall rather than a
        // reduction. Above 1 the reduction must be strict.
        for (normal in listOf(1, 2, 3, 5, 8, 16, 32)) {
            val constrained = EmbeddingBackfillWorker.constrainedBatchForTest(normal)
            assertTrue(constrained >= 1, "constrained must still embed something, got $constrained")
            assertTrue(
                constrained <= normal,
                "constrained ($constrained) must not exceed normal ($normal)",
            )
            if (normal >= 2) {
                assertTrue(
                    constrained < normal,
                    "constrained ($constrained) must be a strict reduction of normal ($normal)",
                )
            }
        }
        assertEquals(1, EmbeddingBackfillWorker.constrainedBatchForTest(5), "Arctic-S: 5 -> 1")
        assertEquals(5, EmbeddingBackfillWorker.constrainedBatchForTest(20), "MiniLM: 20 -> 5")
    }

    // --- The slice is a batching figure, no longer a memory model ---------------------------------

    @Test
    fun `a slice is a chapter count, and pressure shortens it`() {
        // This used to be arithmetic over `availMem` divided by a per-batch byte estimate, which on
        // the test device always collapsed to the 4-chapter floor — a 2561-chapter library then
        // needed ~640 slices, each rebuilding an ONNX session, and the reader's report of indexing
        // being "extremely slow" is exactly that shape. The arithmetic was only sound while peak
        // footprint scaled with the slice; `EmbeddingIndexer.flush` bounds the pass now, so the
        // slice is free to be a batching decision.
        //
        // What still has to hold is the *response*: a pressured device must get a shorter slice, or
        // the pressure branch would be a no-op.
        assertEquals(
            EmbeddingBackfillWorker.CHAPTERS_PER_SLICE,
            EmbeddingBackfillWorker.sliceSizeForTest(pressured = false),
            "a healthy device must get the full slice",
        )
        val pressured = EmbeddingBackfillWorker.sliceSizeForTest(pressured = true)
        assertTrue(
            pressured < EmbeddingBackfillWorker.CHAPTERS_PER_SLICE,
            "a pressured device must get a shorter slice, got $pressured",
        )
        assertTrue(pressured >= 1, "a pressured slice must still make progress, got $pressured")
    }

    @Test
    fun `the slice ceiling is large enough to amortise a session`() {
        // A slice is the unit that amortises ONNX session construction, measured at 142 MB plus a
        // graph load. A ceiling of a handful of chapters would pay that cost hundreds of times over
        // a library, which is a throughput bug rather than a memory saving.
        assertTrue(
            ceiling >= 8,
            "the slice ceiling ($ceiling) is too small to amortise a session over",
        )
    }

    // --- The headroom gate ------------------------------------------------------------------------

    @Test
    fun `the headroom gate is above one batch plus a live session`() {
        // `awaitHeadroom` refuses to start below MIN_HEADROOM_BYTES. If that gate were set below the
        // peak cost of one batch, the worker would happily start a batch it cannot hold — which is
        // the exact failure the gate exists to prevent. Conversely a gate far above a batch's cost
        // would stall indexing on a perfectly usable device.
        val headroom = EmbeddingBackfillWorker.MIN_HEADROOM_BYTES
        assertTrue(headroom >= 128L * 1024 * 1024, "gate is suspiciously low: $headroom bytes")
        assertTrue(headroom <= 1024L * 1024 * 1024, "gate is suspiciously high: $headroom bytes")
        assertTrue(
            headroom >= EmbeddingBackfillWorker.batchPeakBytesForTest(),
            "the gate must at least cover one batch's peak, or it permits work it cannot hold",
        )
    }

    @Test
    fun `the headroom gate does not fire on a merely-busy device`() {
        // The 0.35 version of this floor fired on *every* slice of the test device and made
        // indexing crawl — the gate waited its full five minutes each time. Observed on device:
        //
        //   I EmbeddingBackfill: low memory: 2090MB free of 2616MB needed; yielding
        //
        // A gate that fires on a device with 2 GB free is as wrong as one that never fires, and
        // both come from picking a threshold without checking what a real device reports. The
        // device's own figures, from `cat /proc/meminfo`:
        val deviceTotal = 7_653_712L * 1024L          // MemTotal
        val deviceAvail = 2_090_000L * 1024L          // MemAvailable observed while indexing
        val floor = EmbeddingBackfillWorker.headroomFloorFor(deviceTotal)
        assertTrue(
            floor < deviceAvail,
            "the floor ($floor) must sit below what a busy 7.30 GB device offers " +
                "($deviceAvail), or indexing waits five minutes per slice and never finishes",
        )
    }

    @Test
    fun `the headroom gate is a proportion on a large device, not a flat floor`() {
        // Guards the original defect in the other direction: a flat 256 MB against `availMem` is
        // ~3% of a 7.30 GB device's RAM, so it could not distinguish "busy" from "starved". The
        // floor must still scale with RAM rather than being the constant it started as.
        val deviceTotal = 7_653_712L * 1024L
        val floor = EmbeddingBackfillWorker.headroomFloorFor(deviceTotal)
        assertTrue(
            floor > EmbeddingBackfillWorker.MIN_HEADROOM_BYTES,
            "a large device must be held above the absolute floor, got $floor",
        )
        // And it must still leave room to work: the floor cannot be so high that a device with
        // healthy free memory is refused. 1 GB free must be enough to index on any device.
        assertTrue(
            floor < 1024L * 1024 * 1024,
            "the floor ($floor) must not refuse a device with 1 GB free",
        )
    }

    @Test
    fun `the headroom gate still fires on a starved device`() {
        // The point of a gate is that it can fail. A device genuinely short of memory — the
        // condition that got Folio killed by name — must be held back.
        val floor = EmbeddingBackfillWorker.headroomFloorFor(7_653_712L * 1024L)
        assertTrue(
            300L * 1024 * 1024 < floor,
            "a device with 300 MB free must be refused, got a floor of $floor",
        )
    }

    @Test
    fun `a small device is held to the absolute floor`() {
        // The proportion must not scale *down* without limit, or a tiny device would be asked to
        // keep only a few megabytes free — less than a single batch costs. With the 0.125 fraction
        // the crossover is 4 GB, so anything below that falls through to the absolute floor.
        val floor = EmbeddingBackfillWorker.headroomFloorFor(512L * 1024 * 1024)
        assertEquals(
            EmbeddingBackfillWorker.MIN_HEADROOM_BYTES,
            floor,
            "a 512 MB device must fall through to the absolute floor, got $floor",
        )
    }

    @Test
    fun `the headroom floor is monotonic in RAM`() {
        val samples = listOf(512L, 1024L, 2048L, 4096L, 8192L, 16384L).map { it * 1024 * 1024 }
        val floors = samples.map { EmbeddingBackfillWorker.headroomFloorFor(it) }
        assertEquals(floors.sorted(), floors, "the floor must not fall as RAM grows: $floors")
    }

    @Test
    fun `a run holding a foreground service is allowed to last much longer`() {
        // The two budgets exist because "an ordinary job" and "an FGS-backed job" are different
        // states, and this device punishes confusing them. A handover is a fresh worker start, and
        // a fresh start re-runs `setForeground` — which is refused once the app is off screen. So
        // every handover is a chance to lose the one thing that keeps the process unfrozen, and a
        // run that already holds an FGS should not volunteer to give it up.
        val job = EmbeddingBackfillWorker.runBudgetFor(hasForegroundService = false)
        val fgs = EmbeddingBackfillWorker.runBudgetFor(hasForegroundService = true)
        assertTrue(
            fgs > job,
            "an FGS-backed run must be allowed longer than a plain job, got fgs=$fgs job=$job",
        )
        // The job figure exists to stop *before* the platform's ~10 minute limit, so it must stay
        // under it — a budget above the limit would mean being stopped mid-slice rather than
        // handing over deliberately.
        assertTrue(
            job < 10 * 60 * 1000L,
            "the no-FGS budget must clear the platform's ~10 minute job limit, got $job",
        )
    }
}
