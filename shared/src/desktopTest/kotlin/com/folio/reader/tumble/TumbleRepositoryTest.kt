package com.folio.reader.tumble

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TumbleRepositoryTest {
    private fun fixture(block: suspend (JdbcSettingsRepository) -> Unit) = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-tumble-").toFile()
        val db = Database(java.io.File(root, "test.db").absolutePath)
        try { block(JdbcSettingsRepository(db)) } finally {
            db.close()
            check(root.toPath().toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.deleteRecursively()
        }
    }
    @Test fun reloadPreservesEveryAttemptAndFinishIsIdempotent() = fixture { settings ->
        val repo = TumbleRepository(settings)
        assertNull(repo.load())
        val active = TumbleState("first", 42, 1000)
        repo.checkpoint(active)
        assertEquals(active, TumbleRepository(settings).load())
        val run = TumbleRun(active.copy(phase = TumblePhase.FINISHED, score = 900), 2000)
        repo.finish(run); repo.finish(run)
        repo.checkpoint(active) // A delayed checkpoint must not revive a finished run.
        val next = TumbleRepository(settings)
        assertNull(next.load())
        assertEquals(listOf(run), next.library.value.runs)
        next.finish(TumbleRun(active.copy(id = "second"), 3000, abandoned = true))
        val reload = TumbleRepository(settings)
        reload.load()
        assertEquals(2, reload.library.value.runs.size)
        assertEquals(1, reload.library.value.runs.count { it.ranked })
    }
    @Test fun decorationsUseReadingHighWaterMarkAndCannotOverspend() = fixture { settings ->
        val repo = TumbleRepository(settings); repo.load()
        repo.readingProgress(30); repo.readingProgress(15)
        assertEquals(2, repo.library.value.scraps())
        repo.decorate("stars")
        assertFalse("stars" in repo.library.value.owned)
        repo.decorate("lamp"); repo.decorate("lamp")
        assertEquals(0, repo.library.value.scraps())
        repo.visible(false)
        val next = TumbleRepository(settings); next.load()
        assertEquals("lamp", next.library.value.decoration)
        assertFalse(next.library.value.homeVisible)
        assertEquals(30L, next.library.value.readingMinutes)
    }
    @Test fun corruptHistoryIsReportedAndNeverOverwritten() = fixture { settings ->
        settings.setRaw("tumble_library_v1", "unreadable")
        val repo = TumbleRepository(settings)
        assertFails { repo.load() }
        assertFails { repo.visible(false) }
        assertEquals("unreadable", settings.getRaw("tumble_library_v1"))
    }
    @Test fun personalityReactsToReadingTimeAndAbsence() {
        assertEquals("sleep", picaPersonality(23, 0, 30, 0, 0, emptyList()).pose)
        assertEquals("read", picaPersonality(14, 10, 30, 0, 0, emptyList()).pose)
        assertEquals("Content", picaPersonality(14, 30, 30, 0, 0, emptyList()).mood)
        assertEquals("A familiar face", picaPersonality(14, 0, 30, 7, 0, emptyList()).mood)
    }
    @Test fun newArcadeAndDiscoveriesSurviveReloadWithoutLosingLegacyRuns() = fixture { settings ->
        val repo=TumbleRepository(settings);repo.load()
        val legacy=TumbleRun(TumbleState("old",4,1000,phase=TumblePhase.FINISHED,score=100),2000)
        repo.finish(legacy)
        repo.readingProgress(300) // Historical reading grants cosmetics, not twenty queued stories.
        assertEquals(1L,repo.library.value.pendingDiscoveries())
        repo.discover(0,3000)
        assertEquals(0L,repo.library.value.pendingDiscoveries())
        assertEquals(2,repo.library.value.bond.curiosity)
        repo.readingProgress(315)
        assertEquals(1L,repo.library.value.pendingDiscoveries())
        repo.discover(1,4000);repo.pet(5000)
        val active=ArcadeEngine(ArcadeState("new",PocketGame.SHELVES,42,5000)).state
        repo.checkpointArcade(active)
        val restored=TumbleRepository(settings);restored.load()
        assertEquals(active,restored.loadArcade())
        val run=ArcadeRun(active.copy(finished=true,score=400),6000)
        restored.finishArcade(run);restored.finishArcade(run);restored.checkpointArcade(active)
        val again=TumbleRepository(settings);again.load()
        assertNull(again.loadArcade())
        assertEquals(listOf(legacy),again.library.value.runs)
        assertEquals(listOf(run),again.library.value.arcadeRuns)
        assertEquals(3,again.library.value.bond.memories.size)
        assertEquals(1,again.library.value.bond.affection)
        assertEquals(2,again.library.value.bond.calm)
    }
    @Test fun finishedBookMemoriesArePersistentAndDuplicateSafe() = fixture { settings ->
        val repo=TumbleRepository(settings);repo.load()
        repo.rememberBook("book","Small Worlds",1000);repo.rememberBook("book","Small Worlds",2000)
        val next=TumbleRepository(settings);next.load()
        assertEquals(1,next.library.value.bond.memories.size)
        assertEquals("book:book",next.library.value.bond.memories.single().key)
        assertEquals(1,next.library.value.bond.affection)
    }
}
