package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaHistoryRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.manga.MangaEntry
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaHistoryObservationTest {
    @Test fun recentReadsRefreshOnRecordAndClearWithoutReopeningTheFlow() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-recent-history-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            JdbcMangaRepository(db).upsert(MangaEntry("manga", 1, "Source", "/manga", "Title"), false)
            val history = JdbcMangaHistoryRepository(db)
            val initialRead = CompletableDeferred<Unit>()
            val inserted = async(start = CoroutineStart.UNDISPATCHED) {
                history.observeRecent(25).onEach { initialRead.complete(Unit) }.first { it.isNotEmpty() }
            }
            withTimeout(5_000) { initialRead.await() }
            history.record("manga", null, title = "Title")
            assertEquals("manga", withTimeout(5_000) { inserted.await() }.single().mangaId)
            val beforeClear = CompletableDeferred<Unit>()
            val cleared = async(start = CoroutineStart.UNDISPATCHED) {
                history.observeRecent(25).onEach { beforeClear.complete(Unit) }.first { it.isEmpty() }
            }
            withTimeout(5_000) { beforeClear.await() }
            history.clear()
            assertTrue(withTimeout(5_000) { cleared.await() }.isEmpty())
        } finally {
            db.close()
            val target = root.toPath().toAbsolutePath().normalize()
            check(target.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.deleteRecursively()
        }
    }
}
