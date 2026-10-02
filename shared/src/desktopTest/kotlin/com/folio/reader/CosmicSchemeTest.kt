package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.FOLIO_CLEARING_PEAK
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.CosmicIntensity
import com.folio.reader.ui.theme.CosmicMotion
import com.folio.reader.ui.theme.FOLIO_SIGNATURE_ALPHA_MAX
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.cosmicSchemeFor
import com.folio.reader.ui.theme.fieldColorAtPoint
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.mixG
import com.folio.reader.ui.theme.sky
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cosmic art-direction foundation, pinned.
 *
 * Two things must hold for the intensity system to be safe to roll out across the app:
 *
 *  1. **No intensity level ever renders brighter under body text than the field the
 *     existing guards prove legible.** `FolioFieldModelTest` composites the skyscape
 *     motif at [FOLIO_SIGNATURE_ALPHA_MAX] and asserts ink clears its floors. The field
 *     renderers scale every content-band term by [CosmicIntensity.baseAtmosphereAlpha],
 *     which is capped at 1 — so the content-band ceiling at any level is
 *     `FOLIO_SIGNATURE_ALPHA_MAX * baseAtmosphereAlpha ≤ FOLIO_SIGNATURE_ALPHA_MAX`. This
 *     test asserts legibility at each level's own ceiling, independently, over all 36
 *     palettes and a set of hostile covers — the same method the base guard uses.
 *  2. **The semantic token layer invents no colour and is deterministic**, so a theme's
 *     cosmic vocabulary reproduces launch to launch and derives entirely from its roles.
 */
class CosmicSchemeTest {

    // WCAG relative luminance, re-implemented here (see FolioFieldModelTest for why the
    // cheap sRGB-weighted luminance would pass colours that fail on screen).
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

    private val hostileCovers: List<Color> = (0 until 12).map { i -> Color.hsv(i * 30f, 0.85f, 0.95f) }

    private val sampleFractions: List<Pair<Float, Float>> = buildList {
        for (iy in 0..4) for (ix in 0..4) add((0.1f + ix * 0.2f) to (0.1f + iy * 0.2f))
    }

    private val phoneW = 1080f
    private val phoneH = 2340f

    /**
     * The load-bearing one, parameterized by intensity. For every level, the field at
     * that level's content-band ceiling keeps a cardless label at AA bare and AAA once a
     * `folioClearing` is applied — on every palette, under every hostile cover.
     */
    @Test
    fun everyIntensityKeepsBodyInkLegibleAtItsContentBandCeiling() {
        val bareFloor = 4.5
        val clearedFloor = 7.0
        for (level in CosmicIntensity.entries) {
            // The renderer multiplies the content-band motif alpha by this (capped at 1).
            val ceiling = FOLIO_SIGNATURE_ALPHA_MAX * level.baseAtmosphereAlpha.coerceIn(0f, 1f)
            for (palette in AppPalette.entries) {
                val atmos = atmosphereFor(palette.colors)
                val ink = palette.colors.onBackground
                val motif = atmos.signature.ink
                val paper = atmos.fieldTop
                val grounds = listOf(fieldColors(atmos)) +
                    hostileCovers.map { fieldColors(atmos, it, atmos.fieldTintStrength) }
                for (field in grounds) {
                    var worstBare = Double.MAX_VALUE
                    var worstCleared = Double.MAX_VALUE
                    for ((fx, fy) in sampleFractions) {
                        val base = fieldColorAtPoint(field, phoneW, phoneH, fx * phoneW, fy * phoneH, 0f)
                        val withMotif = mixG(base, motif, ceiling)
                        val cleared = mixG(withMotif, paper, FOLIO_CLEARING_PEAK)
                        worstBare = minOf(worstBare, ratio(withMotif, ink))
                        worstCleared = minOf(worstCleared, ratio(cleared, ink))
                    }
                    assertTrue(
                        worstBare >= bareFloor,
                        "${palette.id} @ $level: bare cosmic field puts ink at " +
                            "${"%.2f".format(worstBare)}:1 (ceiling $ceiling), under $bareFloor",
                    )
                    assertTrue(
                        worstCleared >= clearedFloor,
                        "${palette.id} @ $level: cleared cardless ink at " +
                            "${"%.2f".format(worstCleared)}:1 (ceiling $ceiling), under $clearedFloor",
                    )
                }
            }
        }
    }

