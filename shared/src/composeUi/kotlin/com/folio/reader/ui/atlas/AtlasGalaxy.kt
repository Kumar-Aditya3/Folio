package com.folio.reader.ui.atlas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import com.folio.reader.ml.AtlasBook
import com.folio.reader.ml.AtlasModel
import kotlin.math.PI
import kotlin.math.abs
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
     * Bake resolution. Deliberately high: the nebula is a *baked* bitmap stretched across the whole
     * map, so at 150px it blurred into smooth blobs when upscaled. At this resolution the
     * domain-warped filaments below actually survive as fine, photographic cloud structure. It is
     * baked once per model off the main thread, so the higher cost is paid a single time.
     */
    private const val NEBULA_RES = 384

    /** Base frequency of the cloud noise (higher = finer filaments). */
    private const val NEBULA_NOISE_FREQ = 3.2f

    /** How hard the domain warp curls the filaments — the difference between "clouds" and "blobs". */
    private const val NEBULA_WARP = 1.5f

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

    /**
     * The genre/community palette. Unlike [RING] (sampled by *bearing* from the origin, which
     * carried no meaning), a colour here is chosen by a community's stable `colorHueId`, so a
     * nebula's colour actually says *what genre it is* — the legend's "Colours = genres" is finally
     * true. Ordered as a spread of cosmic tones so adjacent hue slots stay visually distinct.
     */
    private val GENRE_COLORS: List<Color> = listOf(
        Color(0xFF8060B5), // violet
        Color(0xFF466FB5), // electric blue
        Color(0xFF3C9BA6), // cyan / teal
        Color(0xFF5F9E6E), // emerald
        Color(0xFFB28A54), // amber / gold
        Color(0xFFC2607F), // rose
        Color(0xFFAD5D9C), // magenta
        Color(0xFF6E7BD8), // periwinkle
        Color(0xFF4FB0C6), // sky
        Color(0xFF8FB25A), // olive
        Color(0xFFD08A46), // orange
        Color(0xFFB5566B), // crimson
        Color(0xFF7E9C57), // moss
        Color(0xFF9A6FC0), // amethyst
    )

    /** Colour for a stable hue slot (a genre's [com.folio.reader.ml.BroadGenre.hueId] or fallback). */
    fun communityColor(hueId: Int): Color {
        val n = GENRE_COLORS.size
        // Push the base palette toward vivid, luminous cosmic tones — the pastel swatches read as
        // grey haze once blended additively, so we saturate + lift them here (stars, gas and legend
        // all draw from this, so they stay consistent).
        return saturate(GENRE_COLORS[((hueId % n) + n) % n], 1.45f, 1.12f)
    }

    /** Colour of a genre by its display name, for the legend and the sheet chip. */
    fun genreColor(displayName: String?): Color {
        val g = com.folio.reader.ml.GenreTaxonomy.byDisplayName(displayName)
        return if (g != null) communityColor(g.hueId) else communityColor(displayName?.hashCode() ?: 0)
    }

    /**
     * A book's star/nebula colour: its **genre**'s colour when classified, otherwise a stable colour
     * from its emergent **community**. The community fallback (rather than one flat grey) is what
     * keeps the galaxy colourful before genres are classified, or when the classifier leaves a book
     * unclassified — a uniformly grey map was the regression when colouring moved genre-first.
     */
    fun bookColor(book: AtlasBook): Color =
        if (!book.genre.isNullOrBlank()) genreColor(book.genre) else communityColor(book.communityId)

    /** Map of communityId → colour for a model, so stars and nebulae share one source of truth. */
    fun communityColors(model: AtlasModel): Map<Int, Color> =
        model.communities.associate { it.id to communityColor(it.colorHueId) }

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
     * Book star positions = the book's **macro** layout position (force-directed on the robust
     * similarity graph), so "closer = more similar" is actually true. Colour is the book's
     * community colour, so a star matches the nebula it sits in and reads as its genre.
     * Deterministic — same library, same sky.
     */
    fun nodes(model: AtlasModel): List<BookNode> {
        val degree = HashMap<String, Int>()
        model.edges.forEach {
            degree[it.bookIdA] = (degree[it.bookIdA] ?: 0) + it.weight
            degree[it.bookIdB] = (degree[it.bookIdB] ?: 0) + it.weight
        }
        return model.books.map { b ->
            // Macro position from the layout; fall back to the cluster centroid for older caches.
            val x: Float
            val y: Float
            if (b.x != 0f || b.y != 0f || b.clusters.isEmpty()) {
                x = b.x; y = b.y
            } else {
                var sx = 0f; var sy = 0f; var sw = 0f
                b.clusters.forEach { c -> val w = c.mass.coerceAtLeast(0.02f); sx += c.x * w; sy += c.y * w; sw += w }
                x = if (sw > 0f) sx / sw else 0f
                y = if (sw > 0f) sy / sw else 0f
            }
            val deg = degree[b.bookId] ?: 0
            // Size from cluster count (a richer book is a brighter star) + a little connectivity.
            val size = (0.65f + 0.06f * b.clusters.size + 0.03f * deg).coerceIn(0.6f, 1.4f)
            BookNode(
                bookId = b.bookId,
                title = b.title,
                x = x,
                y = y,
                color = bookColor(b),
                readFraction = b.readFraction.coerceIn(0f, 1f),
                sizeScale = size,
                degree = deg,
            )
        }
    }

    // ── Nebula bake ───────────────────────────────────────────────────────────────────────

    private class Bump(val x: Float, val y: Float, val amp: Float, val twoSigmaSq: Float, val r: Float, val g: Float, val b: Float)

    // A single-entry in-memory cache of the last baked nebula. The bake is a 384² per-pixel
    // domain-warped-noise pass — hundreds of ms — so re-baking it every time the Atlas is opened is
    // what made the gas "appear late" even on a cached map. Keyed by the exact inputs the bake reads
    // (each book's colour + its clusters' positions/mass), so it is reused across opens within a
    // session and silently rebaked when the map actually changes. One ~590 KB bitmap, bounded.
    @Volatile private var cachedNebula: ImageBitmap? = null
    @Volatile private var cachedNebulaKey: Long = 0L

    private fun nebulaKey(model: AtlasModel): Long {
        var h = 1125899906842597L
        h = 31 * h + model.books.size
        for (b in model.books) {
            h = 31 * h + b.bookId.hashCode()
            h = 31 * h + (b.genre?.hashCode() ?: 0)
            h = 31 * h + b.communityId
            for (c in b.clusters) {
                h = 31 * h + c.x.toRawBits()
                h = 31 * h + c.y.toRawBits()
            }
        }
        return h
    }

    /** Returns the cached nebula if it matches [model], else null — a cheap synchronous check so the
     *  renderer can show the gas on the first frame of a repeat open instead of after a re-bake. */
    fun peekNebula(model: AtlasModel): ImageBitmap? =
        if (nebulaKey(model) == cachedNebulaKey) cachedNebula else null

    /** [bakeNebula] with the single-entry cache in front of it. Call off the main thread. */
    fun nebulaFor(model: AtlasModel): ImageBitmap {
        val key = nebulaKey(model)
        cachedNebula?.let { if (key == cachedNebulaKey) return it }
        val baked = bakeNebula(model)
        cachedNebula = baked
        cachedNebulaKey = key
        return baked
    }

    /**
     * Bakes the nebula into an [n]×[n] RGBA bitmap in layout space `[-EXTENT, EXTENT]`.
     *
     * Two layers of maths make it read like a real deep-space nebula rather than soft blobs:
     *
     * 1. **Shape & colour** — every cluster still contributes a coloured Gaussian, and each cell is
     *    the density-weighted blend of the clusters reaching it. This decides *where* gas is and what
     *    genre-colour it carries, and lets overlapping same-genre clouds melt together seamlessly.
     * 2. **Structure** — that smooth density is then carved by **domain-warped ridged fractal noise**:
     *    fBm warps the sample point (so filaments curl instead of lying flat), ridged noise turns the
     *    field into bright veins, and a second low-frequency fBm cuts dark dust lanes through it.
     *    Cores near a cluster centre glow toward hot starlight white. The result is wispy, layered,
     *    filamentary cloud with dust lanes and bright cores — deterministic, so same library, same sky.
     *
     * Runs off the main thread; safe on both JVM targets (software bitmap).
     */
    fun bakeNebula(model: AtlasModel, n: Int = NEBULA_RES): ImageBitmap {
        val bumps = ArrayList<Bump>()
        model.books.forEach { book ->
            // A cluster is tinted by its book's colour (genre when known, else its community), so
            // overlapping books of the same kind fuse into one coloured nebula.
            val col = bookColor(book)
            book.clusters.forEach { c ->
                val sigma = SIGMA_BASE * (0.7f + c.mass)
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
            val wy = -EXTENT + 2f * EXTENT * gy / (n - 1)
            for (gx in 0 until n) {
                val idx = gy * n + gx
                val t = total[idx]
                if (t <= 1e-6f) {
                    paint.color = Color.Transparent
                    canvas.drawRect(gx.toFloat(), gy.toFloat(), gx + 1f, gy + 1f, paint)
                    continue
                }
                val d = (t * inv).coerceIn(0f, 1f)
                val ramp = smoothstep(0.05f, 0.5f, d)
                val core = smoothstep(0.58f, 0.95f, d)
                if (ramp <= 0.003f && core <= 0.003f) {
                    paint.color = Color.Transparent
                    canvas.drawRect(gx.toFloat(), gy.toFloat(), gx + 1f, gy + 1f, paint)
                    continue
                }
                val wx = -EXTENT + 2f * EXTENT * gx / (n - 1)

                // Domain-warped ridged filaments — the curling, veined structure of real gas.
                val px = (wx + EXTENT) * NEBULA_NOISE_FREQ
                val py = (wy + EXTENT) * NEBULA_NOISE_FREQ
                val warpX = fbm2(px + 4.7f, py + 2.3f) - 0.5f
                val warpY = fbm2(px - 3.1f, py + 6.9f) - 0.5f
                val sx = px + NEBULA_WARP * warpX
                val sy = py + NEBULA_WARP * warpY
                val filament = ridged(sx, sy)
                val vein = filament * filament // sharpen the bright veins
                // Dark dust lanes threaded through the cloud (low-frequency, warped too).
                val lanes = smoothstep(0.10f, 0.70f, fbm2(sx * 0.6f + 12.1f, sy * 0.6f - 4.3f))

                var col = Color(rA[idx] / t, gA[idx] / t, bA[idx] / t, 1f)
                // Vivid, luminous gas: saturate the blend, then brighten the veins *hue-preserving*
                // (multiply, not lerp-to-white) so bright filaments stay coloured instead of greying
                // out. Only the hottest cores tip toward starlight.
                col = saturate(col, 1.35f, 1.05f)
                col = lerp(col, brighten(col, 1.85f), vein * 0.7f)
                col = lerp(col, CORE_TINT, core * 0.85f)

                val grain = 0.9f + 0.1f * hash01(gx, gy)
                // High contrast: a very low diffuse floor keeps the voids dark, while veins and cores
                // ramp up hard. Drawn additively (BlendMode.Plus) by the renderer, so alpha == glow.
                val body = ramp * (0.07f + 0.9f * vein)
                val alpha = ((body * (0.4f + 0.6f * lanes)) + core * 0.72f) * grain
                if (alpha <= 0.004f) {
                    paint.color = Color.Transparent
                } else {
                    paint.color = col.copy(alpha = alpha.coerceIn(0f, 1f))
                }
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
     * A sparse, irregular star field. Most stars cluster around real nebula centres (so space is
     * denser and more speckled where the gas is — as in a real deep-field image), the rest scatter
     * across the field. Sizes and brightness vary from faint dust motes to a few larger glowing
     * stars. Deterministic per library.
     */
    fun starfield(model: AtlasModel, count: Int = 460, seed: Int = 0): List<BgStar> {
        val centres = ArrayList<Offset>()
        model.books.forEach { b -> b.clusters.forEach { c -> centres.add(Offset(c.x, c.y)) } }
        val rnd = Random(seed.toLong() * 92821L + model.books.size * 2654435761L)
        val out = ArrayList<BgStar>(count)
        repeat(count) {
            val x: Float; val y: Float
            if (centres.isNotEmpty() && rnd.nextFloat() < 0.62f) {
                val c = centres[rnd.nextInt(centres.size)]
                x = c.x + gaussian(rnd) * 0.14f
                y = c.y + gaussian(rnd) * 0.14f
            } else {
                x = (rnd.nextFloat() * 2f - 1f) * EXTENT
                y = (rnd.nextFloat() * 2f - 1f) * EXTENT
            }
            val big = rnd.nextFloat() < 0.07f
            val tiny = !big && rnd.nextFloat() < 0.42f
            val radius = when {
                big -> 1.3f + rnd.nextFloat() * 1.1f
                tiny -> 0.3f + rnd.nextFloat() * 0.4f // faint dust motes for fine speckle
                else -> 0.55f + rnd.nextFloat() * 0.85f
            }
            val baseAlpha = when {
                big -> 0.55f + rnd.nextFloat() * 0.4f
                tiny -> 0.16f + rnd.nextFloat() * 0.3f
                else -> 0.34f + rnd.nextFloat() * 0.5f
            }
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

    /** Pushes a colour away from its own grey (saturation), then scales brightness. Alpha kept. */
    fun saturate(c: Color, satMul: Float, valMul: Float = 1f): Color {
        val l = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        fun ch(x: Float): Float = ((l + (x - l) * satMul).coerceIn(0f, 1f) * valMul).coerceIn(0f, 1f)
        return Color(ch(c.red), ch(c.green), ch(c.blue), c.alpha)
    }

    /** Multiplies RGB (hue-preserving brightening) — brighter than lerp-to-white, keeps colour. */
    private fun brighten(c: Color, f: Float): Color =
        Color((c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), 1f)

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

    /** Fractal (value) noise normalised to ~[0,1). Smooth base layer for warps and dust. */
    private fun fbm2(x: Float, y: Float, octaves: Int = 6): Float {
        var v = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        repeat(octaves) {
            v += amp * valueNoise(x * f + it * 11.3f, y * f - it * 7.7f)
            norm += amp
            f *= 2f; amp *= 0.5f
        }
        return if (norm > 0f) v / norm else v
    }

    /**
     * Ridged multifractal noise in [0,1): folds each octave about its midpoint so the *ridges*
     * become sharp bright lines. This is what turns diffuse gas into filaments and veins.
     */
    private fun ridged(x: Float, y: Float, octaves: Int = 5): Float {
        var v = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        repeat(octaves) {
            val nz = valueNoise(x * f + it * 5.1f, y * f - it * 9.3f)
            val r = 1f - abs(2f * nz - 1f) // ridge peaks where noise ~ 0.5
            v += amp * r * r
            norm += amp
            f *= 2f; amp *= 0.5f
        }
        return if (norm > 0f) (v / norm).coerceIn(0f, 1f) else 0f
    }
}
