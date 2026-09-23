package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Weekly bar charts
// ---------------------------------------------------------------------------

/**
 * One day-bar in the shared chart idiom: §12.5/Rule 15 fill — a vertical
 * gradient from `accentProgress` down to it at [FolioTokens.gradientMinAlpha] —
 * scaled against [peak], stubbed track when the day is empty. The bar at the
 * window's max carries the Rule 15 peak marker: a brighter `accentStreak` cap.
 * Used by the book-detail and manga-detail sparklines.
 *
 * §13.5: [growth] scales the bar from the empty track up to full height, and
 * [capReveal] fades the peak cap in only after its bar has finished growing —
 * both default to 1f, the pre-§13 static rendering.
 */
@Composable
internal fun ChartBar(
    value: Float,
    peak: Float,
    modifier: Modifier = Modifier,
    growth: Float = 1f,
    capReveal: Float = 1f,
) {
    val fraction = if (peak > 0f) (value / peak).coerceIn(0f, 1f) else 0f
    val grown = if (value > 0f) {
        androidx.compose.ui.unit.lerp(FolioTokens.chartTrack, FolioTokens.chartBarBase + FolioTokens.chartBarSpan * fraction, growth)
    } else FolioTokens.chartTrack
    val accent = FolioTheme.colors.accentProgress
    val barShape = RoundedCornerShape(
        topStart = FolioTokens.chartBarRadiusTop, topEnd = FolioTokens.chartBarRadiusTop,
        bottomStart = FolioTokens.chartBarRadiusBottom, bottomEnd = FolioTokens.chartBarRadiusBottom,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(grown)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (value > 0f) Brush.verticalGradient(
                        listOf(accent, accent.copy(alpha = FolioTokens.gradientMinAlpha))
                    ) else SolidColor(FolioTheme.colors.outline.copy(alpha = 0.35f)),
                    barShape,
                )
        )
        if (value > 0f && value >= peak && capReveal > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(FolioTokens.chartPeakCap)
                    .background(
                        FolioTheme.colors.accentStreak.copy(alpha = capReveal),
                        RoundedCornerShape(
                            topStart = FolioTokens.chartBarRadiusTop, topEnd = FolioTokens.chartBarRadiusTop,
                            bottomStart = 1.dp, bottomEnd = 1.dp,
                        ),
                    )
            )
        }
    }
}

/**
 * Manga chapters per day over the last 7 days. Same well treatment as the books
 * [WeekChart] — data sits *in* the page — and the same smooth curve, with the
 * chapter count riding above its day's column.
 */
@Composable
internal fun MangaWeekChart(chaptersPerDay: List<Int>, labels: List<String>) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioEyebrow("Manga — last 7 days")
        }
        Spacer(Modifier.height(FolioTokens.space2))
        SmoothWeekCurve(
            values = chaptersPerDay.map { it.toFloat() },
            dayLabels = List(chaptersPerDay.size) { index ->
                labels.getOrNull(index)?.take(1) ?: ""
            },
            plotHeight = FolioTokens.chartHeight,
            valueCaptions = chaptersPerDay.map { chapters ->
                if (chapters > 0) chapters.toString() else ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        )
    }
}

// ---------------------------------------------------------------------------
// Activity heatmap
// ---------------------------------------------------------------------------

private enum class HeatmapMode { ALL, BOOKS, MANGA }

