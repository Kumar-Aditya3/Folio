package com.folio.reader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioCoverRailSkeleton
import com.folio.reader.ui.components.FolioSkeletonBlock
import com.folio.reader.ui.components.FolioSkeletonLine
import com.folio.reader.ui.components.rememberShimmerPhase
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTokens

/**
 * Home before Home has arrived.
 *
 * Cold start reads the library, the sessions and the stats scope off the database
 * before `HomeUiState.loaded` flips, and on a phone that window is long enough to
 * see. It used to show a spinner centred on an otherwise blank page, which meant
 * the whole screen then *appeared* at once — anchor, ledger and shelf all laying
 * themselves out in the same frame. This draws that layout first, in wells, so the
 * page is already the right shape and the content lands into holes cut for it.
 *
 * Every measure here is the real one lifted from [HomeScreen]: `coverAnchor` and
 * its `coverAspect` for the plate, the anchor's asymmetric gutter inset including
 * the 12dp overhang, `spaceBeat` and `spaceMovement` for the two breaks, `gutter`
 * for the ledger and shelf headings, `coverShelf` over `space3` for the rail. A
 * skeleton that approximates is worse than a spinner, because it promises a
 * geometry and then breaks it.
 *
 * Only the first three blocks are drawn. The week well sits below the fold on
 * every phone at this height, and drawing what cannot be seen just costs frames.
 */

/**
 * Book covers are the printed trim — `coverAspect` is height ÷ width, while
 * `aspectRatio` and `FolioCoverPaneSkeleton.ratio` both want width ÷ height.
 * Derived rather than written out so the two can never drift apart.
 */
internal val homeSkeletonCoverRatio: Float
    get() = 1f / FolioTokens.coverAspect

@Composable
internal fun HomeSkeleton(modifier: Modifier = Modifier) {
    val phase = rememberShimmerPhase()
    Column(modifier.fillMaxSize()) {
        AnchorSkeleton(phase)
        Spacer(Modifier.height(FolioTokens.spaceBeat))
        LedgerSkeleton(phase)
        Spacer(Modifier.height(FolioTokens.spaceMovement))
        ShelfSkeleton(phase)
    }
}

/**
 * The anchor: plate overhanging the leading edge, type beside it — eyebrow, a
 * title that runs to three lines, an author — then the hairline with its
 * last-read caption and the progress figure at the foot. The type column is
 * matched to the plate's height and `SpaceBetween`, exactly as the real one is,
 * so the three groups sit where their content will.
 */
@Composable
private fun AnchorSkeleton(phase: State<Float>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = FolioTokens.gutter, top = FolioTokens.space2)
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.space3,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.offset(x = -(FolioTokens.gutter - FolioTokens.space1))) {
                FolioSkeletonBlock(
                    modifier = Modifier
                        .width(FolioTokens.coverAnchor)
                        .aspectRatio(homeSkeletonCoverRatio),
                    shape = FolioShapes.plate,
                    phase = phase,
                )
            }
            Spacer(Modifier.width(FolioTokens.space2))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(FolioTokens.coverAnchor * FolioTokens.coverAspect),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceHair)) {
                    FolioSkeletonLine(widthFraction = 0.34f, height = 8.dp, phase = phase)
                    FolioSkeletonLine(widthFraction = 0.94f, height = 20.dp, phase = phase)
                    FolioSkeletonLine(widthFraction = 0.71f, height = 20.dp, phase = phase)
                    FolioSkeletonLine(widthFraction = 0.42f, height = 10.dp, phase = phase)
                }
                Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                    FolioSkeletonLine(widthFraction = 1f, height = 1.dp, phase = phase)
                    FolioSkeletonLine(widthFraction = 0.46f, height = 10.dp, phase = phase)
                }
                Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceHair)) {
                    FolioSkeletonLine(widthFraction = 0.28f, height = 26.dp, phase = phase)
                    FolioSkeletonLine(widthFraction = 0.44f, height = 10.dp, phase = phase)
                }
            }
        }
        Spacer(Modifier.height(FolioTokens.space3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FolioSkeletonBlock(
                modifier = Modifier
                    .width(62.dp)
                    .height(12.dp),
                shape = RoundedCornerShape(6.dp),
                phase = phase,
            )
            Spacer(Modifier.width(FolioTokens.space3))
            FolioSkeletonBlock(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                phase = phase,
            )
        }
    }
}

/**
 * The ledger: two quiet figures on the page over a hairline. No container,
 * because the real one has none — boxing it here would make the skeleton read as
 * a card that then dissolves.
 */
@Composable
private fun LedgerSkeleton(phase: State<Float>) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            FigureSkeleton(phase)
            Spacer(Modifier.width(FolioTokens.spaceMovement))
            FigureSkeleton(phase)
        }
        Spacer(Modifier.height(FolioTokens.space2))
        FolioSkeletonLine(widthFraction = 1f, height = 1.dp, phase = phase)
    }
}

@Composable
private fun FigureSkeleton(phase: State<Float>) {
    Column(
        modifier = Modifier.width(72.dp),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceHair),
    ) {
        FolioSkeletonLine(widthFraction = 0.68f, height = 22.dp, phase = phase)
        FolioSkeletonLine(widthFraction = 0.46f, height = 9.dp, phase = phase)
    }
}

/**
 * The continue shelf: heading at the gutter, then a rail that runs off the
 * trailing edge. [FolioCoverRailSkeleton] is not lazy, so the fifth pane simply
 * overflows the frame — the same "there is more over there" the real rail gives.
 */
@Composable
private fun ShelfSkeleton(phase: State<Float>) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioSkeletonLine(widthFraction = 0.26f, height = 12.dp, phase = phase)
        }
        Spacer(Modifier.height(FolioTokens.space3))
        FolioCoverRailSkeleton(
            count = 5,
            modifier = Modifier.padding(start = FolioTokens.gutter),
            paneWidth = FolioTokens.coverShelf,
            spacing = FolioTokens.space3,
            ratio = homeSkeletonCoverRatio,
            phase = phase,
        )
    }
}
