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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.folio.reader.nav.FolioNavCallbacks
import com.folio.reader.nav.FolioNavHost
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.nav.FolioNavShell
import com.folio.reader.nav.FolioRoutes
import com.folio.reader.nav.changeMangaDownloadsLocation
import com.folio.reader.nav.handleAnnotationsExport
import com.folio.reader.nav.handleBackupRestore
import com.folio.reader.nav.handleFontImport
import com.folio.reader.nav.handleFullBackupExport
import com.folio.reader.nav.handleMangaBackupExport
import com.folio.reader.nav.handleMangaBackupImport
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FontTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val FONT_MIMES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-opentype",
    "application/font-woff",
    "application/octet-stream"
)

private val MANGA_ARCHIVE_MIMES = arrayOf(
    "application/x-cbz",
    "application/vnd.comicbook+zip",
    "application/zip",
    "application/octet-stream",
    "*/*"
)

class MainActivity : ComponentActivity() {

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var importStatus by mutableStateOf("")
    internal var refreshTick by mutableIntStateOf(0)
    internal lateinit var navModel: FolioNavModelImpl

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
        graph.applyStoredMangaDownloadsLocation(appScope)
        graph.backfillAnnotationsOnce(appScope)

        setContent {
            val navController = rememberNavController()
            val model = remember { FolioNavModelImpl(this@MainActivity) }
            SideEffect {
                navModel = model
                model.navController = navController
            }

            // ── Document pickers (bodies live in nav/FolioNavModelImporters.kt) ──
            val pickEpubs = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris -> importEpubUris(uris) }
            val pickMangaArchives = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris -> importMangaUris(uris) }
            val pickMangaFolder = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) importMangaFolderUri(uri) }
            val pickMangaBackup = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.handleMangaBackupImport(uri) }
            val exportMangaBackup = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri -> if (uri != null) model.handleMangaBackupExport(uri) }
            val pickFont = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.handleFontImport(uri) }
            val backupExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri -> if (uri != null) model.handleFullBackupExport(uri) }
            val backupImportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.handleBackupRestore(uri) }
            val annotationsExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("*/*")
            ) { uri -> if (uri != null) model.handleAnnotationsExport(uri) }
            val pickMangaDlDir = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) changeMangaDownloadsLocation(uri) {
                    model.mangaDownloadsLocation = graph.mangaDownloadManager.storageDescription()
                }
            }

            val callbacks = object : FolioNavCallbacks {
                override fun onImportEpubs() = pickEpubs.launch(arrayOf("application/epub+zip"))
                override fun onImportMangaArchives() = pickMangaArchives.launch(MANGA_ARCHIVE_MIMES)
                override fun onImportMangaFolder() = pickMangaFolder.launch(null)
                override fun onImportMangaChoice() {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import manga")
                        .setItems(arrayOf("Archive files", "Folder")) { _, which ->
                            when (which) {
                                0 -> pickMangaArchives.launch(MANGA_ARCHIVE_MIMES)
                                1 -> pickMangaFolder.launch(null)
                            }
                        }
                        .show()
                }
                override fun onImportMangaBackup() = pickMangaBackup.launch(arrayOf("*/*"))
                override fun onExportMangaBackup() = exportMangaBackup.launch("folio_manga.backup")
                override fun onImportFont() = pickFont.launch(FONT_MIMES)
                override fun onExportBackup() = backupExportLauncher.launch("folio-backup.json")
                override fun onImportBackup() = backupImportLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
                override fun onExportAnnotations(format: String) =
                    annotationsExportLauncher.launch("annotations.$format")
                override fun onPickMangaDownloadsLocation() = pickMangaDlDir.launch(null)
                override fun onShareEpub(bookId: String) = shareEpub(bookId)
            }
            model.callbacks = callbacks

            LaunchedEffect(Unit) {
                runCatching { model.globalSettings = graph.settingsRepository.getGlobalSettings() }
            }

            // Success statuses auto-clear after 3 seconds; in-progress and error
            // messages persist until the next status replaces them.
            LaunchedEffect(importStatus) {
                if (com.folio.reader.ui.components.isTransientStatus(importStatus)) {
                    kotlinx.coroutines.delay(3000)
                    importStatus = ""
                }
            }

            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route.orEmpty()
            // The bar is visible on exactly the 4 top-level routes (§3.3).
            val showBottomBar = currentRoute in FolioRoutes.BAR_ROUTES

            // Back walks back through states instead of exiting: an active bulk
            // selection (manga or books) clears first, then pushed screens pop,
            // an open manga search closes, Manga returns to Books, and only at
            // the Books root does back exit the app.
            BackHandler {
                when {
                    model.mangaLibVM.isSelectionMode.value -> model.mangaLibVM.clearSelection()
                    model.libraryVM.isSelectionMode.value -> model.libraryVM.clearSelection()
                    model.activeMangaDetailVM?.chapterSelectionMode?.value == true ->
                        model.activeMangaDetailVM?.clearChapterSelection()
                    currentRoute == FolioRoutes.MANGA_BROWSE && model.mangaBrowseVM.searchActive.value ->
                        model.mangaBrowseVM.exitSearch()
                    navController.popBackStack() -> Unit
                    model.libraryVM.statsVisible.value -> model.libraryVM.statsVisible.value = false
                    model.mangaSearchActive -> model.mangaSearchActive = false
                    model.libraryMode == LibraryMode.MANGA -> model.libraryMode = LibraryMode.BOOKS
                    else -> finish()
                }
            }

            // The app chrome follows the app's own light/dark choice. A reading theme
            // describes the page and nothing else — feeding themeId in here is what
            // made the two bleed into each other.
            val isReadingScreen =
                currentRoute == FolioRoutes.READER || currentRoute == FolioRoutes.MANGA_READER
            LaunchedEffect(isReadingScreen) {
                // Outside the reader the status bar sits on the theme's dark ink
                // band, so icons are always light there; readers own their bars.
                if (!isReadingScreen) {
                    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                        .isAppearanceLightStatusBars = false
                }
            }

            FolioTheme.AppTheme(
                palette = AppPalette.byId(model.globalSettings.appThemeId),
                fontTheme = FontTheme.byId(model.globalSettings.fontThemeId)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FolioTheme.colors.background
                ) {
                    androidx.compose.runtime.key(refreshTick) {
                        FolioNavShell(
                            navController = navController,
                            showBottomBar = showBottomBar
                        ) {
                            FolioNavHost(
                                navController = navController,
                                navModel = model,
                                callbacks = callbacks
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(com.folio.reader.ui.theme.FolioTokens.space3)
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        com.folio.reader.ui.components.FolioStatusBanner(importStatus)
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
            var restored = 0
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
                        .onSuccess { book ->
                            imported++
                            val adopted = runCatching {
                                graph.syncEngine?.adoptCloudProgressForBook(book.id, book.epubHash) ?: 0
                            }.getOrDefault(0)
                            if (adopted > 0) restored++
                        }
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
                if (restored > 0) importStatus += " • progress restored from cloud"
                refreshTick++
            }
        }
    }

    /** Imports CBZ/ZIP manga archives into the local manga source. */
    private fun importMangaUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val graph = (application as FolioApplication).graph
        appScope.launch(Dispatchers.IO) {
            var imported = 0
            var restored = 0
            for ((index, uri) in uris.withIndex()) {
                withContext(Dispatchers.Main) {
                    importStatus = "Importing manga ${index + 1}/${uris.size}..."
                }
                val displayName = runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: "manga_$index.cbz"
                val tempFile = File(cacheDir, "manga_${UUID.randomUUID()}_$displayName")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalArgumentException("Unable to open archive")

                    val seriesName = graph.mangaBackend.localSource.import(tempFile)
                    val entryId = com.folio.reader.manga.mangaId(com.folio.reader.manga.LOCAL_SOURCE_ID, seriesName)
                    graph.mangaRepository.upsert(
                        com.folio.reader.manga.MangaEntry(
                            id = entryId,
                            sourceId = com.folio.reader.manga.LOCAL_SOURCE_ID,
                            sourceName = "Local manga",
                            url = seriesName,
                            title = seriesName,
                            inLibrary = true,
                            initialized = true,
                        )
                    )
                    val adopted = runCatching {
                        graph.syncEngine?.adoptCloudProgressForManga(entryId) ?: 0
                    }.getOrDefault(0)
                    if (adopted > 0) restored++
                    imported++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        importStatus = "Manga import failed: ${e.message ?: "unknown error"}"
                    }
                } finally {
                    tempFile.delete()
                }
            }

            withContext(Dispatchers.Main) {
                if (imported > 0) {
                    importStatus = if (imported == 1) "Imported 1 manga" else "Imported $imported manga"
                    if (restored > 0) importStatus += " • progress restored from cloud"
                }
                refreshTick++
            }
        }
    }

    /** Imports a folder selected via ACTION_OPEN_DOCUMENT_TREE as one manga collection. */
    private fun importMangaFolderUri(treeUri: Uri) {
        val graph = (application as FolioApplication).graph
        appScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { importStatus = "Reading manga folder..." }
            try {
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    ?: throw IllegalArgumentException("Cannot read selected folder")
                val folderName = docFile.name ?: "manga_folder"
                val resolver = contentResolver
                val archives = docFile.listFiles()
                    .filter { child ->
                        child.isFile && child.name?.substringAfterLast('.', "")?.lowercase()
                            .let { it == "cbz" || it == "zip" }
                    }
                    .mapNotNull { child ->
                        val name = child.name ?: return@mapNotNull null
                        com.folio.reader.manga.LocalMangaSource.PendingArchive(name) {
                            resolver.openInputStream(child.uri)
                                ?: throw java.io.IOException("Cannot open $name")
                        }
                    }
                if (archives.isEmpty()) {
                    withContext(Dispatchers.Main) { importStatus = "No CBZ/ZIP files found in folder" }
                    return@launch
                }
                val seriesName = graph.mangaBackend.localSource.importFolder(folderName, archives) { done, total ->
                    withContext(Dispatchers.Main) { importStatus = "Importing manga folder... $done/$total" }
                }
                val entryId = com.folio.reader.manga.mangaId(com.folio.reader.manga.LOCAL_SOURCE_ID, seriesName)
                graph.mangaRepository.upsert(
                    com.folio.reader.manga.MangaEntry(
                        id = entryId,
                        sourceId = com.folio.reader.manga.LOCAL_SOURCE_ID,
                        sourceName = "Local manga",
                        url = seriesName,
                        title = seriesName,
                        thumbnailUrl = seriesName,
                        inLibrary = true,
                        initialized = true,
                    )
                )
                runCatching {
                    graph.mangaBackend.localSource.materializeCover(seriesName)?.let { cover ->
                        graph.mangaRepository.setCoverPath(entryId, cover.absolutePath)
                    }
                }
                runCatching { graph.syncEngine?.adoptCloudProgressForManga(entryId) }
                withContext(Dispatchers.Main) {
                    importStatus = "Imported folder '$seriesName' with ${archives.size} chapters"
                    refreshTick++
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    importStatus = "Folder import failed: ${e.message ?: "unknown error"}"
                }
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
        uri?.let {
            // folio:// deep links are consumed by the NavHost, not the importer.
            if (it.scheme == "folio") return@let
            val path = it.path.orEmpty()
            val type = intent.type.orEmpty()
            val isManga = path.endsWith(".cbz", ignoreCase = true) ||
                path.endsWith(".zip", ignoreCase = true) ||
                type.contains("cbz") || type.contains("comicbook")
            if (isManga) importMangaUris(listOf(it)) else importEpubUris(listOf(it))
        }
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
}
