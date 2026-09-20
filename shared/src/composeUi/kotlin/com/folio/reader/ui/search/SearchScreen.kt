package com.folio.reader.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.QuoteRepository
import com.folio.reader.database.SearchRepository
import com.folio.reader.ml.SearchMode
import com.folio.reader.ml.SemanticSearchRepository
import com.folio.reader.model.Book
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.topBarFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookHit(
    val book: Book,
    val spineIndex: Int,
    val chapterTitle: String,
    val context: String,
    /**
     * Where in the chapter to land, as a fraction in [0, 1] of its plain text, or -1 when there
     * is no intra-chapter target (title matches, annotation hits, pure lexical hits). Carried from
     * [com.folio.reader.ml.SemanticHit.chapterFraction] so a semantic result opens at the matched
     * passage instead of the chapter top.
     */
    val startFraction: Float = -1f,
)

enum class SearchScope(val label: String) {
    TITLES("Titles"),
    CONTENT("Content"),
    HIGHLIGHTS("Highlights"),
    NOTES("Notes"),
    BOOKMARKS("Bookmarks")
}

data class AnnotationHit(
    val scope: SearchScope,
    val book: Book,
    val title: String,
    val snippet: String,
    val spineIndex: Int?
)

/**
 * Keeps the search screen's query, results and scroll position outside composition,
 * so returning from an opened result restores the exact screen the reader left
 * instead of a blank field.
 */
class SearchUiState {
    val queryState = mutableStateOf("")
    val scopeState = mutableStateOf(SearchScope.TITLES)
    val contentBookIdState = mutableStateOf<String?>(null)
    val resultsState = mutableStateOf<List<BookHit>>(emptyList())
    val annotationResultsState = mutableStateOf<List<AnnotationHit>>(emptyList())
    val titleMatchesState = mutableStateOf<List<Book>>(emptyList())
    val searchJobState = mutableStateOf<kotlinx.coroutines.Job?>(null)
    val scrollIndexState = mutableStateOf(0)
    val scrollOffsetState = mutableStateOf(0)

    /** Retrieval strategy. Survives leaving and returning, like the query does. */
    val modeState = mutableStateOf(SearchMode.HYBRID)

    /**
     * Set when semantic search is selected but the model is not installed, so the screen
     * can explain why rather than silently returning nothing.
     */
    val semanticUnavailableState = mutableStateOf(false)

    /**
     * Set when the semantic index *did* answer and nothing cleared the relevance floor.
     *
     * Kept apart from [semanticUnavailableState] because the two need different sentences:
     * "download the model and index your library" is useless advice to a reader whose library
     * is already indexed and whose query simply matched nothing well. Unknown queries are a
     * normal outcome of semantic search, not a setup problem.
     */
    val noStrongMatchState = mutableStateOf(false)

    /**
     * Returns the holder to how a *new* visit should open.
     *
     * Called when the search destination is popped, not when it is covered: opening a result
     * pushes the reader on top, which leaves this screen composed and its state intact, so
     * coming back from a result still shows the results the reader was reading. Only leaving
     * search for good resets.
     *
     * The scope returns to [SearchScope.TITLES] rather than staying on whatever was last
     * picked. Scope is a description of the search being run *right now* — "inside books",
     * "highlights" — and carrying it into a fresh visit means the next search silently runs
     * against a corpus the reader did not choose. [modeState] is deliberately *not* reset:
     * it is a retrieval preference the reader set, in the same class as a reading setting,
     * and resetting it would undo a deliberate choice on every visit.
     */
    fun resetForNewVisit() {
        queryState.value = ""
        scopeState.value = SearchScope.TITLES
        contentBookIdState.value = null
        resultsState.value = emptyList()
        annotationResultsState.value = emptyList()
        titleMatchesState.value = emptyList()
        searchJobState.value?.cancel()
        searchJobState.value = null
        scrollIndexState.value = 0
        scrollOffsetState.value = 0
        semanticUnavailableState.value = false
        noStrongMatchState.value = false
    }
}

/**
 * What one search pass concluded.
 *
 * Was a `Pair<BookSearchOutcome?, List<SemanticHit>>` in which `null` meant "the semantic
 * path answered" — which worked only while the semantic path could not legitimately answer
 * with *nothing*. The relevance floor made "indexed, but no strong match" a normal result,
 * and that state is neither "exact search found this" nor "here are your hits", so it needed
 * a name. Three cases, three sentences in the UI.
 */
private sealed interface SemanticAnswer {
    /** Real semantic hits, already ordered. */
    data class Cleared(val hits: List<com.folio.reader.ml.SemanticHit>) : SemanticAnswer

    /**
     * The index is built and nothing cleared the floor. Authoritative: the caller must not
     * fall back to a lexical search the reader did not ask for.
     */
    data object NoStrongMatch : SemanticAnswer

