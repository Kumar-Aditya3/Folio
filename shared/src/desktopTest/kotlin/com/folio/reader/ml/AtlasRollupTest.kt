package com.folio.reader.ml

import com.folio.reader.database.ChunkMeta
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Atlas roll-up is the maths the feature stands on, and it is pure — no ONNX, no database,
 * no Compose — so it is checked here directly, exactly as the plan's validation section asks:
 * whitening removes the common component, k-means and the sign-fixed PCA are deterministic, and
 * adjacency reflects genuine cross-cloud overlap rather than centroid distance.
 */
class AtlasRollupTest {

    private fun meta(id: String, bookId: String) =
        ChunkMeta(id = id, bookId = bookId, chapterId = "c", spineIndex = 0, charStart = 0, charEnd = 10)

    private fun unit(dims: Int, vararg set: Pair<Int, Float>): FloatArray {
        val v = FloatArray(dims)
        for ((i, x) in set) v[i] = x
        return v.l2Normalize()
    }

    @Test
    fun `cluster count follows sqrt over two, clamped`() {
        assertEquals(1, AtlasRollup.clusterCountFor(1))
        assertEquals(1, AtlasRollup.clusterCountFor(3))
        // sqrt(16)/2 = 2
        assertEquals(2, AtlasRollup.clusterCountFor(16))
        // sqrt(100)/2 = 5
        assertEquals(5, AtlasRollup.clusterCountFor(100))
        // sqrt(1000)/2 ≈ 15.8 -> clamped to 8
        assertEquals(8, AtlasRollup.clusterCountFor(1000))
    }

    @Test
    fun `whitening removes the shared common component`() {
        val dims = 12
        // Fully deterministic. The common direction is axis 0 with a large, varying weight (so it is
        // genuinely the top-variance direction and becomes the first principal component); the
        // per-sample topical signal sits on axes 1..4, orthogonal to it. No RNG, so the assertion
        // below cannot wobble between runs — the whole point of the feature is a reproducible layout.
        val common = FloatArray(dims).also { it[0] = 1f } // unit vector on axis 0
        val rows = Array(60) { i ->
            FloatArray(dims).also {
                it[0] = 3f + (i % 5)          // dominant, variance-carrying common component
                it[1 + (i % 4)] = 1f          // orthogonal topical signal
            }
        }

        fun varianceAlong(rs: Array<FloatArray>, axis: FloatArray): Double {
            val dots = rs.map { r -> r.indices.sumOf { (r[it] * axis[it]).toDouble() } }
            val mean = dots.average()
            return dots.sumOf { (it - mean) * (it - mean) } / dots.size
        }

        val whitened = AtlasRollup.whiten(rows, dims, AtlasRollup.WHITEN_COMPONENTS)
        val after = varianceAlong(whitened, common)

        // After projecting out the top components the shared axis carries essentially no variance —
        // an absolute floor, robust to the exact input scale.
        assertTrue(after < 0.01, "whitening should collapse variance along the shared axis (after=$after)")
        // And it re-normalises: every whitened row is unit length.
        whitened.forEach { r ->
            val norm = kotlin.math.sqrt(r.sumOf { (it * it).toDouble() })
            assertTrue(abs(norm - 1.0) < 1e-3, "whitened rows must be unit length, got $norm")
        }
    }

    @Test
    fun `kmeans is deterministic and separates two groups`() {
        val dims = 8
        val rng = Random(7)
        val a = (0 until 20).map { unit(dims, 0 to (2f + rng.nextFloat() * 0.1f), 1 to rng.nextFloat() * 0.05f) }
        val b = (0 until 20).map { unit(dims, 4 to (2f + rng.nextFloat() * 0.1f), 5 to rng.nextFloat() * 0.05f) }
        val vecs = (a + b).toTypedArray()

        val first = AtlasRollup.kMeans(vecs, 2, dims)
        val second = AtlasRollup.kMeans(vecs, 2, dims)
        assertTrue(first.toList() == second.toList(), "identical input must yield identical assignment")

        // The first 20 (group A) all land in one cluster, the last 20 in the other.
        val clusterA = first.take(20).toSet()
        val clusterB = first.drop(20).toSet()
        assertEquals(1, clusterA.size, "group A must be one cluster")
        assertEquals(1, clusterB.size, "group B must be one cluster")
        assertTrue(clusterA.first() != clusterB.first(), "the two groups must be different clusters")
    }

