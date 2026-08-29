package com.folio.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
import com.folio.reader.importer.DuplicateBookException
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.model.Book
import com.folio.reader.model.CloudState
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.sync.NoopStorageSync
import com.folio.reader.sync.RestFirestoreSync
import com.folio.reader.sync.SyncConfig
import com.folio.reader.sync.SyncEngine
import com.folio.reader.ui.library.LibraryScreen
import com.folio.reader.ui.library.LibraryViewModel
import com.folio.reader.ui.reader.ReaderScreen
import com.folio.reader.ui.reader.ReaderViewModel
import com.folio.reader.ui.search.SearchScreen
import com.folio.reader.ui.settings.SettingsScreen
import com.folio.reader.ui.statistics.StatisticsScreen
import com.folio.reader.ui.statistics.StatisticsViewModel
import com.folio.reader.ui.book.BookDetailScreen
import com.folio.reader.ui.book.BookDetailViewModel
import com.folio.reader.ui.tags.TagManagerScreen
import com.folio.reader.ui.tags.TagManagerViewModel
import com.folio.reader.ui.quotes.QuoteBrowserScreen
import com.folio.reader.ui.quotes.QuoteBrowserViewModel
import com.folio.reader.ui.revisit.RevisitItemsScreen
import com.folio.reader.ui.revisit.RevisitItemsViewModel
import com.folio.reader.ui.theme.FolioTheme
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.io.File

private fun loadEnvFile(): Map<String, String> {
    val envFile = java.io.File(".env")
    if (!envFile.exists()) return emptyMap()
    return envFile.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim() else null
        }
        .toMap()
}

/**
 * Firebase project ID baked into the desktop build so cloud sync works out of the
 * box once the user supplies a Web API key. User-entered credentials (Settings >
 * Advanced), the .env file, or FOLIO_FB_PROJECT_ID take precedence.
 */
private const val BUNDLED_FIREBASE_PROJECT_ID = "folio-sync-53b90"

