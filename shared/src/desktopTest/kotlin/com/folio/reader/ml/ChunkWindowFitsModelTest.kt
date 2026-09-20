package com.folio.reader.ml

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the chunk window fits the model's token ceiling, measured with the real tokenizer over
 * real corpus prose.
 *
 * The bug this exists to catch: `DEFAULT_MAX_WORDS = 250` was a plain constant set when MiniLM was
 * the only model, and 250 English words is **more than MiniLM's 256-token limit** (~330–360
 * tokens). The tokenizer truncated the tail of most chunks. Nothing failed — the vector was simply
 * computed from less text than the reader believed, and recall dropped silently.
 *
 * So the assertion is not "the constant looks reasonable" but "the chunk this model produces
 * actually fits, token for token, in prose taken from the shipped corpus".
 */
class ChunkWindowFitsModelTest {

    private val corpusDir = File("testbooks").absoluteFile
        .takeIf { it.isDirectory }
        ?: File("..", "testbooks")

    /**
     * Real chapter prose from the corpus, extracted from the EPUB's XHTML.
     *
     * Deliberately *raw* extraction rather than going through the importer: the token count of a
     * window is a property of prose, and the importer's normalisation only ever removes whitespace
     * runs, which cannot increase the count. Keeping it raw makes the measurement independent of
     * the import pipeline, so a change there cannot silently invalidate this test.
     */
    private fun sampleProse(targetWords: Int): String? {
        val epub = corpusDir.listFiles { f -> f.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.firstOrNull() ?: return null

        return try {
            ZipFile(epub).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) }
                    .sortedBy { it.name }
                    .mapNotNull { entry ->
                        val html = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                        val words = html
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("&[a-zA-Z#0-9]+;"), " ")
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                        if (words.size < targetWords) null
                        else words.subList(0, targetWords).joinToString(" ")
                    }
                    .firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Both catalog models are bert-base-uncased WordPiece over the same 30 522-token vocabulary
     * (`TokenizerParityTest` pins the size; the arctic catalog entry documents the byte-identical
     * `vocab.txt`), so one vocab serves both.
     */
    private fun tokenizerFor(model: EmbeddingModel): WordPieceTokenizer? {
        if (!model.usesTokenTypeIds) return null
        val vocab = TestModelFixtures.miniLmVocab() ?: return null
        return WordPieceTokenizer.fromVocabFile(vocab)
    }

    @Test
    fun `a full-length chunk fits inside the model's token ceiling`() {
        val measured = mutableListOf<String>()

        for (model in EmbeddingModelCatalog.downloadable) {
            val tokenizer = tokenizerFor(model) ?: continue
            val window = model.maxChunkWords
            val prose = sampleProse(window) ?: continue

            // Encode with a ceiling far above the real one: the point is to learn the true length
            // of a full window, not to exercise truncation.
            val tokens = tokenizer.encode(prose, maxLength = Int.MAX_VALUE - 2).size

            assertTrue(
                tokens <= model.maxTokens,
                "${model.id}: a $window-word window tokenized to $tokens tokens, over its " +
                    "${model.maxTokens} ceiling — the tail is being truncated silently",
            )
            measured += "${model.id}: ${window}w -> $tokens tok (ceiling ${model.maxTokens})"
        }

        assumeTrue(
            measured.isNotEmpty(),
            "no model measured — corpus at $corpusDir; " +
                TestModelFixtures.missingHint("MiniLM vocab"),
        )
        println("chunk windows fit: " + measured.joinToString("; "))
    }

    @Test
    fun `the window is derived from the model, so the two cannot drift`() {
        val mini = EmbeddingModelCatalog.MINILM_L6_V2_INT8
        val arctic = EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8

        assertEquals(256, mini.maxTokens)
        assertEquals(512, arctic.maxTokens)

        // A model with twice the ceiling must get a meaningfully larger window. This is the
        // assertion that fails while both models share one hardcoded word count.
        assertTrue(
            arctic.maxChunkWords > mini.maxChunkWords,
            "arctic (${arctic.maxTokens} tok) got ${arctic.maxChunkWords} words, minilm " +
                "(${mini.maxTokens} tok) got ${mini.maxChunkWords} — the window is not " +
                "tracking the model",
        )
    }

    @Test
    fun `the retired constant would not have fit MiniLM`() {
        // Documents *why* the constant was wrong, so nobody restores it as a sane default.
        val tokenizer = tokenizerFor(EmbeddingModelCatalog.MINILM_L6_V2_INT8)
            ?: run { assumeTrue(false, TestModelFixtures.missingHint("MiniLM vocab")); return }

        val legacy = TextChunker.DEFAULT_MAX_WORDS
        val prose = sampleProse(legacy)
            ?: run { assumeTrue(false, "no corpus prose at $corpusDir"); return }
        val tokens = tokenizer.encode(prose, maxLength = Int.MAX_VALUE - 2).size

        assertTrue(
            tokens > EmbeddingModelCatalog.MINILM_L6_V2_INT8.maxTokens,
            "expected the legacy $legacy-word window to exceed MiniLM's " +
                "${EmbeddingModelCatalog.MINILM_L6_V2_INT8.maxTokens} tokens (measured $tokens); " +
                "if this fails, the corpus sample is degenerate rather than the finding being wrong",
        )
    }

    @Test
    fun `the chunker never emits a chunk longer than its window`() {
        // Guards the other half: our own windowing, with no tokenizer involved, so this one runs
        // without fixtures.
        val window = 40
        val text = (1..200).joinToString(" ") { "word$it" }
        val chunks = TextChunker.chunk(
            text = text,
            bookId = "b",
            chapterId = "c",
            spineIndex = 0,
            maxWords = window,
            overlapWords = 10,
        )
        assertTrue(chunks.isNotEmpty(), "a 200-word chapter must chunk")
        for (chunk in chunks) {
            val words = chunk.text.split(" ").count { it.isNotBlank() }
            assertTrue(words <= window, "chunk of $words words exceeds the $window-word window")
        }
    }
}
