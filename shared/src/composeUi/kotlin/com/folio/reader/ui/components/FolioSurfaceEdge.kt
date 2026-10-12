package com.folio.reader.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * §18 — **the surface edge**: one ramp where there used to be one step.
 *
 * ## The defect this exists to remove
 *
 * Every material in [FolioSurfaces.kt][folioVeil] ended its chain the same way:
 *
 * ```
 * .background(<one fill alpha>, shape)   // a uniform lid
 * .border(1.dp, atmos.hairline, shape)   // a full-perimeter high-contrast stroke
 * .clip(shape)                           // a binary in/out decision
 * ```
 *
 * That is, mathematically, a **step function**. A surface's alpha goes from 0 to
 * (say) 0.62 across the width of one anti-aliased pixel, and a 0.30–0.42 hairline is
 * painted exactly on the discontinuity to make sure the eye cannot miss it. No amount
 * of tuning the *height* of a step makes it stop being a step — and "you can make out
 * boxes" is the only thing a step can ever read as. The symptom was reported on cards,
 * on all of settings, on the icon tiles, on the bottom nav capsule and on the quote
 * rows, because all of them end their chain with those three lines.
 *
 * The app already knew the answer and had applied it in exactly one place. From
 * [folioTypeGround]'s own notes, on why a type wash does not read as a card:
 *
 * > *Where it stops.* Nowhere. It dissolves to nothing over
 * > `FOLIO_TYPE_GROUND_FEATHER` at both the head and the foot, so there is no edge to
 * > read as the lip of a card.
 *
 * This file is that idea generalised from a text block's head and foot to **any
 * surface's whole silhouette**, and moved into the shared primitive so it lands on
 * every surface class at once rather than per screen.
 *
 * ## How the ramp is built
 *
 * There is no portable brush that ramps along a rounded rectangle's *normal*:
 * `Brush` interpolates along a line or a radius, neither of which follows a
 * silhouette with four different corner radii. A signed-distance shader would, but
 * AGSL is Android-only and the shared module must render identically on Skiko.
 *
 * So the ramp is quantised into **concentric inset outlines**. Ring `i` is the
 * surface's own outline deflated by `i × quantum` — a true offset curve, not a scale:
 * the rect is deflated and each corner radius is reduced by the same amount, which is
 * exactly what an inset rounded rectangle is. Each ring is filled with the increment
 * of alpha needed to reach the target profile at that depth, so the ring stack
 * composites into a smooth alpha ramp from 0 at the silhouette to the full fill at
 * [FolioEdgeProfile.feather] inside it.
 *
 * The profile is a **smoothstep**, not a line, and that is the point rather than a
 * flourish: a linear ramp has a derivative discontinuity at each end, and the eye
 * finds those as Mach bands — two faint lines instead of one hard one. Smoothstep is
 * C1 at both ends, so the fill leaves the field and arrives at its own plane with zero
 * slope and there is no longer any depth at which a boundary can be measured.
 *
 * ## What happens to the hairline
 *
 * It stops being a line. A full-perimeter 1dp stroke at 0.42 alpha *on* the silhouette
 * is the single strongest box cue in the app. Here the same ink is spread over
 * [FOLIO_EDGE_RIM_TAPS] abutting bands that begin where the feather ends and decay
 * inward — a short-range ambient-occlusion ramp rather than an outline. Two things
 * follow:
 *
 *  - no ink lands on the silhouette at all, so the surface's outer boundary is purely
 *    the feather (alpha ≈ 0 there);
 *  - peak contrast falls by [FolioEdgeProfile.rimScale], and what is left is a
 *    gradient of roughly 0.04 alpha per dp instead of the old 1.27 alpha per dp.
 *
 * The surface still has an edge — a nested group still bounds itself, which is what
 * [FOLIO_EMBEDDED_RIM] exists for — it simply no longer has an *outline*.
 *
 * ## One ladder, so nothing drifts
 *
 * [FolioEdgeRole] is the whole token ladder. Every surface class in the app resolves
 * its feather distance and its rim contrast from it and from nothing else, which is
 * the direct answer to "liquid glass, wherever it is used, is inconsistent": there is
 * now exactly one place where a card, a settings row, an icon tile, a well, a chip,
 * the nav capsule and a sheet can disagree about how their edge meets the background,
 * and it is seven lines long.
 *
 * ## Cost
 *
 * Draw-phase only. The ring geometry is built in [drawWithCache] — once per size, not
 * per frame — and nothing here recomposes, registers a layer, or captures the screen.
 * The per-frame cost is `steps + taps` filled/stroked outlines, where [steps] is
 * adaptive ([FOLIO_EDGE_PX_PER_STEP]: roughly one ring per 2.5 px of feather, capped
 * at [FOLIO_EDGE_MAX_STEPS]), so a 38dp icon tile pays four rings and a full-width
 * section card pays eight. That is a handful of analytic round-rect fills against the
 * single full-surface blur, AGSL lens and AGSL film the overlay tier already pays per
 * surface per frame.
 *
 * [FOLIO_EDGE_FEATHER] is the kill switch: flip it to `false` and every surface in the
 * app returns to the flat fill and the 1dp outline, byte-for-byte, in one line.
 */

