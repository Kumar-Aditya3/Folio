package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaBrowsePage
import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaHistoryEntry
import com.folio.reader.manga.MangaHistoryItem
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaImageData
import com.folio.reader.manga.MangaLastRead
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.home.HomeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §11.4 Phase 9: the manga Continue-reading card (cap 3, library-only,
 * exclusion-gated) and the Discover card (LATEST from the anchor's source,
 * minus library titles, cap 6, cached).
 */
class HomeMangaCardsTest {

    // ── book-side fakes (HomeViewModel's combine needs them even for manga tests) ──

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

    private class FakeSessionRepo : ReadingSessionRepository {
        override fun observeSessionsSince(from: kotlinx.datetime.Instant): Flow<List<ReadingSession>> =
            flowOf(emptyList())
        override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> = flowOf(emptyList())
        override suspend fun getActiveSession(bookId: String): ReadingSession? = null
        override suspend fun getSessionsForDateRange(start: kotlinx.datetime.Instant, end: kotlinx.datetime.Instant): List<ReadingSession> = emptyList()
        override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> = emptyList()
        override suspend fun maxStartedAtExcludingDevice(deviceId: String): kotlinx.datetime.Instant? = null
    }

    // ── manga-side fakes ─────────────────────────────────────────────────────

    private class FakeMangaRepo : MangaRepository {
        val entries = LinkedHashMap<String, MangaEntry>()
        override suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean) { entries[manga.id] = manga }
        override suspend fun delete(mangaId: String, emitSyncEvent: Boolean) { entries.remove(mangaId) }
        override suspend fun get(mangaId: String): MangaEntry? = entries[mangaId]
        override suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry? =
            entries.values.firstOrNull { it.sourceId == sourceId && it.url == url }
        override fun observeLibrary(): Flow<List<MangaEntry>> =
            flowOf(entries.values.filter { it.inLibrary })
        override fun observeAll(): Flow<List<MangaEntry>> = flowOf(entries.values.toList())
        override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean) {}
        override suspend fun setCoverPath(mangaId: String, coverPath: String?) {}
        override suspend fun touchLastRead(mangaId: String) {}
    }

    private class FakeHistoryRepo(var recent: List<MangaHistoryItem>) : MangaHistoryRepository {
        override suspend fun record(
            mangaId: String,
            chapterId: String?,
            title: String,
            coverUrl: String?,
            coverPath: String?,
            sourceName: String?,
            chapterName: String?,
        ) {}

        override fun observeRecent(limit: Int): Flow<List<MangaHistoryItem>> =
            flowOf(recent.take(limit))
        override fun observeHistory(): Flow<List<MangaHistoryEntry>> = flowOf(emptyList())
        override suspend fun clear() {}
        override suspend fun clearHistory() {}
    }

    private class FakeChapterRepo(
        val progressMap: Map<String, Float> = emptyMap(),
        val unreadMap: Map<String, Int> = emptyMap()
    ) : MangaChapterRepository {
        override suspend fun replaceChapters(mangaId: String, chapters: List<MangaChapter>) {}
        override suspend fun getChapters(mangaId: String): List<MangaChapter> = emptyList()
        override suspend fun getChapter(chapterId: String): MangaChapter? = null
        override suspend fun markRead(chapterIds: List<String>, read: Boolean, emitSyncEvent: Boolean) {}
        override suspend fun setBookmarked(chapterId: String, bookmarked: Boolean, emitSyncEvent: Boolean) {}
        override suspend fun saveProgress(chapterId: String, lastPage: Int, totalPages: Int, emitSyncEvent: Boolean) {}
        override suspend fun applyRemoteState(
            chapterId: String,
            read: Boolean,
            bookmarked: Boolean,
            lastPageRead: Int,
            updatedAt: kotlinx.datetime.Instant,
            emitSyncEvent: Boolean,
            totalPages: Int,
        ) {}

        override suspend fun setDownloadedPages(chapterId: String, pages: Int) {}
        override fun observeUnreadCounts(): Flow<Map<String, Int>> = flowOf(unreadMap)
        override fun observeProgress(): Flow<Map<String, Float>> = flowOf(progressMap)
        override fun observeLastRead(): Flow<Map<String, MangaLastRead>> = flowOf(emptyMap())
        override fun observeDownloadedCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun markAllReadForManga(mangaId: String, read: Boolean) {}
    }

    private class FakeCategoryRepo(private val byManga: Map<String, Set<String>> = emptyMap()) : MangaCategoryRepository {
        override suspend fun create(name: String, emitSyncEvent: Boolean): MangaCategory =
            MangaCategory(id = name, name = name)
        override suspend fun rename(id: String, name: String, emitSyncEvent: Boolean) {}
        override suspend fun delete(id: String, emitSyncEvent: Boolean): Boolean = true
        override fun observeCategories(): Flow<List<MangaCategory>> = flowOf(emptyList())
        override suspend fun assign(mangaId: String, categoryIds: Set<String>, emitSyncEvent: Boolean) {}
        override suspend fun categoriesFor(mangaId: String): Set<String> = byManga[mangaId].orEmpty()
        override suspend fun get(id: String): MangaCategory? = null
        override fun observeCategoriesFor(mangaId: String): Flow<Set<String>> = flowOf(byManga[mangaId].orEmpty())
        override fun observeMangaIdsInCategory(categoryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun mangaIdsInCategory(categoryId: String): Set<String> = emptySet()
        override suspend fun applyRemote(category: MangaCategory, mangaIds: Set<String>) {}
        override suspend fun defaultCategory(): MangaCategory? = null
        override suspend fun ensureMembership(mangaId: String) {}
        override suspend fun ensureSeeded() {}
    }

    /** Counting browse backend for the Discover card. */
    private class DiscoverBackend(
        private val sources: List<MangaSourceInfo>,
        private val latest: MangaBrowsePage? = null,
        private val fail: Boolean = false
    ) : MangaBackend {
        var browseCount = 0
        var lastMode: BrowseMode? = null
        override val supportsExtensions: Boolean = false
        override fun observeSources(): Flow<List<MangaSourceInfo>> = flowOf(sources)
        override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> = emptyList()
        override suspend fun fetchBrowse(
            sourceId: Long,
            page: Int,
            mode: BrowseMode,
            query: String,
            filters: List<MangaFilter>?,
        ): MangaBrowsePage {
            browseCount++
            lastMode = mode
            if (fail) throw IllegalStateException("network down")
            return latest ?: MangaBrowsePage(emptyList(), false)
        }

        override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail =
            MangaDetail(url = mangaUrl, title = "Stub")
        override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> = emptyList()
        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> = emptyList()
        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData = MangaImageData(ByteArray(0), "image/png")
        override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? = null
        override fun observeExtensions(): Flow<List<ExtensionEntry>> = flowOf(emptyList())
        override suspend fun refreshExtensionIndex() {}
        override suspend fun getRepos(): List<MangaRepoInfo> = emptyList()
        override suspend fun setRepos(repos: List<MangaRepoInfo>) {}
        override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun uninstallExtension(pkgName: String) {}
        override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {}
        override suspend fun getExtensionIcon(pkgName: String): ByteArray? = null
        override suspend fun setShowNsfwSources(enabled: Boolean) {}
        override suspend fun getShowNsfwSources(): Boolean = false
    }

    private class FakeExclusionRepo(initial: Set<Pair<Scope, String>> = emptySet()) : StatsExclusionRepository {
        val exclusions = MutableStateFlow(initial)
        override fun observeExclusions(): Flow<Set<Pair<Scope, String>>> = exclusions
        override suspend fun add(scope: Scope, targetId: String) {
            exclusions.value = exclusions.value + (scope to targetId)
        }
        override suspend fun remove(scope: Scope, targetId: String) {
            exclusions.value = exclusions.value - (scope to targetId)
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun entry(id: String, sourceId: Long = 10L, inLibrary: Boolean = true): MangaEntry =
        MangaEntry(
            id = id,
            sourceId = sourceId,
            sourceName = "Src $sourceId",
            url = "/manga/$id",
            title = id.uppercase(),
            thumbnailUrl = "thumb-$id",
            inLibrary = inLibrary
        )

    private fun historyRow(
        mangaId: String,
        chapterId: String? = "ch-$mangaId",
        chapterName: String? = "Chapter of $mangaId"
    ): MangaHistoryItem =
        MangaHistoryItem(
            mangaId = mangaId,
            chapterId = chapterId,
            readAt = Clock.System.now(),
            chapterName = chapterName
        )

    private fun homeViewModel(
        books: List<Book> = emptyList(),
        exclusions: StatsExclusionRepository? = null,
        mangaRepo: FakeMangaRepo? = null,
        history: FakeHistoryRepo? = null,
        chapters: FakeChapterRepo? = null,
        categories: FakeCategoryRepo? = null,
        backend: MangaBackend? = null
    ): HomeViewModel = HomeViewModel(
        bookRepository = FakeBookRepo(books),
        sessionRepository = FakeSessionRepo(),
        statsExclusionRepository = exclusions,
        mangaRepository = mangaRepo,
        mangaHistoryRepository = history,
        mangaChapterRepository = chapters,
        mangaCategoryRepository = categories,
        mangaBackend = backend
    )

    // ── §11.4 acceptance ─────────────────────────────────────────────────────

    @Test
    fun `manga continue caps at three and skips removed or out-of-library manga`() = runBlocking {
        val repo = FakeMangaRepo().apply {
            upsert(entry("m1"))
            upsert(entry("m2", inLibrary = false))
            upsert(entry("m3"))
            // m4 was deleted — history row remains but no entry.
            upsert(entry("m5"))
        }
        val state = homeViewModel(
            mangaRepo = repo,
            history = FakeHistoryRepo(
                listOf(
                    historyRow("m1"),
                    historyRow("m2", chapterName = null),
                    historyRow("m3", chapterName = ""),
                    historyRow("m4"),
                    historyRow("m5")
                )
            ),
            chapters = FakeChapterRepo(progressMap = mapOf("m1" to 0.5f), unreadMap = mapOf("m3" to 4))
        ).state.first()

        assertEquals(listOf("m1", "m3", "m5"), state.mangaContinue.map { it.mangaId })
        assertEquals(0.5f, state.mangaContinue.first { it.mangaId == "m1" }.progress)
        assertEquals("Chapter of m1", state.mangaContinue.first { it.mangaId == "m1" }.caption)
        // m3's history chapter name is blank → the unread-count fallback shows.
        assertEquals("4 unread", state.mangaContinue.first { it.mangaId == "m3" }.caption)
        assertTrue(state.mangaContinue.all { it.webUrl == null })
        Unit
    }

    @Test
    fun `manga continue honors the manga exclusions`() = runBlocking {
        val repo = FakeMangaRepo().apply {
            upsert(entry("m1", sourceId = 10L))
            upsert(entry("m2", sourceId = 20L))
            upsert(entry("m3", sourceId = 10L))
            upsert(entry("m4", sourceId = 30L))
        }
        val exclusions = FakeExclusionRepo(
            setOf(
                Scope.MANGA to "m1",
                Scope.MANGA_SOURCE to "20",
                Scope.MANGA_CATEGORY to "cat-1"
            )
        )
        val state = homeViewModel(
            exclusions = exclusions,
            mangaRepo = repo,
            history = FakeHistoryRepo(listOf(historyRow("m1"), historyRow("m2"), historyRow("m3"), historyRow("m4"))),
            chapters = FakeChapterRepo(),
            categories = FakeCategoryRepo(mapOf("m3" to setOf("cat-1")))
        ).state.first()

        assertEquals(
            listOf("m4"),
            state.mangaContinue.map { it.mangaId },
            "direct + source + category exclusions should leave only m4"
        )
        Unit
    }

    @Test
    fun `discover filters library titles and caps at six`() = runBlocking {
        HomeViewModel.resetDiscoverCache()
        val repo = FakeMangaRepo().apply {
            upsert(entry("anchor"))
            upsert(entry("A"))
            upsert(entry("F"))
        }
        val latest = MangaBrowsePage(
            items = ('A'..'H').map { ch -> MangaBrowseItem("/browse/$ch", ch.toString(), "thumb") },
            hasNextPage = false
        )
        val backend = DiscoverBackend(
            sources = listOf(MangaSourceInfo(id = 10L, name = "Src 10", lang = "en", supportsLatest = true)),
            latest = latest
        )
        val state = homeViewModel(
            mangaRepo = repo,
            history = FakeHistoryRepo(listOf(historyRow("anchor"))),
            backend = backend
        ).state.first()

        assertEquals(6, state.discover.size)
        assertEquals(listOf("B", "C", "D", "E", "G", "H"), state.discover.map { it.title })
        assertEquals(BrowseMode.LATEST, backend.lastMode)
        assertEquals(1, backend.browseCount)
        assertEquals("Src 10", state.discover.first().sourceName)
        Unit
    }

    @Test
    fun `discover is silently absent without history, without latest support, or on failure`() = runBlocking {
        HomeViewModel.resetDiscoverCache()
        val sources = listOf(MangaSourceInfo(id = 10L, name = "Src 10", lang = "en", supportsLatest = false))

        // No history at all.
        val noHistory = homeViewModel(
            mangaRepo = FakeMangaRepo().apply { upsert(entry("m1")) },
            history = FakeHistoryRepo(emptyList()),
            backend = DiscoverBackend(sources)
        ).state.first()
        assertTrue(noHistory.discover.isEmpty())

        // The anchor's source cannot serve LATEST.
        HomeViewModel.resetDiscoverCache()
        val noLatest = homeViewModel(
            mangaRepo = FakeMangaRepo().apply { upsert(entry("m1")) },
            history = FakeHistoryRepo(listOf(historyRow("m1"))),
            backend = DiscoverBackend(sources)
        ).state.first()
        assertTrue(noLatest.discover.isEmpty())

        // The browse request fails — empty, never a crash.
        HomeViewModel.resetDiscoverCache()
        val failing = homeViewModel(
            mangaRepo = FakeMangaRepo().apply { upsert(entry("m1")) },
            history = FakeHistoryRepo(listOf(historyRow("m1"))),
            backend = DiscoverBackend(
                sources = listOf(MangaSourceInfo(id = 10L, name = "Src 10", lang = "en", supportsLatest = true)),
                fail = true
            )
        ).state.first()
        assertTrue(failing.discover.isEmpty())
        Unit
    }

    @Test
    fun `discover fetches once per cache window`() = runBlocking {
        HomeViewModel.resetDiscoverCache()
        val backend = DiscoverBackend(
            sources = listOf(MangaSourceInfo(id = 10L, name = "Src 10", lang = "en", supportsLatest = true)),
            latest = MangaBrowsePage(listOf(MangaBrowseItem("/x", "X", null)), false)
        )
        val viewModel = homeViewModel(
            mangaRepo = FakeMangaRepo().apply { upsert(entry("m1")) },
            history = FakeHistoryRepo(listOf(historyRow("m1"))),
            backend = backend
        )

        assertEquals(listOf("X"), viewModel.state.first().discover.map { it.title })
        assertEquals(listOf("X"), viewModel.state.first().discover.map { it.title })
        assertEquals(1, backend.browseCount, "the cached window must suppress the second fetch")

        HomeViewModel.resetDiscoverCache()
        viewModel.state.first()
        assertEquals(2, backend.browseCount)
        Unit
    }
}
