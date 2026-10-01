package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.folio.reader.ui.components.CoverLight
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The lamp — one light source in the room, strong enough to see.
 *
 * The pass before this one coloured the theme's own surfaces, and every number in
 * that path had to stay small because ink sits on them: three thousand lines of
 * careful arithmetic that was not allowed to be loud. A lamp is different in kind. It
 * is light laid into the space *under* the content, so it can peak where a surface
 * never could, and the only thing holding it back is the same floor that governs
 * everything else — the ink's own contrast, measured on the finished page.
 *
 * **Deliberately not a shader.** The whole effect is one radial, which Canvas draws
 * exactly on every API from 24 up, on desktop, in previews and in a unit test. AGSL
 * earns its place where per-pixel dispersion matters — the refraction gel — and here it
 * would only have made the headline feature invisible on most of a `minSdk` 24 install
 * base.
 *
 * **There is no dark side, and that is the point.** An earlier draft deepened the field
 * away from the source, reasoning that a bloom alone reads as a cast. It did the
 * opposite: the darkening ramped across the *same radius* as the bloom, so the glow had
 * a lit centre and a shaded limb, which is how you shade a ball. On device it read as
 * an object standing in the room. A light is allowed to have no edge at all, so the
 * falloff tails flat into the field and nothing darkens with it.
 *
 * **Cost.** One `drawCircle` per tick of the house slow clock, read in the draw
 * phase, so the light breathes and travels without recomposing anything — the same
 * discipline [com.folio.reader.ui.components.folioField]'s pools follow.
 *
 * **Rule 19.** Reduce-motion freezes the breath and keeps the light: a lamp at rest is
 * this effect's static form, not its absence. That deliberately differs from
 * [folioAmbientShader], which stands the whole film down.
 */

/**
 * A lamp resolved into the field's own pixels: where, how far, and how bright.
 * Produced by [lampOf] in the draw phase.
 */
data class FolioLampLight(
    val color: Color,
    val center: Offset,
    val radius: Float,
    /** Bloom alpha at the source. */
    val alpha: Float,
)

/** How much of the cover's colour is replaced by its own fully saturated hue. */
private const val LAMP_SATURATION_PULL = 0.35f

/** A dark room wants a hotter source; on paper the same lift is glare. */
internal const val LAMP_VALUE_LIFT_DARK = 0.22f
internal const val LAMP_VALUE_LIFT_LIGHT = 0.12f

/**
 * The lamp's reach, as a fraction of the field's *short* side.
 *
 * 0.62 of the diagonal washed the page and evened out the field gradient and the three
 * pools that give a pack its character. 0.34 had a boundary you could see, so it read
 * as an object. 0.50 fixed that but threw a halo a full screen-width across — "the
 * entire thing lights up like a bulb". The edge was never the size, it was the
 * coincident dark limb, and [lampFalloff] already removes that. A neighbourhood.
 */
private const val LAMP_REACH = 0.36f

/**
 * How far the cover's own colour is allowed to pull the light before the theme's
 * primary takes it back. A dilution the theme outvotes, not a handover — and it is
 * only the *first* of two throttles: [lampColor] then leans this result toward the
 * theme again by the pack's [FolioLight.hueRange], so the cover's real presence is
 * `LAMP_COVER_SHARE × hueRange`. At the old 0.15 that product was a few hundredths,
 * and the audit (`VisualAuditTest`, "lamp iso") measured two opposite covers landing
 * on the same light — median ΔE 0.72, below perception — so the per-book lamp, the
 * whole point of the lamp, did nothing. Raised so a lending pack's light actually
 * carries the book; a pack that lends nothing ([FolioLight.hueRange] = 0) is still
 * untouched because that second mix zeroes the cover out regardless of this value,
 * and the solved peak still caps the bloom so ink never drops below its floor.
 */
private const val LAMP_COVER_SHARE = 0.5f

/** How far the cover's brightness centroid may move the seat, as a fraction of it. */
private const val LAMP_CENTROID_REACH = 0.22f

/**
 * Light a cover with none still gives, so no book leaves the room entirely unlit — and
 * read the other way, how much the cover is *denied*. A torchlit jacket may raise the
 * lamp only from this floor to 1.0, so the book modulates the room by a quarter rather
 * than by more than double. The range was 0.45..1.0, which is how one cover became the
 * loudest thing on the page.
 */
private const val LAMP_EMISSION_FLOOR = 0.75f

/** Breath amplitude, as a fraction of the peak. */
private const val LAMP_BREATH = 0.12f

/** One breath of the lamp. Slower than the room's own light, on purpose. */
private const val LAMP_PERIOD_MS = 61_000L

