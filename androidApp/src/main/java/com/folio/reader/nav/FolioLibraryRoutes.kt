package com.folio.reader.nav

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.mangaId
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.home.HomeScreen
import com.folio.reader.ui.home.HomeUiState
import com.folio.reader.ui.home.HomeViewModel
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.library.LibraryScreen
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
    // §13.9 hero collapse: Home reports the fully-collapsed flip, the hero title
    // and the hero tint; the bar swaps its title to titleMedium and bleeds the
    // hero gradient upward while the collapse tracks the finger inside HomeScreen.
    // §13.9 hero collapse: Home reports the fraction continuously, plus the hero title
    // and the hero tint. The masthead cross-fades the wordmark out and the book's title
    // in across the same scroll, gains its glass at the same rate, and takes a bleed of
    // the hero's own colour — so the card does not dissolve into a bar that is still
    // saying something else.
    var heroCollapse by remember { mutableStateOf(0f) }
    var heroTitle by remember { mutableStateOf<String?>(null) }
    var heroTint by remember { mutableStateOf<Color?>(null) }
    // Overlay: the page runs to the top of the window and the masthead floats over
    // it, so the hero genuinely passes behind the glass as it collapses into the bar.
    Box(modifier = Modifier.fillMaxSize()) {
        // align stays HERE, on the box that wraps the bar alone: the hero tint below
        // uses matchParentSize, which resolves against this box. Hoisting align to
        // the full-screen one would size that tint to the viewport and flood the page.
        Box(modifier = Modifier.align(Alignment.TopCenter).zIndex(1f)) {
            val fraction = heroCollapse
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
        Box(modifier = Modifier.fillMaxSize()) {
            val viewModel = remember {
                HomeViewModel(
                    graph.bookRepository,
                    graph.sessionRepository,
                    graph.settingsRepository,
                    // §11.2/§12.9: exclusions gate every Home content selection;
                    // group repos resolve each book's tags and collections.
                    com.folio.reader.database.JdbcStatsExclusionRepository(graph.database),
                    graph.tagRepository,
                    graph.collectionRepository,
                    // §11.4: the New-chapters card reads manga_update_state through
                    // the same exclusions the worker's badges are gated by.
                    graph.mangaUpdateRepository,
                    // §11.4 Phase 9: manga Continue reading + Discover.
                    graph.mangaHistoryRepository,
                    graph.mangaRepository,
                    graph.mangaChapterRepository,
                    graph.mangaCategoryRepository,
                    graph.mangaBackend,
                    // §11.4: chapter pace for the manga predictions on Reading now.
                    com.folio.reader.database.JdbcMangaStatisticsRepository(graph.database)
                )
            }
            val state by viewModel.state.collectAsState(initial = HomeUiState())
            HomeScreen(
                state = state,
                topInset = folioBarTopInset(),
                onOpenBook = { navController.navigate(FolioDestination.reader(it)) },
                onOpenBookDetail = { navController.navigate(FolioDestination.bookDetail(it)) },
                onImportClick = { navModel.callbacks.onImportEpubs() },
                onOpenStats = { navController.navigate(FolioRoutes.STATS) },
                onOpenLibrary = { navController.navigate(FolioRoutes.LIBRARY) },
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
                    heroCollapse = collapsed
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
                onImportLocal = { callbacks.onImportMangaChoice() },
                // One rail on the manga shelf too: the Books/Manga switch leads the
                // category chips instead of sitting on a row of its own above them.
                railLeading = {
                    com.folio.reader.ui.library.LibraryModeSwitch(
                        mode = navModel.libraryMode,
                        onModeChange = { navModel.libraryMode = it },
                    )
                }
            )
        }
    )
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
        com.folio.reader.settings.FolioSettingsCategory.DEFAULTS ->
            com.folio.reader.settings.SettingsReaderDefaultsScreen(navModel, onBack)

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

        com.folio.reader.settings.FolioSettingsCategory.STATS ->
            com.folio.reader.settings.SettingsStatsScreen(navModel, onBack)

        com.folio.reader.settings.FolioSettingsCategory.MANGA ->
            com.folio.reader.settings.SettingsMangaScreen(navModel, onBack)

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
