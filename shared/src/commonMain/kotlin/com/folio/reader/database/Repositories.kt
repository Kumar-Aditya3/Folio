package com.folio.reader.database

import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Quote
import com.folio.reader.model.ReadingCycle
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.RevisitItem
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.statistics.BookStatistics
import com.folio.reader.statistics.DailyStatistics
import com.folio.reader.statistics.HeatmapDay
import com.folio.reader.sync.SyncOperation
import com.folio.reader.sync.SyncQueueItem
import com.folio.reader.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

interface BookRepository {
    suspend fun insertBook(book: Book, emitSyncEvent: Boolean = true)
    suspend fun updateBook(book: Book, emitSyncEvent: Boolean = true)
    suspend fun deleteBook(bookId: String, emitSyncEvent: Boolean = true)
    suspend fun getBook(bookId: String): Book?
    fun getAllBooks(): Flow<List<Book>>
    fun getBooksByStatus(status: BookStatus): Flow<List<Book>>
    fun getBooksBySeries(seriesId: String): Flow<List<Book>>
    fun getBooksByCollection(collectionId: String): Flow<List<Book>>
    fun getCurrentlyReading(): Flow<List<Book>>
    fun getFinishedBooks(): Flow<List<Book>>
    fun getUnreadBooks(): Flow<List<Book>>
    fun searchBooks(query: String): Flow<List<Book>>
    suspend fun getBookByEpubHash(hash: String): Book?
    suspend fun getBookByIsbn(isbn: String): Book?
    suspend fun insertChapters(bookId: String, chapters: List<Chapter>)
    suspend fun getChaptersForBook(bookId: String): List<Chapter>
    suspend fun markOpened(bookId: String)
    suspend fun setBookStatus(bookId: String, status: BookStatus)
    suspend fun setCloudState(bookId: String, cloudState: CloudState)
    suspend fun updateNormalizedProgress(bookId: String, progress: Double)
}

interface ReadingPositionRepository {
    suspend fun upsertPosition(position: ReadingPosition, emitSyncEvent: Boolean = true)
    suspend fun getPosition(bookId: String, deviceId: String): ReadingPosition?
    suspend fun getLatestPositionAcrossDevices(bookId: String): ReadingPosition?
    suspend fun getAllPositionsForBook(bookId: String): List<ReadingPosition>
    fun observePosition(bookId: String, deviceId: String): Flow<ReadingPosition?>
    suspend fun deletePosition(bookId: String, deviceId: String)
}

interface ReadingSessionRepository {
    suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean = true)
    suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean = true)
    suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>>
    suspend fun getActiveSession(bookId: String): ReadingSession?
    suspend fun getSessionsForDateRange(start: Instant, end: Instant): List<ReadingSession>
    suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession>

    /** Newest session start recorded by any OTHER device, or null when none exist. */
    suspend fun maxStartedAtExcludingDevice(deviceId: String): Instant?

    /**
     * Every device's sessions since [from]. Sessions are synced, so this is the
     * reading history as it exists in the cloud — unlike the daily rollups, which
     * are per-device and never leave the machine that made them.
     */
    fun observeSessionsSince(from: Instant): Flow<List<ReadingSession>>
}

interface ReadingCycleRepository {
    suspend fun insertCycle(cycle: ReadingCycle)
    suspend fun updateCycle(cycle: ReadingCycle)
    suspend fun getCurrentCycle(bookId: String): ReadingCycle?
    suspend fun getCyclesForBook(bookId: String): List<ReadingCycle>
}

interface HighlightRepository {
    suspend fun insertHighlight(highlight: Highlight, emitSyncEvent: Boolean = true)
    suspend fun updateHighlight(highlight: Highlight, emitSyncEvent: Boolean = true)
    suspend fun deleteHighlight(highlightId: String, emitSyncEvent: Boolean = true)
    suspend fun restoreHighlight(highlightId: String)
    fun getHighlightsForBook(bookId: String): Flow<List<Highlight>>
    fun getHighlightsForChapter(bookId: String, chapterId: String): Flow<List<Highlight>>
    suspend fun getHighlight(highlightId: String): Highlight?
    suspend fun getDeletedHighlights(bookId: String): List<Highlight>
}

interface NoteRepository {
    suspend fun insertNote(note: Note, emitSyncEvent: Boolean = true)
    suspend fun updateNote(note: Note, emitSyncEvent: Boolean = true)
    suspend fun deleteNote(noteId: String, emitSyncEvent: Boolean = true)
    suspend fun restoreNote(noteId: String)
    fun getNotesForBook(bookId: String): Flow<List<Note>>
    suspend fun getNote(noteId: String): Note?
    suspend fun getDeletedNotes(bookId: String): List<Note>
}

interface BookmarkRepository {
    suspend fun insertBookmark(bookmark: Bookmark, emitSyncEvent: Boolean = true)
    suspend fun updateBookmark(bookmark: Bookmark, emitSyncEvent: Boolean = true)
    suspend fun deleteBookmark(bookmarkId: String, emitSyncEvent: Boolean = true)
    suspend fun restoreBookmark(bookmarkId: String)
    fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>>
    suspend fun getBookmark(bookmarkId: String): Bookmark?
    suspend fun getDeletedBookmarks(bookId: String): List<Bookmark>
}

