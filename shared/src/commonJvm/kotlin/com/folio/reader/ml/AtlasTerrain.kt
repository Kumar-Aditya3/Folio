package com.folio.reader.ml

import kotlin.math.exp
import kotlin.math.max

/**
 * Turns the Atlas roll-up into a **topographic field** — the data behind the "real map" rendering.
 *
 * Instead of stamping one translucent circle per topic cluster (which read as overlapping soup),
 * every cluster contributes a Gaussian bump to a shared scalar field sampled on an N×N grid. The
 * summed field is a heightmap: nearby clusters *fuse into contiguous landmasses*, dense topics
 * become peaks, and the space between books is genuine sea. Each cell also records which book owns
 * it (the cluster contributing most there), so the map paints as coloured **territories** with
 * organic borders where books meet — a legible archipelago rather than a scatter of dots.
 *
 * Pure and deterministic: model in, grid out. No Compose, no colour — the renderer maps elevation
 * bands + per-book reading progress to the hypsometric tint and fog-of-war.
 */
object AtlasTerrain {

    /** Gaussian spread of a unit-mass cluster, in layout units (the layout spans [-1, 1]). */
    private const val SIGMA_BASE = 0.16f

    /** Field value (fraction of max) below which a cell is sea rather than land. */
    const val SEA_LEVEL = 0.14f

    data class Field(
        val n: Int,
        /** Row-major N×N density, normalised so the tallest peak is 1.0. */
        val height: FloatArray,
        /** Row-major N×N owning-book index into [bookIds], or -1 for sea. */
        val owner: IntArray,
        val bookIds: List<String>,
        /** Each book's label anchor in layout space [-1, 1] — the centroid of the land it owns. */
        val anchors: List<Pair<Float, Float>>,
    )

    /**
     * @param model the decorated roll-up.
     * @param n grid resolution per side (96 is plenty — it is blitted and bilinear-scaled).
     */
    fun build(model: AtlasModel, n: Int = 96): Field {
        val bookIds = model.books.map { it.bookId }
        val emptyAnchors = bookIds.map { 0f to 0f }
        if (model.books.isEmpty()) return Field(n, FloatArray(0), IntArray(0), bookIds, emptyAnchors)

        // Flatten clusters with their owning-book index for the argmax ownership test.
        data class Bump(val x: Float, val y: Float, val amp: Float, val twoSigmaSq: Float, val book: Int)
        val bumps = ArrayList<Bump>()
        model.books.forEachIndexed { bi, book ->
            book.clusters.forEach { c ->
                val sigma = SIGMA_BASE * (0.6f + c.mass) // bigger topics spread wider
                bumps.add(Bump(c.x, c.y, max(0.05f, c.mass), 2f * sigma * sigma, bi))
            }
        }

        val height = FloatArray(n * n)
        val owner = IntArray(n * n) { -1 }
        // Accumulate per-book owned-cell position sums for the label anchors.
        val ax = DoubleArray(bookIds.size)
        val ay = DoubleArray(bookIds.size)
        val aw = DoubleArray(bookIds.size)

        var maxH = 0f
        for (gy in 0 until n) {
            val wy = -1f + 2f * gy / (n - 1)
            for (gx in 0 until n) {
                val wx = -1f + 2f * gx / (n - 1)
                var total = 0f
                var bestBook = -1
                var bestContribution = 0f
                for (b in bumps) {
                    val dx = wx - b.x; val dy = wy - b.y
                    val contribution = b.amp * exp(-((dx * dx + dy * dy) / b.twoSigmaSq))
                    total += contribution
                    if (contribution > bestContribution) { bestContribution = contribution; bestBook = b.book }
                }
                val idx = gy * n + gx
                height[idx] = total
                owner[idx] = bestBook
                if (total > maxH) maxH = total
                if (bestBook >= 0) {
                    val w = total.toDouble()
                    ax[bestBook] += wx * w; ay[bestBook] += wy * w; aw[bestBook] += w
                }
            }
        }

        // Normalise to [0,1] and null out sea ownership so territories stop at the shore.
        val inv = if (maxH > 1e-6f) 1f / maxH else 0f
        for (i in height.indices) {
            height[i] *= inv
            if (height[i] < SEA_LEVEL) owner[i] = -1
        }

        val anchors = bookIds.indices.map { b ->
            if (aw[b] > 0) (ax[b] / aw[b]).toFloat() to (ay[b] / aw[b]).toFloat()
            else (model.books[b].clusters.firstOrNull()?.let { it.x to it.y } ?: (0f to 0f))
        }
        return Field(n, height, owner, bookIds, anchors)
    }
}
