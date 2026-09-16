package com.folio.reader.ui.manga

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioTabReselect
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MangaLibraryScreen(
    viewModel: MangaLibraryViewModel,
    backend: MangaBackend,
    supportsExtensions: Boolean,
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
    onImportLocal: () -> Unit,
    viewMode: MangaViewMode = MangaViewMode.GRID,
    onViewModeChange: (MangaViewMode) -> Unit = {},
    searchActive: Boolean = false,
    onSearchActiveChange: (Boolean) -> Unit = {},
    browseViewModel: BrowseViewModel? = null,
    onOpenSource: (MangaSourceInfo, String) -> Unit = { _, _ -> },
    onRemoveManga: ((String) -> Unit)? = null,
    /**
     * Leading element on the shelf's own chip rail — the host passes the Books/Manga
     * switch here so manga has one rail rather than a mode row stacked over a
     * category row. Null keeps the rail exactly as it was.
     */
    railLeading: (@Composable () -> Unit)? = null,
) {
    val visible by viewModel.visible.collectAsState()
    val library by viewModel.library.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val activeFilters by viewModel.activeFilters.collectAsState()
    val unread by viewModel.unreadCounts.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val lastRead by viewModel.lastRead.collectAsState()
    val downloaded by viewModel.downloadedCounts.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategoryId.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val activeDownloads by viewModel.activeDownloadCount.collectAsState()
    val updatingLibrary by viewModel.updating.collectAsState()
    val newChapters by viewModel.lastNewChapters.collectAsState()
    val updatedSeriesCount by viewModel.recentlyUpdated.collectAsState()
    var manageCollectionsOpen by remember { mutableStateOf(false) }
    val bulkCategoryInitial by viewModel.bulkPickerInitial.collectAsState()
    var singlePickerManga by remember { mutableStateOf<MangaEntry?>(null) }
    var singlePickerInitial by remember { mutableStateOf<Set<String>?>(null) }
    val libraryScope = rememberCoroutineScope()
    val searchingSources = searchActive && searchScope == MangaSearchScope.SOURCES && browseViewModel != null

    // The periodic library update is otherwise invisible; when it lands new chapters,
    // show the count for a few seconds, then fold the strip away again.
    var showNewChaptersNotice by remember { mutableStateOf(false) }
    LaunchedEffect(newChapters, updatedSeriesCount) {
        if (newChapters > 0) {
            showNewChaptersNotice = true
            kotlinx.coroutines.delay(5000)
            showNewChaptersNotice = false
        }
    }

    // Restore scroll positions from the hoisted ViewModel; save on dispose so navigating
    // to a detail screen and back lands exactly where the reader left off.
    val safeListIndex = viewModel.listScrollIndex.coerceAtLeast(0)
    val safeListOffset = viewModel.listScrollOffset.coerceAtLeast(0)
    val listState = remember(viewModel) { LazyListState(safeListIndex, safeListOffset) }
    val safeGridIndex = viewModel.gridScrollIndex.coerceAtLeast(0)
    val safeGridOffset = viewModel.gridScrollOffset.coerceAtLeast(0)
    val gridState = remember(viewModel) { LazyGridState(safeGridIndex, safeGridOffset) }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.listScrollIndex = listState.firstVisibleItemIndex
            viewModel.listScrollOffset = listState.firstVisibleItemScrollOffset
            viewModel.gridScrollIndex = gridState.firstVisibleItemIndex
            viewModel.gridScrollOffset = gridState.firstVisibleItemScrollOffset
        }
    }

    // A restored scroll index can outlive the list it indexed. The shelf shrinks
    // under it constantly: the feature slot pulls one series out of `rest`, a
    // category switch swaps the whole set, and removing a series drops the count.
    // A LazyGrid/LazyColumn created at an index past the end lays out an empty
    // viewport — no items, nothing to fling, and no empty state either, because
    // `visible` is not empty — which is exactly the blank shelf that a rotation
    // "fixed": the dispose wrote back a clamped index and the rebuilt state
    // landed in range. Clamp it here instead of relying on an accident.
    LaunchedEffect(visible.size, viewMode) {
        if (visible.isEmpty()) return@LaunchedEffect
        val last = visible.lastIndex
        if (viewMode == MangaViewMode.LIST || viewMode == MangaViewMode.COMPACT) {
            if (listState.firstVisibleItemIndex > last) listState.scrollToItem(0)
        } else if (gridState.firstVisibleItemIndex > last) {
            gridState.scrollToItem(0)
        }
    }

    // Re-tap on the Library nav item scrolls whichever shelf view is showing
    // back to its top. The other two shelves (books/documents) collect the same
    // event through their own hosts.
    LaunchedEffect(viewModel) {
        FolioTabReselect.events.collect { (route, _) ->
            if (route == "library") {
                if (viewMode == MangaViewMode.LIST || viewMode == MangaViewMode.COMPACT) {
                    if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                        listState.animateScrollToItem(0)
                    }
                } else {
                    if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                        gridState.animateScrollToItem(0)
                    }
                }
            }
        }
    }

    // The host owns the search bar's visibility; the ViewModel mirrors it so the
    // library filter is only applied while search is actually open.
    LaunchedEffect(searchActive) {
        viewModel.searchActive.value = searchActive
        if (!searchActive) browseViewModel?.exitSearch()
    }

    // Drive the global source search from the shared query text. Re-keyed on scope and
    // visibility too, so switching to All sources resumes the same query the library
    // scope was showing. When the scope switches away from SOURCES we simply stop;
    // exitSearch() is only called when the search bar itself closes (above) so cached
    // results survive scope toggles.
    LaunchedEffect(searchingSources, query) {
        val browseVm = browseViewModel ?: return@LaunchedEffect
        if (!searchingSources) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        val trimmed = query.trim()
        browseVm.globalSearch(if (trimmed.length >= 2) trimmed else "")
    }

    // The shelf runs full-bleed to the top of the window and the masthead floats over
    // it, so covers genuinely pass behind the glass. The rail and the new-chapters
    // notice float with it as one grounded band: they are chrome, and chrome that lets
    // rows show through its own search field reads as noise rather than as depth.
    val barInset = LocalFolioTopInset.current
    // Measured on the furniture, never on the bar: the bar grows a hairline and a 10dp
    // fade once it collapses, and an inset that tracked them would walk every cover up
    // the screen under the reader's finger.
    var furniturePx by remember { mutableIntStateOf(0) }
    val topInset = barInset + with(LocalDensity.current) { furniturePx.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .fillMaxWidth()
                .padding(top = barInset)
                .onSizeChanged { furniturePx = it.height },
        ) {
            if (searchActive) {
                MangaSearchHeader(
                    scope = searchScope,
                    onScopeChange = { next ->
                        if (next != searchScope) viewModel.searchScope.value = next
                    },
                    query = query,
                    onQueryChange = { viewModel.query.value = it },
                    sourcesAvailable = browseViewModel != null,
                    onClose = { onSearchActiveChange(false) },
                    // The Books/Manga switch stays at the head of the row while
                    // searching: the old header replaced the whole rail, which
                    // took the selector with it and left search as the only way
                    // out. It now leads the field exactly as it leads the
                    // category chips below.
                    leading = railLeading,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (railLeading != null) {
                        item { railLeading() }
                    }
                    items(categories) { category ->
                        FolioChip(
                            selected = selectedCategory == category.id,
                            onClick = { viewModel.selectCategory(category.id) },
                            label = category.name,
                        )
                    }
                    item {
                        FolioChip(
                            selected = false,
                            onClick = { manageCollectionsOpen = true },
                            label = "Edit",
                        )
                    }
                    item {
                        // Queue entry point for the whole manga side (it replaced the
                        // overflow-menu item): a live count while anything is queued or
                        // downloading, plain otherwise so storage settings stay reachable.
                        FolioChip(
                            selected = false,
                            onClick = onOpenDownloads,
                            label = if (activeDownloads > 0) "Downloads · $activeDownloads" else "Downloads",
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (!searchActive) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = updatingLibrary || showNewChaptersNotice,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(tween(200)),
                    exit = androidx.compose.animation.fadeOut(tween(300)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FolioTokens.space3)
                            .glassPanel(RoundedCornerShape(FolioTokens.radiusChip))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (updatingLibrary) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = FolioTheme.colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Checking for new chapters…",
                                style = FolioTheme.typography.labelMedium,
                                color = FolioTheme.colors.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                Icons.Filled.LibraryAddCheck,
                                contentDescription = null,
                                tint = FolioTheme.colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            val chapterWord = if (newChapters == 1) "chapter" else "chapters"
                            Text(
                                if (updatedSeriesCount.size == 1) "$newChapters new $chapterWord"
                                else "$newChapters new $chapterWord in ${updatedSeriesCount.size} series",
                                style = FolioTheme.typography.labelMedium,
                                color = FolioTheme.colors.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        val browseVm = browseViewModel
        if (searchingSources && browseVm != null) {
            Box(Modifier.fillMaxSize().padding(top = topInset)) {
                SourceSearchResults(
                    viewModel = browseVm,
                    onOpenManga = onOpenManga,
                    onOpenSource = { source -> onOpenSource(source, query.trim()) },
                )
            }
        } else if (visible.isEmpty() && !isSelectionMode && !ready) {
            // First frames after the shelf (re)mounts: the flows have not landed
            // yet, and flashing the empty state here read as "another version of
            // the same screen" on every mode switch.
            Box(Modifier.fillMaxSize().padding(top = topInset)) {
                com.folio.reader.ui.components.LoadingPlaceholder(modifier = Modifier.fillMaxSize())
            }
        } else if (visible.isEmpty() && !isSelectionMode) {
            Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
                if (searchActive && query.isNotBlank()) {
                    com.folio.reader.ui.components.EmptyState(
                        icon = Icons.Filled.Search,
                        headline = "No matches in your library"
                    )
                } else {
                    // The library and the shelf are different empties: a shelf can
                    // be empty because everything lives in another category, and
                    // "your library is empty" + a Browse button would be a lie there.
                    // Read from the collected state, never viewModel.library.value:
                    // a raw .value read is not a snapshot subscription, so when the
                    // library loaded *without* changing `visible` (the membership set
                    // is empty — precisely the blank-shelf case) nothing invalidated
                    // this branch and it kept claiming the library was empty.
                    val libraryEmpty = library.isEmpty()
                    // A filter is the third, and worst, way this shelf empties: the
                    // quick filters live in the Display-and-sort dropdown, so an
                    // active Unread/Reading/Completed filter leaves no mark on the
                    // rail. Reading one chapter moves a series from progress 0 to
                    // >0 and the Unread filter then drops it mid-session — the
                    // "it was there, I read a chapter, it vanished" report — with a
                    // blank shelf and nothing on screen explaining why. Name the
                    // filter and give it a one-tap escape.
                    val filtered = activeFilters.any { it != MangaLibFilter.DOWNLOADED }
                    com.folio.reader.ui.components.EmptyState(
                        icon = Icons.Filled.MenuBook,
                        headline = when {
                            filtered -> "No manga match your filters"
                            libraryEmpty -> "Your manga library is empty"
                            else -> "No manga in this category"
                        },
                        body = when {
                            filtered -> activeFilters
                                .filter { it != MangaLibFilter.DOWNLOADED }
                                .joinToString(", ") { it.label }
                                .let { "Active: $it" }
                            libraryEmpty -> "Browse sources or import CBZ files to get started."
                            else -> "Move manga here or choose another category."
                        },
                        action = when {
                            filtered -> ({
                                Button(onClick = { viewModel.setQuickFilter(null) }) {
                                    Text("Clear filters")
                                }
                            })
                            libraryEmpty -> ({ Button(onClick = onOpenBrowse) { Text("Browse") } })
                            else -> null
                        }
                    )
                }
            }
        } else if (viewMode == MangaViewMode.LIST || viewMode == MangaViewMode.COMPACT) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = FolioTokens.space2 + topInset,
                    bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
                ),
            ) {
                items(visible, key = { it.id }) { manga ->
                    val prog = progress[manga.id] ?: 0f
                    MangaListItem(
                        manga = manga,
                        backend = backend,
                        unreadCount = unread[manga.id] ?: 0,
                        progress = prog,
                        fullyRead = prog >= 1f,
                        compact = viewMode == MangaViewMode.COMPACT,
                        selected = manga.id in selectedIds,
                        inSelectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) viewModel.toggleSelection(manga.id) else onOpenManga(manga.id)
                        },
                        onLongClick = { viewModel.toggleSelection(manga.id) },
                        onRemove = {
                            onRemoveManga?.invoke(manga.id)
                                ?: viewModel.removeFromLibrary(manga.id)
                        },
                        onMarkRead = { read -> viewModel.markOneRead(manga.id, read) },
                        onCategories = {
                            singlePickerManga = manga
                            singlePickerInitial = null
                            libraryScope.launch {
                                singlePickerInitial = viewModel.categoriesFor(manga.id)
                            }
                        },
                    )
                }
            }
        } else {
            // The feature slot goes to the first in-progress manga in the current
            // (already sorted and filtered) list, so it always reflects the user's
            // own ordering.
            val featured = remember(visible) {
                visible.firstOrNull { (progress[it.id] ?: 0f) > 0f && (progress[it.id] ?: 0f) < 0.99f }
            }
            val showFeature = featured != null && !isSelectionMode
            val rest = remember(visible, featured, showFeature) {
                if (showFeature) visible.filter { it.id != featured!!.id } else visible
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 116.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = FolioTokens.gutter,
                    end = FolioTokens.gutter,
                    top = FolioTokens.space3 + topInset,
                    bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
                ),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceBeat),
            ) {
                if (showFeature) {
                    // maxLineSpan, never Int.MAX_VALUE: a span wider than the line
                    // makes the cell compute to zero width — the hero composes as a
                    // 0×0 node and the shelf reads as blank (the books grid's
                    // FeaturedShelfEntry uses maxLineSpan and renders).
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val prog = progress[featured!!.id] ?: 0f
                        FeaturedMangaShelfEntry(
                            manga = featured!!,
                            backend = backend,
                            unreadCount = unread[featured.id] ?: 0,
                            progress = prog,
                            fullyRead = prog >= 1f,
                            isSelected = featured.id in selectedIds,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) viewModel.toggleSelection(featured.id) else onOpenManga(featured.id)
                            },
                            onLongClick = { viewModel.toggleSelection(featured.id) },
                            onRemove = {
                                onRemoveManga?.invoke(featured.id)
                                    ?: viewModel.removeFromLibrary(featured.id)
                            },
                            onMarkRead = { read -> viewModel.markOneRead(featured.id, read) },
                            onCategories = {
                                singlePickerManga = featured
                                singlePickerInitial = null
                                libraryScope.launch {
                                    singlePickerInitial = viewModel.categoriesFor(featured.id)
                                }
                            },
                        )
                    }
                }
                items(rest, key = { it.id }) { manga ->
                    val prog = progress[manga.id] ?: 0f
                    MangaGridItem(
                        manga = manga,
                        backend = backend,
                        unreadCount = unread[manga.id] ?: 0,
                        progress = prog,
                        fullyRead = prog >= 1f,
                        lastRead = lastRead[manga.id],
                        downloadedCount = downloaded[manga.id] ?: 0,
                        selected = manga.id in selectedIds,
                        inSelectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) viewModel.toggleSelection(manga.id) else onOpenManga(manga.id)
                        },
                        onLongClick = { viewModel.toggleSelection(manga.id) },
                        onRemove = {
                            onRemoveManga?.invoke(manga.id)
                                ?: viewModel.removeFromLibrary(manga.id)
                        },
                        onMarkRead = { read -> viewModel.markOneRead(manga.id, read) },
                        onCategories = {
                            singlePickerManga = manga
                            singlePickerInitial = null
                            libraryScope.launch {
                                singlePickerInitial = viewModel.categoriesFor(manga.id)
                            }
                        },
                    )
                }
            }
        }
    }

    if (manageCollectionsOpen) {
        var newCategory by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { manageCollectionsOpen = false },
            title = { Text("Categories") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = newCategory,
                            onValueChange = { newCategory = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("New category") },
                            singleLine = true,
                        )
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val name = newCategory.trim()
                                if (name.isNotBlank()) {
                                    libraryScope.launch { viewModel.createCategory(name) }
                                    newCategory = ""
                                }
                            },
                            enabled = newCategory.isNotBlank(),
                        ) { Text("Add") }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(categories) { category ->
                            CollectionRow(
                                category = category,
                                // Main holds the library together: it can only go once
                                // another category exists to take over.
                                canDelete = category.id != com.folio.reader.manga.MangaCategory.MAIN_ID ||
                                    categories.size > 1,
                                onRename = { newName -> viewModel.renameCategory(category.id, newName) },
                                onDelete = { viewModel.deleteCategory(category.id) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { manageCollectionsOpen = false }) { Text("Done") }
            },
        )
    }

    val pickerManga = singlePickerManga
    val pickerInitial = singlePickerInitial
    if (pickerManga != null && pickerInitial != null) {
        CategoryPickerDialog(
            categories = categories,
            initialSelected = pickerInitial,
            onCreate = { name -> viewModel.createCategory(name) },
            onApply = { viewModel.setCategoriesFor(pickerManga.id, it) },
            onDismiss = {
                singlePickerManga = null
                singlePickerInitial = null
            },
        )
    }

    if (bulkCategoryInitial != null) {
        CategoryPickerDialog(
            categories = categories,
            initialSelected = bulkCategoryInitial ?: emptySet(),
            onCreate = { name -> viewModel.createCategory(name) },
            onApply = { viewModel.applyBulkCategories(it) },
            onDismiss = { viewModel.closeBulkPicker() },
        )
    }
}

