@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.folio.reader.ui.reader

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.rememberClockTime
import com.folio.reader.ui.theme.FolioTheme
import kotlin.math.roundToInt

/** Desktop furniture lives outside the native page's permanently reserved gutters. */
internal object WindowsReaderChrome : DesktopReaderChrome {
    @Composable
    override fun TopBar(state: DesktopReaderChromeState, actions: DesktopReaderChromeActions, modifier: Modifier) {
        DesktopReaderFocusShortcut(state.activeTool != null, actions.toggleControls)
        val colors = FolioTheme.colors
        BoxWithConstraints(modifier.fillMaxWidth().height(desktopReaderTopBarHeight)
            .background(colors.surface.copy(alpha = 1f)).testTag("windows-reader-topbar")) {
            val typeScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val labeledTools = maxWidth >= 780.dp * typeScale
            val toolIcons = !labeledTools || maxWidth >= 1040.dp * typeScale
            val showClock = state.showClock && state.controlsVisible && maxWidth >= 680.dp * typeScale
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.controlsVisible) ChromeButton("Back to library", Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = actions.back)
                Text(state.bookTitle, Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = FolioTheme.typography.titleSmall.copy(lineHeight = 20.sp),
                    color = if (state.controlsVisible) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.controlsVisible) {
                    listOf(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved, ReaderTool.Settings).forEach { tool ->
                        ChromeButton(tool.label, tool.icon, showLabel = labeledTools, showIcon = toolIcons,
                            selected = state.activeTool == tool, onClick = { actions.selectTool(tool) })
                    }
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(colors.outlineVariant))
                    ChromeButton(if (state.bookmarked) "Remove bookmark" else "Bookmark this page",
                        if (state.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        selected = state.bookmarked, onClick = actions.bookmark)
                }
                ChromeButton(if (state.controlsVisible) "Focus" else "Show controls",
                    if (state.controlsVisible) Icons.Filled.Fullscreen else Icons.Filled.FullscreenExit,
                    showLabel = true, shortcut = "F", description = if (state.controlsVisible)
                        "Focus mode (F): hide reader controls" else "Show controls (F)", onClick = actions.toggleControls)
                // The optional clock yields before any reading command at large type/narrow widths.
                if (showClock) Text(rememberClockTime(),
                    Modifier.padding(horizontal = 6.dp), style = FolioTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        }
    }

