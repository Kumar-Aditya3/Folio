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
import com.folio.reader.settings.diffFields
import com.folio.reader.settings.withFieldsFrom
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.library.LibraryViewModel
import com.folio.reader.ui.search.SearchUiState
import com.folio.reader.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings fields whose change means the sync loop must be rebuilt. */
private val SETTINGS_CREDENTIAL_FIELDS =
    setOf("firebaseApiKey", "firebaseProjectId", "syncAccountEmail", "syncAccountPassword")

/**
 * Android nav model: owns the state hoisted across destinations and implements
 * [FolioNavModel] by delegating each destination to its route composable
 * (§3.2/§3.4 FOLIO_IMPLEMENTATION_SPEC).
 */
class FolioNavModelImpl(internal var activity: MainActivity) : FolioNavModel {

    /**
     * Re-points the model at the new activity instance after a configuration
     * change. The model itself survives relaunches in a ViewModel holder —
     * rebuilding it per rotation recreated every tab view model and flow, which
     * flashed the shelves blank (the "manga appear and disappear" report) and
     * leaked one update-loop per rebuild.
     */
    internal fun rebind(activity: MainActivity) {
        this.activity = activity
    }

    override val graph: AppGraph get() = (activity.application as FolioApplication).graph

    /** Controller is set by setContent before any destination can use it. */
    var navController: NavHostController? = null

    /** Activity-level pickers/dialogs; defaults to no-ops until wired. */
    var callbacks: FolioNavCallbacks = object : FolioNavCallbacks {}

    // ── Hoisted UI state (was remembered in setContent before the nav move) ──
    var globalSettings by mutableStateOf(ReaderSettings())

    /**
     * Guards [warmGlobalSettings] so composition can call it on every frame
     * without queueing a read per frame. Not `remember`-ed, because the model
     * outlives the composition — the flag has to survive as long as the loaded
     * row does.
     */
    private var globalSettingsWarmed = false

    /**
     * Starts the global settings read now and drops the result into
     * [globalSettings] as soon as it lands.
     *
     * Every setting-dependent behaviour in the app reads [globalSettings], which
     * starts as [ReaderSettings] — all defaults. While the read was a
     * `LaunchedEffect` in `MainActivity` it landed one frame late, so the first
     * frame of *every* launch ran on defaults no matter what the reader had
     * configured. `morphIntoReader` (default `false`) is the case that surfaced
     * it: a reader route composed on frame one published no cover key, so the
     * morph had nothing to pair with and never ran — reported as "the morph
     * doesn't work if the books aren't loaded", when the real dependency was this
     * read.
     *
     * Idempotent, so it is safe to call from a `SideEffect`. Deliberately *not*
     * `runBlocking`: the read is a single indexed key lookup and will normally win
     * the race against a cover decode, and blocking the main thread in a
     * composition to save a frame or two would trade a rare missed morph for a
     * reliably slower launch. The upshot is the flag is set before any shelf has
     * decoded a cover to hand over, which is the whole window that matters.
     */
    fun warmGlobalSettings() {
        if (globalSettingsWarmed) return
        globalSettingsWarmed = true
        activity.appScope.launch(Dispatchers.IO) {
            val loaded = runCatching { graph.settingsRepository.getGlobalSettings() }.getOrNull()
                ?: return@launch
            withContext(Dispatchers.Main) { globalSettings = loaded }
        }
    }

    var libraryMode by mutableStateOf(LibraryMode.BOOKS)
    var libraryModeLoaded by mutableStateOf(false)
    var sharedViewIndex by mutableIntStateOf(0)
    var mangaSearchActive by mutableStateOf(false)
    var bookSearchActive by mutableStateOf(false)
    var documentSearchActive by mutableStateOf(false)
    var annotationFormat by mutableStateOf("json")
    var mangaDownloadsLocation by mutableStateOf("")
    var mangaDefaultMode by mutableStateOf(com.folio.reader.ui.manga.MangaReaderMode.WEBTOON)

    fun updateMangaDefaultMode(mode: com.folio.reader.ui.manga.MangaReaderMode) {
        mangaDefaultMode = mode
        activity.appScope.launch(Dispatchers.IO) {
            runCatching {
                graph.settingsRepository.setRaw(
                    com.folio.reader.ui.manga.KEY_MANGA_READER_DEFAULT_MODE,
                    mode.name,
                )
            }
        }
    }

