package com.folio.reader.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Scrub locally, commit once on release. Cancellation never changes a reading position. */
@Composable
internal fun Modifier.folioSeekGesture(
    enabled: Boolean,
    rtl: Boolean,
    onPreview: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
): Modifier {
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    return if (!enabled) this else pointerInput(rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun fraction(x: Float): Float = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                .let { if (rtl) 1f - it else it }
            var target = fraction(down.position.x)
            down.consume()
            preview(target)
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    target = fraction(change.position.x)
                    preview(target)
                    change.consume()
                    if (!change.pressed) { commit(target); break }
                }
            } finally { preview(null) }
        }
    }
}
