package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.statistics.StatisticsViewModel
import com.folio.reader.ui.statistics.TopBook
import com.folio.reader.ui.statistics.everythingElseMinutes
import com.folio.reader.ui.statistics.whereYourTimeCaption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * "Where your time went" — the section that replaced the drill core. Its pure
 * aggregation (the everything-else remainder, the opened caption) and the
 * §11.2 exclusion guarantees the core used to carry, ported to the new data:
 * an excluded title never appears in the ranked rows and never counts toward
 * the aggregate or the opened/library figures. Compose-side behaviour is out
 * of scope (desktopTest has no Compose infrastructure); the ViewModel-level
 * exclusion coverage runs through fakes, exactly as the core's did.
 */
class CoreSampleTest {

    // ── pure aggregation ─────────────────────────────────────────────────────

    @Test
    fun `everything else is the remainder, floored at zero`() {
        val top = listOf(TopBook("a", "A", "Au", null, 30L), TopBook("b", "B", "Bu", null, 20L))
        assertEquals(50L, everythingElseMinutes(top, 100L))
        assertEquals(0L, everythingElseMinutes(top, 50L), "a capped leaderboard never exceeds the total")
        assertEquals(0L, everythingElseMinutes(top, 40L), "rounding can shave a minute; never negative")
        assertEquals(0L, everythingElseMinutes(emptyList(), 0L))
    }

    @Test
    fun `caption copy is grammatical at every count`() {
        assertEquals("", whereYourTimeCaption(0, 0))
        assertEquals("", whereYourTimeCaption(0, 12))
        assertEquals("1 of 1 book opened", whereYourTimeCaption(1, 1))
        assertEquals("all 5 books opened", whereYourTimeCaption(5, 5))
        assertEquals("9 of 23 books opened", whereYourTimeCaption(9, 23))
    }

    // ── §11.2 exclusions, end to end through the view model ─────────────────

    private fun coreBook(id: String): Book {
        val now = Clock.System.now()
        return Book(
            id = id,
            title = "Book $id",
            epubHash = "hash-$id",
            epubFileSize = 1_000L,
            addedAt = now,
        )
    }

    @Test
    fun `excluded books never appear in ranked rows or the opened count`() = runBlocking {
        val exclusions = FakeExclusionRepo().apply { add(Scope.BOOK, "hidden") }
        val books = listOf(coreBook("hidden"), coreBook("kept"), coreBook("also-kept"))
        val sessions = listOf(session("kept", 20L), session("hidden", 45L))
        val viewModel = StatisticsViewModel(
            bookRepository = FakeBookRepo(books),
            sessionRepository = FakeSessionRepo(sessions),
            statsExclusionRepository = exclusions,
        )

        val state = viewModel.state.first()

        // The ranked rows: only titles whose sessions survived.
        assertEquals(listOf("kept"), state.topBooks.map { it.id })
        // The opened/library figures the caption renders.
        assertEquals(1, state.booksOpened, "only the kept book was opened in the window")
        assertEquals(2, state.librarySize, "the hidden book is not part of the library the user sees")
        // The aggregate remainder excludes the hidden book's 45 minutes: the
        // window total is the kept book's 20, all of it in the single top row.
        val totalMinutes = state.timeThisYearMs / 60_000
        assertEquals(20L, totalMinutes)
        assertEquals(0L, everythingElseMinutes(state.topBooks, totalMinutes))
        Unit
    }

