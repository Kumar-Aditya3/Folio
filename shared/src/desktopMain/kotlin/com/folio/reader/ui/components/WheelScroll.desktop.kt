package com.folio.reader.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

actual fun Modifier.onVerticalWheel(onScroll: (deltaY: Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) {
                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (delta != 0f) onScroll(delta)
                }
            }
        }
    }
