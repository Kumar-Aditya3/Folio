package com.folio.reader.ui.atlas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import com.folio.reader.ml.AtlasModel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.random.Random

/**
 * The pure/data half of the galactic Atlas: it turns the roll-up's topic clusters into the
 * ingredients the [AtlasScreen] renderer needs — a blended **nebula** bitmap, a per-book **star**
 * position and colour, and a sparse **background star field** biased toward the nebulae.
 *
 * Nothing here is Compose-lifecycle bound; it is plain maths over the existing [AtlasModel] so the
 * layout, similarity edges and reading data are preserved exactly. The renderer owns the camera,
 * animation and interaction.
 *
 * ### Why the nebula reuses the cluster Gaussians
 * The old topographic terrain summed one Gaussian bump per cluster into a height field. That same
 * field is what makes nearby topics *fuse* into contiguous clouds, so we keep the idea and change
 * only the paint: instead of argmax "ownership" (which draws hard coastlines), every cell blends a
 * *weighted average* of the contributing clusters' colours. Overlapping nebulae therefore melt into
 * one another with no seam — the "colours must fade smoothly" requirement falls out of the physics.
 */
internal object AtlasGalaxy {

    /** Layout is `[-1, 1]`; the nebula is baked a little past that so bumps near the edge don't clip. */
    const val EXTENT = 1.28f

    /** Gaussian spread of a unit-mass cluster, in layout units. Matches the old terrain feel. */
    private const val SIGMA_BASE = 0.17f

    /**
     * The cosmic palette as a **hue ring**. Colours are chosen from the brief's suggested nebula
     * tones and ordered so each neighbour is a small step from the last (violet → blue → cyan →
     * green → amber → rose → magenta → back to violet). A cluster's colour is sampled from this ring
     * by its *angle* around the map centre, so the whole galaxy reads as one continuous flow of
     * colour rather than scattered blobs, and two similar (nearby) books share a colour family.
     */
    private val RING: List<Color> = listOf(
        Color(0xFF8060B5), // violet
        Color(0xFF466FB5), // electric blue
        Color(0xFF3C9BA6), // cyan / teal
        Color(0xFF608E78), // muted emerald
        Color(0xFFB28A54), // amber / gold
        Color(0xFFA75E78), // rose
        Color(0xFFAD5D9C), // magenta
    )

    /** Pale core tint bright cores lift toward — a cool starlight white, never pure #FFFFFF. */
    private val CORE_TINT = Color(0xFFDCE4FF)

    /** Sample the palette ring at [t] in `[0,1)`, interpolated (Oklab via Compose lerp). */
    fun ringColor(t: Float): Color {
        val n = RING.size
        val p = ((t % 1f) + 1f) % 1f * n
        val i = p.toInt() % n
        val f = p - p.toInt()
        return lerp(RING[i], RING[(i + 1) % n], f)
    }

    /** Angle of a layout point around the origin, normalised to `[0,1)`. Drives the nebula hue. */
    fun angle01(x: Float, y: Float): Float {
        val a = atan2(y.toDouble(), x.toDouble()).toFloat() // [-π, π]
        return ((a / (2f * PI.toFloat())) + 1f) % 1f
    }

    // ── Per-book derived geometry ─────────────────────────────────────────────────────────

    /** Everything the renderer needs about one book's star, precomputed once per model. */
    data class BookNode(
        val bookId: String,
        val title: String,
        val x: Float,
        val y: Float,
        val color: Color,
        /** 0..1 reading progress — read books shine brighter, unread ones sit dim. */
        val readFraction: Float,
        /** Relative star size 0.6..1.4, from the book's mass and connectivity. */
        val sizeScale: Float,
        val degree: Int,
    )

