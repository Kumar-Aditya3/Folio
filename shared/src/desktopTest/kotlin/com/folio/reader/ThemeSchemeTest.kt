package com.folio.reader

import com.folio.reader.settings.Theme
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.ThemePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The theme scheme contract. Reader presets, app palettes and the packs that pair
 * them are three parallel registries keyed by hand-written strings, and each one
 * has already drifted once: a preset absent from the picker lists is unselectable,
 * a palette absent from ThemePack.ALL is unreachable, and a pack pointing at a
 * missing id silently falls back to Paper. These are the invariants the UI relies
 * on to stay honest when a theme is added.
 */
class ThemeSchemeTest {

    @Test
    fun everyPresetIsWellFormed() {
        for ((key, theme) in Theme.PRESETS) {
            assertEquals(key, theme.id, "preset declared under key \"$key\" carries id \"${theme.id}\"")
            assertEquals(8, theme.highlightColors.size, "${theme.id} must declare exactly 8 highlight colours")
            assertTrue(theme.name.isNotBlank(), "${theme.id} has no display name")
        }
    }

    @Test
    fun everyPresetIsSelectable() {
        // A preset the pickers do not list cannot be chosen on either platform.
        assertEquals(Theme.PRESETS.keys.toSet(), Theme.PICKER.map { it.id }.toSet())
    }

    @Test
    fun everyPackResolvesOnBothSides() {
        for (pack in ThemePack.ALL) {
            assertTrue(Theme.PRESETS.containsKey(pack.readerThemeId),
                "${pack.id} points at missing reader theme \"${pack.readerThemeId}\"")
            assertTrue(AppPalette.entries.any { it.id == pack.appPaletteId },
                "${pack.id} points at missing app palette \"${pack.appPaletteId}\"")
        }
        assertEquals(ThemePack.ALL.size, ThemePack.ALL.map { it.id }.distinct().size,
            "two packs share an id, so one of them can never be applied")
    }

    @Test
    fun everyAppPaletteIsReachableThroughAPack() {
        // ThemePack.ALL is the only theme picker in SettingsScreen, so an orphan
        // palette is one the user can never select.
        val packed = ThemePack.ALL.map { it.appPaletteId }.toSet()
        val orphans = AppPalette.entries.map { it.id }.filterNot { it in packed }
        assertTrue(orphans.isEmpty(), "app palettes with no theme pack: $orphans")
    }

    @Test
    fun readerAndAppDarknessAgreePerPack() {
        // A light page under dark chrome (or the reverse) is the mismatch a pack is
        // supposed to prevent; the whole point of pairing them is that they agree.
        for (pack in ThemePack.ALL) {
            val appDark = AppPalette.byId(pack.appPaletteId).isDark
            val readerDark = Theme.PRESETS.getValue(pack.readerThemeId).isDark
            assertEquals(appDark, readerDark, "${pack.id} pairs ${pack.appPaletteId} with ${pack.readerThemeId}")
        }
    }
}
