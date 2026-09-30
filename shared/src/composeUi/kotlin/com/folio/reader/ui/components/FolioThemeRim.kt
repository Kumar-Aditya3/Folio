package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.TWO_PI
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberSlowPhases
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The themed rim: a surface's outline carrying one light that travels around it.
 *
 * A hero says which object on the page is the one being read by being the one that
 * is *lit*, and the light comes from its own colour rather than from a generic
 * shimmer: [accent] is the surface's signature hue (a cover's derived accent, the
 * hue its eyebrow and progress bar already speak in) and [counterAccent] is the
 * theme role that names what the surface is *for* — `accentStreak` for a book you are
 * racing to finish, `accentDiscovery` for manga. Two heroes under one palette are
 * therefore still told apart by their edges, which is the point of a themed
 * outline rather than a decorative one.
 *
 * **One light, and it is a comet rather than a pair of bands.** The beam is sampled
 * around the turn as an angular sweep, so exactly one arc of the outline is lit at
 * any instant. That is not a stylistic preference: an earlier cut laid a gradient
 * along a *rotating diameter*, and a chord across a closed convex outline crosses
 * the edge twice — so that version could only ever draw two streaks mirrored about
 * the centre, which read as the decoration duplicating itself rather than as one
 * light moving. The angular form is the only one that can express a travelling
 * light at all.
 *
 * **The two hues blend into each other instead of sitting side by side.** The head
 * is [accent] and the tail runs out toward [counterAccent], but the tail is first
 * pulled [RIM_TAIL_TOWARD_SURFACE] of the way back toward the surface's own hue, so
 * a warm cover under a blue progress theme edges warm-with-a-cool-trail rather than
 * sprouting a blue stripe that belongs to nothing on the plate.
 *
 * It is drawn **behind the content**, so a cover plate that overhangs the edge
 * occludes the arc passing under it: the light reads as travelling behind the
 * objects on the surface, which is what makes the surface feel like a plane in a
 * room rather than a picture with a frame pasted on it.
 *
 * Under the beam the shape keeps a static hairline tinted toward the accent, so the
 * outline is a designed edge at rest and not only in motion. Reduce-motion parks
 * the head at the leading top edge — the side every other material in the app
 * catches light from — so the picture is the animated one frozen at a flattering
 * instant, never a different picture.
 */

/**
 * One circuit per 24s — §13.4's 18–30s band, which is what a hero's own motion is
 * specified to sit in. The earlier 12s cut was outside it, and on a wide plate a
 * glint crossing ~2,600px of perimeter in 12s is brisk enough to read as a loading
 * state rather than as lighting.
 */
internal const val RIM_PERIOD_MS = 24_000L

/**
 * 30 Hz rather than the house 10 Hz slow clock
 * ([com.folio.reader.ui.theme.SLOW_MOTION_TICK_MS]).
 *
 * A glint crossing a hero's ~2,600px perimeter on a 24s circuit still steps ~3.6px
 * per sample here against ~11px at 10 Hz, and a point of light on a long straight
 * edge shows that step. The cost is bounded by what moves: this invalidates the one
 * featured item per shelf, and the list cell stops the clock once it scrolls out.
 */
private const val RIM_TICK_MS = 33L

/** The lit arc's angular width as a fraction of a full turn. */
private const val RIM_BEAM_SPAN = 0.30f

/** Peak alpha at the centre of the arc; it ramps to nothing at both ends across [RIM_BEAM_SPAN]. */
private const val RIM_HEAD_ALPHA = 0.80f

/**
 * How far the tail is pulled back toward the surface's own hue before it is used.
 *
 * The two hues meet at the trailing tip rather than sitting side by side: the arc is
 * the surface's colour for most of its length and takes on the theme role only as it
 * fades, so a themed rim never grows a stripe of a colour nothing else on the plate
 * speaks.
 */
private const val RIM_TAIL_TOWARD_SURFACE = 0.55f

/** Samples around the turn; the sweep interpolates linearly between them. */
private const val RIM_STOP_COUNT = 64

