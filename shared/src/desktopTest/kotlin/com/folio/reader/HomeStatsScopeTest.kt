package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.manga.MangaNewChapterBadge
import com.folio.reader.manga.MangaUpdateRepository
import com.folio.reader.manga.MangaUpdateRunResult
import com.folio.reader.manga.MangaUpdateState
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.home.HomeUiState
import com.folio.reader.ui.home.HomeViewModel
import com.folio.reader.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §11.2 + §12.9: the same StatsScope gates Home's content selections and the
 * statistics — an excluded title is excluded everywhere it is recommended
 * (Rule 18), while "no exclusions" must reproduce the legacy numbers exactly.
 */
class HomeStatsScopeTest {

    // ── fakes ────────────────────────────────────────────────────────────────

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

    private open class EmptyTagRepo : TagRepository {
        override suspend fun insertTag(tag: Tag, emitSyncEvent: Boolean) {}
        override suspend fun updateTag(tag: Tag, emitSyncEvent: Boolean) {}
        override suspend fun deleteTag(tagId: String) {}
        override suspend fun getAllTags(): Flow<List<Tag>> = MutableStateFlow(emptyList())
        override suspend fun getTagsForBook(bookId: String): List<Tag> = emptyList()
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

    private open class EmptyCollectionRepo : CollectionRepository {
        override suspend fun insertCollection(collection: Collection, emitSyncEvent: Boolean) {}
        override suspend fun updateCollection(collection: Collection, emitSyncEvent: Boolean) {}
        override suspend fun deleteCollection(collectionId: String) {}
        override fun getAllCollections(): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override suspend fun getCollectionByName(name: String): Collection? = null
        override suspend fun getCollectionsForBook(bookId: String): List<Collection> = emptyList()
        override suspend fun addBookToCollection(bookId: String, collectionId: String) {}
        override suspend fun removeBookFromCollection(bookId: String, collectionId: String) {}
    }

