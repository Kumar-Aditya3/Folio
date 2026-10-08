package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Note
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B3: restoring a soft-deleted note/bookmark must advance updated_at, or sync
 * last-write-wins re-deletes the item against its own older tombstone.
 */
class RestoreTimestampTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var noteRepo: JdbcNoteRepository
    private lateinit var bookmarkRepo: JdbcBookmarkRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-restore-timestamp-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        noteRepo = JdbcNoteRepository(database)
        bookmarkRepo = JdbcBookmarkRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun restoreNoteAdvancesUpdatedAtPastTheDeleteTime() = runBlocking {
        noteRepo.insertNote(
            Note(id = "n1", bookId = "b1", chapterId = "c1", spineIndex = 0, locator = null, content = "text", deviceId = "dev"),
            emitSyncEvent = false
        )
        noteRepo.deleteNote("n1", emitSyncEvent = false)
        val deleted = assertNotNull(noteRepo.getNote("n1"))
        assertTrue(deleted.isDeleted)

        delay(25) // keep the restore's timestamp strictly past the delete's
        noteRepo.restoreNote("n1")

        val restored = assertNotNull(noteRepo.getNote("n1"))
        assertFalse(restored.isDeleted)
        assertTrue(
            restored.updatedAt > deleted.updatedAt,
            "restore must advance updated_at past the delete's (was ${deleted.updatedAt} -> ${restored.updatedAt})"
        )
    }

    @Test
    fun restoreBookmarkAdvancesUpdatedAtPastTheDeleteTime() = runBlocking {
        bookmarkRepo.insertBookmark(
            Bookmark(id = "bm1", bookId = "b1", chapterId = "c1", spineIndex = 0, locator = "/loc", deviceId = "dev"),
            emitSyncEvent = false
        )
        bookmarkRepo.deleteBookmark("bm1", emitSyncEvent = false)
        val deleted = assertNotNull(bookmarkRepo.getBookmark("bm1"))
        assertTrue(deleted.isDeleted)

        delay(25)
        bookmarkRepo.restoreBookmark("bm1")

        val restored = assertNotNull(bookmarkRepo.getBookmark("bm1"))
        assertFalse(restored.isDeleted)
        assertTrue(
            restored.updatedAt > deleted.updatedAt,
            "restore must advance updated_at past the delete's (was ${deleted.updatedAt} -> ${restored.updatedAt})"
        )
    }
}
