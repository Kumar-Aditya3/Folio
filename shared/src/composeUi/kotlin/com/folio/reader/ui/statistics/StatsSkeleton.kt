package com.folio.reader.ui.statistics

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioSkeletonBlock
import com.folio.reader.ui.components.FolioSkeletonLine
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberShimmerPhase
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Stats before Stats has arrived.
 *
 * The tab opened on `collectAsState(initial = StatisticsUiState())` — an empty
 * state — because [StatisticsViewModel.state] is a `combine` of four suspense
 * queries plus (when exclusions are wired) two roundtrips *per book* to resolve
 * tags and collections. For the whole of that window the reader looked at zeroed
 * figures, an empty heatmap and the "Nothing measured yet" card, and then every
 * value changed at once under them.
 *
 * This draws the real layout first, in wells, so the page is already the right
 * shape and the numbers land into holes cut for them.
 *
 * Every measure is lifted from [StatisticsScreen] rather than approximated:
 * `heroBleed` with the same asymmetric padding for the overture, the 96dp goal
 * dial, `spaceBeat` around the rule in the headline row, `gutter` for every
 * heading, `weekChartHeight` inside an `edgeStart` well for the curve. A skeleton
 * that approximates is worse than a spinner, because it promises a geometry and
 * then breaks it — the two cover ratios in Home's skeleton carry the same note.
 *
 * Only the blocks above the fold are drawn. On every phone at this height the
 * chart, heatmap and cards below it are off-screen, so drawing them would cost
 * frames to produce something nobody sees.
 */
@Composable
internal fun StatsSkeleton(modifier: Modifier = Modifier) {
    val phase = rememberShimmerPhase()
    Column(modifier.fillMaxSize()) {
        OvertureSkeleton(phase)
        Spacer(Modifier.height(FolioTokens.spaceMovement))
        HeadlineSkeleton(phase)
        Spacer(Modifier.height(FolioTokens.spaceMovement))
        WeekSkeleton(phase)
    }
}

/**
 * The overture: hero figure and its supporting sentence on the leading side, the
 * goal dial on the trailing edge, inside the same `heroBleed` raised surface.
 *
 * The dial is a circle, not a block — it is the one element in this layout whose
 * shape the reader already knows, and a rounded rectangle where a ring will land
 * reads as a different component arriving rather than the same one filling in.
 */
@Composable
private fun OvertureSkeleton(phase: State<Float>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = FolioTokens.gutter, top = FolioTokens.space2)
            .folioRaised(shape = FolioShapes.heroBleed, accent = FolioTheme.colors.accentProgress)
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.space3,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                // The hero figure: a large value line with its label beneath.
                FolioSkeletonLine(widthFraction = 0.52f, height = 34.dp, phase = phase)
                Spacer(Modifier.height(FolioTokens.space2))
                FolioSkeletonLine(widthFraction = 0.30f, height = 9.dp, phase = phase)
                Spacer(Modifier.height(FolioTokens.space2))
                // Chronotype, then the peak-window sentence.
                FolioSkeletonLine(widthFraction = 0.68f, height = 16.dp, phase = phase)
                Spacer(Modifier.height(FolioTokens.space1))
                FolioSkeletonLine(widthFraction = 0.46f, height = 11.dp, phase = phase)
            }
            Spacer(Modifier.width(FolioTokens.space3))
            FolioSkeletonBlock(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                phase = phase,
            )
        }
    }
}

/**
 * Streak and active-days figures over the rule, this-year and finished beneath —
 * two columns of two, separated by the same `spaceBeat` the real row uses.
 */
@Composable
private fun HeadlineSkeleton(phase: State<Float>) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            FigureSkeleton(phase, Modifier.weight(1f))
            FigureSkeleton(phase, Modifier.weight(1f))
        }
        Spacer(Modifier.height(FolioTokens.spaceBeat))
        FolioSkeletonLine(widthFraction = 1f, height = 1.dp, phase = phase)
        Spacer(Modifier.height(FolioTokens.spaceBeat))
        Row(modifier = Modifier.fillMaxWidth()) {
            FigureSkeleton(phase, Modifier.weight(1f))
            FigureSkeleton(phase, Modifier.weight(1f))
        }
    }
}

/** One figure cell: value, label, caption — the three lines [FolioFigure] draws. */
@Composable
private fun FigureSkeleton(phase: State<Float>, modifier: Modifier = Modifier) {
    Column(modifier) {
        FolioSkeletonLine(widthFraction = 0.30f, height = 26.dp, phase = phase)
        Spacer(Modifier.height(FolioTokens.space1))
        FolioSkeletonLine(widthFraction = 0.42f, height = 9.dp, phase = phase)
        Spacer(Modifier.height(FolioTokens.space1))
        FolioSkeletonLine(widthFraction = 0.34f, height = 9.dp, phase = phase)
    }
}

/**
 * The week card: section head, then the curve's well at its shipping height.
 *
 * The well is drawn as one block rather than a set of bars because the real chart
 * is a single smooth curve in a sunken pane — bars here would suggest a different
 * chart is coming.
 */
@Composable
private fun WeekSkeleton(phase: State<Float>) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioSkeletonLine(widthFraction = 0.34f, height = 10.dp, phase = phase)
        }
        Spacer(Modifier.height(FolioTokens.space3))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .folioSunken(FolioShapes.edgeStart)
                .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
        ) {
            FolioSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FolioTokens.weekChartHeight),
                phase = phase,
            )
        }
    }
}
