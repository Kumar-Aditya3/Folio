package com.folio.reader

import com.folio.reader.database.SettingsRepository
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ChapterNumberParser
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowsePage
import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaDownload
import com.folio.reader.manga.MangaDownloadManager
import com.folio.reader.manga.MangaDownloadRepository
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.FileDownloadStorage
import com.folio.reader.manga.MangaHistoryEntry
import com.folio.reader.manga.MangaHistoryItem
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaImageData
import com.folio.reader.manga.MangaLastRead
import com.folio.reader.manga.MangaNote
import com.folio.reader.manga.MangaNoteRepository
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.manga.chapterId
import com.folio.reader.manga.mangaId
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.manga.ChapterFilter
import com.folio.reader.ui.manga.MangaDetailViewModel
import com.folio.reader.ui.manga.MangaLibraryViewModel
import com.folio.reader.ui.manga.MangaReaderViewModel
import com.folio.reader.ui.manga.MangaSearchRanker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reader progress, read-state, and loading-priority tests for the manga reader.
 * Fakes stand in for the repositories/backend so the ViewModel's own concurrency
 * (real Dispatchers.Default scope) is exercised end to end.
 */
class MangaReaderProgressTest {

    // ---------- Fakes ----------

    class FakeChapterRepo : MangaChapterRepository {
        val chapters = LinkedHashMap<String, MangaChapter>()
        val progressWrites = mutableListOf<Triple<String, Int, Int>>()
        val readMarks = mutableListOf<Pair<String, Boolean>>()

        override suspend fun replaceChapters(mangaId: String, chapters: List<MangaChapter>) {
            chapters.forEach { this.chapters[it.id] = it }
        }

        override suspend fun getChapters(mangaId: String): List<MangaChapter> =
            chapters.values.filter { it.mangaId == mangaId }.sortedBy { it.sortOrder }

        override suspend fun getChapter(chapterId: String): MangaChapter? = chapters[chapterId]

        override suspend fun markRead(chapterIds: List<String>, read: Boolean, emitSyncEvent: Boolean) {
            chapterIds.forEach { id ->
                readMarks += id to read
                chapters[id]?.let { chapters[id] = it.copy(read = read, lastPageRead = if (read) it.lastPageRead else 0) }
            }
        }

        override suspend fun setBookmarked(chapterId: String, bookmarked: Boolean, emitSyncEvent: Boolean) {}

        override suspend fun saveProgress(chapterId: String, lastPage: Int, totalPages: Int, emitSyncEvent: Boolean) {
            progressWrites += Triple(chapterId, lastPage, totalPages)
            chapters[chapterId]?.let { chapters[chapterId] = it.copy(lastPageRead = lastPage, totalPages = totalPages) }
        }

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
        override fun observeUnreadCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override fun observeProgress(): Flow<Map<String, Float>> = flowOf(emptyMap())
        override fun observeLastRead(): Flow<Map<String, MangaLastRead>> = flowOf(emptyMap())
        override fun observeDownloadedCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun markAllReadForManga(mangaId: String, read: Boolean) {}
    }

    class FakeMangaRepo : MangaRepository {
        val entries = LinkedHashMap<String, MangaEntry>()
        var lastReadTouched: String? = null

        override suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean) {
            entries[manga.id] = manga
        }

        override suspend fun delete(mangaId: String, emitSyncEvent: Boolean) {
            entries.remove(mangaId)
        }

        override suspend fun get(mangaId: String): MangaEntry? = entries[mangaId]

        override suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry? =
            entries.values.firstOrNull { it.sourceId == sourceId && it.url == url }

