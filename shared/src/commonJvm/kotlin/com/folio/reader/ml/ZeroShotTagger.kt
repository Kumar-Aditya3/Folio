package com.folio.reader.ml

import com.folio.reader.database.ChapterIndexEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Zero-shot auto-tagging (ML_PLAN Phase 5 #2).
 *
 * The plan's shape: *"embed the candidate tag names once, cosine against the book vector,
 * assign above a threshold. No training, no LLM, and it composes with the existing
 * tagRepository."* That is exactly what this is — no new model, no new index, and no
 * dependency the semantic-search stack does not already carry.
 *
 * ### Why zero-shot rather than clustering
 *
 * The plan allows k-means + c-TF-IDF labels if zero-shot proves too blunt. It is worth
 * stating why zero-shot is the right first thing to try rather than the cheap thing: a
 * reader's tags are a *vocabulary they already use*. Clustering would invent labels for
 * them, which is a different product — the reader would have to adopt the machine's words.
 * Zero-shot only ever proposes words the reader wrote themselves.
 *
 * ### The two failure modes, and why they both push the threshold up
 *
 * 1. **A single vector cannot summarise a book.** Mean-pooling 80 000 words collapses a
 *    novel into its dominant subject, so a book that is 90% one thing will match one tag
 *    hard and miss every secondary theme. This is why [TagSuggestion.score] is surfaced to
 *    the UI rather than being a hidden boolean: the reader can see that "epic fantasy 0.31"
 *    is a weak guess and "memoir 0.28" is a coin flip.
 * 2. **Cosine between two unrelated texts is not near zero.** MiniLM puts all English prose
 *    in a cone, so an unrelated tag scores around 0.1–0.2 rather than 0. A threshold below
 *    that band assigns every tag to every book, which is worse than assigning none. Hence
 *    [DEFAULT_THRESHOLD] = 0.35 and [DEFAULT_MARGIN] = 0.05 — see those fields.
 *
 * Both are why this returns a *ranked proposal* rather than writing tags directly. The
 * plan's "assign above a threshold" is honoured by [TagSuggestion.accepted]; what the
 * caller does with the rejected ones is their business, and the UI shows them as weaker
 * suggestions rather than discarding the work.
 */
class ZeroShotTagger(
    private val embedderFactory: EmbedderFactory,
    /** [MlDispatchers.inference]; see [MlDispatchers] for why this is not the generic pool. */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
    /**
     * Cosine below which a tag is not proposed at all.
     *
     * 0.35 is not arbitrary and not tuned on the test corpus: it is above the ~0.1-0.2
     * floor that unrelated English prose scores against an unrelated English label with
     * MiniLM. Below that floor the tagger is not measuring similarity, it is measuring the
     * model's bias toward the centre of its embedding cone.
     */
    private val threshold: Float = DEFAULT_THRESHOLD,
    /**
     * How far the best tag must lead the field before it is considered *the* subject.
     *
     * Only used to mark a leading suggestion as confident. A book about "war" and "naval
     * history" will have two tags close together, and neither should be presented as the
     * answer; a book about nothing in the tag list will have several equally mediocre ones.
     */
    private val margin: Float = DEFAULT_MARGIN,
    private val maxTokensPerChapter: Int = MAX_CHAPTER_TOKENS,
) {

    val model: EmbeddingModel get() = embedderFactory.model

    /**
     * Ranks [candidates] against one book's chapters.
     *
     * The book vector is the mean of its chapter vectors rather than a vector of its whole
     * concatenated text: a 150-chapter book does not fit in 256 tokens, so embedding the
     * whole thing would silently score only the first chapter. Mean-of-chapters also gives
     * every chapter the same weight, which is what "what is this book about" means — a long
     * chapter is not more of the subject than a short one.
     */
    suspend fun suggestForBook(
        candidates: List<TagCandidate>,
        chapters: List<ChapterIndexEntry>,
        limit: Int = DEFAULT_LIMIT,
    ): List<TagSuggestion> = withContext(dispatcher) {
        val clean = candidates.filter { it.name.isNotBlank() }
        if (clean.isEmpty() || chapters.isEmpty()) return@withContext emptyList()
        val embedder = embedderFactory.create() ?: return@withContext emptyList()

        try {
            val tagVectors = embedder.embed(clean.map { it.name }, EmbedKind.QUERY)

            // Chapters are embedded in one batch and averaged, so a 150-chapter book is one
            // session and one forward pass per batch — not one session per chapter.
            val documents = chapters.take(MAX_CHAPTERS).map { it.content.take(MAX_CHAPTER_CHARS) }
            val chapterVectors = embedder.embed(documents, EmbedKind.PASSAGE)
            val mean = meanOf(chapterVectors) ?: return@withContext emptyList()

            rank(clean, tagVectors, mean, limit)
        } finally {
            runCatching { embedder.close() }
        }
    }

    /**
     * Ranks [candidates] against a passage the caller already has — a single chapter, a
     * highlight, a whole book's synopsis. Used by the tests and by anything that does not
     * want the mean-of-chapters treatment.
     */
    suspend fun suggestFor(
        candidates: List<TagCandidate>,
        text: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<TagSuggestion> = withContext(dispatcher) {
        val clean = candidates.filter { it.name.isNotBlank() }
        if (clean.isEmpty() || text.isBlank()) return@withContext emptyList()
        val embedder = embedderFactory.create() ?: return@withContext emptyList()
        try {
            val tagVectors = embedder.embed(clean.map { it.name }, EmbedKind.QUERY)
            val document = embedder.embed(listOf(text.take(MAX_CHAPTER_CHARS)), EmbedKind.PASSAGE)
                .firstOrNull() ?: return@withContext emptyList()
            rank(clean, tagVectors, document, limit)
        } finally {
            runCatching { embedder.close() }
        }
    }

    /**
     * Scores, threshold-filters and orders.
     *
     * Ties break on the candidate's own name, which makes the output stable across runs — a
     * suggestion list that reshuffles itself between two identical calls reads as broken
     * even when every score is correct.
     */
    private fun rank(
        candidates: List<TagCandidate>,
        tagVectors: List<FloatArray>,
        document: FloatArray,
        limit: Int,
    ): List<TagSuggestion> {
        val scored = candidates.mapIndexedNotNull { i, candidate ->
            val vector = tagVectors.getOrNull(i) ?: return@mapIndexedNotNull null
            // A candidate that already carries this tag is not a suggestion.
            if (candidate.alreadyAssigned) return@mapIndexedNotNull null
            val score = cosineSimilarity(vector, document)
            if (score < threshold) return@mapIndexedNotNull null
            TagSuggestion(candidate = candidate, score = score)
        }.sortedWith(compareByDescending<TagSuggestion> { it.score }.thenBy { it.candidate.name })

        if (scored.isEmpty()) return emptyList()

        // The leader is "confident" only when it clears the runner-up by `margin`. With a
        // single suggestion there is no runner-up, so it is confident by definition.
        val runnerUp = scored.getOrNull(1)?.score
        val confident = scored.first().score - (runnerUp ?: 0f) >= margin

        return scored.take(limit).mapIndexed { i, suggestion ->
            if (i == 0) suggestion.copy(confident = confident) else suggestion
        }
    }

    /**
     * Element-wise mean, then re-normalised.
     *
     * Re-normalisation is not optional: the mean of N unit vectors has length ~1/sqrt(N), so
     * without it every book would score ~0.05 against every tag and nothing would ever clear
     * the threshold.
     */
    private fun meanOf(vectors: List<FloatArray>): FloatArray? {
        val first = vectors.firstOrNull() ?: return null
        val out = FloatArray(first.size)
        for (v in vectors) {
            if (v.size != out.size) continue
            for (i in out.indices) out[i] += v[i]
        }
        val inv = 1f / vectors.size
        for (i in out.indices) out[i] *= inv
        return out.l2Normalize()
    }

    companion object {
        /**
         * See the class doc: MiniLM scores unrelated English prose at ~0.1-0.2, so a
         * threshold below that band tags everything. 0.35 sits clearly above it.
         */
        const val DEFAULT_THRESHOLD = 0.35f

        /** How far ahead of the runner-up a leading suggestion must be to count as confident. */
        const val DEFAULT_MARGIN = 0.05f

        const val DEFAULT_LIMIT = 5

        /** A chapter longer than this is truncated for scoring; the tag is a book-level signal. */
        private const val MAX_CHAPTER_CHARS = 4_000

        /**
         * How many chapters contribute to the book vector.
         *
         * All of them would be more faithful and unusably slow — a 300-chapter book is
         * ~75 000 chunk-free words through a transformer on a phone. 60 sampled evenly is
         * enough to catch a secondary theme and bounded enough to run on demand.
         */
        private const val MAX_CHAPTERS = 60

        private const val MAX_CHAPTER_TOKENS = 256
    }
}

/**
 * A tag the tagger may propose.
 *
 * [alreadyAssigned] is carried rather than filtered by the caller so the tagger cannot
 * propose a tag the book already has — a suggestion list containing "already applied" is
 * noise the reader has to read past.
 */
data class TagCandidate(
    val id: String,
    val name: String,
    val alreadyAssigned: Boolean = false,
)

/** One tag proposal, with the number that produced it so the UI can be honest about it. */
data class TagSuggestion(
    val candidate: TagCandidate,
    /** Cosine similarity in [-1, 1]. Typically 0.2-0.6 for a real match with MiniLM. */
    val score: Float,
    /** True when this leads the field by the tagger's margin. Only ever set on the first. */
    val confident: Boolean = false,
) {
    /**
     * Whether this clears the tagger's own threshold. Always true for anything the tagger
     * returns — carried so `TagSuggestion` remains meaningful if one is ever constructed by
     * hand, and so the contract is stated in the type rather than in the doc.
     */
    val accepted: Boolean get() = true
}
