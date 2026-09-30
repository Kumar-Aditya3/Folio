package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.epub.FrontMatterLabels
import com.folio.reader.epub.TITLE_LABELER_VERSION
import com.folio.reader.epub.needsTitleRepair
import com.folio.reader.epub.repairStoredChapterTitles
import com.folio.reader.epub.titlesCheckedKey
import com.folio.reader.importer.BookImporter
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.model.Chapter
import com.folio.reader.model.ParsedEpub
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
import kotlin.test.assertNull
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
    private lateinit var settingsRepository: JdbcSettingsRepository
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
        settingsRepository = JdbcSettingsRepository(database)
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

    /** One reader open's worth of title repair, against the stored book's own epub. */
    private suspend fun repair(bookId: String, via: EpubParser = parser) {
        repairStoredChapterTitles(
            bookId = bookId,
            epubPath = platform.fileSystem.getBookEpubPath(bookId),
            parser = via,
            repository = bookRepository,
            settings = settingsRepository
        )
    }

    @Test
    fun `repair relabels stored front matter and keeps ids and spine order`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        val stale = storeStaleLabels(book.id)
        assertTrue(stale.needsTitleRepair(), "stale labels must be detected as needing repair")

        repair(book.id)

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

        repair(book.id)

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
    fun `a healthy book is stamped without a parse`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        val counting = CountingEpubParser(parser)

        repair(book.id, via = counting)

        assertEquals(0, counting.parses, "labels that need no repair must not cost a whole-book parse")
        assertEquals(TITLE_LABELER_VERSION, settingsRepository.getRaw(titlesCheckedKey(book.id)))
    }

    @Test
    fun `the stamp stops the next open from parsing again`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        storeStaleLabels(book.id)
        val counting = CountingEpubParser(parser)

        repair(book.id, via = counting)
        assertEquals(1, counting.parses, "the first open re-derives the labels")
        assertFalse(bookRepository.getChaptersForBook(book.id).needsTitleRepair())

        // The case the stamp exists for: labels a repair cannot improve, because the book really
        // does name its own front matter "Chapter 5". Without a stamp every later open would pay
        // for a full parse hunting a fix that never lands.
        storeStaleLabels(book.id)
        repair(book.id, via = counting)
        assertEquals(1, counting.parses, "a book already checked at this labeling version must not parse again")
    }

    @Test
    fun `a failed check leaves no stamp so the next open retries`() = runBlocking {
        val book = importer.importEpub(bookFile.absolutePath).getOrThrow()
        storeStaleLabels(book.id)

        repair(book.id, via = FailingEpubParser())

        assertNull(
            settingsRepository.getRaw(titlesCheckedKey(book.id)),
            "a book that could not be read this time must be checked again next open"
        )
        assertTrue(
            bookRepository.getChaptersForBook(book.id).needsTitleRepair(),
            "a failed pass must leave the stored titles alone"
        )

        val counting = CountingEpubParser(parser)
        repair(book.id, via = counting)
        assertEquals(1, counting.parses, "the next open must still be able to repair the book")
        assertFalse(bookRepository.getChaptersForBook(book.id).needsTitleRepair())
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

    /** A parse leaves no trace in the database, so the stamp tests count them through this. */
    private class CountingEpubParser(private val delegate: EpubParser) : EpubParser() {
        var parses = 0
            private set

        override suspend fun parseEpub(filePath: String): ParsedEpub {
            parses++
            return delegate.parseEpub(filePath)
        }
    }

    /** Stands in for an epub this open cannot read, so the book must stay unchecked. */
    private class FailingEpubParser : EpubParser() {
        override suspend fun parseEpub(filePath: String): ParsedEpub =
            throw java.io.IOException("unreadable source")
    }
}
