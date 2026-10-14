package com.folio.reader.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

/**
 * The app's authored easing curves.
 *
 * Compose ships four stock easings, and before this existed Folio imported them ad hoc at ~20 call
 * sites — `FastOutSlowInEasing` here, `LinearEasing` there, `LinearOutSlowInEasing` for one branch
 * of the overlay host. The result was three different "arrive" curves on the same screen and no way
 * to answer "what does the app's motion feel like" in one place.
 *
 * These five are named for the *job* rather than the shape, so a call site states its intent and the
 * curve is tuned once:
 *
 * - [Enter] — something arriving. Decelerates hard out of the first frame and settles without
 *   overshoot: the reader's eye is already moving toward the destination, so the motion should
 *   commit immediately and then ease.
 * - [Exit] — something leaving. Accelerates away, and is deliberately faster than [Enter] at the
 *   same duration: an object the reader has dismissed should get out of the way rather than perform
 *   its departure.
 * - [Travel] — a shared element crossing the screen (the cover morph). Symmetric and long-tailed, so
 *   the object reads as one continuous thing that starts and stops, not as a fade between two
 *   positions.
 * - [Continuous] — never-ending motion: the background field, the shimmer, the pulses. Linear,
 *   because a loop that eases reads as a series of starts and stops rather than as a slow drift.
 * - [Timer] — a dwell. There is no motion to shape; it exists so a dwell's spec reads as a spec.
 *
 * Values are cubic-béziers in Compose's `(x1, y1, x2, y2)` form. [Enter] is intentionally close to
 * Material's "emphasized decelerate" and [Exit] to its "emphasized accelerate"; the point is not to
 * invent new physics but to stop the app from mixing four vocabularies for the same idea.
 */
object FolioEasing {

    /** Arriving: commit on the first frame, then settle. */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Leaving: accelerate away, faster than [Enter] at equal duration. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** A shared element travelling across the viewport. */
    val Travel: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Never-ending motion: the field, the shimmer, the pulses. */
    val Continuous: Easing = LinearEasing

    /** A dwell — a duration with no motion to shape. */
    val Timer: Easing = LinearEasing
}
