package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Series
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.theme.FolioTheme

/** Top-level library category: books (EPUB) and manga are separate collections. */
enum class LibraryMode { BOOKS, MANGA }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Book) -> Unit,
    onBookDetailClick: (Book) -> Unit,
    onImportClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showSettingsAction: Boolean = true,
    onTagManagerClick: () -> Unit = {},
    onQuoteBrowserClick: () -> Unit = {},
    onRevisitClick: () -> Unit = {},
    onDeleteBooks: (Set<String>) -> Unit = {},
    onSetBookStatus: (Set<String>, BookStatus) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel,
    syncState: com.folio.reader.sync.SyncState? = null,
    onSyncNow: () -> Unit = {},
    libraryMode: LibraryMode = LibraryMode.BOOKS,
    onLibraryModeChange: (LibraryMode) -> Unit = {},
    booksViewMode: LibraryViewModel.ViewMode = LibraryViewModel.ViewMode.GRID,
    onBooksViewModeChange: (LibraryViewModel.ViewMode) -> Unit = {},
    mangaContent: (@Composable () -> Unit)? = null,
    mangaExtensionsAvailable: Boolean = false,
    onMangaBrowseClick: () -> Unit = {},
    onMangaExtensionsClick: () -> Unit = {},
    onMangaHistoryClick: () -> Unit = {},
    onMangaImportClick: () -> Unit = {},
    onMangaSearchClick: () -> Unit = {},
    onMangaBackupImport: () -> Unit = {},
    onMangaBackupExport: () -> Unit = {},
    mangaViewMode: com.folio.reader.ui.manga.MangaViewMode = com.folio.reader.ui.manga.MangaViewMode.GRID,
    onMangaViewModeChange: (com.folio.reader.ui.manga.MangaViewMode) -> Unit = {},
    mangaLibraryViewModel: com.folio.reader.ui.manga.MangaLibraryViewModel? = null,
    statsContent: (@Composable () -> Unit)? = null
) {
    var sortBy by remember { mutableStateOf(LibraryViewModel.SortBy.LAST_OPENED) }
    var sortAscending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LibraryViewModel.FilterState()) }
    // Selection lives in the view model so the host's system-back handler can
    // clear it instead of falling through and exiting the app.
    val selectedBooks by viewModel.selectedBookIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    var overflowOpen by remember { mutableStateOf(false) }
    var mangaOverflowOpen by remember { mutableStateOf(false) }
    var seriesFilterOpen by remember { mutableStateOf(false) }
    var collectionFilterOpen by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    val showStats by viewModel.statsVisible.collectAsState()

    val mangaSelActive by remember {
        mangaLibraryViewModel?.isSelectionMode ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }.collectAsState(initial = false)
    val mangaSelIds by remember {
        mangaLibraryViewModel?.selectedIds ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    }.collectAsState(initial = emptySet<String>())
    val mangaActiveFilters by remember {
        mangaLibraryViewModel?.activeFilters
            ?: kotlinx.coroutines.flow.MutableStateFlow<Set<com.folio.reader.ui.manga.MangaLibFilter>>(emptySet())
    }.collectAsState(initial = emptySet<com.folio.reader.ui.manga.MangaLibFilter>())

    if (bookToDelete != null) {
        com.folio.reader.ui.components.ConfirmDialog(
            title = "Delete Book",
            message = "Are you sure you want to delete '${bookToDelete?.title}'? This will remove the book and its local files.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = { bookToDelete?.let { onDeleteBooks(setOf(it.id)) } },
            onDismiss = { bookToDelete = null }
        )
    }

    val allSeries by viewModel.allSeries().collectAsState(initial = emptyList())
    val allCollections by viewModel.allCollections().collectAsState(initial = emptyList())

    val books by viewModel.filteredBooks(
        LibraryViewModel.LibraryState(
            viewMode = booksViewMode,
            sortBy = sortBy,
            sortAscending = sortAscending,
            filter = filter,
            selectedBookIds = selectedBooks,
            isSelectionMode = isSelectionMode
        )
    ).collectAsState(initial = null as List<Book>?)

    val mangaMode = libraryMode == LibraryMode.MANGA && mangaContent != null

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            mangaMode && mangaSelActive -> {
                // Selection mode swaps the regular chrome for bulk actions in the same
                // bar — no extra block, no layout shift below.
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${mangaSelIds.size} selected",
                    navigationIcon = {
                        IconButton(onClick = { mangaLibraryViewModel?.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { mangaLibraryViewModel?.requestBulkCategories() }) {
                            Icon(Icons.Filled.Label, contentDescription = "Set categories")
                        }
                        IconButton(onClick = { mangaLibraryViewModel?.markSelectedRead(true) }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Mark read")
                        }
                        IconButton(onClick = { mangaLibraryViewModel?.markSelectedRead(false) }) {
                            Icon(Icons.Filled.MenuBook, contentDescription = "Mark unread")
                        }
                        IconButton(onClick = { mangaLibraryViewModel?.removeSelected() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove from library", tint = FolioTheme.colors.error)
                        }
                    },
                )
            }
            !mangaMode && isSelectionMode -> {
                // Books bulk-selection swaps the same bar, so the tab row below never moves.
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${selectedBooks.size} selected",
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            onSetBookStatus(selectedBooks, BookStatus.READING)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Mark as reading")
                        }
                        IconButton(onClick = {
                            onSetBookStatus(selectedBooks, BookStatus.FINISHED)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Mark as finished")
                        }
                        IconButton(onClick = {
                            onDeleteBooks(selectedBooks)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete books", tint = FolioTheme.colors.error)
                        }
                    },
                )
            }
            else -> {
            com.folio.reader.ui.components.FolioTopBar(
                title = "Folio",
                actions = {
                    if (syncState != null) {
                        com.folio.reader.ui.components.SyncStatusBadge(
                            syncState = syncState,
                            onClick = onSyncNow
                        )
                    }
                    IconButton(onClick = { if (mangaMode && !showStats) onMangaSearchClick() else onSearchClick() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { if (mangaMode && !showStats) onMangaImportClick() else onImportClick() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Import")
                    }
                    if (showSettingsAction) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                    Box {
                        IconButton(onClick = { if (mangaMode && !showStats) mangaOverflowOpen = true else overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        if (mangaMode && !showStats) {
                            DropdownMenu(expanded = mangaOverflowOpen, onDismissRequest = { mangaOverflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Grid view") },
                                    leadingIcon = { ViewCheck(mangaViewMode == com.folio.reader.ui.manga.MangaViewMode.GRID) },
                                    onClick = { mangaOverflowOpen = false; onMangaViewModeChange(com.folio.reader.ui.manga.MangaViewMode.GRID) },
                                )
                                DropdownMenuItem(
                                    text = { Text("List view") },
                                    leadingIcon = { ViewCheck(mangaViewMode == com.folio.reader.ui.manga.MangaViewMode.LIST) },
                                    onClick = { mangaOverflowOpen = false; onMangaViewModeChange(com.folio.reader.ui.manga.MangaViewMode.LIST) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Compact view") },
                                    leadingIcon = { ViewCheck(mangaViewMode == com.folio.reader.ui.manga.MangaViewMode.COMPACT) },
                                    onClick = { mangaOverflowOpen = false; onMangaViewModeChange(com.folio.reader.ui.manga.MangaViewMode.COMPACT) },
                                )
                                HorizontalDivider()
                                com.folio.reader.ui.manga.MangaSortBy.entries.forEach { o ->
                                    val active = mangaLibraryViewModel?.sortBy?.value == o
                                    DropdownMenuItem(
                                        text = {
                                            Text(o.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface)
                                        },
                                        leadingIcon = { ViewCheck(active) },
                                        onClick = {
                                            mangaOverflowOpen = false
                                            mangaLibraryViewModel?.sortBy?.value = o
                                        },
                                    )
                                }
                                HorizontalDivider()
                                com.folio.reader.ui.manga.MangaLibFilter.entries.forEach { f ->
                                    val active = f in mangaActiveFilters
                                    DropdownMenuItem(
                                        text = {
                                            Text(f.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface)
                                        },
                                        leadingIcon = { ViewCheck(active) },
                                        // Menu stays open so several filters can be combined in one go.
                                        onClick = { mangaLibraryViewModel?.toggleFilter(f) },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Browse sources") },
                                    onClick = { mangaOverflowOpen = false; onMangaBrowseClick() },
                                )
                                // Downloads moved to the chip row on the manga shelf (with a
                                // live queue count) — the overflow item was redundant.
                                DropdownMenuItem(
                                    text = { Text("Reading history") },
                                    onClick = { mangaOverflowOpen = false; onMangaHistoryClick() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Check for new chapters") },
                                    onClick = { mangaOverflowOpen = false; mangaLibraryViewModel?.updateLibrary() },
                                )
                                if (mangaExtensionsAvailable) {
                                    DropdownMenuItem(
                                        text = { Text("Extensions") },
                                        onClick = { mangaOverflowOpen = false; onMangaExtensionsClick() },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Import Mihon backup") },
                                    onClick = { mangaOverflowOpen = false; onMangaBackupImport() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Mihon backup") },
                                    onClick = { mangaOverflowOpen = false; onMangaBackupExport() },
                                )
                            }
                        } else {
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                val bookFriendlyNames = linkedMapOf(
                                    LibraryViewModel.SortBy.LAST_OPENED to "Recently opened",
                                    LibraryViewModel.SortBy.DATE_ADDED to "Date added",
                                    LibraryViewModel.SortBy.TITLE to "Title",
                                    LibraryViewModel.SortBy.AUTHOR to "Author",
                                    LibraryViewModel.SortBy.PROGRESS to "Progress",
                                    LibraryViewModel.SortBy.READING_TIME to "Length",
                                    LibraryViewModel.SortBy.COMPLETION_DATE to "Completion date",
                                    LibraryViewModel.SortBy.FILE_SIZE to "File size",
                                )
                                DropdownMenuItem(
                                    text = { Text("Grid view") },
                                    leadingIcon = { ViewCheck(booksViewMode == LibraryViewModel.ViewMode.GRID) },
                                    onClick = { overflowOpen = false; onBooksViewModeChange(LibraryViewModel.ViewMode.GRID) },
                                )
                                DropdownMenuItem(
                                    text = { Text("List view") },
                                    leadingIcon = { ViewCheck(booksViewMode == LibraryViewModel.ViewMode.LIST) },
                                    onClick = { overflowOpen = false; onBooksViewModeChange(LibraryViewModel.ViewMode.LIST) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Compact view") },
                                    leadingIcon = { ViewCheck(booksViewMode == LibraryViewModel.ViewMode.COMPACT) },
                                    onClick = { overflowOpen = false; onBooksViewModeChange(LibraryViewModel.ViewMode.COMPACT) },
                                )
                                HorizontalDivider()
                                bookFriendlyNames.forEach { (option, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (option == sortBy) FolioTheme.colors.primary else FolioTheme.colors.onSurface,
                                            )
                                        },
                                        leadingIcon = { ViewCheck(option == sortBy) },
                                        onClick = { overflowOpen = false; sortBy = option },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (sortAscending) "Ascending" else "Descending") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = if (sortAscending) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    onClick = { overflowOpen = false; sortAscending = !sortAscending },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Tags") },
                                    onClick = { overflowOpen = false; onTagManagerClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Revisit Items") },
                                    onClick = { overflowOpen = false; onRevisitClick() }
                                )
                            }
                        }
                    }
                }
            )
            }
        }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(vertical = com.folio.reader.ui.theme.FolioTokens.space1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.folio.reader.ui.components.FolioChip(
                    selected = !mangaMode && !showStats,
                    onClick = { viewModel.statsVisible.value = false; if (mangaMode) onLibraryModeChange(LibraryMode.BOOKS) },
                    label = "Books",
                )
                com.folio.reader.ui.components.FolioChip(
                    selected = mangaMode && !showStats,
                    onClick = { viewModel.statsVisible.value = false; if (!mangaMode) onLibraryModeChange(LibraryMode.MANGA) },
                    label = "Manga",
                )
                if (statsContent != null) {
                    com.folio.reader.ui.components.FolioChip(
                        selected = showStats,
                        onClick = { viewModel.statsVisible.value = true },
                        label = "Stats",
                    )
                }
            }

            if (showStats && statsContent != null) {
                statsContent.invoke()
            } else {
            androidx.compose.animation.Crossfade(
                targetState = libraryMode,
                modifier = Modifier.fillMaxSize(),
            ) { mode ->
            if (mode == LibraryMode.MANGA) {
                mangaContent?.invoke()
            } else {
                LibraryContent(
                    books = books,
                    viewMode = booksViewMode,
                    sortBy = sortBy,
                    sortAscending = sortAscending,
                    filter = filter,
                    allSeries = allSeries,
                    allCollections = allCollections,
                    selectedBooks = selectedBooks,
                    isSelectionMode = isSelectionMode,
                    onViewMode = onBooksViewModeChange,
                    onSortChange = { sortBy = it },
                    onDirectionChange = { sortAscending = it },
                    onFilterChange = { filter = it },
                    onSeriesFilterOpen = { seriesFilterOpen = it },
                    onCollectionFilterOpen = { collectionFilterOpen = it },
                    seriesFilterOpen = seriesFilterOpen,
                    collectionFilterOpen = collectionFilterOpen,
                    onBookClick = { if (isSelectionMode) viewModel.toggleSelection(it.id) else onBookDetailClick(it) },
                    onBookLongClick = { viewModel.toggleSelection(it.id) },
                    onDeleteBook = { bookToDelete = it },
                    onImportClick = onImportClick
                )
            }
            }
            }
        }
}

@Composable
private fun LibraryContent(
    books: List<Book>?,
    viewMode: LibraryViewModel.ViewMode,
    sortBy: LibraryViewModel.SortBy,
    sortAscending: Boolean,
    filter: LibraryViewModel.FilterState,
    allSeries: List<Series>,
    allCollections: List<FolioCollection>,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean,
    onViewMode: (LibraryViewModel.ViewMode) -> Unit,
    onSortChange: (LibraryViewModel.SortBy) -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onFilterChange: (LibraryViewModel.FilterState) -> Unit,
    onSeriesFilterOpen: (Boolean) -> Unit,
    onCollectionFilterOpen: (Boolean) -> Unit,
    seriesFilterOpen: Boolean,
    collectionFilterOpen: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onImportClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips: status + series + collection (horizontally scrollable)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.statuses.contains(BookStatus.READING),
                    onClick = {
                        onFilterChange(
                            if (filter.statuses.contains(BookStatus.READING)) filter.copy(statuses = filter.statuses - BookStatus.READING)
                            else filter.copy(statuses = filter.statuses + BookStatus.READING)
                        )
                    },
                    label = "Reading"
                )
            }
            item {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.statuses.contains(BookStatus.FINISHED),
                    onClick = {
                        onFilterChange(
                            if (filter.statuses.contains(BookStatus.FINISHED)) filter.copy(statuses = filter.statuses - BookStatus.FINISHED)
                            else filter.copy(statuses = filter.statuses + BookStatus.FINISHED)
                        )
                    },
                    label = "Finished"
                )
            }
            item {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.statuses.contains(BookStatus.UNREAD),
                    onClick = {
                        onFilterChange(
                            if (filter.statuses.contains(BookStatus.UNREAD)) filter.copy(statuses = filter.statuses - BookStatus.UNREAD)
                            else filter.copy(statuses = filter.statuses + BookStatus.UNREAD)
                        )
                    },
                    label = "Unread"
                )
            }

            item {
                Box {
                    com.folio.reader.ui.components.FolioChip(
                        selected = filter.seriesId != null,
                        onClick = { onSeriesFilterOpen(true) },
                        label = allSeries.firstOrNull { it.id == filter.seriesId }?.name ?: "Series"
                    )
                    DropdownMenu(
                        expanded = seriesFilterOpen,
                        onDismissRequest = { onSeriesFilterOpen(false) },
                        offset = DpOffset(0.dp, 36.dp)
                    ) {
                        DropdownMenuItem(text = { Text("All series") }, onClick = {
                            onFilterChange(filter.copy(seriesId = null)); onSeriesFilterOpen(false)
                        })
                        allSeries.forEach { series ->
                            DropdownMenuItem(text = { Text(series.name) }, onClick = {
                                onFilterChange(filter.copy(seriesId = series.id)); onSeriesFilterOpen(false)
                            })
                        }
                        if (allSeries.isEmpty()) {
                            DropdownMenuItem(text = { Text("No series yet") }, onClick = { onSeriesFilterOpen(false) })
                        }
                    }
                }
            }

            item {
                Box {
                    com.folio.reader.ui.components.FolioChip(
                        selected = filter.collectionId != null,
                        onClick = { onCollectionFilterOpen(true) },
                        label = allCollections.firstOrNull { it.id == filter.collectionId }?.name ?: "Collections"
                    )
                    DropdownMenu(
                        expanded = collectionFilterOpen,
                        onDismissRequest = { onCollectionFilterOpen(false) },
                        offset = DpOffset(0.dp, 36.dp)
                    ) {
                        DropdownMenuItem(text = { Text("All collections") }, onClick = {
                            onFilterChange(filter.copy(collectionId = null)); onCollectionFilterOpen(false)
                        })
                        allCollections.forEach { collection ->
                            DropdownMenuItem(text = { Text(collection.name) }, onClick = {
                                onFilterChange(filter.copy(collectionId = collection.id)); onCollectionFilterOpen(false)
                            })
                        }
                        if (allCollections.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No collections yet") },
                                onClick = { onCollectionFilterOpen(false) })
                        }
                    }
                }
            }

            if (filter.hasFilters()) {
                item {
                    // Must be a chip, not a TextButton: the button is taller, and being
                    // the only conditional item it changed the row's measured height as it
                    // scrolled in and out of composition, nudging every other chip.
                    com.folio.reader.ui.components.FolioChip(
                        selected = false,
                        onClick = { onFilterChange(LibraryViewModel.FilterState()) },
                        label = "Clear"
                    )
                }
            }
        }

        if (books == null) {
            com.folio.reader.ui.components.LoadingPlaceholder(modifier = Modifier.fillMaxSize())
        } else if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                com.folio.reader.ui.components.EmptyState(
                    icon = Icons.Filled.MenuBook,
                    headline = "No books in library",
                    body = "Import your first EPUB to get started",
                    action = {
                        Button(onClick = onImportClick) {
                            Text("Import EPUB")
                        }
                    }
                )
            }
        } else {
            when (viewMode) {
                LibraryViewModel.ViewMode.GRID -> BookGrid(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode
                )

                LibraryViewModel.ViewMode.LIST -> BookList(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode
                )

                LibraryViewModel.ViewMode.COMPACT -> BookCompactList(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode
                )
            }
        }
    }
}

@Composable
private fun ViewCheck(active: Boolean) {
    if (active) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.Check,
            contentDescription = null,
            tint = FolioTheme.colors.primary,
            modifier = Modifier.size(18.dp),
        )
    } else {
        Spacer(modifier = Modifier.size(18.dp))
    }
}
