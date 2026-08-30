package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A library refresh replaces the chapter list wholesale; user state attached to
 * chapters (read, bookmark, download markers, page progress) must survive it,
 * and newly arrived chapters must surface as unread.
 */
class MangaChapterPreservationTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var chapters: JdbcMangaChapterRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-manga-preserve-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        chapters = JdbcMangaChapterRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun refreshPreservesReadBookmarkDownloadAndProgress() = runBlocking {
        val mangaId = "1:/manga/one"
        chapters.replaceChapters(
            mangaId,
            listOf(
                MangaChapter(id = "$mangaId|/c1", mangaId = mangaId, url = "/c1", name = "Chapter 1", sortOrder = 0),
                MangaChapter(id = "$mangaId|/c2", mangaId = mangaId, url = "/c2", name = "Chapter 2", sortOrder = 1),
            ),
        )
        chapters.markRead(listOf("$mangaId|/c1"), true, emitSyncEvent = false)
        chapters.setBookmarked("$mangaId|/c2", true, emitSyncEvent = false)
        chapters.saveProgress("$mangaId|/c2", 4, 10, emitSyncEvent = false)
        chapters.setDownloadedPages("$mangaId|/c1", 12)

        // Simulated refresh: same urls plus one new chapter.
        chapters.replaceChapters(
            mangaId,
            listOf(
                MangaChapter(id = "$mangaId|/c1", mangaId = mangaId, url = "/c1", name = "Chapter 1"),
                MangaChapter(id = "$mangaId|/c2", mangaId = mangaId, url = "/c2", name = "Chapter 2"),
                MangaChapter(id = "$mangaId|/c3", mangaId = mangaId, url = "/c3", name = "Chapter 3"),
            ),
        )

        val all = chapters.getChapters(mangaId)
        assertEquals(3, all.size)
        val c1 = all.first { it.url == "/c1" }
        val c2 = all.first { it.url == "/c2" }
        val c3 = all.first { it.url == "/c3" }
        assertTrue(c1.read, "read state must survive a refresh")
        assertEquals(12, c1.downloadedPages, "download markers must survive a refresh")
        assertTrue(c2.bookmarked, "bookmarks must survive a refresh")
        assertEquals(4, c2.lastPageRead, "page progress must survive a refresh")
        assertEquals(10, c2.totalPages, "page totals must survive a refresh")
        assertFalse(c3.read, "new chapters must arrive unread")
    }

    @Test
    fun newChaptersFlipUnreadCountAndProgress() = runBlocking {
        val mangaId = "1:/manga/two"
        chapters.replaceChapters(
            mangaId,
            listOf(MangaChapter(id = "$mangaId|/c1", mangaId = mangaId, url = "/c1", name = "Chapter 1")),
        )
        chapters.markRead(listOf("$mangaId|/c1"), true, emitSyncEvent = false)
        assertEquals(1f, chapters.observeProgress().first()[mangaId])
        assertEquals(null, chapters.observeUnreadCounts().first()[mangaId])

        // Refresh brings a new chapter: the series is no longer fully read.
        chapters.replaceChapters(
            mangaId,
            listOf(
                MangaChapter(id = "$mangaId|/c1", mangaId = mangaId, url = "/c1", name = "Chapter 1"),
                MangaChapter(id = "$mangaId|/c2", mangaId = mangaId, url = "/c2", name = "Chapter 2"),
            ),
        )
        assertEquals(0.5f, chapters.observeProgress().first()[mangaId])
        assertEquals(1, chapters.observeUnreadCounts().first()[mangaId])
    }

    @Test
    fun markUnreadRestoresNeverReadSemantics() = runBlocking {
        val mangaId = "1:/manga/three"
        chapters.replaceChapters(
            mangaId,
            listOf(
                MangaChapter(id = "$mangaId|/c1", mangaId = mangaId, url = "/c1", name = "Chapter 1"),
                MangaChapter(id = "$mangaId|/c2", mangaId = mangaId, url = "/c2", name = "Chapter 2"),
            ),
        )
        chapters.saveProgress("$mangaId|/c1", 7, 20, emitSyncEvent = false)
        chapters.markRead(listOf("$mangaId|/c1"), true, emitSyncEvent = false)
        chapters.saveProgress("$mangaId|/c2", 3, 15, emitSyncEvent = false)
        chapters.markRead(listOf("$mangaId|/c2"), true, emitSyncEvent = false)

        chapters.markRead(listOf("$mangaId|/c1"), false, emitSyncEvent = false)
        val c1 = chapters.getChapter("$mangaId|/c1")!!
        assertFalse(c1.read)
        assertEquals(0, c1.lastPageRead, "marking unread must clear the saved page position")

        chapters.markAllReadForManga(mangaId, false)
        val c2 = chapters.getChapter("$mangaId|/c2")!!
        assertFalse(c2.read)
        assertEquals(0, c2.lastPageRead, "marking the series unread must clear positions too")
        assertEquals(0f, chapters.observeProgress().first()[mangaId])
    }
}