/**
 * Master switch for the whole §18 edge.
 *
 * `false` makes [folioEdgeStack] return a single un-inset ring with no rim taps, which
 * drives [drawFolioPlane] and [drawFolioRim] down their legacy branches — one flat
 * fill and one 1dp stroke on the silhouette. That is the pre-§18 material exactly, so
 * a regression can be bisected without reverting five call sites.
 */
internal const val FOLIO_EDGE_FEATHER = true

/**
 * The hard ceiling on a feather, as a share of the surface's **short** side.
 *
 * One token has to work from a 38dp icon tile to a full-bleed masthead, and a 6dp
 * feather that is right on a 300dp-tall section card would consume a third of a 38dp
 * tile and leave it with no plane at all. Clamping against the short side is what lets
 * the ladder stay a ladder instead of growing a per-call-site override: a small surface
 * silently takes a proportionally smaller ramp.
 *
 * 0.12 also keeps every inset this file takes safely inside the half-extent at which a
 * deflated rect would collapse, including the rim taps that sit *beyond* the feather. And
 * it is what makes the ladder's values honest on the two short surfaces: the nav capsule is
 * 56dp tall, so an unclamped 5dp ramp at both ends would leave only its middle at full
 * alpha, and the More hub's icon tiles are 38dp square.
 */
private const val FOLIO_EDGE_MAX_SHARE = 0.12f

/**
 * How much feather one ring is allowed to cover, in pixels.
 *
 * The requirement is perceptual, not geometric: a ring boundary is invisible while the
 * alpha increment across it is small, and the increment is `fill.alpha / steps`. At one
 * ring per 2.5 px a 0.62 fill steps by under 0.08 per ring at the ramp's steepest
 * point, and each of those steps is itself anti-aliased against its neighbour, so no
 * ring reads as a contour. Density-correct by construction, since this is in pixels —
 * a 3x phone gets three times the rings of a 1x desktop window for the same dp.
 */
private const val FOLIO_EDGE_PX_PER_STEP = 2.5f

/** Below this the ramp is too coarse to read as a ramp; above it, wasted draws. */
private const val FOLIO_EDGE_MIN_STEPS = 3
private const val FOLIO_EDGE_MAX_STEPS = 8

/** Feathers under this are sub-pixel: take the legacy path rather than pay for nothing. */
private const val FOLIO_EDGE_MIN_FEATHER_PX = 1.5f

/**
 * The rim's decay, as weights on [FolioEdgeProfile.rimScale]'s peak.
 *
 * Five abutting bands rather than three, because the thing being removed is a visible
 * step and three bands reintroduce one: at five taps the absolute alpha difference
 * between neighbouring bands is around 0.05 spread over more than a dp, which is
 * roughly thirty times gentler than the 1dp stroke this replaces.
 *
 * The first weight is 1.0 deliberately — the band that sits exactly at the feather's
 * shoulder is the one doing the work of saying "the surface starts here", and the four
 * behind it are what stop it being a line.
 */
private val FOLIO_EDGE_RIM_WEIGHTS = floatArrayOf(1f, 0.78f, 0.52f, 0.28f, 0.10f)

/** How many rim bands [FOLIO_EDGE_RIM_WEIGHTS] describes. */
private const val FOLIO_EDGE_RIM_TAPS = 5

/** An alpha below this costs a draw call and changes no pixel. */
private const val FOLIO_EDGE_ALPHA_FLOOR = 0.002f