    @Composable
    override fun BottomBar(state: DesktopReaderChromeState, actions: DesktopReaderChromeActions, modifier: Modifier) {
        val colors = FolioTheme.colors
        var preview by remember(state.bookTitle) { mutableStateOf<Float?>(null) }
        // Losing readiness or hiding the strip cancels a drag rather than seeking a new document.
        LaunchedEffect(state.ready, state.controlsVisible, state.showProgress) {
            if (!state.ready || !state.controlsVisible || !state.showProgress) preview = null
        }
        BoxWithConstraints(modifier.fillMaxWidth().height(desktopReaderBottomBarHeight)
            .background(colors.surface.copy(alpha = 1f)).testTag("windows-reader-bottombar")) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(colors.outlineVariant))
            if (!state.controlsVisible) {
                Text(state.locationLabel, Modifier.align(Alignment.Center),
                    style = FolioTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant, maxLines = 1)
                return@BoxWithConstraints
            }
            val detailedProgress = maxWidth >= 1000.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val shownFraction = (preview ?: state.fraction).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            val percent = "${(shownFraction * 100).roundToInt()}%"
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChromeButton(if (state.paged) "Previous page" else "Previous section", Icons.AutoMirrored.Filled.ArrowBack,
                    description = "Previous ${if (state.paged) "page" else "section"} (Left arrow / Page Up)",
                    enabled = state.ready && state.canGoBack, onClick = actions.previous)
                Text(state.locationLabel, Modifier.widthIn(max = 140.dp),
                    style = FolioTheme.typography.labelLarge.copy(lineHeight = 18.sp, fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChromeButton(if (state.paged) "Next page" else "Next section", Icons.AutoMirrored.Filled.ArrowForward,
                    description = "Next ${if (state.paged) "page" else "section"} (Right arrow / Page Down / Space)",
                    enabled = state.ready && state.canGoForward, onClick = actions.next)
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(colors.outlineVariant))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (preview != null) "Release to move" else state.chapterTitle.ifBlank { "Reading position" },
                            Modifier.weight(1f), style = FolioTheme.typography.labelLarge.copy(lineHeight = 16.sp),
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.showProgress) Text(if (preview == null && detailedProgress && state.progressLabel.isNotBlank() && state.progressLabel != percent)
                            "${state.progressLabel} · $percent" else percent,
                            Modifier.semantics { contentDescription = "Book progress: $percent. ${state.progressLabel}" },
                            style = FolioTheme.typography.labelLarge.copy(lineHeight = 16.sp, fontFeatureSettings = "tnum"),
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.showProgress) ReaderScrubber(
                        value = preview ?: state.fraction,
                        enabled = state.ready,
                        onPreview = { preview = it },
                        onCommit = { target ->
                            preview = null
                            if (state.ready && state.controlsVisible) actions.seek(target)
                        },
                    )
                }
                if (state.returnLabel != null && actions.returnToReading != null) ChromeButton("Return to reading",
                    Icons.AutoMirrored.Filled.Undo, description = "Return to ${state.returnLabel}",
                    onClick = actions.returnToReading)
            }
        }
    }

    @Composable
    override fun Workspace(active: ReaderTool, tools: List<ReaderTool>, location: String,
        onSelect: (ReaderTool) -> Unit, onDismiss: () -> Unit, content: @Composable (ReaderTool) -> Unit) {
        val colors = FolioTheme.colors
        val focus = LocalFocusManager.current
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface.copy(alpha = 1f)).testTag("windows-reader-inspector")) {
            val columns = if (tools.size <= 4 || LocalDensity.current.fontScale > 1.25f || maxWidth < 340.dp) 2 else 3
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active.label, style = FolioTheme.typography.titleLarge, color = colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(location, style = FolioTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ChromeButton("Close reader tools", Icons.Filled.Close, description = "Close reader tools (Esc)", onClick = onDismiss)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                Column(Modifier.fillMaxWidth().selectableGroup().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tools.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { tool ->
                                ChromeButton(tool.label, tool.icon, selected = active == tool, role = Role.Tab,
                                    modifier = Modifier.weight(1f), showLabel = true, showIcon = false, expand = true,
                                    description = "${tool.label}: ${tool.description}", onClick = {
                                        if (active != tool) { focus.clearFocus(); onSelect(tool) }
                                    })
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                Box(Modifier.weight(1f).fillMaxWidth().clip(androidx.compose.ui.graphics.RectangleShape)) {
                    CompositionLocalProvider(LocalReaderWorkspace provides true) { content(active) }
                }
            }
        }
    }
}

