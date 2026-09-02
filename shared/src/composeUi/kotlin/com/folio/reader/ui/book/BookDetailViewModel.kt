package com.folio.reader.ui.book

import com.folio.reader.database.BookRepository
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SeriesRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.UUID

class BookDetailViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val seriesRepository: SeriesRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository
) {
    private val _book = MutableStateFlow<Book?>(null)
    private val _sessions = MutableStateFlow<List<ReadingSession>>(emptyList())
    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _series = MutableStateFlow<Series?>(null)
    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    private val _availableTags = MutableStateFlow<List<Tag>>(emptyList())
    private val _availableSeries = MutableStateFlow<List<Series>>(emptyList())
    private val _availableCollections = MutableStateFlow<List<Collection>>(emptyList())

    val book: Flow<Book?> = _book.asStateFlow()
    val sessions: Flow<List<ReadingSession>> = _sessions.asStateFlow()
    val highlights: Flow<List<Highlight>> = _highlights.asStateFlow()
    val bookmarks: Flow<List<Bookmark>> = _bookmarks.asStateFlow()
    val notes: Flow<List<Note>> = _notes.asStateFlow()
    val series: Flow<Series?> = _series.asStateFlow()
    val collections: Flow<List<Collection>> = _collections.asStateFlow()
    val tags: Flow<List<Tag>> = _tags.asStateFlow()
    val availableTags: Flow<List<Tag>> = _availableTags.asStateFlow()
    val availableSeries: Flow<List<Series>> = _availableSeries.asStateFlow()
    val availableCollections: Flow<List<Collection>> = _availableCollections.asStateFlow()

    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentBookId: String? = null

    fun loadBook(bookId: String) {
        currentBookId = bookId
        viewModelScope.launch {
            val loadedBook = bookRepository.getBook(bookId)
            _book.value = loadedBook

            _series.value = loadedBook?.seriesId?.let { seriesRepository.getSeries(it) }
            _availableSeries.value = seriesRepository.getAllSeries().first()
            _availableCollections.value = collectionRepository.getAllCollections().first()

            launch {
                sessionRepository.getSessionsForBook(bookId).collect { _sessions.value = it }
            }
            launch {
                bookmarkRepository.getBookmarksForBook(bookId).collect { _bookmarks.value = it }
            }
            launch {
                highlightRepository.getHighlightsForBook(bookId).collect { _highlights.value = it }
            }
            launch {
                noteRepository.getNotesForBook(bookId).collect { _notes.value = it }
            }
            launch {
                _collections.value = collectionRepository.getCollectionsForBook(bookId)
            }
            launch {
                _tags.value = tagRepository.getTagsForBook(bookId)
            }
            launch {
                _availableTags.value = tagRepository.getAllTags().first()
            }
        }
    }

    fun saveMetadata(
        title: String,
        subtitle: String,
        authors: String,
        publisher: String,
        language: String,
        isbn: String,
        description: String,
        seriesId: String?,
        seriesNumber: String,
        collectionIds: Set<String>,
        newSeriesName: String,
        newCollectionName: String
    ) {
        val bookId = currentBookId ?: return
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) return

        viewModelScope.launch {
            val current = bookRepository.getBook(bookId) ?: return@launch
            val createdSeries = newSeriesName.trim().takeIf { it.isNotEmpty() }?.let { name ->
                seriesRepository.getSeriesByName(name) ?: Series(id = UUID.randomUUID().toString(), name = name)
                    .also { seriesRepository.insertSeries(it) }
            }
            val createdCollection = newCollectionName.trim().takeIf { it.isNotEmpty() }?.let { name ->
                collectionRepository.getCollectionByName(name) ?: Collection(id = UUID.randomUUID().toString(), name = name)
                    .also { collectionRepository.insertCollection(it) }
            }
            val selectedSeriesId = createdSeries?.id ?: seriesId
            val selectedCollectionIds = collectionIds + listOfNotNull(createdCollection?.id)
            val number = seriesNumber.trim().toDoubleOrNull()

            val updated = current.copy(
                title = normalizedTitle,
                subtitle = subtitle.blankToNull(),
                authors = authors.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                publisher = publisher.blankToNull(),
                language = language.blankToNull(),
                isbn = isbn.blankToNull(),
                description = description.blankToNull(),
                seriesId = selectedSeriesId,
                seriesNumber = number,
                updatedAt = Clock.System.now()
            ).also { it.updateProgress(current.normalizedProgress) }
            bookRepository.updateBook(updated)

            val assignedCollectionIds = collectionRepository.getCollectionsForBook(bookId).mapTo(mutableSetOf()) { it.id }
            (assignedCollectionIds - selectedCollectionIds).forEach {
                collectionRepository.removeBookFromCollection(bookId, it)
            }
            (selectedCollectionIds - assignedCollectionIds).forEach {
                collectionRepository.addBookToCollection(bookId, it)
            }
            refreshMetadata(bookId)
        }
    }

    private suspend fun refreshMetadata(bookId: String) {
        val refreshed = bookRepository.getBook(bookId)
        _book.value = refreshed
        _series.value = refreshed?.seriesId?.let { seriesRepository.getSeries(it) }
        _collections.value = collectionRepository.getCollectionsForBook(bookId)
        _availableSeries.value = seriesRepository.getAllSeries().first()
        _availableCollections.value = collectionRepository.getAllCollections().first()
    }

    /** Diff-assigns tags the same way saveMetadata syncs collections. */
    fun updateBookTags(selectedIds: Set<String>) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val currentIds = tagRepository.getTagsForBook(bookId).mapTo(mutableSetOf()) { it.id }
            (currentIds - selectedIds).forEach { tagRepository.removeTagFromBook(bookId, it) }
            (selectedIds - currentIds).forEach { tagRepository.addTagToBook(bookId, it) }
            _tags.value = tagRepository.getTagsForBook(bookId)
            _availableTags.value = tagRepository.getAllTags().first()
        }
    }

    private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }
}
