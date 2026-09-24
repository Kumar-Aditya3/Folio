package com.folio.reader.nav

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.mangaId
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.home.HomeScreen
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.library.LibraryScreen
import com.folio.reader.ui.library.DocumentLibraryViewModel
import com.folio.reader.ui.library.LibraryViewModel
import com.folio.reader.ui.statistics.StatisticsTabContent
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.folioBarTopInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * §5.4: Home is a purpose-built surface — daily goal ring, continue reading,
 * because-you-finished, this week — not a mirror of the books library.
 */
@Composable
fun HomeRoute(navModel: FolioNavModelImpl) {
    val graph = navModel.graph
    val navController = navModel.navController ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // §13.9 hero collapse: Home reports the fraction continuously, plus the hero title
    // and the hero tint. The masthead cross-fades the wordmark out and the book's title
    // in across the same scroll, gains its glass at the same rate, and takes a bleed of
    // the hero's own colour — so the card does not dissolve into a bar that is still
    // saying something else. The fraction stays offset-derived, as §13.9 requires, but
    // it is *stored* in the same state the other mastheads use: that is what lets Home's
    // bar come back collapsed along with the page it belongs to instead of resetting to
    // glass-free at the top. Home keeps its own 160dp range.
    val heroHeader = rememberFolioHeaderState(range = 160.dp)
    var heroTitle by remember { mutableStateOf<String?>(null) }
    var heroTint by remember { mutableStateOf<Color?>(null) }
    // Overlay: the page runs to the top of the window and the masthead floats over
    // it, so the hero genuinely passes behind the glass as it collapses into the bar.
    Box(modifier = Modifier.fillMaxSize()) {
        // align stays HERE, on the box that wraps the bar alone: the hero tint below
        // uses matchParentSize, which resolves against this box. Hoisting align to
        // the full-screen one would size that tint to the viewport and flood the page.
        Box(modifier = Modifier.align(Alignment.TopCenter).zIndex(1f)) {
            val fraction = heroHeader.collapse
            FolioTopBar(
                title = "Folio",
                collapse = fraction,
                titleContent = {
                    Box {
                        // The masthead carries the product name on Home and the screen's
                        // name everywhere else. It used to be inverted — "Home" here and
                        // "Folio" over the library — which read as two different apps.
                        Text(
                            text = "Folio",
                            style = FolioTheme.typography.headlineMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            modifier = Modifier.graphicsLayer {
                                alpha = (1f - fraction * 1.7f).coerceIn(0f, 1f)
                            },
                        )
                        val migrated = heroTitle
                        if (migrated != null) {
                            Text(
                                text = migrated,
                                style = MaterialTheme.typography.titleMedium,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer {
                                    alpha = ((fraction - 0.4f) / 0.6f).coerceIn(0f, 1f)
                                },
                            )
                        }
                    }
                },
            )
            if (heroTint != null && fraction > 0.01f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = fraction }
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, heroTint!!.copy(alpha = 0.12f))
                            )
                        )
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().folioBackdropSource()) {
            // One view model for the app's lifetime, with its state kept hot
            // from app start (FolioNavModelImpl.homeState) — the skeleton used
            // to re-run on every visit because the route rebuilt the VM.
            val state by navModel.homeState.collectAsState()
            // Atlas hero gating: the app-level flag plus a map-ready library (≥ threshold embedded
            // books). Resolved off the main thread; the hero simply does not appear until true.
            var atlasReady by remember { mutableStateOf(navModel.atlasEligible == true) }
            LaunchedEffect(navModel.globalSettings.semanticDiscovery) {
                if (!navModel.globalSettings.semanticDiscovery) { atlasReady = false; return@LaunchedEffect }
                // Use the session-cached answer immediately if we have it (revisits are instant);
                // otherwise resolve it once here, but AFTER a short beat so the COUNT(DISTINCT) does
                // not contend with Home's own startup queries on the single serialized DB connection
                // — that contention is what made the screen janky on launch. The card fades/expands
                // in when the answer lands (see HomeScreen's AnimatedVisibility), so the deferral
                // reads as a gentle arrival rather than a pop. Cached in the nav model thereafter.
                navModel.atlasEligible?.let { atlasReady = it; return@LaunchedEffect }
                kotlinx.coroutines.delay(700)
                val eligible = runCatching { graph.semanticDiscoveryRepository.atlasHeroEligible() }.getOrDefault(false)
                navModel.atlasEligible = eligible
                atlasReady = eligible
            }
            HomeScreen(
                state = state,
                topInset = folioBarTopInset(),
                onOpenBook = { navController.navigate(FolioDestination.reader(it)) },
                onOpenBookDetail = { navController.navigate(FolioDestination.bookDetail(it)) },
                onImportClick = { navModel.callbacks.onImportEpubs() },
                onOpenStats = { navController.navigate(FolioRoutes.STATS) },
                onOpenLibrary = { navController.navigate(FolioRoutes.LIBRARY) },
                onOpenAtlas = { navController.navigate(FolioRoutes.ATLAS) },
                atlasReady = atlasReady,
                onOpenExclusions = {
                    navController.navigate(FolioDestination.settings(com.folio.reader.settings.FolioSettingsCategory.STATS))
                },
                mangaBackend = graph.mangaBackend,
                onOpenMangaDetail = { navController.navigate(FolioDestination.mangaDetail(it)) },
                onOpenMangaReader = { mangaId, chapterId ->
                    navController.navigate(FolioDestination.mangaReader(mangaId, chapterId))
                },
                onOpenSourceWeb = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                onOpenDiscover = { item ->
                    // Persist for the detail screen without adding to the library —
                    // the browse screen's ensureEntry contract, minimal form.
                    scope.launch(Dispatchers.IO) {
                        val id = graph.mangaRepository.findBySourceUrl(item.sourceId, item.url)?.id
                            ?: mangaId(item.sourceId, item.url).also { newId ->
                                graph.mangaRepository.upsert(
                                    MangaEntry(
                                        id = newId,
                                        sourceId = item.sourceId,
                                        sourceName = item.sourceName,
                                        url = item.url,
                                        title = item.title,
                                        thumbnailUrl = item.thumbnailUrl,
                                        inLibrary = false
                                    )
                                )
                            }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            navController.navigate(FolioDestination.mangaDetail(id))
                        }
                    }
                },
                onHeroCollapse = { collapsed, title, tint ->
                    heroHeader.setProgress(collapsed)
                    heroTitle = title
                    heroTint = tint
                }
            )
        }
    }
}

