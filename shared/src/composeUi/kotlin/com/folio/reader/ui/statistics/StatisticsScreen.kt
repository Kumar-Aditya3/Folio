package com.folio.reader.ui.statistics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.database.SettingsRepository
import com.folio.reader.manga.MangaStatistics
import com.folio.reader.manga.MangaStatisticsRepository
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.HeatmapCell
import com.folio.reader.ui.components.StatCard
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

// ---------------------------------------------------------------------------
// Embeddable Stats Tab — no Scaffold / top bar of its own
// ---------------------------------------------------------------------------

/**
 * Embeddable statistics hub rendered inside the library screen's tab area.
 * No Scaffold or top bar — the host provides those.
 *
 * @param viewModel statistics view model (must outlive this composable)
 * @param onBookClick navigates to a book detail / reader
 * @param settingsRepository optional; needed to persist daily-goal edits.
 *        When null the goal ring still renders but the edit affordance is hidden.
 * @param initialGoalMinutes the current daily goal from GlobalSettings; used as
 *        the starting value before any in-session edit. The host should pass
 *        `settings.dailyGoalMinutes`.
 * @param mangaStatsRepo optional manga statistics repository. When non-null and
 *        the repo reports data, a manga section is rendered in the same glassy
 *        design language as the book stats.
 */
@Composable
fun StatisticsTabContent(
    viewModel: StatisticsViewModel,
    onBookClick: (String) -> Unit,
    settingsRepository: SettingsRepository? = null,
    initialGoalMinutes: Int = 60,
    mangaStatsRepo: MangaStatisticsRepository? = null,
) {
    val stats by viewModel.state.collectAsState(initial = StatisticsUiState())
    val recentQuotes by (viewModel.recentQuotes
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    // ── Manga statistics (loaded once when the repo is available) ────────
    var mangaStats by remember { mutableStateOf<MangaStatistics?>(null) }
    LaunchedEffect(mangaStatsRepo) {
        mangaStats = mangaStatsRepo?.getStatistics()
    }

    var goalMinutes by remember { mutableIntStateOf(initialGoalMinutes) }
    // Sync when the host pushes a new value (e.g. after external settings change).
    LaunchedEffect(initialGoalMinutes) { goalMinutes = initialGoalMinutes }

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
        // ── a. Daily goal ring ────────────────────────────────────────
        item {
            DailyGoalRing(
                todayMinutes = stats.todayMinutes,
                goalMinutes = goalMinutes,
                onGoalChanged = { newGoal ->
                    goalMinutes = newGoal
                },
                settingsRepository = settingsRepository,
            )
        }

        // ── b. Finish predictions ─────────────────────────────────────
        if (stats.currentlyReading.isNotEmpty()) {
            item {
                FinishPredictionsCard(
                    books = stats.currentlyReading.take(5),
                    onBookClick = onBookClick,
                )
            }
        }

        // ── c. Floating quotes & highlights ───────────────────────────
        if (recentQuotes.isNotEmpty()) {
            item {
                FloatingQuotesCard(quotes = recentQuotes)
            }
        }

        // ── c2. Manga statistics (when available) ─────────────────────
        if (mangaStats != null && mangaStats!!.hasData) {
            item {
                MangaStatsSection(mangaStats!!)
            }
        }

        // ── d. Deep stats (existing sections) ─────────────────────────
        item { HeadlineRow(stats) }
        item { WeekChart(stats.week) }
        item { ActivityHeatmap(stats.heatmap, stats.heatmapBooks, stats.heatmapManga) }
        item { PatternsCard(stats, mangaStats) }
        if (!stats.hasData) {
            item { EmptyState() }
        }
        item { Spacer(Modifier.height(FolioTokens.space1)) }
    }
}

// ---------------------------------------------------------------------------
// Daily Goal Ring
// ---------------------------------------------------------------------------

