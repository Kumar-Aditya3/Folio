package com.folio.reader.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
actual fun rememberFolioHaptics(): FolioHaptics {
    val context = LocalContext.current
    val viewFeedback = LocalHapticFeedback.current
    // Keyed on the application context so the vibrator handle survives config
    // changes rather than being rebuilt with every recomposition.
    return remember(context.applicationContext) {
        AndroidFolioHaptics(context.applicationContext, viewFeedback)
    }
}

/**
 * Android [FolioHaptics]. Prefers `VibrationEffect.Composition` primitives (API
 * 31+, where TICK/CLICK amplitudes give the vocabulary its distinct weights),
 * falls back to amplitude waveforms (API 26+), then to legacy one-shots, then to
 * view-level feedback when the device reports no vibrator. Every path is guarded
 * by the system touch-feedback setting and wrapped so a missing VIBRATE grant or
 * an unsupported primitive degrades to silence, never a crash.
 */
private class AndroidFolioHaptics(
    private val context: Context,
    private val viewFeedback: HapticFeedback,
) : FolioHaptics {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    // TICK and CLICK are the two primitives every composition here is built from;
    // if either is unsupported we drop to the waveform path rather than emit a
    // primitive the device silently ignores.
    private val primitivesSupported: Boolean =
        Build.VERSION.SDK_INT >= 31 && hasVibrator && runCatching {
            vibrator!!.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
        }.getOrDefault(false)

    private fun hapticsEnabled(): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)

    override fun play(feel: FolioHaptic) {
        if (!hapticsEnabled()) return
        if (!hasVibrator) {
            playViewFeedback(feel)
            return
        }
        val ok = runCatching {
            when {
                Build.VERSION.SDK_INT >= 31 && primitivesSupported ->
                    vibrator!!.vibrate(composition(feel))
                Build.VERSION.SDK_INT >= 26 ->
                    vibrator!!.vibrate(waveform(feel))
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator!!.vibrate(legacyMs(feel))
                }
            }
            true
        }.getOrDefault(false)
        if (!ok) playViewFeedback(feel)
    }

    // ── API 31+ primitive compositions ──────────────────────────────────────
    private fun composition(feel: FolioHaptic): VibrationEffect {
        val c = VibrationEffect.startComposition()
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        return when (feel) {
            FolioHaptic.PageTurn -> c.addPrimitive(tick, 0.35f)
            FolioHaptic.ScrubTick -> c.addPrimitive(tick, 0.2f)
            FolioHaptic.Commit -> c.addPrimitive(click, 0.55f)
            FolioHaptic.PickUp -> c.addPrimitive(click, 0.7f)
            FolioHaptic.Confirm -> c.addPrimitive(tick, 0.5f).addPrimitive(click, 0.85f, 40)
            FolioHaptic.Reject -> c.addPrimitive(click, 0.9f).addPrimitive(click, 0.9f, 90)
            FolioHaptic.Celebrate ->
                c.addPrimitive(tick, 0.4f)
                    .addPrimitive(click, 0.7f, 60)
                    .addPrimitive(click, 1.0f, 60)
        }.compose()
    }

    // ── API 26–30 amplitude waveforms (amplitude 1..255) ─────────────────────
    private fun waveform(feel: FolioHaptic): VibrationEffect = when (feel) {
        FolioHaptic.ScrubTick -> VibrationEffect.createOneShot(6, 40)
        FolioHaptic.PageTurn -> VibrationEffect.createOneShot(10, 70)
        FolioHaptic.Commit -> VibrationEffect.createOneShot(14, 120)
        FolioHaptic.PickUp -> VibrationEffect.createOneShot(18, 170)
        FolioHaptic.Confirm ->
            VibrationEffect.createWaveform(longArrayOf(0, 12, 40, 22), intArrayOf(0, 120, 0, 210), -1)
        FolioHaptic.Reject ->
            VibrationEffect.createWaveform(longArrayOf(0, 20, 80, 20), intArrayOf(0, 210, 0, 210), -1)
        FolioHaptic.Celebrate ->
            VibrationEffect.createWaveform(
                longArrayOf(0, 10, 50, 16, 50, 22),
                intArrayOf(0, 90, 0, 150, 0, 220),
                -1,
            )
    }

    // ── Below API 26: no amplitude control, only durations ───────────────────
    @Suppress("DEPRECATION")
    private fun legacyMs(feel: FolioHaptic): Long = when (feel) {
        FolioHaptic.ScrubTick, FolioHaptic.PageTurn -> 8L
        FolioHaptic.Commit, FolioHaptic.Confirm, FolioHaptic.PickUp -> 18L
        FolioHaptic.Reject -> 30L
        FolioHaptic.Celebrate -> 40L
    }

    // ── No vibrator: the two feels view feedback can express ──────────────────
    private fun playViewFeedback(feel: FolioHaptic) {
        runCatching {
            val type = when (feel) {
                FolioHaptic.ScrubTick, FolioHaptic.PageTurn -> HapticFeedbackType.TextHandleMove
                else -> HapticFeedbackType.LongPress
            }
            viewFeedback.performHapticFeedback(type)
        }
    }
}
