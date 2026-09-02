package com.folio.reader.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.StatCard
import com.folio.reader.ui.components.finishEstimate
import com.folio.reader.ui.components.readingPaceWordsPerDay
import com.folio.reader.ui.statistics.ChartBar
import com.folio.reader.ui.statistics.intensityFor
import com.folio.reader.ui.statistics.shortMinutes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlin.math.roundToLong
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun BookMetadataEditorDialog(
    book: Book,
    assignedCollectionIds: Set<String>,
    series: List<Series>,
    collections: List<Collection>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String?, String, Set<String>, String, String) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var subtitle by remember(book.id) { mutableStateOf(book.subtitle.orEmpty()) }
    var authors by remember(book.id) { mutableStateOf(book.authors.joinToString(", ")) }
    var publisher by remember(book.id) { mutableStateOf(book.publisher.orEmpty()) }
    var language by remember(book.id) { mutableStateOf(book.language.orEmpty()) }
    var isbn by remember(book.id) { mutableStateOf(book.isbn.orEmpty()) }
    var description by remember(book.id) { mutableStateOf(book.description.orEmpty()) }
    var selectedSeriesId by remember(book.id) { mutableStateOf(book.seriesId) }
    var seriesNumber by remember(book.id) { mutableStateOf(book.seriesNumber?.toString().orEmpty()) }
    var selectedCollectionIds by remember(book.id) { mutableStateOf(assignedCollectionIds) }
    var newSeriesName by remember(book.id) { mutableStateOf("") }
    var newCollectionName by remember(book.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), isError = title.isBlank())
                OutlinedTextField(subtitle, { subtitle = it }, label = { Text("Subtitle") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(authors, { authors = it }, label = { Text("Authors (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(publisher, { publisher = it }, label = { Text("Publisher") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(language, { language = it }, label = { Text("Language") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(isbn, { isbn = it }, label = { Text("ISBN") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Text("Series", style = FolioTheme.typography.titleSmall)
                TextButton(onClick = { selectedSeriesId = null }) { Text(if (selectedSeriesId == null) "No series" else "Remove series") }
                series.forEach { item ->
                    AssistChip(
                        onClick = { selectedSeriesId = item.id },
                        label = { Text(if (selectedSeriesId == item.id) "✓ ${item.name}" else item.name) }
                    )
                }
                OutlinedTextField(newSeriesName, { newSeriesName = it }, label = { Text("Create series") }, modifier = Modifier.fillMaxWidth())
                if (selectedSeriesId != null || newSeriesName.isNotBlank()) {
                    OutlinedTextField(seriesNumber, { seriesNumber = it }, label = { Text("Series number") }, modifier = Modifier.fillMaxWidth())
                }
                Text("Collections", style = FolioTheme.typography.titleSmall)
                collections.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.id in selectedCollectionIds,
                            onCheckedChange = { checked ->
                                selectedCollectionIds = if (checked) selectedCollectionIds + item.id else selectedCollectionIds - item.id
                            }
                        )
                        Text(item.name)
                    }
                }
                OutlinedTextField(newCollectionName, { newCollectionName = it }, label = { Text("Create collection") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title, subtitle, authors, publisher, language, isbn, description, selectedSeriesId, seriesNumber, selectedCollectionIds, newSeriesName, newCollectionName)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun BookDescription(description: String) {
    val plainDescription = remember(description) {
        description
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Description",
                style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = plainDescription,
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
            if (plainDescription.length > 300) {
                TextButton(
                    onClick = { expanded = !expanded }
                ) {
                    Text(if (expanded) "Show less" else "Read more")
                }
            }
        }
    }
}

@Composable
private fun ReadingActionSection(
    book: Book,
    onStartReading: () -> Unit
) {
    val hasStarted = book.normalizedProgress > 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStartReading,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (hasStarted) "Continue Reading" else "Start Reading",
                style = FolioTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun StatsRow(
    sessionsCount: Int,
    wordsRead: Long,
    highlightsCount: Int,
    bookmarksCount: Int,
    notesCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Reading Stats",
            style = FolioTheme.typography.titleMedium,
            color = FolioTheme.colors.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = "Sessions",
                value = "$sessionsCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
            StatCard(
                title = "Words",
                value = formatCount(wordsRead),
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = "Highlights",
                value = "$highlightsCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
            StatCard(
                title = "Bookmarks",
                value = "$bookmarksCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
            StatCard(
                title = "Notes",
                value = "$notesCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
        }
    }
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = FolioTheme.colors.surfaceVariant,
            contentColor = FolioTheme.colors.onSurfaceVariant
        )
    ) {
        content()
    }
}

internal fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}

/**
 * §5.2 "Your reading" card on book detail: a 30-day session sparkline, lifetime
 * totals and pace, and a highlight-density strip by chapter. Renders nothing
 * while the book has neither sessions nor highlights.
 */
@Composable
internal fun BookReadingSection(
    sessions: List<ReadingSession>,
    highlights: List<Highlight>,
    totalWords: Long,
    progress: Double,
) {
    if (sessions.isEmpty() && highlights.isEmpty()) return

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
            )
        }

        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(FolioTokens.space1))
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                val totalMinutes = sessions.sumOf { it.durationMs } / 60_000L
                ReadingRow("Total time read", shortMinutes(totalMinutes))
                val averageMinutes = sessions.sumOf { it.durationMs }.toDouble() / sessions.size / 60_000.0
                ReadingRow("Average session", shortMinutes(averageMinutes.roundToLong()))
                readingPaceWordsPerDay(sessions)?.let { pace ->
                    ReadingRow("Pace (words/day)", "≈ ${formatCount(pace.toLong())} words/day")
                }
                finishEstimate(totalWords, progress, sessions)?.let { estimate ->
                    Text(
                        text = estimate,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
        }

        if (highlights.isNotEmpty()) {
            val perChapter = highlights.groupingBy { it.spineIndex }.eachCount()
            val busiest = perChapter.values.max()
            Spacer(Modifier.height(FolioTokens.space2))
            Row(modifier = Modifier.fillMaxWidth().height(FolioTokens.heatStrip)) {
                (perChapter.keys.min()..perChapter.keys.max()).forEach { spine ->
                    val count = perChapter[spine] ?: 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (count == 0) FolioTheme.colors.outline.copy(alpha = 0.22f)
                                else FolioTheme.colors.primary.copy(
                                    alpha = stripAlpha(intensityFor(count.toLong(), busiest.toLong()))
                                )
                            )
                    )
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
            Text(
                text = "${highlights.size} highlight${if (highlights.size == 1) "" else "s"} " +
                    "across ${perChapter.size} chapter${if (perChapter.size == 1) "" else "s"}",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ReadingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
        Text(value, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurface)
    }
}

/** Mirrors the heatmap banding so one dense chapter does not flatten the rest. */
private fun stripAlpha(intensity: Int): Float = when (intensity) {
    4 -> 1f
    3 -> 0.7f
    2 -> 0.45f
    else -> 0.25f
}

internal fun dayLabel(date: LocalDate): String = "${date.dayOfMonth}.${date.monthNumber}"

internal data class DayBucket(val date: LocalDate, val minutes: Long)

internal fun sessionMinutesByDay(sessions: List<ReadingSession>, days: Int): List<DayBucket> {
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    val byDay = sessions.groupBy { it.startedAt.toLocalDateTime(zone).date }
        .mapValues { (_, day) -> day.sumOf { it.durationMs } / 60_000L }
    return (days - 1 downTo 0).map { offset ->
        val date = LocalDate.fromEpochDays(today.toEpochDays() - offset)
        DayBucket(date, byDay[date] ?: 0L)
    }
}
