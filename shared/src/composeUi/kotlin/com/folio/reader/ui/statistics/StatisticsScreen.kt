package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.HeatmapCell
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.datetime.LocalDate

/**
 * Reading statistics, aggregated from the synced session history of every device on
 * the account rather than just this one.
 *
 * Every figure is sized for the space it is given: values are formatted compactly and
 * capped to one line, because a card whose number wraps one character per line reads
 * as broken rather than as full.
 */
@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    onBackPress: () -> Unit,
    onBookClick: (String) -> Unit
) {
    val stats by viewModel.state.collectAsState(initial = StatisticsUiState())

    Column(modifier = Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        FolioTopBar(
            title = "Statistics",
            navigationIcon = {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = FolioTokens.space3,
                end = FolioTokens.space3,
                top = FolioTokens.space3,
                bottom = FolioTokens.space4
            ),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            item { HeadlineRow(stats) }
            item { WeekChart(stats.week) }
            item { ActivityHeatmap(stats.heatmap) }
            item { PatternsCard(stats) }
            if (stats.currentlyReading.isNotEmpty()) {
                item { CurrentlyReadingCard(stats.currentlyReading, onBookClick) }
            }
            if (!stats.hasData) {
                item { EmptyState() }
            }
            item { Spacer(Modifier.height(FolioTokens.space1)) }
        }
    }
}

@Composable
private fun HeadlineRow(stats: StatisticsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            StatTile("This week", formatDuration(stats.timeThisWeekMs),
                plural(stats.sessionsThisWeek, "session"), Modifier.weight(1f))
            StatTile("Day streak", stats.streakDays.toString(),
                if (stats.longestStreakDays > stats.streakDays) "best ${stats.longestStreakDays}" else "reading days",
                Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            StatTile("Finished", stats.booksFinished.toString(), "books completed", Modifier.weight(1f))
            StatTile("This year", formatHours(stats.timeThisYearMs),
                "${formatCount(stats.wordsReadThisYear)} words", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .glassPanel(RoundedCornerShape(FolioTokens.radiusCard))
            .padding(FolioTokens.space3)
    ) {
        Text(
            text = label.uppercase(),
            style = FolioTheme.typography.labelSmall,
            color = FolioTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        // The display face carries the number; the caption carries everything else so
        // the value itself never has to shrink to fit.
        Text(
            text = value,
            style = FolioTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = FolioTheme.colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = caption,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WeekChart(week: List<StatDay>) {
    FolioSectionCard(title = "Last 7 days") {
        val peak = (week.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
        Row(
            modifier = Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            verticalAlignment = Alignment.Bottom
        ) {
            week.forEach { day ->
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
                    val fraction = (day.minutes.toFloat() / peak).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (day.minutes > 0) (28 + fraction * 48).dp else 6.dp)
                            .background(
                                if (day.minutes > 0) FolioTheme.colors.primary
                                else FolioTheme.colors.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
                            )
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
}

@Composable
private fun ActivityHeatmap(days: List<StatDay>) {
    FolioSectionCard(title = "Activity") {
        if (days.isEmpty()) {
            Text(
                "No reading recorded yet.",
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant
            )
            return@FolioSectionCard
        }
        val peak = (days.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // A partial trailing week keeps its slot so the grid stays square.
                (0..6).forEach { index ->
                    val day = week.getOrNull(index)
                    if (day == null || day.minutes <= 0L) {
                        Box(
                            Modifier.size(14.dp).background(
                                FolioTheme.colors.outline.copy(alpha = 0.22f),
                                RoundedCornerShape(3.dp)
                            )
                        )
                    } else {
                        HeatmapCell(intensity = intensityFor(day.minutes, peak), size = 14.dp)
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
                (1..4).forEach { HeatmapCell(intensity = it, size = 9.dp) }
                Text("More", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            }
        }
    }
}

/** Log-ish banding: a 2-hour day should not max out the scale against a 30-second one. */
private fun intensityFor(minutes: Long, peak: Long): Int {
    val ratio = minutes.toFloat() / peak
    return when {
        ratio > 0.66f -> 4
        ratio > 0.33f -> 3
        ratio > 0.12f -> 2
        else -> 1
    }
}

@Composable
private fun PatternsCard(stats: StatisticsUiState) {
    FolioSectionCard(title = "Reading patterns") {
        if (!stats.hasData) {
            Text(
                "These fill in once there is a session or two to measure.",
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant
            )
            return@FolioSectionCard
        }
        PatternRow("Average session", shortMinutes(stats.averageSessionMinutes.toLong()))
        PatternRow("Average speed", "${stats.averageSpeedWpm.toInt()} wpm")
        PatternRow("Longest streak", plural(stats.longestStreakDays, "day"))
        PatternRow("Most read day", stats.mostReadDay.ifBlank { "—" })
        PatternRow("Most read hour", stats.mostReadHour.ifBlank { "—" })
        PatternRow("Active days this week", "${stats.activeDaysThisWeek} of 7")
        PatternRow(
            label = "Synced from",
            value = plural(stats.sourceDevices, "device")
        )
    }
}

@Composable
private fun PatternRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = FolioTheme.typography.titleSmall,
            color = FolioTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CurrentlyReadingCard(books: List<ReadingInProgress>, onBookClick: (String) -> Unit) {
    FolioSectionCard(title = "Currently reading") {
        books.forEach { book ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookClick(book.id) }
                    .padding(vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.title,
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${(book.progress * 100).toInt()}%",
                        style = FolioTheme.typography.labelMedium,
                        color = FolioTheme.colors.primary
                    )
                }
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FolioProgressBar(progress = book.progress, color = FolioTheme.colors.primary)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    FolioSectionCard(title = "Nothing measured yet") {
        Text(
            text = "Folio counts reading time per session and syncs that history to your " +
                "account, so these figures cover every device you read on. Open a book and " +
                "the numbers appear here.",
            style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurfaceVariant
        )
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours >= 100 -> "${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

private fun formatHours(ms: Long): String = "${ms / 3_600_000}h"

private fun shortMinutes(minutes: Long): String = when {
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    minutes > 0 -> "${minutes}m"
    else -> "0m"
}

private fun formatCount(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun plural(count: Int, word: String): String =
    "$count $word" + if (count == 1) "" else "s"

private fun LocalDate.shortLabel(): String = "$dayOfMonth.$monthNumber"
