package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room is actually lit.
 *
 * [FolioAtmosphere] gives every palette one *fixed* light source — above and
 * slightly leading — and every material shades itself from that one direction
 * forever. That is a lighting rig, not a room. A room has a window, and what
 * comes through it depends on the hour: low warm light at 7am, hard neutral
 * light at noon, amber raking light at 6pm, and a cool dim nothing overnight.
 * This derives that hour from the reader's own clock so the app visibly looks
 * different in the morning than it does at night — without ever looking lit by
 * a coloured bulb.
 *
 * It is a **separate additive layer** on top of the atmosphere, never a rewrite
 * of it. `atmosphereFor` and its contrast-pinned outputs (§15, and the barGlass
 * band `DesignSystemTest` guards) are untouched; daylight only ever *mixes a
 * small fraction* into the rims the atmosphere already computed and *shifts the
 * direction* they run in. Direction is free — it costs no contrast. Colour is
 * rationed: see [DAYLIGHT_RIM_TINT_MAX] and [DAYLIGHT_WASH_ALPHA_MAX].
 *
 * Pure on purpose: [daylightAt] takes the time as a parameter and reads no
 * clock, so the whole day's curve is unit-testable without Compose (see
 * `DaylightTest`). Only [rememberDaylight] reads the live clock.
 *
 * Rule 19: there is deliberately no `animate*AsState` anywhere in this layer.
 * The value steps once a minute — a clock refresh, not motion — and because
 * [daylightAt] is continuous in time that step is far below the eye's threshold.
 * So under reduce-motion the room renders its correct static value for the
 * current hour and simply keeps it correct as the hour moves; it never freezes
 * on a stale value and never animates.
 */
@Immutable
data class FolioDaylight(
    /**
     * Sun height, 0..1. 0 is below the horizon (night), 1 is solar noon
     * straight overhead. Drives how *directional* the light is: a low sun rakes
     * across a surface, a high one lands flat on top, and no sun at all leaves
     * the material at the atmosphere's neutral.
     */
    val elevation: Float,
    /**
     * Horizontal light direction, -1..+1: leading edge (start) to trailing edge
     * (end). Morning light comes in from one side, noon from straight ahead,
     * evening from the other. This is the free axis — swinging it costs contrast
     * nothing, so it may travel its full range.
     */
    val azimuth: Float,
    /**
     * The colour temperature of the light: warm amber when the sun is low,
     * neutral white when it is high, cool blue-black at night. Always an opaque
     * source colour (alpha 1) — consumers ration how much of it they mix in.
     */
    val temperature: Color,
    /**
     * Multiplier on rim strength and tint, 0..1. This is the restraint knob:
     * every daylight contribution in the material system scales by it, so at
     * night (a small floor, see below) the room collapses back toward the
     * palette's own neutral instead of inventing light that is not there.
     */
    val intensity: Float,
    /** True while the sun is below civil twilight — the genuinely dark band. */
    val isNight: Boolean,
) {
    companion object {
        /**
         * The zero-impact daylight. Straight overhead, neutral white, no
         * intensity: everything the materials derive from daylight collapses to
         * the atmosphere's own fixed lighting, so an un-provided tree — a unit
         * test, a preview, any surface composed outside a themed root — renders
         * exactly as it did before the room learned what time it was. This is the
         * [LocalFolioDaylight] default and the reason the whole feature is safe
         * to leave switched off.
         */
        val Neutral = FolioDaylight(
            elevation = 1f,
            azimuth = 0f,
            temperature = NEUTRAL_WHITE,
            intensity = 0f,
            isNight = false,
        )
    }
}

// ── the subtlety envelope ─────────────────────────────────────────────────
//
// Daylight is air, not a filter. These two ceilings are the whole promise, and
// DaylightTest pins them: no material may mix more than RIM_TINT toward the sun
// colour (a tint, never a replacement) and no full-surface wash may exceed
// WASH_ALPHA (low enough that it cannot move a WCAG ratio off its floor). Both
// are multiplied by `intensity`, which is ≤ 1, so the effective values only ever
// sit below these.

/** Max lerp fraction a rim may travel toward [FolioDaylight.temperature]. */
const val DAYLIGHT_RIM_TINT_MAX = 0.28f

/** Max alpha of an opt-in full-surface daylight wash. Kept at the `folioField`
 *  gradient's own "~4%, only as air" register — 0.06 is already generous. */
const val DAYLIGHT_WASH_ALPHA_MAX = 0.06f

