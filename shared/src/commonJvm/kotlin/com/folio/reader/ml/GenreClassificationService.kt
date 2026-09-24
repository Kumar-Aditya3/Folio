package com.folio.reader.ml

import com.folio.reader.database.ChunkRepository
import com.folio.reader.database.GenreRepository
import com.folio.reader.database.StoredGenre
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Classifies embedded books into a [BroadGenre] and persists the result, so the Atlas roll-up can
 * name its communities without ever opening an ONNX session itself.
 *
 * This is the genre counterpart of [AutoTaggerService]: the pure ranking lives in [GenreClassifier]
 * and this half knows where the inputs come from and where the answer goes. Two decisions matter:
 *
 * - **The book vector is the mean of its already-stored chunk vectors**, not a fresh embedding of
 *   its chapters. The backfill has embedded every chunk; re-embedding whole books for genre would
 *   be a second full pass and a second peak-memory event. Only the ≈20 fixed taxonomy labels are
 *   embedded (once, cached in the classifier). The stored vectors and the taxonomy vectors are both
 *   raw model space, so the cosine is meaningful.
 * - **A book that cannot be classified now is marked resolved-as-unknown**, not left missing. Left
 *   missing, an unclassifiable book would be selected first on every backfill and block the books
 *   behind it — the same trap an unchunkable chapter once was. A [SOURCE_NONE] row keeps the pass
 *   moving; the community simply falls back to a c-TF-IDF phrase or "Mixed".
 */
