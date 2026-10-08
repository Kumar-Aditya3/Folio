package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The height of a masthead's rail, known during the pass that needs it.
 *
 * ## What this exists to fix
 *
 * A screen whose bar hangs a rail — Library's Books/Manga/Documents switch and its
 * filter chips — has a two-way dependency it cannot resolve in one pass with the
 * obvious arrangement:
 *
 *  1. the rail's height is whatever its content measures (a chip row, a search
 *     field, a segmented switch), and
 *  2. the shelf below must be padded by exactly that height so its first row clears
 *     the glass.
 *
 * The idiomatic way to wire that is `Modifier.onSizeChanged { height = it.height }`
 * on the rail, with the shelf padded from a CompositionLocal built out of `height`.
 * It is also wrong, in a way that is easy to miss: `onSizeChanged` fires in the
 * **layout** phase, after the consumer has already been measured. So the pass that
 * matters goes
 *
 *  * pass 1 — bar and rail measure, `onSizeChanged` writes the height, and the
 *    shelf lays out (and paints) against the *previous* height;
 *  * pass 2 — the state write invalidates, everything re-measures, and the shelf
 *    finally sits at the right offset.
 *
 * On a warm entry the previous height is the cached one and the difference is
 * invisible. On a cold entry, or on a switch to a mode whose rail has never been
 * measured, pass 1 uses `0` and the whole shelf — every cover, every caption —
 * paints a rail-height too high and then drops. That is the reported "the
 * Books/Manga/Documents bar renders later than the page": it is not the bar
 * arriving late, it is the *page* painting at the wrong offset for a frame and the
 * bar appearing to move.
 *
 * ## What this does instead
 *
 * It measures the rail in the same pass, before the content, and hands the real
 * pixel height to [content] as a `State`. The content reads that state **inside its
 * own measure block** rather than during composition, which is the same mechanism
 * `SubcomposeLayout` and a lazy list's own content padding rely on: the value is
 * already resolved by the time the read happens, so it never triggers a second
 * pass and the shelf is never laid out against a height it is about to change.
 *
 * Deliberately a plain `Layout` rather than a `SubcomposeLayout`: the rail is a
 * real child of the composition either way, so subcomposition would only add a slot
 * table for no benefit. The ordering is expressed by *where each child sits in the
 * measure order* — rail first, then content — which is the one thing a `Layout`
 * controls exactly and a `Column` does not (a `Column` measures its children in
 * order but gives the consumer no way to read a sibling's height inside its own
 * measurement).
 *
 * ## Usage
 *
 * ```kotlin
 * FolioBarRailHost(rail = { LibraryModeSwitch(...) }) { railHeight ->
 *     val inset by remember { derivedStateOf { folioBarTopInset(railHeight.value) } }
 *     CompositionLocalProvider(LocalFolioTopInset provides inset) { Shelf() }
 * }
 * ```
 *
 * @param rail the rail content, measured first at the constrained width.
 * @param content the surface below. The rail's measured height is passed as a
 *        `State` so it can be read during measurement instead of composition.
 */
@Composable
fun FolioBarRailHost(
    rail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (State<Dp>) -> Unit,
) {
    val density = LocalDensity.current
    // Written by the measure policy and read by the content's own measure block.
    // Not `rememberSaveable`: a measurement is not state worth restoring, and a
    // restored pixel height from a different device configuration is precisely the
    // kind of plausible-but-wrong seed this file exists to remove.
    val railHeight = remember { mutableStateOf(0.dp) }

    Layout(
        modifier = modifier,
        contents = listOf(rail, { content(railHeight) }),
        measurePolicy = remember(density) { FolioBarRailMeasurePolicy(density, railHeight) },
    )
}

/**
 * Measures the rail to get its height, publishes it, then measures the content with
 * that height already resolved — all inside one pass.
 *
 * The `State` write below happens during measurement, which is safe *because* the
 * only reader is the sibling measure further down this same policy: Compose
 * resolves it synchronously for that read, so the content never observes the old
 * value. Writing a `State` in a measure block that some unrelated node reads during
 * *composition* is the hazard this shape avoids by construction.
 */
private class FolioBarRailMeasurePolicy(
    private val density: Density,
    private val railHeightState: MutableState<Dp>,
) : MultiContentMeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val railMeasurable = measurables.getOrNull(0)?.firstOrNull()
        val contentMeasurable = measurables.getOrNull(1)?.firstOrNull()
        val width = constraints.maxWidth
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width)

        val railPlaceable: Placeable? = railMeasurable?.measure(childConstraints)
        val railHeightPx = railPlaceable?.height ?: 0
        // Publish before the content measures, so the content's own measure block
        // sees the height of the rail it is about to be placed under.
        railHeightState.value = with(density) { railHeightPx.toDp() }

        val contentPlaceable: Placeable? = contentMeasurable?.measure(childConstraints)

        val totalHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            railHeightPx + (contentPlaceable?.height ?: 0)
        }

        return layout(width, totalHeight) {
            railPlaceable?.placeRelative(0, 0)
            contentPlaceable?.placeRelative(0, railHeightPx)
        }
    }
}