// ── the light's own colours ───────────────────────────────────────────────

/** Solar noon: neutral white with the faintest warmth, so a hard midday light
 *  never turns clinical. Near-achromatic on purpose — DaylightTest asserts it. */
private val NEUTRAL_WHITE = Color(0xFFFFFEFB)

/** Golden hour: the low sun's amber. Only ever mixed in at ≤ [DAYLIGHT_RIM_TINT_MAX]
 *  × intensity, so this can be honestly warm without the page going orange. */
private val WARM_AMBER = Color(0xFFFFB27A)

/** Overnight: a deep, desaturated blue-black. Cold and dim, not saturated — a
 *  moonlit room is short of light, not bathed in blue. */
private val NIGHT_COOL = Color(0xFF1B2740)

// ── the day's shape ───────────────────────────────────────────────────────
//
// Two 24-entry keyframe tables, one per hour, sampled by a periodic Catmull-Rom
// so the curve is C1 (no slope kink at a keyframe) and wraps cleanly across
// midnight. Both are symmetric about noon (keys[h] == keys[24 - h]), which is
// what makes dawn and dusk mirror each other, and both flatten to a genuinely
// dark band from ~20:00 to ~04:00. Catmull-Rom passes exactly through each
// keyframe, so solar noon is the elevation peak to the float.

/**
 * Sun height by hour. Civil dawn/dusk sit at 6/18 (0.08), first and last light
 * at 5/19 (0.01), and the deep night band is a hard 0.00 from 20:00 to 04:00 —
 * "almost no directional light at all", per the brief.
 */
private val ELEVATION_KEYS = floatArrayOf(
    0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.01f, 0.08f, 0.25f,
    0.45f, 0.63f, 0.78f, 0.92f, 1.00f, 0.92f, 0.78f, 0.63f,
    0.45f, 0.25f, 0.08f, 0.01f, 0.00f, 0.00f, 0.00f, 0.00f,
)

/**
 * Rim strength by hour. Tracks elevation but keeps a small floor (0.05) through
 * the night — a whisper of cool moonlight rather than a literal zero, so an
 * overnight room is dim and cold without being flat. Midnight dips to 0.04, the
 * day's single darkest minute.
 */
private val INTENSITY_KEYS = floatArrayOf(
    0.04f, 0.05f, 0.05f, 0.05f, 0.05f, 0.07f, 0.14f, 0.33f,
    0.55f, 0.72f, 0.85f, 0.95f, 1.00f, 0.95f, 0.85f, 0.72f,
    0.55f, 0.33f, 0.14f, 0.07f, 0.05f, 0.05f, 0.05f, 0.05f,
)

/** Below this sun height it is night (civil twilight ends/starts here). */
private const val NIGHT_ELEVATION = 0.06f

/**
 * The vertical floor on the light direction. A sun exactly on the horizon has no
 * "up" to come from, so the direction vector keeps at least this much downward
 * component — which is what collapses a dark room back to the neutral top-light
 * instead of raking it sideways from a sun that is not up.
 */
private const val HORIZON_FLOOR = 0.15f

/** Sun height below which the light reads as low-and-warm. */
private const val LOW_SUN_ELEVATION = 0.22f

/** Sun height above which the light reads as high-and-neutral. */
private const val HIGH_SUN_ELEVATION = 0.75f

private const val PI_F = 3.14159265f

/**
 * The room's light at a local time of day. Pure: pass the hour and minute, get
 * back the sun. Continuous to the minute and periodic across midnight, so 23:59
 * and 00:00 are effectively the same room.
 */
fun daylightAt(hour: Int, minute: Int = 0): FolioDaylight {
    // Normalise to a fractional hour in [0, 24) so the tables and the azimuth
    // sinusoid both wrap cleanly and a stray 24 or -1 cannot index out of range.
    val t = (((hour + minute / 60f) % 24f) + 24f) % 24f

    val elevation = sampleDay(ELEVATION_KEYS, t).coerceIn(0f, 1f)
    val intensity = sampleDay(INTENSITY_KEYS, t).coerceIn(0f, 1f)

    // Azimuth is a single sinusoid over the day rather than a table: -1 at the
    // 6am horizon, 0 overhead at noon, +1 at the 6pm horizon, and back through 0
    // at midnight. One smooth periodic function, so it is antisymmetric about
    // noon (morning mirrors evening) and continuous across the wrap by
    // construction.
    val azimuth = sin(PI_F * (t - 12f) / 12f)

    return FolioDaylight(
        elevation = elevation,
        azimuth = azimuth,
        temperature = temperatureFor(elevation),
        intensity = intensity,
        isNight = elevation < NIGHT_ELEVATION,
    )
}

