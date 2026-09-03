package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.chartStagger
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberEntryProgress
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

// ---------------------------------------------------------------------------
// Weekly bar charts
// ---------------------------------------------------------------------------

/**
 * One day-bar in the shared chart idiom: §12.5/Rule 15 fill — a vertical
 * gradient from `accentProgress` down to it at [FolioTokens.gradientMinAlpha] —
 * scaled against [peak], stubbed track when the day is empty. The bar at the
 * window's max carries the Rule 15 peak marker: a brighter `accentStreak` cap.
 * Used by the weekly charts and the book-detail sparkline.
 *
 * §13.5: [growth] scales the bar from the empty track up to full height, and
 * [capReveal] fades the peak cap in only after its bar has finished growing —
 * both default to 1f, the pre-§13 static rendering.
 */
@Composable
internal fun ChartBar(
    value: Float,
    peak: Float,
    modifier: Modifier = Modifier,
    growth: Float = 1f,
    capReveal: Float = 1f,
) {
    val fraction = if (peak > 0f) (value / peak).coerceIn(0f, 1f) else 0f
    val grown = if (value > 0f) {
        androidx.compose.ui.unit.lerp(FolioTokens.chartTrack, FolioTokens.chartBarBase + FolioTokens.chartBarSpan * fraction, growth)
    } else FolioTokens.chartTrack
    val accent = FolioTheme.colors.accentProgress
    val barShape = RoundedCornerShape(
        topStart = FolioTokens.chartBarRadiusTop, topEnd = FolioTokens.chartBarRadiusTop,
        bottomStart = FolioTokens.chartBarRadiusBottom, bottomEnd = FolioTokens.chartBarRadiusBottom,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(grown)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (value > 0f) Brush.verticalGradient(
                        listOf(accent, accent.copy(alpha = FolioTokens.gradientMinAlpha))
                    ) else SolidColor(FolioTheme.colors.outline.copy(alpha = 0.35f)),
                    barShape,
                )
        )
        if (value > 0f && value >= peak && capReveal > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(FolioTokens.chartPeakCap)
                    .background(
                        FolioTheme.colors.accentStreak.copy(alpha = capReveal),
                        RoundedCornerShape(
                            topStart = FolioTokens.chartBarRadiusTop, topEnd = FolioTokens.chartBarRadiusTop,
                            bottomStart = 1.dp, bottomEnd = 1.dp,
                        ),
                    )
            )
        }
    }
}

/**
 * Manga chapters per day over the last 7 days. Same well treatment as the books
 * [WeekChart] — data sits *in* the page — with counts instead of minutes.
 */
@Composable
internal fun MangaWeekChart(chaptersPerDay: List<Int>, labels: List<String>) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioEyebrow("Manga — last 7 days")
        }
        Spacer(Modifier.height(FolioTokens.space2))
        val peak = (chaptersPerDay.maxOrNull() ?: 0).coerceAtLeast(1)
        // §13.5: same entry sweep as the books week chart, keyed on the labels.
        val entry = rememberEntryProgress(labels)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
                .height(FolioTokens.chartHeight),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            verticalAlignment = Alignment.Bottom,
        ) {
            chaptersPerDay.forEachIndexed { index, chapters ->
                val label = labels.getOrNull(index)?.take(1) ?: ""
                val (growth, cap) = chartStagger(entry, index, chaptersPerDay.size)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = if (chapters > 0) chapters.toString() else "",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    ChartBar(
                        value = chapters.toFloat(),
                        peak = peak.toFloat(),
                        growth = growth,
                        capReveal = cap,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Last 7 days, as a well cut into the page. Sinking the chart is what stops the
 * one data visual on the screen from reading as another card in the stack; the
 * bars grow out of the well's floor, which is where a bar chart belongs.
 */
@Composable
internal fun WeekChart(week: List<StatDay>) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioSectionHead(title = "Last 7 days")
        }
        Spacer(Modifier.height(FolioTokens.space3))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
        ) {
            WeekBars(week)
        }
    }
}

