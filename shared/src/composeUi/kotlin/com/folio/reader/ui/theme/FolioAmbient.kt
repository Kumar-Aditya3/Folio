package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.PI

/**
 * §17 living glass — the room's air, moving.
 *
 * [FolioDaylight] made every surface's light depend on the hour, but the hour
 * steps once a minute and is far below the eye's threshold on purpose: it is a
 * clock, not motion. Liquid glass has the opposite need — blur and specular
 * only read as *liquid* while something behind or upon them changes, and a page
 * at rest changes nothing. This layer is the deliberate complement: one very
 * slow animated light, shared by the whole tree, that swings the direction
 * every material's sheen runs in and drifts the field's colour pools. A bar
 * over a still page now catches a highlight that is imperceptibly travelling,
 * and the capsule floats over a temperature that is imperceptibly shifting —
 * which is all the motion glass needs to read as material rather than tint.
 *
 * **Subtlety envelope.** Two ceilings, pinned by `AmbientLightTest` exactly as
 * daylight's are:
 *  - the light's azimuth may travel at most [AMBIENT_SWING_MAX] from wherever
 *    the hour put it (and only in proportion to daylight intensity, so a night
 *    room stays nearly still — the room does not invent a midnight sun);
 *  - a field pool's centre may drift at most [AMBIENT_DRIFT_MAX] of the width.
 *  Both are slower than any eye tracks as animation: the swing period is ~47s
 *  and the breath ~71s, distinct primes so they never beat in sync.
 *
 * **Cost model.** The state is read in the *draw phase only* (inside
 * `onDrawBehind`, the `FolioShimmer` precedent), so an animated room costs one
 * drawing pass per lit surface and zero recompositions. Surfaces without a
 * directional sheen — [folioPanel], rows, covers — never read it and never
 * redraw for it.
 *
 * **Rule 19.** This is motion, so it is gated on [rememberMotionEnabled]:
 * reduce-motion parks the light at [FolioAmbient.Neutral], which is the
 * identity — every consumer renders exactly what it rendered before the room
 * learned to breathe. The default of [LocalFolioAmbient] is the same neutral,
 * for the same reason an un-provided tree (desktop, previews, unit tests)
 * renders today's look byte-for-byte.
 */
@Immutable
data class FolioAmbient(
    /**
     * Horizontal light offset, -1..1, mapping onto ±[AMBIENT_SWING_MAX] of
     * azimuth around the daylight's own. Sine-driven, so it eases to rest at
     * each extreme instead of snapping direction.
     */
    val swing: Float,
    /**
     * Vertical breathing phase, 0..1. Drives the field pools' vertical drift
     * (and nothing else yet); centred at 0.5 so [FolioAmbient.Neutral] means
     * "no displacement", not "displaced to one edge".
     */
    val breath: Float,
) {
    companion object {
        /** The still room. Identity for every consumer; see the class doc. */
        val Neutral = FolioAmbient(swing = 0f, breath = 0.5f)
    }
}

/** Max azimuth the ambient light may add to (or subtract from) the daylight's. */
const val AMBIENT_SWING_MAX = 0.35f

/** Max drift of a field pool's centre, as a fraction of the field's width. */
const val AMBIENT_DRIFT_MAX = 0.05f

/**
 * The swing and breath periods, in ms. Coprime-ish and both past ~45s on
 * purpose: past that a moving light stops reading as animation and starts
 * reading as a room (the same argument that holds §13.4's mesh at 18–30s, one
 * register slower).
 */
internal val AMBIENT_PERIODS_MS = listOf(47_000L, 71_000L)

/**
 * The effective azimuth after the ambient light's contribution — the one pure
 * seam between this layer and [FolioDaylight]. Scaled by daylight intensity so
 * the ambient inherits the room's night discipline instead of fighting it, and
 * exact (no floating noise) at [FolioAmbient.Neutral] so the identity is
 * byte-for-byte.
 */
fun ambientAzimuth(daylight: FolioDaylight, ambient: FolioAmbient): Float =
    daylight.azimuth + ambient.swing * AMBIENT_SWING_MAX * daylight.intensity

/**
 * The daylight with the ambient's swing folded in. Consumers that shade a
 * surface ([folioRaised], [folioSunken], the veil's specular, the masthead's
 * crown) pass the result to their gradient axis; colour and intensity pass
 * through untouched, because the ambient moves the light, it is not a second
 * one.
 */
fun FolioDaylight.swungBy(ambient: FolioAmbient): FolioDaylight =
    if (ambient.swing == 0f) this else copy(azimuth = ambientAzimuth(this, ambient))

/**
 * A field pool's ambient displacement as (x, y) fractions of the field. The
 * pools are enormous radial gradients, so ±[AMBIENT_DRIFT_MAX] of centre is a
 * shift of temperature rather than a sliding shape — the same distinction
 * [POOL_DRIFT] already draws for the sun's own drift.
 */
fun ambientPoolDrift(ambient: FolioAmbient): Pair<Float, Float> =
    (ambient.swing * AMBIENT_DRIFT_MAX) to
        ((ambient.breath - 0.5f) * 2f * AMBIENT_DRIFT_MAX)

/**
 * The living light. Provided once at the app root (Android); the default for
 * an un-provided tree is the still room, so desktop and tests are unchanged.
 *
 * A `State` rather than a plain value because every consumer must read it in
 * the draw phase — a plain value read in composition would recompose the
 * subtree every frame, which is the one cost this layer is not allowed. The
 * default is one shared instance holding the immutable neutral: every
 * un-provided consumer reads the same still room instead of minting its own.
 */
