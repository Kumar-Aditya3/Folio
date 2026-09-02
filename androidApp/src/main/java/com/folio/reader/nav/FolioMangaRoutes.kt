package com.folio.reader.nav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

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
    com.folio.reader.ui.manga.MangaDetailScreen(
        viewModel = viewModel,
        backend = graph.mangaBackend,
        downloadsAvailable = graph.mangaBackend.supportsExtensions,
        onRead = { _, chapter -> onRead(chapter.id) },
        onBack = onBack
    )
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
                cycleRepo = graph.readingCycleRepository
            )
        },
        manga = m,
        chapter = c,
        onOpenChapter = { onNextChapter(it.id) },
        onBack = onBack
    )
}
