package com.folio.reader

import com.folio.reader.ui.components.navSweepAlpha
import com.folio.reader.ui.components.navSweepBand
import com.folio.reader.ui.components.segmentedSlotBounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17 liquid selection — the two geometry halves that make selection motion
 * land where the layout put things, pinned by test rather than by eye.
 */
class LiquidSelectionTest {

    // ── the traveling segment ────────────────────────────────────────────────

    @Test
    fun segmentLandsExactlyOnItsSlot() {
        val widths = listOf(88f, 132f, 104f)
        val gap = 2f
        for (index in widths.indices) {
            val (left, width) = segmentedSlotBounds(widths, index, gap)
            assertEquals(widths[index], width, "index $index: indicator width is not the slot's")
            val expectedLeft = widths.take(index).sum() + gap * index
            assertEquals(expectedLeft, left, "index $index: indicator left is not the slot's")
        }
    }

    @Test
    fun segmentBoundsAreMonotonicAndGapless() {
        // The glide must never have to cross a gap the labels do not have:
        // consecutive slots abut exactly at the gap, and the ordering never
        // inverts. (The first slot has no predecessor to abut.)
        val widths = listOf(70f, 90f, 60f, 110f)
        val gap = 2f
        var previousRight: Float? = null
        widths.indices.forEach { index ->
            val (left, width) = segmentedSlotBounds(widths, index, gap)
            previousRight?.let { assertEquals(it + gap, left, "index $index does not abut its predecessor") }
            previousRight = left + width
        }
    }

    @Test
    fun segmentIndexIsClamped() {
        val widths = listOf(100f, 120f)
        val (leftBelow, _) = segmentedSlotBounds(widths, -3, 2f)
        assertEquals(0f, leftBelow, "an index below range must resolve to the first slot")
        val (leftAbove, _) = segmentedSlotBounds(widths, 99, 2f)
        assertEquals(102f, leftAbove, "an index above range must resolve to the last slot")
    }

    // ── the capsule sweep ────────────────────────────────────────────────────

    @Test
    fun sweepIsOffEdgeAtBothEnds() {
        // The band enters and exits fully off the capsule, so it never pops in
        // or out mid-glass — the same contract shimmerSweep holds for panes.
        val width = 420f
        val band = 140f
        assertEquals(-band, navSweepBand(0f, width, band, +1f), "start must be off the leading edge")
        assertEquals(width, navSweepBand(1f, width, band, +1f), "end must be past the trailing edge")
        assertEquals(
            width,
            navSweepBand(0f, width, band, -1f),
            "reversed start must be off the trailing edge",
        )
        assertEquals(-band, navSweepBand(1f, width, band, -1f), "reversed end must be past the leading edge")
    }

    @Test
    fun sweepTravelsItsDirectionMonotonically() {
        val width = 420f
        val band = 100f
        var last = Float.NEGATIVE_INFINITY
        var progress = 0f
        while (progress <= 1f) {
            val x = navSweepBand(progress, width, band, +1f)
            assertTrue(x >= last, "forward sweep reversed at progress $progress")
            last = x
            progress += 0.1f
        }
        last = Float.POSITIVE_INFINITY
        progress = 0f
        while (progress <= 1f) {
            val x = navSweepBand(progress, width, band, -1f)
            assertTrue(x <= last, "reverse sweep reversed at progress $progress")
            last = x
            progress += 0.1f
        }
    }

    @Test
    fun sweepAlphaRestsAtZeroAtBothEnds() {
        assertEquals(0f, navSweepAlpha(0f), "the band must ease in, not switch on")
        // sin(π) is 1.2e-16 in floating point, so the rest at the far end is a
        // tolerance, not an exact zero.
        assertTrue(abs(navSweepAlpha(1f)) < 1e-6f, "the band must ease out, not switch off")
        var progress = 0f
        while (progress <= 1f) {
            val a = navSweepAlpha(progress)
            assertTrue(a in 0f..1f, "alpha $a out of range at progress $progress")
            progress += 0.1f
        }
        // And it peaks somewhere mid-travel — a band that never rises is a
        // no-op wearing an animation's clock.
        assertTrue(navSweepAlpha(0.5f) > 0.99f, "the envelope never peaks")
        assertTrue(abs(navSweepAlpha(0.25f) - navSweepAlpha(0.75f)) < 1e-4f, "envelope is not symmetric")
    }
}
