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
import com.folio.reader.settings.changedFields
import com.folio.reader.settings.clearing
import com.folio.reader.settings.overriddenFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.folio.reader.ui.render.LinkClickResult

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
    private val revisitRepository: com.folio.reader.database.RevisitRepository? = null
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
    private val _chapterHtml = MutableStateFlow("")
    private val _isLoadingContent = MutableStateFlow(false)
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: Flow<String?> = _loadError

    private var currentBookId: String? = null
    private var deviceId: String = ""
    private var closeStarted = false

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
        currentChapterIndex = { _currentChapterIndex.value }
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
    val chapterHtml: Flow<String> = _chapterHtml
    val isLoadingContent: Flow<Boolean> = _isLoadingContent
    val linkClickResult: Flow<LinkClickResult?> = links.linkClickResult

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

            // Load or create session; the engagement clock starts from zero every open.
            sessionTracker.openSession(bookId, deviceId, chapters, startIndex, _position.value)

            // Mark opened
            loadedBook?.let { runCatching { bookRepository.markOpened(it.id) } }

            // Annotations
            annotations.startCollecting(bookId)

            // This book's own settings. A book that doesn't have any yet gets the
            // current global defaults snapshotted onto it, so from then on it owns
            // its settings and later changes to the defaults don't leak in — except
            // the Main Settings → Formatting fields (alignment, formatting mode,
            // hyphenation), which no in-reader control can change and therefore
            // always follow the global defaults (see ReaderSettings.toBookSettings).
            val stored = runCatching { settingsRepository.getBookSettings(bookId) }.getOrNull()
            if (stored != null) {
                _bookSettings.value = stored
            } else {
                val snapshot = _settings.value.toBookSettings()
                _bookSettings.value = snapshot
                runCatching { settingsRepository.saveBookSettings(bookId, snapshot) }
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
     */
    fun onChapterStart() {
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        if (_currentChapterIndex.value <= 0) return
        if (!sessionTracker.chapterStartGuard.accept(chapter.id)) return
        previousChapter(openAtEnd = true)
    }

    /** Advances at most once for a chapter, despite repeated browser scroll events. */
    fun onChapterEnd() {
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        if (!sessionTracker.chapterEndGuard.accept(chapter.id)) return
        if (_currentChapterIndex.value < _chapters.value.lastIndex) nextChapter()
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
        if (next) _showAnnotations.value = false
    }

    fun toggleAnnotations() {
        val next = !_showAnnotations.value
        _showAnnotations.value = next
        if (next) _showToc.value = false
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
     * "All books" write: stores the new global defaults and drops this book's
     * override for the fields being changed, so it follows the new defaults.
     */
    fun updateGlobalSettings(newSettings: ReaderSettings) {
        val changed = _settings.value.changedFields(newSettings)
        _settings.value = newSettings
        viewModelScope.launch { runCatching { settingsRepository.saveGlobalSettings(newSettings) } }
        val snapshot = _bookSettings.value ?: return
        if (changed.isEmpty()) return
        val cleared = snapshot.clearing(changed)
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
