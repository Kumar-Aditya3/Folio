package com.folio.reader.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.model.Book
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioHeroCard
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.statistics.ReadingInProgress
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled

/**
 * §12.4 Home surface: hero first, then never two adjacent surfaces of the same
 * weight — goal strip (quiet), continue-reading carousel, because-you-finished,
 * this-week sparkline (quiet). The sparkline is Home's own (Rule 16); the only
 * ui/statistics imports are the shared data types.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenBook: (String) -> Unit,
    onOpenBookDetail: (String) -> Unit,
    onImportClick: () -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenExclusions: () -> Unit = {},
    mangaBackend: MangaBackend? = null,
    onOpenMangaDetail: (String) -> Unit = {},
    onOpenMangaReader: (String, String) -> Unit = { _, _ -> },
    onOpenSourceWeb: (String) -> Unit = {},
    onOpenDiscover: (MangaDiscoverItem) -> Unit = {},
    onHeroCollapse: (Float, String?, Color?) -> Unit = { _, _, _ -> }
) {
    when {
        !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingPlaceholder()
        }
        !state.hasBooks && !state.hasManga ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.MenuBook,
                    headline = "Your library is empty — import an EPUB to start.",
                    action = { Button(onClick = onImportClick) { Text("Import a book") } }
                )
            }
        else -> {
            val listState = rememberLazyListState()
            // §13.9 hero collapse: tracked 1:1 off the scroll offset — no spring,
            // no settle — over the first 160dp; reduce-motion keeps the hero full
            // size and simply scrolling away.
            val motion = rememberMotionEnabled()
            val density = LocalDensity.current
            val collapseRange = with(density) { 160.dp.toPx() }
            val collapse = remember(motion, collapseRange) {
                derivedStateOf {
                    when {
                        !motion -> 0f
                        listState.firstVisibleItemIndex > 0 -> 1f
                        else -> (listState.firstVisibleItemScrollOffset / collapseRange).coerceIn(0f, 1f)
                    }
                }
            }
            val fullyCollapsed = remember { derivedStateOf { collapse.value >= 1f } }
            // The hero tint is computed here so the host top bar can bleed the
            // same colour upward (§13.9); the hero itself re-derives it cheaply
            // from the accent cache.
            val heroTint = if (state.coverTint && state.hero != null) {
                rememberCoverAccent(state.hero.coverPath, FolioTheme.colors.accentProgress)
            } else null
            val fallbackTint = FolioTheme.colors.accentProgress
            LaunchedEffect(fullyCollapsed.value, state.hero?.id, state.coverTint) {
                onHeroCollapse(
                    if (fullyCollapsed.value) 1f else 0f,
                    state.hero?.title,
                    heroTint ?: fallbackTint
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space4),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space4)
            ) {
                item { HeroCard(state, collapse, heroTint, onOpenBook, onOpenLibrary) }
                item { GoalStrip(state, onOpenStats, onOpenExclusions) }
                if (state.continueReading.isNotEmpty()) {
                    item { ContinueReadingCard(state.continueReading, onOpenBook) }
                }
                // §11.4: manga Continue reading — separate card, never merged into the
                // books carousel. Primary tap opens the reader; the overflow item is
                // the only path to the source's web page.
                if (state.mangaContinue.isNotEmpty() && mangaBackend != null) {
                    item {
                        MangaContinueCard(
                            items = state.mangaContinue,
                            backend = mangaBackend,
                            onOpenReader = onOpenMangaReader,
                            onOpenDetail = onOpenMangaDetail,
                            onOpenSourceWeb = onOpenSourceWeb
                        )
                    }
                }
                // §11.4: manga "New chapters" sits after the books carousel; hidden
                // when the total is zero. Tapping a row opens the manga detail.
                if (state.newChapters.isNotEmpty() && mangaBackend != null) {
                    item { NewChaptersCard(state.newChapters, mangaBackend, onOpenMangaDetail) }
                }
                // §11.4: Discover — LATEST from the most recently read manga's source.
                if (state.discover.isNotEmpty() && mangaBackend != null) {
                    item { DiscoverCard(state.discover, mangaBackend, onOpenDiscover) }
                }
                val finishedTitle = state.becauseFinishedTitle
                if (finishedTitle != null && state.candidates.size >= 2) {
                    item { BecauseYouFinishedCard(finishedTitle, state.candidates, onOpenBookDetail) }
                }
                item { ThisWeekCard(state) }
            }
        }
    }
}

/**
 * §12.4 hero — "Reading now". Thin progress bar under the cover (one form per
 * view, §2.6), one filled "Continue" button. Null hero is the §12.9 designed
 * empty: never fall back to an excluded book.
 *
 * §13.3: the hero gradient is tinted by the current cover's dominant colour
 * (contrast-guarded, toggle in Themes) instead of a flat accentProgress. §13.9:
 * over the first 160dp of scroll the cover scales 1.0→0.55 toward the top bar
 * and the gradient fades 0.30→0.12, tracked 1:1 with the finger.
 */
