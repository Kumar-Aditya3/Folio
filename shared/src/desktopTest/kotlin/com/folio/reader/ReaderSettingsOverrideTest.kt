package com.folio.reader

import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import com.folio.reader.settings.changedFields
import com.folio.reader.settings.clearing
import com.folio.reader.settings.overriddenFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the §4.3 scope helpers: [overriddenFields] feeds the reader panel's
 * per-book override dots and [clearing] backs "Reset this book to defaults"
 * and the "All books" write-through, so both must agree with what
 * [com.folio.reader.settings.ReaderSettings.copyWith] actually honours.
 */
class ReaderSettingsOverrideTest {

    private val global = ReaderSettings(
        fontFamily = "Literata",
        fontSize = 18f,
        themeId = "paper",
        layoutMode = LayoutMode.CONTINUOUS
    )

    @Test
    fun `fresh snapshot of the globals reports no overrides`() {
        assertTrue(global.toBookSettings().overriddenFields(global).isEmpty())
    }

    @Test
    fun `only fields differing from the global defaults count as overrides`() {
        // themeId is snapshotted at its global value, so it must not register.
        val snapshot = global.copy(fontSize = 22f, themeId = "paper").toBookSettings()

        assertEquals(setOf("fontSize"), snapshot.overriddenFields(global))
    }

    @Test
    fun `legacy formatting fields never count as overrides`() {
        // Pre-937069c snapshots can carry these, but copyWith ignores them,
        // so they must never surface as override dots.
        val stored = BookReaderSettings(
            alignment = TextAlignment.JUSTIFIED,
            formattingMode = FormattingMode.NORMALIZED,
            hyphenation = false,
            fontSize = 22f
        )

        assertEquals(setOf("fontSize"), stored.overriddenFields(global))
    }

    @Test
    fun `clearing drops only the named fields`() {
        val snapshot = global
            .copy(fontSize = 22f, themeId = "dusk", layoutMode = LayoutMode.PAGINATED)
            .toBookSettings()

        val cleared = snapshot.clearing(setOf("fontSize", "themeId"))

        assertNull(cleared.fontSize)
        assertNull(cleared.themeId)
        assertEquals(LayoutMode.PAGINATED, cleared.layoutMode)
    }

    @Test
    fun `clearing every override makes the book follow the globals again`() {
        val snapshot = global.copy(fontSize = 22f, themeId = "dusk").toBookSettings()

        val cleared = snapshot.clearing(snapshot.overriddenFields(global))

        assertTrue(cleared.overriddenFields(global).isEmpty())
        val effective = cleared.toReaderSettings(global)
        assertEquals(global.fontSize, effective.fontSize)
        assertEquals(global.themeId, effective.themeId)
    }

    @Test
    fun `changed fields reports only what differs between globals`() {
        val updated = global.copy(fontSize = 20f, themeId = "dusk")

        assertEquals(setOf("fontSize", "themeId"), global.changedFields(updated))
        assertTrue(global.changedFields(global).isEmpty())
    }

    @Test
    fun `all books write clears only the changed override`() {
        // The book owns a bigger font and its own theme; the reader then changes
        // the font size for every book. The font-size override must disappear
        // (the book follows the new default) while the theme override survives.
        val snapshot = global.copy(fontSize = 22f, themeId = "dusk").toBookSettings()
        val newGlobal = global.copy(fontSize = 20f)

        val cleared = snapshot.clearing(global.changedFields(newGlobal))

        val effective = cleared.toReaderSettings(newGlobal)
        assertEquals(20f, effective.fontSize)
        assertEquals("dusk", effective.themeId)
        assertEquals(setOf("themeId"), cleared.overriddenFields(newGlobal))
    }
}
