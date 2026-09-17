package com.folio.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioSkeletonBlock
import com.folio.reader.ui.components.FolioSkeletonLine
import com.folio.reader.ui.components.rememberShimmerPhase
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset

/**
 * The shelf before it has been measured.
 *
 * The loader used to be a 24dp spinner centred on an otherwise empty page, which
 * is the wrong shape in two ways. It promises nothing about what is coming, so the
 * page then *appears* — every cover and caption laying itself out in one frame when
 * the read lands. And because the shelf is reached by a tab cross-fade, that empty
 * page is what the fade carries in: the reader sees the library arrive blank and
 * then fill, which is the reported flash. Same reasoning as [HomeSkeleton]; the two
 * now agree about how this app waits.
 *
 * Every measure is the real one lifted from [LibraryGrid] and [FeaturedShelfEntry]:
 * the adaptive 116dp column floor, `gutter` side margins, the `space3 + topInset`
 * head inset, `spaceBeat` between rows and `space3` between columns, the feature row
 * spanning the full line, and `coverFeature` over its own type block. A skeleton
 * that approximates is worse than a spinner — it promises a geometry and breaks it —
 * so these are read from the same tokens the grid uses rather than copied as
 * literals.
 *
 * Columns are not counted here. `GridCells.Adaptive` decides that from the width the
 * grid is actually given, and the skeleton is given the same width, so the two agree
 * without either doing the arithmetic.
 */
@Composable
internal fun LibrarySkeleton(modifier: Modifier = Modifier) {
    val phase = rememberShimmerPhase()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 116.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FolioTokens.gutter,
            end = FolioTokens.gutter,
            top = FolioTokens.space3 + LocalFolioTopInset.current,
            bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
        ),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceBeat),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3),
        // Nothing is interactive while the shelf is unmeasured, and a scroll
        // gesture against a skeleton only strands the reader mid-flight when the
        // real grid takes over with its own scroll position.
        userScrollEnabled = false,
    ) {
        // One feature cell, then rows of plain cards. Enough of each to fill the
        // viewport at this row height; the grid is not visible past the fold and
        // drawing more just costs frames.
        item(span = { GridItemSpan(maxLineSpan) }) {
            FeatureSkeleton(phase)
        }
        items(count = SKELETON_CARDS) { CellSkeleton(phase) }
    }
}

/**
 * The feature entry's stand-in: the plate at `coverFeature` beside a title that
 * runs to two lines and an author, so the wide first cell occupies the height the
 * real one will and the rows below do not jump when it lands.
 */
@Composable
private fun FeatureSkeleton(phase: State<Float>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = FolioTokens.space2),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioSkeletonBlock(
            modifier = Modifier
                .width(FolioTokens.coverFeature)
                .aspectRatio(LibrarySkeletonCoverRatio),
            shape = RoundedCornerShape(FolioTokens.radiusCard),
            phase = phase,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            FolioSkeletonLine(widthFraction = 0.9f, phase = phase)
            FolioSkeletonLine(widthFraction = 0.62f, phase = phase)
            Spacer(Modifier.height(FolioTokens.space1))
            FolioSkeletonLine(widthFraction = 0.4f, height = 11.dp, phase = phase)
        }
    }
}

/** One grid cell: the plate at the shipping aspect, then its two type lines. */
@Composable
private fun CellSkeleton(phase: State<Float>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
    ) {
        FolioSkeletonBlock(
            modifier = Modifier.fillMaxWidth().aspectRatio(LibrarySkeletonCoverRatio),
            shape = RoundedCornerShape(FolioTokens.radiusCard),
            phase = phase,
        )
        FolioSkeletonLine(widthFraction = 0.86f, height = 12.dp, phase = phase)
        FolioSkeletonLine(widthFraction = 0.54f, height = 10.dp, phase = phase)
    }
}

/**
 * Covers are the printed trim — [FolioTokens.coverAspect] is height ÷ width, while
 * `aspectRatio` wants width ÷ height. Derived rather than written out so the
 * skeleton and the real plate can never drift.
 */
private val LibrarySkeletonCoverRatio: Float
    get() = 1f / FolioTokens.coverAspect

/** Two rows below the feature on a phone at this plate height. */
private const val SKELETON_CARDS = 6