    /**
     * Book star positions = the mass-weighted centroid of the book's own topic clusters, i.e. where
     * the book's light actually concentrates. Colour is the nebula colour at that point, so a star
     * always complements the gas around it. Deterministic — same library, same sky.
     */
    fun nodes(model: AtlasModel): List<BookNode> {
        val degree = HashMap<String, Int>()
        model.edges.forEach {
            degree[it.bookIdA] = (degree[it.bookIdA] ?: 0) + it.weight
            degree[it.bookIdB] = (degree[it.bookIdB] ?: 0) + it.weight
        }
        return model.books.map { b ->
            var sx = 0f; var sy = 0f; var sw = 0f
            b.clusters.forEach { c -> val w = c.mass.coerceAtLeast(0.02f); sx += c.x * w; sy += c.y * w; sw += w }
            val x = if (sw > 0f) sx / sw else 0f
            val y = if (sw > 0f) sy / sw else 0f
            val deg = degree[b.bookId] ?: 0
            // Size from cluster count (a richer book is a brighter star) + a little connectivity.
            val size = (0.65f + 0.06f * b.clusters.size + 0.03f * deg).coerceIn(0.6f, 1.4f)
            BookNode(
                bookId = b.bookId,
                title = b.title,
                x = x,
                y = y,
                color = ringColor(angle01(x, y)),
                readFraction = b.readFraction.coerceIn(0f, 1f),
                sizeScale = size,
                degree = deg,
            )
        }
    }

    // ── Nebula bake ───────────────────────────────────────────────────────────────────────

    private class Bump(val x: Float, val y: Float, val amp: Float, val twoSigmaSq: Float, val r: Float, val g: Float, val b: Float)

    /**
     * Bakes the nebula into an [n]×[n] RGBA bitmap in layout space `[-EXTENT, EXTENT]`.
     *
     * Every cluster contributes a coloured Gaussian; each cell is the density-weighted blend of the
     * clusters reaching it, with alpha ramped by a smoothstep of the normalised density so cores are
     * bright and edges dissolve into transparent (real dark space between nebulae). A cheap value
     * noise breaks up the perfectly-smooth gradients into cloud-like texture. Runs off the main
     * thread; safe on both JVM targets (software bitmap).
     */
    fun bakeNebula(model: AtlasModel, n: Int = 150): ImageBitmap {
        val bumps = ArrayList<Bump>()
        model.books.forEach { book ->
            book.clusters.forEach { c ->
                val sigma = SIGMA_BASE * (0.6f + c.mass)
                val col = ringColor(angle01(c.x, c.y))
                bumps.add(Bump(c.x, c.y, c.mass.coerceAtLeast(0.05f), 2f * sigma * sigma, col.red, col.green, col.blue))
            }
        }

        val cells = n * n
        val total = FloatArray(cells)
        val rA = FloatArray(cells)
        val gA = FloatArray(cells)
        val bA = FloatArray(cells)
        var maxT = 1e-6f
        for (gy in 0 until n) {
            val wy = -EXTENT + 2f * EXTENT * gy / (n - 1)
            for (gx in 0 until n) {
                val wx = -EXTENT + 2f * EXTENT * gx / (n - 1)
                var t = 0f; var r = 0f; var g = 0f; var b = 0f
                for (bump in bumps) {
                    val dx = wx - bump.x; val dy = wy - bump.y
                    val c = bump.amp * exp(-((dx * dx + dy * dy) / bump.twoSigmaSq))
                    t += c; r += c * bump.r; g += c * bump.g; b += c * bump.b
                }
                val idx = gy * n + gx
                total[idx] = t; rA[idx] = r; gA[idx] = g; bA[idx] = b
                if (t > maxT) maxT = t
            }
        }

        val inv = 1f / maxT
        val bmp = ImageBitmap(n, n)
        val canvas = androidx.compose.ui.graphics.Canvas(bmp)
        val paint = androidx.compose.ui.graphics.Paint()
        for (gy in 0 until n) {
            for (gx in 0 until n) {
                val idx = gy * n + gx
                val t = total[idx]
                val d = (t * inv).coerceIn(0f, 1f)
                val ramp = smoothstep(0.10f, 0.55f, d)
                if (ramp <= 0.004f || t <= 1e-6f) {
                    paint.color = Color.Transparent
                    canvas.drawRect(gx.toFloat(), gy.toFloat(), gx + 1f, gy + 1f, paint)
                    continue
                }
                var col = Color(rA[idx] / t, gA[idx] / t, bA[idx] / t, 1f)
                // Bright cores glow toward cool starlight; keep saturation off the walls.
                col = lerp(col, CORE_TINT, (d * d) * 0.5f)
                // Multi-octave value noise carves the smooth blobs into wispy, filamentary cloud.
                val wx = -EXTENT + 2f * EXTENT * gx / (n - 1)
                val wy = -EXTENT + 2f * EXTENT * gy / (n - 1)
                val tex = (0.34f + 1.0f * fbm((wx + EXTENT) * 4.4f, (wy + EXTENT) * 4.4f)).coerceIn(0f, 1.15f)
                val grain = 0.9f + 0.1f * hash01(gx, gy)
                val alpha = (ramp * 0.95f * tex * grain).coerceIn(0f, 0.95f)
                paint.color = col.copy(alpha = alpha)
                canvas.drawRect(gx.toFloat(), gy.toFloat(), gx + 1f, gy + 1f, paint)
            }
        }
        return bmp
    }