        override fun observeLibrary(): Flow<List<MangaEntry>> = flowOf(entries.values.toList())
        override fun observeAll(): Flow<List<MangaEntry>> = flowOf(entries.values.toList())
        override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean) {}
        override suspend fun setCoverPath(mangaId: String, coverPath: String?) {}
        override suspend fun touchLastRead(mangaId: String) {
            lastReadTouched = mangaId
        }
    }

    class FakeHistoryRepo : MangaHistoryRepository {
        val records = mutableListOf<Pair<String, String?>>()

        override suspend fun record(
            mangaId: String,
            chapterId: String?,
            title: String,
            coverUrl: String?,
            coverPath: String?,
            sourceName: String?,
            chapterName: String?,
        ) {
            records += mangaId to chapterId
        }

        var recent: List<MangaHistoryItem> = emptyList()

        override fun observeRecent(limit: Int): Flow<List<MangaHistoryItem>> = flowOf(recent)
        override suspend fun clear() {}

        override fun observeHistory(): Flow<List<MangaHistoryEntry>> = flowOf(emptyList())
        override suspend fun clearHistory() {}
    }

    class FakeNoteRepo : MangaNoteRepository {
        override suspend fun upsert(note: MangaNote, emitSyncEvent: Boolean) {}
        override suspend fun delete(noteId: String, emitSyncEvent: Boolean) {}
        override suspend fun get(noteId: String): MangaNote? = null
        override suspend fun notesForChapter(chapterId: String): List<MangaNote> = emptyList()
        override fun observeNotesForChapter(chapterId: String): Flow<List<MangaNote>> = flowOf(emptyList())
        override fun observeAllNotes(): Flow<List<MangaNote>> = flowOf(emptyList())
    }

    class FakeCategoryRepo : MangaCategoryRepository {
        var categoriesList: List<MangaCategory> = emptyList()

        override suspend fun create(name: String, emitSyncEvent: Boolean): MangaCategory =
            MangaCategory(id = name, name = name)

        override suspend fun rename(id: String, name: String, emitSyncEvent: Boolean) {}
        override suspend fun delete(id: String, emitSyncEvent: Boolean): Boolean = true
        override fun observeCategories(): Flow<List<MangaCategory>> = flowOf(categoriesList)
        override suspend fun assign(mangaId: String, categoryIds: Set<String>, emitSyncEvent: Boolean) {}
        override suspend fun categoriesFor(mangaId: String): Set<String> = emptySet()
        override suspend fun get(id: String): MangaCategory? = null
        override fun observeCategoriesFor(mangaId: String): Flow<Set<String>> = flowOf(emptySet())
        override fun observeMangaIdsInCategory(categoryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun mangaIdsInCategory(categoryId: String): Set<String> = emptySet()
        override suspend fun applyRemote(category: MangaCategory, mangaIds: Set<String>) {}
        override suspend fun defaultCategory(): MangaCategory? = null
        override suspend fun ensureMembership(mangaId: String) {}
        override suspend fun ensureSeeded() {}
    }

    class FakeSettingsRepo(val values: MutableMap<String, String> = mutableMapOf()) : SettingsRepository {
        override suspend fun getGlobalSettings(): ReaderSettings = ReaderSettings()
        override suspend fun saveGlobalSettings(settings: ReaderSettings, emitSyncEvent: Boolean) {}
        override suspend fun getBookSettings(bookId: String): com.folio.reader.settings.BookReaderSettings? = null
        override suspend fun saveBookSettings(bookId: String, settings: com.folio.reader.settings.BookReaderSettings) {}
        override suspend fun deleteBookSettings(bookId: String) {}
        override suspend fun setRaw(key: String, value: String) {
            values[key] = value
        }

        override suspend fun getRaw(key: String): String? = values[key]
    }

    class FakeBackend(
        private val pageLists: Map<String, List<MangaPageRef>>,
        private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ) : MangaBackend {
        val pageListFetches = mutableListOf<String>()
        private val fakeBytes = ByteArray(16) { it.toByte() }

        override val supportsExtensions: Boolean = false
        override fun observeSources(): Flow<List<MangaSourceInfo>> = flowOf(emptyList())
        override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> = emptyList()
        override suspend fun fetchBrowse(
            sourceId: Long,
            page: Int,
            mode: BrowseMode,
            query: String,
            filters: List<MangaFilter>?,
        ): MangaBrowsePage = MangaBrowsePage(emptyList(), false)

        override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail =
            MangaDetail(url = mangaUrl, title = "Fake")

        override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> = emptyList()

        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> {
            pageListFetches += chapter.url
            gates[chapter.url]?.await()
            return pageLists[chapter.url] ?: error("No scripted pages for ${chapter.url}")
        }

        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData = MangaImageData(fakeBytes, "image/png")

        override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? = null
        override fun observeExtensions(): Flow<List<ExtensionEntry>> = flowOf(emptyList())
        override suspend fun refreshExtensionIndex() {}
        override suspend fun getRepos(): List<MangaRepoInfo> = emptyList()
        override suspend fun setRepos(newRepos: List<MangaRepoInfo>) {}
        override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun uninstallExtension(pkgName: String) {}
        override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {}
        override suspend fun getExtensionIcon(pkgName: String): ByteArray? = null
        override suspend fun setShowNsfwSources(enabled: Boolean) {}
        override suspend fun getShowNsfwSources(): Boolean = false
    }

    // ---------- Harness ----------

    private lateinit var tempRoot: File
    private lateinit var chapterRepo: FakeChapterRepo
    private lateinit var mangaRepo: FakeMangaRepo
    private lateinit var historyRepo: FakeHistoryRepo
    private lateinit var settingsRepo: FakeSettingsRepo

    private val sourceId = 1L
    private val mangaUrl = "/manga/one"
    private val mId = mangaId(sourceId, mangaUrl)
    private lateinit var manga: MangaEntry

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-manga-reader-")
        chapterRepo = FakeChapterRepo()
        mangaRepo = FakeMangaRepo()
        historyRepo = FakeHistoryRepo()
        settingsRepo = FakeSettingsRepo()
        manga = MangaEntry(
            id = mId,
            sourceId = sourceId,
            sourceName = "Fake",
            url = mangaUrl,
            title = "Test Manga",
            inLibrary = true,
            initialized = true,
        )
        mangaRepo.entries[mId] = manga
        // Ascending chapter order keeps navList intuitive in these tests.
        settingsRepo.values["manga.chapter.sort.$mId"] = "ASC"
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun seedChapters(pageCounts: Map<String, Int>): List<MangaChapter> {
        val list = pageCounts.entries.mapIndexed { index, (url, pages) ->
            MangaChapter(
                id = chapterId(mId, url),
                mangaId = mId,
                url = url,
                name = "Chapter ${index + 1}",
                sortOrder = index,
                totalPages = pages,
            )
        }
        list.forEach { chapterRepo.chapters[it.id] = it }
        return list
    }

    private fun pageListsFor(chapters: List<MangaChapter>): Map<String, List<MangaPageRef>> =
        chapters.associate { ch ->
            ch.url to List(ch.totalPages) { i -> MangaPageRef(index = i, url = "${ch.url}/p$i") }
        }

    private fun newReaderVm(
        backend: FakeBackend,
        downloadManager: MangaDownloadManager? = null,
    ) = MangaReaderViewModel(
        backend = backend,
        downloadManager = downloadManager,
        chapterRepo = chapterRepo,
        mangaRepo = mangaRepo,
        historyRepo = historyRepo,
        noteRepo = FakeNoteRepo(),
        settingsRepo = settingsRepo,
        fileSystem = DesktopPlatform(tempRoot).fileSystem,
        sessionRepo = null,
    )

    private fun downloadManager(): MangaDownloadManager = MangaDownloadManager(
        backend = FakeBackend(emptyMap()),
        downloadsRepo = object : MangaDownloadRepository {
            override suspend fun enqueue(download: MangaDownload) {}
            override suspend fun update(download: MangaDownload) {}
            override suspend fun remove(id: String) {}
            override suspend fun clearFinished() {}
            override fun observeQueue(): Flow<List<MangaDownload>> = flowOf(emptyList())
            override suspend fun isChapterDownloaded(chapterId: String): Boolean = false
        },
        chapterRepo = chapterRepo,
        initialStorage = FileDownloadStorage(File(tempRoot, "downloads")),
    )

    private fun seedDownloadedPages(chapter: MangaChapter, count: Int) {
        val dir = File(tempRoot, "downloads/${chapter.mangaId}/${chapter.id}").apply { mkdirs() }
        repeat(count) { index -> File(dir, "%03d.png".format(index + 1)).writeBytes(byteArrayOf(1)) }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 8000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out: $message")
            delay(20)
        }
    }

    // ---------- Offline downloads ----------

    @Test
    fun fullyDownloadedChapterOpensOfflineAfterQueueMarkerIsCleared() = runBlocking {
        val chapter = seedChapters(linkedMapOf("/c1" to 3)).single().copy(downloadedPages = 3)
        chapterRepo.chapters[chapter.id] = chapter
        seedDownloadedPages(chapter, 3)
        val backend = FakeBackend(emptyMap())
        val vm = newReaderVm(backend, downloadManager())

        vm.open(manga, chapter)
        awaitCondition(message = "downloaded chapter opens without its queue marker") {
            !vm.loading.value && vm.pages.value.size == 3
        }

        assertEquals(null, vm.error.value)
        assertTrue(backend.pageListFetches.isEmpty(), "the offline chapter must not contact the source")
        vm.close()
    }

    @Test
    fun incompleteLocalChapterStillFallsBackToSource() = runBlocking {
        val chapter = seedChapters(linkedMapOf("/c1" to 3)).single().copy(downloadedPages = 3)
        chapterRepo.chapters[chapter.id] = chapter
        seedDownloadedPages(chapter, 2)
        val backend = FakeBackend(pageListsFor(listOf(chapter)))
        val vm = newReaderVm(backend, downloadManager())

        vm.open(manga, chapter)
        awaitCondition(message = "incomplete local chapter uses source page list") {
            !vm.loading.value && vm.pages.value.size == 3
        }

        assertEquals(listOf(chapter.url), backend.pageListFetches)
        vm.close()
    }

    // ---------- Position-aware loading ----------

    @Test
    fun openPublishesCurrentChapterBeforeNeighbours() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        val (c1, c2, c3) = chapters
        val nextGate = CompletableDeferred<Unit>()
        val backend = FakeBackend(
            pageListsFor(chapters),
            gates = mapOf("/c3" to nextGate),
        )
        val vm = newReaderVm(backend)

        vm.open(manga, c2)
        // Current + previous assemble before the first publish; next is still gated.
        awaitCondition(message = "current+previous render while next is gated") {
            !vm.loading.value && vm.pages.value.size == 6
        }
        assertEquals(c2.id, vm.chapter.value?.id, "the opened chapter is live")
        assertEquals(3, vm.currentIndex.value, "resume index offset by the prepended previous chapter")

        nextGate.complete(Unit)
        awaitCondition(message = "next splices in once it arrives") { vm.pages.value.size == 9 }
        assertEquals(c2.url, backend.pageListFetches.first(), "current chapter's page list is fetched first")
        assertEquals(setOf("/c1", "/c2", "/c3"), backend.pageListFetches.toSet(), "the window fully loads")
        assertEquals(3, vm.currentIndex.value)
        assertEquals(c2.id, vm.chapter.value?.id)
        vm.close()
    }

    @Test
    fun readerNavigatesStoryOrderWhenDisplaySortDescending() = runBlocking {
        // The detail screen's descending display toggle must not bend reader
        // navigation: forward scrolling still advances through the story.
        settingsRepo.values["manga.chapter.sort.$mId"] = "DESC"
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        val (_, c2, _) = chapters
        val backend = FakeBackend(pageListsFor(chapters))
        val vm = newReaderVm(backend)

        vm.open(manga, c2)
        awaitCondition(message = "window assembles in story order") { vm.pages.value.size == 9 }
        assertEquals(3, vm.currentIndex.value, "the prepended slot is the story-earlier chapter")
        assertEquals(c2.id, vm.chapter.value?.id)
        vm.close()
    }

    // ---------- Unread state / forward finalization ----------

    @Test
    fun forwardChapterCrossingMarksLeftBehindChapterRead() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        val (c1, c2, c3) = chapters
        val backend = FakeBackend(pageListsFor(chapters))
        val vm = newReaderVm(backend)

        vm.open(manga, c2)
        awaitCondition(message = "window assembles") { vm.pages.value.size == 9 }

        // The reader scrolls forward into chapter 3's first page.
        val nextStart = vm.pages.value.size - 3
        vm.onPageChanged(nextStart)

        awaitCondition(message = "chapter left behind finalizes as read") {
            chapterRepo.chapters[c2.id]?.read == true
        }
        assertEquals(2, chapterRepo.chapters[c2.id]?.lastPageRead, "finalized chapter keeps its last page")
        assertEquals(3, chapterRepo.chapters[c2.id]?.totalPages)
        assertEquals(c3.id, historyRepo.records.last().second, "history follows the crossing")
        assertFalse(chapterRepo.chapters[c1.id]!!.read, "chapters before the session start are never finalized")
        assertFalse(chapterRepo.chapters[c3.id]!!.read, "the entered chapter stays unread until finished")
        vm.close()
    }

    // ---------- Progress persistence ----------

    @Test
    fun reachingLastPageMarksChapterRead() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3))
        val (c1, _) = chapters
        val backend = FakeBackend(pageListsFor(chapters))
        val vm = newReaderVm(backend)

        vm.open(manga, c1)
        awaitCondition(message = "window assembles") { vm.pages.value.size == 6 }

        vm.onPageChanged(2)
        awaitCondition(message = "last page persists and marks read") {
            chapterRepo.chapters[c1.id]?.read == true
        }
        assertEquals(2, chapterRepo.chapters[c1.id]?.lastPageRead)
        vm.close()
    }

    @Test
    fun closePersistsPositionEvenWhenThrottleSkippedIt() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 5, "/c2" to 3))
        val (c1, _) = chapters
        val backend = FakeBackend(pageListsFor(chapters))
        val vm = newReaderVm(backend)

        vm.open(manga, c1)
        awaitCondition(message = "window assembles") { vm.pages.value.size == 8 }

        // First move saves; the second arrives inside the throttle window and would
        // previously have been lost on exit — close() must write the authoritative state.
        vm.onPageChanged(1)
        awaitCondition(message = "first save lands") { chapterRepo.chapters[c1.id]?.lastPageRead == 1 }
        vm.onPageChanged(2)
        vm.close()

        assertEquals(2, chapterRepo.chapters[c1.id]?.lastPageRead, "final position must survive the throttle")
    }

    @Test
    fun progressSnapshotsLandInReadingOrder() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 6))
        val (c1) = chapters
        val backend = FakeBackend(pageListsFor(chapters))
        val vm = newReaderVm(backend)

        vm.open(manga, c1)
        awaitCondition(message = "pages ready") { vm.pages.value.size == 6 }

        // Rapid forward moves within one throttle window: whatever lands, the last write
        // must be the furthest page (no stale snapshot may overwrite a newer one).
        vm.onPageChanged(1)
        vm.onPageChanged(2)
        vm.onPageChanged(3)
        vm.onPageChanged(4)
        vm.close()

        val writesForChapter = chapterRepo.progressWrites.filter { it.first == c1.id }
        assertTrue(writesForChapter.isNotEmpty(), "progress must be persisted")
        assertEquals(4, chapterRepo.chapters[c1.id]?.lastPageRead, "latest position wins")
        assertEquals(4, writesForChapter.last().second, "writes end in reading order")
    }

    // ---------- Continue Reading resume ----------

    @Test
    fun continuePrefersInProgressChapterOverHistory() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        val (c1, c2, c3) = chapters
        chapterRepo.chapters[c1.id] = c1.copy(read = true, lastPageRead = 2)
        chapterRepo.chapters[c2.id] = c2.copy(read = true, lastPageRead = 2)
        chapterRepo.chapters[c3.id] = c3.copy(read = false, lastPageRead = 1, totalPages = 3)
        // History lags behind the actual in-progress chapter.
        historyRepo.recent = listOf(
            MangaHistoryItem(mangaId = mId, chapterId = c1.id, readAt = Clock.System.now()),
        )
        val vm = MangaDetailViewModel(
            backend = FakeBackend(emptyMap()),
            mangaRepo = mangaRepo,
            chapterRepo = chapterRepo,
            historyRepo = historyRepo,
            downloadManager = null,
            downloadRepo = null,
            categoryRepo = FakeCategoryRepo(),
            settingsRepo = settingsRepo,
        )
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.manga.value != null && vm.chapters.value.isNotEmpty() }

        assertEquals(c3.id, vm.nextChapterToRead()?.id, "in-progress chapter wins over stale history")
    }

    @Test
    fun continueResumesAfterFinishedChapter() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        val (c1, c2, c3) = chapters
        chapterRepo.chapters[c1.id] = c1.copy(read = true, lastPageRead = 2)
        chapterRepo.chapters[c2.id] = c2.copy(read = true, lastPageRead = 2)
        historyRepo.recent = listOf(
            MangaHistoryItem(mangaId = mId, chapterId = c2.id, readAt = Clock.System.now()),
        )
        val vm = MangaDetailViewModel(
            backend = FakeBackend(emptyMap()),
            mangaRepo = mangaRepo,
            chapterRepo = chapterRepo,
            historyRepo = historyRepo,
            downloadManager = null,
            downloadRepo = null,
            categoryRepo = FakeCategoryRepo(),
            settingsRepo = settingsRepo,
        )
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.manga.value != null && vm.chapters.value.isNotEmpty() }

        assertEquals(c3.id, vm.nextChapterToRead()?.id, "a finished history chapter continues to the next")

        // Unfinished chapter resumes in place.
        chapterRepo.chapters[c2.id] = c2.copy(read = false, lastPageRead = 1)
        assertEquals(c2.id, vm.nextChapterToRead()?.id, "an unfinished chapter resumes in place")
    }

    private fun detailVm() = MangaDetailViewModel(
        backend = FakeBackend(emptyMap()),
        mangaRepo = mangaRepo,
        chapterRepo = chapterRepo,
        historyRepo = historyRepo,
        downloadManager = null,
        downloadRepo = null,
        categoryRepo = FakeCategoryRepo(),
        settingsRepo = settingsRepo,
    )

    @Test
    fun continueAfterMarkPreviousGoesToFirstUnread() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3, "/c4" to 3, "/c5" to 3))
        val (c1, c2, c3, _, _) = chapters
        chapterRepo.chapters[c1.id] = c1.copy(read = true, lastPageRead = 2)
        // History points at the chapter the reader physically left off in; after
        // mark-previous it is read, so Continue must skip it for the first unread.
        historyRepo.recent = listOf(
            MangaHistoryItem(mangaId = mId, chapterId = c1.id, readAt = Clock.System.now()),
        )
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.manga.value != null && vm.chapters.value.isNotEmpty() }

        vm.markPreviousAsRead(c3)
        awaitCondition(message = "previous chapters marked read") {
            chapterRepo.chapters[c2.id]?.read == true
        }

        assertEquals(c3.id, vm.nextChapterToRead()?.id,
            "Continue lands on the first unread, not the stale left-off chapter")
    }

    @Test
    fun markPreviousIsStoryOrderRegardlessOfDisplaySort() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3, "/c4" to 3, "/c5" to 3))
        val (c1, c2, c3, c4, c5) = chapters
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }
        vm.sortAscending.value = false

        vm.markPreviousAsRead(c3)
        awaitCondition(message = "story-earlier chapters marked read") {
            chapterRepo.chapters[c1.id]?.read == true && chapterRepo.chapters[c2.id]?.read == true
        }
        assertFalse(chapterRepo.chapters[c4.id]!!.read, "the display sort never changes which chapters count as previous")
        assertFalse(chapterRepo.chapters[c5.id]!!.read, "the display sort never changes which chapters count as previous")
    }

    @Test
    fun continueRespectsActiveChapterFilter() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        val (c1, c2, _) = chapters
        chapterRepo.chapters[c2.id] = c2.copy(bookmarked = true)
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }
        vm.setChapterFilter(ChapterFilter.BOOKMARKED)

        assertEquals(c2.id, vm.nextChapterToRead()?.id,
            "with a bookmark filter, Continue jumps to the first unread bookmarked chapter")
    }

    @Test
    fun continueAllReadGoesToFirstChapter() = runBlocking {
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3))
        chapters.forEach { chapterRepo.chapters[it.id] = it.copy(read = true) }
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }

        assertEquals(chapters.first().id, vm.nextChapterToRead()?.id,
            "everything read falls back to the first chapter")
    }

    // ---------- Newest-first sources (JJK repro) ----------

    private fun seedNewestFirst(names: List<String>): List<MangaChapter> {
        // sortOrder 0 is the LATEST chapter, mirroring sources that list newest-first;
        // chapterNumber stays unset (-1), so story order must come from the titles.
        val list = names.mapIndexed { index, name ->
            MangaChapter(
                id = chapterId(mId, "/n$index"),
                mangaId = mId,
                url = "/n$index",
                name = name,
                sortOrder = index,
                totalPages = 3,
            )
        }
        list.forEach { chapterRepo.chapters[it.id] = it }
        return list
    }

    @Test
    fun continueFollowsStoryOrderForNewestFirstSource() = runBlocking {
        val list = seedNewestFirst(listOf("Chapter 6", "Chapter 5", "Chapter 4", "Chapter 3", "Chapter 2", "Chapter 1"))
        list.filter { it.name in setOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4") }
            .forEach { chapterRepo.chapters[it.id] = it.copy(read = true) }
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }

        assertEquals("Chapter 5", vm.nextChapterToRead()?.name,
            "Continue lands on the first story-unread chapter, not the newest one")
    }

    @Test
    fun markPreviousIsStoryOrderForNewestFirstSource() = runBlocking {
        val list = seedNewestFirst(listOf("Chapter 5", "Chapter 4", "Chapter 3", "Chapter 2", "Chapter 1"))
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }

        vm.markPreviousAsRead(list.first { it.name == "Chapter 3" })
        val byName = { name: String -> chapterRepo.chapters[list.first { it.name == name }.id]!! }
        awaitCondition(message = "story-earlier chapters marked read") {
            byName("Chapter 1").read && byName("Chapter 2").read
        }
        assertFalse(byName("Chapter 4").read, "story-later chapters are never 'previous'")
        assertFalse(byName("Chapter 5").read, "story-later chapters are never 'previous'")
    }

    @Test
    fun readerNavigatesStoryOrderForNewestFirstSource() = runBlocking {
        val list = seedNewestFirst(listOf("Chapter 4", "Chapter 3", "Chapter 2", "Chapter 1"))
        val backend = FakeBackend(pageListsFor(list))
        val vm = newReaderVm(backend)

        vm.open(manga, list.first { it.name == "Chapter 2" })
        awaitCondition(message = "window assembles in story order") { vm.pages.value.size == 9 }
        assertEquals(3, vm.currentIndex.value, "the prepended slot is the story-earlier chapter")
        vm.close()
    }

    @Test
    fun chapterNumberParserRecoversNumbersFromTitles() {
        assertEquals(272f, ChapterNumberParser.parse("Chapter 272"))
        assertEquals(262.5f, ChapterNumberParser.parse("Ch. 262.5"))
        assertEquals(12f, ChapterNumberParser.parse("Vol.3 Ch.12"))
        assertEquals(100f, ChapterNumberParser.parse("One Piece 100"))
        assertEquals(-1f, ChapterNumberParser.parse("Oneshot"))
    }

    @Test
    fun staleProgressOnLaterChapterDoesNotHijackContinue() = runBlocking {
        // JJK repro: chapters 1-4 read, chapter 5 never opened, but the last chapter
        // carries leftover partial progress from an old session. Continue must land on
        // chapter 5, not jump to the chapter with stale progress.
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3, "/c4" to 3, "/c5" to 3, "/c6" to 3))
        val c5 = chapters[4]
        chapters.take(4).forEach { chapterRepo.chapters[it.id] = it.copy(read = true, lastPageRead = 2) }
        chapterRepo.chapters[chapters[5].id] = chapters[5].copy(read = false, lastPageRead = 1)
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }

        assertEquals(c5.id, vm.nextChapterToRead()?.id,
            "Continue lands on the first untouched unread chapter, ignoring stale later progress")
    }

    @Test
    fun continueIsStoryOrderRegardlessOfDisplaySort() = runBlocking {
        // The display sort toggle only arranges the chapter list; Continue must keep
        // resolving through the story (source list order) in either direction.
        val chapters = seedChapters(linkedMapOf("/c1" to 3, "/c2" to 3, "/c3" to 3, "/c4" to 3, "/c5" to 3))
        val (c1, c2, c3, c4, c5) = chapters
        listOf(c1, c2, c3, c4).forEach { chapterRepo.chapters[it.id] = it.copy(read = true, lastPageRead = 2) }
        val vm = detailVm()
        vm.open(mId)
        awaitCondition(message = "detail loads") { vm.chapters.value.isNotEmpty() }
        vm.sortAscending.value = false

        assertEquals(c5.id, vm.nextChapterToRead()?.id,
            "Continue stays in story order even under a descending display toggle")
    }

    @Test
    fun libraryRestoresLastViewedCategoryAndPersistsSelections() = runBlocking {
        val catRepo = FakeCategoryRepo()
        catRepo.categoriesList = listOf(
            MangaCategory(id = "main", name = "Main"),
            MangaCategory(id = "reading", name = "Reading"),
        )
        val settings = FakeSettingsRepo(mutableMapOf("manga.library.category" to "reading"))
        val vm = MangaLibraryViewModel(
            backend = FakeBackend(emptyMap()),
            mangaRepo = mangaRepo,
            categoryRepo = catRepo,
            chapterRepo = chapterRepo,
            settingsRepo = settings,
        )

        awaitCondition(message = "remembered category restored on startup") {
            vm.selectedCategoryId.value == "reading"
        }

        vm.selectCategory("main")
        awaitCondition(message = "new selection persisted") {
            settings.values["manga.library.category"] == "main"
        }
    }
}