/**
 * How a surface's edge meets whatever is behind it.
 *
 * Two numbers, because the edge only has two degrees of freedom once the ramp's *shape*
 * is fixed by [folioEdgeStack]: how far it takes to get there, and how much edge ink is
 * left at the end of it.
 */
@Immutable
data class FolioEdgeProfile(
    /**
     * How far the fill's alpha ramps in from the silhouette before reaching the plane.
     *
     * This is the lever the user's report is actually about. "The gradient of change
     * between outer and inner structure is too high" is `fill.alpha / feather`, and at
     * the old `feather = 0` it was infinite. Raising this is free in a way that lowering
     * the fill alpha is not: the interior keeps its full opacity, so on-surface text keeps
     * every contrast ratio it had, and only the boundary softens.
     *
     * Clamped by [FOLIO_EDGE_MAX_SHARE], so a generous value on a card degrades
     * gracefully on a tile instead of erasing it.
     */
    val feather: Dp,
    /**
     * Factor on the atmosphere's hairline alpha at the rim ramp's peak.
     *
     * Never 1.0 for a feathered surface: the hairline was calibrated as the *only* thing
     * marking a boundary, and once the fill ramps there instead, the same contrast reads
     * as a drawn outline on a surface that no longer has one. This is where a
     * luminance-aware rim would be keyed if a theme needs one — the ladder already passes
     * a per-role value, so a palette-sensitive scale is a change to the resolver below
     * and to nothing else.
     */
    val rimScale: Float,
    /**
     * Width of one rim band, as a multiple of the ring quantum.
     *
     * Bands abut rather than overlap, which matters: overlapping strokes accumulate, and
     * accumulated alpha in the overlaps would put the peak contrast back where it started
     * while also making the bands individually visible.
     */
    val rimSpread: Float = 1.4f,
) {
    companion object {
        /** The legacy hard edge: flat fill, 1dp outline on the silhouette, no ramp. */
        val Step = FolioEdgeProfile(feather = 0.dp, rimScale = 1f)
    }
}

/**
 * The one token ladder every surface class resolves its edge from.
 *
 * Roles are keyed by what a surface *is to the page*, not by which modifier draws it,
 * so two materials that play the same part get the same edge by construction. The
 * values differ only where the role genuinely differs:
 *
 * | Role | Feather | Rim | Why |
 * |---|---|---|---|
 * | [Chrome] | 5dp | 0.42 | The capsule and the masthead are always on screen, over live content, and are the surfaces the user named last ("even the bottom nav bar"). Longest ramp the 56dp capsule can take under [FOLIO_EDGE_MAX_SHARE], and the faintest edge on the ladder. |
 * | [Pane] | 4.5dp | 0.50 | Glass genuinely over content: sheets, menus, chips, the reader's panels. It must stay findable against a page, so a touch shorter than chrome. |
 * | [Card] | 6dp | 0.50 | The in-flow workhorse — section cards, settings groups, panels, the shelf plate. The biggest surfaces, the biggest offenders, and the ones with the most height to spend on a ramp. |
 * | [Inset] | 3.5dp | 0.60 | A surface inside another surface. Its rim is the §19 division — [FOLIO_EMBEDDED_RIM] of the hairline over a floor of the parent's plane — and a long ramp inside a parent plane reads as a smudge rather than a division, so it stays short. |
 * | [Tile] | 3dp | 0.60 | Icon tiles, small chips. Short side measured in tens of dp, so the clamp would cut a longer feather down anyway — naming it keeps the intent honest. |
 * | [Well] | 3dp | 0.70 | A recess is an *inversion*, and its lip is the thing that reads as depth. Shortest ramp on the ladder: enough to kill the hard rectangle, not enough to fill the hole in. |
 */
enum class FolioEdgeRole {
    Chrome,
    Pane,
    Card,
    Inset,
    Tile,
    Well,
}

