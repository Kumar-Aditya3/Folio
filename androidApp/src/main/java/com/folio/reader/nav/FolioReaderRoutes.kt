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
import com.folio.reader.epub.repairStoredChapterTitles
import com.folio.reader.model.Book
import com.folio.reader.settings.normalized
import com.folio.reader.settings.overriddenFields
import com.folio.reader.ui.book.BookDetailScreen
import com.folio.reader.ui.book.BookDetailViewModel
import com.folio.reader.ui.document.DocumentReaderScreen
import com.folio.reader.ui.document.DocumentReaderViewModel
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
        onSettingsChanged = { navModel.globalSettings = it },
        // Read off the global row here — the only place in this route that still
        // holds the nav model. [globalSettings] is a Compose `mutableStateOf`, so
        // this read subscribes the whole reader route to it: whenever the settings
        // read lands, the route recomposes and `morphIntoReader` becomes the
        // reader's own value instead of the default.
        //
        // It used to be a plain parameter read at the call site, which happened to
        // look identical — until the settings read landed late, at which point the
        // reader had already composed with `morphIntoReader = false` and, because
        // nothing in the route *observed* the setting, it never recomposed to pick
        // the real value up. Reading it here rather than passing it in is what makes
        // the flag converge; `warmGlobalSettings()` is what makes it usually already
        // correct on the first frame.
        morphIntoReader = navModel.globalSettings.morphIntoReader
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
    onSettingsChanged: (com.folio.reader.settings.ReaderSettings) -> Unit = {},
    /** §17 morph landing opt-in; see [com.folio.reader.settings.ReaderSettings.morphIntoReader]. */
    morphIntoReader: Boolean = false
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
    // Both are StateFlows, so these calls resolve to the no-`initial` overload and
    // read the current value on the first frame. Passing `initial` here was the
    // bug: the view model said "not loading" while the html was still empty, and a
    // supplied `initial = true` only papered over it for one frame.
    val html by viewModel.chapterHtml.collectAsState()
    val loadingContent by viewModel.isLoadingContent.collectAsState()
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
    // Continuous-mode chapter window.
    val windowSections by viewModel.windowSections.collectAsState(initial = emptyList())
    val windowLoad by viewModel.windowLoad.collectAsState(initial = emptyList())
    val windowRange by viewModel.windowRange.collectAsState(initial = null)
    val windowOp by viewModel.windowOp.collectAsState(initial = null)
    // A window only renders while continuous layout is actually in effect; a
    // stale window from a layout switch must not bleed into paged mode.
    val windowed = windowRange != null &&
        settings.layoutMode.normalized == com.folio.reader.settings.LayoutMode.CONTINUOUS
    val overridden by remember {
        combine(viewModel.settings, viewModel.bookSettings) { global, book ->
            book?.overriddenFields(global) ?: emptySet()
        }
    }.collectAsState(initial = emptySet())
    val syncState: com.folio.reader.sync.SyncState? by remember(graph.syncEngineState.value) {
        graph.syncEngine?.syncState ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(Unit) {
        // Re-derive chapter titles stored before the labeling fixes (see ChapterTitleRepair).
        repairStoredChapterTitles(
            bookId = book.id,
            epubPath = graph.platform.fileSystem.getBookEpubPath(book.id),
            parser = graph.epubParser,
            repository = graph.bookRepository
        )
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
        sections = if (windowed) windowSections else emptyList(),
        documentSections = if (windowed) windowLoad else emptyList(),
        windowed = windowed,
        windowOp = windowOp,
        onVisibleSection = { spine -> viewModel.onVisibleSection(spine) },
        onExtendForward = { viewModel.extendWindow(forward = true) },
        onExtendBackward = { viewModel.extendWindow(forward = false) },
        onWindowOpApplied = { nonce -> viewModel.onWindowOpApplied(nonce) },
        chapterChip = chapterChip,
        onDismissChapterChip = { viewModel.dismissChapterChip() },
        onHighlightParagraph = { chapterId, paragraphIndex, snippet ->
            // The paragraph may sit in a windowed chapter that is not the anchor,
            // so the locator's spine comes from the selection's own chapter.
            val spine = chapters.firstOrNull { it.id == chapterId }?.spineIndex
                ?: position?.spineIndex ?: 0
            viewModel.addHighlight(
                startLocator = "/$spine/$paragraphIndex:0",
                endLocator = "/$spine/$paragraphIndex:end",
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
            // The navModel's snapshot must stay the *global* row; `updated` is
            // effective-derived and carries this book's overrides.
            onSettingsChanged(viewModel.global())
        },
        onResetBook = { viewModel.resetBookToDefaults() },
        // §17 morph landing, opt-in (see ReaderSettings.morphIntoReader). Decided by
        // the caller from the *global* settings row rather than from `settings`,
        // which is this book's effective copy: it is a device-confidence flag, not a
        // reading preference, so it must not vary per book.
        morphBookId = book.id.takeIf { morphIntoReader }
    )
}

@Composable
fun DocumentReaderRoute(
    navModel: FolioNavModelImpl,
    documentId: String,
    onBack: () -> Unit
) {
    val graph = navModel.graph
    // The reader defaults now contain the doc defaults: the persisted
    // documentReaderMode seeds the reader (unknown values fall back to today's
    // single-page), and a mode change in the reader writes back through the
    // canonical settings path, so it survives the visit and syncs like every
    // other default.
    val initialMode = remember(documentId) {
        runCatching {
            com.folio.reader.ui.document.DocumentReaderMode.valueOf(
                navModel.globalSettings.documentReaderMode
            )
        }.getOrDefault(com.folio.reader.ui.document.DocumentReaderMode.SINGLE_PAGE)
    }
    val viewModel = remember(documentId) {
        DocumentReaderViewModel(
            repository = graph.documentRepository,
            fileSystem = graph.platform.fileSystem,
            initialMode = initialMode,
            onModeChanged = { mode ->
                navModel.updateSettings(
                    navModel.globalSettings.copy(documentReaderMode = mode.name)
                )
            }
        )
    }
    LaunchedEffect(documentId) {
        viewModel.open(documentId)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }
    DocumentReaderScreen(
        viewModel = viewModel,
        settings = navModel.globalSettings,
        onBack = onBack,
        // §17 morph landing. Unconditional, unlike the EPUB reader's: this path is
        // pure Compose on both platforms, so there is no native surface whose paint
        // timing could disagree with the plate being dropped.
        morphDocumentId = documentId
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
