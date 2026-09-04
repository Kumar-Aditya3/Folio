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
    fun appBarsAreGlassNotLids() {
        // The reader veil is near-opaque on purpose (see above), but the *app* bar
        // sits over Compose content, so it must stay see-through even at full
        // collapse. This is the invariant that keeps the masthead from turning back
        // into the grey lid the redesign removed.
        //
        // The window sits low deliberately. Half-opaque was still enough fill for the
        // collapsed bar to describe its own rectangle over the page, which is all the
        // eye needs to call it a panel; a third reads as tinted glass. The floor is
        // there so the bar does not vanish entirely and leave the title floating.
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            assertTrue(
                atmos.barGlass.alpha in 0.22f..0.42f,
                "${palette.id}: bar glass alpha ${atmos.barGlass.alpha} is outside the " +
                    "0.22–0.42 glass window — below it the bar stops reading as a surface " +
                    "at all, above it the bar reads as a solid lid",
            )
            assertTrue(
                atmos.barGlass.alpha < atmos.veilFill.alpha,
                "${palette.id}: the app bar must be more transparent than the reader veil",
            )
        }
    }

    @Test
    fun statusScrimFollowsThePaletteNotAFixedInk() {
        // A single dark ink band across the top of every theme is what put a black
        // stripe over the light palettes. The scrim now derives from the field, so
        // it must land on the same side of the lightness split as the palette —
        // that is what lets MainActivity flip the OS icons by `isDark` alone and
        // still get contrast.
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val scrim = luminance(atmos.barScrim)
            if (palette.isDark) {
                assertTrue(
                    scrim < 0.25,
                    "${palette.id}: dark palette derived a light status scrim " +
                        "(${"%.4f".format(scrim)}) — the light OS icons would disappear",
                )
            } else {
                assertTrue(
                    scrim > 0.45,
                    "${palette.id}: light palette derived a dark status scrim " +
                        "(${"%.4f".format(scrim)}) — this is the black band over paper",
                )
            }
            // It is a scrim, not a band: the page has to show through it. Kept well
            // under half, because in this layout the only thing behind the scrim is
            // the page's own field — a heavy one buys no contrast the field does not
            // already give the OS icons, and costs a painted strip along the top.
            assertTrue(
                atmos.barScrim.alpha <= 0.55f,
                "${palette.id}: status scrim alpha ${atmos.barScrim.alpha} is a painted band",
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
