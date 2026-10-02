package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.FOLIO_CLEARING_PEAK
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FOLIO_SIGNATURE_ALPHA_MAX
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.barGlassFor
import com.folio.reader.ui.theme.desaturate
import com.folio.reader.ui.theme.FolioFieldColors
import com.folio.reader.ui.theme.fieldColorAt
import com.folio.reader.ui.theme.fieldColorAtPoint
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.mixG
import com.folio.reader.ui.theme.poolAlphaAt
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant the content-lit room is built on.
 *
 * The overhaul lets the environment take real perceptual amplitude for the first
 * time — a dark field carries up to 0.34 of a cover's hue, and its pools gain alpha
 * with it. The doctrine that makes that legal is one sentence: the room may shift
 * hue and the luminance-of-air freely, but it may not take a contrast ratio from
 * ink. Every assertion below is that sentence, measured independently of the code
 * under test, over all 36 app palettes and a set of deliberately hostile covers.
 *
 * The hostile covers are the twelve 30° hue bins at high saturation and value rather
 * than real sampled covers on purpose: a real shelf would under-sample the one hue
 * that hurts a given palette most, and the point of a guard is that it still holds
 * for a book nobody has imported yet.
 */
class FolioFieldModelTest {

    // WCAG relative luminance, re-implemented here deliberately. The cheap
    // sRGB-weighted `luminanceOf` in the atmosphere module is fine for *picking* a
    // lighting model but reports ~5.9:1 where the true ratio is above 15:1, so a
    // floor asserted with it would pass on colours that fail on a screen.
    private fun relLum(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = relLum(a)
        val lb = relLum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Lightness on the same scale the atmosphere module picks polarity with. */
    private fun luma(c: Color): Float = c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f

    private fun chroma(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    private fun maxChannelDelta(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private val hostileCovers: List<Color> = (0 until 12).map { i ->
        Color.hsv(i * 30f, 0.85f, 0.95f)
    }

    /** A 5x5 grid of field positions, as fractions of a phone's page. */
    private val sampleFractions: List<Pair<Float, Float>> = buildList {
        for (iy in 0..4) for (ix in 0..4) add((0.1f + ix * 0.2f) to (0.1f + iy * 0.2f))
    }

    private val phoneW = 1080f
    private val phoneH = 2340f

    @Test
    fun anUnlitRoomIsThePalettesOwnByteForByte() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            assertEquals(fieldColors(atmos), fieldColors(atmos, null, atmos.fieldTintStrength),
                "${palette.id}: a null tint must not move the field at all")
            assertEquals(fieldColors(atmos), fieldColors(atmos, hostileCovers[0], 0f),
                "${palette.id}: zero strength must not move the field at all")
            assertEquals(atmos.barGlass, barGlassFor(atmos, null, atmos.fieldTintStrength),
                "${palette.id}: a null tint must not move the bar's glass")
            assertEquals(atmos.barGlass, barGlassFor(atmos, hostileCovers[3], 0f),
                "${palette.id}: zero strength must not move the bar's glass")
        }
    }

    /**
     * The load-bearing one, stated as what it actually guarantees.
     *
     * Tinting may move hue as far as it likes; it may not put body ink under the
     * §12.3 reading floor. Measured at every sample point on the field, for every
     * palette, against every hostile cover.
     *
     * This replaced two tighter claims. The first, "a lit field loses no headroom",
     * was falsified on paper: LIGHT's field sits near the white ceiling, and a bright
     * cover genuinely lifts it — 13.9:1 untinted, 9.9:1 lit. That is the feature
     * working, so the delta bound came out too: the floor is the promise, and it is
     * also the runaway guard. The untinted field is still checked against the same
     * floor separately, so a theme that ships thin is reported as its own finding
     * rather than blamed on this feature.
     */
    @Test
    fun bodyInkOnALitFieldClearsTheReadingFloor() {
        val floor = 7.0
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val ink = colors.onBackground
            val plainMin = worstRatio(fieldColors(atmos), ink)
            assertTrue(
                plainMin >= floor,
                "${palette.id}: body ink on the field is already ${"%.2f".format(plainMin)}:1, " +
                    "under the $floor floor before any tint is applied",
            )
            for (cover in hostileCovers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                val litMin = worstRatio(lit, ink)
                assertTrue(
                    litMin >= floor,
                    "${palette.id} on cover ${cover.hex()}: the lit field puts body ink at " +
                        "${"%.2f".format(litMin)}:1, under the $floor floor (${"%.2f".format(plainMin)}:1 " +
                            "untinted) — the room may take any colour, not any ratio",
                )
            }
        }
    }