@Composable
private fun MangaSearchHeader(
    scope: MangaSearchScope,
    onScopeChange: (MangaSearchScope) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    sourcesAvailable: Boolean,
    onClose: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    // Same adaptive contract as the Books/Documents rail (LibrarySearchRail):
    // wide window puts switch and field on one line; on phones the field takes
    // the full row with the switch folded to the row beneath. The scope
    // selector is a dropdown on the field's leading search icon — the old chip
    // row was the same second row that hid the books scopes on phones.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val scopeOptions = if (sourcesAvailable) listOf("In library", "All sources") else listOf("In library")
        val scopeIndex = if (scope == MangaSearchScope.SOURCES && sourcesAvailable) 1 else 0
        if (maxWidth >= 600.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(8.dp))
                    }
                    // One field for both scopes: the typed text survives scope
                    // switches; the pill matches the switch beside it.
                    com.folio.reader.ui.library.FolioSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = if (scope == MangaSearchScope.LIBRARY) "Search your library" else "Search all sources",
                        modifier = Modifier.weight(1f),
                        scopeOptions = scopeOptions,
                        scopeSelected = scopeIndex,
                        onScopeSelect = { index ->
                            onScopeChange(if (index == 1) MangaSearchScope.SOURCES else MangaSearchScope.LIBRARY)
                        },
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FolioTokens.space1),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FolioTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.folio.reader.ui.library.FolioSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = if (scope == MangaSearchScope.LIBRARY) "Search your library" else "Search all sources",
                        modifier = Modifier.weight(1f),
                        scopeOptions = scopeOptions,
                        scopeSelected = scopeIndex,
                        onScopeSelect = { index ->
                            onScopeChange(if (index == 1) MangaSearchScope.SOURCES else MangaSearchScope.LIBRARY)
                        },
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                    }
                }
                if (leading != null) {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = FolioTokens.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item(key = "switch") { leading() }
                    }
                }
            }
        }
    }
}
