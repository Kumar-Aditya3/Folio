package com.folio.reader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    BOOKMARKS("Bookmarks"),
    QUOTES("Quotes")
}

data class AnnotationHit(
    val scope: SearchScope,
    val book: Book,
    val title: String,
    val snippet: String,
    val spineIndex: Int?
)

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
    onResultClick: (BookHit) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.TITLES) }
    var results by remember { mutableStateOf<List<BookHit>>(emptyList()) }
    var annotationResults by remember { mutableStateOf<List<AnnotationHit>>(emptyList()) }
    var titleMatches by remember { mutableStateOf<List<Book>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    fun runSearch(q: String, activeScope: SearchScope) {
        query = q
        if (q.isBlank()) {
            results = emptyList()
            titleMatches = emptyList()
            annotationResults = emptyList()
            return
        }
        coroutineScope.launch {
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
                    for (book in books) {
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
                            SearchScope.QUOTES -> {
                                quoteRepository.getAllQuotes().first()
                                    .filter { it.bookId == book.id && (it.text.contains(q, true) || (it.note?.contains(q, true) == true)) }
                                    .take(5)
                                    .forEach { quote ->
                                        hits.add(AnnotationHit(activeScope, book, quote.text.take(80), quote.note ?: quote.text.take(140), null))
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
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { runSearch(it, scope) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search library, content, annotations…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = FolioTheme.colors.surface)
        )

        // Scope chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            SearchScope.entries.forEach { s ->
                FilterChip(
                    selected = scope == s,
                    onClick = {
                        scope = s
                        runSearch(query, s)
                    },
                    label = { Text(s.label) }
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        supportingContent = { Text(hit.context, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
