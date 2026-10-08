package com.folio.reader.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CosineIndexTest {

    @Test
    fun `cosine similarity matches the textbook cases`() {
        assertEquals(1f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 1e-6f)
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-1f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
        // Scale invariance is the property the whole index relies on.
        assertEquals(1f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(7f, 0f)), 1e-6f)
    }

    @Test
    fun `cosine similarity rejects a dimension mismatch`() {
        val error = runCatching {
            cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `search ranks by similarity, best first`() {
        val index = CosineIndex(4)
        index.add("north", floatArrayOf(1f, 0f, 0f, 0f))
        index.add("east", floatArrayOf(0f, 1f, 0f, 0f))
        index.add("diagonal", floatArrayOf(0.7071f, 0.7071f, 0f, 0f))

        val hits = index.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 3)
        assertEquals(listOf("north", "diagonal", "east"), hits.map { it.chunkId })
        assertEquals(1f, hits[0].score, 1e-4f)
    }

    @Test
    fun `search respects the limit`() {
        val index = CosineIndex(2)
        repeat(20) { index.add("id-$it", floatArrayOf(it.toFloat() + 1f, 1f)) }
        assertEquals(5, index.search(floatArrayOf(1f, 1f), limit = 5).size)
        assertEquals(20, index.search(floatArrayOf(1f, 1f), limit = 100).size)
        assertTrue(index.search(floatArrayOf(1f, 1f), limit = 0).isEmpty())
    }

    @Test
    fun `search filters candidates`() {
        val index = CosineIndex(2)
        index.add("book-a:1", floatArrayOf(1f, 0f))
        index.add("book-b:1", floatArrayOf(1f, 0f))
        index.add("book-a:2", floatArrayOf(0.9f, 0.1f))

        val hits = index.search(floatArrayOf(1f, 0f), limit = 10) { it.startsWith("book-a:") }
        assertEquals(listOf("book-a:1", "book-a:2"), hits.map { it.chunkId })
    }

    @Test
    fun `adding the same id twice replaces the vector`() {
        val index = CosineIndex(2)
        index.add("a", floatArrayOf(1f, 0f))
        index.add("b", floatArrayOf(0f, 1f))
        index.add("a", floatArrayOf(0f, 1f))

        assertEquals(2, index.size)
        val hits = index.search(floatArrayOf(0f, 1f), limit = 2)
        assertEquals(setOf("a", "b"), hits.map { it.chunkId }.toSet())
        assertEquals(1f, hits[0].score, 1e-6f)
    }

    @Test
    fun `addAll is equivalent to repeated add`() {
        val a = CosineIndex(3)
        val b = CosineIndex(3)
        val entries = (0 until 50).map { "id-$it" to floatArrayOf(it.toFloat(), 1f, 2f) }
        a.addAll(entries)
        entries.forEach { (id, vector) -> b.add(id, vector) }

        assertEquals(50, a.size)
        val query = floatArrayOf(1f, 1f, 2f)
        assertEquals(
            b.search(query, limit = 10).map { it.chunkId },
            a.search(query, limit = 10).map { it.chunkId },
        )
    }

    @Test
    fun `index grows past its initial capacity`() {
        val index = CosineIndex(8)
        repeat(1000) { index.add("id-$it", FloatArray(8) { i -> (it + i).toFloat() }) }
        assertEquals(1000, index.size)
        assertEquals(10, index.search(FloatArray(8) { 1f }, limit = 10).size)
    }

    @Test
    fun `clear empties the index`() {
        val index = CosineIndex(2)
        index.add("a", floatArrayOf(1f, 0f))
        index.clear()
        assertEquals(0, index.size)
        assertTrue(index.search(floatArrayOf(1f, 0f), limit = 5).isEmpty())
    }

    @Test
    fun `wrong dimension is rejected on insert`() {
        val error = runCatching { CosineIndex(4).add("a", floatArrayOf(1f, 2f)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `l2Normalize produces a unit vector and is idempotent`() {
        val vector = floatArrayOf(3f, 4f)
        vector.l2Normalize()
        assertEquals(1f, l2Norm(vector), 1e-6f)
        assertEquals(0.6f, vector[0], 1e-6f)
        vector.l2Normalize()
        assertEquals(0.6f, vector[0], 1e-6f)
    }

    @Test
    fun `normalising a zero vector does not divide by zero`() {
        val vector = floatArrayOf(0f, 0f, 0f)
        vector.l2Normalize()
        assertTrue(vector.all { it == 0f })
    }
}
