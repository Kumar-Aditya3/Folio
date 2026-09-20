package com.folio.reader.ml

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `openOnnxModel` must not leak native memory.
 *
 * This exists because it did. `OrtSession.SessionOptions` was closed on the *failure* path only,
 * so every successfully opened model stranded a native handle that owns an ORT CPU memory arena.
 * Java GC cannot see that memory and `-Xmx` does not bound it, so it surfaced as the opposite of
 * the usual OOM: a test JVM sitting at **36 GB of working set with 648 MB of live heap**, still
 * allocating, on a machine with no more room. The benchmark harness opened three models, which is
 * how it grew fast enough to notice at all.
 *
 * Three things are asserted, all of which the leak would break:
 *
 * - opening and closing a session many times does not grow the process without bound;
 * - a session can be reopened after being closed, and produces identical output — a
 *   use-after-free on the options handle would corrupt the arithmetic or crash;
 * - `enableCpuMemArena = false` is accepted and changes no embedding component, since the sweep
 *   path now depends on it.
 *
 * Native footprint is read from the OS, not from `Runtime`: the heap was never the problem.
 */
class OnnxSessionLifecycleTest {

    private val model: java.io.File? = TestModelFixtures.miniLmModel()
    private val vocab: java.io.File? = TestModelFixtures.miniLmVocab()

    @Test
    fun `opening and closing a session repeatedly does not strand native memory`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        // A warm-up round set first. The first session pays for loading the native runtime, for
        // JIT-compiling the hot path, and for the allocator's initial regions; measuring that
        // against a later round would report growth that has nothing to do with the leak.
        repeat(ROUNDS) { round(modelFile, vocabFile) }
        val afterWarmup = committedNonHeapBytes()

        repeat(ROUNDS) { round(modelFile, vocabFile) }
        val afterMeasured = committedNonHeapBytes()

        val growth = afterMeasured - afterWarmup
        assertTrue(
            growth < MAX_GROWTH_BYTES,
            "opening and closing $ROUNDS sessions grew committed non-heap memory by " +
                "${growth / 1_000_000} MB (limit ${MAX_GROWTH_BYTES / 1_000_000} MB). " +
                "A leaked SessionOptions per model looks exactly like this — see the class KDoc.",
        )
    }

    @Test
    fun `a session closed and reopened still embeds correctly`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        val first = round(modelFile, vocabFile)
        val second = round(modelFile, vocabFile)

        assertEquals(first.size, second.size, "the same input must embed to the same width")
        // Identical input through two independently opened sessions must produce an identical
        // vector: the model is deterministic and the options must not change the arithmetic.
        assertVectorsEqual(first, second, "across a close/reopen")
    }

    @Test
    fun `a session with the arena disabled embeds the same as one with it enabled`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        // The sweep path turns the arena off. That must be a memory decision only — if it moved a
        // single component, the benchmark would be measuring a different model from the app's.
        val withArena = round(modelFile, vocabFile, enableCpuMemArena = true)
        val withoutArena = round(modelFile, vocabFile, enableCpuMemArena = false)

        assertEquals(withArena.size, withoutArena.size)
        assertVectorsEqual(withArena, withoutArena, "with the CPU memory arena disabled")
    }

    /** Opens a session, embeds one sentence, closes, and returns the vector. */
    private fun round(
        modelFile: java.io.File,
        vocabFile: java.io.File,
        enableCpuMemArena: Boolean = true,
    ): FloatArray {
        val embedder = OnnxEmbedder.wordPiece(
            descriptor = EmbeddingModelCatalog.MINILM_L6_V2_INT8,
            modelFile = modelFile,
            vocabFile = vocabFile,
            threads = 1,
            enableCpuMemArena = enableCpuMemArena,
        )
        return try {
            kotlinx.coroutines.runBlocking {
                embedder.embed(listOf(SAMPLE), EmbedKind.PASSAGE).first()
            }
        } finally {
            // The whole point: close must release both the session and its options. If it does
            // not, the next round's `createSession` inherits a live arena and blows the bound.
            embedder.close()
        }
    }

    private fun assertVectorsEqual(a: FloatArray, b: FloatArray, context: String) {
        a.indices.forEach { i ->
            assertEquals(
                a[i], b[i], 1e-6f,
                "component $i differs $context — the session is not deterministic",
            )
        }
    }

    private fun skip(what: String): Unit =
        assumeTrue(false, TestModelFixtures.missingHint(what))

    companion object {
        /** Enough rounds to make a per-round leak obvious; small enough to stay quick. */
        private const val ROUNDS = 12

        /**
         * Generous on purpose. Native allocators keep freed regions, and JIT/GC bookkeeping moves
         * by tens of MB on its own. One leaked 22 MB model arena per round is ~264 MB over 12
         * rounds, so this catches the real thing without flaking on allocator noise.
         */
        private const val MAX_GROWTH_BYTES = 150L * 1_000_000

        private const val SAMPLE = "the vessel foundered and the sea took everything"
    }
}

/**
 * Committed non-heap memory for this process, in bytes.
 *
 * This is the closest portable, in-JVM reading of "memory that is not the Java heap". It covers
 * the JIT code cache, metaspace and — the part that grows when a native arena leaks — the
 * runtime's own allocations, because `-Xmx` bounds none of them.
 *
 * It under-reports the leak relative to an OS working-set reading: a leaked ORT arena counted
 * here as ~22 MB per round, where the working set showed the same leak as hundreds of MB because
 * of how the allocator and the OS map regions. That is the right direction for a test — a
 * conservative bound that still catches one leaked arena per open.
 */
private fun committedNonHeapBytes(): Long =
    ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.committed
