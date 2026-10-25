package com.folio.reader.ui.statistics

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.ReadingTrace
import com.folio.reader.ui.components.LocalReadingTraces
import com.folio.reader.ui.components.ReadingTraceLine
import com.folio.reader.manga.MangaStatistics
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioCallout
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.chartStagger
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Stats' sections, rebuilt as an editorial spread.
 *
 * The through-line: **not every statistic deserves a card.** Lists get hairline
 * rules, figures get type scale, and charts sit in the open field. The bound
 * allocation ledger and rounded fingerprint instrument earn glass of their own.
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
    Column(modifier = Modifier.fillMaxWidth()) {
        FolioSectionHead(title = "On pace to finish")
        Spacer(Modifier.height(FolioTokens.space3))
        books.forEachIndexed { index, book ->
            if (index > 0) FolioRule()
            val interaction = rememberFolioInteraction()
            Row(Modifier.fillMaxWidth().folioPressable(interaction, scaleTo = .994f)
                .clickable(interactionSource = interaction, indication = null) { onBookClick(book.id) }
                .padding(vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                FolioCoverPlate(book.coverPath, book.title, book.author, width = 38.dp,
                    shape = FolioShapes.plateSmall, small = true)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(book.title, style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.onSurface, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(book.progress * 100).toInt()}%",
                            style = FolioTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            color = FolioTheme.colors.accentProgress,
                            modifier = Modifier.padding(start = 10.dp))
                    }
                    // Same real sitting/annotation contours as Home, without a second click target.
                    val trace = (LocalReadingTraces.current[book.id] ?: ReadingTrace()).copy(progress = book.progress)
                    ReadingTraceLine(trace, Modifier.fillMaxWidth().height(28.dp))
                    book.finishEstimate?.let {
                        Text(it, style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Where your time went: the leaderboard and the composition question, merged.
 *
 * Ranked rows carry a proportional bar (window minutes over the peak), so the
 * section answers *how the reading time was distributed* — the question the
 * drill core used to ask with strata — using the leaderboard's own data. Rank
 * figures number the shelf, the peak row marks itself in accentStreak (Rule
 * 15), and the tail aggregates into one honest "Everything else" row instead
 * of pretending the top five is the whole library. A closing caption states
 * how much of the library the window actually touched.
 */
@Composable
internal fun WhereYourTimeWentCard(
    books: List<ReadingTimeEntry>,
    everythingElseMinutes: Long,
    booksOpened: Int,
    librarySize: Int,
    onBookClick: (String) -> Unit,
    onMangaClick: (String) -> Unit = {},
    mangaBackend: com.folio.reader.manga.MangaBackend? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FolioSectionHead(title = "Where your time went", eyebrow = "Last 365 days")
        Spacer(Modifier.height(FolioTokens.space3))
        val peak = (books.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
        // §13.5: each ranked bar grows from the leading edge on entry, in the same
        // top-down stagger the genre well uses, so the leaderboard reads as one
        // chart drawing itself. The "everything else" tail rides the last slot.
        val barCount = books.size + 1
        val barEntry = rememberEntryState(books)
        books.forEachIndexed { index, entry ->
            if (index > 0) FolioRule()
            val interaction = rememberFolioInteraction()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .folioPressable(interaction, scaleTo = 0.99f)
                    .clickable(interactionSource = interaction, indication = null) {
                        if (entry.isManga) onMangaClick(entry.id) else onBookClick(entry.id)
                    }
                    .padding(vertical = FolioTokens.space2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}",
                        style = FolioTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        color = FolioTheme.colors.textTertiary,
                        modifier = Modifier.width(26.dp),
                    )
                    if (entry.isManga && mangaBackend != null) {
                        com.folio.reader.ui.manga.MangaCoverPlate(mangaBackend, entry.sourceId ?: 0L,
                            entry.thumbnailUrl, entry.coverPath, width = FolioTokens.coverInline)
                    } else {
                    FolioCoverPlate(
                        coverPath = entry.coverPath,
                        title = entry.title,
                        author = entry.author,
                        width = FolioTokens.coverInline,
                        shape = FolioShapes.plateSmall,
                        elevation = FolioTokens.elevationPanel,
                        small = true,
                    )
                    }
                    Spacer(Modifier.width(FolioTokens.space3))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.title, style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.onSurface, maxLines = 3,
                            overflow = TextOverflow.Ellipsis)
                        if (entry.author.isNotBlank() || entry.isManga) Text(
                            if (entry.isManga) listOf("Manga", entry.author).filter { it.isNotBlank() }.joinToString(" · ") else entry.author,
                            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(shortMinutes(entry.minutes),
                            style = FolioTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            color = FolioTheme.colors.accentProgress)
                    }
                }
                Spacer(Modifier.height(FolioTokens.spaceHair))
                val (growth, _) = chartStagger(barEntry.value, index, barCount)
                TimeBar(progress = entry.minutes.toFloat() / peak, peak = entry.minutes >= peak, growth = growth)
            }
        }
        if (everythingElseMinutes > 0L) {
            FolioRule()
            Column(Modifier.padding(vertical = FolioTokens.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "More titles",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shortMinutes(everythingElseMinutes),
                        style = FolioTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FolioTokens.spaceHair))
                val (tailGrowth, _) = chartStagger(barEntry.value, books.size, barCount)
                TimeBar(progress = everythingElseMinutes.toFloat() / peak, subdued = true, growth = tailGrowth)
            }
        }
        if (booksOpened > 0 && librarySize > 0) {
            Text(
                text = "$booksOpened of $librarySize titles opened",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = FolioTokens.space1),
            )
        }
    }
}