/** The ladder itself. Nothing else in the app may name a feather distance. */
val FolioEdgeRole.profile: FolioEdgeProfile
    get() = when (this) {
        FolioEdgeRole.Chrome -> FolioEdgeProfile(feather = 5.dp, rimScale = 0.42f)
        FolioEdgeRole.Pane -> FolioEdgeProfile(feather = 4.5.dp, rimScale = 0.50f)
        FolioEdgeRole.Card -> FolioEdgeProfile(feather = 6.dp, rimScale = 0.50f)
        FolioEdgeRole.Inset -> FolioEdgeProfile(feather = 3.5.dp, rimScale = 0.60f)
        FolioEdgeRole.Tile -> FolioEdgeProfile(feather = 3.dp, rimScale = 0.60f)
        FolioEdgeRole.Well -> FolioEdgeProfile(feather = 3.dp, rimScale = 0.70f)
    }

/**
 * The precomputed geometry of one surface's edge, cached per size.
 *
 * [rings] is outermost-first: `rings[0]` is the surface's own outline and `rings[n-1]`
 * sits one quantum short of the full feather, which is where the plane reaches its
 * authored alpha. [core] is the outline inset by the *whole* feather — the region a
 * sheen or a specular catch may paint on without putting its brightest row on the
 * silhouette. [rimRings] start at the feather's shoulder and march inward.
 */
internal class FolioEdgeStack(
    val rings: List<Path>,
    val core: Path,
    val rimRings: List<Path>,
    val rimWidth: Float,
    /** False when the shape, the size or [FOLIO_EDGE_FEATHER] left nothing to ramp. */
    val feathered: Boolean,
)

/**
 * A true inset of a rounded rectangle: deflate the box and reduce every corner radius
 * by the same amount.
 *
 * Not a scale about the centre, which is the tempting one-liner and is wrong — it would
 * keep the corner radius proportional and make the inner rings rounder than the outer
 * ones, so the ramp would be narrow at the corners and wide at the edges and the
 * *corners* would then be the visible boundary.
 *
 * Guarded rather than asserted: a surface can be measured at one pixel during a layout
 * pass or an animation, and a deflate past the half-extent would invert the rect.
 */
private fun RoundRect.insetBy(delta: Float): RoundRect {
    if (delta <= 0f) return this
    val halfW = (right - left) * 0.5f
    val halfH = (bottom - top) * 0.5f
    if (halfW <= 1f || halfH <= 1f) return this
    val dx = delta.coerceAtMost(halfW - 1f)
    val dy = delta.coerceAtMost(halfH - 1f)
    fun shrink(radius: CornerRadius) = CornerRadius(
        (radius.x - dx).coerceAtLeast(0f),
        (radius.y - dy).coerceAtLeast(0f),
    )
    return RoundRect(
        left = left + dx,
        top = top + dy,
        right = right - dx,
        bottom = bottom - dy,
        topLeftCornerRadius = shrink(topLeftCornerRadius),
        topRightCornerRadius = shrink(topRightCornerRadius),
        bottomRightCornerRadius = shrink(bottomRightCornerRadius),
        bottomLeftCornerRadius = shrink(bottomLeftCornerRadius),
    )
}

/**
 * The shape as a round rect, or null when it cannot be offset exactly.
 *
 * `Outline.Generic` is the null case on purpose. A generic path has no closed-form
 * offset curve, and the approximations (scale about the centre, or a stroke-and-fill
 * trick) distort asymmetric silhouettes — which in this app means the hero and the
 * callout, the two shapes whose asymmetry is load-bearing (`FolioShapes.hero`: "it
 * cannot be mistaken for a card even blurred to 8px"). A generic shape therefore keeps
 * the legacy flat edge rather than getting a wrong one; every value in `FolioShapes`
 * except that is a `RoundedCornerShape` or `CircleShape`, both of which resolve to
 * `Outline.Rounded`.
 */
private fun Outline.asRoundRect(): RoundRect? = when (this) {
    is Outline.Rounded -> roundRect
    is Outline.Rectangle -> RoundRect(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
    )
    is Outline.Generic -> null
}

