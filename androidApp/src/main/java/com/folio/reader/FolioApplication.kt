package com.folio.reader

import android.app.Application
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcDeviceRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcReadingSessionRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.database.JdbcSeriesRepository
import com.folio.reader.database.JdbcQuoteRepository
import com.folio.reader.database.JdbcRevisitRepository
import com.folio.reader.database.JdbcTagRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.database.JdbcStatisticsRepository
import com.folio.reader.database.JdbcSyncQueueRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.epub.JvmChapterContentProvider
import com.folio.reader.importer.BookImporter
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.platform.AndroidPlatform
import com.folio.reader.sync.NoopStorageSync
import com.folio.reader.sync.RestFirebaseStorageSync
import com.folio.reader.sync.RestFirestoreSync
import com.folio.reader.sync.SyncConfig
import com.folio.reader.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency graph for the Android app (no DI framework).
 * sqldroid (JDBC over android.database.sqlite) provides the driver on Android; desktop uses sqlite-jdbc. Same Database class on both.
 *
 * Cloud sync activates when the user supplies a Firebase Project ID + Web API key
 * in Settings > Advanced (persisted in global settings); FOLIO_FB_PROJECT_ID /
 * FOLIO_FB_API_KEY env vars remain as a developer fallback. No account system —
 * Firestore access is authenticated anonymously using only the Web API key.
 */
class AppGraph(private val app: Application) {
    val platform = AndroidPlatform(app)
    val database = Database(platform.fileSystem.getDatabasePath())

    val bookRepository = JdbcBookRepository(database)
    val positionRepository = JdbcReadingPositionRepository(database)
    val sessionRepository = JdbcReadingSessionRepository(database)
    val bookmarkRepository = JdbcBookmarkRepository(database)
    val highlightRepository = JdbcHighlightRepository(database)
    val noteRepository = JdbcNoteRepository(database)
    val collectionRepository = JdbcCollectionRepository(database)
    val seriesRepository = JdbcSeriesRepository(database)
    val tagRepository = JdbcTagRepository(database)
    val quoteRepository = JdbcQuoteRepository(database)
    val revisitRepository = JdbcRevisitRepository(database)
    val settingsRepository = JdbcSettingsRepository(database)
    val statisticsRepository = JdbcStatisticsRepository(database)
    val syncQueueRepository = JdbcSyncQueueRepository(database)
    val deviceRepository = JdbcDeviceRepository(database)
    val searchRepository = JdbcSearchRepository(database)
    val searchIndexer = SearchIndexer(searchRepository)

    // ---------- Manga category (Mihon-powered backend) ----------
    val mangaRepository = com.folio.reader.database.JdbcMangaRepository(database)
    val mangaChapterRepository = com.folio.reader.database.JdbcMangaChapterRepository(database)
    val mangaCategoryRepository = com.folio.reader.database.JdbcMangaCategoryRepository(database)
    val mangaHistoryRepository = com.folio.reader.database.JdbcMangaHistoryRepository(database)
    val mangaDownloadRepository = com.folio.reader.database.JdbcMangaDownloadRepository(database)
    val mangaNoteRepository = com.folio.reader.database.JdbcMangaNoteRepository(database)
    val mangaBackend = com.folio.reader.manga.AndroidMangaBackend(
        context = app,
        settings = settingsRepository,
        fileSystem = platform.fileSystem,
    )
    val mangaDownloadManager = com.folio.reader.manga.MangaDownloadManager(
        backend = mangaBackend,
        downloadsRepo = mangaDownloadRepository,
        chapterRepo = mangaChapterRepository,
        downloadsDir = platform.fileSystem.mangaDownloadsDir,
    ).apply { start() }

    val epubParser = EpubParser(platform)
    val contentProvider = JvmChapterContentProvider(platform, epubParser)
    val fontManager = com.folio.reader.font.FontManager(platform)
    val bookImporter = BookImporter(
        platform = platform,
        epubParser = epubParser,
        bookRepository = bookRepository,
        positionRepository = positionRepository,
        searchIndexer = searchIndexer,
        hashUtil = platform.hasher
    )

