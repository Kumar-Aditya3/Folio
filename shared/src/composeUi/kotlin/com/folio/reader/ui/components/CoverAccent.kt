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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** §13.3: raw sampled accents keyed by coverPath — one 32×32 sample per cover, process-lifetime. */
private val accentCache = ConcurrentHashMap<String, Color>()

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
 * Dominant cover colour for the Home hero, or [fallback] when there is no
 * cover, the cover has not decoded yet, or nothing in the sample qualifies.
 * Samples the ALREADY CACHED bitmap downsampled to 32×32 — never re-decodes the
 * source file (the v1.0.24 216MB Canvas crash came from exactly that). The raw
 * sample is cached per path; the contrast guard runs per read against the live
 * palette surface, so a theme switch re-checks legibility.
 */
@Composable
fun rememberCoverAccent(coverPath: String?, fallback: Color): Color {
    if (coverPath.isNullOrBlank()) return fallback
    accentCache[coverPath]?.let { return guardCoverContrast(it, FolioTheme.colors.surface, fallback) }
    var sampled by remember(coverPath) { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverPath) {
        val bitmap = awaitCoverBitmapForAccent(coverPath) ?: return@LaunchedEffect
        sampled = sampleFromBitmap(bitmap)
    }
    val raw = sampled ?: return fallback
    return guardCoverContrast(raw, FolioTheme.colors.surface, fallback)
}

private fun sampleFromBitmap(bitmap: ImageBitmap): Color? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    // Stride-sample ~1024 points straight off the cached bitmap's pixel map —
    // the 32×32-class downsample of §13.3 without any second decode or huge
    // canvas allocation (the v1.0.24 crash class).
    val stride = max(1, kotlin.math.sqrt(w.toDouble() * h / (32 * 32)).toInt())
    val pixelMap = bitmap.toPixelMap()
    val pixels = ArrayList<Int>(1024)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
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
    return sampleCoverAccent(pixels.toIntArray())
}
