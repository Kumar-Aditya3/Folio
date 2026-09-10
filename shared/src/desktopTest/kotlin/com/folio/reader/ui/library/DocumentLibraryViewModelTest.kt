package com.folio.reader.ui.library

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcDocumentCategoryRepository
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentCategory
import com.folio.reader.model.DocumentFormat
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLibraryViewModelTest {
    private val imported = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun persistedLibraryModesRemainCompatible() {
        assertEquals(LibraryMode.BOOKS, libraryModeFromPersistedName("BOOKS"))
        assertEquals(LibraryMode.MANGA, libraryModeFromPersistedName("MANGA"))
        assertEquals(LibraryMode.DOCUMENTS, libraryModeFromPersistedName("DOCUMENTS"))
        assertEquals(LibraryMode.BOOKS, libraryModeFromPersistedName("unknown"))
        assertEquals(LibraryMode.BOOKS, libraryModeFromPersistedName(null))
    }

    @Test
    fun displayOrderIsIndependentFromPersistedEnumOrder() {
        assertEquals(
            listOf(LibraryMode.BOOKS, LibraryMode.MANGA, LibraryMode.DOCUMENTS),
            libraryModeDisplayOrder
        )
        libraryModeDisplayOrder.forEachIndexed { index, mode ->
            assertEquals(index, libraryModeDisplayIndex(mode))
            assertEquals(mode, libraryModeAtDisplayIndex(index))
        }
        assertEquals(LibraryMode.BOOKS, libraryModeAtDisplayIndex(-1))
    }

    @Test
    fun metadataSearchCoversDocumentFields() {
        val document = document(
            title = "Project notes",
            filename = "meeting.txt",
            format = DocumentFormat.TXT,
            author = "Ada Lovelace",
            description = "Architecture review"
        )

        listOf("project", "meeting", "txt", "ada", "architecture", "text/plain").forEach { query ->
            assertEquals(listOf(document), filterDocuments(listOf(document), query), query)
        }
        assertEquals(emptyList(), filterDocuments(listOf(document), "invoice"))
    }

    @Test
    fun formatFilterCombinesWithMetadataSearch() {
        val pdf = document("Project brief", "brief.pdf", DocumentFormat.PDF)
        val text = document("Project notes", "notes.txt", DocumentFormat.TXT)

        assertEquals(listOf(pdf), filterDocuments(listOf(pdf, text), "project", DocumentFormat.PDF))
        assertEquals(listOf(text), filterDocuments(listOf(pdf, text), "", DocumentFormat.TXT))
        assertEquals(emptyList(), filterDocuments(listOf(pdf, text), "brief", DocumentFormat.TXT))
    }

    @Test
    fun allDocumentSortOptionsRespectDirection() {
        val older = document("Alpha", "z.pdf", DocumentFormat.PDF, size = 20, progress = 0.2)
        val newer = document(
            "Beta",
            "a.txt",
            DocumentFormat.TXT,
            size = 10,
            progress = 0.8,
            importedAt = Instant.parse("2026-02-01T00:00:00Z"),
            lastOpenedAt = Instant.parse("2026-03-01T00:00:00Z")
        )
        val documents = listOf(newer, older)

        DocumentSortBy.entries.forEach { sort ->
            val ascending = sortDocuments(documents, sort, true)
            assertEquals(ascending.reversed(), sortDocuments(documents, sort, false), sort.name)
        }
    }

    @Test
    fun sharedCategoriesUseIntersectionForBulkPicker() {
        assertEquals(
            setOf("main", "shared"),
            sharedCategoryIds(
                listOf(
                    setOf("main", "shared", "first"),
                    setOf("main", "shared", "second"),
                    setOf("main", "shared")
                )
            )
        )
        assertEquals(emptySet(), sharedCategoryIds(emptyList()))
    }

    @Test
    fun categorySelectionFilteringAndLiveAssignmentMatchMangaBehavior() = runBlocking {
        withFixture { documents, categories, settings ->
            val one = document("One", "one.pdf", DocumentFormat.PDF)
            val two = document("Two", "two.pdf", DocumentFormat.PDF)
            documents.upsertDocument(one)
            documents.upsertDocument(two)
            categories.ensureSeeded()
            val research = categories.create("Research")
            categories.assign(one.id, setOf(research.id))
            val viewModel = DocumentLibraryViewModel(
                documents,
                categories,
                settings,
                Dispatchers.Default,
                fileExists = { true }
            )
            try {
                await { viewModel.selectedCategoryId.value == DocumentCategory.MAIN_ID }
                await { viewModel.state.value.items.map { it.document.id } == listOf(two.id) }

                viewModel.selectCategory(research.id)
                await { viewModel.state.value.items.map { it.document.id } == listOf(one.id) }
                await { settings.values["document.library.category"] == research.id }

                categories.assign(two.id, setOf(research.id))
                await {
                    viewModel.state.value.items.map { it.document.id }.toSet() == setOf(one.id, two.id)
                }
            } finally {
                viewModel.close()
            }
        }
    }

    @Test
    fun persistedSelectionIsRestoredAndDeletedSelectionFallsBackToMain() = runBlocking {
        withFixture { documents, categories, settings ->
            documents.upsertDocument(document("One", "one.pdf", DocumentFormat.PDF))
            categories.ensureSeeded()
            val research = categories.create("Research")
            settings.values["document.library.category"] = research.id
            val viewModel = DocumentLibraryViewModel(
                documents,
                categories,
                settings,
                Dispatchers.Default,
                fileExists = { true }
            )
            try {
                await { viewModel.selectedCategoryId.value == research.id }

                viewModel.deleteCategory(research.id)

                await { viewModel.selectedCategoryId.value == DocumentCategory.MAIN_ID }
                await { settings.values["document.library.category"] == DocumentCategory.MAIN_ID }
            } finally {
                viewModel.close()
            }
        }
    }

    @Test
    fun selectionAndSingleOrBulkEmptyAssignmentsFallBackToMain() = runBlocking {
        withFixture { documents, categories, settings ->
            val one = document("One", "one.pdf", DocumentFormat.PDF)
            val two = document("Two", "two.pdf", DocumentFormat.PDF)
            documents.upsertDocument(one)
            documents.upsertDocument(two)
            categories.ensureSeeded()
            val research = categories.create("Research")
            categories.assign(one.id, setOf(DocumentCategory.MAIN_ID, research.id))
            categories.assign(two.id, setOf(DocumentCategory.MAIN_ID))
            val viewModel = DocumentLibraryViewModel(
                documents,
                categories,
                settings,
                Dispatchers.Default,
                fileExists = { true }
            )
            try {
                viewModel.toggleSelection(one.id)
                viewModel.toggleSelection(two.id)
                assertTrue(viewModel.isSelectionMode.value)
                assertEquals(setOf(one.id, two.id), viewModel.selectedIds.value)

                viewModel.requestBulkCategories()
                await { viewModel.bulkPickerInitial.value == setOf(DocumentCategory.MAIN_ID) }

                viewModel.applyBulkCategories(setOf(research.id))
                await {
                    categories.categoriesFor(one.id) == setOf(research.id) &&
                        categories.categoriesFor(two.id) == setOf(research.id)
                }

                viewModel.applyBulkCategories(emptySet())
                await {
                    categories.categoriesFor(one.id) == setOf(DocumentCategory.MAIN_ID) &&
                        categories.categoriesFor(two.id) == setOf(DocumentCategory.MAIN_ID)
                }

                viewModel.setCategoriesFor(one.id, emptySet())
                await { categories.categoriesFor(one.id) == setOf(DocumentCategory.MAIN_ID) }

                viewModel.clearSelection()
                assertFalse(viewModel.isSelectionMode.value)
                assertEquals(emptySet(), viewModel.selectedIds.value)
            } finally {
                viewModel.close()
            }
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                kotlinx.coroutines.yield()
            }
        }
    }

    private suspend fun withFixture(
        block: suspend (
            JdbcDocumentRepository,
            JdbcDocumentCategoryRepository,
            FakeSettingsRepository
        ) -> Unit
    ) {
        val root = createTempDir("folio-document-library-view-model-")
        val database = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            block(
                JdbcDocumentRepository(database),
                JdbcDocumentCategoryRepository(database),
                FakeSettingsRepository()
            )
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private class FakeSettingsRepository(
        val values: MutableMap<String, String> = mutableMapOf()
    ) : SettingsRepository {
        override suspend fun getGlobalSettings(): ReaderSettings = ReaderSettings()
        override suspend fun saveGlobalSettings(settings: ReaderSettings, emitSyncEvent: Boolean) = Unit
        override suspend fun getBookSettings(bookId: String): BookReaderSettings? = null
        override suspend fun saveBookSettings(bookId: String, settings: BookReaderSettings) = Unit
        override suspend fun deleteBookSettings(bookId: String) = Unit
        override suspend fun setRaw(key: String, value: String) {
            values[key] = value
        }
        override suspend fun getRaw(key: String): String? = values[key]
    }

    private fun document(
        title: String,
        filename: String,
        format: DocumentFormat,
        size: Long = 1,
        progress: Double = 0.0,
        importedAt: Instant = imported,
        lastOpenedAt: Instant? = null,
        author: String? = null,
        description: String? = null
    ) = Document(
        id = filename,
        title = title,
        originalFilename = filename,
        format = format,
        mimeType = if (format == DocumentFormat.TXT) "text/plain" else "application/pdf",
        contentHash = filename,
        byteSize = size,
        author = author,
        description = description,
        importedAt = importedAt,
        updatedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
        normalizedProgress = progress
    )
}
