package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.barGlassFor
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.mixG
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
 *     warm paper reads as a hole);
 *  6. a bar's glass may take the colour of the room it stands in — the book being
 *     read — and never its weight, so the glass window holds in a lit room too.
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
            // Measure the material as it is *drawn*. sunkenFill is a low-alpha layer
            // that the field shows through, so comparing its raw RGB against an opaque
            // raisedFill compares two different kinds of thing: on the near-black
            // rooms the counter-accent that gives the well its temperature is lighter
            // than a surface that is almost black, and depth reads inverted on paper
            // while rendering correctly on screen. Compositing over the field is the
            // only way the assertion describes the pixels.
            val raised = luminance(mixG(atmos.fieldTop, atmos.raisedFill, atmos.raisedFill.alpha))
            val sunken = luminance(mixG(atmos.fieldTop, atmos.sunkenFill, atmos.sunkenFill.alpha))
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

    /**
     * §13.6: the cover morph is the longest motion in the app, and it has to be.
     *
     * Two properties, both load-bearing. It must outlast `motionEmphasis`, or the
     * plate is still flying when the screen it is flying to has finished arriving
     * and the morph reads as a snap and a settle. And it must outlast the
     * navigation cross-fade (`motionStandard` fade in, `motionFast + 60` fade out),
     * or the arriving screen is not yet composed when the plate lands and the
     * morph is cut off mid-flight.
     */
    @Test
    fun morphOutlastsBothTheEmphasisStepAndTheNavCrossFade() {
        assertTrue(
            FolioTokens.motionMorph > FolioTokens.motionEmphasis,
            "morph ${FolioTokens.motionMorph}ms must exceed emphasis " +
                "${FolioTokens.motionEmphasis}ms",
        )
        val navFadeOut = FolioTokens.motionFast + 60
        assertTrue(
            FolioTokens.motionMorph > FolioTokens.motionStandard &&
                FolioTokens.motionMorph > navFadeOut,
            "morph ${FolioTokens.motionMorph}ms must outlast the nav cross-fade " +
                "(${FolioTokens.motionStandard}/$navFadeOut) so the destination is composed " +
                "before the plate lands",
        )
        // A morph is a state change with travel in it, not a second of theatre.
        assertTrue(
            FolioTokens.motionMorph <= 600L,
            "morph ${FolioTokens.motionMorph}ms is past the point the eye reads travel as lag",
        )
    }

    /**
     * §13.6 swapped the API's default `BoundsTransform` (a spring) for a tween,
     * because Rule 6 bans springs in navigation. This pins the reasoning itself:
     * a spring's settle time depends on distance travelled, so two morphs of
     * different lengths would land at different moments and the paired title text —
     * which runs on the same spec — would drift against its own cover.
     */
    @Test
    fun morphTimingIsDistanceIndependent() {
        // folioMorphBounds is one tween for every (initial, target) pair; the
        // function it wraps ignores both bounds. Asserted structurally by the
        // helper's own contract: see folioMorphBounds.
        assertTrue(
            FolioTokens.motionMorph > 0L,
            "a zero-duration root would make the morph a hard cut",
        )
    }

    /**
     * The bar's highlight is driven by the scroll, not by a timer.
     *
     * At rest it must be exactly the stop the masthead has always used — the bar is a
     * shipped material and this is a movement, not a repaint — and it must travel
     * monotonically with the finger without ever running past the bright end of its own
     * ramp, which would put the transparent stop after the sheen and invert the band.
     */
    @Test
    fun mastheadSheenTracksTheFingerAndRestsWhereItAlwaysDid() {
        assertEquals(
            0.62f,
            com.folio.reader.ui.components.mastheadSheenStop(0f),
            "an expanded bar changed the masthead's resting light",
        )
        var previous = 1f
        for (f in listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)) {
            val stop = com.folio.reader.ui.components.mastheadSheenStop(f)
            assertTrue(stop < previous, "the sheen did not travel at collapse $f")
            assertTrue(stop in 0.05f..0.62f, "the sheen stop left its legal range at $f: $stop")
            previous = stop
        }
        assertEquals(
            com.folio.reader.ui.components.mastheadSheenStop(1f),
            com.folio.reader.ui.components.mastheadSheenStop(4f),
            "collapse past 1.0 is not clamped, so an over-scroll would fling the light",
        )
    }

    /**
     * Saturated synthetic lights — a cover's colour may be anything, and a bar's glass
     * has to keep its weight whatever it is. The raw primaries and the two extremes
     * are deliberate: white and black are where `matchLuma`'s channel clamp binds
     * hardest, and the chromatic ones are where a hue actually moves.
     */
    private val litTints = listOf(
        Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF),
        Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFFFF00FF),
        Color(0xFFFFFFFF), Color(0xFF000000),
        Color(0xFFEC4899), Color(0xFF26C6DA), Color(0xFFF59E0B), Color(0xFF10B981),
    )

    /**
     * Nothing lit, nothing spent. `LocalFolioAmbientTint` is null on desktop, in every
     * preview and in every test, and chrome falls back to the palette's own colour —
     * which has to be *exactly* the palette's own, or a bar over an unread book is a
     * different bar than the one the atmosphere designed.
     *
     * The nav capsule compares its result against `FolioAtmosphere.barGlass` to decide
     * whether to draw its room wash at all, so this identity is what keeps that wash
     * off an unlit screen. It used to be protected twice over: `folioBarGlass` passed
     * the glass as the *fallback tint*, so "no book" arrived as "lit by the glass
     * itself" and had to be a no-op by arithmetic as well. That aliasing is gone — the
     * holder is read for its nullable `requested` — which is precisely what let
     * `barGlassFor` start following the lit field instead of only its hue.
     */
    @Test
    fun barGlassForLeavesTheGlassAloneWhenNothingIsLit() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            assertEquals(
                atmos.barGlass,
                barGlassFor(atmos, null, atmos.fieldTintStrength),
                "${palette.id}: a null tint returned something other than the palette's own " +
                    "bar glass",
            )
            for (tint in litTints) {
                for (strength in listOf(0f, -1f)) {
                    assertEquals(
                        atmos.barGlass,
                        barGlassFor(atmos, tint, strength),
                        "${palette.id}: strength $strength is no light at all, but the glass " +
                            "moved toward $tint",
                    )
                }
            }
        }
    }

    /**
     * A room may change a bar's colour and never its weight.
     *
     * `barGlass`'s alpha *is* the §15 glass window — below 0.22 the bar stops reading
     * as a surface, above 0.42 it reads as the grey lid the redesign removed — and it
     * is a palette decision, made once per theme. The ambient is a runtime value,
     * chosen by what the reader is holding, so it may not spend that budget: a bar over
     * a red book must be exactly as transparent as a bar over no book at all, or
     * `appBarsAreGlassNotLids` only holds in the empty room.
     */
    @Test
    fun barGlassForNeverChangesTheGlassAlpha() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val designed = atmos.barGlass.alpha
            for (tint in litTints + Color(0x80FF0080)) {
                for (strength in listOf(atmos.fieldTintStrength, 0.05f, 1f)) {
                    val alpha = barGlassFor(atmos, tint, strength).alpha
                    assertTrue(
                        alpha == designed,
                        "${palette.id}: bar glass alpha ${"%.4f".format(designed)} became " +
                            "${"%.4f".format(alpha)} under a $tint light at strength $strength",
                    )
                }
            }
            // The window itself, re-measured through the lit path.
            for (tint in litTints) {
                val alpha = barGlassFor(atmos, tint, atmos.fieldTintStrength).alpha
                assertTrue(
                    alpha in 0.22f..0.42f,
                    "${palette.id}: lit bar glass alpha ${"%.4f".format(alpha)} left the " +
                        "0.22–0.42 glass window",
                )
            }
        }
    }

    /**
     * The bar is a piece of the room, and it stays readable.
     *
     * This guard used to assert the opposite — that a lit room could move a bar's hue
     * and nothing else, because `tintFill` pinned its lightness. That pin is exactly
     * why the top of the screen read as a separate subject from the field under it: the
     * page was free to brighten with a cover and the bar was not. `barGlassFor` now
     * aims at the field's own lit endpoint, so lightness moving is the design.
     *
     * What replaces the pin is the thing a pin was only ever standing in for. The bar's
     * ink is measured on the veil composited over the lit page it actually sits on, so
     * legibility is governed by measurement rather than by holding one channel still.
     */
    @Test
    fun barGlassFollowsTheLitRoomAndStaysReadable() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val ink = palette.colors.onSurface
            for (tint in litTints) {
                val field = fieldColors(atmos, tint, atmos.fieldTintStrength)
                val glass = barGlassFor(atmos, tint, atmos.fieldTintStrength)
                val ground = mixG(field.top, glass, glass.alpha)
                val ratio = contrastRatio(ground, ink)
                assertTrue(
                    ratio >= 4.5,
                    "${palette.id} on $tint: the bar's own ink reads at " +
                        "${"%.2f".format(ratio)}:1 on the lit veil — a bar that follows the " +
                        "room has to keep its label with it",
                )
            }
            // Out of the loop on purpose: a white light on paper's already-white glass
            // moves nothing and must not be called a failure. A saturated one can only
            // pass if the function is doing something at all.
            assertTrue(
                barGlassFor(atmos, Color(0xFF10B981), atmos.fieldTintStrength) != atmos.barGlass,
                "${palette.id}: a saturated light moved the bar's glass not at all",
            )
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
