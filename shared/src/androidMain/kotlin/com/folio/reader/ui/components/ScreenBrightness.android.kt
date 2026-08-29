package com.folio.reader.ui.components

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberScreenBrightness(): ScreenBrightness {
    val context = LocalContext.current
    var value by remember(context) { mutableStateOf(readBrightness(context)) }
    return ScreenBrightness(
        value = value,
        supported = true,
        set = { requested ->
            val applied = requested.coerceIn(0.01f, 1f)
            if (applyToWindow(context, applied)) {
                value = applied
            }
        }
    )
}

private fun readBrightness(context: Context): Float {
    val windowValue = (context as? Activity)?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
    if (windowValue != null) return windowValue
    // Reading system settings needs no permission — writing them does, which is why
    // applying goes through the window attributes instead.
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(1f)
}

private fun applyToWindow(context: Context, value: Float): Boolean {
    val activity = context as? Activity ?: return false
    val attributes = activity.window.attributes
    attributes.screenBrightness = value
    activity.window.attributes = attributes
    return true
}
