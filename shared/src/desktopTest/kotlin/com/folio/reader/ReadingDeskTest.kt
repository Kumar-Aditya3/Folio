package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.diffFields
import com.folio.reader.settings.withFieldsFrom
import com.folio.reader.ui.home.readingDeskRevealFraction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingDeskTest {
    @Test
    fun existingSettingsRemainClassicAndTheChoiceSurvivesReload() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<ReaderSettings>("{}").homeReadingDesk)
        val root = kotlin.io.path.createTempDirectory("folio-reading-desk-").toFile()
        val database = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            val repo = JdbcSettingsRepository(database)
            repo.saveGlobalSettings(ReaderSettings(homeReadingDesk = true), emitSyncEvent = false)
            assertTrue(JdbcSettingsRepository(database).getGlobalSettings().homeReadingDesk)
            repo.saveGlobalSettings(repo.getGlobalSettings().copy(homeReadingDesk = false), emitSyncEvent = false)
            assertFalse(JdbcSettingsRepository(database).getGlobalSettings().homeReadingDesk)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun comparisonTogglePatchesOnlyItsOwnFieldAndCanResetToDefault() {
        val initial = ReaderSettings()
        val desk = initial.copy(homeReadingDesk = true)
        val concurrent = initial.copy(fontSize = 25f, ambientColor = false)
        val enabled = concurrent.withFieldsFrom(initial.diffFields(desk), desk)
        assertTrue(enabled.homeReadingDesk)
        assertEquals(25f, enabled.fontSize)
        assertFalse(enabled.ambientColor)
        val restored = enabled.withFieldsFrom(desk.diffFields(initial), initial)
        assertFalse(restored.homeReadingDesk)
        assertEquals(25f, restored.fontSize)
    }

    @Test
    fun sectionsRevealInsideTheUsableViewportAndStayVisibleAfterPassingIt() {
        assertEquals(0f, readingDeskRevealFraction(720f, 700f, 100f))
        assertEquals(0f, readingDeskRevealFraction(700f, 700f, 100f))
        assertEquals(0.5f, readingDeskRevealFraction(650f, 700f, 100f))
        assertEquals(1f, readingDeskRevealFraction(600f, 700f, 100f))
        assertEquals(1f, readingDeskRevealFraction(-300f, 700f, 100f))
        assertEquals(1f, readingDeskRevealFraction(700f, 700f, 0f))
        assertEquals(1f, readingDeskRevealFraction(Float.NaN, 700f, 100f))
    }
}