    @Test
    fun `projection is deterministic and sign-stable`() {
        val dims = 6
        val points = listOf(
            unit(dims, 0 to 1f), unit(dims, 0 to 0.9f, 1 to 0.1f),
            unit(dims, 2 to 1f), unit(dims, 2 to 0.9f, 3 to 0.1f),
        )
        val first = AtlasRollup.projectTo2D(points, dims)
        val second = AtlasRollup.projectTo2D(points, dims)
        first.indices.forEach { i ->
            assertEquals(first[i][0], second[i][0], 1e-6f)
            assertEquals(first[i][1], second[i][1], 1e-6f)
        }
        // Coordinates are scaled into [-1, 1].
        first.forEach { xy ->
            assertTrue(xy[0] in -1.0001f..1.0001f && xy[1] in -1.0001f..1.0001f)
        }
    }

    @Test
    fun `adjacency links books that share a topic more than a stranger`() {
        val dims = 10
        val rng = Random(11)
        // Books A and B are about the same subject (axis 0/1); book C is elsewhere (axis 6/7).
        val entries = ArrayList<Pair<ChunkMeta, FloatArray>>()
        repeat(30) { i -> entries.add(meta("a$i", "A") to unit(dims, 0 to (2f + rng.nextFloat() * 0.2f), 1 to rng.nextFloat() * 0.1f)) }
        repeat(30) { i -> entries.add(meta("b$i", "B") to unit(dims, 0 to (2f + rng.nextFloat() * 0.2f), 1 to rng.nextFloat() * 0.1f)) }
        repeat(30) { i -> entries.add(meta("c$i", "C") to unit(dims, 6 to (2f + rng.nextFloat() * 0.2f), 7 to rng.nextFloat() * 0.1f)) }

        val whitened = AtlasRollup.whiten(entries.map { it.second }.toTypedArray(), dims, AtlasRollup.WHITEN_COMPONENTS)
        val edges = AtlasRollup.adjacency(entries, whitened)

        val ab = edges.firstOrNull { setOf(it.bookIdA, it.bookIdB) == setOf("A", "B") }
        assertTrue(ab != null && ab.weight >= AtlasRollup.MIN_EDGE_WEIGHT, "A and B share a subject and must border: $edges")
        val cWeight = edges.filter { it.bookIdA == "C" || it.bookIdB == "C" }.sumOf { it.weight }
        assertTrue(ab.weight > cWeight, "the A–B border must outweigh C's total (ab=${ab.weight}, c=$cWeight)")
    }

    @Test
    fun `compute produces per-book clusters and stable output`() {
        val dims = 10
        val rng = Random(3)
        val entries = ArrayList<Pair<ChunkMeta, FloatArray>>()
        // Book A: two topics. Book B: one topic.
        repeat(20) { i -> entries.add(meta("a$i", "A") to unit(dims, 0 to (2f + rng.nextFloat() * 0.1f))) }
        repeat(20) { i -> entries.add(meta("a2$i", "A") to unit(dims, 3 to (2f + rng.nextFloat() * 0.1f))) }
        repeat(20) { i -> entries.add(meta("b$i", "B") to unit(dims, 6 to (2f + rng.nextFloat() * 0.1f))) }

        val first = AtlasRollup.compute(entries)
        val second = AtlasRollup.compute(entries)

        assertEquals(2, first.books.size)
        first.books.forEach { b ->
            assertTrue(b.clusters.isNotEmpty(), "every book must have at least one region")
            val totalMass = b.clusters.sumOf { it.mass.toDouble() }
            assertTrue(abs(totalMass - 1.0) < 0.01, "cluster masses must sum to 1 for ${b.bookId}, got $totalMass")
            b.clusters.forEach { assertTrue(it.exemplarChunkId.isNotBlank()) }
        }
        // Deterministic: same input, same layout.
        first.books.zip(second.books).forEach { (f, s) ->
            assertEquals(f.bookId, s.bookId)
            assertEquals(f.clusters.size, s.clusters.size)
        }
    }

    @Test
    fun `empty input yields an empty map`() {
        val result = AtlasRollup.compute(emptyList())
        assertTrue(result.books.isEmpty() && result.edges.isEmpty())
    }
}
