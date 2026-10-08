package com.folio.reader.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.SearchRepository
import com.folio.reader.ml.SearchMode
import com.folio.reader.ml.SemanticHit
import com.folio.reader.ml.SemanticSearchRepository
import com.folio.reader.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
                // Cooperative cancellation. `searchJob.cancel()` on the previous query only
                // stops this loop at a suspension point, so a fast typist's abandoned
                // fan-outs would otherwise keep running to completion *inside* the database
                // connection mutex — delaying the search they actually want, and doing it
                // once per keystroke. `ensureActive()` turns the cancel into an immediate
                // exit at the top of each book.
                coroutineContext.ensureActive()
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
                coroutineContext.ensureActive()
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
    /**
     * Null on a build with no embedding model. The rail search then behaves exactly as it did
     * before semantic search existed, and the mode chips are not offered — the same contract
     * the full-screen search honours, so the two surfaces agree about what this build can do.
     */
    private val semanticSearchRepository: SemanticSearchRepository? = null,
) {
    var query by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.TITLES)
    var contentBookId by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<BookHit>>(emptyList())
    var annotationResults by mutableStateOf<List<AnnotationHit>>(emptyList())
    var titleMatches by mutableStateOf<List<Book>>(emptyList())
    var searchJob by mutableStateOf<Job?>(null)

    /**
     * Retrieval strategy, the same three the full-screen search offers.
     *
     * Lives here rather than in the screen so the library rail and the reader's search share
     * one value: the reader picks "Meaning" on one surface and finds it already selected on
     * the other, instead of each surface having its own opinion about how to search.
     *
     * [SearchMode.HYBRID] is the default for the same reason it is in `SearchUiState`.
     */
    var mode by mutableStateOf(SearchMode.HYBRID)

    /** True when a semantic mode ran and nothing cleared the relevance floor. See the floor. */
    var noStrongMatch by mutableStateOf(false)

    /** True when semantics were requested but could not run — no model, or nothing indexed. */
    var semanticUnavailable by mutableStateOf(false)

    /** True while a semantic query is embedding + scanning (or warming the index on first use). */
    var searching by mutableStateOf(false)

    /** True when this build can offer the Meaning/Best modes at all. */
    val semanticAvailable: Boolean get() = semanticSearchRepository != null

    /**
     * Loads the vector index into memory so the first semantic keystroke does not pay for it.
     *
     * The rail should call this when it opens, mirroring the full-screen search's preload — without
     * it the ~60 MB matrix build happens lazily inside the first `search`, on the critical path, and
     * the first in-book/meaning query stalls. A no-op when there is no model or nothing indexed, and
     * safe to call repeatedly (a second call while loaded returns immediately).
     */
    suspend fun preload() {
        // Warm the same scope the search will query — the open book when set, else the whole
        // library — so the first query reuses the index instead of rebuilding a different scope.
        semanticSearchRepository?.preload(contentBookId)
        // Open the ONNX session now too, off the critical path, so the first query pays neither
        // the index build nor the ~100-300ms session construction.
        semanticSearchRepository?.warm()
    }

    /** Drops the in-memory index when the rail closes, freeing the ~60 MB it holds. */
    suspend fun release() {
        semanticSearchRepository?.release()
    }

    /**
     * Closes the cached embedder session without dropping the index.
     *
     * Called when the search rail closes. The index stays resident (it is small and its reload
     * from SQLite is what made a reopen slow), but the embedder is 40-60 MB of native memory that
     * must not linger in the background — so this is the right thing to release on close, not the
     * whole index.
     */
    suspend fun releaseEmbedder() {
        semanticSearchRepository?.releaseEmbedder()
    }

    /**
     * Runs the same search the full-screen surface runs, in the same mode.
     *
     * The semantic path is deliberately restricted to [SearchScope.CONTENT] and to the same
     * two modes the screen allows: titles, highlights, notes and bookmarks are exact-match
     * lookups over short strings an embedding adds nothing to, and offering "Meaning" over a
     * list of titles would promise something the index cannot deliver.
     */
    fun runSearch(q: String, activeScope: SearchScope, books: List<Book>, coroutineScope: CoroutineScope) {
        query = q
        scope = activeScope
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            titleMatches = emptyList()
            annotationResults = emptyList()
            noStrongMatch = false
            semanticUnavailable = false
            searching = false
            return
        }
        // Captured at launch rather than read inside: the reader can change the mode while a
        // search is in flight, and the result that lands must be the one for the mode that
        // was asked for.
        val activeMode = mode
        searchJob = coroutineScope.launch {
            delay(250)
            // Off the caller's dispatcher: this fans out to one query per book, and the
            // caller is a composition scope (Main). See the same note in `SearchScreen`.
            val repository = semanticSearchRepository
            val semanticPath = repository != null &&
                activeScope == SearchScope.CONTENT &&
                activeMode != SearchMode.FULL_TEXT

            // Show the indicator for any non-title search: content and annotation scopes fan out
            // one query per book (content also embeds + scans), so the reader must see work is in
            // flight instead of a premature empty state. Titles filter the in-memory list instantly,
            // so a spinner there would only flicker.
            searching = activeScope != SearchScope.TITLES
            val outcome = try {
                withContext(Dispatchers.Default) {
                if (repository != null && semanticPath) {
                    // Same three-way split as the full-screen search: real hits, indexed-but-
                    // nothing-cleared the floor, or the index cannot answer and the exact path
                    // must. See `SemanticSearchRepository.MIN_SIMILARITY`.
                    val indexed = repository.isLoaded || repository.canAnswer()
                    val hits = runCatching {
                        repository.search(query = q, mode = activeMode, bookId = contentBookId)
                    }.getOrElse { emptyList() }
                    if (hits.isNotEmpty()) return@withContext RailSearchOutcome(hits = hits)
                    if (indexed) return@withContext RailSearchOutcome(semanticAnswered = true)
                }
                RailSearchOutcome(
                    exactOutcome = executeBookSearch(
                        query = q,
                        scope = activeScope,
                        books = books,
                        contentBookId = contentBookId,
                        searchRepository = searchRepository,
                        highlightRepository = highlightRepository,
                        noteRepository = noteRepository,
                        bookmarkRepository = bookmarkRepository,
                    ),
                )
                }
            } finally {
                searching = false
            }

            when {
                outcome.hits != null -> {
                    semanticUnavailable = false
                    noStrongMatch = false
                    titleMatches = emptyList()
                    annotationResults = emptyList()
                    results = outcome.hits.mapNotNull { hit ->
                        books.firstOrNull { it.id == hit.bookId }?.let { book ->
                            BookHit(
                                book = book,
                                spineIndex = hit.spineIndex,
                                chapterTitle = hit.title.ifBlank { book.title },
                                context = hit.snippet,
                                startFraction = hit.chapterFraction,
                            )
                        }
                    }
                }

                outcome.semanticAnswered -> {
                    // The index answered and nothing cleared the floor: authoritative.
                    semanticUnavailable = false
                    noStrongMatch = true
                    titleMatches = emptyList()
                    annotationResults = emptyList()
                    results = emptyList()
                }

                else -> {
                    semanticUnavailable = semanticPath
                    noStrongMatch = false
                    val exact = outcome.exactOutcome
                    titleMatches = exact?.titleMatches ?: emptyList()
                    results = exact?.results ?: emptyList()
                    annotationResults = exact?.annotationResults ?: emptyList()
                }
            }
        }
    }
}

/** What one rail search pass concluded. Mirrors the full-screen search's own outcome type. */
private data class RailSearchOutcome(
    /** Non-null when semantic hits were returned. */
    val hits: List<SemanticHit>? = null,
    /** True when the index ran and nothing cleared the relevance floor. */
    val semanticAnswered: Boolean = false,
    /** The exact-search result, when the semantic path could not run. */
    val exactOutcome: BookSearchOutcome? = null,
)
