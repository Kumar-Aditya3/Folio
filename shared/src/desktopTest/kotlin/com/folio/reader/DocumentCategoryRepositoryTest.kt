package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcDocumentCategoryRepository
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentCategory
import com.folio.reader.model.DocumentFormat
import com.folio.reader.platform.DesktopPlatform
import java.io.File
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentCategoryRepositoryTest {
    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var categories: JdbcDocumentCategoryRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-document-categories-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        documents = JdbcDocumentRepository(database)
        categories = JdbcDocumentCategoryRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun `seeding creates Main and repairs documents without membership`() = runBlocking {
        documents.upsertDocument(document("one"))
        documents.upsertDocument(document("two"))

        categories.ensureSeeded()

        assertEquals(
            listOf(DocumentCategory.MAIN_ID),
            categories.observeCategories().first().map { it.id }
        )
        assertEquals(setOf(DocumentCategory.MAIN_ID), categories.categoriesFor("one"))
        assertEquals(setOf(DocumentCategory.MAIN_ID), categories.categoriesFor("two"))
        assertEquals(setOf("one", "two"), categories.documentIdsInCategory(DocumentCategory.MAIN_ID))
    }

    @Test
    fun `categories and assignments persist across repository and database restarts`() = runBlocking {
        documents.upsertDocument(document("one"))
        categories.ensureSeeded()
        val research = categories.create("Research")
        categories.assign("one", setOf(research.id))
        val path = DesktopPlatform(tempRoot).fileSystem.getDatabasePath()

        database.close()
        database = Database(path)
        documents = JdbcDocumentRepository(database)
        categories = JdbcDocumentCategoryRepository(database)

        assertEquals("Research", categories.get(research.id)?.name)
        assertEquals(setOf(research.id), categories.categoriesFor("one"))
    }

    @Test
    fun `assignment replacement emits live membership updates`() = runBlocking {
        documents.upsertDocument(document("one"))
        categories.ensureSeeded()
        val reading = categories.create("Reading")
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            categories.observeCategoriesFor("one").drop(1).first()
        }

        categories.assign("one", setOf(reading.id))

        assertEquals(setOf(reading.id), observed.await())
        assertEquals(setOf(reading.id), categories.categoriesFor("one"))
        assertTrue("one" !in categories.documentIdsInCategory(DocumentCategory.MAIN_ID))
    }

    @Test
    fun `deleting a category preserves documents and rehomes affected memberships`() = runBlocking {
        documents.upsertDocument(document("one"))
        documents.upsertDocument(document("two"))
        categories.ensureSeeded()
        val reading = categories.create("Reading")
        categories.assign("one", setOf(reading.id))
        categories.assign("two", setOf(DocumentCategory.MAIN_ID, reading.id))

        assertTrue(categories.delete(reading.id))

        assertNotNull(documents.getDocument("one"))
        assertNotNull(documents.getDocument("two"))
        assertEquals(setOf(DocumentCategory.MAIN_ID), categories.categoriesFor("one"))
        assertEquals(setOf(DocumentCategory.MAIN_ID), categories.categoriesFor("two"))
        assertNull(categories.get(reading.id))
    }

    @Test
    fun `sole Main category cannot be deleted`() = runBlocking {
        categories.ensureSeeded()

        assertFalse(categories.delete(DocumentCategory.MAIN_ID))
        assertNotNull(categories.defaultCategory())
    }

    private fun document(id: String) = Document(
        id = id,
        title = id,
        originalFilename = "$id.pdf",
        format = DocumentFormat.PDF,
        mimeType = "application/pdf",
        contentHash = "hash-$id",
        byteSize = 1,
        importedAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(1_000)
    )
}
