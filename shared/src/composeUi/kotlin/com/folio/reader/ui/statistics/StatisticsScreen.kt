package com.folio.reader.ui.statistics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import com.folio.reader.ui.components.rememberEntryState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.database.SettingsRepository
import com.folio.reader.manga.MangaStatistics
import com.folio.reader.manga.MangaStatisticsRepository
import com.folio.reader.statistics.Scope
import kotlinx.coroutines.flow.StateFlow
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.launch

/**
 * Duration of the skeleton → content handoff.
 *
 * Deliberately longer than [FolioTokens.motionFast] and shorter than
 * [FolioTokens.motionStandard]: the skeleton has been on screen for however long
 * the queries took, so the eye has already adapted to the layout. This is a
 * settle, not an arrival — long enough to read as intentional, short enough that
 * the reader never waits on it.
 */
private const val StatsHandoffFadeMs = 180

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
    /**
     * The page's state.
     *
     * Typed `StateFlow` rather than `Flow` on purpose, and it is not incidental:
     * `collectAsState` has a separate overload for `StateFlow` that takes **no**
     * initial value and reads `.value` synchronously, so a hot flow paints its real
     * contents on the very first composition frame. Through the generic `Flow`
     * overload the same flow would instead render the passed initial for one frame
     * and then correct itself — the flash this parameter exists to remove, and one
     * Kotlin cannot avoid because overload resolution is static on the declared type.
     *
     * [StatisticsViewModel.state] is a cold `combine` over a year of sessions, the
     * book lists and a per-book exclusion pass. Collected inside a nav destination
     * it restarted in full on every return to the tab. Defaulting to the view
     * model's `stateIn` — which is eager and process-wide — means the work happens
     * once at app start and every later visit is a read of an already-final value.
     */
    state: StateFlow<StatisticsUiState> = viewModel.sharedState,
    /** Companion to [state]; see [StatisticsViewModel.ready]. */
    readyFlow: StateFlow<Boolean> = viewModel.sharedReady,
    /** Companion to [state]; see [StatisticsViewModel.exclusions]. */
    exclusionsFlow: StateFlow<Set<Pair<Scope, String>>> = viewModel.sharedExclusions,
    /** Companion to [state]; see [StatisticsViewModel.recentQuotes]. */
    recentQuotesFlow: StateFlow<List<RecentQuote>> = viewModel.sharedRecentQuotes,
    /** Tapping a kept passage opens that quote's chapter in the reader (bookId, spineIndex).
     *  Default no-op keeps other hosts unchanged. */
    onOpenQuote: (RecentQuote) -> Unit = {},
) {
    val stats by state.collectAsState()
    // Gate the first frames on the real emission. `state` is a combine of four
    // suspense queries (plus a roundtrip per book when exclusions resolve tags and
    // collections), so the initial empty state used to paint zeroed figures and the
    // "Nothing measured yet" card, then change every value at once. The skeleton
    // draws the same layout in wells so only the numbers arrive.
    val ready by readyFlow.collectAsState()
    val recentQuotes by recentQuotesFlow.collectAsState()
    // §11.2/Rule 8: the live exclusion set drives both the review line and the
    // manga statistics, which compute with it (never filter after the fact).
    val exclusions by exclusionsFlow.collectAsState()

    // ── Manga statistics ─────────────────────────────────────────────────
    // The repository runs a library count, a completed count and the reading
    // aggregates in one pass. Keyed on the exclusion set rather than re-fetched
    // per visit: the old `LaunchedEffect(mangaStatsRepo, exclusions)` re-ran all of
    // it every time the tab was entered, which made re-entering Stats as expensive
    // as opening it. A settings-driven exclusion change still reloads, because that
    // is a real change to what the numbers mean.
    var mangaStats by remember { mutableStateOf<MangaStatistics?>(null) }
    LaunchedEffect(mangaStatsRepo, exclusions) {
        mangaStats = mangaStatsRepo?.getStatistics(exclusions)
    }

    var goalMinutes by remember { mutableIntStateOf(initialGoalMinutes) }
    // Sync when the host pushes a new value (e.g. after external settings change).
    LaunchedEffect(initialGoalMinutes) { goalMinutes = initialGoalMinutes }

    // Rule 19: read once, use for both halves of the cross-fade. When motion is
    // off the duration is 0ms and the two AnimatedVisibility blocks become a plain
    // swap — same information, none of the motion.
    val motionEnabled = rememberMotionEnabled()
    val fadeMs = StatsHandoffFadeMs

    // Re-tap on the Stats nav item scrolls the tab back to its top. The tab
    // renders inside the library host's tab area, so the bus is the only way
    // the re-tap reaches this scrollable.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        com.folio.reader.ui.components.FolioTabReselect.events.collect { (route, _) ->
            if (route == "stats" &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            ) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // The skeleton carries the same top inset as the list so the first real frame
    // does not shift the page up by the height of the masthead's clearance.
    //
    // The handoff is cross-faded rather than cut. `ready` flips when the combine
    // emits, and a hard swap there is the *second* half of the abruptness the
    // reader reported: the page is correct at last, but it changes in one frame.
    // A short fade costs nothing and lets the values settle in rather than appear.
    //
    // Rule 19: under reduce-motion the fade collapses to 0ms, which is a plain
    // swap — the same information, none of the motion.
    AnimatedVisibility(
        visible = !ready,
        enter = EnterTransition.None,
        exit = fadeOut(tween(if (motionEnabled) fadeMs else 0)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = com.folio.reader.ui.theme.LocalFolioTopInset.current),
        ) {
            StatsSkeleton()
        }
    }

    AnimatedVisibility(
        visible = ready,
        enter = fadeIn(tween(if (motionEnabled) fadeMs else 0)),
        exit = ExitTransition.None,
    ) {
        StatisticsContent(
            stats = stats,
            listState = listState,
            exclusions = exclusions,
            goalMinutes = goalMinutes,
            onGoalChanged = { goalMinutes = it },
            settingsRepository = settingsRepository,
            mangaStats = mangaStats,
            recentQuotes = recentQuotes,
            onBookClick = onBookClick,
            onOpenExclusions = onOpenExclusions,
            onOpenQuote = onOpenQuote,
        )
    }
}

