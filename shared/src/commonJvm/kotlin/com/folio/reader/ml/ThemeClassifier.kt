package com.folio.reader.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Ranks a book against the fixed [NarrativeTheme] vocabulary — the theme analogue of
 * [GenreClassifier], one tier finer than a broad genre.
 *
 * The recipe is deliberately the same as the genre classifier's, because the same failure modes
 * apply to whole-book vectors (anisotropy clusters every cosine; a fixed absolute threshold means
 * something different on every model): **multi-prompt prototypes** (each theme is the mean of a few
 * exemplar phrases), **calibration** (subtract each theme's library-wide mean cosine), and
 * **relative-margin gating** (the winner must clearly beat the runner-up, else no theme is assigned
 * rather than a confident-looking wrong one). None of it hard-codes a model id or an absolute cosine.
 *
 * Unlike the genre classifier it also exposes [embed]: the accurate input for a theme is the book's
 * *blurb* (a concentrated statement of what it is about), which sits far closer to a theme phrase
 * than the mean of its narrative prose does. The caller embeds the blurb with this and ranks it;
 * where there is no blurb it falls back to the stored chunk-mean vector.
 */
class ThemeClassifier(
    private val embedderFactory: EmbedderFactory,
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
    private val margin: Float = DEFAULT_MARGIN,
) {
    val model: EmbeddingModel get() = embedderFactory.model

    @Volatile
    private var cachedThemes: Map<NarrativeTheme, FloatArray>? = null

    suspend fun isAvailable(): Boolean = withContext(dispatcher) {
        embedderFactory.create()?.let { runCatching { it.close() }; true } ?: false
    }

    /**
     * Embeds each theme's prototype phrases once (as queries — the same asymmetry the genre
     * classifier uses against passage-embedded book text), averages each theme's phrases into a unit
     * prototype, and caches the result for the model in force. Null when no model is on disk.
     */
    suspend fun themeVectors(): Map<NarrativeTheme, FloatArray>? = withContext(dispatcher) {
        cachedThemes?.let { return@withContext it }
        val embedder = embedderFactory.create() ?: return@withContext null
        try {
            val themes = ThemeTaxonomy.all
            val phrasesPer = themes.map { it.prototypePhrases }
            val flat = phrasesPer.flatten()
            val vecs = embedder.embed(flat, EmbedKind.QUERY)
            var idx = 0
            val map = LinkedHashMap<NarrativeTheme, FloatArray>()
            themes.forEachIndexed { i, t ->
                val count = phrasesPer[i].size
                val slice = ArrayList<FloatArray>(count)
                for (k in 0 until count) vecs.getOrNull(idx + k)?.let { slice.add(it) }
                idx += count
                meanNormalize(slice)?.let { map[t] = it }
            }
            if (map.isNotEmpty()) cachedThemes = map
            map
        } finally {
            runCatching { embedder.close() }
        }
    }

    /**
     * Embeds arbitrary text (book blurbs) as passages, in one session, so a batch of books can be
     * theme-classified from their descriptions. Returns a null slot for anything that failed to
     * embed. Empty input, or no model on disk, yields all-null / empty.
     */
    suspend fun embed(texts: List<String>): List<FloatArray?> = withContext(dispatcher) {
        if (texts.isEmpty()) return@withContext emptyList()
        val embedder = embedderFactory.create() ?: return@withContext texts.map { null }
        try {
            val vecs = embedder.embed(texts, EmbedKind.PASSAGE)
            texts.indices.map { vecs.getOrNull(it) }
        } catch (_: Throwable) {
            texts.map { null }
        } finally {
            runCatching { embedder.close() }
        }
    }

    /** Each theme's mean cosine over a sample of book vectors, to de-bias the ranking. */
    fun calibrationBias(
        bookVectors: List<FloatArray>,
        themeVectors: Map<NarrativeTheme, FloatArray>,
    ): Map<NarrativeTheme, Float> {
        if (bookVectors.isEmpty() || themeVectors.isEmpty()) return emptyMap()
        val sums = HashMap<NarrativeTheme, Double>(themeVectors.size)
        var count = 0
        for (bv in bookVectors) {
            if (bv.isEmpty()) continue
            count++
            for ((t, v) in themeVectors) sums[t] = (sums[t] ?: 0.0) + cosineSimilarity(bv, v)
        }
        if (count == 0) return emptyMap()
        return sums.mapValues { (it.value / count).toFloat() }
    }

    /**
     * The calibrated, margin-gated top themes for [bookVector] (strongest first, up to [topN]).
     * Empty when the ranking is not confident — the winner must clear the runner-up by [margin] in
     * per-book-centred units — so the caller shows a theme only when there is a real one. Themes
     * after the first are included only while they stay above the book's mean similarity.
     */
    fun rankTop(
        bookVector: FloatArray,
        themeVectors: Map<NarrativeTheme, FloatArray>,
        bias: Map<NarrativeTheme, Float>? = null,
        topN: Int = 2,
    ): List<NarrativeTheme> {
        if (bookVector.isEmpty() || themeVectors.isEmpty()) return emptyList()
        val adjusted = ThemeTaxonomy.all.mapNotNull { t ->
            val v = themeVectors[t] ?: return@mapNotNull null
            t to (cosineSimilarity(bookVector, v) - (bias?.get(t) ?: 0f))
        }
        if (adjusted.isEmpty()) return emptyList()
        val mean = adjusted.map { it.second }.average().toFloat()
        val centred = adjusted.map { it.first to (it.second - mean) }.sortedByDescending { it.second }
        val top = centred[0]
        val runnerUp = centred.getOrNull(1)?.second ?: 0f
        if (top.second <= 0f || (top.second - runnerUp) < margin) return emptyList()
        return centred.asSequence()
            .filter { it.second > 0f }
            .take(topN.coerceAtLeast(1))
            .map { it.first }
            .toList()
    }

    private fun meanNormalize(vecs: List<FloatArray>): FloatArray? {
        if (vecs.isEmpty()) return null
        val dims = vecs.first().size
        val out = FloatArray(dims)
        for (v in vecs) if (v.size == dims) for (i in out.indices) out[i] += v[i]
        val normed = out.l2Normalize()
        return if (normed.any { it != 0f }) normed else null
    }

    companion object {
        /** Relative-margin gate; a touch above the genre gate because there are more, closer themes. */
        const val DEFAULT_MARGIN = 0.05f
    }
}
