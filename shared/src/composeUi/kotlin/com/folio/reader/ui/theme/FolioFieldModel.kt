package com.folio.reader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The room as data.
 *
 * [folioField] used to own this arithmetic inline, which meant the page's colour
 * could only ever be *described by* the code that draws it. Two things now need
 * the closed form of the backdrop instead of its raster: the refraction gel,
 * which has to bend what is behind a card rather than what the card contains, and
 * every chrome surface that should pick up the light of the book being read
 * rather than stay a constant grey. So the field's model lives here and each
 * renderer samples it — one source, no drift between them (Rule 3).
 *
 * Two invariants govern everything below, and both exist because the environment
 * is allowed real amplitude for the first time:
 *
 *  - **Hue moves, luminance does not.** [fieldColors] tints through [tintFill],
 *    which pins the result's lightness back to the base's. Text sitting directly
 *    on the field therefore keeps the contrast ratio it had, because a WCAG ratio
 *    is a statement about luminance. The room may become any colour the cover is;
 *    it may not become brighter under ink.
 *  - **The bottom only ever deepens.** [fieldColors] darkens the lower endpoint as
 *    it injects colour, which is what makes a cover's hue read as a lamp in a dark
 *    room rather than as more brown — and darkening can only *raise* contrast for
 *    the light ink a dark field carries.
 *
 * Everything here is pure and platform-neutral, so `desktopTest` can pin it across
 * all [AppPalette] entries. The AGSL mirror in `GlassShader.android.kt` samples the
 * same ramp; the stop table is generated here rather than passed as two endpoints
 * precisely so the shader never has to reproduce Oklab colour math to agree.
 */

/**
 * One ambient pool's resting geometry as fractions of the field, plus the
 * horizontal side it represents so daylight knows which pool the sun is on.
 * Centres sit outside the bounds and radii are large multiples of the width so no
 * pool ever shows an edge — unchanged from before; daylight only drifts them and
 * re-weights their alpha.
 */
data class DaylightPool(
    val fx: Float,
    val fy: Float,
    val fr: Float,
    val index: Int,
    val sideX: Float,
)

val DAYLIGHT_POOLS = listOf(
    DaylightPool(-0.15f, -0.10f, 1.15f, 0, -1f),
    DaylightPool(1.10f, 0.28f, 0.95f, 1, +1f),
    DaylightPool(0.35f, 1.15f, 1.30f, 2, 0f),
)

/**
 * How far the pools drift toward the anti-sun edge, as a fraction of width. Small
 * on purpose: this shifts the page's temperature, it does not slide the
 * background. Scaled by intensity in the caller, so at night there is no drift.
 */
internal const val POOL_DRIFT = 0.12f

/**
 * How much the far pool recedes while the sun-side one holds. The facing term is
 * capped by [poolAlphaAt] so no alpha ever exceeds the field's own ceiling —
 * daylight may dim a pool, never brighten it past its design.
 */
internal const val POOL_FACING = 0.45f

/**
 * The most any pool may be worth once the cover's colour is folded in, whatever
 * the tint strength asks for. [FolioAtmosphere.poolAlpha] is the design ceiling
 * for an untinted room; this is the ceiling for a lit one. Past it the pools stop
 * being air and start being a painted background, which is the exact look the
 * whole atmosphere layer exists to avoid.
 */
internal const val POOL_ALPHA_CEILING = 0.24f

/** How much of [fieldColors]' tint goes into pool alpha rather than into hue. */
private const val POOL_ALPHA_GAIN = 0.5f

/** Extra deepening of the lower endpoint, as a fraction of the tint strength. */
private const val BOTTOM_EXTRA_DEEPEN = 0.16f

/**
 * Each pool's share of the cover's hue, largest first. The three pools must not
 * all converge on the cover colour: a room lit by one lamp still has three
 * surfaces catching it differently, and three distinct hues is what keeps the
 * field reading as *light* rather than as a flat fill with a tint dial on it.
 */
private val POOL_TINT_SHARE = listOf(0.78f, 0.46f, 0.30f)

/**
 * The share of the field tint a bar's glass picks up. Lower than the field's own
 * because a bar is a surface, not the room: it should look like the same room seen
 * through the same window, not like the room painted again.
 */
private const val BAR_TINT_SHARE = 0.55f

/**
 * The field's colour at rest, before any of the day's or the reader's influence:
 * two endpoints and the pools between them. Everything derivable once per palette
 * or per cover change lives here, so the per-frame draw path only does geometry.
 */
@Immutable
data class FolioFieldColors(
    val top: Color,
    val bottom: Color,
    val poolColors: List<Color>,
    val poolAlpha: Float,
)