/**
 * The unit light direction across a surface, as (x, y) with y negative meaning
 * "from above". The horizontal push is scaled by elevation, so a sun low on the
 * horizon cannot throw a hard sideways catch, and once the sun is down
 * (elevation 0) the vector collapses to straight down-from-top — the
 * atmosphere's own neutral. This is the free axis: direction costs no contrast.
 */
fun FolioDaylight.lightDirection(): Pair<Float, Float> {
    val horiz = azimuth * elevation
    val vert = -max(elevation, HORIZON_FLOOR)
    val len = sqrt(horiz * horiz + vert * vert)
    return if (len < 1e-4f) 0f to -1f else (horiz / len) to (vert / len)
}

/**
 * Colour temperature as a function of sun height only, so dawn and dusk (equal
 * elevation) share one temperature. Rises cool→amber through twilight, then
 * amber→neutral-white as the sun climbs, and is neutral-white from
 * [HIGH_SUN_ELEVATION] up. `lerp` interpolates in Oklab, which keeps the passage
 * perceptually even — no green band between the amber and the white.
 */
private fun temperatureFor(elevation: Float): Color = when {
    elevation <= 0f -> NIGHT_COOL
    elevation < LOW_SUN_ELEVATION ->
        lerp(NIGHT_COOL, WARM_AMBER, (elevation / LOW_SUN_ELEVATION).coerceIn(0f, 1f))
    elevation < HIGH_SUN_ELEVATION ->
        lerp(
            WARM_AMBER,
            NEUTRAL_WHITE,
            ((elevation - LOW_SUN_ELEVATION) / (HIGH_SUN_ELEVATION - LOW_SUN_ELEVATION))
                .coerceIn(0f, 1f),
        )
    else -> NEUTRAL_WHITE
}

/**
 * Periodic Catmull-Rom over a 24-key table at fractional hour [t]. Tangents wrap
 * (index -1 is 23), which is what makes the curve continuous — in value *and*
 * slope — across midnight rather than merely meeting at it.
 */
private fun sampleDay(keys: FloatArray, t: Float): Float {
    val n = keys.size
    val i = floor(t).toInt()
    val f = t - i
    val p0 = keys[(i - 1 + n) % n]
    val p1 = keys[i % n]
    val p2 = keys[(i + 1) % n]
    val p3 = keys[(i + 2) % n]
    val f2 = f * f
    val f3 = f2 * f
    return 0.5f * (
        (2f * p1) +
            (-p0 + p2) * f +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * f2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * f3
        )
}

// ── the Compose half ──────────────────────────────────────────────────────

/**
 * The room's light. Provided at the theme root ([FolioTheme.MaterialTheme]) so
 * every material reads it for free; defaults to [FolioDaylight.Neutral], which
 * is the zero-impact daylight that leaves an un-themed tree exactly as it was.
 *
 * Dynamic, not static, on purpose: the value is recomputed from the clock once a
 * minute, and a `staticCompositionLocalOf` does not track individual reads — so
 * every tick would invalidate the whole subtree below the theme root instead of
 * just the surfaces actually drawing the light. Same reason [LocalFolioBarInset]
 * is dynamic for scroll.
 */
val LocalFolioDaylight = compositionLocalOf { FolioDaylight.Neutral }

/**
 * The live daylight, read from the reader's own clock and refreshed once a
 * minute. A minute is far finer than the eye can follow on a curve this smooth
 * and far coarser than a per-frame animation, so this is a data cadence, not
 * motion — which is exactly what Rule 19 wants under reduce-motion.
 *
 * Reads the clock the way `StatisticsViewModel` already does: system instant,
 * projected into [TimeZone.currentSystemDefault], so "7am" means the reader's
 * 7am wherever they are.
 */
@Composable
fun rememberDaylight(): FolioDaylight {
    var instant by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            instant = Clock.System.now()
        }
    }
    return remember(instant) {
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        daylightAt(local.hour, local.minute)
    }
}

/** One refresh a minute; see [rememberDaylight]. */
private const val REFRESH_MS = 60_000L

/** The active daylight. Provided at the theme root; neutral if absent. */
val FolioTheme.daylight: FolioDaylight
    @Composable
    @ReadOnlyComposable
    get() = LocalFolioDaylight.current
