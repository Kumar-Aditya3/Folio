package com.folio.reader.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.phrase
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.components.tint
import com.folio.reader.ui.components.weekForecast
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * **The week well.** Extracted from HomeScreen so the weather additions here
 * did not push that file further over Rule 9's budget. Content unchanged
 * apart from the forecast line and climate-tinted figure — see HomeScreen's
 * block comments for the composition rationale.
 */

/**
 * **The week.** The only chart on Home, and the only *sunken* surface — cut into
 * the page while the anchor floats above it, so the screen reads as having a
 * genuine top and bottom rather than one plane of cards.
 *
 * Edge-to-edge on purpose: the well spans the full width with only the type inset,
 * which gives the sparkline room and keeps the bottom of the screen from becoming
 * a fourth card. Rule 16 still holds — this is Home's own sparkline, not the Stats
 * week chart.
 */
@Composable
internal fun ThisWeekWell(
    state: HomeUiState,
    climate: ReadingClimate?,
    onOpenStats: () -> Unit,
    bottomInset: Dp = 0.dp
) {
    val colors = FolioTheme.colors
    // The forecast: the week's shape projected forward, phrased in the weather's
    // dialect. Null when there is nothing honest to say (no goal, too few days,
    // a silent week) — a forecast for silence would be confident nonsense.
    val forecast = weekForecast(state.week.map { it.minutes }, state.goalMinutes)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioSunken(FolioShapes.edgeStart)
            .clickable(onClick = onOpenStats)
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.gutter,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            )
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            FolioFigure(
                value = formatMinutes(state.week.sumOf { it.minutes }),
                label = "This week",
                emphasis = FigureScale.Quiet,
                accent = climate?.tint() ?: colors.accentProgress,
            )
            Spacer(Modifier.weight(1f))
            if (forecast != null) {
                Text(
                    forecast.phrase(),
                    style = FolioTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = FolioTokens.spaceHair),
                )
                Spacer(Modifier.width(FolioTokens.space3))
            }
            Text(
                "${state.startedThisWeek} started · ${state.finishedThisWeek} finished",
                style = FolioTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(FolioTokens.space3))
        WeekSparkline(
            week = state.week,
            modifier = Modifier.fillMaxWidth().height(FolioTokens.sparkHeight * 1.4f)
        )
        // The floating capsule's clearance, held *inside* the well. The page therefore
        // ends on a surface rather than on dead scroll, and the leaf sits in the band
        // to the leading side of the capsule as a closing mark.
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
    // drawn in the canvas phase so nothing recomposes per frame.
    val entry = rememberEntryState(week.map { it.date })
    Canvas(modifier = modifier) {
        if (week.size < 2) return@Canvas
        val progress = entry.value
        val max = week.maxOf { it.minutes }.coerceAtLeast(1L)
        val stepX = size.width / (week.size - 1)
        val points = week.mapIndexed { index, day ->
            Offset(index * stepX, size.height * (1f - day.minutes.toFloat() / max))
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
        clipRect(right = size.width * progress) {
            drawPath(
                areaPath,
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent))
            )
            drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        // The dot marks today — the last day of the trailing week.
        if (progress >= 1f) points.last().let { drawCircle(color, radius = 3.dp.toPx(), center = it) }
    }
}

internal fun formatMinutes(total: Long): String =
    if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
