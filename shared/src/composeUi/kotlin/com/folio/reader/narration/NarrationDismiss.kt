package com.folio.reader.narration

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberFolioHaptics
import com.folio.reader.ui.theme.rememberMotionAnimation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Physical right swipe, independent of layout direction. Stops through the normal saved-position path. */
@Composable
fun Modifier.narrationSwipeToDismiss(bookId: String?, onDismiss: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val haptics = rememberFolioHaptics()
    val dismissSpec = rememberMotionAnimation<Float>(MotionKey.SheetDismiss)
    val currentDismiss by rememberUpdatedState(onDismiss)
    var offset by remember(bookId) { mutableFloatStateOf(0f) }
    var committed by remember(bookId) { mutableStateOf(false) }
    var settling by remember(bookId) { mutableStateOf<Job?>(null) }
    DisposableEffect(bookId) { onDispose { settling?.cancel() } }
    return this
        .semantics {
            dismiss {
                if (!committed) {
                    committed = true
                    settling?.cancel()
                    currentDismiss()
                }
                true
            }
        }
        .graphicsLayer {
            translationX = offset
            alpha = (1f - offset / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        .pointerInput(bookId, dismissSpec) {
            fun settle(target: Float, dismiss: Boolean = false) {
                settling?.cancel()
                settling = scope.launch {
                    animate(offset, target, animationSpec = dismissSpec) { value, _ ->
                        offset = value
                    }
                    if (dismiss) currentDismiss()
                }
            }
            detectHorizontalDragGestures(
                onDragStart = { if (!committed) settling?.cancel() },
                onDragCancel = { if (!committed) settle(0f) },
                onDragEnd = {
                    if (!committed) {
                        if (offset >= size.width * .38f) {
                            committed = true
                            haptics.play(FolioHaptic.Commit)
                            settle(size.width.toFloat(), dismiss = true)
                        } else settle(0f)
                    }
                },
            ) { change, amount ->
                if (!committed) {
                    change.consume()
                    offset = (offset + amount).coerceIn(0f, size.width.toFloat())
                }
            }
        }
}
