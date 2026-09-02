package com.folio.reader.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.home.HomeScreen
import com.folio.reader.ui.home.HomeUiState
import com.folio.reader.ui.home.HomeViewModel
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.library.LibraryScreen
import com.folio.reader.ui.library.LibraryViewModel
import com.folio.reader.ui.statistics.StatisticsTabContent
import com.folio.reader.ui.theme.FolioTheme
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FolioTheme.colors.background)
    ) {
        FolioTopBar(title = "Home")
        Box(modifier = Modifier.weight(1f)) {
            val viewModel = remember {
                HomeViewModel(graph.bookRepository, graph.sessionRepository, graph.settingsRepository)
            }
            val state by viewModel.state.collectAsState(initial = HomeUiState())
            HomeScreen(
                state = state,
                onOpenBook = { navController.navigate(FolioDestination.reader(it)) },
                onOpenBookDetail = { navController.navigate(FolioDestination.bookDetail(it)) },
                onImportClick = { navModel.callbacks.onImportEpubs() }
            )
        }
    }
}

@Composable
fun LibraryRoute(
    navModel: FolioNavModelImpl,
    onOpenReader: (String) -> Unit,
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

    // Persist the Books/Manga choice across launches (same raw key as pre-nav).
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
        onImportClick = { callbacks.onImportEpubs() },
        onSearchClick = onOpenSearch,
        onSettingsClick = onOpenSettings,
        showSettingsAction = false,
        onTagManagerClick = onOpenTags,
        onQuoteBrowserClick = onOpenQuotes,
        onRevisitClick = onOpenRevisit,
        onDeleteBooks = { ids ->
            activity.appScope.launch(Dispatchers.IO) {
                ids.forEach { id ->
                    runCatching { graph.bookRepository.deleteBook(id) }
                    runCatching { graph.platform.fileSystem.deleteBookFiles(id) }
                }
                activity.refreshTick++
            }
        },
        onSetBookStatus = { ids, status ->
            activity.appScope.launch(Dispatchers.IO) {
                ids.forEach { id -> runCatching { graph.bookRepository.setBookStatus(id, status) } }
            }
        },
        viewModel = navModel.libraryVM,
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
        mangaViewMode = com.folio.reader.ui.manga.MangaViewMode.entries[navModel.sharedViewIndex],
        onMangaViewModeChange = { navModel.sharedViewIndex = it.ordinal },
        onMangaSearchClick = { navModel.mangaSearchActive = !navModel.mangaSearchActive },
        onMangaImportClick = { callbacks.onImportMangaChoice() },
        mangaContent = {
            com.folio.reader.ui.manga.MangaLibraryScreen(
                viewModel = navModel.mangaLibVM,
                backend = graph.mangaBackend,
                supportsExtensions = graph.mangaBackend.supportsExtensions,
                onOpenManga = { id -> onOpenMangaDetail(id) },
                onOpenBrowse = onOpenMangaBrowse,
                onOpenExtensions = onOpenMangaExtensions,
                onOpenDownloads = onOpenMangaDownloads,
                onOpenSource = { source, q -> onOpenMangaSource(source.id, q) },
                viewMode = com.folio.reader.ui.manga.MangaViewMode.entries[navModel.sharedViewIndex],
                onViewModeChange = { navModel.sharedViewIndex = it.ordinal },
                searchActive = navModel.mangaSearchActive,
                onSearchActiveChange = { navModel.mangaSearchActive = it },
                browseViewModel = navModel.mangaBrowseVM,
                onImportLocal = { callbacks.onImportMangaChoice() }
            )
        },
        // Stats moved to its own bottom-bar destination (§3.4 step 2).
        statsContent = null
    )
}

@Composable
fun StatsRoute(navModel: FolioNavModelImpl, onOpenBookDetail: (String) -> Unit) {
    // StatisticsTabContent is embeddable and supplies no top bar of its own; the
    // Android host provides one (with the status-bar band) like the other tabs.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FolioTheme.colors.background)
    ) {
        FolioTopBar(title = "Stats")
        Box(modifier = Modifier.weight(1f)) {
            StatisticsTabContent(
                viewModel = navModel.statisticsVM,
                onBookClick = onOpenBookDetail,
                settingsRepository = navModel.graph.settingsRepository,
                initialGoalMinutes = navModel.globalSettings.dailyGoalMinutes,
                mangaStatsRepo = com.folio.reader.database.JdbcMangaStatisticsRepository(navModel.graph.database)
            )
        }
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
    onOpenHistory: () -> Unit
) {
    com.folio.reader.settings.SettingsHubScreen(
        onOpenSettings = onOpenSettings,
        onOpenTags = onOpenTags,
        onOpenQuotes = onOpenQuotes,
        onOpenRevisit = onOpenRevisit,
        onOpenExtensions = onOpenExtensions,
        onOpenDownloads = onOpenDownloads,
        onOpenHistory = onOpenHistory
    )
}

@Composable
fun SettingsRoute(navModel: FolioNavModelImpl, category: String, onBack: () -> Unit) {
    when (category) {
        com.folio.reader.settings.FolioSettingsCategory.TYPOGRAPHY ->
            com.folio.reader.settings.SettingsTypographyScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.LAYOUT ->
            com.folio.reader.settings.SettingsLayoutScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.FORMATTING ->
            com.folio.reader.settings.SettingsFormattingScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.READING ->
            com.folio.reader.settings.SettingsReadingScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.THEMES ->
            com.folio.reader.settings.SettingsThemesScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.CLOUD_SYNC ->
            com.folio.reader.settings.SettingsCloudSyncScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.ADVANCED ->
            com.folio.reader.settings.SettingsAdvancedScreen(navModel, onBack)

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
            onOpenHistory = { navModel.navController?.navigate(FolioRoutes.MANGA_HISTORY) }
        )
    }
}