@Composable
private fun ChromeButton(label: String, icon: ImageVector, modifier: Modifier = Modifier,
    showLabel: Boolean = false, showIcon: Boolean = true, enabled: Boolean = true, selected: Boolean? = null,
    shortcut: String? = null, description: String = label, role: Role = Role.Button, expand: Boolean = false, onClick: () -> Unit) {
    val colors = FolioTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val fill = when {
        enabled && (pressed || selected == true) -> colors.primaryContainer.copy(alpha = 1f)
        enabled && hovered -> colors.surfaceVariant.copy(alpha = 1f)
        else -> Color.Transparent
    }
    val ink = if (!enabled) colors.onSurface.copy(alpha = .38f)
        else if (selected == true) colors.onPrimaryContainer else colors.onSurface
    TooltipArea(modifier = modifier, delayMillis = 650,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        tooltip = {
            Surface(color = colors.inverseSurface.copy(alpha = 1f), contentColor = colors.inverseOnSurface,
                shape = RoundedCornerShape(4.dp)) {
                Text(description, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = FolioTheme.typography.bodySmall, maxLines = 3)
            }
        }) {
        Row(Modifier.height(44.dp).then(if (expand) Modifier.fillMaxWidth() else if (showLabel) Modifier else Modifier.width(40.dp))
            .clip(shape).background(fill).border(1.dp, if (focused) colors.primary else Color.Transparent, shape)
            .hoverable(interactions, enabled).clickable(interactionSource = interactions, indication = null,
                enabled = enabled, role = role, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (selected != null) this.selected = selected
                if (shortcut != null) stateDescription = "Shortcut $shortcut"
            }.padding(horizontal = if (showLabel) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            if (showIcon) Icon(icon, null, Modifier.size(20.dp), tint = ink)
            if (showLabel) Text(label, color = ink, style = FolioTheme.typography.labelLarge.copy(lineHeight = 20.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (shortcut != null) Text(shortcut, Modifier.padding(start = 2.dp), color = colors.onSurfaceVariant,
                style = FolioTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Pointer previews never leave this composition; only an unconsumed release writes a position. */
@Composable
private fun ReaderScrubber(value: Float, enabled: Boolean, onPreview: (Float?) -> Unit, onCommit: (Float) -> Unit) {
    val colors = FolioTheme.colors
    val fraction = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    val current by rememberUpdatedState(fraction)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val inset = with(LocalDensity.current) { 10.dp.toPx() }
    val focusRequester = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().height(20.dp).testTag("windows-reader-scrubber")
        .semantics {
            contentDescription = "Reading position"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            stateDescription = "${(fraction * 100).roundToInt()}%"
            if (!enabled) disabled() else setProgress { target ->
                if (!target.isFinite()) false else { commit(target.coerceIn(0f, 1f)); true }
            }
        }.onKeyEvent { event ->
            if (!enabled || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) false else {
                val target = when (event.key) {
                    Key.DirectionLeft -> current + if (rtl) .01f else -.01f
                    Key.DirectionRight -> current + if (rtl) -.01f else .01f
                    Key.MoveHome -> 0f
                    Key.MoveEnd -> 1f
                    else -> null
                }
                if (target == null) false else {
                    if (event.type == KeyEventType.KeyUp) commit(target.coerceIn(0f, 1f))
                    true
                }
            }
        }.hoverable(interactions, enabled).focusRequester(focusRequester).focusable(enabled, interactions)
        .pointerInput(enabled, rtl, inset) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                fun at(x: Float): Float {
                    val position = ((x - inset) / (size.width - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return if (rtl) 1f - position else position
                }
                down.consume()
                focusRequester.requestFocus()
                preview(at(down.position.x))
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (change.changedToUp()) {
                            change.consume()
                            commit(at(change.position.x))
                            break
                        }
                        if (!change.pressed) break
                        change.consume()
                        preview(at(change.position.x))
                    }
                } finally { preview(null) }
            }
        }.drawBehind {
            val left = inset.coerceAtMost(size.width / 2f)
            val right = (size.width - inset).coerceAtLeast(left)
            val start = if (rtl) right else left
            val end = if (rtl) left else right
            val thumb = start + (end - start) * fraction
            val y = size.height / 2f
            val accent = if (enabled) colors.accentProgress else colors.outline
            drawLine(colors.outlineVariant, Offset(left, y), Offset(right, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(accent, Offset(start, y), Offset(thumb, y), 3.dp.toPx(), StrokeCap.Round)
            if (focused) drawCircle(colors.primary, 8.dp.toPx(), Offset(thumb, y))
            drawCircle(colors.surface.copy(alpha = 1f), if (hovered || focused) 6.dp.toPx() else 5.dp.toPx(), Offset(thumb, y))
            drawCircle(accent, if (hovered || focused) 4.dp.toPx() else 3.dp.toPx(), Offset(thumb, y))
        })
}