    private fun worstRatio(field: FolioFieldColors, ink: Color): Double =
        sampleFractions.minOf { (fx, fy) ->
            ratio(
                fieldColorAtPoint(field, phoneW, phoneH, fx * phoneW, fy * phoneH, 0f),
                ink,
            )
        }

    /**
     * Lightness is no longer pinned — that pin is what made the feature invisible,
     * since a pale cover flattened to a dark room's lightness keeps almost no colour.
     * What replaces it is a bound: each tinted endpoint must clear the ink's floor on
     * its own, so the room may brighten only as far as the theme's own headroom buys.
     */
    @Test
    fun theLitEndpointsStayInsideTheInkBand() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            for (cover in hostileCovers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                val topRatio = ratio(lit.top, atmos.ink)
                val bottomRatio = ratio(lit.bottom, atmos.ink)
                assertTrue(
                    topRatio >= 8.0,
                    "${palette.id} on cover ${cover.hex()}: the lit field's top puts body ink at " +
                        "${"%.2f".format(topRatio)}:1 — the clamp targets 8.5 to leave the pools " +
                            "somewhere to spend",
                )
                assertTrue(
                    bottomRatio >= 8.0,
                    "${palette.id} on cover ${cover.hex()}: the lit field's bottom puts body ink " +
                        "at ${"%.2f".format(bottomRatio)}:1",
                )
                // Where the amplitude has to land. A near-black endpoint cannot carry a
                // hue at all — on SYNTHWAVE the round trip through 8-bit channels returns
                // the identical stored colour, which is a property of black rather than a
                // broken tint. So the claim is about the room's *light*, and about the
                // field moving somewhere in it, not about one specific endpoint.
                assertTrue(
                    lit.poolColors.indices.any {
                        maxChannelDelta(atmos.pools[it], lit.poolColors[it]) > 0.001f
                    },
                    "${palette.id} on cover ${cover.hex()}: not one pool took the cover's hue, so " +
                        "the living light is still the palette's own",
                )
                assertTrue(
                    maxChannelDelta(atmos.fieldTop, lit.top) > 0.001f ||
                        maxChannelDelta(atmos.fieldBottom, lit.bottom) > 0.001f,
                    "${palette.id} on cover ${cover.hex()}: neither field endpoint moved, so the " +
                        "room itself is unchanged",
                )
            }
        }
    }

    /**
     * `desaturate` mixes toward a grey of the colour's *own* lightness, and because
     * the sRGB weights sum to one that is luminance-preserving by construction. The
     * neutralised base field leans on that to clear a place for the cover's hue
     * without touching a single ratio, so the arithmetic itself is what is pinned.
     */
    @Test
    fun neutralisingColoursCostsLuminanceAndGainsRoom() {
        val suspects = hostileCovers + listOf(
            Color.White, Color.Black, Color(0.5f, 0.1f, 0.1f), Color(0.2f, 0.9f, 0.4f),
        )
        for (base in suspects) {
            for (amount in listOf(0.1f, 0.35f, 0.7f, 1f)) {
                val flat = desaturate(base, amount)
                assertEquals(luma(base), luma(flat), EPS_QUANTUM,
                    "${base.hex()} at $amount: neutralising moved its lightness")
                assertTrue(chroma(flat) <= chroma(base) + 2 * EPS_QUANTUM,
                    "${base.hex()} at $amount: neutralising gave it chroma instead of taking it")
            }
        }
        assertEquals(0f, chroma(desaturate(Color(0.4f, 0.1f, 0.9f), 1f)), EPS_QUANTUM,
            "a fully neutralised colour must be a grey")
    }

    @Test
    fun poolsKeepTheirCeilingsWhenLit() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            assertEquals(3, atmos.pools.size, "${palette.id}: the field lost its three pools")
            for (cover in hostileCovers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                assertTrue(
                    lit.poolAlpha <= POOL_ALPHA_CEILING + 1e-4f,
                    "${palette.id} on cover ${cover.hex()}: pools reached alpha ${lit.poolAlpha}, " +
                        "past the $POOL_ALPHA_CEILING ceiling — past it the pools are a painted " +
                            "background, not air",
                )
                assertTrue(
                    lit.poolAlpha >= atmos.poolAlpha - 1e-4f,
                    "${palette.id}: a lit room dimmed its pools below the untinted design",
                )
                assertEquals(atmos.pools.size, lit.poolColors.size,
                    "${palette.id}: tinting changed the number of pools")
                assertEquals(1f, lit.poolColors[0].alpha,
                    "${palette.id}: a pool's colour carries an alpha the ceiling does not know " +
                        "about, so the two would multiply")
            }
        }
    }

    /** Daylight may dim a pool and never lift it past the field's own design. */
    @Test
    fun daylightDimsPoolsAndNeverLiftsThem() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val lit = fieldColors(atmos, hostileCovers[5], atmos.fieldTintStrength)
            for (sideX in listOf(-1f, 0f, 1f)) {
                for (shiftStep in 0..10) {
                    val shift = -1f + shiftStep * 0.2f
                    val alpha = poolAlphaAt(lit.poolAlpha, sideX, shift)
                    assertTrue(
                        alpha in 0f..lit.poolAlpha + 1e-4f,
                        "${palette.id}: pool alpha $alpha escaped 0..${lit.poolAlpha} " +
                            "at side $sideX shift $shift",
                    )
                }
            }
        }
    }

    /**
     * The seam the refraction gel is sampled from. The gel reproduces this ramp and
     * these pools in AGSL, so the two Kotlin sides of that agreement — the per-channel
     * ramp and the point compositor — must not disagree either.
     */
    @Test
    fun thePointSamplerAgreesWithTheRampWhereNothingElsePaints() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val noPools = fieldColors(atmos).copy(poolAlpha = 0f)
            for (step in 0..20) {
                val fy = step / 20f
                val fromRamp = fieldColorAt(noPools, fy)
                val fromPoint = fieldColorAtPoint(
                    noPools, phoneW, phoneH, 0.5f * phoneW, fy * phoneH, 0f,
                )
                assertTrue(
                    maxChannelDelta(fromRamp, fromPoint) < 1e-5f,
                    "${palette.id} at height $fy: the compositor and the ramp disagree by " +
                        "${maxChannelDelta(fromRamp, fromPoint)}",
                )
            }
        }
    }

    /** A bar in a lit room is the same weight of glass; only its colour moves. */
    @Test
    fun lightingABarChangesItsColourAndNotItsWeight() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            for (cover in hostileCovers) {
                val glass = barGlassFor(atmos, cover, atmos.fieldTintStrength)
                assertEquals(atmos.barGlass.alpha, glass.alpha,
                    "${palette.id} on cover ${cover.hex()}: the bar's alpha moved")
                // The bar's *weight* is the invariant — its lightness no longer is.
                // Holding luma still was the `tintFill` pin, and that pin is exactly
                // what made the masthead read as a separate subject from a field that
                // was free to brighten with a cover. It is bounded here rather than
                // pinned so chrome can follow the room without becoming a different
                // object, and legibility is measured for real in DesignSystemTest's
                // barGlassFollowsTheLitRoomAndStaysReadable.
                val drift = abs(luma(atmos.barGlass) - luma(glass))
                assertTrue(
                    drift <= EPS_BAR_LUMA_DRIFT,
                    "${palette.id} on cover ${cover.hex()}: the bar's glass moved lightness by " +
                        "${"%.4f".format(drift)}, past the $EPS_BAR_LUMA_DRIFT that still " +
                        "reads as the same surface",
                )
                assertTrue(
                    glass.alpha in 0.22f..0.42f,
                    "${palette.id}: lit bar glass at ${glass.alpha} left the 0.22–0.42 window",
                )
            }
        }
    }

    /**
     * The amplitude token is paired per polarity, it is not zero, and it is not the
     * book.
     *
     * The pairing runs the other way from the one this guard used to assert. Dark was
     * required to carry *more* of a cover than paper — the theory being that a deep
     * field can absorb hue before it reads as painted — and on device that is exactly
     * what turned dark rooms olive: "makes dark themes bright and saturated... make
     * the darker background more dominant." Paper is now allowed the larger share,
     * because a bright cover genuinely does less to a cream page than to a black one.
     *
     * What has *not* moved is the floor. Whether the amplitude is visible is not a
     * fact about this constant — it is a fact about the rendered field, and
     * [aLitRoomIsNotTheSameRoomTwice] measures that directly, on every palette, with
     * two covers. This guard only stops the token drifting back to a whisper.
     */
    @Test
    fun theAmplitudeIsPairedAndReal() {
        val dark = atmosphereFor(AppPalette.DARK.colors)
        val light = atmosphereFor(AppPalette.LIGHT.colors)
        assertTrue(dark.fieldTintStrength <= light.fieldTintStrength,
            "a dark room carries more of a cover's hue than paper does — the pairing that " +
                "made deep darks read as saturated jackets rather than rooms")
        assertTrue(
            dark.fieldTintStrength >= 0.10f && light.fieldTintStrength >= 0.10f,
            "dark ${dark.fieldTintStrength} / light ${light.fieldTintStrength} is drifting " +
                "back toward the whisper that made every screen look the same",
        )
        assertTrue(
            dark.fieldTintStrength <= 0.5f && light.fieldTintStrength <= 0.5f,
            "past half the field stops being a room lit by a book and becomes the book",
        )
        // Whether the amplitude is *visible* is not a fact about this constant — it
        // is a fact about the rendered field, and `aLitRoomIsNotTheSameRoomTwice`
        // measures that directly.
    }

    @Test
    fun aLitRoomIsNotTheSameRoomTwice() {
        // The complaint this whole phase answers is that content never changed the
        // room. Two different covers must therefore produce measurably different
        // fields on every palette — the payoff, asserted, not assumed.
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val a = fieldColors(atmos, hostileCovers[0], atmos.fieldTintStrength)
            val b = fieldColors(atmos, hostileCovers[6], atmos.fieldTintStrength)
            assertTrue(
                maxChannelDelta(a.top, b.top) > 0.01f,
                "${palette.id}: a cyan book and a red book light the same room — the field " +
                    "is still not hearing what is being read",
            )
        }
    }

    /**
     * The per-theme guilloché signature is drawn into the field at
     * [FOLIO_SIGNATURE_ALPHA_MAX]. It may make the room distinct; it may not take a
     * ratio from ink. Measured conservatively — the motif's opaque line composited at
     * the full ceiling over the worst sampled point, on the untinted field and under
     * every hostile lit cover — because a real stroke only covers a fraction of any
     * glyph's ground, so full-coverage here over-states the darkening/lightening.
     *
     * This guard, not taste, is what sets [FOLIO_SIGNATURE_ALPHA_MAX].
     */
    @Test
    fun theSignatureMotifKeepsBodyInkLegible() {
        // AA (4.5:1), not AAA: the skyscape is decoration in the open field, and cardless
        // body text that needs the AAA 7:1 floor gets a folioClearing (guarded separately
        // below) that restores it. This bounds the *bare* field so an un-cleared label is
        // still comfortably readable over the motif at its ceiling.
        val floor = 4.5
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val ink = palette.colors.onBackground
            val motif = atmos.signature.ink
            val grounds = listOf(fieldColors(atmos)) +
                hostileCovers.map { fieldColors(atmos, it, atmos.fieldTintStrength) }
            for (field in grounds) {
                val worst = sampleFractions.minOf { (fx, fy) ->
                    val base = fieldColorAtPoint(field, phoneW, phoneH, fx * phoneW, fy * phoneH, 0f)
                    ratio(mixG(base, motif, FOLIO_SIGNATURE_ALPHA_MAX), ink)
                }
                assertTrue(
                    worst >= floor,
                    "${palette.id}: the signature motif at $FOLIO_SIGNATURE_ALPHA_MAX puts body " +
                        "ink at ${"%.2f".format(worst)}:1, under the $floor floor — lower the " +
                        "ceiling, the guard sets it",
                )
            }
        }
    }

    /**
     * `folioClearing` paints the field's own paper over the motif behind cardless
     * content. Its promise is the *worst case*, not every pixel: the lowest-contrast
     * point of a cleared block must be at least as legible as the lowest-contrast
     * point of the same block with the bare motif under it, and must clear the
     * reading floor with margin — on every palette and under the loudest lit rooms.
     *
     * It is deliberately not a pointwise "only ever lifts": the clearing tends the
     * whole block toward the clean [FolioAtmosphere.fieldTop], which on a dark field
     * is a faint lift that can *lower* the raw ratio at a point that was already very
     * dark (and so very legible) while raising the point that was weakest. The floor
     * lives at the weakest point, which is the one that matters.
     */
    @Test
    fun folioClearingRestoresLegibilityOverTheMotif() {
        val floor = 7.0
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val ink = palette.colors.onBackground
            val motif = atmos.signature.ink
            val paper = atmos.fieldTop
            val grounds = listOf(fieldColors(atmos)) +
                hostileCovers.map { fieldColors(atmos, it, atmos.fieldTintStrength) }
            for (field in grounds) {
                var worstMotif = Double.MAX_VALUE
                var worstCleared = Double.MAX_VALUE
                for ((fx, fy) in sampleFractions) {
                    val base = fieldColorAtPoint(field, phoneW, phoneH, fx * phoneW, fy * phoneH, 0f)
                    val withMotif = mixG(base, motif, FOLIO_SIGNATURE_ALPHA_MAX)
                    val cleared = mixG(withMotif, paper, FOLIO_CLEARING_PEAK)
                    worstMotif = minOf(worstMotif, ratio(withMotif, ink))
                    worstCleared = minOf(worstCleared, ratio(cleared, ink))
                }
                assertTrue(
                    worstCleared >= worstMotif - 1e-6,
                    "${palette.id}: clearing lowered the worst-case legibility " +
                        "(${"%.2f".format(worstCleared)}:1 < ${"%.2f".format(worstMotif)}:1)",
                )
                assertTrue(
                    worstCleared >= floor,
                    "${palette.id}: cleared cardless ink reads at ${"%.2f".format(worstCleared)}:1 " +
                        "at its worst point, under the $floor floor",
                )
            }
        }
    }

    /**
     * The signature's ceiling and sky are pinned here so neither drifts silently: the
     * alpha stays a tracery, the sky is deterministic per palette (a theme must look the
     * same every launch, Android and Desktop), its polarity follows the palette (a dark
     * face gets a night sky with no mist, a light face a day sky with one), and the 36
     * built-ins do not all collapse onto one sky.
     */
    @Test
    fun theSignatureCeilingAndFigureArePinned() {
        assertTrue(
            FOLIO_SIGNATURE_ALPHA_MAX in 0.01f..0.14f,
            "the signature alpha ceiling $FOLIO_SIGNATURE_ALPHA_MAX left the tracery register",
        )
        val seeds = mutableSetOf<Int>()
        for (palette in AppPalette.entries) {
            val a = atmosphereFor(palette.colors).signature
            val b = atmosphereFor(palette.colors).signature
            assertEquals(a.seed, b.seed, "${palette.id}: the signature seed is not deterministic")
            assertEquals(a.star, b.star, "${palette.id}: the signature sky is not deterministic")
            assertEquals(a.isDark, palette.isDark, "${palette.id}: the sky polarity disagrees with the palette")
            assertEquals(
                palette.isDark, a.mist == null,
                "${palette.id}: a dark face must have no mist and a light face must have one",
            )
            assertEquals(FOLIO_SIGNATURE_ALPHA_MAX, a.alphaCeiling, "${palette.id}: ceiling drifted")
            seeds.add(a.seed)
        }
        assertTrue(
            seeds.size > AppPalette.entries.size / 2,
            "the signatures barely vary across themes (${seeds.size} distinct of " +
                "${AppPalette.entries.size}) — the sky is supposed to tell them apart",
        )
    }

    private fun Color.hex(): String = "#" + (
        (red * 255).toInt() * 0x10000 + (green * 255).toInt() * 0x100 + (blue * 255).toInt()
        ).toString(16).padStart(6, '0')

    private companion object {
        /**
         * Tolerance on the atmosphere module's sRGB-weighted lightness.
         *
         * One quantum, not a fudge: an sRGB `Color` stores 8 bits per channel, so every
         * channel-wise mix round-trips through a 1/255 lattice and lightness can only
         * ever be restored to within about that. A guard tighter than the storage
         * format fails on arithmetic that is correct.
         */
        const val EPS_QUANTUM = 0.004f

        /**
         * How far a bar's glass may move in lightness with the room before it stops
         * reading as the same surface.
         *
         * Not a pin. `barGlassFor` used to hold lightness still through `tintFill`, and
         * this slot existed only to excuse the rounding that pin took at the white
         * ceiling (measured 0.048 on LIGHT). That pin caused the masthead seam, so the
         * bar now follows the lit field and what is guarded is runaway, not movement —
         * legibility itself is measured in DesignSystemTest.
         */
        const val EPS_BAR_LUMA_DRIFT = 0.16f

        /**
         * Mirrors `POOL_ALPHA_CEILING` in the field model, restated rather than
         * imported because a guard that reads its value from the code it is checking
         * can never notice that value being moved.
         */
        const val POOL_ALPHA_CEILING = 0.24f
    }
}
