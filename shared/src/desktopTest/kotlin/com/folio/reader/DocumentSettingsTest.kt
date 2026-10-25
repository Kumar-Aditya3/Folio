package com.folio.reader

import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.Theme
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.withDocumentReaderMode
import com.folio.reader.settings.toReaderSettings
import com.folio.reader.settings.withFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Documents own their reading settings.
 *
 * These guards pin the two halves of that claim: a document row is independent of
 * the book row (the bug — documents inherited the book defaults), and folding a
 * `ReaderSettings` — the dialect the reader panels speak — back into a document
 * row touches only the fields a document owns, so the app-level knobs survive.
 */
class DocumentSettingsTest {

    @Test
    fun aDocumentRowIsIndependentOfTheBookRow() {
        val books = ReaderSettings(fontFamily = "Calluna", fontSize = 21f, themeId = "dusk")
        // The document row is its own default — it does not inherit the book row.
        assertEquals(DocumentSettings(), books.documentSettings)
    }

    @Test
    fun renderSettingsTakeTheDocumentFieldsAndKeepTheAppLevelOnes() {
        val books = ReaderSettings(
            fontFamily = "Calluna", fontSize = 21f, themeId = "dusk",
            ambientColor = false, eyeProtection = true,
        )
        val docs = DocumentSettings(fontFamily = "Georgia", fontSize = 15f, themeId = "sepia")
        val render = docs.toReaderSettings(books)
        // Document-owned: taken from the document row.
        assertEquals("Georgia", render.fontFamily)
        assertEquals(15f, render.fontSize)
        assertEquals("sepia", render.themeId)
        // App-level: still the book row's, so the device-wide knobs keep working.
        assertEquals(false, render.ambientColor)
        assertEquals(true, render.eyeProtection)
    }

    @Test
    fun foldingReaderSettingsBackLeavesTheBookFieldsAlone() {
        val docs = DocumentSettings(fontFamily = "Georgia", themeId = "sepia")
        val edited = ReaderSettings(fontFamily = "Literata", themeId = "paper", ambientColor = false)
        val folded = docs.withFrom(edited)
        assertEquals("Literata", folded.fontFamily)
        assertEquals("paper", folded.themeId)
        // Nothing a document does not own leaks in: the row is still a DocumentSettings
        // whose every other field is the document default, not the book row's.
        assertEquals(DocumentSettings(fontFamily = "Literata", themeId = "paper"), folded)
    }

    @Test
    fun customThemeRoundTrips() {
        val custom = Theme(
            id = "custom-1", name = "Custom", background = 1, surface = 2,
            primaryText = 3, secondaryText = 4, headingText = 5, link = 6,
            selection = 7, bookmark = 8, highlightColors = listOf(1, 2, 3, 4, 5, 6, 7, 8),
            progress = 9, divider = 10, isDark = true,
        )
        val docs = DocumentSettings(customTheme = custom)
        assertNotEquals(docs, DocumentSettings())
        assertEquals(custom, docs.toReaderSettings().customTheme)
    }

    @Test
    fun sharedPageModeEnablesTheAnimatedWordLayoutWithoutChangingBookSettings() {
        val before = ReaderSettings(fontFamily = "Georgia", themeId = "dusk", layoutMode = LayoutMode.CONTINUOUS,
            documentSettings = DocumentSettings(fontFamily = "Literata", fontSize = 19f, themeId = "sepia"))
        val paged = before.withDocumentReaderMode("SINGLE_PAGE")
        assertEquals("SINGLE_PAGE", paged.documentReaderMode)
        assertEquals(LayoutMode.PAGINATED, paged.documentSettings.layoutMode)
        assertEquals(LayoutMode.PAGINATED, paged.documentSettings.toReaderSettings(paged).layoutMode)
        assertEquals(before, paged.copy(documentReaderMode = before.documentReaderMode,
            documentSettings = before.documentSettings))
        assertEquals(before.documentSettings.copy(layoutMode = LayoutMode.PAGINATED), paged.documentSettings)
        val continuous = paged.withDocumentReaderMode("CONTINUOUS")
        assertEquals("CONTINUOUS", continuous.documentReaderMode)
        assertEquals(before.documentSettings, continuous.documentSettings)
    }

    @Test
    fun explicitWordLayoutRemainsAuthoritativeUntilTheSharedModeIsChanged() {
        val defaults = ReaderSettings(documentReaderMode = "SINGLE_PAGE",
            documentSettings = DocumentSettings(layoutMode = LayoutMode.CONTINUOUS))
        assertEquals(LayoutMode.CONTINUOUS, defaults.documentSettings.toReaderSettings(defaults).layoutMode)
        val explicitPage = defaults.documentSettings.copy(layoutMode = LayoutMode.PAGINATED)
        assertEquals(LayoutMode.PAGINATED, explicitPage.toReaderSettings(defaults).layoutMode)
        assertEquals("SINGLE_PAGE", defaults.documentReaderMode)
    }
}
