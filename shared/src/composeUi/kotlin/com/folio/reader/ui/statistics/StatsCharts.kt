package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
            val peak = (days.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
            val cellAccent = FolioTheme.colors.accentProgress
            val weeks = days.chunked(7)
            // The tapped day, if any. Keyed on `mode` so switching All/Books/Manga
            // clears a stale selection rather than pointing at a cell that moved.
            var selected by remember(mode) { mutableStateOf<StatDay?>(null) }
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
                        modifier = Modifier.graphicsLayer {
                            val p = gridEntry.value
                            scaleX = p
                            scaleY = p
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
                                        selected = day != null && day == selected,
                                        onClick = { selected = day },
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
                // The tooltip line: a tapped day states its date and minutes here,
                // in place of the range, so the detail never floats over the grid.
                Text(
                    text = selected?.let { heatmapDayDetail(it) }
                        ?: "${days.first().date.shortLabel()} – ${days.last().date.shortLabel()}",
                    style = FolioTheme.typography.bodySmall,
                    color = if (selected != null) FolioTheme.colors.onSurface
                        else FolioTheme.colors.onSurfaceVariant,
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outer = min(size.width, size.height) / 2f
                val hub = outer * 0.30f          // inner clear zone for the label
                val span = outer * 0.94f - hub    // spoke travel
                val grow = entry.value

                // Two faint guide rings: the hub edge and the full-day maximum, so
                // an empty rhythm still reads as a clock rather than a blank disc.
                drawCircle(color = ring, radius = hub, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                drawCircle(color = ring, radius = hub + span, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))

                val n = 24
                val strokePx = (2f * PI.toFloat() * (hub + span) / n) * 0.5f
                for (hour in 0 until n) {
                    // Midnight at the top (−90°), clockwise like a clock face.
                    val angle = (-PI / 2.0) + (hour * 2.0 * PI / n)
                    val ux = cos(angle).toFloat()
                    val uy = sin(angle).toFloat()
                    val minutes = hourTotals.getOrElse(hour) { 0L }
                    val frac = if (peak > 0L) minutes.toFloat() / peak else 0f
                    val start = Offset(center.x + ux * hub, center.y + uy * hub)
                    if (frac <= 0f) {
                        // A silent hour keeps its tick: a stub at the hub.
                        val stub = Offset(center.x + ux * (hub + 2.dp.toPx()), center.y + uy * (hub + 2.dp.toPx()))
                        drawLine(color = ring, start = start, end = stub, strokeWidth = strokePx, cap = StrokeCap.Round)
                    } else {
                        val len = span * frac * grow
                        val end = Offset(center.x + ux * (hub + len), center.y + uy * (hub + len))
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
            if (chronotype.isNotBlank()) {
                Text(
                    text = chronotype,
                    style = FolioTheme.typography.labelMedium,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
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
 * reading, else the accent stepped by [intensityFor] — the same 0.25/0.5/0.75/1
 * ladder [HeatmapCell] and the legend use, so grid and key read identically. A
 * tapped cell carries a peak-accent ring. Only dated cells are tappable, and each
 * carries a spoken description for screen readers (Rule 17).
 */
@Composable
private fun HeatmapDayCell(
    day: StatDay?,
    peak: Long,
    size: Dp,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(3.dp)
    val ladder = remember { listOf(0.25f, 0.5f, 0.75f, 1f) }
    val hasReading = day != null && day.minutes > 0L
    val fill = when {
        day == null -> Color.Transparent
        !hasReading -> FolioTheme.colors.outline.copy(alpha = 0.14f)
        else -> accent.copy(alpha = ladder[(intensityFor(day.minutes, peak) - 1).coerceIn(0, 3)])
    }
    val desc = day?.let { heatmapDayDetail(it) }
    Box(
        modifier = Modifier
            .size(size)
            .background(fill, shape)
            .then(
                if (selected) Modifier.border(1.5.dp, FolioTheme.colors.accentStreak, shape)
                else Modifier
            )
            .then(
                if (day != null) Modifier.pointerInput(day) {
                    detectTapGestures(onTap = { onClick() })
                } else Modifier
            )
            .then(
                if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier
            )
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