interface TagRepository {
    suspend fun insertTag(tag: Tag, emitSyncEvent: Boolean = true)
    suspend fun updateTag(tag: Tag, emitSyncEvent: Boolean = true)
    suspend fun deleteTag(tagId: String)
    suspend fun getAllTags(): Flow<List<Tag>>
    suspend fun getTagsForBook(bookId: String): List<Tag>
    suspend fun getTagsForHighlight(highlightId: String): List<Tag>
    suspend fun getBooksForTag(tagId: String): List<Book>
    suspend fun getHighlightsForTag(tagId: String): List<Highlight>
    suspend fun addTagToBook(bookId: String, tagId: String)
    suspend fun removeTagFromBook(bookId: String, tagId: String)
    suspend fun addTagToHighlight(highlightId: String, tagId: String)
    suspend fun removeTagFromHighlight(highlightId: String, tagId: String)
}

interface CollectionRepository {
    suspend fun insertCollection(collection: Collection, emitSyncEvent: Boolean = true)
    suspend fun updateCollection(collection: Collection, emitSyncEvent: Boolean = true)
    suspend fun deleteCollection(collectionId: String)
    fun getAllCollections(): Flow<List<Collection>>
    suspend fun getCollectionByName(name: String): Collection?
    suspend fun getCollectionsForBook(bookId: String): List<Collection>
    suspend fun addBookToCollection(bookId: String, collectionId: String)
    suspend fun removeBookFromCollection(bookId: String, collectionId: String)
}

interface SeriesRepository {
    suspend fun insertSeries(series: Series, emitSyncEvent: Boolean = true)
    suspend fun updateSeries(series: Series, emitSyncEvent: Boolean = true)
    suspend fun deleteSeries(seriesId: String)
    fun getAllSeries(): Flow<List<Series>>
    suspend fun getSeries(seriesId: String): Series?
    suspend fun getSeriesByName(name: String): Series?
    suspend fun getBooksInSeries(seriesId: String): Flow<List<Book>>
}

interface QuoteRepository {
    suspend fun insertQuote(quote: Quote, emitSyncEvent: Boolean = true)
    fun getQuotesForBook(bookId: String): Flow<List<Quote>>
    fun getAllQuotes(): Flow<List<Quote>>
}

interface RevisitRepository {
    suspend fun insertRevisitItem(item: RevisitItem, emitSyncEvent: Boolean = true)
    suspend fun resolveRevisitItem(itemId: String, emitSyncEvent: Boolean = true)
    fun getUnresolvedRevisitItems(): Flow<List<RevisitItem>>
    suspend fun getRevisitItemsForBook(bookId: String): List<RevisitItem>
    suspend fun getRevisitItemCount(): Int
}

data class ChapterIndexEntry(
    val chapterId: String,
    val spineIndex: Int,
    val title: String,
    val content: String
)

interface SearchRepository {
    suspend fun indexChapter(bookId: String, chapterId: String, spineIndex: Int, title: String, content: String)
    suspend fun deleteIndexForBook(bookId: String)
    fun search(query: String): Flow<List<SearchResult>>
    fun searchInBook(bookId: String, query: String): Flow<List<SearchResult>>

    /**
     * Indexes many chapters in one transaction. Default delegates to per-chapter
     * [indexChapter]; JDBC implementations override this with a real batch so
     * importing a 150-chapter book is one commit instead of 150.
     */
    suspend fun indexChaptersBulk(bookId: String, chapters: List<ChapterIndexEntry>) {
        for (c in chapters) indexChapter(bookId, c.chapterId, c.spineIndex, c.title, c.content)
    }
}

@Serializable
data class SearchResult(
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val title: String,
    val context: String
)

interface SettingsRepository {
    suspend fun getGlobalSettings(): ReaderSettings
    suspend fun saveGlobalSettings(settings: ReaderSettings, emitSyncEvent: Boolean = true)
    suspend fun getBookSettings(bookId: String): BookReaderSettings?
    suspend fun saveBookSettings(bookId: String, settings: BookReaderSettings)
    suspend fun deleteBookSettings(bookId: String)

    /** Raw key/value access (persists the auth session). Empty string clears the value. */
    suspend fun setRaw(key: String, value: String)
    suspend fun getRaw(key: String): String?
}

interface StatisticsRepository {
    suspend fun upsertBookStats(stats: BookStatistics)
    suspend fun getBookStats(bookId: String): BookStatistics?
    suspend fun upsertDailyStats(stats: DailyStatistics)
    fun getDailyStats(deviceId: String, fromDate: LocalDate): Flow<List<DailyStatistics>>
    fun getHeatmapData(deviceId: String, year: Int): Flow<List<HeatmapDay>>
}

interface SyncRepository {
    suspend fun enqueueSync(entityType: String, entityId: String, operation: SyncOperation, payload: String)
    suspend fun getPendingSync(limit: Int): List<SyncQueueItem>
    suspend fun getPendingSyncCount(): Int
    suspend fun recoverStaleSyncing(before: Instant)
    suspend fun markSyncing(id: String)
    suspend fun markSynced(id: String)
    suspend fun markError(id: String)
    suspend fun clearSynced(before: Instant)
    fun getSyncState(): Flow<SyncState>
    suspend fun updateSyncState(state: SyncState)
}

interface DeviceRepository {
    suspend fun upsertDevice(device: Device)
    fun getAllDevices(): Flow<List<Device>>
    suspend fun getDevice(deviceId: String): Device?
    suspend fun deactivateDevice(deviceId: String)
}

@Serializable
data class Device(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val lastSeenAt: Instant,
    val isCurrent: Boolean,
    val isActive: Boolean
)
