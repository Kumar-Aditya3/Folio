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
import com.folio.reader.manga.MangaDownloadRepository
import com.folio.reader.manga.MangaDownloadStatus
import com.folio.reader.manga.MangaDownloadStorage
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaImageData
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The download pool runs multiple workers over the same queue. Chapter pickup must
 * be an atomic claim — two workers can never process the same row — and a cancel
 * during flight must not be resurrected by the worker's next progress write (the
 * old INSERT OR REPLACE update made cancelled rows come back as zombie
 * DOWNLOADING/ERROR entries).
 */
class MangaParallelDownloadTest {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database

    private lateinit var chapters: JdbcMangaChapterRepository
    private lateinit var queue: JdbcMangaDownloadRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-parallel-dl-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        chapters = JdbcMangaChapterRepository(database)
        queue = JdbcMangaDownloadRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun `jdbc claim returns each queued row exactly once`() = runBlocking {
        seedChapters("c1", "c2", "c3", "c4", "c5")
        val ids = listOf("c1", "c2", "c3", "c4", "c5")
        ids.forEachIndexed { i, id ->
            queue.enqueue(MangaDownload(id = id, mangaId = MANGA_ID, chapterId = id))
            // Distinct queued_at ordering so "first QUEUED by queued_at" is deterministic.
            delay(5)
        }

        val claimed = mutableListOf<String>()
        while (true) {
            val row = queue.claimNextQueued() ?: break
            claimed += row.id
            assertEquals(MangaDownloadStatus.DOWNLOADING, row.status, "a claimed row arrives as DOWNLOADING")
        }

        assertEquals(ids, claimed, "claims come out in queued_at order and cover every chapter once")
        assertNull(queue.claimNextQueued(), "a drained queue has nothing left to claim")
    }

    @Test
    fun `three workers process five chapters exactly once`() = runBlocking {
        val ids = listOf("c1", "c2", "c3", "c4", "c5")
        seedChapters(*ids.toTypedArray())
        ids.forEach { id ->
            queue.enqueue(MangaDownload(id = id, mangaId = MANGA_ID, chapterId = id))
        }

        val storage = FakeStorage()
        val backend = CountingBackend(pageCount = 2)
        val manager = MangaDownloadManager(backend, queue, chapters, storage, parallelChapters = 3)

        manager.start()
        try {
            awaitAll(ids, MangaDownloadStatus.DOWNLOADED)
        } finally {
            manager.stop()
        }

        ids.forEach { id ->
            assertEquals(1, backend.pageListAttempts["https://test/$id"], "chapter $id's page list is fetched exactly once")
            assertEquals(2, storage.names("$MANGA_ID/$id").size, "chapter $id's pages are written once")
        }
    }

    @Test
    fun `cancel during flight does not resurrect the row`() = runBlocking {
        seedChapters("c1")
        val id = "c1"
        queue.enqueue(MangaDownload(id = id, mangaId = MANGA_ID, chapterId = id))

        val storage = FakeStorage()
        val gate = CompletableDeferred<Unit>()
        val backend = GatedBackend(pageCount = 4, pageListGate = gate)
        val manager = MangaDownloadManager(backend, queue, chapters, storage, parallelChapters = 1)

        manager.start()
        try {
            // Wait until the worker is mid-chapter, then cancel like the UI does.
            awaitStatus(id, MangaDownloadStatus.DOWNLOADING)
            manager.cancel(id)

            // Release the in-flight fetch; the worker's progress writes land after
            // the cancel and must not bring the deleted row back.
            gate.complete(Unit)
            delay(1500)

            val row = queue.observeQueue().first().firstOrNull { it.id == id }
            assertNull(row, "a cancelled row stays deleted — no zombie DOWNLOADING/ERROR entry")
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `default claim flips the first queued row to downloading`() = runBlocking {
        val repo = FakeQueueRepo()
        repo.enqueue(MangaDownload(id = "a", mangaId = MANGA_ID, chapterId = "a"))
        repo.enqueue(MangaDownload(id = "b", mangaId = MANGA_ID, chapterId = "b"))

        val first = repo.claimNextQueued()
        assertEquals("a", first?.id, "the first QUEUED row by queue order is claimed")
        assertEquals(MangaDownloadStatus.DOWNLOADING, first?.status)

        val second = repo.claimNextQueued()
        assertEquals("b", second?.id, "a DOWNLOADING row is never re-claimed")

        assertNull(repo.claimNextQueued(), "nothing left to claim")
        assertFalse(repo.rows.values.any { it.status == MangaDownloadStatus.QUEUED && it.id == "a" })
    }

    // ---------- Helpers ----------

    private suspend fun seedChapters(vararg ids: String) {
        chapters.replaceChapters(
            MANGA_ID,
            ids.mapIndexed { i, id ->
                MangaChapter(id = id, mangaId = MANGA_ID, url = "https://test/$id", name = "Chapter ${i + 1}")
            },
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
        fail("chapter $id never reached $want (last ${last?.status})")
    }

    private suspend fun awaitAll(ids: List<String>, want: MangaDownloadStatus, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rows = queue.observeQueue().first()
            if (rows.isNotEmpty() && rows.all { it.status == want }) return
            delay(50)
        }
        fail("chapters never all reached $want")
    }

    private companion object {
        const val MANGA_ID = "9:m1"
        val PAGE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5)
    }

    /** In-memory queue with the interface's default claim implementation exercised. */
    private class FakeQueueRepo : MangaDownloadRepository {
        val rows = LinkedHashMap<String, MangaDownload>()
        private val revision = MutableStateFlow(0L)

        override suspend fun enqueue(download: MangaDownload) {
            rows[download.id] = download
            revision.value += 1
        }

        override suspend fun update(download: MangaDownload) {
            // Mirror the JDBC contract: update never inserts, so a removed row
            // stays removed. This is what the cancel-during-flight guarantee
            // relies on.
            if (download.id in rows) {
                rows[download.id] = download
                revision.value += 1
            }
        }

        override suspend fun remove(id: String) {
            rows.remove(id)
            revision.value += 1
        }

        override suspend fun clearFinished() {}

        override fun observeQueue(): Flow<List<MangaDownload>> =
            kotlinx.coroutines.flow.flow { revision.collect { emit(rows.values.toList()) } }

        override suspend fun isChapterDownloaded(chapterId: String): Boolean =
            rows[chapterId]?.status == MangaDownloadStatus.DOWNLOADED
    }

    private class FakeStorage : MangaDownloadStorage {
        private val dirs = mutableMapOf<String, MutableMap<String, ByteArray>>()

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

    /** Counts page-list fetches per chapter; two workers on one chapter would double it. */
    private class CountingBackend(private val pageCount: Int) : MangaBackend {
        val pageListAttempts = ConcurrentHashMap<String, Int>()
        private val fetched = Collections.synchronizedList(mutableListOf<Int>())

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

        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> {
            pageListAttempts.merge(chapter.url, 1) { a, b -> a + b }
            return (0 until pageCount).map { MangaPageRef(index = it, url = "page-$it") }
        }

        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData {
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

    /** Holds the first page-image fetch until a gate opens, so a cancel can land mid-flight. */
    private class GatedBackend(
        private val pageCount: Int,
        private val pageListGate: CompletableDeferred<Unit>,
    ) : MangaBackend {
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
            if (page.index == 0) pageListGate.await()
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
