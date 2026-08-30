package com.folio.reader.ui.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.ui.components.decodeCoverImage
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page image decode cache, bounded so long chapters don't balloon memory. */
private val pageBitmapCache = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
private const val PAGE_CACHE_MAX = 10

private fun pageCacheGet(key: String): ImageBitmap? = synchronized(pageBitmapCache) { pageBitmapCache[key] }

private fun pageCachePut(key: String, bitmap: ImageBitmap) = synchronized(pageBitmapCache) {
    pageBitmapCache[key] = bitmap
    while (pageBitmapCache.size > PAGE_CACHE_MAX) {
        pageBitmapCache.keys.firstOrNull()?.let { pageBitmapCache.remove(it) }
    }
}

@Composable
fun MangaReaderScreen(
    viewModel: MangaReaderViewModel,
    manga: MangaEntry,
    chapter: MangaChapter,
    nextChapter: MangaChapter?,
    onNextChapter: (MangaChapter) -> Unit,
    onBack: () -> Unit,
) {
    val pages by viewModel.pages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val showControls by viewModel.showControls.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val chapterState by viewModel.chapter.collectAsState()
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
            else -> when (mode) {
                MangaReaderMode.WEBTOON -> WebtoonReader(
                    viewModel = viewModel,
                    pages = pages,
                    onPageChanged = { viewModel.onPageChanged(it) },
                    onTap = { viewModel.toggleControls() },
                    onLongPressPage = { pageActionIndex = it },
                    nextChapter = nextChapter,
                    onNextChapter = onNextChapter,
                )
                MangaReaderMode.PAGED_LTR, MangaReaderMode.PAGED_RTL -> PagedReader(
                    viewModel = viewModel,
                    pages = pages,
                    rtl = mode == MangaReaderMode.PAGED_RTL,
                    onPageChanged = { viewModel.onPageChanged(it) },
                    onTap = { viewModel.toggleControls() },
                    onLongPressPage = { pageActionIndex = it },
                    nextChapter = nextChapter,
                    onNextChapter = onNextChapter,
                )
                MangaReaderMode.PAGED_VERTICAL -> VerticalReader(
                    viewModel = viewModel,
                    pages = pages,
                    onPageChanged = { viewModel.onPageChanged(it) },
                    onTap = { viewModel.toggleControls() },
                    onLongPressPage = { pageActionIndex = it },
                    nextChapter = nextChapter,
                    onNextChapter = onNextChapter,
                )
            }
        }

        com.folio.reader.ui.components.ReaderSystemBars(showControls && pages.isNotEmpty())

        if (showControls && pages.isNotEmpty()) {
            ReaderControls(
                mangaTitle = manga.title,
                chapterName = chapter.name,
                pageCount = pages.size,
                currentPage = viewModel.currentIndex.collectAsState().value,
                mode = mode,
                bookmarked = chapterState?.bookmarked == true,
                onToggleBookmark = { viewModel.toggleBookmark() },
                onShowNotes = { showNotesList = true },
                onShowSettings = { showReaderSettings = true },
                onSeek = { viewModel.onPageChanged(it) },
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

    pageActionIndex?.let { pageIndex ->
        PageActionsDialog(
            pageIndex = pageIndex,
            onNote = {
                pageActionIndex = null
                noteDialogPage = pageIndex
            },
            onSave = {
                pageActionIndex = null
                readerScope.launch {
                    pageMessage = "Saving page…"
                    val location = viewModel.savePage(pageIndex)
                    pageMessage = if (location != null) "Saved to $location" else "Could not save this page"
                }
            },
            onDismiss = { pageActionIndex = null },
        )
    }

    noteDialogPage?.let { pageIndex ->
        NoteDialog(
            pageIndex = pageIndex,
            existing = null,
            onSave = { content -> viewModel.saveNote(content, pageIndex, null) },
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

@Composable
private fun WebtoonReader(
    viewModel: MangaReaderViewModel,
    pages: List<MangaPageRef>,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPressPage: (Int) -> Unit,
    nextChapter: MangaChapter?,
    onNextChapter: (MangaChapter) -> Unit,
) {
    var visibleIndex by remember { mutableStateOf(0) }
    LaunchedEffect(visibleIndex) { onPageChanged(visibleIndex) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(pages.size, key = { it }) { index ->
            ReaderPage(
                viewModel = viewModel,
                index = index,
                modifier = Modifier.fillMaxWidth(),
                onTap = onTap,
                onLongPress = { onLongPressPage(index) },
                onVisible = { visibleIndex = index },
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(FolioTokens.space4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("End of chapter", color = Color.White.copy(alpha = 0.7f))
                if (nextChapter != null) {
                    Spacer(Modifier.height(FolioTokens.space2))
                    Button(onClick = { onNextChapter(nextChapter) }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Next chapter")
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    viewModel: MangaReaderViewModel,
    pages: List<MangaPageRef>,
    rtl: Boolean,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPressPage: (Int) -> Unit,
    nextChapter: MangaChapter?,
    onNextChapter: (MangaChapter) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = viewModel.currentIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()

    fun goNext() {
        val current = pagerState.currentPage
        if (current < pages.size - 1) scope.launch { pagerState.animateScrollToPage(current + 1) }
    }

    fun goPrev() {
        val current = pagerState.currentPage
        if (current > 0) scope.launch { pagerState.animateScrollToPage(current - 1) }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
    ) { index ->
        Box(Modifier.fillMaxSize()) {
            ReaderPage(
                viewModel = viewModel,
                index = index,
                modifier = Modifier.fillMaxSize(),
                fit = true,
                onTap = {},
                onLongPress = { onLongPressPage(index) },
            )
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            onClick = { if (rtl) goNext() else goPrev() },
                            onLongClick = { onLongPressPage(index) },
                        )
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            onClick = onTap,
                            onLongClick = { onLongPressPage(index) },
                        )
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            onClick = { if (rtl) goPrev() else goNext() },
                            onLongClick = { onLongPressPage(index) },
                        )
                )
            }
            if (index == pages.size - 1 && nextChapter != null) {
                Button(
                    onClick = { onNextChapter(nextChapter) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(FolioTokens.space4),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Next chapter")
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VerticalReader(
    viewModel: MangaReaderViewModel,
    pages: List<MangaPageRef>,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPressPage: (Int) -> Unit,
    nextChapter: MangaChapter?,
    onNextChapter: (MangaChapter) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = viewModel.currentIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        pageCount = { pages.size },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { index ->
        Box(Modifier.fillMaxSize()) {
            ReaderPage(
                viewModel = viewModel,
                index = index,
                modifier = Modifier.fillMaxSize(),
                fit = true,
                onTap = onTap,
                onLongPress = { onLongPressPage(index) },
            )
            if (index == pages.size - 1 && nextChapter != null) {
                Button(
                    onClick = { onNextChapter(nextChapter) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(FolioTokens.space4),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Next chapter")
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    mode: MangaReaderMode,
    onModeChange: (MangaReaderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Reading mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderModeChip(
                        label = "Webtoon (continuous vertical)",
                        selected = mode == MangaReaderMode.WEBTOON,
                        onClick = { onModeChange(MangaReaderMode.WEBTOON) },
                    )
                    ReaderModeChip(
                        label = "Paged (left to right)",
                        selected = mode == MangaReaderMode.PAGED_LTR,
                        onClick = { onModeChange(MangaReaderMode.PAGED_LTR) },
                    )
                    ReaderModeChip(
                        label = "Paged (right to left)",
                        selected = mode == MangaReaderMode.PAGED_RTL,
                        onClick = { onModeChange(MangaReaderMode.PAGED_RTL) },
                    )
                    ReaderModeChip(
                        label = "Paged (vertical)",
                        selected = mode == MangaReaderMode.PAGED_VERTICAL,
                        onClick = { onModeChange(MangaReaderMode.PAGED_VERTICAL) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ReaderModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReaderPage(
    viewModel: MangaReaderViewModel,
    index: Int,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onVisible: () -> Unit = {},
) {
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(index) { mutableStateOf(false) }
    val chapterId = viewModel.chapter.collectAsState().value?.id ?: ""
    val cacheKey = "$chapterId:$index"

    LaunchedEffect(index) {
        onVisible()
        pageCacheGet(cacheKey)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val bytes = viewModel.resolvePageImage(index)
        if (bytes == null) {
            failed = true
        } else {
            val decoded = withContext(Dispatchers.IO) { decodeCoverImage(bytes) }
            if (decoded == null) {
                failed = true
            } else {
                pageCachePut(cacheKey, decoded)
                bitmap = decoded
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().let { base ->
                    if (fit) base.fillMaxSize() else base
                },
                contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
            )
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                Text(
                    "Page failed to load",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun ReaderControls(
    mangaTitle: String,
    chapterName: String,
    pageCount: Int,
    currentPage: Int,
    mode: MangaReaderMode,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onShowNotes: () -> Unit,
    onShowSettings: () -> Unit,
    onSeek: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val overlayColor = Color.Black.copy(alpha = 0.72f)

    Column(Modifier.fillMaxSize()) {
        com.folio.reader.ui.components.FolioStatusBarBand()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(overlayColor)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    mangaTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    chapterName,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "Bookmark chapter",
                    tint = if (bookmarked) Color(0xFF8B9DC3) else Color.White,
                )
            }
            IconButton(onClick = onShowNotes) {
                Icon(
                    imageVector = Icons.Filled.EditNote,
                    contentDescription = "Notes",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onShowSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Reader settings",
                    tint = Color.White,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(overlayColor)
                .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${currentPage + 1}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = currentPage.toFloat(),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..((pageCount - 1).coerceAtLeast(0)).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = FolioTokens.space2),
            )
            Text(
                "$pageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PageActionsDialog(
    pageIndex: Int,
    onNote: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page ${pageIndex + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNote)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.EditNote,
                        contentDescription = null,
                        tint = FolioTheme.colors.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Add a note", style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSave)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = FolioTheme.colors.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Save page to Downloads", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoteDialog(
    pageIndex: Int,
    existing: com.folio.reader.manga.MangaNote?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(existing?.content ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note — page ${pageIndex + 1}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(text.trim())
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NotesListDialog(
    viewModel: MangaReaderViewModel,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf<List<com.folio.reader.manga.MangaNote>>(emptyList()) }
    val revision by viewModel.notesRevision.collectAsState()
    val colors = FolioTheme.colors

    LaunchedEffect(revision) { notes = viewModel.chapterNotes() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapter notes") },
        text = {
            if (notes.isEmpty()) {
                Text(
                    "No notes yet. Long-press a page to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notes.forEach { note ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Page ${note.pageIndex + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { viewModel.deleteNote(note.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete note",
                                    tint = colors.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
