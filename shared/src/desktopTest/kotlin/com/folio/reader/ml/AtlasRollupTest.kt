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
        val dims = 12
        val rng = Random(11)
        // Books A and B are about the same subject (axis 0/1); book C is elsewhere (axis 6/7).
        val entries = ArrayList<Pair<ChunkMeta, FloatArray>>()
        repeat(30) { i -> entries.add(meta("a$i", "A") to unit(dims, 0 to (2f + rng.nextFloat() * 0.2f), 1 to rng.nextFloat() * 0.1f)) }
        repeat(30) { i -> entries.add(meta("b$i", "B") to unit(dims, 0 to (2f + rng.nextFloat() * 0.2f), 1 to rng.nextFloat() * 0.1f)) }
        repeat(30) { i -> entries.add(meta("c$i", "C") to unit(dims, 6 to (2f + rng.nextFloat() * 0.2f), 7 to rng.nextFloat() * 0.1f)) }
        // Two more distinct-topic books so the whitening PCs and the hub background are not
        // dominated by A/B alone.
        repeat(30) { i -> entries.add(meta("d$i", "D") to unit(dims, 3 to (2f + rng.nextFloat() * 0.2f), 4 to rng.nextFloat() * 0.1f)) }
        repeat(30) { i -> entries.add(meta("e$i", "E") to unit(dims, 9 to (2f + rng.nextFloat() * 0.2f), 10 to rng.nextFloat() * 0.1f)) }

        val whitened = AtlasRollup.whiten(entries.map { it.second }.toTypedArray(), dims, AtlasRollup.WHITEN_COMPONENTS)
        val edges = AtlasRollup.adjacency(entries, whitened)

        val ab = edges.firstOrNull { setOf(it.bookIdA, it.bookIdB) == setOf("A", "B") }
        assertTrue(ab != null && ab.weight >= AtlasRollup.MIN_EDGE_WEIGHT, "A and B share a subject and must border: $edges")
        val cWeight = edges.filter { it.bookIdA == "C" || it.bookIdB == "C" }.sumOf { it.weight }
        assertTrue(ab.weight > cWeight, "the A–B border must outweigh C's total (ab=${ab.weight}, c=$cWeight)")

        // Weight is now a scaled similarity score, not a raw count — the two runs must still agree.
        val again = AtlasRollup.adjacency(entries, whitened)
        assertEquals(
            edges.map { Triple(it.bookIdA, it.bookIdB, it.weight) },
            again.map { Triple(it.bookIdA, it.bookIdB, it.weight) },
            "adjacency must be deterministic regardless of shard partition",
        )
    }

    @Test
    fun `adjacency suppresses a generic hub link but keeps a real shared topic`() {
        val dims = 16
        val rng = Random(29)
        val entries = ArrayList<Pair<ChunkMeta, FloatArray>>()
        // A and B are genuinely about the same subject (axes 0/1) — a strong, real overlap.
        repeat(24) { i -> entries.add(meta("a$i", "A") to unit(dims, 0 to (2f + rng.nextFloat() * 0.15f), 1 to rng.nextFloat() * 0.1f)) }
        repeat(24) { i -> entries.add(meta("b$i", "B") to unit(dims, 0 to (2f + rng.nextFloat() * 0.15f), 1 to rng.nextFloat() * 0.1f)) }
        // C is about something else entirely (axes 8/9).
        repeat(24) { i -> entries.add(meta("c$i", "C") to unit(dims, 8 to (2f + rng.nextFloat() * 0.15f), 9 to rng.nextFloat() * 0.1f)) }
        // Two more distinct-topic books to diversify the whitening PCs and the hub background.
        repeat(24) { i -> entries.add(meta("d$i", "D") to unit(dims, 4 to (2f + rng.nextFloat() * 0.15f), 5 to rng.nextFloat() * 0.1f)) }
        repeat(24) { i -> entries.add(meta("e$i", "E") to unit(dims, 12 to (2f + rng.nextFloat() * 0.15f), 13 to rng.nextFloat() * 0.1f)) }
        // A "generic" direction (axis 15) that A, B and C all carry a little of — the stock passage
        // that used to fuse unrelated books. A–C share *only* this, so their edge must stay weak.
        repeat(5) { i -> entries.add(meta("ag$i", "A") to unit(dims, 15 to 1.4f)) }
        repeat(5) { i -> entries.add(meta("bg$i", "B") to unit(dims, 15 to 1.4f)) }
        repeat(5) { i -> entries.add(meta("cg$i", "C") to unit(dims, 15 to 1.4f)) }

        val whitened = AtlasRollup.whiten(entries.map { it.second }.toTypedArray(), dims, AtlasRollup.WHITEN_COMPONENTS)
        val edges = AtlasRollup.adjacency(entries, whitened)

        val ab = edges.firstOrNull { setOf(it.bookIdA, it.bookIdB) == setOf("A", "B") }?.weight ?: 0
        val ac = edges.firstOrNull { setOf(it.bookIdA, it.bookIdB) == setOf("A", "C") }?.weight ?: 0
        assertTrue(ab > 0, "A and B share a genuine topic and must border: $edges")
        assertTrue(ab > ac, "the genuine A–B topic must outweigh the generic A–C overlap (ab=$ab, ac=$ac)")
    }

    @Test
    fun `layout is deterministic and separates two clusters of books`() {
        val bookIds = listOf("a", "b", "c", "x", "y", "z")
        // Seeds are irrelevant to the invariant (determinism), but supply spread ones anyway.
        val seeds = List(bookIds.size) { i -> floatArrayOf((i - 3) * 0.1f, (i % 2) * 0.1f) }
        // Two tight triangles, no edge between the groups.
        val edges = listOf(
            AtlasEdge("a", "b", 9000),
            AtlasEdge("b", "c", 9000),
            AtlasEdge("a", "c", 9000),
            AtlasEdge("x", "y", 9000),
            AtlasEdge("y", "z", 9000),
            AtlasEdge("x", "z", 9000),
        )
        val first = AtlasRollup.layoutBooks(bookIds, seeds, edges)
        val second = AtlasRollup.layoutBooks(bookIds, seeds, edges)
        first.indices.forEach { i ->
            assertEquals(first[i][0], second[i][0], 1e-5f, "layout x must be reproducible")
            assertEquals(first[i][1], second[i][1], 1e-5f, "layout y must be reproducible")
        }
        // Coordinates are normalised into [-1, 1].
        first.forEach { xy -> assertTrue(xy[0] in -1.0001f..1.0001f && xy[1] in -1.0001f..1.0001f) }

        // The two connected triangles should end up closer within-group than across it.
        fun dist(i: Int, j: Int) = kotlin.math.hypot((first[i][0] - first[j][0]).toDouble(), (first[i][1] - first[j][1]).toDouble())
        val within = dist(0, 1) // a-b
        val across = dist(0, 3) // a-x
        assertTrue(across > within, "linked books must sit closer than unlinked ones (within=$within, across=$across)")
    }

    @Test
    fun `same-genre attraction pulls a genre's books closer while keeping distinct genres apart`() {
        // Five books on a pentagon with an *asymmetric* genre split (four G1, one G2), so the genre
        // force is not cancelled by symmetry the way an even split on a regular polygon would be.
        // The four G1 books, spread around the ring at the seed, should be drawn into one clump.
        val bookIds = listOf("a0", "a1", "a2", "a3", "b0")
        val genres = listOf("G1", "G1", "G1", "G1", "G2")
        val seeds = List(5) { i ->
            val ang = 2.0 * Math.PI * i / 5.0
            floatArrayOf((0.6 * kotlin.math.cos(ang)).toFloat(), (0.6 * kotlin.math.sin(ang)).toFloat())
        }
        val edges = emptyList<AtlasEdge>() // isolate the genre force from similarity edges

        val plain = AtlasRollup.layoutBooks(bookIds, seeds, edges)
        val grouped = AtlasRollup.layoutBooks(bookIds, seeds, edges, genres = genres)
        val groupedAgain = AtlasRollup.layoutBooks(bookIds, seeds, edges, genres = genres)

        // Deterministic — a fixed weight, no RNG.
        grouped.indices.forEach { i ->
            assertEquals(grouped[i][0], groupedAgain[i][0], 1e-5f, "genre layout x must be reproducible")
            assertEquals(grouped[i][1], groupedAgain[i][1], 1e-5f, "genre layout y must be reproducible")
        }

        fun d(l: List<FloatArray>, i: Int, j: Int) =
            kotlin.math.hypot((l[i][0] - l[j][0]).toDouble(), (l[i][1] - l[j][1]).toDouble())
        // The genre force must actually change the shape of the layout.
        val moved = grouped.indices.any { i ->
            kotlin.math.abs(grouped[i][0] - plain[i][0]) > 1e-4f ||
                kotlin.math.abs(grouped[i][1] - plain[i][1]) > 1e-4f
        }
        assertTrue(moved, "the same-genre attraction must change the layout")

        // The four same-genre books end up closer to each other (mean pairwise) than to the lone
        // book of the other genre — the attraction pulled them into a cloud.
        val g1 = listOf(0, 1, 2, 3)
        var within = 0.0; var withinN = 0
        for (i in g1.indices) for (j in i + 1 until g1.size) { within += d(grouped, g1[i], g1[j]); withinN++ }
        val meanWithin = within / withinN
        val meanCross = g1.map { d(grouped, it, 4) }.average()
        assertTrue(
            meanWithin < meanCross,
            "same-genre books must sit closer to each other than to the other genre (within=$meanWithin, cross=$meanCross)",
        )
        // …but not collapsed onto a single point.
        for (i in bookIds.indices) for (j in i + 1 until bookIds.size) {
            assertTrue(d(grouped, i, j) > 1e-3, "books must not collapse onto one point")
        }
    }

    @Test
    fun `community detection is deterministic and splits two disjoint groups`() {
        val bookIds = listOf("a", "b", "c", "x", "y", "z")
        val edges = listOf(
            AtlasEdge("a", "b", 9000),
            AtlasEdge("b", "c", 9000),
            AtlasEdge("a", "c", 9000),
            AtlasEdge("x", "y", 9000),
            AtlasEdge("y", "z", 9000),
            AtlasEdge("x", "z", 9000),
        )
        val first = AtlasRollup.detectCommunities(bookIds, edges)
        val second = AtlasRollup.detectCommunities(bookIds, edges)
        assertEquals(first, second, "communities must be reproducible")
        assertEquals(2, first.values.toSet().size, "two disjoint triangles are two communities: $first")
        assertEquals(first["a"], first["b"], "a and b are in one triangle")
        assertEquals(first["a"], first["c"], "a and c are in one triangle")
        assertTrue(first["a"] != first["x"], "the two triangles must not share a community")
    }

    @Test
    fun `compute places clusters near their book's macro position`() {
        val dims = 10
        val rng = Random(5)
        val entries = ArrayList<Pair<ChunkMeta, FloatArray>>()
        // Book A: two topics. Book B: one topic. Plus more books so a layout actually forms.
        repeat(20) { i -> entries.add(meta("a$i", "A") to unit(dims, 0 to (2f + rng.nextFloat() * 0.1f))) }
        repeat(20) { i -> entries.add(meta("a2$i", "A") to unit(dims, 3 to (2f + rng.nextFloat() * 0.1f))) }
        repeat(20) { i -> entries.add(meta("b$i", "B") to unit(dims, 6 to (2f + rng.nextFloat() * 0.1f))) }
        repeat(20) { i -> entries.add(meta("c$i", "C") to unit(dims, 8 to (2f + rng.nextFloat() * 0.1f))) }

        val model = AtlasRollup.compute(entries)
        model.books.forEach { b ->
            b.clusters.forEach { c ->
                val dx = kotlin.math.abs(c.x - b.x)
                val dy = kotlin.math.abs(c.y - b.y)
                assertTrue(
                    dx <= 0.2f && dy <= 0.2f,
                    "cluster offsets must stay local to the book's macro position (dx=$dx, dy=$dy)",
                )
            }
            assertTrue(b.communityId >= 0, "every book must carry a community id")
        }
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
