package com.folio.reader.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.sin
import kotlin.math.PI
import com.folio.reader.ui.theme.rememberMotionAnimation
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberMotionSpec

/** One functional glass target, shared by book and manga actions and Home. */
@Composable
fun FolioActionDock(title: String, subtitle: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, accent: Color = FolioTheme.colors.primary,
    progress: Float? = null, accessibilityLabel: String = title, enabled: Boolean = true) {
    val colors = FolioTheme.colors
    val signal = folioSignalAccent(accent)
    val shape = FolioMaterialShapes.dock
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val motion = rememberMotionEnabled() && !com.folio.reader.ui.theme.LocalMotion.current.reduced
    val spec = rememberMotionSpec(MotionKey.ShelfFadeSwap)
    val duration = if (motion && spec.intensity > 0f) spec.durationMs.toInt() else 0
    val sheen = com.folio.reader.ui.theme.folioSheenGain()
    val feedback = rememberMotionSpec(MotionKey.ProgressBars)
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(title, subtitle, motion, feedback) {
        if (motion && feedback.intensity > 0f && feedback.durationMs > 0L) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(feedback.durationMs.toInt(), easing = feedback.easing))
        } else sweep.snapTo(1f)
    }
    var point by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(interactions) { interactions.interactions.collect { event ->
        if (event is PressInteraction.Press) point = event.pressPosition
    } }
    val response = animateFloatAsState(if (pressed && motion) 1f else 0f,
        animationSpec = rememberMotionAnimation(MotionKey.PressFocal), label = "actionDockPress")
    Column(modifier.fillMaxWidth().heightIn(min = if (progress == null) 64.dp else 80.dp)
        .graphicsLayer { scaleX = 1f - response.value * .018f; scaleY = scaleX; alpha = if (enabled) 1f else .5f }
        .folioReadingDockGlass(shape, signal).clip(shape).folioSignalEdge().drawWithCache {
            onDrawWithContent {
                drawContent()
                val phase = sweep.value
                if (phase > 0f && phase < 1f) {
                    val x = size.width * (if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) 1f - phase else phase)
                    val strength = sin(phase * PI).toFloat() * .16f * sheen
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent,
                        signal.copy(alpha = strength), Color.Transparent),
                        startX = x - size.width * .18f, endX = x + size.width * .18f))
                }
                point?.let { at -> if (response.value > .001f) drawCircle(
                    Brush.radialGradient(listOf(signal.copy(alpha = .30f * response.value * sheen), Color.Transparent),
                        center = at, radius = size.width * .65f), radius = size.width * .65f, center = at) }
            }
        }.clickable(enabled = enabled, interactionSource = interactions, indication = null, role = Role.Button,
            onClickLabel = title, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
        .padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = FolioTheme.typography.titleMedium, color = colors.onSurface)
                if (subtitle.isNotBlank()) AnimatedContent(subtitle, transitionSpec = {
                    fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2))
                }, label = "actionDockCaption") {
                    Text(it, style = FolioTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.size(40.dp).background(signal.copy(alpha = .17f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.onSurface,
                    modifier = Modifier.size(22.dp).graphicsLayer { translationX = response.value * 3.dp.toPx() })
            }
        }
        progress?.let { FolioProgressBar(it, color = colors.accentProgress, track = colors.progressTrack) }
    }
}