class GenreClassificationService(
    private val classifier: GenreClassifier,
    private val chunkRepository: ChunkRepository,
    private val genreRepository: GenreRepository,
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
) {
    val model: EmbeddingModel get() = classifier.model

    /** True when the embedding model is on disk, so the inference fallback can run. */
    suspend fun isAvailable(): Boolean = classifier.isAvailable()

    /**
     * Classifies and stores one book's genre if it can, returning the assignment (or null). Used by
     * the import path so a freshly imported, freshly embedded book gets a genre immediately.
     */
    suspend fun classifyBook(bookId: String): GenreAssignment? = withContext(dispatcher) {
        val subjects = runCatching { genreRepository.subjectsFor(bookId) }.getOrDefault(emptyList())
        classifier.canonicalize(subjects)?.let { genre ->
            val assignment = GenreAssignment(genre, 1f, GenreSource.METADATA)
            store(bookId, assignment)
            return@withContext assignment
        }
        val taxonomy = classifier.taxonomyVectors() ?: return@withContext null // no model → retry later
        val vector = bookVector(bookId) ?: return@withContext null            // not embedded → retry later
        val inferred = classifier.rank(vector, taxonomy)
        if (inferred == null) {
            storeUnresolved(bookId) // attempted, genuinely below threshold — don't retry forever
            return@withContext null
        }
        store(bookId, inferred)
        inferred
    }

    /**
     * Classifies up to [limit] embedded books that have no genre row for the current model, and
     * returns how many rows it wrote (including resolved-as-unknown). The backfill worker calls this
     * after its embedding pass; it is resumable and idempotent, like the embedding backfill itself.
     *
     * Metadata-resolvable books are stored immediately. The rest are gathered with their book
     * vectors so a single **calibration** pass over this batch can de-bias each genre's cosines
     * (see [GenreClassifier.calibrationBias]) before the margin-gated rank — that calibration is the
     * accuracy win, and computing it from the run's own book vectors keeps it model-agnostic.
     */
    suspend fun backfillMissing(limit: Int = DEFAULT_BACKFILL_LIMIT): Int = withContext(dispatcher) {
        val ids = runCatching { genreRepository.booksMissingGenre(model.id, limit) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return@withContext 0
        var taxonomy: Map<BroadGenre, FloatArray>? = null
        var written = 0
        val needInference = ArrayList<Pair<String, FloatArray>>()
        for (bookId in ids) {
            val subjects = runCatching { genreRepository.subjectsFor(bookId) }.getOrDefault(emptyList())
            val metadata = classifier.canonicalize(subjects)
            if (metadata != null) {
                store(bookId, GenreAssignment(metadata, 1f, GenreSource.METADATA))
                written++
                continue
            }
            if (taxonomy == null) taxonomy = classifier.taxonomyVectors()
            if (taxonomy == null) break // no model on disk — nothing more we can do this run
            val vector = bookVector(bookId) ?: continue // not embedded yet — retry a later run
            needInference.add(bookId to vector)
        }
        val tax = taxonomy
        if (tax != null && needInference.isNotEmpty()) {
            val bias = classifier.calibrationBias(needInference.map { it.second }, tax)
            for ((bookId, vector) in needInference) {
                val inferred = classifier.rank(vector, tax, bias)
                if (inferred != null) store(bookId, inferred) else storeUnresolved(bookId)
                written++
            }
        }
        written
    }

    /** Clears every stored genre for the current model — used to force a re-derivation. */
    suspend fun clearForModel(): Int =
        runCatching { genreRepository.clearForModel(model.id) }.getOrDefault(0)

    /**
     * Mean of a book's stored chunk vectors (raw model space), re-normalised, as a **trimmed** mean:
     * the chunks furthest from the raw mean are dropped so one off-topic section (a foreword, an
     * index, an appendix) can't drag the whole-book vector off its subject. Deterministic. Null when
     * the book has no stored vectors.
     */
    private suspend fun bookVector(bookId: String): FloatArray? {
        val rows = runCatching { chunkRepository.loadVectorMetadata(model.id, model.dims, bookId) }
            .getOrDefault(emptyList())
        val vecs = rows.mapNotNull { (_, v) -> v.takeIf { it.size == model.dims } }
        if (vecs.isEmpty()) return null
        val kept = if (vecs.size >= 5) {
            val raw = meanOf(vecs)
            // Closest-to-mean first; keep the inner ~80% so outliers don't dominate. Stable sort ⇒
            // deterministic on ties.
            vecs.sortedByDescending { cosineSimilarity(it, raw) }
                .take((vecs.size * 0.8f).toInt().coerceAtLeast(1))
        } else {
            vecs
        }
        return meanOf(kept).l2Normalize()
    }

    /** Component-wise mean of a set of vectors (not normalised). */
    private fun meanOf(vecs: List<FloatArray>): FloatArray {
        val out = FloatArray(model.dims)
        for (v in vecs) if (v.size == out.size) for (i in out.indices) out[i] += v[i]
        val n = vecs.size.coerceAtLeast(1)
        for (i in out.indices) out[i] /= n
        return out
    }

    private suspend fun store(bookId: String, assignment: GenreAssignment) {
        runCatching {
            genreRepository.upsertGenre(
                StoredGenre(
                    bookId = bookId,
                    genre = assignment.genre.name,
                    confidence = assignment.confidence,
                    source = assignment.source.name,
                    modelId = model.id,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun storeUnresolved(bookId: String) {
        runCatching {
            genreRepository.upsertGenre(
                StoredGenre(
                    bookId = bookId,
                    genre = "",
                    confidence = 0f,
                    source = SOURCE_NONE,
                    modelId = model.id,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        /** Source string for a resolved-but-unclassifiable book (keeps it out of the work list). */
        const val SOURCE_NONE = "NONE"

        /** How many books one genre backfill pass classifies before yielding. */
        const val DEFAULT_BACKFILL_LIMIT = 200

        /**
         * Bumped whenever the classifier's *algorithm* changes (multi-prompt prototypes, calibration,
         * margin gating), so the backfill worker can clear and re-derive stored genres once — rows
         * are keyed by model, not algorithm, so without a version they would otherwise persist from
         * the old classifier. v2 introduced calibrated multi-prompt prototypes + relative-margin
         * gating (replacing the flat-threshold single-label argmax).
         */
        const val CLASSIFIER_VERSION = 2
    }
}
