package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.SHIMMER_EMISSION
import com.folio.reader.ui.components.SHIMMER_OCCLUSION
import com.folio.reader.ui.components.shimmerSweep
import com.folio.reader.ui.home.homeSkeletonCoverRatio
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphereFor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards for the unarrived-pane shimmer (`FolioShimmer.kt`).
 *
 * Two things can go wrong with a shimmer and neither is caught by looking at it
 * once:
 *
 *  1. **the sweep geometry.** A band that is still mid-pane when the loop restarts
 *     jumps backwards every cycle — the flicker every hand-rolled shimmer has. So
 *     the band must be fully clear of both edges at the endpoints, must advance
 *     monotonically, and must light every column somewhere in between.
 *  2. **visibility per palette.** Rule 19 (§13.2): an effect that silently does
 *     nothing on a supported device is a bug. The band is the atmosphere's own
 *     `rimLight` over its `sunkenFill`, so a palette where those two nearly agree
 *     would ship a skeleton that never shimmers. Every palette is checked.
 */
class ShimmerTest {

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Source-over in the surface's own encoding, which is what the GPU does when
     * the band's gradient is drawn over the well. Deliberately *not*
     * `Color.lerp`: that interpolates in Oklab, and interpolating 11% away from
     * pure black in Oklab lands below the sRGB gamma toe and clamps straight back
     * to black — an artefact of the wrong model, not of the effect.
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
    fun bandClearsBothEdgesAtTheEndpoints() {
        val width = 320f
        val band = width * 0.45f
        // Trailing edge still off the leading side at the start of a cycle…
        val start = shimmerSweep(0f, width, band)
        assertTrue(
            start + band <= 0f,
            "band is already on the pane at progress 0 (trailing edge ${start + band})",
        )
        // …and the leading edge past the trailing side at the end, so the restart
        // has nothing on screen to jump.
        val end = shimmerSweep(1f, width, band)
        assertTrue(end >= width, "band has not left the pane at progress 1 (leading edge $end)")
    }

    @Test
    fun sweepAdvancesMonotonically() {
        val width = 240f
        val band = width * 0.45f
        var previous = Float.NEGATIVE_INFINITY
        var step = 0
        while (step <= 100) {
            val x = shimmerSweep(step / 100f, width, band)
            assertTrue(x > previous, "sweep went backwards at ${step / 100f}: $x after $previous")
            previous = x
            step++
        }
    }

    @Test
    fun everyColumnIsLitSomewhereInTheCycle() {
        val width = 200f
        val band = width * 0.45f
        var column = 0
        while (column <= width.toInt()) {
            val x = column.toFloat()
            val lit = (0..200).any { step ->
                val leading = shimmerSweep(step / 200f, width, band)
                x >= leading && x <= leading + band
            }
            assertTrue(lit, "column $x is never inside the band")
            column += 5
        }
    }

    @Test
    fun progressOutsideZeroToOneClampsRatherThanFlinging() {
        val width = 100f
        val band = 45f
        // An interruptible spec can hand back a value slightly out of range; the
        // band must sit at an endpoint, never off in space.
        assertEquals(shimmerSweep(0f, width, band), shimmerSweep(-0.4f, width, band))
        assertEquals(shimmerSweep(1f, width, band), shimmerSweep(1.6f, width, band))
    }

    @Test
    fun sweepPeriodReadsAsActivity() {
        // §13.4 keeps the hero mesh at 18–30s precisely so it never reads as a
        // loading state. This is the other end of that scale and must stay there:
        // slower than ~2s stops reading as activity, faster than ~0.6s strobes.
        assertTrue(
            FolioTokens.motionShimmer in 600L..2000L,
            "shimmer period ${FolioTokens.motionShimmer}ms is outside the 600–2000ms band",
        )
        // And it must be clearly slower than a state flip, or a skeleton next to a
        // toggling chip reads as the same event.
        assertTrue(
            FolioTokens.motionShimmer > FolioTokens.motionEmphasis * 2,
            "shimmer period ${FolioTokens.motionShimmer}ms is too close to motionEmphasis",
        )
    }

    @Test
    fun everyPaletteShimmersVisibly() {
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            // The band follows the palette's lighting model: dark fields brighten,
            // paper darkens. `.copy(alpha = …)` *sets* the band's alpha, so the peak
            // constant is the whole story — composite it over the well so the check
            // is on what actually reaches the screen.
            val peak = if (atmos.isDark) {
                atmos.rimLight.copy(alpha = SHIMMER_EMISSION)
            } else {
                atmos.rimShade.copy(alpha = SHIMMER_OCCLUSION)
            }
            val lit = composite(peak, atmos.sunkenFill)
            val ratio = contrast(lit, atmos.sunkenFill)
            assertTrue(
                ratio >= 1.06,
                "${palette.id}: shimmer band is invisible over its own well " +
                    "(contrast $ratio, well ${atmos.sunkenFill}, band $peak, lit $lit)",
            )
            // Subtle, though: the band is a change in light crossing a surface, not
            // a shape sliding over it.
            assertTrue(
                ratio <= 2.2,
                "${palette.id}: shimmer band reads as a shape, not a highlight (contrast $ratio)",
            )
        }
    }

    /**
     * A skeleton's whole promise is that the page will not move when the data
     * lands, so the geometry it borrows has to be the geometry it replaces. Home
     * stands in for **book** covers, which `FolioCoverPlate` draws at the printed
     * trim — not the manga tile's ratio, which is the pane skeleton's default.
     */
    @Test
    fun homeSkeletonBorrowsTheGeometryItStandsInFor() {
        assertEquals(
            1f / FolioTokens.coverAspect,
            homeSkeletonCoverRatio,
            "Home skeleton ratio is no longer the printed trim FolioCoverPlate draws at",
        )
        assertTrue(
            homeSkeletonCoverRatio != FolioTokens.coverPaneRatio,
            "book skeleton ratio ${homeSkeletonCoverRatio} is indistinguishable from the " +
                "manga tile's ${FolioTokens.coverPaneRatio} — the ratio parameter on " +
                "FolioCoverPaneSkeleton would be doing nothing",
        )
        // The anchor sizes its type column to `coverAnchor * coverAspect`; the
        // skeleton sizes its plate by ratio. Both routes must land on one height,
        // within a float's rounding of a 148dp plate.
        val real = (FolioTokens.coverAnchor * FolioTokens.coverAspect).value
        val implied = (FolioTokens.coverAnchor / homeSkeletonCoverRatio).value
        assertTrue(
            abs(real - implied) < 0.01f,
            "anchor plate height disagrees with itself: $real via coverAspect, $implied via ratio",
        )
    }
}
