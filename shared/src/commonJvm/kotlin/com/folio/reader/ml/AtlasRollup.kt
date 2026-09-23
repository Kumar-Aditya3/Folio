package com.folio.reader.ml

import com.folio.reader.database.ChunkMeta
import kotlinx.serialization.Serializable
import java.util.concurrent.Callable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The pure roll-up that turns a library's chunk vectors into an Atlas layout.
 *
 * Everything here is deterministic and free of Compose, ONNX, the database and the resident
 * index — it takes `List<Pair<ChunkMeta, FloatArray>>` in and returns an [AtlasGeometry] out,
 * so the whole pipeline is unit-testable on the desktop target without a device (Rule: the
 * hard part is the maths, and the maths must be checkable). The repository ([SemanticDiscoveryRepository])
 * owns loading the vectors and decorating the geometry with per-book display data (title,
 * cover, read fraction); this owns only the shape of the map.
 *
 * ### Why not a single mean-of-chunks per book
 *
 * A book's mean vector is the exact failure mode [ZeroShotTagger] documents: mean-pooling a
 * whole novel collapses it to its dominant subject and every secondary theme vanishes. And a
 * *centroid* map would place two unrelated books close merely because both sit near the centre
 * of the model's anisotropic cone. So this does two things the naive version does not:
 *
 * 1. **Whitens** every vector first — subtracts the global mean and projects out the top few
 *    principal components (the "all-but-the-top" transform). Those top PCs are the common
 *    component every English passage shares; removing them is what turns "all prose is 0.1–0.2
 *    similar" into a space where topical distance is legible. This is non-negotiable and runs
 *    before anything else.
 * 2. **Clusters each book** into several topics with k-means, so a book that is 60% one thing
 *    and 30% another shows *two* regions, not one averaged blob.
 *
 * Centroids are then projected to 2D with a sign-fixed PCA so the same library lays out the
 * same way every open, and books are joined by edges built from cross-book nearest-neighbour
 * overlap (how often one book's passages land nearest a passage in another) rather than by
 * centroid distance, which would only restate the 2D layout.
 */
object AtlasRollup {

    /**
     * Books whose sampled chunks are used to build adjacency. Bounds the cross-book
     * nearest-neighbour pass: it is O(sampled × candidates), and on a large library the full
     * cross product is billions of dot products. Sampling per book keeps the pass to a second
     * or two while still reflecting which clouds actually overlap.
     */
    const val MAX_ADJACENCY_SAMPLES_PER_BOOK = 24

    /** Total candidate pool the adjacency NN search compares against, strided across the library. */
    const val MAX_ADJACENCY_CANDIDATES = 4_096

    /** Only edges at or above this overlap count are kept — one stray neighbour is not a border. */
    const val MIN_EDGE_WEIGHT = 2

    /** How many top principal components the whitening projects out. */
    const val WHITEN_COMPONENTS = 3

    private const val KMEANS_MAX_ITERS = 25
    private const val POWER_ITERS = 40

    /**
     * Runs the whole roll-up.
     *
     * @param entries every chunk's identity and its raw (already L2-normalised) embedding.
     * @return the structural map: books with their topic clusters in 2D, plus book-to-book edges.
     *   Empty in, empty out.
     */
    fun compute(
        entries: List<Pair<ChunkMeta, FloatArray>>,
        /**
         * Polled at loop boundaries; return true to abort. The roll-up has no suspension points,
         * so this is the only way a cancelled coroutine can stop the CPU work instead of running
         * it to completion. Aborting throws [RollupCancelledException].
         */
        shouldCancel: () -> Boolean = { false },
    ): AtlasGeometry {
        if (entries.isEmpty()) return AtlasGeometry(emptyList(), emptyList())
        val dims = entries.first().second.size
        if (dims == 0) return AtlasGeometry(emptyList(), emptyList())

        // 1. Whiten. One pass over the whole library so every book sits in the same whitened space.
        val raw = Array(entries.size) { entries[it].second }
        val whitened = whiten(raw, dims, WHITEN_COMPONENTS, shouldCancel)

        // Group whitened rows by book, preserving each row's original index so we can name the
        // exemplar chunk and reach its vector for adjacency later.
        val byBook = LinkedHashMap<String, MutableList<Int>>()
        entries.forEachIndexed { i, (meta, _) -> byBook.getOrPut(meta.bookId) { mutableListOf() }.add(i) }

        // 2. Per-book k-means on the whitened chunks.
        val books = ArrayList<BookClusters>(byBook.size)
        for ((bookId, rowIdx) in byBook) {
            if (shouldCancel()) throw RollupCancelledException()
            val vecs = Array(rowIdx.size) { whitened[rowIdx[it]] }
            val k = clusterCountFor(vecs.size)
            val assignment = kMeans(vecs, k, dims)
            val clusters = summariseClusters(assignment, vecs, rowIdx, entries, dims)
            if (clusters.isNotEmpty()) books.add(BookClusters(bookId, clusters))
        }
        if (books.isEmpty()) return AtlasGeometry(emptyList(), emptyList())

        // 3. Project every cluster centroid to 2D with a deterministic sign-fixed PCA, so an
        //    unchanged library lays out identically each open.
        val centroids = ArrayList<FloatArray>()
        books.forEach { b -> b.clusters.forEach { centroids.add(it.centroidWhitened) } }
        val coords = projectTo2D(centroids, dims)
        var c = 0
        val books2D = books.map { b ->
            AtlasBookGeometry(
                bookId = b.bookId,
                clusters = b.clusters.map { cl ->
                    val xy = coords[c++]
                    AtlasClusterGeometry(
                        x = xy[0],
                        y = xy[1],
                        mass = cl.mass,
                        tightness = cl.tightness,
                        exemplarChunkId = cl.exemplarChunkId,
                        memberChunkIds = cl.memberChunkIds,
                        rawCentroid = cl.rawCentroid,
                    )
                },
            )
        }

        // 4. Book-to-book edges from cross-cloud nearest-neighbour overlap.
        val edges = adjacency(entries, whitened, shouldCancel)
        return AtlasGeometry(books2D, edges)
    }

    /**
     * `k = clamp(round(sqrt(chunks)/2), 2, 8)`, except a book too small to split stays at one
     * tight cluster. A single-topic book collapses to one region regardless; the clamp keeps a
     * sprawling anthology from fragmenting into unreadable confetti.
     */
    fun clusterCountFor(chunks: Int): Int {
        if (chunks <= 3) return 1
        val k = round(sqrt(chunks.toDouble()) / 2.0).toInt()
        return k.coerceIn(2, 8).coerceAtMost(chunks)
    }

    // ---- Whitening -----------------------------------------------------------------------

    /**
     * The "all-but-the-top" transform: subtract the global mean, project out the top
     * [components] principal directions, then re-normalise. Returns fresh arrays; inputs are
     * untouched.
     */
    fun whiten(
        rows: Array<FloatArray>,
        dims: Int,
        components: Int,
        shouldCancel: () -> Boolean = { false },
    ): Array<FloatArray> {
        val n = rows.size
        val mean = DoubleArray(dims)
        for (r in rows) for (d in 0 until dims) mean[d] += r[d]
        for (d in 0 until dims) mean[d] /= n

        // Centre into a working double matrix.
        val centred = Array(n) { i -> DoubleArray(dims) { d -> rows[i][d] - mean[d] } }

        // Top principal components by power iteration + deflation. No KMP linear-algebra lib
        // exists in this project, and the matrices are tall-and-thin (n × 384), so iterating the
        // covariance action A^T A v without ever materialising the 384×384 covariance is both
        // simplest and cheapest.
        val pcs = ArrayList<DoubleArray>(components)
        val comp = min(components, dims)
        for (p in 0 until comp) {
            if (shouldCancel()) throw RollupCancelledException()
            val v = powerIteration(centred, dims, pcs)
            if (v == null) break
            pcs.add(v)
        }

        // Project each centred row off the accumulated PCs, then L2-normalise back to the sphere.
        val out = Array(n) { FloatArray(dims) }
        for (i in 0 until n) {
            val row = centred[i]
            for (pc in pcs) {
                var dot = 0.0
                for (d in 0 until dims) dot += row[d] * pc[d]
                for (d in 0 until dims) row[d] -= dot * pc[d]
            }
            var norm = 0.0
            for (d in 0 until dims) norm += row[d] * row[d]
            norm = sqrt(norm)
            val inv = if (norm > 1e-12) 1.0 / norm else 0.0
            val o = out[i]
            for (d in 0 until dims) o[d] = (row[d] * inv).toFloat()
        }
        return out
    }

    /**
     * Leading eigenvector of the covariance of [centred], deflated against [existing] PCs.
     *
     * Iterates `v ← normalize(Σ_i x_i (x_iᵀ v))`, re-orthogonalising against the PCs already
     * found each step so deflation is exact rather than drifting. Deterministic seed (all ones)
     * so the same data yields the same component every run — the whole point of a stable map.
     */
    private fun powerIteration(
        centred: Array<DoubleArray>,
        dims: Int,
        existing: List<DoubleArray>,
    ): DoubleArray? {
        var v = DoubleArray(dims) { 1.0 / sqrt(dims.toDouble()) }
        orthogonalise(v, existing)
        normalizeInPlace(v)
        val next = DoubleArray(dims)
        repeat(POWER_ITERS) {
            java.util.Arrays.fill(next, 0.0)
            for (x in centred) {
                var dot = 0.0
                for (d in 0 until dims) dot += x[d] * v[d]
                for (d in 0 until dims) next[d] += x[d] * dot
            }
            orthogonalise(next, existing)
            val norm = normalizeInPlace(next)
            if (norm < 1e-12) return null
            System.arraycopy(next, 0, v, 0, dims)
        }
        return v.copyOf()
    }

    private fun orthogonalise(v: DoubleArray, basis: List<DoubleArray>) {
        for (b in basis) {
            var dot = 0.0
            for (d in v.indices) dot += v[d] * b[d]
            for (d in v.indices) v[d] -= dot * b[d]
        }
    }

    private fun normalizeInPlace(v: DoubleArray): Double {
        var norm = 0.0
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-12) {
            val inv = 1.0 / norm
            for (i in v.indices) v[i] *= inv
        }
        return norm
    }

    // ---- k-means -------------------------------------------------------------------------

    /**
     * Deterministic k-means over unit vectors (cosine ≈ Euclidean on the sphere).
     *
     * Seeds with farthest-point initialisation from a fixed start (row 0), which needs no RNG
     * and spreads the initial centres — so the clustering is reproducible, the requirement the
     * whole feature leans on. Returns the cluster index per row.
     */
    fun kMeans(vecs: Array<FloatArray>, k: Int, dims: Int): IntArray {
        val n = vecs.size
        if (n == 0) return IntArray(0)
        if (k <= 1) return IntArray(n) { 0 }

        val centres = Array(k) { FloatArray(dims) }
        // Farthest-point seeding.
        vecs[0].copyInto(centres[0])
        val minDist = DoubleArray(n) { distanceSq(vecs[it], centres[0], dims) }
        for (c in 1 until k) {
            var far = 0
            var farVal = -1.0
            for (i in 0 until n) if (minDist[i] > farVal) { farVal = minDist[i]; far = i }
            vecs[far].copyInto(centres[c])
            for (i in 0 until n) minDist[i] = min(minDist[i], distanceSq(vecs[i], centres[c], dims))
        }

        val assign = IntArray(n)
        repeat(KMEANS_MAX_ITERS) {
            var moved = false
            for (i in 0 until n) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = distanceSq(vecs[i], centres[c], dims)
                    if (d < bestD) { bestD = d; best = c }
                }
                if (assign[i] != best) { assign[i] = best; moved = true }
            }
            // Recompute centres as the (renormalised) mean of members.
            val sums = Array(k) { DoubleArray(dims) }
            val counts = IntArray(k)
            for (i in 0 until n) {
                counts[assign[i]]++
                val s = sums[assign[i]]
                val v = vecs[i]
                for (d in 0 until dims) s[d] += v[d]
            }
            for (c in 0 until k) {
                if (counts[c] == 0) continue
                val s = sums[c]
                var norm = 0.0
                for (d in 0 until dims) norm += s[d] * s[d]
                norm = sqrt(norm)
                val inv = if (norm > 1e-12) 1.0 / norm else 0.0
                val ce = centres[c]
                for (d in 0 until dims) ce[d] = (s[d] * inv).toFloat()
            }
            if (!moved) return assign
        }
        return assign
    }

    private fun distanceSq(a: FloatArray, b: FloatArray, dims: Int): Double {
        var s = 0.0
        for (d in 0 until dims) {
            val diff = a[d] - b[d]
            s += diff.toDouble() * diff.toDouble()
        }
        return s
    }

    private fun summariseClusters(
        assign: IntArray,
        vecs: Array<FloatArray>,
        rowIdx: List<Int>,
        entries: List<Pair<ChunkMeta, FloatArray>>,
        dims: Int,
    ): List<ClusterSummary> {
        val n = vecs.size
        if (n == 0) return emptyList()
        val k = (assign.maxOrNull() ?: 0) + 1
        val members = Array(k) { mutableListOf<Int>() }
        for (i in 0 until n) members[assign[i]].add(i)

        val out = ArrayList<ClusterSummary>(k)
        for (c in 0 until k) {
            val mem = members[c]
            if (mem.isEmpty()) continue
            // Centroid = renormalised mean.
            val centroid = FloatArray(dims)
            for (i in mem) { val v = vecs[i]; for (d in 0 until dims) centroid[d] += v[d] }
            var norm = 0.0
            for (d in 0 until dims) norm += centroid[d] * centroid[d]
            norm = sqrt(norm)
            val inv = if (norm > 1e-12) (1.0 / norm).toFloat() else 0f
            for (d in 0 until dims) centroid[d] *= inv

            // Tightness = mean cosine to centroid. Also rank members by cosine so we can keep the
            // top-K nearest the centroid — the exemplar (nearest) drives the deep-link, and the
            // whole K feed the c-TF-IDF label (one passage is too thin to name a theme from).
            var sumCos = 0.0
            val scored = ArrayList<Pair<Int, Double>>(mem.size)
            for (i in mem) {
                var dot = 0.0
                val v = vecs[i]
                for (d in 0 until dims) dot += v[d] * centroid[d]
                sumCos += dot
                scored.add(i to dot)
            }
            scored.sortByDescending { it.second }
            val topRows = scored.take(LABEL_CHUNKS_PER_CLUSTER).map { it.first }
            val memberChunkIds = topRows.map { entries[rowIdx[it]].first.id }
            val exemplarChunkId = memberChunkIds.firstOrNull() ?: entries[rowIdx[mem.first()]].first.id

            // Raw (un-whitened) centroid: the mean of members' *original* vectors, renormalised.
            // This is the anchor for the lazy stage-2 label re-rank — candidate phrases embed into
            // raw model space, so they must be compared against a raw centroid, NOT the whitened
            // one used for layout (mixing the two spaces would silently mis-rank the labels).
            val rawCentroid = FloatArray(dims)
            for (i in mem) { val rv = entries[rowIdx[i]].second; for (d in 0 until dims) rawCentroid[d] += rv[d] }
            var rn = 0.0
            for (d in 0 until dims) rn += rawCentroid[d] * rawCentroid[d]
            rn = sqrt(rn)
            val rinv = if (rn > 1e-12) (1.0 / rn).toFloat() else 0f
            for (d in 0 until dims) rawCentroid[d] *= rinv

            out.add(
                ClusterSummary(
                    centroidWhitened = centroid,
                    mass = mem.size.toFloat() / n,
                    tightness = (sumCos / mem.size).toFloat().coerceIn(-1f, 1f),
                    exemplarChunkId = exemplarChunkId,
                    memberChunkIds = memberChunkIds,
                    rawCentroid = rawCentroid,
                )
            )
        }
        // Largest topic first, so the renderer can lay dominant continents down before minor isles.
        return out.sortedByDescending { it.mass }
    }

    // ---- 2D projection -------------------------------------------------------------------

    /**
     * Projects [points] to 2D using the top two principal components, with each component's
     * sign pinned by its largest-magnitude coordinate. The sign pin is what makes the layout
     * stable: power iteration can return either ±v, and an unpinned sign would mirror the whole
     * map between runs for no reason the reader could understand.
     *
     * Output coordinates are normalised into roughly [-1, 1] on each axis so the renderer can
     * scale to any canvas.
     */
    fun projectTo2D(points: List<FloatArray>, dims: Int): List<FloatArray> {
        val n = points.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(floatArrayOf(0f, 0f))

        val mean = DoubleArray(dims)
        for (p in points) for (d in 0 until dims) mean[d] += p[d]
        for (d in 0 until dims) mean[d] /= n
        val centred = Array(n) { i -> DoubleArray(dims) { d -> points[i][d] - mean[d] } }

        val pc1 = powerIteration(centred, dims, emptyList())
        val axes = ArrayList<DoubleArray>()
        if (pc1 != null) { signFix(pc1); axes.add(pc1) }
        val pc2 = if (pc1 != null) powerIteration(centred, dims, listOf(pc1)) else null
        if (pc2 != null) { signFix(pc2); axes.add(pc2) }

        val coords = Array(n) { FloatArray(2) }
        for (i in 0 until n) {
            val row = centred[i]
            for (a in 0 until 2) {
                val axis = axes.getOrNull(a) ?: continue
                var dot = 0.0
                for (d in 0 until dims) dot += row[d] * axis[d]
                coords[i][a] = dot.toFloat()
            }
        }
        // Scale each axis to [-1, 1] by its max absolute extent.
        for (a in 0 until 2) {
            var maxAbs = 0f
            for (i in 0 until n) maxAbs = max(maxAbs, abs(coords[i][a]))
            if (maxAbs > 1e-6f) for (i in 0 until n) coords[i][a] /= maxAbs
        }
        return coords.toList()
    }

    /** Pins a component's sign so its largest-magnitude entry is positive. */
    private fun signFix(v: DoubleArray) {
        var idx = 0
        var mag = 0.0
        for (d in v.indices) { val a = abs(v[d]); if (a > mag) { mag = a; idx = d } }
        if (v[idx] < 0) for (d in v.indices) v[d] = -v[d]
    }

    // ---- Adjacency -----------------------------------------------------------------------

    /**
     * Book-to-book edges from cross-cloud nearest-neighbour overlap.
     *
     * For a bounded, strided sample of each book's whitened chunks, finds the nearest chunk in a
     * (bounded, strided) candidate pool drawn from *other* books, and tallies the (A, B) book
     * pair. Directed counts are folded into one undirected weight. This measures genuine cloud
     * overlap — how often one book's passages sit nearest another's — which centroid distance
     * cannot, and it is what makes two books that share a subject share a coastline.
     */
    fun adjacency(
        entries: List<Pair<ChunkMeta, FloatArray>>,
        whitened: Array<FloatArray>,
        shouldCancel: () -> Boolean = { false },
    ): List<AtlasEdge> {
        val n = entries.size
        if (n < 2) return emptyList()
        val dims = whitened[0].size

        // Candidate pool: stride across the whole library so it is representative, capped.
        val stride = max(1, n / MAX_ADJACENCY_CANDIDATES)
        val candidateRows = (0 until n step stride).toList()

        // Sample rows per book, strided within each book, capped. Flattened into parallel arrays
        // so the (dominant) nearest-candidate scan can be sharded across cores.
        val byBook = LinkedHashMap<String, MutableList<Int>>()
        entries.forEachIndexed { i, (meta, _) -> byBook.getOrPut(meta.bookId) { mutableListOf() }.add(i) }

        val bookIds = byBook.keys.toList()
        val bookIndex = bookIds.withIndex().associate { (i, id) -> id to i }

        val srcRows = ArrayList<Int>()
        val srcBookIdx = ArrayList<Int>()
        for ((bookId, rows) in byBook) {
            val aIdx = bookIndex.getValue(bookId)
            val srcStride = max(1, rows.size / MAX_ADJACENCY_SAMPLES_PER_BOOK)
            var s = 0
            while (s < rows.size) {
                srcRows.add(rows[s]); srcBookIdx.add(aIdx)
                s += srcStride
            }
        }

        // For one source row, find its single nearest chunk in another book and return the packed
        // undirected edge key, or -1L when it matched nothing. Pure over shared read-only state, so
        // it is safe to run concurrently across disjoint source-row ranges.
        fun edgeForSource(i: Int): Long {
            val srcRow = srcRows[i]
            val aIdx = srcBookIdx[i]
            val bookId = bookIds[aIdx]
            val srcVec = whitened[srcRow]
            var bestCand = -1
            var bestDot = -2.0
            for (candRow in candidateRows) {
                if (entries[candRow].first.bookId == bookId) continue
                var dot = 0.0
                val cv = whitened[candRow]
                for (d in 0 until dims) dot += srcVec[d] * cv[d]
                if (dot > bestDot) { bestDot = dot; bestCand = candRow }
            }
            if (bestCand < 0) return -1L
            val b = bookIndex.getValue(entries[bestCand].first.bookId)
            return edgeKey(aIdx, b)
        }

        // Tally one contiguous range of source rows into a local map; checks cancellation
        // periodically so a cancelled roll-up stops promptly without an exception across threads.
        fun tallyRange(from: Int, to: Int): HashMap<Long, Int> {
            val local = HashMap<Long, Int>()
            var i = from
            var sinceCheck = 0
            while (i < to) {
                val key = edgeForSource(i)
                if (key >= 0L) local[key] = (local[key] ?: 0) + 1
                i++
                if (++sinceCheck >= 32) { sinceCheck = 0; if (shouldCancel()) break }
            }
            return local
        }

        val total = srcRows.size
        val workers = ScanPool.parallelism
        val pairCount: HashMap<Long, Int>
        if (workers <= 1 || total < 2 * workers) {
            if (shouldCancel()) throw RollupCancelledException()
            pairCount = tallyRange(0, total)
        } else {
            // Contiguous shards across the worker pool; identical result to the serial tally because
            // each source row picks its nearest candidate independently and the counts just sum.
            val chunk = (total + workers - 1) / workers
            val tasks = ArrayList<Callable<HashMap<Long, Int>>>(workers)
            var start = 0
            while (start < total) {
                val from = start
                val to = minOf(start + chunk, total)
                tasks.add(Callable { tallyRange(from, to) })
                start = to
            }
            val shards = ScanPool.executor.invokeAll(tasks).map { it.get() }
            pairCount = HashMap()
            for (shard in shards) for ((k, v) in shard) pairCount[k] = (pairCount[k] ?: 0) + v
        }
        if (shouldCancel()) throw RollupCancelledException()

        return pairCount.entries
            .filter { it.value >= MIN_EDGE_WEIGHT }
            .map { (key, weight) ->
                val a = (key ushr 32).toInt()
                val b = (key and 0xffffffffL).toInt()
                AtlasEdge(bookIds[a], bookIds[b], weight)
            }
            .sortedByDescending { it.weight }
    }

    /** Order-independent packed key for an undirected book pair. */
    private fun edgeKey(a: Int, b: Int): Long {
        val lo = min(a, b)
        val hi = max(a, b)
        return (lo.toLong() shl 32) or hi.toLong()
    }

    private data class BookClusters(val bookId: String, val clusters: List<ClusterSummary>)

    private data class ClusterSummary(
        val centroidWhitened: FloatArray,
        val mass: Float,
        val tightness: Float,
        val exemplarChunkId: String,
        val memberChunkIds: List<String>,
        val rawCentroid: FloatArray,
    )

    /** How many centroid-nearest chunks each cluster keeps to feed the c-TF-IDF label. */
    const val LABEL_CHUNKS_PER_CLUSTER = 6
}

