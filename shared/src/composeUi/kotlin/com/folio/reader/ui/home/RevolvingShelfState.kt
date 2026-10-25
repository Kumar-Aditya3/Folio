package com.folio.reader.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folio.reader.ui.theme.MotionSpec
import com.folio.reader.ui.theme.toAnimationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun ReadingNowItem.shelfKey(): String = "${kind.name}:$id"

internal fun revolvingShelfItems(items: List<ReadingNowItem>): List<ReadingNowItem> =
    items.distinctBy { it.shelfKey() }.take(3)

internal fun shelfIndex(position: Float, count: Int): Int {
    if (count <= 1 || !position.isFinite()) return 0
    val index = position.roundToInt()
    return if (count == 2) index.coerceIn(0, 1) else ((index % count) + count) % count
}

/** Two titles form an open pair; three form a continuous revolving shelf. */
internal fun shelfRelative(index: Int, position: Float, count: Int): Float {
    if (count <= 1 || !position.isFinite()) return 0f
    val relative = index - position
    return if (count == 2) relative else
        ((relative + count / 2f) % count + count) % count - count / 2f
}

internal fun shelfDestination(position: Float, index: Int, count: Int): Float = when {
    count <= 1 -> 0f
    count == 2 -> index.coerceIn(0, 1).toFloat()
    else -> position + shelfRelative(index, position, count)
}

/** A short velocity projection advances naturally without flinging through many revolutions. */
internal fun shelfReleaseTarget(position: Float, velocity: Float, count: Int): Float {
    if (count <= 1 || !position.isFinite()) return 0f
    val projected = position + (velocity.takeIf { it.isFinite() } ?: 0f).times(.12f).coerceIn(-.8f, .8f)
    val target = projected.roundToInt().toFloat()
    return if (count == 2) target.coerceIn(0f, 1f) else target
}

internal data class ShelfPose(val x: Float, val scale: Float, val rotation: Float, val depth: Float)

/** Offset foreground, softened toward the center, with upcoming covers in the trailing field. */
internal fun shelfPose(index: Int, position: Float, count: Int, width: Float, depthEnabled: Boolean): ShelfPose {
    val relative = shelfRelative(index, position, count)
    val phase = relative * 120f
    val radians = phase * (PI / 180).toFloat()
    val depth = cos(radians)
    val focus = ((depth + .5f) / 1.5f).coerceIn(0f, 1f)
    return ShelfPose(
        x = width * (.22f - .35f * focus + sin(radians) * .06f),
        scale = .58f + .42f * focus,
        rotation = if (depthEnabled) (phase * .22f).coerceIn(-36f, 36f) else 0f,
        depth = depth,
    )
}

/** One short-lived animation job. Gesture deltas never launch coroutines. */
@Stable
internal class RevolvingShelfState(val count: Int, initialIndex: Int, private val scope: CoroutineScope) {
    var position by mutableFloatStateOf(initialIndex.coerceIn(0, (count - 1).coerceAtLeast(0)).toFloat())
        private set
    var moving by mutableStateOf(false)
        private set
    val selectedIndex by derivedStateOf { shelfIndex(position, count) }
    private var targetPosition = position
    private var job: Job? = null
    private var generation = 0

    fun stop() {
        generation++
        job?.cancel()
        moving = false
    }

    fun snapToNearest() {
        stop()
        position = shelfIndex(position, count).toFloat()
        targetPosition = position
    }

    fun beginDrag() {
        generation++
        job?.cancel()
        targetPosition = position
        moving = count > 1
    }

    fun dragBy(delta: Float) {
        if (count <= 1 || !delta.isFinite()) return
        position = (position + delta).let { if (count == 2) it.coerceIn(0f, 1f) else it }
        targetPosition = position
    }

    fun release(velocity: Float, spec: MotionSpec, animated: Boolean) =
        turnTo(shelfReleaseTarget(position, velocity, count), velocity, spec, animated)

    fun select(index: Int, spec: MotionSpec, animated: Boolean) =
        turnTo(shelfDestination(position, index, count), 0f, spec, animated)

    fun step(direction: Int, spec: MotionSpec, animated: Boolean) {
        val target = targetPosition.roundToInt() + direction
        turnTo(if (count == 2) target.coerceIn(0, 1).toFloat() else target.toFloat(), 0f, spec, animated)
    }

    private fun turnTo(target: Float, velocity: Float, spec: MotionSpec, animated: Boolean) {
        val run = ++generation
        job?.cancel()
        targetPosition = if (count <= 1) 0f else target
        if (!animated || spec.durationMs <= 0 || spec.intensity <= 0f || targetPosition == position) {
            position = shelfIndex(targetPosition, count).toFloat()
            targetPosition = position
            moving = false
            return
        }
        moving = true
        job = scope.launch {
            try {
                animate(position, targetPosition, initialVelocity = velocity.coerceIn(-8f, 8f),
                    animationSpec = spec.toAnimationSpec<Float>()) { value, _ ->
                    position = if (count == 2) value.coerceIn(0f, 1f) else value
                }
                position = shelfIndex(targetPosition, count).toFloat()
                targetPosition = position
            } finally {
                if (generation == run) moving = false
            }
        }
    }
}