/** The least chroma a lamp carries, so a pale cover still lights the room in colour. */
private const val LAMP_MIN_CHROMA = 0.45f

/**
 * The lamp's colour: **the theme's own colour**, which the cover may pull a bounded
 * distance toward its hue.
 *
 * This is what stops the light overwriting the theme. `Toxic Lime` glows acid green
 * and `Abyss` glows teal because that is what their `primary` already is; the book
 * being read can lean that colour toward itself, up to [hueRange] of the way, and a
 * pack that wants its colour untouched sets the range to zero and the cover simply
 * does not get a vote.
 *
 * The chroma work after that is the correction the first lamp needed most. A cover's
 * sampled average is a pale grey for a pale cover, and a pale light stays pale at any
 * alpha — which is how a correctly wired lighting system showed nothing. So the result
 * is pulled toward the fully saturated form of its own hue, lifted the way an
 * incandescent source is, and given a floor it cannot fall below.
 */
fun lampColor(
    theme: Color,
    cover: Color?,
    hueRange: Float,
    atmos: FolioAtmosphere,
): Color {
    val borrowed = if (cover == null) theme else mixG(theme, cover, hueRange.coerceIn(0f, 1f))
    val pure = pureHueOf(borrowed)
    val saturated = mixG(borrowed, pure, LAMP_SATURATION_PULL)
    val source = liftG(
        saturated,
        if (atmos.isDark) LAMP_VALUE_LIFT_DARK else LAMP_VALUE_LIFT_LIGHT,
    )
    // A colour with no hue has no direction to be pulled in, and stays as it was.
    if (chromaOf(borrowed) <= 0.02f) return source
    return if (chromaOf(source) >= LAMP_MIN_CHROMA) source else mixG(source, pure, 0.85f)
}

private fun chromaOf(c: Color): Float =
    maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

/**
 * The same hue at full saturation and value — where [lampColor] pulls a washed-out
 * cover toward. Done on the channels rather than through HSV so the degenerate case is
 * honest: a cover with no hue has no direction to be pulled in, and stays as it was.
 */
private fun pureHueOf(c: Color): Color {
    val mx = maxOf(c.red, c.green, c.blue)
    if (mx <= 0.001f) return c
    val r = c.red / mx
    val g = c.green / mx
    val b = c.blue / mx
    val mn = minOf(r, g, b)
    if (mn >= 0.999f) return c
    val k = 1f / (1f - mn)
    return Color(
        red = ((r - mn) * k).coerceIn(0f, 1f),
        green = ((g - mn) * k).coerceIn(0f, 1f),
        blue = ((b - mn) * k).coerceIn(0f, 1f),
    )
}

/**
 * The strongest lamp this page can carry, solved rather than granted.
 *
 * The first version measured each theme's spare contrast at rest and scaled the lamp
 * by it. That is self-defeating: the bloom's own brightening is exactly what spends the
 * headroom being measured, so a deep dark sitting at 14.7:1 was handed full strength
 * and came to 5.06:1 under the light. So the cap is solved against the result — the
 * largest amount of this colour that can be laid over the lit page while the body ink
 * still clears the floor — by bisection, since the ratio falls monotonically as more
 * of a brighter source is mixed in.
 *
 * [base] is the worst of the field's two endpoints: whichever is already closer to the
 * ink, because that is the one the light threatens first.
 */
private fun solvedPeak(base: Color, ink: Color, over: Color, wanted: Float): Float {
    if (contrastRatio(mixG(base, over, wanted), ink) >= LAMP_CONTRAST_FLOOR) return wanted
    var lo = 0f
    var hi = wanted
    repeat(12) {
        val mid = (lo + hi) / 2f
        if (contrastRatio(mixG(base, over, mid), ink) >= LAMP_CONTRAST_FLOOR) lo = mid else hi = mid
    }
    return lo
}

/**
 * The ratio the lit page must hold for body ink — a touch above §12.3's 7:1 so the
 * breath's peak, not just its average, stays clear.
 */
private const val LAMP_CONTRAST_FLOOR = 7.4f

private fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (((maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05))).toFloat()
}

/**
 * The lamp for one field. Null only when the field has not been measured — a caller
 * with no lamp draws exactly what it drew before this file existed.
 *
 * The seat is the pack's [FolioLight.sourceX]/[FolioLight.sourceY]; the cover's
 * measured centre, [at], only pulls the light toward itself by [FolioLight.lean], and
 * null leaves it exactly where the theme put it.
 */