/**
 * The real page, split out so [StatisticsTabContent] can cross-fade it against the
 * skeleton. The split is presentational only — every hook the page needs is passed
 * in, so this composable holds no state of its own and mounting it later than the
 * skeleton changes nothing about what it reads.
 */
@Composable
private fun StatisticsContent(
    stats: StatisticsUiState,
    listState: LazyListState,
    exclusions: Set<Pair<Scope, String>>,
    goalMinutes: Int,
    onGoalChanged: (Int) -> Unit,
    settingsRepository: SettingsRepository?,
    mangaStats: MangaStatistics?,
    recentQuotes: List<RecentQuote>,
    onBookClick: (String) -> Unit,
    onOpenExclusions: (() -> Unit)?,
    onOpenQuote: (RecentQuote) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = com.folio.reader.ui.theme.LocalFolioTopInset.current,
            bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current
        ),
    ) {
        // ── Rule 8 — an exclusion the user cannot see is invisible behavior ──
        if (exclusions.isNotEmpty() && onOpenExclusions != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenExclusions)
                        .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space2),
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

        // ── No sessions yet: lead with the explanation, not a screenful of
        // zeroed charts. Manga is tracked separately and can still carry data of
        // its own, so it keeps its section beneath the note. ──
        if (!stats.hasData) {
            item { EmptyState() }
            if (mangaStats != null && mangaStats!!.hasData) {
                item {
                    Spacer(Modifier.height(FolioTokens.spaceMovement))
                    MangaStatsSection(mangaStats!!)
                }
            }
            return@LazyColumn
        }

        // ── a. Daily goal ring ────────────────────────────────────────
        item {
            StatsOverture(
                stats = stats,
                goalMinutes = goalMinutes,
                onGoalChanged = onGoalChanged,
                settingsRepository = settingsRepository,
            )
            Spacer(Modifier.height(FolioTokens.spaceMovement))
        }

        item {
            HeadlineRow(stats)
            Spacer(Modifier.height(FolioTokens.spaceMovement))
        }

        item {
            WeekChart(stats.week)
            Spacer(Modifier.height(FolioTokens.spaceMovement))
        }

        item {
            ActivityHeatmap(stats.heatmap, stats.heatmapBooks, stats.heatmapManga)
            Spacer(Modifier.height(FolioTokens.spaceMovement))
        }

        if (stats.currentlyReading.isNotEmpty()) {
            item {
                FinishPredictionsCard(
                    books = stats.currentlyReading.take(5),
                    onBookClick = onBookClick,
                )
                Spacer(Modifier.height(FolioTokens.spaceMovement))
            }
        }

        if (stats.topBooks.isNotEmpty()) {
            item {
                WhereYourTimeWentCard(
                    books = stats.topBooks.take(6),
                    everythingElseMinutes = everythingElseMinutes(stats.topBooks, stats.timeThisYearMs / 60_000),
                    booksOpened = stats.booksOpened,
                    librarySize = stats.librarySize,
                    onBookClick = onBookClick,
                )
                Spacer(Modifier.height(FolioTokens.spaceMovement))
            }
        }
        if (stats.genres.isNotEmpty()) {
            item {
                GenresCard(slices = stats.genres)
                Spacer(Modifier.height(FolioTokens.spaceMovement))
            }
        }

        item {
            PatternsCard(stats, mangaStats)
            Spacer(Modifier.height(FolioTokens.spaceMovement))
        }

        if (recentQuotes.isNotEmpty()) {
            item {
                FloatingQuotesCard(quotes = recentQuotes, onQuoteClick = onOpenQuote)
                Spacer(Modifier.height(FolioTokens.spaceMovement))
            }
        }

        if (mangaStats != null && mangaStats!!.hasData) {
            item {
                MangaStatsSection(mangaStats!!)
                Spacer(Modifier.height(FolioTokens.spaceMovement))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The overture — Stats' opening statement
// ---------------------------------------------------------------------------

/**
 * Stats' opening statement, and the screen's only raised composition.
 *
 * The old screen opened with a 140dp ring inside a card, so the first thing it
 * said was "here is a widget". This says something about the reader instead:
 * this week's reading time as the hero figure, the chronotype as a sentence,
 * and the daily-goal ring demoted to a compact companion on the trailing edge —
 * still informative, no longer the headline.
 *
 * Both progress forms coexist legitimately here because they measure different
 * spans (week vs today) and only one is a ring; §2.6 forbids duplicated *forms*
 * for the same quantity.
 */
@Composable
private fun StatsOverture(
    stats: StatisticsUiState,
    goalMinutes: Int,
    onGoalChanged: (Int) -> Unit,
    settingsRepository: SettingsRepository?,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val colors = FolioTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = FolioTokens.gutter, top = FolioTokens.space2)
            .folioRaised(shape = FolioShapes.heroBleed, accent = colors.accentProgress)
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.space3,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            )
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                FolioFigure(
                    value = formatWeekHours(stats.timeThisWeekMs),
                    label = "Reading this week",
                    accent = colors.accentProgress,
                    emphasis = FigureScale.Hero,
                )
                if (stats.chronotype.isNotBlank()) {
                    Spacer(Modifier.height(FolioTokens.space2))
                    Text(
                        text = stats.chronotype,
                        style = FolioTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (stats.peakWindow.isNotBlank()) {
                    Text(
                        text = "Mostly between ${stats.peakWindow}",
                        style = FolioTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(FolioTokens.space3))
            GoalDial(
                todayMinutes = stats.todayMinutes,
                goalMinutes = goalMinutes,
                onEdit = if (settingsRepository != null) {
                    { showEditDialog = true }
                } else null,
            )
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

/** The hero figure's compact form: "6h 20m" / "45m" for the week's reading. */
private fun formatWeekHours(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

/**
 * The daily goal, compact. 96dp instead of 140dp, no card of its own, the numeral
 * inside the ring rather than a label beside it — a companion to the hero figure,
 * not a rival. Tapping the ring edits the goal, so the whole dial is the target
 * instead of a 32dp pencil.
 */
@Composable
private fun GoalDial(
    todayMinutes: Long,
    goalMinutes: Int,
    onEdit: (() -> Unit)?,
) {
    val colors = FolioTheme.colors
    val fraction = if (goalMinutes > 0) (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f) else 0f
    // Kept as State (not delegated) and read in the draw phase below, so the 800ms sweep animates
    // without recomposing GoalDial every frame.
    val animatedFraction = animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "goalRing",
    )
    // §13.5: the ring sweeps in once on entry; live goal changes keep using the
    // fraction animation above.
    val ringEntry = rememberEntryState("dailyGoalRing")
    val met = goalMinutes > 0 && todayMinutes >= goalMinutes
    // Rule 14: forward motion is accentProgress; a met goal is a celebration and
    // switches to accentStreak.
    val accent = if (met) colors.accentStreak else colors.accentProgress
    // The ring's spoken form, built from the same figures the arc encodes, so a
    // screen reader hears the goal instead of an unlabelled Canvas.
    val goalDesc = if (goalMinutes > 0) {
        "Daily reading goal: ${todayMinutes.toInt()} of $goalMinutes minutes today" +
            if (met) ", goal met" else ""
    } else {
        "Daily reading goal: ${todayMinutes.toInt()} minutes today"
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = goalDesc }
                .drawWithCache {
                    // Arc metrics + the gradient brush are built once; only the swept angle reads
                    // the animated fraction/entry in the draw phase.
                    val strokeWidth = 7.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    val trackColor = accent.copy(alpha = 0.16f)
                    val brush = Brush.verticalGradient(
                        listOf(accent, accent.copy(alpha = FolioTokens.gradientMinAlpha))
                    )
                    onDrawBehind {
                        drawArc(
                            color = trackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke,
                        )
                        drawArc(
                            brush = brush,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedFraction.value * ringEntry.value,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke,
                        )
                    }
                }
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${todayMinutes.toInt()}",
                style = FolioTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = accent,
            )
            Text(
                text = "/ $goalMinutes min",
                style = FolioTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant,
            )
        }
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
                    color = rememberLegibleAccent(FolioTheme.colors.primary),
                )
                Spacer(Modifier.height(FolioTokens.space2))
                FolioSlider(
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
                        settingsRepository.mergeGlobalSettings { it.copy(dailyGoalMinutes = newGoal) }
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