/**
 * The head's rest position under reduce-motion, as a fraction of a turn.
 *
 * It parks the *brightest* point of the arc, not its leading tip. The sweep's zero
 * is at 3 o'clock and increases clockwise, so 7/8 of a turn puts the light at the
 * leading top edge — where the room's light and every other material's highlight
 * already come from.
 */
private const val RIM_PARKED_HEAD = 0.875f

private fun wrapToOne(x: Float): Float = x - floor(x)

/**
 * The beam sampled at [turn] around the plate, as evenly-spaced sweep stops.
 *
 * [turn] is the *centre* of the arc — where it is brightest — so the light ramps in
 * and out at both ends rather than starting on a cut: a hard leading edge reads as a
 * seam in the beam, and the whole point is one soft light moving.
 *
 * Exposed for `FolioThemeRimTest`, which checks the three things invisible in a still
 * frame and fatal in motion: the sample is *one* cyclic run (a second lit arc is the
 * doubling this design exists to avoid), no two neighbours differ enough to show as
 * a step, and it carries both theme hues without either reading as foreign.
 */
internal fun rimBeamColors(turn: Float, accent: Color, counterAccent: Color): List<Color> {
    val tail = lerp(counterAccent, accent, RIM_TAIL_TOWARD_SURFACE)
    // The unlit region is the tail at zero alpha rather than Color.Transparent: a
    // gradient interpolates hue too, and Transparent is (0,0,0,0) — an undefined hue
    // — so fading to it drags the light through black and greys the edge. This is
    // the same gamma-toe artefact ShimmerTest documents, and it leaves through the
    // trail's own colour instead.
    val unlit = tail.copy(alpha = 0f)
    val half = RIM_BEAM_SPAN / 2f
    return List(RIM_STOP_COUNT) { index ->
        // Distance back from the leading tip, so the centre of the arc sits at turn.
        val behind = wrapToOne(turn + half - index / RIM_STOP_COUNT.toFloat())
        if (behind >= RIM_BEAM_SPAN) {
            unlit
        } else {
            val along = behind / RIM_BEAM_SPAN
            // Convex, so the accent holds the leading two-thirds and the trail's hue
            // only arrives where the light is already faint.
            lerp(accent, tail, along.pow(1.3f))
                .copy(alpha = RIM_HEAD_ALPHA * sin(PI.toFloat() * along).pow(1.15f))
        }
    }
}

@Composable
fun Modifier.folioThemeRim(
    shape: Shape,
    accent: Color,
    counterAccent: Color,
    width: Dp = 1.5.dp,
    animate: Boolean = rememberMotionEnabled(),
): Modifier {
    val hairline = FolioTheme.atmosphere.hairline
    val phases: State<List<Float>>? =
        if (animate) rememberSlowPhases(listOf(RIM_PERIOD_MS), RIM_TICK_MS) else null
    return drawWithCache {
        val path = shapePath(shape)
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = lerp(hairline, accent, 0.40f)
        // Every pass is clipped to the shape and drawn at twice its visible
        // weight, so half the stroke falls outside and what remains is a rim that
        // hugs the inside of the edge. Without the clip, a surface that is not
        // itself clipped would leak its bloom onto its neighbours.
        val edge = Stroke(2.dp.toPx())
        val ring = Stroke(width.toPx() * 2f)
        val bloom = Stroke(width.toPx() * 4.5f)
        onDrawBehind {
            val turn = phases?.value?.getOrNull(0)
            // The clock's phase runs 0..2π and the sweep takes a fraction of a
            // turn, so the wrap is exact: a full circuit lands precisely where it
            // started and there is no restart to hide.
            val head = if (turn == null) RIM_PARKED_HEAD else wrapToOne(turn / TWO_PI)
            val beam = Brush.sweepGradient(rimBeamColors(head, accent, counterAccent), center)
            clipPath(path) {
                drawPath(path, base, style = edge)
                drawPath(path, beam, style = bloom)
                drawPath(path, beam, style = ring)
            }
        }
    }
}
