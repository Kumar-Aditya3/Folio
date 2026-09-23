package com.folio.reader.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * The week chart — Stats' 7-day history as one smooth, continuous curve.
 *
 * The previous terrain drew one jittered needle per day, which read as seven
 * independent spikes rather than as a week: segregated. The curve is a single
 * monotone cubic through the daily totals — smooth, and honest by construction
 * (it never overshoots, so the chart never displays minutes the reader did not
 * read) — washed with the Rule 15 gradient beneath, with the peak marker as a
 * dot on the week's biggest day. Day initials range beneath their columns, so
 * the curve and its axis agree, and the window's total rides the section head
 * instead of floating over the data.
 *
 * Home keeps its sparkline (Rule 16); this is Stats' own chart, larger and
 * labelled. The manga chapters chart reuses [SmoothWeekCurve] so both halves
 * of the screen speak one chart language.
 */
@Composable
internal fun WeekChart(week: List<StatDay>) {
    val total = week.sumOf { it.minutes }
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioSectionHead(
                title = "Last 7 days",
                trailing = if (total > 0L) {
                    {
                        Text(
                            text = shortMinutes(total),
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                } else null,
            )
        }
        Spacer(Modifier.height(FolioTokens.space3))
        SmoothWeekCurve(
            values = week.map { it.minutes.toFloat() },
            dayLabels = week.map { it.date.dayOfWeek.name.take(1) },
            plotHeight = FolioTokens.weekChartHeight,
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        )
    }
}

/**
 * One smooth week curve, shared by the books minutes chart and the manga
 * chapters chart.
 *
 * The samples sit at their columns' centres (never the plot's edges), which is
 * exactly where [dayLabels]' equal-width columns centre — curve and axis stay
 * in agreement without measuring text. [valueCaptions], when supplied, ride
 * above their columns the same way.
 *
 * §13.5: the curve trims in once per window, left to right, drawn in the
 * canvas phase so nothing recomposes per frame.
 */
@Composable
internal fun SmoothWeekCurve(
    values: List<Float>,
    dayLabels: List<String>,
    plotHeight: Dp,
    modifier: Modifier = Modifier,
    valueCaptions: List<String> = emptyList(),
) {
    val colors = FolioTheme.colors
    Column(modifier = modifier) {
        if (valueCaptions.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                valueCaptions.forEach { caption ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (caption.isNotBlank()) {
                            Text(
                                text = caption,
                                style = FolioTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        val accent = colors.accentProgress
        val peakAccent = colors.accentStreak
        // Keyed on the window's shape: live value updates must not replay the
        // sweep mid-scroll.
        val entry: State<Float> = rememberEntryState(values.size)
        val peakIndex = values.indices.maxByOrNull { values[it] } ?: -1
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight)
                .drawWithCache {
                    // Built once per size/data change, not per animation frame: the monotone-cubic
                    // solve, both paths and the gradient are all independent of the sweep progress,
                    // which is read only in onDrawBehind below.
                    if (values.isEmpty()) return@drawWithCache onDrawBehind { }
                    val peak = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
                    val n = values.size
                    val colWidth = size.width / n
                    val topPad = 3.dp.toPx()
                    val baseline = size.height - 1.dp.toPx()
                    val span = (baseline - topPad).coerceAtLeast(1f)
                    val points = values.mapIndexed { index, value ->
                        Offset(colWidth * (index + 0.5f), baseline - span * (value / peak))
                    }
                    val curve = Path().apply { appendMonotoneCubic(points) }
                    val area = Path().apply {
                        addPath(curve)
                        lineTo(points.last().x, baseline)
                        lineTo(points.first().x, baseline)
                        close()
                    }
                    val areaBrush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.25f), Color.Transparent)
                    )
                    val strokePx = 2.dp.toPx()
                    val dotRadius = 3.dp.toPx()
                    onDrawBehind {
                        val progress = entry.value
                        clipRect(right = size.width * progress) {
                            drawPath(area, brush = areaBrush)
                            drawPath(curve, color = accent, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                        }
                        // Rule 15 peak marker: the week's max day carries a dot in
                        // accentStreak, appearing only once the sweep has reached it.
                        if (progress >= 1f && peakIndex >= 0 && values[peakIndex] > 0f) {
                            drawCircle(peakAccent, radius = dotRadius, center = points[peakIndex])
                        }
                    }
                }
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEach { label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = FolioTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
