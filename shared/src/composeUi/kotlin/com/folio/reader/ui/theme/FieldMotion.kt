package com.folio.reader.ui.theme

import androidx.compose.runtime.Immutable

/**
 * The background's timing, as data — the field's per-family rate and amplitude, and every slow
 * layer's period and tick.
 *
 * ## Why it lives here rather than in the Android dispatch
 *
 * These values were previously scattered: twenty `speed` literals inside `MeshField.android.kt`'s
 * 25-branch `when`, two of them binary `isDark` pairs, and nine periods across four packages
 * (`FolioAmbient`, `SilkField`, `FolioThemeRim`, `FolioLamp`, the guilloche and glass shaders).
 * Nothing could be retuned without finding each one, and nothing could be *asserted* — the field is
 * Android-only, so no desktop test could reach any of it.
 *
 * Declaring the numbers here makes them platform-neutral and testable, and gives the two facts that
 * matter one home:
 *
 * - **Which families can move at all.** Five of the twenty-five have no `u_time` uniform in their
 *   shader ([isClockless]), so no speed will ever animate them. The picker used to describe two of
 *   them as motion ("Wave bands rolling across", "a quiet breathing dot grid"); the type now says
 *   otherwise, and Phase 4 drives those two from Folio's own tick instead.
 * - **That the rate costs nothing.** `speed` scales the shader's internal clock, not the redraw
 *   rate — the field repaints every vsync regardless — which is why the motion levers can change it
 *   freely. Cost is governed by [FolioFieldCost] (render scale) and the occluder (presence).
 */
internal object FieldMotion {

    // ── Slow layers ────────────────────────────────────────────────────────────
    // The ambient light: a horizontal swing and a vertical breath, on distinct primes so they never
    // beat in sync. Both past ~45 s on purpose — past that a moving light stops reading as animation
    // and starts reading as a room.
    const val ambientSwingPeriodMs: Long = 47_000L
    const val ambientBreathPeriodMs: Long = 71_000L
    const val ambientTickMs: Long = 100L

    /**
     * The silk ground plane's three drift periods. The row in [motionProfile] carries the first;
     * the painter reads all three so the ramp, the pools and the sheen never lock together.
     */
    val silkPeriodsMs: List<Long> = listOf(37_000L, 53_000L, 43_000L)
    val silkPeriodMs: Long get() = silkPeriodsMs.first()
    const val silkTickMs: Long = 50L

    /** The silk *strands*' own period and tick: 48 s at 16 ms. Dead code with no call site, carried
     * here so the one table still describes every slow layer the app has rather than forgetting
     * three of them. */
    const val silkStrandPeriodMs: Long = 48_000L
    const val silkStrandTickMs: Long = 16L

    /** The themed rim's travelling beam: 24 s, sampled at 30 Hz. */
    const val rimPeriodMs: Long = 24_000L
    const val rimTickMs: Long = 33L

    /** The period driving DotGrid's parameter clock; Waves owns a travelling phase now. */
    const val clocklessPeriodMs: Long = 41_000L
    const val clocklessTickMs: Long = SLOW_MOTION_TICK_MS

    /** The lamp's bloom: the slowest of the set, so the room's light never appears to pulse. */
    const val lampPeriodMs: Long = 61_000L
    const val lampTickMs: Long = 100L
}

/**
 * A family's amplitude at the two ends of the face range.
 *
 * Most families are the same on both faces — one number. Three are not: `GrainGradient` was authored
 * as a lit wall by day and a dim one at night (0.38 / 0.26), `GodRays` is volumetric light and has
 * to fall much further on a near-black page (0.65 / 0.42), and `NeuroNoise` was effectively flat.
 *
 * Holding both ends and interpolating ([at]) is what replaces the binary `if (isDark)` branches: a
 * future third face (a dimmed-light palette, a high-contrast mode) needs no new branch, and the
 * vibrancy lever can lift the dark end continuously instead of jumping between two points.
 */
@Immutable
data class FieldAmplitude(val dark: Float, val light: Float) {
    /** The amplitude for a face gain, 0 == the dark end, 1 == the light end. */
    fun at(gain: Float): Float {
        val g = gain.coerceIn(0f, 1f)
        return dark + (light - dark) * g
    }

    companion object {
        /** The common case: one value on both faces. */
        fun flat(v: Float) = FieldAmplitude(v, v)
    }
}

/**
 * Whether this family's shader has no time uniform at all, so **no** speed can animate it.
 *
 * DotGrid still needs a parameter clock. The redesigned Waves field has a real time uniform;
 * HalftoneDots and the two Static families remain intentionally still.
 */