    init {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database.onEntityChanged = { type, id, op, payload ->
            appScope.launch {
                syncQueueRepository.enqueueSync(type, id, com.folio.reader.sync.SyncOperation.fromString(op), payload)
                // Reading positions update on every scroll tick; a prompt sync per
                // write hammers Firestore. They still enqueue and ride along on the
                // next crucial sync. Only discrete edits schedule a prompt sync.
                if (type != "position") syncEngine?.triggerSync(immediate = false)
            }
        }
        // Seed the built-in Main category and adopt uncategorized library manga once
        // the sync hook above is live, so the seed document reaches other devices too.
        appScope.launch {
            runCatching { mangaCategoryRepository.ensureSeeded() }
        }
    }

    /** Stable per-installation device id, persisted next to the database. */
    val deviceId: String by lazy {
        val file = File(platform.fileSystem.getDatabasePath()).resolveSibling("device_id")
        if (file.exists()) {
            file.readText().trim()
        } else {
            val id = "android-" + java.util.UUID.randomUUID().toString().take(8)
            file.parentFile?.mkdirs()
            file.writeText(id)
            id
        }
    }

    private fun stringRes(app: Application, name: String): String? {
        val id = app.resources.getIdentifier(name, "string", app.packageName)
        return if (id != 0) app.getString(id) else null
    }

    /**
     * Firebase project baked into the app so cloud sync works out of the box once
     * the user supplies a Web API key. A user-entered Project ID in Settings >
     * Advanced (or FOLIO_FB_PROJECT_ID / folio_fb_project_id resource) takes priority.
     */
    private val bundledFirebaseProjectId = "folio-sync-53b90"

    private fun firebaseCreds(): FirebaseCredentials {
        // User-entered API key (Settings > Advanced) takes priority over env fallback.
        val global = cachedGlobalSettings
        val projectId = global?.firebaseProjectId?.takeIf { it.isNotBlank() }
            ?: System.getenv("FOLIO_FB_PROJECT_ID")
            ?: stringRes(app, "folio_fb_project_id")
            ?: bundledFirebaseProjectId
        val apiKey = global?.firebaseApiKey?.takeIf { it.isNotBlank() }
            ?: System.getenv("FOLIO_FB_API_KEY")
            ?: stringRes(app, "folio_fb_api_key")
        return FirebaseCredentials(projectId, apiKey)
    }

    private data class FirebaseCredentials(
        val projectId: String?,
        val apiKey: String?
    ) {
        val isConfigured: Boolean get() = !projectId.isNullOrBlank() && !apiKey.isNullOrBlank()
    }

    private fun storageBucket(): String? =
        System.getenv("FOLIO_FB_STORAGE_BUCKET") ?: stringRes(app, "folio_fb_storage_bucket")

    val exportManager by lazy {
        com.folio.reader.export.ExportManager(
            bookRepository = bookRepository,
            positionRepository = positionRepository,
            sessionRepository = sessionRepository,
            highlightRepository = highlightRepository,
            noteRepository = noteRepository,
            bookmarkRepository = bookmarkRepository,
            tagRepository = tagRepository,
            collectionRepository = collectionRepository,
            seriesRepository = seriesRepository,
            quoteRepository = quoteRepository,
            revisitRepository = revisitRepository,
            settingsRepository = settingsRepository,
            statisticsRepository = statisticsRepository
        )
    }

    @Volatile
    private var cachedSyncEngine: SyncEngine? = null

    /**
     * Settings snapshot used to build sync credentials. Loaded off-main at startup
     * and refreshed by [restartSync]; [syncEngine] is reached from composition, so
     * building it must never block on the database (runBlocking here parked the UI
     * thread for seconds — a full ANR — whenever the IO pool or SQLite was busy).
     */
    @Volatile
    private var cachedGlobalSettings: com.folio.reader.settings.ReaderSettings? = null

