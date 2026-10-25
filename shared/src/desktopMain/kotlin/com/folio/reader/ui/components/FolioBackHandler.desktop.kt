package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

private val transientBackHandlers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

/** The window's Escape handler gives transient tools priority over destination navigation. */
@Composable
actual fun FolioBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val callback = { latest() }
        if (enabled) transientBackHandlers += callback
        onDispose { transientBackHandlers -= callback }
    }
}

fun consumeFolioBack(): Boolean {
    val callback = transientBackHandlers.lastOrNull() ?: return false
    callback()
    return true
}
