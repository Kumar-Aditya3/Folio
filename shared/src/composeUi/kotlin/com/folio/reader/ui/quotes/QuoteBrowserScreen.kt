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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FormatQuote
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioCallout
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.theme.FolioShapes
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

    val relatedSource by viewModel.relatedSource.collectAsState()
    val relatedState by viewModel.relatedState.collectAsState()

    LaunchedEffect(Unit) {
        allTags.value = viewModel.allTags()
    }

    // Cancel the view model's scope when the hub leaves composition, so its shared data flows and
    // edit coroutines don't outlive the screen.
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    val quotesLoading by viewModel.loading.collectAsState(initial = true)
    val displayItems by viewModel.filteredDisplayItems(filter)
        .collectAsState(initial = emptyList())
    // null = the manga-notes flow has not emitted yet (loading); an empty list is a real
    // "no manga notes". Kept distinct so a slow manga source cannot force a false empty state.
    val mangaItems by viewModel.mangaItems(filter)
        .collectAsState(initial = null)

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
                        IconButton(
                            onClick = { viewMode = QuoteBrowserViewModel.ViewMode.GRID },
                            // Active view mode is otherwise signalled only by tint;
                            // expose it as a selection state so TalkBack announces it.
                            modifier = Modifier.semantics {
                                selected = viewMode == QuoteBrowserViewModel.ViewMode.GRID
                            },
                        ) {
                            Icon(
                                Icons.Filled.GridView,
                                contentDescription = "Grid view",
                                tint = if (viewMode == QuoteBrowserViewModel.ViewMode.GRID)
                                    FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewMode = QuoteBrowserViewModel.ViewMode.LIST },
                            modifier = Modifier.semantics {
                                selected = viewMode == QuoteBrowserViewModel.ViewMode.LIST
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
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
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
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
                                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
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
        val onFindRelated: ((QuoteDisplayItem) -> Unit)? =
            if (viewModel.canFindRelated) ({ item -> viewModel.findRelatedFor(item) }) else null

        Box(modifier = Modifier.fillMaxSize()) {
            val manga = mangaItems.orEmpty()
            when {
                // Anything to show wins immediately, so quotes arriving before the manga
                // flow (or vice-versa) never flashes a spinner over real content.
                displayItems.isNotEmpty() || manga.isNotEmpty() -> when (viewMode) {
                    QuoteBrowserViewModel.ViewMode.GRID -> QuoteGrid(
                        displayItems, manga, onQuoteClick, onMangaNoteClick, padding,
                        onEditTags = { tagEditorFor = it },
                        onFindRelated = onFindRelated
                    )
                    QuoteBrowserViewModel.ViewMode.LIST -> QuoteList(
                        displayItems, manga, onQuoteClick, onMangaNoteClick, padding,
                        onEditTags = { tagEditorFor = it },
                        onFindRelated = onFindRelated
                    )
                }
                // Nothing yet AND a source is still loading: the honest "still loading",
                // not the old false "No quotes found".
                quotesLoading || mangaItems == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingPlaceholder()
                }
                // Both sources have loaded and are genuinely empty.
                else -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.Outlined.FormatQuote,
                        headline = "No quotes found",
                        body = "Create highlights in the reader to see them here"
                    )
                }
            }

            // Anchored to the bottom of the hub so the reader keeps their place in the
            // quote list while comparing the source passage against its neighbours,
            // instead of the panel overlaying the top of the list.
            if (relatedSource != null) {
                RelatedPanel(
                    state = relatedState,
                    onClose = { viewModel.closeRelated() },
                    modifier = Modifier.align(Alignment.BottomCenter),
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
    onEditTags: (QuoteDisplayItem) -> Unit = {},
    onFindRelated: ((QuoteDisplayItem) -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(padding)
    ) {
        items(items, key = { "q:${it.quote.id}" }) { item ->
            QuoteCard(
                item = item, onQuoteClick = onQuoteClick, onEditTags = onEditTags,
                onFindRelated = onFindRelated,
            )
        }
        items(mangaItems, key = { "m:${it.note.id}" }) { item ->
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
    onEditTags: (QuoteDisplayItem) -> Unit = {},
    onFindRelated: ((QuoteDisplayItem) -> Unit)? = null,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(padding)
    ) {
        items(items, key = { "q:${it.quote.id}" }) { item ->
            QuoteListItem(
                item = item, onQuoteClick = onQuoteClick, onEditTags = onEditTags,
                onFindRelated = onFindRelated,
            )
        }
        items(mangaItems, key = { "m:${it.note.id}" }) { item ->
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
    onEditTags: (QuoteDisplayItem) -> Unit = {},
    onFindRelated: ((QuoteDisplayItem) -> Unit)? = null,
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
                    onFindRelated?.let { find ->
                        RelatedChip(compact = false, onClick = { find(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteListItem(
    item: QuoteDisplayItem,
    onQuoteClick: (QuoteDisplayItem) -> Unit,
    onEditTags: (QuoteDisplayItem) -> Unit = {},
    onFindRelated: ((QuoteDisplayItem) -> Unit)? = null,
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
                        onFindRelated?.let { find ->
                            RelatedChip(compact = true, onClick = { find(item) })
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
        shape = FolioShapes.chip
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
        shape = FolioShapes.chip,
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

/**
 * "More like this" affordance — ML_PLAN Phase 5 #3.
 *
 * Only rendered when a lookup is actually available, so it never appears as a control that
 * cannot do anything. Deliberately worded as a phrase rather than an icon: a bare "sparkle"
 * glyph gives no clue that it searches *by meaning*, which is the whole feature.
 */
@Composable
private fun RelatedChip(compact: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = FolioTheme.colors.surfaceVariant,
        shape = FolioShapes.chip,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = "More like this",
            style = if (compact) FolioTheme.typography.labelSmall else FolioTheme.typography.labelMedium,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        )
    }
}

/**
 * The suggestions list.
 *
 * Anchored to the bottom of the hub rather than pushed as a new screen: the reader keeps
 * their place in the quote list, which is what makes comparing the source passage against
 * its neighbours possible at all.
 */
@Composable
private fun RelatedPanel(state: RelatedState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Glass over content, like every other over-content panel — a sheet rising
    // from the bottom, so its top corners are rounded.
    val panelShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .folioVeil(panelShape)
            .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space2)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Passages like this one",
                style = FolioTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close suggestions")
            }
        }

        when (state) {
            RelatedState.Idle -> Unit
            RelatedState.Loading -> Text(
                "Looking for passages with a similar meaning…",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant
            )

            RelatedState.NotIndexed -> Text(
                "The semantic index is not built yet, so passages cannot be compared by " +
                    "meaning. Download the model in Settings, then index your library.",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant
            )

            is RelatedState.Failed -> Text(
                state.message,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.error
            )

            is RelatedState.Ready -> if (state.passages.isEmpty()) {
                Text(
                    "No other passage in your library reads like this one yet.",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.passages) { passage -> RelatedPassageRow(passage) }
                }
            }
        }
    }
}

@Composable
private fun RelatedPassageRow(passage: RelatedPassage) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "\u201C${passage.text}\u201D",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = listOfNotNull(passage.bookTitle, passage.chapterTitle)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = FolioTheme.typography.labelSmall,
            color = FolioTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