    /** Mirrors the real query's one-way MANGA scope; source/category resolution lives in JDBC tests. */
    private class FakeMangaUpdateRepo(badges: List<MangaNewChapterBadge>) : MangaUpdateRepository {
        val badges = MutableStateFlow(badges)
        override suspend fun runUpdateCheck(): MangaUpdateRunResult = MangaUpdateRunResult(0, 0, 0)
        override fun observeUpdateStates(): Flow<List<MangaUpdateState>> = flowOf(emptyList())
        override suspend fun clearNewChapters(mangaId: String) {}
        override suspend fun getNewChapterBadges(
            exclusions: Set<Pair<Scope, String>>
        ): List<MangaNewChapterBadge> {
            val excluded = exclusions
                .mapNotNull { (kind, id) -> if (kind == Scope.MANGA) id else null }
                .toSet()
            return badges.value.filter { it.mangaId !in excluded }
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun book(
        id: String,
        status: BookStatus = BookStatus.READING,
        authors: List<String> = emptyList(),
        seriesId: String? = null,
        addedAt: kotlinx.datetime.Instant = Clock.System.now()
    ): Book = Book(
        id = id,
        title = "Book $id",
        authors = authors,
        epubHash = "hash-$id",
        epubFileSize = 1_000L,
        addedAt = addedAt,
        lastOpenedAt = if (status == BookStatus.UNREAD) null else addedAt,
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

    private fun badge(mangaId: String, count: Int) = MangaNewChapterBadge(
        mangaId = mangaId,
        title = "Manga $mangaId",
        sourceId = 1L,
        thumbnailUrl = null,
        coverPath = null,
        newChapterCount = count,
        lastCheckedAt = Clock.System.now()
    )

    private fun homeViewModel(
        books: List<Book>,
        sessions: List<ReadingSession>,
        exclusions: FakeExclusionRepo? = null,
        mangaUpdate: FakeMangaUpdateRepo? = null
    ): HomeViewModel = HomeViewModel(
        bookRepository = FakeBookRepo(books),
        sessionRepository = FakeSessionRepo(sessions),
        statsExclusionRepository = exclusions,
        tagRepository = EmptyTagRepo(),
        collectionRepository = EmptyCollectionRepo(),
        mangaUpdateRepository = mangaUpdate
    )

    // ── §12.9 acceptance ─────────────────────────────────────────────────────

    @Test
    fun `excluding the hero promotes the next passing in-progress book`() = runBlocking {
        val exclusions = FakeExclusionRepo().apply { add(Scope.BOOK, "a") }
        val state = homeViewModel(
            books = listOf(book("a", addedAt = Clock.System.now()), book("b", addedAt = Clock.System.now())),
            sessions = emptyList(),
            exclusions = exclusions
        ).state.first()

        assertEquals("b", state.hero?.id)
        assertTrue(state.continueReading.none { it.id == "a" })
        assertTrue(state.exclusionsActive)
    }

    @Test
    fun `excluding every in-progress book leaves the designed empty, never an excluded book`() = runBlocking {
        val exclusions = FakeExclusionRepo().apply { add(Scope.BOOK_STATUS, BookStatus.ABANDONED.name) }
        val state = homeViewModel(
            books = listOf(book("a", status = BookStatus.ABANDONED)),
            sessions = emptyList(),
            exclusions = exclusions
        ).state.first()

        assertNull(state.hero)
        assertTrue(state.continueReading.isEmpty())
        // The library is inventory: it still counts, only the recommendation hides.
        assertTrue(state.hasBooks)
        assertTrue(state.exclusionsActive)
    }

    @Test
    fun `zero-progress library hero shows the most recently added book with no progress`() = runBlocking {
        val now = Clock.System.now()
        val older = book("older", status = BookStatus.UNREAD, addedAt = now - 2.days)
        val newer = book("newer", status = BookStatus.UNREAD, addedAt = now)
        val state = homeViewModel(books = listOf(older, newer), sessions = emptyList()).state.first()

        assertEquals("newer", state.hero?.id)
        assertEquals(0f, state.hero?.progress)
        assertNull(state.hero?.finishEstimate)
    }

    @Test
    fun `because-finished anchor skips an excluded finished book`() = runBlocking {
        val exclusions = FakeExclusionRepo().apply { add(Scope.BOOK, "anchor") }
        val state = homeViewModel(
            books = listOf(
                book("anchor", status = BookStatus.FINISHED, authors = listOf("Author X")),
                book("next", status = BookStatus.FINISHED, authors = listOf("Author X")),
                book("c1", status = BookStatus.UNREAD, authors = listOf("Author X")),
                book("c2", status = BookStatus.UNREAD, authors = listOf("Author X"))
            ),
            sessions = emptyList(),
            exclusions = exclusions
        ).state.first()

        // The excluded book is not the anchor; the passing one is.
        assertEquals("Book next", state.becauseFinishedTitle)
        assertTrue(state.candidates.none { it.id == "anchor" })
        assertEquals(setOf("c1", "c2"), state.candidates.map { it.id }.toSet())
    }

    @Test
    fun `no exclusions reproduces the legacy numbers exactly`() = runBlocking {
        val books = listOf(book("a"), book("b", status = BookStatus.FINISHED))
        val sessions = listOf(session("a", 34), session("b", 10))
        val withRepo = homeViewModel(books, sessions, FakeExclusionRepo()).state.first()
        val legacy = homeViewModel(books, sessions, exclusions = null).state.first()

        assertEquals(legacy, withRepo)
    }

    @Test
    fun `Home and StatisticsViewModel agree under the same scope`() = runBlocking {
        // "a" reads 30 min today; "b" is abandoned with 45 min today.
        val books = listOf(book("a"), book("b", status = BookStatus.ABANDONED))
        val sessions = listOf(session("a", 30), session("b", 45))
        val exclusions = FakeExclusionRepo().apply { add(Scope.BOOK_STATUS, BookStatus.ABANDONED.name) }

        val home = homeViewModel(books, sessions, exclusions).state.first()
        val stats = StatisticsViewModel(
            bookRepository = FakeBookRepo(books),
            sessionRepository = FakeSessionRepo(sessions),
            statsExclusionRepository = exclusions,
            tagRepository = EmptyTagRepo(),
            collectionRepository = EmptyCollectionRepo()
        ).state.first()

        assertEquals(30L, home.todayMinutes)
        // Both surfaces must count the same session minutes this week.
        assertEquals(home.week.sumOf { it.minutes } * 60_000, stats.timeThisWeekMs)
        // And the same list of in-progress books (hero + carousel on Home).
        val homeInProgress = listOfNotNull(home.hero?.id) + home.continueReading.map { it.id }
        assertEquals(stats.currentlyReading.map { it.id }.toSet(), homeInProgress.toSet())
    }

    @Test
    fun `manga new-chapter badges surface on Home and honor the manga exclusions`() = runBlocking {
        val mangaUpdate = FakeMangaUpdateRepo(listOf(badge("m1", 3), badge("m2", 12)))
        val exclusions = FakeExclusionRepo().apply { add(Scope.MANGA, "m2") }

        val state = homeViewModel(
            books = listOf(book("a")),
            sessions = emptyList(),
            exclusions = exclusions,
            mangaUpdate = mangaUpdate
        ).state.first()

        // §12.9: an excluded manga's badge never reaches Home.
        assertEquals(listOf("m1"), state.newChapters.map { it.mangaId })
        assertEquals(3, state.newChapters.single().newChapterCount)
    }

    @Test
    fun `new-chapters card is hidden with no badges or no update repository`() = runBlocking {
        val noBadges = homeViewModel(
            books = listOf(book("a")),
            sessions = emptyList(),
            mangaUpdate = FakeMangaUpdateRepo(emptyList())
        ).state.first()
        assertTrue(noBadges.newChapters.isEmpty())

        val noRepo = homeViewModel(books = listOf(book("a")), sessions = emptyList()).state.first()
        assertTrue(noRepo.newChapters.isEmpty())
    }
}
