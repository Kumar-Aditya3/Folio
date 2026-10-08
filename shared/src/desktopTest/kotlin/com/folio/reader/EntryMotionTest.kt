package com.folio.reader

import com.folio.reader.ui.components.chartStagger
import com.folio.reader.ui.components.folioFadeSwap
import com.folio.reader.ui.components.folioSizeTransformEligible
import com.folio.reader.ui.components.folioSwapSizeTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §13.5 chart entry stagger math: bars sweep left-to-right, and the Rule 15
 * peak cap never appears before its own bar has finished growing (+80ms).
 */
class EntryMotionTest {

    @Test
    fun endpointsAreExact() {
        for (index in 0 until 7) {
            val (growth, cap) = chartStagger(0f, index, 7)
            assertEquals(0f, growth)
            assertEquals(0f, cap)
            val (doneGrowth, doneCap) = chartStagger(1f, index, 7)
            assertEquals(1f, doneGrowth)
            assertEquals(1f, doneCap)
        }
    }

    @Test
    fun barsSweepLeftToRight() {
        // Mid-sweep, earlier bars must be further along than later ones.
        // (0.5f is deliberately avoided: bars 0 and 1 both finish exactly there.)
        val (growth0, _) = chartStagger(0.45f, 0, 7)
        val (growth1, _) = chartStagger(0.45f, 1, 7)
        assertTrue(growth0 > growth1, "bar 0 ($growth0) must lead bar 1 ($growth1)")
        val (growth3, _) = chartStagger(0.45f, 3, 7)
        val (growth6, _) = chartStagger(0.45f, 6, 7)
        assertTrue(growth3 > growth6, "bar 3 ($growth3) must lead bar 6 ($growth6)")
    }

    @Test
    fun peakCapLagsItsOwnBar() {
        // Mirror the defaults: stagger·(n-1) + bar + delay + fade.
        val barMs = 320
        val staggerMs = 40
        val capMs = 80
        val total = staggerMs * 6 + barMs + capMs * 2
        for (index in 0 until 7) {
            // The instant this bar finishes growing, its cap must not have begun.
            val grownAt = (staggerMs * index + barMs).toFloat() / total
            val (growth, cap) = chartStagger(grownAt, index, 7)
            assertTrue(growth >= 0.99f, "index $index: bar not grown at its completion point ($growth)")
            assertEquals(0f, cap, "index $index: cap started before its bar finished ($cap)")
            // Halfway through the delay window the cap is still fully out.
            val (_, midDelayCap) = chartStagger(grownAt + (capMs / 2f) / total, index, 7)
            assertEquals(0f, midDelayCap, "index $index: cap revealed during its delay window")
            // Halfway through the fade the cap is partway in, bar long done.
            val (midFadeGrowth, midFadeCap) = chartStagger(grownAt + (capMs * 1.5f) / total, index, 7)
            assertEquals(1f, midFadeGrowth, "index $index: bar regressed during cap fade")
            assertTrue(
                midFadeCap in 0.25f..0.75f,
                "index $index: mid-fade cap ($midFadeCap) not mid-fade",
            )
        }
        // And the cap never leads its bar anywhere on the timeline.
        var entry = 0f
        while (entry <= 1f) {
            for (index in 0 until 7) {
                val (growth, cap) = chartStagger(entry, index, 7)
                assertTrue(cap <= growth + 1e-6f, "entry $entry index $index: cap ($cap) leads bar ($growth)")
            }
            entry += 0.1f
        }
    }

    /**
     * The shelf/view-mode swap's `SizeTransform` is opt-in and threshold-gated, so
     * the gate has to be exact at both ends. It exists because a `SizeTransform`
     * measures the incoming lazy grid against an interpolated width, which past a
     * handful of rows changes its column count and re-flows every row — the
     * "layout changes for a split second" report the plain dissolve was chosen to
     * fix.
     */
    @Test
    fun sizeTransformGateIsInclusiveAtTheBoundaryAndRefusesEmptyAndLarge() {
        assertTrue(folioSizeTransformEligible(1), "a one-item shelf is the safest case there is")
        assertTrue(folioSizeTransformEligible(8), "the threshold itself must be eligible")
        assertTrue(!folioSizeTransformEligible(9), "past the threshold the grid can re-column")
        assertTrue(!folioSizeTransformEligible(0), "an empty shelf has no rows to interpolate")
        assertTrue(!folioSizeTransformEligible(-1), "a negative count is not a shelf")
        // Monotonic off the boundary: nothing larger than the threshold sneaks in.
        for (count in 100..400 step 100) {
            assertTrue(!folioSizeTransformEligible(count), "count $count must not be eligible")
        }
    }

    /** The swap keeps its dissolve when nothing supplies a size spec. */
    @Test
    fun fadeSwapDefaultsToNoSizeTransform() {
        assertNull(
            folioFadeSwap(motionEnabled = true).sizeTransform,
            "the default swap must stay a pure dissolve — the size spec is opt-in",
        )
    }

    /** When a caller does supply one, it survives the transform. */
    @Test
    fun fadeSwapCarriesAnOfferedSizeTransform() {
        assertNotNull(
            folioFadeSwap(
                motionEnabled = true,
                sizeTransform = folioSwapSizeTransform(),
            ).sizeTransform,
            "an offered SizeTransform must reach the ContentTransform",
        )
    }

    /**
     * Rule 19: with motion off the swap is instant, and that includes its size.
     * A size spec left live under reduce-motion would animate the layout on a
     * device whose owner has explicitly asked the system for no animation.
     */
    @Test
    fun fadeSwapDropsSizeTransformWhenMotionIsOff() {
        assertNull(
            folioFadeSwap(
                motionEnabled = false,
                sizeTransform = folioSwapSizeTransform(),
            ).sizeTransform,
            "reduce-motion must degrade the swap to static, size included",
        )
    }
}
