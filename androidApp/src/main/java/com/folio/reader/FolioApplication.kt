package com.folio.reader

import android.app.Application
import io.sentry.android.core.SentryAndroid
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
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
    val readingCycleRepository = com.folio.reader.database.JdbcReadingCycleRepository(database)
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
        initialStorage = com.folio.reader.manga.FileDownloadStorage(platform.fileSystem.mangaDownloadsDir),
    ).apply { start() }
    val mangaUpdateRepository = com.folio.reader.database.JdbcMangaUpdateRepository(
        db = database,
        mangaRepository = mangaRepository,
        chapterRepository = mangaChapterRepository,
        backend = mangaBackend,
    )

    /**
     * Restores a user-picked manga downloads location at startup. The picker persists a
     * SAF tree URI in settings plus a persistable permission; if the permission was
     * revoked since, the setting is dropped and the app default stays active.
     */
    fun applyStoredMangaDownloadsLocation(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val raw = runCatching { settingsRepository.getRaw(com.folio.reader.manga.KEY_MANGA_DOWNLOADS_LOCATION) }
                .getOrNull()
            if (raw.isNullOrBlank()) return@launch
            val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return@launch
            val granted = app.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            if (!granted) {
                runCatching { settingsRepository.setRaw(com.folio.reader.manga.KEY_MANGA_DOWNLOADS_LOCATION, "") }
                return@launch
            }
            runCatching {
                val ok = mangaDownloadManager.switchStorage(com.folio.reader.manga.SafDownloadStorage(app, uri))
                if (!ok) runCatching { settingsRepository.setRaw(com.folio.reader.manga.KEY_MANGA_DOWNLOADS_LOCATION, "") }
            }
        }
    }

    /**
     * One-time backfill of Quotes/Revisit for annotations created before the reader
     * started populating those stores. Deterministic ids match the reader's
     * populate-on-create path, so the INSERT OR REPLACE writes are idempotent and a
     * retry after a mid-way failure is safe; the raw settings flag keeps it to one
     * successful pass per install.
     */
    fun backfillAnnotationsOnce(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val flag = "annotations_backfill_v1"
            if (runCatching { settingsRepository.getRaw(flag) }.getOrNull() == "1") return@launch
            runCatching {
                val quotedHighlights =
                    quoteRepository.getAllQuotes().first().map { it.highlightId }.toHashSet()
                for (book in bookRepository.getAllBooks().first()) {
                    val existingRevisits = revisitRepository.getRevisitItemsForBook(book.id)
                        .map { it.type to it.sourceId }
                        .toHashSet()

                    for (h in highlightRepository.getHighlightsForBook(book.id).first().filter { !it.isDeleted }) {
                        if (h.id !in quotedHighlights) {
                            quoteRepository.insertQuote(
                                com.folio.reader.model.Quote(
                                    id = "quote-${h.id}",
                                    bookId = h.bookId,
                                    chapterId = h.chapterId,
                                    highlightId = h.id,
                                    text = h.selectedText,
                                    deviceId = h.deviceId
                                )
                            )
                        }
                        if ((com.folio.reader.model.RevisitType.HIGHLIGHT to h.id) !in existingRevisits) {
                            revisitRepository.insertRevisitItem(
                                com.folio.reader.model.RevisitItem(
                                    id = "revisit-h-${h.id}",
                                    bookId = h.bookId,
                                    chapterId = h.chapterId,
                                    type = com.folio.reader.model.RevisitType.HIGHLIGHT,
                                    sourceId = h.id,
                                    deviceId = h.deviceId
                                )
                            )
                        }
                    }

                    for (b in bookmarkRepository.getBookmarksForBook(book.id).first().filter { !it.isDeleted }) {
                        if ((com.folio.reader.model.RevisitType.BOOKMARK to b.id) !in existingRevisits) {
                            revisitRepository.insertRevisitItem(
                                com.folio.reader.model.RevisitItem(
                                    id = "revisit-b-${b.id}",
                                    bookId = b.bookId,
                                    chapterId = b.chapterId,
                                    type = com.folio.reader.model.RevisitType.BOOKMARK,
                                    sourceId = b.id,
                                    deviceId = b.deviceId
                                )
                            )
                        }
                    }

                    for (n in noteRepository.getNotesForBook(book.id).first().filter { !it.isDeleted }) {
                        if ((com.folio.reader.model.RevisitType.NOTE to n.id) !in existingRevisits) {
                            revisitRepository.insertRevisitItem(
                                com.folio.reader.model.RevisitItem(
                                    id = "revisit-n-${n.id}",
                                    bookId = n.bookId,
                                    chapterId = n.chapterId ?: "",
                                    type = com.folio.reader.model.RevisitType.NOTE,
                                    sourceId = n.id,
                                    deviceId = n.deviceId
                                )
                            )
                        }
                    }
                }
                settingsRepository.setRaw(flag, "1")
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * One-time backfill of Revisit for manga annotations (§11.5): reader notes become
     * NOTE items, bookmarked chapters become BOOKMARK items. Runs on its own flag so
     * installs that already ran the book backfill still pick up the manga pass.
     */
    fun backfillMangaAnnotationsOnce(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val flag = "manga_annotations_backfill_v1"
            if (runCatching { settingsRepository.getRaw(flag) }.getOrNull() == "1") return@launch
            runCatching {
                val notes = mangaNoteRepository.observeAllNotes().first()
                for (manga in mangaRepository.observeAll().first()) {
                    val existingRevisits = revisitRepository.getRevisitItemsForBook(manga.id)
                        .map { it.type to it.sourceId }
                        .toHashSet()

                    for (n in notes.filter { it.mangaId == manga.id }) {
                        if ((com.folio.reader.model.RevisitType.NOTE to n.id) !in existingRevisits) {
                            revisitRepository.insertRevisitItem(
                                com.folio.reader.model.RevisitItem(
                                    id = "revisit-mn-${n.id}",
                                    bookId = manga.id,
                                    chapterId = n.chapterId,
                                    type = com.folio.reader.model.RevisitType.NOTE,
                                    sourceId = n.id,
                                    deviceId = deviceId
                                )
                            )
                        }
                    }

                    for (c in mangaChapterRepository.getChapters(manga.id).filter { it.bookmarked }) {
                        if ((com.folio.reader.model.RevisitType.BOOKMARK to c.id) !in existingRevisits) {
                            revisitRepository.insertRevisitItem(
                                com.folio.reader.model.RevisitItem(
                                    id = "revisit-mb-${c.id}",
                                    bookId = manga.id,
                                    chapterId = c.id,
                                    type = com.folio.reader.model.RevisitType.BOOKMARK,
                                    sourceId = c.id,
                                    deviceId = deviceId
                                )
                            )
                        }
                    }
                }
                settingsRepository.setRaw(flag, "1")
            }.onFailure { it.printStackTrace() }
        }
    }

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
        // §11.3: align the manga update worker with the stored interval (0 = off cancels it).
        appScope.launch {
            val hours = runCatching { settingsRepository.getGlobalSettings().mangaUpdateIntervalHours }
                .getOrDefault(0)
            com.folio.reader.work.MangaUpdateScheduler.sync(app, hours)
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
     * Compose-observable mirror of [cachedSyncEngine]. Updated every time the
     * cache is written so `remember(syncEngineState.value)` recomputes and the
     * UI picks up a rebuilt engine without an app restart.
     */
    val syncEngineState = mutableStateOf<SyncEngine?>(null)

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
        // Keep a dataSync foreground service alive exactly while the download queue has
        // pending work, so in-flight chapters survive the app being closed/frozen. The queue
        // flow is revision-driven (emits only on change), so this is one cheap collector —
        // no polling. Start/stop is decided here; the service only reports progress and
        // retires itself when the queue drains. The `running` flag means we call
        // start/stop only on a transition, not on every per-page emission.
        graphScope.launch {
            var running = false
            mangaDownloadRepository.observeQueue().collect { queue ->
                val pending = queue.any {
                    it.status == com.folio.reader.manga.MangaDownloadStatus.QUEUED ||
                        it.status == com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING
                }
                if (pending != running) {
                    running = pending
                    com.folio.reader.downloads.MangaDownloadService.sync(app, pending)
                }
            }
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
                cachedSyncEngine ?: createSyncEngine()?.also {
                    cachedSyncEngine = it
                    syncEngineState.value = it
                }
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
        syncEngineState.value = null
        old?.stop()
        appScope.launch(Dispatchers.IO) {
            cachedGlobalSettings =
                runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
            cachedSyncEngine = null
            startSync(appScope)
            // Reflect the final state on the observable so Compose picks up
            // the new engine (or null if credentials were cleared).
            syncEngineState.value = cachedSyncEngine
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
        // ── CRASH REPORTING (§8.4 FOLIO_IMPLEMENTATION_SPEC) ───────────────────
        // Opt-in crash reporting with visible privacy notice; reports contain stack trace + build version.
        runCatching {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            SentryAndroid.init(this) { options ->
                options.dsn = "https://159f3530b7a14c9b2bae0d6e20c31ee1@o600280.ingest.us.sentry.io/6033629"
                // Disable automatic session tracking to avoid false positives for background launches
                options.isEnableAutoSessionTracking = false
                // Capture native crashes (zstd, JNI issues)
                options.isEnableNdk = true
                // Capture ANRs (property renamed isAnrEnabled in Sentry 7.x)
                options.isAnrEnabled = true
                // Release version for grouping reports by build
                options.release = "com.folio.reader@$versionName"
                // Only capture 1% of sessions for now (we'll enable more after validation)
                options.sampleRate = 0.01
            }
        }.onFailure { e ->
            e.printStackTrace() // Don't block startup on Sentry init failure
        }
        
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
