package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaStatistics
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioCallout
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Stats' sections, rebuilt as an editorial spread.
 *
 * The through-line: **not every statistic deserves a card.** Lists get hairline
 * rules, figures get type scale, charts get sunken wells, and only the heatmap —
 * the one genuinely visual artefact — stays raised. Eight identical cards were
 * what made the old screen read as a spreadsheet.
 */

/**
 * Finish predictions: a ruled list with the percentage as a figure at the leading
 * edge, so the eye can scan progress down a column instead of hunting inside
 * boxes. No container at all.
 */
@Composable
internal fun FinishPredictionsCard(
    books: List<ReadingInProgress>,
    onBookClick: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        FolioSectionHead(title = "On pace to finish")
        Spacer(Modifier.height(FolioTokens.space3))
        books.forEachIndexed { index, book ->
            if (index > 0) FolioRule()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookClick(book.id) }
                    .padding(vertical = FolioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceHair),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${(book.progress * 100).toInt()}",
                        style = FolioTheme.typography.titleLarge,
                        color = FolioTheme.colors.accentProgress,
                    )
                    Text(
                        text = "%",
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.accentProgress.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Spacer(Modifier.width(FolioTokens.space2))
                    Text(
                        text = book.title,
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FolioProgressBar(progress = book.progress, color = FolioTheme.colors.accentProgress)
                if (book.finishEstimate != null) {
                    Text(
                        text = book.finishEstimate,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Top books: a ranked shelf with the rank set as a figure. Numbering a
 * leaderboard is what makes it read as a leaderboard — the old version was three
 * anonymous rows in a card and could have been any list on the screen.
 */
@Composable
internal fun TopBooksCard(
    books: List<TopBook>,
    onBookClick: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        FolioSectionHead(title = "Most read", eyebrow = "This year")
        Spacer(Modifier.height(FolioTokens.space3))
        books.forEachIndexed { index, entry ->
            if (index > 0) FolioRule()
            val interaction = rememberFolioInteraction()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .folioPressable(interaction, scaleTo = 0.99f)
                    .clickable(interactionSource = interaction, indication = null) {
                        onBookClick(entry.id)
                    }
                    .padding(vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = FolioTheme.typography.titleLarge,
                    color = FolioTheme.colors.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.width(26.dp),
                )
                FolioCoverPlate(
                    coverPath = entry.coverPath,
                    title = entry.title,
                    author = entry.author,
                    width = FolioTokens.coverInline,
                    shape = FolioShapes.plateSmall,
                    elevation = 5.dp,
                    small = true,
                )
                Spacer(Modifier.width(FolioTokens.space3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.author.isNotBlank()) {
                        Text(
                            text = entry.author,
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(FolioTokens.space2))
                Text(
                    text = shortMinutes(entry.minutes),
                    style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.accentProgress,
                )
            }
        }
    }
}

/**
 * Genre breakdown, as a **sunken well of stacked bars**. §12.6: one hue per row
 * from the theme's `chartSeries` role — never one hue at N alphas. The peak row
 * marks itself with label weight (Rule 15). Sinking it separates data from the
 * ruled lists above and below without adding another card rim; the bars grow from
 * the leading edge, so the well reads as a chart rather than a list of pills.
 */
@Composable
internal fun GenresCard(slices: List<TagSlice>) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioSectionHead(title = "What you read")
        }
        Spacer(Modifier.height(FolioTokens.space3))
        val hues = FolioTheme.colors.chartSeries
        val peak = (slices.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            slices.forEachIndexed { index, slice ->
                val hue = hues[index % hues.size]
                val isPeak = slice.minutes >= peak
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = slice.label,
                        style = FolioTheme.typography.bodyMedium,
                        fontWeight = if (isPeak) FontWeight.SemiBold else FontWeight.Normal,
                        color = FolioTheme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shortMinutes(slice.minutes),
                        style = FolioTheme.typography.labelMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth((slice.minutes.toFloat() / peak).coerceIn(0.04f, 1f))
                        .height(7.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(hue, hue.copy(alpha = FolioTokens.gradientMinAlpha))
                            ),
                            RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
                        )
                )
            }
        }
    }
}

/**
 * Recent highlights as pull-quotes: an accent rule on the leading edge, the
 * passage in the italic display face (`typography.quote`), the source beneath. A
 * quote is the author's voice — boxing it in a card with an icon is what made
 * these read as log entries.
 */
