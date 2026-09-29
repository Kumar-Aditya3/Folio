package com.folio.reader.ml

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ChunkRepository
import com.folio.reader.database.GenreRepository
import com.folio.reader.database.StoredGenre
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** One book gathered for a classification pass: its metadata genre (if any), vector and blurb. */
private data class BookEntry(
    val bookId: String,
    val metadataGenre: BroadGenre?,
    val vector: FloatArray?,
    val description: String?,
)

/**
 * Classifies embedded books into a [BroadGenre] **and** their dominant [NarrativeTheme]s, persisting
 * both, so the Atlas roll-up can name and colour its communities without ever opening an ONNX
 * session itself.
 *
 * This is the genre counterpart of [AutoTaggerService]: the pure ranking lives in [GenreClassifier]
 * / [ThemeClassifier] and this half knows where the inputs come from and where the answer goes. Two
 * decisions matter:
 *
 * - **The genre book vector is the mean of a book's already-stored chunk vectors**, not a fresh
 *   embedding of its chapters — the backfill has embedded every chunk, so re-embedding whole books
 *   for genre would be a second full pass and a second peak-memory event. Only the fixed prototypes
 *   are embedded (once, cached).
 * - **Themes prefer the book's blurb.** A description ("a tale of court intrigue and betrayal") sits
 *   far closer to a theme phrase than the mean of the book's narrative prose does, so themes are
 *   ranked from the embedded description where one exists, falling back to the chunk-mean vector
 *   otherwise. The blurbs of one backfill batch are embedded together, once.
 *
 * A book that cannot be classified is marked resolved-as-unknown rather than left missing, so an
 * unclassifiable book cannot block the work list forever.
 */
