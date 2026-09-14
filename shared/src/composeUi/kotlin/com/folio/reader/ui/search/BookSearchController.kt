package com.folio.reader.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.SearchRepository
import com.folio.reader.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One book-search pass over the library, split into the sections the list renders. */
internal data class BookSearchOutcome(
    val titleMatches: List<Book>,
    val results: List<BookHit>,
    val annotationResults: List<AnnotationHit>,
)

/**
 * The book-search execution shared by the full-screen search (opened from the
 * reader) and the library rail search: the same scopes, the same per-section
 * caps, one implementation. A search fans out across every book, so callers
 * debounce before calling this, not inside it.
 */
internal suspend fun executeBookSearch(
    query: String,
    scope: SearchScope,
    books: List<Book>,
    contentBookId: String?,
    searchRepository: SearchRepository,
    highlightRepository: HighlightRepository,
    noteRepository: NoteRepository,
    bookmarkRepository: BookmarkRepository,
): BookSearchOutcome {
    return when (scope) {
        SearchScope.TITLES -> BookSearchOutcome(
            titleMatches = books.filter {
                it.title.contains(query, ignoreCase = true) || it.displayAuthor.contains(query, ignoreCase = true)
            },
            results = emptyList(),
            annotationResults = emptyList(),
        )

        SearchScope.CONTENT -> {
            val hits = mutableListOf<BookHit>()
            val scopeBooks = contentBookId?.let { id -> books.filter { it.id == id } } ?: books
            for (book in scopeBooks) {
                val chapterHits = searchRepository.searchInBook(book.id, query).first()
                for (hit in chapterHits.take(5)) {
                    hits.add(BookHit(book, hit.spineIndex, hit.title, hit.context))
                }
            }
            BookSearchOutcome(emptyList(), hits, emptyList())
        }

        else -> {
            val hits = mutableListOf<AnnotationHit>()
            for (book in books) {
                when (scope) {
                    SearchScope.HIGHLIGHTS -> {
                        highlightRepository.getHighlightsForBook(book.id).first()
                            .filter { it.selectedText.contains(query, ignoreCase = true) }
                            .take(5)
                            .forEach { h ->
                                hits.add(AnnotationHit(scope, book, h.selectedText.take(80), h.selectedText.take(140), h.spineIndex))
                            }
                    }

                    SearchScope.NOTES -> {
                        noteRepository.getNotesForBook(book.id).first()
                            .filter { it.content.contains(query, ignoreCase = true) }
                            .take(5)
                            .forEach { n ->
                                hits.add(AnnotationHit(scope, book, n.content.take(80), n.content.take(140), n.spineIndex))
                            }
                    }

                    SearchScope.BOOKMARKS -> {
                        bookmarkRepository.getBookmarksForBook(book.id).first()
                            .filter { (it.label?.contains(query, true) == true) }
                            .take(5)
                            .forEach { b ->
                                hits.add(AnnotationHit(scope, book, b.label ?: "Bookmark", "Spine ${b.spineIndex}", b.spineIndex))
                            }
                    }

                    else -> Unit
                }
            }
            BookSearchOutcome(emptyList(), emptyList(), hits)
        }
    }
}

/**
 * State holder for the library rail's books search: the field's text, the
 * active scope and the last outcome, kept outside composition so navigating to
 * a result and back restores the search exactly as it was left. The full-screen
 * reader search keeps its own [SearchUiState]; both run the same
 * [executeBookSearch].
 */
class BookSearchController(
    private val searchRepository: SearchRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val bookmarkRepository: BookmarkRepository,
) {
    var query by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.TITLES)
    var contentBookId by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<BookHit>>(emptyList())
    var annotationResults by mutableStateOf<List<AnnotationHit>>(emptyList())
    var titleMatches by mutableStateOf<List<Book>>(emptyList())
    var searchJob by mutableStateOf<Job?>(null)

    /**
     * Same contract as the full-screen search: debounce, then fan out over
     * [books] in [activeScope]. A blank query clears every section rather than
     * matching everything.
     */
    fun runSearch(q: String, activeScope: SearchScope, books: List<Book>, coroutineScope: CoroutineScope) {
        query = q
        scope = activeScope
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            titleMatches = emptyList()
            annotationResults = emptyList()
            return
        }
        searchJob = coroutineScope.launch {
            delay(250)
            val outcome = executeBookSearch(
                query = q,
                scope = activeScope,
                books = books,
                contentBookId = contentBookId,
                searchRepository = searchRepository,
                highlightRepository = highlightRepository,
                noteRepository = noteRepository,
                bookmarkRepository = bookmarkRepository,
            )
            titleMatches = outcome.titleMatches
            results = outcome.results
            annotationResults = outcome.annotationResults
        }
    }
}
