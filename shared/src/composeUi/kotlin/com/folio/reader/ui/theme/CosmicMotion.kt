package com.folio.reader.ui.theme

/**
 * Centralized cosmic motion tokens — every duration, speed and **amplitude** the cosmic
 * system animates on, in one place, so motion is tunable here instead of as literals
 * scattered across the primitives (Rule 2, applied to §cosmic).
 *
 * The doctrine, straight from the art direction: motion must be **noticeable but calm**
 * on both polarities. Everything rides the house idle slow clock
 * ([SLOW_MOTION_TICK_MS]); nothing fast, no parallax, no rapid gradient change; the only
 * "event" motion is a rare shooting star. Under reduce-motion every one of these freezes
 * to a static final frame (the primitives park their phase at 0, exactly as the field
 * does), so these values describe the *lively* case only.
 *
 * The periods are deliberately coprime-ish and span different registers so the layers
 * never beat in sync into a single visible pulse — the same reasoning [AMBIENT_PERIODS_MS]
 * uses one register slower.
 */
object CosmicMotion {

    // ── Durations / periods (ms) ────────────────────────────────────────────────────

    /** One very slow turn of an orbital ring. A ring that reads as *moving* is wrong. */
    const val orbitDurationMs = 90_000L

    /** One drift cycle of gold sunlight particles (light faces) and cosmic dust. */
    const val particleSpeedMs = 24_000L

    /** One breath of a celestial glow — in and back out. */
    const val glowBreathMs = 7_000L

    /** One slow drift cycle of the nebula pools. Matches the field's 30s signature clock. */
    const val nebulaDriftMs = 30_000L

    /**
     * One twinkle cycle of a star, in a few seconds rather than the field's old 30s — the
     * single change that fixes "the twinkle is too faint to notice". Per-star phase and a
     * per-star speed scatter it so stars shimmer *out of sync*, a few at a time, never as
     * one global pulse. A few-second period reads smooth at the 10 Hz house clock.
     */
    const val starTwinkleMs = 4_200L

    /**
     * The base interval between shooting stars. Long and jittered per occurrence (the
     * primitive adds a random multiple), so the event stays rare — the one thing allowed
     * to actually catch the eye, and only once in a long while.
     */
    const val shootingStarIntervalMs = 42_000L

    /** How long a single shooting star takes to cross and fade. Brief, so it is an event. */
    const val shootingStarTravelMs = 1_100L

    // ── Amplitudes (fraction) ───────────────────────────────────────────────────────

    /**
     * Star twinkle depth: ±42% brightness around the resting value, raised from the old
     * ±~17% so a resting page visibly shimmers within a few seconds of looking.
     */
    const val twinkleAmplitude = 0.42f

    /** The resting star brightness the twinkle swings around (so peaks can glint). */
    const val twinkleBase = 0.70f

    /** A soft glow's breathing depth — a slow swell, never a flash. */
    const val glowBreathAmplitude = 0.14f

    /** Nebula drift reach, as a fraction of the field width. A temperature shift, not a slide. */
    const val nebulaDriftAmplitude = 0.03f

    /** Very slow orbital-ring wobble, in degrees, on top of the full rotation. */
    const val orbitWobbleDegrees = 2f

    // ── Ticks ─────────────────────────────────────────────────────────────────────────

    /**
     * The star layer's sampling tick. The house 10 Hz clock is smooth enough for a
     * few-second twinkle; the art direction allows dropping to [starTickFastMs] only if a
     * brighter glint reads steppy, trading some idle for smoothness. Default stays 10 Hz
     * so the frame loop can still fall idle.
     */
    const val starTickMs = SLOW_MOTION_TICK_MS

    /** The faster fallback tick (≈30 Hz), the register `folioThemeRim` already uses. */
    const val starTickFastMs = 33L
}
