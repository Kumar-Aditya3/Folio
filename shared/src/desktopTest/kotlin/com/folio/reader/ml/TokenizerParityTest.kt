package com.folio.reader.ml

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the hand-rolled [WordPieceTokenizer] agrees with the reference Rust implementation.
 *
 * This is the test that makes hand-rolling a tokenizer defensible. A tokenizer that is
 * subtly wrong does not crash — it silently degrades retrieval quality, which is the
 * hardest possible failure to attribute. So the assertion is id-for-id equality over a
 * large sample of real prose from the actual corpus, not a handful of hand-picked strings.
 *
 * Skipped (not failed) when the model fixtures are absent, so a clean checkout still runs
 * the rest of the suite.
 */
class TokenizerParityTest {

    private val corpusDir = File("testbooks").absoluteFile
        .takeIf { it.isDirectory } ?: File("..", "testbooks")

    private fun openReference(): Pair<WordPieceTokenizer, DjlTokenizerAdapter>? {
        val vocab = TestModelFixtures.miniLmVocab() ?: return null
        val tokenizerJson = TestModelFixtures.find("minilm-tokenizer.json") ?: return null
        return WordPieceTokenizer.fromVocabFile(vocab) to DjlTokenizerAdapter.fromTokenizerJson(tokenizerJson)
    }

    @Test
    fun `vocabulary size matches the BERT base vocabulary`() {
        val (mine, _) = openReference()
            ?: run { assumeTrue(false, TestModelFixtures.missingHint("MiniLM vocab/tokenizer")); return }

        // 30522 is the canonical bert-base-uncased vocabulary size; MiniLM reuses it.
        assertEquals(30522, mine.vocabSize)
    }

    @Test
    fun `special token ids agree with the reference`() {
        val (mine, reference) = openReference()
            ?: run { assumeTrue(false, TestModelFixtures.missingHint("MiniLM vocab/tokenizer")); return }

        // The reference binding exposes no vocab lookup, so compare the ids it emits.
        assertEquals(reference.encode("hello", 256).first(), mine.clsId)
        assertEquals(reference.encode("hello", 256).last(), mine.sepId)
        assertEquals(reference.encode("", 256).toList(), mine.encode("", 256).toList())
        assertEquals(101, mine.clsId)
        assertEquals(102, mine.sepId)
        assertEquals(100, mine.unkId)
        assertEquals(0, mine.padId)
    }

    @Test
    fun `hand-picked hard cases agree with the reference`() {
        val (mine, reference) = openReference()
            ?: run { assumeTrue(false, TestModelFixtures.missingHint("MiniLM vocab/tokenizer")); return }

        val cases = listOf(
            "hello world",
            "Hello, world!",
            "don't",
            "it's a test",
            "café",
            "naïve résumé",
            "co-operate",
            "well-known fact",
            "1234567890",
            "3.14159",
            "e-mail",
            "…ellipsis…",
            "\"quoted\"",
            "(parenthesised)",
            "semi;colon:",
            "under_score",
            "42%",
            "#hashtag",
            "@mention",
            "http://example.com/path?q=1",
            "THE QUICK BROWN FOX",
            "MiXeD cAsE",
            "   leading and trailing   ",
            "tabs\tand\nnewlines",
            "trailing hyphen-",
            "-leading hyphen",
            "a",
            "",
            "é",
            "ǅungla",
            "你好世界",
            "こんにちは",
            "Привет мир",
            "Ünïcödé tëxt",
            "𝔘𝔫𝔦𝔠𝔬𝔡𝔢",
            "supercalifragilisticexpialidocious",
        )
        for (case in cases) {
            assertEquals(
                reference.encode(case, 256).toList(),
                mine.encode(case, 256).toList(),
                "tokenization diverged for: ${case.take(60)}",
            )
        }
    }

    @Test
    fun `tokenization agrees over real corpus prose`() {
        val (mine, reference) = openReference()
            ?: run { assumeTrue(false, TestModelFixtures.missingHint("MiniLM vocab/tokenizer")); return }

        val sample = corpusSample(limit = 4000)
        assumeTrue(sample.isNotEmpty(), "no corpus text available to compare against")

        var checked = 0
        val mismatches = mutableListOf<String>()
        for (text in sample) {
            val expected = reference.encode(text, 256).toList()
            val actual = mine.encode(text, 256).toList()
            checked++
            if (expected != actual) {
                if (mismatches.size < 10) {
                    mismatches.add(
                        "text=${text.take(120)}\n  reference=${expected.take(24)}\n  mine     =${actual.take(24)}"
                    )
                }
            }
        }
        println("Tokenizer parity: compared $checked spans from the real corpus")
        assertTrue(
            mismatches.isEmpty(),
            "tokenizer diverged from the reference on ${mismatches.size}+ of $checked spans:\n" +
                mismatches.joinToString("\n"),
        )
    }

    /**
     * Pulls sentence-sized spans straight out of the EPUBs, bypassing the app's own
     * sanitizer so this measures the tokenizer and nothing else.
     */
    private fun corpusSample(limit: Int): List<String> {
        if (!corpusDir.isDirectory) return emptyList()
        val files = corpusDir.listFiles { f -> f.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<String>(limit)
        for (file in files) {
            if (out.size >= limit) break
            runCatching {
                ZipFile(file).use { zip ->
                    for (entry in zip.entries()) {
                        if (out.size >= limit) break
                        if (!entry.name.lowercase().matches(Regex(".*\\.(x?html?|xhtml)$"))) continue
                        val raw = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                        val text = raw
                            .replace(Regex("(?s)<(script|style).*?</\\1>"), " ")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        if (text.length < 200) continue
                        // Sentence-ish spans: enough context to exercise the pipeline.
                        text.split(Regex("(?<=[.!?])\\s+"))
                            .filter { it.length in 20..300 }
                            .take(limit - out.size)
                            .forEach { out.add(it) }
                    }
                }
            }
        }
        return out
    }
}
