package com.folio.reader.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.QuoteRepository
import com.folio.reader.database.SearchRepository
import com.folio.reader.model.Book
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.flow.first
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

    /** Renders FTS5 snippets: <<term>> becomes bold + accent instead of raw markers. */
    @Composable
    fun SnippetText(text: String, maxLines: Int) {
        val primary = FolioTheme.colors.primary
        val annotated = remember(text) {
            androidx.compose.ui.text.buildAnnotatedString {
                var i = 0
                while (i < text.length) {
                    val open = text.indexOf("<<", i)
                    if (open < 0) { append(text.substring(i)); break }
                    append(text.substring(i, open))
                    val close = text.indexOf(">>", open + 2)
                    if (close < 0) { append(text.substring(open)); break }
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = primary)) {
                        append(text.substring(open + 2, close))
                    }
                    i = close + 2
                }
            }
        }
        Text(annotated, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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
            // once typing pauses instead of on every keystroke.
            kotlinx.coroutines.delay(250)
            when (activeScope) {
                SearchScope.TITLES -> {
                    titleMatches = books.filter {
                        it.title.contains(q, ignoreCase = true) || it.displayAuthor.contains(q, ignoreCase = true)
                    }
                    results = emptyList()
                    annotationResults = emptyList()
                }
                SearchScope.CONTENT -> {
                    titleMatches = emptyList()
                    annotationResults = emptyList()
                    val hits = mutableListOf<BookHit>()
                    val scopeBooks = contentBookId?.let { id -> books.filter { it.id == id } } ?: books
                    for (book in scopeBooks) {
                        val chapterHits = searchRepository.searchInBook(book.id, q).first()
                        for (hit in chapterHits.take(5)) {
                            hits.add(BookHit(book, hit.spineIndex, hit.title, hit.context))
                        }
                    }
                    results = hits
                }
                else -> {
                    titleMatches = emptyList()
                    results = emptyList()
                    val hits = mutableListOf<AnnotationHit>()
                    for (book in books) {
                        when (activeScope) {
                            SearchScope.HIGHLIGHTS -> {
                                highlightRepository.getHighlightsForBook(book.id).first()
                                    .filter { it.selectedText.contains(q, ignoreCase = true) }
                                    .take(5)
                                    .forEach { h ->
                                        hits.add(AnnotationHit(activeScope, book, h.selectedText.take(80), h.selectedText.take(140), h.spineIndex))
                                    }
                            }
                            SearchScope.NOTES -> {
                                noteRepository.getNotesForBook(book.id).first()
                                    .filter { it.content.contains(q, ignoreCase = true) }
                                    .take(5)
                                    .forEach { n ->
                                        hits.add(AnnotationHit(activeScope, book, n.content.take(80), n.content.take(140), n.spineIndex))
                                    }
                            }
                            SearchScope.BOOKMARKS -> {
                                bookmarkRepository.getBookmarksForBook(book.id).first()
                                    .filter { (it.label?.contains(q, true) == true) }
                                    .take(5)
                                    .forEach { b ->
                                        hits.add(AnnotationHit(activeScope, book, b.label ?: "Bookmark", "Spine ${b.spineIndex}", b.spineIndex))
                                    }
                            }
                            else -> Unit
                        }
                    }
                    annotationResults = hits
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
                .background(FolioTheme.colors.surface)
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

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (titleMatches.isNotEmpty()) {
                item {
                    Text(
                        "Titles & authors",
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.secondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(titleMatches, key = { "title:${it.id}" }) { book ->
                    ListItem(
                        headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(book.displayAuthor, maxLines = 1) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            onResultClick(BookHit(book, -1, "", ""))
                        }
                    )
                }
            }
            if (results.isNotEmpty()) {
                item {
                    Text(
                        "Inside books",
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.secondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(results, key = { "hit:${it.book.id}:${it.spineIndex}:${it.context.hashCode()}" }) { hit ->
                    ListItem(
                        headlineContent = { Text("${hit.book.title} - ${hit.chapterTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { SnippetText(hit.context, maxLines = 2) },
                        modifier = Modifier.fillMaxWidth().clickable { onResultClick(hit) }
                    )
                }
            }
            if (annotationResults.isNotEmpty()) {
                item {
                    Text(
                        "${scope.label} matches",
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.secondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(annotationResults, key = { "${it.scope}:${it.book.id}:${it.title.hashCode()}:${it.snippet.hashCode()}" }) { hit ->
                    ListItem(
                        headlineContent = { Text("${hit.book.title} · ${hit.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            onResultClick(BookHit(hit.book, hit.spineIndex ?: -1, hit.scope.label, hit.snippet))
                        }
                    )
                }
            }
            if (query.isNotBlank() && titleMatches.isEmpty() && results.isEmpty() && annotationResults.isEmpty()) {
                item {
                    Text(
                        "No matches for \"$query\" in ${scope.label}.",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
