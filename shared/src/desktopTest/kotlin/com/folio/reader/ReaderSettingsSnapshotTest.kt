package com.folio.reader

import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the scope split between global defaults and per-book snapshots: the
 * Main Settings → Formatting fields (alignment, formatting mode, hyphenation)
 * have no in-reader UI, so they must stay global — snapshotting them froze the
 * first-open values and made the panel look dead on devices whose books were
 * opened before the change ("justify works on Windows, not on Android").
 */
class ReaderSettingsSnapshotTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `formatting panel fields are never snapshotted onto a book`() {
        val snapshot = ReaderSettings(
            alignment = TextAlignment.JUSTIFIED,
            formattingMode = FormattingMode.NORMALIZED,
            hyphenation = false,
            fontSize = 22f
        ).toBookSettings()

        assertNull(snapshot.alignment)
        assertNull(snapshot.formattingMode)
        assertNull(snapshot.hyphenation)
        // Fields the reader panel actually owns keep snapshotting.
        assertEquals(22f, snapshot.fontSize)
    }

    @Test
    fun `stored snapshots no longer pin formatting panel fields`() {
        val stored = BookReaderSettings(
            alignment = TextAlignment.LEFT,
            formattingMode = FormattingMode.ORIGINAL,
            hyphenation = true,
            fontSize = 14f
        )
        val base = ReaderSettings(
            alignment = TextAlignment.JUSTIFIED,
            formattingMode = FormattingMode.HYBRID,
            hyphenation = false,
            fontSize = 18f
        )

        val effective = stored.toReaderSettings(base)

        // Formatting panel fields follow the current global defaults…
        assertEquals(TextAlignment.JUSTIFIED, effective.alignment)
        assertEquals(FormattingMode.HYBRID, effective.formattingMode)
        assertEquals(false, effective.hyphenation)
        // …while per-book fields still win.
        assertEquals(14f, effective.fontSize)
    }

    @Test
    fun `snapshots written before the fix still decode and stop blocking the defaults`() {
        // Shape of the JSON this app wrote before the formatting fields went global.
        val legacyJson = """
            {"alignment":"JUSTIFIED","formattingMode":"ORIGINAL","hyphenation":true,
             "fontSize":20.0,"layoutMode":"CONTINUOUS"}
        """.trimIndent()

        val stored = lenientJson
            .decodeFromString(BookReaderSettings.serializer(), legacyJson)
        assertEquals(TextAlignment.JUSTIFIED, stored.alignment)

        val effective = stored.toReaderSettings(ReaderSettings(alignment = TextAlignment.CENTER))
        assertEquals(TextAlignment.CENTER, effective.alignment)
    }

    @Test
    fun `in reader changes still write a book owned snapshot`() {
        val updated = ReaderSettings(fontSize = 26f, themeId = "dusk")
            .copy(fontSize = 28f)
            .toBookSettings()
        assertEquals(28f, updated.fontSize)
        assertEquals("dusk", updated.themeId)
        assertTrue(updated.customTheme == null)
    }

    @Test
    fun `layout mode remains per book`() {
        val stored = BookReaderSettings(layoutMode = LayoutMode.PAGINATED)
        val effective = stored.toReaderSettings(ReaderSettings(layoutMode = LayoutMode.CONTINUOUS))
        assertEquals(LayoutMode.PAGINATED, effective.layoutMode)
    }
}
