package com.folio.reader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.folioReaderGlass
import com.folio.reader.ui.components.folioSheetDragToDismiss
import com.folio.reader.ui.components.FolioBackHandler
import com.folio.reader.ui.theme.*

internal enum class ReaderTool(val label: String, val icon: ImageVector, val description: String) {
    Listen("Listen", Icons.Filled.Headphones, "Read this book aloud"),
    Contents("Contents", Icons.AutoMirrored.Filled.Toc, "The shape of this book"),
    Pages("Pages", Icons.Filled.GridView, "A map of this chapter"),
    Search("Search", Icons.Filled.Search, "Find a passage in this book"),
    Saved("Saved", Icons.AutoMirrored.Filled.Notes, "Passages and notes you kept"),
    Echoes("Echoes", Icons.Filled.AutoAwesome, "Follow a thought across your library"),
    Settings("Settings", Icons.Filled.Tune, "Make room for your way of reading"),
}

internal val LocalReaderWorkspace = compositionLocalOf { false }

internal data class ReaderWorkspaceLayout(val dockWidth: Dp, val sheetHeight: Dp) {
    val docked: Boolean get() = dockWidth > 0.dp
}

/** Reserve a readable page before allocating a context pane, including at larger type sizes. */
internal fun readerWorkspaceLayout(width: Dp, height: Dp, fontScale: Float = 1f): ReaderWorkspaceLayout {
    val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f
    val pane = minOf(416.dp * scale.coerceAtMost(1.35f), width * .36f).coerceAtLeast(352.dp)
    val dock = if (width >= 480.dp * scale + pane + 24.dp && height >= 360.dp) pane else 0.dp
    val available = (height - 24.dp).coerceAtLeast(0.dp)
    val sheet = if (height < 480.dp) available else minOf(640.dp * scale.coerceAtMost(1.25f), height * .74f, available)
    return ReaderWorkspaceLayout(dock, sheet)
}

/** Working text needs a denser coat than a short command strip. The same slider still controls
 * the whole range, with an exact solid endpoint; real manga diffusion remains underneath. */
internal fun readerWorkspaceFill(alpha: Float): Float {
    val value = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else .9f
    return value + .6f * value * (1f - value)
}