private val STILL_ROOM: State<FolioAmbient> = mutableStateOf(FolioAmbient.Neutral)

val LocalFolioAmbient = compositionLocalOf<State<FolioAmbient>> { STILL_ROOM }

/**
 * How often the two never-ending animations in the app are re-sampled — this
 * light and §13.4's hero mesh — in milliseconds.
 *
 * Both are slow past the point where their *value* depends on the sampling rate
 * at all. A 47s swing advances its phase by 0.0134 rad per 100ms tick — 0.27° of
 * azimuth at [AMBIENT_SWING_MAX], a fraction of a percent of the light's
 * displacement — and the mesh's shortest cycle is 18 seconds, where a tick moves
 * a pool centre by ~1% of its own radius. Ten samples a second of either is
 * indistinguishable from ninety.
 *
 * Their *cost*, however, was not rate-independent. Both are read in the draw
 * phase by surfaces that cover the screen — the light by `folioField`, the mesh
 * by the hero — so a per-frame read invalidated the whole page every frame and
 * the app never went idle. Measured on the test device (1080x2392 @90Hz,
 * `dumpsys gfxinfo … framestats`): at rest the app rendered ~118 frames in four
 * seconds with 100% of them over the 90Hz budget, and with motion disabled it
 * rendered **zero**. The room was paying a full-screen redraw, forever, to move
 * a light far below the eye's threshold.
 *
 * So the clock ticks on its own schedule instead of the display's. Between ticks
 * nothing is invalidated, the frame loop stops, and the device is free to fall
 * back to its resting refresh rate. The motion is unchanged: the phase is
 * computed from elapsed time, so a tick that lands late still reports the phase
 * that instant actually has — the light is never behind, it simply is not drawn
 * in between. A drift-and-catch-up clock would make the room stutter, which is
 * the one thing an effect whose whole job is to be below notice cannot do.
 */
internal const val SLOW_MOTION_TICK_MS = 100L

/** One whole turn, the value every phase in this file is expressed against. */
internal val TWO_PI = (2.0 * PI).toFloat()

/** `withFrameNanos` counts nanoseconds; every period in this file is milliseconds. */
private const val NANOS_PER_MS = 1_000_000L

/**
 * The phase, in 0..2π, that a [periodMs] oscillator has reached after
 * [elapsedMs] of running. One whole turn per period, linear — the same ramp
 * `tween(periodMs, LinearEasing)` produces, which is what §17 and §13.4 both
 * specified.
 *
 * Pure and public-internal so `AmbientLightTest` can pin the *rate*: the one
 * property of this clock that a reader cannot check by looking at it, and the
 * one this file has already got wrong once (an elapsed time in nanoseconds
 * against a period in milliseconds wraps every 47µs, not every 47s, and strobes
 * the light). See [SLOW_MOTION_TICK_MS].
 */
internal fun slowPhaseAt(elapsedMs: Long, periodMs: Long): Float {
    val ms = periodMs.coerceAtLeast(1L)
    val elapsed = elapsedMs.coerceAtLeast(0L)
    return ((elapsed % ms).toFloat() / ms.toFloat()) * TWO_PI
}

/**
 * [periodsMs].size phases, each advancing 0..2π once per its own period, re-sampled
 * every [tickMs] rather than every frame — see [SLOW_MOTION_TICK_MS].
 *
 * Returns a `State` of the phase list, so callers keep reading it in the draw
 * phase and never recompose for it. Reduce-motion is *not* handled here: the
 * callers own that decision, and they park their own value at its static form.
 */
@Composable
internal fun rememberSlowPhases(
    periodsMs: List<Long>,
    tickMs: Long = SLOW_MOTION_TICK_MS,
): State<List<Float>> {
    val phases = remember(periodsMs, tickMs) { mutableStateOf(periodsMs.map { 0f }) }
    LaunchedEffect(periodsMs, tickMs) {
        // `delay` for the spacing, not `withFrameNanos`: asking the Choreographer
        // for a frame every vsync would keep the frame loop alive and defeat the
        // whole point. One frame request per tick is all this needs.
        val origin = withFrameNanos { it }
        while (true) {
            delay(tickMs)
            val now = withFrameNanos { it }
            // Nanoseconds in, milliseconds out — see slowPhaseAt.
            val elapsedMs = (now - origin).coerceAtLeast(0L) / NANOS_PER_MS
            phases.value = periodsMs.map { period -> slowPhaseAt(elapsedMs, period) }
        }
    }
    return phases
}

/**
 * The ambient light's clock: two sines on distinct slow periods, combined into
 * one [FolioAmbient]. Frozen at [FolioAmbient.Neutral] under reduce-motion.
 *
 * Sampled on [SLOW_MOTION_TICK_MS] rather than per frame; see there for why a
 * light this slow is the same picture at 10Hz and why the frames it gives back
 * are the difference between an app that idles and one that does not.
 */
@Composable
fun rememberAmbientLight(): State<FolioAmbient> {
    if (!rememberMotionEnabled()) {
        return remember { mutableStateOf(FolioAmbient.Neutral) }
    }
    val phases = rememberSlowPhases(AMBIENT_PERIODS_MS)
    // Full-circle phases rather than ±1 triangles: a triangle wave kinks its
    // slope at each extreme, and a kink is a visible tick in something whose
    // entire job is to be below notice.
    return remember(phases) {
        derivedStateOf {
            val p = phases.value
            FolioAmbient(
                swing = sin(p[0]),
                breath = 0.5f + 0.5f * sin(p[1]),
            )
        }
    }
}
