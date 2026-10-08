package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaNoteRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.database.JdbcMangaStatisticsRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §11.2 guard: the manga statistics aggregates only library manga (favorite = 1).
 * Chapters read and notes written on a title that was never added to the library
 * must not move read/unread/downloaded/bookmarked counts, notes count, the
 * weekly bars, or the active-days figure.
 */
class StatsLibraryOnlyTest {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database

    private lateinit var manga: JdbcMangaRepository
    private lateinit var chapters: JdbcMangaChapterRepository
    private lateinit var notes: JdbcMangaNoteRepository
    private lateinit var stats: JdbcMangaStatisticsRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-stats-lib-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        manga = JdbcMangaRepository(database)
        chapters = JdbcMangaChapterRepository(database)
        notes = JdbcMangaNoteRepository(database)
        stats = JdbcMangaStatisticsRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun entry(id: String, inLibrary: Boolean) = MangaEntry(
        id = id,
        sourceId = 7L,
        sourceName = "TestSource",
        url = "https://test/$id",
        title = "Manga $id",
        inLibrary = inLibrary,
    )

    private fun chapter(id: String, mangaId: String) = MangaChapter(
        id = id,
        mangaId = mangaId,
        url = "https://test/$mangaId/${id}",
        name = "Chapter $id",
    )

    @Test
    fun `stats count only library manga chapters and notes`() = runBlocking {
        manga.upsert(entry("lib-1", inLibrary = true), emitSyncEvent = false)
        manga.upsert(entry("ext-1", inLibrary = false), emitSyncEvent = false)

        chapters.replaceChapters("lib-1", listOf(chapter("c1", "lib-1"), chapter("c2", "lib-1")))
        chapters.replaceChapters("ext-1", listOf(chapter("c3", "ext-1"), chapter("c4", "ext-1")))

        // One chapter read in the library, one read outside it.
        chapters.markRead(listOf("c1", "c3"), read = true, emitSyncEvent = false)

        notes.upsert(
            MangaNote(id = "n1", mangaId = "ext-1", chapterId = "c3", pageIndex = 0, content = "outside"),
            emitSyncEvent = false,
        )

        val s = stats.getStatistics(emptySet())

        assertEquals(1, s.libraryCount)
        assertEquals(1, s.readChapters, "only the library manga's read chapter counts")
        assertEquals(1, s.unreadChapters, "only the library manga's unread chapter counts")
        assertEquals(0, s.notesCount, "notes on a non-library manga must not count")
        assertEquals(1, s.weekReadChapters.sum(), "weekly bars must exclude non-library reads")
        assertEquals(1, s.readActiveDays, "active days must come from library manga only")
        assertEquals(0, s.totalReadMinutes)
        assertEquals(0, s.topManga.size)
    }
}
