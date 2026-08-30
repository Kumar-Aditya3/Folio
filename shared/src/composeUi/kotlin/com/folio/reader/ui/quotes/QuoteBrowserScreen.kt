package com.folio.reader.ui.quotes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.NoteType
import com.folio.reader.model.Quote
import com.folio.reader.model.Tag
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class QuoteDisplayItem(
    val quote: Quote,
    val book: Book,
    val chapter: Chapter?,
    val highlight: Highlight?,
    val note: Note?,
    val tags: List<Tag>
)

class QuoteBrowserViewModel(
    private val getAllQuotes: () -> kotlinx.coroutines.flow.Flow<List<Quote>>,
    private val getBook: suspend (String) -> Book?,
    private val getChaptersForBook: suspend (String) -> List<Chapter>,
    private val getHighlight: suspend (String) -> Highlight?,
    private val getNote: suspend (String) -> Note?,
    private val getTagsForHighlight: suspend (String) -> List<Tag>,
    private val getAllBooks: () -> kotlinx.coroutines.flow.Flow<List<Book>>,
    private val getAllTags: suspend () -> List<Tag>
) {
    enum class ViewMode { GRID, LIST }

    data class FilterState(
        val bookId: String? = null,
        val tagIds: Set<String> = emptySet(),
        val searchQuery: String = ""
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawQuotes = flowOf(Unit)
        .flatMapLatest { getAllQuotes() }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    private val allBooksState = getAllBooks().stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun filteredDisplayItems(filter: FilterState): kotlinx.coroutines.flow.Flow<List<QuoteDisplayItem>> {
        return channelFlow {
            combine(rawQuotes, allBooksState) { quotes, books ->
                Pair(quotes, books)
            }.collect { (quotes, books) ->
                val items = withContext(Dispatchers.IO) {
                    quotes.mapNotNull { quote ->
                        val book = books.find { it.id == quote.bookId } ?: getBook(quote.bookId) ?: return@mapNotNull null
                        val chapters = getChaptersForBook(quote.bookId)
                        val chapter = chapters.find { it.id == quote.chapterId }
                        val highlight = quote.highlightId.takeIf { it.isNotBlank() }?.let { getHighlight(it) }
                        val note = quote.highlightId.takeIf { it.isNotBlank() }?.let { getHighlight(it)?.noteId }?.let { getNote(it) }
                            ?: if (quote.note != null) Note(
                                id = "inline-${quote.id}",
                                bookId = quote.bookId,
                                chapterId = quote.chapterId,
                                spineIndex = highlight?.spineIndex,
                                locator = highlight?.startLocator,
                                content = quote.note,
                                type = NoteType.HIGHLIGHT_NOTE,
                                deviceId = quote.deviceId
                            ) else null
                        val tags = quote.highlightId.takeIf { it.isNotBlank() }?.let { getTagsForHighlight(it) } ?: emptyList()
                        QuoteDisplayItem(quote, book, chapter, highlight, note, tags)
                    }.filter { item ->
                        filter.bookId?.let { item.book.id == it } ?: true &&
                        (filter.tagIds.isEmpty() || item.tags.any { it.id in filter.tagIds }) &&
                        (filter.searchQuery.isBlank() ||
                            item.quote.text.contains(filter.searchQuery, ignoreCase = true) ||
                            item.book.title.contains(filter.searchQuery, ignoreCase = true) ||
                            (item.note?.content?.contains(filter.searchQuery, ignoreCase = true) == true))
                    }
                }
                send(items)
            }
        }
    }

    fun allBooks(): List<Book> = allBooksState.value

    suspend fun allTags(): List<Tag> = getAllTags()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteBrowserScreen(
    onBack: () -> Unit,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    viewModel: QuoteBrowserViewModel
) {
    var viewMode by remember { mutableStateOf(QuoteBrowserViewModel.ViewMode.LIST) }
    var filter by remember { mutableStateOf(QuoteBrowserViewModel.FilterState()) }
    var searchExpanded by remember { mutableStateOf(false) }
    var bookDropdownExpanded by remember { mutableStateOf(false) }
    var tagDropdownExpanded by remember { mutableStateOf(false) }
    val allTags = remember { mutableStateOf<List<Tag>>(emptyList()) }

    LaunchedEffect(Unit) {
        allTags.value = viewModel.allTags()
    }

    val displayItems by viewModel.filteredDisplayItems(filter)
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                com.folio.reader.ui.components.FolioStatusBarBand()
                TopAppBar(
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    title = { Text("Quotes", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchExpanded = !searchExpanded }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { viewMode = QuoteBrowserViewModel.ViewMode.GRID }) {
                            Icon(
                                Icons.Filled.List,
                                contentDescription = "Grid view",
                                tint = if (viewMode == QuoteBrowserViewModel.ViewMode.GRID)
                                    FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewMode = QuoteBrowserViewModel.ViewMode.LIST }) {
                            Icon(
                                Icons.Filled.List,
                                contentDescription = "List view",
                                tint = if (viewMode == QuoteBrowserViewModel.ViewMode.LIST)
                                    FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = FolioTheme.colors.surface,
                        titleContentColor = FolioTheme.colors.onSurface
                    )
                )

                if (searchExpanded) {
                    OutlinedTextField(
                        value = filter.searchQuery,
                        onValueChange = { filter = filter.copy(searchQuery = it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search quotes, notes, books...") },
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        FilterChip(
                            selected = filter.bookId != null,
                            onClick = { bookDropdownExpanded = true },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = filter.bookId?.let { id -> viewModel.allBooks().find { it.id == id }?.title?.take(20) }
                                            ?: "All books",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = bookDropdownExpanded,
                            onDismissRequest = { bookDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All books") },
                                onClick = {
                                    filter = filter.copy(bookId = null)
                                    bookDropdownExpanded = false
                                }
                            )
                            viewModel.allBooks().forEach { book ->
                                DropdownMenuItem(
                                    text = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        filter = filter.copy(bookId = book.id)
                                        bookDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        FilterChip(
                            selected = filter.tagIds.isNotEmpty(),
                            onClick = { tagDropdownExpanded = true },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (filter.tagIds.isEmpty()) "All tags"
                                        else "${filter.tagIds.size} tag${if (filter.tagIds.size > 1) "s" else ""}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = tagDropdownExpanded,
                            onDismissRequest = { tagDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All tags") },
                                onClick = {
                                    filter = filter.copy(tagIds = emptySet())
                                    tagDropdownExpanded = false
                                }
                            )
                            allTags.value.forEach { tag ->
                                val selected = tag.id in filter.tagIds
                                DropdownMenuItem(
                                    text = { Text(tag.name) },
                                    onClick = {
                                        filter = filter.copy(
                                            tagIds = if (selected) filter.tagIds - tag.id else filter.tagIds + tag.id
                                        )
                                    },
                                    leadingIcon = {
                                        androidx.compose.material3.Checkbox(
                                            checked = selected,
                                            onCheckedChange = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (displayItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No quotes found", style = FolioTheme.typography.headlineSmall)
                    Text(
                        "Create highlights in the reader to see them here",
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }
        } else {
            when (viewMode) {
                QuoteBrowserViewModel.ViewMode.GRID -> QuoteGrid(displayItems, onQuoteClick, padding)
                QuoteBrowserViewModel.ViewMode.LIST -> QuoteList(displayItems, onQuoteClick, padding)
            }
        }
    }
}

@Composable
private fun QuoteGrid(
    items: List<QuoteDisplayItem>,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    padding: PaddingValues
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(padding)
    ) {
        items(items) { item ->
            QuoteCard(item = item, onQuoteClick = onQuoteClick)
        }
    }
}

@Composable
private fun QuoteList(
    items: List<QuoteDisplayItem>,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    padding: PaddingValues
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(padding)
    ) {
        items(items) { item ->
            QuoteListItem(item = item, onQuoteClick = onQuoteClick)
        }
    }
}

@Composable
private fun QuoteCard(
    item: QuoteDisplayItem,
    onQuoteClick: (QuoteDisplayItem) -> Unit
) {
    val highlightColor = item.highlight?.effectiveColor
    val borderColor = highlightColor?.let { androidx.compose.ui.graphics.Color(it) }
        ?: FolioTheme.colors.primary

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onQuoteClick(item) },
        colors = CardDefaults.cardColors(
            containerColor = FolioTheme.colors.surface,
            contentColor = FolioTheme.colors.onSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(borderColor)
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "\u201C${item.quote.text}\u201D",
                style = FolioTheme.typography.quote,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = item.book.title,
                style = FolioTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.chapter?.let { chapter ->
                Text(
                    text = chapter.title,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.note != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = FolioTheme.colors.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = item.note.content,
                        style = FolioTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (item.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item.tags.take(3).forEach { tag ->
                        TagChip(tag = tag)
                    }
                    if (item.tags.size > 3) {
                        Text(
                            "+${item.tags.size - 3}",
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteListItem(
    item: QuoteDisplayItem,
    onQuoteClick: (QuoteDisplayItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onQuoteClick(item) },
        colors = CardDefaults.cardColors(
            containerColor = FolioTheme.colors.surface,
            contentColor = FolioTheme.colors.onSurface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            val highlightColor = item.highlight?.effectiveColor
                ?.let { androidx.compose.ui.graphics.Color(it) }
                ?: FolioTheme.colors.primary
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(highlightColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\u201C${item.quote.text}\u201D",
                    style = FolioTheme.typography.quote.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.book.title,
                    style = FolioTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.chapter?.let { chapter ->
                    Text(
                        text = chapter.title,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.tags.take(2).forEach { tag ->
                            TagChip(tag = tag, compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(tag: Tag, compact: Boolean = false) {
    val color = tag.color?.let { androidx.compose.ui.graphics.Color(it) }
        ?: FolioTheme.colors.tertiaryContainer
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(if (compact) 4.dp else 8.dp)
    ) {
        Text(
            text = tag.name,
            style = if (compact) FolioTheme.typography.labelSmall else FolioTheme.typography.labelMedium,
            color = FolioTheme.colors.onSurface,
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        )
    }
}
