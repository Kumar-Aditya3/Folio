package com.folio.reader.settings

import kotlin.test.*

class SettingsSearchIndexTest {
    @Test fun searchFindsActionsByIntentAndRequiresEveryWord() {
        assertEquals(FolioSettingsCategory.DISCOVER, searchSettings("hide discover").first().route)
        assertEquals(FolioSettingsCategory.TRANSPARENCY, searchSettings("reader opacity").first().route)
        assertEquals(FolioSettingsCategory.TEXT_AND_PAGE, searchSettings("font weight").first().route)
        assertEquals(searchSettings("font weight"), searchSettings("font-weight"))
        assertEquals(FolioSettingsCategory.ANIMATIONS, searchSettings("reduced motion").first().route)
        assertEquals("@extensions", searchSettings("install extension").first().route)
        assertTrue(searchSettings("thisdoesnotexist").isEmpty())
        assertTrue(searchSettings("font thisdoesnotexist").isEmpty())
    }

    @Test fun emptySearchOffersUsefulDestinationsAndInputIsCaseAndAccentInsensitive() {
        assertEquals(8, searchSettings("   ").size)
        assertEquals(searchSettings("themes"), searchSettings("  THÉMES  "))
        assertEquals(settingsSearchEntries.size, settingsSearchEntries.map { it.route }.distinct().size)
        assertTrue(settingsSearchEntries.any { it.route == FolioSettingsCategory.DISCOVER })
    }
}
