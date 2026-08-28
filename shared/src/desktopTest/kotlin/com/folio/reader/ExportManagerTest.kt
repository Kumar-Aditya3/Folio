package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcQuoteRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcReadingSessionRepository
import com.folio.reader.database.JdbcRevisitRepository
import com.folio.reader.database.JdbcSeriesRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.database.JdbcStatisticsRepository
import com.folio.reader.database.JdbcTagRepository
import com.folio.reader.export.ExportManager
import com.folio.reader.export.RestoreMode
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.Quote
import com.folio.reader.model.ReadingPosition
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export/backup round-trips (gap report §Test Coverage Gaps):
 *  - full backup captures all entities and restores them into a fresh database
 *  - Markdown/JSON/CSV annotation exports produce non-empty, well-formed output
 */
class ExportManagerTest {

    private lateinit var tempRootA: File
    private lateinit var tempRootB: File
    private lateinit var platformA: DesktopPlatform
    private lateinit var platformB: DesktopPlatform
    private lateinit var dbA: Database
    private lateinit var dbB: Database
    private lateinit var managerA: ExportManager
    private lateinit var reposA: TestRepos
    private lateinit var reposB: TestRepos

    private class TestRepos(
        val books: JdbcBookRepository,
        val positions: JdbcReadingPositionRepository,
        val sessions: JdbcReadingSessionRepository,
        val highlights: JdbcHighlightRepository,
        val notes: JdbcNoteRepository,
        val bookmarks: JdbcBookmarkRepository,
        val tags: JdbcTagRepository,
        val collections: JdbcCollectionRepository,
        val series: JdbcSeriesRepository,
        val quotes: JdbcQuoteRepository,
        val revisit: JdbcRevisitRepository,
        val settings: JdbcSettingsRepository,
        val statistics: JdbcStatisticsRepository
    )

