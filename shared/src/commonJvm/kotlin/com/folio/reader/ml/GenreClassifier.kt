package com.folio.reader.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Where a book's genre came from — metadata is trusted absolutely, inference is a best guess. */
enum class GenreSource { METADATA, INFERRED }

/** One resolved genre for a book, with the confidence and provenance the store keeps. */
data class GenreAssignment(
    val genre: BroadGenre,
    /** Winner's raw cosine for [GenreSource.INFERRED]; 1.0 for [GenreSource.METADATA]. */
    val confidence: Float,
    val source: GenreSource,
)

/**
 * Resolves a book's [BroadGenre] — metadata first, a calibrated zero-shot embedding fallback second.
 *
 * This is the genre analogue of [ZeroShotTagger], but the naive "embed one label string, take the
 * argmax cosine above a fixed threshold" recipe fails on whole-book vectors: the embedder's
 * anisotropy clusters every cosine in a narrow band, a single-word anchor is weak, and a fixed
 * absolute threshold means something different on every model (MiniLM, Arctic-Embed-S, E5). Three
 * things fix that here, and none of them hard-codes a model id, a dimensionality or an absolute
 * cosine — they self-tune to whichever model is selected:
 *
 * 1. **Multi-prompt prototypes.** Each [BroadGenre] is represented by the *mean* of several exemplar
 *    phrases ([BroadGenre.prototypePhrases]), not one label — a far more stable anchor. Embedded
 *    once per model and cached.
 * 2. **Calibration.** Before ranking, each genre's cosine is de-biased by that genre's library-wide
 *    mean similarity (see [calibrationBias]) so a genre that is generically close to *everything*
 *    stops always winning; the scores are then centred per book so the decision is in
 *    model-independent relative units.
 * 3. **Relative-margin gating.** The winner must beat the runner-up by [margin] in those centred
 *    units — never an absolute cosine. Below it, the book is left **Unclassified** (null) rather
 *    than forced onto a confident-looking wrong shelf.
 *
 * Metadata (`<dc:subject>` / BISAC) still wins outright when present — see [canonicalize].
 * The book vector is supplied by the caller (the mean of its already-stored chunk vectors), never
 * re-embedded here, so no large session is opened per book — the memory-conscious choice the
 * roll-up's OOM history demands. Only the ≈20 fixed prototypes are embedded, once, and cached.
 */
