package com.folio.reader.ui.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaStatistics
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@Composable
internal fun FinishPredictionsCard(
    books: List<ReadingInProgress>,
    onBookClick: (String) -> Unit,
) {
    FolioSectionCard(title = "Finish predictions") {
        books.forEach { book ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookClick(book.id) }
                    .padding(vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.title,
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${(book.progress * 100).toInt()}%",
                        style = FolioTheme.typography.labelMedium,
                        color = FolioTheme.colors.primary,
                    )
                }
                FolioProgressBar(progress = book.progress, color = FolioTheme.colors.primary)
                if (book.finishEstimate != null) {
                    Text(
                        text = book.finishEstimate,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun FloatingQuotesCard(quotes: List<RecentQuote>) {
    FolioSectionCard(title = "Recent highlights") {
        quotes.forEach { quote ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.FormatQuote,
                        contentDescription = null,
                        tint = FolioTheme.colors.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = quote.text,
                        style = FolioTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = FolioTheme.colors.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = quote.bookTitle,
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
        }
    }
}

@Composable
internal fun MangaStatsSection(stats: MangaStatistics) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
        // ── Section header ────────────────────────────────────────────
        Text(
            text = "Manga",
            style = FolioTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = FolioTheme.colors.onSurface,
        )

        // ── Headline tiles (mirror the books StatTile grid) ───────────
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                StatTile(
                    label = "Chapters read",
                    value = stats.readChapters.toString(),
                    caption = plural(stats.completedCount, "series completed"),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Reading time",
                    value = formatDuration(stats.totalReadMinutes * 60_000L),
                    caption = plural(stats.downloadedChapters, "downloaded"),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Manga weekly activity chart ───────────────────────────────
        if (stats.weekReadChapters.any { it > 0 }) {
            MangaWeekChart(
                chaptersPerDay = stats.weekReadChapters,
                labels = stats.weekLabels,
            )
        }

        // ── Most-read manga (compact, non-clickable) ──────────────────
        if (stats.topManga.isNotEmpty()) {
            FolioSectionCard(title = "Most read") {
                stats.topManga.take(5).forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.title,
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = plural(entry.readChapters, "chapter"),
                            style = FolioTheme.typography.labelMedium,
                            color = FolioTheme.colors.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HeadlineRow(stats: StatisticsUiState) {
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
internal fun PatternsCard(stats: StatisticsUiState, mangaStats: MangaStatistics?) {
    FolioSectionCard(title = "Reading patterns") {
        if (!stats.hasData) {
            Text(
                "These fill in once there is a session or two to measure.",
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant
            )
            return@FolioSectionCard
        }
        if (stats.chronotype.isNotBlank()) {
            Text(
                text = stats.chronotype.uppercase(),
                style = FolioTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FolioTheme.colors.onSurface
            )
        }
        if (stats.peakWindow.isNotBlank()) {
            Text(
                text = "You read mostly between ${stats.peakWindow}.",
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant
            )
        }
        if (stats.chronotype.isNotBlank() || stats.peakWindow.isNotBlank()) {
            Spacer(Modifier.height(FolioTokens.space1))
        }
        PatternRow("Average session", shortMinutes(stats.averageSessionMinutes.toLong()))
        if (mangaStats != null && mangaStats.readActiveDays > 0) {
            PatternRow(
                "Average binge",
                "%.1f chapters".format(mangaStats.readChapters.toDouble() / mangaStats.readActiveDays)
            )
        }
        PatternRow("Current streak", plural(stats.streakDays, "day"))
        PatternRow("Most active hour", stats.mostReadHour.ifBlank { "—" })
        PatternRow("Favourite day", stats.mostReadDay.ifBlank { "—" })
        PatternRow("Average speed", "${stats.averageSpeedWpm.toInt()} wpm")
        PatternRow("Longest streak", plural(stats.longestStreakDays, "day"))
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

internal fun shortMinutes(minutes: Long): String = when {
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    minutes > 0 -> "${minutes}m"
    else -> "0m"
}

private fun formatCount(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun plural(count: Int, word: String): String =
    "$count $word" + if (count == 1) "" else "s"
