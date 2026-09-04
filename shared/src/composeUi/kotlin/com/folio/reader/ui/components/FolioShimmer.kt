package com.folio.reader.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled

/**
 * Shimmer: the material a pane is made of *before* it has arrived.
 *
 * A spinner reports that something is happening somewhere. A shimmering pane
 * reports what is coming and what shape it is — the grid's geometry is on screen
 * before the network answers, so covers land in cells that were already drawn and
 * nothing reflows under the reader's thumb. That is the whole argument for
 * skeletons over spinners in a cover grid, and it is why everything here mirrors
 * the real layout's sizes (`FolioTokens.coverPaneRatio`, `coverRailWidth`,
 * `coverGridMin`) instead of inventing its own.
 *
 * Materially it is one more member of the §12 family: the base fill is the
 * atmosphere's `sunkenFill`, the same well used for charts and inputs, because a
 * pane that has not arrived is a hole in the page rather than a card on it. The
 * travelling band follows the atmosphere's own lighting model — a dark field
 * shimmers by *emission* (`rimLight` brightens the well) and paper by *occlusion*
 * (`rimShade` passes over it as a shadow), the same asymmetry every other surface
 * obeys. A white sheen on paper is invisible, which is exactly the bug Rule 19
 * forbids.
 *
 * **Rule 19 (§13.2).** No API floor: one linear gradient inside a
 * `drawWithCache`, so it renders at `minSdk` 24. Reduce-motion parks the band
 * mid-pane — the placeholder still reads as an unfilled surface, it simply stops
 * moving. The phase is read in the draw phase only, so a shimmering grid costs one
 * drawing pass per frame and zero recompositions.
 */

/** Fraction of a pane's width the highlight band spans. */
private const val SHIMMER_BAND_FRACTION = 0.45f

/**
 * Peak alpha at the centre of the band, per lighting model. Low: the band is a
 * change in light crossing a well, and anything stronger reads as a shape sliding
 * about. Paper takes more than a dark field does, because occluding an already
 * bright surface by a little changes it by less than emitting into a dark one.
 *
 * Internal rather than private so the §13.2 "an effect that silently does nothing
 * is a bug" guard can composite them per palette in test.
 */
internal const val SHIMMER_EMISSION = 0.20f
internal const val SHIMMER_OCCLUSION = 0.30f

/** Where the band rests when the reader has asked for no motion. */
private const val SHIMMER_PARKED = 0.5f

/** Stand-in height for one list row (a chapter, an extension). */
private val SKELETON_ROW_HEIGHT = 56.dp

/** Stand-in height for one line of type. */
private val SKELETON_LINE_HEIGHT = 10.dp

/**
 * Leading-edge x of the highlight band, in pixels, for [progress] across a pane
 * [width] px wide with a [band] px band.
 *
 * Pure so the geometry is pinned by test rather than by eye: at 0 the band sits
 * entirely off the leading edge and at 1 entirely past the trailing edge, so one
 * cycle lights every column exactly once and the loop never begins or ends with
 * the band mid-pane — which is what makes a badly-built shimmer flicker on
 * restart.
 */
internal fun shimmerSweep(progress: Float, width: Float, band: Float): Float =
    -band + progress.coerceIn(0f, 1f) * (width + 2f * band)

/**
 * The shared sweep clock. Hoist one per container and pass it to every pane
 * inside: a grid whose panes shimmer on one beat reads as a single surface
 * waiting, whereas independent clocks read as noise.
 */
@Composable
fun rememberShimmerPhase(): State<Float> {
    if (!rememberMotionEnabled()) {
        return remember { mutableStateOf(SHIMMER_PARKED) }
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = FolioTokens.motionShimmer.toInt(),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerSweep",
    )
}

/**
 * Paints [shape] as an unarrived pane: the sunken well plus a band crossing it —
 * light on a dark field, shadow on paper. Slightly diagonal, because a strictly
 * vertical wipe reads as a scanline.
 */
@Composable
fun Modifier.folioShimmer(
    shape: Shape = RoundedCornerShape(FolioTokens.radiusChip),
    phase: State<Float> = rememberShimmerPhase(),
): Modifier {
    val atmos = FolioTheme.atmosphere
    val base = atmos.sunkenFill
    val peak = if (atmos.isDark) {
        atmos.rimLight.copy(alpha = SHIMMER_EMISSION)
    } else {
        atmos.rimShade.copy(alpha = SHIMMER_OCCLUSION)
    }
    return this
        .clip(shape)
        .drawWithCache {
            val band = size.width * SHIMMER_BAND_FRACTION
            onDrawBehind {
                drawRect(color = base)
                val x = shimmerSweep(phase.value, size.width, band)
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to peak,
                        1f to Color.Transparent,
                        start = Offset(x, 0f),
                        end = Offset(x + band, size.height),
                    ),
                )
            }
        }
}