@Composable
fun LibraryRoute(
    navModel: FolioNavModelImpl,
    onOpenReader: (String) -> Unit,
    onOpenReaderAt: (String, Int?, Float?) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenBookDetail: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenQuotes: () -> Unit,
    onOpenRevisit: () -> Unit,
    onOpenMangaBrowse: () -> Unit,
    onOpenMangaExtensions: () -> Unit,
    onOpenMangaHistory: () -> Unit,
    onOpenMangaDetail: (String) -> Unit,
    onOpenMangaDownloads: () -> Unit,
    onOpenMangaSource: (Long, String) -> Unit
) {
    val graph = navModel.graph
    val callbacks = navModel.callbacks
    val activity = navModel.activity
    val removalScope = rememberCoroutineScope()
    var mangaRemovalIds by remember { mutableStateOf<Set<String>?>(null) }
    var deleteMangaDownloads by remember { mutableStateOf(false) }
    var clearMangaSelectionAfterRemoval by remember { mutableStateOf(false) }

    // Persist the selected Library mode across launches (same raw key as pre-nav).
    LaunchedEffect(Unit) {
        runCatching {
            val raw = graph.settingsRepository.getRaw("library.mode")
            LibraryMode.entries.firstOrNull { it.name == raw }?.let { navModel.libraryMode = it }
            val viewIdx = graph.settingsRepository.getRaw("library.viewMode")?.toIntOrNull()
            if (viewIdx != null && viewIdx in LibraryViewModel.ViewMode.entries.indices) {
                navModel.sharedViewIndex = viewIdx
            }
        }
        navModel.libraryModeLoaded = true
    }
    LaunchedEffect(navModel.libraryMode, navModel.libraryModeLoaded) {
        if (navModel.libraryModeLoaded) {
            runCatching { graph.settingsRepository.setRaw("library.mode", navModel.libraryMode.name) }
        }
    }
    LaunchedEffect(navModel.sharedViewIndex, navModel.libraryModeLoaded) {
        if (navModel.libraryModeLoaded) {
            runCatching { graph.settingsRepository.setRaw("library.viewMode", navModel.sharedViewIndex.toString()) }
        }
    }

    LibraryScreen(
        onBookClick = { onOpenReader(it.id) },
        onBookDetailClick = { onOpenBookDetail(it.id) },
        onImportClick = { callbacks.onImportContent() },
        documentLibraryViewModel = navModel.documentLibraryVM,
        onDocumentImportClick = { callbacks.onImportContent() },
        onDocumentOpen = { onOpenDocument(it.id) },
        onDocumentDelete = { document ->
            activity.appScope.launch(Dispatchers.IO) {
                val result = graph.documentDeletionService.delete(document.id)
                if (!result.filesDeleted) {
                    activity.importStatus =
                        "Document removed; some local files could not be deleted"
                }
            }
        },
        // One search contract for every shelf: the icon toggles the mode's rail
        // search, which keeps the Books/Manga/Documents switch on screen.
        onSearchClick = {
            when (navModel.libraryMode) {
                LibraryMode.BOOKS -> navModel.bookSearchActive = !navModel.bookSearchActive
                LibraryMode.DOCUMENTS -> navModel.documentSearchActive = !navModel.documentSearchActive
                LibraryMode.MANGA -> navModel.mangaSearchActive = !navModel.mangaSearchActive
            }
        },
        bookSearchActive = navModel.bookSearchActive,
        onBookSearchActiveChange = { navModel.bookSearchActive = it },
        bookSearchController = navModel.bookSearchController,
        onOpenBookHit = { hit ->
            onOpenReaderAt(hit.book.id, hit.spineIndex.takeIf { it >= 0 }, hit.startFraction.takeIf { it >= 0f })
        },
        documentSearchActive = navModel.documentSearchActive,
        onDocumentSearchActiveChange = { navModel.documentSearchActive = it },
        onSettingsClick = onOpenSettings,
        showSettingsAction = false,
        onTagManagerClick = onOpenTags,
        onQuoteBrowserClick = onOpenQuotes,
        onRevisitClick = onOpenRevisit,
        onShareBooks = { callbacks.onShareBooks(it) },
        onShareDocuments = { callbacks.onShareDocuments(it) },
        onDeleteBooks = { ids ->
            activity.appScope.launch(Dispatchers.IO) {
                ids.forEach { id ->
                    runCatching { graph.bookRepository.deleteBook(id) }
                    runCatching { graph.platform.fileSystem.deleteBookFiles(id) }
                }
            }
        },
        onSetBookStatus = { ids, status ->
            activity.appScope.launch(Dispatchers.IO) {
                ids.forEach { id -> runCatching { graph.bookRepository.setBookStatus(id, status) } }
            }
        },
        viewModel = navModel.libraryVM,
        // The shelf's seed for its opening frame, kept hot since app start. See
        // LibraryScreen.knownBooks — without this the grid composes in the middle
        // of the Home→Library morph.
        knownBooks = navModel.libraryBooks,
        syncState = navModel.collectSyncState(),
        onSyncNow = { graph.syncEngine?.triggerSync(immediate = true) },
        libraryMode = navModel.libraryMode,
        onLibraryModeChange = { navModel.libraryMode = it },
        mangaLibraryViewModel = navModel.mangaLibVM,
        mangaExtensionsAvailable = graph.mangaBackend.supportsExtensions,
        onMangaBrowseClick = onOpenMangaBrowse,
        onMangaExtensionsClick = onOpenMangaExtensions,
        onMangaHistoryClick = onOpenMangaHistory,
        onMangaBackupImport = { callbacks.onImportMangaBackup() },
        onMangaBackupExport = { callbacks.onExportMangaBackup() },
        booksViewMode = LibraryViewModel.ViewMode.entries[navModel.sharedViewIndex],
        onBooksViewModeChange = { navModel.sharedViewIndex = it.ordinal },
        preserveFeaturedBookDuringSelection = true,
        mangaViewMode = com.folio.reader.ui.manga.MangaViewMode.entries[navModel.sharedViewIndex],
        onMangaViewModeChange = { navModel.sharedViewIndex = it.ordinal },
        onMangaSearchClick = { navModel.mangaSearchActive = !navModel.mangaSearchActive },
        mangaSearchActive = navModel.mangaSearchActive,
        onMangaSearchActiveChange = { navModel.mangaSearchActive = it },
        mangaSourcesAvailable = navModel.mangaBrowseVM != null,
        onOpenMangaDownloads = onOpenMangaDownloads,
        onMangaImportClick = { callbacks.onImportMangaChoice() },
        onRemoveSelectedManga = { ids ->
            mangaRemovalIds = ids
            deleteMangaDownloads = false
            clearMangaSelectionAfterRemoval = true
        },
        mangaContent = {
            com.folio.reader.ui.manga.MangaLibraryScreen(
                viewModel = navModel.mangaLibVM,
                backend = graph.mangaBackend,
                supportsExtensions = graph.mangaBackend.supportsExtensions,
                onOpenManga = { id -> onOpenMangaDetail(id) },
                onOpenBrowse = onOpenMangaBrowse,
                onOpenExtensions = onOpenMangaExtensions,
                onOpenSource = { source, q -> onOpenMangaSource(source.id, q) },
                viewMode = com.folio.reader.ui.manga.MangaViewMode.entries[navModel.sharedViewIndex],
                onViewModeChange = { navModel.sharedViewIndex = it.ordinal },
                searchActive = navModel.mangaSearchActive,
                onSearchActiveChange = { navModel.mangaSearchActive = it },
                browseViewModel = navModel.mangaBrowseVM,
                onImportLocal = { callbacks.onImportMangaChoice() },
                onRemoveManga = { id ->
                    mangaRemovalIds = setOf(id)
                    deleteMangaDownloads = false
                    clearMangaSelectionAfterRemoval = false
                },
            )
        }
    )

    mangaRemovalIds?.let { ids ->
        AlertDialog(
            onDismissRequest = {
                mangaRemovalIds = null
                deleteMangaDownloads = false
                clearMangaSelectionAfterRemoval = false
            },
            title = { Text("Remove manga?") },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deleteMangaDownloads = !deleteMangaDownloads
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = deleteMangaDownloads,
                        onCheckedChange = { deleteMangaDownloads = it },
                    )
                    Text("Also delete downloaded chapters")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deleteDownloads = deleteMangaDownloads
                        val clearSelection = clearMangaSelectionAfterRemoval
                        mangaRemovalIds = null
                        deleteMangaDownloads = false
                        clearMangaSelectionAfterRemoval = false
                        if (clearSelection) {
                            navModel.mangaLibVM.clearSelection()
                        }
                        removalScope.launch(Dispatchers.IO) {
                            navModel.removeMangaFromLibrary(ids, deleteDownloads)
                        }
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        mangaRemovalIds = null
                        deleteMangaDownloads = false
                        clearMangaSelectionAfterRemoval = false
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun StatsRoute(navModel: FolioNavModelImpl, onOpenBookDetail: (String) -> Unit) {
    val navController = navModel.navController ?: return
    // Same scroll-linked masthead as Library and Home: the charts dissolve into the
    // bar's glass instead of sliding under a fixed slab.
    val headerState = com.folio.reader.ui.components.rememberFolioHeaderState()
    // StatisticsTabContent is embeddable and supplies no top bar of its own; the
    // Android host provides one (with the status-bar band) like the other tabs.
    // The screen paints nothing of its own: the app's `folioField` ground plane
    // shows through, so Stats sits in the same environment as every other tab.
    //
    // Overlay, not a row: the charts run to the top of the window and the masthead
    // floats over them, which is the only way its glass has something behind it.
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.folio.reader.ui.theme.LocalFolioTopInset provides
                com.folio.reader.ui.theme.folioBarTopInset()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection)
                    .folioBackdropSource()
            ) {
                StatisticsTabContent(
                    viewModel = navModel.statisticsVM,
                    onBookClick = onOpenBookDetail,
                    settingsRepository = navModel.graph.settingsRepository,
                    initialGoalMinutes = navModel.globalSettings.dailyGoalMinutes,
                    mangaStatsRepo = com.folio.reader.database.JdbcMangaStatisticsRepository(navModel.graph.database),
                    onOpenExclusions = {
                        navController.navigate(FolioDestination.settings(com.folio.reader.settings.FolioSettingsCategory.STATS))
                    }
                )
            }
        }
        FolioTopBar(
            title = "Stats",
            collapse = headerState.collapse,
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
        )
    }
}

