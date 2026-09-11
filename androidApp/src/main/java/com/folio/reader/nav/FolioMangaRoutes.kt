package com.folio.reader.nav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MangaBrowseRoute(
    navModel: FolioNavModelImpl,
    onBack: () -> Unit,
    onOpenSource: (Long, String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenManga: (String) -> Unit
) {
    com.folio.reader.ui.manga.MangaBrowseScreen(
        viewModel = navModel.mangaBrowseVM,
        onOpenSource = { source, query -> onOpenSource(source.id, query) },
        onOpenExtensions = onOpenExtensions,
        onOpenManga = { mangaId -> onOpenManga(mangaId) },
        onBack = onBack
    )
}

@Composable
fun MangaSourceBrowseRoute(
    navModel: FolioNavModelImpl,
    sourceId: Long,
    query: String,
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit
) {
    val graph = navModel.graph
    val sources by remember { graph.mangaBackend.observeSources() }
        .collectAsState(initial = emptyList())
    val sourceInfo = sources.firstOrNull { it.id == sourceId }
    if (sourceInfo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val sbVm = navModel.sourceBrowseVmCache.getOrPut(sourceInfo.id) {
        com.folio.reader.ui.manga.SourceBrowseViewModel(
            backend = graph.mangaBackend,
            source = sourceInfo,
            mangaRepo = graph.mangaRepository,
            categoryRepo = graph.mangaCategoryRepository,
            initialQuery = query,
            onAddedToLibrary = { id -> graph.syncEngine?.adoptCloudProgressForManga(id) }
        )
    }
    com.folio.reader.ui.manga.SourceBrowseScreen(
        viewModel = sbVm,
        onOpenManga = { mangaId -> onOpenManga(mangaId) },
        onBack = onBack
    )
}

@Composable
fun ExtensionsRoute(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    com.folio.reader.ui.manga.ExtensionsScreen(
        viewModel = remember {
            com.folio.reader.ui.manga.BrowseViewModel(graph.mangaBackend, graph.mangaRepository)
        },
        onBack = onBack
    )
}

@Composable
fun MangaDownloadsRoute(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    val activity = navModel.activity
    val downloadsVM = remember {
        com.folio.reader.ui.manga.DownloadsViewModel(
            downloadRepo = graph.mangaDownloadRepository,
            mangaRepo = graph.mangaRepository,
            chapterRepo = graph.mangaChapterRepository,
            downloadManager = graph.mangaDownloadManager
        )
    }
    val pickDownloadsDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) activity.changeMangaDownloadsLocation(uri) {
            downloadsVM.refreshStorageDescription()
        }
    }
    com.folio.reader.ui.manga.DownloadsScreen(
        viewModel = downloadsVM,
        onBack = onBack,
        onPickLocation = { pickDownloadsDir.launch(null) }
    )
}

@Composable
fun MangaHistoryRoute(navModel: FolioNavModelImpl, onBack: () -> Unit, onOpenManga: (String) -> Unit) {
    val graph = navModel.graph
    com.folio.reader.ui.manga.MangaHistoryScreen(
        backend = graph.mangaBackend,
        historyRepo = graph.mangaHistoryRepository,
        onOpenManga = { entry -> onOpenManga(entry.mangaId) },
        onBack = onBack
    )
}

@Composable
fun MangaDetailRoute(
    navModel: FolioNavModelImpl,
    mangaId: String,
    onBack: () -> Unit,
    onRead: (String) -> Unit
) {
    val graph = navModel.graph
    val viewModel = navModel.mangaDetailViewModel(mangaId)
        .also { vm -> LaunchedEffect(mangaId) { vm.open(mangaId) } }
    val removalScope = rememberCoroutineScope()
    var showRemovalDialog by remember { mutableStateOf(false) }
    var deleteDownloads by remember { mutableStateOf(false) }

    com.folio.reader.ui.manga.MangaDetailScreen(
        viewModel = viewModel,
        backend = graph.mangaBackend,
        downloadsAvailable = graph.mangaBackend.supportsExtensions,
        onRead = { _, chapter -> onRead(chapter.id) },
        onBack = onBack,
        onRemoveFromLibrary = {
            deleteDownloads = false
            showRemovalDialog = true
        },
    )

    if (showRemovalDialog) {
        AlertDialog(
            onDismissRequest = {
                showRemovalDialog = false
                deleteDownloads = false
            },
            title = { Text("Remove manga?") },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteDownloads = !deleteDownloads }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = deleteDownloads,
                        onCheckedChange = { deleteDownloads = it },
                    )
                    Text("Also delete downloaded chapters")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val shouldDeleteDownloads = deleteDownloads
                        showRemovalDialog = false
                        deleteDownloads = false
                        removalScope.launch(Dispatchers.IO) {
                            navModel.removeMangaFromLibrary(
                                setOf(mangaId),
                                shouldDeleteDownloads,
                            )
                            withContext(Dispatchers.Main) {
                                viewModel.reflectRemovedFromLibrary()
                            }
                        }
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRemovalDialog = false
                        deleteDownloads = false
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun MangaReaderRoute(
    navModel: FolioNavModelImpl,
    mangaId: String,
    chapterId: String,
    onBack: () -> Unit,
    onNextChapter: (String) -> Unit
) {
    val graph = navModel.graph
    var manga by remember(mangaId) { mutableStateOf<com.folio.reader.manga.MangaEntry?>(null) }
    var chapter by remember(chapterId) { mutableStateOf<com.folio.reader.manga.MangaChapter?>(null) }

    LaunchedEffect(mangaId, chapterId) {
        manga = graph.mangaRepository.get(mangaId)
        chapter = graph.mangaChapterRepository.getChapter(chapterId)
    }

    val m = manga
    val c = chapter
    if (m == null || c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    com.folio.reader.ui.manga.MangaReaderScreen(
        viewModel = remember(c.id) {
            com.folio.reader.ui.manga.MangaReaderViewModel(
                backend = graph.mangaBackend,
                downloadManager = graph.mangaDownloadManager,
                chapterRepo = graph.mangaChapterRepository,
                mangaRepo = graph.mangaRepository,
                historyRepo = graph.mangaHistoryRepository,
                noteRepo = graph.mangaNoteRepository,
                settingsRepo = graph.settingsRepository,
                fileSystem = graph.platform.fileSystem,
                sessionRepo = graph.sessionRepository,
                cycleRepo = graph.readingCycleRepository,
                updateRepo = graph.mangaUpdateRepository
            )
        },
        manga = m,
        chapter = c,
        onOpenChapter = { onNextChapter(it.id) },
        onBack = onBack
    )
}
