package com.folio.reader.ui.manga

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.manga.MangaStatus
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.decodeCoverImage
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

// ---------- Cover loading ----------

private val mangaCoverCache = ConcurrentHashMap<String, ImageBitmap>()
private val mangaCoverOrder = ConcurrentLinkedDeque<String>()
private const val MANGA_COVER_CACHE_MAX = 150

private suspend fun loadMangaCover(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String?,
): ImageBitmap? {
    coverPath?.let { path ->
        val file = java.io.File(path)
        if (file.isFile) {
            return withContext(Dispatchers.IO) { decodeCoverImage(file.readBytes()) }
        }
    }
    val key = "$sourceId:$thumbnailUrl"
    mangaCoverCache[key]?.let { return it }
    if (thumbnailUrl.isNullOrBlank()) return null
    val bytes = try {
        backend.fetchCover(sourceId, thumbnailUrl)
    } catch (_: Throwable) {
        null
    } ?: return null
    val bitmap = withContext(Dispatchers.IO) { decodeCoverImage(bytes) } ?: return null
    if (mangaCoverCache.size >= MANGA_COVER_CACHE_MAX) {
        mangaCoverOrder.pollFirst()?.let { mangaCoverCache.remove(it) }
    }
    mangaCoverCache[key] = bitmap
    mangaCoverOrder.addLast(key)
    return bitmap
}

@Composable
fun MangaCover(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String? = null,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    var bitmap by remember(sourceId, thumbnailUrl, coverPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(sourceId, thumbnailUrl, coverPath) { mutableStateOf(false) }
    LaunchedEffect(sourceId, thumbnailUrl, coverPath) {
        bitmap = loadMangaCover(backend, sourceId, thumbnailUrl, coverPath)
        failed = bitmap == null
    }
    val colors = FolioTheme.colors
    Box(
        modifier = modifier.background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = if (dimmed)
                    androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.35f) }
                    )
                else null,
            )
            failed -> Icon(
                imageVector = Icons.Filled.MenuBook,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
            )
            else -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = colors.onSurfaceVariant,
            )
        }
        if (dimmed && bitmap != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.scrim.copy(alpha = 0.42f)),
            )
        }
    }
}

// ---------- Manga library ----------

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
    val sourceQuery by viewModel.sourceQuery.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    var manageCollectionsOpen by remember { mutableStateOf(false) }
    val bulkCategoryInitial by viewModel.bulkPickerInitial.collectAsState()
    var singlePickerManga by remember { mutableStateOf<MangaEntry?>(null) }
    var singlePickerInitial by remember { mutableStateOf<Set<String>?>(null) }
    val libraryScope = rememberCoroutineScope()
    val searchingSources = searchActive && searchScope == MangaSearchScope.SOURCES && browseViewModel != null

    // The host owns the search bar's visibility; the ViewModel mirrors it so the
    // library filter is only applied while search is actually open.
    LaunchedEffect(searchActive) {
        viewModel.searchActive.value = searchActive
        if (!searchActive) browseViewModel?.exitSearch()
    }

    // Drive the global source search from the persisted source-scope text. Re-keyed on
    // scope and visibility too, so returning to All sources resumes the saved query.
    LaunchedEffect(searchingSources, sourceQuery) {
        val browseVm = browseViewModel ?: return@LaunchedEffect
        if (!searchingSources) {
            browseVm.exitSearch()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350)
        val trimmed = sourceQuery.trim()
        browseVm.globalSearch(if (trimmed.length >= 2) trimmed else "")
    }

    Column(modifier = Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        if (searchActive) {
            MangaSearchHeader(
                scope = searchScope,
                onScopeChange = { next ->
                    if (next != searchScope) viewModel.searchScope.value = next
                },
                libraryQuery = query,
                onLibraryQueryChange = { viewModel.query.value = it },
                sourceQuery = sourceQuery,
                onSourceQueryChange = { viewModel.sourceQuery.value = it },
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
            }
            Spacer(Modifier.height(4.dp))
        }

        val browseVm = browseViewModel
        if (searchingSources && browseVm != null) {
            SourceSearchResults(
                viewModel = browseVm,
                onOpenManga = onOpenManga,
                onOpenSource = { source -> onOpenSource(source, sourceQuery.trim()) },
            )
        } else if (visible.isEmpty() && !isSelectionMode) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (searchActive && query.isNotBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = FolioTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(FolioTokens.space2))
                        Text(
                            "No matches in your library",
                            style = MaterialTheme.typography.titleMedium,
                            color = FolioTheme.colors.onSurface,
                        )
                    }
                } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(FolioTokens.space2))
                    Text(
                        "Your manga library is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = FolioTheme.colors.onSurface,
                    )
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        "Browse sources or import CBZ files to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(FolioTokens.space3))
                    Button(onClick = onOpenBrowse) { Text("Browse") }
                }
                }
            }
        } else if (viewMode == MangaViewMode.LIST || viewMode == MangaViewMode.COMPACT) {
            LazyColumn(
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
            onSave = { set ->
                viewModel.setCategoriesFor(pickerManga.id, set)
                singlePickerManga = null
                singlePickerInitial = null
            },
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
            onSave = { set -> viewModel.assignCategories(selectedIds, set) },
            onDismiss = { viewModel.closeBulkPicker() },
        )
    }
}

