package com.folio.reader.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberMotionSpec

/** A viewport reveal, not a timer: scroll reads invalidate only the graphics layer. */
@Composable
internal fun ReadingDeskSection(
    listState: LazyListState,
    itemKey: String,
    enabled: Boolean,
    bottomInset: Dp,
    content: @Composable () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val spec = rememberMotionSpec(MotionKey.ChartEntry)
    val animate = enabled && motion && spec.durationMs > 0 && spec.intensity > 0f
    val density = LocalDensity.current
    val revealDistance = with(density) { 96.dp.toPx() }
    val travel = with(density) { 24.dp.toPx() }
    val insetPx = with(density) { bottomInset.toPx() }
    Box(if (!animate) Modifier else Modifier.graphicsLayer {
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
        val progress = if (item == null) 1f else readingDeskRevealFraction(
            item.offset.toFloat(), layout.viewportEndOffset - insetPx, revealDistance,
        )
        val eased = spec.easing.transform(progress).coerceIn(0f, 1f)
        alpha = eased
        translationY = travel * (1f - eased) * spec.intensity
    }) { content() }
}

internal fun readingDeskRevealFraction(itemTop: Float, viewportEnd: Float, distance: Float): Float =
    if (!itemTop.isFinite() || !viewportEnd.isFinite() || !distance.isFinite() || distance <= 0f) 1f
    else ((viewportEnd - itemTop) / distance).coerceIn(0f, 1f)