/** One proportional bar under a ranked row — the genre-well idiom, one row tall. */
@Composable
private fun TimeBar(progress: Float, peak: Boolean = false, subdued: Boolean = false, growth: Float = 1f) {
    val hue = when {
        peak -> FolioTheme.colors.accentStreak
        subdued -> FolioTheme.colors.onSurfaceVariant
        else -> FolioTheme.colors.accentProgress
    }
    Box(
        Modifier
            .fillMaxWidth((progress.coerceIn(0.04f, 1f) * growth).coerceAtLeast(0.0001f))
            .height(FolioTokens.chartTrack)
            .background(
                Brush.horizontalGradient(
                    listOf(hue, hue.copy(alpha = FolioTokens.gradientMinAlpha))
                ),
                RoundedCornerShape(
                    topEnd = FolioTokens.chartBarRadiusTop,
                    bottomEnd = FolioTokens.chartBarRadiusTop,
                ),
            )
    )
}

/** The closing caption's copy, pure so its plurals are testable. */
internal fun whereYourTimeCaption(booksOpened: Int, librarySize: Int): String = when {
    booksOpened <= 0 -> ""
    booksOpened >= librarySize -> if (librarySize == 1) "1 of 1 book opened" else "all $librarySize books opened"
    else -> "$booksOpened of $librarySize books opened"
}

/**
 * The remainder the ranked rows do not show: total minutes minus the top rows',
 * floored at zero (a capped leaderboard can never exceed the total, but the
 * aggregation is pure and pinned by tests anyway).
 */
internal fun everythingElseMinutes(topBooks: List<TopBook>, totalMinutes: Long): Long =
    (totalMinutes - topBooks.sumOf { it.minutes }).coerceAtLeast(0L)

/**
 * Genre breakdown, as a **sunken well of stacked bars**. §12.6: one hue per row
 * from the theme's `chartSeries` role — never one hue at N alphas. The peak row
 * marks itself with label weight (Rule 15). Sinking it separates data from the
 * ruled lists above and below without adding another card rim; the bars grow from
 * the leading edge, so the well reads as a chart rather than a list of pills.
 */
