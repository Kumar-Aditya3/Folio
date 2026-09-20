package com.folio.reader.ml

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session census must be accurate, because it is the only instrument the app has for the
 * one failure mode that has actually killed it.
 *
 * The app was measured at 819 MB native against a 19 MB Java heap and was reaped by the
 * low-memory killer. A desktop probe then established that a live session costs ~41 MB of native
 * memory, exactly linearly — so the count of live sessions *is* the memory figure, and an
 * instrument that silently over- or under-counts is worse than none. See [MlSessionCensus].
 *
 * These tests cover the three ways a counter like this goes wrong: it misses an open, it misses a
 * close, or it goes negative on a double close and reports a leak as smaller than it is.
 */
class MlSessionCensusTest {

    private val model: File? = TestModelFixtures.miniLmModel()
    private val vocab: File? = TestModelFixtures.miniLmVocab()

    @Test
    fun `opening and closing a session returns the live count to where it started`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        val before = MlSessionCensus.alive
        repeat(3) {
            val embedder = embedder(modelFile, vocabFile)
            runBlocking { embedder.embed(listOf(SAMPLE), EmbedKind.PASSAGE) }
            assertEquals(before + 1, MlSessionCensus.alive, "one session open must count as one")
            embedder.close()
            assertEquals(before, MlSessionCensus.alive, "close must return the count")
        }
    }

    @Test
    fun `a session that is never embedded in is not counted`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        // The session is lazy: constructing an embedder commits no native memory, so counting it
        // at construction would over-report a spike that never happened.
        val before = MlSessionCensus.alive
        val embedder = embedder(modelFile, vocabFile)
        assertEquals(before, MlSessionCensus.alive, "a lazy session must not be counted before it opens")
        embedder.close()
        assertEquals(before, MlSessionCensus.alive, "closing a never-opened session must not go negative")
    }

    @Test
    fun `closing twice does not drive the count below its start`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        val before = MlSessionCensus.alive
        val embedder = embedder(modelFile, vocabFile)
        runBlocking { embedder.embed(listOf(SAMPLE), EmbedKind.PASSAGE) }
        embedder.close()
        embedder.close()
        // `OnnxEmbedder.close` is documented as idempotent and is called from `finally` blocks
        // that can run after an earlier close. If the second call decremented, every such path
        // would under-report, and a genuine leak would read as a smaller one.
        assertEquals(before, MlSessionCensus.alive, "a double close must be a no-op")
    }

    @Test
    fun `concurrent sessions each count, and the peak records the high-water mark`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        val before = MlSessionCensus.alive
        // Three at once is the shape a leaked backfill leaves behind, and it is what the peak
        // exists to make visible — a count sampled after the fact would show only the survivors.
        val held = (1..3).map { embedder(modelFile, vocabFile).also { e ->
            runBlocking { e.embed(listOf(SAMPLE), EmbedKind.PASSAGE) }
        } }
        assertEquals(before + 3, MlSessionCensus.alive, "each concurrent session must count")
        assertTrue(
            MlSessionCensus.peak >= before + 3,
            "peak ${MlSessionCensus.peak} must be at least ${before + 3}",
        )
        held.forEach { it.close() }
        assertEquals(before, MlSessionCensus.alive, "releasing all must return the count")
    }

    private fun embedder(modelFile: File, vocabFile: File) = OnnxEmbedder.wordPiece(
        descriptor = EmbeddingModelCatalog.MINILM_L6_V2_INT8,
        modelFile = modelFile,
        vocabFile = vocabFile,
        threads = 1,
    )

    private fun skip(what: String): Unit =
        assumeTrue(false, TestModelFixtures.missingHint(what))

    companion object {
        private const val SAMPLE = "the vessel foundered and the sea took everything"
    }
}