/** Thrown by [AtlasRollup.compute] when its `shouldCancel` hook asks it to stop mid-flight. */
class RollupCancelledException : RuntimeException("Atlas roll-up cancelled")

/** The structural output of [AtlasRollup.compute]: books with topic clusters, joined by edges. */
data class AtlasGeometry(
    val books: List<AtlasBookGeometry>,
    val edges: List<AtlasEdge>,
)

/** One book's topic regions in the shared 2D layout. */
data class AtlasBookGeometry(
    val bookId: String,
    val clusters: List<AtlasClusterGeometry>,
)

/**
 * One topic region of a book.
 *
 * @param x,y layout position in roughly [-1, 1] on each axis (renderer scales to the canvas).
 * @param mass this cluster's fraction of the book's chunks — the region's area weight.
 * @param tightness mean cosine of members to the centroid; a coherent topic is high (sharp
 *   coastline), a diffuse one low (ragged coastline).
 * @param exemplarChunkId the real chunk nearest the centroid, used for the label and deep-link.
 */
data class AtlasClusterGeometry(
    val x: Float,
    val y: Float,
    val mass: Float,
    val tightness: Float,
    val exemplarChunkId: String,
    /** The centroid-nearest chunk ids (exemplar first), for labelling the cluster's theme. */
    val memberChunkIds: List<String> = emptyList(),
    /** Raw (un-whitened) unit centroid — the anchor for the lazy stage-2 label re-rank. */
    val rawCentroid: FloatArray = FloatArray(0),
) {
    // Data classes with FloatArray need identity-free equals/hashCode only if compared; the Atlas
    // never compares geometries by value, so the defaults are fine and intentionally left as-is.
}

/** An undirected book-to-book border, weighted by cross-cloud nearest-neighbour overlap. */
@Serializable
data class AtlasEdge(
    val bookIdA: String,
    val bookIdB: String,
    val weight: Int,
)