@Composable
internal fun ActivityHeatmap(
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

    // §12.5: the year heatmap is Stats' one raised surface (Rule 13). It earns it
    // by being the only genuinely *visual* artefact on the screen — 365 cells of
    // the reader's own year. The asymmetric hero silhouette and the accent-lit rim
    // separate it from every ruled list around it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter)
            .folioRaised(shape = FolioShapes.hero, accent = FolioTheme.colors.accentProgress)
            .padding(FolioTokens.space3)
    ) {
        Column {
            FolioSectionHead(title = "Your year", eyebrow = "Activity")

            Spacer(Modifier.height(FolioTokens.space3))

            // ── Segmented toggle ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolioChip(selected = mode == HeatmapMode.ALL, onClick = { mode = HeatmapMode.ALL }, label = "All")
                FolioChip(selected = mode == HeatmapMode.BOOKS, onClick = { mode = HeatmapMode.BOOKS }, label = "Books")
                FolioChip(selected = mode == HeatmapMode.MANGA, onClick = { mode = HeatmapMode.MANGA }, label = "Manga")
            }

            Spacer(Modifier.height(FolioTokens.space3))

            if (days.isEmpty()) {
                Text(
                    "No reading recorded yet.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                return@Column
            }
            val peak = remember(days) { (days.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L) }
            val cellAccent = FolioTheme.colors.accentProgress
            // Up to 365 cells → 52 week sublists; memoize so a mode toggle / entry animation / parent
            // scroll doesn't re-scan and re-chunk the whole year every recomposition.
            val weeks = remember(days) { days.chunked(7) }
            // A single spoken summary stands in for 365 individually-tappable
            // cells. 13dp is far below a usable touch target, so the per-cell tap
            // is gone (Rule 17) and the year reads through this description on the
            // grid instead — built from the same days the cells are tinted from.
            val activeDays = days.count { it.minutes > 0L }
            val totalMinutes = days.sumOf { it.minutes }
            val busiest = days.maxByOrNull { it.minutes }?.takeIf { it.minutes > 0L }
            val heatmapSummary = buildString {
                append("Reading activity, ")
                append("${days.first().date.shortLabel()} to ${days.last().date.shortLabel()}. ")
                append("$activeDays active ${if (activeDays == 1) "day" else "days"}, ")
                append("${shortMinutes(totalMinutes)} total.")
                if (busiest != null) append(" Busiest ${heatmapDayDetail(busiest)}.")
            }
            // GitHub orientation: one column per week, most recent on the right;
            // even 4dp gutters on both axes so cells never chain into a wall. At a
            // full year the grid overflows a phone, so it scrolls and lands on the
            // most recent week.
            val gridScroll = rememberScrollState()
            LaunchedEffect(weeks.size) {
                snapshotFlow { gridScroll.maxValue }.first { it > 0 }
                gridScroll.animateScrollTo(gridScroll.maxValue)
            }
            val cell = 13.dp
            val colGap = 4.dp
            Box(Modifier.fillMaxWidth().horizontalScroll(gridScroll)) {
                // §13.5: the whole grid grows from zero on first composition — one
                // shared timeline, read in the layer phase so a year of cells never
                // recomposes per frame.
                val gridEntry = rememberEntryState(mode)
                Column {
                    // ── Month scale ───────────────────────────────────────────
                    // A label at each month boundary, aligned to that month's first
                    // week column. Labels overflow their 13dp slot to the right
                    // (unbounded) so "September" is legible; the slots after it are
                    // blank until the next month, so nothing collides.
                    Row(horizontalArrangement = Arrangement.spacedBy(colGap)) {
                        var lastMonth = -1
                        weeks.forEach { week ->
                            val month = week.firstOrNull()?.date?.monthNumber ?: -1
                            val show = month > 0 && month != lastMonth
                            lastMonth = month
                            Box(Modifier.width(cell)) {
                                if (show) {
                                    Text(
                                        text = monthAbbrev(month),
                                        style = FolioTheme.typography.labelSmall,
                                        color = FolioTheme.colors.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.wrapContentWidth(
                                            align = Alignment.Start,
                                            unbounded = true,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(colGap))
                    Column(
                        modifier = Modifier
                            .graphicsLayer {
                                val p = gridEntry.value
                                scaleX = p
                                scaleY = p
                            }
                            .semantics(mergeDescendants = true) {
                                contentDescription = heatmapSummary
                            },
                        verticalArrangement = Arrangement.spacedBy(colGap)
                    ) {
                        (0..6).forEach { index ->
                            Row(horizontalArrangement = Arrangement.spacedBy(colGap)) {
                                weeks.forEach { week ->
                                    val day = week.getOrNull(index)
                                    HeatmapDayCell(
                                        day = day,
                                        peak = peak,
                                        size = cell,
                                        accent = cellAccent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = FolioTokens.space2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The range this grid spans. Per-cell selection is gone — the
                // cells are far too small to tap — so the spoken grid summary
                // carries the day-level detail for assistive tech instead.
                Text(
                    text = "${days.first().date.shortLabel()} – ${days.last().date.shortLabel()}",
                    style = FolioTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Less", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
                    (1..4).forEach { HeatmapCell(intensity = it, size = 9.dp, accent = cellAccent) }
                    Text("More", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reading fingerprint
// ---------------------------------------------------------------------------

/**
 * The reader's own daily rhythm as a 24-spoke radial clock: midnight at the top,
 * clockwise through noon. Each spoke's length is that start-hour's share of the
 * reader's busiest hour, so the silhouette *is* the reader's day — a night owl's
 * fingerprint leans to the bottom, an early bird's to the top-right. Only real
 * metrics feed it: [hourTotals] (24 buckets) for the spokes, [mostReadHour] for
 * the accent-lit peak spoke, [chronotype] for the centre label. Nothing here is
 * invented to fill the graphic.
 *
 * §13.5: the spokes sweep out from the hub on first composition on the one shared
 * entry timeline, read in the draw phase so the 24 spokes never recompose per
 * frame. Under reduce-motion [rememberEntryState] resolves to its final value and
 * the fingerprint renders complete.
 */
@Composable
internal fun ReadingFingerprint(
    hourTotals: List<Long>,
    mostReadHour: String,
    chronotype: String,
) {
    val colors = FolioTheme.colors
    val peak = hourTotals.maxOrNull() ?: 0L
    val peakIndex = hourTotals.indexOfFirst { it == peak && peak > 0L }
    val ring = colors.onSurfaceVariant.copy(alpha = 0.16f)
    val spoke = colors.accentProgress
    val peakSpoke = colors.accentStreak
    val entry = rememberEntryState(hourTotals)
    // Spoken form of the radial clock, built from the same buckets it draws, so
    // the fingerprint is not a silent Canvas to a screen reader (Rule 17).
    val activeHours = hourTotals.count { it > 0L }
    val fingerprintDesc = buildString {
        append("Reading fingerprint, a 24-hour radial clock.")
        if (chronotype.isNotBlank()) append(" $chronotype.")
        if (mostReadHour.isNotBlank()) append(" Busiest hour $mostReadHour.")
        append(" Active in $activeHours of 24 hours.")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioSunken(FolioShapes.edgeStart)
            .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
    ) {
        FolioEyebrow("Reading fingerprint")
        Spacer(Modifier.height(FolioTokens.space2))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = fingerprintDesc }
                    .drawWithCache {
                        // Geometry that doesn't depend on the animated `grow` (center, radii, the
                        // 24 unit vectors and their hub start/stub points, the guide-ring stroke) is
                        // built once per size change; only the spoke length + its moving gradient
                        // read `grow` in onDrawBehind. Was 24 cos/sin + Offset allocs every frame.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val outer = min(size.width, size.height) / 2f
                        val hub = outer * 0.30f
                        val span = outer * 0.94f - hub
                        val n = 24
                        val strokePx = (2f * PI.toFloat() * (hub + span) / n) * 0.5f
                        val ringStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        val stubPx = 2.dp.toPx()
                        val ux = FloatArray(n)
                        val uy = FloatArray(n)
                        val fracs = FloatArray(n)
                        val starts = arrayOfNulls<Offset>(n)
                        val stubs = arrayOfNulls<Offset>(n)
                        for (hour in 0 until n) {
                            // Midnight at the top (−90°), clockwise like a clock face.
                            val angle = (-PI / 2.0) + (hour * 2.0 * PI / n)
                            val vx = cos(angle).toFloat()
                            val vy = sin(angle).toFloat()
                            ux[hour] = vx
                            uy[hour] = vy
                            val minutes = hourTotals.getOrElse(hour) { 0L }
                            fracs[hour] = if (peak > 0L) minutes.toFloat() / peak else 0f
                            starts[hour] = Offset(center.x + vx * hub, center.y + vy * hub)
                            stubs[hour] = Offset(center.x + vx * (hub + stubPx), center.y + vy * (hub + stubPx))
                        }
                        onDrawBehind {
                            val grow = entry.value
                            // Two faint guide rings: the hub edge and the full-day maximum, so
                            // an empty rhythm still reads as a clock rather than a blank disc.
                            drawCircle(color = ring, radius = hub, center = center, style = ringStroke)
                            drawCircle(color = ring, radius = hub + span, center = center, style = ringStroke)
                            for (hour in 0 until n) {
                                val start = starts[hour]!!
                                val frac = fracs[hour]
                                if (frac <= 0f) {
                                    // A silent hour keeps its tick: a stub at the hub.
                                    drawLine(color = ring, start = start, end = stubs[hour]!!, strokeWidth = strokePx, cap = StrokeCap.Round)
                                } else {
                                    val len = span * frac * grow
                                    val end = Offset(center.x + ux[hour] * (hub + len), center.y + uy[hour] * (hub + len))
                                    val isPeak = hour == peakIndex
                                    val hue = if (isPeak) peakSpoke else spoke
                                    drawLine(
                                        brush = Brush.linearGradient(
                                            colors = listOf(hue.copy(alpha = FolioTokens.gradientMinAlpha), hue),
                                            start = start,
                                            end = end,
                                        ),
                                        start = start,
                                        end = end,
                                        strokeWidth = if (isPeak) strokePx * 1.15f else strokePx,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
            )
            if (chronotype.isNotBlank()) {
                Text(
                    text = chronotype,
                    style = FolioTheme.typography.labelMedium,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.42f),
                )
            }
        }
        if (mostReadHour.isNotBlank()) {
            Text(
                text = "\u25B2 peak \u00B7 $mostReadHour",
                style = FolioTheme.typography.labelSmall,
                color = colors.accentStreak,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = FolioTokens.space1),
            )
        }
    }
}

/** Log-ish banding: a 2-hour day should not max out the scale against a 30-second one. */
internal fun intensityFor(minutes: Long, peak: Long): Int {
    val ratio = minutes.toFloat() / peak
    return when {
        ratio > 0.66f -> 4
        ratio > 0.33f -> 3
        ratio > 0.12f -> 2
        else -> 1
    }
}

private fun LocalDate.shortLabel(): String = "$dayOfMonth.$monthNumber"

/**
 * One heatmap day: transparent when the slot has no date (the leading/trailing
 * days of the first/last calendar weeks), the faint empty tint on a day with no
 * reading, else the accent stepped by [intensityFor]. The non-empty ladder is
 * floored at 0.4 alpha so the lowest activity level stays clearly distinct from a
 * no-reading cell rather than fading into it. Cells are purely visual — the year's
 * day-level detail is spoken once through the grid's summary description (Rule 17),
 * so there is no impossible per-13dp-cell tap target.
 */
private val HEATMAP_CELL_SHAPE = RoundedCornerShape(3.dp)
private val HEATMAP_LADDER = listOf(0.4f, 0.6f, 0.8f, 1f)

@Composable
private fun HeatmapDayCell(
    day: StatDay?,
    peak: Long,
    size: Dp,
    accent: Color,
) {
    val shape = HEATMAP_CELL_SHAPE
    val ladder = HEATMAP_LADDER
    val hasReading = day != null && day.minutes > 0L
    val fill = when {
        day == null -> Color.Transparent
        !hasReading -> FolioTheme.colors.outline.copy(alpha = 0.14f)
        else -> accent.copy(alpha = ladder[(intensityFor(day.minutes, peak) - 1).coerceIn(0, 3)])
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(fill, shape)
    )
}

/** "Mon 3 Sep · 45m read" / "Mon 3 Sep · no reading" — the tapped-cell tooltip. */
internal fun heatmapDayDetail(day: StatDay): String {
    val d = day.date
    val weekday = dayAbbrev(d.dayOfWeek.ordinal)
    val month = monthAbbrev(d.monthNumber)
    val when_ = "$weekday ${d.dayOfMonth} $month"
    return if (day.minutes > 0L) "$when_ · ${shortMinutes(day.minutes)} read" else "$when_ · no reading"
}

/** [ordinal] is kotlinx DayOfWeek.ordinal: 0 = Monday … 6 = Sunday. */
private fun dayAbbrev(ordinal: Int): String =
    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").getOrElse(ordinal) { "" }

/** [monthNumber] is 1 = January … 12 = December. */
internal fun monthAbbrev(monthNumber: Int): String =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .getOrElse(monthNumber - 1) { "" }
