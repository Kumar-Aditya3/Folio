package com.folio.reader.ui.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The webtoon pinch-zoom scroll compensation (Task: "webtoon zoom drifts the
 * scroll position"). Webtoon zoom is a layout change — every item height
 * scales linearly with the zoom ratio — so the content at `anchorPx` from the
 * scroll origin lands displaced by exactly `(ratio - 1) * anchorPx`.
 * Dispatching that same amount as raw scroll in the same pointer event keeps
 * the pinch centroid pinned on screen. Precedent: DocumentReaderCoreTest pins
 * the reader's pure helpers.
 */
class WebtoonZoomDeltaTest {

    @Test
    fun `the delta is proportional to the anchor distance`() {
        // Zooming 2x around the scroll origin itself moves nothing.
        assertEquals(0f, zoomScrollDelta(2f, 0f), 0.0001f)
        // Zooming 2x around an anchor 100px down the strip pulls 100px of
        // content past the anchor; the list must scroll 100px to compensate.
        assertEquals(100f, zoomScrollDelta(2f, 100f), 0.0001f)
        // Zooming out to half size at the same anchor gives back the same
        // correction in the opposite direction.
        assertEquals(-50f, zoomScrollDelta(0.5f, 100f), 0.0001f)
    }

    @Test
    fun `no zoom change means no scroll`() {
        assertEquals(0f, zoomScrollDelta(1f, 5000f), 0.0001f)
    }

    @Test
    fun `zoom-in scrolls down the strip and zoom-out scrolls back up`() {
        val anchor = 1000f
        assertTrue(zoomScrollDelta(1.5f, anchor) > 0f, "zooming in must advance the list")
        assertTrue(zoomScrollDelta(0.8f, anchor) < 0f, "zooming out must rewind the list")
    }

    @Test
    fun `the double-tap reset composes to the exact inverse`() {
        // Zoom in from 1f to 2f around a point `a` px from the scroll origin,
        // then reset (ratio 1/2) around the same *screen* point. By then that
        // point sits 2a px from the origin, so the reset's anchor is 2a and its
        // delta is (0.5 − 1)·2a = −a — the exact inverse of the zoom-in's +a.
        // This is why WebtoonReader.resetZoomAt passes the old zoom's anchor,
        // not the original one.
        val a = 400f
        val zoomIn = zoomScrollDelta(2f, a)
        val reset = zoomScrollDelta(0.5f, a * 2f)
        assertEquals(0f, zoomIn + reset, 0.0001f)
        // At the scroll origin the reset is also a no-op, for any zoom.
        assertEquals(0f, zoomScrollDelta(0.5f, 0f), 0.0001f)
    }

    @Test
    fun `a chain of small steps matches one big step at the anchor`() {
        // Each step anchors at the *current* (already-zoomed) distance from the
        // scroll origin to the centroid, so the i-th step's anchor is a·r^(i-1):
        // the chain sums a geometric series, not n copies of the first term.
        val a = 1000f
        val steps = 10
        val totalRatio = 2f
        val stepRatio = Math.pow(totalRatio.toDouble(), 1.0 / steps).toFloat()
        var chained = 0f
        var anchor = a
        repeat(steps) {
            chained += zoomScrollDelta(stepRatio, anchor)
            anchor *= stepRatio
        }
        val big = zoomScrollDelta(totalRatio, a)
        assertEquals(big, chained, 0.5f, "steps must sum to the single-step correction")
    }
}