@Composable
private fun MangaSearchHeader(
    scope: MangaSearchScope,
    onScopeChange: (MangaSearchScope) -> Unit,
    libraryQuery: String,
    onLibraryQueryChange: (String) -> Unit,
    sourceQuery: String,
    onSourceQueryChange: (String) -> Unit,
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
            // Separate fields per scope: each keeps its own text, so switching scopes
            // mid-session never throws either query away.
            if (scope == MangaSearchScope.LIBRARY) {
                OutlinedTextField(
                    value = libraryQuery,
                    onValueChange = onLibraryQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search your library") },
                    singleLine = true,
                )
                if (libraryQuery.isNotBlank()) {
                    IconButton(onClick = { onLibraryQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear library search")
                    }
                }
            } else {
                OutlinedTextField(
                    value = sourceQuery,
                    onValueChange = onSourceQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search all sources") },
                    singleLine = true,
                )
                if (sourceQuery.isNotBlank()) {
                    IconButton(onClick = { onSourceQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear source search")
                    }
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Close search")
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

@Composable
private fun SourceSearchResults(
    viewModel: BrowseViewModel,
    onOpenManga: (String) -> Unit,
    onOpenSource: (MangaSourceInfo) -> Unit,
) {
    val globalResults by viewModel.globalResults.collectAsState()
    val globalResultsOrdered by viewModel.globalResultsOrdered.collectAsState()
    val query by viewModel.globalQuery.collectAsState()
    val preparing by viewModel.preparingSources.collectAsState()
    val scope = rememberCoroutineScope()

    if (query.isBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Search every installed source at once.\nResults appear as each source answers.",
                style = MaterialTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val finished = globalResults.count { !it.loading }
    val totalItems = globalResults.sumOf { it.items.size }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
    ) {
        item {
            Text(
                when {
                    preparing && globalResults.isEmpty() -> "Preparing sources…"
                    finished < globalResults.size -> "Searching ${finished + 1}/${globalResults.size}…"
                    else -> "$totalItems results from ${globalResults.count { it.items.isNotEmpty() }} sources"
                },
                style = MaterialTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant,
            )
        }
        globalResultsOrdered.forEach { result ->
            item(key = "global-${result.source.id}") {
                Box(Modifier.animateItem()) {
                    GlobalSourceSection(
                        result = result,
                        viewModel = viewModel,
                        onViewAll = { onOpenSource(result.source) },
                        onOpenManga = { item ->
                            scope.launch {
                                val entry = viewModel.ensureEntry(result.source, item)
                                onOpenManga(entry.id)
                            }
                        },
                    )
                }
            }
        }
        if (!preparing && finished == globalResults.size && totalItems == 0) {
            item {
                Box(Modifier.fillMaxWidth().padding(FolioTokens.space4), contentAlignment = Alignment.Center) {
                    Text(
                        "No results across your sources.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(
    category: com.folio.reader.manga.MangaCategory,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(category.name) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            androidx.compose.material3.TextButton(
                onClick = { if (name.isNotBlank()) onRename(name.trim()); editing = false },
            ) { Text("Save") }
        } else {
            Text(
                category.name,
                style = MaterialTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = { editing = true }) { Text("Rename") }
            androidx.compose.material3.IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (canDelete) "Remove" else "Create another category before removing this one",
                    tint = if (canDelete) FolioTheme.colors.error else FolioTheme.colors.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MangaGridItem(
    manga: MangaEntry,
    backend: MangaBackend,
    unreadCount: Int,
    progress: Float,
    fullyRead: Boolean,
    lastRead: com.folio.reader.manga.MangaLastRead?,
    downloadedCount: Int,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onCategories: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) FolioTheme.colors.primaryContainer else FolioTheme.colors.surface,
        ),
    ) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            MangaCover(
                backend = backend,
                sourceId = manga.sourceId,
                thumbnailUrl = manga.thumbnailUrl,
                coverPath = manga.coverPath,
                modifier = Modifier.fillMaxSize(),
                dimmed = fullyRead,
            )
            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            if (selected) FolioTheme.colors.primary else FolioTheme.colors.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(
                        if (selected) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (selected) FolioTheme.colors.onPrimary else FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(FolioTheme.colors.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = unreadCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = FolioTheme.colors.onPrimary,
                        )
                    }
                }
                if (downloadedCount > 0) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Downloaded",
                        tint = FolioTheme.colors.tertiary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(16.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(26.dp)
                    .background(FolioTheme.colors.surface.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    tint = FolioTheme.colors.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Categories…") },
                        leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                        onClick = { menuOpen = false; onCategories() },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all as read") },
                        leadingIcon = { Icon(Icons.Filled.Done, contentDescription = null) },
                        onClick = { menuOpen = false; onMarkRead(true) },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all as unread") },
                        leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                        onClick = { menuOpen = false; onMarkRead(false) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
        if (progress > 0f) {
            com.folio.reader.ui.components.FolioProgressBar(
                progress = progress,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = FolioTheme.colors.primary,
            )
        }
        Text(
            text = manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = FolioTheme.typography.labelLarge,
            color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            text = manga.sourceName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (progress > 0f) {
            Text(
                text = "${(progress * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MangaListItem(
    manga: MangaEntry,
    backend: MangaBackend,
    unreadCount: Int,
    progress: Float,
    fullyRead: Boolean,
    compact: Boolean,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onCategories: () -> Unit,
) {
    val percent = (progress * 100).toInt()
    val statusLabel = when {
        fullyRead -> "Read"
        progress > 0f -> "Reading · $percent%"
        else -> if (unreadCount > 0) "Unread" else ""
    }
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.padding(start = 16.dp).width(32.dp).height(48.dp)) {
                MangaCover(
                    backend = backend,
                    sourceId = manga.sourceId,
                    thumbnailUrl = manga.thumbnailUrl,
                    coverPath = manga.coverPath,
                    modifier = Modifier.fillMaxSize(),
                    dimmed = fullyRead,
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    manga.title,
                    style = FolioTheme.typography.bodyMedium,
                    color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(manga.sourceName, statusLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MangaRowOptions(
                fullyRead = fullyRead,
                onMarkRead = onMarkRead,
                onRemove = onRemove,
                onCategories = onCategories,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    } else {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp))) {
                    MangaCover(
                        backend = backend,
                        sourceId = manga.sourceId,
                        thumbnailUrl = manga.thumbnailUrl,
                        coverPath = manga.coverPath,
                        modifier = Modifier.fillMaxSize(),
                        dimmed = fullyRead,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        manga.title,
                        style = FolioTheme.typography.titleMedium,
                        color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(manga.sourceName, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (statusLabel.isNotBlank()) {
                        Text(
                            statusLabel,
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                }
                if (progress > 0f) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.width(60.dp),
                        progress = progress,
                        color = FolioTheme.colors.primary,
                    )
                }
                MangaRowOptions(
                    fullyRead = fullyRead,
                    onMarkRead = onMarkRead,
                    onRemove = onRemove,
                    onCategories = onCategories,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MangaRowOptions(
    fullyRead: Boolean,
    onMarkRead: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = FolioTheme.colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Categories…") },
                leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                onClick = { open = false; onCategories() },
            )
            DropdownMenuItem(
                text = { Text("Mark all as read") },
                leadingIcon = { Icon(Icons.Filled.Done, contentDescription = null) },
                onClick = { open = false; onMarkRead(true) },
            )
            DropdownMenuItem(
                text = { Text("Mark all as unread") },
                leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                onClick = { open = false; onMarkRead(false) },
            )
            DropdownMenuItem(
                text = { Text("Remove from library") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

// ---------- Browse (sources list) ----------

@Composable
fun MangaBrowseScreen(
    viewModel: BrowseViewModel,
    onOpenSource: (MangaSourceInfo, String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sources by viewModel.sources.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val refreshing by viewModel.refreshingIndex.collectAsState()
    val globalQuery by viewModel.globalQuery.collectAsState()
    val globalResults by viewModel.globalResults.collectAsState()
    val globalResultsOrdered by viewModel.globalResultsOrdered.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()
    val preparing by viewModel.preparingSources.collectAsState()
    var queryText by remember { mutableStateOf(viewModel.globalQuery.value) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        FolioTopBar(
            title = "Browse",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search all sources")
                }
                if (viewModel.supportsExtensions) {
                    IconButton(onClick = { viewModel.refreshIndex() }) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh extension index")
                        }
                    }
                }
            },
        )

        if (searchActive) {
            LaunchedEffect(searchActive, queryText) {
                kotlinx.coroutines.delay(350)
                val trimmed = queryText.trim()
                viewModel.globalSearch(if (trimmed.length >= 2) trimmed else "")
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FolioTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search all sources") },
                    singleLine = true,
                )
                Spacer(Modifier.width(FolioTokens.space1))
                IconButton(onClick = { queryText = "" }, enabled = queryText.isNotBlank()) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
        }

        if (searchActive && globalQuery.isNotBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
            ) {
                val finished = globalResults.count { !it.loading }
                item {
                    Text(
                        when {
                            preparing && globalResults.isEmpty() -> "Preparing sources…"
                            finished < globalResults.size -> "Searching ${finished + 1}/${globalResults.size}…"
                            else -> "${globalResults.sumOf { it.items.size }} results from ${globalResults.count { it.items.isNotEmpty() }} sources"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
                globalResultsOrdered.forEach { result ->
                    item(key = "global-${result.source.id}") {
                        Box(Modifier.animateItem()) {
                            GlobalSourceSection(
                                result = result,
                                viewModel = viewModel,
                                onViewAll = { onOpenSource(result.source, globalQuery) },
                                onOpenManga = { item ->
                                    scope.launch {
                                        val entry = viewModel.ensureEntry(result.source, item)
                                        onOpenManga(entry.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                item {
                    Text(
                        "SOURCES",
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.primary,
                    )
                }
                sources.filter { it.isLocal }.forEach { source ->
                    item(key = "src-${source.id}") {
                        SourceRow(source = source, onClick = { onOpenSource(source, "") })
                    }
                }
                val englishSources = sources.filter { !it.isLocal && it.lang == "en" }
                val otherSources = sources.filter { !it.isLocal && it.lang != "en" }
                if (englishSources.isNotEmpty()) {
                    item(key = "hdr-en") {
                        Text(
                            "ENGLISH",
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary,
                            modifier = Modifier.padding(top = FolioTokens.space2),
                        )
                    }
                    englishSources.forEach { source ->
                        item(key = "src-${source.id}") {
                            SourceRow(source = source, onClick = { onOpenSource(source, "") })
                        }
                    }
                }
                if (otherSources.isNotEmpty()) {
                    item(key = "hdr-other") {
                        Text(
                            "OTHER LANGUAGES",
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary,
                            modifier = Modifier.padding(top = FolioTokens.space2),
                        )
                    }
                    otherSources.forEach { source ->
                        item(key = "src-${source.id}") {
                            SourceRow(source = source, onClick = { onOpenSource(source, "") })
                        }
                    }
                }
                if (viewModel.supportsExtensions) {
                    item {
                        Spacer(Modifier.height(FolioTokens.space2))
                        Text(
                            "EXTENSIONS",
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary,
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                                .clickable(onClick = onOpenExtensions)
                                .padding(FolioTokens.space3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Extension, contentDescription = null, tint = FolioTheme.colors.primary)
                            Spacer(Modifier.width(FolioTokens.space2))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Manage extensions",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = FolioTheme.colors.onSurface,
                                )
                                Text(
                                    "${extensions.count { it.isInstalled }} installed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FolioTheme.colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Spacer(Modifier.height(FolioTokens.space2))
                        Text(
                            "Extensions run on Android only. On desktop you can read local manga " +
                                "(CBZ/ZIP/folders) imported into the library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Checkable category editor shared by the detail screen (single manga) and the
 * library selection mode (bulk). Creating a category inline selects it, so a
 * brand-new shelf can be filled without leaving the dialog.
 */
@Composable
private fun CategoryPickerDialog(
    categories: List<com.folio.reader.manga.MangaCategory>,
    initialSelected: Set<String>,
    onCreate: suspend (String) -> String?,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    val scope = rememberCoroutineScope()
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (categories.isEmpty()) {
                    Text(
                        "No categories yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (category.id in selected) selected - category.id
                                else selected + category.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = category.id in selected,
                            onCheckedChange = {
                                selected = if (it) selected + category.id else selected - category.id
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            if (name.isNotBlank()) {
                                scope.launch {
                                    onCreate(name)?.let { selected = selected + it }
                                }
                                newName = ""
                            }
                        },
                        enabled = newName.trim().isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun GlobalSourceSection(
    result: BrowseViewModel.GlobalSourceResult,
    viewModel: BrowseViewModel,
    onViewAll: () -> Unit,
    onOpenManga: (MangaBrowseItem) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                result.source.name,
                style = MaterialTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.items.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = onViewAll) { Text("View all") }
            }
        }
        Spacer(Modifier.height(FolioTokens.space1))
        AnimatedContent(
            targetState = result.loading,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "source-section-${result.source.id}",
        ) { loading ->
            when {
                loading -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                items(3) {
                    Column(Modifier.width(96.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.68f)
                                .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .background(
                                    FolioTheme.colors.surfaceVariant,
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
            }
            else -> if (result.items.isEmpty() && result.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = FolioTheme.colors.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        result.error ?: "Search failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            } else LazyRow(
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                items(result.items, key = { it.url + it.title }) { item ->
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .clickable { onOpenManga(item) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.68f)
                                .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                        ) {
                            MangaCover(
                                backend = viewModel.backend,
                                sourceId = result.source.id,
                                thumbnailUrl = item.thumbnailUrl,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SourceRow(source: MangaSourceInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
            .clickable(onClick = onClick)
            .padding(FolioTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (source.isLocal) Icons.Filled.MenuBook else Icons.Filled.Explore,
            contentDescription = null,
            tint = FolioTheme.colors.primary,
        )
        Spacer(Modifier.width(FolioTokens.space2))
        Column(Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (source.isLocal) "CBZ, ZIP and image folders" else source.lang.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = FolioTheme.colors.onSurfaceVariant)
    }
}

// ---------- Source browse / search grid ----------

@Composable
fun SourceBrowseScreen(
    viewModel: SourceBrowseViewModel,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val filterTemplate by viewModel.filterTemplate.collectAsState()
    var searchActive by remember { mutableStateOf(viewModel.state.value.query.isNotBlank()) }
    var searchQuery by remember { mutableStateOf(viewModel.state.value.query) }
    var showFilters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        FolioTopBar(
            title = viewModel.source.name,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { searchActive = !searchActive }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                if (filterTemplate.isNotEmpty()) {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filters")
                    }
                }
            },
        )

        if (searchActive) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FolioTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search ${viewModel.source.name}") },
                    singleLine = true,
                )
                Spacer(Modifier.width(FolioTokens.space1))
                IconButton(onClick = { viewModel.reload(BrowseMode.POPULAR, searchQuery, state.filters) }) {
                    Icon(Icons.Filled.Check, contentDescription = "Run search")
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1),
        ) {
            item {
                FolioChip(
                    selected = state.mode == BrowseMode.POPULAR && state.query.isBlank(),
                    onClick = { viewModel.reload(BrowseMode.POPULAR, "", null) },
                    label = "Popular",
                )
            }
            if (viewModel.source.supportsLatest) {
                item {
                    FolioChip(
                        selected = state.mode == BrowseMode.LATEST && state.query.isBlank(),
                        onClick = { viewModel.reload(BrowseMode.LATEST, "", null) },
                        label = "Latest",
                    )
                }
            }
            if (state.query.isNotBlank()) {
                item {
                    FolioChip(selected = true, onClick = {}, label = "“${state.query}”")
                }
            }
        }
        Spacer(Modifier.height(FolioTokens.space2))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.items.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = FolioTheme.colors.error)
                        Spacer(Modifier.height(FolioTokens.space1))
                        Text(state.error ?: "", color = FolioTheme.colors.onSurface)
                        Spacer(Modifier.height(FolioTokens.space2))
                        Button(onClick = { viewModel.reload(state.mode, state.query, state.filters) }) {
                            Text("Retry")
                        }
                    }
                }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
            ) {
                items(state.items, key = { it.url + it.title }) { item ->
                    BrowseGridItem(
                        item = item,
                        viewModel = viewModel,
                        onClick = {
                            scope.launch {
                                val entry = viewModel.ensureEntry(item)
                                onOpenManga(entry.id)
                            }
                        },
                    )
                }
                if (state.hasNextPage) {
                    item {
                        LaunchedEffect(Unit) { viewModel.loadNextPage() }
                        Box(Modifier.fillMaxWidth().padding(FolioTokens.space3), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            template = filterTemplate,
            onApply = { filters ->
                showFilters = false
                viewModel.reload(state.mode, state.query, filters)
            },
            onDismiss = { showFilters = false },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowseGridItem(
    item: MangaBrowseItem,
    viewModel: SourceBrowseViewModel,
    onClick: () -> Unit,
) {
    var inLibrary by remember(item.url) { mutableStateOf(false) }
    LaunchedEffect(item.url) { inLibrary = viewModel.isInLibrary(item) }
    val backend = viewModel.backend
    val itemScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    itemScope.launch {
                        if (inLibrary) {
                            viewModel.removeFromLibrary(item)
                            inLibrary = false
                        } else {
                            viewModel.addToLibrary(item)
                            inLibrary = true
                        }
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
        ) {
            MangaCover(
                backend = backend,
                sourceId = viewModel.source.id,
                thumbnailUrl = item.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
            )
            if (inLibrary) {
                Icon(
                    Icons.Filled.LibraryAddCheck,
                    contentDescription = "In library",
                    tint = FolioTheme.colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = FolioTheme.colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- Filters ----------

@Composable
private fun FilterSheet(
    template: List<MangaFilter>,
    onApply: (List<MangaFilter>) -> Unit,
    onDismiss: () -> Unit,
) {
    var filters by remember { mutableStateOf(template) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column {
                        filters.forEachIndexed { index, filter ->
                            FilterControl(filter = filter) { updated ->
                                filters = filters.toMutableList().also { it[index] = updated }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(filters) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FilterControl(filter: MangaFilter, onChange: (MangaFilter) -> Unit) {
    val colors = FolioTheme.colors
    when (filter) {
        is MangaFilter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        is MangaFilter.Separator -> Spacer(Modifier.height(2.dp))
        is MangaFilter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = { onChange(filter.copy(state = it)) },
            label = { Text(filter.name.ifBlank { "Text" }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is MangaFilter.CheckBox -> Row(
            modifier = Modifier.fillMaxWidth().clickable { onChange(filter.copy(state = !filter.state)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = filter.state, onCheckedChange = { onChange(filter.copy(state = it)) })
            Text(filter.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
        is MangaFilter.TriState -> Row(
            modifier = Modifier.fillMaxWidth().clickable { onChange(filter.copy(state = (filter.state + 1) % 3)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = when (filter.state) {
                    1 -> androidx.compose.ui.state.ToggleableState.Indeterminate
                    2 -> androidx.compose.ui.state.ToggleableState.On
                    else -> androidx.compose.ui.state.ToggleableState.Off
                },
                onClick = { onChange(filter.copy(state = (filter.state + 1) % 3)) },
            )
            Text(filter.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
        is MangaFilter.Select -> Column(Modifier.fillMaxWidth()) {
            Text(filter.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(filter.values.getOrNull(filter.state) ?: "Select")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        filter.values.forEachIndexed { index, value ->
                            DropdownMenuItem(
                                text = { Text(value) },
                                onClick = {
                                    expanded = false
                                    onChange(filter.copy(state = index))
                                },
                            )
                        }
                    }
                }
            }
        }
        is MangaFilter.Sort -> Column(Modifier.fillMaxWidth()) {
            Text(filter.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            filter.values.forEachIndexed { index, value ->
                val selected = filter.selection?.index == index
                val ascending = filter.selection?.ascending ?: true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newSelection = if (selected) {
                                MangaFilter.Sort.SortSelection(index, !ascending)
                            } else {
                                MangaFilter.Sort.SortSelection(index, true)
                            }
                            onChange(filter.copy(selection = newSelection))
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (selected && !ascending) Icons.Filled.Sort else Icons.Filled.Sort,
                        contentDescription = null,
                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) colors.primary else colors.onSurface,
                    )
                }
            }
        }
        is MangaFilter.Group -> Column(Modifier.fillMaxWidth()) {
            Text(filter.name, style = MaterialTheme.typography.titleSmall, color = colors.primary)
            filter.filters.forEachIndexed { index, child ->
                FilterControl(child) { updated ->
                    onChange(filter.copy(filters = filter.filters.toMutableList().also { it[index] = updated }))
                }
            }
        }
    }
}

// ---------- Manga detail ----------

@Composable
fun MangaDetailScreen(
    viewModel: MangaDetailViewModel,
    backend: MangaBackend,
    downloadsAvailable: Boolean,
    onRead: (MangaEntry, MangaChapter) -> Unit,
    onBack: () -> Unit,
) {
    val manga by viewModel.manga.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val chapterFilter by viewModel.chapterFilter.collectAsState()
    val chapterSelectionMode by viewModel.chapterSelectionMode.collectAsState()
    val selectedChapterIds by viewModel.selectedChapterIds.collectAsState()
    val scope = rememberCoroutineScope()
    var startChapter by remember { mutableStateOf<MangaChapter?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val allCategories by viewModel.allCategories.collectAsState()
    val myCategoryIds by viewModel.myCategoryIds.collectAsState()
    var categoryPickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { startChapter = viewModel.nextChapterToRead() }

    val displayChapters = remember(chapters, sortAscending, chapterFilter) {
        val filtered = viewModel.applyFilter(chapters)
        if (sortAscending) filtered else filtered.reversed()
    }

    val m = manga
    if (m == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        FolioTopBar(
            // The title/author/status block below already carries the identity; a top-bar
            // title just repeats it.
            title = "",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleInLibrary() }) {
                    Icon(
                        imageVector = if (m.inLibrary) Icons.Filled.LibraryAddCheck else Icons.Filled.LibraryAdd,
                        contentDescription = if (m.inLibrary) "In library — tap to remove" else "Add to library",
                        tint = if (m.inLibrary) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = { viewModel.sortAscending.value = !sortAscending }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort order")
                }
                Box {
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter chapters")
                    }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                        ChapterFilter.entries.forEach { f ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (chapterFilter == f) {
                                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(f.label)
                                    }
                                },
                                onClick = {
                                    filterOpen = false
                                    viewModel.chapterFilter.value = f
                                },
                            )
                        }
                    }
                }
                Box {
                    var moreOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { moreOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Mark all as read") },
                            leadingIcon = { Icon(Icons.Filled.Done, contentDescription = null) },
                            onClick = { moreOpen = false; viewModel.markAllRead(true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark all as unread") },
                            leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                            onClick = { moreOpen = false; viewModel.markAllRead(false) },
                        )
                    }
                }
            },
        )

        if (m.inLibrary) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = FolioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(allCategories.filter { it.id in myCategoryIds }) { category ->
                    FolioChip(selected = true, onClick = { categoryPickerOpen = true }, label = category.name)
                }
                item {
                    FolioChip(selected = false, onClick = { categoryPickerOpen = true }, label = "Categories")
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (categoryPickerOpen) {
            CategoryPickerDialog(
                categories = allCategories,
                initialSelected = myCategoryIds,
                onCreate = { name -> viewModel.createCategory(name) },
                onSave = { set ->
                    viewModel.setCategories(set)
                    categoryPickerOpen = false
                },
                onDismiss = { categoryPickerOpen = false },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .aspectRatio(0.68f)
                            .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                    ) {
                        MangaCover(
                            backend = backend,
                            sourceId = m.sourceId,
                            thumbnailUrl = m.thumbnailUrl,
                            coverPath = m.coverPath,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = FolioTheme.colors.onSurface,
                        )
                        listOfNotNull(m.author, m.artist).distinct().forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
                        }
                        Text(
                            when (m.status) {
                                MangaStatus.ONGOING -> "Ongoing"
                                MangaStatus.COMPLETED -> "Completed"
                                MangaStatus.LICENSED -> "Licensed"
                                MangaStatus.PUBLISHING_FINISHED -> "Publishing finished"
                                MangaStatus.CANCELLED -> "Cancelled"
                                MangaStatus.ON_HIATUS -> "On hiatus"
                                MangaStatus.UNKNOWN -> m.sourceName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = FolioTheme.colors.primary,
                        )
                    }
                }
            }

            item {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                ) {
                    if (m.inLibrary) {
                        OutlinedButton(
                            onClick = { viewModel.toggleInLibrary() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.LibraryAddCheck, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("In library — tap to remove", maxLines = 1)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.toggleInLibrary() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.LibraryAdd, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add to library", maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                        Button(
                            onClick = {
                                val target = startChapter ?: displayChapters.firstOrNull()
                                if (target != null) {
                                    viewModel.recordHistory(target.id)
                                    onRead(m, target)
                                }
                            },
                            enabled = displayChapters.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (chapters.any { it.read }) "Continue" else "Start reading",
                                maxLines = 1,
                            )
                        }
                        OutlinedButton(onClick = { viewModel.downloadUnread() }) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Download", maxLines = 1)
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(message, color = FolioTheme.colors.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (m.genres.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(m.genres) { genre ->
                            Box(
                                modifier = Modifier
                                    .background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(FolioTokens.radiusChip))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(genre, style = MaterialTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (!m.description.isNullOrBlank()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        m.description!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }

            item {
                Text(
                    "${chapters.size} CHAPTERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = FolioTheme.colors.primary,
                )
            }

            if (chapters.isEmpty() && refreshing) {
                item {
                    Box(Modifier.fillMaxWidth().padding(FolioTokens.space4), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (chapterSelectionMode) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FolioTheme.colors.primaryContainer, RoundedCornerShape(FolioTokens.radiusChip))
                            .padding(horizontal = FolioTokens.space2, vertical = FolioTokens.space1),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.clearChapterSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                            Text(
                                "${selectedChapterIds.size} selected",
                                style = MaterialTheme.typography.titleSmall,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.selectAllChapters() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            IconButton(onClick = { viewModel.bulkMarkRead(true) }) {
                                Icon(Icons.Filled.Done, contentDescription = "Mark read")
                            }
                            IconButton(onClick = { viewModel.bulkMarkRead(false) }) {
                                Icon(Icons.Filled.MenuBook, contentDescription = "Mark unread")
                            }
                            if (downloadsAvailable && !m.isLocal) {
                                IconButton(onClick = { viewModel.bulkDownload() }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download")
                                }
                            }
                            IconButton(onClick = { viewModel.bulkDeleteDownloads() }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete downloads", tint = FolioTheme.colors.error)
                            }
                        }
                    }
                }
            }

            items(displayChapters, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isQueued = chapter.downloadedPages > 0,
                    downloadsAvailable = downloadsAvailable && !m.isLocal,
                    selected = chapter.id in selectedChapterIds,
                    inSelectionMode = chapterSelectionMode,
                    onRead = {
                        if (chapterSelectionMode) viewModel.toggleChapterSelection(chapter.id)
                        else { viewModel.recordHistory(chapter.id); onRead(m, chapter) }
                    },
                    onLongClick = { viewModel.toggleChapterSelection(chapter.id) },
                    onToggleRead = { viewModel.toggleRead(chapter) },
                    onMarkPrevious = { viewModel.markPreviousAsRead(chapter) },
                    onToggleBookmark = { viewModel.toggleBookmark(chapter) },
                    onDownload = { viewModel.download(chapter) },
                )
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ChapterRow(
    chapter: MangaChapter,
    isQueued: Boolean,
    downloadsAvailable: Boolean,
    selected: Boolean,
    inSelectionMode: Boolean,
    onRead: () -> Unit,
    onLongClick: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPrevious: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = FolioTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRead, onLongClick = onLongClick)
            .background(
                if (selected) FolioTheme.colors.primaryContainer else FolioTheme.colors.surface,
                RoundedCornerShape(FolioTokens.radiusControl),
            )
            .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.SemiBold,
                color = if (chapter.read) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                chapter.scanlator?.takeIf { it.isNotBlank() },
                chapter.chapterNumber.takeIf { it > 0 }?.let { "Ch. ${formatChapterNumber(it)}" },
                if (!chapter.read && chapter.lastPageRead > 0)
                    if (chapter.totalPages > 0) "p.${chapter.lastPageRead + 1}/${chapter.totalPages}" else "p.${chapter.lastPageRead + 1}"
                else null,
                if (chapter.downloadedPages > 0) "DL ${chapter.downloadedPages}" else null,
            ).joinToString(" • ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (isQueued || chapter.downloadedPages > 0) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Downloaded",
                tint = colors.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                imageVector = if (chapter.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = "Bookmark",
                tint = if (chapter.bookmarked) colors.primary else colors.onSurfaceVariant,
            )
        }
        if (downloadsAvailable) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Download", tint = colors.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (chapter.read) "Mark as unread" else "Mark as read") },
                    onClick = {
                        menuOpen = false
                        onToggleRead()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Mark previous as read") },
                    onClick = {
                        menuOpen = false
                        onMarkPrevious()
                    },
                )
            }
        }
    }
}

private fun formatChapterNumber(number: Float): String =
    if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

// ---------- Extensions manager ----------

@Composable
fun ExtensionsScreen(
    viewModel: BrowseViewModel,
    onBack: () -> Unit,
) {
    val extensions by viewModel.extensions.collectAsState()
    val installStates by viewModel.installStates.collectAsState()
    val repos by viewModel.repos.collectAsState()
    val refreshing by viewModel.refreshingIndex.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0 installed, 1 available, 2 untrusted
    var showAddRepo by remember { mutableStateOf(false) }

    val nsfw by viewModel.nsfw.collectAsState()
    val visibleExt = extensions.filter { nsfw || !it.isNsfw }
    val installed = visibleExt.filter { it.isInstalled }
    val untrusted = visibleExt.filter { it.isUntrusted }
    val available = visibleExt.filter { !it.isInstalled && !it.isUntrusted }
    var extQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (viewModel.supportsExtensions && available.isEmpty()) {
            viewModel.refreshIndex()
        }
    }
    LaunchedEffect(extensions) {
        if (installed.isEmpty() && available.isNotEmpty() && tab == 0) tab = 1
    }

    val shownBase = when (tab) {
        0 -> installed
        1 -> available
        else -> untrusted
    }
    val shown = if (extQuery.isBlank()) shownBase
        else extensions
            .distinctBy { it.pkgName }
            .filter { it.name.contains(extQuery, ignoreCase = true) }

    Column(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        FolioTopBar(
            title = "Extensions",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.refreshIndex() }) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            },
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1),
        ) {
            item { FolioChip(selected = tab == 0, onClick = { tab = 0 }, label = "Installed (${installed.size})") }
            item { FolioChip(selected = tab == 1, onClick = { tab = 1 }, label = "Available (${available.size})") }
            item { FolioChip(selected = tab == 2, onClick = { tab = 2 }, label = "Untrusted (${untrusted.size})") }
        }
        Spacer(Modifier.height(FolioTokens.space1))
        if (viewModel.supportsExtensions) {
            val nsfw by viewModel.nsfw.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setNsfw(!nsfw) }
                    .padding(horizontal = FolioTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = nsfw, onCheckedChange = { viewModel.setNsfw(it) })
                Text(
                    "Show NSFW extensions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurface,
                )
            }
        }
        Spacer(Modifier.height(FolioTokens.space1))
        OutlinedTextField(
            value = extQuery,
            onValueChange = { extQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioTokens.space3),
            placeholder = { Text("Search extensions") },
            singleLine = true,
        )
        Spacer(Modifier.height(FolioTokens.space2))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            if (shown.isEmpty() && tab == 1 && !refreshing) {
                item {
                    Text(
                        "No extension index loaded yet. Tap refresh to fetch the repository index.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
            val shownEn = shown.filter { it.lang == "en" }
            val shownOther = shown.filter { it.lang != "en" }
            if (shownEn.isNotEmpty()) {
                item(key = "ext-hdr-en") {
                    Text(
                        "ENGLISH",
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.primary,
                    )
                }
            }
            items(shownEn, key = { it.pkgName }) { entry ->
                ExtensionRow(
                    entry = entry,
                    installStep = installStates[entry.pkgName],
                    onInstall = { viewModel.install(entry.pkgName) },
                    onUpdate = { viewModel.update(entry.pkgName) },
                    onUninstall = { viewModel.uninstall(entry.pkgName) },
                    onTrust = { viewModel.trust(entry) },
                )
            }
            if (shownOther.isNotEmpty()) {
                item(key = "ext-hdr-other") {
                    Text(
                        "OTHER LANGUAGES",
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.primary,
                        modifier = Modifier.padding(top = FolioTokens.space2),
                    )
                }
            }
            items(shownOther, key = { it.pkgName }) { entry ->
                ExtensionRow(
                    entry = entry,
                    installStep = installStates[entry.pkgName],
                    onInstall = { viewModel.install(entry.pkgName) },
                    onUpdate = { viewModel.update(entry.pkgName) },
                    onUninstall = { viewModel.uninstall(entry.pkgName) },
                    onTrust = { viewModel.trust(entry) },
                )
            }
            item {
                Spacer(Modifier.height(FolioTokens.space2))
                if (viewModel.supportsExtensions) {
                    Text("REPOSITORIES", style = MaterialTheme.typography.labelSmall, color = FolioTheme.colors.primary)
                } else {
                    Text(
                        "Extensions run on Android only. On this device you can read local manga " +
                            "(CBZ/ZIP archives and image folders) imported into the library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
            if (viewModel.supportsExtensions) {
                items(repos, key = { it.baseUrl }) { repo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                        .padding(FolioTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(repo.name, style = MaterialTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
                        Text(
                            repo.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { viewModel.saveRepos(repos.filterNot { it.baseUrl == repo.baseUrl }) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove repo", tint = FolioTheme.colors.error)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showAddRepo = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add repository")
                }
                }
            }
        }
    }

    if (showAddRepo) {
        AddRepoDialog(
            onAdd = { name, baseUrl, indexUrl ->
                viewModel.saveRepos(
                    repos + MangaRepoInfo(
                        name = name.ifBlank { baseUrl },
                        baseUrl = baseUrl,
                        indexUrl = indexUrl.ifBlank { baseUrl.trimEnd('/') + "/index.min.json" },
                    )
                )
                showAddRepo = false
            },
            onDismiss = { showAddRepo = false },
        )
    }
}

@Composable
private fun ExtensionRow(
    entry: ExtensionEntry,
    installStep: ExtensionInstallStep?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onTrust: () -> Unit,
) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
            .padding(FolioTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isNsfw) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(colors.errorContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("18+", style = MaterialTheme.typography.labelMedium, color = colors.onErrorContainer)
                    }
                }
            }
            Text(
                listOfNotNull(
                    "v${entry.versionName}",
                    entry.lang?.uppercase(),
                    if (entry.sourceNames.isNotEmpty()) entry.sourceNames.joinToString(", ") else null,
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(FolioTokens.space2))
        when {
            entry.isUntrusted -> Button(onClick = onTrust) { Text("Trust") }
            installStep == ExtensionInstallStep.Downloading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            installStep == ExtensionInstallStep.Installing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            entry.isInstalled && entry.hasUpdate -> {
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = colors.error)
                }
                Button(onClick = onUpdate) { Text("Update") }
            }
            entry.isInstalled -> {
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = colors.error)
                }
            }
            else -> Button(onClick = onInstall) { Text("Install") }
        }
    }
}

@Composable
private fun AddRepoDialog(onAdd: (name: String, baseUrl: String, indexUrl: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var indexUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add extension repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = indexUrl,
                    onValueChange = { indexUrl = it },
                    label = { Text("Index URL (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name.trim(), baseUrl.trim(), indexUrl.trim()) },
                enabled = baseUrl.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Downloads ----------

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
) {
    val queue by viewModel.queue.collectAsState()
    val titles by viewModel.mangaTitles.collectAsState()

    Column(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        FolioTopBar(
            title = "Downloads",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.clearFinished() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear finished")
                }
            },
        )
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No downloads in the queue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                items(queue, key = { it.id }) { download ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                            .padding(FolioTokens.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                titles[download.mangaId] ?: download.mangaId,
                                style = MaterialTheme.typography.titleSmall,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (download.status) {
                                    com.folio.reader.manga.MangaDownloadStatus.QUEUED -> "Queued"
                                    com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING ->
                                        "Downloading ${download.downloadedPages}/${download.totalPages}"
                                    com.folio.reader.manga.MangaDownloadStatus.DOWNLOADED -> "Downloaded"
                                    com.folio.reader.manga.MangaDownloadStatus.ERROR -> "Failed"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = FolioTheme.colors.onSurfaceVariant,
                            )
                        }
                        if (download.status != com.folio.reader.manga.MangaDownloadStatus.DOWNLOADED) {
                            IconButton(onClick = { viewModel.cancel(download.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = FolioTheme.colors.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
