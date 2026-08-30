package com.folio.reader.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun ReaderSystemBars(visible: Boolean) {
    val view = LocalView.current
    val controller = remember {
        (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    LaunchedEffect(visible, controller) {
        val c = controller ?: return@LaunchedEffect
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            c.show(WindowInsetsCompat.Type.systemBars())
            // The chrome draws the theme's dark band behind the status bar.
            c.isAppearanceLightStatusBars = false
        } else {
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the reader always brings the system bars back.
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
