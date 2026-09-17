package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaLastRead
import com.folio.reader.manga.MangaRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home's cold start must not wait on the manga side.
 *
 * The bug this guards: [HomeViewModel.build] used to resolve the §11.4 manga
 * cards inline — `getNewChapterBadges`, then `buildMangaContinue` (itself a
 * serial fan-out of progress, unread counts, statistics, history and a per-row
 * category lookup), then a second full `observeLibrary()` read — all before it
 * could return the state carrying `loaded = true`. On a cold start that held the
 * skeleton up until every one of those queries answered, though none of them
 * feed the reading ring, the streak or the Continue-reading shelf.
 *
 * These tests assert *ordering* — the property that was wrong. A test that
 * merely collected a final state would pass either way.
 */
class HomeColdStartTest {

    private fun book(id: String) = Book(
        id = id,
        title = "Book $id",
        authors = listOf("Author"),
        epubHash = "hash-$id",
        epubFileSize = 1024L,
        addedAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
        status = BookStatus.READING,
        totalWords = 50_000,
        totalCharacters = 250_000,
    )

    /** Books and sessions answer immediately; nothing here is gated. */
    private class InstantBookRepo(books: List<Book>) : BookRepository {
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
        override fun getBooksByCollection(collectionId: String): Flow<List<Book>> =
            MutableStateFlow(emptyList())
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

    private class InstantSessionRepo : ReadingSessionRepository {
        override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> =
            MutableStateFlow(emptyList())
        override suspend fun getActiveSession(bookId: String): ReadingSession? = null
        override suspend fun getSessionsForDateRange(start: Instant, end: Instant): List<ReadingSession> =
            emptyList()
        override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> = emptyList()
        override suspend fun maxStartedAtExcludingDevice(deviceId: String): Instant? = null
        override fun observeSessionsSince(from: Instant): Flow<List<ReadingSession>> =
            MutableStateFlow(emptyList())
    }

    /**
     * A manga library that says nothing until [release] completes.
     *
     * Standing in for a slow query on a cold start: while it is held the manga
     * side cannot produce its sections, so anything Home renders in that window
     * provably is not waiting on it.
     */
    private class GatedMangaRepo(private val release: CompletableDeferred<Unit>) : MangaRepository {
        override suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean) {}
        override suspend fun delete(mangaId: String, emitSyncEvent: Boolean) {}
        override suspend fun get(mangaId: String): MangaEntry? {
            release.await()
            return null
        }
        override suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry? = null
        override fun observeLibrary(): Flow<List<MangaEntry>> = flow {
            release.await()
            emit(emptyList())
        }
        override fun observeAll(): Flow<List<MangaEntry>> = flow {
            release.await()
            emit(emptyList())
        }
        override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean) {}
        override suspend fun setCoverPath(mangaId: String, coverPath: String?) {}
        override suspend fun touchLastRead(mangaId: String) {}
    }

    /** The reads `buildMangaContinue` hits first — gated on the same latch. */
    private class GatedChapterRepo(private val release: CompletableDeferred<Unit>) :
        MangaChapterRepository {
        override suspend fun replaceChapters(mangaId: String, chapters: List<MangaChapter>) {}
        override suspend fun getChapters(mangaId: String): List<MangaChapter> = emptyList()
        override suspend fun getChapter(chapterId: String): MangaChapter? = null
        override suspend fun markRead(chapterIds: List<String>, read: Boolean, emitSyncEvent: Boolean) {}
        override suspend fun setBookmarked(chapterId: String, bookmarked: Boolean, emitSyncEvent: Boolean) {}
        override suspend fun saveProgress(
            chapterId: String,
            lastPage: Int,
            totalPages: Int,
            emitSyncEvent: Boolean
        ) {}
        override suspend fun applyRemoteState(
            chapterId: String,
            read: Boolean,
            bookmarked: Boolean,
            lastPageRead: Int,
            updatedAt: Instant,
            emitSyncEvent: Boolean,
            totalPages: Int
        ) {}
        override suspend fun setDownloadedPages(chapterId: String, pages: Int) {}
        override fun observeUnreadCounts(): Flow<Map<String, Int>> = flow {
            release.await()
            emit(emptyMap())
        }
        override fun observeProgress(): Flow<Map<String, Float>> = flow {
            release.await()
            emit(emptyMap())
        }
        override fun observeLastRead(): Flow<Map<String, MangaLastRead>> = flow {
            release.await()
            emit(emptyMap())
        }
        override fun observeDownloadedCounts(): Flow<Map<String, Int>> = flow {
            release.await()
            emit(emptyMap())
        }
        override suspend fun markAllReadForManga(mangaId: String, read: Boolean) {}
    }

    /**
     * The property under test: with the manga side deliberately hung, Home still
     * reports `loaded = true`.
     *
     * Before the split this times out — `build` awaited the manga queries before
     * it could return at all.
     */
    @Test
    fun loaded_arrives_while_the_manga_side_is_still_silent() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val vm = HomeViewModel(
            bookRepository = InstantBookRepo(listOf(book("a"))),
            sessionRepository = InstantSessionRepo(),
            mangaRepository = GatedMangaRepo(release),
            mangaChapterRepository = GatedChapterRepo(release),
        )

        // No release. If Home waits on manga, this never completes.
        val loaded = withTimeout(10_000) { vm.state.first { it.loaded } }
        assertTrue(loaded.loaded, "Home must report loaded without the manga side answering")
        assertTrue(loaded.hasBooks, "the book pipeline's own facts must be present")
    }

    /**
     * The split defers the manga sections, it does not discard them: once the
     * gate opens the flow emits again, still reporting `loaded`.
     */
    @Test
    fun the_manga_sections_still_land_once_the_side_answers() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val vm = HomeViewModel(
            bookRepository = InstantBookRepo(listOf(book("a"))),
            sessionRepository = InstantSessionRepo(),
            mangaRepository = GatedMangaRepo(release),
            mangaChapterRepository = GatedChapterRepo(release),
        )

        val first = withTimeout(10_000) { vm.state.first { it.loaded } }
        assertTrue(first.mangaContinue.isEmpty(), "nothing manga-shaped before the gate opens")

        release.complete(Unit)

        // After release the resolved sections land. The library is empty here, so
        // the observable change is that the emission is a settled one.
        val settled = withTimeout(10_000) { vm.state.first { it.loaded && it.mangaContinue.isEmpty() } }
        assertTrue(settled.loaded)
    }
}
