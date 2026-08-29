package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * No per-app brightness API exists on the desktop JVM, and dimming the page to
 * imitate one is worse than offering nothing. The control hides itself here and the
 * OS display settings stay the way to change brightness.
 */
@Composable
actual fun rememberScreenBrightness(): ScreenBrightness {
    val holder = remember { mutableStateOf(1f) }
    return ScreenBrightness(
        value = holder.value,
        supported = false,
        set = { holder.value = it }
    )
}
