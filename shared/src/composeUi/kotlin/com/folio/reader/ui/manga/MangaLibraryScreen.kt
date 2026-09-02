package com.folio.reader.ui.manga

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch

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
) {
    val visible by viewModel.visible.collectAsState()
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

    Column(modifier = Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
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
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

        val browseVm = browseViewModel
        if (searchingSources && browseVm != null) {
            SourceSearchResults(
                viewModel = browseVm,
                onOpenManga = onOpenManga,
                onOpenSource = { source -> onOpenSource(source, query.trim()) },
            )
        } else if (visible.isEmpty() && !isSelectionMode) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (searchActive && query.isNotBlank()) {
                    com.folio.reader.ui.components.EmptyState(
                        icon = Icons.Filled.Search,
                        headline = "No matches in your library"
                    )
                } else {
                    com.folio.reader.ui.components.EmptyState(
                        icon = Icons.Filled.MenuBook,
                        headline = "Your manga library is empty",
                        body = "Browse sources or import CBZ files to get started.",
                        action = { Button(onClick = onOpenBrowse) { Text("Browse") } }
                    )
                }
            }
        } else if (viewMode == MangaViewMode.LIST || viewMode == MangaViewMode.COMPACT) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space1),
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
                        onRemove = { viewModel.removeFromLibrary(manga.id) },
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
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
            ) {
                items(visible, key = { it.id }) { manga ->
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
                        onRemove = { viewModel.removeFromLibrary(manga.id) },
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One field for both scopes: the typed text survives scope switches.
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (scope == MangaSearchScope.LIBRARY) "Search your library" else "Search all sources")
                },
                singleLine = true,
            )
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FolioChip(
                selected = scope == MangaSearchScope.LIBRARY,
                onClick = { onScopeChange(MangaSearchScope.LIBRARY) },
                label = "In library",
            )
            if (sourcesAvailable) {
                FolioChip(
                    selected = scope == MangaSearchScope.SOURCES,
                    onClick = { onScopeChange(MangaSearchScope.SOURCES) },
                    label = "All sources",
                )
            }
        }
    }
}
