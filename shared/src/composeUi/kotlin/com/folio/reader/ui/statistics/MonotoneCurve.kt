package com.folio.reader.ui.statistics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/**
 * Monotone cubic interpolation (Fritsch–Carlson tangents), for the stats
 * week charts' curves.
 *
 * The constraint that matters for a reading chart: the curve is smooth but
 * **never overshoots** — between two samples it stays inside the local min/max
 * envelope of those samples, so the chart never displays minutes the reader
 * did not read. Flat runs (equal consecutive samples) get zero tangents and
 * stay perfectly flat. Fewer than 3 points degrade to straight lines.
 */

/**
 * One cubic Bézier segment in curve order: [p0] start sample, [c1]/[c2] control
 * points, [p3] end sample. Pure data so the geometry can be pinned by desktop
 * tests without a Path backend.
 */
internal class MonotoneSegment(
    val p0: Offset,
    val c1: Offset,
    val c2: Offset,
    val p3: Offset,
) {
    /** The segment point at parameter [t] ∈ [0, 1]. */
    fun at(t: Float): Offset = Offset(
        bez(p0.x, c1.x, c2.x, p3.x, t),
        bez(p0.y, c1.y, c2.y, p3.y, t),
    )

    private fun bez(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }
}

/**
 * Fritsch–Carlson monotone cubic through [points]: one segment per pair of
 * adjacent samples, endpoints exactly on the samples. The tangents are the
 * average of the neighbouring secants where they agree in sign (zero at a
 * local extremum), then clamped to three times their segment's secant — the
 * clamp is what keeps every control point inside the endpoint envelope, and a
 * Bézier never leaves the convex hull of its control points, so the curve
 * cannot overshoot.
 */
internal fun monotoneCubicSegments(points: List<Offset>): List<MonotoneSegment> {
    when (points.size) {
        0, 1 -> return emptyList()
        2 -> {
            val a = points[0]
            val b = points[1]
            // Straight line as a degenerate cubic (control points at 1/3 and 2/3).
            return listOf(
                MonotoneSegment(
                    a,
                    Offset(a.x + (b.x - a.x) / 3f, a.y + (b.y - a.y) / 3f),
                    Offset(b.x - (b.x - a.x) / 3f, b.y - (b.y - a.y) / 3f),
                    b,
                )
            )
        }

        else -> Unit
    }

    // Secant slopes of each segment; a zero-width segment (repeated sample) has
    // no slope and forces zero tangents on both its ends.
    val dx = FloatArray(points.size - 1)
    val slope = FloatArray(points.size - 1)
    for (i in dx.indices) {
        dx[i] = points[i + 1].x - points[i].x
        slope[i] = if (dx[i] != 0f) (points[i + 1].y - points[i].y) / dx[i] else 0f
    }
    // Initial tangents: the average of adjacent secants where they agree in
    // sign, zero at a local extremum. Endpoints take their one adjacent secant.
    val tangent = FloatArray(points.size)
    tangent[0] = slope[0]
    tangent[points.size - 1] = slope[slope.size - 1]
    for (i in 1 until points.size - 1) {
        tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f
        else (slope[i - 1] + slope[i]) / 2f
    }
    // Fritsch–Carlson limiter: a tangent longer than 3× its segment's secant
    // would place a control point outside the endpoint envelope and overshoot.
    for (i in 0 until points.size - 1) {
        if (slope[i] == 0f) {
            tangent[i] = 0f
            tangent[i + 1] = 0f
            continue
        }
        val limit = 3f * kotlin.math.abs(slope[i])
        if (kotlin.math.abs(tangent[i]) > limit) {
            tangent[i] = if (tangent[i] > 0f) limit else -limit
        }
        if (kotlin.math.abs(tangent[i + 1]) > limit) {
            tangent[i + 1] = if (tangent[i + 1] > 0f) limit else -limit
        }
    }

    return (0 until points.size - 1).map { i ->
        val x0 = points[i].x
        val y0 = points[i].y
        val x1 = points[i + 1].x
        val y1 = points[i + 1].y
        val m0 = tangent[i] * dx[i]
        val m1 = tangent[i + 1] * dx[i]
        MonotoneSegment(
            Offset(x0, y0),
            Offset(x0 + dx[i] / 3f, y0 + m0 / 3f),
            Offset(x1 - dx[i] / 3f, y1 - m1 / 3f),
            Offset(x1, y1),
        )
    }
}

/** Appends the smoothed curve to this path: a move to the first point, then one cubic per segment. */
fun Path.appendMonotoneCubic(points: List<Offset>) {
    val segments = monotoneCubicSegments(points)
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    for (seg in segments) {
        cubicTo(seg.c1.x, seg.c1.y, seg.c2.x, seg.c2.y, seg.p3.x, seg.p3.y)
    }
}
