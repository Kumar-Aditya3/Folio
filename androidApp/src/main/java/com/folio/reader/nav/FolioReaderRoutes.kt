package com.folio.reader.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.folio.reader.AppGraph
import com.folio.reader.model.Book
import com.folio.reader.settings.overriddenFields
import com.folio.reader.ui.book.BookDetailScreen
import com.folio.reader.ui.book.BookDetailViewModel
import com.folio.reader.ui.reader.ReaderScreen
import com.folio.reader.ui.reader.ReaderViewModel
import com.folio.reader.ui.search.SearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@Composable
fun ReaderRoute(
    navModel: FolioNavModelImpl,
    bookId: String,
    targetSpineIndex: Int?,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val graph = navModel.graph
    val book = remember(bookId) { mutableStateOf<Book?>(null) }
    LaunchedEffect(bookId) { book.value = graph.bookRepository.getBook(bookId) }
    val b = book.value
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    ReaderRouteContent(
        graph = graph,
        book = b,
        targetSpineIndex = targetSpineIndex,
        initialSettings = navModel.globalSettings,
        onBackPress = onBack,
        onSearchClick = onOpenSearch,
        onSettingsClick = onOpenSettings,
        onSettingsChanged = { navModel.globalSettings = it }
    )
}

@Composable
private fun ReaderRouteContent(
    graph: AppGraph,
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
            bookRepository = graph.bookRepository,
            positionRepository = graph.positionRepository,
            sessionRepository = graph.sessionRepository,
            bookmarkRepository = graph.bookmarkRepository,
            highlightRepository = graph.highlightRepository,
            noteRepository = graph.noteRepository,
            settingsRepository = graph.settingsRepository,
            chapterContentProvider = { id, href -> graph.contentProvider.getHtml(id, href) },
            syncEngine = graph.syncEngine,
            quoteRepository = graph.quoteRepository,
            revisitRepository = graph.revisitRepository
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
    val chapterChip by viewModel.chapterChip.collectAsState(initial = null)
    val overridden by remember {
        combine(viewModel.settings, viewModel.bookSettings) { global, book ->
            book?.overriddenFields(global) ?: emptySet()
        }
    }.collectAsState(initial = emptySet())
    val syncState: com.folio.reader.sync.SyncState? by remember(graph.syncEngineState.value) {
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

    DisposableEffect(viewModel) {
        onDispose { viewModel.closeBook() }
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
        onSettingsChange = { updated ->
            viewModel.updateSettings(updated)
            onSettingsChanged(viewModel.global())
        },
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
        chapterChip = chapterChip,
        onDismissChapterChip = { viewModel.dismissChapterChip() },
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
        syncState = syncState,
        scopeControlEnabled = true,
        overriddenFields = overridden,
        onWriteGlobal = { updated ->
            viewModel.updateGlobalSettings(updated)
            onSettingsChanged(updated)
        },
        onResetBook = { viewModel.resetBookToDefaults() }
    )
}

@Composable
fun SearchRoute(navModel: FolioNavModelImpl, onBack: () -> Unit, onOpenReader: (String, Int?) -> Unit) {
    val graph = navModel.graph
    val books by remember { graph.bookRepository.getAllBooks() }.collectAsState(initial = emptyList())
    SearchScreen(
        books = books,
        searchRepository = graph.searchRepository,
        highlightRepository = graph.highlightRepository,
        noteRepository = graph.noteRepository,
        bookmarkRepository = graph.bookmarkRepository,
        quoteRepository = graph.quoteRepository,
        onBackPress = onBack,
        uiState = navModel.searchUiState,
        onResultClick = { hit ->
            val target = hit.spineIndex.takeIf { it >= 0 }
            books.firstOrNull { it.id == hit.book.id }?.let { onOpenReader(it.id, target) }
        }
    )
}

@Composable
fun BookDetailRoute(
    navModel: FolioNavModelImpl,
    bookId: String,
    onBack: () -> Unit,
    onStartReading: () -> Unit,
    onOpenTags: () -> Unit
) {
    val graph = navModel.graph
    val activity = navModel.activity
    val book = remember(bookId) { mutableStateOf<Book?>(null) }
    LaunchedEffect(bookId) { book.value = graph.bookRepository.getBook(bookId) }
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
                        tagRepository = graph.tagRepository
                    )
                }.also { vm -> LaunchedEffect(b.id) { vm.loadBook(b.id) } },
                onBackPress = onBack,
                onStartReading = onStartReading,
                onDeleteClick = {
                    activity.appScope.launch(Dispatchers.IO) {
                        runCatching { graph.bookRepository.deleteBook(b.id) }
                        runCatching { graph.platform.fileSystem.deleteBookFiles(b.id) }
                        activity.refreshTick++
                    }
                    onBack()
                },
                onEditClick = { },
                onShareClick = { navModel.callbacks.onShareEpub(b.id) },
                onTagClick = { onOpenTags() },
                onSeriesClick = { },
                onCollectionClick = { }
            )
        }
    }
}
