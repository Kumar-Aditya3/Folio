package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
import com.folio.reader.ui.components.*
import com.folio.reader.ui.reader.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MangaReaderScreen(
    viewModel: MangaReaderViewModel,
    manga: MangaEntry,
    chapter: MangaChapter,
    onOpenChapter: (MangaChapter) -> Unit,
    onBack: () -> Unit,
) {
    val pages by viewModel.pages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val modeResolved by viewModel.modeResolved.collectAsState()
    val showControls by viewModel.showControls.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val chapterState by viewModel.chapter.collectAsState()
    val localPage by viewModel.localPage.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val extendingForward by viewModel.extendingForward.collectAsState()
    val readerScope = rememberCoroutineScope()
    val vmScope = viewModel.scope

    var pageActionKey by remember { mutableStateOf<String?>(null) }
    var noteDialogPage by remember { mutableStateOf<Int?>(null) }
    var noteChapterId by remember { mutableStateOf<String?>(null) }
    var editingNote by remember { mutableStateOf<MangaNote?>(null) }
    var activeTool by remember { mutableStateOf<ReaderTool?>(null) }
    var previewPage by remember { mutableStateOf<Int?>(null) }
    var returnPage by remember(chapterState?.id) { mutableStateOf<Int?>(null) }
    var pageMessage by remember { mutableStateOf<String?>(null) }

    fun seek(page: Int) {
        if (returnPage == null) returnPage = localPage
        viewModel.seekLocal(page)
        activeTool = null
    }
    LaunchedEffect(pageMessage) {
        val message = pageMessage ?: return@LaunchedEffect
        kotlinx.coroutines.delay(3200)
        if (pageMessage == message) pageMessage = null
    }
    LaunchedEffect(chapter.id) { viewModel.open(manga, chapter) }
    DisposableEffect(chapter.id) {
        onDispose { vmScope.launch { viewModel.close() } }
    }

    FolioFieldOccluder()
    // Only uncovered gutters inherit the theme; source artwork is never tinted or filtered.
    val stage = lerp(Color.Black, FolioTheme.colors.background, .94f)
    BoxWithConstraints(Modifier.fillMaxSize().background(stage)) {
        val workspaceLayout = readerWorkspaceLayout(maxWidth, maxHeight, LocalDensity.current.fontScale)
        val reservedEnd = if (activeTool != null && workspaceLayout.docked) workspaceLayout.dockWidth else 0.dp
        Box(Modifier.fillMaxSize().padding(end = reservedEnd)) {
        when {
            // Resolve persisted mode before composing a pager, avoiding a webtoon flash.
            loading || !modeResolved -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FolioTheme.colors.accentProgress)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = FolioTheme.colors.onSurfaceVariant)
                    Text(error.orEmpty(), color = FolioTheme.colors.onSurface, style = FolioTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::retry) { Text("Try again") }
                }
            }
            pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pages in this chapter", color = FolioTheme.colors.onSurfaceVariant)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().folioBackdropSource()) {
                // Preserve the reader's decode budget and all zoom/placeholder geometry.
                val targetWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() } * 3
                when (mode) {
                    MangaReaderMode.WEBTOON -> WebtoonReader(
                        viewModel, pages, onPageKeyVisible = { viewModel.onPageKeyVisible(it) },
                        onTap = { viewModel.toggleControls() }, onLongPressPage = { pageActionKey = viewModel.pageKey(it) },
                        extendingForward = extendingForward, targetWidthPx = targetWidthPx)
                    MangaReaderMode.PAGED_LTR, MangaReaderMode.PAGED_RTL -> PagedReader(
                        viewModel, pages, rtl = mode == MangaReaderMode.PAGED_RTL,
                        onPageChanged = { viewModel.onPageChanged(it) }, onTap = { viewModel.toggleControls() },
                        onLongPressPage = { pageActionKey = viewModel.pageKey(it) }, onOpenChapter = onOpenChapter,
                        targetWidthPx = targetWidthPx)
                    MangaReaderMode.PAGED_VERTICAL -> VerticalReader(
                        viewModel, pages, onPageChanged = { viewModel.onPageChanged(it) },
                        onTap = { viewModel.toggleControls() }, onLongPressPage = { pageActionKey = viewModel.pageKey(it) },
                        onOpenChapter = onOpenChapter, targetWidthPx = targetWidthPx)
                }
            }
        }
        ReaderSystemBars(showControls && pages.isNotEmpty())
        ReaderControls(
            visible = showControls && pages.isNotEmpty(), mangaTitle = manga.title, mangaId = manga.id,
            chapterName = chapterState?.name ?: chapter.name, pageCount = localCount,
            currentPage = localPage, mode = mode, rtl = mode == MangaReaderMode.PAGED_RTL,
            bookmarked = chapterState?.bookmarked == true, onToggleBookmark = { viewModel.toggleBookmark() },
            onShowNotes = { activeTool = ReaderTool.Saved }, onShowSettings = { activeTool = ReaderTool.Settings },
            onShowPages = { activeTool = ReaderTool.Pages }, onPreview = { previewPage = it },
            onSeek = ::seek, onBack = onBack)
        if (showControls && pages.isNotEmpty()) FolioStatusBarBand(inkBand = true)

        val preview = previewPage
        if (preview != null) {
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp)
                .width(176.dp).readerIsland(FolioSoftShape(16.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MangaCachedPage(viewModel, chapterState?.id.orEmpty(), preview,
                    Modifier.fillMaxWidth().height(152.dp))
                ReaderLocationLabel("PAGE ${preview + 1} / $localCount")
                Text("Release to go there", style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant)
            }
        } else if (returnPage != null && showControls && activeTool == null) {
            val origin = returnPage!!
            ReaderReturnButton("Page ${origin + 1}", onClick = {
                viewModel.seekLocal(origin); returnPage = null
            }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))
        }
        pageMessage?.let { message ->
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp)
                .readerIsland(FolioSoftShape(14.dp)).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = FolioTheme.typography.bodyMedium)
                TextButton(onClick = { pageMessage = null }) { Text("Dismiss") }
            }
        }
        }
        MangaReaderWorkspace(activeTool, viewModel,
            onSelect = { activeTool = it }, onDismiss = { activeTool = null }, onSeek = ::seek,
            onOpenChapter = { entry -> activeTool = null; onOpenChapter(entry) },
            onEditNote = { note -> editingNote = note; noteChapterId = note.chapterId; noteDialogPage = note.pageIndex },
            onOpenNote = { note ->
                if (note.chapterId == chapterState?.id && returnPage == null) returnPage = localPage
                viewModel.openNote(note)
                activeTool = null
            }, layout = workspaceLayout)
    }
    pageActionKey?.let { key ->
        val combinedIndex = viewModel.indexForPageKey(key) ?: return@let
        val slot = viewModel.slotForIndex(combinedIndex)
        val localIndex = if (slot != null) combinedIndex - slot.startIndex else combinedIndex
        PageActionsDialog(localIndex,
            onNote = {
                pageActionKey = null; editingNote = null
                noteChapterId = slot?.chapter?.id; noteDialogPage = localIndex
            },
            onSave = {
                pageActionKey = null
                readerScope.launch {
                    pageMessage = "Saving page…"
                    val location = viewModel.savePage(combinedIndex)
                    pageMessage = if (location != null) "Saved to Downloads" else "Could not save this page"
                }
            }, onDismiss = { pageActionKey = null })
    }
    noteDialogPage?.let { localIndex ->
        NoteDialog(localIndex, editingNote,
            onSave = { content -> viewModel.saveNote(content, localIndex, editingNote, noteChapterId) },
            onDismiss = { noteDialogPage = null })
    }
}
