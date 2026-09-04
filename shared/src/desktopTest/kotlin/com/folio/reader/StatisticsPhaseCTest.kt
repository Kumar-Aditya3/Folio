package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Tag
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §12.5 Phase C data layer: the top-books leaderboard and the tag-driven
 * genre breakdown rank by window minutes, respect the caps, and obey Rule 18 —
 * an excluded title reaches neither surface.
 */
class StatisticsPhaseCTest {

    // ── fakes (mirrors HomeStatsScopeTest's) ─────────────────────────────────

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

    private class FakeCollectionRepo : CollectionRepository {
        override suspend fun insertCollection(collection: Collection, emitSyncEvent: Boolean) {}
        override suspend fun updateCollection(collection: Collection, emitSyncEvent: Boolean) {}
        override suspend fun deleteCollection(collectionId: String) {}
        override fun getAllCollections(): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override suspend fun getCollectionByName(name: String): Collection? = null
        override suspend fun getCollectionsForBook(bookId: String): List<Collection> = emptyList()
        override suspend fun addBookToCollection(bookId: String, collectionId: String) {}
        override suspend fun removeBookFromCollection(bookId: String, collectionId: String) {}
    }

    private class FakeTagRepo(
        tagsByBook: Map<String, List<Tag>> = emptyMap()
    ) : TagRepository {
        val tagsByBook = tagsByBook.toMutableMap()
        override suspend fun insertTag(tag: Tag, emitSyncEvent: Boolean) {}
        override suspend fun updateTag(tag: Tag, emitSyncEvent: Boolean) {}
        override suspend fun deleteTag(tagId: String) {}
        override suspend fun getAllTags(): Flow<List<Tag>> = MutableStateFlow(emptyList())
        override suspend fun getTagsForBook(bookId: String): List<Tag> = tagsByBook[bookId].orEmpty()
        override suspend fun getTagsForHighlight(highlightId: String): List<Tag> = emptyList()
        override suspend fun getBooksForTag(tagId: String): List<Book> = emptyList()
        override suspend fun getHighlightsForTag(tagId: String): List<Highlight> = emptyList()
        override suspend fun addTagToBook(bookId: String, tagId: String) {}
        override suspend fun removeTagFromBook(bookId: String, tagId: String) {}
        override suspend fun addTagToHighlight(highlightId: String, tagId: String) {}
        override suspend fun removeTagFromHighlight(highlightId: String, tagId: String) {}
        override suspend fun getTagsForManga(mangaId: String): List<Tag> = emptyList()
        override suspend fun addTagToManga(mangaId: String, tagId: String) {}
        override suspend fun removeTagFromManga(mangaId: String, tagId: String) {}
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun book(
        id: String,
        coverPath: String? = null,
        authors: List<String> = emptyList(),
        status: BookStatus = BookStatus.READING
    ): Book = Book(
        id = id,
        title = "Book $id",
        authors = authors,
        epubHash = "hash-$id",
        epubFileSize = 1_000L,
        addedAt = Clock.System.now(),
        lastOpenedAt = Clock.System.now(),
        coverPath = coverPath,
        status = status
    )

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

    /** The manga reader's own locator shape — what tells the two kinds of session apart. */
    private fun mangaSession(mangaId: String, minutes: Long): ReadingSession =
        session(mangaId, minutes).let { base ->
            base.copy(
                startPosition = base.startPosition.copy(contentLocator = "manga-page-1"),
            )
        }

    private fun tag(id: String, name: String) = Tag(id = id, name = name)

    private fun viewModel(
        books: List<Book>,
        sessions: List<ReadingSession>,
        tags: FakeTagRepo? = null,
        exclusions: FakeExclusionRepo? = null
    ): StatisticsViewModel = StatisticsViewModel(
        bookRepository = FakeBookRepo(books),
        sessionRepository = FakeSessionRepo(sessions),
        statsExclusionRepository = exclusions,
        tagRepository = tags,
        collectionRepository = FakeCollectionRepo()
    )

    // ── §12.5 acceptance ─────────────────────────────────────────────────────

    @Test
    fun `top books rank by window minutes and drop titles without rows`() = runBlocking {
        val books = listOf(
            book("a", coverPath = "/covers/a.jpg", authors = listOf("A. Author")),
            book("b"),
            book("c"),
            book("d") // in the library but never read
        )
        // "m1" is a manga session: its id is absent from the book map and drops out.
        val sessions = listOf(session("a", 30), session("b", 10), session("c", 5), session("m1", 20))

        val state = viewModel(books, sessions).state.first()

        assertEquals(listOf("a", "b", "c"), state.topBooks.map { it.id })
        val first = state.topBooks.first()
        assertEquals("Book a", first.title)
        assertEquals("A. Author", first.author)
        assertEquals("/covers/a.jpg", first.coverPath)
        assertEquals(30L, first.minutes)
        Unit
    }

    @Test
    fun `top books cap at five`() = runBlocking {
        val books = (1..6).map { book("f$it") }
        val sessions = (1..6).map { session("f$it", it * 5L) }

        val state = viewModel(books, sessions).state.first()

        assertEquals(5, state.topBooks.size)
        assertEquals((6 downTo 2).map { "f$it" }, state.topBooks.map { it.id })
        Unit
    }

    @Test
    fun `genre breakdown aggregates minutes per tag, largest first`() = runBlocking {
        val books = listOf(book("a"), book("b"))
        val sessions = listOf(session("a", 30), session("b", 10))
        val tags = FakeTagRepo(
            mapOf(
                "a" to listOf(tag("t-f", "Fantasy"), tag("t-e", "Epic")),
                "b" to listOf(tag("t-f", "Fantasy"))
            )
        )

        val state = viewModel(books, sessions, tags = tags).state.first()

        assertEquals(listOf("Fantasy" to 40L, "Epic" to 30L), state.genres.map { it.label to it.minutes })
        Unit
    }

    @Test
    fun `genre breakdown cap at six and genre breakdown absent without a tag repository`() = runBlocking {
        val books = (1..8).map { book("g$it") }
        val sessions = (1..8).map { session("g$it", 10L) }
        val tags = FakeTagRepo(books.associate { it.id to listOf(tag("t-${it.id}", "Genre ${it.id}")) })

        val capped = viewModel(books, sessions, tags = tags).state.first()
        assertEquals(6, capped.genres.size)

        val noTags = viewModel(books, sessions, tags = null).state.first()
        assertTrue(noTags.genres.isEmpty())
        // The leaderboard does not depend on tags.
        assertEquals(5, noTags.topBooks.size)
        Unit
    }

    @Test
    fun `excluded book never reaches top books or genres`() = runBlocking {
        val exclusions = FakeExclusionRepo().apply { add(Scope.BOOK, "a") }
        val books = listOf(book("a"), book("b"))
        val sessions = listOf(session("a", 30), session("b", 10))
        val tags = FakeTagRepo(mapOf("a" to listOf(tag("t-a", "Hidden")), "b" to listOf(tag("t-b", "Kept"))))

        val state = viewModel(books, sessions, tags = tags, exclusions = exclusions).state.first()

        assertEquals(listOf("b"), state.topBooks.map { it.id })
        assertEquals(listOf("Kept"), state.genres.map { it.label })
        Unit
    }

    // ── local truth ─────────────────────────────────────────────────────────────
    // Sessions arrive from every device on the account, so the raw history outlives
    // the local library. Stats describe the library in front of the reader: a book
    // deleted here stops counting immediately, with no sync round in between.

    @Test
    fun `sessions for a book that is not on this device do not count`() = runBlocking {
        val books = listOf(book("a"))
        // "ghost" was deleted locally (or only ever read on another device): the row
        // is gone, the session is not.
        val sessions = listOf(session("a", 30), session("ghost", 20))

        val state = viewModel(books, sessions).state.first()

        assertEquals(30L * 60_000, state.timeThisWeekMs)
        assertEquals(1, state.sessionsThisWeek)
        assertEquals(listOf("a"), state.topBooks.map { it.id })
        Unit
    }

    @Test
    fun `the local gate also applies under an exclusion scope`() = runBlocking {
        val exclusions = FakeExclusionRepo()
        val books = listOf(book("a"))
        val sessions = listOf(session("a", 30), session("ghost", 20))

        val state = viewModel(books, sessions, exclusions = exclusions).state.first()

        assertEquals(30L * 60_000, state.timeThisWeekMs)
        Unit
    }

    @Test
    fun `manga sessions survive the local book gate`() = runBlocking {
        // A manga's subject lives in the manga tables, so the book list says nothing
        // about it — gating on books would have silently deleted manga reading time.
        val sessions = listOf(mangaSession("manga-1", 25))

        val state = viewModel(books = emptyList(), sessions = sessions).state.first()

        assertEquals(25L * 60_000, state.timeThisWeekMs)
        assertEquals(1, state.sessionsThisWeek)
        assertTrue(state.hasData)
        Unit
    }
}
