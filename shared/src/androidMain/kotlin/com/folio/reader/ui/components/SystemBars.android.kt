package com.folio.reader.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Walks the context chain to the host [Activity].
 *
 * `LocalView.current.context` is not the Activity in a Compose Android app: the
 * view is inflated against a `ContextThemeWrapper`, which wraps a
 * `ContextImpl`, and the Activity sits further down the chain. The original
 * `view.context as? Activity` therefore returned null in the reader, which made
 * every call below a silent no-op — the system bars only changed when something
 * outside the reader happened to touch them, which is why they appeared to take
 * seconds to settle after opening a book.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
actual fun ReaderSystemBars(visible: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
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
