package com.folio.reader.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.book.ReadingRow
import com.folio.reader.ui.book.dayLabel
import com.folio.reader.ui.book.sessionMinutesByDay
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.statistics.ChartBar
import com.folio.reader.ui.statistics.shortMinutes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlin.math.roundToLong
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * §11.5 per-manga reading stats on manga detail, mirroring the book §5.2 card:
 * a 30-day minutes sparkline and time/chapter rows. Manga sessions have no words,
 * so the words-pace and finish-estimate rows are replaced by chapter counts and
 * §11.6 explicitly excludes a finish estimate.
 */
@Composable
internal fun MangaReadingSection(
    sessions: List<ReadingSession>,
    chaptersRead: Int,
    chaptersReadThisWeek: Int,
) {
    if (sessions.isEmpty()) return

    FolioSectionCard(title = "Your reading") {
        val buckets = sessionMinutesByDay(sessions, days = 30)
        if (buckets.any { it.minutes > 0L }) {
            val peak = buckets.maxOf { it.minutes }.coerceAtLeast(1L)
            Row(
                modifier = Modifier.fillMaxWidth().height(FolioTokens.chartHeight),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.chartSparkGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                buckets.forEach { day ->
                    ChartBar(
                        value = day.minutes.toFloat(),
                        peak = peak.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
            Text(
                text = "${dayLabel(buckets.first().date)} – ${dayLabel(buckets.last().date)}",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(FolioTokens.space1))
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
            val totalMinutes = sessions.sumOf { it.durationMs } / 60_000L
            ReadingRow("Total time read", shortMinutes(totalMinutes))
            val averageMinutes = sessions.sumOf { it.durationMs }.toDouble() / sessions.size / 60_000.0
            ReadingRow("Average session", shortMinutes(averageMinutes.roundToLong()))
            val zone = TimeZone.currentSystemDefault()
            val weekAgo = Clock.System.now().toLocalDateTime(zone).date.minus(DatePeriod(days = 7))
            val weekMinutes = sessions
                .filter { it.startedAt.toLocalDateTime(zone).date >= weekAgo }
                .sumOf { it.durationMs } / 60_000L
            ReadingRow("Last 7 days", shortMinutes(weekMinutes))
            ReadingRow("Chapters read", chaptersRead.toString())
            ReadingRow("Chapters (7 days)", chaptersReadThisWeek.toString())
        }
    }
}
