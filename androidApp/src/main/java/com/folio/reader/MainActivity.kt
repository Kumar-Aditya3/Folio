package com.folio.reader

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.folio.reader.model.Book
import com.folio.reader.export.RestoreMode
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var importStatus by mutableStateOf("")
    private var refreshTick by mutableIntStateOf(0)

    override fun onDestroy() {
        super.onDestroy()
        (application as? FolioApplication)?.graph?.shutdown()
    }

    /**
     * The reader WebView already declines ActionMode, but OEM skins can raise the
     * selection toolbar (Copy / Share / Select all) at the window level, where it
     * lands on top of Folio's own Highlight button. Stripping the menu leaves the
     * selection and its handles working with no competing toolbar.
     */
    override fun onActionModeStarted(mode: android.view.ActionMode) {
        super.onActionModeStarted(mode)
        runCatching { mode.menu?.clear() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as FolioApplication).graph
        graph.startSync(appScope)

        setContent {
            // Navigation back stack: forward navigation pushes a screen onto the
            // stack and back pops to wherever the user came from
            // (e.g. Reader -> BookDetail -> Library) instead of always jumping
            // straight to the library root.
            val navStack = remember { mutableStateListOf<Screen>(Screen.Library) }
            val pushScreen: (Screen) -> Unit = { target ->
                if (navStack.last() != target) navStack.add(target)
            }
            val popScreen: () -> Unit = {
                if (navStack.size > 1) navStack.removeAt(navStack.size - 1)
            }
            var globalSettings by remember { mutableStateOf(com.folio.reader.settings.ReaderSettings()) }

            // Top-level sync state for library screen
            val librarySyncState by remember(graph.syncEngine) {
                graph.syncEngine?.syncState ?: kotlinx.coroutines.flow.flowOf(null)
            }.collectAsState(initial = null)

            LaunchedEffect(Unit) {
                runCatching { globalSettings = graph.settingsRepository.getGlobalSettings() }
            }

            // Gesture/system back pops the previous screen off the stack; at the
            // library root no handler is enabled so back finishes the activity
            // (standard Android exit behavior).
            BackHandler(enabled = navStack.size > 1) { popScreen() }

            // Auto-clear import status after 3 seconds
            LaunchedEffect(importStatus) {
                if (importStatus.isNotEmpty() && !importStatus.endsWith("...")) {
                    kotlinx.coroutines.delay(3000)
                    importStatus = ""
                }
            }

            val pickEpubs = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris ->
                importEpubUris(uris)
            }

            val pickFont = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    appScope.launch(Dispatchers.IO) {
                        val displayName = runCatching {
                            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { c ->
                                    if (c.moveToFirst()) c.getString(0) else null
                                }
                        }.getOrNull() ?: "imported_font.ttf"
                        val tempFile = File(cacheDir, displayName)
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            graph.fontManager.run {
                                runCatching { importFont(tempFile, tempFile.nameWithoutExtension) }
                                    .onSuccess { font ->
                                        if (font != null) {
                                            runCatching {
                                                val settings = graph.settingsRepository.getGlobalSettings()
                                                graph.settingsRepository.saveGlobalSettings(
                                                    settings.copy(customFonts = settings.customFonts + font)
                                                )
                                            }.onFailure { e ->
                                                appScope.launch(Dispatchers.Main) {
                                                    importStatus = "Font save failed: ${e.message}"
                                                }
                                                return@onSuccess
                                            }
                                            appScope.launch(Dispatchers.Main) {
                                                globalSettings = globalSettings.copy(
                                                    customFonts = globalSettings.customFonts + font
                                                )
                                                importStatus = "Font imported: ${font.name}"
                                            }
                                        } else {
                                            appScope.launch(Dispatchers.Main) {
                                                importStatus = "Failed: unsupported font file"
                                            }
                                        }
                                    }
                                    .onFailure { e ->
                                        appScope.launch(Dispatchers.Main) {
                                            importStatus = "Font import failed: ${e.message}"
                                        }
                                    }
                            }
                        } catch (e: Exception) {
                            appScope.launch(Dispatchers.Main) { importStatus = "Font import failed: ${e.message}" }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }

            val backupExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) {
                    appScope.launch(Dispatchers.IO) {
                        val tempFile = File(cacheDir, "folio-backup-${System.currentTimeMillis()}.json")
                        try {
                            graph.exportManager.createFullBackup(tempFile, graph.deviceId)
                                .onSuccess { backup ->
                                    contentResolver.openOutputStream(uri)?.use { output ->
                                        tempFile.inputStream().use { input -> input.copyTo(output) }
                                    }
                                    appScope.launch(Dispatchers.Main) {
                                        importStatus =
                                            "Backup saved: ${backup.books.size} books, ${backup.highlights.size} highlights"
                                    }
                                }
                                .onFailure { e ->
                                    appScope.launch(Dispatchers.Main) { importStatus = "Backup failed: ${e.message}" }
                                }
                        } catch (e: Exception) {
                            appScope.launch(Dispatchers.Main) { importStatus = "Backup failed: ${e.message}" }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }

            val backupImportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    appScope.launch(Dispatchers.IO) {
                        val tempFile = File(cacheDir, "restore_${System.currentTimeMillis()}.json")
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            graph.exportManager.restoreBackup(tempFile, RestoreMode.MERGE)
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
                        } catch (e: Exception) {
                            appScope.launch(Dispatchers.Main) { importStatus = "Restore failed: ${e.message}" }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }

            var annotationFormat by remember { mutableStateOf("json") }
            val annotationsExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("*/*")
            ) { uri ->
                if (uri != null) {
                    val format = annotationFormat
                    appScope.launch(Dispatchers.IO) {
                        val tempFile = File(cacheDir, "annotations-${System.currentTimeMillis()}.$format")
                        try {
                            when (format) {
                                "markdown" -> graph.exportManager.exportAnnotationsMarkdown(tempFile, null)
                                "csv" -> graph.exportManager.exportAnnotationsCsv(tempFile, null)
                                else -> graph.exportManager.exportAnnotationsJson(tempFile, null)
                            }.onSuccess {
                                contentResolver.openOutputStream(uri)?.use { output ->
                                    tempFile.inputStream().use { input -> input.copyTo(output) }
                                }
                                appScope.launch(Dispatchers.Main) {
                                    importStatus = "Annotations exported to ${uri.lastPathSegment}"
                                }
                            }.onFailure { e ->
                                appScope.launch(Dispatchers.Main) { importStatus = "Export failed: ${e.message}" }
                            }
                        } catch (e: Exception) {
                            appScope.launch(Dispatchers.Main) { importStatus = "Export failed: ${e.message}" }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }

            // The app chrome follows the app's own light/dark choice. A reading theme
            // describes the page and nothing else — feeding themeId in here is what
            // made the two bleed into each other.
            FolioTheme.AppTheme(palette = com.folio.reader.ui.theme.AppPalette.byId(globalSettings.appThemeId)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FolioTheme.colors.background
                ) {
                    androidx.compose.runtime.key(refreshTick) {
                        when (val current = navStack.last()) {
                            is Screen.Library -> LibraryScreen(
                                onBookClick = { book ->
                                    // Open book directly in reader from saved position
                                    pushScreen(Screen.Reader(book, targetSpineIndex = null))
                                },
                                onBookDetailClick = { book ->
                                    pushScreen(Screen.BookDetail(book.id))
                                },
                                onImportClick = { pickEpubs.launch(arrayOf("application/epub+zip")) },
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
                                                graph.bookRepository.deleteBook(id)
                                            } catch (_: Throwable) {
                                            }
                                            try {
                                                graph.platform.fileSystem.deleteBookFiles(id)
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
                                                graph.bookRepository.setBookStatus(
                                                    id,
                                                    status
                                                )
                                            }
                                        }
                                    }
                                },
                                viewModel = remember {
                                    LibraryViewModel(
                                        bookRepository = graph.bookRepository,
                                        collectionRepository = graph.collectionRepository,
                                        seriesRepository = graph.seriesRepository
                                    )
                                },
                                syncState = librarySyncState,
                                onSyncNow = { graph.syncEngine?.triggerSync(immediate = true) }
                            )

                            is Screen.Reader -> ReaderRoute(
                                graph = graph,
                                book = current.book,
                                targetSpineIndex = current.targetSpineIndex,
                                initialSettings = globalSettings,
                                onBackPress = { popScreen() },
                                onSearchClick = { pushScreen(Screen.Search) },
                                onSettingsClick = { pushScreen(Screen.Settings) }
                            )

                            is Screen.Settings -> {
                                SettingsScreen(
                                    settings = globalSettings,
                                    onSettingsChange = { updated ->
                                        val credsChanged = updated.firebaseApiKey != globalSettings.firebaseApiKey ||
                                                updated.firebaseProjectId != globalSettings.firebaseProjectId ||
                                                updated.syncAccountEmail != globalSettings.syncAccountEmail ||
                                                updated.syncAccountPassword != globalSettings.syncAccountPassword
                                        globalSettings = updated
                                        appScope.launch(Dispatchers.IO) {
                                            runCatching { graph.settingsRepository.saveGlobalSettings(updated) }
                                        }
                                        // Rebuild the sync loop so a newly saved/cleared API key takes effect immediately.
                                        if (credsChanged) graph.restartSync(appScope)
                                    },
                                    onBackPress = { popScreen() },
                                    syncState = librarySyncState
                                        ?: com.folio.reader.sync.SyncState(isConfigured = graph.isSyncConfigured),
                                    onSyncNow = { graph.syncEngine?.triggerSync(immediate = true) },
                                    onImportFont = {
                                        pickFont.launch(
                                            arrayOf(
                                                "font/ttf",
                                                "font/otf",
                                                "application/x-font-ttf",
                                                "application/x-font-opentype",
                                                "application/font-woff",
                                                "application/octet-stream"
                                            )
                                        )
                                    },
                                    onExportBackup = { backupExportLauncher.launch("folio-backup.json") },
                                    onImportBackup = {
                                        backupImportLauncher.launch(
                                            arrayOf(
                                                "application/json",
                                                "text/plain",
                                                "application/octet-stream"
                                            )
                                        )
                                    },
                                    onExportAnnotations = { format ->
                                        annotationFormat = format
                                        annotationsExportLauncher.launch("annotations.$format")
                                    }
                                )
                            }

                            is Screen.Statistics -> StatisticsScreen(
                                viewModel = remember {
                                    StatisticsViewModel(
                                        bookRepository = graph.bookRepository,
                                        sessionRepository = graph.sessionRepository
                                    )
                                },
                                onBackPress = { popScreen() },
                                onBookClick = { bookId ->
                                    appScope.launch(Dispatchers.IO) {
                                        graph.bookRepository.getBook(bookId)?.let { book ->
                                            appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(book)) }
                                        }
                                    }
                                }
                            )

                            is Screen.Search -> SearchRoute(
                                graph = graph,
                                onBackPress = { popScreen() },
                                onOpenBook = { book, target -> pushScreen(Screen.Reader(book, target)) }
                            )

                            is Screen.BookDetail -> {
                                val book = remember(current.bookId) { mutableStateOf<Book?>(null) }
                                LaunchedEffect(current.bookId) {
                                    book.value = graph.bookRepository.getBook(current.bookId)
                                }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    book.value?.let { b ->
                                        BookDetailScreen(
                                        viewModel = remember {
                                            BookDetailViewModel(
                                                bookRepository = graph.bookRepository,
                                                sessionRepository = graph.sessionRepository,
                                                bookmarkRepository = graph.bookmarkRepository,
                                                highlightRepository = graph.highlightRepository,
                                                noteRepository = graph.noteRepository,
                                                seriesRepository = graph.seriesRepository,
                                                collectionRepository = graph.collectionRepository,
                                                tagRepository = graph.tagRepository,
                                                uploadEpub = { bookId -> graph.uploadBookToCloud(bookId) }
                                            )
                                        }.also { vm -> LaunchedEffect(b.id) { vm.loadBook(b.id) } },
                                        onBackPress = { popScreen() },
                                        onStartReading = { pushScreen(Screen.Reader(b)) },
                                        onDeleteClick = {
                                            appScope.launch(Dispatchers.IO) {
                                                try {
                                                    graph.bookRepository.deleteBook(b.id)
                                                } catch (_: Throwable) {
                                                }
                                                try {
                                                    graph.platform.fileSystem.deleteBookFiles(b.id)
                                                } catch (_: Throwable) {
                                                }
                                                refreshTick++
                                            }
                                            popScreen()
                                        },
                                        onEditClick = { },
                                        onShareClick = { shareEpub(b.id) },
                                        onTagClick = { pushScreen(Screen.TagManager) },
                                        onSeriesClick = { },
                                            onCollectionClick = { }
                                        )

                                    }
                                }
                            }

                            is Screen.TagManager -> TagManagerScreen(
                                onBack = { popScreen() },
                                onHighlightClick = { _, book ->
                                    appScope.launch(Dispatchers.IO) {
                                        graph.bookRepository.getBook(book.id)?.let { b ->
                                            appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(b)) }
                                        }
                                    }
                                },
                                onBookClick = { book -> pushScreen(Screen.BookDetail(book.id)) },
                                viewModel = remember {
                                    TagManagerViewModel(
                                        getAllTags = { graph.tagRepository.getAllTags().first() },
                                        insertTag = { graph.tagRepository.insertTag(it) },
                                        updateTag = { graph.tagRepository.updateTag(it) },
                                        deleteTag = { graph.tagRepository.deleteTag(it) },
                                        getTagsForBook = { graph.tagRepository.getTagsForBook(it) },
                                        getTagsForHighlight = { graph.tagRepository.getTagsForHighlight(it) },
                                        getBooksForTag = { graph.tagRepository.getBooksForTag(it) },
                                        getHighlightsForTag = { graph.tagRepository.getHighlightsForTag(it) },
                                        getBook = { graph.bookRepository.getBook(it) }
                                    )
                                }
                            )

                            is Screen.QuoteBrowser -> QuoteBrowserScreen(
                                onBack = { popScreen() },
                                onQuoteClick = { item ->
                                    appScope.launch(Dispatchers.IO) {
                                        graph.bookRepository.getBook(item.book.id)?.let { b ->
                                            appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(b)) }
                                        }
                                    }
                                },
                                viewModel = remember {
                                    QuoteBrowserViewModel(
                                        getAllQuotes = { graph.quoteRepository.getAllQuotes() },
                                        getBook = { graph.bookRepository.getBook(it) },
                                        getChaptersForBook = { graph.bookRepository.getChaptersForBook(it) },
                                        getHighlight = { graph.highlightRepository.getHighlight(it) },
                                        getNote = { graph.noteRepository.getNote(it) },
                                        getTagsForHighlight = { graph.tagRepository.getTagsForHighlight(it) },
                                        getAllBooks = { graph.bookRepository.getAllBooks() },
                                        getAllTags = { graph.tagRepository.getAllTags().first() }
                                    )
                                }
                            )

                            is Screen.RevisitItems -> RevisitItemsScreen(
                                onBack = { popScreen() },
                                onItemClick = { item ->
                                    appScope.launch(Dispatchers.IO) {
                                        graph.bookRepository.getBook(item.book.id)?.let { b ->
                                            appScope.launch(Dispatchers.Main) { pushScreen(Screen.Reader(b)) }
                                        }
                                    }
                                },
                                viewModel = remember {
                                    RevisitItemsViewModel(
                                        getUnresolvedRevisitItems = { graph.revisitRepository.getUnresolvedRevisitItems() },
                                        resolveRevisitItem = { graph.revisitRepository.resolveRevisitItem(it) },
                                        getBook = { graph.bookRepository.getBook(it) },
                                        getChaptersForBook = { graph.bookRepository.getChaptersForBook(it) },
                                        getHighlight = { graph.highlightRepository.getHighlight(it) },
                                        getBookmark = { graph.bookmarkRepository.getBookmark(it) },
                                        getNote = { graph.noteRepository.getNote(it) }
                                    )
                                }
                            )
                        }
                    }

                    if (importStatus.isNotBlank()) {
                        Text(
                            text = importStatus,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 96.dp),
                            color = FolioTheme.colors.onSurfaceVariant,
                            style = FolioTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // Avoid importing the same launch intent again after an activity recreation.
        if (savedInstanceState == null) handleEpubIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            setIntent(intent)
            handleEpubIntent(intent)
        }

    /** Imports EPUB content supplied by either the system document picker or another app. */
    private fun importEpubUris(uris: List<Uri>) {
            if (uris.isEmpty()) return

            val graph = (application as FolioApplication).graph
            appScope.launch(Dispatchers.IO) {
                var imported = 0
                for ((index, uri) in uris.withIndex()) {
                    withContext(Dispatchers.Main) {
                        importStatus = "Importing ${index + 1}/${uris.size}..."
                    }

                    val tempFile = File(cacheDir, "import_${UUID.randomUUID()}.epub")
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalArgumentException("Unable to open shared EPUB")

                        graph.bookImporter.importEpub(tempFile.absolutePath)
                            .onSuccess { imported++ }
                            .onFailure { failure ->
                                withContext(Dispatchers.Main) {
                                    importStatus = "Failed: ${failure.message ?: "unknown error"}"
                                }
                            }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            importStatus = "Failed: ${e.message ?: "unable to import EPUB"}"
                        }
                    } finally {
                        tempFile.delete()
                    }
                }

                withContext(Dispatchers.Main) {
                    importStatus = when {
                        imported == 1 -> "Imported 1 book"
                        imported > 1 -> "Imported $imported of ${uris.size} books"
                        importStatus.endsWith("...") -> "Import failed"
                        else -> importStatus
                    }
                    refreshTick++
                }
            }
        }

    private fun handleEpubIntent(intent: Intent) {
            val uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                ) ?: intent.clipData?.getItemAt(0)?.uri
                else -> null
            }
            uri?.let { importEpubUris(listOf(it)) }
        }

    /** Shares the imported EPUB using the app's existing FileProvider grant. */
    fun shareEpub(bookId: String) {
            val graph = (application as FolioApplication).graph
            val epub = File(graph.platform.fileSystem.getBookEpubPath(bookId))
            if (!epub.isFile) {
                importStatus = "EPUB file is unavailable"
                return
            }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", epub)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("EPUB", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share EPUB"))
        }

    @Composable
    private fun ReaderRoute(
        graph: AppGraph,
        book: Book,
        targetSpineIndex: Int? = null,
        initialSettings: com.folio.reader.settings.ReaderSettings,
        onBackPress: () -> Unit,
        onSearchClick: () -> Unit,
        onSettingsClick: () -> Unit
    ) {
        val viewModel = remember {
            ReaderViewModel(
                bookRepository = graph.bookRepository,
                positionRepository = graph.positionRepository,
                sessionRepository = graph.sessionRepository,
                bookmarkRepository = graph.bookmarkRepository,
                highlightRepository = graph.highlightRepository,
                noteRepository = graph.noteRepository,
                settingsRepository = graph.settingsRepository,
                chapterContentProvider = { bookId, href ->
                    graph.contentProvider.getHtml(bookId, href)
                },
                syncEngine = graph.syncEngine
            )
        }

        val chapters by viewModel.chapters.collectAsState(initial = emptyList())
        val chapterIndex by viewModel.currentChapterIndex.collectAsState(initial = 0)
        val html by viewModel.chapterHtml.collectAsState(initial = "")
        val loadingContent by viewModel.isLoadingContent.collectAsState(initial = true)
        val position by viewModel.position.collectAsState(initial = null)
        val settings by viewModel.effectiveSettings.collectAsState(initial = initialSettings)
        val bookmarks by viewModel.bookmarks.collectAsState(initial = emptyList())
        val highlights by viewModel.highlights.collectAsState(initial = emptyList())
        val notes by viewModel.notes.collectAsState(initial = emptyList())
        val showControls by viewModel.showControls.collectAsState(initial = true)
        val showToc by viewModel.showToc.collectAsState(initial = false)
        val showAnnotations by viewModel.showAnnotations.collectAsState(initial = false)
        val loadError by viewModel.loadError.collectAsState(initial = null)
        val syncState: com.folio.reader.sync.SyncState? by remember(graph.syncEngine) {
            graph.syncEngine?.syncState ?: kotlinx.coroutines.flow.flowOf(null)
        }.collectAsState(initial = null)

        LaunchedEffect(Unit) {
            // Self-heal generic chapter titles (imports before the title fix)
            runCatching {
                val existing = graph.bookRepository.getChaptersForBook(book.id)
                if (existing.size > 2 && existing.distinctBy { it.title }.size == 1) {
                    val parsed = graph.epubParser.parseEpub(graph.platform.fileSystem.getBookEpubPath(book.id))
                    val fixed = parsed.chapters.map { it.copy(bookId = book.id) }
                    if (fixed.size == existing.size && fixed.distinctBy { it.title }.size > 1) {
                        graph.bookRepository.insertChapters(book.id, fixed)
                    }
                }
            }
            viewModel.openBook(
                book.id,
                graph.deviceId,
                initialSettings,
                startChapterOverride = targetSpineIndex?.takeIf { it >= 0 })
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
            onBackPress = { viewModel.closeBook { onBackPress() } },
            onSearchClick = { viewModel.closeBook { onSearchClick() } },
            onBookmarkClick = { viewModel.toggleBookmark() },
            onSettingsClick = { viewModel.closeBook { onSettingsClick() } },
            onSettingsChange = { updated -> viewModel.updateSettings(updated) },
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
            onChapterEnd = { viewModel.onChapterEnd() },
            onChapterStart = { viewModel.onChapterStart() },
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
            onResolveImage = { chapterHref, src -> graph.contentProvider.resolveImage(book.id, chapterHref, src) },
            onResolveResource = { chapterHref, src -> graph.contentProvider.resolveResource(book.id, chapterHref, src) },
            syncState = syncState
        )
    }

    @Composable
    private fun SearchRoute(
        graph: AppGraph,
        onBackPress: () -> Unit,
        onOpenBook: (Book, Int?) -> Unit
    ) {
        val books by remember { graph.bookRepository.getAllBooks() }.collectAsState(initial = emptyList())
        SearchScreen(
            books = books,
            searchRepository = graph.searchRepository,
            highlightRepository = graph.highlightRepository,
            noteRepository = graph.noteRepository,
            bookmarkRepository = graph.bookmarkRepository,
            quoteRepository = graph.quoteRepository,
            onBackPress = onBackPress,
            onResultClick = { hit ->
                val target = hit.spineIndex.takeIf { it >= 0 }
                books.firstOrNull { it.id == hit.book.id }?.let { onOpenBook(it, target) }
            }
        )
    }
}
