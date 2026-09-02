package com.folio.reader.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.folio.reader.AppGraph
import com.folio.reader.MainActivity
import com.folio.reader.FolioApplication
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.library.LibraryViewModel
import com.folio.reader.ui.search.SearchUiState
import com.folio.reader.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Android nav model: owns the state hoisted across destinations and implements
 * [FolioNavModel] by delegating each destination to its route composable
 * (§3.2/§3.4 FOLIO_IMPLEMENTATION_SPEC).
 */
class FolioNavModelImpl(internal val activity: MainActivity) : FolioNavModel {

    override val graph: AppGraph get() = (activity.application as FolioApplication).graph

    /** Controller is set by setContent before any destination can use it. */
    var navController: NavHostController? = null

    /** Activity-level pickers/dialogs; defaults to no-ops until wired. */
    var callbacks: FolioNavCallbacks = object : FolioNavCallbacks {}

    // ── Hoisted UI state (was remembered in setContent before the nav move) ──
    var globalSettings by mutableStateOf(ReaderSettings())
    var libraryMode by mutableStateOf(LibraryMode.BOOKS)
    var libraryModeLoaded by mutableStateOf(false)
    var sharedViewIndex by mutableIntStateOf(0)
    var mangaSearchActive by mutableStateOf(false)
    var annotationFormat by mutableStateOf("json")
    var mangaDownloadsLocation by mutableStateOf("")

    // ── View models shared across destinations ───────────────────────────────
    val libraryVM by lazy {
        LibraryViewModel(
            bookRepository = graph.bookRepository,
            collectionRepository = graph.collectionRepository,
            seriesRepository = graph.seriesRepository
        )
    }
    val mangaLibVM by lazy {
        com.folio.reader.ui.manga.MangaLibraryViewModel(
            backend = graph.mangaBackend,
            mangaRepo = graph.mangaRepository,
            categoryRepo = graph.mangaCategoryRepository,
            chapterRepo = graph.mangaChapterRepository,
            settingsRepo = graph.settingsRepository,
            downloadRepo = graph.mangaDownloadRepository
        )
    }
    val statisticsVM by lazy {
        StatisticsViewModel(
            bookRepository = graph.bookRepository,
            sessionRepository = graph.sessionRepository,
            quoteRepository = graph.quoteRepository,
            highlightRepository = graph.highlightRepository
        )
    }
    val mangaBrowseVM by lazy {
        com.folio.reader.ui.manga.BrowseViewModel(graph.mangaBackend, graph.mangaRepository)
    }
    val sourceBrowseVmCache = mutableMapOf<Long, com.folio.reader.ui.manga.SourceBrowseViewModel>()
    val searchUiState = SearchUiState()
    val mangaBackupManager by lazy {
        com.folio.reader.manga.backup.MangaBackupManager(
            mangaRepo = graph.mangaRepository,
            chapterRepo = graph.mangaChapterRepository,
            categoryRepo = graph.mangaCategoryRepository,
            historyRepo = graph.mangaHistoryRepository
        )
    }

    // ── Manga detail: one live VM so system back can clear chapter selection ─
    private var mangaDetailVmKey: String? = null
    private var mangaDetailVmInstance: com.folio.reader.ui.manga.MangaDetailViewModel? = null
    val activeMangaDetailVM: com.folio.reader.ui.manga.MangaDetailViewModel?
        get() = mangaDetailVmInstance

    fun mangaDetailViewModel(mangaId: String): com.folio.reader.ui.manga.MangaDetailViewModel {
        if (mangaDetailVmKey == mangaId) return mangaDetailVmInstance!!
        return com.folio.reader.ui.manga.MangaDetailViewModel(
            backend = graph.mangaBackend,
            mangaRepo = graph.mangaRepository,
            chapterRepo = graph.mangaChapterRepository,
            historyRepo = graph.mangaHistoryRepository,
            downloadManager = graph.mangaDownloadManager,
            downloadRepo = graph.mangaDownloadRepository,
            categoryRepo = graph.mangaCategoryRepository,
            settingsRepo = graph.settingsRepository
        ).also {
            mangaDetailVmKey = mangaId
            mangaDetailVmInstance = it
        }
    }

    /** Saves settings whole, mirroring the pre-nav behaviour; restarts sync on credential change. */
    fun updateSettings(updated: ReaderSettings) {
        val credsChanged = updated.firebaseApiKey != globalSettings.firebaseApiKey ||
                updated.firebaseProjectId != globalSettings.firebaseProjectId ||
                updated.syncAccountEmail != globalSettings.syncAccountEmail ||
                updated.syncAccountPassword != globalSettings.syncAccountPassword
        globalSettings = updated
        activity.appScope.launch(Dispatchers.IO) {
            runCatching { graph.settingsRepository.saveGlobalSettings(updated) }
        }
        if (credsChanged) graph.restartSync(activity.appScope)
    }

