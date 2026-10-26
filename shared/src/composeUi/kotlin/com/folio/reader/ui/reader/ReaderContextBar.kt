package com.folio.reader.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.render.ReaderSelectionBounds
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.*

@Composable
internal fun AnimatedReaderSelectionTools(
    visible: Boolean,
    modifier: Modifier = Modifier,
    origin: TransformOrigin = TransformOrigin.Center,
    content: @Composable () -> Unit,
) {
    val resolved = LocalMotion.current
    val motion = resolved[MotionKey.SelectionToolbar]
    if (resolved.reduced || (motion.durationMs <= 0L && motion.spring == null)) {
        if (visible) Box(modifier) { content() }
        return
    }
    val timing = rememberMotionAnimation<Float>(MotionKey.SelectionToolbar)
    val scale = 1f - .04f * motion.intensity
    AnimatedVisibility(visible, modifier.then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
        enter = fadeIn(timing) + scaleIn(timing, initialScale = scale, transformOrigin = origin),
        exit = fadeOut(timing) + scaleOut(timing, targetScale = scale, transformOrigin = origin)) {
        Box(Modifier.pointerInput(visible) {
            if (!visible) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }) { content() }
    }
}

/** Selection owns a small contextual strip; it never summons all navigation chrome. */
@Suppress("DEPRECATION")
@Composable
internal fun ReaderSelectionBar(
    text: String,
    onHighlight: () -> Unit,
    onNote: (() -> Unit)?,
    onEchoes: (() -> Unit)?,
    onExport: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onListen: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberFolioHaptics()
    var more by remember { mutableStateOf(false) }
    ReaderChromeGroup(verticalInset = 0.dp) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { haptics.play(FolioHaptic.Confirm); onHighlight() }) {
                Text("Keep", color = FolioTheme.colors.onSurface)
            }
            if (onNote != null) IconButton(onClick = onNote) {
                Icon(Icons.Filled.EditNote, "Add note to selection", Modifier.size(20.dp), FolioTheme.colors.onSurface)
            }
            if (onExport != null) TextButton(onClick = onExport) {
                Text("Export", color = FolioTheme.colors.onSurface)
            }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreHoriz, "More selection actions", Modifier.size(19.dp), FolioTheme.colors.onSurface) }
                com.folio.reader.ui.components.FolioMenu(more, { more = false }, readerMaterial = true) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Copy") }, onClick = { more = false; clipboard.setText(AnnotatedString(text)); onDismiss() })
                    if (onEchoes != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Find echoes") }, onClick = { more = false; onEchoes() })
                    if (onListen != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Listen from here") }, onClick = { more = false; onListen() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Dismiss selection") }, onClick = { more = false; onDismiss() })
                }
            }
        }
    }
}

@Composable
internal fun ReaderNavigationPreview(title: String, detail: String, modifier: Modifier = Modifier,
    passage: String? = null, loading: Boolean = false) {
    Column(modifier.widthIn(max = 320.dp).readerIsland(FolioSoftShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReaderLocationLabel(detail)
        Text(title, style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface,
            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (!passage.isNullOrBlank()) Text(passage, style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurface, maxLines = 4,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        else if (loading) Text("Loading passage...", style = FolioTheme.typography.labelSmall,
            color = FolioTheme.colors.onSurfaceVariant)
        Text("Release to go there", style = FolioTheme.typography.labelSmall,
            color = FolioTheme.colors.onSurfaceVariant)
    }
}

@Composable
internal fun ReaderReturnButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ReaderChromeGroup(modifier, verticalInset = 0.dp) {
        TextButton(onClick = onClick) {
            Icon(Icons.Filled.Undo, null, Modifier.size(17.dp), tint = FolioTheme.colors.primary)
            Text("Return · $label", style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.onSurface, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 240.dp))
        }
    }
}

internal fun readerSelectionRect(bounds: ReaderSelectionBounds, viewport: Rect): Rect? {
    val left = viewport.left + bounds.left.coerceIn(0f, 1f) * viewport.width
    val right = viewport.left + bounds.right.coerceIn(0f, 1f) * viewport.width
    val top = viewport.top + bounds.top.coerceIn(0f, 1f) * viewport.height
    val bottom = viewport.top + bounds.bottom.coerceIn(0f, 1f) * viewport.height
    return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
}

internal fun readerSelectionToolbarPosition(selection: Rect, viewport: Rect, size: IntSize, gap: Float): IntOffset {
    val maxX = (viewport.right - size.width).coerceAtLeast(viewport.left)
    val maxY = (viewport.bottom - size.height).coerceAtLeast(viewport.top)
    val x = ((selection.left + selection.right - size.width) / 2).coerceIn(viewport.left, maxX)
    // Leave the native selection toolbar above the text and the selection handles below it clear.
    val below = selection.bottom + gap
    val above = selection.top - size.height - gap * 3
    val y = when { below <= maxY -> below; above >= viewport.top -> above; else -> maxY }
        .coerceIn(viewport.top, maxY)
    return IntOffset(x.roundToInt(), y.roundToInt())
}
