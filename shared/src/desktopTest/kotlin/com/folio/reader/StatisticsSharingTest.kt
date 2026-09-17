package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stats' shared flows, and the property that makes them worth having: each query
 * runs **once**, not once per visit.
 *
 * The bug these guard: [StatisticsViewModel.state] is a cold `combine` over a year
 * of sessions, the book lists and a per-book exclusion pass. A nav destination that
 * collected it from inside the composable restarted the whole thing on every entry
 * to the tab, so the reader watched the skeleton and then the numbers arrive again
 * each time. These tests assert the *collection count* — the thing the old code got
 * wrong and the thing a future refactor would most easily break again.
 */
class StatisticsSharingTest {

    /** Counts how many times each query is collected. */
    private class CountingBookRepo(books: List<Book>) : BookRepository {
        val all = MutableStateFlow(books)
        var allBooksCollections = 0
            private set

        override fun getAllBooks(): Flow<List<Book>> = all.onStart { allBooksCollections++ }
        override fun getCurrentlyReading(): Flow<List<Book>> = all.map { l -> l.filter { it.status == BookStatus.READING } }
        override fun getFinishedBooks(): Flow<List<Book>> = all.map { l -> l.filter { it.status == BookStatus.FINISHED } }
        override suspend fun getBook(bookId: String): Book? = all.value.find { it.id == bookId }
        override suspend fun insertBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun updateBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun deleteBook(bookId: String, emitSyncEvent: Boolean) {}
        override fun getBooksByStatus(status: BookStatus): Flow<List<Book>> = all.map { l -> l.filter { it.status == status } }
        override fun getBooksBySeries(seriesId: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getBooksByCollection(collectionId: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getUnreadBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
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

    private class CountingSessionRepo(sessions: List<ReadingSession>) : ReadingSessionRepository {
        val all = MutableStateFlow(sessions)
        var scans = 0
            private set

        override fun observeSessionsSince(from: Instant): Flow<List<ReadingSession>> =
            all.map { l -> l.filter { it.startedAt >= from } }.onStart { scans++ }

        override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> =
            MutableStateFlow(emptyList())
        override suspend fun getActiveSession(bookId: String): ReadingSession? = null
        override suspend fun getSessionsForDateRange(start: Instant, end: Instant): List<ReadingSession> = emptyList()
        override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> = emptyList()
        override suspend fun maxStartedAtExcludingDevice(deviceId: String): Instant? = null
    }

    private fun book(id: String = "b1") = Book(
        id = id,
        title = "Book $id",
        epubHash = "hash-$id",
        epubFileSize = 1_000L,
        addedAt = Clock.System.now(),
        lastOpenedAt = Clock.System.now(),
        status = BookStatus.READING,
    )

    private fun session(bookId: String = "b1", minutes: Long = 30): ReadingSession {
        val now = Clock.System.now()
        return ReadingSession(
            id = "s-$bookId",
            bookId = bookId,
            cycleId = null,
            deviceId = "test",
            startedAt = now,
            endedAt = now,
            durationMs = minutes * 60_000,
            startPosition = ReadingPosition(
                bookId = bookId, deviceId = "test", chapterId = "c1", spineIndex = 0, contentLocator = "cfi",
            ),
        )
    }

    private fun viewModel(books: BookRepository, sessions: ReadingSessionRepository) =
        StatisticsViewModel(bookRepository = books, sessionRepository = sessions)

    /**
     * Waits until the shared state carries data. `Eagerly` starts the work when the
     * view model is built, so this only has to wait for it — never to trigger it.
     */
    private suspend fun awaitReady(vm: StatisticsViewModel) =
        withTimeout(15_000) { vm.sharedState.first { it.hasData } }

    @Test
    fun one_visit_runs_each_query_once() = runBlocking {
        val books = CountingBookRepo(listOf(book()))
        val sessions = CountingSessionRepo(listOf(session()))
        val vm = viewModel(books, sessions)

        awaitReady(vm)

        assertEquals(1, books.allBooksCollections, "one visit should read the library once")
        assertEquals(1, sessions.scans, "one visit should scan sessions once")
    }

    @Test
    fun re_entering_the_tab_does_not_run_the_queries_again() = runBlocking {
        val books = CountingBookRepo(listOf(book()))
        val sessions = CountingSessionRepo(listOf(session()))
        val vm = viewModel(books, sessions)

        awaitReady(vm)
        // A second read stands in for the tab being re-entered: the composable is
        // rebuilt and collects again. The flow is already hot and eager, so this is
        // a read of the current value rather than a new query.
        awaitReady(vm)

        assertEquals(1, books.allBooksCollections, "re-entering the tab must not re-read the library")
        assertEquals(1, sessions.scans, "re-entering the tab must not re-scan sessions")
    }

    @Test
    fun a_visit_reads_a_finished_value_not_an_empty_one() = runBlocking {
        val books = CountingBookRepo(listOf(book()))
        val sessions = CountingSessionRepo(listOf(session()))
        val vm = viewModel(books, sessions)

        awaitReady(vm)

        // The point of `Eagerly`: by the time a host reads the flow it already holds
        // a real emission. The composable's `collectAsState()` — the StateFlow
        // overload, which takes no initial and reads `.value` — therefore paints
        // final figures on its first frame instead of an empty state that corrects
        // itself a frame later, which is the flash this change removes.
        assertTrue(
            vm.sharedState.value.hasData,
            "a ready Stats state must already carry data, or the first frame still lies",
        )
    }

    @Test
    fun readiness_is_already_true_before_a_visit_reads_it() = runBlocking {
        val books = CountingBookRepo(listOf(book()))
        val sessions = CountingSessionRepo(listOf(session()))
        val vm = viewModel(books, sessions)

        awaitReady(vm)

        assertEquals(
            true, vm.sharedReady.value,
            "the skeleton gate must already be open, or the tab shows a skeleton it does not need",
        )
    }

    @Test
    fun exclusions_are_hot_too() = runBlocking {
        val books = CountingBookRepo(listOf(book()))
        val sessions = CountingSessionRepo(listOf(session()))
        val vm = viewModel(books, sessions)

        awaitReady(vm)

        // With no exclusion repository wired this is the empty set — but it must be a
        // *hot* empty set. A cold `flowOf` would still be a fresh flow per visit, and
        // the review line would flash on every entry.
        assertEquals(emptySet(), vm.sharedExclusions.value)
    }

    @Test
    fun the_recent_feed_is_hot_even_when_empty() = runBlocking {
        val books = CountingBookRepo(listOf(book()))
        val sessions = CountingSessionRepo(listOf(session()))
        val vm = viewModel(books, sessions)

        // No quote or highlight repository means no feed. It must still surface as a
        // hot empty list rather than a null the composable has to special-case, or
        // the two hosts would each need their own branch.
        assertEquals(emptyList(), vm.sharedRecentQuotes.value)
    }
}
