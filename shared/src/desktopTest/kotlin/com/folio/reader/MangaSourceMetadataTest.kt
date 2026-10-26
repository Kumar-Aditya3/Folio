package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MangaSourceMetadataTest {
    @Test
    fun extensionRoutesSurviveDatabaseReopenAndStateOnlyUpserts() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-source-metadata-").toFile()
        val path = DesktopPlatform(root).fileSystem.getDatabasePath()
        var database = Database(path)
        val mangaMemo = """{"path":"/manga/series/","slug":"series"}"""
        val chapterMemo = """{"mangaId":"abc123","token":"route-token"}"""
        try {
            JdbcMangaRepository(database).upsert(
                MangaEntry("m", 12, "Source", "123", "Title", sourceMemo = mangaMemo),
                emitSyncEvent = false,
            )
            JdbcMangaChapterRepository(database).replaceChapters("m", listOf(
                MangaChapter("c", "m", "1", "Chapter 1", sourceMemo = chapterMemo),
            ))
            database.close()
            database = Database(path)
            val mangas = JdbcMangaRepository(database)
            val chapters = JdbcMangaChapterRepository(database)
            assertEquals(mangaMemo, mangas.get("m")?.sourceMemo)
            assertEquals(chapterMemo, chapters.getChapter("c")?.sourceMemo)
            // Older sync payloads carry no local source memo.
            mangas.upsert(MangaEntry("m", 12, "Source", "123", "Updated title"), emitSyncEvent = false)
            assertEquals(mangaMemo, mangas.get("m")?.sourceMemo)
            chapters.markRead(listOf("c"), true, emitSyncEvent = false)
            assertEquals(chapterMemo, chapters.getChapter("c")?.sourceMemo)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
