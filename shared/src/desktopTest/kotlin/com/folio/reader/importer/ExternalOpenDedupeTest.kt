package com.folio.reader.importer

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcDocumentCategoryRepository
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.platform.renderDesktopDocumentThumbnail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the "open a file that is already in the library" contract: the second open of the same
 * content must hand back the existing row and change nothing, because the app layer keys its
 * import UX (shelf prompt, cloud-progress adopt, backfill, "Imported n of n" banner) off
 * [IncomingContentResult.isNewImport] versus [IncomingContentResult.isAlreadyInLibrary].
 */
class ExternalOpenDedupeTest {
    private lateinit var root: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var bookRepository: JdbcBookRepository
    private lateinit var documentRepository: JdbcDocumentRepository
    private lateinit var coordinator: IncomingContentCoordinator

    private val corpusDir = File("testbooks").absoluteFile.takeIf { it.isDirectory }
        ?: File("..", "testbooks")

    @BeforeTest
    fun setUp() {
        root = createTempDir("folio-external-open-")
        platform = DesktopPlatform(root)
        database = Database(platform.fileSystem.getDatabasePath())
        bookRepository = JdbcBookRepository(database)
        documentRepository = JdbcDocumentRepository(database)
        coordinator = IncomingContentCoordinator(
            DocumentFormatDetector(),
            BookImporter(
                platform = platform,
                epubParser = EpubParser(platform),
                bookRepository = bookRepository,
                positionRepository = JdbcReadingPositionRepository(database),
                searchIndexer = SearchIndexer(JdbcSearchRepository(database)),
                hashUtil = platform.hasher
            ),
            DocumentImporter(
                platform,
                documentRepository,
                JdbcDocumentCategoryRepository(database),
                ::renderDesktopDocumentThumbnail
            )
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        root.deleteRecursively()
    }

    @Test
    fun `reopening an imported epub adds no second copy and rewrites nothing`() = runBlocking {
        val source = checkNotNull(
            corpusDir.listFiles { file -> file.extension.equals("epub", ignoreCase = true) }
                ?.minByOrNull { it.length() }
        ) { "corpus required" }

        val first = coordinator.import(source.absolutePath, source.name)
        val book = assertIs<IncomingContentResult.ImportedBook>(first).book
        assertTrue(first.isNewImport)
        assertFalse(first.isAlreadyInLibrary)

        // Reading state recorded after the first open must survive the second one.
        bookRepository.updateNormalizedProgress(book.id, 0.4)
        val storedBefore = assertNotNull(bookRepository.getBook(book.id))

        val second = coordinator.import(source.absolutePath, source.name)
        val duplicate = assertIs<IncomingContentResult.DuplicateBook>(second)
        assertTrue(second.isAlreadyInLibrary)
        assertFalse(second.isNewImport)
        assertEquals(book.id, duplicate.book.id)

        val storedAfter = assertNotNull(bookRepository.getBook(book.id))
        assertEquals(storedBefore, storedAfter)
        assertEquals(0.4, storedAfter.normalizedProgress, 1e-9)
        assertEquals(listOf(book.id), bookRepository.getAllBooks().first().map { it.id })
        assertEquals(
            1,
            assertNotNull(platform.fileSystem.libraryBooksDir.listFiles()).count { it.isDirectory },
            "the second open must not write a second library copy"
        )
    }

    @Test
    fun `reopening an imported document returns the existing row`() = runBlocking {
        val note = File(root, "note.txt").apply { writeText("already in the library") }
        val first = assertIs<IncomingContentResult.ImportedDocument>(
            coordinator.import(note.absolutePath, note.name)
        )
        assertTrue(first.isNewImport)

        // Stood in for a second tap on the same file reached through a different path.
        val copy = File(root, "note copy.txt").apply { note.copyTo(this, overwrite = true) }
        val second = coordinator.import(copy.absolutePath, copy.name)

        val duplicate = assertIs<IncomingContentResult.DuplicateDocument>(second)
        assertTrue(second.isAlreadyInLibrary)
        assertEquals(first.document.id, duplicate.document.id)
        assertEquals(1, documentRepository.observeDocuments().first().size)
    }
}