/** One rectangular unarrived pane. */
@Composable
fun FolioSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FolioTokens.radiusChip),
    phase: State<Float> = rememberShimmerPhase(),
) {
    Box(modifier.folioShimmer(shape, phase))
}

/**
 * A stand-in for one line of type. [widthFraction] exists so a stack of these
 * reads as prose — equal-length lines read as a bar chart.
 */
@Composable
fun FolioSkeletonLine(
    widthFraction: Float = 1f,
    height: Dp = SKELETON_LINE_HEIGHT,
    modifier: Modifier = Modifier,
    phase: State<Float> = rememberShimmerPhase(),
) {
    FolioSkeletonBlock(
        modifier = modifier
            .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
            .height(height),
        shape = RoundedCornerShape(height / 2),
        phase = phase,
    )
}

/**
 * One unarrived manga pane: cover plate at the shipping aspect plus its title
 * lines, so the pane occupies the height it will still occupy once the cover
 * lands.
 *
 * [ratio] is width ÷ height and defaults to the manga tile. Books are a hair
 * narrower (`1f / coverAspect`, the printed trim), and a skeleton that guesses
 * wrong here reflows the moment the real cover lands — the one thing a skeleton
 * exists to prevent.
 */
@Composable
fun FolioCoverPaneSkeleton(
    modifier: Modifier = Modifier,
    titleLines: Int = 2,
    ratio: Float = FolioTokens.coverPaneRatio,
    phase: State<Float> = rememberShimmerPhase(),
) {
    Column(modifier) {
        FolioSkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
            phase = phase,
        )
        repeat(titleLines) { line ->
            Spacer(Modifier.height(FolioTokens.spaceHair))
            // The last line stops short, because real titles end mid-line.
            FolioSkeletonLine(
                widthFraction = if (line == titleLines - 1) 0.62f else 1f,
                phase = phase,
            )
        }
    }
}

/**
 * The rail form: [count] unarrived panes at the width a results rail uses.
 * Deliberately not lazy — nobody scrolls a skeleton, and overflow clipping reads
 * correctly as more still to come.
 *
 * [paneWidth] and [spacing] default to the manga results rail; Home's continue
 * shelf is narrower and more open (`coverShelf` / `space3`), and a rail skeleton
 * has to match the rail it stands in or the covers jump sideways when they land.
 */
@Composable
fun FolioCoverRailSkeleton(
    count: Int = 4,
    modifier: Modifier = Modifier,
    paneWidth: Dp = FolioTokens.coverRailWidth,
    spacing: Dp = FolioTokens.space2,
    ratio: Float = FolioTokens.coverPaneRatio,
    phase: State<Float> = rememberShimmerPhase(),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(count) {
            FolioCoverPaneSkeleton(
                modifier = Modifier.width(paneWidth),
                ratio = ratio,
                phase = phase,
            )
        }
    }
}

/**
 * The grid form, for a whole pane that has not arrived yet — opening a source, or
 * a source answering its first page. Mirrors the real grid's adaptive columns and
 * gaps exactly; scrolling is off because there is nothing below to reach.
 */
@Composable
fun FolioCoverGridSkeleton(
    count: Int = 12,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(FolioTokens.space3),
) {
    val phase = rememberShimmerPhase()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = FolioTokens.coverGridMin),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
        userScrollEnabled = false,
    ) {
        items(count) {
            FolioCoverPaneSkeleton(phase = phase)
        }
    }
}

/**
 * A stack of unarrived rows: a chapter list, an extension index. Sized to the real
 * row so the list does not resize when the data lands.
 */
@Composable
fun FolioRowListSkeleton(
    rows: Int = 6,
    rowHeight: Dp = SKELETON_ROW_HEIGHT,
    modifier: Modifier = Modifier,
    phase: State<Float> = rememberShimmerPhase(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
    ) {
        repeat(rows) {
            FolioSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                shape = RoundedCornerShape(FolioTokens.radiusControl),
                phase = phase,
            )
        }
    }
}

/**
 * A whole unarrived results section: the source's name line above its rail. For
 * the window where even the source names are unknown; once a source is known,
 * [FolioCoverRailSkeleton] goes under its real heading instead.
 */
@Composable
fun FolioSourceSectionSkeleton(
    panes: Int = 4,
    modifier: Modifier = Modifier,
) {
    val phase = rememberShimmerPhase()
    Column(modifier) {
        FolioSkeletonLine(widthFraction = 0.38f, phase = phase)
        Spacer(Modifier.height(FolioTokens.space2))
        FolioCoverRailSkeleton(count = panes, phase = phase)
    }
}
