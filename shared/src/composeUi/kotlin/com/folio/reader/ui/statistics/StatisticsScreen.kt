package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import com.folio.reader.statistics.DashboardData
import com.folio.reader.statistics.ReadingPatterns
import com.folio.reader.ui.components.HeatmapCell
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    deviceId: String,
    onBackPress: () -> Unit,
    onBookClick: (String) -> Unit
) {
    val dashboard by viewModel.getDashboardData(deviceId).collectAsState(initial = DashboardData())
    val patterns by viewModel.getReadingPatterns(deviceId).collectAsState(initial = ReadingPatterns())

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("Statistics", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = FolioTheme.colors.surface)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("This week", formatDuration(dashboard.totalReadingTimeMs), Modifier.weight(1f))
                    StatCard("Streak", "${dashboard.currentStreak} day${if (dashboard.currentStreak == 1) "" else "s"}", Modifier.weight(1f))
                    StatCard("Finished", "${dashboard.totalBooksFinished}", Modifier.weight(1f))
                }
            }

            item {
                SectionCard("Reading patterns") {
                    PatternRow("Average session", "${"%.1f".format(patterns.averageSessionMinutes)} min")
                    PatternRow("Average speed", "${"%.0f".format(patterns.averageReadingSpeedWpm)} wpm")
                    PatternRow("Longest streak", "${patterns.longestStreakDays} days")
                    val dow = patterns.mostReadDayOfWeek.takeIf { it > 0 }
                        ?.let { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] } ?: "-"
                    PatternRow("Most read day", dow)
                    val hour = patterns.mostReadHour.takeIf { it >= 0 }?.let { "%02d:00".format(it) } ?: "-"
                    PatternRow("Most read hour", hour)
                    PatternRow("Total time", "%.1f h".format(patterns.totalReadingHours))
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = FolioTheme.colors.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Reading Heatmap", style = FolioTheme.typography.titleMedium)
                        val currentYear = Clock.System.todayIn(TimeZone.currentSystemDefault()).year
                        val heatmapData by viewModel.getHeatmapData(deviceId, currentYear).collectAsState(initial = emptyList())
                        // Render last 16 weeks as grid
                        if (heatmapData.isEmpty()) {
                            Text("No reading activity yet", style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
                        } else {
                            val weeks = heatmapData.sortedBy { it.date }.takeLast(112) // 16 weeks
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Group by week (7 days)
                                val chunks = weeks.chunked(7)
                                items(chunks.size) { weekIdx ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        chunks[weekIdx].forEach { entry ->
                                            val intensity = when {
                                                entry.readingTimeMs == 0L -> 0
                                                entry.readingTimeMs < 30 * 60 * 1000 -> 1
                                                entry.readingTimeMs < 60 * 60 * 1000 -> 2
                                                entry.readingTimeMs < 120 * 60 * 1000 -> 3
                                                else -> 4
                                            }.coerceIn(0, 4)
                                            // Fallback to model intensity if computed is 0 but model has value
                                            val finalIntensity = if (intensity == 0 && entry.intensity != 0) entry.intensity.coerceIn(0, 4) else intensity
                                            HeatmapCell(intensity = finalIntensity, size = 12.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (dashboard.currentlyReading.isNotEmpty()) {
                item {
                    SectionCard("Currently reading") {
                        for (book in dashboard.currentlyReading) {
                            Text(
                                text = book.title,
                                style = FolioTheme.typography.bodyLarge,
                                color = FolioTheme.colors.onSurface,
                                fontWeight = if (book.id == dashboard.continueReading?.id) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = FolioTheme.colors.surface)) {
        Column(Modifier.padding(16.dp)) {
            // Display serif: the numbers carry the page, so they get the widest
            // optical cut rather than a faked-bold weight.
            Text(value, style = FolioTheme.typography.displaySmall, color = FolioTheme.colors.primary)
            Text(label, style = FolioTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = FolioTheme.colors.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PatternRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
        Text(value, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurface)
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
