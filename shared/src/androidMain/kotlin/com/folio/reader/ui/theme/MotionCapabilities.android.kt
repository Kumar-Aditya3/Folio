package com.folio.reader.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Rule 19: when the user has disabled system animations, every §13 effect must
 * degrade to its static form rather than fight the setting.
 */
@Composable
actual fun rememberMotionEnabled(): Boolean {
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

@Composable
actual fun rememberBlurSupported(): Boolean = Build.VERSION.SDK_INT >= 31

@Composable
actual fun rememberShaderSupported(): Boolean = Build.VERSION.SDK_INT >= 33
