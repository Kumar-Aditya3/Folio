package com.folio.reader.ui.document

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.model.DocumentPosition
import com.folio.reader.platform.DesktopPlatform
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DocumentReaderViewModelTest {
    private lateinit var root: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var repository: JdbcDocumentRepository
    private var viewModel: DocumentReaderViewModel? = null

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("folio-document-reader-").toFile()
        platform = DesktopPlatform(root)
        database = Database(platform.fileSystem.getDatabasePath())
        repository = JdbcDocumentRepository(database)
    }

    @AfterTest
    fun tearDown() {
        viewModel?.close()
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun restoresClampsAndPersistsFixedPageAndBookmarks() = runBlocking {
        val original = File(platform.fileSystem.getDocumentDir("pdf-doc"), "original.pdf")
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        repository.upsertDocument(document("pdf-doc", DocumentFormat.PDF, original))
        repository.upsertPosition(
            DocumentPosition(
                "pdf-doc",
                DocumentLocator.FixedPage(pageIndex = 9),
                0.9
            )
        )

        val reader = reader().also { it.open("pdf-doc") }
        val restored = awaitReady(reader)
        assertEquals(9, restored.currentPage)
        assertEquals(0.9, restored.normalizedProgress)

        reader.onPdfOpened(3)
        assertEquals(2, reader.state.value.currentPage)
        assertEquals(1.0, reader.state.value.normalizedProgress)
        val persisted = withTimeout(2_000) {
            repository.observePosition("pdf-doc").first {
                (it?.locator as? DocumentLocator.FixedPage)?.pageIndex == 2
            }
        }
        assertEquals(1.0, persisted?.normalizedProgress)

        reader.toggleBookmark()
        val bookmarked = withTimeout(2_000) {
            reader.state.first { it.bookmarks.size == 1 }
        }
        assertTrue(bookmarked.isCurrentPositionBookmarked)
        assertEquals("Page 3", bookmarked.bookmarks.single().label)

        reader.removeBookmark(bookmarked.bookmarks.single().id)
        withTimeout(2_000) { reader.state.first { it.bookmarks.isEmpty() } }
    }

    @Test
    fun switchingFixedPageModePreservesCurrentPage() = runBlocking {
        val original = File(platform.fileSystem.getDocumentDir("mode-pdf"), "original.pdf")
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        repository.upsertDocument(document("mode-pdf", DocumentFormat.PDF, original))

        val reader = reader().also { it.open("mode-pdf") }
        awaitReady(reader)
        reader.onPdfOpened(12)
        reader.setCurrentPage(7)

        reader.setMode(DocumentReaderMode.CONTINUOUS)
        assertEquals(DocumentReaderMode.CONTINUOUS, reader.state.value.mode)
        assertEquals(7, reader.state.value.currentPage)

        reader.setMode(DocumentReaderMode.SINGLE_PAGE)
        assertEquals(DocumentReaderMode.SINGLE_PAGE, reader.state.value.mode)
        assertEquals(7, reader.state.value.currentPage)

        reader.seekToProgress(0.5f)
        assertEquals(6, reader.state.value.currentPage)
        assertEquals(6.0 / 11.0, reader.state.value.normalizedProgress)
    }

    @Test
    fun restoresAndPersistsReflowableProgressAndBookmark() = runBlocking {
        val original = File(platform.fileSystem.getDocumentDir("html-doc"), "original.html")
            .apply { parentFile.mkdirs(); writeText("source") }
        platform.fileSystem.getDocumentGeneratedIndexPath("html-doc").let { path ->
            File(path).writeText("<html><body>Document</body></html>")
        }
        repository.upsertDocument(document("html-doc", DocumentFormat.HTML, original))
        val locator = DocumentLocator.Reflowable(
            sectionId = DOCUMENT_REFLOWABLE_SECTION,
            domLocator = "#saved",
            characterOffset = 12
        )
        repository.upsertPosition(DocumentPosition("html-doc", locator, 0.65))

        val reader = reader().also { it.open("html-doc") }
        val restored = awaitReady(reader)
        assertEquals(0.65, restored.normalizedProgress)
        assertEquals(locator, restored.reflowableLocator)
        assertIs<DocumentReaderContent.Reflowable>(
            (restored.loadState as DocumentReaderLoadState.Ready).content
        )

        reader.updateReflowablePage(3, 8)
        assertEquals(3, reader.state.value.currentPage)
        assertEquals(8, reader.state.value.pageCount)

        reader.seekToProgress(0.25f)
        assertEquals(0.25, reader.state.value.normalizedProgress)

        reader.updateReflowableProgress(1.5f, 42)
        val persisted = withTimeout(2_000) {
            repository.observePosition("html-doc").first {
                it?.normalizedProgress == 1.0 &&
                    (it.locator as? DocumentLocator.Reflowable)?.characterOffset == 42
            }
        }
        assertEquals(1.0, persisted?.normalizedProgress)

        reader.toggleBookmark()
        val bookmarked = withTimeout(2_000) {
            reader.state.first { it.bookmarks.size == 1 }
        }
        assertTrue(bookmarked.isCurrentPositionBookmarked)
    }

    private fun reader() = DocumentReaderViewModel(
        repository = repository,
        fileSystem = platform.fileSystem,
        dispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.IO
    ).also { viewModel = it }

    private suspend fun awaitReady(reader: DocumentReaderViewModel): DocumentReaderState =
        withTimeout(2_000) {
            reader.state.first { it.loadState !is DocumentReaderLoadState.Loading }
        }.also { assertIs<DocumentReaderLoadState.Ready>(it.loadState) }

    private fun document(id: String, format: DocumentFormat, original: File) = Document(
        id = id,
        title = id,
        originalFilename = original.name,
        format = format,
        mimeType = if (format == DocumentFormat.PDF) "application/pdf" else "text/html",
        contentHash = "hash-$id",
        byteSize = original.length(),
        localPath = original.absolutePath
    )
}
