package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable

/**
 * The app's haptic vocabulary — the "Living Paper" body layer.
 *
 * Each entry is *one* consistent feel mapped to *one* class of interaction, so
 * the whole app speaks the same tactile language instead of scattering ad-hoc
 * `vibrate()` calls that drift apart over time. Add a feel here, then reuse it;
 * do not invent a new waveform at a call site.
 *
 * The seam is the same shape as [rememberGlassTick] and the other Rule 19
 * capability flags in [MotionCapabilities]: the Android actual composes real
 * `VibrationEffect`s and honours the system touch-feedback setting, while the
 * desktop actual is a silent no-op so shared UI can call it unconditionally.
 */
enum class FolioHaptic {
    /** A page turned — a paper-thin tick. The reader's signature feel. */
    PageTurn,

    /** A navigation/tab switch or sheet settle committed — a soft, confident click. */
    Commit,

    /** Something *took*: a highlight saved, a toggle confirmed. A single firm click. */
    Confirm,

    /** One step of a slider or scrubber crossed — the lightest repeatable tick. */
    ScrubTick,

    /** A long-press pickup / entering selection mode — a distinct grab. */
    PickUp,

    /** A destructive action confirmed (delete) — a sharp, cautionary double. */
    Reject,

    /** A milestone: finishing a book, a streak landing — a rising celebratory pulse. */
    Celebrate,
}

/**
 * Plays entries of the [FolioHaptic] vocabulary. Inert where the device has no
 * vibrator, where the user has turned system touch feedback off, or on desktop.
 */
interface FolioHaptics {
    fun play(feel: FolioHaptic)
}

/**
 * The active [FolioHaptics] for this platform.
 *
 * Android composes `VibrationEffect` primitives on API 31+, falls back to
 * amplitude waveforms on API 26+, then to the softest legacy vibration, and
 * finally to view-level [androidx.compose.ui.hapticfeedback.HapticFeedback] when
 * there is no vibrator — always gated on the system touch-feedback setting.
 * Desktop returns a shared no-op.
 */
@Composable
expect fun rememberFolioHaptics(): FolioHaptics
