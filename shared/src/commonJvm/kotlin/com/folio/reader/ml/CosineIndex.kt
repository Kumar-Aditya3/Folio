package com.folio.reader.ml

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Shared worker pool for the brute-force scan.
 *
 * The scan is the dominant per-query cost on a large library — a whole-library Meaning query
 * decodes ~98.5k int8 vectors, measured at ~730 ms single-threaded — and it is embarrassingly
 * parallel: each candidate's dot product is independent, so splitting the slot range across
 * cores is a near-linear win on the one thing the reader is actually waiting for.
 *
 * This pool is separate from [MlDispatchers.inference] on purpose. That pool sizes *how many
 * queries or slices* run at once (deliberately 2); this one sizes *how many cores one scan may
 * fan across*. A search is interactive and short, so it is allowed most of the machine for the
 * few hundred milliseconds it runs — unlike the backfill, which is throttled because nobody is
 * waiting on it. Daemon threads so the pool never holds the JVM alive.
 */
internal object ScanPool {
    /** Leave one core for the UI/dispatcher; the scan saturates the rest for a few hundred ms. */
    val parallelism: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)

    val executor by lazy {
        Executors.newFixedThreadPool(parallelism) { r ->
            Thread(r, "folio-scan").apply { isDaemon = true }
        }
    }

    /**
     * Below this many candidates a scan stays single-threaded: the fork/join and merge overhead
     * costs more than it saves for an in-book search or a small library. Whole-library scans on a
     * real device are far above this.
     */
    const val PARALLEL_THRESHOLD = 8_192
}

/**
 * Bounded top-k merge: keep the [limit] highest-scoring hits across all shard results.
 *
 * Each shard already returned at most [limit] hits, so this is a small merge (shards × limit),
 * and it reproduces the single-threaded result exactly because the per-candidate scores are
 * identical regardless of which thread computed them.
 */
internal fun mergeTopK(shards: List<List<VectorHit>>, limit: Int): List<VectorHit> {
    val all = ArrayList<VectorHit>(shards.sumOf { it.size })
    for (s in shards) all.addAll(s)
    return all.sortedByDescending { it.score }.take(limit)
}

/**
 * Brute-force cosine index over float32 vectors, held as one flat array.
 *
 * Flat storage rather than `Array<FloatArray>` is deliberate: the scan is memory-bandwidth
 * bound, and a contiguous block keeps the 40 000 x 384 case (~61 MB) free of per-row
 * pointer chasing. Vectors are L2-normalised on insert so the scan is a plain dot product
 * with no per-candidate square roots.
 */
