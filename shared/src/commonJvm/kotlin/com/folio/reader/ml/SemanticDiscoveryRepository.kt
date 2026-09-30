package com.folio.reader.ml

import com.folio.reader.database.BookRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data owner for **Echoes** — cross-book resonant passages for a selected passage.
 *
 * It deliberately sits *beside* [SemanticSearchRepository] and reuses it rather than standing
 * up a second embedder or a second resident index: an ONNX session is tens to hundreds of MB
 * and the whole point of the search repo's design is that exactly one is held while a semantic
 * surface is open. Echoes is a "more like this" query with one extra filter, so it borrows the
 * search repo's embedder, its `preload`d index and its `hasIndex` gate. This class only adds
 * what Echoes needs on top: the current-book exclusion.
 *
 * Injected alongside the search repository from [EmbeddingModelSelection], so it is torn down
 * and rebuilt on a model swap in lockstep with everything else keyed on `model_id`.
 */
class SemanticDiscoveryRepository(
    private val semanticSearch: SemanticSearchRepository,
    private val bookRepository: BookRepository,
    /** [MlDispatchers.inference] — the pool the embed/scan runs on. */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
) {
    val model: EmbeddingModel get() = semanticSearch.model

    /** True when there is a model and vectors to answer from — the same gate search uses. */
    suspend fun canEcho(): Boolean = semanticSearch.hasIndex()

    /**
     * Resonant passages from *other* books for a selected passage.
     *
     * Wraps [SemanticSearchRepository.moreLikeThis], excluding the open book so a result is
     * always a genuine cross-book echo, and honours the same [SemanticSearchRepository.MIN_SIMILARITY]
     * floor — an empty list here means "no echoes found", a result the panel states honestly,
     * not "the index is missing" (which [canEcho] answers separately).
     *
     * Titles and cover paths are resolved per distinct book (a batch of echoes usually draws
     * from a handful of books), so the panel can label and tint each card without a lookup per hit.
     */
    suspend fun echoes(
        selectedText: String,
        currentBookId: String,
        excludeChunkId: String? = null,
        limit: Int = 8,
    ): List<EchoHit> = withContext(dispatcher) {
        if (selectedText.isBlank()) return@withContext emptyList()
        val hits = semanticSearch.moreLikeThis(
            text = selectedText,
            excludeChunkId = excludeChunkId,
            limit = limit,
            excludeBookId = currentBookId,
        )
        if (hits.isEmpty()) return@withContext emptyList()

        val resolved = HashMap<String, com.folio.reader.model.Book?>()
        hits.map { hit ->
            val book = resolved.getOrPut(hit.bookId) { runCatching { bookRepository.getBook(hit.bookId) }.getOrNull() }
            EchoHit(
                bookId = hit.bookId,
                bookTitle = book?.title.orEmpty(),
                coverPath = book?.coverPath,
                chapterId = hit.chapterId,
                spineIndex = hit.spineIndex,
                charStart = hit.charStart,
                chapterFraction = hit.chapterFraction,
                snippet = hit.snippet,
                score = hit.score,
                chunkId = hit.chunkId,
            )
        }
    }

    /**
     * Warms the shared embedder session and preloads the resident index so the first Echoes tap
     * pays for neither a cold ONNX session nor an index build. Safe to call repeatedly (both are
     * cached) and never throws — a missing model just leaves the session null. Called when the
     * reader detects a selection, so warming overlaps the moment before the reader taps Echoes.
     */
    suspend fun warm() = kotlinx.coroutines.coroutineScope {
        // Overlap the two independent one-time costs — the ONNX session init and the resident-index
        // build — instead of paying them back to back, so the first Echoes tap resolves sooner.
        val session = launch { runCatching { semanticSearch.warm() } }
        val index = launch { runCatching { semanticSearch.preload() } }
        session.join(); index.join()
    }

    /** Releases the embedder session (its ~50 MB native footprint); keeps the small index resident. */
    suspend fun releaseEmbedder() {
        runCatching { semanticSearch.releaseEmbedder() }
    }
}

/**
 * A cross-book resonant passage.
 *
 * [coverPath] is carried rather than a resolved colour because the accent is derived from the
 * decoded cover bitmap in the Compose layer (`rememberCoverAccent`), which this module cannot
 * see — so the "land fragment" card tints itself from this path at render time.
 */
data class EchoHit(
    val bookId: String,
    val bookTitle: String,
    val coverPath: String?,
    val chapterId: String,
    val spineIndex: Int,
    val charStart: Int,
    val chapterFraction: Float,
    val snippet: String,
    val score: Float,
    val chunkId: String?,
)
