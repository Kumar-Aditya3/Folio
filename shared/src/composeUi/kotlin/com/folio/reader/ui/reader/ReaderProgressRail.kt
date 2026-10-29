package com.folio.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A whole-content measure: loading another chapter never changes the rail's extent. */
@Composable
internal fun ReaderProgressRail(fraction: Float, stops: List<Float> = emptyList(),
    modifier: Modifier = Modifier, ink: Color) {
    val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    Canvas(modifier.width(10.dp).semantics {
        contentDescription = "Reading progress through the whole content"
        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
    }) {
        val x = size.width / 2
        drawLine(ink.copy(alpha = .10f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ink.copy(alpha = .45f), Offset(x, 0f), Offset(x, size.height * progress), 2.dp.toPx(), StrokeCap.Round)
        stops.drop(1).dropLast(1).forEach { stop ->
            val y = size.height * stop.coerceIn(0f, 1f)
            drawLine(ink.copy(alpha = .20f), Offset(x - 2.dp.toPx(), y), Offset(x + 2.dp.toPx(), y), 1.dp.toPx())
        }
        drawCircle(ink.copy(alpha = .7f), 2.5.dp.toPx(), Offset(x, size.height * progress))
    }
}
