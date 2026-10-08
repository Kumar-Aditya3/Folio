package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * §13.3: raw sampled accents keyed by coverPath — one 32×32 sample per cover,
 * process-lifetime. Internal rather than private so the desktop test can pin the
 * property this file has always claimed ("the raw sample is cached per path")
 * without a Compose runtime: [cachedOrSampledAccent] is the only writer.
 */
internal val accentCache = ConcurrentHashMap<String, Color>()

/**
 * How many covers may be pixel-sampled at once.
 *
 * Ungated, [rememberCoverAccent] fans a shelf's samples out over every thread
 * `Dispatchers.Default` has, and the samples arrive pre-aligned: a shelf
 * composes ~12 covers, their decodes finish together, `awaitCoverBitmapForAccent`
 * polls on a 100ms tick, so ~12 × ~0.8ms of stride reads *and* the recompositions
 * their state writes cause land in the same one or two frames — measured on
 * device as the ~10ms spike inside the Home→Library morph, on a Library already
 * at 92.8% janky frames.
 *
 * Three permits, chosen against that number rather than by taste. A sample costs
 * ~0.8ms, so three in flight is ~2.4ms of the 16.6ms frame budget worst-case —
 * it leaves the frame room for the grid's own measure/layout — and a 12-cover
 * shelf drains through the gate in four separate permit hand-offs instead of one
 * burst. Each hand-off is its own coroutine dispatch, so the state writes fall in
 * different frames and each cover's glow arrives on its own frame rather than
 * twelve at once; the strength ramp in [rememberCoverHaloStrength] is what makes
 * that spread visible. Not 1: a scrolled page would trickle in over a second and
 * the shelf would read half-lit. Not 6: that is the decode gate's number
 * (`coverLoadGate` in MangaCovers.kt) and it is a *memory* bound, while this is a
 * CPU one — a sample never allocates more than the ~4KB grid it reads, so widening
 * here buys nothing and re-stacks most of the measured spike.
 */
internal const val COVER_SAMPLE_PERMITS = 3

/**
 * The gate [COVER_SAMPLE_PERMITS] describes. Held across the pixel read, not just
 * across the dispatch onto Default — releasing it before `withContext` would let
 * twelve coroutines queue up and then run their reads concurrently, which is the
 * exact cost the gate exists to bound.
 */
internal val coverSampleGate = Semaphore(COVER_SAMPLE_PERMITS)

// ── pure colour math (unit-tested on desktop) ───────────────────────────────

/** ARGB → (hue 0..360, saturation, value). */
internal fun rgbToHsv(argb: Int): Triple<Float, Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxc = max(r, max(g, b))
    val minc = min(r, min(g, b))
    val d = maxc - minc
    val h = when {
        d <= 0f -> 0f
        maxc == r -> {
            val raw = 60f * (((g - b) / d) % 6f)
            if (raw < 0f) raw + 360f else raw
        }
        maxc == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    val s = if (maxc <= 0f) 0f else d / maxc
    return Triple(h, s, maxc)
}

/**
 * §13.3 sampling: pixels qualifying (saturation > 0.25 — paper-white loses on
 * saturation alone; value ≥ 0.2 — black bars lose) are bucketed into 12 hue
 * bins elected by chroma salience — each pixel weighs s·v². No upper value cap:
 * the first pass capped at 0.9 and filtered out exactly the bloom of glow
 * covers, then the modal bin's flat mean landed on the dark field colour,
 * failed the contrast guard and fell back to the accent, so the hero stopped
 * visibly tracking the cover. Salience weighting lets the glow speak for the
 * cover. The modal bin's salience-weighted mean colour wins. Null when nothing
 * qualifies — the caller keeps accentProgress.
 */
internal fun sampleCoverAccent(argbPixels: IntArray): Color? {
    val binWeight = DoubleArray(12)
    val binHue = DoubleArray(12)
    val binSat = DoubleArray(12)
    val binVal = DoubleArray(12)
    for (pixel in argbPixels) {
        val (h, s, v) = rgbToHsv(pixel)
        if (s > 0.25f && v >= 0.2f) {
            val w = s.toDouble() * v.toDouble() * v.toDouble()
            val bin = ((h / 30f).toInt()).coerceIn(0, 11)
            binWeight[bin] += w
            binHue[bin] += h * w
            binSat[bin] += s * w
            binVal[bin] += v * w
        }
    }
    val modal = (0..11).maxByOrNull { binWeight[it] } ?: return null
    val w = binWeight[modal]
    if (w <= 0.0) return null
    return Color.hsv(
        hue = (binHue[modal] / w).toFloat(),
        saturation = (binSat[modal] / w).toFloat(),
        value = (binVal[modal] / w).toFloat(),
    )
}

