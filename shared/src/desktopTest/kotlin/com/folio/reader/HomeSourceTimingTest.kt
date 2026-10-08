package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.home.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where Home's cold start goes, measured rather than assumed.
 *
 * The reading numbers need the book source, the session source and the exclusion
 * source. `combine` cannot emit until each has produced a first value, so the
 * wait should be the **maximum** of those three — not their sum, and not more
 * than that. This test pins both ends: it fails if a source is awaited twice
 * (the sum) and it fails if the wait exceeds the slowest source.
 *
 * It exists because the symptom ("Home sits on the skeleton for seconds") has
 * several plausible causes, and the previous attempt at this fix picked the
 * wrong one by reasoning from the code instead of measuring.
 */
class HomeSourceTimingTest {

    private class ImmediateBookRepo : BookRepository {
        override fun getAllBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getCurrentlyReading(): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getFinishedBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
        override suspend fun getBook(bookId: String): Book? = null
        override suspend fun insertBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun updateBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun deleteBook(bookId: String, emitSyncEvent: Boolean) {}
        override fun getBooksByStatus(status: BookStatus): Flow<List<Book>> =
            MutableStateFlow(emptyList())
        override fun getBooksBySeries(seriesId: String): Flow<List<Book>> =
            MutableStateFlow(emptyList())
        override fun getBooksByCollection(collectionId: String): Flow<List<Book>> =
            MutableStateFlow(emptyList())
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

    /** The session source, delayed to stand in for the year-wide scan. Counts reads. */
    private class SlowSessionRepo(private val delayMs: Long) : ReadingSessionRepository {
        var subscriptions = 0
            private set

        override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> =
            MutableStateFlow(emptyList())
        override suspend fun getActiveSession(bookId: String): ReadingSession? = null
        override suspend fun getSessionsForDateRange(
            start: kotlinx.datetime.Instant,
            end: kotlinx.datetime.Instant
        ): List<ReadingSession> = emptyList()
        override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> = emptyList()
        override suspend fun maxStartedAtExcludingDevice(deviceId: String): kotlinx.datetime.Instant? =
            null
        override fun observeSessionsSince(
            from: kotlinx.datetime.Instant
        ): Flow<List<ReadingSession>> = flow {
            subscriptions++
            delay(delayMs)
            emit(emptyList())
        }
    }

    /** Exclusions, delayed to stand in for the row read. */
    private class SlowExclusionRepo(private val delayMs: Long) : StatsExclusionRepository {
        override fun observeExclusions(): Flow<Set<Pair<Scope, String>>> = flow {
            delay(delayMs)
            emit(emptySet())
        }
        override suspend fun add(scope: Scope, targetId: String) {}
        override suspend fun remove(scope: Scope, targetId: String) {}
    }

    /**
     * The wait is bounded by the slowest source and nothing more.
     *
     * A sum here would mean the session source is subscribed once per collector
     * rather than once in total — which is the duplicated-load failure mode this
     * codebase has hit before.
     */
    @Test
    fun home_numbers_wait_on_the_slowest_source_not_the_sum() = runBlocking {
        val sessionsDelay = 250L
        val exclusionsDelay = 120L

        val sessions = SlowSessionRepo(sessionsDelay)
        val vm = HomeViewModel(
            bookRepository = ImmediateBookRepo(),
            sessionRepository = sessions,
            statsExclusionRepository = SlowExclusionRepo(exclusionsDelay),
        )

        val start = System.nanoTime()
        vm.state.first { it.loaded }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        println(
            "HOME_SOURCE_TIMING\n" +
                "  session delay: ${sessionsDelay}ms\n" +
                "  exclusion delay: ${exclusionsDelay}ms\n" +
                "  elapsed: ${elapsedMs}ms\n" +
                "  session subscriptions: ${sessions.subscriptions}"
        )

        assertTrue(
            elapsedMs >= sessionsDelay,
            "must wait at least the slowest source (${sessionsDelay}ms), was ${elapsedMs}ms"
        )
        assertTrue(
            elapsedMs < sessionsDelay + exclusionsDelay,
            "sources must overlap; ${elapsedMs}ms is close to their sum " +
                "(${sessionsDelay + exclusionsDelay}ms), so one is not parallelised"
        )
    }
}
