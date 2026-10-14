package com.folio.reader.settings

import kotlinx.serialization.Serializable

/**
 * The reader's animation preferences: one **preset** plus a small set of **levers**.
 *
 * ## Why the surface is this small
 *
 * Folio has ~40 named animations and roughly a hundred tuned parameters behind them (durations,
 * easings, springs, intensities, background field speeds and every slow layer's period). Exposing
 * that table would be a settings screen nobody can use, and every value in it is a design decision
 * the app is better at making than the reader is — *what* the reader actually has an opinion about
 * is how much motion they want, not whether the selection toolbar is 180 ms or 200 ms.
 *
 * So the split is:
 *
 * - **System-tuned (fixed)**: every animation's own duration, easing, spring and intensity lives in
 *   `com.folio.reader.ui.theme.motionProfile` — an exhaustive table, `internal`, with no settings
 *   path at all. See [MotionPreset] for the three shipped profiles it was tuned against.
 * - **User-adjustable (this class)**: a preset, the seven channel levers below, and the
 *   reduce-motion mode. A lever is a *multiplier* on the fixed table, so a lever can never invent a
 *   duration, an easing or a curve the design never had.
 *
 * ## Semantics of a lever
 *
 * `null` means "follow the preset" and is what the UI shows as **Auto** — the default state, so an
 * install that never opens this section resolves exactly the shipped values. A non-null value
 * overrides the preset for that channel: durations **divide** by it (2.0 is twice as fast), and the
 * two background levers are direct factors (speed multiplies, intensity multiplies).
 *
 * ## Scope
 *
 * App-level, like [ReaderSettings.eyeProtection] and [ReaderSettings.minimalLiquidGlass] — motion is
 * a property of the reader's device and disposition, not of a book, so this is deliberately **not**
 * part of [BookReaderSettings] and no book ever snapshots it.
 *
 * Every field carries a default, so a settings row written by an older build decodes unchanged
 * (the serializer runs with `encodeDefaults = false`) and nothing migrates.
 */
@Serializable
data class MotionPreferences(
    /** The shipped motion profile. [MotionPreset.STANDARD] is today's tuned behaviour. */
    val preset: MotionPreset = MotionPreset.STANDARD,
    /** How this app responds to the platform's "remove animations" setting. See [ReduceMotionMode]. */
    val reduceMotion: ReduceMotionMode = ReduceMotionMode.FOLLOW_SYSTEM,
    /**
     * Background field speed, 0..2. Scales the shader's own clock, which is the one time lever the
     * field exposes — it costs no extra frames (the field redraws at vsync regardless), so this is
     * the cheapest lever in the app.
     */
    val fieldMotion: Float? = null,
    /** Background field amplitude, 0..1. A direct multiplier on each family's intensity. */
    val fieldIntensity: Float? = null,
    /** Reader chrome: the top/bottom bars, the floating rail, the side panels, the selection toolbar. */
    val readerChrome: Float? = null,
    /** Sheets, menus, dialogs, overlays and the navigation push/pop transitions. */
    val sheetsOverlays: Float? = null,
    /** The cover morph, the tab dissolve, the reader plate dissolve and the bookmark glyph swap. */
    val coverMorph: Float? = null,
    /** Progress rings and bars, the stats dial, the shimmer, the liquid sweep, the sync pill. */
    val progressFeedback: Float? = null,
    /** Entry staggers and chart bar growth. */
    val chartEntry: Float? = null,
)

/**
 * The three shipped motion profiles. Each is a set of channel multipliers over the fixed table, so
 * a profile cannot change *what* an animation is — only how quickly it plays.
 *
 * - [CALM] — for a reader who finds the app busy: the room moves at half speed, chrome and feedback
 *   take longer so nothing snaps, and the field is quieter.
 * - [STANDARD] — 1.0 everywhere: exactly the values the table was tuned to, and therefore exactly
 *   what the app shipped before this existed.
 * - [LIVELY] — for a reader who wants the interface to keep up: everything a little quicker and the
 *   field a little louder.
 */
@Serializable
enum class MotionPreset {
    CALM,
    STANDARD,
    LIVELY,
}

/**
 * How the app resolves reduce-motion.
 *
 * The platform flag (`Settings.Global.ANIMATOR_DURATION_SCALE` on Android) is a blunt instrument: it
 * exists for readers who are made unwell by motion, and it says nothing about a reader who simply
 * wants less of it. [FOLLOW_SYSTEM] honours it as before; [REDUCED] lets the reader ask for reduced
 * motion *without* disabling animation system-wide; [FULL] is the escape hatch for a reader who has
 * the platform setting off for unrelated reasons.
 *
 * Reduced motion is never a freeze. Each animation carries a genuine reduced spec — shorter, and
 * with the purely decorative ones (shimmer, liquid sweep, pulse) switched off — because a field
 * frozen mid-drift and a UI that snaps between states are both worse than a quiet version of the
 * same motion. See `MotionSpec.reducedFor`.
 */
@Serializable
enum class ReduceMotionMode {
    FOLLOW_SYSTEM,
    REDUCED,
    FULL,
}
