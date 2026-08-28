package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.importer.BookImporter
import com.folio.reader.importer.DuplicateBookException
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.model.ReadingPosition
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Corpus-level verification against the real EPUBs in /testbooks.
 *
 * Covers (logic level):
 *  - P1-01  EPUB 2/3 parsing across a mixed corpus
 *  - P1-04  metadata + spine + TOC extraction sanity
 *  - P1-06  SHA-256 duplicate detection on import
 *  - P1-16  reading-position persistence roundtrip
 */
class CorpusTest {

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

    private fun corpusFiles(): List<File> =
        corpusDir.listFiles { f -> f.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-test-")
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
            positionRepository = JdbcReadingPositionRepository(database),
            searchIndexer = SearchIndexer(JdbcSearchRepository(database)),
            hashUtil = platform.hasher
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun `corpus parses - every epub yields metadata, spine, toc and word counts`() = runBlocking {
        val files = corpusFiles()
        assertTrue(files.isNotEmpty(), "testbooks corpus must be present")
        assertTrue(files.size >= 20, "expected the full ~22-book corpus, found ${files.size}")

        for (file in files) {
            val parsed = parser.parseEpub(file.absolutePath)

            assertTrue(parsed.metadata.title.isNotBlank(), "${file.name}: title missing")
            assertTrue(parsed.spine.isNotEmpty(), "${file.name}: spine empty")
            assertTrue(parsed.manifest.isNotEmpty(), "${file.name}: manifest empty")
            assertTrue(
                parsed.toc.isNotEmpty(),
                "${file.name}: TOC missing"
            )
            assertTrue(parsed.chapters.isNotEmpty(), "${file.name}: chapter list missing")
            assertTrue(parsed.totalWords > 0, "${file.name}: totalWords not computed")
            assertTrue(parsed.totalCharacters > 0, "${file.name}: totalCharacters not computed")
        }
    }

    @Test
    fun `import persists book, copies file, and computes progress fields`() = runBlocking {
        val files = corpusFiles()
        val first = files.first()

        val result = importer.importEpub(first.absolutePath)
        val book = result.getOrElse { fail("import failed for ${first.name}: ${it.message}") }

        assertEquals(first.length(), book.epubFileSize)
        assertTrue(book.title.isNotBlank())
        assertNotNull(book.epubHash)

        // persisted?
        val reloaded = bookRepository.getBook(book.id)
        assertNotNull(reloaded)
        assertEquals(book.title, reloaded.title)

        // hash lookup works
        val byHash = bookRepository.getBookByEpubHash(book.epubHash)
        assertNotNull(byHash)
        assertEquals(book.id, byHash.id)

        // file copied into library storage
        val storedEpub = File(platform.fileSystem.getBookEpubPath(book.id))
        assertTrue(storedEpub.exists(), "EPUB should be copied into library dir")
        assertEquals(first.length(), storedEpub.length())

        // chapters persisted for reader navigation
        val chapters = bookRepository.getChaptersForBook(book.id)
        assertEquals(book.chapterCount, chapters.size, "persisted chapter count must match book.chapterCount")
        assertTrue(chapters.isNotEmpty(), "imported book must have chapters")
        assertTrue(
            chapters.zipWithNext().all { (a, b) -> a.spineIndex <= b.spineIndex },
            "chapters must be ordered by spine index"
        )
    }

    @Test
    fun `re-importing same file is rejected as duplicate`() = runBlocking {
        val first = corpusFiles().first()
        val initial = importer.importEpub(first.absolutePath).getOrThrow()

        val second = importer.importEpub(first.absolutePath)
        assertTrue(second.isFailure, "second import of identical file must fail")

        val error = second.exceptionOrNull()
        assertTrue(error is DuplicateBookException, "expected DuplicateBookException but was $error")
        assertEquals(initial.id, (error as DuplicateBookException).existingBook.id)
    }

    @Test
    fun `reading position roundtrip and normalized progress update`() = runBlocking {
        val book = importer.importEpub(corpusFiles().first().absolutePath).getOrThrow()
        val deviceId = "test-device"

        val position = ReadingPosition(
            bookId = book.id,
            deviceId = deviceId,
            chapterId = "chapter-1",
            spineIndex = 3,
            contentLocator = "/2/4/1:120",
            characterOffset = 120,
            paragraphIndex = 7
        ).withProgress(newNormalizedProgress = 0.42, newChapterProgress = 0.1)

        positionRepository.upsertPosition(position)

        val loaded = positionRepository.getPosition(book.id, deviceId)
        assertNotNull(loaded)
        assertEquals(position.chapterId, loaded.chapterId)
        assertEquals(position.spineIndex, loaded.spineIndex)
        assertEquals(position.contentLocator, loaded.contentLocator)
        assertEquals(0.42, loaded.normalizedProgress, 1e-9)

        // multi-device positions coexist
        val otherDevice = position.copy(deviceId = "phone")
            .withProgress(newNormalizedProgress = 0.9, newChapterProgress = 0.5)
        positionRepository.upsertPosition(otherDevice)
        assertEquals(deviceId, positionRepository.getPosition(book.id, deviceId)?.deviceId)
        assertEquals("phone", positionRepository.getPosition(book.id, "phone")?.deviceId)
        assertEquals(2, positionRepository.getAllPositionsForBook(book.id).size)
    }

    @Test
    fun `reader settings persist as JSON in database`() = runBlocking {
        val original = com.folio.reader.settings.ReaderSettings(
            fontFamily = "Literata",
            fontSize = 21f,
            themeId = "dark"
        )
        settingsRepository.saveGlobalSettings(original)

        val loaded = settingsRepository.getGlobalSettings()
        assertEquals(original.fontSize, loaded.fontSize)
        assertEquals(original.fontFamily, loaded.fontFamily)
        assertEquals("dark", loaded.themeId)
    }
}
