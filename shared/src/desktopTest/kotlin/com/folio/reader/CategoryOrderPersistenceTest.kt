package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcDocumentCategoryRepository
import com.folio.reader.database.JdbcMangaCategoryRepository
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CategoryOrderPersistenceTest {
    @Test
    fun allThreeLibrariesPersistTheirOwnOrderIncludingMovingMain() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-category-order-").toFile()
        val path = DesktopPlatform(root).fileSystem.getDatabasePath()
        var db = Database(path)
        try {
            val books = JdbcCollectionRepository(db).apply { ensureSeeded() }
            val docs = JdbcDocumentCategoryRepository(db).apply { ensureSeeded() }
            val manga = JdbcMangaCategoryRepository(db).apply { ensureSeeded() }
            val b = books.createCollection("Books later")
            val d = docs.create("Documents later")
            val m = manga.create("Manga later")
            val expectedBooks = books.getAllCollections().first().map { it.id }.reversed()
            val expectedDocs = docs.observeCategories().first().map { it.id }.reversed()
            val expectedManga = manga.observeCategories().first().map { it.id }.reversed()
            val events = mutableListOf<Pair<String, String>>()
            db.onEntityChanged = { type, id, _, _ -> events += type to id }
            books.reorderCollections(expectedBooks)
            docs.reorder(expectedDocs)
            manga.reorder(expectedManga)
            assertEquals(setOf(b.id), books.getAllCollections().first().take(1).map { it.id }.toSet())
            assertEquals(setOf(d.id), docs.observeCategories().first().take(1).map { it.id }.toSet())
            assertEquals(setOf(m.id), manga.observeCategories().first().take(1).map { it.id }.toSet())
            assertEquals(expectedBooks.toSet(), events.filter { it.first == "collection" }.map { it.second }.toSet())
            assertEquals(expectedManga.toSet(), events.filter { it.first == "manga_category" }.map { it.second }.toSet())
            db.close()
            db = Database(path)
            assertEquals(expectedBooks, JdbcCollectionRepository(db).getAllCollections().first().map { it.id })
            assertEquals(expectedDocs, JdbcDocumentCategoryRepository(db).observeCategories().first().map { it.id })
            assertEquals(expectedManga, JdbcMangaCategoryRepository(db).observeCategories().first().map { it.id })
            val added = JdbcMangaCategoryRepository(db).create("A new manga category")
            assertEquals(added.id, JdbcMangaCategoryRepository(db).observeCategories().first().last().id)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun staleIdsAreIgnoredAndCategoriesAddedDuringADragAreRetained() = runBlocking<Unit> {
        val root = kotlin.io.path.createTempDirectory("folio-category-stale-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            val docs = JdbcDocumentCategoryRepository(db).apply { ensureSeeded() }
            val first = docs.observeCategories().first().single()
            val a = docs.create("A")
            val b = docs.create("B")
            docs.reorder(listOf("gone", a.id, a.id, first.id))
            assertEquals(listOf(a.id, first.id, b.id), docs.observeCategories().first().map { it.id })
            assertNotNull(docs.get(b.id))
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test
    fun failedReorderRollsBackEveryCategoryAndDoesNotPublishARevision() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-category-rollback-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            val docs = JdbcDocumentCategoryRepository(db).apply { ensureSeeded() }
            docs.create("Research")
            val before = docs.observeCategories().first()
            val revision = db.documentDataRevision.value
            db.withConnection { connection -> connection.createStatement().use { statement ->
                statement.execute("CREATE TRIGGER fail_category_reorder BEFORE UPDATE OF sort_order ON document_categories " +
                    "WHEN NEW.sort_order = 1 BEGIN SELECT RAISE(ABORT, 'blocked reorder'); END")
            } }
            assertFailsWith<SQLException> { docs.reorder(before.map { it.id }.reversed()) }
            assertEquals(before, docs.observeCategories().first())
            assertEquals(revision, db.documentDataRevision.value)
        } finally { db.close(); root.deleteRecursively() }
    }
}
