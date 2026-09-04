package com.folio.reader.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere

/**
 * The app's one slider. Material3 still owns the gesture handling, keyboard
 * stepping and accessibility semantics — only the thumb and track are ours, so
 * every existing behaviour (drag, tap-to-position, stepped snapping, TalkBack
 * value announcements) survives untouched.
 *
 * The look is deliberately quiet: a 6dp pill track inlaid into the surface, a
 * compact knob ringed in the page's own colour so it never dissolves into the
 * track, and a halo that only blooms while the knob is held. No tick marks even
 * when [steps] is set — the value readout above the track is the affordance, and
 * ticks turn a 900-unit weight scale into visual noise.
 */
private val TrackHeight = 6.dp
private val KnobSize = 16.dp
private val KnobBox = 26.dp
private val HaloResting = 0.dp
private val HaloActive = 26.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolioSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = FolioTheme.colors.primary,
    background: Color = FolioTheme.colors.surface,
    inactive: Color? = null,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val interaction = rememberFolioInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val engaged = (pressed || dragged) && enabled

    // The active fill must stay visible against whatever it sits on, which for
    // the manga chrome is a black overlay rather than the themed surface. 3:1 is
    // the non-text tier — the track is a graphical object, not a label.
    val active = legibleOn(accent, background, colors.onSurface, minRatio = 3.0)
        .let { if (enabled) it else lerp(it, atmos.sunkenFill, 0.55f) }
    val inactiveTrack = inactive ?: if (enabled) {
        lerp(atmos.sunkenFill, colors.outlineVariant, 0.35f)
    } else {
        atmos.sunkenFill.copy(alpha = 0.5f)
    }

    val halo by animateDpAsState(
        targetValue = if (engaged) HaloActive else HaloResting,
        animationSpec = spring(stiffness = 900f),
        label = "sliderHalo",
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = { _: SliderState ->
            FolioSliderKnob(
                accent = active,
                ring = background,
                halo = halo,
                elevation = if (enabled) 3.dp * atmos.shadowScale else 0.dp,
            )
        },
        track = { _: SliderState ->
            val span = valueRange.endInclusive - valueRange.start
            val fraction =
                if (span <= 0f) 0f
                else ((value - valueRange.start) / span).coerceIn(0f, 1f)
            FolioSliderTrack(fraction = fraction, active = active, inactive = inactiveTrack)
        },
    )
}

@Composable
private fun FolioSliderKnob(accent: Color, ring: Color, halo: Dp, elevation: Dp) {
    Box(
        modifier = Modifier.size(KnobBox),
        contentAlignment = Alignment.Center,
    ) {
        if (halo > 0.dp) {
            Box(
                modifier = Modifier
                    .size(halo)
                    .background(accent.copy(alpha = 0.16f), CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(KnobSize)
                .shadow(elevation, CircleShape)
                .background(accent, CircleShape)
                .border(2.dp, ring, CircleShape)
        )
    }
}

@Composable
private fun FolioSliderTrack(fraction: Float, active: Color, inactive: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackHeight)
            .background(inactive, CircleShape)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(active, CircleShape)
            )
        }
    }
}

/**
 * A slider with its own label and value readout — the shape every settings row
 * wants. The label sits in `onSurfaceVariant`, the readout in a legibility-
 * guarded accent, so the number never disappears into a pastel surface the way
 * a raw `primary` readout does.
 *
 * [leading] and [trailing] host stepper buttons for the rows that have them.
 */
@Composable
fun FolioSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = FolioTheme.colors.primary,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val readout = rememberLegibleAccent(accent)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = FolioTheme.typography.labelLarge,
                color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = valueLabel,
                style = FolioTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) readout else readout.copy(alpha = 0.5f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            leading?.invoke()
            FolioSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                accent = accent,
            )
            trailing?.invoke()
        }
    }
}
