package com.folio.reader

import com.folio.reader.ui.settings.SettingsCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * U-TYPE: the merged Text & page category. Route names must round-trip, the
 * pre-merge routes must keep resolving, and no category may lose its label.
 */
class SettingsCategoryMappingTest {

    @Test
    fun routeNamesRoundTrip() {
        SettingsCategory.entries.forEach { category ->
            assertEquals(
                category,
                SettingsCategory.fromRoute(category.routeName),
                "fromRoute('${category.routeName}') must return $category"
            )
        }
    }

    @Test
    fun legacyRoutesResolveToTheMergedCategory() {
        listOf("typography", "layout", "formatting", "text").forEach { legacy ->
            assertEquals(
                SettingsCategory.TEXT_AND_PAGE,
                SettingsCategory.fromRoute(legacy),
                "legacy route '$legacy' must land on Text & page, not a dead end"
            )
        }
    }

    @Test
    fun everyCategoryHasALabelAndARoute() {
        SettingsCategory.entries.forEach { category ->
            assertTrue(category.displayName.isNotBlank(), "${category.name} needs a label")
            assertTrue(category.routeName.isNotBlank(), "${category.name} needs a route name")
            assertTrue(
                category.routeName.matches(Regex("[a-z_]+")),
                "${category.name}'s route '$${category.routeName}' must be URL-safe"
            )
        }
    }

    @Test
    fun unknownRoutesResolveToNothing() {
        assertEquals(null, SettingsCategory.fromRoute("nope"))
        assertEquals(null, SettingsCategory.fromRoute(""))
    }

    @Test
    fun fromRouteFindsCategoryByRouteWithoutPrefix() {
        assertNotNull(SettingsCategory.fromRoute("text_and_page"))
        assertEquals(SettingsCategory.TEXT_AND_PAGE, SettingsCategory.fromRoute("text_and_page"))
    }
}