fun lampOf(
    atmos: FolioAtmosphere,
    authored: FolioLight,
    themeColor: Color,
    cover: Color?,
    at: Offset?,
    field: IntSize,
    phase: Float,
    coverLight: CoverLight? = null,
): FolioLampLight? {
    if (field.width <= 0 || field.height <= 0) return null
    val w = field.width.toFloat()
    val h = field.height.toFloat()
    // The pack's seat, with the cover allowed to pull the light toward itself by the
    // pack's own lean. An unmeasured cover simply leaves it where the theme put it.
    val seat = Offset(w * authored.sourceX, h * authored.sourceY)
    val center = at?.let { seat + (it - seat) * authored.lean.coerceIn(0f, 1f) } ?: seat
    // The artwork decides where its own light is. A cover lit from the lower-left is
    // not a cover that glows from its middle, so the centroid of its brightness
    // offsets the seat — still bounded by the pack's lean, which is the theme's say
    // in how much of itself it lets the book move.
    val local = coverLight?.let {
        (it.centerX - 0.5f) to (it.centerY - 0.5f)
    }
    val placed = if (center == null || local == null) center else Offset(
        center.x + local.first * min(w, h) * LAMP_CENTROID_REACH * authored.lean,
        center.y + local.second * min(w, h) * LAMP_CENTROID_REACH * authored.lean,
    )
    // Scaled to the short side, so the glow stays a neighbourhood of the cover
    // rather than a wash over the page.
    val radius = min(w, h) * LAMP_REACH
    // The highlight, not the average, when the sampler found one: the average of a
    // cover is mostly its background, and a light made from it is the dull thing this
    // effect kept being. Still diluted toward the theme before it pulls any hue, so
    // the book is carried and never replaces the theme showing it.
    val source = coverLight?.highlight ?: cover
    val diluted = source?.let { mixG(themeColor, it, LAMP_COVER_SHARE) }
    val color = lampColor(themeColor, diluted, authored.hueRange, atmos)
    val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
    // The ground the bloom is laid onto, sampled where the bloom is strongest: the
    // lamp's own centre. The worse of the two endpoints is not enough, because the
    // pools sit on top of the ramp and can lift a point past both — which is how
    // Abyss put its own body ink at 6.96:1 while every endpoint individually passed.
    val groundPoint = placed ?: seat
    val ground = fieldColorAtPoint(lit, w, h, groundPoint.x, groundPoint.y, shift = 0f)
    // A flat matte jacket has almost no light to give; a torchlit one has a lot. The
    // cover's own emission decides, with a floor so no book leaves the room unlit.
    val emission = LAMP_EMISSION_FLOOR +
        (1f - LAMP_EMISSION_FLOOR) * (coverLight?.emission ?: 0f)
    val wanted = authored.strength(atmos.isDark) * emission * (1f + LAMP_BREATH)
    val breath = 1f - LAMP_BREATH * (1f - sin(phase)) * 0.5f
    val alpha = solvedPeak(ground, atmos.ink, color, wanted) * breath
    return FolioLampLight(
        color = color,
        center = groundPoint,
        radius = radius,
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * Knots across the lamp's reach. [folioLamp] hands Canvas one colour per knot and
 * [lampAlphaAt] interpolates linearly between the same ones, so the screen and the
 * guard test run one arithmetic by construction rather than by claim.
 */
private const val LAMP_KNOTS = 12

/**
 * How much of the source survives at [d] — a fraction of the lamp's reach.
 *
 * A smoothstep of what is left, not the straight line to zero the first lamp drew.
 * A linear ramp *ends* at a boundary you can see, and a bright disc with a visible
 * edge is an object sitting in the room; this leaves the source flat and arrives flat,
 * so there is no limb anywhere for the eye to trace.
 */
internal fun lampFalloff(d: Float): Float {
    if (d >= 1f) return 0f
    if (d <= 0f) return 1f
    val s = 1f - d
    return s * s * (3f - 2f * s)
}

private fun lampAlphaAt(d: Float): Float {
    if (d >= 1f) return 0f
    if (d <= 0f) return 1f
    val step = (d * LAMP_KNOTS).toInt().coerceAtMost(LAMP_KNOTS - 1)
    val lo = step.toFloat() / LAMP_KNOTS
    val hi = (step + 1f) / LAMP_KNOTS
    val t = (d - lo) / (hi - lo)
    return lampFalloff(lo) * (1f - t) + lampFalloff(hi) * t
}

/**
 * The lamp's contribution to the page at one point, after the field and its pools.
 *
 * Composites identically to the radial [folioLamp] draws, so the contrast floor is
 * asserted against the *lit* page rather than the page before the light was switched
 * on — and against the page that is actually shipped, which the discarded vignette
 * this replaces never was.
 */
fun applyLamp(over: Color, lamp: FolioLampLight, x: Float, y: Float): Color {
    val dx = x - lamp.center.x
    val dy = y - lamp.center.y
    val d = sqrt(dx * dx + dy * dy) / lamp.radius
    val a = lamp.alpha * lampAlphaAt(d)
    return if (a <= 0f) over else mixG(over, lamp.color, a)
}

/**
 * Draws the lamp. Applied to the app's ground plane over [com.folio.reader.ui.components.folioField]
 * and its pools and under every screen's content, so the light is what the content
 * sits in.
 */
@Composable
fun Modifier.folioLamp(lamp: FolioLamp): Modifier {
    val atmos = FolioTheme.atmosphere
    val motion = rememberMotionEnabled()
    val phases = rememberSlowPhases(listOf(LAMP_PERIOD_MS))
    return this.drawWithCache {
        onDrawBehind {
            val field = IntSize(size.width.toInt(), size.height.toInt())
            val lit = lampOf(
                atmos = atmos,
                authored = lamp.authored,
                themeColor = lamp.themeColor,
                cover = lamp.light.value,
                at = lamp.at.value,
                field = field,
                phase = if (motion) phases.value.firstOrNull() ?: 0f else 0f,
                coverLight = lamp.coverLight.value,
            ) ?: return@onDrawBehind
            val center = lit.center
            // One colour per knot, spread evenly across the radius — which is exactly
            // how Brush.radialGradient interpolates them, and the same piecewise curve
            // lampAlphaAt evaluates for the guard test.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = (0..LAMP_KNOTS).map {
                        lit.color.copy(alpha = lit.alpha * lampFalloff(it.toFloat() / LAMP_KNOTS))
                    },
                    center = center,
                    radius = lit.radius,
                ),
                radius = lit.radius,
                center = center,
            )
        }
    }
}

