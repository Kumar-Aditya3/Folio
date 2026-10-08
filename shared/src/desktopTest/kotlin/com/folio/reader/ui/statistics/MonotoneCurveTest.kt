package com.folio.reader.ui.statistics

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Monotone cubic smoothing, pinned pure. A reading chart must be smooth but
 * honest: interpolation may never display minutes the reader did not read.
 */
class MonotoneCurveTest {

    @Test
    fun `the curve passes exactly through every sample`() {
        val samples = listOf(
            Offset(0f, 100f), Offset(10f, 40f), Offset(20f, 60f),
            Offset(30f, 10f), Offset(40f, 80f),
        )
        val segs = monotoneCubicSegments(samples)
        assertEquals(samples.size - 1, segs.size, "one segment per pair of samples")
        for (i in segs.indices) {
            assertEquals(samples[i], segs[i].p0, "segment $i must start at sample $i")
            assertEquals(samples[i + 1], segs[i].p3, "segment $i must end at sample ${i + 1}")
        }
    }

    @Test
    fun `the curve never overshoots the local envelope`() {
        // [0, 10, 0]: between the samples the curve must stay within [0, 10].
        val samples = listOf(Offset(0f, 0f), Offset(1f, 10f), Offset(2f, 0f))
        for (seg in monotoneCubicSegments(samples)) {
            for (step in 0..20) {
                val p = seg.at(step / 20f)
                assertTrue(p.y >= -0.0001f, "dipped below the envelope: y=${p.y}")
                assertTrue(p.y <= 10.0001f, "rose above the envelope: y=${p.y}")
            }
        }
        // An asymmetric rise/fall shape too.
        val zig = listOf(
            Offset(0f, 5f), Offset(1f, 0f), Offset(2f, 12f), Offset(3f, 3f), Offset(4f, 12f),
        )
        for (seg in monotoneCubicSegments(zig)) {
            val lo = minOf(seg.p0.y, seg.p3.y)
            val hi = maxOf(seg.p0.y, seg.p3.y)
            for (step in 0..20) {
                val y = seg.at(step / 20f).y
                assertTrue(y >= lo - 0.0001f && y <= hi + 0.0001f,
                    "segment ${seg.p0.y}→${seg.p3.y} left its envelope at y=$y")
            }
        }
    }

    @Test
    fun `a flat run of samples yields an exactly flat curve`() {
        val samples = listOf(
            Offset(0f, 42f), Offset(1f, 42f), Offset(2f, 42f), Offset(3f, 42f), Offset(4f, 10f),
        )
        val segs = monotoneCubicSegments(samples)
        // The flat run's interior segments must be perfectly flat.
        for (i in 0 until 2) {
            val seg = segs[i]
            assertEquals(42f, seg.c1.y, "flat segment $i control 1 must stay at 42")
            assertEquals(42f, seg.c2.y, "flat segment $i control 2 must stay at 42")
            for (step in 0..10) {
                assertEquals(42f, seg.at(step / 10f).y, 0.0001f, "flat segment $i curved")
            }
        }
    }

    @Test
    fun `fewer than three points degrade to a polyline`() {
        assertEquals(0, monotoneCubicSegments(emptyList()).size)
        assertEquals(0, monotoneCubicSegments(listOf(Offset(3f, 4f))).size)
        // Two points: exactly one segment, straight.
        val two = monotoneCubicSegments(listOf(Offset(0f, 0f), Offset(10f, 20f)))
        assertEquals(1, two.size)
        for (step in 0..10) {
            val p = two[0].at(step / 10f)
            assertEquals(2f * p.x, p.y, 0.0001f, "two-point curve must be the straight line")
        }
    }

    @Test
    fun `duplicate x positions do not crash or invent slope`() {
        // Zero-width segments (repeated samples) must not divide by zero; the
        // curve between distinct neighbours stays inside their envelope.
        val samples = listOf(
            Offset(0f, 10f), Offset(1f, 10f), Offset(1f, 10f), Offset(2f, 0f),
        )
        val segs = monotoneCubicSegments(samples)
        for (seg in segs) {
            val lo = minOf(seg.p0.y, seg.p3.y)
            val hi = maxOf(seg.p0.y, seg.p3.y)
            for (step in 0..10) {
                val y = seg.at(step / 10f).y
                assertTrue(y >= lo - 0.0001f && y <= hi + 0.0001f,
                    "duplicate-x segment ${seg.p0.y}→${seg.p3.y} left its envelope at y=$y")
            }
        }
    }
}