    suspend fun removeMangaFromLibrary(mangaIds: Set<String>, deleteDownloads: Boolean) {
        mangaIds.forEach { mangaId ->
            if (deleteDownloads) {
                graph.mangaChapterRepository.getChapters(mangaId)
                    .filter { it.downloadedPages > 0 }
                    .forEach { chapter ->
                        graph.mangaDownloadManager.deleteChapterDownload(mangaId, chapter.id)
                        graph.mangaChapterRepository.setDownloadedPages(chapter.id, 0)
                    }
            }
            graph.mangaRepository.setInLibrary(mangaId, false)
        }
    }

    // ── View models shared across destinations ───────────────────────────────
    val libraryVM by lazy {
        LibraryViewModel(
            bookRepository = graph.bookRepository,
            collectionRepository = graph.collectionRepository,
            seriesRepository = graph.seriesRepository,
            // §5.1: enables the "~6 days left" captions on the Android shelf.
            sessionRepository = graph.sessionRepository,
            // Collection shelves: the selection is remembered in settings, so
            // the shelf the reader left reopens on the next visit.
            settingsRepository = graph.settingsRepository
        )
    }
    val documentLibraryVM by lazy {
        com.folio.reader.ui.library.DocumentLibraryViewModel(
            repository = graph.documentRepository,
            categoryRepository = graph.documentCategoryRepository,
            settingsRepository = graph.settingsRepository
        )
    }

    /**
     * Home, hoisted like the other tab view models: one instance for the app's
     * lifetime. It used to be `remember`ed inside the route, so every visit
     * rebuilt it and re-ran its whole query set behind a skeleton.
     */
    val homeVM by lazy {
        com.folio.reader.ui.home.HomeViewModel(
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

    /**
     * Home's state, kept hot for the app's whole lifetime. `state` is a cold
     * flow that re-runs every query per collection; collecting it eagerly from
     * the moment the app opens means the first Home visit renders real content
     * (not a skeleton) and tab morphs into Home compose against live data.
     */
    val homeState: kotlinx.coroutines.flow.StateFlow<com.folio.reader.ui.home.HomeUiState> by lazy {
        homeVM.state.stateIn(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.folio.reader.ui.home.HomeUiState()
        )
    }

    /**
     * The books shelf's unfiltered read, kept hot for the app's whole lifetime —
     * the Library counterpart of [homeState], and for the same reason.
     *
     * The shelf's own flow is a cold `combine` that the *destination* starts, so
     * before this existed the Library's first composition had nothing to seed
     * from. [com.folio.reader.ui.library.LibraryScreen] reads this list to decide
     * what its opening frame shows (see `libraryShelfSeed`), and against a cold
     * read that answer was always "not measured yet" — the skeleton. The real
     * grid then composed whenever the read landed, which on a cold start is
     * ~280–380 ms after the tap: the *middle* of the Home→Library morph, where
     * it cost a single 27–31 ms recomposition plus ~50–90 ms of blocked UI
     * thread inside one frame. That is the stutter the reader reports as "slow at
     * the very beginning when I open the app", why a few warm visits look
     * smooth, and why relaunching brings it back.
     *
     * Hot from app start via [warmTabState], so the destination's opening frame
     * already has a complete shelf: the grid composes once, on the transition's
     * first frame, where the enter fade is still at ~0 alpha and a long frame is
     * not visible — instead of landing mid-flight with covers already in motion.
     *
     * Note this must be read as a `StateFlow`, not merely collected: the seed has
     * to be right on the *first* composition, and `collectAsState` cannot deliver
     * a value before the frame after that. See `LibraryScreen.knownBooks`.
     */
    val libraryBooks: kotlinx.coroutines.flow.StateFlow<List<com.folio.reader.model.Book>> by lazy {
        libraryVM.allBooks().stateIn(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            SharingStarted.Eagerly,
            emptyList()
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
            highlightRepository = graph.highlightRepository,
            // §11.2 stats exclusions: repository plus the group repos that resolve
            // each book's tags/collections (series/status come from the books).
            // The exclusions repo is built here against the shared database; the
            // settings UI is a later slice.
            statsExclusionRepository = com.folio.reader.database.JdbcStatsExclusionRepository(graph.database),
            tagRepository = graph.tagRepository,
            collectionRepository = graph.collectionRepository,
            // EXTENSION exclusions expand to source ids through the backend's
            // source list, so the manga statistics resolve them too.
            mangaBackend = graph.mangaBackend
        )
    }
    val mangaBrowseVM by lazy {
        com.folio.reader.ui.manga.BrowseViewModel(graph.mangaBackend, graph.mangaRepository)
    }
    /**
     * Cold-start warm-up: start every tab's eager collection now, in parallel
     * with the first frame of the Library, so by the time the reader reaches
     * Home or Stats the data is waiting rather than just beginning.
     *
     * **This `init` must stay below every `by lazy` it touches.** Kotlin runs
     * property initialisers and `init` blocks in textual order, so a `lazy`
     * declared *after* this block still has a null delegate field when the
     * block runs — touching it threw
     * `NullPointerException: kotlin.Lazy.getValue() on a null object reference`
     * from `getStatisticsVM` and killed the app on its first frame. Declaring
     * the warm-up last makes the ordering impossible to get wrong when more
     * tabs are added.
     *
     * `statisticsVM` is touched deliberately: its shared flows are eager, so
     * building the view model is what puts Stats' year-wide queries in flight.
     */
    init {
        warmTabState()
    }

    fun warmTabState() {
        // Settings first: they are the cheapest read here and the one every other
        // surface's behaviour depends on. See [warmGlobalSettings].
        warmGlobalSettings()
        homeState
        // The Library's shelf read. Warmed for the same reason as Home's — and
        // it is the one that *has* to be warm, because the shelf's own flow is
        // started by the destination and therefore cannot seed its own first
        // frame. Left cold, the grid composes mid-morph. See [libraryBooks].
        libraryBooks
        statisticsVM
    }
    val sourceBrowseVmCache = mutableMapOf<Long, com.folio.reader.ui.manga.SourceBrowseViewModel>()
    val searchUiState = SearchUiState()
    /**
     * The library rail's books search: query, scope and results hoisted here so
     * they survive navigation, running the same shared execution as the
     * full-screen search the reader opens.
     */
    val bookSearchController by lazy {
        com.folio.reader.ui.search.BookSearchController(
            searchRepository = graph.searchRepository,
            highlightRepository = graph.highlightRepository,
            noteRepository = graph.noteRepository,
            bookmarkRepository = graph.bookmarkRepository
        )
    }
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
            settingsRepo = graph.settingsRepository,
            sessionRepo = graph.sessionRepository,
            tagRepo = graph.tagRepository,
            cycleRepo = graph.readingCycleRepository
        ).also {
            mangaDetailVmKey = mangaId
            mangaDetailVmInstance = it
        }
    }

