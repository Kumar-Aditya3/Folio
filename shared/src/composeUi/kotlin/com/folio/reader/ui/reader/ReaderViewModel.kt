package com.folio.reader.ui.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.ReadingPositionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.clearing
import com.folio.reader.settings.diffFields
import com.folio.reader.settings.normalized
import com.folio.reader.settings.overriddenFields
import com.folio.reader.settings.withFieldsFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.folio.reader.ui.render.LinkClickResult
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days

class ReaderViewModel(
    private val bookRepository: BookRepository,
    private val positionRepository: ReadingPositionRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val chapterContentProvider: suspend (bookId: String, chapterHref: String) -> String,
    private val syncEngine: com.folio.reader.sync.SyncEngine? = null,
    private val quoteRepository: com.folio.reader.database.QuoteRepository? = null,
    private val revisitRepository: com.folio.reader.database.RevisitRepository? = null,
    /**
     * Atlas + Echoes engine. Nullable because the reader is constructed on both platforms and in
     * tests, and Echoes is an additive surface — a null repo (or the feature flag off) simply
     * means the action never lights and no panel opens. Reused from the app graph, so it shares
     * the one embedder session the search stack already holds.
     */
    private val discoveryRepository: com.folio.reader.ml.SemanticDiscoveryRepository? = null
) {
    private val _book = MutableStateFlow<Book?>(null)
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    private val _currentChapterIndex = MutableStateFlow(0)
    private val _position = MutableStateFlow<ReadingPosition?>(null)
    private val _session = MutableStateFlow<ReadingSession?>(null)
    private val _settings = MutableStateFlow(ReaderSettings())
    private val _bookSettings = MutableStateFlow<BookReaderSettings?>(null)
    private val _showControls = MutableStateFlow(true)
    private val _showToc = MutableStateFlow(false)
    private val _showAnnotations = MutableStateFlow(false)
    private val _showEchoes = MutableStateFlow(false)
    private val _echoes = MutableStateFlow<EchoesState>(EchoesState.Idle)
    /** Guards against a stale result landing after the reader moved on to a newer selection. */
    private var echoRequest = 0
    /** Warm the embedder/index once per opened book, on first selection. */
    private var echoesWarmed = false
    private val _chapterHtml = MutableStateFlow("")
    private val _chapterChip = MutableStateFlow<String?>(null)
    /**
     * True while a chapter is being loaded.
     *
     * Starts **true**, not false. A reader is only ever constructed in order to
     * open a book, so at construction a load is pending by definition — and the
     * `false` default was a lie that the UI could not tell apart from a finished
     * empty chapter. That lie is what put "This page is empty." on screen before
     * the first chapter arrived: the screen consumed a real `isLoading = false`
     * with `html` still empty and `loadError` still null, which is
     * indistinguishable from a genuinely empty chapter.
     *
     * Every load path sets this true and clears it in a `finally`, so the only
     * cost of the honest default is that a reader which never loads anything
     * shows its spinner rather than an empty-page dead end — which is the correct
     * thing to show for a book that has not opened yet.
     */
    private val _isLoadingContent = MutableStateFlow(true)
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: kotlinx.coroutines.flow.StateFlow<String?> = _loadError

    private var currentBookId: String? = null
    private var deviceId: String = ""
    private var closeStarted = false
    /** This book's session history, loaded at open; feeds the chapter-chip pace band. */
    private var recentSessions: List<ReadingSession> = emptyList()

    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sessionTracker = ReadingSessionTracker(
        sessionRepository,
        _session,
        bookTotalWords = { _book.value?.totalWords ?: 0L }
    )
    private val positionStore = ReaderPositionStore(
        positionRepository = positionRepository,
        bookRepository = bookRepository,
        settingsRepository = settingsRepository,
        scope = viewModelScope,
        tracker = sessionTracker,
        positionState = _position,
        sessionState = _session,
        bookState = _book,
        chaptersState = _chapters,
        currentChapterIndexState = _currentChapterIndex,
        currentBookId = { currentBookId },
        syncEngine = syncEngine
    )
    private val annotations = ReaderAnnotations(
        bookmarkRepository = bookmarkRepository,
        highlightRepository = highlightRepository,
        noteRepository = noteRepository,
        scope = viewModelScope,
        positionState = _position,
        currentBookId = { currentBookId },
        deviceId = { deviceId },
        effectiveSettings = { effective() },
        quoteRepository = quoteRepository,
        revisitRepository = revisitRepository
    )
    private val links = ReaderLinks(
        currentBookId = { currentBookId },
        chapters = { _chapters.value },
        currentChapterIndex = { _currentChapterIndex.value }
    )
    private val contentLoader = ReaderContentLoader(
        scope = viewModelScope,
        chapterContentProvider = chapterContentProvider,
        chapterHtmlState = _chapterHtml,
        isLoadingContentState = _isLoadingContent,
        loadErrorState = _loadError,
        currentBookId = { currentBookId },
        chapters = { _chapters.value },
        currentChapterIndex = { _currentChapterIndex.value },
        // Continuous layout is the windowed layout: chapters load as one
        // scrolling document so they flow instead of swapping per chapter.
        windowMode = { effective().layoutMode.normalized == com.folio.reader.settings.LayoutMode.CONTINUOUS }
    )

    val book: Flow<Book?> = _book
    val chapters: Flow<List<Chapter>> = _chapters
    val currentChapter: Flow<Chapter?> = combine(_chapters, _currentChapterIndex) { chapters, index ->
        chapters.getOrNull(index)
    }
    val currentChapterIndex: Flow<Int> = _currentChapterIndex
    val position: Flow<ReadingPosition?> = _position
    val session: Flow<ReadingSession?> = _session
    val settings: Flow<ReaderSettings> = _settings
    val bookSettings: Flow<BookReaderSettings?> = _bookSettings
    val effectiveSettings: Flow<ReaderSettings> = combine(_settings, _bookSettings) { global, book ->
        book?.toReaderSettings(global) ?: global
    }
    val bookmarks: Flow<List<Bookmark>> = annotations.bookmarks
    val highlights: Flow<List<Highlight>> = annotations.highlights
    val notes: Flow<List<Note>> = annotations.notes
    val showControls: Flow<Boolean> = _showControls
    val showToc: Flow<Boolean> = _showToc
    val showAnnotations: Flow<Boolean> = _showAnnotations
    val showEchoes: Flow<Boolean> = _showEchoes
    val echoes: Flow<EchoesState> = _echoes
    /**
     * Declared [StateFlow], not the erased `Flow`, so callers read the current
     * value synchronously and cannot be handed an `initial` that disagrees with
     * it. `collectAsState` resolves on the *declared* type: typed as `Flow`, the
     * route had to pass `initial = …`, and it passed `true` for a state whose
     * real value was `false` — the contradiction behind the "This page is empty."
     * flash. As `StateFlow` the no-`initial` overload is selected and the first
     * frame paints the truth.
     */
    val chapterHtml: kotlinx.coroutines.flow.StateFlow<String> = _chapterHtml
    val chapterChip: Flow<String?> = _chapterChip
    val isLoadingContent: kotlinx.coroutines.flow.StateFlow<Boolean> = _isLoadingContent
    val linkClickResult: Flow<LinkClickResult?> = links.linkClickResult

    // Continuous-mode chapter window: the chapters on screen together, the range
    // they span, and the pending DOM mutation the surface still has to apply.
    val windowSections: kotlinx.coroutines.flow.StateFlow<List<com.folio.reader.ui.render.ReaderSection>> =
        contentLoader.windowSections
    val windowRange: kotlinx.coroutines.flow.StateFlow<IntRange?> = contentLoader.windowRange
    val windowOp: kotlinx.coroutines.flow.StateFlow<com.folio.reader.ui.render.WindowOp?> = contentLoader.windowOp

    /** The window as last rebuilt — the document the surface renders (extensions mutate it in place). */
    val windowLoad: kotlinx.coroutines.flow.StateFlow<List<com.folio.reader.ui.render.ReaderSection>> =
        contentLoader.windowLoad

    fun openBook(bookId: String, deviceId: String, initialSettings: ReaderSettings = ReaderSettings(), startChapterOverride: Int? = null) {
        this.deviceId = deviceId
        currentBookId = bookId
        closeStarted = false
        _settings.value = initialSettings
        // Per-book isolation: start from a clean overlay so nothing from the
        // previously opened book leaks into this one; this book's stored
        // overlay (if any) is layered back on below.
        _bookSettings.value = null

        viewModelScope.launch {
            // Load settings + book
            runCatching { settingsRepository.getGlobalSettings() }.getOrNull()?.let { _settings.value = it }
            val loadedBook = bookRepository.getBook(bookId)
            _book.value = loadedBook

            // Load chapters persisted at import time
            val chapters = bookRepository.getChaptersForBook(bookId)
            _chapters.value = chapters

            val startIndex = positionStore.restorePosition(bookId, deviceId, chapters, startChapterOverride, loadedBook)

            // This book's own settings. A book that doesn't have any yet gets the
            // current global defaults snapshotted onto it, so from then on it owns
            // its settings and later changes to the defaults don't leak in — except
            // the Main Settings → Formatting fields (alignment, formatting mode,
            // hyphenation), which no in-reader control can change and therefore
            // always follow the global defaults (see ReaderSettings.toBookSettings).
            // These feed the layout the loader uses, so resolve them before the load;
            // persisting a fresh snapshot is a write and not needed for first paint.
            val stored = runCatching { settingsRepository.getBookSettings(bookId) }.getOrNull()
            val snapshotToPersist = if (stored != null) {
                _bookSettings.value = stored
                null
            } else {
                val snapshot = _settings.value.toBookSettings()
                _bookSettings.value = snapshot
                snapshot
            }

            // First paint needs only settings + chapters + position. The session bookkeeping, the
            // recent-sessions read (end-of-chapter pace chip), the markOpened write, the snapshot
            // persist and annotation collection are not on that path, so run them alongside the
            // load instead of gating the chapter behind ~4 serialized DB round-trips.
            launch {
                sessionTracker.openSession(bookId, deviceId, chapters, startIndex, _position.value)
                recentSessions = runCatching { sessionRepository.getSessionsForBook(bookId).first() }
                    .getOrNull() ?: emptyList()
                loadedBook?.let { runCatching { bookRepository.markOpened(it.id) } }
                if (snapshotToPersist != null) {
                    runCatching { settingsRepository.saveBookSettings(bookId, snapshotToPersist) }
                }
                annotations.startCollecting(bookId)
            }

            // Content of the initial chapter
            loadChapterHtml()
        }
    }

    suspend fun loadChapterHtml() = contentLoader.loadChapterHtml()

    fun reloadChapter() = contentLoader.reloadChapter()

    fun updateScrollProgress(scrollFraction: Float, characterOffsetEstimate: Int = 0) =
        positionStore.updateScrollProgress(scrollFraction, characterOffsetEstimate)

    fun updatePosition(newPosition: ReadingPosition) = positionStore.updatePosition(newPosition)

    fun flushProgress() = positionStore.flushProgress()

    fun turnPage(forward: Boolean) {
        if (forward) nextChapter() else previousChapter()
    }

    /**
     * Moves to [index] and sets the fraction the new chapter should open at, both
     * synchronously. The browser surface reads [position]'s offset the moment the new
     * chapter's HTML arrives, so writing it after the load starts races it and can
     * render the chapter at the wrong place — hopping back then landed at the top of
     * the previous chapter instead of the end the reader came from.
     */
    private fun setChapter(index: Int, resumeFraction: Double) {
        // The interval up to a chapter turn is reading time; bank it before the
        // position is repointed and flushed.
        sessionTracker.markReadingActivity()
        // Save where the reader really was before the position is repointed.
        positionStore.flushProgress()
        sessionTracker.chapterEndGuard.reset()
        sessionTracker.chapterStartGuard.reset()
        _currentChapterIndex.value = index.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value)
        _position.value = _position.value?.copy(
            chapterId = chapter?.id ?: "",
            spineIndex = chapter?.spineIndex ?: 0,
            chapterProgress = resumeFraction,
            scrollOffset = resumeFraction
        )
        sessionTracker.markChapterEntry(_position.value?.normalizedProgress ?: 0.0)
        // A windowed jump to a chapter already on screen reloads nothing: the
        // document holds it and the jump's seek scrolls to the target.
        val range = contentLoader.windowRange.value
        if (range != null && range.contains(_currentChapterIndex.value)) return
        viewModelScope.launch { loadChapterHtml() }
    }

    fun nextChapter() = setChapter(_currentChapterIndex.value + 1, 0.0)

    /**
     * A normal chapter change opens at the top; pushing back past the top resumes at
     * the end, which is where the reader came from.
     */
    fun previousChapter(openAtEnd: Boolean = false) =
        setChapter(_currentChapterIndex.value - 1, if (openAtEnd) 1.0 else 0.0)

    fun goToChapter(index: Int) = setChapter(index, 0.0)

    /**
     * Backwards counterpart of [onChapterEnd]: the reader kept pushing up at the top
     * of a chapter, so continue into the previous one. At most once per chapter.
     * In a window the prepend path supplies the chapters above, so the edge only
     * matters at the very start of the book.
     */
    fun onChapterStart() {
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        if (_currentChapterIndex.value <= 0) return
        val range = contentLoader.windowRange.value
        if (range != null && range.first > 1) return
        if (!sessionTracker.chapterStartGuard.accept(chapter.id)) return
        previousChapter(openAtEnd = true)
    }

    /**
     * Advances at most once for a chapter, despite repeated browser scroll events.
     * In a window the scroll itself moves chapters — a bottom edge can only mean
     * the extension could not add more, i.e. the book ended; mid-book edges are
     * stray bottom-outs while an append is in flight and are ignored.
     */
    fun onChapterEnd() {
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        val range = contentLoader.windowRange.value
        if (range != null) {
            if (range.last < _chapters.value.lastIndex) return
            if (!sessionTracker.chapterEndGuard.accept(chapter.id)) return
            val share = sessionTracker.chapterShare(_position.value?.normalizedProgress ?: 0.0)
            _chapterChip.value = buildChapterChip(_currentChapterIndex.value, share.first, share.second)
            return
        }
        if (!sessionTracker.chapterEndGuard.accept(chapter.id)) return
        val finishedIndex = _currentChapterIndex.value
        val share = sessionTracker.chapterShare(_position.value?.normalizedProgress ?: 0.0)
        _chapterChip.value = buildChapterChip(finishedIndex, share.first, share.second)
        if (finishedIndex < _chapters.value.lastIndex) nextChapter()
    }

    /**
     * The visible section moved with the scroll — the continuous-mode chapter
     * change. Repoints the position (and banks the finished chapter's §5.3 chip
     * when reading forward) without reloading anything: the chapter is already
     * on screen.
     */
    fun onVisibleSection(spineIndex: Int) {
        val chaptersList = _chapters.value
        if (chaptersList.isEmpty()) return
        val index = chaptersList.indexOfFirst { it.spineIndex == spineIndex }
        if (index < 0 || index == _currentChapterIndex.value) return
        val range = contentLoader.windowRange.value ?: return
        if (!range.contains(index)) return
        sessionTracker.markReadingActivity()
        positionStore.flushProgress()
        if (index > _currentChapterIndex.value) {
            val finishedIndex = _currentChapterIndex.value
            val finished = chaptersList.getOrNull(finishedIndex)
            if (finished != null && sessionTracker.chapterEndGuard.accept(finished.id)) {
                val share = sessionTracker.chapterShare(_position.value?.normalizedProgress ?: 0.0)
                _chapterChip.value = buildChapterChip(finishedIndex, share.first, share.second)
            }
        }
        _currentChapterIndex.value = index
        val chapter = chaptersList[index]
        _position.value = _position.value?.copy(
            chapterId = chapter.id,
            spineIndex = chapter.spineIndex,
            chapterProgress = 0.0,
            scrollOffset = 0.0
        )
        sessionTracker.markChapterEntry(0.0)
    }

    /** The engine hit a window edge: grow the window that way. */
    fun extendWindow(forward: Boolean) {
        // One extension at a time: an op that has not been applied on screen yet
        // must not be overwritten — StateFlow conflates, and a lost append leaves
        // a permanent gap between sections.
        if (contentLoader.hasPendingWindowOp()) return
        viewModelScope.launch { contentLoader.extendWindow(forward) }
    }

    /** Surfaces acknowledge each window mutation they applied. */
    fun onWindowOpApplied(nonce: Long) = contentLoader.onWindowOpApplied(nonce)

    fun dismissChapterChip() {
        _chapterChip.value = null
    }

    /** §5.3 end-of-chapter summary; null when there is nothing honest to claim. */
    private fun buildChapterChip(finishedIndex: Int, chapterMs: Long, chapterWords: Long): String? {
        if (chapterMs < 30_000L) return null
        val minutesLabel = if (chapterMs >= 60_000L) "${chapterMs / 60_000L} min" else "<1 min"
        val base = "Chapter ${finishedIndex + 1} · $minutesLabel"
        val totalWords = _book.value?.totalWords ?: 0L
        val comparison = paceComparison(chapterMs, chapterWords, totalWords) ?: return base
        return "$base · $comparison"
    }

    /**
     * Compares this chapter's minutes per 1000 words against the trailing 7-day pace
     * on the same book; ±10% counts as usual. A too-thin week shows no comparison.
     */
    private fun paceComparison(chapterMs: Long, chapterWords: Long, totalWords: Long): String? {
        if (totalWords <= 0L || chapterWords <= 0L) return null
        val weekAgo = Clock.System.now() - 7.days
        val week = recentSessions.filter { it.startedAt >= weekAgo }
        val weekMs = week.sumOf { it.durationMs }
        val weekWords = week.sumOf { it.wordsRead }
        if (weekMs <= 0L || weekWords < 700L) return null
        val chapterRate = (chapterMs / 60_000.0) / (chapterWords / 1000.0)
        val usualRate = (weekMs / 60_000.0) / (weekWords / 1000.0)
        return when {
            chapterRate < usualRate * 0.9 -> "a little faster than usual"
            chapterRate > usualRate * 1.1 -> "a little slower than usual"
            else -> "about your usual pace"
        }
    }

    fun toggleControls() {
        _showControls.value = !_showControls.value
    }

    fun hideControls() {
        _showControls.value = false
    }

    fun showControlsFn() {
        _showControls.value = true
    }

    fun toggleToc() {
        val next = !_showToc.value
        _showToc.value = next
        if (next) { _showAnnotations.value = false; _showEchoes.value = false }
    }

    fun toggleAnnotations() {
        val next = !_showAnnotations.value
        _showAnnotations.value = next
        if (next) { _showToc.value = false; _showEchoes.value = false }
    }

    /** Closes the Echoes panel without discarding its results, so a reopen is instant. */
    fun closeEchoes() {
        _showEchoes.value = false
    }

    /**
     * Warms the embedder session and resident index in the background so the first Echoes tap is
     * near-instant instead of paying for a cold ONNX session *and* an index build at once. Fired
     * lazily on the reader's first text selection, so warming overlaps the beat before the reader
     * decides to tap. Guarded to run once per opened book.
     */
    fun prewarmEchoes() {
        val repo = discoveryRepository ?: return
        if (echoesWarmed) return
        echoesWarmed = true
        viewModelScope.launch { runCatching { repo.warm() } }
    }

    /**
     * Opens the Echoes side panel for [selectedText] and launches the cross-book lookup.
     *
     * Selection-driven and explicit — nothing embeds while the reader merely turns pages. The
     * open closes the other panels (they are mutually exclusive), runs the query through the
     * shared discovery engine off the main thread, and exposes Loading → Results/Empty. A blank
     * selection or a missing engine short-circuits to Empty rather than spinning.
     */
    fun openEchoes(selectedText: String, excludeChunkId: String? = null) {
        _showToc.value = false
        _showAnnotations.value = false
        _showEchoes.value = true
        val repo = discoveryRepository
        val bookId = currentBookId
        val text = selectedText.trim()
        if (repo == null || bookId == null || text.isBlank()) {
            _echoes.value = EchoesState.Empty
            return
        }
        val request = ++echoRequest
        _echoes.value = EchoesState.Loading
        viewModelScope.launch {
            val result = runCatching {
                repo.echoes(selectedText = text, currentBookId = bookId, excludeChunkId = excludeChunkId)
            }.getOrDefault(emptyList())
            // Drop a result the reader has already superseded with a newer selection.
            if (request != echoRequest) return@launch
            _echoes.value = if (result.isEmpty()) EchoesState.Empty else EchoesState.Results(result)
        }
    }

    fun addBookmark(label: String? = null) = annotations.addBookmark(label)

    fun toggleBookmark() = annotations.toggleBookmark()

    fun removeBookmark(bookmarkId: String) = annotations.removeBookmark(bookmarkId)

    fun addHighlight(
        startLocator: String,
        endLocator: String,
        selectedText: String,
        color: com.folio.reader.model.HighlightColor = com.folio.reader.model.HighlightColor.YELLOW,
        customColor: Int? = annotations.defaultHighlightColor()
    ) = annotations.addHighlight(startLocator, endLocator, selectedText, color, customColor)

    fun removeHighlight(highlightId: String) = annotations.removeHighlight(highlightId)

    fun addNote(content: String, type: com.folio.reader.model.NoteType = com.folio.reader.model.NoteType.GENERAL, highlightId: String? = null) =
        annotations.addNote(content, type, highlightId)

    fun noteFor(highlight: Highlight): Note? = annotations.noteFor(highlight)

    fun setHighlightNote(highlightId: String, content: String) = annotations.setHighlightNote(highlightId, content)

    fun removeNote(noteId: String) = annotations.removeNote(noteId)

    /** Ends the active session with its measured reading time, then syncs the position. */
    fun closeBook(onDone: () -> Unit = {}) {
        // Back navigation and the composition's dispose both call closeBook; only
        // the first may end the session and trigger the close-time sync.
        if (closeStarted) {
            onDone()
            return
        }
        closeStarted = true
        val session = _session.value
        val position = _position.value
        val bookId = currentBookId

        // Release the Echoes embedder's ~50 MB native session on the way out; the small resident
        // index stays for a fast reopen. Only meaningful if Echoes was actually warmed this visit.
        if (echoesWarmed) viewModelScope.launch { runCatching { discoveryRepository?.releaseEmbedder() } }

        viewModelScope.launch {
            runCatching {
                sessionTracker.endActiveSession(session, position)

                if (bookId != null && position != null) {
                    positionStore.persistOnClose(bookId, position)
                }
            }.onFailure { e ->
                println("❌ Error closing book: ${e.message}")
            }
            onDone()
        }
    }

    /** This book's own settings — what the reader paints. */
    fun effective(): ReaderSettings =
        _bookSettings.value?.toReaderSettings(_settings.value) ?: _settings.value

    /** Global defaults: applied to books as they join the library; edited from the app's Settings screen. */
    fun global(): ReaderSettings = _settings.value

    /**
     * In-reader setting changes belong to this book alone. The book owns a full
     * snapshot of its settings (seeded from the global defaults when it joined
     * the library), so a change here rewrites that snapshot and never touches
     * the defaults or any other book.
     */
    fun updateSettings(newSettings: ReaderSettings) {
        val eff = effective()
        if (newSettings == eff) return
        val snapshot = newSettings.toBookSettings()
        _bookSettings.value = snapshot
        currentBookId?.let { id ->
            viewModelScope.launch { runCatching { settingsRepository.saveBookSettings(id, snapshot) } }
        }
    }

    /**
     * Global write: stores the new global defaults and drops this book's
     * override for the fields being changed, so it follows the new defaults.
     * Carries both the panel's "All books" scope and the global-only comfort
     * rows (Eye protection), which have no per-book counterpart.
     *
     * The panel paints effective (book-merged) values, so "the fields being
     * changed" is measured against those — diffing against the globals instead
     * would promote this book's untouched overrides into the defaults every
     * other book follows (the "All books" leak). The write itself is
     * field-scoped, so no other global field can be clobbered either.
     *
     * The diff is serializer-driven ([diffFields]), not the per-book override
     * vocabulary ([changedFields]). The panel also carries controls that live
     * only in the global row — Eye protection and its warmth — and
     * `changedFields` does not list those, so a write confined to one of them
     * produced an EMPTY change set: `withFieldsFrom(emptySet(), …)` returns its
     * receiver untouched, the merge below was a no-op too, and the switch
     * snapped straight back. That is the eye-comfort toggle that "did not
     * toggle". A hand-listed vocabulary cannot be the diff for a row it does
     * not fully describe.
     */
    fun updateGlobalSettings(newSettings: ReaderSettings) {
        val changed = effective().diffFields(newSettings)
        if (changed.isEmpty()) return
        _settings.value = _settings.value.withFieldsFrom(changed, newSettings)
        viewModelScope.launch {
            runCatching { settingsRepository.mergeGlobalSettings { it.withFieldsFrom(changed, newSettings) } }
        }
        // Only a field this book can override has an override to drop. A
        // global-only name in `changed` (Eye protection) leaves the snapshot
        // identical, and an identical snapshot is not worth a write.
        val snapshot = _bookSettings.value ?: return
        val cleared = snapshot.clearing(changed)
        if (cleared == snapshot) return
        _bookSettings.value = cleared
        currentBookId?.let { id ->
            viewModelScope.launch { runCatching { settingsRepository.saveBookSettings(id, cleared) } }
        }
    }

    /** "Reset this book to defaults": drops every override on the open book. */
    fun resetBookToDefaults() {
        val snapshot = _bookSettings.value ?: return
        val cleared = snapshot.clearing(snapshot.overriddenFields(_settings.value))
        _bookSettings.value = cleared
        currentBookId?.let { id ->
            viewModelScope.launch { runCatching { settingsRepository.saveBookSettings(id, cleared) } }
        }
    }

    fun handleLinkClick(href: String) = links.handleLinkClick(href)

    fun clearLinkClickResult() = links.clearLinkClickResult()
}

data class SearchResult(
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val title: String,
    val context: String
)

/**
 * What the Echoes panel is showing.
 *
 * The states are separated rather than folded into a nullable list because each is a different
 * thing to say: [Loading] is a spinner, [Empty] is the honest "No echoes found." (a real result,
 * not an error), and [Results] is the land-fragment cards. [Idle] is "never opened".
 */
sealed interface EchoesState {
    data object Idle : EchoesState
    data object Loading : EchoesState
    data class Results(val hits: List<com.folio.reader.ml.EchoHit>) : EchoesState
    data object Empty : EchoesState
}
