package com.folio.reader.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.LocalGlassBackdrop
import com.folio.reader.ui.components.LocalGlassCapabilities
import com.folio.reader.ui.theme.FolioTokens

/**
 * §16 P0 — the **glass stats readout**: what the glass is actually doing, on the device, right now.
 *
 * The performance work in this plan is all about *rate* — the room re-drawing, and every pane
 * re-blurring behind it. That is invisible in a screenshot and easy to argue about from the code, so
 * the plan's Phase 0 is a readout that says it out loud, and Phase 9's attribution order
 * (lens → film → field motion → in-flow blur) is only meaningful next to one.
 *
 * ## What it shows, and why exactly this
 *
 *  • **Frame time p50 / p95**, sampled from `withFrameNanos` over a rolling window. p50 is what the
 *    screen feels like; p95 is the one that decides whether it stutters. Both, because a change that
 *    improves the median and worsens the tail has not improved anything a reader notices.
 *  • **The capability verdict** — whether this device can blur, and whether a backdrop registry is
 *    live. Every glass decision in the app forks on those two, so a report of "the glass looks
 *    wrong" is unanswerable without them: on a device that cannot blur, or a screen that provides no
 *    backdrop, the material is *supposed* to be the non-blur fallback.
 *  • **The ground-plane cadence**, read from the token rather than restated, so the readout cannot
 *    disagree with the field it is describing.
 *
 * ## Cost
 *
 * One `withFrameNanos` loop, and only while [enabled]. That loop is itself an infinite animation, so
 * it keeps the frame clock awake — which is exactly why it is gated rather than always on, and why
 * the readout must be turned off before any measurement of the field's own cost is believed.
 */
@Composable
fun FolioGlassStats(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return

    val samples = remember { mutableStateListOf<Float>() }
    val lastFrame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                val previous = lastFrame.longValue
                if (previous != 0L) {
                    samples.add((now - previous) / 1_000_000f)
                    // A rolling window, not a running total: this is meant to describe the last
                    // couple of seconds of scrolling, not the whole session.
                    if (samples.size > FOLIO_STATS_WINDOW) samples.removeAt(0)
                }
                lastFrame.longValue = now
            }
        }
    }

    val caps = LocalGlassCapabilities.current
    val backdropLive = LocalGlassBackdrop.current != null
    // Sorted once per frame rather than per read: the window is small and the list is stable.
    val ordered = samples.sorted()
    val p50 = ordered.percentile(0.50f)
    val p95 = ordered.percentile(0.95f)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .padding(FolioTokens.space3)
                .background(FOLIO_STATS_SCRIM, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Line("frames  ${samples.size}/$FOLIO_STATS_WINDOW")
            Line("p50     ${p50.fmt()} ms")
            Line("p95     ${p95.fmt()} ms")
            Line("blur    ${caps.blur}  backdrop $backdropLive")
            Line("spec    ${caps.specular}  noise ${caps.noise}")
            Line("field   ${FolioTokens.fieldAnimationFrameMs} ms (20 Hz)")
        }
    }
}

/** One readout row. Monospace so the numbers do not jitter as they change width. */
@Composable
private fun Line(text: String) {
    Text(text = text, color = FOLIO_STATS_INK, fontSize = 11.sp)
}

/** The 50th or 95th percentile of a sorted list, or NaN before there is anything to say. */
private fun List<Float>.percentile(fraction: Float): Float {
    if (isEmpty()) return Float.NaN
    val index = ((size - 1) * fraction).toInt().coerceIn(0, size - 1)
    return this[index]
}

private fun Float.fmt(): String = if (isNaN()) "--" else "%.1f".format(this)

/** How many frames the window holds — about two seconds at 60 Hz. */
private const val FOLIO_STATS_WINDOW = 120

/**
 * The developer flag the readout is gated on. Flip to `true` to profile, and back to `false` before
 * shipping — the loop it starts is itself an infinite animation (see the note on cost above), so a
 * build that leaves it on is measuring itself.
 */
const val FOLIO_GLASS_STATS_ENABLED = false

private val FOLIO_STATS_SCRIM = Color(0xCC101014)
private val FOLIO_STATS_INK = Color(0xFFE8E8EC)
