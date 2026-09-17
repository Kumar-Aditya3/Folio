package com.folio.reader.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay

/**
 * §13.5 chart entry progress, 0f→1f over [FolioTokens.motionEmphasis] with
 * FastOutSlowIn. The timeline runs once per [identity] — pass the data window
 * (the days' dates), never live values, so a progress update cannot re-trigger
 * the sweep mid-scroll. rememberSaveable keeps "played" across lazy-list
 * disposal, so scrolling away and back does not replay it. Reduce-motion
 * (Rule 19) returns 1f — final state immediately, no fade substitute.
 *
 * [rememberEntryState] is the draw-phase form: charts whose canvases read the
 * returned State animate without recomposing per frame.
 */
@Composable
fun rememberEntryProgress(vararg identity: Any?): Float =
    rememberEntryState(*identity).value

@Composable
fun rememberEntryState(vararg identity: Any?): State<Float> {
    if (!rememberMotionEnabled()) {
        val done = remember { mutableStateOf(1f) }
        return done
    }
    var played by rememberSaveable(*identity) { mutableStateOf(false) }
    val progress = remember(*identity) { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(*identity) {
        if (progress.value < 1f) {
            progress.animateTo(
                1f,
                tween(durationMillis = FolioTokens.motionEmphasis.toInt(), easing = FastOutSlowInEasing),
            )
            played = true
        }
    }
    return remember(*identity) { derivedStateOf { progress.value } }
}

/**
 * The spec for a swap that changes *what is on the page* without changing the
 * route — the Library's Books/Manga/Documents shelves and each shelf's
 * grid↔list↔compact view. A dissolve, and nothing else — unless [sizeTransform]
 * is supplied, which is the one exception the layout allows.
 *
 * The other transforms all fail on a surface this large. A slide uncovers a bare
 * band of ground plane at the leading edge; a scale re-rasterises covers and body
 * text, so the page goes soft for the whole cross. A `SizeTransform` measures the
 * incoming lazy grid against an interpolated width, which changes its column
 * count and re-flows every row, then snaps them back when the cross ends — that
 * last one is what read as the layout "changing for a split second", and it is
 * why the default here clips and animates nothing.
 *
 * It is not forbidden outright, though. A swap between two views of *the same
 * item count* — the Books/Manga/Documents shelves all render whatever the shelf
 * holds — has a stable row count, so the grid never re-columns and the measured
 * size only ever moves by the couple of rows a differ goes from a short shelf to
 * a tall one. That is what [folioSizeTransformEligible] gates: past a handful of
 * rows the re-column risk and the cost of measuring every interpolated frame
 * outweigh the smoothness. `clip = false` is deliberate — clipping would crop the
 * outgoing grid to the interpolated box and reveal the bare ground plane the
 * slide rejection is about.
 *
 * Direction is not lost: [FolioSegmented]'s indicator already springs toward the
 * segment being selected, so the control says which way the switch went.
 *
 * [motionEnabled] is read by the caller because `transitionSpec` lambdas are not
 * composable and cannot call `rememberMotionEnabled()` themselves. Rule 19: with
 * motion off the swap is instant, not faded.
 */
fun folioFadeSwap(
    motionEnabled: Boolean,
    sizeTransform: SizeTransform? = null,
): ContentTransform = ContentTransform(
    fadeIn(tween(if (motionEnabled) fadeSwapEnterMs.toInt() else 0)),
    fadeOut(tween(if (motionEnabled) fadeSwapExitMs.toInt() else 0)),
    sizeTransform = sizeTransform.takeIf { motionEnabled },
)

/**
 * True when a swap's two sides are small enough that a [SizeTransform] helps
 * rather than hurts. See [folioFadeSwap] for why the threshold exists.
 *
 * [itemCount] is the shared item count of the two views crossing — `maxOf` the
 * two, since it is the taller side that decides how many rows get measured.
 * Below [sizeTransformMaxItems] the height difference a swap can produce is a
 * few rows and interpolating it reads as the page resettling; above it, the
 * incoming lazy grid can re-column mid-cross, which is the glitch.
 *
 * Pure and public so the threshold is unit-testable without a composition.
 */
fun folioSizeTransformEligible(itemCount: Int): Boolean =
    itemCount in 1..sizeTransformMaxItems

internal const val sizeTransformMaxItems = 8

/**
 * The spec each swap's size runs on, matching the enter fade exactly. A size that
 * outlives its fade keeps the two views' containers animating after one of them
 * is invisible, which reads as the page still settling when it has finished.
 */
internal fun folioSwapSizeTransform(): SizeTransform = SizeTransform(
    clip = false,
    sizeAnimationSpec = { _, _ ->
        tween(fadeSwapEnterMs.toInt(), easing = FastOutSlowInEasing)
    },
)

private val fadeSwapEnterMs = FolioTokens.motionFast + 60
private val fadeSwapExitMs = FolioTokens.motionFast
private object NeverSwapped

/**
 * True for the length of a [folioFadeSwap] dissolve after [identity] changes, and
 * false while that swap first composes — a screen mounting is not a swap, and a
 * caller that suspended something for the first 180ms of every shelf would suspend
 * it on every visit.
 *
 * It exists for the cover morph: during a dissolve the outgoing grid and the
 * incoming list are composed at once and both publish the same cover key under one
 * `AnimatedVisibilityScope`, which the shared-transition registry cannot resolve.
 * See [FolioSharedElementsSuppressed].
 */
@Composable
fun rememberSwapInFlight(identity: Any?): Boolean {
    val motion = rememberMotionEnabled()
    var inFlight by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf<Any?>(NeverSwapped) }
    LaunchedEffect(identity, motion) {
        val swapped = previous != NeverSwapped && previous != identity
        previous = identity
        if (!swapped || !motion) return@LaunchedEffect
        inFlight = true
        delay(fadeSwapEnterMs)
        inFlight = false
    }
    return inFlight
}

/**
 * §13.5 bar stagger, pure so it is unit-testable: [entry] is the shared chart
 * timeline; returns (growth, capReveal) for bar [index] of [count] — the bar
 * grows over [barMs] starting [staggerMs]·index, and the Rule 15 peak cap fades
 * in over [capDelayMs] starting [capDelayMs] AFTER its own bar has finished
 * growing (a cap never overlaps its bar's growth). The plan's total fits every
 * bar's cap tail, so entry=1f completes every element exactly.
 */
internal fun chartStagger(
    entry: Float,
    index: Int,
    count: Int,
    barMs: Int = 320,
    staggerMs: Int = 40,
    capDelayMs: Int = 80,
): Pair<Float, Float> {
    val total = staggerMs * (count - 1).coerceAtLeast(0) + barMs + capDelayMs * 2
    val t = entry * total
    val growth = ((t - staggerMs * index) / barMs).coerceIn(0f, 1f)
    val cap = ((t - staggerMs * index - barMs - capDelayMs) / capDelayMs).coerceIn(0f, 1f)
    return growth to cap
}
