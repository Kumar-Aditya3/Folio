package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaDownloadRepository
import com.folio.reader.database.JdbcMangaNoteRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaDownload
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
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
 * B2: deleting a manga cascades to chapters, notes and downloads — rows AND
 * the on-disk download directory (via the injected cleanup hook) — and still
 * emits the tombstone sync event and bumps the manga data revision.
 */
class MangaCascadeDeleteTest {

    private data class SyncEvent(val type: String, val id: String, val op: String, val payload: String)

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private val events = mutableListOf<SyncEvent>()
    private val deletedDirs = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-manga-cascade-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        database.onEntityChanged = { type, id, op, payload -> events += SyncEvent(type, id, op, payload) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun downloadDir(mangaId: String): File = File(tempRoot, "downloads/$mangaId")

    @Test
    fun deleteRemovesChildRowsDownloadDirAndEmitsTombstone() = runBlocking {
        val mangaId = "m1"
        val mangaRepo = JdbcMangaRepository(database) { id ->
            deletedDirs += id
            downloadDir(id).deleteRecursively()
        }
        val chapterRepo = JdbcMangaChapterRepository(database)
        val noteRepo = JdbcMangaNoteRepository(database)
        val downloadRepo = JdbcMangaDownloadRepository(database)

        mangaRepo.upsert(
            MangaEntry(
                id = mangaId, sourceId = 1L, sourceName = "S", url = "/m/one",
                title = "Cascade", inLibrary = true, initialized = true
            ),
            emitSyncEvent = false
        )
        chapterRepo.replaceChapters(
            mangaId,
            listOf(MangaChapter(id = "m1|c1", mangaId = mangaId, url = "/c1", name = "Ch 1", sortOrder = 0, downloadedPages = 2))
        )
        noteRepo.upsert(
            MangaNote(id = "mn1", mangaId = mangaId, chapterId = "m1|c1", pageIndex = 0, content = "note"),
            emitSyncEvent = false
        )
        downloadRepo.enqueue(MangaDownload(id = "m1|c1", mangaId = mangaId, chapterId = "m1|c1"))
        downloadDir(mangaId).apply {
            mkdirs()
            File(this, "001.png").writeBytes(byteArrayOf(1))
        }
        events.clear()
        val revisionBefore = database.mangaDataRevision.value

        mangaRepo.delete(mangaId)

        assertNull(mangaRepo.get(mangaId))
        assertEquals(emptyList(), chapterRepo.getChapters(mangaId), "chapters must be deleted")
        assertEquals(
            0,
            database.withConnection { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM manga_notes WHERE manga_id = ?").use { stmt ->
                    stmt.setString(1, mangaId)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            },
            "notes must be deleted"
        )
        assertEquals(
            0,
            database.withConnection { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM manga_downloads WHERE manga_id = ?").use { stmt ->
                    stmt.setString(1, mangaId)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            },
            "download queue rows must be deleted"
        )
        assertEquals(listOf(mangaId), deletedDirs, "the disk cleanup hook must run for this manga")
        assertTrue(!downloadDir(mangaId).exists(), "the download directory must be gone")
        assertTrue(database.mangaDataRevision.value > revisionBefore, "mangaDataRevision must be bumped")
        val tombstone = events.lastOrNull { it.type == "manga" }
        assertNotNull(tombstone, "a manga sync event must be emitted")
        assertEquals("DELETE", tombstone.op)
        assertEquals(mangaId, tombstone.id)
        assertTrue(tombstone.payload.contains("\"isDeleted\":true"), "payload must carry the tombstone flag")
    }
}
