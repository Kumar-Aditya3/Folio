package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaNoteRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaNoteRepositoryTest {
    @Test
    fun mangaQueryIncludesEveryChapterAndExcludesOtherManga() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-manga-notes-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            val manga = JdbcMangaRepository(db)
            manga.upsert(MangaEntry("manga", 1, "S", "/manga", "Manga"), false)
            manga.upsert(MangaEntry("other", 1, "S", "/other", "Other"), false)
            val notes = JdbcMangaNoteRepository(db)
            notes.upsert(MangaNote("one", "manga", "chapter-one", 3, "One"), false)
            notes.upsert(MangaNote("two", "manga", "chapter-two", 3, "Two"), false)
            notes.upsert(MangaNote("other", "other", "other-chapter", 3, "Other"), false)
            assertEquals(setOf("one", "two"), notes.notesForManga("manga").map { it.id }.toSet())
            assertEquals(listOf("one"), notes.notesForChapter("chapter-one").map { it.id })
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test
    fun openMangaNoteFlowRefreshesAfterInsertEditAndDelete() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-manga-notes-live-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            JdbcMangaRepository(db).upsert(MangaEntry("manga", 1, "S", "/manga", "Manga"), false)
            val notes = JdbcMangaNoteRepository(db)
            val note = MangaNote("note", "manga", "chapter", 2, "Before")
            val inserted = async(start = CoroutineStart.UNDISPATCHED) {
                notes.observeNotesForManga("manga").first { it.isNotEmpty() }
            }
            notes.upsert(note, false)
            val stored = withTimeout(5_000) { inserted.await() }.single()
            assertEquals(note.id, stored.id)
            assertEquals(note.content, stored.content)
            val edited = async(start = CoroutineStart.UNDISPATCHED) {
                notes.observeNotesForManga("manga").first { it.firstOrNull()?.content == "After" }
            }
            notes.upsert(note.copy(content = "After"), false)
            assertEquals("After", withTimeout(5_000) { edited.await() }.single().content)
            val deleted = async(start = CoroutineStart.UNDISPATCHED) {
                notes.observeNotesForManga("manga").first { it.isEmpty() }
            }
            notes.delete(note.id, false)
            assertTrue(withTimeout(5_000) { deleted.await() }.isEmpty())
        } finally { db.close(); root.deleteRecursively() }
    }
}