/** The bare 7-day bar row shared by the stats week chart and the Home "This week" card. */
@Composable
internal fun WeekBars(week: List<StatDay>) {
    val peak = (week.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
    // §13.5: one entry sweep for the window, keyed on the days' DATES — minutes
    // updating live must not replay it — staggered 40ms left-to-right.
    val entry = rememberEntryProgress(week.map { it.date })
    Row(
        modifier = Modifier.fillMaxWidth().height(FolioTokens.chartHeight),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        verticalAlignment = Alignment.Bottom
    ) {
        week.forEachIndexed { index, day ->
            val (growth, cap) = chartStagger(entry, index, week.size)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (day.minutes > 0) shortMinutes(day.minutes) else "",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                ChartBar(
                    value = day.minutes.toFloat(),
                    peak = peak.toFloat(),
                    growth = growth,
                    capReveal = cap,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = day.date.dayOfWeek.name.take(1),
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Activity heatmap
// ---------------------------------------------------------------------------

private enum class HeatmapMode { ALL, BOOKS, MANGA }

@Composable
internal fun ActivityHeatmap(
    allDays: List<StatDay>,
    bookDays: List<StatDay>,
    mangaDays: List<StatDay>,
) {
    var mode by remember { mutableStateOf(HeatmapMode.ALL) }
    val days = when (mode) {
        HeatmapMode.ALL -> allDays
        HeatmapMode.BOOKS -> bookDays
        HeatmapMode.MANGA -> mangaDays
    }

    // §12.5: the year heatmap is Stats' one raised surface (Rule 13). It earns it
    // by being the only genuinely *visual* artefact on the screen — 365 cells of
    // the reader's own year. The asymmetric hero silhouette and the accent-lit rim
    // separate it from every ruled list around it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter)
            .folioRaised(shape = FolioShapes.hero, accent = FolioTheme.colors.accentProgress)
            .padding(FolioTokens.space3)
    ) {
        Column {
            FolioSectionHead(title = "Your year", eyebrow = "Activity")

            Spacer(Modifier.height(FolioTokens.space3))

            // ── Segmented toggle ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolioChip(selected = mode == HeatmapMode.ALL, onClick = { mode = HeatmapMode.ALL }, label = "All")
                FolioChip(selected = mode == HeatmapMode.BOOKS, onClick = { mode = HeatmapMode.BOOKS }, label = "Books")
                FolioChip(selected = mode == HeatmapMode.MANGA, onClick = { mode = HeatmapMode.MANGA }, label = "Manga")
            }

            Spacer(Modifier.height(FolioTokens.space3))

            if (days.isEmpty()) {
                Text(
                    "No reading recorded yet.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                return@Column
            }
            val peak = (days.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
            val cellAccent = FolioTheme.colors.accentProgress
            val weeks = days.chunked(7)
            // GitHub orientation: one column per week, most recent on the right;
            // even 4dp gutters on both axes so cells never chain into a wall.
            val gridScroll = rememberScrollState()
            LaunchedEffect(weeks.size) {
                snapshotFlow { gridScroll.maxValue }.first { it > 0 }
                gridScroll.animateScrollTo(gridScroll.maxValue)
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(gridScroll)) {
                // §13.5: the whole grid grows from zero on first composition — one
                // shared timeline, read in the layer phase so 365 cells never
                // recompose per frame.
                val gridEntry = rememberEntryState(mode)
                Column(
                    modifier = Modifier.graphicsLayer {
                        val p = gridEntry.value
                        scaleX = p
                        scaleY = p
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (0..6).forEach { index ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            weeks.forEach { week ->
                                val day = week.getOrNull(index)
                                if (day == null || day.minutes <= 0L) {
                                    Box(
                                        Modifier.size(14.dp).background(
                                            FolioTheme.colors.outline.copy(alpha = 0.14f),
                                            RoundedCornerShape(3.dp)
                                        )
                                    )
                                } else {
                                    HeatmapCell(
                                        intensity = intensityFor(day.minutes, peak),
                                        size = 14.dp,
                                        accent = cellAccent
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = FolioTokens.space1),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${days.first().date.shortLabel()} – ${days.last().date.shortLabel()}",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Less", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
                    (1..4).forEach { HeatmapCell(intensity = it, size = 9.dp, accent = cellAccent) }
                    Text("More", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Log-ish banding: a 2-hour day should not max out the scale against a 30-second one. */
internal fun intensityFor(minutes: Long, peak: Long): Int {
    val ratio = minutes.toFloat() / peak
    return when {
        ratio > 0.66f -> 4
        ratio > 0.33f -> 3
        ratio > 0.12f -> 2
        else -> 1
    }
}

private fun LocalDate.shortLabel(): String = "$dayOfMonth.$monthNumber"