@Composable
internal fun FloatingQuotesCard(quotes: List<RecentQuote>) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        FolioSectionHead(
            title = "Passages you kept",
            accent = FolioTheme.colors.accentAnnotation,
        )
        Spacer(Modifier.height(FolioTokens.space3))
        quotes.forEachIndexed { index, quote ->
            if (index > 0) Spacer(Modifier.height(FolioTokens.spaceBeat))
            FolioCallout(accent = FolioTheme.colors.accentAnnotation) {
                Text(
                    text = quote.text,
                    style = FolioTheme.typography.quote,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quote.bookTitle,
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The manga half of Stats. Same language as the books half — an eyebrow, figures
 * on the page, the chart in a well — so the two halves read as one document with a
 * section break rather than two dashboards stapled together.
 */
@Composable
internal fun MangaStatsSection(stats: MangaStatistics) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioSectionHead(title = "Manga", eyebrow = "Also tracked")
            Spacer(Modifier.height(FolioTokens.space3))
            Row(modifier = Modifier.fillMaxWidth()) {
                FolioFigure(
                    value = stats.readChapters.toString(),
                    label = "Chapters read",
                    caption = plural(stats.completedCount, "series completed"),
                    accent = FolioTheme.colors.accentProgress,
                    emphasis = FigureScale.Quiet,
                    modifier = Modifier.weight(1f),
                )
                FolioFigure(
                    value = formatDuration(stats.totalReadMinutes * 60_000L),
                    label = "Reading time",
                    caption = plural(stats.downloadedChapters, "downloaded"),
                    accent = FolioTheme.colors.accentProgress,
                    emphasis = FigureScale.Quiet,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (stats.weekReadChapters.any { it > 0 }) {
            Spacer(Modifier.height(FolioTokens.spaceBeat))
            MangaWeekChart(
                chaptersPerDay = stats.weekReadChapters,
                labels = stats.weekLabels,
            )
        }

        if (stats.topManga.isNotEmpty()) {
            Spacer(Modifier.height(FolioTokens.spaceBeat))
            Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
                FolioEyebrow("Most read")
                Spacer(Modifier.height(FolioTokens.space1))
                stats.topManga.take(5).forEachIndexed { index, entry ->
                    if (index > 0) FolioRule()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FolioTokens.space2),
                        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            text = entry.title,
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = shortMinutes(entry.readMinutes),
                            style = FolioTheme.typography.labelMedium,
                            color = FolioTheme.colors.accentProgress,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The ledger: four figures on the page, no tiles. Two per row, generous gaps, a
 * hairline rule between rows. The streak figure runs at Standard emphasis while
 * the rest are Quiet, so even inside a group of small numbers there is a rank.
 */
@Composable
internal fun HeadlineRow(stats: StatisticsUiState) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            FolioFigure(
                value = formatDuration(stats.timeThisWeekMs),
                label = "This week",
                caption = plural(stats.sessionsThisWeek, "session"),
                accent = FolioTheme.colors.accentProgress,
                emphasis = FigureScale.Quiet,
                modifier = Modifier.weight(1f),
            )
            FolioFigure(
                value = stats.streakDays.toString(),
                unit = if (stats.streakDays == 1) "day" else "days",
                label = "Streak",
                caption = if (stats.longestStreakDays > 0) {
                    "best ${stats.longestStreakDays}"
                } else "reading days",
                accent = FolioTheme.colors.accentStreak,
                emphasis = FigureScale.Standard,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(FolioTokens.spaceBeat))
        FolioRule()
        Spacer(Modifier.height(FolioTokens.spaceBeat))
        Row(modifier = Modifier.fillMaxWidth()) {
            FolioFigure(
                value = stats.booksFinished.toString(),
                label = "Finished",
                caption = "books completed",
                emphasis = FigureScale.Quiet,
                modifier = Modifier.weight(1f),
            )
            FolioFigure(
                value = formatHours(stats.timeThisYearMs),
                label = "This year",
                caption = "${formatCount(stats.wordsReadThisYear)} words",
                emphasis = FigureScale.Quiet,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Reading patterns as a narrative, not a settings list. The chronotype is a
 * headline, the peak window is its sentence, and the numbers below it are ruled
 * rows in a sunken well — data that supports a statement rather than eleven
 * equal-weight facts in a box.
 */
@Composable
internal fun PatternsCard(stats: StatisticsUiState, mangaStats: MangaStatistics?) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioEyebrow("Reading patterns", accent = FolioTheme.colors.accentDiscovery)
            Spacer(Modifier.height(FolioTokens.spaceHair))
            if (!stats.hasData) {
                Text(
                    "These fill in once there is a session or two to measure.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                return
            }
            if (stats.chronotype.isNotBlank()) {
                Text(
                    text = stats.chronotype,
                    style = FolioTheme.typography.headlineSmall,
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
        }
        Spacer(Modifier.height(FolioTokens.space3))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space2)
        ) {
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
            PatternRow("Synced from", plural(stats.sourceDevices, "device"), last = true)
        }
    }
}

@Composable
private fun PatternRow(label: String, value: String, last: Boolean = false) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = FolioTokens.space2),
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
        if (!last) FolioRule()
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