    /**
     * Saves only the fields the screen actually changed, so a stale snapshot
     * cannot clobber values written elsewhere since (e.g. changing the app
     * theme here must not resurrect an old reading theme). Restarts sync on
     * credential change.
     */
    fun updateSettings(updated: ReaderSettings) {
        val changed = updated.diffFields(globalSettings)
        val credsChanged = SETTINGS_CREDENTIAL_FIELDS.any { it in changed }
        // Optimistic patch keeps the screen live; the merged row the repository
        // returns reconciles anything written while the save was in flight.
        globalSettings = globalSettings.withFieldsFrom(changed, updated)
        activity.appScope.launch(Dispatchers.IO) {
            runCatching {
                val merged = graph.settingsRepository.mergeGlobalSettings { it.withFieldsFrom(changed, updated) }
                withContext(Dispatchers.Main) { globalSettings = merged }
            }
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
        onOpenReaderAt: (String, Int?) -> Unit,
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
    ) = LibraryRoute(
        this, onOpenReader, onOpenReaderAt, onOpenDocument, onOpenBookDetail, onOpenSearch, onOpenSettings,
        onOpenTags, onOpenQuotes, onOpenRevisit, onOpenMangaBrowse, onOpenMangaExtensions,
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
    override fun documentReaderContent(
        documentId: String,
        onBack: () -> Unit
    ) = DocumentReaderRoute(this, documentId, onBack)

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
    override fun quotesContent(onBack: () -> Unit, onOpenReader: (String) -> Unit, onOpenMangaDetail: (String) -> Unit) =
        QuotesRoute(this, onBack, onOpenReader, onOpenMangaDetail)

    @Composable
    override fun revisitContent(onBack: () -> Unit, onOpenReader: (String) -> Unit, onOpenMangaDetail: (String) -> Unit) =
        RevisitRoute(this, onBack, onOpenReader, onOpenMangaDetail)

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
