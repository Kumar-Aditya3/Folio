package com.folio.reader.ui.components

import androidx.compose.animation.ContentTransform
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
 * grid↔list↔compact view. A dissolve, and nothing else.
 *
 * The other transforms all fail on a surface this large. A slide uncovers a bare
 * band of ground plane at the leading edge; a scale re-rasterises covers and body
 * text, so the page goes soft for the whole cross; and a `SizeTransform` measures
 * the incoming lazy grid against an interpolated width, which changes its column
 * count and re-flows every row, then snaps them back when the cross ends. That
 * last one is what read as the layout "changing for a split second".
 *
 * Direction is not lost: [FolioSegmented]'s indicator already springs toward the
 * segment being selected, so the control says which way the switch went.
 *
 * [motionEnabled] is read by the caller because `transitionSpec` lambdas are not
 * composable and cannot call `rememberMotionEnabled()` themselves. Rule 19: with
 * motion off the swap is instant, not faded.
 */
fun folioFadeSwap(motionEnabled: Boolean): ContentTransform = ContentTransform(
    fadeIn(tween(if (motionEnabled) fadeSwapEnterMs.toInt() else 0)),
    fadeOut(tween(if (motionEnabled) fadeSwapExitMs.toInt() else 0)),
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
