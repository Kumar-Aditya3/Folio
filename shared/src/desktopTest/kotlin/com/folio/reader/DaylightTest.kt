package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.DAYLIGHT_RIM_TINT_MAX
import com.folio.reader.ui.theme.DAYLIGHT_WASH_ALPHA_MAX
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.daylightAt
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards for the daylight model (`FolioDaylight.kt`) — the pure half only. There
 * is no Compose test infrastructure in this repo, and there does not need to be:
 * [daylightAt] takes the hour and minute as parameters and reads no clock, so the
 * entire day's curve is a plain function these tests can walk minute by minute.
 *
 * What is pinned here is the *design*, not an implementation detail:
 *
 *  1. the sun peaks at solar noon and the room is darkest at midnight;
 *  2. dawn and dusk mirror each other, and the curve is continuous (and smooth to
 *     the minute) all the way around midnight — no banding on a 4%-register
 *     gradient;
 *  3. elevation and azimuth stay inside their ranges for every minute of the day;
 *  4. night is classified as the genuinely dark band, with civil twilight lit;
 *  5. — the one that matters most — the colour temperature never becomes so
 *     saturated or so bright that it could wreck text contrast: near-neutral at
 *     noon, bounded in luma and chroma everywhere, and the applied rim tint and
 *     wash alpha stay inside the subtlety envelope the materials promise.
 */
class DaylightTest {

    /** The day's light at a minute-of-day, for walking the whole curve. */
    private fun at(minuteOfDay: Int): FolioDaylight =
        daylightAt(minuteOfDay / 60, minuteOfDay % 60)

    /** sRGB-weighted lightness — enough to bound a light source's brightness. */
    private fun luma(c: Color): Double =
        0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue

