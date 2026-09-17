package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.rememberSlowPhases
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** §13.4: cycle lengths within the 18–30s band, distinct so they never beat in sync. */
private val MESH_PERIODS_MS = listOf(21_000L, 27_000L, 18_000L)
private const val MESH_MAX_ALPHA = 0.18f

/**
 * §13.4 drifting gradient mesh: three large, low-alpha radial gradients behind
 * the hero, their centres drifting on slow independent cycles. Draw-phase state
 * reads keep this one drawing pass — no per-frame recomposition. Reduce-motion
 * freezes the centres (same mesh, zero animation); the lazy list disposing the
 * hero offscreen stops the clocks entirely. Radius scales with the shorter edge
 * and centres can sit outside the bounds, so no layer ever shows a visible edge.
 *
 * The phases come from [rememberSlowPhases] rather than a
 * `rememberInfiniteTransition`, so an 18–30 second cycle is sampled ten times a
 * second instead of ninety. At that period the two are the same picture, and the
 * difference is that the hero stops invalidating itself every vsync — which,
 * added to the §17 light doing the same thing on every other surface, is what
 * kept the whole page redrawing forever. See [SLOW_MOTION_TICK_MS].
 */
@Composable
internal fun Modifier.heroMesh(layers: List<Color>, animate: Boolean): Modifier {
    if (layers.isEmpty()) return this
    val phases: State<List<Float>>? = if (animate) {
        rememberSlowPhases(MESH_PERIODS_MS)
    } else {
        null
    }
    return drawWithCache {
        val radii = listOf(0.8f, 1.1f, 1.4f).map { it * min(size.width, size.height) }
        onDrawBehind {
            layers.forEachIndexed { index, color ->
                // Each layer keeps its own starting angle — the offsets are what
                // stop the three pools from drifting as one blob.
                val phase = index * 2.1f + (phases?.value?.getOrNull(index) ?: 0f)
                val center = Offset(
                    x = size.width * (0.5f + 0.9f * cos(phase + index * 2.1f)),
                    // The y term must advance by a whole multiple of the wrap's
                    // 2π or the light snaps vertically at every Restart — a
                    // scaled phase (0.8×) ends its cycle at 1.6π, mid-oscillation.
                    y = size.height * (0.5f + 0.9f * sin(phase + index * 1.7f)),
                )
                val radius = radii[index % radii.size]
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = MESH_MAX_ALPHA), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
    }
}
