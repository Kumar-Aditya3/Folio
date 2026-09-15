package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioColors
import com.folio.reader.ui.theme.deepenInkRoles
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17 contrast pass — the ink derivation's own contract, so widening the
 * hierarchy can never quietly narrow it (or drop a palette under the §15
 * floors the raw palettes are still pinned to).
 */
class InkDeepeningTest {

    @Test
    fun deepeningOnlyEverRaisesPrimaryInkContrast() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            val d = deepenInkRoles(c)
            val raw = wcag(c.onSurface, c.surface)
            val deep = wcag(d.onSurface, d.surface)
            assertTrue(
                deep >= raw - 1e-9,
                "${palette.id}: onSurface contrast fell ($raw → $deep)",
            )
            val rawBg = wcag(c.onBackground, c.background)
            val deepBg = wcag(d.onBackground, d.background)
            assertTrue(
                deepBg >= rawBg - 1e-9,
                "${palette.id}: onBackground contrast fell ($rawBg → $deepBg)",
            )
            // And it actually moves — a derivation that changes nothing is a
            // bug wearing a seam (Rule 19's spirit).
            assertTrue(deep > raw, "${palette.id}: onSurface did not deepen (already at extreme?)")
        }
    }

    @Test
    fun secondaryInkAndSurfacesAreUntouched() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            val d = deepenInkRoles(c)
            assertEquals(c.onSurfaceVariant, d.onSurfaceVariant, "${palette.id}: variant moved")
            assertEquals(c.surface, d.surface, "${palette.id}: surface moved")
            assertEquals(c.background, d.background, "${palette.id}: background moved")
            assertEquals(c.primary, d.primary, "${palette.id}: primary moved")
            assertEquals(c.accentProgress, d.accentProgress, "${palette.id}: accentProgress moved")
        }
    }

    @Test
    fun deepenedInkStillClearsTheReadingFloor() {
        // The derivation runs after the raw palettes' floors, so it must not be
        // able to fall below them either (it cannot — it only pushes toward the
        // extreme — but the pin is cheap and the regression it prevents is not).
        for (palette in AppPalette.entries) {
            val d = deepenInkRoles(palette.colors)
            val ratio = wcag(d.onSurface, d.surface)
            assertTrue(ratio >= 7.0, "${palette.id}: deepened ink is $ratio:1 — below the 7:1 floor")
        }
    }

    @Test
    fun deepeningPreservesTheHierarchy() {
        // Secondary ink stays secondary: deepening the primary must widen, never
        // close, the split between the two inks.
        for (palette in AppPalette.entries) {
            val c = palette.colors
            val d = deepenInkRoles(c)
            assertTrue(
                wcag(d.onSurface, d.surface) > wcag(d.onSurfaceVariant, d.surface),
                "${palette.id}: variant is no longer subordinate after deepening",
            )
        }
    }

    @Test
    fun deepeningNeverAddsChroma() {
        // The Rule 22 allowlist palettes keep their character by lightness; the
        // derivation travels toward an achromatic extreme, so whatever trace of
        // hue an ink carried can only shrink, never grow. (Their inks were never
        // perfectly achromatic to begin with — #202124 carries a whisper of
        // blue — so the premise is monotonicity, not purity.)
        for (id in listOf("light", "dark", "silver", "graphite")) {
            val c = AppPalette.byId(id).colors
            val d = deepenInkRoles(c)
            assertTrue(
                chromaSpread(d.onSurface) <= chromaSpread(c.onSurface) + 1e-4f,
                "$id: deepened ink gained chroma",
            )
        }
    }

    @Test
    fun extremeInkIsAFixedPoint() {
        // A palette already at the extreme deepens to itself, so the derivation
        // is stable where it has nothing to do.
        val atExtreme = FolioColors(
            surface = Color.White,
            onSurface = Color.Black,
            onBackground = Color.Black,
        )
        val d = deepenInkRoles(atExtreme)
        assertEquals(Color.Black, d.onSurface)
        assertEquals(Color.Black, d.onBackground)
        // And an ink with room left lands strictly between where it was and the
        // extreme — monotone, never past it. (Compose lerps colours gamma-
        // correctly, so the pin is the sandwich, not a hand-computed channel.)
        val room = FolioColors(surface = Color.White, onSurface = Color(0xFF404040))
        val deepened = deepenInkRoles(room).onSurface
        assertTrue(deepened.red < room.onSurface.red, "deepened ink did not move toward black")
        assertTrue(deepened.red > 0f, "deepened ink overshot the extreme")
    }

    // ── colour math (the same sRGB helpers ThemeSchemeTest uses) ────────────

    /** Channel spread, the cheap chroma proxy: max − min of the RGB triple. */
    private fun chromaSpread(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    private fun wcag(fg: Color, bg: Color): Double {
        val l1 = relLum(fg)
        val l2 = relLum(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private fun relLum(c: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
