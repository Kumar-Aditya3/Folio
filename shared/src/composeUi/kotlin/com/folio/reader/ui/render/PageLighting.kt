package com.folio.reader.ui.render

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.lightDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The reader page's own light, as a CSS `background-image` stack.
 *
 * Every surface in this app is lit. [FolioAtmosphere] decides where a raised pane's
 * highlight falls and [FolioDaylight] decides where the room's sun is, and the shelf,
 * the bars and the sheets all read them — but the page a reader actually looks at for
 * two hours was a flat hex fill (`ReaderCss`'s `html,body{background:#paper}`), which
 * is why the reader can feel like a different product from its own library. This hands
 * the page the same light the app already computed, in the one place the page can be
 * reached: CSS, inside the browser, on both platforms.
 *
 * **Two layers, one anchor.** A *lift* — the surface's own rim light, brightest at the
 * sun-facing edge — and a *shade* that gathers in the outer band. Both are radial and
 * both are anchored at the same point, so they read as one lamp over one sheet rather
 * than two effects. The colours are `rimLight` and `rimShade`, both derived from the
 * palette: a generic black vignette is what makes a shade look like a photograph's
 * defect instead of a room's lighting.
 *
 * **Neither layer may move the ink's contrast.** The light is painted *under* the text,
 * which is the app's standing rule for effects — but a background you can darken is a
 * background you can darken *under the letters*, and the §12 floor is 7:1. So the
 * alphas are not constants, they are ceilings: each is halved until the paper as lit
 * still clears the floor against that reader theme's own ink, and a theme with no room
 * simply gets no light. This is why a deep sepia at midnight and a bright white at noon
 * can share one function.
 *
 * Cost: the root element's background propagates to the canvas, where `scroll` is
 * treated as `fixed`, so the light is anchored to the viewport and does not scroll,
 * does not need `background-attachment: fixed`, and promotes no composited layer. It is
 * the cheapest paint in the document. It is evaluated at load and at restyle, never on
 * a clock — §17's ambient swing would put a whole-chapter style recalc on a 10 Hz
 * timer, which is the exact cost the slow clock was invented to remove.
 */

/** Ceiling for the sun-facing lift, before the ink floor pulls it down. */
private const val PAGE_LIGHT_LIFT_ALPHA = 0.045f

/** An ARGB int as a [Color]. The reader theme speaks in ints; the atmosphere does not. */
internal fun argbColor(argb: Int): Color = Color(
    ((argb shr 16) and 0xFF) / 255f,
    ((argb shr 8) and 0xFF) / 255f,
    (argb and 0xFF) / 255f,
)

/** Ceiling for the outer shade, same. */
private const val PAGE_LIGHT_SHADE_ALPHA = 0.055f

/** §12.3's ink floor. The light may not be the reason a page fails it. */
private const val PAGE_LIGHT_INK_FLOOR = 7f

/**
 * Where the lamp sits, as percentages of the viewport.
 *
 * [FolioDaylight.lightDirection] returns the direction light *travels* across a
 * surface, with y negative meaning "from above" — so the sun-facing edge is
 * `centre + direction`, which is what [com.folio.reader.ui.components.daylightGradient]
 * already calls its `end`. The page has to agree with the bar above it or the room has
 * two suns.
 */
internal fun pageLightAnchor(daylight: FolioDaylight): Pair<Float, Float> {
    val (dx, dy) = daylight.lightDirection()
    return ((50f + dx * 50f).coerceIn(0f, 100f)) to ((50f + dy * 50f).coerceIn(0f, 100f))
}

/** Alpha compositing over an opaque paper, per channel. */
private fun over(base: Color, top: Color, alpha: Float) = Color(
    base.red + (top.red - base.red) * alpha,
    base.green + (top.green - base.green) * alpha,
    base.blue + (top.blue - base.blue) * alpha,
)

/**
 * WCAG's relative luminance, with the channels linearised.
 *
 * The rest of the app measures lightness with a fast sRGB-weighted average
 * (`luminanceOf` in FolioInk.kt), which is right for choosing between a colour's two
 * poles and wrong here: on a paper of 0.95 against an ink of 0.12 the weighted
 * average reports ~5.9:1 where the real ratio is above 15:1, so guarding the 7:1 floor
 * with it would reject almost every legal reader theme and switch the light off
 * everywhere. This is the measurement §12.3's floor is actually stated in, and the one
 * `ReaderCssThemeTest` asserts against.
 */
private fun channelLinear(v: Float) =
    if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

private fun relativeLuminance(c: Color) =
    0.2126f * channelLinear(c.red) + 0.7152f * channelLinear(c.green) + 0.0722f * channelLinear(c.blue)

private fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * The largest alpha ≤ [requested] that leaves [paper] as lit still clearing
 * [floor] against [ink]; 0 when even the smallest term would fail.
 *
 * Halving rather than solving: the blend is linear in alpha but the ratio is not, and
 * eight steps reach 0.4% of the request — far below what any panel can show — without
 * this file owning a colour-science derivation the rest of the app does not have.
 */
internal fun inkFloorRespectingAlpha(
    paper: Color,
    ink: Color,
    veil: Color,
    requested: Float,
    floor: Float = PAGE_LIGHT_INK_FLOOR,
): Float {
    var alpha = requested.coerceIn(0f, 1f)
    repeat(8) {
        if (alpha <= 0.0005f) return 0f
        if (contrastRatio(over(paper, veil, alpha), ink) >= floor) return alpha
        alpha *= 0.5f
    }
    return 0f
}

private fun rgba(c: Color, alpha: Float) =
    "rgba(${(c.red * 255).roundToInt()},${(c.green * 255).roundToInt()}," +
        "${(c.blue * 255).roundToInt()},${(alpha * 1000).roundToInt() / 1000f})"

/**
 * The `background-image` stack for [paper]/[ink] under the current [daylight], or
 * empty when the ink floor leaves no room for either term — in which case the page
 * stays exactly as flat as it is today, which is a correct outcome, not a degraded one.
 */
fun pageLightImage(
    daylight: FolioDaylight,
    atmosphere: FolioAtmosphere,
    paperArgb: Int,
    inkArgb: Int,
): String {
    val paper = argbColor(paperArgb)
    val ink = argbColor(inkArgb)
    val (x, y) = pageLightAnchor(daylight)
    val lift = inkFloorRespectingAlpha(paper, ink, atmosphere.rimLight, PAGE_LIGHT_LIFT_ALPHA)
    val shade = inkFloorRespectingAlpha(paper, ink, atmosphere.rimShade, PAGE_LIGHT_SHADE_ALPHA)
    if (lift <= 0f && shade <= 0f) return ""
    val anchor = "$x% $y%"
    return buildString {
        var first = true
        if (lift > 0f) {
            append(
                "radial-gradient(115% 115% at $anchor," +
                    "${rgba(atmosphere.rimLight, lift)} 0%," +
                    "${rgba(atmosphere.rimLight, 0f)} 46%)"
            )
            first = false
        }
        if (shade > 0f) {
            if (!first) append(',')
            append(
                "radial-gradient(135% 135% at $anchor," +
                    "${rgba(atmosphere.rimShade, 0f)} 58%," +
                    "${rgba(atmosphere.rimShade, shade)} 100%)"
            )
        }
    }
}