/**
 * §3.5: More is the hub of grouped rows into settings categories and library
 * tools. Chips no longer switch settings categories — rows navigate.
 */
@Composable
fun MoreRoute(
    navModel: FolioNavModelImpl,
    onOpenSettings: (String) -> Unit,
    onOpenTags: () -> Unit,
    onOpenQuotes: () -> Unit,
    onOpenRevisit: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAtlas: () -> Unit
) {
    com.folio.reader.settings.SettingsHubScreen(
        onOpenSettings = onOpenSettings,
        onOpenTags = onOpenTags,
        onOpenQuotes = onOpenQuotes,
        onOpenRevisit = onOpenRevisit,
        onOpenExtensions = onOpenExtensions,
        onOpenDownloads = onOpenDownloads,
        onOpenHistory = onOpenHistory,
        onOpenAtlas = onOpenAtlas
    )
}

@Composable
fun SettingsRoute(navModel: FolioNavModelImpl, category: String, onBack: () -> Unit) {
    when (category) {
        com.folio.reader.settings.FolioSettingsCategory.DEFAULTS ->
            com.folio.reader.settings.SettingsReaderDefaultsScreen(navModel, onBack)

        // §14.2: one merged screen; the pre-merge categories (typography,
        // layout, formatting) keep resolving to it for old deep links.
        com.folio.reader.settings.FolioSettingsCategory.TEXT_AND_PAGE,
        com.folio.reader.settings.FolioSettingsCategory.TYPOGRAPHY,
        com.folio.reader.settings.FolioSettingsCategory.LAYOUT,
        com.folio.reader.settings.FolioSettingsCategory.FORMATTING ->
            com.folio.reader.settings.SettingsTextPageScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.READING ->
            com.folio.reader.settings.SettingsReadingScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.THEMES ->
            com.folio.reader.settings.SettingsThemesScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.CUSTOM_THEME ->
            com.folio.reader.settings.SettingsCustomThemeScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.TRANSPARENCY ->
            com.folio.reader.settings.SettingsTransparencyScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.CLOUD_SYNC ->
            com.folio.reader.settings.SettingsCloudSyncScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.ADVANCED ->
            com.folio.reader.settings.SettingsAdvancedScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.STATS ->
            com.folio.reader.settings.SettingsStatsScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.MANGA ->
            com.folio.reader.settings.SettingsMangaScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.LIBRARY_SCAN ->
            com.folio.reader.settings.SettingsLibraryScanScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.SEMANTIC_SEARCH ->
            com.folio.reader.settings.SettingsSemanticSearchScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.STORAGE ->
            com.folio.reader.settings.SettingsStorageScreen(navModel, onBack)

        // Unknown/legacy categories (e.g. deep links from older builds) land
        // on the hub instead of a dead end.
        else -> MoreRoute(
            navModel,
            onOpenSettings = { navModel.navController?.navigate(FolioDestination.settings(it)) },
            onOpenTags = { navModel.navController?.navigate(FolioRoutes.TAGS) },
            onOpenQuotes = { navModel.navController?.navigate(FolioRoutes.QUOTES) },
            onOpenRevisit = { navModel.navController?.navigate(FolioRoutes.REVISIT) },
            onOpenExtensions = { navModel.navController?.navigate(FolioRoutes.EXTENSIONS) },
            onOpenDownloads = { navModel.navController?.navigate(FolioRoutes.MANGA_DOWNLOADS) },
            onOpenHistory = { navModel.navController?.navigate(FolioRoutes.MANGA_HISTORY) },
            onOpenAtlas = { navModel.navController?.navigate(FolioRoutes.ATLAS) }
        )
    }
}