/**
 * The light a cover throws, as distinct from the colour it is.
 *
 * [sampleCoverAccent] answers "what colour is this book" — one salience-weighted
 * average, which is the right question for an accent and the wrong one for a light.
 * A light needs three things the average throws away: **where** the brightness is
 * (a cover lit from the lower-left is not a cover that glows from its middle), **what
 * colour the lit parts are** rather than what the whole thing averages to, and **how
 * much light there is to give** — a flat matte dust jacket should barely light
 * anything, and a torchlit cover should be obvious about it.
 *
 * All three come out of the same 32x32 grid that is already in hand, so this costs a
 * second walk over ~1024 ints once per cover, cached for the life of the process
 * beside the accent itself.
 */
data class CoverLight(
    /** The colour of the cover's lit areas, weighted toward its brightest pixels. */
    val highlight: Color,
    /** Brightness centroid across the cover, 0..1 left-to-right. */
    val centerX: Float,
    /** Brightness centroid across the cover, 0..1 top-to-bottom. */
    val centerY: Float,
    /** What share of the sampled pixels carried real light. 0 means a flat cover. */
    val emission: Float,
)

/**
 * The cover's light, from the same grid as its accent. [gridWidth] and [gridHeight]
 * are the sample grid's own dimensions, needed only to turn a pixel index into a
 * position on the cover.
 *
 * Weight is luminance cubed, which is deliberately aggressive: it is what stops a
 * large pale background from outvoting the small bright thing that is actually the
 * light source in the artwork.
 */
internal fun sampleCoverLight(argbPixels: IntArray, gridWidth: Int, gridHeight: Int): CoverLight? {
    if (argbPixels.isEmpty() || gridWidth <= 0 || gridHeight <= 0) return null
    var weight = 0.0
    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var sumX = 0.0
    var sumY = 0.0
    var lit = 0
    for ((index, pixel) in argbPixels.withIndex()) {
        val (_, _, v) = rgbToHsv(pixel)
        val w = v.toDouble() * v.toDouble() * v.toDouble()
        if (w <= 0.0) continue
        val r = (pixel shr 16 and 0xFF) / 255.0
        val g = (pixel shr 8 and 0xFF) / 255.0
        val b = (pixel and 0xFF) / 255.0
        weight += w
        sumR += r * w
        sumG += g * w
        sumB += b * w
        sumX += (index % gridWidth) / gridWidth.toDouble() * w
        sumY += (index / gridWidth) / gridHeight.toDouble() * w
        if (v > 0.6f) lit++
    }
    if (weight <= 0.0) return null
    return CoverLight(
        highlight = Color((sumR / weight).toFloat(), (sumG / weight).toFloat(), (sumB / weight).toFloat()),
        centerX = (sumX / weight).toFloat().coerceIn(0f, 1f),
        centerY = (sumY / weight).toFloat().coerceIn(0f, 1f),
        emission = (lit.toFloat() / argbPixels.size).coerceIn(0f, 1f),
    )
}

private fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = max(la, lb)
    val darker = min(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * The general legibility guard: return [color] when it already clears [minRatio]
 * against [background], otherwise blend it toward [fallback] in tenths until it
 * does; if even [fallback] misses the bar on that background, finish toward
 * whichever pure extreme reads better so the invariant holds unconditionally
 * (the better extreme is always ≥5.6:1 — the black/white crossover sits at the
 * midpoint of the two).
 *
 * This is the single place the app enforces "an accent may tint a thing, but it
 * may never make that thing invisible". Monochromatic palettes (SAKURA and
 * friends) put `primary` and `surface` within a hair of each other in luminance,
 * so any accent-on-surface text or icon must pass through here.
 */