@Composable
private fun DailyGoalRing(
    todayMinutes: Long,
    goalMinutes: Int,
    onGoalChanged: (Int) -> Unit,
    settingsRepository: SettingsRepository?,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val fraction = if (goalMinutes > 0) (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "goalRing",
    )

    Column(
        modifier = Modifier
            .glassPanel(RoundedCornerShape(FolioTokens.radiusCard))
            .padding(FolioTokens.space3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Daily goal",
                style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
            )
            if (settingsRepository != null) {
                IconButton(onClick = { showEditDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit daily goal",
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(FolioTokens.space2))

        val trackColor = FolioTheme.colors.outline.copy(alpha = 0.2f)
        val progressColor = FolioTheme.colors.primary
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 12.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                // Track
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // Progress
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${todayMinutes.toInt()}",
                    style = FolioTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = FolioTheme.colors.primary,
                )
                Text(
                    text = "of $goalMinutes min",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
        }
    }

    if (showEditDialog) {
        GoalEditDialog(
            currentGoal = goalMinutes,
            onDismiss = { showEditDialog = false },
            onSave = { newGoal ->
                onGoalChanged(newGoal)
                showEditDialog = false
            },
            settingsRepository = settingsRepository,
        )
    }
}

@Composable
private fun GoalEditDialog(
    currentGoal: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    settingsRepository: SettingsRepository?,
) {
    var sliderValue by remember { mutableFloatStateOf(currentGoal.toFloat()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily reading goal") },
        text = {
            Column {
                Text(
                    "${sliderValue.toInt()} minutes",
                    style = FolioTheme.typography.headlineSmall,
                    color = FolioTheme.colors.primary,
                )
                Spacer(Modifier.height(FolioTokens.space2))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 5f..300f,
                    steps = 58, // 5-minute increments
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newGoal = sliderValue.toInt()
                onSave(newGoal)
                if (settingsRepository != null) {
                    scope.launch {
                        val current = settingsRepository.getGlobalSettings()
                        settingsRepository.saveGlobalSettings(
                            current.copy(dailyGoalMinutes = newGoal)
                        )
                    }
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---------------------------------------------------------------------------
// Finish Predictions
// ---------------------------------------------------------------------------

@Composable
private fun FinishPredictionsCard(
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

// ---------------------------------------------------------------------------
// Floating Quotes
// ---------------------------------------------------------------------------

@Composable
private fun FloatingQuotesCard(quotes: List<RecentQuote>) {
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

// ---------------------------------------------------------------------------
// Manga Statistics Section
// ---------------------------------------------------------------------------

@Composable
private fun MangaStatsSection(stats: MangaStatistics) {
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

/**
 * A bar chart of manga chapters read per day over the last 7 days, styled
 * identically to the books [WeekChart] but with chapter counts instead of minutes.
 */
@Composable
private fun MangaWeekChart(chaptersPerDay: List<Int>, labels: List<String>) {
    FolioSectionCard(title = "Manga — last 7 days") {
        val peak = (chaptersPerDay.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(
            modifier = Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            verticalAlignment = Alignment.Bottom,
        ) {
            chaptersPerDay.forEachIndexed { index, chapters ->
                val label = labels.getOrNull(index)?.take(1) ?: ""
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
                    val fraction = (chapters.toFloat() / peak).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (chapters > 0) (28 + fraction * 48).dp else 6.dp)
                            .background(
                                if (chapters > 0) FolioTheme.colors.primary
                                else FolioTheme.colors.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(
                                    topStart = 6.dp, topEnd = 6.dp,
                                    bottomStart = 2.dp, bottomEnd = 2.dp,
                                ),
                            ),
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

// ---------------------------------------------------------------------------
// Existing private helpers (unchanged)
// ---------------------------------------------------------------------------

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

private enum class HeatmapMode { ALL, BOOKS, MANGA }

@Composable
private fun ActivityHeatmap(
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

    FolioSectionCard(title = "Activity") {
        // ── Segmented toggle ──────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolioChip(selected = mode == HeatmapMode.ALL, onClick = { mode = HeatmapMode.ALL }, label = "All")
            FolioChip(selected = mode == HeatmapMode.BOOKS, onClick = { mode = HeatmapMode.BOOKS }, label = "Books")
            FolioChip(selected = mode == HeatmapMode.MANGA, onClick = { mode = HeatmapMode.MANGA }, label = "Manga")
        }

        Spacer(Modifier.height(FolioTokens.space2))

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
private fun PatternsCard(stats: StatisticsUiState, mangaStats: MangaStatistics?) {
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
