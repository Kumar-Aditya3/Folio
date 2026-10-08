package com.folio.reader.settings

import com.folio.reader.ui.settings.SettingsCategory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §14.2 route compat: the merged Text & page screen replaced the Typography,
 * Layout and Formatting destinations, but their route values must keep
 * resolving — a deep link or saved destination from an older build landing on
 * a dead end is a regression.
 */
class SettingsRouteCompatTest {

    @Test
    fun legacyCategoriesResolveToTheMergedScreen() {
        assertEquals(
            SettingsCategory.TEXT_AND_PAGE,
            SettingsCategory.fromRoute(FolioSettingsCategory.TYPOGRAPHY),
            "settings/typography must land on Text & page"
        )
        assertEquals(
            SettingsCategory.TEXT_AND_PAGE,
            SettingsCategory.fromRoute(FolioSettingsCategory.LAYOUT),
            "settings/layout must land on Text & page"
        )
        assertEquals(
            SettingsCategory.TEXT_AND_PAGE,
            SettingsCategory.fromRoute(FolioSettingsCategory.FORMATTING),
            "settings/formatting must land on Text & page"
        )
    }

    @Test
    fun mergedCategoryRouteAgreesWithSharedEnum() {
        assertEquals(
            SettingsCategory.TEXT_AND_PAGE,
            SettingsCategory.fromRoute(FolioSettingsCategory.TEXT_AND_PAGE),
            "the hub's merged destination must resolve through the shared mapping"
        )
    }

    @Test
    fun mergedRoutesCoverTheOldOnes() {
        // Everything the hub can now send for the merged screen.
        listOf(
            FolioSettingsCategory.TEXT_AND_PAGE,
            FolioSettingsCategory.TYPOGRAPHY,
            FolioSettingsCategory.LAYOUT,
            FolioSettingsCategory.FORMATTING,
        ).forEach { route ->
            assertEquals(
                SettingsCategory.TEXT_AND_PAGE,
                SettingsCategory.fromRoute(route),
                "'$route' must land on Text & page"
            )
        }
    }
}