/** One shell: a reachable bottom instrument on phones, a page-adjacent pane on wide windows. */
@Composable
internal fun ReaderWorkspace(
    active: ReaderTool?,
    tools: List<ReaderTool>,
    location: String,
    onSelect: (ReaderTool) -> Unit,
    onDismiss: () -> Unit,
    layout: ReaderWorkspaceLayout? = null,
    content: @Composable (ReaderTool) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close = { focus.clearFocus(); keyboard?.hide(); onDismiss() }
    FolioBackHandler(enabled = active != null, onBack = close)
    val fade = rememberMotionAnimation<Float>(MotionKey.ReaderSidePanels)
    val slide = rememberMotionAnimation<IntOffset>(MotionKey.ReaderSidePanels)
    // Retain the outgoing content through dismissal, without keeping a dismissed pane interactive.
    var last by remember { mutableStateOf(active ?: tools.first()) }
    SideEffect { if (active != null) last = active }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = layout ?: readerWorkspaceLayout(maxWidth, maxHeight, LocalDensity.current.fontScale)
        AnimatedVisibility(active != null && !geometry.docked, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(FolioTheme.colors.onSurface.copy(alpha = .18f))
                .clickable(onClick = close))
        }
        val paneWidth = if (geometry.docked) (geometry.dockWidth - 16.dp).coerceAtLeast(0.dp)
            else minOf(640.dp, (maxWidth - 16.dp).coerceAtLeast(0.dp))
        // No `imePadding()` here. The sheet is anchored top and bottom, and the IME is absorbed
        // by the tool content below (see the AnimatedContent). Padding the whole sheet with it
        // made the sheet's bounds ride the keyboard's inset animation — the reader taps Search,
        // the field demands focus, the keyboard opens, and the header, the tabs and the search
        // field all bob up and down while the inset value walks to its resting height.
        AnimatedVisibility(active != null,
            modifier = Modifier.align(if (geometry.docked) Alignment.CenterEnd else Alignment.BottomCenter)
                .statusBarsPadding().navigationBarsPadding().padding(8.dp),
            enter = (if (geometry.docked) slideInHorizontally(slide) { it / 3 }
                else if (active == ReaderTool.Contents || active == ReaderTool.Pages) slideInVertically(slide) { it / 4 }
                else EnterTransition.None) + fadeIn(fade),
            exit = (if (geometry.docked) slideOutHorizontally(slide) { it / 3 }
                else if (last == ReaderTool.Contents || last == ReaderTool.Pages) slideOutVertically(slide) { it / 4 }
                else ExitTransition.None) + fadeOut(fade)) {
            val selected = active ?: last
            Column(Modifier.width(paneWidth).then(if (geometry.docked) Modifier.fillMaxHeight()
                else Modifier.height(geometry.sheetHeight))
                .then(if (active == null) Modifier.clearAndSetSemantics { } else Modifier)
                .pointerInput(active) {
                    if (active == null) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
                .folioSheetDragToDismiss(close)
                .folioReaderGlass(FolioSoftShape(24.dp), readerWorkspaceFill(FolioTheme.readerPanelVeilAlpha))) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selected.label, style = FolioTheme.typography.headlineSmall,
                            color = FolioTheme.colors.onSurface)
                        Text(location, style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = close) {
                        Icon(Icons.Filled.Close, "Close reader tools", tint = FolioTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                    }
                }
                val compactTabs = paneWidth < 344.dp || LocalDensity.current.fontScale > 1.25f
                val tabWidth = (paneWidth - 16.dp) / tools.size
                val tabOffset by animateDpAsState(8.dp + tabWidth * tools.indexOf(selected).coerceAtLeast(0) + (tabWidth - 24.dp) / 2,
                    rememberMotionAnimation<Dp>(MotionKey.ReaderChromeToggle), label = "reader-tool-marker")
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    tools.forEach { tool ->
                        val chosen = tool == selected
                        Column(Modifier.weight(1f).selectable(chosen, role = Role.Tab,
                            onClick = { if (!chosen) { focus.clearFocus(); keyboard?.hide(); onSelect(tool) } })
                            .heightIn(min = 48.dp).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(tool.icon, tool.label, Modifier.size(19.dp),
                                tint = if (chosen) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant)
                            if (!compactTabs) Text(tool.label, style = FolioTheme.typography.labelSmall,
                                color = if (chosen) FolioTheme.colors.onSurface else FolioTheme.colors.onSurfaceVariant,
                                maxLines = 1, modifier = Modifier.padding(top = 5.dp), overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Box(Modifier.offset(x = tabOffset).width(24.dp).height(2.dp).background(FolioTheme.colors.primary))
                Box(Modifier.fillMaxWidth().height(.75.dp).background(FolioTheme.colors.outlineVariant))
                CompositionLocalProvider(LocalReaderWorkspace provides true) {
                    // No `imePadding()` here. The window is edge-to-edge (`enableEdgeToEdge()`), so
                    // the keyboard never resizes the window and this inset is the only thing that
                    // knows about it — but padding *this* box changes the body's height, and every
                    // tool whose layout is positioned against that box shifts for the length of the
                    // keyboard animation. Opening Search bounced everything once, closing it bounced
                    // everything again, in whichever panel happened to be showing.
                    //
                    // Each tool now takes the inset as bottom content padding on its own scroller
                    // (see `readerImeBottomPadding()`), so items keep their y and only the scrollable
                    // range grows. A tool that forgets it just scrolls under the keyboard instead of
                    // moving — a far better failure than a bounce.
                    AnimatedContent(selected, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                        modifier = Modifier.fillMaxWidth().weight(1f), label = "reader-tool") { content(it) }
                }
            }
        }
    }
}

/** Standalone previews keep their frame; embedded tools inherit the workspace's single plane. */
@Composable
internal fun Modifier.readerPanelBody(width: Dp = 336.dp): Modifier =
    if (LocalReaderWorkspace.current) fillMaxSize()
    else fillMaxHeight().width(width).folioReaderGlass(FolioSoftShape(24.dp), readerWorkspaceFill(FolioTheme.readerPanelVeilAlpha))

@Composable
internal fun ReaderLocationLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = FolioTheme.colors.onSurfaceVariant,
        style = FolioTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace,
            fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The keyboard's height, to be spent as **bottom content padding** on a tool's scroller.
 *
 * The window is edge-to-edge, so the IME never resizes it; this is the only place the inset is
 * known. Padding a tool's *container* with it would change the body's height and move every row
 * that is positioned against that box — that was the bounce when Search opened and again when it
 * closed. As content padding the viewport keeps its size, the rows keep their y, and the only thing
 * that changes is how much empty scrollable space sits below the last item.
 */
@Composable
internal fun readerImeBottomPadding(): Dp =
    WindowInsets.ime.asPaddingValues().calculateBottomPadding()
