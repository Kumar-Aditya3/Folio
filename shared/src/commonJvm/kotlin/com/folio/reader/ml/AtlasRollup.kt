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

    /**
     * How many cross-book neighbours each sampled source chunk contributes, instead of a single
     * argmax. Taking the top-[MAX_NEIGHBORS_PER_SOURCE] above [MIN_MATCH_COSINE] stops one stray
     * coincidental match from being the *only* signal — a passage genuinely near several books
     * links to all of them, a passage near one links to one.
     */
    const val MAX_NEIGHBORS_PER_SOURCE = 5

    /**
     * Cosine floor (in the whitened space) a cross-book match must clear before it counts at all.
     * Below this the two passages are not similar, they are merely both English prose sitting in
     * the model's centre — the same cone the zero-shot tagger's 0.35 guards against, lower here
     * because whitening has already removed the shared component.
     */
    const val MIN_MATCH_COSINE = 0.30

    /**
     * Each book keeps its strongest [TOP_K_EDGES_PER_BOOK] edges; the final graph is the union of
     * both endpoints' top-K (mutual-ish). Sparsifying this way keeps the graph connected enough to
     * lay out and cluster while dropping the long tail of weak, noisy borders.
     */
    const val TOP_K_EDGES_PER_BOOK = 6

    /**
     * Weight is stored as `round(normalized_similarity * EDGE_WEIGHT_SCALE)` so [AtlasEdge.weight]
     * stays an `Int` (and serialises unchanged) while carrying a real score rather than a raw count.
     */
    const val EDGE_WEIGHT_SCALE = 10_000

    /** Only edges whose scaled weight reaches this survive — the score floor, in scaled units. */
    const val MIN_EDGE_WEIGHT = 1

    /** How many top principal components the whitening projects out. */
    const val WHITEN_COMPONENTS = 3

    /** Background sample size used to estimate a candidate chunk's "hubness" (mean similarity). */
    private const val HUB_BACKGROUND_SAMPLES = 192

    /** Fixed-point scale for accumulating float similarity as order-independent Long sums. */
    private const val CONTRIB_SCALE = 1_000_000.0

    /** Deterministic force-directed layout: fixed iteration count (no RNG, no early stop). */
    private const val LAYOUT_ITERATIONS = 300

    /** How far a book's topic clusters spread around its macro position (mini-PCA, layout units). */
    private const val LOCAL_OFFSET_SCALE = 0.12f

    /** Rounds of deterministic label propagation before communities are read off. */
    private const val LP_ROUNDS = 24

    /**
     * Above this many books, a label propagation that collapses to a single community is treated as
     * degenerate and replaced by connected components of each book's strongest edges.
     */
    private const val COMMUNITY_FALLBACK_MIN_BOOKS = 8

    private const val KMEANS_MAX_ITERS = 25
    private const val POWER_ITERS = 40

    /**
     * Weight of the same-genre attraction in [layoutBooks], relative to an edge of weight 1. Small on
     * purpose — a nudge that pulls a genre into one cloud without overpowering the real similarity
     * edges (which run up to 1.0). Only applied when per-book genres are supplied.
     */
    private const val GENRE_ATTRACT = 0.12

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
         * Per-book broad genre (any stable string per genre — the classifier's enum name is fine),
         * used only to give the layout a mild same-genre attraction so a genre drifts into one cloud.
         * Empty (the default, and every test) means no attraction, so the layout is byte-identical to
         * before — determinism is preserved.
         */
        bookGenre: Map<String, String> = emptyMap(),
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

        // 3. Book-to-book edges from a robust, hub-down-weighted, sparsified similarity graph.
        //    This is what "closer = more similar" and the communities below both stand on, so it
        //    is built before the layout rather than as an afterthought.
        val edges = adjacency(entries, whitened, shouldCancel)

        // 4. A deterministic macro layout of the *books* on the robust graph, seeded by a sign-fixed
        //    PCA of each book's own mean centroid so an unchanged library lays out identically each
        //    open. Force-direction then pulls genuinely-similar books together and pushes strangers
        //    apart, which the old per-cluster PCA shadow could not — proximity there was noise.
        val bookIds = books.map { it.bookId }
        val bookMeans = books.map { massWeightedMean(it.clusters, dims) }
        val seeds = projectTo2D(bookMeans, dims)
        val macro = layoutBooks(bookIds, seeds, edges, shouldCancel, genres = bookIds.map { bookGenre[it] })

        // 5. Emergent communities on the same graph (deterministic label propagation), so the map's
        //    coloured regions come from the data rather than from bearing around the origin.
        val communities = detectCommunities(bookIds, edges)

        // 6. Place each book's clusters as small local offsets around its macro position — a mini-PCA
        //    of the book's own cluster centroids — so a multi-topic book still shows texture while
        //    the macro structure stays honest.
        val books2D = books.mapIndexed { bi, b ->
            if (shouldCancel()) throw RollupCancelledException()
            val mx = macro[bi][0]
            val my = macro[bi][1]
            val local =
                if (b.clusters.size > 1) projectTo2D(b.clusters.map { it.centroidWhitened }, dims)
                else listOf(floatArrayOf(0f, 0f))
            AtlasBookGeometry(
                bookId = b.bookId,
                x = mx,
                y = my,
                communityId = communities[b.bookId] ?: 0,
                clusters = b.clusters.mapIndexed { ci, cl ->
                    val lc = local.getOrElse(ci) { floatArrayOf(0f, 0f) }
                    AtlasClusterGeometry(
                        x = mx + lc[0] * LOCAL_OFFSET_SCALE,
                        y = my + lc[1] * LOCAL_OFFSET_SCALE,
                        mass = cl.mass,
                        tightness = cl.tightness,
                        exemplarChunkId = cl.exemplarChunkId,
                        memberChunkIds = cl.memberChunkIds,
                        rawCentroid = cl.rawCentroid,
                    )
                },
            )
        }
        return AtlasGeometry(books2D, edges)
    }

    /** Mass-weighted, re-normalised mean of a book's cluster centroids — the book's own centre. */
    private fun massWeightedMean(clusters: List<ClusterSummary>, dims: Int): FloatArray {
        val out = FloatArray(dims)
        var total = 0f
        for (cl in clusters) {
            val w = cl.mass.coerceAtLeast(1e-4f)
            total += w
            val v = cl.centroidWhitened
            for (d in 0 until dims) out[d] += v[d] * w
        }
        if (total > 0f) for (d in 0 until dims) out[d] /= total
        var norm = 0.0
        for (d in 0 until dims) norm += out[d] * out[d]
        norm = sqrt(norm)
        if (norm > 1e-12) { val inv = (1.0 / norm).toFloat(); for (d in 0 until dims) out[d] *= inv }
        return out
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
     * A robust, weighted book-to-book similarity graph.
     *
     * The old version tallied a single nearest-neighbour hit per sampled chunk, so two coincidental
     * matches became a permanent border and a passage that sits near *everything* (a stock phrase,
     * a generic scene) linked every book it touched. This version fixes all three failure modes:
     *
     * 1. **Top-k, not argmax.** Each sampled source chunk contributes its
     *    [MAX_NEIGHBORS_PER_SOURCE] nearest cross-book neighbours above [MIN_MATCH_COSINE], so a
     *    genuinely-shared topic accumulates weight and a single stray match does not dominate.
     * 2. **Hub down-weighting.** Every candidate's mean similarity to a small background sample is
     *    subtracted from its match score, so a passage near everything contributes ≈nothing — this
     *    is what kills "one common phrase links all books".
     * 3. **Normalise + sparsify.** Pair weight is divided by `sqrt(samplesA·samplesB)` so a large
     *    book cannot hoard edges by sheer chunk count, then each book keeps only its strongest
     *    [TOP_K_EDGES_PER_BOOK] edges (the final set is the union of both endpoints' top-K).
     *
     * The float similarity is accumulated as fixed-point [CONTRIB_SCALE] `Long` sums, which are
     * order-independent — so the shard partition (which depends on the machine's core count) cannot
     * change the result, preserving the "same library, same sky" reproducibility the map depends on.
     * Weight is finally scaled by [EDGE_WEIGHT_SCALE] to keep [AtlasEdge.weight] an `Int`.
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
        val candCount = candidateRows.size

        val byBook = LinkedHashMap<String, MutableList<Int>>()
        entries.forEachIndexed { i, (meta, _) -> byBook.getOrPut(meta.bookId) { mutableListOf() }.add(i) }
        val bookIds = byBook.keys.toList()
        val bookIndex = bookIds.withIndex().associate { (i, id) -> id to i }

        // Which book each candidate belongs to, resolved once.
        val candBookIdx = IntArray(candCount) { bookIndex.getValue(entries[candidateRows[it]].first.bookId) }

        // Hub score per candidate: mean cosine to a small strided background sample. A passage near
        // everything scores high here and is subtracted down to nothing below. Computed once.
        val bgStride = max(1, candCount / HUB_BACKGROUND_SAMPLES)
        val bgRows = ArrayList<Int>()
        run { var i = 0; while (i < candCount) { bgRows.add(candidateRows[i]); i += bgStride } }
        val hub = DoubleArray(candCount)
        for (ci in 0 until candCount) {
            if ((ci and 0x3FF) == 0 && shouldCancel()) throw RollupCancelledException()
            val cv = whitened[candidateRows[ci]]
            var sum = 0.0
            var cnt = 0
            for (b in bgRows) {
                if (b == candidateRows[ci]) continue
                val bv = whitened[b]
                var dot = 0.0
                for (d in 0 until dims) dot += cv[d] * bv[d]
                sum += dot; cnt++
            }
            hub[ci] = if (cnt > 0) sum / cnt else 0.0
        }

        // Source rows per book (strided, capped) and how many each book contributed.
        val srcRows = ArrayList<Int>()
        val srcBookIdx = ArrayList<Int>()
        val sampleCount = IntArray(bookIds.size)
        for ((bookId, rows) in byBook) {
            val aIdx = bookIndex.getValue(bookId)
            val srcStride = max(1, rows.size / MAX_ADJACENCY_SAMPLES_PER_BOOK)
            var s = 0
            while (s < rows.size) {
                srcRows.add(rows[s]); srcBookIdx.add(aIdx); sampleCount[aIdx]++
                s += srcStride
            }
        }

        // Accumulate one source row's top-k adjusted contributions into a local Long-sum map. Long
        // addition is order-independent, so any shard partition yields the same totals.
        fun accumulateSource(i: Int, local: HashMap<Long, Long>) {
            val aIdx = srcBookIdx[i]
            val srcVec = whitened[srcRows[i]]
            val k = MAX_NEIGHBORS_PER_SOURCE
            val topDot = DoubleArray(k) { Double.NEGATIVE_INFINITY }
            val topCi = IntArray(k) { -1 }
            for (ci in 0 until candCount) {
                if (candBookIdx[ci] == aIdx) continue
                val cv = whitened[candidateRows[ci]]
                var dot = 0.0
                for (d in 0 until dims) dot += srcVec[d] * cv[d]
                if (dot < MIN_MATCH_COSINE) continue
                // Insert into the fixed-size top-k by raw cosine.
                var minSlot = 0
                for (s in 1 until k) if (topDot[s] < topDot[minSlot]) minSlot = s
                if (dot > topDot[minSlot]) { topDot[minSlot] = dot; topCi[minSlot] = ci }
            }
            for (s in 0 until k) {
                val ci = topCi[s]
                if (ci < 0) continue
                val adjusted = topDot[s] - hub[ci]
                if (adjusted <= 0.0) continue
                val scaled = (adjusted * CONTRIB_SCALE).toLong()
                if (scaled <= 0L) continue
                val key = edgeKey(aIdx, candBookIdx[ci])
                local[key] = (local[key] ?: 0L) + scaled
            }
        }

        fun tallyRange(from: Int, to: Int): HashMap<Long, Long> {
            val local = HashMap<Long, Long>()
            var i = from
            var sinceCheck = 0
            while (i < to) {
                accumulateSource(i, local)
                i++
                if (++sinceCheck >= 32) { sinceCheck = 0; if (shouldCancel()) break }
            }
            return local
        }

        val total = srcRows.size
        val workers = ScanPool.parallelism
        val pairSum: HashMap<Long, Long>
        if (workers <= 1 || total < 2 * workers) {
            if (shouldCancel()) throw RollupCancelledException()
            pairSum = tallyRange(0, total)
        } else {
            val chunk = (total + workers - 1) / workers
            val tasks = ArrayList<Callable<HashMap<Long, Long>>>(workers)
            var start = 0
            while (start < total) {
                val from = start
                val to = minOf(start + chunk, total)
                tasks.add(Callable { tallyRange(from, to) })
                start = to
            }
            val shards = ScanPool.executor.invokeAll(tasks).map { it.get() }
            pairSum = HashMap()
            for (shard in shards) for ((key, v) in shard) pairSum[key] = (pairSum[key] ?: 0L) + v
        }
        if (shouldCancel()) throw RollupCancelledException()

        // Normalise by sqrt(samplesA·samplesB) and scale to an Int weight; drop anything below the
        // score floor.
        data class Scored(val a: Int, val b: Int, val weight: Int)
        val scored = ArrayList<Scored>(pairSum.size)
        for ((key, sum) in pairSum) {
            val a = (key ushr 32).toInt()
            val b = (key and 0xffffffffL).toInt()
            val denom = sqrt(sampleCount[a].toDouble() * sampleCount[b].toDouble())
            if (denom <= 0.0) continue
            val score = (sum / CONTRIB_SCALE) / denom
            val weight = round(score * EDGE_WEIGHT_SCALE).toInt()
            if (weight >= MIN_EDGE_WEIGHT) scored.add(Scored(a, b, weight))
        }
        if (scored.isEmpty()) return emptyList()

        // Sparsify: keep each book's strongest [TOP_K_EDGES_PER_BOOK]; the final graph is the union.
        val perBook = HashMap<Int, MutableList<Scored>>()
        for (e in scored) {
            perBook.getOrPut(e.a) { ArrayList() }.add(e)
            perBook.getOrPut(e.b) { ArrayList() }.add(e)
        }
        val kept = HashSet<Long>()
        for ((_, list) in perBook) {
            list.sortWith(compareByDescending<Scored> { it.weight }.thenBy { edgeKey(it.a, it.b) })
            for (e in list.take(TOP_K_EDGES_PER_BOOK)) kept.add(edgeKey(e.a, e.b))
        }

        return scored
            .filter { edgeKey(it.a, it.b) in kept }
            .distinctBy { edgeKey(it.a, it.b) }
            .map { AtlasEdge(bookIds[it.a], bookIds[it.b], it.weight) }
            .sortedByDescending { it.weight }
    }

    // ---- Deterministic macro layout ------------------------------------------------------

    /**
     * A deterministic Fruchterman–Reingold force-directed layout of the books on the weighted
     * similarity graph, seeded by [seeds] (a sign-fixed PCA of each book's mean centroid).
     *
     * Reproducibility is a hard requirement — the same library must lay out the same way every open
     * — so there is **no RNG**: the seed is deterministic, the iteration count is fixed, node order
     * is fixed, and the cooling schedule is a plain function of the iteration index. Output is
     * normalised into roughly [-1, 1] per axis for the renderer, with the sign already pinned by the
     * PCA seed.
     */
    fun layoutBooks(
        bookIds: List<String>,
        seeds: List<FloatArray>,
        edges: List<AtlasEdge>,
        shouldCancel: () -> Boolean = { false },
        /**
         * Optional per-book genre bucket aligned to [bookIds] (null = unclassified). When present,
         * same-genre books feel a mild extra attraction so a genre settles into one cloud on top of
         * the similarity edges — deterministic, a fixed weight, no RNG. Empty ⇒ no attraction, so the
         * layout is unchanged and byte-reproducible (the property the tests lock).
         */
        genres: List<String?> = emptyList(),
    ): List<FloatArray> {
        val n = bookIds.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(floatArrayOf(0f, 0f))

        val index = bookIds.withIndex().associate { (i, id) -> id to i }
        val pos = Array(n) { doubleArrayOf(seeds[it][0].toDouble(), seeds[it][1].toDouble()) }

        // Genre bucket per node (-1 = none/unknown), so a same-genre test is an int compare.
        val genreIdx = IntArray(n) { -1 }
        if (genres.size == n) {
            val ids = HashMap<String, Int>()
            for (i in 0 until n) {
                val g = genres[i]
                if (!g.isNullOrBlank()) genreIdx[i] = ids.getOrPut(g) { ids.size }
            }
        }

        // Edge weights normalised to (0, 1] so attraction is comparable regardless of the raw scale.
        val maxW = (edges.maxOfOrNull { it.weight } ?: 1).coerceAtLeast(1).toDouble()
        val ea = IntArray(edges.size)
        val eb = IntArray(edges.size)
        val ew = DoubleArray(edges.size)
        var m = 0
        for (e in edges) {
            val a = index[e.bookIdA] ?: continue
            val b = index[e.bookIdB] ?: continue
            ea[m] = a; eb[m] = b; ew[m] = (e.weight / maxW).coerceIn(0.0, 1.0); m++
        }

        val k = sqrt(4.0 / n)          // ideal spring length for a [-1,1]² area
        val eps = 1e-4
        val disp = Array(n) { DoubleArray(2) }

        repeat(LAYOUT_ITERATIONS) { iter ->
            if (shouldCancel()) throw RollupCancelledException()
            for (i in 0 until n) { disp[i][0] = 0.0; disp[i][1] = 0.0 }

            // Repulsion between every pair.
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = pos[i][0] - pos[j][0]
                    var dy = pos[i][1] - pos[j][1]
                    var dist = sqrt(dx * dx + dy * dy)
                    if (dist < eps) {
                        // Deterministic tiny nudge so coincident seeds still separate.
                        dx = (i - j).toDouble() * eps
                        dy = eps
                        dist = sqrt(dx * dx + dy * dy)
                    }
                    val rep = (k * k) / dist
                    val ux = dx / dist
                    val uy = dy / dist
                    disp[i][0] += ux * rep; disp[i][1] += uy * rep
                    disp[j][0] -= ux * rep; disp[j][1] -= uy * rep
                    // Mild same-genre attraction: a weak spring between books of the same genre so a
                    // genre settles into one cloud, on top of (never overriding) the similarity edges.
                    if (genreIdx[i] >= 0 && genreIdx[i] == genreIdx[j]) {
                        val gattr = (dist * dist) / k * GENRE_ATTRACT
                        disp[i][0] -= ux * gattr; disp[i][1] -= uy * gattr
                        disp[j][0] += ux * gattr; disp[j][1] += uy * gattr
                    }
                }
            }
            // Attraction along edges, scaled by weight.
            for (e in 0 until m) {
                val a = ea[e]; val b = eb[e]
                var dx = pos[a][0] - pos[b][0]
                var dy = pos[a][1] - pos[b][1]
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(eps)
                val attr = (dist * dist) / k * ew[e]
                val ux = dx / dist
                val uy = dy / dist
                disp[a][0] -= ux * attr; disp[a][1] -= uy * attr
                disp[b][0] += ux * attr; disp[b][1] += uy * attr
            }
            // Cooling: max step shrinks linearly with the iteration index.
            val temp = 0.1 * (1.0 - iter.toDouble() / LAYOUT_ITERATIONS)
            for (i in 0 until n) {
                val d = sqrt(disp[i][0] * disp[i][0] + disp[i][1] * disp[i][1])
                if (d > eps) {
                    val step = min(d, temp)
                    pos[i][0] += disp[i][0] / d * step
                    pos[i][1] += disp[i][1] / d * step
                }
            }
        }

        // Centre and scale each axis into [-1, 1] by its max absolute extent.
        val mean = DoubleArray(2)
        for (i in 0 until n) { mean[0] += pos[i][0]; mean[1] += pos[i][1] }
        mean[0] /= n; mean[1] /= n
        val maxAbs = doubleArrayOf(0.0, 0.0)
        for (i in 0 until n) {
            pos[i][0] -= mean[0]; pos[i][1] -= mean[1]
            maxAbs[0] = max(maxAbs[0], abs(pos[i][0]))
            maxAbs[1] = max(maxAbs[1], abs(pos[i][1]))
        }
        return List(n) { i ->
            floatArrayOf(
                if (maxAbs[0] > 1e-9) (pos[i][0] / maxAbs[0]).toFloat() else 0f,
                if (maxAbs[1] > 1e-9) (pos[i][1] / maxAbs[1]).toFloat() else 0f,
            )
        }
    }

    // ---- Deterministic communities -------------------------------------------------------

    /**
     * Emergent communities on the weighted graph via deterministic **label propagation**.
     *
     * Every node starts in its own community and, in a fixed node order over a fixed number of
     * rounds, adopts the community carrying the most edge weight among its neighbours (ties broken
     * toward the smaller community id, so the result cannot wobble between runs). Communities are
     * then renumbered by size (largest is 0) so the ids are stable and meaningful to the renderer.
     *
     * If propagation collapses a large library into a single blob — the known failure mode on a
     * densely-connected graph — it falls back to connected components of each book's strongest
     * edges, which always yields the real separations. A book with no edges is its own community.
     */
    fun detectCommunities(bookIds: List<String>, edges: List<AtlasEdge>): Map<String, Int> {
        val n = bookIds.size
        if (n == 0) return emptyMap()
        val index = bookIds.withIndex().associate { (i, id) -> id to i }

        // Weighted neighbour lists.
        val neigh = Array(n) { ArrayList<IntArray>() } // each entry: [neighbourIndex, weight]
        for (e in edges) {
            val a = index[e.bookIdA] ?: continue
            val b = index[e.bookIdB] ?: continue
            if (a == b) continue
            neigh[a].add(intArrayOf(b, e.weight))
            neigh[b].add(intArrayOf(a, e.weight))
        }

        val labels = IntArray(n) { it }
        repeat(LP_ROUNDS) {
            var changed = false
            for (i in 0 until n) {
                val ns = neigh[i]
                if (ns.isEmpty()) continue
                // Sum edge weight per neighbouring label; pick the heaviest, smallest-label on ties.
                val weightByLabel = HashMap<Int, Long>()
                for (nb in ns) {
                    val lbl = labels[nb[0]]
                    weightByLabel[lbl] = (weightByLabel[lbl] ?: 0L) + nb[1].toLong()
                }
                var bestLabel = labels[i]
                var bestWeight = Long.MIN_VALUE
                for ((lbl, w) in weightByLabel.entries.sortedBy { it.key }) {
                    if (w > bestWeight) { bestWeight = w; bestLabel = lbl }
                }
                if (bestLabel != labels[i]) { labels[i] = bestLabel; changed = true }
            }
            if (!changed) return@repeat
        }

        var result = renumberBySize(bookIds, labels)
        val distinct = result.values.toSet().size
        if (distinct <= 1 && n >= COMMUNITY_FALLBACK_MIN_BOOKS) {
            result = connectedComponents(bookIds, edges)
        }
        return result
    }

    /** Connected components over each book's strongest [TOP_K_EDGES_PER_BOOK] edges. */
    private fun connectedComponents(bookIds: List<String>, edges: List<AtlasEdge>): Map<String, Int> {
        val n = bookIds.size
        val index = bookIds.withIndex().associate { (i, id) -> id to i }
        // Keep only each book's strongest edges so a single weak bridge cannot fuse two components.
        val perBook = Array(n) { ArrayList<AtlasEdge>() }
        for (e in edges) {
            val a = index[e.bookIdA] ?: continue
            val b = index[e.bookIdB] ?: continue
            perBook[a].add(e); perBook[b].add(e)
        }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        fun union(x: Int, y: Int) { val rx = find(x); val ry = find(y); if (rx != ry) parent[max(rx, ry)] = min(rx, ry) }
        for (i in 0 until n) {
            perBook[i].sortedByDescending { it.weight }.take(TOP_K_EDGES_PER_BOOK).forEach { e ->
                val a = index[e.bookIdA] ?: return@forEach
                val b = index[e.bookIdB] ?: return@forEach
                union(a, b)
            }
        }
        val labels = IntArray(n) { find(it) }
        return renumberBySize(bookIds, labels)
    }

    /** Renumbers raw labels so the largest community is 0, deterministically (size, then min index). */
    private fun renumberBySize(bookIds: List<String>, labels: IntArray): Map<String, Int> {
        val members = LinkedHashMap<Int, MutableList<Int>>()
        labels.forEachIndexed { i, lbl -> members.getOrPut(lbl) { ArrayList() }.add(i) }
        val ordered = members.entries.sortedWith(
            compareByDescending<Map.Entry<Int, MutableList<Int>>> { it.value.size }
                .thenBy { it.value.minOrNull() ?: Int.MAX_VALUE },
        )
        val remap = HashMap<Int, Int>()
        ordered.forEachIndexed { newId, entry -> remap[entry.key] = newId }
        return bookIds.indices.associate { i -> bookIds[i] to (remap[labels[i]] ?: 0) }
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
    /** The book's macro position in roughly [-1, 1] on each axis, from the force-directed layout. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** The book's emergent community id (0 = largest), from deterministic label propagation. */
    val communityId: Int = 0,
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
