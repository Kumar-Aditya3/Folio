package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.settings.FOLIO_VIBRANCY_DEFAULT
import com.folio.reader.settings.FOLIO_VIBRANCY_NEUTRAL
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioFieldColors
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.fieldColorAtPoint
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.oklabChroma
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on the "Muted ↔ Vivid" lever.
 *
 * The lever is allowed to make the room as colourful or as quiet as the user wants, on one
 * scalar, tuned per light/dark. The single doctrine it must obey is the same one the content-lit
 * room already runs on: it may move hue and chroma freely, but it may **not** take a contrast
 * ratio from ink. These tests state that sentence three ways —
 *
 *  1. at [FOLIO_VIBRANCY_NEUTRAL] the whole pipeline is byte-for-byte the pre-lever look, so the
 *     rest of the design-system/field/lamp suite (which all derives at the default) is untouched;
 *  2. at the shipped [FOLIO_VIBRANCY_DEFAULT] the pools genuinely gain chroma while their
 *     luminance is held — colour added, legibility not spent;
 *  3. at the shipped default the *lit* field still clears §12.3's 7:1 for body ink over every
 *     palette and a set of deliberately hostile covers, which is the guard on the relaxed
 *     lit-cover clamp (change C).
 */
class FolioVibrancyTest {

    // WCAG relative luminance, re-implemented here on purpose (see FolioFieldModelTest): the
    // atmosphere module's cheap sRGB-weighted luma is fine for picking a model but wrong for
    // asserting a floor.
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

    /** The same sRGB-weighted luma the atmosphere pins when it enriches chroma. */
    private fun luma(c: Color): Float = c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f

    /** Perceptual chroma — the quantity the lever actually raises (Oklab, like the derivation). */
    private fun chroma(c: Color): Float = oklabChroma(c)

    private val hostileCovers: List<Color> = (0 until 12).map { i -> Color.hsv(i * 30f, 0.85f, 0.95f) }

    private val sampleFractions: List<Pair<Float, Float>> = buildList {
        for (iy in 0..4) for (ix in 0..4) add((0.1f + ix * 0.2f) to (0.1f + iy * 0.2f))
    }
    private val phoneW = 1080f
    private val phoneH = 2340f

    private fun worstRatio(field: FolioFieldColors, ink: Color): Double =
        sampleFractions.minOf { (fx, fy) ->
            ratio(fieldColorAtPoint(field, phoneW, phoneH, fx * phoneW, fy * phoneH, 0f), ink)
        }

    @Test
    fun neutralIsTheDefaultDerivationByteForByte() {
        for (palette in AppPalette.entries) {
            val default = atmosphereFor(palette.colors)
            val neutral = atmosphereFor(palette.colors, vibrancy = FOLIO_VIBRANCY_NEUTRAL)
            assertEquals(default.pools, neutral.pools, "${palette.id}: neutral pools must equal the default derivation")
            assertEquals(default.fieldBottom, neutral.fieldBottom, "${palette.id}: neutral fieldBottom must be unchanged")
            assertEquals(default.poolAlpha, neutral.poolAlpha, "${palette.id}: neutral poolAlpha must be unchanged")
            assertEquals(default.paneAlpha, neutral.paneAlpha, "${palette.id}: neutral paneAlpha must be unchanged")
            // And neutral is genuinely the untouched look: the enrichment is a no-op there.
            assertEquals(FOLIO_VIBRANCY_NEUTRAL, neutral.vibrancy, "${palette.id}: neutral vibrancy carried")
        }
    }

    @Test
    fun vividAddsChromaButHoldsLuminance() {
        var anyChromaGain = false
        for (palette in AppPalette.entries) {
            val neutral = atmosphereFor(palette.colors, vibrancy = FOLIO_VIBRANCY_NEUTRAL)
            val vivid = atmosphereFor(palette.colors, vibrancy = FOLIO_VIBRANCY_DEFAULT)
            assertEquals(neutral.pools.size, vivid.pools.size, "${palette.id}: pool count must not change")
            neutral.pools.zip(vivid.pools).forEachIndexed { i, (n, v) ->
                // Luminance is pinned (change A/E are chroma-only), so no field composite can
                // darken or lighten out from under ink. A couple of points of slack for the
                // gamut bisection in tierColour.
                assertTrue(
                    abs(luma(n) - luma(v)) < 0.03f,
                    "${palette.id} pool $i: vivid moved luminance ${luma(n)} -> ${luma(v)}; it must only move chroma",
                )
                // Chroma may only rise (or hold, when the pool is already at the gamut edge).
                assertTrue(
                    chroma(v) >= chroma(n) - 1e-3f,
                    "${palette.id} pool $i: vivid lowered chroma ${chroma(n)} -> ${chroma(v)}",
                )
                if (chroma(v) > chroma(n) + 1e-2f) anyChromaGain = true
            }
        }
        assertTrue(anyChromaGain, "the vivid default must raise chroma on at least some palettes, or the lever does nothing")
    }

    @Test
    fun shippedVividLitFieldStillClearsTheReadingFloor() {
        val floor = 7.0
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors, vibrancy = FOLIO_VIBRANCY_DEFAULT)
            val ink = colors.onBackground
            val plainMin = worstRatio(fieldColors(atmos), ink)
            assertTrue(
                plainMin >= floor,
                "${palette.id}: body ink on the vivid field is ${"%.2f".format(plainMin)}:1, under the $floor floor before any tint",
            )
            for (cover in hostileCovers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                val litMin = worstRatio(lit, ink)
                assertTrue(
                    litMin >= floor,
                    "${palette.id} on cover ${cover.hex()}: the vivid lit field puts body ink at " +
                        "${"%.2f".format(litMin)}:1, under the $floor floor — the lever may take colour, not a ratio",
                )
            }
        }
    }

    @Test
    fun fullyMutedStaysWellFormed() {
        for (palette in AppPalette.entries) {
            val muted = atmosphereFor(palette.colors, vibrancy = 0f)
            muted.pools.forEachIndexed { i, c ->
                assertTrue(
                    c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f,
                    "${palette.id} pool $i: muted produced an out-of-gamut colour $c",
                )
            }
            assertTrue(muted.poolAlpha >= 0f, "${palette.id}: muted poolAlpha must stay non-negative")
            assertTrue(muted.paneAlpha in 0f..1f, "${palette.id}: muted paneAlpha must stay in range")
        }
    }

    private fun Color.hex(): String {
        fun h(v: Float) = ((v * 255).toInt().coerceIn(0, 255)).toString(16).padStart(2, '0')
        return "#${h(red)}${h(green)}${h(blue)}"
    }
}