private fun Outline.asPath(): Path = when (this) {
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

/**
 * Build (or decline to build) a surface's edge ramp.
 *
 * Call from [drawWithCache]'s build phase: the paths are allocated here, once per size,
 * and only the fills and strokes happen per frame.
 */
internal fun CacheDrawScope.folioEdgeStack(
    shape: Shape,
    profile: FolioEdgeProfile,
): FolioEdgeStack {
    val outline = shape.createOutline(size, layoutDirection, this)
    val base = outline.asRoundRect()
    val limit = min(size.width, size.height) * FOLIO_EDGE_MAX_SHARE
    val feather = if (base == null || !FOLIO_EDGE_FEATHER) {
        0f
    } else {
        min(profile.feather.toPx(), limit)
    }
    if (base == null || feather < FOLIO_EDGE_MIN_FEATHER_PX) {
        val flat = outline.asPath()
        return FolioEdgeStack(
            rings = listOf(flat),
            core = flat,
            rimRings = emptyList(),
            rimWidth = 0f,
            feathered = false,
        )
    }
    val steps = ceil(feather / FOLIO_EDGE_PX_PER_STEP)
        .toInt()
        .coerceIn(FOLIO_EDGE_MIN_STEPS, FOLIO_EDGE_MAX_STEPS)
    val quantum = feather / steps
    val bandWidth = quantum * profile.rimSpread
    return FolioEdgeStack(
        rings = List(steps) { i -> Path().apply { addRoundRect(base.insetBy(i * quantum)) } },
        core = Path().apply { addRoundRect(base.insetBy(feather)) },
        // Centred on the band, so the outermost band's outer half still lands inside the
        // feather and no rim ink reaches the silhouette.
        rimRings = List(FOLIO_EDGE_RIM_TAPS) { k ->
            Path().apply {
                addRoundRect(base.insetBy(feather + (k + 0.5f) * bandWidth))
            }
        },
        rimWidth = bandWidth,
        feathered = true,
    )
}

/**
 * The per-ring alphas that composite into the ramp, outermost first.
 *
 * Each ring is painted *over* the ones outside it, so a ring cannot simply be given the
 * profile's value at its depth — it has to be given the increment that takes the
 * already-accumulated alpha there. Solving `A_i = A_(i-1) + head·(1 − A_(i-1))` gives
 * `head = (A_i − A_(i-1)) / (1 − A_(i-1))`, which is exact and, unlike the tempting
 * `1 − (1 − a)^(1/n)` shortcut, stays correct when the fill is fully opaque: an opaque
 * plane ramps up through its rings and only the innermost one is solid.
 *
 * Cached alongside the geometry because `Brush.verticalGradient` with no explicit
 * coordinates is resolved against the draw size at paint time, so the brushes are
 * size-independent and never need rebuilding on a resize.
 */
internal fun folioPlaneBrushes(
    steps: Int,
    fill: Color,
    foot: Color,
): List<Brush> {
    if (steps <= 0) return emptyList()
    // The foot's share of the head's alpha, so the authored top-to-bottom decay survives
    // being split across rings instead of being applied once to a flat fill.
    val footShare = if (fill.alpha > 0.001f) (foot.alpha / fill.alpha).coerceIn(0f, 1f) else 1f
    val brushes = ArrayList<Brush>(steps)
    var covered = 0f
    for (i in 0 until steps) {
        // Smoothstep over the normalised depth: zero slope where the surface leaves the
        // field and zero slope where it arrives at its own plane, so neither end of the
        // ramp is itself an edge. See the file header.
        val t = (i + 1).toFloat() / steps
        val target = fill.alpha * t * t * (3f - 2f * t)
        val head = if (covered >= 1f) 1f else ((target - covered) / (1f - covered)).coerceIn(0f, 1f)
        covered = target
        brushes += Brush.verticalGradient(
            listOf(
                fill.copy(alpha = head),
                foot.copy(alpha = head * footShare),
            ),
        )
    }
    return brushes
}

/** Paints the ramp. One fill per ring; no layer, no shader, no allocation. */
internal fun DrawScope.drawFolioPlane(stack: FolioEdgeStack, brushes: List<Brush>) {
    val n = min(stack.rings.size, brushes.size)
    for (i in 0 until n) {
        drawPath(stack.rings[i], brushes[i])
    }
}

/**
 * Paints the edge as a short inward ramp instead of an outline.
 *
 * The legacy branch is a real branch, not a formality: it is what a generic shape, a
 * degenerate size and [FOLIO_EDGE_FEATHER] `= false` all fall through to, and it has to
 * be the pre-§18 stroke exactly so those cases stay surfaces rather than becoming
 * edgeless.
 */
internal fun DrawScope.drawFolioRim(
    stack: FolioEdgeStack,
    rim: Color,
    scale: Float,
    hairlinePx: Float,
) {
    if (rim.alpha <= FOLIO_EDGE_ALPHA_FLOOR) return
    if (!stack.feathered || stack.rimRings.isEmpty()) {
        drawPath(stack.rings[0], rim, style = Stroke(width = hairlinePx))
        return
    }
    val peak = (rim.alpha * scale).coerceIn(0f, 1f)
    if (peak <= FOLIO_EDGE_ALPHA_FLOOR) return
    val stroke = Stroke(width = stack.rimWidth)
    for (k in stack.rimRings.indices) {
        val alpha = peak * FOLIO_EDGE_RIM_WEIGHTS[k]
        if (alpha <= FOLIO_EDGE_ALPHA_FLOOR) continue
        drawPath(stack.rimRings[k], rim.copy(alpha = alpha), style = stroke)
    }
}

/**
 * The feathered plane, as a drop-in replacement for `Modifier.background(brush, shape)`.
 *
 * Deliberately **not** `@Composable` and deliberately a separate modifier from
 * [folioSurfaceRim], even though both rebuild the same ring geometry. The two are at
 * opposite ends of their materials' modifier chains — the plane under the lens, the
 * sheen, the bevel and the film; the rim over all of them — and collapsing them into
 * one pass to save a geometry build would silently reorder five materials' layers. The
 * duplicated build is paid once per size change, in the cache phase; the saving would
 * have been zero per frame.
 *
 * [foot] carries both the authored top-to-bottom alpha decay and, optionally, a hue:
 * passing a foot lerped toward the field's own lower endpoint makes a surface ramp the
 * same way the room behind it ramps, which is the cheapest spatial variation available
 * to a surface that is not paying for a blur.
 */
internal fun Modifier.folioSurfacePlane(
    shape: Shape,
    fill: Color,
    profile: FolioEdgeProfile,
    foot: Color = fill,
): Modifier = drawWithCache {
    val stack = folioEdgeStack(shape, profile)
    val brushes = folioPlaneBrushes(stack.rings.size, fill, foot)
    onDrawBehind { drawFolioPlane(stack, brushes) }
}

/**
 * The feathered rim, as a drop-in replacement for `Modifier.border(1.dp, color, shape)`.
 *
 * [hairline] is only consulted on the legacy branch; a feathered rim's width comes from
 * the ring quantum, so the band scales with the feather rather than with a dp that was
 * chosen when the edge was a line.
 */
internal fun Modifier.folioSurfaceRim(
    shape: Shape,
    rim: Color,
    profile: FolioEdgeProfile,
    hairline: Dp = 1.dp,
): Modifier = drawWithCache {
    val stack = folioEdgeStack(shape, profile)
    val hairlinePx = hairline.toPx()
    onDrawBehind { drawFolioRim(stack, rim, profile.rimScale, hairlinePx) }
}

/**
 * The same rim, struck with a brush rather than a colour.
 *
 * The one caller is an opaque [folioRaised] slab, whose edge is a directional
 * light→shade gradient rather than a uniform hairline. It still has to be feathered or
 * it is the last hard outline left in the app, and spreading a *gradient* over the rim
 * bands works the same way the colour version does because every band is stroked from
 * the same brush: the brush varies along the surface's axis, the bands vary along its
 * normal, and the two are independent.
 */
internal fun Modifier.folioSurfaceRim(
    shape: Shape,
    rim: Brush,
    profile: FolioEdgeProfile,
    hairline: Dp = 1.dp,
): Modifier = drawWithCache {
    val stack = folioEdgeStack(shape, profile)
    val hairlinePx = hairline.toPx()
    onDrawBehind {
        if (!stack.feathered || stack.rimRings.isEmpty()) {
            drawPath(stack.rings[0], rim, style = Stroke(width = hairlinePx))
            return@onDrawBehind
        }
        val peak = profile.rimScale.coerceIn(0f, 1f)
        val stroke = Stroke(width = stack.rimWidth)
        for (k in stack.rimRings.indices) {
            val alpha = peak * FOLIO_EDGE_RIM_WEIGHTS[k]
            if (alpha <= FOLIO_EDGE_ALPHA_FLOOR) continue
            drawPath(stack.rimRings[k], rim, alpha = alpha, style = stroke)
        }
    }
}

/**
 * The surface's outline inset by its whole feather — where a light pass may land.
 *
 * Every specular layer in `FolioSurfaces` paints on `shapePath(shape)`, which means the
 * brightest row of a sheen sits on the silhouette: a flat vertical gradient whose peak
 * is the top edge, clipped dead at the shape, is a *second* box cue laid over the first
 * one. Painting the same brush on this path instead puts the catch inside the feather,
 * so the sheen fades out with the plane it belongs to and the surface's lit edge stops
 * being its boundary.
 *
 * Falls back to the plain outline whenever [folioEdgeStack] declined to feather, so a
 * generic shape keeps the sheen it always had.
 */
internal fun CacheDrawScope.folioEdgeCorePath(
    shape: Shape,
    profile: FolioEdgeProfile,
): Path = folioEdgeStack(shape, profile).core

/**
 * §18 — **`Modifier.background(color, shape)`, feathered**, for call sites outside this
 * package.
 *
 * [folioSurfacePlane] is `internal`, which is right for the material family but left two
 * call sites with no way to spend the ladder — and both of them were drawing the exact
 * step function this file exists to delete, *on top of* a surface that had already
 * feathered itself:
 *
 *  - the **nav bar's selected pill**, a `clip(pill).background(accent, pill)` at 0.76
 *    alpha, 42dp tall, centred inside a 54dp glass capsule. A hard-edged opaque shape
 *    inside a soft-edged translucent one is not a subtle artifact of a shader — it is
 *    the literal reading of "nested shapes", and it is the one in the screenshot;
 *  - the **home hero's tint wash**, a `background(linearGradient, heroShape)` applied
 *    after `folioRaised` had already ramped the plane to nothing at the silhouette. A
 *    hard fill of the same shape painted over a feathered one *restores the hard
 *    silhouette*: the ramp underneath is still there and still correct, and completely
 *    invisible, because something opaque was drawn over its outer end.
 *
 * Same ring stack, same smoothstep profile, same cache-phase build as the material
 * family. Not `@Composable` and not themed: the caller already has the colour it wants,
 * and the only thing it is missing is the edge.
 */
fun Modifier.folioSoftFill(
    shape: Shape,
    color: Color,
    role: FolioEdgeRole = FolioEdgeRole.Tile,
    foot: Color = color,
): Modifier = folioSurfacePlane(shape, color, role.profile, foot)

/**
 * The same thing for a [Brush] — a wash, a gradient, a tint sweep.
 *
 * A brush carries its own per-pixel alpha, so the ring stack cannot bake the ramp into
 * the colour the way [folioPlaneBrushes] does. It drives the ring's *layer* alpha
 * instead, with the identical "increment that takes the already-accumulated alpha there"
 * solve, so a gradient composites into the same C1 ramp a flat fill does and the brush's
 * own variation along the surface's axis survives untouched. The two are independent:
 * the brush varies along the axis, the rings vary along the normal.
 */
fun Modifier.folioSoftWash(
    shape: Shape,
    brush: Brush,
    role: FolioEdgeRole = FolioEdgeRole.Card,
): Modifier = drawWithCache {
    val profile = role.profile
    val stack = folioEdgeStack(shape, profile)
    val heads = folioWashHeads(stack.rings.size)
    onDrawBehind {
        val n = min(stack.rings.size, heads.size)
        for (i in 0 until n) {
            if (heads[i] <= FOLIO_EDGE_ALPHA_FLOOR) continue
            drawPath(stack.rings[i], brush, alpha = heads[i])
        }
    }
}

/**
 * Per-ring layer alphas for [folioSoftWash], outermost first.
 *
 * The same exact solve as [folioPlaneBrushes] — `head = (A_i − A_(i-1)) / (1 − A_(i-1))`
 * — against a target of 1.0, because the brush supplies the absolute alpha and the rings
 * only have to deliver the *profile*. A single un-inset ring (the legacy branch) comes
 * back as `[1.0]`, which is `background(brush, shape)` exactly.
 */
private fun folioWashHeads(steps: Int): FloatArray {
    if (steps <= 1) return floatArrayOf(1f)
    val heads = FloatArray(steps)
    var covered = 0f
    for (i in 0 until steps) {
        val t = (i + 1).toFloat() / steps
        val target = t * t * (3f - 2f * t)
        heads[i] = if (covered >= 1f) 1f else ((target - covered) / (1f - covered)).coerceIn(0f, 1f)
        covered = target
    }
    return heads
}
