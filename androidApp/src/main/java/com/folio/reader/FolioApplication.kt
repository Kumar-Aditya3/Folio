package com.folio.reader

import android.app.Application
import io.sentry.android.core.SentryAndroid
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcDeviceRepository
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.database.ThumbnailDocumentRepository
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
import com.folio.reader.importer.DocumentDeletionService
import com.folio.reader.importer.DocumentFormatDetector
import com.folio.reader.importer.DocumentImporter
import com.folio.reader.importer.IncomingContentCoordinator
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.platform.AndroidPlatform
import com.folio.reader.platform.renderAndroidDocumentThumbnail
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
    val documentRepository = ThumbnailDocumentRepository(
        database,
        JdbcDocumentRepository(database)
    )
    val documentCategoryRepository =
        com.folio.reader.database.JdbcDocumentCategoryRepository(database)
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

    // ── SEMANTIC SEARCH (ML_PLAN) ───────────────────────────────────────────
    // The model is downloaded on demand into the models dir and never bundled; the
    // embedder factory returns null while it is absent, which callers treat as
    // "semantic search unavailable" rather than an error.
    //
    // The model itself is no longer a fixed `val`: it is the reader's choice, stored in
    // settings and resolved by `EmbeddingModelSelection`. That holder owns the embedder
    // factory *and* every service derived from it, so switching models can never leave the
    // indexer on one model and the searcher on another — a mismatch that reads as "search
    // returns nothing" rather than as an error. See that class.
    val chunkRepository = com.folio.reader.database.JdbcChunkRepository(database)
    val genreRepository = com.folio.reader.database.JdbcGenreRepository(database)
    val modelDownloader = com.folio.reader.ml.ModelDownloader(platform.fileSystem, platform.hasher)
    val modelSelection = com.folio.reader.ml.EmbeddingModelSelection(
        settingsRepository = settingsRepository,
        searchRepository = searchRepository,
        modelsDir = platform.fileSystem.getModelsDir(),
        chunkRepository = chunkRepository,
        bookRepository = bookRepository,
        genreRepository = genreRepository,
    )

    /** The model in force. Kept as a convenience so existing call sites keep reading well. */
    val embeddingModel: com.folio.reader.ml.EmbeddingModel
        get() = modelSelection.model.value

    val embedderFactory: com.folio.reader.ml.OnnxEmbedderFactory
        get() = modelSelection.embedderFactory

    val embeddingIndexer: com.folio.reader.ml.EmbeddingIndexer
        get() = modelSelection.indexer

    /** Genre backfill/classification pass, or null when the genre store is unavailable. */
    val genreClassification: com.folio.reader.ml.GenreClassificationService?
        get() = modelSelection.genreClassification

    val semanticSearchRepository: com.folio.reader.ml.SemanticSearchRepository
        get() = modelSelection.semanticSearch

    /** Atlas + Echoes data owner; reuses the searcher's embedder and resident index. */
    val semanticDiscoveryRepository: com.folio.reader.ml.SemanticDiscoveryRepository
        get() = modelSelection.discovery

    // Phase 5 #2. Built from the same embedder factory, so a build with no model downloaded
    // gets a tagger that reports itself unavailable rather than a second download path.
    val autoTaggerService: com.folio.reader.ml.AutoTaggerService
        get() = com.folio.reader.ml.AutoTaggerService(
            tagger = modelSelection.tagger,
            embedderFactory = modelSelection.embedderFactory,
            tagRepository = tagRepository,
            bookRepository = bookRepository,
            searchRepository = searchRepository,
        )
    // Phase 6 (OCR + translation). Android gets the real ML Kit implementation; desktop's
    // actual is a documented no-op. See the decision in OnDeviceTextTools.kt.
    val onDeviceTextTools = com.folio.reader.ml.onDeviceTextTools()

    // ---------- Manga category (Mihon-powered backend) ----------
    val mangaRepository = com.folio.reader.database.JdbcMangaRepository(database) { mangaId ->
        // Cascade delete also removes the manga's downloaded pages from disk.
        mangaDownloadManager.deleteMangaDownloads(mangaId)
    }
    val mangaChapterRepository = com.folio.reader.database.JdbcMangaChapterRepository(database)
    val mangaCategoryRepository = com.folio.reader.database.JdbcMangaCategoryRepository(database)
    val mangaHistoryRepository = com.folio.reader.database.JdbcMangaHistoryRepository(database)
    val mangaDownloadRepository = com.folio.reader.database.JdbcMangaDownloadRepository(database)
    val mangaNoteRepository = com.folio.reader.database.JdbcMangaNoteRepository(database)
    // Built lazily off the main thread (first touched by runStartupTasks on graphScope): the Mihon
    // backend eagerly constructs two OkHttpClients + a disk cache, the extension manager and a
    // synchronous Injekt registration, none of which the reader/library first frame needs. Keeping
    // them `by lazy` moves that class-load + init burst off the cold-start critical path.
    val mangaBackend by lazy {
        com.folio.reader.manga.AndroidMangaBackend(
            context = app,
            settings = settingsRepository,
            fileSystem = platform.fileSystem,
        )
    }
    val mangaDownloadManager by lazy {
        com.folio.reader.manga.MangaDownloadManager(
            backend = mangaBackend,
            downloadsRepo = mangaDownloadRepository,
            chapterRepo = mangaChapterRepository,
            initialStorage = com.folio.reader.manga.FileDownloadStorage(platform.fileSystem.mangaDownloadsDir),
        )
    }
    val mangaUpdateRepository by lazy {
        com.folio.reader.database.JdbcMangaUpdateRepository(
            db = database,
            mangaRepository = mangaRepository,
            chapterRepository = mangaChapterRepository,
            backend = mangaBackend,
        )
    }

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
     * One library scan pass over the configured scope. The folder scope walks its
     * granted SAF tree; the device scope walks the storage roots directly, which
     * requires All Files Access on Android 11+ (or the legacy storage permission
     * below it) — without it the scan is a no-op rather than a partial miss.
     * Returns null when scanning is off or its access is missing.
     */
    suspend fun runLibraryScan(): com.folio.reader.importer.LibraryScanSummary? {
        val keys = com.folio.reader.importer.LibraryScanKeys
        val scope = com.folio.reader.importer.LibraryScanScope.fromRaw(
            runCatching { settingsRepository.getRaw(keys.SCOPE) }.getOrNull()
        )
        if (scope == com.folio.reader.importer.LibraryScanScope.OFF) return null
        val summary = when (scope) {
            com.folio.reader.importer.LibraryScanScope.DEVICE -> {
                if (!com.folio.reader.scan.AndroidLibraryScanner.hasDeviceAccess(app)) return null
                com.folio.reader.scan.AndroidLibraryScanner.scanStorage(app, libraryScanCoordinator)
            }

            com.folio.reader.importer.LibraryScanScope.FOLDER -> {
                val raw = runCatching { settingsRepository.getRaw(keys.FOLDER) }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: return null
                val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return null
                val granted = app.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
                // A revoked folder grant is dropped so the settings screen asks again.
                if (!granted) {
                    runCatching { settingsRepository.setRaw(keys.FOLDER, "") }
                    return null
                }
                com.folio.reader.scan.AndroidLibraryScanner.scan(app, uri, libraryScanCoordinator)
            }

            com.folio.reader.importer.LibraryScanScope.OFF -> return null
        }
        runCatching { settingsRepository.setRaw(keys.LAST, summary.describe()) }
        return summary
    }

    /**
     * The "scan on app start" setting: one quiet pass off the main thread. New
     * files simply appear in the library; failures are swallowed so a bad scan
     * can never affect startup.
     */
    fun scanOnStartIfEnabled(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val enabled = runCatching {
                settingsRepository.getRaw(com.folio.reader.importer.LibraryScanKeys.ON_START)
            }.getOrNull() == "1"
            if (enabled) runCatching { runLibraryScan() }
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
     * One-time repair of chapter text indexed before entities were decoded.
     *
     * `SearchIndexer.extractPlainText` used to strip tags and leave character references
     * alone, so `It&#8217;s` went into FTS5 — and therefore into every snippet and every
     * search result — as the literal nine characters `&#8217;`. Decoding at import fixes
     * books imported from now on, but it cannot reach text that is already stored: the
     * snippet is read straight out of the FTS5 index, so the only way to clean an existing
     * library is to rewrite the rows.
     *
     * That is what this does, and the reason it is a rewrite rather than a display-time
     * decode: the *index* holds `&#8217;` as its own token, so a search for `it's` cannot
     * match the stored text no matter what the UI does. Fixing it in the reader would have
     * hidden the symptom and left search still broken for exactly the words the reader was
     * most likely to type.
     *
     * Reuses [SearchIndexer.extractPlainText] rather than its own decoder so the repair and
     * the import path cannot drift — running the current extractor over text it already
     * produced is idempotent, which is what makes a retry after a mid-way failure safe.
     * The raw settings flag keeps it to one successful pass per install.
     */
    fun repairIndexEntitiesOnce(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val flag = "search_index_entities_v1"
            if (runCatching { settingsRepository.getRaw(flag) }.getOrNull() == "1") return@launch
            runCatching {
                bookRepository.getAllBooks().first().forEach { book ->
                    val entries = searchRepository.getChapterTexts(book.id)
                    if (entries.isEmpty()) return@forEach
                    val repaired = entries.map { entry ->
                        entry.copy(content = SearchIndexer.extractPlainText(entry.content))
                    }
                    // Rewrite wholesale rather than diffing: `indexChaptersBulk` is one
                    // transaction per book, and for text without a reference in it the
                    // "repaired" value is byte-identical to the stored one, so an untouched
                    // book costs a single no-op write instead of a per-chapter comparison.
                    searchRepository.indexChaptersBulk(book.id, repaired)
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
        hashUtil = platform.hasher,
        // Imported books land on a real collection shelf (Main) right away.
        collectionRepository = collectionRepository,
        // No inline embedding on Android. `indexChapters` runs the ONNX model over every chapter
        // synchronously before `importEpub` returns, and on Arctic that is the heaviest workload in
        // the app — it made a single import take minutes and held back the post-import shelf prompt
        // until it finished, reading as a hang. Android already has `EmbeddingBackfillWorker`
        // (scheduled at startup and kicked right after an import in MainActivity) whose whole job is
        // to embed chapters missing vectors, so the index is built in the background instead. The
        // desktop app keeps its inline indexer because it has no background worker.
        embeddingIndexer = null,
        // Record parsed subjects at import (cheap, no model) so the metadata-first genre path works.
        // Classification itself runs in EmbeddingBackfillWorker, after the chapters are embedded.
        genreRepository = genreRepository,
    )
    val documentImporter = DocumentImporter(
        platform,
        documentRepository,
        documentCategoryRepository,
        ::renderAndroidDocumentThumbnail
    )
    val incomingContentCoordinator = IncomingContentCoordinator(
        DocumentFormatDetector(),
        bookImporter,
        documentImporter
    )
    val libraryScanCoordinator = com.folio.reader.importer.LibraryScanCoordinator(incomingContentCoordinator)
    val documentDeletionService =
        DocumentDeletionService(documentRepository, platform)

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
        // android.util.Log, never println: stdout is not wired to logcat on a release
        // build, so a failed seed here — the exact failure that leaves a shelf blank
        // under a selected category — was completely silent on device.
        appScope.launch {
            runCatching { mangaCategoryRepository.ensureSeeded() }
                .onFailure { android.util.Log.e("FolioSeed", "Manga category seed failed", it) }
        }
        // Same repair for book collections: seed Main and give every
        // collection-less book its shelf, so the rail never hides a book.
        appScope.launch {
            runCatching { collectionRepository.ensureSeeded() }
                .onFailure { android.util.Log.e("FolioSeed", "Book collection seed failed", it) }
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

    /**
     * Guards [runStartupTasks] so process-level startup runs once per process, not once per
     * Activity relaunch. Reset naturally on process death because the whole graph is rebuilt.
     */
    private val startupTasksStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        graphScope.launch {
            cachedGlobalSettings =
                runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        }
        // Resolve the embedding model the reader chose, off-main for the same reason as the
        // settings snapshot above: reading the settings row is a database round trip, and
        // parking the UI thread on it during graph construction is an ANR. Until this
        // completes the selection holds the catalog default, which is the same model a
        // reader who never chose one gets — so the window is invisible rather than wrong.
        graphScope.launch {
            runCatching { modelSelection.start() }
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

    /**
     * Process-level startup work, run exactly once per process.
     *
     * Previously each of these was fired straight from `MainActivity.onCreate`, so every
     * configuration change (rotation, dark-mode, font-scale, locale) re-launched a full library
     * scan and re-hit the DB for the idempotent backfills. Gating on an [java.util.concurrent.atomic.AtomicBoolean]
     * keyed to the process — not to `savedInstanceState` — also does the right thing after
     * process death, where the Activity is recreated with saved state but the graph (and this
     * flag) is fresh.
     *
     * The idempotent/heavy passes run on [graphScope] so an Activity relaunch cannot cancel a
     * scan mid-flight; only sync starts on the caller's [uiScope], matching its prior behaviour
     * (the sync engine keeps its own scope regardless).
     */
    fun runStartupTasks(uiScope: CoroutineScope) {
        if (!startupTasksStarted.compareAndSet(false, true)) return
        startSync(uiScope)
        applyStoredMangaDownloadsLocation(graphScope)
        backfillAnnotationsOnce(graphScope)
        backfillMangaAnnotationsOnce(graphScope)
        repairIndexEntitiesOnce(graphScope)
        scanOnStartIfEnabled(graphScope)
        // Resume interrupted manga downloads, but construct the (heavy) download manager + backend
        // on graphScope/IO rather than eagerly on the main thread at graph construction.
        graphScope.launch { mangaDownloadManager.start() }
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
        // Same directory holds the interface faces (Fraunces/Manrope); installed here
        // so the first frame is never drawn in the system font.
        com.folio.reader.ui.theme.UiFonts.install(graph.platform.fileSystem.getFontsDir())
        // Network-fetched manga covers persist to disk from here on, so library
        // thumbnails survive cold starts instead of refetching every launch.
        com.folio.reader.ui.manga.MangaCoverDiskCache.directory =
            graph.platform.fileSystem.mangaCoversDir

        // Extract the bundled reader fonts off the main thread.
        //
        // This used to be a `runBlocking` here, on the reasoning that "a local file
        // copy + one settings row" was fast enough. On a cold start it is neither:
        // every `if (!dest.exists())` branch is true, so the block reads every font
        // out of assets, writes each one to disk, then does a settings **read** and a
        // settings **write** — all before the first frame could be drawn, which is
        // what put seconds of empty screen in front of Home.
        //
        // Nothing on the first frame needs a custom face: `UiFonts.load` resolves
        // lazily by file name and falls back to the system family if a file is not
        // there yet, and the reader re-composes once the fonts land. So the work
        // moves to a background scope and the UI starts immediately.
        //
        // `UiFonts.install` above stays on the main thread deliberately — it only
        // records the directory and clears a cache, and the fallback it enables has
        // to be in place before anything asks for a family.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                com.folio.reader.font.BundledFonts.ensureInstalled(
                    graph.platform,
                    graph.settingsRepository
                ) { path ->
                    runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()
                }
            }.onFailure { it.printStackTrace() }
        }

        // ── SEMANTIC INDEX BACKFILL (ML_PLAN Phase 3) ──────────────────────
        // The settings screen promises the reader that "the automatic pass only runs while
        // the device is charging". This is what makes that true. It was never called, so the
        // charging-gated pass did not exist and the only way to index an existing library was
        // the button — which meant a backfill that got interrupted simply stopped.
        //
        // Gated on the model being on disk: scheduling before the reader has opted into the
        // download would just enqueue a worker whose only job is to discover there is nothing
        // to embed with.
        val modelFile = java.io.File(
            graph.platform.fileSystem.getModelsDir(),
            graph.embeddingModel.fileName,
        )
        if (modelFile.isFile) {
            com.folio.reader.work.EmbeddingBackfillScheduler.schedule(this)
        }
    }

    override fun onTerminate() {
        graph.shutdown()
        super.onTerminate()
    }
}
