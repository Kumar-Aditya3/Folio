package com.folio.reader.ui.reader

import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.QuoteRepository
import com.folio.reader.database.RevisitRepository
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.Quote
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.RevisitItem
import com.folio.reader.model.RevisitType
import com.folio.reader.model.locatorsMatch
import com.folio.reader.model.spotLocator
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The open book's bookmarks, highlights and notes: live collections plus the
 * reader's edits to them.
 */
internal class ReaderAnnotations(
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val scope: CoroutineScope,
    private val positionState: MutableStateFlow<ReadingPosition?>,
    private val currentBookId: () -> String?,
    private val deviceId: () -> String,
    private val effectiveSettings: () -> ReaderSettings,
    // Android populates Quotes/Revisit from annotations; desktop passes neither.
    private val quoteRepository: QuoteRepository? = null,
    private val revisitRepository: RevisitRepository? = null
) {
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val bookmarks: Flow<List<Bookmark>> = _bookmarks
    val highlights: Flow<List<Highlight>> = _highlights
    val notes: Flow<List<Note>> = _notes

    fun startCollecting(bookId: String) {
        scope.launch {
            bookmarkRepository.getBookmarksForBook(bookId).collect { _bookmarks.value = it }
        }
        scope.launch {
            highlightRepository.getHighlightsForBook(bookId).collect { _highlights.value = it }
        }
        scope.launch {
            noteRepository.getNotesForBook(bookId).collect { _notes.value = it }
        }
    }

    fun addBookmark(label: String? = null) {
        val position = positionState.value ?: return
        val bookId = currentBookId() ?: return
        val bookmark = Bookmark(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = position.chapterId,
            spineIndex = position.spineIndex,
            locator = position.spotLocator(),
            label = label,
            deviceId = deviceId()
        )
        scope.launch {
            val saved = runCatching { bookmarkRepository.insertBookmark(bookmark) }.isSuccess
            if (saved) {
                // #9: reflect the bookmark in memory only once the write actually persisted.
                _bookmarks.value = _bookmarks.value + bookmark
                runCatching {
                    revisitRepository?.insertRevisitItem(
                        RevisitItem(
                            id = "revisit-b-${bookmark.id}",
                            bookId = bookmark.bookId,
                            chapterId = bookmark.chapterId,
                            type = RevisitType.BOOKMARK,
                            sourceId = bookmark.id,
                            deviceId = bookmark.deviceId
                        )
                    )
                }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed bookmark write; the annotation is silently dropped.
            }
        }
    }

    fun toggleBookmark() {
        val position = positionState.value ?: return
        val locator = position.spotLocator()
        val existing = _bookmarks.value.firstOrNull {
            it.chapterId == position.chapterId && locatorsMatch(it.locator, locator)
        }
        if (existing != null) {
            removeBookmark(existing.id)
        } else {
            addBookmark()
        }
    }

    fun removeBookmark(bookmarkId: String) {
        scope.launch {
            val deleted = runCatching { bookmarkRepository.deleteBookmark(bookmarkId) }.isSuccess
            if (deleted) {
                _bookmarks.value = _bookmarks.value.filterNot { it.id == bookmarkId }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed bookmark delete; the removal is silently reverted on reload.
            }
        }
    }

    fun addHighlight(
        startLocator: String,
        endLocator: String,
        selectedText: String,
        color: com.folio.reader.model.HighlightColor = com.folio.reader.model.HighlightColor.YELLOW,
        customColor: Int? = defaultHighlightColor()
    ) {
        val position = positionState.value ?: return
        val bookId = currentBookId() ?: return
        val highlight = Highlight(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = position.chapterId,
            spineIndex = position.spineIndex,
            startLocator = startLocator,
            endLocator = endLocator,
            selectedText = selectedText,
            color = color,
            customColor = customColor,
            deviceId = deviceId()
        )
        scope.launch {
            val saved = runCatching { highlightRepository.insertHighlight(highlight) }.isSuccess
            if (saved) {
                // #9: reflect the highlight in memory only once the write actually persisted.
                _highlights.value = _highlights.value + highlight
                runCatching {
                    quoteRepository?.insertQuote(
                        Quote(
                            id = "quote-${highlight.id}",
                            bookId = highlight.bookId,
                            chapterId = highlight.chapterId,
                            highlightId = highlight.id,
                            text = highlight.selectedText,
                            deviceId = highlight.deviceId
                        )
                    )
                }
                runCatching {
                    revisitRepository?.insertRevisitItem(
                        RevisitItem(
                            id = "revisit-h-${highlight.id}",
                            bookId = highlight.bookId,
                            chapterId = highlight.chapterId,
                            type = RevisitType.HIGHLIGHT,
                            sourceId = highlight.id,
                            deviceId = highlight.deviceId
                        )
                    )
                }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed highlight write; the annotation is silently dropped.
            }
        }
    }

    /** The reader's chosen slot in the active theme's highlight palette. */
    fun defaultHighlightColor(): Int? {
        val settings = effectiveSettings()
        val theme = settings.customTheme
            ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
        return theme.highlightColors.getOrElse(settings.highlightColorIndex) { theme.highlightColors.firstOrNull() }
    }

    fun removeHighlight(highlightId: String) {
        scope.launch {
            val deleted = runCatching { highlightRepository.deleteHighlight(highlightId) }.isSuccess
            if (deleted) {
                _highlights.value = _highlights.value.filterNot { it.id == highlightId }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed highlight delete; the removal is silently reverted on reload.
            }
        }
    }

    fun addNote(content: String, type: com.folio.reader.model.NoteType = com.folio.reader.model.NoteType.GENERAL, highlightId: String? = null) {
        val position = positionState.value
        val bookId = currentBookId() ?: return
        val highlight = highlightId?.let { id -> _highlights.value.firstOrNull { it.id == id } }
        val note = Note(
            id = java.util.UUID.randomUUID().toString(),
            bookId = bookId,
            // A note on a highlight belongs where the highlight does.
            chapterId = highlight?.chapterId ?: position?.chapterId,
            spineIndex = highlight?.spineIndex ?: position?.spineIndex,
            locator = highlight?.startLocator ?: position?.spotLocator(),
            content = content,
            type = if (highlight != null) com.folio.reader.model.NoteType.HIGHLIGHT_NOTE else type,
            deviceId = deviceId()
        )
        scope.launch {
            val saved = runCatching { noteRepository.insertNote(note) }.isSuccess
            if (saved) {
                // #9: reflect the note in memory only once the write actually persisted.
                _notes.value = _notes.value + note
                if (highlight != null) linkNoteToHighlight(highlight.id, note.id)
                runCatching {
                    revisitRepository?.insertRevisitItem(
                        RevisitItem(
                            id = "revisit-n-${note.id}",
                            bookId = note.bookId,
                            chapterId = note.chapterId ?: "",
                            type = RevisitType.NOTE,
                            sourceId = note.id,
                            deviceId = note.deviceId
                        )
                    )
                }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed note write; the annotation is silently dropped.
            }
        }
    }

    /** Points a highlight at its note; the reverse link is what the lists render on. */
    private fun linkNoteToHighlight(highlightId: String, noteId: String) {
        val existing = _highlights.value.firstOrNull { it.id == highlightId } ?: return
        val updated = existing.withNote(noteId)
        scope.launch {
            val saved = runCatching { highlightRepository.updateHighlight(updated) }.isSuccess
            if (saved) {
                _highlights.value = _highlights.value.map { if (it.id == highlightId) updated else it }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed highlight-note link; the link is silently lost on reload.
            }
        }
    }

    /** The note attached to a highlight, if it has one. */
    fun noteFor(highlight: Highlight): Note? =
        highlight.noteId?.let { id -> _notes.value.firstOrNull { it.id == id && !it.isDeleted } }

    /** Adds or replaces the note on a highlight. */
    fun setHighlightNote(highlightId: String, content: String) {
        val existing = noteFor(_highlights.value.firstOrNull { it.id == highlightId } ?: return)
        if (existing != null) {
            if (content.isBlank()) return
            val updated = existing.copy(content = content, updatedAt = kotlinx.datetime.Clock.System.now())
            scope.launch {
                val saved = runCatching { noteRepository.updateNote(updated) }.isSuccess
                if (saved) {
                    _notes.value = _notes.value.map { if (it.id == updated.id) updated else it }
                } else {
                    // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed note update; the edit is silently reverted on reload.
                }
            }
        } else {
            addNote(content, highlightId = highlightId)
        }
    }

    fun removeNote(noteId: String) {
        scope.launch {
            val deleted = runCatching { noteRepository.deleteNote(noteId) }.isSuccess
            if (deleted) {
                _notes.value = _notes.value.filterNot { it.id == noteId }
            } else {
                // TODO(#9): no error channel in ReaderAnnotations or ReaderViewModel to surface this failed note delete; the removal is silently reverted on reload.
            }
        }
    }
}
