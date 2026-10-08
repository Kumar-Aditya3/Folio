package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcDocumentCategoryRepository
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.database.ThumbnailDocumentRepository
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentBookmark
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.model.DocumentPosition
import com.folio.reader.platform.DesktopPlatform
import java.io.File
import java.sql.SQLException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentRepositoryTest {
    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var documents: com.folio.reader.database.DocumentRepository
    private val importedAt = Instant.fromEpochMilliseconds(1_000L)

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-documents-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        documents = ThumbnailDocumentRepository(
            database,
            JdbcDocumentRepository(database)
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun document(id: String = "doc-1", hash: String = "hash-1") = Document(
        id = id,
        title = "Architecture Notes",
        originalFilename = "architecture.pdf",
        format = DocumentFormat.PDF,
        mimeType = "application/pdf",
        contentHash = hash,
        byteSize = 4096L,
        localPath = "documents/$id.pdf",
        thumbnailPath = "documents/$id/cache/thumbnails/first-page.png",
        author = "Ada Lovelace",
        description = "Distributed systems reference",
        pageCount = 42,
        importedAt = importedAt,
        updatedAt = importedAt
    )

    @Test
    fun `document CRUD observation search and hash uniqueness remain local`() = runBlocking {
        var syncEvents = 0
        database.onEntityChanged = { _, _, _, _ -> syncEvents++ }
        assertTrue(documents.observeDocuments().first().isEmpty())
        val observedUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            documents.observeDocuments().drop(1).first()
        }

        documents.upsertDocument(document())
        assertEquals(listOf("doc-1"), observedUpdate.await().map { it.id })
        assertEquals(document(), documents.getDocument("doc-1"))
        assertEquals(
            "documents/doc-1/cache/thumbnails/first-page.png",
            documents.getDocument("doc-1")?.thumbnailPath
        )
        assertEquals("doc-1", documents.getDocumentByHash("hash-1")?.id)
        assertEquals(listOf("doc-1"), documents.searchDocuments("distributed").first().map { it.id })
        assertEquals(listOf("doc-1"), documents.searchDocuments("ARCHITECTURE.PDF").first().map { it.id })
        assertTrue(documents.searchDocuments("   ").first().isEmpty())

        documents.upsertDocument(document().copy(title = "Updated Notes"))
        assertEquals("Updated Notes", documents.getDocument("doc-1")?.title)
        assertFailsWith<SQLException> {
            documents.upsertDocument(document(id = "doc-2", hash = "hash-1"))
        }
        assertEquals(0, syncEvents)
    }

    @Test
    fun `fixed and reflowable positions and bookmarks roundtrip`() = runBlocking {
        documents.upsertDocument(document())
        val fixed = DocumentPosition(
            documentId = "doc-1",
            locator = DocumentLocator.FixedPage(pageIndex = 7, normalizedX = 0.25, normalizedY = 0.75),
            normalizedProgress = 0.2,
            updatedAt = Instant.fromEpochMilliseconds(2_000L)
        )
        documents.upsertPosition(fixed)
        assertEquals(fixed, documents.observePosition("doc-1").first())
        assertEquals(0.2, documents.getDocument("doc-1")?.normalizedProgress)

        val reflowable = fixed.copy(
            locator = DocumentLocator.Reflowable(
                sectionId = "chapter-3",
                domLocator = "#paragraph-4",
                textLocator = "important phrase",
                characterOffset = 12
            ),
            normalizedProgress = 0.6,
            updatedAt = Instant.fromEpochMilliseconds(3_000L)
        )
        documents.upsertPosition(reflowable)
        assertEquals(reflowable, documents.observePosition("doc-1").first())

        val bookmark = DocumentBookmark(
            id = "bookmark-1",
            documentId = "doc-1",
            locator = fixed.locator,
            label = "Diagram",
            createdAt = Instant.fromEpochMilliseconds(4_000L),
            updatedAt = Instant.fromEpochMilliseconds(4_000L)
        )
        documents.upsertBookmark(bookmark)
        assertEquals(bookmark, documents.getBookmark("bookmark-1"))
        documents.upsertBookmark(bookmark.copy(label = "Updated diagram"))
        assertEquals("Updated diagram", documents.observeBookmarks("doc-1").first().single().label)
        documents.deleteBookmark("bookmark-1")
        assertNull(documents.getBookmark("bookmark-1"))
    }

    @Test
    fun `document upsert preserves children and delete removes them transactionally`() = runBlocking {
        documents.upsertDocument(document())
        documents.upsertPosition(DocumentPosition("doc-1", DocumentLocator.FixedPage(pageIndex = 1)))
        documents.upsertBookmark(DocumentBookmark("bookmark-1", "doc-1", DocumentLocator.FixedPage(pageIndex = 1)))
        val categories = JdbcDocumentCategoryRepository(database)
        categories.ensureSeeded()
        categories.ensureMembership("doc-1")
        assertEquals(setOf("main"), categories.categoriesFor("doc-1"))

        documents.upsertDocument(document().copy(title = "Metadata update"))
        assertEquals(1, (documents.observePosition("doc-1").first()?.locator as DocumentLocator.FixedPage).pageIndex)
        assertEquals(1, documents.observeBookmarks("doc-1").first().size)

        documents.deleteDocument("doc-1")
        assertNull(documents.getDocument("doc-1"))
        assertNull(documents.observePosition("doc-1").first())
        assertTrue(documents.observeBookmarks("doc-1").first().isEmpty())
        database.withConnection { connection ->
            for (table in listOf(
                "document_positions",
                "document_bookmarks",
                "document_category_map"
            )) {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "$table should be empty after document deletion")
                    }
                }
            }
        }
    }
}
