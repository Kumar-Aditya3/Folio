package com.folio.reader.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** §13.4: cycle lengths within the 18–30s band, distinct so they never beat in sync. */
private val MESH_PERIODS_MS = listOf(21_000, 27_000, 18_000)
private const val MESH_MAX_ALPHA = 0.18f

/**
 * §13.4 drifting gradient mesh: three large, low-alpha radial gradients behind
 * the hero, their centres drifting on slow independent cycles. Draw-phase state
 * reads keep this one drawing pass — no per-frame recomposition. Reduce-motion
 * freezes the centres (same mesh, zero animation); the lazy list disposing the
 * hero offscreen stops the clocks entirely. Radius scales with the shorter edge
 * and centres can sit outside the bounds, so no layer ever shows a visible edge.
 */
@Composable
internal fun Modifier.heroMesh(layers: List<Color>, animate: Boolean): Modifier {
    if (layers.isEmpty()) return this
    val angles: List<State<Float>>? = if (animate) {
        val transition = rememberInfiniteTransition(label = "heroMesh")
        MESH_PERIODS_MS.mapIndexed { index, period ->
            transition.animateFloat(
                initialValue = index * 2.1f,
                targetValue = index * 2.1f + (2f * PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = period, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "heroMesh$index",
            )
        }
    } else {
        null
    }
    return drawWithCache {
        val radii = listOf(0.8f, 1.1f, 1.4f).map { it * min(size.width, size.height) }
        onDrawBehind {
            layers.forEachIndexed { index, color ->
                val phase = angles?.get(index)?.value ?: (index * 2.1f)
                val center = Offset(
                    x = size.width * (0.5f + 0.9f * cos(phase + index * 2.1f)),
                    y = size.height * (0.5f + 0.9f * sin(phase * 0.8f + index * 1.7f)),
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
