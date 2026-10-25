package com.folio.reader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.folioSheenGain
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

/** Interaction belongs to the chosen theme, including genuinely achromatic themes. */
@Composable
internal fun folioSignalAccent(requested: Color = FolioTheme.colors.primary): Color {
    return requested
}

/** Paired bookmark/page strokes: a stable identity cue with a finite selection response. */
@Composable
fun FolioSignalIndex(modifier: Modifier = Modifier, accent: Color = FolioTheme.colors.primary,
    active: Boolean = true) {
    val level by animateFloatAsState(if (active) 1f else .35f,
        rememberMotionAnimation(MotionKey.SegmentedIndicator), label = "signalIndex")
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(modifier.width(10.dp).height(28.dp)) {
        val inset = size.height * (1f - level.coerceIn(0f, 1f)) * .25f
        drawLine(accent, Offset(if (rtl) size.width - 2.dp.toPx() else 2.dp.toPx(), inset), Offset(if (rtl) size.width - 2.dp.toPx() else 2.dp.toPx(), size.height - inset),
            2.dp.toPx(), StrokeCap.Round)
        drawLine(accent.copy(alpha = .45f), Offset(if (rtl) size.width - 6.dp.toPx() else 6.dp.toPx(), size.height * .25f),
            Offset(if (rtl) size.width - 6.dp.toPx() else 6.dp.toPx(), size.height), 1.dp.toPx(), StrokeCap.Round)
    }
}

/** Short spectral edge catches, rather than a glowing enclosing box. No fill or idle clock. */
@Composable
internal fun Modifier.folioSignalEdge(): Modifier {
    val colors = FolioTheme.colors
    val sheen = folioSheenGain()
    val signal = folioSignalAccent()
    return drawWithCache {
        val inset = 24.dp.toPx().coerceAtMost(size.width * .2f)
        val start = Offset(inset, .8.dp.toPx())
        val end = Offset(size.width - inset, .8.dp.toPx())
        val glint = Brush.linearGradient(listOf(colors.onSurface.copy(alpha = .10f * sheen),
            signal.copy(alpha = .42f * sheen), colors.accentDiscovery.copy(alpha = .16f * sheen), Color.Transparent), start, end)
        onDrawWithContent {
            drawContent()
            drawLine(glint, start, end, 1.dp.toPx(), StrokeCap.Round)
        }
    }
}
