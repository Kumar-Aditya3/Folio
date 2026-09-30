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
import com.folio.reader.ui.theme.FolioTokens
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

/** The lit arc's angular width as a fraction of a full turn, at rest. */
internal const val RIM_BEAM_SPAN = 0.30f

/** Peak alpha at the centre of the arc; it ramps to nothing at both ends across [RIM_BEAM_SPAN]. */
internal const val RIM_HEAD_ALPHA = 0.80f

/**
 * How much a fling may widen the beam, as a fraction of [RIM_BEAM_SPAN].
 *
 * Width is the primary lever and brightness only a follower, for the reason the
 * file's own doctrine gives: the beam is parameterised by `along = behind / span`,
 * so widening it rescales the *same* light and changes nothing else — same envelope,
 * same hue mix, no new element. Alpha is the axis the design already rations
 * (`DAYLIGHT_WASH_ALPHA_MAX`), and it raises the crisp `Stroke(width * 2)` ring
 * alongside the bloom; past a small lift the edge stops reading as light and starts
 * reading as a border, which is a second object on the plate.
 */
internal const val RIM_SPAN_GAIN = 0.25f

/** How much a fling may lift the head's alpha, as a fraction of [RIM_HEAD_ALPHA]. */
internal const val RIM_ALPHA_GAIN = 0.10f

/**
 * The travel that counts as full speed, in px/s. A finger drag lands around a
 * quarter of this and a real fling at or past it, which is what makes the quadratic
 * in [rimSpeedGain] able to hide during ordinary scrolling.
 */
private const val RIM_SPEED_REFERENCE_PX_PER_S = 6_000f

/** One frame, for the first event of a gesture — which has no measured gap to divide by. */
private const val DEFAULT_EVENT_GAP_NS = 16_666_667L

/**
 * How long after the last scroll event the rim is provably back at rest. The
 * §17 specular band's own sweep: the same "a light catching glass" gesture, and the
 * eye reads anything past this as a second, separate animation.
 */
internal val RIM_SETTLE_MS = FolioTokens.motionLiquidSweep

/**
 * How far reading progress may move the parked head, as a fraction of a turn.
 *
 * Half, so a book at 0% and one at 100% do not collide by wrapping: the light
 * travels the top-left half of the outline and never crosses itself.
 */
private const val RIM_PROGRESS_SWING = 0.5f

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
internal const val RIM_PARKED_HEAD = 0.875f

private fun wrapToOne(x: Float): Float = x - floor(x)

/**
 * One scroll event's motion, as 0..1.
 *
 * Rate, not displacement: [consumedPx] alone would make a slow drag that happens to
 * cover 60 px look like a fling that covered 60 px in a sixth of the time. Dividing
 * by the measured gap between events is also what keeps the number honest when the
 * platform delivers fewer, larger events on a busy frame.
 */
internal fun rimSpeedLevel(consumedPx: Float, gapNanos: Long): Float {
    val px = if (consumedPx >= 0f) consumedPx else -consumedPx
    if (px <= 0f) return 0f
    val seconds = (if (gapNanos > 0L) gapNanos else DEFAULT_EVENT_GAP_NS) / 1_000_000_000f
    return (px / seconds / RIM_SPEED_REFERENCE_PX_PER_S).coerceIn(0f, 1f)
}

/**
 * The envelope: how strong a rim still looks [ageMs] after the shelf last moved.
 *
 * Deliberately a pure function of age rather than a scheduled animation. The
 * consumers are cells of a `LazyVerticalGrid`, which disposes them on scroll-out — so
 * a `LaunchedEffect` or an `Animatable` doing the decay dies with the cell and the
 * rim stays lit. Riding the 30 Hz tick the beam *already* owns costs no frames, and
 * returning exactly 0f past the window means the resting picture is the designed one
 * rather than an asymptotic cousin of it.
 */
internal fun rimVelocityLevel(raw: Float, ageMs: Long): Float {
    if (raw <= 0f) return 0f
    if (ageMs >= RIM_SETTLE_MS) return 0f
    return (raw * (1f - ageMs.toFloat() / RIM_SETTLE_MS.toFloat())).coerceIn(0f, 1f)
}

/**
 * Gain applied to both budgets. Quadratic so a drag — the thing that happens most of
 * the time — lands near zero, and only a fling reaches the caps. A linear map would
 * switch this on during every scroll, and the state it puts the rim in would be one
 * nobody has looked at.
 */
internal fun rimSpeedGain(level: Float): Float {
    val l = level.coerceIn(0f, 1f)
    return l * l
}

/**
 * The beam's width and peak alpha at a given velocity level, in one place so the
 * ceilings the tests assert are the ceilings the rim actually draws. Rest must return
 * the two constants exactly, which is what makes an unmodulated rim byte-identical to
 * the beam that shipped before speed existed.
 */
internal fun rimBeamShape(level: Float): Pair<Float, Float> {
    val gain = rimSpeedGain(level)
    return (RIM_BEAM_SPAN + RIM_BEAM_SPAN * RIM_SPAN_GAIN * gain) to
        (RIM_HEAD_ALPHA * (1f + RIM_ALPHA_GAIN * gain))
}