@Composable
private fun HeroCard(
    state: HomeUiState,
    collapse: State<Float>,
    heroTint: Color?,
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val hero = state.hero
    if (hero == null) {
        FolioSectionCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)
            ) {
                Text(
                    "Nothing in progress — pick something from your library.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenLibrary) { Text("Open library") }
            }
        }
        return
    }
    val collapseFraction = collapse.value
    val gradientAlpha = androidx.compose.ui.util.lerp(0.30f, 0.12f, collapseFraction)
    FolioHeroCard(
        modifier = Modifier.clickable { onOpenBook(hero.id) },
        accent = heroTint,
        mesh = true,
        gradientAlpha = gradientAlpha
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Box(
                    modifier = Modifier
                        .width(FolioTokens.heroCoverMin)
                        .height(180.dp)
                        .graphicsLayer {
                            val scale = androidx.compose.ui.util.lerp(1f, 0.55f, collapse.value)
                            scaleX = scale
                            scaleY = scale
                            // Shrink toward the top bar; never fully hidden — the
                            // collapsed thumbnail keeps the anchor.
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    BookCover(coverPath = hero.coverPath, title = hero.title, author = hero.author)
                }
                if (hero.progress > 0f) {
                    Spacer(Modifier.height(FolioTokens.space1))
                    Box(
                        modifier = Modifier
                            .width(FolioTokens.heroCoverMin)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(FolioTheme.colors.accentProgress.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(hero.progress)
                                .height(4.dp)
                                .background(FolioTheme.colors.accentProgress, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            Spacer(Modifier.width(FolioTokens.space3))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Text(
                    hero.title,
                    style = FolioTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (hero.author.isNotBlank()) {
                    Text(
                        hero.author,
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hero.finishEstimate != null) {
                    Text(
                        hero.finishEstimate,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(onClick = { onOpenBook(hero.id) }) { Text("Continue") }
            }
        }
    }
}

/**
 * §12.4 goal strip: 32dp ring in accentProgress, streak in accentStreak, met-goal
 * fill at 12% with the numeral semibold. Tapping opens the Stats tab. Rule 8:
 * when any exclusion is active, the review line sits under the strip.
 */
@Composable
private fun GoalStrip(state: HomeUiState, onOpenStats: () -> Unit, onOpenExclusions: () -> Unit) {
    val colors = FolioTheme.colors
    val met = state.goalMinutes > 0 && state.todayMinutes >= state.goalMinutes
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FolioTokens.radiusChip))
                .background(if (met) colors.accentStreak.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(onClick = onOpenStats)
                .padding(horizontal = FolioTokens.space2, vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProgressRing(
                progress = if (state.goalMinutes > 0) {
                    (state.todayMinutes.toFloat() / state.goalMinutes).coerceIn(0f, 1f)
                } else 0f,
                modifier = Modifier.size(32.dp),
                strokeWidth = 3f,
                color = colors.accentProgress
            )
            Spacer(Modifier.width(FolioTokens.space2))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = if (met) FontWeight.SemiBold else FontWeight.Normal, color = colors.onSurface)) {
                        append("${state.todayMinutes}")
                    }
                    withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = colors.onSurfaceVariant)) {
                        append(" / ${state.goalMinutes} min")
                    }
                    withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = colors.accentStreak)) {
                        append(if (state.streakDays > 0) " · ${state.streakDays}-day streak" else " · No streak yet")
                    }
                },
                style = FolioTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.exclusionsActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenExclusions)
                    .padding(horizontal = FolioTokens.space2, vertical = FolioTokens.space1),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Some titles are excluded — review",
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** §12.4 carousel: covers at listCoverMin with rings, hero excluded upstream. */
@Composable
private fun ContinueReadingCard(books: List<ReadingInProgress>, onOpenBook: (String) -> Unit) {
    FolioSectionCard(title = "Continue reading") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            items(books.size) { index ->
                val book = books[index]
                Column(
                    modifier = Modifier
                        .width(FolioTokens.listCoverMin)
                        .clickable { onOpenBook(book.id) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(FolioTokens.listCoverMin)
                            .height(96.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        BookCover(coverPath = book.coverPath, title = book.title, author = book.author, small = true)
                    }
                    Spacer(Modifier.height(FolioTokens.space1))
                    ProgressRing(
                        progress = book.progress,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3f
                    )
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        book.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** §12.4: card title tinted accentDiscovery; covers at listCoverMin. */
@Composable
private fun BecauseYouFinishedCard(
    finishedTitle: String,
    candidates: List<Book>,
    onOpenBookDetail: (String) -> Unit
) {
    FolioSectionCard(
        title = "Because you finished $finishedTitle",
        accent = FolioTheme.colors.accentDiscovery
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            candidates.forEach { book ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenBookDetail(book.id) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        BookCover(
                            coverPath = book.coverPath,
                            title = book.title,
                            author = book.displayAuthor,
                            small = true
                        )
                    }
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        book.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** §12.4: Home's own sparkline (Rule 16) — 32dp line with a dot on today. */
@Composable
private fun ThisWeekCard(state: HomeUiState) {
    FolioSectionCard(title = "This week") {
        WeekSparkline(
            week = state.week,
            modifier = Modifier.fillMaxWidth().height(FolioTokens.sparkHeight)
        )
        Spacer(Modifier.height(FolioTokens.space1))
        Text(
            "${formatMinutes(state.week.sumOf { it.minutes })} this week · " +
                "${state.startedThisWeek} started · ${state.finishedThisWeek} finished",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant
        )
    }
}

@Composable
private fun WeekSparkline(week: List<StatDay>, modifier: Modifier = Modifier) {
    val color = FolioTheme.colors.accentProgress
    // §13.5: the line trims in once per window (keyed on dates, not minutes),
    // drawn in the canvas phase so nothing recomposes per frame.
    val entry = rememberEntryState(week.map { it.date })
    Canvas(modifier = modifier) {
        if (week.size < 2) return@Canvas
        val progress = entry.value
        val max = week.maxOf { it.minutes }.coerceAtLeast(1L)
        val stepX = size.width / (week.size - 1)
        val points = week.mapIndexed { index, day ->
            Offset(index * stepX, size.height * (1f - day.minutes.toFloat() / max))
        }
        val areaPath = Path().apply {
            moveTo(0f, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(size.width, size.height)
            close()
        }
        val linePath = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        clipRect(right = size.width * progress) {
            drawPath(
                areaPath,
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent))
            )
            drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        // The dot marks today — the last day of the trailing week.
        if (progress >= 1f) points.last().let { drawCircle(color, radius = 3.dp.toPx(), center = it) }
    }
}

private fun formatMinutes(total: Long): String =
    if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
