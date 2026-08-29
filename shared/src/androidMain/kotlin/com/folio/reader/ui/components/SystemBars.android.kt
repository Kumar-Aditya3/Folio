package com.folio.reader.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView

private const val IMMERSIVE_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN or
    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

@Suppress("DEPRECATION")
@Composable
actual fun ReaderSystemBars(visible: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return
    LaunchedEffect(visible) {
        activity.window.decorView.systemUiVisibility = if (visible) 0 else IMMERSIVE_FLAGS
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the reader always brings the system bars back.
            activity.window.decorView.systemUiVisibility = 0
        }
    }
}