class CosineIndex(
    override val dims: Int,
    /**
     * Slots to allocate up front. Pass the known row count when loading a whole index in one
     * shot: the backing array otherwise grows by *doubling*, so a bulk load of ~100k vectors
     * momentarily needs a 2^17-slot array (~192 MB at 384 dims) to hold ~98k — the exact
     * allocation that was throwing `OutOfMemoryError` on a large library. Sizing to the real
     * count removes that spike. Defaults small for the incremental-add path.
     */
    initialCapacity: Int = INITIAL_CAPACITY,
) : VectorIndex {

    private val startCapacity = initialCapacity.coerceAtLeast(1)
    private var ids: Array<String?> = arrayOfNulls(startCapacity)
    private var vectors = FloatArray(startCapacity * dims)
    private var count = 0

    private val idToSlot = HashMap<String, Int>()

    override val size: Int get() = count

    override fun add(chunkId: String, vector: FloatArray) {
        require(vector.size == dims) { "expected $dims dims, got ${vector.size}" }
        val existing = idToSlot[chunkId]
        if (existing != null) {
            System.arraycopy(vector, 0, vectors, existing * dims, dims)
            return
        }
        ensureCapacity(count + 1)
        System.arraycopy(vector, 0, vectors, count * dims, dims)
        ids[count] = chunkId
        idToSlot[chunkId] = count
        count++
    }

    override fun addAll(entries: List<Pair<String, FloatArray>>) {
        ensureCapacity(count + entries.size)
        entries.forEach { (id, vector) -> add(id, vector) }
    }

    override fun search(
        query: FloatArray,
        limit: Int,
        filter: ((chunkId: String) -> Boolean)?,
    ): List<VectorHit> {
        require(query.size == dims) { "expected $dims dims, got ${query.size}" }
        if (count == 0 || limit <= 0) return emptyList()

        val q = query.l2Normalize()
        // Bounded top-k: keeps the heap at `limit` entries instead of sorting 40 000 hits.
        val heap = java.util.PriorityQueue<VectorHit>(limit + 1, compareBy { it.score })
        for (slot in 0 until count) {
            val id = ids[slot] ?: continue
            if (filter != null && !filter(id)) continue
            val offset = slot * dims
            var dot = 0.0
            for (d in 0 until dims) dot += q[d].toDouble() * vectors[offset + d].toDouble()
            val score = dot.toFloat()
            if (heap.size < limit) {
                heap.add(VectorHit(id, score))
            } else if (heap.peek().score < score) {
                heap.poll()
                heap.add(VectorHit(id, score))
            }
        }
        return heap.sortedByDescending { it.score }
    }

    override fun clear() {
        ids = arrayOfNulls(INITIAL_CAPACITY)
        vectors = FloatArray(INITIAL_CAPACITY * dims)
        count = 0
        idToSlot.clear()
    }

    private fun ensureCapacity(required: Int) {
        if (required <= ids.size) return
        var newCapacity = ids.size
        while (newCapacity < required) newCapacity *= 2
        ids = ids.copyOf(newCapacity)
        vectors = vectors.copyOf(newCapacity * dims)
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}

/**
 * Brute-force cosine index that stores each vector as `int8`, a quarter the size of [CosineIndex].
 *
 * This is the shipped on-device index. The float32 flat array is ~150 MB for a large library's
 * ~100k Arctic chunks, which does not fit under a phone's heap growth limit and threw
 * `OutOfMemoryError` on load; the same vectors as `int8` are ~38 MB and fit comfortably.
 *
 * The quantisation is exact for the input this sees: embedder output is L2-normalised, so every
 * component is already in [-1, 1] and maps to a signed byte by `round(v * 127)`. Cosine of two
 * unit vectors is their dot product, computed here as the dequantised dot — the query stays
 * float, each stored component is read back as `byte / 127`. The error is bounded by the 1/127
 * quantisation step per component and averages out across [dims] terms, well under the retrieval
 * floor's tolerance (see [SemanticSearchRepository.MIN_SIMILARITY]); ranking order is preserved.
 *
 * Capacity is sized exactly at construction from the known row count, so there is no doubling
 * spike on load — the whole point is to avoid a large transient allocation, so growth here would
 * defeat it, but the incremental [add] path still doubles for correctness if a caller appends.
 */
class QuantizedCosineIndex(
    override val dims: Int,
    initialCapacity: Int = 256,
) : VectorIndex {

    private val startCapacity = initialCapacity.coerceAtLeast(1)
    private var ids: Array<String?> = arrayOfNulls(startCapacity)
    private var data = ByteArray(startCapacity * dims)
    private var count = 0
    private val idToSlot = HashMap<String, Int>()

    override val size: Int get() = count

    override fun add(chunkId: String, vector: FloatArray) {
        require(vector.size == dims) { "expected $dims dims, got ${vector.size}" }
        val existing = idToSlot[chunkId]
        val slot = if (existing != null) existing else {
            ensureCapacity(count + 1)
            val s = count
            ids[s] = chunkId
            idToSlot[chunkId] = s
            count++
            s
        }
        quantizeInto(vector, data, slot * dims)
    }

    override fun addAll(entries: List<Pair<String, FloatArray>>) {
        ensureCapacity(count + entries.size)
        entries.forEach { (id, vector) -> add(id, vector) }
    }

    override fun search(
        query: FloatArray,
        limit: Int,
        filter: ((chunkId: String) -> Boolean)?,
    ): List<VectorHit> {
        require(query.size == dims) { "expected $dims dims, got ${query.size}" }
        if (count == 0 || limit <= 0) return emptyList()

        val q = query.l2Normalize()

        // Small scans stay single-threaded: the fork/merge overhead is not worth it for an
        // in-book search or a small library.
        if (count < ScanPool.PARALLEL_THRESHOLD || ScanPool.parallelism <= 1) {
            return scanRange(0, count, q, limit, filter)
        }

        // Split the slot range into `parallelism` contiguous shards, scan each on its own core,
        // and merge the per-shard top-k. Contiguous ranges keep each thread reading a
        // cache-friendly run of the flat array. Scores are identical to the serial path — only
        // which thread computes a given dot product changes — so ranking is unchanged.
        val workers = ScanPool.parallelism
        val chunk = (count + workers - 1) / workers
        val tasks = ArrayList<Callable<List<VectorHit>>>(workers)
        var start = 0
        while (start < count) {
            val from = start
            val to = minOf(start + chunk, count)
            tasks.add(Callable { scanRange(from, to, q, limit, filter) })
            start = to
        }
        val shards = ScanPool.executor.invokeAll(tasks).map { it.get() }
        return mergeTopK(shards, limit)
    }

    /**
     * Scans slots in [from, to) and returns that range's own top-[limit] hits.
     *
     * The `int * INV_SCALE` per component was one multiply per dimension against a constant; the
     * integer products are summed first and the single [INV_SCALE] factor applied once at the end,
     * which is algebraically identical (the query is already normalised) but removes ~[dims]
     * multiplies per candidate from the hot loop.
     */
    private fun scanRange(
        from: Int,
        to: Int,
        q: FloatArray,
        limit: Int,
        filter: ((chunkId: String) -> Boolean)?,
    ): List<VectorHit> {
        val heap = java.util.PriorityQueue<VectorHit>(limit + 1, compareBy { it.score })
        val dims = dims
        for (slot in from until to) {
            val id = ids[slot] ?: continue
            if (filter != null && !filter(id)) continue
            val offset = slot * dims
            var dot = 0.0
            for (d in 0 until dims) dot += q[d] * data[offset + d].toInt()
            val score = (dot * INV_SCALE).toFloat()
            if (heap.size < limit) {
                heap.add(VectorHit(id, score))
            } else if (heap.peek().score < score) {
                heap.poll()
                heap.add(VectorHit(id, score))
            }
        }
        return heap.sortedByDescending { it.score }
    }

    override fun clear() {
        ids = arrayOfNulls(startCapacity)
        data = ByteArray(startCapacity * dims)
        count = 0
        idToSlot.clear()
    }

    private fun ensureCapacity(required: Int) {
        if (required <= ids.size) return
        var newCapacity = ids.size
        while (newCapacity < required) newCapacity *= 2
        ids = ids.copyOf(newCapacity)
        data = data.copyOf(newCapacity * dims)
    }

    private companion object {
        /** Full-scale int8: a unit component maps to +/-127. */
        const val SCALE = 127.0
        const val INV_SCALE = 1.0 / SCALE

        fun quantizeInto(vector: FloatArray, out: ByteArray, offset: Int) {
            for (d in vector.indices) {
                val scaled = (vector[d] * SCALE)
                val clamped = if (scaled > 127.0f) 127 else if (scaled < -127.0f) -127 else kotlin.math.round(scaled).toInt()
                out[offset + d] = clamped.toByte()
            }
        }
    }
}

/**
 * Reciprocal Rank Fusion.
 *
 * RRF combines rankings, not scores, which is exactly what is needed here: BM25's scale is
 * unbounded and corpus-dependent while cosine is bounded in [-1, 1], so any score-level
 * blend would need per-corpus tuning. `score(d) = sum over rankings of 1 / (k + rank(d))`.
 *
 * `k = 60` is the value from the original Cormack et al. paper and the standard default;
 * it damps the influence of the very top ranks so one list cannot dominate.
 */
object RrfFusion {

    const val DEFAULT_K = 60

    /**
     * Weight applied to the lexical list by [fusePair] when the caller does not choose one.
     *
     * ### Why this is not 1.0
     *
     * Equal weights are the textbook default and they are wrong for *this* pair of rankers, which
     * the Phase 0b benchmark measured on the real 22-book corpus (`.dbg/phase0/phase0b-retrieval.md`):
     *
     * | ranker | recall@5 | recall@10 |
     * |---|---|---|
     * | MiniLM-L6-v2 | 30% | 40% |
     * | RRF (BM25-AND + MiniLM), equal weights | 40% | 50% |
     *
     * Overall that is an improvement, and it is why fusion exists. Split by query kind it is not:
     *
     * | ranker | paraphrase recall@10 | lexical recall@10 |
     * |---|---|---|
     * | MiniLM-L6-v2 | **43%** | 33% |
     * | RRF (BM25-AND + MiniLM), equal weights | **29%** | 100% |
     * | RRF (BM25-OR + MiniLM), equal weights | 36% | 67% |
     *
     * So fusion *loses a third of the semantic ranker's paraphrase recall* while winning the lexical
     * case outright. That is the shape of the defect: RRF compares **ranks**, and rank 1 of a lexical
     * list is worth exactly rank 1 of a semantic one — but for a vague query those are not equal
     * achievements. BM25 always returns *something* (a long natural-language query still shares a
     * common noun with hundreds of chapters), so its top entry is a weak match promoted to the same
     * footing as a strong one. A chapter that BM25 happened to rank first and the embedder ranked
     * twentieth then outranks the embedder's own best answer.
     *
     * Down-weighting the lexical list fixes exactly that asymmetry without discarding the lexical
     * evidence: a passage *both* rankers like still wins (it collects from both lists), a strong
     * lexical match is still returned, and a bare lexical coincidence can no longer displace a
     * confident semantic hit. The value is a measurement, not a preference — see
     * `RetrievalQualityBenchmark`, which reports both weights side by side so this number can be
     * re-derived rather than argued about.
     */
    const val DEFAULT_LEXICAL_WEIGHT = 0.5

    /**
     * @param rankings ordered result lists, best first; ids may repeat across lists
     * @param limit max fused results to return
     * @param weights per-list multiplier, positionally matched to [rankings]; missing entries are
     *   1.0. A list weighted 0 contributes nothing, which is how a caller disables one ranker.
     * @return fused ids, best first, with their RRF score
     */
    fun fuse(
        rankings: List<List<String>>,
        limit: Int = Int.MAX_VALUE,
        k: Int = DEFAULT_K,
        weights: List<Double> = emptyList(),
    ): List<VectorHit> {
        require(k > 0) { "k must be positive" }
        if (rankings.isEmpty()) return emptyList()
        val scores = HashMap<String, Double>()
        rankings.forEachIndexed { listIndex, ranking ->
            val weight = weights.getOrNull(listIndex) ?: 1.0
            if (weight <= 0.0) return@forEachIndexed
            // A ranking that repeats an id would otherwise double-count it.
            val seen = HashSet<String>()
            ranking.forEachIndexed { rank, id ->
                if (!seen.add(id)) return@forEachIndexed
                scores[id] = (scores[id] ?: 0.0) + weight / (k + rank + 1)
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { VectorHit(it.key, it.value.toFloat()) }
    }

    /**
     * Convenience for the common two-list case. [bm25] and [semantic] are ordered ids.
     *
     * @param lexicalWeight weight on [bm25]; the semantic list carries 1.0. See
     *   [DEFAULT_LEXICAL_WEIGHT] for why the default is below one.
     */
    fun fusePair(
        bm25: List<String>,
        semantic: List<String>,
        limit: Int = Int.MAX_VALUE,
        k: Int = DEFAULT_K,
        lexicalWeight: Double = DEFAULT_LEXICAL_WEIGHT,
    ): List<VectorHit> = fuse(listOf(bm25, semantic), limit, k, listOf(lexicalWeight, 1.0))
}

/** Euclidean norm; used by callers that need to check a vector before normalising it. */
fun l2Norm(vector: FloatArray): Float {
    var sum = 0.0
    for (v in vector) sum += v.toDouble() * v.toDouble()
    return sqrt(sum).toFloat()
}