    private val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        graphScope.launch {
            cachedGlobalSettings =
                runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        }
    }

    /**
     * The active sync engine, or null when Firebase credentials are absent.
     * Built lazily from [firebaseCreds] and cached; rebuilt by [restartSync]
     * whenever the user changes the API key in Settings > Advanced so new
     * credentials take effect immediately.
     */
    val syncEngine: SyncEngine?
        get() {
            cachedSyncEngine?.let { return it }
            return synchronized(this) {
                cachedSyncEngine ?: createSyncEngine()?.also { cachedSyncEngine = it }
            }
        }

    private fun createSyncEngine(): SyncEngine? {
        val creds = firebaseCreds()
        if (!creds.isConfigured) return null
        val settings = cachedGlobalSettings
        val email = settings?.syncAccountEmail?.takeIf { it.isNotBlank() }
        val password = settings?.syncAccountPassword?.takeIf { it.isNotBlank() }
        return SyncEngine(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            syncRepository = syncQueueRepository,
            bookRepository = bookRepository,
            positionRepository = positionRepository,
            highlightRepository = highlightRepository,
            noteRepository = noteRepository,
            bookmarkRepository = bookmarkRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            deviceRepository = deviceRepository,
            collectionRepository = collectionRepository,
            seriesRepository = seriesRepository,
            tagRepository = tagRepository,
            quoteRepository = quoteRepository,
            revisitRepository = revisitRepository,
            firestoreSync = RestFirestoreSync(
                projectId = creds.projectId!!,
                apiKey = creds.apiKey!!,
                accountEmail = email,
                accountPassword = password
            ),
            // Real file/cover sync when a storage bucket is configured; metadata-only otherwise.
            storageSync = storageBucket()?.takeIf { it.isNotBlank() }?.let { bucket ->
                RestFirebaseStorageSync(
                    projectId = creds.projectId!!,
                    apiKey = creds.apiKey!!,
                    bucket = bucket,
                    accountEmail = email,
                    accountPassword = password
                )
            } ?: NoopStorageSync,
            config = SyncConfig(),
            deviceId = deviceId,
            mangaRepository = mangaRepository,
            mangaChapterRepository = mangaChapterRepository,
            mangaNoteRepository = mangaNoteRepository,
            mangaCategoryRepository = mangaCategoryRepository,
        )
    }

    /** True when Firebase credentials are present (env fallback or user-entered API key). */
    val isSyncConfigured: Boolean get() = firebaseCreds().isConfigured

    /**
     * Rebuilds the sync engine after the user saves/clears a Firebase API key in
     * Settings > Advanced. Stops any running loop, then starts a fresh engine if
     * credentials are now available. Safe to call from the UI thread.
     */
    fun restartSync(appScope: CoroutineScope) {
        val old = syncEngine
        cachedSyncEngine = null // next access rebuilds from current credentials
        old?.stop()
        appScope.launch(Dispatchers.IO) {
            cachedGlobalSettings =
                runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
            cachedSyncEngine = null
            startSync(appScope)
        }
    }

    fun startSync(scope: CoroutineScope) {
        val engine = syncEngine ?: return
        scope.launch { engine.start() }
    }

    fun shutdown() {
        syncEngine?.syncOnAppClose()
        syncEngine?.stop()
        database.close()
    }
}

class FolioApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = runCatching { AppGraph(this) }.getOrElse { e ->
            e.printStackTrace()
            // Last-resort: rethrow so the crash is visible in logcat rather than a blank hang
            throw e
        }
        // Extract bundled fonts (Calluna, Comfortaa) and register them in settings.
        // Local file copy + one settings row — fast enough to run on the main thread
        // and guarantees the activity sees them on first launch.
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.folio.reader.font.BundledFonts.ensureInstalled(graph.platform, graph.settingsRepository) { path ->
                    runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()
                }
            }
        }.onFailure { it.printStackTrace() }
        // Same directory holds the interface faces (Fraunces/Manrope); installed here
        // so the first frame is never drawn in the system font.
        com.folio.reader.ui.theme.UiFonts.install(graph.platform.fileSystem.getFontsDir())
    }

    override fun onTerminate() {
        graph.shutdown()
        super.onTerminate()
    }
}
