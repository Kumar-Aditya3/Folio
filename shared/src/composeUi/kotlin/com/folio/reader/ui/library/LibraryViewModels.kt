package com.folio.reader.ui.library

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SeriesRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import com.folio.reader.ui.components.finishHorizon
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository,
    private val seriesRepository: SeriesRepository,
    /**
     * Optional: supplied by the Android host so library rows can show §5.1's
     * "~6 days left" caption. Left null elsewhere, which yields no captions
     * rather than a per-book query storm.
     */
    private val sessionRepository: ReadingSessionRepository? = null,
    /**
     * Optional: persists the selected collection shelf across launches. The
     * shelves themselves work without it — the selection just doesn't survive
     * a restart.
     */
    private val settingsRepository: SettingsRepository? = null
) {
    /** Backing scope for the shelf selection and the bulk-collection picker. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    // ── Collection shelves (the manga category model, over collections) ─────
    //
    // Collections are the books library's categories: Main is seeded and always
    // first, one shelf shows at a time, and every book lives on at least one
    // shelf. The selection is remembered per device and restored on the next
    // visit — the manga library's category row, on the books side.

    /** The shelf row: every collection, Main first (sortOrder = -1), then sort order and name. */
    val collections: StateFlow<List<Collection>> = collectionRepository.getAllCollections()
        .map { list ->
            list.sortedWith(compareBy({ it.sortOrder != -1 }, { it.sortOrder }, { it.name }))
        }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    /** The selected shelf's collection id; null while no selection has landed. */
    val selectedCollectionId = MutableStateFlow<String?>(null)

    /**
     * Live membership of the selected collection. A stale snapshot here is what
     * made freshly shelved manga invisible until the library was re-entered.
     */
    val shelfBookIds: StateFlow<Set<String>?> = selectedCollectionId
        .flatMapLatest { id ->
            if (id == null) flowOf<Set<String>?>(null)
            else collectionRepository.observeBookIdsInCollection(id).map { ids -> ids as Set<String>? }
        }
        .stateIn(scope, SharingStarted.Lazily, null)

    /** No virtual All bucket: the shelf always shows one real collection. */
    fun selectCollection(collectionId: String) {
        selectedCollectionId.value = collectionId
        scope.launch { settingsRepository?.setRaw(KEY_BOOKS_COLLECTION, collectionId) }
    }

    /** Selects Main when present, otherwise the first collection; startup and after deletes. */
    fun selectDefaultCollection() {
        scope.launch {
            val target = runCatching { collectionRepository.defaultCollection() }.getOrNull() ?: return@launch
            if (selectedCollectionId.value != target.id) selectCollection(target.id)
        }
    }

    init {
        // Self-heal exactly like DocumentLibraryViewModel: seed Main and shelve
        // every collection-less book the moment this screen is reached, so the
        // rail never depends on the app-start hook having run (or survived) —
        // a library imported by an older build ports itself on first visit.
        scope.launch {
            runCatching { collectionRepository.ensureSeeded() }
                .onFailure { println("⚠️ Book collection seed failed: $it") }
        }
        // Follow the collection list so a fresh default selection lands as soon
        // as Main exists, and a deleted selection falls back to the default
        // instead of an empty grid.
        scope.launch {
            collections.collect { list ->
                val current = selectedCollectionId.value
                if (list.none { it.id == current }) {
                    // First selection of the session: reopen the shelf the reader
                    // left, but only while it still exists; a gone or stale
                    // remembered id degrades to the default rather than
                    // resurrecting a deleted collection.
                    val remembered = if (current == null) settingsRepository?.getRaw(KEY_BOOKS_COLLECTION) else null
                    if (remembered != null && list.any { it.id == remembered }) selectCollection(remembered)
                    else selectDefaultCollection()
                }
            }
        }
    }

    fun renameCollection(id: String, name: String) {
        scope.launch { runCatching { collectionRepository.renameCollection(id, name) } }
    }

    fun deleteCollection(id: String) {
        scope.launch {
            if (runCatching { collectionRepository.deleteCollection(id) }.getOrDefault(false) &&
                selectedCollectionId.value == id
            ) {
                selectDefaultCollection()
            }
        }
    }

    /**
     * Bulk collection picker, opened from the selection top bar — the books
     * counterpart of the manga and document pickers, so all three libraries
     * offer the same hold-select-and-shelve gesture. The initial check set is
     * the collections shared by *every* selected book (the manga picker's
     * intersection rule): what is checked is each book's complete membership
     * after apply, so unchecking removes as deliberately as checking adds.
     */
    val bulkCollectionPickerInitial = MutableStateFlow<Set<String>?>(null)

    fun requestBulkCollections() {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val memberships: List<Set<String>> = ids.map { bookId ->
                collectionRepository.getCollectionsForBook(bookId).mapTo(mutableSetOf()) { it.id }
            }
            bulkCollectionPickerInitial.value =
                memberships.reduceOrNull { a, b -> a intersect b } ?: emptySet()
        }
    }

    fun closeBulkPicker() {
        bulkCollectionPickerInitial.value = null
    }

    /**
     * Live-applies the picker's current set to every selected book — a full
     * membership replace per book (the manga picker's `assign` contract), with
     * the default shelf substituted for an empty set so a book never ends up
     * shelfless. Selection stays, so a second collection can be filled without
     * re-picking the books.
     */
    fun applyBulkCollections(collectionIds: Set<String>) {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val target = collectionIds.ifEmpty {
                runCatching { collectionRepository.defaultCollection() }.getOrNull()
                    ?.let { setOf(it.id) }
                    ?: return@launch
            }
            ids.forEach { bookId -> runCatching { collectionRepository.assign(bookId, target) } }
        }
    }

    /** Creates (or finds) a collection by name; the picker's inline "new shelf" row. */
    suspend fun createCollection(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return collectionRepository.getCollectionByName(trimmed)?.id
            ?: runCatching { collectionRepository.createCollection(trimmed).id }.getOrNull()
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
            return statuses.isNotEmpty() || author != null || seriesId != null ||
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

    fun searchBooks(query: String): Flow<List<Book>> = bookRepository.searchBooks(query)

    fun allCollections(): Flow<List<Collection>> = collectionRepository.getAllCollections()

    fun allSeries(): Flow<List<Series>> = seriesRepository.getAllSeries()

    /**
     * §5.1: "~6 days left" captions for the shelf, keyed by book id.
     *
     * One query for the whole 7-day session window feeds every book's estimate,
     * so the caption costs one read rather than one per row. Books with no
     * projection are absent from the map — callers render nothing for them
     * instead of a placeholder.
     */
    fun finishEstimates(): Flow<Map<String, String>> {
        val sessions = sessionRepository ?: return flowOf(emptyMap())
        return combine(
            bookRepository.getAllBooks(),
            sessions.observeSessionsSince(Clock.System.now() - PACE_WINDOW_DAYS.days)
        ) { books, window ->
            val byBook = window.groupBy { it.bookId }
            books.mapNotNull { book ->
                finishHorizon(
                    totalWords = book.totalWords,
                    progress = book.normalizedProgress,
                    bookSessions = byBook[book.id].orEmpty(),
                    paceSessions = window
                )?.let { book.id to it }
            }.toMap()
        }
    }

    fun filteredBooks(state: LibraryState): Flow<List<Book>> {
        // Series picks the source flow (join-based, not a column); the status
        // and date filters apply on top so they combine. The collection shelf
        // rides along as a membership filter, so shelf + filters compose the
        // way the manga library's category + filters do.
        val baseFlow = when {
            state.filter.seriesId != null -> booksBySeries(state.filter.seriesId!!)
            else -> allBooks()
        }

        return combine(baseFlow, shelfBookIds) { books, shelfIds ->
            books.filter { book ->
                (shelfIds == null || book.id in shelfIds) && applyFilters(book, state.filter)
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

    private companion object {
        /** Matches ReadingPace's own trailing window so both agree on "pace". */
        const val PACE_WINDOW_DAYS = 7

        /** Raw settings key for the selected collection shelf (same scheme as manga's). */
        const val KEY_BOOKS_COLLECTION = "library.books.collection"
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

/** Restores sort order across process death; unknown names fall back to the default. */
val SortBySaver: Saver<LibraryViewModel.SortBy, String> = Saver(
    save = { it.name },
    restore = { name ->
        LibraryViewModel.SortBy.entries.firstOrNull { it.name == name }
            ?: LibraryViewModel.SortBy.LAST_OPENED
    }
)

/** Serializes FilterState to Bundle-safe primitives (enum names, epoch millis). */
val FilterStateSaver: Saver<LibraryViewModel.FilterState, Any> = mapSaver(
    save = { state ->
        mapOf(
            "statuses" to state.statuses.map { it.name },
            "author" to state.author,
            "seriesId" to state.seriesId,
            "tagId" to state.tagId,
            "progressMin" to state.progressMin,
            "progressMax" to state.progressMax,
            "dateAddedAfter" to state.dateAddedAfter?.toEpochMilliseconds(),
            "dateAddedBefore" to state.dateAddedBefore?.toEpochMilliseconds(),
            "lastReadAfter" to state.lastReadAfter?.toEpochMilliseconds(),
            "lastReadBefore" to state.lastReadBefore?.toEpochMilliseconds(),
            "completionDateAfter" to state.completionDateAfter?.toEpochMilliseconds(),
            "completionDateBefore" to state.completionDateBefore?.toEpochMilliseconds()
        )
    },
    restore = { map ->
        @Suppress("UNCHECKED_CAST")
        val statuses = (map["statuses"] as? List<String>).orEmpty()
            .mapNotNull { name -> BookStatus.entries.firstOrNull { it.name == name } }
            .toSet()
        fun instant(key: String): Instant? =
            (map[key] as? Number)?.toLong()?.let { Instant.fromEpochMilliseconds(it) }
        LibraryViewModel.FilterState(
            statuses = statuses,
            author = map["author"] as? String,
            seriesId = map["seriesId"] as? String,
            tagId = map["tagId"] as? String,
            progressMin = (map["progressMin"] as? Number)?.toInt() ?: 0,
            progressMax = (map["progressMax"] as? Number)?.toInt() ?: 100,
            dateAddedAfter = instant("dateAddedAfter"),
            dateAddedBefore = instant("dateAddedBefore"),
            lastReadAfter = instant("lastReadAfter"),
            lastReadBefore = instant("lastReadBefore"),
            completionDateAfter = instant("completionDateAfter"),
            completionDateBefore = instant("completionDateBefore")
        )
    }
)