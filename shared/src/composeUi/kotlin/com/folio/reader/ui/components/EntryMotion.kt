package com.folio.reader.ui.components

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
