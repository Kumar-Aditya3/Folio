package com.folio.reader.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests against a synthetic vocabulary.
 *
 * These pin the algorithm's behaviour (normalisation, punctuation isolation, greedy
 * longest-match, `##` continuation, truncation). Whether the algorithm matches the
 * reference Rust implementation on real vocabulary is [TokenizerParityTest]'s job — the
 * two are deliberately separate so this file can run with no fixtures on disk.
 */
class WordPieceTokenizerTest {

    private val vocab = listOf(
        "[PAD]",      // 0
        "[UNK]",      // 1
        "[CLS]",      // 2
        "[SEP]",      // 3
        "hello",      // 4
        "world",      // 5
        "##s",        // 6
        "don",        // 7
        "'",          // 8
        "t",          // 9
        "the",        // 10
        "quick",      // 11
        "brown",      // 12
        "fox",        // 13
        "cafe",       // 14
        ",",          // 15
        "!",          // 16
        "##ing",      // 17
        "go",         // 18
        "a",          // 19
    )

    private val tokenizer = WordPieceTokenizer.fromVocabLines(vocab)

    @Test
    fun `special token ids are read from the vocabulary`() {
        assertEquals(2, tokenizer.clsId)
        assertEquals(3, tokenizer.sepId)
        assertEquals(1, tokenizer.unkId)
        assertEquals(0, tokenizer.padId)
        assertEquals(vocab.size, tokenizer.vocabSize)
    }

    @Test
    fun `plain text is framed with CLS and SEP`() {
        assertEquals(listOf(2, 4, 5, 3), tokenizer.encode("hello world", 256).toList())
    }

    @Test
    fun `punctuation is isolated`() {
        assertEquals(listOf(2, 4, 15, 5, 16, 3), tokenizer.encode("hello, world!", 256).toList())
    }

    @Test
    fun `contractions split the way BERT does`() {
        // "don't" -> "don" + "'" + "t"
        assertEquals(listOf(2, 7, 8, 9, 3), tokenizer.encode("don't", 256).toList())
    }

    @Test
    fun `continuation pieces use the double-hash prefix`() {
        // "hellos" -> "hello" + "##s"
        assertEquals(listOf(2, 4, 6, 3), tokenizer.encode("hellos", 256).toList())
    }

    @Test
    fun `a word with no full match becomes UNK`() {
        assertEquals(listOf(2, 1, 3), tokenizer.encode("xyzzy", 256).toList())
    }

    @Test
    fun `text is lowercased`() {
        assertEquals(
            tokenizer.encode("the quick", 256).toList(),
            tokenizer.encode("THE QUICK", 256).toList(),
        )
    }

    @Test
    fun `accents are stripped`() {
        // "café" normalises to "cafe", which is in the vocabulary.
        assertEquals(listOf(2, 14, 3), tokenizer.encode("café", 256).toList())
    }

    @Test
    fun `control characters are removed and whitespace collapses`() {
        assertEquals(
            tokenizer.encode("hello world", 256).toList(),
            tokenizer.encode("hello\u0000 \t\n  world", 256).toList(),
        )
    }

    @Test
    fun `CJK characters are isolated so each is its own token`() {
        // Not in the synthetic vocab, so each ideograph is UNK — but the point is that
        // they were separated rather than glued into one unknown word.
        assertEquals(listOf(2, 1, 1, 3), tokenizer.encode("你好", 256).toList())
    }

    @Test
    fun `encoding truncates to the requested length including specials`() {
        val ids = tokenizer.encode("hello world hello world hello", maxLength = 4)
        assertEquals(4, ids.size)
        assertEquals(2, ids.first())
        assertEquals(3, ids.last())
    }

    @Test
    fun `empty text encodes to just the frame`() {
        assertEquals(listOf(2, 3), tokenizer.encode("", 256).toList())
    }

    @Test
    fun `batch encoding pads to the longest sequence and masks the padding`() {
        val batch = tokenizer.encodeBatch(listOf("hello", "hello world"), 256)
        // "hello" is [CLS] hello [SEP] = 3 tokens; "hello world" is 4, so the batch is 4 wide.
        assertEquals(2, batch.batchSize)
        assertEquals(4, batch.sequenceLength)
        assertEquals(8, batch.inputIds.size)

        val mask = batch.attentionMask.toList()
        assertEquals(listOf(1L, 1L, 1L, 0L), mask.take(4))
        assertEquals(listOf(1L, 1L, 1L, 1L), mask.drop(4))

        // The pad slot carries the pad id, not a real token.
        assertEquals(0L, batch.inputIds[3])
    }

    @Test
    fun `token type ids are all zero`() {
        val batch = tokenizer.encodeBatch(listOf("hello world"), 256)
        assertTrue(batch.tokenTypeIds.all { it == 0L })
    }

    @Test
    fun `normalisation is idempotent`() {
        val once = tokenizer.normalize("Hello,  Café!\u0000")
        assertEquals(once, tokenizer.normalize(once))
    }

    @Test
    fun `a word longer than the maximum input length is UNK`() {
        val long = "a".repeat(200)
        assertEquals(listOf(2, 1, 3), tokenizer.encode(long, 256).toList())
    }
}