class GenreClassificationService(
    private val classifier: GenreClassifier,
    private val chunkRepository: ChunkRepository,
    private val genreRepository: GenreRepository,
    private val bookRepository: BookRepository,
    private val themeClassifier: ThemeClassifier? = null,
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
) {
    val model: EmbeddingModel get() = classifier.model

    /** True when the embedding model is on disk, so the inference fallback can run. */
    suspend fun isAvailable(): Boolean = classifier.isAvailable()

    /**
     * Classifies and stores one book's genre + themes if it can, returning the genre assignment (or
     * null). Used by the import path so a freshly imported, freshly embedded book is decorated
     * immediately.
     */
    suspend fun classifyBook(bookId: String): GenreAssignment? = withContext(dispatcher) {
        val subjects = runCatching { genreRepository.subjectsFor(bookId) }.getOrDefault(emptyList())
        val metadata = classifier.canonicalize(subjects)
        val description = runCatching { bookRepository.getBook(bookId)?.description }.getOrNull()
        val needVector = metadata == null || description.isNullOrBlank()
        val vector = if (needVector) bookVector(bookId) else null

        val themes = runCatching { themesFor(listOf(BookEntry(bookId, metadata, vector, description))) }
            .getOrDefault(emptyMap())[bookId].orEmpty()

        if (metadata != null) {
            val assignment = GenreAssignment(metadata, 1f, GenreSource.METADATA)
            store(bookId, assignment, themes)
            return@withContext assignment
        }
        val taxonomy = classifier.taxonomyVectors() ?: return@withContext null // no model → retry later
        val v = vector ?: return@withContext null                              // not embedded → retry later
        val inferred = classifier.rank(v, taxonomy)
        if (inferred == null) {
            storeUnresolved(bookId, themes) // attempted, genuinely below threshold — don't retry forever
            return@withContext null
        }
        store(bookId, inferred, themes)
        inferred
    }

    /**
     * Classifies up to [limit] embedded books that have no genre row for the current model, writing
     * genre + themes, and returns how many rows it wrote. The backfill worker calls this after its
     * embedding pass; it is resumable and idempotent, like the embedding backfill itself.
     *
     * Gathers each book's vector and blurb first so one **calibration** pass over the batch can
     * de-bias genre *and* theme cosines before the margin-gated rank — that calibration is the
     * accuracy win, and computing it from the run's own vectors keeps it model-agnostic.
     */
    suspend fun backfillMissing(limit: Int = DEFAULT_BACKFILL_LIMIT): Int = withContext(dispatcher) {
        val ids = runCatching { genreRepository.booksMissingGenre(model.id, limit) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return@withContext 0

        // Phase 1 — gather: metadata genre, blurb, and the chunk-mean vector where it is needed
        // (for genre inference, or as the theme fallback when there is no blurb).
        val entries = ids.map { bookId ->
            val subjects = runCatching { genreRepository.subjectsFor(bookId) }.getOrDefault(emptyList())
            val metadata = classifier.canonicalize(subjects)
            val description = runCatching { bookRepository.getBook(bookId)?.description }.getOrNull()
            val needVector = metadata == null || description.isNullOrBlank()
            BookEntry(bookId, metadata, if (needVector) bookVector(bookId) else null, description)
        }

        // Phase 2 — themes for the whole batch (blurb-embedded where possible), calibrated together.
        val themesByBook = runCatching { themesFor(entries) }.getOrDefault(emptyMap())

        // Phase 3 — genre: calibrate over the books that need inference, then rank + store each.
        val needInference = entries.filter { it.metadataGenre == null && it.vector != null }
        val taxonomy = if (needInference.isNotEmpty()) classifier.taxonomyVectors() else null
        val bias = if (taxonomy != null && needInference.isNotEmpty()) {
            classifier.calibrationBias(needInference.map { it.vector!! }, taxonomy)
        } else null

        var written = 0
        for (e in entries) {
            val themes = themesByBook[e.bookId].orEmpty()
            when {
                e.metadataGenre != null -> {
                    store(e.bookId, GenreAssignment(e.metadataGenre, 1f, GenreSource.METADATA), themes)
                    written++
                }
                e.vector == null -> Unit // not embedded yet — retry a later run
                taxonomy == null -> Unit // no model on disk — nothing more we can do this run
                else -> {
                    val inferred = classifier.rank(e.vector, taxonomy, bias)
                    if (inferred != null) store(e.bookId, inferred, themes) else storeUnresolved(e.bookId, themes)
                    written++
                }
            }
        }
        written
    }

    /** Clears every stored genre for the current model — used to force a re-derivation. */
    suspend fun clearForModel(): Int =
        runCatching { genreRepository.clearForModel(model.id) }.getOrDefault(0)

    /**
     * Dominant themes (display names, strongest first) per book, from the blurb where present else
     * the chunk-mean vector. Blurbs are embedded in one batch and the whole set is calibrated
     * together, so a theme that is generically close to everything cannot always win. Empty when
     * there is no theme classifier or no model on disk.
     */
    private suspend fun themesFor(entries: List<BookEntry>): Map<String, List<String>> {
        val tc = themeClassifier ?: return emptyMap()
        val themeTax = tc.themeVectors() ?: return emptyMap()

        val described = entries.filter { !it.description.isNullOrBlank() }
        val descVecs = if (described.isNotEmpty()) {
            tc.embed(described.map { it.description!!.take(MAX_BLURB_CHARS) })
        } else emptyList()
        val descVecById = HashMap<String, FloatArray>(described.size)
        described.forEachIndexed { i, e -> descVecs.getOrNull(i)?.let { descVecById[e.bookId] = it } }

        val themeVecById = LinkedHashMap<String, FloatArray>(entries.size)
        for (e in entries) {
            val v = descVecById[e.bookId] ?: e.vector ?: continue
            themeVecById[e.bookId] = v
        }
        if (themeVecById.isEmpty()) return emptyMap()

        val bias = tc.calibrationBias(themeVecById.values.toList(), themeTax)
        return themeVecById.mapValues { (_, v) -> tc.rankTop(v, themeTax, bias).map { it.displayName } }
    }

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

    private suspend fun store(bookId: String, assignment: GenreAssignment, themes: List<String>) {
        runCatching {
            genreRepository.upsertGenre(
                StoredGenre(
                    bookId = bookId,
                    genre = assignment.genre.name,
                    confidence = assignment.confidence,
                    source = assignment.source.name,
                    modelId = model.id,
                    updatedAt = System.currentTimeMillis(),
                    themes = themes,
                )
            )
        }
    }

    private suspend fun storeUnresolved(bookId: String, themes: List<String>) {
        runCatching {
            genreRepository.upsertGenre(
                StoredGenre(
                    bookId = bookId,
                    genre = "",
                    confidence = 0f,
                    source = SOURCE_NONE,
                    modelId = model.id,
                    updatedAt = System.currentTimeMillis(),
                    themes = themes,
                )
            )
        }
    }

    companion object {
        /** Source string for a resolved-but-unclassifiable book (keeps it out of the work list). */
        const val SOURCE_NONE = "NONE"

        /** How many books one genre backfill pass classifies before yielding. */
        const val DEFAULT_BACKFILL_LIMIT = 200

        /** Blurbs are truncated before embedding — the first paragraph carries the themes. */
        const val MAX_BLURB_CHARS = 1200

        /**
         * Bumped whenever the classifier's *algorithm* changes, so the backfill worker can clear and
         * re-derive stored rows once — rows are keyed by model, not algorithm. v2 introduced
         * calibrated multi-prompt prototypes + relative-margin gating; v3 adds per-book theme
         * classification (blurb-first) alongside the genre.
         */
        const val CLASSIFIER_VERSION = 3
    }
}