class GenreClassifier(
    private val embedderFactory: EmbedderFactory,
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
    private val margin: Float = DEFAULT_MARGIN,
) {
    val model: EmbeddingModel get() = embedderFactory.model

    @Volatile
    private var cachedTaxonomy: Map<BroadGenre, FloatArray>? = null

    /** True when the embedding model is actually on disk (so the fallback can run). */
    suspend fun isAvailable(): Boolean = withContext(dispatcher) {
        embedderFactory.create()?.let { runCatching { it.close() }; true } ?: false
    }

    /** Metadata-only resolution; pure, no model. */
    fun canonicalize(subjects: List<String>): BroadGenre? = GenreTaxonomy.canonicalize(subjects)

    /**
     * Embeds every genre's prototype phrases once (as queries), averages each genre's phrases into a
     * single unit prototype, and caches the result for the model in force. Returns null when no
     * model is on disk. The vectors live in raw (un-whitened) model space, the same space the stored
     * chunk vectors occupy, so a book vector can be ranked against them.
     */
    suspend fun taxonomyVectors(): Map<BroadGenre, FloatArray>? = withContext(dispatcher) {
        cachedTaxonomy?.let { return@withContext it }
        val embedder = embedderFactory.create() ?: return@withContext null
        try {
            val labels = GenreTaxonomy.all
            val phrasesPerGenre = labels.map { it.prototypePhrases }
            val flat = phrasesPerGenre.flatten()
            val vecs = embedder.embed(flat, EmbedKind.QUERY)
            var idx = 0
            val map = LinkedHashMap<BroadGenre, FloatArray>()
            labels.forEachIndexed { i, g ->
                val count = phrasesPerGenre[i].size
                val slice = ArrayList<FloatArray>(count)
                for (k in 0 until count) vecs.getOrNull(idx + k)?.let { slice.add(it) }
                idx += count
                meanNormalize(slice)?.let { map[g] = it }
            }
            if (map.isNotEmpty()) cachedTaxonomy = map
            map
        } finally {
            runCatching { embedder.close() }
        }
    }

    /**
     * The library's per-genre "cone bias": each genre's mean cosine over a sample of book vectors.
     * Subtracting it in [rank] cancels the anisotropy that makes some genres score high against
     * *every* book, so the argmax becomes a real ranking rather than a fixed favourite. Pure and
     * computed from the active model's own distribution, so it self-tunes per model, per run.
     */
    fun calibrationBias(
        bookVectors: List<FloatArray>,
        taxonomyVectors: Map<BroadGenre, FloatArray>,
    ): Map<BroadGenre, Float> {
        if (bookVectors.isEmpty() || taxonomyVectors.isEmpty()) return emptyMap()
        val sums = HashMap<BroadGenre, Double>(taxonomyVectors.size)
        var count = 0
        for (bv in bookVectors) {
            if (bv.isEmpty()) continue
            count++
            for ((g, v) in taxonomyVectors) sums[g] = (sums[g] ?: 0.0) + cosineSimilarity(bv, v)
        }
        if (count == 0) return emptyMap()
        return sums.mapValues { (it.value / count).toFloat() }
    }

    /**
     * Ranks [bookVector] against the (pre-embedded) taxonomy and returns the calibrated, margin-gated
     * argmax genre, or null when the ranking is not confident enough. [bias] (from [calibrationBias])
     * is subtracted per genre before ranking; pass it during a library backfill so the calibration
     * bites, or null for a single book. Pure — no model, no IO — so it is exhaustively testable with
     * a fake embedder. [bookVector] must be in the same space as [taxonomyVectors] (raw model space).
     */
    fun rank(
        bookVector: FloatArray,
        taxonomyVectors: Map<BroadGenre, FloatArray>,
        bias: Map<BroadGenre, Float>? = null,
    ): GenreAssignment? {
        if (bookVector.isEmpty() || taxonomyVectors.isEmpty()) return null
        // Raw cosine, de-biased per genre. Iterate in taxonomy order so ties resolve deterministically.
        val adjusted = ArrayList<Pair<BroadGenre, Float>>(taxonomyVectors.size)
        for (g in GenreTaxonomy.all) {
            val v = taxonomyVectors[g] ?: continue
            val cos = cosineSimilarity(bookVector, v)
            adjusted.add(g to (cos - (bias?.get(g) ?: 0f)))
        }
        if (adjusted.isEmpty()) return null
        // Centre per book so the decision is in relative units, then gate on the top-vs-runner-up
        // margin. A book near-equidistant to several genres yields a small margin → Unclassified.
        val mean = adjusted.map { it.second }.average().toFloat()
        val centred = adjusted.map { it.first to (it.second - mean) }.sortedByDescending { it.second }
        val top = centred[0]
        val runnerUp = centred.getOrNull(1)?.second ?: 0f
        if (top.second <= 0f || (top.second - runnerUp) < margin) return null
        val rawCos = cosineSimilarity(bookVector, taxonomyVectors.getValue(top.first))
        return GenreAssignment(top.first, rawCos, GenreSource.INFERRED)
    }

    /**
     * Full resolution for one book: metadata first, then the calibrated zero-shot fallback against
     * [bookVector]. Returns null when neither yields a genre. [bias] is optional library calibration.
     */
    fun classify(
        subjects: List<String>,
        bookVector: FloatArray?,
        taxonomyVectors: Map<BroadGenre, FloatArray>?,
        bias: Map<BroadGenre, Float>? = null,
    ): GenreAssignment? {
        canonicalize(subjects)?.let { return GenreAssignment(it, 1f, GenreSource.METADATA) }
        if (bookVector == null || bookVector.isEmpty() || taxonomyVectors.isNullOrEmpty()) return null
        return rank(bookVector, taxonomyVectors, bias)
    }

    /** Mean of a set of unit vectors, re-normalised to a unit prototype. Null when empty/degenerate. */
    private fun meanNormalize(vecs: List<FloatArray>): FloatArray? {
        if (vecs.isEmpty()) return null
        val dims = vecs.first().size
        val out = FloatArray(dims)
        for (v in vecs) if (v.size == dims) for (i in out.indices) out[i] += v[i]
        val normed = out.l2Normalize()
        return if (normed.any { it != 0f }) normed else null
    }

    companion object {
        /**
         * Minimum centred-cosine margin between the top genre and the runner-up for an inference to
         * stand. Relative (not an absolute cosine), so it means the same thing on every model: the
         * winner has to be *clearly* ahead, otherwise the book is left Unclassified. Bumping this,
         * or any change to the prototypes/calibration above, is a classifier-algorithm change — see
         * [GenreClassificationService.CLASSIFIER_VERSION].
         */
        const val DEFAULT_MARGIN = 0.04f
    }
}