/** The palette's own room, exactly as [FolioAtmosphere] describes it. */
fun fieldColors(atmos: FolioAtmosphere): FolioFieldColors = FolioFieldColors(
    top = atmos.fieldTop,
    bottom = atmos.fieldBottom,
    poolColors = atmos.pools,
    poolAlpha = atmos.poolAlpha,
)

/**
 * The room lit by [tint] — the colour of what is being read.
 *
 * Null or zero [strength] returns the untinted field byte-for-byte, which is what
 * an un-provided tree, a preview and every existing caller still get.
 *
 * **What governs the amplitude, and why it changed.** The first version of this
 * tinted through [tintFill], which restores the base's lightness — a blanket pin
 * that made the feature invisible. A pale cover *is* a low-chroma colour once you
 * drag it down to a dark room's lightness, so the book arrived and flattened back
 * into the brown it replaced. The floor is what should govern this, not a pin: the
 * room may brighten under a bright cover, and the limit on that is the lightness at
 * which the palette's own body ink still clears §12.3. So the mix below is a plain
 * per-channel one, which keeps the cover's saturation and lightness, and the result
 * is then clamped into the band the ink can survive.
 *
 * The clamp bounds the *endpoints*. The pools are painted over them, so the
 * composite is what the guards measure — [fieldColorAtPoint] exists precisely so a
 * test can assert the colour a reader actually gets rather than the colour asked
 * for.
 */
fun fieldColors(
    atmos: FolioAtmosphere,
    tint: Color?,
    strength: Float,
): FolioFieldColors {
    if (tint == null || strength <= 0f) return fieldColors(atmos)
    val s = strength.coerceIn(0f, 1f)
    // Margin under the floor: the pools sit on top of these endpoints and spend some
    // of the headroom, so the endpoints are held to the lightness that leaves the
    // composite clear of 7:1 rather than exactly at it.
    val limit = inkFieldLimit(atmos.ink, INK_FLOOR_WITH_MARGIN, atmos.fieldTop)
    return FolioFieldColors(
        top = clampToInk(mixG(atmos.fieldTop, tint, s), limit),
        bottom = clampToInk(
            deepen(mixG(atmos.fieldBottom, tint, s), s * BOTTOM_EXTRA_DEEPEN), limit,
        ),
        // The pools are painted *over* the endpoints at up to POOL_ALPHA_CEILING, so
        // an unclamped pool spends the margin the endpoints were held to and wins:
        // a bright cover hue at 0.24 alpha lifted a dark field's composite ink ratio to
        // 6.53:1 while both endpoints individually passed. They get the same bound.
        poolColors = atmos.pools.mapIndexed { i, pool ->
            clampToInk(
                mixG(pool, tint, s * (POOL_TINT_SHARE.getOrNull(i) ?: 0.25f)), limit
            )
        },
        poolAlpha = (atmos.poolAlpha * (1f + POOL_ALPHA_GAIN * s))
            .coerceAtMost(POOL_ALPHA_CEILING),
    )
}

/**
 * The floor the endpoints and pools are held to — deliberately above §12.3's 7:1.
 *
 * The pools sit on top of the endpoints at up to [POOL_ALPHA_CEILING] of their own
 * colour, so whatever the endpoints clear, the composite gives some of it back. The
 * measured relationship is that a full pool leaves about 0.768 of the endpoint ratio,
 * which is how a held-to-8.5 field came in at 6.53:1. Eleven clears seven with the
 * pools at their ceiling and still leaves the room genuinely lit.
 */
private const val INK_FLOOR_WITH_MARGIN = 11.0

/** A field luminance range, in WCAG relative luminance, that keeps the ink legible. */
private class InkFieldLimit(val min: Float, val max: Float)

/**
 * How bright or dark the field may be for [ink] to still clear [floor] against it.
 *
 * Only one end is ever finite: light ink on a dark field is threatened by the room
 * getting *brighter*, dark ink on paper by it getting *darker*, and the other
 * direction is free. Which side [ink] is on is decided against the untinted [field],
 * so a palette that ships thin gets a tight range and a deep dark gets a wide one —
 * the amplitude is bounded by the theme's own headroom rather than by one number
 * chosen for all 36 of them.
 */
private fun inkFieldLimit(ink: Color, floor: Double, field: Color): InkFieldLimit {
    val l = relativeLuminance(ink)
    return if (l > relativeLuminance(field)) {
        InkFieldLimit(0f, (((l + 0.05) / floor) - 0.05).coerceAtLeast(0.0).toFloat())
    } else {
        InkFieldLimit((((l + 0.05) * floor) - 0.05).coerceAtMost(1.0).toFloat(), 1f)
    }
}

