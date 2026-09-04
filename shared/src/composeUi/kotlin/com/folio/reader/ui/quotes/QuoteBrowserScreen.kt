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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.FolioCallout
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteBrowserScreen(
    onBack: () -> Unit,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    onMangaNoteClick: (MangaQuoteItem) -> Unit = {},
    viewModel: QuoteBrowserViewModel
) {
    var viewMode by remember { mutableStateOf(QuoteBrowserViewModel.ViewMode.LIST) }
    var filter by remember { mutableStateOf(QuoteBrowserViewModel.FilterState()) }
    var searchExpanded by remember { mutableStateOf(false) }
    var bookDropdownExpanded by remember { mutableStateOf(false) }
    var tagDropdownExpanded by remember { mutableStateOf(false) }
    var tagEditorFor by remember { mutableStateOf<QuoteDisplayItem?>(null) }
    val allTags = remember { mutableStateOf<List<Tag>>(emptyList()) }

    LaunchedEffect(Unit) {
        allTags.value = viewModel.allTags()
    }

    val displayItems by viewModel.filteredDisplayItems(filter)
        .collectAsState(initial = emptyList())
    val mangaItems by viewModel.mangaItems(filter)
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        // No fill: the same at-rest rule FolioTopBar follows. An opaque
                        // surface here made the bar a grey lid over the page.
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
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
        if (displayItems.isEmpty() && mangaItems.isEmpty()) {
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
                QuoteBrowserViewModel.ViewMode.GRID -> QuoteGrid(
                    displayItems, mangaItems, onQuoteClick, onMangaNoteClick, padding,
                    onEditTags = { tagEditorFor = it }
                )
                QuoteBrowserViewModel.ViewMode.LIST -> QuoteList(
                    displayItems, mangaItems, onQuoteClick, onMangaNoteClick, padding,
                    onEditTags = { tagEditorFor = it }
                )
            }
        }
    }

    tagEditorFor?.let { editing ->
        com.folio.reader.ui.tags.TagPickerDialog(
            tags = allTags.value,
            assignedTagIds = editing.tags.map { it.id }.toSet(),
            onDismiss = { tagEditorFor = null },
            onSave = { selected ->
                editing.highlight?.id?.let { highlightId ->
                    viewModel.updateHighlightTags(highlightId, selected)
                }
                tagEditorFor = null
            },
        )
    }
}

@Composable
private fun QuoteGrid(
    items: List<QuoteDisplayItem>,
    mangaItems: List<MangaQuoteItem>,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    onMangaNoteClick: (MangaQuoteItem) -> Unit,
    padding: PaddingValues,
    onEditTags: (QuoteDisplayItem) -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(padding)
    ) {
        items(items) { item ->
            QuoteCard(item = item, onQuoteClick = onQuoteClick, onEditTags = onEditTags)
        }
        items(mangaItems) { item ->
            MangaQuoteCard(item = item, onClick = { onMangaNoteClick(item) })
        }
    }
}

@Composable
private fun QuoteList(
    items: List<QuoteDisplayItem>,
    mangaItems: List<MangaQuoteItem>,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    onMangaNoteClick: (MangaQuoteItem) -> Unit,
    padding: PaddingValues,
    onEditTags: (QuoteDisplayItem) -> Unit = {}
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(padding)
    ) {
        items(items) { item ->
            QuoteListItem(item = item, onQuoteClick = onQuoteClick, onEditTags = onEditTags)
        }
        items(mangaItems) { item ->
            MangaQuoteCard(item = item, onClick = { onMangaNoteClick(item) })
        }
    }
}

/**
 * A saved passage, as a pull-quote rather than a card. The highlight's own colour
 * becomes the leading rule, so the reader's chosen highlighter is what identifies
 * the quote — the strongest available signal, and it was previously reduced to a
 * 4dp strip on top of a grey box.
 */
@Composable
private fun QuoteCard(
    item: QuoteDisplayItem,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    onEditTags: (QuoteDisplayItem) -> Unit = {}
) {
    val highlightColor = item.highlight?.effectiveColor
    val accent = highlightColor?.let { androidx.compose.ui.graphics.Color(it) }
        ?: FolioTheme.colors.accentAnnotation

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onQuoteClick(item) }
    ) {
        FolioCallout(accent = accent) {
            Text(
                text = "\u201C${item.quote.text}\u201D",
                style = FolioTheme.typography.quote,
                color = FolioTheme.colors.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.book.title,
                style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
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
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .folioSunken(com.folio.reader.ui.theme.FolioShapes.inset)
                        .padding(10.dp)
                ) {
                    Text(
                        text = item.note.content,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (item.highlight != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item.tags.take(3).forEach { tag ->
                        TagChip(tag = tag)
                    }
                    if (item.tags.size > 3) {
                        Text(
                            "+${item.tags.size - 3}",
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant
                        )
                    }
                    AddTagChip(onClick = { onEditTags(item) })
                }
            }
        }
    }
}

@Composable
private fun QuoteListItem(
    item: QuoteDisplayItem,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    onEditTags: (QuoteDisplayItem) -> Unit = {}
) {
    // Compact form: the same leading-rule language as the card form, no box.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onQuoteClick(item) }
            .padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            val highlightColor = item.highlight?.effectiveColor
                ?.let { androidx.compose.ui.graphics.Color(it) }
                ?: FolioTheme.colors.accentAnnotation
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(72.dp)
                    .background(highlightColor.copy(alpha = 0.75f), RoundedCornerShape(1.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\u201C${item.quote.text}\u201D",
                    style = FolioTheme.typography.quote.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = FolioTheme.colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.book.title,
                    style = FolioTheme.typography.labelMedium,
                    color = FolioTheme.colors.onSurface,
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
                if (item.highlight != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item.tags.take(2).forEach { tag ->
                            TagChip(tag = tag, compact = true)
                        }
                        AddTagChip(compact = true, onClick = { onEditTags(item) })
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

/** Inline "+ Tag" affordance on quote cards; opens the shared picker for that highlight. */
@Composable
private fun AddTagChip(compact: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = FolioTheme.colors.surfaceVariant,
        shape = RoundedCornerShape(if (compact) 4.dp else 8.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = "+ Tag",
            style = if (compact) FolioTheme.typography.labelSmall else FolioTheme.typography.labelMedium,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        )
    }
}