@Composable
internal fun GenresCard(slices: List<TagSlice>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FolioSectionHead(title = "What you read")
        Spacer(Modifier.height(FolioTokens.space3))
        val hues = FolioTheme.colors.chartSeries
        val peak = (slices.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
        // §13.5: the bars grow from the leading edge on entry in a gentle
        // top-down stagger (chartStagger's growth term). rememberEntryState parks at
        // 1f under reduce-motion, so the bars render at full width immediately.
        val entry = rememberEntryState(slices)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            slices.forEachIndexed { index, slice ->
                val hue = hues[index % hues.size]
                val isPeak = slice.minutes >= peak
                val (growth, _) = chartStagger(entry.value, index, slices.size)
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
                        style = FolioTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
                val target = (slice.minutes.toFloat() / peak).coerceIn(0.04f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth((target * growth).coerceAtLeast(0.0001f))
                        .height(FolioTokens.chartTrack)
                        .background(
                            Brush.horizontalGradient(
                                listOf(hue, hue.copy(alpha = FolioTokens.gradientMinAlpha))
                            ),
                            RoundedCornerShape(
                                topEnd = FolioTokens.chartBarRadiusTop,
                                bottomEnd = FolioTokens.chartBarRadiusTop,
                            ),
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
internal fun FloatingQuotesCard(
    quotes: List<RecentQuote>,
    onQuoteClick: (RecentQuote) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FolioSectionHead(
            title = "Passages you kept",
            accent = FolioTheme.colors.accentAnnotation,
        )
        Spacer(Modifier.height(FolioTokens.space3))
        quotes.forEachIndexed { index, quote ->
            if (index > 0) Spacer(Modifier.height(FolioTokens.spaceBeat))
            Box(modifier = Modifier.fillMaxWidth().clickable { onQuoteClick(quote) }) {
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
}

/**
 * The manga half of Stats. Same language as the books half — an eyebrow, figures
 * on the page, the chart in a well — so the two halves read as one document with a
 * section break rather than two dashboards stapled together.
 */
@Composable
internal fun MangaStatsSection(
    stats: MangaStatistics,
    mostRead: List<com.folio.reader.manga.MangaTopEntry>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FolioSectionHead(title = "Manga", eyebrow = "Also tracked")
            Spacer(Modifier.height(FolioTokens.space3))
            Row(modifier = Modifier.fillMaxWidth()) {
                FolioFigure(
                    value = stats.readChapters.toString(),
                    label = "Chapters read",
                    // "series" is invariant in English and "downloaded" is an
                    // adjective — neither takes a plural s, so no pluralizer here.
                    caption = "${stats.completedCount} series completed",
                    accent = FolioTheme.colors.accentProgress,
                    emphasis = FigureScale.Quiet,
                    modifier = Modifier.weight(1f),
                )
                FolioFigure(
                    value = formatDuration(stats.totalReadMinutes * 60_000L),
                    label = "Reading time",
                    caption = "${stats.downloadedChapters} downloaded",
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

        if (mostRead.isNotEmpty()) {
            Spacer(Modifier.height(FolioTokens.spaceBeat))
            Column(modifier = Modifier.fillMaxWidth()) {
                FolioEyebrow("Most read · Last 365 days")
                Spacer(Modifier.height(FolioTokens.space1))
                mostRead.take(5).forEachIndexed { index, entry ->
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
                            style = FolioTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            color = FolioTheme.colors.textTertiary,
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
                            style = FolioTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = FolioTheme.colors.accentProgress,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The ledger: figures on the page, no tiles, ranked by how much they matter.
 *
 * **Positionally this is a composition, not a table.** It used to be a 2×2 grid —
 * two `weight(1f)` figures, a rule, two more `weight(1f)` figures — which is the
 * one arrangement guaranteed to read as a spreadsheet no matter how good the
 * typography inside the cells is: four equal cells on a regular lattice, so the
 * eye finds no entry point and ranks nothing.
 *
 * The four numbers were never equal in importance, so they no longer occupy equal
 * cells. The streak is the one figure worth a pause, so it *is* the composition's
 * subject: it sits alone on the leading side at `FigureScale.Standard`, roughly
 * 1.3× the width of everything else. Active days, the year's time and the finished
 * count are supporting context, so they stack in a narrower trailing column at
 * `Quiet`, ruled apart from each other.
 *
 * That gives the block the two properties the grid version could not have: a
 * **dominant** (one big thing, not four medium things) and an **asymmetric split**
 * (≈57/43, not 50/50). The 36sp-against-24sp gap is a hierarchy the eye can read
 * at a glance, where four figures at similar sizes read as inert — but it stays
 * one step under the overture's `Hero`, so this block supports the screen's
 * opening statement instead of arguing with it.
 *
 * The session count still lives here nowhere: sessions are an implementation
 * detail, not a reading habit.
 */
@Composable
internal fun HeadlineRow(stats: StatisticsUiState) {
    val colors = FolioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            FolioFigure(stats.streakDays.toString(),
                unit = if (stats.streakDays == 1) "day" else "days", label = "Streak",
                caption = "best ${stats.longestStreakDays}", accent = colors.accentStreak,
                emphasis = FigureScale.Standard, modifier = Modifier.weight(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                FolioFigure("${stats.activeDaysThisWeek}", unit = "of 7", label = "Active days",
                    emphasis = FigureScale.Quiet, accent = colors.accentProgress)
                Row(Modifier.fillMaxWidth().height(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.week.takeLast(7).forEach { day ->
                        Box(Modifier.weight(1f).height(7.dp).background(
                            if (day.minutes > 0L) colors.accentProgress.copy(alpha = .75f)
                            else colors.outline.copy(alpha = .25f), RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
        FolioRule()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            FolioFigure(formatHours(stats.timeThisYearMs), label = "This year",
                caption = "${formatCount(stats.wordsReadThisYear)} words", emphasis = FigureScale.Quiet,
                modifier = Modifier.weight(1f))
            FolioFigure(stats.booksFinished.toString(), label = "Finished", caption = "books completed",
                emphasis = FigureScale.Quiet, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Reading patterns as a narrative, not a settings list. The chronotype is a
 * headline, the peak window is its sentence, and beneath them a 24-hour band
 * shows *when* the reading happens — one thin bar per hour, the peak hour in
 * accentStreak — with the supporting numbers as ruled rows in a sunken well.
 */
@Composable
internal fun PatternsCard(stats: StatisticsUiState, mangaStats: MangaStatistics?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
            // The one line here that compares rather than reports. It is absent far
            // more often than it is present — `trendSentence` is null unless the
            // reader's recent pace has genuinely moved against their own baseline,
            // so the card never carries a sentence that says "you are normal".
            stats.trendSentence?.let { sentence ->
                Spacer(Modifier.height(FolioTokens.space2))
                Text(
                    text = sentence,
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.accentStreak
                )
            }
        }
        Spacer(Modifier.height(FolioTokens.space3))
        // The same 24 buckets re-projected into a polar clock — the reader's day
        // as a silhouette rather than a bar chart. Shares HourBand's data exactly.
        ReadingFingerprint(
            hourTotals = stats.hourTotals,
            mostReadHour = stats.mostReadHour,
            chronotype = stats.chronotype,
        )
        Spacer(Modifier.height(FolioTokens.space3))
        val facts = buildList {
            add("Average sitting" to shortMinutes(stats.averageSessionMinutes.toLong()))
            add("Longest sitting" to shortMinutes(stats.longestSessionMinutes))
            if (mangaStats != null && mangaStats.biggestDayChapters > 0)
                add("Biggest day" to plural(mangaStats.biggestDayChapters, "chapter"))
            add("Current streak" to plural(stats.streakDays, "day"))
            add("Most active hour" to stats.mostReadHour.ifBlank { "—" })
            add("Favourite day" to stats.mostReadDay.ifBlank { "—" })
            add("Average speed" to "${stats.averageSpeedWpm.toInt()} wpm")
            add("Longest streak" to plural(stats.longestStreakDays, "day"))
            add("Active this week" to "${stats.activeDaysThisWeek} of 7")
            add("Synced from" to plural(stats.sourceDevices, "device"))
        }
        facts.chunked(2).forEach { pair ->
            FolioRule()
            Row(Modifier.fillMaxWidth().padding(vertical = FolioTokens.space2),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant)
                        Text(value, style = FolioTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            color = FolioTheme.colors.onSurface)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The day's shape: 24 thin bars in a sunken well, edge-labelled 12a–11p. The
 * peak hour carries accentStreak (Rule 15's peak mark) and a "your peak"
 * caption; every other bar is accentProgress at the §12.6 gradient floor.
 */
@Composable
private fun HourBand(hourTotals: List<Long>, mostReadHour: String) {
    val colors = FolioTheme.colors
    val peak = hourTotals.maxOrNull() ?: 0L
    val peakIndex = hourTotals.indexOfFirst { it == peak && peak > 0L }
    // §13.5: the band draws on once, bars rising from the base in a soft
    // left-to-right sweep. Read in the draw phase so the 24 bars never recompose
    // per frame; reduce-motion parks the state at 1f (rendered complete).
    val entry = rememberEntryState(hourTotals)
    // Spoken form of the 24-hour band, built from the same buckets the bars draw,
    // so the Canvas is not silent to a screen reader (Rule 17).
    val activeHours = hourTotals.count { it > 0L }
    val bandDesc = buildString {
        append("Reading by hour of day.")
        if (mostReadHour.isNotBlank()) append(" Busiest hour $mostReadHour.")
        append(" Active in $activeHours of 24 hours.")
    }
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = bandDesc }
                .drawWithCache {
                    // Bar geometry follows the animated sweep (the wave fill), so it stays in
                    // onDrawBehind; the two gradient brushes and the column metrics don't depend on
                    // the sweep, so build them once per size/theme change instead of 24 brushes/frame.
                    val n = 24
                    val gap = 1.5.dp.toPx()
                    val barWidth = (size.width - gap * (n - 1)) / n
                    val hairlinePx = 1.dp.toPx()
                    val silentColor = colors.onSurfaceVariant.copy(alpha = 0.14f)
                    val progressBrush = Brush.verticalGradient(
                        listOf(colors.accentProgress, colors.accentProgress.copy(alpha = FolioTokens.gradientMinAlpha))
                    )
                    val streakBrush = Brush.verticalGradient(
                        listOf(colors.accentStreak, colors.accentStreak.copy(alpha = FolioTokens.gradientMinAlpha))
                    )
                    onDrawBehind {
                        val sweep = entry.value
                        hourTotals.forEachIndexed { hour, minutes ->
                            // Each bar starts a touch after the one to its left, so the band
                            // fills like a wave rather than every column snapping up at once.
                            val grow = ((sweep - hour.toFloat() / n * 0.4f) / 0.6f).coerceIn(0f, 1f)
                            val h = if (peak > 0L) (minutes.toFloat() / peak) * size.height * grow else 0f
                            if (h <= 0f) {
                                // A silent hour still shows its slot: a hairline at the base.
                                drawRect(
                                    color = silentColor,
                                    topLeft = Offset(hour * (barWidth + gap), size.height - hairlinePx),
                                    size = Size(barWidth, hairlinePx),
                                )
                            } else {
                                val brush = if (hour == peakIndex) streakBrush else progressBrush
                                drawRect(
                                    brush = brush,
                                    topLeft = Offset(hour * (barWidth + gap), size.height - h),
                                    size = Size(barWidth, h),
                                )
                            }
                        }
                    }
                }
        )
        Spacer(Modifier.height(FolioTokens.space1))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "12a",
                style = FolioTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "12p",
                style = FolioTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "11p",
                style = FolioTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (mostReadHour.isNotBlank()) {
            Text(
                text = "▲ your peak · $mostReadHour",
                style = FolioTheme.typography.labelSmall,
                color = colors.accentStreak,
                modifier = Modifier.padding(top = FolioTokens.space1),
            )
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
                style = FolioTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
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

/**
 * "3 sessions" / "1 session". Only for nouns that pluralize with a plain s —
 * invariant words ("series") and adjectival captions ("downloaded") must be
 * written out directly or they render as "serieses" / "downloadeds".
 */
private fun plural(count: Int, word: String): String =
    "$count $word" + if (count == 1) "" else "s"
