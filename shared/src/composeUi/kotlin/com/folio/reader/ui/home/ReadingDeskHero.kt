package com.folio.reader.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioActionDock
import com.folio.reader.ui.components.FolioArtworkGlassScene
import com.folio.reader.ui.components.folioReadingDockGlass
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberMotionSpec

/** The selected title and its actions follow the front of the revolving shelf. */
@Composable
internal fun ReadingDeskHero(
    items: List<ReadingNowItem>,
    item: ReadingNowItem?,
    backend: MangaBackend?,
    minimumHeight: Dp,
    tint: Color?,
    ambientColor: Boolean = true,
    onSelected: (ReadingNowItem) -> Unit,
    onOpenDetails: (ReadingNowItem) -> Unit,
    onContinue: (ReadingNowItem) -> Unit,
    onExplore: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val colors = FolioTheme.colors
    val accent = colors.primary
    val motion = rememberMotionEnabled()
    val captionSpec = rememberMotionSpec(MotionKey.ReadingShelfTurn)
    val captionMotion = motion && captionSpec.durationMs > 0 && captionSpec.intensity > 0f
    val captionDuration = if (captionMotion) captionSpec.durationMs.toInt() else 0
    val turnSpec = rememberMotionSpec(MotionKey.ReadingShelfTurn)
    val turnMotion = motion && turnSpec.durationMs > 0 && turnSpec.intensity > 0f
    val keys = items.map { it.shelfKey() }
    val scope = rememberCoroutineScope()
    val controller = remember(keys) {
        RevolvingShelfState(items.size, keys.indexOf(item?.shelfKey()).coerceAtLeast(0), scope)
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    FolioArtworkGlassScene(controller.moving) {
        Box(Modifier.fillMaxWidth()) {
            if (item != null) ReadingDeskArtwork(items, controller, turnMotion, ambientColor, Modifier.matchParentSize())
            Column(
                Modifier.fillMaxWidth().heightIn(min = minimumHeight.coerceAtMost(640.dp))
                    .padding(horizontal = FolioTokens.gutter, vertical = 18.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (item == null) {
                    FolioEyebrow("Your reading desk", accent = accent)
                    Text("Your next story is waiting", style = FolioTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center, color = colors.onSurface)
                    Button(onClick = onOpenLibrary) { Text("Browse your library") }
                } else {
                    AnimatedContent(
                        targetState = item,
                        contentKey = { it.shelfKey() },
                        transitionSpec = {
                            ((fadeIn(tween(captionDuration, easing = captionSpec.easing)) +
                                slideInVertically(tween(captionDuration, easing = captionSpec.easing)) {
                                    if (captionMotion) it / 10 else 0
                                }) togetherWith (fadeOut(tween(captionDuration / 2)) +
                                slideOutVertically(tween(captionDuration)) { if (captionMotion) -it / 10 else 0 }))
                                .using(SizeTransform(clip = false))
                        },
                        label = "shelfCaption",
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) { selected ->
                        Column(Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FolioEyebrow("In your hands / ${controller.selectedIndex + 1}", accent = accent)
                            Text(selected.title, style = FolioTheme.typography.headlineLarge.copy(
                                fontSize = 44.sp, lineHeight = 45.sp, letterSpacing = (-1.8).sp),
                                color = colors.onSurface, textAlign = TextAlign.Start,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (selected.subtitle.isNotBlank()) Text(selected.subtitle,
                                style = FolioTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    RevolvingReadingShelf(items, controller, backend, onSelected, onOpenDetails)
                    if (item.kind == HomeItemKind.BOOK) {
                        com.folio.reader.ui.components.FolioReadingTrace(item.id, item.title, item.progress)
                    }
                    FolioActionDock("Resume",
                        listOfNotNull(item.caption?.takeIf { item.kind == HomeItemKind.MANGA },
                            "${(item.progress.coerceIn(0f, 1f) * 100).toInt()}% complete").joinToString(" · "),
                        onClick = { onContinue(items.getOrNull(controller.selectedIndex) ?: item) }, modifier = Modifier.widthIn(max = 480.dp),
                        accent = accent, progress = item.progress,
                        accessibilityLabel = "Continue reading ${item.title}")
                }
                TextButton(onClick = onExplore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Scroll to your library", style = FolioTheme.typography.labelMedium)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    }
}