    @Composable
    fun collectSyncState(): SyncState? {
        val state by remember(graph.syncEngineState.value) {
            graph.syncEngine?.syncState ?: flowOf(null)
        }.collectAsState(initial = null)
        return state
    }

    // ── FolioNavModel: each destination delegates to its route composable ───
    @Composable
    override fun homeContent() = HomeRoute(this)

    @Composable
    override fun libraryContent(
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
    ) = LibraryRoute(
        this, onOpenReader, onOpenBookDetail, onOpenSearch, onOpenSettings, onOpenTags,
        onOpenQuotes, onOpenRevisit, onOpenMangaBrowse, onOpenMangaExtensions,
        onOpenMangaHistory, onOpenMangaDetail, onOpenMangaDownloads, onOpenMangaSource
    )

    @Composable
    override fun statsContent(onOpenBookDetail: (String) -> Unit) = StatsRoute(this, onOpenBookDetail)

    @Composable
    override fun moreContent(
        onOpenSettings: (String) -> Unit,
        onOpenTags: () -> Unit,
        onOpenQuotes: () -> Unit,
        onOpenRevisit: () -> Unit,
        onOpenExtensions: () -> Unit,
        onOpenDownloads: () -> Unit,
        onOpenHistory: () -> Unit
    ) = MoreRoute(
        this, onOpenSettings, onOpenTags, onOpenQuotes, onOpenRevisit,
        onOpenExtensions, onOpenDownloads, onOpenHistory
    )

    @Composable
    override fun readerContent(
        bookId: String,
        targetSpineIndex: Int?,
        onBack: () -> Unit,
        onOpenSearch: () -> Unit,
        onOpenSettings: () -> Unit
    ) = ReaderRoute(this, bookId, targetSpineIndex, onBack, onOpenSearch, onOpenSettings)

    @Composable
    override fun bookDetailContent(
        bookId: String,
        onBack: () -> Unit,
        onStartReading: () -> Unit,
        onOpenTags: () -> Unit
    ) = BookDetailRoute(this, bookId, onBack, onStartReading, onOpenTags)

    @Composable
    override fun searchContent(onBack: () -> Unit, onOpenReader: (String, Int?) -> Unit) =
        SearchRoute(this, onBack, onOpenReader)

    @Composable
    override fun settingsContent(category: String, onBack: () -> Unit) =
        SettingsRoute(this, category, onBack)

    @Composable
    override fun tagsContent(onBack: () -> Unit, onOpenBookDetail: (String) -> Unit, onOpenReader: (String) -> Unit) =
        TagsRoute(this, onBack, onOpenBookDetail, onOpenReader)

    @Composable
    override fun quotesContent(onBack: () -> Unit, onOpenReader: (String) -> Unit) =
        QuotesRoute(this, onBack, onOpenReader)

    @Composable
    override fun revisitContent(onBack: () -> Unit, onOpenReader: (String) -> Unit) =
        RevisitRoute(this, onBack, onOpenReader)

    @Composable
    override fun mangaDetailContent(mangaId: String, onBack: () -> Unit, onRead: (String) -> Unit) =
        MangaDetailRoute(this, mangaId, onBack, onRead)

    @Composable
    override fun mangaReaderContent(mangaId: String, chapterId: String, onBack: () -> Unit, onNextChapter: (String) -> Unit) =
        MangaReaderRoute(this, mangaId, chapterId, onBack, onNextChapter)

    @Composable
    override fun mangaSourceBrowseContent(sourceId: Long, query: String, onBack: () -> Unit, onOpenManga: (String) -> Unit) =
        MangaSourceBrowseRoute(this, sourceId, query, onBack, onOpenManga)

    @Composable
    override fun mangaBrowseContent(onBack: () -> Unit, onOpenSource: (Long, String) -> Unit, onOpenExtensions: () -> Unit, onOpenManga: (String) -> Unit) =
        MangaBrowseRoute(this, onBack, onOpenSource, onOpenExtensions, onOpenManga)

    @Composable
    override fun extensionsContent(onBack: () -> Unit) = ExtensionsRoute(this, onBack)

    @Composable
    override fun mangaDownloadsContent(onBack: () -> Unit) = MangaDownloadsRoute(this, onBack)

    @Composable
    override fun mangaHistoryContent(onBack: () -> Unit, onOpenManga: (String) -> Unit) =
        MangaHistoryRoute(this, onBack, onOpenManga)
}