    /**
     * The cap that makes the above safe: no level's content-band multiplier may exceed 1,
     * or it would render brighter under text than the base guard measures. Quiet must be
     * genuinely fainter; Atmospheric and Expressive share the content-band ceiling (their
     * difference lives at the margins), and Expressive's edges must be bolder.
     */
    @Test
    fun theIntensityLadderIsCappedAndOrdered() {
        for (level in CosmicIntensity.entries) {
            assertTrue(
                level.baseAtmosphereAlpha in 0f..1f,
                "$level base atmosphere alpha ${level.baseAtmosphereAlpha} escaped 0..1 — " +
                    "a level above 1 would outshine the contrast-guarded field",
            )
            assertTrue(level.starDensity in 0f..1f, "$level star density out of range")
        }
        val quiet = CosmicIntensity.Quiet
        val atmo = CosmicIntensity.Atmospheric
        val expr = CosmicIntensity.Expressive
        assertTrue(
            quiet.baseAtmosphereAlpha < atmo.baseAtmosphereAlpha,
            "Quiet must be fainter than Atmospheric",
        )
        assertEquals(
            atmo.baseAtmosphereAlpha, expr.baseAtmosphereAlpha,
            "Atmospheric and Expressive must share the content-band ceiling",
        )
        assertTrue(
            expr.edgeStrength > atmo.edgeStrength && atmo.edgeStrength > quiet.edgeStrength,
            "edge strength must order Quiet < Atmospheric < Expressive",
        )
        assertTrue(!quiet.drawsPlanet && atmo.drawsPlanet && expr.drawsPlanet,
            "the planet draws on Atmospheric/Expressive, not Quiet")
    }

    /** The solved renderer scalars never exceed the content-band cap. */
    @Test
    fun solvedSkyScalarsAreClamped() {
        for (level in CosmicIntensity.entries) {
            val sky = level.sky()
            assertTrue(sky.contentScale in 0f..1f, "$level contentScale ${sky.contentScale} > 1")
            assertTrue(sky.starDensity in 0f..1f, "$level starDensity escaped 0..1")
            assertTrue(sky.edgeStrength >= 0f, "$level edgeStrength negative")
            assertEquals(level.drawsPlanet, sky.drawsPlanet, "$level planet flag lost in solve")
        }
    }

    /**
     * The semantic layer maps roles, invents nothing, and reproduces deterministically —
     * a theme's cosmic vocabulary must be byte-identical launch to launch, Android and
     * Desktop, and derive only from the palette's own colours.
     */
    @Test
    fun theSchemeMapsRolesAndIsDeterministic() {
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val a = cosmicSchemeFor(colors)
            val b = cosmicSchemeFor(colors)
            assertEquals(a, b, "${palette.id}: the cosmic scheme is not deterministic")
            assertEquals(palette.isDark, a.isDark, "${palette.id}: cosmic polarity disagrees")
            // Backgrounds / accents / text come straight from the roles — no new hex.
            assertEquals(colors.background, a.background.primary, "${palette.id}: background.primary")
            assertEquals(atmos.fieldBottom, a.background.secondary, "${palette.id}: background.secondary")
            assertEquals(colors.primary, a.accent.primary, "${palette.id}: accent.primary")
            assertEquals(colors.accentDiscovery, a.accent.secondary, "${palette.id}: accent.secondary")
            assertEquals(colors.accentStreak, a.accent.tertiary, "${palette.id}: accent.tertiary")
            assertEquals(colors.onBackground, a.text.primary, "${palette.id}: text.primary")
            assertEquals(colors.onSurfaceVariant, a.text.secondary, "${palette.id}: text.secondary")
            // Surfaces / artwork mirror the atmosphere + signature.
            assertEquals(atmos.raisedFill, a.surface.primary, "${palette.id}: surface.primary")
            assertEquals(atmos.veilFill, a.surface.elevated, "${palette.id}: surface.elevated")
            assertEquals(atmos.signature.star, a.artwork.star, "${palette.id}: artwork.star")
            assertEquals(FOLIO_SIGNATURE_ALPHA_MAX, a.artwork.alphaCeiling, "${palette.id}: ceiling drifted")
            assertEquals(
                palette.isDark, a.artwork.mist == null,
                "${palette.id}: a dark face must have no mist and a light face must have one",
            )
        }
    }

    /**
     * The motion tokens are "noticeable but calm": twinkle depth is raised enough to be
     * seen but not a strobe, the twinkle period is a few seconds (not the old 30s), every
     * duration is positive, and the shooting-star interval dwarfs the twinkle so it stays
     * a rare event rather than a recurring tic.
     */
    @Test
    fun motionTokensAreNoticeableButCalm() {
        assertTrue(
            CosmicMotion.twinkleAmplitude in 0.30f..0.50f,
            "twinkle amplitude ${CosmicMotion.twinkleAmplitude} left the noticeable-but-calm band",
        )
        assertTrue(
            CosmicMotion.starTwinkleMs in 2_000L..8_000L,
            "twinkle period ${CosmicMotion.starTwinkleMs}ms is not the few-second register the brief asks for",
        )
        assertTrue(CosmicMotion.glowBreathMs > 0L && CosmicMotion.orbitDurationMs > 0L)
        assertTrue(
            CosmicMotion.orbitDurationMs > CosmicMotion.glowBreathMs,
            "an orbital ring must turn far slower than a glow breathes",
        )
        assertTrue(
            CosmicMotion.shootingStarIntervalMs >= CosmicMotion.starTwinkleMs * 5,
            "the shooting star must be rare relative to the twinkle, or it stops being an event",
        )
        assertTrue(
            CosmicMotion.twinkleBase + CosmicMotion.twinkleAmplitude <= 1.2f,
            "twinkle crest overshoots too far past full brightness",
        )
    }
}
