package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.diffFields
import com.folio.reader.settings.withFieldsFrom
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B0: a patch-write built from a stale snapshot must not clobber fields another
 * writer changed in between, and the sync event it emits must carry the merged
 * row (not the stale one the caller derived its patch from).
 */
class MergeGlobalSettingsStaleSnapshotTest {

    private data class SyncEvent(val type: String, val id: String, val op: String, val payload: String)

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var repo: JdbcSettingsRepository
    private val events = mutableListOf<SyncEvent>()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-merge-settings-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        repo = JdbcSettingsRepository(database)
        database.onEntityChanged = { type, id, op, payload -> events += SyncEvent(type, id, op, payload) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun staleSnapshotPatchKeepsConcurrentWriteAndEmitsMergedRow() = runBlocking {
        val seeded = ReaderSettings(themeId = "paper", appThemeId = "light", fontSize = 18f)
        repo.saveGlobalSettings(seeded, emitSyncEvent = false)
        val snapshot = repo.getGlobalSettings()

        // A second writer changes the reading theme after the snapshot was taken.
        repo.saveGlobalSettings(seeded.copy(themeId = "dusk"), emitSyncEvent = false)
        events.clear()

        // The first writer patches only the app theme, derived from its stale snapshot.
        val updated = snapshot.copy(appThemeId = "midnight")
        val changed = updated.diffFields(snapshot)
        assertEquals(setOf("appThemeId"), changed)
        val merged = repo.mergeGlobalSettings { it.withFieldsFrom(changed, updated) }

        assertEquals("dusk", merged.themeId, "concurrent write must survive the patch")
        assertEquals("midnight", merged.appThemeId, "patched field must be applied")
        assertEquals(merged, repo.getGlobalSettings())

        val settingsEvent = events.filter { it.type == "settings" }.lastOrNull()
        assertNotNull(settingsEvent, "merge must emit a settings sync event")
        assertEquals("UPSERT", settingsEvent.op)
        assertEquals(
            merged,
            json.decodeFromString(ReaderSettings.serializer(), settingsEvent.payload),
            "sync payload must be the merged row"
        )
    }

    @Test
    fun mergeWithoutChangeWritesNothing() = runBlocking {
        repo.saveGlobalSettings(ReaderSettings(themeId = "sepia"), emitSyncEvent = false)
        events.clear()

        val merged = repo.mergeGlobalSettings { it }

        assertEquals(ReaderSettings(themeId = "sepia"), merged)
        assertTrue(events.none { it.type == "settings" }, "a no-op merge must not emit a sync event")
    }

    @Test
    fun mergeTransformSeesCurrentRowNotCallersSnapshot() = runBlocking {
        repo.saveGlobalSettings(ReaderSettings(dailyGoalMinutes = 30, customFonts = emptyList()), emitSyncEvent = false)

        val merged = repo.mergeGlobalSettings { it.copy(dailyGoalMinutes = 75) }

        assertEquals(75, merged.dailyGoalMinutes)
        assertEquals(75, repo.getGlobalSettings().dailyGoalMinutes)
    }
}
