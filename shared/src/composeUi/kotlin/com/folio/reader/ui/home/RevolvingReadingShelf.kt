@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.folio.reader.manga.MangaBackend
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioGlassScene
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberMotionSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Cached cover plates follow the shared artwork scene's gesture-driven angle. */
@Composable
internal fun RevolvingReadingShelf(
    items: List<ReadingNowItem>,
    controller: RevolvingShelfState,
    backend: MangaBackend?,
    onSelected: (ReadingNowItem) -> Unit,
    onOpenDetails: (ReadingNowItem) -> Unit,
) {
    if (items.isEmpty()) return
    val selectedIndex = controller.selectedIndex
    val latestItems by rememberUpdatedState(items)
    val latestSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(controller) {
        snapshotFlow { controller.selectedIndex }.distinctUntilChanged().collect { index ->
            latestItems.getOrNull(index)?.let(latestSelected)
        }
    }
    val spec = rememberMotionSpec(MotionKey.ReadingShelfTurn)
    val animated = rememberMotionEnabled() && spec.durationMs > 0 && spec.intensity > 0f
    // Settle to the resting pose on the first composition, animation or not. The shelf's
    // `position` starts at the carousel's neutral offset and only reaches `selectedIndex` by
    // easing there, so the *front* card — the one that carries the cover's shared-element key —
    // is still sliding into place while a Library→Home morph is in flight. The morph is a tween
    // to a moving target, so it tracks the card toward the middle of the shelf and then has to
    // snap to wherever the card finally stopped: the sideways hop at the end of the flight.
    // Snapping first means the target is already still when the cover arrives.
    LaunchedEffect(controller) { controller.snapToNearest() }
    val colors = FolioTheme.colors
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val density = LocalDensity.current

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val coverWidth = (maxWidth * .50f).coerceAtMost(176.dp)
            val coverHeight = coverWidth * FolioTokens.coverAspect
            val stageHeight = coverHeight + 28.dp + if (density.fontScale > 1.3f) 48.dp else 0.dp
            val contextWidth = (maxWidth * .30f).coerceAtLeast(96.dp)
            val stepPx = widthPx * .65f
            FolioGlassScene(moving = controller.moving) {
                Box(Modifier.fillMaxWidth().height(stageHeight)) {
                    Layout(
                        modifier = Modifier.fillMaxSize()
                            .draggable(
                                state = rememberDraggableState { delta -> controller.dragBy(-delta / stepPx * direction) },
                                orientation = Orientation.Horizontal,
                                enabled = items.size > 1,
                                onDragStarted = { controller.beginDrag() },
                                onDragStopped = { velocity -> controller.release(-velocity / stepPx * direction, spec, animated) },
                            )
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                                    controller.step(if (event.key == Key.DirectionRight) 1 else -1, spec, animated)
                                    true
                                } else false
                            }
                            .semantics {
                                contentDescription = "Revolving reading shelf"
                                stateDescription = "${selectedIndex + 1} of ${items.size}"
                                collectionInfo = CollectionInfo(1, items.size)
                                if (items.size > 1) customActions = listOf(
                                    CustomAccessibilityAction("Previous cover") { controller.step(-1, spec, animated); true },
                                    CustomAccessibilityAction("Next cover") { controller.step(1, spec, animated); true },
                                )
                            },
                        content = {
                            items.forEachIndexed { index, item -> key(item.shelfKey()) {
                                val isFront = index == selectedIndex
                                Box(Modifier.clickable(role = Role.Button) {
                                    if (isFront) onOpenDetails(item) else controller.select(index, spec, animated)
                                }.semantics(mergeDescendants = true) {
                                    selected = isFront
                                    contentDescription = if (isFront) "Open details for ${item.title}" else "Bring ${item.title} forward"
                                }) {
                                    val shared = if (!isFront) Modifier else Modifier.sharedElementOrNoop(
                                        if (item.kind == HomeItemKind.MANGA) FolioSharedKeys.mangaCover(item.id)
                                        else FolioSharedKeys.bookCover(item.id))
                                    if (item.kind == HomeItemKind.MANGA && backend != null) {
                                        MangaPlate(backend, item.sourceId, item.thumbnailUrl, item.coverPath,
                                            width = coverWidth, elevation = FolioTokens.elevationRaised, modifier = shared)
                                    } else {
                                        FolioCoverPlate(item.coverPath, item.title, item.subtitle,
                                            width = coverWidth, elevation = FolioTokens.elevationRaised, modifier = shared,
                                            overlay = {
                                                com.folio.reader.ui.components.FolioTraceSpine(item.id, item.progress,
                                                    Modifier.align(Alignment.CenterEnd))
                                            })
                                    }
                                }
                            } }
                        },
                    ) { measurables, constraints ->
                        val bookConstraints = Constraints.fixed(coverWidth.roundToPx(), coverHeight.roundToPx())
                        val covers = measurables.map { it.measure(bookConstraints) }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            // Only placement/layers read the angle. The page and decoded images stay put.
                            covers.forEachIndexed { index, cover ->
                                val pose = shelfPose(index, controller.position, items.size, widthPx, animated)
                                val focus = ((pose.depth + .5f) / 1.5f).coerceIn(0f, 1f)
                                // Shared-element lookahead must see the foreground card's
                                // offset, not the centered slot beneath its graphics layer.
                                cover.placeWithLayer(
                                    (constraints.maxWidth - cover.width) / 2 + (pose.x * direction).roundToInt(),
                                    14.dp.roundToPx() - ((1f - focus) * 55.dp.toPx()).roundToInt(),
                                    zIndex = pose.depth,
                                ) {
                                    scaleX = pose.scale
                                    scaleY = pose.scale
                                    rotationY = pose.rotation * direction
                                    // Tilt grows *away* from the front card instead of peaking on it.
                                    // The front card is the one that carries the cover's shared-element
                                    // key, and the shared-transition overlay cannot carry a Z rotation:
                                    // it flies in upright and hands back to this node on the last frame,
                                    // so a tilt here is a 1.5° snap at the end of every Library→Home
                                    // morph. Leaving the front upright costs nothing visually — the
                                    // splay is what reads as a revolving shelf, and the neighbours still
                                    // have it.
                                    rotationZ = if (animated) (-3f * (1f - focus) + pose.rotation * .12f) * direction else 0f
                                    cameraDistance = cover.width * 4f
                                    alpha = .86f + focus * .14f
                                }
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomEnd).width(contextWidth),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val current = items[selectedIndex]
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(current.progress.coerceIn(0f, 1f) * 100).toInt()}",
                                style = FolioTheme.typography.headlineLarge.copy(
                                    fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-1.5).sp),
                                color = colors.onSurface)
                            Text("%", style = FolioTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                        }
                        Text(current.caption?.takeIf { current.kind == HomeItemKind.MANGA } ?: "COMPLETE",
                            style = FolioTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (items.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller.step(-1, spec, animated) }, modifier = Modifier.size(48.dp),
                                enabled = items.size > 2 || selectedIndex > 0) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous cover")
                            }
                            IconButton(onClick = { controller.step(1, spec, animated) }, modifier = Modifier.size(48.dp),
                                enabled = items.size > 2 || selectedIndex < items.lastIndex) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next cover")
                            }
                        }
                    }
                }
            }
        }
    }
}