/** Relevance ranking: normalization, scoring tiers, stable ordering. */
class MangaSearchRankingTest {

    @Test
    fun normalizeFoldsCasePunctuationAndWhitespace() {
        assertEquals("one piece", MangaSearchRanker.normalize("  One-Piece!! "))
        assertEquals("berserk", MangaSearchRanker.normalize("Berserk."))
        assertEquals("kaguya sama wa kokurasetai", MangaSearchRanker.normalize("Kaguya-sama wa Kokurasetai"))
    }

    @Test
    fun scoreTiersRankExactAbovePrefixAboveTokenMatches() {
        val exact = MangaSearchRanker.score("one piece", "One Piece")
        val prefix = MangaSearchRanker.score("one piece", "One Piece: Great Adventures")
        val tokens = MangaSearchRanker.score("one piece", "Piece of One")
        val partial = MangaSearchRanker.score("one piece", "Two Pieces of Nothing")
        val none = MangaSearchRanker.score("one piece", "Totally Different Manga")

        assertTrue(exact > prefix, "exact match outranks prefix match")
        assertTrue(prefix > tokens, "prefix match outranks scattered token match")
        assertTrue(tokens > partial, "all-token match outranks partial match")
        assertTrue(partial > none, "partial match outranks no textual match")
        assertEquals(0, none)
    }

    @Test
    fun rankPutsStrongestMatchesFirstAndKeepsSourceOrderOnTies() {
        val items = listOf(
            com.folio.reader.manga.MangaBrowseItem(url = "/a", title = "Piece of One"),
            com.folio.reader.manga.MangaBrowseItem(url = "/b", title = "One Piece"),
            com.folio.reader.manga.MangaBrowseItem(url = "/c", title = "One Piece: Great Adventures"),
            com.folio.reader.manga.MangaBrowseItem(url = "/d", title = "One Piece: Another Tale"),
            com.folio.reader.manga.MangaBrowseItem(url = "/e", title = "Unrelated"),
        )
        val ranked = MangaSearchRanker.rank(items, "one piece")
        assertEquals(listOf("/b", "/c", "/d", "/a", "/e"), ranked.map { it.url },
            "exact first, prefix ties keep source order, token match next, unrelated last")
    }

    @Test
    fun blankQueryLeavesSourceOrderUntouched() {
        val items = listOf(
            com.folio.reader.manga.MangaBrowseItem(url = "/a", title = "B"),
            com.folio.reader.manga.MangaBrowseItem(url = "/b", title = "A"),
        )
        assertEquals(items, MangaSearchRanker.rank(items, "  "))
    }
}