fun legibleOn(
    color: Color,
    background: Color,
    fallback: Color,
    minRatio: Double = 4.5,
): Color {
    if (contrastRatio(color, background) >= minRatio) return color
    for (step in 1..10) {
        val blended = lerp(color, fallback, step / 10f)
        if (contrastRatio(blended, background) >= minRatio) return blended
    }
    val anchor =
        if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
            Color.Black
        } else {
            Color.White
        }
    for (step in 1..10) {
        val blended = lerp(fallback, anchor, step / 10f)
        if (contrastRatio(blended, background) >= minRatio) return blended
    }
    return anchor
}

/**
 * [legibleOn] against the live palette surface, falling back to `onSurface`
 * (or `onSurfaceVariant` for the 3:1 large-text/icon tier) unless told otherwise.
 * The common case for accent-tinted labels, eyebrows, figures and icons.
 */
@Composable
fun rememberLegibleAccent(
    accent: Color,
    background: Color = FolioTheme.colors.surface,
    fallback: Color = FolioTheme.colors.onSurface,
    minRatio: Double = 4.5,
): Color = remember(accent, background, fallback, minRatio) {
    legibleOn(accent, background, fallback, minRatio)
}

/**
 * §13.3 contrast guard, non-negotiable: a cover may tint the hero but it may
 * never make the hero unreadable. A thin alias over [legibleOn] at the 4.5:1
 * body-text tier.
 */
internal fun guardCoverContrast(color: Color, surface: Color, fallback: Color): Color =
    legibleOn(color, surface, fallback, 4.5)

/**
 * Read-through the [accentCache]: [sample] runs at most once per path, and a
 * non-null result is stored before it is returned. That makes the cache the
 * process-lifetime single-sample-per-cover the §13.3 doc has always promised —
 * which matters now that every grid cell samples, because a `LazyGrid` recycles
 * its slots: without the write, a scrolled library would re-derive the same
 * hundred-odd colours on every pass.
 *
 * A null is deliberately *not* cached. It means "nothing in the sample
 * qualified", which is the white-cover case, and negative-caching it would put a
 * sentinel in a map whose values the contrast guard already trusts as colours.
 */
internal fun cachedOrSampledAccent(coverPath: String, sample: () -> Color?): Color? {
    accentCache[coverPath]?.let { return it }
    return sample()?.also { accentCache[coverPath] = it }
}

/**
 * The cache is process-lifetime by design, so the only reset is for tests that key
 * it. Mirrors `clearCoverCache()` in BookCover.kt.
 */
internal fun clearCoverAccentCache() = accentCache.clear()

/**
 * Dominant cover colour for the Home hero, or [fallback] when there is no
 * cover, the cover has not decoded yet, or nothing in the sample qualifies.
 * Samples the ALREADY CACHED bitmap downsampled to 32×32 — never re-decodes the
 * source file (the v1.0.24 216MB Canvas crash came from exactly that). The raw
 * sample is cached per path; the contrast guard runs per read against the live
 * palette surface, so a theme switch re-checks legibility.
 *
 * Callers that want to know whether the value is still the fallback can compare
 * it against the [fallback] they passed — that comparison is what drives the
 * halo's arrival ramp in [rememberCoverHaloStrength].
 */
@Composable
fun rememberCoverAccent(coverPath: String?, fallback: Color): Color {
    if (coverPath.isNullOrBlank()) return fallback
    // The fast path stays a synchronous map read: a warm cover costs one lookup
    // and one guard, no coroutine and no permit.
    accentCache[coverPath]?.let { return guardCoverContrast(it, FolioTheme.colors.surface, fallback) }
    var sampled by remember(coverPath) { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverPath) {
        val bitmap = awaitCoverBitmapForAccent(coverPath) ?: return@LaunchedEffect
        // Off the main thread. `sampleFromBitmap` reads the entire bitmap out into
        // an IntArray (`toPixelMap`), which is the one genuinely heavy thing this
        // app does with a cover — and `LaunchedEffect` runs on the composition's
        // own dispatcher, i.e. the UI thread, so leaving it inline put that read
        // inside a frame. It is worse than one frame's worth: a shelf composes a
        // dozen covers at once, their decodes finish together, and
        // `awaitCoverBitmapForAccent` polls on a 100ms tick, so the samples land
        // in the same one or two frames. Measured on device as a ~10ms
        // recomposition spike just after the shelf's covers finished decoding,
        // inside the Home→Library morph. The bitmap is immutable and already
        // cached, so hopping to Default is safe.
        //
        // The gate wraps the hop rather than sitting inside it: the permit has to
        // be held for the whole pixel read, and a sibling cell that finished the
        // same cover while we queued is answered by the cache re-check below
        // without touching the bitmap again.
        sampled = coverSampleGate.withPermit {
            withContext(Dispatchers.Default) {
                cachedOrSampledAccent(coverPath) { sampleFromBitmap(bitmap) }
            }
        }
    }
    val raw = sampled ?: return fallback
    return guardCoverContrast(raw, FolioTheme.colors.surface, fallback)
}

