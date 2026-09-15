package com.folio.reader.ui.theme

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * §16: the wallpaper-derived pair, straight from Material You on API 31+.
 * Remembered per context so a configuration change (wallpaper swap, dark-mode
 * flip) — which recreates the host — always resolves fresh schemes.
 */
@Composable
actual fun rememberDynamicSchemes(): Pair<androidx.compose.material3.ColorScheme, androidx.compose.material3.ColorScheme>? {
    if (Build.VERSION.SDK_INT < 31) return null
    val context = LocalContext.current
    return remember(context) {
        dynamicLightColorScheme(context) to dynamicDarkColorScheme(context)
    }
}