internal fun FolioFieldKind.isClockless(): Boolean = when (this) {
    FolioFieldKind.DotGrid,
    FolioFieldKind.HalftoneDots,
    FolioFieldKind.StaticMeshGradient,
    FolioFieldKind.StaticRadialGradient -> true

    else -> false
}

/**
 * This family's authored rate, or `null` when the family cannot move ([isClockless]).
 *
 * Redesigned fields own their geometric displacement as well as this tempo. `MotionSchemaTest`
 * pins the rates; each is chosen for its motif before the reader's speed lever multiplies it.
 * Dot Orbit's original 0.9 rate stays unchanged.
 */
internal fun FolioFieldKind.fieldSpeed(): Float? = when (this) {
    // The quiet end: things that read as a room's air rather than as an effect.
    FolioFieldKind.GrainGradient -> 0.3f
    FolioFieldKind.Swirl -> 0.3f
    FolioFieldKind.PerlinNoise -> 0.8f
    FolioFieldKind.SimplexNoise -> 0.3f
    FolioFieldKind.Voronoi -> 0.8f

    // The middle: recognisable drift.
    FolioFieldKind.Warp -> 0.85f
    FolioFieldKind.SmokeRing -> 0.9f
    FolioFieldKind.ColorPanels -> 0.9f
    FolioFieldKind.Dithering -> 0.5f
    FolioFieldKind.GodRays -> 0.5f
    FolioFieldKind.NeuroNoise -> 0.5f
    FolioFieldKind.Spiral -> 0.8f
    FolioFieldKind.Waves -> 0.85f
    FolioFieldKind.GemSmoke -> 0.5f
    FolioFieldKind.Metaballs -> 0.9f
    FolioFieldKind.LiquidMetal -> 0.95f
    FolioFieldKind.Heatmap -> 0.85f

    // The loud end: the families whose motif *is* movement.
    FolioFieldKind.MeshGradient -> 0.9f
    FolioFieldKind.PulsingBorder -> 0.7f
    FolioFieldKind.Water -> 0.9f
    FolioFieldKind.DotOrbit -> 0.9f

    // Clockless — see [isClockless].
    FolioFieldKind.DotGrid,
    FolioFieldKind.HalftoneDots,
    FolioFieldKind.StaticMeshGradient,
    FolioFieldKind.StaticRadialGradient -> null
}

/**
 * This family's amplitude at each end of the face range. See [FieldAmplitude].
 *
 * Only the three families that already carried a dark/light pair keep one; every other family is
 * flat, and that is a statement about the design rather than an omission — a family whose figure is
 * a thin line (Dithering, HalftoneDots) reads the same at both ends of the room.
 */
internal fun FolioFieldKind.fieldAmplitude(): FieldAmplitude = when (this) {
    // The lit wall: authored as a wall by day, a dim one at night.
    FolioFieldKind.GrainGradient -> FieldAmplitude(dark = 0.26f, light = 0.38f)
    // Volumetric light is the loudest figure on a near-black page, so it falls furthest.
    FolioFieldKind.GodRays -> FieldAmplitude(dark = 0.42f, light = 0.65f)
    // Effectively flat already; kept as a pair so the gain can lift it if the dark end ever moves.
    FolioFieldKind.NeuroNoise -> FieldAmplitude(dark = 0.05f, light = 0.04f)

    else -> FieldAmplitude.flat(1f)
}

/** How much of the light-end amplitude a fully vivid dark face recovers. */
private const val FIELD_DARK_VIVID_LIFT = 0.15f

/**
 * The face gain: 0 at the dark end, 1 at the light end, with the vibrancy lever lifting the dark end
 * continuously.
 *
 * This is the function the "too bright in dark mode" reports were reaching for by hand. Each of those
 * was fixed by editing one family's literal, which over-darkened the same family for everyone else —
 * because a binary `if (isDark)` has no room for "a little brighter". The gain has room, and the
 * vibrancy lever already exists and is already continuous, so the two become one number.
 *
 * Light faces are always 1: there is nothing to lift.
 */
internal fun fieldGain(isDark: Boolean, vibrancy: Float): Float {
    if (!isDark) return 1f
    val up = ((vibrancy - VIBRANCY_NEUTRAL) / (1f - VIBRANCY_NEUTRAL)).coerceIn(0f, 1f)
    return up * FIELD_DARK_VIVID_LIFT
}