/**
 * The lamp's anchor: which cover the room is lit by.
 *
 * A screen cannot hand a position *down* to the ground plane it stands on any more
 * than it could hand a colour up, so the featured cover writes its own centre here and
 * the plane reads it in the draw phase. Modelled on [folioPressFocalOrigin], the only
 * existing seam where one node publishes a position for another to draw with.
 */
class FolioLamp internal constructor(
    /** The colour being lit with, already crossfaded by [FolioAmbientTint]. */
    val light: State<Color?>,
    /** The anchor's centre in the field's pixels; null until something measures. */
    val at: State<Offset?>,
    /** The theme's authored light: seat, strength, depth, and how much hue it lends. */
    val authored: FolioLight,
    /** The theme's own colour, which is what the lamp glows unless the book leans it. */
    val themeColor: Color,
    /** What the cover's own artwork says its light is: colour, position, how much. */
    val coverLight: State<CoverLight?>,
)

/**
 * Builds the holder. Public because the app root that owns it is in another module
 * and the constructor is deliberately not.
 */
@Composable
fun rememberFolioLamp(
    light: State<Color?>,
    at: State<Offset?>,
    authored: FolioLight,
    themeColor: Color,
    coverLight: State<CoverLight?>,
): FolioLamp = remember(light, at, authored, themeColor, coverLight) {
    FolioLamp(light, at, authored, themeColor, coverLight)
}

/** Where a screen reports the cover's own light, for the root's lamp to consume. */
val LocalFolioCoverLight = compositionLocalOf<MutableState<CoverLight?>?> { null }

/**
 * Declares that this screen's room is lit by [light] — the brightness centroid,
 * highlight and emission read off the featured cover itself. Same upward-write shape
 * as [FolioAmbientSource], for the same reason: the ground plane is an ancestor, and
 * a CompositionLocal only flows down.
 */
@Composable
fun FolioCoverLightSource(light: CoverLight?) {
    val sink = LocalFolioCoverLight.current ?: return
    LaunchedEffect(light) { sink.value = light }
}

/** Provided at the app root. Null means no lamp, and an un-provided tree is unchanged. */
val LocalFolioLamp = compositionLocalOf<FolioLamp?> { null }

/**
 * Marks this node as the room's light source: its centre becomes the lamp's anchor.
 * Applied to the hero plate, the featured shelf tiles and the Stats plate — the last
 * writer wins, which is what makes the light move when the tab does.
 */
@Composable
fun Modifier.folioLampSource(): Modifier {
    val anchor = LocalFolioLampAnchor.current ?: return this
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.size
        anchor.value = coordinates.positionInRoot() +
            Offset(bounds.width * 0.5f, bounds.height * 0.5f)
    }
}

/**
 * The write end of the anchor, kept separate from [FolioLamp] so the plane can read a
 * state that only the source node owns.
 */
val LocalFolioLampAnchor = compositionLocalOf<MutableState<Offset?>?> { null }
