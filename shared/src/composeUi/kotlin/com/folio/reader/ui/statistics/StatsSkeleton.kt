package com.folio.reader.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/** The current open Stats layout, awaiting values rather than a different set of cards. */
@Composable
internal fun StatsSkeleton(modifier: Modifier = Modifier, onOpenExclusions: (() -> Unit)? = null) {
    val phase = rememberShimmerPhase()
    StatsPage(modifier.semantics { contentDescription = "Loading reading statistics" }) {
        if (onOpenExclusions != null) item { StatsExclusionsNotice(onOpenExclusions) }
        item {
            OvertureSkeleton(phase)
            Spacer(Modifier.height(FolioTokens.spaceBeat))
        }
        item {
            StatsOpenSection { HeadlineSkeleton(phase) }
            Spacer(Modifier.height(FolioTokens.spaceMovement))
        }
        item {
            StatsOpenSection { WeekSkeleton(phase) }
            Spacer(Modifier.height(FolioTokens.spaceBeat))
        }
        item { StatsOpenSection { YearSkeleton(phase) } }
    }
}

@Composable
private fun OvertureSkeleton(phase: State<Float>) {
    StatsOvertureLayout(
        figure = { FigureSkeleton("Reading this week", phase, hero = true) },
        rhythm = { modifier ->
            Column(modifier) {
                TextSkeleton("The Night Owl", FolioTheme.typography.titleMedium, phase, .72f, maxLines = 2)
                TextSkeleton("Mostly between 9 PM–midnight", FolioTheme.typography.bodySmall, phase, .88f, maxLines = 3)
            }
        },
        goal = {
            val track = FolioTheme.colors.accentProgress.copy(alpha = .16f)
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 7.dp.toPx()
                    drawCircle(track, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                }
                Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextSkeleton("00", FolioTheme.typography.headlineSmall, phase, .65f)
                    TextSkeleton("/ 60 min", FolioTheme.typography.labelSmall, phase, .85f)
                }
            }
        },
    )
}

@Composable
private fun HeadlineSkeleton(phase: State<Float>) {
    StatsHeadlineLayout(
        streak = { FigureSkeleton("Streak", phase, it, standard = true, caption = "best 00") },
        activeDays = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                FigureSkeleton("Active days", phase)
                Row(Modifier.fillMaxWidth().height(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) {
                        FolioSkeletonBlock(Modifier.weight(1f).height(7.dp), RoundedCornerShape(3.dp), phase)
                    }
                }
            }
        },
        year = { FigureSkeleton("This year", phase, it, caption = "1,240,500 words") },
        finished = { FigureSkeleton("Finished", phase, it, caption = "books completed") },
    )
}

/** Measure with the actual figure typography, including large-text settings. */
@Composable
private fun FigureSkeleton(
    label: String,
    phase: State<Float>,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    standard: Boolean = false,
    caption: String? = null,
) {
    val type = FolioTheme.typography
    val style = when {
        hero -> type.displayMedium
        standard -> type.displaySmall
        else -> type.headlineSmall
    }
    Column(modifier) {
        FolioEyebrow(label)
        Spacer(Modifier.height(if (hero) 6.dp else 3.dp))
        TextSkeleton("00", style.copy(fontFamily = FontFamily.Monospace), phase, if (hero) .74f else .44f)
        if (caption != null) {
            Spacer(Modifier.height(5.dp))
            TextSkeleton(caption, type.bodySmall, phase, .70f, maxLines = 2)
        }
    }
}

@Composable
private fun WeekSkeleton(phase: State<Float>) {
    val type = FolioTheme.typography
    StatsWeekLayout(
        heading = {
            FolioSectionHead(title = "Last 7 days", trailing = {
                TextSkeleton("00m", type.labelSmall, phase, modifier = Modifier.width(42.dp))
            })
        },
        plot = {
            Column {
                FolioSkeletonBlock(Modifier.fillMaxWidth().height(FolioTokens.weekChartHeight), phase = phase)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            TextSkeleton("M", type.labelSmall, phase, modifier = Modifier.width(12.dp))
                        }
                    }
                }
            }
        },
        readout = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextSkeleton("0000-00-00", type.bodySmall, phase, .56f, Modifier.weight(1f))
                TextSkeleton("00m", type.titleSmall, phase, modifier = Modifier.width(42.dp))
            }
        },
        hint = { TextSkeleton("Tap or slide across the curve", type.labelSmall, phase, .64f) },
    )
}

/** The year starts within view on taller screens; keep its open-field silhouette too. */
@Composable
private fun YearSkeleton(phase: State<Float>) {
    FolioSectionHead(title = "Your year", eyebrow = "Activity")
    Spacer(Modifier.height(FolioTokens.space3))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (width in listOf(52.dp, 72.dp, 76.dp)) {
            Box(Modifier.size(width, 48.dp), contentAlignment = Alignment.Center) {
                FolioSkeletonBlock(Modifier.fillMaxWidth().height(32.dp), RoundedCornerShape(16.dp), phase)
            }
        }
    }
    Spacer(Modifier.height(FolioTokens.space3))
    TextSkeleton("Month", FolioTheme.typography.labelSmall, phase, .35f)
    Spacer(Modifier.height(4.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 4.dp) / 17.dp).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(columns) {
                        FolioSkeletonBlock(Modifier.size(13.dp), RoundedCornerShape(2.dp), phase)
                    }
                }
            }
        }
    }
}

/** Invisible measuring text reserves the real line box; only the shimmer is visible. */
@Composable
private fun TextSkeleton(
    measure: String,
    style: TextStyle,
    phase: State<Float>,
    fraction: Float = 1f,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val height = with(LocalDensity.current) { style.fontSize.toDp() * .58f }
    Box(modifier.fillMaxWidth().clearAndSetSemantics {}, contentAlignment = Alignment.CenterStart) {
        Text(measure, style = style, maxLines = maxLines, modifier = Modifier.alpha(0f))
        FolioSkeletonLine(widthFraction = fraction, height = height, phase = phase)
    }
}