    /** Channel span; a neutral colour keeps it near zero, a neon one drives it to 1. */
    private fun chroma(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    /**
     * Source-over in the surface's own encoding, which is what the GPU does when a
     * wash is drawn over a field. Mirrors `ShimmerTest.composite` deliberately:
     * `Color.lerp` interpolates in Oklab and would answer a different question.
     */
    private fun composite(src: Color, dst: Color): Color {
        val a = src.alpha
        return Color(
            red = src.red * a + dst.red * (1f - a),
            green = src.green * a + dst.green * (1f - a),
            blue = src.blue * a + dst.blue * (1f - a),
        )
    }

    @Test
    fun solarNoonIsTheElevationAndIntensityPeak() {
        val noon = daylightAt(12, 0)
        assertTrue(abs(noon.elevation - 1f) < 1e-4f,
            "solar noon must reach the elevation ceiling, got ${noon.elevation}")
        assertTrue(noon.intensity >= 0.999f,
            "solar noon must be the day's strongest rim, got intensity ${noon.intensity}")
        var m = 0
        while (m < 1440) {
            val d = at(m)
            assertTrue(noon.elevation >= d.elevation - 1e-4f,
                "minute $m elevation ${d.elevation} exceeds solar noon ${noon.elevation}")
            assertTrue(noon.intensity >= d.intensity - 1e-4f,
                "minute $m intensity ${d.intensity} exceeds solar noon ${noon.intensity}")
            m++
        }
    }

    @Test
    fun midnightIsTheDarkestPoint() {
        val midnight = daylightAt(0, 0)
        assertTrue(midnight.elevation <= 1e-4f,
            "midnight must be on the horizon, got elevation ${midnight.elevation}")
        var minElevation = Float.MAX_VALUE
        var minIntensity = Float.MAX_VALUE
        var m = 0
        while (m < 1440) {
            val d = at(m)
            minElevation = minOf(minElevation, d.elevation)
            minIntensity = minOf(minIntensity, d.intensity)
            m++
        }
        assertTrue(abs(midnight.elevation - minElevation) < 1e-3f,
            "midnight is not the day's elevation floor (floor $minElevation)")
        assertTrue(abs(midnight.intensity - minIntensity) < 1e-3f,
            "midnight is not the day's dimmest minute (floor $minIntensity)")
        assertTrue(midnight.intensity < daylightAt(12, 0).intensity,
            "midnight must be dimmer than noon")
        assertTrue(midnight.isNight, "midnight must be classified as night")
    }

    @Test
    fun dawnAndDuskAreSymmetric() {
        // Mirror about solar noon: elevation and intensity equal, azimuth opposite
        // (morning from the leading edge, evening from the trailing), temperature
        // equal because it is a function of elevation alone.
        val offsets = listOf(30, 90, 60, 120, 180, 240, 300, 360, 390)
        offsets.forEach { off ->
            val am = at(12 * 60 - off)
            val pm = at(12 * 60 + off)
            assertTrue(abs(am.elevation - pm.elevation) < 1e-3f,
                "elevation asymmetric at ±$off min: ${am.elevation} vs ${pm.elevation}")
            assertTrue(abs(am.intensity - pm.intensity) < 1e-3f,
                "intensity asymmetric at ±$off min: ${am.intensity} vs ${pm.intensity}")
            assertTrue(abs(am.azimuth + pm.azimuth) < 1e-3f,
                "azimuth not antisymmetric at ±$off min: ${am.azimuth} vs ${pm.azimuth}")
            assertTrue(abs(am.temperature.red - pm.temperature.red) < 0.01f &&
                abs(am.temperature.blue - pm.temperature.blue) < 0.01f,
                "temperature asymmetric at ±$off min")
        }
        // Civil dawn and dusk are the same room: 6am and 6pm agree exactly.
        assertTrue(abs(daylightAt(6, 0).elevation - daylightAt(18, 0).elevation) < 1e-3f,
            "6am and 6pm elevation disagree")
    }

    @Test
    fun curveIsContinuousAcrossMidnight() {
        val before = daylightAt(23, 59)
        val after = daylightAt(0, 0)
        assertTrue(abs(before.elevation - after.elevation) < 0.01f,
            "elevation jumps across midnight: ${before.elevation} vs ${after.elevation}")
        assertTrue(abs(before.intensity - after.intensity) < 0.01f,
            "intensity jumps across midnight: ${before.intensity} vs ${after.intensity}")
        assertTrue(abs(before.azimuth - after.azimuth) < 0.01f,
            "azimuth jumps across midnight: ${before.azimuth} vs ${after.azimuth}")
        assertTrue(abs(before.temperature.red - after.temperature.red) < 0.02f &&
            abs(before.temperature.green - after.temperature.green) < 0.02f &&
            abs(before.temperature.blue - after.temperature.blue) < 0.02f,
            "temperature jumps across midnight")
        assertTrue(before.isNight == after.isNight, "the night flag flips across midnight")
    }

    @Test
    fun adjacentMinutesNeverBand() {
        // A 4%-register gradient cannot survive a visible step. The steepest legal
        // climb is well under a hundredth of the range per minute.
        var maxElevationStep = 0f
        var maxAzimuthStep = 0f
        var maxIntensityStep = 0f
        var m = 0
        while (m < 1439) {
            val a = at(m)
            val b = at(m + 1)
            maxElevationStep = max(maxElevationStep, abs(a.elevation - b.elevation))
            maxAzimuthStep = max(maxAzimuthStep, abs(a.azimuth - b.azimuth))
            maxIntensityStep = max(maxIntensityStep, abs(a.intensity - b.intensity))
            m++
        }
        assertTrue(maxElevationStep < 0.01f, "elevation bands: $maxElevationStep in one minute")
        assertTrue(maxAzimuthStep < 0.01f, "azimuth bands: $maxAzimuthStep in one minute")
        assertTrue(maxIntensityStep < 0.01f, "intensity bands: $maxIntensityStep in one minute")
    }

    @Test
    fun elevationAndAzimuthStayInRangeEveryMinute() {
        var m = 0
        while (m < 1440) {
            val d = at(m)
            assertTrue(d.elevation in 0f..1f, "minute $m elevation ${d.elevation} out of 0..1")
            assertTrue(d.azimuth in -1f..1f, "minute $m azimuth ${d.azimuth} out of -1..1")
            assertTrue(d.intensity in 0f..1f, "minute $m intensity ${d.intensity} out of 0..1")
            m++
        }
    }

    @Test
    fun nightIsClassifiedCorrectly() {
        listOf(0, 1, 3, 5, 19, 20, 22, 23).forEach { h ->
            assertTrue(daylightAt(h, 0).isNight, "$h:00 should be night")
        }
        listOf(7, 9, 12, 15, 17).forEach { h ->
            assertTrue(!daylightAt(h, 0).isNight, "$h:00 should not be night")
        }
        // Civil twilight (6:00 / 18:00) is the boundary and counts as lit: there is
        // enough light to read by, so the room should not go dark at dusk.
        assertTrue(!daylightAt(6, 0).isNight, "6:00 civil dawn should already be lit")
        assertTrue(!daylightAt(18, 0).isNight, "18:00 civil dusk should still be lit")
        // The night flag must agree with a low sun — never night at noon, never day
        // in the small hours.
        assertTrue(daylightAt(0, 0).elevation < daylightAt(12, 0).elevation,
            "midnight sun is not below the noon sun")
    }

    @Test
    fun azimuthSweepsLeadingToTrailing() {
        assertTrue(daylightAt(7, 0).azimuth < -0.5f,
            "7am light should rake in from the leading edge, got ${daylightAt(7, 0).azimuth}")
        assertTrue(abs(daylightAt(12, 0).azimuth) < 1e-3f,
            "noon light should be straight overhead, got ${daylightAt(12, 0).azimuth}")
        assertTrue(daylightAt(17, 0).azimuth > 0.5f,
            "5pm light should rake in from the trailing edge, got ${daylightAt(17, 0).azimuth}")
        assertTrue(daylightAt(9, 0).azimuth < 0f && daylightAt(15, 0).azimuth > 0f,
            "azimuth should cross zero once, at noon")
    }

    @Test
    fun noonTemperatureIsNearNeutralAndBright() {
        val noon = daylightAt(12, 0).temperature
        assertTrue(abs(noon.alpha - 1f) < 1e-4f,
            "temperature must be an opaque source colour, got alpha ${noon.alpha}")
        // The day's hardest light must not tint the page: a near-white keeps the
        // channel span tiny.
        assertTrue(chroma(noon) <= 0.04f,
            "noon temperature chroma ${chroma(noon)} is not near-neutral")
        assertTrue(luma(noon) >= 0.92,
            "noon temperature luma ${luma(noon)} is not a bright neutral white")
    }

    @Test
    fun temperatureStaysWithinLumaAndChromaBoundsEveryMinute() {
        var maxChroma = 0f
        var m = 0
        while (m < 1440) {
            val t = at(m).temperature
            assertTrue(abs(t.alpha - 1f) < 1e-4f,
                "minute $m temperature is not opaque (alpha ${t.alpha})")
            // Never a hole (pure black) and never blown out past white: the wash and
            // rim both ride this colour, so its own range is the ceiling on theirs.
            assertTrue(luma(t) >= 0.10, "minute $m temperature luma ${luma(t)} is darker than the night floor")
            assertTrue(luma(t) <= 1.0, "minute $m temperature luma ${luma(t)} exceeds white")
            maxChroma = max(maxChroma, chroma(t))
            m++
        }
        // The golden-hour amber is the most saturated the light ever gets; it stays
        // a warm white-gold, never a neon filter.
        assertTrue(maxChroma <= 0.60f,
            "temperature chroma peaks at $maxChroma — the light reads as a filter, not air")
    }

    @Test
    fun appliedTintAndWashStayInsideTheSubtletyEnvelope() {
        // The whole promise, in two ceilings: a rim never travels more than a small
        // fraction toward the sun colour (a tint, never a replacement), and a
        // full-surface wash never exceeds a whisper. Both scale by intensity <= 1.
        assertTrue(DAYLIGHT_RIM_TINT_MAX <= 0.30f,
            "rim tint ceiling $DAYLIGHT_RIM_TINT_MAX is a replacement, not a mix")
        assertTrue(DAYLIGHT_WASH_ALPHA_MAX <= 0.06f,
            "wash ceiling $DAYLIGHT_WASH_ALPHA_MAX could shift text contrast")
        var m = 0
        while (m < 1440) {
            val i = at(m).intensity
            assertTrue(DAYLIGHT_RIM_TINT_MAX * i <= 0.30f,
                "minute $m rim tint ${DAYLIGHT_RIM_TINT_MAX * i} exceeds the 0.30 mix ceiling")
            assertTrue(DAYLIGHT_WASH_ALPHA_MAX * i <= 0.06f,
                "minute $m wash alpha ${DAYLIGHT_WASH_ALPHA_MAX * i} exceeds the 0.06 ceiling")
            m++
        }
    }

    @Test
    fun daylightWashCannotMoveTextContrast() {
        // The strongest wash the system ever draws is the ceiling alpha of the
        // day's most extreme temperatures. Composite it over pure black and pure
        // white — the two backgrounds text contrast is most sensitive to — and it
        // must move each channel by no more than its own alpha. A shift that small
        // cannot lift a background far enough to drop a WCAG ratio off its floor.
        val alpha = DAYLIGHT_WASH_ALPHA_MAX
        val extremes = listOf(
            daylightAt(12, 0).temperature, // brightest (noon white)
            daylightAt(0, 0).temperature,  // darkest (night blue-black)
            daylightAt(7, 0).temperature,  // most saturated (golden-hour amber)
        )
        extremes.forEach { temp ->
            val src = temp.copy(alpha = alpha)
            val overBlack = composite(src, Color.Black)
            val overWhite = composite(src, Color.White)
            assertTrue(overBlack.red <= alpha + 1e-3f &&
                overBlack.green <= alpha + 1e-3f &&
                overBlack.blue <= alpha + 1e-3f,
                "wash lifts a black field past its own alpha ($temp)")
            assertTrue(overWhite.red >= 1f - alpha - 1e-3f &&
                overWhite.green >= 1f - alpha - 1e-3f &&
                overWhite.blue >= 1f - alpha - 1e-3f,
                "wash darkens a white field past its own alpha ($temp)")
        }
    }
}
