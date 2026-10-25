package com.folio.reader.ui.home

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioLogoMark
import com.folio.reader.ui.components.ReadingClimate
import com.folio.reader.ui.components.phrase
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.components.tint
import com.folio.reader.ui.components.weekForecast
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberFolioHaptics

/** An open weekly summary on Home's own field, aligned with the surrounding sections. */
@Composable
internal fun ThisWeekWell(
    state: HomeUiState,
    climate: ReadingClimate?,
    onOpenStats: () -> Unit,
    bottomInset: Dp = 0.dp
) {
    val colors = FolioTheme.colors
    val haptics = rememberFolioHaptics()
    // The forecast: the week's shape projected forward, phrased in the weather's
    // dialect. Null when there is nothing honest to say (no goal, too few days,
    // a silent week) — a forecast for silence would be confident nonsense.
    val forecast = weekForecast(state.week.map { it.minutes }, state.goalMinutes)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
            .clickable(role = Role.Button, onClickLabel = "View reading statistics") {
                haptics.play(FolioHaptic.Commit)
                onOpenStats()
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            FolioFigure(value = formatMinutes(state.week.sumOf { it.minutes }), label = "This week",
                emphasis = FigureScale.Quiet, accent = climate?.tint() ?: colors.accentProgress)
            forecast?.let { Text(it.phrase(), style = FolioTheme.typography.bodySmall,
                color = colors.onSurfaceVariant) }
            Text("${state.startedThisWeek} started · ${state.finishedThisWeek} finished",
                style = FolioTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(FolioTokens.space3))
        WeekSparkline(
            week = state.week,
            modifier = Modifier.fillMaxWidth().height(72.dp)
        )
        // Keep the final graph and closing mark clear of the floating navigation.
        if (bottomInset > 0.dp) {
            Box(
                modifier = Modifier.fillMaxWidth().height(bottomInset),
                contentAlignment = Alignment.CenterStart,
            ) {
                FolioLogoMark(
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { alpha = 0.28f }
                )
            }
        }
    }
}

@Composable
private fun WeekSparkline(week: List<StatDay>, modifier: Modifier = Modifier) {
    val color = FolioTheme.colors.accentProgress
    // §13.5: the line trims in once per window (keyed on dates, not minutes),
    // drawn in the canvas phase so nothing recomposes per frame. The paths + gradient are built
    // once per size/data change via drawWithCache; only the sweep clip reads the animated progress.
    val entry = rememberEntryState(week.map { it.date })
    Spacer(
        modifier = modifier.drawWithCache {
            if (week.size < 2) return@drawWithCache onDrawBehind { }
            val max = week.maxOf { it.minutes }.coerceAtLeast(1L)
            val dotRadius = 3.dp.toPx()
            val plotWidth = (size.width - dotRadius * 2f).coerceAtLeast(0f)
            val plotHeight = (size.height - dotRadius * 2f).coerceAtLeast(0f)
            val stepX = plotWidth / (week.size - 1)
            val points = week.mapIndexed { index, day ->
                Offset(dotRadius + index * stepX, dotRadius + plotHeight * (1f - day.minutes.toFloat() / max))
            }
            val areaPath = Path().apply {
                moveTo(0f, size.height)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(size.width, size.height)
                close()
            }
            val linePath = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            val areaBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent))
            val strokePx = 2.dp.toPx()
            onDrawBehind {
                val progress = entry.value
                clipRect(right = size.width * progress) {
                    drawPath(areaPath, brush = areaBrush)
                    drawPath(linePath, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                }
                // The dot marks today — the last day of the trailing week.
                if (progress >= 1f) points.last().let { drawCircle(color, radius = dotRadius, center = it) }
            }
        }
    )
}

internal fun formatMinutes(total: Long): String =
    if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
