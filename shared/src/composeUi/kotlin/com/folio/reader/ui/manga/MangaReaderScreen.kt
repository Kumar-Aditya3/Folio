package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.ui.theme.FolioTokens
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
    val showControls by viewModel.showControls.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val chapterState by viewModel.chapter.collectAsState()
    val localPageVal by viewModel.localPage.collectAsState()
    val localCountVal by viewModel.localCount.collectAsState()
    val extendingForward by viewModel.extendingForward.collectAsState()
    val vmScope = viewModel.scope

    var pageActionIndex by remember { mutableStateOf<Int?>(null) }
    var noteDialogPage by remember { mutableStateOf<Int?>(null) }
    var showNotesList by remember { mutableStateOf(false) }
    var showReaderSettings by remember { mutableStateOf(false) }
    var pageMessage by remember { mutableStateOf<String?>(null) }
    val readerScope = rememberCoroutineScope()

    LaunchedEffect(pageMessage) {
        val message = pageMessage ?: return@LaunchedEffect
        kotlinx.coroutines.delay(2400)
        if (pageMessage == message) pageMessage = null
    }

    LaunchedEffect(chapter.id) { viewModel.open(manga, chapter) }

    DisposableEffect(chapter.id) {
        onDispose { vmScope.launch { viewModel.close() } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(error ?: "", color = Color.White)
                    Spacer(Modifier.height(FolioTokens.space2))
                    Button(onClick = { viewModel.open(manga, chapter) }) { Text("Retry") }
                }
            }
            pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pages", color = Color.White)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // Decode budget: ~3x the viewport keeps zoom sharp without paying for
                // source-resolution bitmaps (webtoon strips can be tens of thousands of
                // pixels tall and dominate memory/GC when decoded raw).
                val targetWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() } * 3
                when (mode) {
                    MangaReaderMode.WEBTOON -> WebtoonReader(
                        viewModel = viewModel,
                        pages = pages,
                        onPageKeyVisible = { viewModel.onPageKeyVisible(it) },
                        onTap = { viewModel.toggleControls() },
                        onLongPressPage = { pageActionIndex = it },
                        extendingForward = extendingForward,
                        targetWidthPx = targetWidthPx,
                    )
                    MangaReaderMode.PAGED_LTR, MangaReaderMode.PAGED_RTL -> PagedReader(
                        viewModel = viewModel,
                        pages = pages,
                        rtl = mode == MangaReaderMode.PAGED_RTL,
                        onPageChanged = { viewModel.onPageChanged(it) },
                        onTap = { viewModel.toggleControls() },
                        onLongPressPage = { pageActionIndex = it },
                        onOpenChapter = onOpenChapter,
                        targetWidthPx = targetWidthPx,
                    )
                    MangaReaderMode.PAGED_VERTICAL -> VerticalReader(
                        viewModel = viewModel,
                        pages = pages,
                        onPageChanged = { viewModel.onPageChanged(it) },
                        onTap = { viewModel.toggleControls() },
                        onLongPressPage = { pageActionIndex = it },
                        onOpenChapter = onOpenChapter,
                        targetWidthPx = targetWidthPx,
                    )
                }
            }
        }

        com.folio.reader.ui.components.ReaderSystemBars(showControls && pages.isNotEmpty())

        if (showControls && pages.isNotEmpty()) {
            ReaderControls(
                mangaTitle = manga.title,
                chapterName = chapterState?.name ?: chapter.name,
                pageCount = localCountVal,
                currentPage = localPageVal,
                mode = mode,
                rtl = mode == MangaReaderMode.PAGED_RTL,
                bookmarked = chapterState?.bookmarked == true,
                onToggleBookmark = { viewModel.toggleBookmark() },
                onShowNotes = { showNotesList = true },
                onShowSettings = { showReaderSettings = true },
                onSeek = { viewModel.seekLocal(it) },
                onBack = onBack,
            )
        }

        val message = pageMessage
        if (message != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 84.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                        .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
                ) {
                    Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showReaderSettings) {
        ReaderSettingsDialog(
            mode = mode,
            onModeChange = { viewModel.setMode(it) },
            onDismiss = { showReaderSettings = false },
        )
    }

    pageActionIndex?.let { combinedIndex ->
        val slot = viewModel.activeSlotForDisplay()
        val localIndex = if (slot != null) combinedIndex - slot.startIndex else combinedIndex
        PageActionsDialog(
            pageIndex = localIndex,
            onNote = {
                pageActionIndex = null
                noteDialogPage = localIndex
            },
            onSave = {
                pageActionIndex = null
                readerScope.launch {
                    pageMessage = "Saving page…"
                    val location = viewModel.savePage(combinedIndex)
                    pageMessage = if (location != null) "Saved to $location" else "Could not save this page"
                }
            },
            onDismiss = { pageActionIndex = null },
        )
    }

    noteDialogPage?.let { localIndex ->
        NoteDialog(
            pageIndex = localIndex,
            existing = null,
            onSave = { content -> viewModel.saveNote(content, localIndex, null) },
            onDismiss = { noteDialogPage = null },
        )
    }

    if (showNotesList) {
        NotesListDialog(
            viewModel = viewModel,
            onDismiss = { showNotesList = false },
        )
    }
}
