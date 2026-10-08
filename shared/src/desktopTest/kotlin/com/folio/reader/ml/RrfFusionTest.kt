package com.folio.reader.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RrfFusionTest {

    @Test
    fun `empty input fuses to nothing`() {
        assertTrue(RrfFusion.fuse(emptyList()).isEmpty())
        assertTrue(RrfFusion.fuse(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `a document both rankers agree on wins`() {
        val bm25 = listOf("a", "b", "c")
        val semantic = listOf("c", "a", "b")
        val fused = RrfFusion.fusePair(bm25, semantic)
        // "a" is 1st+2nd, "c" is 3rd+1st, "b" is 2nd+3rd. The property under test is that the two
        // ids *both* rankers returned ("a", "c") beat the one only one of them did ("b") — that is
        // the whole reason to fuse rather than to pick a winner.
        assertEquals(setOf("a", "b", "c"), fused.map { it.chunkId }.toSet())
        assertEquals(setOf("a", "c"), fused.take(2).map { it.chunkId }.toSet())
        assertEquals("b", fused.last().chunkId)
        // Between "a" and "c" the order is set by [RrfFusion.DEFAULT_LEXICAL_WEIGHT], and this is
        // the smallest case that shows it. Under equal weights the two are near-symmetric and "a"
        // wins on the id tie-break; at a lexical weight of 0.5 the semantic ranker's *own* rank-1
        // answer ("c") takes the top spot, because the semantic list now carries twice the weight.
        // The margin is genuinely thin — 0.0243299 against 0.0243258 — so this asserts the
        // direction rather than a magnitude, and the equal-weight case is asserted alongside it so
        // the difference is attributable to the weight and not to the tie-break.
        assertEquals(
            listOf("a", "c"),
            RrfFusion.fusePair(bm25, semantic, lexicalWeight = 1.0).take(2).map { it.chunkId },
        )
        assertEquals(
            listOf("c", "a"),
            fused.take(2).map { it.chunkId },
        )
    }

    @Test
    fun `a document found by only one ranker loses to one found by both`() {
        val bm25 = listOf("only-bm25", "shared")
        val semantic = listOf("shared", "only-semantic")
        val fused = RrfFusion.fusePair(bm25, semantic)
        assertEquals("shared", fused.first().chunkId)
        // All three ids survive; "shared" just outscores the two single-list candidates.
        assertEquals(3, fused.size)
        // The two singletons swap relative to equal-weight fusion, and that is the point of
        // [RrfFusion.DEFAULT_LEXICAL_WEIGHT]: "only-semantic" is the semantic ranker's second-best
        // answer while "only-bm25" is the lexical ranker's best, and at a lexical weight of 0.5 the
        // semantic one wins. Under the old equal weights they were separated only by rank.
        assertEquals(
            listOf("shared", "only-semantic", "only-bm25"),
            fused.map { it.chunkId },
        )
        // With equal weights the lexical singleton regains the edge, which pins the weight as the
        // thing doing the work rather than some incidental tie-break.
        assertEquals(
            listOf("shared", "only-bm25", "only-semantic"),
            RrfFusion.fusePair(bm25, semantic, lexicalWeight = 1.0).map { it.chunkId },
        )
    }

    @Test
    fun `a lighter lexical weight lets a semantic hit outrank a bare lexical one`() {
        // The measured defect this weight exists to fix: for a vague query BM25 still returns
        // *something* (a long natural-language query shares a common noun with hundreds of
        // chapters), so its rank-1 entry is a weak match promoted to the same footing as a strong
        // one. Here "lexical-only" is BM25's best and the embedder's twentieth; "semantic-best" is
        // BM25's thirtieth and the embedder's first.
        val bm25 = listOf("lexical-only") + (2..29).map { "filler-$it" } + listOf("semantic-best")
        val semantic = listOf("semantic-best") + (2..19).map { "other-$it" } + listOf("lexical-only")
        // Equal weights: the two are symmetric, so the lexical one wins on the tie-break by id.
        assertEquals(
            "lexical-only",
            RrfFusion.fusePair(bm25, semantic, lexicalWeight = 1.0).first().chunkId,
        )
        // Weighted: the semantic ranker's own best answer takes the top spot, which is the
        // behaviour the 29%-vs-43% paraphrase gap calls for.
        assertEquals(
            "semantic-best",
            RrfFusion.fusePair(bm25, semantic).first().chunkId,
        )
    }

    @Test
    fun `scores follow the reciprocal rank formula`() {
        // Equal weights, so the two contributions are identical and the expected score is exactly
        // twice one of them. 1/(60+0+1) twice. Scores are Float, so compare with a Float-sized
        // tolerance.
        val fused = RrfFusion.fusePair(listOf("a"), listOf("a"), k = 60, lexicalWeight = 1.0)
        assertEquals(2.0 / 61.0, fused.first().score.toDouble(), 1e-6)
    }

    @Test
    fun `the default lexical weight is below one`() {
        // Guards the calibration itself. The default is a *measurement* — see
        // [RrfFusion.DEFAULT_LEXICAL_WEIGHT] for the Phase 0b numbers — and reverting it to 1.0
        // would silently restore the equal-weight fusion that lost a third of the semantic
        // ranker's paraphrase recall.
        assertTrue(
            RrfFusion.DEFAULT_LEXICAL_WEIGHT < 1.0,
            "the lexical list must be down-weighted, got ${RrfFusion.DEFAULT_LEXICAL_WEIGHT}",
        )
        assertTrue(
            RrfFusion.DEFAULT_LEXICAL_WEIGHT > 0.0,
            "a zero weight would discard lexical evidence entirely, got " +
                "${RrfFusion.DEFAULT_LEXICAL_WEIGHT}",
        )
    }

    @Test
    fun `a zero weight disables a list without removing it`() {
        // How a caller turns one ranker off. It must not throw, and the remaining list must be
        // ranked normally — this is the mechanism the benchmark uses to compare "semantic only".
        val fused = RrfFusion.fusePair(listOf("lexical"), listOf("semantic"), lexicalWeight = 0.0)
        assertEquals(listOf("semantic"), fused.map { it.chunkId })
    }

    @Test
    fun `k damps the influence of the top rank`() {
        // A single list, so the two ranks are not symmetric and the spread is observable.
        val low = RrfFusion.fuse(listOf(listOf("x", "y")), k = 1)
        val high = RrfFusion.fuse(listOf(listOf("x", "y")), k = 1000)
        // Small k: rank 1 is worth twice rank 2. Large k: the ranks nearly tie.
        val lowSpread = low.first().score - low.last().score
        val highSpread = high.first().score - high.last().score
        assertTrue(highSpread < lowSpread, "larger k must compress the score range")
    }

    @Test
    fun `an id repeated inside one ranking is counted once`() {
        val fused = RrfFusion.fuse(listOf(listOf("a", "a", "a"), listOf("b")))
        val a = fused.first { it.chunkId == "a" }
        assertEquals(1.0 / 61.0, a.score.toDouble(), 1e-6)
    }

    @Test
    fun `limit truncates the fused list`() {
        val bm25 = (0 until 100).map { "id-$it" }
        val semantic = (0 until 100).reversed().map { "id-$it" }
        assertEquals(10, RrfFusion.fusePair(bm25, semantic, limit = 10).size)
    }

    @Test
    fun `three rankings combine`() {
        val fused = RrfFusion.fuse(
            listOf(
                listOf("a", "b"),
                listOf("a", "c"),
                listOf("a", "d"),
            )
        )
        assertEquals("a", fused.first().chunkId)
    }

    @Test
    fun `k must be positive`() {
        val error = runCatching { RrfFusion.fuse(listOf(listOf("a")), k = 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `output ordering is deterministic for equal scores`() {
        val first = RrfFusion.fusePair(listOf("b"), listOf("a"))
        val second = RrfFusion.fusePair(listOf("b"), listOf("a"))
        assertEquals(first.map { it.chunkId }, second.map { it.chunkId })
    }
}