private fun sampleFromBitmap(bitmap: ImageBitmap): Color? {
    val grid = coverGridOf(bitmap) ?: return null
    return sampleCoverAccent(grid.pixels)
}

/** The sampled grid plus its own dimensions, so a position can be recovered from an index. */
private data class CoverGrid(val pixels: IntArray, val width: Int, val height: Int)

private fun coverGridOf(bitmap: ImageBitmap): CoverGrid? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    val stride = max(1, kotlin.math.sqrt(w.toDouble() * h / (32 * 32)).toInt())
    val pixels = sampleCoverGrid(bitmap, w, h, stride)
    return CoverGrid(pixels, (w + stride - 1) / stride, (h + stride - 1) / stride)
}

/** Process-lifetime, keyed by cover path, exactly like [accentCache]. */
private val lightCache = ConcurrentHashMap<String, CoverLight?>()

/**
 * The light this cover throws — its highlight colour, where its brightness sits, and
 * how much of it there is. Null when there is no cover or nothing in it was bright.
 *
 * Deliberately a second walk over the same grid rather than a change to
 * [rememberCoverAccent]'s flow: the walk is ~1024 ints once per cover behind the
 * same gate, and the accent path is under test. Sharing one pass would save
 * nanoseconds and put a tested function at risk.
 */
@Composable
fun rememberCoverLight(coverPath: String?): CoverLight? {
    if (coverPath.isNullOrBlank()) return null
    lightCache[coverPath]?.let { return it }
    var sampled by remember(coverPath) { mutableStateOf<CoverLight?>(null) }
    LaunchedEffect(coverPath) {
        val bitmap = awaitCoverBitmapForAccent(coverPath) ?: return@LaunchedEffect
        // Same gate as the accent: a shelf's covers must not sample at once.
        sampled = coverSampleGate.withPermit {
            withContext(Dispatchers.Default) {
                lightCache[coverPath] ?: coverGridOf(bitmap)?.let {
                    sampleCoverLight(it.pixels, it.width, it.height)
                }
            }
        }
        sampled?.let { lightCache[coverPath] = it }
    }
    return sampled
}

/**
 * The §13.3 sampling grid: every [stride]-th pixel of every [stride]-th row, row-major, as opaque
 * ARGB. Same points, same packing as the original read — only the way they are fetched changes.
 *
 * The shared implementation behind this had to copy the whole bitmap (`ImageBitmap.toPixelMap()`)
 * to answer ~1024 reads: ~21 MB per cover at the 2048 decode cap, and a shelf finishes a dozen
 * decodes inside the same frames, so the copies land together. Android reads one row at a time
 * into a reusable buffer instead.
 */
internal expect fun sampleCoverGrid(
    bitmap: ImageBitmap,
    width: Int,
    height: Int,
    stride: Int,
): IntArray

/** The whole-bitmap read, kept as the fallback and the desktop implementation. */
internal fun coverGridFromPixelMap(
    bitmap: ImageBitmap,
    width: Int,
    height: Int,
    stride: Int,
): IntArray {
    val pixelMap = bitmap.toPixelMap()
    val pixels = ArrayList<Int>(1024)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val c = pixelMap[x, y]
            pixels.add(
                (255 shl 24) or
                    ((c.red * 255f).toInt().coerceIn(0, 255) shl 16) or
                    ((c.green * 255f).toInt().coerceIn(0, 255) shl 8) or
                    (c.blue * 255f).toInt().coerceIn(0, 255)
            )
            x += stride
        }
        y += stride
    }
    return pixels.toIntArray()
}
