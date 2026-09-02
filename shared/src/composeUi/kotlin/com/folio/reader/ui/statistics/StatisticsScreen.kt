package com.folio.reader.ui.statistics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import com.folio.reader.ui.components.rememberEntryProgress
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folio.reader.database.SettingsRepository
import com.folio.reader.manga.MangaStatistics
import com.folio.reader.manga.MangaStatisticsRepository
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch

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
    onOpenExclusions: (() -> Unit)? = null,
) {
    val stats by viewModel.state.collectAsState(initial = StatisticsUiState())
    val recentQuotes by (viewModel.recentQuotes
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    // §11.2/Rule 8: the live exclusion set drives both the review line and the
    // manga statistics, which compute with it (never filter after the fact).
    val exclusions by viewModel.exclusions.collectAsState(initial = emptySet())

    // ── Manga statistics (reloaded when the exclusion set changes) ───────
    var mangaStats by remember { mutableStateOf<MangaStatistics?>(null) }
    LaunchedEffect(mangaStatsRepo, exclusions) {
        mangaStats = mangaStatsRepo?.getStatistics(exclusions)
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
        // ── Rule 8 — an exclusion the user cannot see is invisible behavior ──
        if (exclusions.isNotEmpty() && onOpenExclusions != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FolioTokens.radiusChip))
                        .clickable(onClick = onOpenExclusions)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Some titles are excluded — review",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }
        }

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

        // ── b2. §12.5 top-books leaderboard + genre breakdown ─────────
        if (stats.topBooks.isNotEmpty()) {
            item { TopBooksCard(books = stats.topBooks, onBookClick = onBookClick) }
        }
        if (stats.genres.isNotEmpty()) {
            item { GenresCard(slices = stats.genres) }
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
    // §13.5: the ring sweeps in once on entry; live goal changes keep using the
    // fraction animation above.
    val ringEntry = rememberEntryProgress("dailyGoalRing")

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
        // §12.5/Rule 14: forward motion is accentProgress, never primary.
        val accent = FolioTheme.colors.accentProgress
        val progressBrush = Brush.verticalGradient(
            listOf(accent, accent.copy(alpha = FolioTokens.gradientMinAlpha))
        )
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
                    brush = progressBrush,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedFraction * ringEntry,
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
                    color = accent,
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
// Empty state
// ---------------------------------------------------------------------------

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
