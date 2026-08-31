package com.folio.reader.ui.library

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.SeriesRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository,
    private val seriesRepository: SeriesRepository
) {
    /** Books bulk-selection; hoisted so system back can clear it instead of exiting. */
    val selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)

    fun toggleSelection(bookId: String) {
        val next = if (bookId in selectedBookIds.value) selectedBookIds.value - bookId else selectedBookIds.value + bookId
        selectedBookIds.value = next
        isSelectionMode.value = next.isNotEmpty()
    }

    fun clearSelection() {
        selectedBookIds.value = emptySet()
        isSelectionMode.value = false
    }

    enum class ViewMode {
        GRID, LIST, COMPACT
    }

    enum class SortBy {
        TITLE, AUTHOR, DATE_ADDED, LAST_OPENED, PROGRESS, READING_TIME, COMPLETION_DATE, FILE_SIZE
    }

    data class FilterState(
        val statuses: Set<BookStatus> = emptySet(),
        val author: String? = null,
        val seriesId: String? = null,
        val collectionId: String? = null,
        val tagId: String? = null,
        val progressMin: Int = 0,
        val progressMax: Int = 100,
        val dateAddedAfter: Instant? = null,
        val dateAddedBefore: Instant? = null,
        val lastReadAfter: Instant? = null,
        val lastReadBefore: Instant? = null,
        val completionDateAfter: Instant? = null,
        val completionDateBefore: Instant? = null
    ) {
        fun hasFilters(): Boolean {
            return statuses.isNotEmpty() || author != null || seriesId != null || collectionId != null ||
                tagId != null || progressMin > 0 || progressMax < 100 ||
                dateAddedAfter != null || dateAddedBefore != null ||
                lastReadAfter != null || lastReadBefore != null ||
                completionDateAfter != null || completionDateBefore != null
        }
    }

    data class LibraryState(
        val books: List<Book> = emptyList(),
        val viewMode: ViewMode = ViewMode.GRID,
        val sortBy: SortBy = SortBy.LAST_OPENED,
        val sortAscending: Boolean = false,
        val filter: FilterState = FilterState(),
        val selectedBookIds: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false
    )

    fun allBooks(): Flow<List<Book>> = bookRepository.getAllBooks()

    fun currentlyReading(): Flow<List<Book>> = bookRepository.getCurrentlyReading()

    fun finishedBooks(): Flow<List<Book>> = bookRepository.getFinishedBooks()

    fun unreadBooks(): Flow<List<Book>> = bookRepository.getUnreadBooks()

    fun booksByStatus(status: BookStatus): Flow<List<Book>> = bookRepository.getBooksByStatus(status)

    fun booksBySeries(seriesId: String): Flow<List<Book>> = bookRepository.getBooksBySeries(seriesId)

    fun booksByCollection(collectionId: String): Flow<List<Book>> = bookRepository.getBooksByCollection(collectionId)

    fun searchBooks(query: String): Flow<List<Book>> = bookRepository.searchBooks(query)

    fun allCollections(): Flow<List<Collection>> = collectionRepository.getAllCollections()

    fun allSeries(): Flow<List<Series>> = seriesRepository.getAllSeries()

    fun filteredBooks(state: LibraryState): Flow<List<Book>> {
        // Series/collection pick the source flow (they are join-based, not columns);
        // status/author/progress/date filters are applied on top so they combine.
        val baseFlow = when {
            state.filter.collectionId != null -> booksByCollection(state.filter.collectionId!!)
            state.filter.seriesId != null -> booksBySeries(state.filter.seriesId!!)
            else -> allBooks()
        }

        return baseFlow.map { books ->
            books.filter { book ->
                applyFilters(book, state.filter)
            }.sortedWith(compareBooks(state.sortBy, state.sortAscending))
        }
    }

    private fun applyFilters(book: Book, filter: FilterState): Boolean {
        if (filter.statuses.isNotEmpty() && book.status !in filter.statuses) return false
        if (filter.author != null && !book.displayAuthor.contains(filter.author!!, ignoreCase = true)) return false
        if (filter.progressMin > 0 && book.progressPercent < filter.progressMin) return false
        if (filter.progressMax < 100 && book.progressPercent > filter.progressMax) return false
        if (filter.dateAddedAfter != null && book.addedAt < filter.dateAddedAfter!!) return false
        if (filter.dateAddedBefore != null && book.addedAt > filter.dateAddedBefore!!) return false
        if (filter.lastReadAfter != null && (book.lastOpenedAt == null || book.lastOpenedAt!! < filter.lastReadAfter!!)) return false
        if (filter.lastReadBefore != null && (book.lastOpenedAt == null || book.lastOpenedAt!! > filter.lastReadBefore!!)) return false
        return true
    }

    private fun compareBooks(sortBy: SortBy, ascending: Boolean): Comparator<Book> {
        val comparator = when (sortBy) {
            SortBy.TITLE -> compareBy<Book> { it.title.lowercase() }
            SortBy.AUTHOR -> compareBy<Book> { it.displayAuthor.lowercase() }
            SortBy.DATE_ADDED -> compareBy<Book> { it.addedAt.toEpochMilliseconds() }
            SortBy.LAST_OPENED -> compareBy<Book> { it.lastOpenedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
            SortBy.PROGRESS -> compareBy<Book> { it.normalizedProgress }
            SortBy.READING_TIME -> compareBy<Book> { it.totalWords }
            SortBy.COMPLETION_DATE -> compareBy<Book> { it.lastOpenedAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }
            SortBy.FILE_SIZE -> compareBy<Book> { it.epubFileSize }
        }
        return if (ascending) comparator else comparator.reversed()
    }
}

data class BookCardData(
    val book: Book,
    val coverUrl: String?,
    val progressPercent: Int,
    val statusLabel: String
)

fun Book.toCardData(): BookCardData {
    return BookCardData(
        book = this,
        coverUrl = coverPath,
        progressPercent = progressPercent,
        statusLabel = when (status) {
            BookStatus.READING -> "Reading"
            BookStatus.PAUSED -> "Paused"
            BookStatus.FINISHED -> "Finished"
            BookStatus.ABANDONED -> "Abandoned"
            else -> "Unread"
        }
    )
}