    // ── Background star field ───────────────────────────────────────────────────────────────

    /** One decorative background star, positioned in layout space so it rides the camera. */
    class BgStar(
        val x: Float,
        val y: Float,
        val radiusDp: Float,
        val baseAlpha: Float,
        val color: Color,
        val twinklePhase: Float,
        val twinkleAmp: Float,
        val glow: Boolean,
    )

    private val STAR_WHITE = Color(0xFFF2F4FF)
    private val STAR_BLUE = Color(0xFFC6D4FF)
    private val STAR_LAVENDER = Color(0xFFD9CFF2)

    /**
     * A sparse, irregular star field. ~45% of stars cluster around real nebula centres (so space is
     * denser where the galaxy is), the rest scatter across the field. Sizes and brightness vary; a
     * handful are larger glowing stars. Deterministic per library.
     */
    fun starfield(model: AtlasModel, count: Int = 300, seed: Int = 0): List<BgStar> {
        val centres = ArrayList<Offset>()
        model.books.forEach { b -> b.clusters.forEach { c -> centres.add(Offset(c.x, c.y)) } }
        val rnd = Random(seed.toLong() * 92821L + model.books.size * 2654435761L)
        val out = ArrayList<BgStar>(count)
        repeat(count) {
            val x: Float; val y: Float
            if (centres.isNotEmpty() && rnd.nextFloat() < 0.45f) {
                val c = centres[rnd.nextInt(centres.size)]
                x = c.x + gaussian(rnd) * 0.16f
                y = c.y + gaussian(rnd) * 0.16f
            } else {
                x = (rnd.nextFloat() * 2f - 1f) * EXTENT
                y = (rnd.nextFloat() * 2f - 1f) * EXTENT
            }
            val big = rnd.nextFloat() < 0.055f
            val radius = if (big) 1.2f + rnd.nextFloat() * 1.0f else 0.4f + rnd.nextFloat() * 0.85f
            val baseAlpha = if (big) 0.42f + rnd.nextFloat() * 0.28f else 0.16f + rnd.nextFloat() * 0.4f
            val color = when {
                rnd.nextFloat() < 0.7f -> STAR_WHITE
                rnd.nextFloat() < 0.6f -> STAR_BLUE
                else -> STAR_LAVENDER
            }
            out.add(
                BgStar(
                    x = x, y = y,
                    radiusDp = radius,
                    baseAlpha = baseAlpha,
                    color = color,
                    twinklePhase = rnd.nextFloat() * (2f * PI.toFloat()),
                    twinkleAmp = 0.12f + rnd.nextFloat() * 0.35f,
                    glow = big,
                )
            )
        }
        return out
    }

    // ── small maths helpers ─────────────────────────────────────────────────────────────────

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun gaussian(rnd: Random): Float =
        (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) // ~N(0, ~0.5), cheap

    private fun hash01(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** Smooth value noise in [0,1) at a lattice point, deterministic. */
    private fun latticeHash(ix: Int, iy: Int): Float {
        var h = ix * 73856093 xor iy * 19349663
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / 2147483647f
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        val n00 = latticeHash(x0, y0); val n10 = latticeHash(x0 + 1, y0)
        val n01 = latticeHash(x0, y0 + 1); val n11 = latticeHash(x0 + 1, y0 + 1)
        val a = n00 + (n10 - n00) * sx
        val b = n01 + (n11 - n01) * sx
        return a + (b - a) * sy
    }

    /** 3-octave fractal noise, ~[0,1). Gives the nebulae their cloud-like structure. */
    private fun fbm(x: Float, y: Float): Float {
        var v = 0f; var amp = 0.5f; var f = 1f
        repeat(3) {
            v += amp * valueNoise(x * f + it * 11.3f, y * f - it * 7.7f)
            f *= 2f; amp *= 0.5f
        }
        return v
    }
}
