package com.folio.reader.ml

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped on-device index. These tests pin two things the parallel scan must not break:
 * the int8 quantisation still ranks correctly, and the multi-threaded path (taken above
 * [ScanPool.PARALLEL_THRESHOLD]) returns exactly what a single serial scan of the same vectors
 * would — same ids, same order, same scores. The per-candidate dot product is identical
 * regardless of which thread computes it, so any divergence is a sharding or merge bug.
 */
class QuantizedCosineIndexTest {

    private fun randomUnit(dims: Int, rng: Random): FloatArray {
        val v = FloatArray(dims) { (rng.nextFloat() * 2f) - 1f }
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val inv = (1.0 / sqrt(sum)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    /** A deliberately serial brute-force cosine over the same int8 quantisation, for reference. */
    private fun serialReference(
        entries: List<Pair<String, FloatArray>>,
        query: FloatArray,
        limit: Int,
    ): List<String> {
        val q = query.copyOf().l2Normalize()
        return entries
            .map { (id, vec) ->
                // Match QuantizedCosineIndex's quantise-then-dequantise so scores are comparable.
                var dot = 0.0
                for (d in q.indices) {
                    val scaled = vec[d] * 127.0f
                    val clamped = when {
                        scaled > 127f -> 127
                        scaled < -127f -> -127
                        else -> kotlin.math.round(scaled).toInt()
                    }
                    dot += q[d] * clamped
                }
                id to (dot / 127.0).toFloat()
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    @Test
    fun `ranks by similarity like the float index`() {
        val index = QuantizedCosineIndex(4)
        index.add("north", floatArrayOf(1f, 0f, 0f, 0f))
        index.add("east", floatArrayOf(0f, 1f, 0f, 0f))
        index.add("diagonal", floatArrayOf(0.7071f, 0.7071f, 0f, 0f))

        val hits = index.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 3)
        assertEquals(listOf("north", "diagonal", "east"), hits.map { it.chunkId })
        assertTrue(hits[0].score > 0.99f, "an exact match should score ~1, got ${hits[0].score}")
    }

    @Test
    fun `respects the limit and filter`() {
        val index = QuantizedCosineIndex(2)
        index.add("book-a:1", floatArrayOf(1f, 0f))
        index.add("book-b:1", floatArrayOf(1f, 0f))
        index.add("book-a:2", floatArrayOf(0.9f, 0.1f))

        val hits = index.search(floatArrayOf(1f, 0f), limit = 10) { it.startsWith("book-a:") }
        assertEquals(setOf("book-a:1", "book-a:2"), hits.map { it.chunkId }.toSet())
    }

    @Test
    fun `parallel scan above the threshold matches a serial reference`() {
        // Comfortably above ScanPool.PARALLEL_THRESHOLD so the multi-threaded path runs.
        val dims = 64
        val n = ScanPool.PARALLEL_THRESHOLD * 3 + 137
        val rng = Random(20260920)
        val entries = (0 until n).map { "chunk-$it" to randomUnit(dims, rng) }

        val index = QuantizedCosineIndex(dims, initialCapacity = n)
        index.addAll(entries)
        assertTrue(index.size >= ScanPool.PARALLEL_THRESHOLD, "test must exercise the parallel path")

        val query = randomUnit(dims, rng)
        val limit = 20
        val got = index.search(query, limit)
        val expected = serialReference(entries, query, limit)

        assertEquals(expected, got.map { it.chunkId },
            "parallel scan must return the same ranking as a serial scan")
        // Scores must be descending and in [-1, 1].
        for (i in 1 until got.size) {
            assertTrue(got[i - 1].score >= got[i].score, "hits must be ordered best-first")
        }
        assertTrue(got.all { it.score <= 1.0001f && it.score >= -1.0001f })
    }

    @Test
    fun `parallel and serial paths agree on the same vectors`() {
        // Same 100 real vectors searched two ways: once padded past the threshold with filler the
        // filter excludes (parallel path), once as a small index (serial path). Same candidates in,
        // same ranking out.
        val dims = 32
        val rng = Random(7)
        val real = (0 until 100).map { "real-$it" to randomUnit(dims, rng) }

        val small = QuantizedCosineIndex(dims, initialCapacity = real.size)
        small.addAll(real)

        val filler = (0 until ScanPool.PARALLEL_THRESHOLD * 2).map { "filler-$it" to randomUnit(dims, rng) }
        val big = QuantizedCosineIndex(dims, initialCapacity = real.size + filler.size)
        big.addAll(real + filler)

        val query = randomUnit(dims, rng)
        val fromSmall = small.search(query, limit = 15).map { it.chunkId }
        val fromBig = big.search(query, limit = 15) { it.startsWith("real-") }.map { it.chunkId }
        assertEquals(fromSmall, fromBig)
    }
}
