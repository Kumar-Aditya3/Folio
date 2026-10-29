package com.folio.reader.ui.theme

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Rule 19: when the user has disabled system animations, every §13 effect must
 * degrade to its static form rather than fight the setting.
 */
@Composable
actual fun rememberMotionEnabled(): Boolean {
    LocalFolioMotionAllowed.current?.let { return it }
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

/** One observer for the whole window; offscreen ambient effects stop their clocks. */
@Composable
fun FolioAndroidMotionEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    fun animations() = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE,1f) > 0 }.getOrDefault(true)
    var allowed by remember(context) { mutableStateOf(animations()) }
    DisposableEffect(context,lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible) allowed = animations()
        }
        val settings = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { allowed = animations() }
        }
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,settings)
        onDispose { lifecycle.removeObserver(observer); context.contentResolver.unregisterContentObserver(settings) }
    }
    CompositionLocalProvider(LocalFolioWindowVisible provides visible, LocalFolioMotionAllowed provides (visible && allowed),content = content)
}

@Composable
actual fun rememberBlurSupported(): Boolean = Build.VERSION.SDK_INT >= 31

@Composable
actual fun rememberShaderSupported(): Boolean = Build.VERSION.SDK_INT >= 33

@Composable
actual fun rememberGlassTick(): () -> Unit {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return remember(context) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true && Build.VERSION.SDK_INT >= 31) {
            {
                runCatching {
                    vibrator.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                            .compose()
                    )
                }
            }
        } else {
            // Below 31 there is no primitive API; the softest standard feedback
            // is the closest thing to a tick without becoming a click.
            { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
    }
}
