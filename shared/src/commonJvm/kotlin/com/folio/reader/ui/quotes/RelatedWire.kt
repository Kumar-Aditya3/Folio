package com.folio.reader.ui.quotes

import com.folio.reader.database.BookRepository
import com.folio.reader.ml.SemanticSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A passage found by meaning rather than by words (ML_PLAN Phase 5 #3, "more like this").
 *
 * Lives beside [QuoteRelatedFinder] in `commonJvm` rather than in the Compose layer because
 * the finder produces it and the view model consumes it; putting it in `composeUi` would
 * have made the finder — which needs `BookRepository` and the semantic index — depend
 * upwards on the UI source set.
 *
 * [bookTitle] and [chapterTitle] are resolved for display; [text] is the chunk's own window
 * of the chapter, so the reader can judge the match without opening the book.
 */
data class RelatedPassage(
    val bookId: String,
    val bookTitle: String,
    val chapterId: String,
    val chapterTitle: String?,
    val spineIndex: Int,
    val text: String,
    val score: Float,
)

/**
 * How the "more like this" lookup ended.
 *
 * The failure modes are modelled rather than collapsed into an empty list, because "found
 * nothing" and "the index has not been built" are different things to tell a reader — the
 * first is a result, the second is an instruction.
 */
sealed interface RelatedLookup {
    data class Ready(val passages: List<RelatedPassage>) : RelatedLookup

    /** No vectors for this model, or no model on disk. */
    data object NotIndexed : RelatedLookup
    data class Failed(val message: String) : RelatedLookup
}

/**
 * Bridges the semantic index to the quote hub's "more like this" panel (ML_PLAN Phase 5 #3).
 *
 * In `commonJvm` rather than duplicated in both app modules because the interesting part is
 * not the call to [SemanticSearchRepository.moreLikeThis] — it is resolving a chunk hit into
 * something displayable, and that has to be identical on Android and desktop or the two
 * platforms will report different-looking results for the same library.
 *
 * Two resolutions happen here, both of which `SemanticHit` deliberately does not carry:
 *
 * - **Book title.** `SemanticHit.bookId` is an internal id. A suggestion list showing
 *   `cb8f1e02-…` instead of *The Will of the Many* is technically correct and useless.
 * - **Chapter title.** The chunk knows its `(bookId, chapterId)`, and `chapter_id` is the
 *   EPUB manifest id — book-local — so the lookup is by pair and never by id alone.
 *
 * Chapter titles are resolved lazily per distinct book rather than per hit: a suggestion
 * list usually draws several passages from the same book, and `getChaptersForBook` is a
 * full table read for that book. Asking once per hit would repeat it up to eight times on a
 * single tap.
 */
class QuoteRelatedFinder(
    private val semanticSearch: SemanticSearchRepository,
    private val bookRepository: BookRepository,
) {

    /**
     * @param excludeChunkId the source passage's own chunk, so a quote is never suggested as
     *   a neighbour of itself — which is what the top hit always is otherwise.
     */
    suspend fun related(
        text: String,
        limit: Int,
        excludeChunkId: String? = null,
    ): RelatedLookup = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext RelatedLookup.Ready(emptyList())

        // Ask before searching. An empty result from a library that was never indexed is not
        // "nothing similar exists", and the panel must not say that it is.
        if (!semanticSearch.hasIndex()) return@withContext RelatedLookup.NotIndexed

        val hits = runCatching { semanticSearch.moreLikeThis(text, excludeChunkId, limit) }
            .getOrElse { return@withContext RelatedLookup.Failed(it.message ?: "The lookup failed.") }
        if (hits.isEmpty()) return@withContext RelatedLookup.Ready(emptyList())

        val titles = HashMap<String, Pair<String, Map<String, String>>>()
        val passages = hits.map { hit ->
            val resolved = titles.getOrPut(hit.bookId) {
                val book = bookRepository.getBook(hit.bookId)
                book?.title.orEmpty() to bookRepository.getChaptersForBook(hit.bookId)
                    .associate { it.id to it.title }
            }
            RelatedPassage(
                bookId = hit.bookId,
                bookTitle = resolved.first,
                chapterId = hit.chapterId,
                chapterTitle = resolved.second[hit.chapterId]?.takeIf { it.isNotBlank() },
                spineIndex = hit.spineIndex,
                text = hit.snippet,
                score = hit.score,
            )
        }
        RelatedLookup.Ready(passages)
    }
}

