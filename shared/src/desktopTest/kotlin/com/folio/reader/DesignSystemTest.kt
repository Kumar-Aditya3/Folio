package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphereFor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants for the redesign's material system.
 *
 * These are the rules a screen cannot violate without the whole depth model
 * collapsing back into "everything is a card", so they are pinned here rather
 * than left to visual review:
 *
 *  1. every palette derives an atmosphere, and its lighting model matches its own
 *     background lightness;
 *  2. raised, panel and sunken fills are actually *distinguishable* — the point of
 *     three materials is three readable values;
 *  3. the elevation ladder is strictly increasing, so "raised" can never render at
 *     or below "panel";
 *  4. the spacing rhythm is strictly increasing for the same reason;
 *  5. ambient shadow is never pure black on a light palette (a black shadow on
 *     warm paper reads as a hole).
 */
class DesignSystemTest {

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    @Test
    fun everyPaletteDerivesACoherentAtmosphere() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            // The lighting model must agree with the palette's own declaration:
            // a "dark" palette that derived a light atmosphere would invert every
            // rim in the app.
            assertEquals(
                palette.isDark,
                atmos.isDark,
                "${palette.id}: atmosphere darkness (${atmos.isDark}) disagrees with " +
                    "AppPalette.isDark (${palette.isDark})",
            )
            assertTrue(
                atmos.pools.size == 3,
                "${palette.id}: expected exactly three ambient pools, got ${atmos.pools.size}",
            )
            // Atmosphere is depth, not decoration: a pool bright enough to read as
            // a shape would turn the app into a gradient showcase.
            assertTrue(
                atmos.poolAlpha <= 0.18f,
                "${palette.id}: pool alpha ${atmos.poolAlpha} exceeds the 0.18 atmosphere ceiling",
            )
        }
    }

    @Test
    fun materialsAreVisuallyDistinguishable() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val raised = luminance(atmos.raisedFill)
            val sunken = luminance(atmos.sunkenFill)
            val field = luminance(atmos.fieldTop)
            // A raised surface must be lighter than a sunken one on every palette:
            // that single relationship is what makes the depth legible.
            assertTrue(
                raised > sunken,
                "${palette.id}: raised fill (${"%.4f".format(raised)}) is not lighter than " +
                    "sunken fill (${"%.4f".format(sunken)}) — depth would read inverted",
            )
            // And each must differ from the page itself, or the material vanishes.
            assertTrue(
                abs(raised - field) > 0.002 || abs(sunken - field) > 0.002,
                "${palette.id}: neither raised nor sunken separates from the field",
            )
        }
    }

    @Test
    fun elevationLadderIsStrictlyIncreasing() {
        assertTrue(FolioTokens.elevationFlat < FolioTokens.elevationPanel)
        assertTrue(FolioTokens.elevationPanel < FolioTokens.elevationVeil)
        assertTrue(FolioTokens.elevationVeil < FolioTokens.elevationRaised)
    }

    @Test
    fun spacingRhythmIsStrictlyIncreasing() {
        assertTrue(FolioTokens.spaceHair < FolioTokens.space1)
        assertTrue(FolioTokens.space3 < FolioTokens.spaceBeat)
        assertTrue(FolioTokens.spaceBeat < FolioTokens.spaceMovement)
    }

    @Test
    fun coverLadderIsStrictlyIncreasing() {
        assertTrue(FolioTokens.coverInline < FolioTokens.coverShelf)
        assertTrue(FolioTokens.coverShelf < FolioTokens.coverFeature)
        assertTrue(FolioTokens.coverFeature < FolioTokens.coverAnchor)
    }

    @Test
    fun veilStaysOpaqueEnoughForReaderMenus() {
        // Reader bars and panels are drawn straight over rendered page text (and on
        // desktop over a native browser surface). At the old 0.82/0.86 alphas the
        // page bled through and menu labels lost contrast on every palette, so the
        // veil keeps its material character but not its transparency.
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            assertTrue(
                atmos.veilFill.alpha >= 0.95f,
                "${palette.id}: veil alpha ${atmos.veilFill.alpha} lets page text through the " +
                    "reader menus",
            )
        }
    }

    @Test
    fun floatingNavCapsuleStaysACapsule() {
        // The bar is detached, so it needs air on every side and a width ceiling —
        // a full-bleed capsule is just a bar with rounded corners.
        assertTrue(
            FolioTokens.navFloatInset > FolioTokens.spaceHair,
            "the floating nav needs a real inset, not a hairline",
        )
        assertTrue(
            FolioTokens.navFloatMaxWidth > FolioTokens.navFloatHeight * 4,
            "four items must fit inside the capsule's width ceiling",
        )
    }

    @Test
    fun lightPalettesDoNotCastBlackShadows() {
        for (palette in AppPalette.entries.filter { !it.isDark }) {
            val atmos = atmosphereFor(palette.colors)
            assertTrue(
                atmos.shadowAmbient != Color.Black,
                "${palette.id}: a pure-black ambient shadow on a light page reads as a hole",
            )
        }
    }

    @Test
    fun darkPalettesDampenShadowsAndLightPalettesDoNot() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            if (palette.isDark) {
                assertTrue(
                    atmos.shadowScale < 1f,
                    "${palette.id}: dark palettes must trim elevation — the same physical " +
                        "shadow reads far heavier on a dark field",
                )
            } else {
                assertEquals(1f, atmos.shadowScale, "${palette.id}: light palettes render full elevation")
            }
        }
    }
}