class FolioDesktopAppDependencies(rootOverride: String? = null) {
    val platform = DesktopPlatform(rootOverride?.let { java.io.File(it) })
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
                syncEngine?.triggerSync(immediate = false)
            }
        }
    }

    val deviceId: String by lazy {
        val file = File(platform.fileSystem.getDatabasePath()).resolveSibling("device_id")
        if (file.exists()) {
            file.readText().trim()
        } else {
            val id = "desktop-" + java.util.UUID.randomUUID().toString().take(8)
            file.parentFile?.mkdirs()
            file.writeText(id)
            id
        }
    }

    @Volatile
    private var cachedSyncEngine: SyncEngine? = null

    /**
     * The active sync engine, or null when Firebase credentials are absent.
     * Built lazily from [firebaseCreds] and cached; rebuilt by [restartSync]
     * whenever the user changes credentials in Settings > Advanced so new
     * settings take effect immediately.
     */
    val syncEngine: SyncEngine?
        get() {
            cachedSyncEngine?.let { return it }
            return synchronized(this) {
                cachedSyncEngine ?: createSyncEngine()?.also { cachedSyncEngine = it }
            }
        }

    val isSyncConfigured: Boolean get() = firebaseCreds() != null
    val storageConfigured: Boolean get() = loadEnvFile()["storageBucket"] != null || System.getenv("FOLIO_FB_STORAGE_BUCKET") != null

    private fun firebaseCreds(): Pair<String, String>? {
        // User-entered API key (Settings > Advanced) takes priority over env fallback.
        val global = runBlocking { runCatching { settingsRepository.getGlobalSettings() }.getOrNull() }
        val env = loadEnvFile()
        val projectId = global?.firebaseProjectId?.takeIf { it.isNotBlank() }
            ?: env["projectId"]
            ?: System.getenv("FOLIO_FB_PROJECT_ID")
            ?: BUNDLED_FIREBASE_PROJECT_ID
        val apiKey = global?.firebaseApiKey?.takeIf { it.isNotBlank() }
            ?: env["apiKey"]
            ?: System.getenv("FOLIO_FB_API_KEY")
            ?: return null
        return if (projectId.isBlank() || apiKey.isBlank()) null else projectId to apiKey
    }

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

    private fun createSyncEngine(): SyncEngine? {
        val (projectId, apiKey) = firebaseCreds() ?: return null
        val settings = runBlocking { runCatching { settingsRepository.getGlobalSettings() }.getOrNull() }
        val email = settings?.syncAccountEmail?.takeIf { it.isNotBlank() }
        val password = settings?.syncAccountPassword?.takeIf { it.isNotBlank() }
        val firestoreSync = RestFirestoreSync(
            projectId = projectId,
            apiKey = apiKey,
            accountEmail = email,
            accountPassword = password
        )
        // Real file/cover sync when a storage bucket is configured; metadata-only otherwise.
        val bucket = loadEnvFile()["storageBucket"] ?: System.getenv("FOLIO_FB_STORAGE_BUCKET")
        val storageSync: com.folio.reader.sync.StorageSync = if (!bucket.isNullOrBlank()) {
            com.folio.reader.sync.RestFirebaseStorageSync(
                projectId = projectId,
                apiKey = apiKey,
                bucket = bucket,
                accountEmail = email,
                accountPassword = password
            )
        } else {
            NoopStorageSync
        }
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
            firestoreSync = firestoreSync,
            storageSync = storageSync,
            config = SyncConfig(),
            deviceId = deviceId
        )
    }

    fun startSync(appScope: CoroutineScope) {
        syncEngine?.let { engine ->
            appScope.launch { engine.start() }
        }
    }

    /**
     * Uploads a book's EPUB body (and cover) to Firebase Storage, tracking the
     * transfer in [CloudState]. Fails when cloud storage isn't configured or the
     * local EPUB file is missing (metadata-only sync still applies).
     */
    suspend fun uploadBookToCloud(bookId: String): Result<Unit> {
        val storage = (syncEngine?.storageSync as? com.folio.reader.sync.RestFirebaseStorageSync)
            ?: return Result.failure(IllegalStateException("Cloud storage not configured"))
        val uid = storage.uid
        if (uid.isBlank()) return Result.failure(IllegalStateException("Not signed in to cloud storage"))
        val localPath = platform.fileSystem.getBookEpubPath(bookId)
        if (!File(localPath).exists()) {
            return Result.failure(IllegalStateException("EPUB file not found: $localPath"))
        }
        val book = bookRepository.getBook(bookId)
            ?: return Result.failure(IllegalStateException("Book not found: $bookId"))
        try {
            bookRepository.setCloudState(bookId, CloudState.UPLOADING_PROGRESS)
            storage.uploadBook(uid, bookId, localPath)
            File(platform.fileSystem.getBookCoverPath(bookId)).takeIf { it.exists() }?.let { cover ->
                runCatching { storage.uploadCover(uid, bookId, cover.absolutePath) }
            }
            setCloudState(bookId, CloudState.SYNCED)
            return Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            setCloudState(bookId, CloudState.SYNC_ERROR)
            return Result.failure(e)
        }
    }

    private suspend fun setCloudState(bookId: String, state: CloudState) {
        try {
            bookRepository.setCloudState(bookId, state)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Rebuilds the sync engine after the user saves/clears Firebase credentials in
     * Settings > Advanced. Stops any running loop, drops the cached engine so the
     * next access rebuilds from current credentials, then starts fresh. Safe to
     * call from the UI thread.
     */
    fun restartSync(appScope: CoroutineScope) {
        val old = syncEngine
        cachedSyncEngine = null // next access rebuilds from current credentials
        old?.stop()
        startSync(appScope)
    }

    fun shutdown() {
        syncEngine?.syncOnAppClose()
        syncEngine?.stop()
        database.close()
    }
}

private sealed interface Screen {
    data object Library : Screen
    data class Reader(val book: Book, val targetSpineIndex: Int? = null) : Screen
    data object Settings : Screen
    data object Statistics : Screen
    data object Search : Screen
    data class BookDetail(val bookId: String) : Screen
    data object TagManager : Screen
    data object QuoteBrowser : Screen
    data object RevisitItems : Screen
}

private val epubFilter: java.io.FilenameFilter = java.io.FilenameFilter { _, name ->
    name.endsWith(".epub", ignoreCase = true)
}

private fun isComposeSceneClosedException(t: Throwable?): Boolean {
    var curr = t
    while (curr != null) {
        val msg = curr.message
        if (msg != null && (
                    msg.contains("ComposeScene is closed", ignoreCase = true) ||
                            msg.contains("size set after ComposeScene", ignoreCase = true) ||
                            msg.contains("ComposeScene has been closed", ignoreCase = true)
                    )
        ) {
            return true
        }
        curr = curr.cause
    }
    return false
}

fun main(args: Array<String>) {
    val startupEpubs = args
        .map(::File)
        .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        if (isComposeSceneClosedException(throwable)) {
            return@setDefaultUncaughtExceptionHandler
        }
        defaultHandler?.uncaughtException(thread, throwable) ?: throwable.printStackTrace()
    }

    // Verify exact JAR loaded — helps diagnose stale installed package vs. latest build.
    try {
        val codeSource = object {}.javaClass.protectionDomain?.codeSource?.location
        println("Folio startup: codeSource=$codeSource args=${args.joinToString()}")
        val cfg = File("app/Folio.cfg").takeIf { it.isFile } ?: File("Folio.cfg").takeIf { it.isFile }
        if (cfg != null && cfg.isFile) {
            println("Folio.cfg first lines: ${cfg.readLines().take(5).joinToString(" | ")}")
        }
        // Also log shared-desktop JAR hash for verification
        val sharedJar = codeSource?.toString() ?: "unknown"
        (System.getenv("LOCALAPPDATA")?.let { File(it, "Folio/folio.log") } ?: File(System.getProperty("user.home"), ".folio/folio.log")).apply {
            parentFile?.mkdirs()
            appendText("${java.time.Instant.now()} Folio startup codeSource=$sharedJar\n")
        }
    } catch (e: Exception) {
        println("Folio startup verification failed: ${e.message}")
    }

    val deps = FolioDesktopAppDependencies()
    // Extract bundled fonts (Calluna, Comfortaa) and register them in settings
    // before any screen reads them.
    runCatching {
        runBlocking {
            com.folio.reader.font.BundledFonts.ensureInstalled(deps.platform, deps.settingsRepository) { path ->
                runCatching {
                    com.folio.reader.font.BundledFonts::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
                }.getOrNull()
            }
        }
    }.onFailure { it.printStackTrace() }
    // Same directory holds the interface faces (Fraunces/Manrope); installed before
    // the first frame so no screen opens in the system font.
    com.folio.reader.ui.theme.UiFonts.install(deps.platform.fileSystem.getFontsDir())
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    application {
        // Navigation back stack: forward navigation pushes a screen onto the
        // stack and Escape/back pops to wherever the user came from
        // (e.g. Reader -> BookDetail -> Library) instead of always jumping
        // straight to the library root.
        val navStack = remember { androidx.compose.runtime.mutableStateListOf<Screen>(Screen.Library) }
        val pushScreen: (Screen) -> Unit = { target ->
            if (navStack.last() != target) navStack.add(target)
        }
        val popScreen: () -> Unit = {
            if (navStack.size > 1) navStack.removeAt(navStack.size - 1)
        }
        var importStatus by remember { mutableStateOf("") }
        var refreshTick by remember { mutableStateOf(0) }
        var globalSettings by remember { mutableStateOf(com.folio.reader.settings.ReaderSettings()) }
        val windowState = rememberWindowState()

        // Top-level sync state for library screen badge
        val librarySyncState by remember(deps.syncEngine) {
            deps.syncEngine?.syncState ?: kotlinx.coroutines.flow.flowOf(null)
        }.collectAsState(initial = null)

        LaunchedEffect(Unit) {
            runCatching { globalSettings = deps.settingsRepository.getGlobalSettings() }
            deps.startSync(appScope)
        }

        // Auto-clear import/sync status after 3 seconds unless it's an error or an in-progress message.
        LaunchedEffect(importStatus) {
            if (importStatus.isNotEmpty() && !importStatus.contains("Failed", ignoreCase = true) &&
                !importStatus.contains("...", ignoreCase = false)
            ) {
                kotlinx.coroutines.delay(3000)
                importStatus = ""
            }
        }

        fun pickAndImportFiles() {
            java.awt.EventQueue.invokeLater {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose EPUB files", java.awt.FileDialog.LOAD)
                dialog.setMultipleMode(true)
                dialog.filenameFilter = epubFilter
                dialog.isVisible = true
                val files = dialog.files
                if (files != null && files.isNotEmpty()) {
                    appScope.launch(Dispatchers.IO) {
                        var ok = 0
                        for ((index, file) in files.withIndex()) {
                            appScope.launch(Dispatchers.Main) {
                                importStatus = "Importing ${file.name} (${index + 1}/${files.size})"
                            }
                            deps.bookImporter.importEpub(file.absolutePath)
                                .onSuccess { ok++ }
                                .onFailure { failure ->
                                    val msg = when (failure) {
                                        is DuplicateBookException -> "Already in library"
                                        else -> "Failed: ${failure.message ?: file.name}"
                                    }
                                    appScope.launch(Dispatchers.Main) {
                                        importStatus = "${file.name}: $msg"
                                    }
                                }
                        }
                        appScope.launch(Dispatchers.Main) {
                            importStatus = "Imported $ok of ${files.size} file(s)"
                            refreshTick++
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            val file = startupEpubs.firstOrNull() ?: return@LaunchedEffect
            appScope.launch(Dispatchers.IO) {
                deps.bookImporter.importEpub(file.absolutePath)
                    .fold(
                        onSuccess = { book ->
                            appScope.launch(Dispatchers.Main) {
                                refreshTick++
                                pushScreen(Screen.Reader(book))
                            }
                        },
                        onFailure = { error ->
                            val book = (error as? DuplicateBookException)?.existingBook
                            appScope.launch(Dispatchers.Main) {
                                if (book != null) pushScreen(Screen.Reader(book))
                                else importStatus = "Failed to open ${file.name}: ${error.message}"
                            }
                        }
                    )
            }
        }

        fun pickAndImportFont() {
            java.awt.EventQueue.invokeLater {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import Font", java.awt.FileDialog.LOAD)
                dialog.setFilenameFilter { _, name ->
                    name.lowercase().endsWith(".ttf") || name.lowercase().endsWith(".otf")
                }
                dialog.isVisible = true
                val file = dialog.file?.let { File(dialog.directory, it) }
                if (file != null && file.exists()) {
                    appScope.launch(Dispatchers.IO) {
                        runCatching { deps.fontManager.importFont(file, file.nameWithoutExtension) }
                            .onSuccess { font ->
                                if (font != null) {
                                    val settings = deps.settingsRepository.getGlobalSettings()
                                    deps.settingsRepository.saveGlobalSettings(
                                        settings.copy(customFonts = settings.customFonts + font)
                                    )
                                    appScope.launch(Dispatchers.Main) {
                                        globalSettings = settings.copy(customFonts = settings.customFonts + font)
                                        importStatus = "Font imported: ${font.name}"
                                    }
                                }
                            }
                    }
                }
            }
        }

        fun pickBackupDestination() {
            java.awt.EventQueue.invokeLater {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export Backup", java.awt.FileDialog.SAVE)
                dialog.setFile("folio-backup.json")
                dialog.isVisible = true
                val file = dialog.file?.let { File(dialog.directory, it) }
                if (file != null) {
                    appScope.launch(Dispatchers.IO) {
                        deps.exportManager.createFullBackup(file, deps.deviceId)
                            .onSuccess { backup ->
                                appScope.launch(Dispatchers.Main) {
                                    importStatus =
                                        "Backup saved: ${backup.books.size} books, ${backup.highlights.size} highlights"
                                }
                            }
                            .onFailure { e ->
                                appScope.launch(Dispatchers.Main) { importStatus = "Backup failed: ${e.message}" }
                            }
                    }
                }
            }
        }

        fun pickBackupSource() {
            java.awt.EventQueue.invokeLater {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Restore Backup", java.awt.FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".json") }
                dialog.isVisible = true
                val file = dialog.file?.let { File(dialog.directory, it) }
                if (file != null) {
                    appScope.launch(Dispatchers.IO) {
                        deps.exportManager.restoreBackup(file, com.folio.reader.export.RestoreMode.MERGE)
                            .onSuccess { summary ->
                                appScope.launch(Dispatchers.Main) {
                                    importStatus =
                                        "Restored: ${summary.booksRestored} books, ${summary.highlightsRestored} highlights, ${summary.errors.size} errors"
                                    refreshTick++
                                }
                            }
                            .onFailure { e ->
                                appScope.launch(Dispatchers.Main) { importStatus = "Restore failed: ${e.message}" }
                            }
                    }
                }
            }
        }

        fun exportAnnotations(format: String) {
            appScope.launch(Dispatchers.IO) {
                java.awt.EventQueue.invokeLater {
                    val dialog =
                        java.awt.FileDialog(null as java.awt.Frame?, "Export Annotations", java.awt.FileDialog.SAVE)
                    dialog.setFile("annotations.$format")
                    dialog.isVisible = true
                    val file = dialog.file?.let { File(dialog.directory, it) } ?: return@invokeLater
                    appScope.launch(Dispatchers.IO) {
                        val result = when (format) {
                            "markdown" -> deps.exportManager.exportAnnotationsMarkdown(file, null)
                            "csv" -> deps.exportManager.exportAnnotationsCsv(file, null)
                            else -> deps.exportManager.exportAnnotationsJson(file, null)
                        }
                        result.onSuccess {
                            appScope.launch(Dispatchers.Main) { importStatus = "Annotations exported to ${file.name}" }
                        }.onFailure { e ->
                            appScope.launch(Dispatchers.Main) { importStatus = "Export failed: ${e.message}" }
                        }
                    }
                }
            }
        }

        Window(
            onCloseRequest = {
                runCatching { deps.shutdown() }
                exitApplication()
            },
            onPreviewKeyEvent = { e ->
                // Escape = back: pops to the previous screen in the navigation stack
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && e.key == Key.Escape) {
                    if (navStack.size > 1) {
                        popScreen()
                        true
                    } else false
                } else false
            },
            title = "Folio",
            state = windowState
        ) {
            // The app chrome follows the app's own light/dark choice. A reading theme
            // describes the page and nothing else — feeding themeId in here is what
            // made the two bleed into each other.
            FolioTheme.AppTheme(palette = com.folio.reader.ui.theme.AppPalette.byId(globalSettings.appThemeId)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FolioTheme.colors.background
                ) {
                    // Auto-clear import status after 3 seconds
                    LaunchedEffect(importStatus) {
                        if (importStatus.isNotEmpty()) {
                            kotlinx.coroutines.delay(3000)
                            importStatus = ""
                        }
                    }

                    androidx.compose.runtime.key(refreshTick) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = navStack.last(),
                            transitionSpec = {
                                androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                            },
                            label = "screen"
                        ) { current ->
                            when (current) {
                                is Screen.Library -> {
                                    LibraryScreen(
                                        onBookClick = { book ->
                                            pushScreen(Screen.BookDetail(book.id))
                                        },
                                        onBookDetailClick = { book ->
                                            pushScreen(Screen.BookDetail(book.id))
                                        },
                                        onImportClick = { pickAndImportFiles() },
                                        onSearchClick = { pushScreen(Screen.Search) },
                                        onSettingsClick = { pushScreen(Screen.Settings) },
                                        onStatsClick = { pushScreen(Screen.Statistics) },
                                        onTagManagerClick = { pushScreen(Screen.TagManager) },
                                        onQuoteBrowserClick = { pushScreen(Screen.QuoteBrowser) },
                                        onRevisitClick = { pushScreen(Screen.RevisitItems) },
                                        onDeleteBooks = { ids ->
                                            appScope.launch(Dispatchers.IO) {
                                                ids.forEach { id ->
                                                    try {
                                                        deps.bookRepository.deleteBook(id)
                                                    } catch (_: Throwable) {
                                                    }
                                                    try {
                                                        deps.platform.fileSystem.deleteBookFiles(id)
                                                    } catch (_: Throwable) {
                                                    }
                                                }
                                                refreshTick++
                                            }
                                        },
                                        onSetBookStatus = { ids, status ->
                                            appScope.launch(Dispatchers.IO) {
                                                ids.forEach { id ->
                                                    runCatching {
                                                        deps.bookRepository.setBookStatus(
                                                            id,
                                                            status
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        viewModel = remember {
                                            LibraryViewModel(
                                                bookRepository = deps.bookRepository,
                                                collectionRepository = deps.collectionRepository,
                                                seriesRepository = deps.seriesRepository
                                            )
                                        },
                                        syncState = librarySyncState,
                                        onSyncNow = { deps.syncEngine?.triggerSync(immediate = true) }
                                    )
                                }

                                is Screen.Reader -> ReaderRoute(
                                    deps = deps,
                                    book = current.book,
                                    targetSpineIndex = current.targetSpineIndex,
                                    initialSettings = globalSettings,
                                    onBackPress = { popScreen() },
                                    onSearchClick = { pushScreen(Screen.Search) },
                                    onSettingsClick = { pushScreen(Screen.Settings) },
                                    onSettingsChanged = { globalSettings = it }
                                )

                                is Screen.Settings -> {
                                    SettingsScreen(
                                        settings = globalSettings,
                                        onSettingsChange = { updated ->
                                            val credsChanged =
                                                updated.firebaseApiKey != globalSettings.firebaseApiKey ||
                                                        updated.firebaseProjectId != globalSettings.firebaseProjectId ||
                                                        updated.syncAccountEmail != globalSettings.syncAccountEmail ||
                                                        updated.syncAccountPassword != globalSettings.syncAccountPassword
                                            globalSettings = updated
                                            appScope.launch(Dispatchers.IO) {
                                                runCatching { deps.settingsRepository.saveGlobalSettings(updated) }
                                            }
                                            // Rebuild the sync loop so a newly saved/cleared API key takes effect immediately.
                                            if (credsChanged) deps.restartSync(appScope)
                                        },
                                        onBackPress = { popScreen() },
                                        syncState = librarySyncState
                                            ?: com.folio.reader.sync.SyncState(isConfigured = deps.isSyncConfigured),
                                        onSyncNow = { deps.syncEngine?.triggerSync(immediate = true) },
                                        onImportFont = { pickAndImportFont() },
                                        onExportBackup = { pickBackupDestination() },
                                        onImportBackup = { pickBackupSource() },
                                        onExportAnnotations = { format -> exportAnnotations(format) }
                                    )
                                }

                                is Screen.Statistics -> StatisticsScreen(
                                    viewModel = remember {
                                        StatisticsViewModel(
                                            bookRepository = deps.bookRepository,
                                            sessionRepository = deps.sessionRepository
                                        )
                                    },
                                    onBackPress = { popScreen() },
                                    onBookClick = { bookId ->
                                        appScope.launch(Dispatchers.IO) {
                                            deps.bookRepository.getBook(bookId)?.let { book ->
                                                appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(book)) }
                                            }
                                        }
                                    }
                                )

                                is Screen.Search -> SearchRoute(
                                    deps = deps,
                                    onBackPress = { popScreen() },
                                    onOpenBookAt = { hit ->
                                        appScope.launch(Dispatchers.IO) {
                                            deps.bookRepository.getBook(hit.book.id)?.let { book ->
                                                val target = hit.spineIndex.takeIf { it >= 0 }
                                                appScope.launch(Dispatchers.Main) {
                                                    pushScreen(
                                                        Screen.Reader(
                                                            book,
                                                            target
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )

                                is Screen.BookDetail -> {
                                    val book = remember(current.bookId) { mutableStateOf<Book?>(null) }
                                    LaunchedEffect(current.bookId) {
                                        book.value = deps.bookRepository.getBook(current.bookId)
                                    }
                                    book.value?.let { b ->
                                        BookDetailScreen(
                                            viewModel = remember {
                                                BookDetailViewModel(
                                                    bookRepository = deps.bookRepository,
                                                    sessionRepository = deps.sessionRepository,
                                                    bookmarkRepository = deps.bookmarkRepository,
                                                    highlightRepository = deps.highlightRepository,
                                                    noteRepository = deps.noteRepository,
                                                    seriesRepository = deps.seriesRepository,
                                                    collectionRepository = deps.collectionRepository,
                                                    tagRepository = deps.tagRepository,
                                                    uploadEpub = { bookId -> deps.uploadBookToCloud(bookId) }
                                                )
                                            }.also { vm -> LaunchedEffect(b.id) { vm.loadBook(b.id) } },
                                            onBackPress = { popScreen() },
                                            onStartReading = { pushScreen(Screen.Reader(b)) },
                                            onDeleteClick = {
                                                appScope.launch(Dispatchers.IO) {
                                                    try {
                                                        deps.bookRepository.deleteBook(b.id)
                                                    } catch (_: Throwable) {
                                                    }
                                                    try {
                                                        deps.platform.fileSystem.deleteBookFiles(b.id)
                                                    } catch (_: Throwable) {
                                                    }
                                                    refreshTick++
                                                }
                                                popScreen()
                                            },
                                            onEditClick = { },
                                            onTagClick = { pushScreen(Screen.TagManager) },
                                            onSeriesClick = { },
                                            onCollectionClick = { }
                                        )
                                    }
                                }

                                is Screen.TagManager -> TagManagerScreen(
                                    onBack = { popScreen() },
                                    onHighlightClick = { _, book ->
                                        appScope.launch(Dispatchers.IO) {
                                            deps.bookRepository.getBook(book.id)?.let { b ->
                                                appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(b)) }
                                            }
                                        }
                                    },
                                    onBookClick = { book ->
                                        pushScreen(Screen.BookDetail(book.id))
                                    },
                                    viewModel = remember {
                                        TagManagerViewModel(
                                            getAllTags = { deps.tagRepository.getAllTags().first() },
                                            insertTag = { deps.tagRepository.insertTag(it) },
                                            updateTag = { deps.tagRepository.updateTag(it) },
                                            deleteTag = { deps.tagRepository.deleteTag(it) },
                                            getTagsForBook = { deps.tagRepository.getTagsForBook(it) },
                                            getTagsForHighlight = { deps.tagRepository.getTagsForHighlight(it) },
                                            getBooksForTag = { deps.tagRepository.getBooksForTag(it) },
                                            getHighlightsForTag = { deps.tagRepository.getHighlightsForTag(it) },
                                            getBook = { deps.bookRepository.getBook(it) }
                                        )
                                    }
                                )

                                is Screen.QuoteBrowser -> QuoteBrowserScreen(
                                    onBack = { popScreen() },
                                    onQuoteClick = { item ->
                                        appScope.launch(Dispatchers.IO) {
                                            deps.bookRepository.getBook(item.book.id)?.let { book ->
                                                appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(book)) }
                                            }
                                        }
                                    },
                                    viewModel = remember {
                                        QuoteBrowserViewModel(
                                            getAllQuotes = { deps.quoteRepository.getAllQuotes() },
                                            getBook = { deps.bookRepository.getBook(it) },
                                            getChaptersForBook = { deps.bookRepository.getChaptersForBook(it) },
                                            getHighlight = { deps.highlightRepository.getHighlight(it) },
                                            getNote = { deps.noteRepository.getNote(it) },
                                            getTagsForHighlight = { deps.tagRepository.getTagsForHighlight(it) },
                                            getAllBooks = { deps.bookRepository.getAllBooks() },
                                            getAllTags = { deps.tagRepository.getAllTags().first() }
                                        )
                                    }
                                )

                                is Screen.RevisitItems -> RevisitItemsScreen(
                                    onBack = { popScreen() },
                                    onItemClick = { item ->
                                        appScope.launch(Dispatchers.IO) {
                                            deps.bookRepository.getBook(item.book.id)?.let { book ->
                                                appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(book)) }
                                            }
                                        }
                                    },
                                    viewModel = remember {
                                        RevisitItemsViewModel(
                                            getUnresolvedRevisitItems = { deps.revisitRepository.getUnresolvedRevisitItems() },
                                            resolveRevisitItem = { deps.revisitRepository.resolveRevisitItem(it) },
                                            getBook = { deps.bookRepository.getBook(it) },
                                            getChaptersForBook = { deps.bookRepository.getChaptersForBook(it) },
                                            getHighlight = { deps.highlightRepository.getHighlight(it) },
                                            getBookmark = { deps.bookmarkRepository.getBookmark(it) },
                                            getNote = { deps.noteRepository.getNote(it) }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (importStatus.isNotBlank()) {
                        androidx.compose.material3.Text(
                            text = importStatus,
                            modifier = Modifier.padding(8.dp),
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderRoute(
    deps: FolioDesktopAppDependencies,
    book: Book,
    targetSpineIndex: Int? = null,
    initialSettings: com.folio.reader.settings.ReaderSettings,
    onBackPress: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSettingsChanged: (com.folio.reader.settings.ReaderSettings) -> Unit = {}
) {
    val viewModel = remember {
        ReaderViewModel(
            bookRepository = deps.bookRepository,
            positionRepository = deps.positionRepository,
            sessionRepository = deps.sessionRepository,
            bookmarkRepository = deps.bookmarkRepository,
            highlightRepository = deps.highlightRepository,
            noteRepository = deps.noteRepository,
            settingsRepository = deps.settingsRepository,
            chapterContentProvider = { bookId, href -> deps.contentProvider.getHtml(bookId, href) },
            syncEngine = deps.syncEngine
        )
    }

    val chapters by viewModel.chapters.collectAsState(initial = emptyList())
    val position by viewModel.position.collectAsState(initial = null)
    val chapterIndex by viewModel.currentChapterIndex.collectAsState(initial = 0)
    val html by viewModel.chapterHtml.collectAsState(initial = "")
    val loadingContent by viewModel.isLoadingContent.collectAsState(initial = true)
    val settings by viewModel.effectiveSettings.collectAsState(initial = initialSettings)
    val bookmarks by viewModel.bookmarks.collectAsState(initial = emptyList())
    val highlights by viewModel.highlights.collectAsState(initial = emptyList())
    val notes by viewModel.notes.collectAsState(initial = emptyList())
    val showControls by viewModel.showControls.collectAsState(initial = true)
    val showToc by viewModel.showToc.collectAsState(initial = false)
    val showAnnotations by viewModel.showAnnotations.collectAsState(initial = false)
    val linkResult by viewModel.linkClickResult.collectAsState(initial = null)
    val loadError by viewModel.loadError.collectAsState(initial = null)
    val syncState: com.folio.reader.sync.SyncState? by remember(deps.syncEngine) {
        deps.syncEngine?.syncState ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(Unit) {
        // Self-heal: books imported before the title-extraction fix stored the book
        // title as EVERY chapter name. Re-derive from the EPUB when detected.
        runCatching {
            val existing = deps.bookRepository.getChaptersForBook(book.id)
            if (existing.size > 2 && existing.distinctBy { it.title }.size == 1) {
                val parsed = deps.epubParser.parseEpub(deps.platform.fileSystem.getBookEpubPath(book.id))
                val fixed = parsed.chapters.map { it.copy(bookId = book.id) }
                if (fixed.size == existing.size && fixed.distinctBy { it.title }.size > 1) {
                    deps.bookRepository.insertChapters(book.id, fixed)
                }
            }
        }
        viewModel.openBook(
            book.id,
            deps.deviceId,
            initialSettings,
            startChapterOverride = targetSpineIndex?.takeIf { it >= 0 })
    }

    LaunchedEffect(linkResult) {
        when (val r = linkResult) {
            is com.folio.reader.ui.render.LinkClickResult.InternalChapter -> viewModel.goToChapter(r.chapterIndex)
            is com.folio.reader.ui.render.LinkClickResult.ExternalUrl -> runCatching {
                java.awt.Desktop.getDesktop().browse(java.net.URI(r.url))
            }

            is com.folio.reader.ui.render.LinkClickResult.InlineContent -> {
                // Fallback: try to find chapter by href ignoring fragment
                val idx = chapters.indexOfFirst { it.href.substringBefore("#") == r.resolvedHref.substringBefore("#") }
                if (idx >= 0) viewModel.goToChapter(idx)
            }

            null -> {}
        }
        if (linkResult != null) viewModel.clearLinkClickResult()
    }

    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose {
            viewModel.closeBook()
        }
    }

    ReaderScreen(
        bookTitle = book.title,
        chapters = chapters,
        currentChapterIndex = chapterIndex,
        chapterHtml = html,
        isLoadingContent = loadingContent,
        loadError = loadError,
        coverPath = book.coverPath,
        position = position,
        settings = settings,
        bookmarks = bookmarks,
        highlights = highlights,
        notes = notes,
        showControls = showControls,
        showToc = showToc,
        showAnnotations = showAnnotations,
        onChapterChange = { viewModel.goToChapter(it) },
        onBackPress = {
            viewModel.closeBook { onBackPress() }
        },
        onSearchClick = {
            viewModel.closeBook { onSearchClick() }
        },
        onBookmarkClick = { viewModel.toggleBookmark() },
        onSettingsClick = {
            viewModel.closeBook { onSettingsClick() }
        },
        onSettingsChange = { updated -> onSettingsChanged(updated); viewModel.updateSettings(updated) },
        onToggleControls = { viewModel.toggleControls() },
        onShowControls = { viewModel.showControlsFn() },
        onToggleToc = { viewModel.toggleToc() },
        onToggleAnnotations = { viewModel.toggleAnnotations() },
        onRemoveBookmark = { viewModel.removeBookmark(it) },
        onRemoveHighlight = { viewModel.removeHighlight(it) },
        onRemoveNote = { viewModel.removeNote(it) },
        onAddNote = { viewModel.addNote(it) },
        onSetHighlightNote = { id, text -> viewModel.setHighlightNote(id, text) },
        onScrollProgress = { fraction -> viewModel.updateScrollProgress(fraction) },
        onHighlightParagraph = { paragraphIndex, snippet ->
            val pos = position
            viewModel.addHighlight(
                startLocator = "/${pos?.spineIndex ?: 0}/$paragraphIndex:0",
                endLocator = "/${pos?.spineIndex ?: 0}/$paragraphIndex:end",
                selectedText = snippet
            )
            viewModel.showControlsFn()
        },
        onRetryChapter = { viewModel.reloadChapter() },
        onLinkClick = { href -> viewModel.handleLinkClick(href) },
        onChapterEnd = { viewModel.onChapterEnd() },
        onChapterStart = { viewModel.onChapterStart() },
        onResolveImage = { chapterHref, src -> deps.contentProvider.resolveImage(book.id, chapterHref, src) },
        onResolveResource = { chapterHref, src -> deps.contentProvider.resolveResource(book.id, chapterHref, src) },
        syncState = syncState
    )
}

@Composable
private fun SearchRoute(
    deps: FolioDesktopAppDependencies,
    onBackPress: () -> Unit,
    onOpenBookAt: (com.folio.reader.ui.search.BookHit) -> Unit
) {
    val books by remember { deps.bookRepository.getAllBooks() }.collectAsState(initial = emptyList())
    SearchScreen(
        books = books,
        searchRepository = deps.searchRepository,
        highlightRepository = deps.highlightRepository,
        noteRepository = deps.noteRepository,
        bookmarkRepository = deps.bookmarkRepository,
        quoteRepository = deps.quoteRepository,
        onBackPress = onBackPress,
        onResultClick = { onOpenBookAt(it) }
    )
}