    @Test
    fun `the new VM fields compute from synthetic sessions`() = runBlocking {
        val books = listOf(coreBook("a"), coreBook("b"))
        val sessions = listOf(
            session("a", 30L),
            session("a", 90L),  // longest sitting
            session("b", 15L),
        )
        val viewModel = StatisticsViewModel(
            bookRepository = FakeBookRepo(books),
            sessionRepository = FakeSessionRepo(sessions),
        )

        val state = viewModel.state.first()

        assertEquals(90L, state.longestSessionMinutes)
        assertEquals(1, state.daysActiveThisYear, "all sessions share today's local date")
        assertEquals(2, state.booksOpened)
        assertEquals(2, state.librarySize)
        assertEquals(24, state.hourTotals.size)
        // The sessions all start now: their shared hour holds all 135 minutes.
        val nowHour = kotlinx.datetime.TimeZone.currentSystemDefault()
            .let { tz -> Clock.System.now().toLocalDateTime(tz).hour }
        assertEquals(135L, state.hourTotals[nowHour])
        Unit
    }

    // ── fakes (unchanged from the core's era) ────────────────────────────────

    private class FakeBookRepo(books: List<Book>) : BookRepository {
        val all = MutableStateFlow(books)
        override fun getAllBooks(): Flow<List<Book>> = all
        override fun getCurrentlyReading(): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == BookStatus.READING } }
        override fun getFinishedBooks(): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == BookStatus.FINISHED } }
        override suspend fun getBook(bookId: String): Book? = all.value.find { it.id == bookId }
        override suspend fun insertBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun updateBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun deleteBook(bookId: String, emitSyncEvent: Boolean) {}
        override fun getBooksByStatus(status: BookStatus): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == status } }
        override fun getBooksBySeries(seriesId: String): Flow<List<Book>> =
            all.map { list -> list.filter { it.seriesId == seriesId } }
        override fun getBooksByCollection(collectionId: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getUnreadBooks(): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == BookStatus.UNREAD } }
        override fun searchBooks(query: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override suspend fun getBookByEpubHash(hash: String): Book? = null
        override suspend fun getBookByIsbn(isbn: String): Book? = null
        override suspend fun insertChapters(bookId: String, chapters: List<Chapter>) {}
        override suspend fun getChaptersForBook(bookId: String): List<Chapter> = emptyList()
        override suspend fun markOpened(bookId: String) {}
        override suspend fun setBookStatus(bookId: String, status: BookStatus) {}
        override suspend fun setCloudState(bookId: String, cloudState: CloudState) {}
        override suspend fun updateNormalizedProgress(bookId: String, progress: Double) {}
    }

    private class FakeSessionRepo(sessions: List<ReadingSession>) : ReadingSessionRepository {
        val all = MutableStateFlow(sessions)
        override fun observeSessionsSince(from: kotlinx.datetime.Instant): Flow<List<ReadingSession>> =
            all.map { list -> list.filter { it.startedAt >= from } }
        override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> =
            all.map { list -> list.filter { it.bookId == bookId } }
        override suspend fun getActiveSession(bookId: String): ReadingSession? = null
        override suspend fun getSessionsForDateRange(start: kotlinx.datetime.Instant, end: kotlinx.datetime.Instant): List<ReadingSession> = emptyList()
        override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> = emptyList()
        override suspend fun maxStartedAtExcludingDevice(deviceId: String): kotlinx.datetime.Instant? = null
    }

    private class FakeExclusionRepo : StatsExclusionRepository {
        val exclusions = MutableStateFlow<Set<Pair<Scope, String>>>(emptySet())
        override fun observeExclusions(): Flow<Set<Pair<Scope, String>>> = exclusions
        override suspend fun add(scope: Scope, targetId: String) {
            exclusions.value = exclusions.value + (scope to targetId)
        }
        override suspend fun remove(scope: Scope, targetId: String) {
            exclusions.value = exclusions.value - (scope to targetId)
        }
    }

    private fun session(bookId: String, minutes: Long): ReadingSession {
        val now = Clock.System.now()
        return ReadingSession(
            id = "s-$bookId-$minutes",
            bookId = bookId,
            cycleId = null,
            deviceId = "test",
            startedAt = now,
            endedAt = now,
            durationMs = minutes * 60_000,
            startPosition = ReadingPosition(bookId = bookId, deviceId = "test", chapterId = "c1", spineIndex = 0, contentLocator = "cfi")
        )
    }
}
