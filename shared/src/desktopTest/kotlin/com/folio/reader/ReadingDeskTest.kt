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
    fun theRetiredReadingDeskKeyIsIgnoredOnDecode() {
        val json = Json { ignoreUnknownKeys = true }
        // The field is gone from ReaderSettings; a blob an older build wrote carrying the key
        // must still decode cleanly rather than fall back to defaults.
        val decoded = json.decodeFromString<ReaderSettings>("""{"homeReadingDesk": false}""")
        assertEquals(60, decoded.dailyGoalMinutes)
    }

    @Test
    fun comparisonTogglePatchesOnlyItsOwnFieldAndCanResetToDefault() {
        val initial = ReaderSettings()
        val desk = initial.copy(homeCoverTint = false)
        val concurrent = initial.copy(fontSize = 25f, ambientColor = false)
        val enabled = concurrent.withFieldsFrom(initial.diffFields(desk), desk)
        assertFalse(enabled.homeCoverTint)
        assertEquals(25f, enabled.fontSize)
        assertFalse(enabled.ambientColor)
        val restored = enabled.withFieldsFrom(desk.diffFields(initial), initial)
        assertTrue(restored.homeCoverTint)
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
