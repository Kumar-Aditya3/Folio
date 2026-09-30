package com.folio.reader.ml

import kotlinx.coroutines.delay

/**
 * The **rate** gate for a long embedding sweep.
 *
 * ### What the existing gates already cover, and the one they leave out
 *
 * The sweep is already held back four ways, and every one of them is about *how much* of the
 * machine it may touch at an instant:
 *
 * - **pool isolation** — it runs on [MlDispatchers.inference], a two-worker pool of its own,
 *   rather than the `Dispatchers.Default` the UI's off-main work shares;
 * - **width** — [MlDispatchers.backfillThreads] intra-op threads while the reader may be in the
 *   app, [MlDispatchers.backgroundThreads] once it is provably elsewhere;
 * - **memory** — [EmbeddingIndexer.batchFor] bounds rows per forward pass, and the worker's
 *   `awaitHeadroom()` yields when the device is short;
 * - **wall-clock** — a slice budget, a run budget, and a resumable slice query.
 *
 * None of them caps *how often it works*. From the first forward pass to the last the sweep
 * issues pass after pass back-to-back for up to 90 seconds: two of eight cores, but occupied
 * 100% of the time. A capped width with no duty cycle still steals scheduler slots continuously,
 * which is what "the whole app is slow while it indexes" looks like from the inside.
 *
 * ### Why a gap rather than a lower priority
 *
 * The pool's own threads are `Thread.NORM_PRIORITY - 1`, but that is not the thread doing the
 * maths. ONNX Runtime creates its intra-op workers inside native `env.createSession`
 * (`OnnxModel.android.kt`), so whatever Java-level priority we set on the coroutine worker is not
 * reliably inherited by the native pool that actually burns the cores. Since we cannot *ask* the
 * scheduler to prefer the UI, we hand it the window instead: after every pass the sweep stops
 * runnable for as long as the pass took, so the UI thread gets the machine with no priority
 * assumption baked in.
 *
 * ### What this deliberately is not
 *
 * It is not a deferral, and not a cancellation. The sweep keeps working in whatever process state
 * it finds — waiting for the app to be backgrounded was tried and removed, because ColorOS freezes
 * a backgrounded process within seconds (`EmbeddingBackfillWorker` documents that reversal), so a
 * gate that waits for background only ever advances while the reader is in the app. A started pass
 * always completes. Only *when* work runs changes; never *what* is computed — see
 * [BackfillPacedDeterminismTest] for the lock on that.
 */
class SweepPace(
    /**
     * Whether the reader is in the app *right now*, read fresh for every pass.
     *
     * The whole point is the reaction time: a forward pass is ~15-100 ms, so the sweep's CPU
     * occupancy responds to the reader arriving within one pass. Width cannot respond that fast —
     * intra-op threads are frozen when the session is opened and re-opening costs ~142 MB plus a
     * graph load — so thread count still changes only per slice. Callers memoise this; see
     * `EmbeddingBackfillWorker`, where the Android state read happens once per ~250 ms rather than
     * once per pass.
     */
    private val readerInApp: () -> Boolean = { false },
    /**
     * How the pause is actually taken.
     *
     * Injectable so the determinism test can prove a pass is followed by a hold without the suite
     * really sleeping between every forward pass of a corpus.
     */
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    /**
     * Runs one forward pass and then holds off for as long as it took.
     *
     * Wrapping the block rather than handing a start timestamp back keeps the call site one line
     * and makes it impossible to pace a pass without measuring it.
     */
    suspend fun <T> embedPass(block: suspend () -> T): T {
        val started = System.nanoTime()
        val result = block()
        val pause = pauseMsFor((System.nanoTime() - started) / 1_000_000L, readerInApp())
        if (pause > 0L) sleep(pause)
        return result
    }

    companion object {
        /** Never sleeps: the reader is not in the app, or the caller has no rate to respect. */
        val None = SweepPace()

        /**
         * Shortest pause taken while the reader is in the app.
         *
         * A pass that finishes in a millisecond and sleeps for a millisecond re-runs before the
         * scheduler gives anything else a turn; at Linux's normal granularity a sub-4 ms yield is
         * close to no yield at all. So even an instant pass still opens a usable gap.
         */
        const val MIN_PAUSE_MS = 4L

        /**
         * Longest pause taken for any single pass.
         *
         * Set well *above* what a pass actually costs (~15-100 ms on the test device), which is
         * the point: a ceiling near or below the pass duration would raise the duty cycle on the
         * *slowest* devices, which is where the sweep needs to back off most. It exists only so one
         * pathological timing — a long GC, a wedged session open — cannot spend a whole 90 s slice
         * budget asleep.
         */
        const val MAX_PAUSE_MS = 500L

        /**
         * Milliseconds to hold off after a pass that cost [passMs], for a reader in state
         * [readerInApp].
         *
         * Pure, so [SweepPaceTest] can pin the arithmetic without a sweep, a model or a device.
         *
         * The in-app figure is one-for-one: pause == the pass's own duration, so the sweep holds a
         * core half the time. Combined with [MlDispatchers.backfillThreads] (two of eight cores on
         * the test device) the sweep's average draw is roughly half a core, and every pass is
         * followed by a window in which no ML thread is runnable. The cost is honest and worth
         * stating: an index that is building while the reader is in the app takes about twice as
         * long. Away from the app it is 0 ms, so the sweep runs flat out — and that is the state
         * the width gate already made safe.
         */
        fun pauseMsFor(passMs: Long, readerInApp: Boolean): Long =
            if (!readerInApp) 0L
            else passMs.coerceAtLeast(0L).coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
    }
}