/**
 * Where the light parks for a surface at [progress] through the book.
 *
 * Null — or a book not started — is exactly [RIM_PARKED_HEAD], so the resting
 * picture is the one that shipped before progress entered the maths.
 */
internal fun rimParkedHead(progress: Float?): Float =
    wrapToOne(RIM_PARKED_HEAD + (progress ?: 0f).coerceIn(0f, 1f) * RIM_PROGRESS_SWING)

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
 *
 * [span] and [headAlpha] exist so scroll speed can widen and lift the beam without
 * touching a single colour: the mix curve below is untouched by them, so the hue set
 * at rest and the hue set mid-fling are the same samples. That is the executable form
 * of "a second hue may only arrive by fading out of this surface's own colour" — a
 * moving light may get bigger and brighter, never get re-coloured.
 */
internal fun rimBeamColors(
    turn: Float,
    accent: Color,
    counterAccent: Color,
    span: Float = RIM_BEAM_SPAN,
    headAlpha: Float = RIM_HEAD_ALPHA,
): List<Color> {
    val tail = lerp(counterAccent, accent, RIM_TAIL_TOWARD_SURFACE)
    // The unlit region is the tail at zero alpha rather than Color.Transparent: a
    // gradient interpolates hue too, and Transparent is (0,0,0,0) — an undefined hue
    // — so fading to it drags the light through black and greys the edge. This is
    // the same gamma-toe artefact ShimmerTest documents, and it leaves through the
    // trail's own colour instead.
    val unlit = tail.copy(alpha = 0f)
    val half = span / 2f
    return List(RIM_STOP_COUNT) { index ->
        // Distance back from the leading tip, so the centre of the arc sits at turn.
        val behind = wrapToOne(turn + half - index / RIM_STOP_COUNT.toFloat())
        if (behind >= span) {
            unlit
        } else {
            val along = behind / span
            // Convex, so the accent holds the leading two-thirds and the trail's hue
            // only arrives where the light is already faint.
            lerp(accent, tail, along.pow(1.3f))
                .copy(alpha = headAlpha * sin(PI.toFloat() * along).pow(1.15f))
        }
    }
}

/**
 * The rim, optionally coupled to how fast its shelf is moving and parked at how far
 * through the book the surface is.
 *
 * **Contract (Rule 19 — every effect declares its floor and its fallback):**
 *
 * | Condition | Result |
 * |---|---|
 * | Motion on, shelf scrolling | the same travelling beam, widened by up to [RIM_SPAN_GAIN] and lifted by up to [RIM_ALPHA_GAIN] as a function of scroll rate; period, hue and arc position unchanged |
 * | Motion on, at rest (or [velocity] null) | today's rim exactly — `rimSpeedGain(0f) == 0f`, and the two budgets multiply constants rather than replacing them |
 * | Reduce-motion | [animate] false ⇒ no clock, so no ticks and no gain to read; the head parks at [rimParkedHead] of [parkedAt], which is [RIM_PARKED_HEAD] exactly for a book not started |
 * | Desktop / previews / unit tests | [LocalFolioScrollVelocity] is never provided ⇒ both draw-phase reads see null ⇒ receiver unchanged, byte-for-byte |
 *
 * There is no API floor here: this is Canvas-level drawing, so the effect's ceiling
 * and its floor are the same picture on every device it runs on.
 */
@Composable
fun Modifier.folioThemeRim(
    shape: Shape,
    accent: Color,
    counterAccent: Color,
    width: Dp = 1.5.dp,
    animate: Boolean = rememberMotionEnabled(),
    /** The shelf's scroll motion. Null means "this surface has no scroll source",
     *  which is what Home's hero and every desktop tree pass. */
    velocity: FolioScrollVelocity? = null,
    /** How far through the book this surface is, 0..1, or null for none. */
    parkedAt: Float? = null,
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
        // The parked head is resolved once per cache pass, not per frame: progress
        // changes when a book is read, not while a rim travels.
        val parked = rimParkedHead(parkedAt)
        onDrawBehind {
            // Both motion reads live here and nowhere else. Hoisting them into the
            // cache block would make a plain captured value part of the cache's
            // identity, and every frame of a fling would rebuild the path and the
            // three strokes above.
            val level = velocity?.let {
                rimVelocityLevel(
                    it.speed.floatValue,
                    (System.nanoTime() - it.stamp.longValue) / 1_000_000L,
                )
            } ?: 0f
            val (span, headAlpha) = rimBeamShape(level)
            val turn = phases?.value?.getOrNull(0)
            // The clock's phase runs 0..2π and the sweep takes a fraction of a
            // turn, so the wrap is exact: a full circuit lands precisely where it
            // started and there is no restart to hide. Progress shifts where the
            // circuit *begins*; it never touches the rate, so a book half-read does
            // not travel any quicker — and a fling cannot, either.
            val head = if (turn == null) {
                parked
            } else {
                wrapToOne(turn / TWO_PI + (parkedAt ?: 0f).coerceIn(0f, 1f) * RIM_PROGRESS_SWING)
            }
            val beam = Brush.sweepGradient(
                rimBeamColors(head, accent, counterAccent, span, headAlpha),
                center,
            )
            clipPath(path) {
                drawPath(path, base, style = edge)
                drawPath(path, beam, style = bloom)
                drawPath(path, beam, style = ring)
            }
        }
    }
}
