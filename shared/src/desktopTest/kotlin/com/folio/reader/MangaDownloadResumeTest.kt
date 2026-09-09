package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaDownloadRepository
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowsePage
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaDownload
import com.folio.reader.manga.MangaDownloadManager
import com.folio.reader.manga.MangaDownloadStatus
import com.folio.reader.manga.MangaDownloadStorage
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaImageData
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Downloads have to survive the app being closed. Android freezes or kills the
 * process, the socket timeout fires while it is frozen, and the queue row is left
 * DOWNLOADING or written off as ERROR — the loop only ever picked up QUEUED rows,
 * so the chapter never came back and the reader was told "Failed · timed out".
 * These pin the recovery: interrupted chapters re-queue, pages already on disk are
 * kept, and one bad page does not condemn a whole chapter.
 */
class MangaDownloadResumeTest {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database

    private lateinit var chapters: JdbcMangaChapterRepository
    private lateinit var queue: JdbcMangaDownloadRepository
    private lateinit var storage: FakeStorage
    private lateinit var backend: FakeBackend
    private lateinit var manager: MangaDownloadManager

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-dl-resume-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        chapters = JdbcMangaChapterRepository(database)
        queue = JdbcMangaDownloadRepository(database)
        storage = FakeStorage()
        backend = FakeBackend(pageCount = 4)
        manager = MangaDownloadManager(backend, queue, chapters, storage)
    }

    @AfterTest
    fun tearDown() {
        manager.stop()
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun seedChapter(id: String = CHAPTER) {
        chapters.replaceChapters(
            MANGA_ID,
            listOf(MangaChapter(id = id, mangaId = MANGA_ID, url = "https://test/$id", name = "Chapter $id")),
        )
    }

    private suspend fun seedRow(status: MangaDownloadStatus, error: String? = null, pages: Int = 0) {
        queue.enqueue(
            MangaDownload(
                id = CHAPTER,
                mangaId = MANGA_ID,
                chapterId = CHAPTER,
                status = status,
                totalPages = pages,
                error = error,
            )
        )
    }

    private suspend fun awaitStatus(
        id: String,
        want: MangaDownloadStatus,
        timeoutMs: Long = 30_000,
    ): MangaDownload {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: MangaDownload? = null
        while (System.currentTimeMillis() < deadline) {
            last = queue.observeQueue().first().firstOrNull { it.id == id }
            if (last?.status == want) return last
            delay(50)
        }
        fail("chapter $id never reached $want (last seen ${last?.status}, error ${last?.error})")
    }

    @Test
    fun `a chapter interrupted mid-download resumes from the pages already on disk`() = runBlocking {
        seedChapter()
        storage.seed("$MANGA_ID/$CHAPTER", "001.png", PAGE)
        storage.seed("$MANGA_ID/$CHAPTER", "002.png", PAGE)
        seedRow(MangaDownloadStatus.DOWNLOADING, pages = 4)

        manager.start()
        val done = awaitStatus(CHAPTER, MangaDownloadStatus.DOWNLOADED)

        assertEquals(listOf(2, 3), backend.fetched.sorted(), "only the missing pages are fetched again")
        assertEquals(4, done.downloadedPages)
        assertEquals(4, storage.names("$MANGA_ID/$CHAPTER").size)
        assertEquals(4, manager.downloadedPageCount(MANGA_ID, CHAPTER), "the reader may now serve it offline")
    }

    @Test
    fun `a chapter written off by a timeout goes back in the queue on the next start`() = runBlocking {
        seedChapter()
        seedRow(MangaDownloadStatus.ERROR, error = "timeout", pages = 4)

        manager.start()
        val done = awaitStatus(CHAPTER, MangaDownloadStatus.DOWNLOADED)

        assertNull(done.error, "the stale failure must not survive a successful retry")
        assertEquals(listOf(0, 1, 2, 3), backend.fetched.sorted())
        assertEquals(4, done.downloadedPages)
    }

    @Test
    fun `one page timing out does not fail the chapter`() = runBlocking {
        seedChapter()
        backend.failFirstAttemptFor = setOf(2)
        seedRow(MangaDownloadStatus.QUEUED)

        manager.start()
        val done = awaitStatus(CHAPTER, MangaDownloadStatus.DOWNLOADED)

        assertEquals(2, backend.attempts[2], "the page that failed is tried again")
        assertEquals(1, backend.attempts[0], "pages that arrive first time are not refetched")
        assertNull(done.error)
        assertEquals(4, storage.names("$MANGA_ID/$CHAPTER").size)
    }

    @Test
    fun `a page that never arrives still reports the failure`() = runBlocking {
        seedChapter()
        backend.alwaysFailFor = setOf(1)
        seedRow(MangaDownloadStatus.QUEUED)

        manager.start()
        val failed = awaitStatus(CHAPTER, MangaDownloadStatus.ERROR)

        assertEquals("timed out", failed.error)
        assertEquals(3, backend.attempts[1], "retries are bounded, then the chapter is written off")
    }

    @Test
    fun `starting twice does not download the chapter twice`() = runBlocking {
        seedChapter()
        seedRow(MangaDownloadStatus.QUEUED)

        manager.start()
        manager.start()
        val done = awaitStatus(CHAPTER, MangaDownloadStatus.DOWNLOADED)

        assertEquals(listOf(0, 1, 2, 3), backend.fetched.sorted(), "one loop, one pass over the pages")
        assertEquals(4, done.downloadedPages)
    }

    /** Pages are written under "<mangaId>/<chapterId>/NNN.ext"; mangaId carries the source. */
    private companion object {
        const val MANGA_ID = "7:m1"
        const val CHAPTER = "c1"
        val PAGE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5)
    }

    /** In-memory stand-in for the download folder, so a test can pre-seed a partial chapter. */
    private class FakeStorage : MangaDownloadStorage {
        private val dirs = mutableMapOf<String, MutableMap<String, ByteArray>>()

        fun seed(path: String, name: String, bytes: ByteArray) {
            dirs.getOrPut(path) { mutableMapOf() }[name] = bytes
        }

        fun names(path: String): List<String> = dirs[path]?.keys?.toList() ?: emptyList()

        override fun ensureDir(relativePath: String): Boolean {
            dirs.getOrPut(relativePath) { mutableMapOf() }
            return true
        }

        override fun write(relativePath: String, fileName: String, bytes: ByteArray): Boolean {
            dirs.getOrPut(relativePath) { mutableMapOf() }[fileName] = bytes
            return true
        }

        override fun readFirst(relativePath: String, fileNamePrefix: String): ByteArray? =
            dirs[relativePath]?.entries?.firstOrNull { it.key.startsWith(fileNamePrefix) }?.value

        override fun listFiles(relativePath: String): List<String> = names(relativePath)

        override fun listNonEmptyFiles(relativePath: String): List<String> =
            dirs[relativePath]?.filterValues { it.isNotEmpty() }?.keys?.toList() ?: emptyList()

        override fun listSubDirs(relativePath: String): List<String> = emptyList()

        override fun deleteDir(relativePath: String) {
            dirs.remove(relativePath)
        }

        override fun describe(): String = "fake"
    }

    /** Serves a fixed page list and records what was asked for, with injectable failures. */
    private class FakeBackend(private val pageCount: Int) : MangaBackend {
        val fetched: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val attempts = ConcurrentHashMap<Int, Int>()
        var failFirstAttemptFor: Set<Int> = emptySet()
        var alwaysFailFor: Set<Int> = emptySet()

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
            MangaDetail(url = mangaUrl, title = "Stub")

        override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> = emptyList()

        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> =
            (0 until pageCount).map { MangaPageRef(index = it, url = "page-$it") }

        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData {
            val attempt = attempts.merge(page.index, 1) { a, b -> a + b }
            if (page.index in alwaysFailFor) throw java.io.IOException("timed out")
            if (page.index in failFirstAttemptFor && attempt == 1) throw java.io.IOException("timed out")
            fetched.add(page.index)
            return MangaImageData(PAGE, "image/png")
        }

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
}
