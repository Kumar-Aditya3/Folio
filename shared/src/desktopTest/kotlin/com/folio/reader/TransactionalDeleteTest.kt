package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Note
import com.folio.reader.manga.MangaEntry
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B1/B2: the multi-table deletes must be atomic. A statement that fails
 * mid-sequence (simulated by dropping a child table) rolls the whole delete
 * back — the parent row and its surviving children stay exactly as they were.
 */
class TransactionalDeleteTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-transactional-delete-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun dropTable(table: String) {
        database.withConnection { conn ->
            conn.createStatement().use { it.execute("DROP TABLE $table") }
        }
    }

    private suspend fun countRows(table: String, column: String, value: String): Int =
        database.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use { stmt ->
                stmt.setString(1, value)
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    @Test
    fun deleteBookRollsBackWhenAChildDeleteFails() {
        runBlocking {
            val bookRepo = JdbcBookRepository(database)
            val noteRepo = JdbcNoteRepository(database)
            bookRepo.insertBook(Book(id = "b1", title = "Atomic", epubHash = "h1", epubFileSize = 1L), emitSyncEvent = false)
            noteRepo.insertNote(
                Note(id = "n1", bookId = "b1", chapterId = "c1", spineIndex = 0, locator = null, content = "keep me", deviceId = "dev"),
                emitSyncEvent = false
            )
            dropTable("notes")

            val result = runCatching { bookRepo.deleteBook("b1") }

            assertTrue(result.isFailure, "the delete must fail when a child table is missing")
            assertNotNull(bookRepo.getBook("b1"), "the book row must survive the rollback")
        }
    }

    @Test
    fun deleteBookSuccessPathLeavesNoChildRows() = runBlocking {
        val bookRepo = JdbcBookRepository(database)
        val noteRepo = JdbcNoteRepository(database)
        bookRepo.insertBook(Book(id = "b1", title = "Atomic", epubHash = "h1", epubFileSize = 1L), emitSyncEvent = false)
        noteRepo.insertNote(
            Note(id = "n1", bookId = "b1", chapterId = "c1", spineIndex = 0, locator = null, content = "gone", deviceId = "dev"),
            emitSyncEvent = false
        )
        // A bookmark row and a per-book settings row exercise two more branches.
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO bookmarks (id, book_id, chapter_id, spine_index, locator, created_at, updated_at, device_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, "bm1"); stmt.setString(2, "b1"); stmt.setString(3, "c1")
                stmt.setInt(4, 0); stmt.setString(5, "/loc"); stmt.setLong(6, 1); stmt.setLong(7, 1); stmt.setString(8, "dev")
                stmt.executeUpdate()
            }
        }
        database.setSettings(Database.bookSettingsKey("b1"), "{}")

        bookRepo.deleteBook("b1")

        assertNull(bookRepo.getBook("b1"))
        assertEquals(0, countRows("notes", "book_id", "b1"))
        assertEquals(0, countRows("bookmarks", "book_id", "b1"))
        assertNull(database.getSettings(Database.bookSettingsKey("b1")), "per-book settings row must be removed")
    }

    @Test
    fun deleteMangaRollsBackWhenAChildDeleteFails() {
        runBlocking {
            val mangaRepo = JdbcMangaRepository(database)
            mangaRepo.upsert(
                MangaEntry(
                    id = "m1", sourceId = 1L, sourceName = "S", url = "/m/one",
                    title = "Atomic", inLibrary = true, initialized = true
                ),
                emitSyncEvent = false
            )
            dropTable("manga_chapters")

            val result = runCatching { mangaRepo.delete("m1") }

            assertTrue(result.isFailure, "the delete must fail when a child table is missing")
            assertNotNull(mangaRepo.get("m1"), "the manga row must survive the rollback")
        }
    }

    @Test
    fun deleteMangaSuccessPathLeavesNoChildRows() = runBlocking {
        val mangaRepo = JdbcMangaRepository(database)
        val chapterRepo = JdbcMangaChapterRepository(database)
        mangaRepo.upsert(
            MangaEntry(
                id = "m1", sourceId = 1L, sourceName = "S", url = "/m/one",
                title = "Cascade", inLibrary = true, initialized = true
            ),
            emitSyncEvent = false
        )
        chapterRepo.replaceChapters(
            "m1",
            listOf(
                com.folio.reader.manga.MangaChapter(id = "m1|c1", mangaId = "m1", url = "/c1", name = "Ch 1", sortOrder = 0)
            )
        )

        mangaRepo.delete("m1")

        assertNull(mangaRepo.get("m1"))
        assertEquals(0, countRows("manga_chapters", "manga_id", "m1"))
    }
}
