package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * The device's own screen brightness.
 *
 * Folio deliberately does not dim the page to fake this: an overlay laid over the
 * book washes the ink and greys the paper, which is the opposite of what a reader
 * wants. The control changes the panel instead, like any other e-reader.
 *
 * [supported] is false where the OS exposes no such control, so the UI can hide it
 * rather than offer a slider that does nothing.
 */
@Stable
class ScreenBrightness(
    val value: Float,
    val supported: Boolean,
    val set: (Float) -> Unit
)

/** Current brightness, and a setter scoped to this app's window. */
@Composable
expect fun rememberScreenBrightness(): ScreenBrightness
