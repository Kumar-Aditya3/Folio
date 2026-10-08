package com.folio.reader.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * Where a finger is pressing a glass surface, in that surface's own pixels, and how
 * far the press has settled.
 *
 * This exists because the app's press response was *uniform*: [§16's liquid
 * press](com.folio.reader.ui.components.folioGlassPress) brightened the whole outline
 * by the same amount no matter where the thumb landed, which reads as a control
 * switching state rather than as a surface catching light under a finger. Real glass
 * lights where you touch it. Both glass paths now take one of these — the AGSL film
 * puts a specular hot-spot at the point, and the modifier chain draws the same bloom
 * for every API level and platform the shader does not cover.
 *
 * Both are read in the **draw phase only**. [strength] is deliberately a [State]
 * rather than a `Float`: passing a plain value would make it part of the draw cache's
 * identity and rebuild the shader brush and every path in the surface, per frame, for
 * the whole life of the press.
 *
 * [point] is in this surface's own coordinate space. [PressInteraction.Press] arrives
 * in *window* coordinates, so [Modifier.folioPressFocalOrigin] captures the node's
 * window origin and the two are subtracted — without that, the bloom would sit
 * wherever the surface happens to be on the window rather than under the finger.
 */
@Stable
class FolioPressFocal internal constructor(
    internal val point: MutableState<Offset?>,
    internal val strength: State<Float>,
    internal val origin: MutableState<Offset>,
) {
    /** The press in unit coordinates of a surface of [size], or null when nothing is pressing. */
    internal fun normalized(size: Offset): Offset? {
        val p = point.value ?: return null
        val s = strength.value
        if (s <= 0f || size.x <= 0f || size.y <= 0f) return null
        return Offset(
            ((p.x - origin.value.x) / size.x).coerceIn(0f, 1f),
            ((p.y - origin.value.y) / size.y).coerceIn(0f, 1f),
        )
    }

    /** The same point in pixels, for the Canvas-level bloom that needs no shader. */
    internal fun pixelPoint(): Offset? {
        val p = point.value ?: return null
        if (strength.value <= 0f) return null
        return Offset(p.x - origin.value.x, p.y - origin.value.y)
    }
}

/**
 * Track one interaction source's presses and settle the focal's strength on the same
 * spring the rim flash uses, so a release overshoots slightly and lands like liquid
 * rather than snapping like a checkbox.
 *
 * Reduce-motion collapses the focal to nothing: [strength] never leaves 0, so both
 * glass paths take their resting branch and the surface presses exactly as it did
 * before this existed.
 */
@Composable
fun rememberFolioPressFocal(interactionSource: InteractionSource): FolioPressFocal {
    val motion = rememberMotionEnabled()
    val origin = remember { mutableStateOf(Offset.Zero) }
    val point = remember { mutableStateOf<Offset?>(null) }
    var pressing by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    point.value = interaction.pressPosition
                    pressing = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> pressing = false
                else -> Unit
            }
        }
    }
    val strength = animateFloatAsState(
        targetValue = if (pressing && motion) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 700f),
        label = "folioPressFocal",
    )
    return remember(interactionSource, point, strength, origin) {
        FolioPressFocal(point, strength, origin)
    }
}

/**
 * Publish this node's window origin into [focal] so a window-coordinate press becomes
 * a local one. Applied by both glass paths when a focal is passed, so no call site
 * needs to remember it.
 */
internal fun Modifier.folioPressFocalOrigin(focal: FolioPressFocal): Modifier =
    onGloballyPositioned { coordinates ->
        focal.origin.value = coordinates.positionInWindow()
    }
