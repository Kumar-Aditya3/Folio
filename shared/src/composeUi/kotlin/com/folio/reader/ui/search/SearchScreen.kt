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
import com.folio.reader.model.Book
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.topBarFill
import kotlinx.coroutines.launch

data class BookHit(
    val book: Book,
    val spineIndex: Int,
    val chapterTitle: String,
    val context: String
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
    uiState: SearchUiState = remember { SearchUiState() }
) {
    var query by uiState.queryState
    var scope by uiState.scopeState
    var contentBookId by uiState.contentBookIdState
    var results by uiState.resultsState
    var annotationResults by uiState.annotationResultsState
    var titleMatches by uiState.titleMatchesState
    var searchJob by uiState.searchJobState
    val coroutineScope = rememberCoroutineScope()

    // Compute the total item count across all sections so we can clamp the restored
    // scroll index. Without this, a stale index that exceeds the current list size
    // causes LazyListState to silently reset to 0, which the user sees as "lost position".
    val totalItems = run {
        var count = 0
        if (titleMatches.isNotEmpty()) count += 1 + titleMatches.size   // header + items
        if (results.isNotEmpty()) count += 1 + results.size
        if (annotationResults.isNotEmpty()) count += 1 + annotationResults.size
        if (query.isNotBlank() && titleMatches.isEmpty() && results.isEmpty() && annotationResults.isEmpty()) count += 1
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

    fun runSearch(q: String, activeScope: SearchScope) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            titleMatches = emptyList()
            annotationResults = emptyList()
            return
        }
        searchJob = coroutineScope.launch {
            // Debounce: a search fans out across every book, so it only runs
            // once typing pauses instead of on every keystroke. The execution
            // itself is shared with the library rail search.
            kotlinx.coroutines.delay(250)
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
        )
    }
}
