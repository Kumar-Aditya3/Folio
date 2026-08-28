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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.folio.reader.ui.render.LinkClickResult
import kotlinx.datetime.Clock

enum class LayoutMode {
        CONTINUOUS,
        PAGINATED,
        TWO_COLUMN,
        FOCUS
    }

    /** Small stateful guard shared by browser and Compose scroll surfaces. */
    internal class ChapterEndGuard {
        private var handledChapter: String? = null

        fun accept(chapterId: String): Boolean {
            if (handledChapter == chapterId) return false
            handledChapter = chapterId
            return true
        }

        fun reset() {
            handledChapter = null
        }
    }

class ReaderViewModel(
    private val bookRepository: BookRepository,
    private val positionRepository: ReadingPositionRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val chapterContentProvider: suspend (bookId: String, chapterHref: String) -> String,
    private val syncEngine: com.folio.reader.sync.SyncEngine? = null
) {
    enum class LayoutMode {
        CONTINUOUS,
        PAGINATED,
        TWO_COLUMN,
        FOCUS
    }

    private val _book = MutableStateFlow<Book?>(null)
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    private val _currentChapterIndex = MutableStateFlow(0)
    private val _position = MutableStateFlow<ReadingPosition?>(null)
    private val _session = MutableStateFlow<ReadingSession?>(null)
    private val _settings = MutableStateFlow(ReaderSettings())
    private val _bookSettings = MutableStateFlow<BookReaderSettings?>(null)
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _showControls = MutableStateFlow(true)
    private val _showToc = MutableStateFlow(false)
    private val _showAnnotations = MutableStateFlow(false)
    private val _chapterHtml = MutableStateFlow("")
    private val _isLoadingContent = MutableStateFlow(false)
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: Flow<String?> = _loadError
    private val _layoutMode = MutableStateFlow(LayoutMode.CONTINUOUS)
    private val _linkClickResult = MutableStateFlow<LinkClickResult?>(null)
    val linkClickResult: Flow<LinkClickResult?> = _linkClickResult

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
    val bookmarks: Flow<List<Bookmark>> = _bookmarks
    val highlights: Flow<List<Highlight>> = _highlights
    val notes: Flow<List<Note>> = _notes
    val showControls: Flow<Boolean> = _showControls
    val showToc: Flow<Boolean> = _showToc
    val showAnnotations: Flow<Boolean> = _showAnnotations
    val chapterHtml: Flow<String> = _chapterHtml
    val isLoadingContent: Flow<Boolean> = _isLoadingContent

    private var currentBookId: String? = null
    private var deviceId: String = ""
    /** Session words read, accumulated as progress advances. */
    private var sessionWordsBaseProgress: Double = 0.0
    private val chapterEndGuard = ChapterEndGuard()

    /** Access-order LRU of loaded chapter HTML keyed "bookId:chapterHref"; bounded to [MAX_HTML_CACHE] entries. */
    private val htmlCache = LinkedHashMap<String, String>(16, 0.75f, true)

    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun openBook(bookId: String, deviceId: String, initialSettings: ReaderSettings = ReaderSettings(), startChapterOverride: Int? = null) {
        this.deviceId = deviceId
        currentBookId = bookId
        _settings.value = initialSettings

        viewModelScope.launch {
            // Load settings + book
            runCatching { settingsRepository.getGlobalSettings() }.getOrNull()?.let { _settings.value = it }
            val loadedBook = bookRepository.getBook(bookId)
            _book.value = loadedBook

            // Load chapters persisted at import time
            val chapters = bookRepository.getChaptersForBook(bookId)
            _chapters.value = chapters

            // Restore last known position across devices (preferring most recently updated), falling back to this device
            val saved = positionRepository.getLatestPositionAcrossDevices(bookId)
                ?: positionRepository.getPosition(bookId, deviceId)
            val startIndex = startChapterOverride
                ?: saved?.let { pos ->
                    chapters.indexOfFirst { it.spineIndex == pos.spineIndex }.takeIf { it >= 0 }
                }
                ?: 0
            _currentChapterIndex.value = startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            sessionWordsBaseProgress = saved?.normalizedProgress ?: 0.0

            // Initialize position for this device using saved position data. The
            // saved scroll offset only applies when the saved position is actually
            // inside the chapter being opened (search jumps / overrides may target
            // a different chapter).
            val startChapter = chapters.getOrNull(_currentChapterIndex.value)
            val savedForStartChapter = saved?.takeIf { startChapter != null && it.spineIndex == startChapter.spineIndex }
            _position.value = savedForStartChapter?.copy(deviceId = deviceId) ?: ReadingPosition(
                bookId = bookId,
                deviceId = deviceId,
                chapterId = startChapter?.id ?: "",
                spineIndex = startChapter?.spineIndex ?: 0,
                contentLocator = "",
                normalizedProgress = saved?.normalizedProgress ?: 0.0,
                chapterProgress = 0.0,
                scrollOffset = 0.0
            )
            println("📚 Position initialized: ${if (saved != null) "restored from DB" else "created new"}, chapter=${_position.value?.chapterProgress}, book=${_position.value?.normalizedProgress}")

            // Update book's normalized progress from saved position
            saved?.let { pos ->
                loadedBook?.updateProgress(pos.normalizedProgress)
                bookRepository.updateNormalizedProgress(bookId, pos.normalizedProgress)
                _book.value = loadedBook
            }

            // Load or create session
            val activeSession = runCatching { sessionRepository.getActiveSession(bookId) }.getOrNull()
            if (activeSession != null && activeSession.isActive) {
                _session.value = activeSession
            } else {
                val startPosition = _position.value ?: ReadingPosition(
                    bookId = bookId,
                    deviceId = deviceId,
                    chapterId = chapters.getOrNull(startIndex)?.id ?: "",
                    spineIndex = chapters.getOrNull(startIndex)?.spineIndex ?: 0,
                    contentLocator = ""
                )
                val newSession = ReadingSession(
                    id = java.util.UUID.randomUUID().toString(),
                    bookId = bookId,
                    cycleId = null,
                    deviceId = deviceId,
                    startedAt = Clock.System.now(),
                    startPosition = startPosition,
                    startProgress = startPosition.normalizedProgress
                )
                runCatching { sessionRepository.insertSession(newSession) }
                _session.value = newSession
                sessionWordsBaseProgress = startPosition.normalizedProgress
            }

            // Mark opened
            loadedBook?.let { runCatching { bookRepository.markOpened(it.id) } }

            // Annotations
            launch {
                bookmarkRepository.getBookmarksForBook(bookId).collect { _bookmarks.value = it }
            }
            launch {
                highlightRepository.getHighlightsForBook(bookId).collect { _highlights.value = it }
            }
            launch {
                noteRepository.getNotesForBook(bookId).collect { _notes.value = it }
            }

            // Book-specific overrides
            settingsRepository.getBookSettings(bookId)?.let { _bookSettings.value = it }

            // Focus mode is distraction-free: open with the chrome hidden.
            val effective = _bookSettings.value?.toReaderSettings(_settings.value) ?: _settings.value
            if (effective.layoutMode == com.folio.reader.settings.LayoutMode.FOCUS) {
                _showControls.value = false
            }

            // Content of the initial chapter
            loadChapterHtml()
        }
    }

    suspend fun loadChapterHtml() {
        val bookId = currentBookId ?: return
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        _isLoadingContent.value = true
        _loadError.value = null
        try {
            val key = "$bookId:${chapter.href}"
            val html = htmlCache[key]
                ?: chapterContentProvider(bookId, chapter.href).also { loaded ->
                    if (loaded.isNotBlank()) {
                        htmlCache[key] = loaded
                        while (htmlCache.size > MAX_HTML_CACHE) {
                            val eldest = htmlCache.entries.iterator()
                            if (!eldest.hasNext()) break
                            eldest.next()
                            eldest.remove()
                        }
                    }
                }
            _chapterHtml.value = html
            if (html.isBlank()) {
                _loadError.value = "Empty chapter (href=${chapter.href}) — file may be missing or parse failed"
            }
        } catch (e: Exception) {
            _chapterHtml.value = ""
            _loadError.value = e.message ?: e::class.simpleName ?: "Unknown error"
            e.printStackTrace()
        } finally {
            _isLoadingContent.value = false
        }
    }

    fun reloadChapter() {
        viewModelScope.launch { loadChapterHtml() }
    }

    fun updateScrollProgress(scrollFraction: Float, characterOffsetEstimate: Int = 0) {
        val current = _position.value ?: return
        val chapters = _chapters.value
        val chapter = chapters.getOrNull(_currentChapterIndex.value)
        val totalWords = _book.value?.totalWords ?: 0L

        // Chapter progress from scroll fraction
        val chapterProgress = scrollFraction.toDouble().coerceIn(0.0, 1.0)

        // Normalized book progress via cumulative word counts across chapters
        val normalized = if (chapters.isNotEmpty() && totalWords > 0) {
            val beforeWords = chapters.take(_currentChapterIndex.value).sumOf { it.wordCount }
            val chapterWords = chapter?.wordCount ?: 0L
            ((beforeWords + chapterWords * chapterProgress).toDouble() / totalWords).coerceIn(0.0, 1.0)
        } else {
            current.normalizedProgress
        }

        println("📊 Progress Update: scroll=$scrollFraction, chapter=${(chapterProgress * 100).toInt()}%, book=${(normalized * 100).toInt()}%, totalWords=$totalWords, chapterIdx=${_currentChapterIndex.value}")

        val updated = current.withProgress(
            newNormalizedProgress = normalized,
            newChapterProgress = chapterProgress,
            newScrollOffset = scrollFraction.toDouble()
        )
        _position.value = updated

        // Track session words read
        val deltaProgress = (normalized - sessionWordsBaseProgress).coerceAtLeast(0.0)
        val wordsSoFar = (deltaProgress * totalWords).toLong()
        _session.value = _session.value?.copy(
            endPosition = updated,
            endProgress = normalized,
            wordsRead = wordsSoFar
        )

        // Persist to database immediately and update book progress
        viewModelScope.launch {
            runCatching {
                positionRepository.upsertPosition(updated)
                println("💾 Progress saved locally: chapter=${(chapterProgress * 100).toInt()}%, book=${(normalized * 100).toInt()}%")

                // Update the book's normalized progress
                val bookId = currentBookId
                if (bookId != null) {
                    bookRepository.updateNormalizedProgress(bookId, normalized)
                    // Update local book object to reflect new progress
                    _book.value?.let { book ->
                        book.updateProgress(normalized)
                    }
                }
            }.onFailure {
                println("❌ Failed to save progress: ${it.message}")
            }
        }
    }

    fun updatePosition(newPosition: ReadingPosition) {
        _position.value = newPosition
        viewModelScope.launch {
            runCatching { positionRepository.upsertPosition(newPosition) }
        }
    }

    fun turnPage(forward: Boolean) {
        if (forward) nextChapter() else previousChapter()
    }

    fun nextChapter() {
        _currentChapterIndex.update { (it + 1).coerceAtMost(_chapters.value.size - 1) }
        viewModelScope.launch {
            loadChapterHtml()
            // Update position's chapterId when changing chapters so restoration works.
            // The new chapter starts at its top — never inherit the old scroll offset.
            val newChapter = _chapters.value.getOrNull(_currentChapterIndex.value)
            _position.value = _position.value?.copy(
                chapterId = newChapter?.id ?: "",
                spineIndex = newChapter?.spineIndex ?: 0,
                chapterProgress = 0.0,
                scrollOffset = 0.0
            )
        }
    }

    fun previousChapter() {
        chapterEndGuard.reset()
        _currentChapterIndex.update { (it - 1).coerceAtLeast(0) }
        viewModelScope.launch {
            loadChapterHtml()
            // Update position's chapterId when changing chapters so restoration works.
            // The new chapter starts at its top — never inherit the old scroll offset.
            val newChapter = _chapters.value.getOrNull(_currentChapterIndex.value)
            _position.value = _position.value?.copy(
                chapterId = newChapter?.id ?: "",
                spineIndex = newChapter?.spineIndex ?: 0,
                chapterProgress = 0.0,
                scrollOffset = 0.0
            )
        }
    }

    fun goToChapter(index: Int) {
        chapterEndGuard.reset()
        _currentChapterIndex.value = index.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
        viewModelScope.launch {
            loadChapterHtml()
            // Update position's chapterId when changing chapters so restoration works.
            // The new chapter starts at its top — never inherit the old scroll offset.
            val newChapter = _chapters.value.getOrNull(_currentChapterIndex.value)
            _position.value = _position.value?.copy(
                chapterId = newChapter?.id ?: "",
                spineIndex = newChapter?.spineIndex ?: 0,
                chapterProgress = 0.0,
                scrollOffset = 0.0
            )
        }

    }

    /** Advances at most once for a chapter, despite repeated browser scroll events. */
    fun onChapterEnd() {
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return
        if (!chapterEndGuard.accept(chapter.id)) return
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
        _showToc.value = !_showToc.value
    }

    fun toggleAnnotations() {
        _showAnnotations.value = !_showAnnotations.value
    }

    fun addBookmark(label: String? = null) {
        val position = _position.value ?: return
        val bookId = currentBookId ?: return
        val bookmark = Bookmark(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = position.chapterId,
            spineIndex = position.spineIndex,
            locator = position.contentLocator,
            label = label,
            deviceId = deviceId
        )
        viewModelScope.launch { runCatching { bookmarkRepository.insertBookmark(bookmark) } }
        _bookmarks.value = _bookmarks.value + bookmark
    }

    fun toggleBookmark() {
        val position = _position.value ?: return
        val existing = _bookmarks.value.firstOrNull {
            it.chapterId == position.chapterId && it.locator == position.contentLocator
        }
        if (existing != null) {
            removeBookmark(existing.id)
        } else {
            addBookmark()
        }
    }

    fun removeBookmark(bookmarkId: String) {
        viewModelScope.launch { runCatching { bookmarkRepository.deleteBookmark(bookmarkId) } }
        _bookmarks.value = _bookmarks.value.filterNot { it.id == bookmarkId }
    }

    fun addHighlight(
        startLocator: String,
        endLocator: String,
        selectedText: String,
        color: com.folio.reader.model.HighlightColor = com.folio.reader.model.HighlightColor.YELLOW
    ) {
        val position = _position.value ?: return
        val bookId = currentBookId ?: return
        val highlight = Highlight(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = position.chapterId,
            spineIndex = position.spineIndex,
            startLocator = startLocator,
            endLocator = endLocator,
            selectedText = selectedText,
            color = color,
            deviceId = deviceId
        )
        viewModelScope.launch { runCatching { highlightRepository.insertHighlight(highlight) } }
        _highlights.value = _highlights.value + highlight
    }

    fun removeHighlight(highlightId: String) {
        viewModelScope.launch { runCatching { highlightRepository.deleteHighlight(highlightId) } }
        _highlights.value = _highlights.value.filterNot { it.id == highlightId }
    }

    fun addNote(content: String, type: com.folio.reader.model.NoteType = com.folio.reader.model.NoteType.GENERAL) {
        val position = _position.value
        val bookId = currentBookId ?: return
        val note = Note(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = position?.chapterId,
            spineIndex = position?.spineIndex,
            locator = position?.contentLocator,
            content = content,
            type = type,
            deviceId = deviceId
        )
        viewModelScope.launch { runCatching { noteRepository.insertNote(note) } }
        _notes.value = _notes.value + note
    }

    fun removeNote(noteId: String) {
        viewModelScope.launch { runCatching { noteRepository.deleteNote(noteId) } }
        _notes.value = _notes.value.filterNot { it.id == noteId }
    }

    /** Ends the active session, persisting duration + estimated words read, and triggers cloud sync for reading position on book close. */
    fun closeBook(onDone: () -> Unit = {}) {
        val session = _session.value
        val position = _position.value
        val bookId = currentBookId

        viewModelScope.launch {
            runCatching {
                if (session != null && session.isActive) {
                    val ended = session.end(
                        endPos = position ?: session.startPosition,
                        endProgress = position?.normalizedProgress ?: session.startProgress,
                        wordsRead = session.wordsRead
                    )
                    sessionRepository.updateSession(ended)
                    _session.value = ended
                }

                if (bookId != null && position != null) {
                    positionRepository.upsertPosition(position)
                    bookRepository.updateNormalizedProgress(bookId, position.normalizedProgress)

                    // Enqueue position for cloud sync and trigger sync on book close
                    syncEngine?.let { engine ->
                        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
                        if (settings?.cloudSyncEnabled == true && settings.syncPositions) {
                            engine.enqueuePositionChange(position, com.folio.reader.sync.SyncOperation.UPSERT)
                            engine.triggerSync(immediate = true)
                            println("☁️ Book closed: Reading position queued and cloud sync triggered")
                        }
                    }
                }
            }.onFailure { e ->
                println("❌ Error closing book: ${e.message}")
            }
            onDone()
        }
    }

    fun updateSettings(newSettings: ReaderSettings) {
        _settings.value = newSettings
        viewModelScope.launch { runCatching { settingsRepository.saveGlobalSettings(newSettings) } }
    }

    fun updateBookSettings(newSettings: BookReaderSettings) {
        _bookSettings.value = newSettings
        currentBookId?.let { bookId ->
            viewModelScope.launch { runCatching { settingsRepository.saveBookSettings(bookId, newSettings) } }
        }
    }

    fun handleLinkClick(href: String) {
        val bookId = currentBookId ?: return
        val chapters = _chapters.value

        // External link
        if (href.startsWith("http://") || href.startsWith("https://")) {
            _linkClickResult.value = LinkClickResult.ExternalUrl(href)
            return
        }

        // Internal link - resolve relative to current chapter
        val currentChapter = chapters.getOrNull(_currentChapterIndex.value)
        val resolvedHref = resolveInternalHref(currentChapter?.href ?: "", href)

        // Try to find the target chapter
        val targetIndex = chapters.indexOfFirst { ch ->
            ch.href == resolvedHref || ch.href.endsWith("/$resolvedHref") || resolvedHref.endsWith("/${ch.href}")
        }

        if (targetIndex >= 0) {
            _linkClickResult.value = LinkClickResult.InternalChapter(targetIndex)
        } else {
            // Might be a footnote or non-chapter content - show inline
            _linkClickResult.value = LinkClickResult.InlineContent(href, resolvedHref)
        }
    }

    fun clearLinkClickResult() {
        _linkClickResult.value = null
    }

    private fun resolveInternalHref(currentHref: String, href: String): String {
        if (href.startsWith("/")) return href.substring(1)
        if (href.contains("://")) return href
        val base = currentHref.substringBeforeLast('/', "")
        return if (base.isNotEmpty()) "$base/$href" else href
    }

    private companion object {
        const val MAX_HTML_CACHE = 12
    }
}

data class SearchResult(
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val title: String,
    val context: String
)