/**
 * Brings [c] into [limit] by scaling its channels together.
 *
 * A scale, not a mix: the hue and the chroma the cover brought through survive it and
 * only the lightness moves. That is what the old per-channel pin could not do —
 * restoring a dark field's lightness by mixing back toward the base is exactly how a
 * pale cover's colour got flattened into the room's own brown.
 *
 * Relative luminance rises monotonically with the scale factor, so this bisection
 * converges. It runs when the room's colour changes, not per frame.
 */
private fun clampToInk(c: Color, limit: InkFieldLimit): Color {
    val l = relativeLuminance(c).toFloat()
    if (l in limit.min..limit.max) return c
    var lo = 0f
    var hi = 4f
    val target = if (l > limit.max) limit.max else limit.min
    repeat(14) {
        val mid = (lo + hi) / 2f
        if (relativeLuminance(scaled(c, mid)).toFloat() > target) hi = mid else lo = mid
    }
    return scaled(c, (lo + hi) / 2f)
}

private fun scaled(c: Color, k: Float): Color = Color(
    red = (c.red * k).coerceIn(0f, 1f),
    green = (c.green * k).coerceIn(0f, 1f),
    blue = (c.blue * k).coerceIn(0f, 1f),
)

/** WCAG relative luminance — the linearised one, not the cheap weighting. */
internal fun relativeLuminance(c: Color): Double {
    fun ch(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

/**
 * The field's own ramp at [y] (0 at the top of the field, 1 at the bottom).
 *
 * Per-channel sRGB, not Oklab, because that is what `Brush.verticalGradient`
 * interpolates when it draws the two-stop wash: a Kotlin mirror that picked a
 * different colour space would disagree with the pixels on screen, which is the
 * one failure this file exists to prevent.
 */
fun fieldColorAt(colors: FolioFieldColors, y: Float): Color =
    mixG(colors.top, colors.bottom, y.coerceIn(0f, 1f))

/** The sun's horizontal pull, scaled by intensity. At night this is 0. */
fun fieldDrift(shift: Float): Float = -shift * POOL_DRIFT

/**
 * A pool's alpha where the sun is. Holds at [FolioFieldColors.poolAlpha] on the
 * lit side and recedes on the far side; the ceiling is the field's own design, so
 * daylight can never lift a pool past what the atmosphere asked for.
 */
fun poolAlphaAt(poolAlpha: Float, sideX: Float, shift: Float): Float =
    (poolAlpha * (1f + POOL_FACING * sideX * shift)).coerceIn(0f, poolAlpha)

/**
 * A pool's centre as fractions of the field: its resting position, pushed by the
 * sun's drift and by §17's ambient displacement. Centres stay outside the bounds
 * and radii stay large multiples of the width, so no pool ever shows an edge.
 */
fun poolCenterAt(
    pool: DaylightPool,
    drift: Float,
    driftX: Float,
    driftY: Float,
): Pair<Float, Float> =
    (pool.fx + drift + driftX) to (pool.fy + driftY)

/**
 * The exact colour the field renders at one point, gradient and every pool
 * composited in draw order.
 *
 * `Brush.radialGradient` fades a colour to `Color.Transparent` per channel, which
 * composites identically to a linear alpha falloff of the same colour over the
 * same background — so this is the pixels, not an approximation of them. It is
 * what `DesignSystemTest` measures ink against, and what the gel's taps would
 * sample in a build without AGSL.
 */
fun fieldColorAtPoint(
    colors: FolioFieldColors,
    width: Float,
    height: Float,
    x: Float,
    y: Float,
    shift: Float,
    driftX: Float = 0f,
    driftY: Float = 0f,
    dayTint: Color? = null,
    dayStrength: Float = 0f,
    lamp: FolioLampLight? = null,
): Color {
    if (width <= 0f || height <= 0f) return colors.top
    val ramp = fieldColorAt(colors, y / height)
    var r = ramp.red
    var g = ramp.green
    var b = ramp.blue
    DAYLIGHT_POOLS.forEach { pool ->
        val color = colors.poolColors.getOrNull(pool.index) ?: return@forEach
        val alpha = poolAlphaAt(colors.poolAlpha, pool.sideX, shift)
        if (alpha <= 0f) return@forEach
        val (cx, cy) = poolCenterAt(pool, fieldDrift(shift), driftX, driftY)
        val radius = pool.fr * width
        val dx = x - cx * width
        val dy = y - cy * height
        val d = sqrt(dx * dx + dy * dy) / radius
        if (d >= 1f) return@forEach
        val a = alpha * (1f - d)
        r = r * (1f - a) + color.red * a
        g = g * (1f - a) + color.green * a
        b = b * (1f - a) + color.blue * a
    }
    val page = Color(r, g, b)
    // The day laid over the room: the same <= DAYLIGHT_WASH_ALPHA_MAX wash folioField
    // draws as its final pass, mirrored here so a sampled tap reads the lit-by-time
    // colour and not the pre-dawn one. `dayStrength` already carries the x intensity
    // from the caller, so night is a no-op; the ceiling is re-clamped in case a caller
    // hands over more than the envelope allows.
    val lit = if (dayTint != null && dayStrength > 0f) {
        mixG(page, dayTint, dayStrength.coerceAtMost(DAYLIGHT_WASH_ALPHA_MAX))
    } else {
        page
    }
    return if (lamp == null) lit else applyLamp(lit, lamp, x, y)
}

/**
 * The glass a bar is made of in *this* room.
 *
 * [FolioAtmosphere.barGlass] is derived at palette time and the ambient tint is a
 * runtime value, so the tint cannot be folded in there — a bar asks here instead.
 * Alpha is carried through untouched, so a lit room changes a bar's colour and
 * never its weight; the 0.22–0.42 glass window `DesignSystemTest` pins stays
 * exactly where the atmosphere put it.
 *
 * **What made this possible.** It could not be done while `folioBarGlass` encoded
 * "nothing lit" as `tint == atmos.barGlass`: mixing toward the lit field is not then a
 * no-op, so an unlit tree — desktop, every preview, every test — would have arrived
 * permanently lit. Callers now read `LocalFolioAmbientTint.current?.requested`, so null
 * means unlit and returns the palette's own glass byte-for-byte. Do not reintroduce a
 * non-null fallback here.
 */
fun barGlassFor(atmos: FolioAtmosphere, tint: Color?, strength: Float): Color {
    val glass = atmos.barGlass
    if (tint == null || strength <= 0f) return glass
    return mixG(glass, fieldColors(atmos, tint, strength).top, BAR_TINT_SHARE)
        .copy(alpha = glass.alpha)
}

/**
 * The ground under the status icons, in *this* room.
 *
 * [FolioAtmosphere.barScrim] is derived at palette time from the untinted
 * background, so the moment the field takes a book's colour the strip beneath the
 * icons becomes a different object from the page it sits on — which is exactly what
 * made the top of the screen read as a separate band rather than the top of the
 * room. Same treatment as [barGlassFor]: it follows the colour the page actually
 * wears, and only the alpha stays put.
 */
fun scrimFor(atmos: FolioAtmosphere, tint: Color?, strength: Float): Color {
    val scrim = atmos.barScrim
    if (tint == null || strength <= 0f) return scrim
    return mixG(scrim, fieldColors(atmos, tint, strength).top, SCRIM_TINT_SHARE)
        .copy(alpha = scrim.alpha)
}

/** The scrim is a piece of the page, so it follows the room almost completely. */
private const val SCRIM_TINT_SHARE = 0.9f

/**
 * The handle to the room a surface needs in order to paint *it* instead of
 * guessing at it — the refraction gel, which bends the page behind a card and so
 * has to know what the page looks like at each of its own pixels.
 *
 * The three things that only ever move in the draw phase are carried as `State`s,
 * not as values: the ambient colour (which crossfades), §17's living light (which
 * breathes on a 100ms tick) and the field's own pixel size. Resolved by [colors]
 * and the pool helpers at draw time, so a refracting surface costs one drawing
 * pass and zero recompositions, exactly like `folioField` does. A holder built from
 * plain values would have made every breathing tick a recomposition of the hero.
 */
class FolioBackdrop(
    val atmos: FolioAtmosphere,
    val strength: Float,
    val shift: Float,
    internal val tint: State<Color?>,
    internal val ambient: State<FolioAmbient>,
    internal val fieldSize: State<IntSize>,
    /**
     * The lamp, so a pane bends the light in the room and not only the wash under it.
     * Null where there is none — desktop, previews, any tree outside the app root.
     *
     * The breath is deliberately not carried through: a rim band is a very small place
     * to show a twelve percent wobble, and sampling the lamp at rest keeps the shader
     * honest about the one thing it is reproducing.
     */
    internal val lamp: FolioLamp? = null,
) {
    /** The field as it is drawing right now. Call in the draw phase. */
    fun colors(): FolioFieldColors = fieldColors(atmos, tint.value, strength)

    /** §17's ambient pool displacement, in fractions of the field. */
    fun drift(): Pair<Float, Float> = ambientPoolDrift(ambient.value)
}

/**
 * The room, published from the app root beneath `folioField`.
 *
 * Null means there is no field to reproduce — desktop, a preview, a unit test, or
 * a surface placed outside the root — and a consumer must fall back to whatever it
 * drew before, never invent a room. That is what keeps the shader-gated look and
 * the un-shaded one the same picture.
 */
val LocalFolioBackdrop = compositionLocalOf<FolioBackdrop?> { null }