    @BeforeTest
    fun setUp() {
        tempRootA = createTempDir("folio-exp-a-")
        tempRootB = createTempDir("folio-exp-b-")
        platformA = DesktopPlatform(tempRootA)
        platformB = DesktopPlatform(tempRootB)
        dbA = Database(platformA.fileSystem.getDatabasePath())
        dbB = Database(platformB.fileSystem.getDatabasePath())
        reposA = TestRepos(
            JdbcBookRepository(dbA), JdbcReadingPositionRepository(dbA), JdbcReadingSessionRepository(dbA),
            JdbcHighlightRepository(dbA), JdbcNoteRepository(dbA), JdbcBookmarkRepository(dbA),
            JdbcTagRepository(dbA), JdbcCollectionRepository(dbA), JdbcSeriesRepository(dbA),
            JdbcQuoteRepository(dbA), JdbcRevisitRepository(dbA), JdbcSettingsRepository(dbA),
            JdbcStatisticsRepository(dbA)
        )
        reposB = TestRepos(
            JdbcBookRepository(dbB), JdbcReadingPositionRepository(dbB), JdbcReadingSessionRepository(dbB),
            JdbcHighlightRepository(dbB), JdbcNoteRepository(dbB), JdbcBookmarkRepository(dbB),
            JdbcTagRepository(dbB), JdbcCollectionRepository(dbB), JdbcSeriesRepository(dbB),
            JdbcQuoteRepository(dbB), JdbcRevisitRepository(dbB), JdbcSettingsRepository(dbB),
            JdbcStatisticsRepository(dbB)
        )
        managerA = ExportManager(
            bookRepository = reposA.books,
            positionRepository = reposA.positions,
            sessionRepository = reposA.sessions,
            highlightRepository = reposA.highlights,
            noteRepository = reposA.notes,
            bookmarkRepository = reposA.bookmarks,
            tagRepository = reposA.tags,
            collectionRepository = reposA.collections,
            seriesRepository = reposA.series,
            quoteRepository = reposA.quotes,
            revisitRepository = reposA.revisit,
            settingsRepository = reposA.settings,
            statisticsRepository = reposA.statistics
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { dbA.close() }
        runCatching { dbB.close() }
        tempRootA.deleteRecursively()
        tempRootB.deleteRecursively()
    }

    private suspend fun seedLibrary() {
        val book = Book(
            id = "bk-1",
            title = "Export Test",
            authors = listOf("Author A"),
            epubHash = "hash-exp-1",
            epubFileSize = 42L,
            totalWords = 10
        )
        reposA.books.insertBook(book)
        reposA.books.insertChapters(
            "bk-1",
            listOf(
                com.folio.reader.model.Chapter(
                    id = "ch-1", bookId = "bk-1", title = "Chapter One",
                    href = "text/ch1.xhtml", spineIndex = 0, wordCount = 10
                )
            )
        )
        reposA.highlights.insertHighlight(
            Highlight(
                id = "hl-1", bookId = "bk-1", chapterId = "ch-1", spineIndex = 0,
                startLocator = "/0/1:0", endLocator = "/0/1:end",
                selectedText = "quoted, \"tricky\" text", color = com.folio.reader.model.HighlightColor.YELLOW,
                deviceId = "dev-a", createdAt = Clock.System.now(), updatedAt = Clock.System.now()
            )
        )
        reposA.notes.insertNote(
            Note(
                id = "nt-1", bookId = "bk-1", chapterId = "ch-1", spineIndex = 0,
                locator = "/0/1", content = "a note\nwith newline", deviceId = "dev-a",
                createdAt = Clock.System.now(), updatedAt = Clock.System.now()
            )
        )
        reposA.bookmarks.insertBookmark(
            Bookmark(
                id = "bm-1", bookId = "bk-1", chapterId = "ch-1", spineIndex = 0,
                locator = "/0/0", label = "Start", deviceId = "dev-a",
                createdAt = Clock.System.now(), updatedAt = Clock.System.now()
            )
        )
        reposA.positions.upsertPosition(
            ReadingPosition(bookId = "bk-1", deviceId = "dev-a", chapterId = "ch-1", spineIndex = 0, contentLocator = "/0/0")
        )
        reposA.tags.insertTag(com.folio.reader.model.Tag(id = "tag-1", name = "Favourite"))
        reposA.tags.addTagToBook("bk-1", "tag-1")
        reposA.quotes.insertQuote(
            Quote(
                id = "qt-1", bookId = "bk-1", chapterId = "ch-1", highlightId = "hl-1",
                text = "a saved quote", deviceId = "dev-a"
            )
        )
    }

    private fun managerB(): ExportManager = ExportManager(
        bookRepository = reposB.books,
        positionRepository = reposB.positions,
        sessionRepository = reposB.sessions,
        highlightRepository = reposB.highlights,
        noteRepository = reposB.notes,
        bookmarkRepository = reposB.bookmarks,
        tagRepository = reposB.tags,
        collectionRepository = reposB.collections,
        seriesRepository = reposB.series,
        quoteRepository = reposB.quotes,
        revisitRepository = reposB.revisit,
        settingsRepository = reposB.settings,
        statisticsRepository = reposB.statistics
    )

    @Test
    fun `full backup restores all entities into a fresh database`() = runBlocking {
        seedLibrary()
        val backupFile = File(tempRootA, "backup.json")

        val backup = managerA.createFullBackup(backupFile, deviceId = "dev-a").getOrThrow()
        assertEquals(1, backup.books.size)
        assertEquals(1, backup.highlights.size)
        assertEquals(1, backup.notes.size)
        assertEquals(1, backup.bookmarks.size)
        assertEquals(1, backup.tags.size)
        assertEquals(1, backup.quotes.size)
        assertTrue(backupFile.exists() && backupFile.length() > 0)

        // Restore into the second, empty database
        val restored = managerB().restoreBackup(backupFile, RestoreMode.MERGE).getOrThrow()
        assertEquals(1, restored.booksRestored)
        assertEquals(1, restored.highlightsRestored)
        assertEquals(1, restored.notesRestored)
        assertEquals(1, restored.bookmarksRestored)

        // Verify data actually landed in DB B
        val restoredBook = reposB.books.getBook("bk-1")
        assertEquals("Export Test", restoredBook?.title)
        assertEquals(1, reposB.highlights.getHighlightsForBook("bk-1").first().size)
        assertEquals("quoted, \"tricky\" text", reposB.highlights.getHighlightsForBook("bk-1").first().first().selectedText)
        assertEquals(1, reposB.notes.getNotesForBook("bk-1").first().size)
        assertEquals(1, reposB.bookmarks.getBookmarksForBook("bk-1").first().size)
        assertEquals(listOf("Favourite"), reposB.tags.getTagsForBook("bk-1").map { it.name })
        assertEquals(1, reposB.quotes.getAllQuotes().first().size)
        assertNotNull2(restoredBook)
    }

    private fun assertNotNull2(any: Any?) = kotlin.test.assertNotNull(any)

    @Test
    fun `annotation exports produce markdown json and csv`() = runBlocking {
        seedLibrary()

        val md = File(tempRootA, "ann.md")
        managerA.exportAnnotationsMarkdown(md, null).getOrThrow()
        val mdText = md.readText()
        assertTrue(mdText.contains("# Annotations"), "markdown header")
        assertTrue(mdText.contains("Export Test"), "book title in markdown")
        assertTrue(mdText.contains("quoted, \"tricky\" text"), "highlight text in markdown")

        val json = File(tempRootA, "ann.json")
        managerA.exportAnnotationsJson(json, null).getOrThrow()
        val jsonText = json.readText()
        assertTrue(jsonText.contains("\"highlights\""), "json highlights key")
        assertTrue(jsonText.contains("Export Test"), "book title in json")

        val csv = File(tempRootA, "ann.csv")
        managerA.exportAnnotationsCsv(csv, null).getOrThrow()
        val csvText = csv.readText()
        assertTrue(csvText.startsWith("type,book,chapter_spine,text,color,created_at,device_id"), "csv header")
        // CSV escaping: embedded quotes are doubled
        assertTrue(csvText.contains("\"quoted, \"\"tricky\"\" text\""), "csv quoted escaping")
    }
}