    /** The semantic path could not run (no model, no index) — exact search's result. */
    data class Exact(val outcome: BookSearchOutcome) : SemanticAnswer
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    books: List<Book>,
    searchRepository: SearchRepository,
    highlightRepository: HighlightRepository,
    noteRepository: NoteRepository,
    bookmarkRepository: BookmarkRepository,
    quoteRepository: QuoteRepository,
    onBackPress: () -> Unit,
    onResultClick: (BookHit) -> Unit,
    uiState: SearchUiState = remember { SearchUiState() },
    /**
     * Null on a build with no embedding model available. The screen then behaves exactly
     * as it did before semantic search existed.
     */
    semanticSearchRepository: SemanticSearchRepository? = null,
) {
    var query by uiState.queryState
    var scope by uiState.scopeState
    var contentBookId by uiState.contentBookIdState
    var results by uiState.resultsState
    var annotationResults by uiState.annotationResultsState
    var titleMatches by uiState.titleMatchesState
    var searchJob by uiState.searchJobState
    var mode by uiState.modeState
    var semanticUnavailable by uiState.semanticUnavailableState
    var noStrongMatch by uiState.noStrongMatchState
    // Transient: true while a semantic query is embedding + scanning (or the index is warming on
    // first use). Local, not hoisted — it should never survive leaving the screen.
    var searching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Deliberately no `FolioBackHandler` here.
    //
    // One was added on the theory that a back press fell through to the host and popped the
    // app. Device testing disproved that: `SEARCH` is its own destination and the nav graph
    // pops it correctly, so this handler never fired for a press it was meant to catch and
    // instead shadowed the nav host's own handling. The reader's own back — which *does* land
    // on the wrong tab — is fixed in the nav graph (`FolioNavHost`'s reader route, via
    // `popToTab`), which is where a destination's back belongs.
    //
    // The rule still stands for screens that own transient state the nav stack cannot see
    // (a bulk selection, an open lens); search has no such level, so it has nothing to claim.

    // The vector matrix is loaded when the screen opens, not when the query arrives: the
    // plan requires the scan to be ready, and building it on the first keystroke would put
    // a 60 MB read on the critical path. Released on dispose so the memory does not
    // outlive the screen.
    DisposableEffect(semanticSearchRepository, contentBookId) {
        val repository = semanticSearchRepository
        if (repository != null) {
            // Warm the index for the scope this screen will actually query: the open book for
            // in-book search, the whole library otherwise. Scoping keeps in-book memory tiny and
            // matches what `semanticRank` will request, so the first query does not rebuild.
            // runCatching because this runs on the composition scope, where an uncaught throw is
            // an app crash rather than a failed load. `preload` already swallows its own failures;
            // this is the backstop for anything the launch itself could surface.
            coroutineScope.launch {
                runCatching { repository.preload(contentBookId) }
                // Also open the embedder session now, off the critical path, so the first query
                // pays neither the index build nor the ~100-300ms ONNX/XNNPACK session construction.
                runCatching { repository.warm() }
            }
        }
        onDispose {
            // Intentionally not releasing the index here. It is int8 and small, and rebuilding it
            // from SQLite on every reopen was the source of the slow first query. It stays resident
            // and only rebuilds when the queried scope changes (see SemanticSearchRepository.preload).
            //
            // The embedder session IS released, though: it is 40-60 MB of native memory (the
            // multilingual model far more) — the resident footprint that gets the app killed in
            // the background — so it must not outlive the screen. The next visit reopens it on the
            // first query, cached again for that visit's subsequent queries.
            if (repository != null) {
                coroutineScope.launch { runCatching { repository.releaseEmbedder() } }
            }
        }
    }

    // Compute the total item count across all sections so we can clamp the restored
    // scroll index. Without this, a stale index that exceeds the current list size
    // causes LazyListState to silently reset to 0, which the user sees as "lost position".
    val totalItems = run {
        var count = 0
        if (titleMatches.isNotEmpty()) count += 1 + titleMatches.size   // header + items
        if (results.isNotEmpty()) count += 1 + results.size
        if (annotationResults.isNotEmpty()) count += 1 + annotationResults.size
        // The empty-state row is only rendered when the list is not suppressed by the
        // relevance floor's message; counting it either way would allow a scroll index one
        // past the end, which is what the clamp below exists to prevent.
        if (query.isNotBlank() && !noStrongMatch &&
            titleMatches.isEmpty() && results.isEmpty() && annotationResults.isEmpty()
        ) count += 1
        count += 1 // trailing spacer
        count
    }
    val safeIndex = if (totalItems == 0) 0 else uiState.scrollIndexState.value.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
    val safeOffset = if (totalItems == 0) 0 else uiState.scrollOffsetState.value
    val listState = remember(uiState) {
        LazyListState(safeIndex, safeOffset)
    }
    DisposableEffect(uiState) {
        onDispose {
            uiState.scrollIndexState.value = listState.firstVisibleItemIndex
            uiState.scrollOffsetState.value = listState.firstVisibleItemScrollOffset
        }
    }

    // Leaving search for good clears it.
    //
    // [SearchUiState] is hoisted to the app's nav model, so it outlives this destination.
    // That is what makes an opened result return to the results the reader was reading —
    // the reader is *pushed* on top, this screen is not disposed, and the query and the
    // scope are still there when they come back.
    //
    // But the same lifetime meant the state also survived *leaving search entirely*: after
    // one search, every later visit reopened on the old query with the old results already
    // filled in, and the Meaning/Content selection carried over. Reported as stale search
    // state. Disposal is exactly the right trigger — a push does not dispose this screen,
    // a pop that leaves search does — and it is keyed on [uiState] so a future call site
    // passing its own holder gets its own reset rather than a shared one.
    //
    // A second `DisposableEffect` rather than one merged with the scroll save above: that
    // one must run *first* (it reads `listState`, which this one's reset does not touch),
    // and `onDispose` blocks run in declaration order.
    DisposableEffect(uiState) {
        onDispose { uiState.resetForNewVisit() }
    }

    fun runSearch(q: String, activeScope: SearchScope) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            titleMatches = emptyList()
            annotationResults = emptyList()
            searching = false
            return
        }
        searchJob = coroutineScope.launch {
            // Debounce: a search fans out across every book, so it only runs
            // once typing pauses instead of on every keystroke. The execution
            // itself is shared with the library rail search.
            kotlinx.coroutines.delay(250)

            // Everything below runs on Dispatchers.Default, not on the composition's scope.
            //
            // `rememberCoroutineScope()` launches on AndroidUiDispatcher.Main. Every
            // repository call inside it is `withContext(Dispatchers.IO)` so the *database*
            // work was already off the main thread — but the loop *around* those calls was
            // not, and neither was the semantic path's index scan. `executeBookSearch` in
            // CONTENT scope issues one `searchInBook` per book in the library: 22 suspends,
            // 22 main-thread resumptions, plus a `snippet()` FTS5 evaluation per row and the
            // BM25 sort inside each query, all serialised behind one connection mutex. On a
            // phone that is a multi-second main-thread stall the user sees as a hang, and
            // the debounce made it worse rather than better — cancelling only takes effect
            // at the next suspension point, so a fast typist queues overlapping fan-outs.
            //
            // The loop belongs on Default. The *state writes* stay where they are: assigning
            // to `mutableStateOf` from another thread is allowed (Snapshot state is
            // thread-safe) and Compose picks the change up on the next frame.
            val repository = semanticSearchRepository
            val semanticPath = repository != null &&
                activeScope == SearchScope.CONTENT &&
                mode != SearchMode.FULL_TEXT

            // Show the indicator for any non-title search. Titles filter the in-memory book list
            // instantly (no async work, so a spinner there would only flicker), but content and
            // annotation scopes both fan out one query per book — and content in particular runs
            // the embed+scan or the FTS fan-out — so the reader must see that work is happening
            // rather than a premature "no matches". Scoping it to semanticPath before meant a
            // reader without the model, or on Exact mode, got no indicator on a slow content search.
            searching = activeScope != SearchScope.TITLES
            val outcome = try {
                withContext(Dispatchers.Default) {
                // Semantic modes only apply to content search; titles, highlights, notes and
                // bookmarks are exact-match lookups where an embedding adds nothing.
                if (repository != null && semanticPath) {
                    // Three outcomes, not two, and the middle one is what the relevance floor
                    // added. `hits` is empty for two very different reasons:
                    //   * the index was never built (no model / nothing embedded) — the exact
                    //     path must answer, and the UI must say the model is missing;
                    //   * the index answered and nothing cleared the relevance floor — this is
                    //     a real answer, and it is "no strong matches", not "no index".
                    // Telling them apart is the whole point: before the floor, the second case
                    // could not happen at all, because a nearest neighbour always exists, so
                    // a nonsense query returned confident noise instead.
                    val indexed = repository.isLoaded || repository.canAnswer()
                    val hits = runCatching {
                        repository.search(
                            query = q,
                            mode = mode,
                            bookId = contentBookId,
                        )
                    }.getOrElse { emptyList() }
                    if (hits.isNotEmpty()) {
                        return@withContext SemanticAnswer.Cleared(hits)
                    }
                    if (indexed) {
                        // Indexed and nothing matched: authoritative, do not fall through to
                        // a lexical search the reader did not ask for.
                        return@withContext SemanticAnswer.NoStrongMatch
                    }
                }
                SemanticAnswer.Exact(
                    executeBookSearch(
                        query = q,
                        scope = activeScope,
                        books = books,
                        contentBookId = contentBookId,
                        searchRepository = searchRepository,
                        highlightRepository = highlightRepository,
                        noteRepository = noteRepository,
                        bookmarkRepository = bookmarkRepository,
                    )
                )
                }
            } finally {
                searching = false
            }

            when (outcome) {
                is SemanticAnswer.Cleared -> {
                    // The semantic path answered with real matches.
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

                SemanticAnswer.NoStrongMatch -> {
                    // The index is built and nothing cleared the floor. This is an answer —
                    // "no strong matches" — and falling through to exact search here would
                    // quietly re-introduce the old behaviour for a semantic query the reader
                    // deliberately chose.
                    semanticUnavailable = false
                    noStrongMatch = true
                    titleMatches = emptyList()
                    annotationResults = emptyList()
                    results = emptyList()
                }

                is SemanticAnswer.Exact -> {
                    // Falling back to exact matching: say why if semantics were requested.
                    semanticUnavailable = semanticPath
                    noStrongMatch = false
                    val exact = outcome.outcome
                    titleMatches = exact.titleMatches
                    results = exact.results
                    annotationResults = exact.annotationResults
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: the themed status band, a field with room for its own text, and chips
        // that scroll instead of being crushed into the remaining width.
        com.folio.reader.ui.components.FolioStatusBarBand()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Glass, like every other bar in the app: an opaque surface slab here
                // was the one lid left over the search field.
                // The search masthead never scrolls away, so it wears the crown
                // alpha of a fully collapsed bar rather than tracking a collapse.
                .background(
                    FolioTheme.atmosphere.barGlass.copy(
                        alpha = FolioTheme.surfaceOpacity.topBarFill(1f).crown
                    )
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { runSearch(it, scope) },
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(com.folio.reader.ui.theme.FolioTokens.radiusControl),
                    placeholder = { Text("Search") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(SearchScope.entries, key = { "scope:${it.name}" }) { s ->
                    com.folio.reader.ui.components.FolioChip(
                        selected = scope == s,
                        onClick = {
                            scope = s
                            runSearch(query, s)
                        },
                        label = s.label
                    )
                }
            }
        }

        // Retrieval mode. Shown only for content search, and only when the build can do
        // semantics at all — otherwise it would offer choices that cannot be honoured.
        if (scope == SearchScope.CONTENT && semanticSearchRepository != null) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                items(SearchMode.entries.toList(), key = { "mode:${it.name}" }) { m ->
                    com.folio.reader.ui.components.FolioChip(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            runSearch(query, scope)
                        },
                        label = m.label
                    )
                }
            }
            if (semanticUnavailable) {
                Text(
                    text = "Semantic index not built yet — showing exact matches. " +
                        "Download the model in Settings, then index your library.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The relevance floor's own state. Deliberately *not* phrased as "no matches":
            // the library may well contain the words, and the mode is about meaning rather
            // than presence. It is also not the index-is-missing message above — the advice
            // there would be wrong here, because there is nothing to install.
            if (noStrongMatch) {
                Text(
                    text = "No strong matches. Nothing in your library is close enough in " +
                        "meaning to this query — try a shorter phrase, or switch to Exact " +
                        "to search for the words themselves.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Content scope: optionally narrow the search to one book.
        if (scope == SearchScope.CONTENT && books.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                item {
                    com.folio.reader.ui.components.FolioChip(
                        selected = contentBookId == null,
                        onClick = { contentBookId = null; runSearch(query, scope) },
                        label = "All books"
                    )
                }
                items(books, key = { "f:${it.id}" }) { book ->
                    com.folio.reader.ui.components.FolioChip(
                        selected = contentBookId == book.id,
                        onClick = { contentBookId = book.id; runSearch(query, scope) },
                        label = book.title.substringBefore(" -").take(24)
                    )
                }
            }
        }

        // Progress while a semantic query embeds + scans (or warms the index on first use).
        // A thin indeterminate line under the chrome, not a full-screen spinner, so results can
        // stream in beneath it without the list jumping.
        if (searching) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = FolioTheme.colors.accentProgress,
                trackColor = FolioTheme.colors.accentProgress.copy(alpha = 0.18f),
            )
        }

        // The result list itself is the shared one the library rail search
        // renders; only the header above it belongs to this screen.
        BookSearchResultsList(
            query = query,
            scope = scope,
            titleMatches = titleMatches,
            results = results,
            annotationResults = annotationResults,
            onOpenTitle = { book -> onResultClick(BookHit(book, -1, "", "")) },
            onOpenHit = onResultClick,
            modifier = Modifier.fillMaxSize(),
            listState = listState,
            noStrongMatch = noStrongMatch,
            searching = searching,
        )
    }
}
