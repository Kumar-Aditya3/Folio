package com.folio.reader.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Living Paper" predictive back — the pushed screen recedes as the back gesture
 * grows, so a drag reads as the current page lifting away to reveal what is
 * underneath, not a hard cut on release.
 *
 * Driven by the root `PredictiveBackHandler`'s `backProgress` (0 at rest → 1 at
 * commit), the same signal that already reels in the nav capsule and status
 * banner. Applied to the pushed content only (the reader, a detail page) — never
 * a top-level tab, whose back exits the app rather than lifting a card.
 *
 * The transform is a small scale-down, a slight downward settle and a fade, with
 * the corners rounding in as it shrinks so it reads as a physical card being set
 * back. All in the layer phase (`graphicsLayer`), so the gesture stays smooth and
 * nothing recomposes per frame. At `progress == 0` the modifier is the identity
 * transform, so a screen at rest is untouched.
 *
 * @param progress the gesture progress, 0..1.
 * @param maxCorner the corner radius reached at full progress.
 */
fun Modifier.folioPredictiveBackScale(
    progress: Float,
    maxCorner: Dp = 32.dp,
): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return this
    // Scale to 92% at full drag — enough to show the page has let go, not so much
    // that it looks thrown away.
    val scale = 1f - 0.08f * p
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            // A gentle settle downward, a few percent of the height at most.
            translationY = size.height * 0.04f * p
            alpha = 1f - 0.25f * p
        }
        .clip(RoundedCornerShape(maxCorner * p))
}
