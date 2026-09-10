package com.folio.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentCategory
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Series
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalFolioTopInset
import com.folio.reader.ui.theme.folioBarTopInset
import kotlinx.coroutines.launch

/** Top-level library category. Names are persisted, so existing values must remain stable. */
enum class LibraryMode { BOOKS, DOCUMENTS, MANGA }

internal val libraryModeDisplayOrder = listOf(
    LibraryMode.BOOKS,
    LibraryMode.MANGA,
    LibraryMode.DOCUMENTS
)

internal fun libraryModeDisplayIndex(mode: LibraryMode): Int =
    libraryModeDisplayOrder.indexOf(mode).coerceAtLeast(0)

internal fun libraryModeAtDisplayIndex(index: Int): LibraryMode =
    libraryModeDisplayOrder.getOrElse(index) { LibraryMode.BOOKS }

fun libraryModeFromPersistedName(name: String?): LibraryMode =
    LibraryMode.entries.firstOrNull { it.name == name } ?: LibraryMode.BOOKS

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
    documentLibraryViewModel: DocumentLibraryViewModel? = null,
    onDocumentImportClick: () -> Unit = {},
    onDocumentOpen: (Document) -> Unit = {},
    onDocumentDelete: (Document) -> Unit = {},
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
    onOpenStats: (() -> Unit)? = null
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
    // View mode, sort and (manga) shelf filters live in their own "Display" menu.
    // They used to share the overflow with Tags, Revisit, Browse sources, history
    // and both backup routes, which made a twenty-item menu that ran the height of
    // the screen — the manga one especially.
    var displayOpen by remember { mutableStateOf(false) }
    var mangaDisplayOpen by remember { mutableStateOf(false) }
    var seriesFilterOpen by remember { mutableStateOf(false) }
    var collectionFilterOpen by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }
    var documentPicker by remember { mutableStateOf<Pair<Document, Set<String>>?>(null) }
    var manageDocumentCategories by remember { mutableStateOf(false) }
    val documentCategories by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.categories
            ?: kotlinx.coroutines.flow.MutableStateFlow<List<DocumentCategory>>(emptyList())
    }.collectAsState(initial = emptyList())
    val selectedDocumentCategory by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.selectedCategoryId
            ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }.collectAsState(initial = null)
    val selectedDocumentIds by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.selectedIds
            ?: kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    }.collectAsState(initial = emptySet())
    val documentSelectionActive by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.isSelectionMode
            ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }.collectAsState(initial = false)
    val documentBulkInitial by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.bulkPickerInitial
            ?: kotlinx.coroutines.flow.MutableStateFlow<Set<String>?>(null)
    }.collectAsState(initial = null)
    val documentScope = rememberCoroutineScope()

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
    if (documentToDelete != null) {
        com.folio.reader.ui.components.ConfirmDialog(
            title = "Delete document",
            message = "Delete '${documentToDelete?.title}' and its local files?",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                documentToDelete?.let(onDocumentDelete)
                documentToDelete = null
            },
            onDismiss = { documentToDelete = null }
        )
    }
    if (manageDocumentCategories) {
        DocumentCategoryManagerDialog(
            categories = documentCategories,
            onCreate = { name -> documentScope.launch { documentLibraryViewModel?.createCategory(name) } },
            onRename = { id, name -> documentLibraryViewModel?.renameCategory(id, name) },
            onDelete = { id -> documentLibraryViewModel?.deleteCategory(id) },
            onDismiss = { manageDocumentCategories = false }
        )
    }
    documentPicker?.let { (document, initial) ->
        DocumentCategoryPickerDialog(
            categories = documentCategories,
            initialSelected = initial,
            onCreate = { name -> documentLibraryViewModel?.createCategory(name) },
            onApply = { documentLibraryViewModel?.setCategoriesFor(document.id, it) },
            onDismiss = { documentPicker = null }
        )
    }
    documentBulkInitial?.let { initial ->
        DocumentCategoryPickerDialog(
            categories = documentCategories,
            initialSelected = initial,
            onCreate = { name -> documentLibraryViewModel?.createCategory(name) },
            onApply = { documentLibraryViewModel?.applyBulkCategories(it) },
            onDismiss = { documentLibraryViewModel?.closeBulkPicker() }
        )
    }

    val allSeries by viewModel.allSeries().collectAsState(initial = emptyList())
    val allCollections by viewModel.allCollections().collectAsState(initial = emptyList())
    // §5.1: pace captions keyed by book id. Empty unless the host supplied a
    // session repository, so callers that don't want the extra read pay nothing.
    val finishEstimates by remember(viewModel) { viewModel.finishEstimates() }
        .collectAsState(initial = emptyMap())

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

    val documentState by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.state ?: kotlinx.coroutines.flow.MutableStateFlow(DocumentLibraryState())
    }.collectAsState()
    val mangaMode = libraryMode == LibraryMode.MANGA
    val documentMode = libraryMode == LibraryMode.DOCUMENTS
    // The masthead collapses off whatever the shelf below it consumed, so the grid
    // dissolves into the bar the way Home's hero does instead of sliding under a
    // fixed slab of chrome.
    val headerState = com.folio.reader.ui.components.rememberFolioHeaderState()
    // The collapse is a sticky accumulator, so a shelf scrolled down hands its
    // collapse to the shelf that replaces it — the Books/Manga switch would arrive
    // still folded under the bar exactly when it is the thing you need. Raising the
    // masthead on every shelf swap re-anchors it; the shelf keeps its own scroll.
    androidx.compose.runtime.LaunchedEffect(libraryMode) {
        headerState.reset()
    }
    // A green tick with a red "1" on it was the loudest object in the bar and said
    // nothing a reader can act on. The badge now appears only when sync actually
    // wants attention.
    val syncNeedsAttention = syncState != null && syncState.isConfigured && (
        syncState.isSyncing ||
            syncState.lastError != null ||
            syncState.quotaLimited ||
            (syncState.pendingUploadCount + syncState.pendingDownloadCount) > 0
        )

    // The books rail: the Books/Manga switch, then the status and grouping filters,
    // on one scrollable row. Built here so the masthead can fold it away as a unit.
    // Measured on the content, not the folding box the masthead wraps it in — that
    // one reports a shrinking height as the shelf scrolls, by design.
    var railPx by remember { mutableIntStateOf(0) }
    val railContent: (@Composable () -> Unit)? = when (libraryMode) {
        LibraryMode.MANGA -> null
        LibraryMode.DOCUMENTS -> ({
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { railPx = it.height }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                if (maxWidth < 600.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LibraryModeSwitch(libraryMode, onLibraryModeChange, Modifier.fillMaxWidth())
                        DocumentCategoryRail(
                            categories = documentCategories,
                            selectedCategoryId = selectedDocumentCategory,
                            onSelect = { documentLibraryViewModel?.selectCategory(it) },
                            onManage = { manageDocumentCategories = true }
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = documentState.query,
                            onValueChange = { documentLibraryViewModel?.setQuery(it) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = documentLibraryViewModel != null,
                            singleLine = true,
                            placeholder = { Text("Search documents") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LibraryModeSwitch(libraryMode, onLibraryModeChange)
                        Spacer(Modifier.width(12.dp))
                        DocumentCategoryRail(
                            categories = documentCategories,
                            selectedCategoryId = selectedDocumentCategory,
                            onSelect = { documentLibraryViewModel?.selectCategory(it) },
                            onManage = { manageDocumentCategories = true },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = documentState.query,
                            onValueChange = { documentLibraryViewModel?.setQuery(it) },
                            modifier = Modifier.weight(1f),
                            enabled = documentLibraryViewModel != null,
                            singleLine = true,
                            placeholder = { Text("Search documents") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                        )
                    }
                }
            }
        })
        LibraryMode.BOOKS -> ({
            Box(Modifier.onSizeChanged { railPx = it.height }) {
                LibraryFilterChips(
                    filter = filter,
                    allSeries = allSeries,
                    allCollections = allCollections,
                    seriesFilterOpen = seriesFilterOpen,
                    collectionFilterOpen = collectionFilterOpen,
                    onFilterChange = { filter = it },
                    onSeriesFilterOpen = { seriesFilterOpen = it },
                    onCollectionFilterOpen = { collectionFilterOpen = it },
                    leading = if (mangaContent != null || documentLibraryViewModel != null) {
                        ({ LibraryModeSwitch(libraryMode, onLibraryModeChange) })
                    } else {
                        null
                    },
                )
            }
        })
    }

    // Published rather than imposed, exactly like LocalFolioBarInset at the bottom
    // edge: the shelves add it to their own contentPadding so their first row
    // clears the glass while everything past it scrolls underneath. They are
    // reached through the opaque `mangaContent` lambda, so it could not be passed.
    val topInset = folioBarTopInset(
        if (mangaMode) 0.dp else with(LocalDensity.current) { railPx.toDp() }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            documentMode && documentSelectionActive -> {
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${selectedDocumentIds.size} selected",
                    collapse = headerState.collapse,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                    navigationIcon = {
                        IconButton(onClick = { documentLibraryViewModel?.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { documentLibraryViewModel?.requestBulkCategories() }) {
                            Icon(Icons.Filled.Label, contentDescription = "Set categories")
                        }
                    }
                )
            }
            mangaMode && mangaSelActive -> {
                // Selection mode swaps the regular chrome for bulk actions in the same
                // bar — no extra block, no layout shift below.
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${mangaSelIds.size} selected",
                    collapse = headerState.collapse,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
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
            !mangaMode && !documentMode && isSelectionMode -> {
                // Books bulk-selection swaps the same bar, so the tab row below never moves.
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${selectedBooks.size} selected",
                    collapse = headerState.collapse,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
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
                // The screen's own name. "Folio" belongs on Home, where the mark and
                // the wordmark form the masthead; repeating it here left the library
                // as the only top-level screen that never said what it was.
                title = "Library",
                collapse = headerState.collapse,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                actions = {
                    if (syncState != null && syncNeedsAttention) {
                        com.folio.reader.ui.components.SyncStatusBadge(
                            syncState = syncState,
                            onClick = onSyncNow
                        )
                    }
                    IconButton(onClick = {
                        when (libraryMode) {
                            LibraryMode.BOOKS -> onSearchClick()
                            LibraryMode.DOCUMENTS -> Unit
                            LibraryMode.MANGA -> onMangaSearchClick()
                        }
                    }, enabled = !documentMode) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = {
                        when (libraryMode) {
                            LibraryMode.BOOKS -> onImportClick()
                            LibraryMode.DOCUMENTS -> onDocumentImportClick()
                            LibraryMode.MANGA -> onMangaImportClick()
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Import")
                    }
                    // Settings moved into the overflow. Five action slots plus the mark
                    // left the masthead ~80dp for its own name, which is how "Library"
                    // became "Lib…"; a destination belongs in a menu, not on the rail.
                    // Display: how the shelf is drawn and ordered. Splitting this out
                    // of the overflow is what got both menus back to a readable length.
                    Box {
                        IconButton(onClick = {
                            when (libraryMode) {
                                LibraryMode.BOOKS, LibraryMode.DOCUMENTS -> displayOpen = true
                                LibraryMode.MANGA -> mangaDisplayOpen = true
                            }
                        }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Display and sort")
                        }
                        when (libraryMode) {
                            LibraryMode.MANGA -> {
                            DropdownMenu(expanded = mangaDisplayOpen, onDismissRequest = { mangaDisplayOpen = false }) {
                                com.folio.reader.ui.components.FolioMenuLabel("View")
                                ViewModeRow(
                                    selected = mangaViewMode.ordinal,
                                    onSelect = { index ->
                                        onMangaViewModeChange(com.folio.reader.ui.manga.MangaViewMode.entries[index])
                                    },
                                )
                                com.folio.reader.ui.components.FolioMenuLabel("Sort")
                                com.folio.reader.ui.manga.MangaSortBy.entries.forEach { o ->
                                    val active = mangaLibraryViewModel?.sortBy?.value == o
                                    DropdownMenuItem(
                                        text = {
                                            Text(o.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface)
                                        },
                                        leadingIcon = { ViewCheck(active) },
                                        onClick = {
                                            mangaDisplayOpen = false
                                            mangaLibraryViewModel?.sortBy?.value = o
                                        },
                                    )
                                }
                                com.folio.reader.ui.components.FolioMenuLabel("Filter")
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
                            }
                            }
                            LibraryMode.DOCUMENTS -> {
                                DropdownMenu(
                                    expanded = displayOpen,
                                    onDismissRequest = { displayOpen = false },
                                    modifier = Modifier.heightIn(max = 420.dp)
                                ) {
                                    com.folio.reader.ui.components.FolioMenuLabel("View")
                                    ViewModeRow(
                                        selected = documentState.viewMode.ordinal,
                                        optionCount = DocumentViewMode.entries.size,
                                        onSelect = { index -> documentLibraryViewModel?.setViewMode(DocumentViewMode.entries[index]) },
                                    )
                                    com.folio.reader.ui.components.FolioMenuLabel("Sort")
                                    DocumentSortBy.entries.forEach { option ->
                                        val active = option == documentState.sortBy
                                        DropdownMenuItem(
                                            text = { Text(option.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface) },
                                            leadingIcon = { ViewCheck(active) },
                                            onClick = { displayOpen = false; documentLibraryViewModel?.setSort(option) },
                                        )
                                    }
                                    HorizontalDivider()
                                    com.folio.reader.ui.components.FolioMenuLabel("Format")
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "All formats",
                                                color = if (documentState.formatFilter == null) FolioTheme.colors.primary else FolioTheme.colors.onSurface
                                            )
                                        },
                                        leadingIcon = { ViewCheck(documentState.formatFilter == null) },
                                        onClick = { documentLibraryViewModel?.setFormatFilter(null) }
                                    )
                                    DocumentFormat.entries.forEach { format ->
                                        val active = format == documentState.formatFilter
                                        DropdownMenuItem(
                                            text = { Text(format.name, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface) },
                                            leadingIcon = { ViewCheck(active) },
                                            onClick = { documentLibraryViewModel?.setFormatFilter(format) }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (documentState.sortAscending) "Ascending" else "Descending") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (documentState.sortAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = FolioTheme.colors.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { displayOpen = false; documentLibraryViewModel?.toggleSortDirection() },
                                    )
                                }
                            }
                            LibraryMode.BOOKS -> {
                            DropdownMenu(expanded = displayOpen, onDismissRequest = { displayOpen = false }) {
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
                                com.folio.reader.ui.components.FolioMenuLabel("View")
                                ViewModeRow(
                                    selected = booksViewMode.ordinal,
                                    onSelect = { index ->
                                        onBooksViewModeChange(LibraryViewModel.ViewMode.entries[index])
                                    },
                                )
                                com.folio.reader.ui.components.FolioMenuLabel("Sort")
                                bookFriendlyNames.forEach { (option, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (option == sortBy) FolioTheme.colors.primary else FolioTheme.colors.onSurface,
                                            )
                                        },
                                        leadingIcon = { ViewCheck(option == sortBy) },
                                        onClick = { displayOpen = false; sortBy = option },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (sortAscending) "Ascending" else "Descending") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (sortAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = FolioTheme.colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    onClick = { displayOpen = false; sortAscending = !sortAscending },
                                )
                            }
                        }
                    }
                    }
                    // Overflow: destinations and one-off actions only.
                    Box {
                        if (!documentMode || showSettingsAction) {
                            IconButton(onClick = { if (mangaMode) mangaOverflowOpen = true else overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                        }
                        if (mangaMode) {
                            DropdownMenu(expanded = mangaOverflowOpen, onDismissRequest = { mangaOverflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Browse sources") },
                                    onClick = { mangaOverflowOpen = false; onMangaBrowseClick() },
                                )
                                // Downloads live on the manga rail (with a live queue
                                // count) — the overflow item was redundant.
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
                                    text = { Text("Import backup") },
                                    onClick = { mangaOverflowOpen = false; onMangaBackupImport() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export backup") },
                                    onClick = { mangaOverflowOpen = false; onMangaBackupExport() },
                                )
                                if (showSettingsAction) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { mangaOverflowOpen = false; onSettingsClick() },
                                    )
                                }
                            }
                        } else if (documentMode) {
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                if (showSettingsAction) {
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { overflowOpen = false; onSettingsClick() },
                                    )
                                }
                            }
                        } else {
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tags") },
                                    onClick = { overflowOpen = false; onTagManagerClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Revisit Items") },
                                    onClick = { overflowOpen = false; onRevisitClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Quotes") },
                                    onClick = { overflowOpen = false; onQuoteBrowserClick() }
                                )
                                // Desktop reaches Stats from here; the Android host has
                                // it in the nav capsule and passes null.
                                if (onOpenStats != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Stats") },
                                        onClick = { overflowOpen = false; onOpenStats() }
                                    )
                                }
                                if (showSettingsAction) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { overflowOpen = false; onSettingsClick() },
                                    )
                                }
                            }
                        }
                    }
                },
                // One rail instead of three stacked rows. The Library mode switch leads
                // the same scrollable row the filters live on, and the whole row folds
                // up under the bar as the shelf scrolls. In manga mode the shelf's own
                // rail carries the switch (MangaLibraryScreen.railLeading), so there is
                // still exactly one row either way.
                rail = railContent,
            )
            }
        }

        // The shelf passes its scroll up to the masthead through nested scroll, so no
        // grid or list had to hoist its own state to get the collapse. It now runs
        // full-bleed to the top of the window with the masthead floating over it —
        // the only arrangement in which there is anything behind the glass to see.
        //
        // No crossfade between the shelves: a fade disposes the outgoing shelf and
        // rebuilds the incoming one from scratch every toggle — scroll positions lost,
        // covers re-resolving — which is exactly what made the switch feel slow. Each
        // shelf keeps its saveable state (LazyGrid scroll, selection) through the
        // state holder, so a swap is a single-frame recomposition.
        val shelfStateHolder = rememberSaveableStateHolder()
        CompositionLocalProvider(LocalFolioTopInset provides topInset) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection),
            ) {
                when (libraryMode) {
                    LibraryMode.MANGA -> shelfStateHolder.SaveableStateProvider("manga") {
                        mangaContent?.invoke()
                    }
                    LibraryMode.DOCUMENTS -> shelfStateHolder.SaveableStateProvider("documents") {
                        DocumentLibraryContent(
                            state = documentState,
                            selectedIds = selectedDocumentIds,
                            isSelectionMode = documentSelectionActive,
                            onImport = onDocumentImportClick,
                            onOpen = onDocumentOpen,
                            onDelete = { documentToDelete = it },
                            onCategories = { document ->
                                documentScope.launch {
                                    val initial = documentLibraryViewModel
                                        ?.categoriesFor(document.id)
                                        ?: emptySet()
                                    documentPicker = document to initial
                                }
                            },
                            onToggleSelection = {
                                documentLibraryViewModel?.toggleSelection(it)
                            }
                        )
                    }
                    LibraryMode.BOOKS -> shelfStateHolder.SaveableStateProvider("books") {
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
                        finishEstimates = finishEstimates,
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
}

@Composable
private fun DocumentCategoryRail(
    categories: List<DocumentCategory>,
    selectedCategoryId: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(categories, key = { it.id }) { category ->
            com.folio.reader.ui.components.FolioChip(
                selected = selectedCategoryId == category.id,
                onClick = { onSelect(category.id) },
                label = category.name
            )
        }
        item {
            com.folio.reader.ui.components.FolioChip(
                selected = false,
                onClick = onManage,
                label = "Edit"
            )
        }
    }
}

@Composable
private fun DocumentCategoryManagerDialog(
    categories: List<DocumentCategory>,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newCategory by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("New category") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            val name = newCategory.trim()
                            if (name.isNotEmpty()) {
                                onCreate(name)
                                newCategory = ""
                            }
                        },
                        enabled = newCategory.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it.id }) { category ->
                        DocumentCategoryRow(
                            category = category,
                            canDelete = category.id != DocumentCategory.MAIN_ID || categories.size > 1,
                            onRename = { onRename(category.id, it) },
                            onDelete = { onDelete(category.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun DocumentCategoryRow(
    category: DocumentCategory,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember(category.id) { mutableStateOf(false) }
    var name by remember(category.id, category.name) { mutableStateOf(category.name) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onRename(name.trim())
                    editing = false
                }
            ) {
                Text("Save")
            }
        } else {
            Text(
                category.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { editing = true }) {
                Text("Rename")
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (canDelete) {
                        "Remove"
                    } else {
                        "Create another category before removing this one"
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentCategoryPickerDialog(
    categories: List<DocumentCategory>,
    initialSelected: Set<String>,
    onCreate: suspend (String) -> String?,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var newName by remember { mutableStateOf("") }

    fun apply(next: Set<String>) {
        selected = next
        onApply(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (categories.isEmpty()) {
                    Text(
                        "No categories yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                apply(
                                    if (category.id in selected) {
                                        selected - category.id
                                    } else {
                                        selected + category.id
                                    }
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = category.id in selected,
                            onCheckedChange = { checked ->
                                apply(
                                    if (checked) selected + category.id
                                    else selected - category.id
                                )
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    onCreate(name)?.let { apply(selected + it) }
                                }
                                newName = ""
                            }
                        },
                        enabled = newName.trim().isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun DocumentLibraryContent(
    state: DocumentLibraryState,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    onImport: () -> Unit,
    onOpen: (Document) -> Unit,
    onDelete: (Document) -> Unit,
    onCategories: (Document) -> Unit,
    onToggleSelection: (String) -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.isLoading || state.isImporting -> com.folio.reader.ui.components.LoadingPlaceholder(Modifier.fillMaxSize())
            state.errorMessage != null -> com.folio.reader.ui.components.EmptyState(
                icon = Icons.Filled.MenuBook,
                headline = "Couldn't load documents",
                body = state.errorMessage
            )
            state.items.isEmpty() -> com.folio.reader.ui.components.EmptyState(
                icon = Icons.Filled.MenuBook,
                headline = if (state.query.isBlank()) "No documents in category" else "No matching documents",
                body = if (state.query.isBlank()) "Import a document or choose another category" else "Try a different title, filename, author, description, or format",
                action = if (state.query.isBlank()) ({
                    Button(onClick = onImport) { Text("Import a document") }
                }) else null
            )
            else -> when (state.viewMode) {
                DocumentViewMode.GRID -> DocumentGrid(
                    items = state.items,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    onCategories = onCategories,
                    onToggleSelection = onToggleSelection
                )
                DocumentViewMode.LIST -> DocumentList(
                    items = state.items,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    onCategories = onCategories,
                    onToggleSelection = onToggleSelection
                )
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
    finishEstimates: Map<String, String>,
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
        // The filter chips now ride the masthead's rail (LibraryScreen's `railContent`),
        // where they fold away with it as the shelf scrolls, so the shelf itself starts
        // straight at the content. The filter/sort parameters below are still the ones
        // the rail and the Display menu write through.
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
                    isSelectionMode,
                    finishEstimates
                )

                LibraryViewModel.ViewMode.LIST -> BookList(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode,
                    finishEstimates
                )

                LibraryViewModel.ViewMode.COMPACT -> BookCompactList(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode,
                    finishEstimates
                )
            }
        }
    }
}

/**
 * The Books/Manga switch.
 *
 * Public because the manga shelf carries it on *its* rail: in manga mode the shelf
 * already needs a row for categories, downloads and the collection editor, so the
 * switch joins that row instead of adding a second one above it.
 *
 * A segmented track rather than two chips: the choice is exclusive, and one track
 * costs a third of the width two chips did — which is what let the rail hold the
 * switch and the filters at once.
 */
@Composable
fun LibraryModeSwitch(
    mode: LibraryMode,
    onModeChange: (LibraryMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.folio.reader.ui.components.FolioSegmented(
        options = listOf("Books", "Manga", "Documents"),
        selectedIndex = libraryModeDisplayIndex(mode),
        onSelect = { index -> onModeChange(libraryModeAtDisplayIndex(index)) },
        modifier = modifier,
    )
}

/**
 * View mode as one row of three marks at the head of the Display menu, instead of
 * three menu items with tick icons. The choice is visual, so the control is too —
 * and it saves two thirds of the vertical space those items used.
 */
@Composable
private fun ViewModeRow(selected: Int, optionCount: Int = 3, onSelect: (Int) -> Unit) {
    val shape = com.folio.reader.ui.theme.FolioShapes.pill
    val icons = listOf(
        Icons.Filled.GridView,
        Icons.AutoMirrored.Filled.ViewList,
        Icons.Filled.ViewAgenda,
    )
    val labels = listOf("Grid view", "List view", "Compact view")
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.take(optionCount).forEachIndexed { index, icon ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(
                        if (active) FolioTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
                        shape,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = labels[index],
                    tint = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
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
