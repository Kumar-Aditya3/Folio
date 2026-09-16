package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.epub.FrontMatterLabels
import com.folio.reader.epub.needsTitleRepair
import com.folio.reader.epub.repairStoredChapterTitles
import com.folio.reader.importer.BookImporter
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingPosition
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Chapter titles freeze into the `chapters` table at import, so a book added before the
 * front-matter labeling fix keeps labels like "Chapter 5" for its Acknowledgments page. The
 * repair re-derives them on open without renumbering the book, because positions and bookmarks
 * key on chapter id and spine index.
 */
class ChapterTitleRepairTest {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var bookRepository: JdbcBookRepository
    private lateinit var positionRepository: JdbcReadingPositionRepository
    private lateinit var importer: BookImporter
    private lateinit var parser: EpubParser

    private val corpusDir = File("testbooks").absoluteFile
        .takeIf { it.isDirectory }
        ?: File("..", "testbooks")

    private val bookFile: File
        get() = File(corpusDir, "02.1 Liveship Traders Trilogy 01 - Ship of Magic.epub")

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-repair-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        bookRepository = JdbcBookRepository(database)
        positionRepository = JdbcReadingPositionRepository(database)
        parser = EpubParser(platform)
        importer = BookImporter(
            platform = platform,
            epubParser = parser,
            bookRepository = bookRepository,
            positionRepository = positionRepository,
            searchIndexer = SearchIndexer(JdbcSearchRepository(database)),
            hashUtil = platform.hasher
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    /** Rewrites the stored rows the way an import made before the labeling fix would have. */
    private suspend fun storeStaleLabels(bookId: String): List<Chapter> {
        val stale = bookRepository.getChaptersForBook(bookId).map { chapter ->
            if (chapter.wordCount < FrontMatterLabels.MIN_BODY_WORDS) {
                chapter.copy(title = "Chapter ${chapter.spineIndex + 1}")
            } else {
                chapter
            }
        }
        bookRepository.insertChapters(bookId, stale)
        return stale
    }

    @Test
    fun `repair relabels stored front matter and keeps ids and spine order`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        val stale = storeStaleLabels(book.id)
        assertTrue(stale.needsTitleRepair(), "stale labels must be detected as needing repair")

        repairStoredChapterTitles(
            bookId = book.id,
            epubPath = platform.fileSystem.getBookEpubPath(book.id),
            parser = parser,
            repository = bookRepository
        )

        val repaired = bookRepository.getChaptersForBook(book.id)
        assertEquals(stale.map { it.id }, repaired.map { it.id }, "chapter ids must not change")
        assertEquals(stale.map { it.spineIndex }, repaired.map { it.spineIndex })
        assertEquals(stale.size, repaired.size)

        val frontMatter = repaired.take(8).map { it.title }
        assertTrue(
            "Acknowledgments" in frontMatter,
            "expected the front matter to name itself, got: $frontMatter"
        )
        assertFalse(repaired.needsTitleRepair(), "a repaired book must not need repair again")
    }

    @Test
    fun `repair leaves a stored reading position resolvable`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        val stale = storeStaleLabels(book.id)
        val target = stale.first { it.title == "Chapter 5" }
        positionRepository.upsertPosition(
            ReadingPosition(
                bookId = book.id,
                deviceId = "device",
                chapterId = target.id,
                spineIndex = target.spineIndex,
                contentLocator = "/4/2/1:80",
                characterOffset = 80,
                paragraphIndex = 3
            ).withProgress(newNormalizedProgress = 0.12, newChapterProgress = 0.4)
        )

        repairStoredChapterTitles(
            bookId = book.id,
            epubPath = platform.fileSystem.getBookEpubPath(book.id),
            parser = parser,
            repository = bookRepository
        )

        val position = positionRepository.getPosition(book.id, "device")
        assertNotNull(position)
        assertEquals(target.id, position.chapterId, "the bookmarked chapter must still exist")
        val reloaded = bookRepository.getChaptersForBook(book.id).first { it.id == position.chapterId }
        assertEquals("Acknowledgments", reloaded.title)
    }

    @Test
    fun `a healthy import needs no repair`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        val chapters = bookRepository.getChaptersForBook(book.id)
        assertFalse(chapters.needsTitleRepair(), "fresh import should already be labeled correctly")
    }

    @Test
    fun `legacy all-identical titles are still detected`() {
        fun rows(titles: List<String>) = titles.mapIndexed { i, t ->
            Chapter(id = "c$i", bookId = "b", title = t, href = "c$i.xhtml", spineIndex = i, wordCount = 900)
        }
        assertTrue(rows(listOf("Same", "Same", "Same", "Same")).needsTitleRepair())
        assertFalse(rows(listOf("One", "Two", "Three")).needsTitleRepair())
        assertFalse(rows(listOf("Same", "Same")).needsTitleRepair(), "two-row books are left alone")

        val numberedFurniture = rows(listOf("One", "Two", "Three")).map {
            it.copy(title = "Chapter 2", wordCount = 12)
        }
        assertTrue(numberedFurniture.needsTitleRepair())
    }
}
