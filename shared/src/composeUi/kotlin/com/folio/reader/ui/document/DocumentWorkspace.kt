package com.folio.reader.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.DocumentBookmark
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.reader.ReaderLocationLabel
import com.folio.reader.ui.reader.ReaderTool
import com.folio.reader.ui.reader.ReaderWorkspace
import com.folio.reader.ui.reader.ReaderWorkspaceLayout
import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.reader.readerImeBottomPadding
import com.folio.reader.ui.reader.readerIsland
import com.folio.reader.ui.theme.FolioSoftShape
import com.folio.reader.ui.theme.FolioTheme
import kotlin.math.roundToInt

@Composable
internal fun DocumentReaderWorkspace(
    active: ReaderTool?, state: DocumentReaderState,
    onSelect: (ReaderTool) -> Unit, onDismiss: () -> Unit,
    onPreviewProgress: (Float) -> Unit, onPreviewBookmark: (DocumentBookmark) -> Unit,
    onCancelPreview: () -> Unit, onReadHere: () -> Unit, onRemoveBookmark: (String) -> Unit,
    settings: DocumentSettings, onSettingsChange: (DocumentSettings) -> Unit,
    onOpenFullSettings: () -> Unit, globalSettings: ReaderSettings,
    onMode: (DocumentReaderMode) -> Unit, onRotate: () -> Unit, onResetZoom: () -> Unit,
    layout: ReaderWorkspaceLayout? = null,
) {
    ReaderWorkspace(active, documentReaderTools, state.document?.title.orEmpty(),
        onSelect = { onCancelPreview(); onSelect(it) }, onDismiss = onDismiss, layout = layout) { tool ->
        val preview = state.preview
        if (preview != null && tool != ReaderTool.Settings) {
            com.folio.reader.ui.components.FolioBackHandler { onCancelPreview() }
            DocumentPreviewPanel(preview, onReadHere = onReadHere, onCancel = onCancelPreview)
        } else when (tool) {
            ReaderTool.Pages -> DocumentPagesPanel(state, onPreviewProgress)
            ReaderTool.Saved -> DocumentBookmarksPanel(state, onPreviewBookmark, onRemoveBookmark)
            ReaderTool.Settings -> if (state.document?.format == DocumentFormat.PDF) {
                DocumentPdfControlsPanel(state, onMode, onRotate, onResetZoom)
            } else DocumentSettingsPanel(settings, onSettingsChange, onDismiss, onOpenFullSettings,
                customFonts = globalSettings.customFonts)
            else -> Unit
        }
    }
}

@Composable
internal fun DocumentPagesPanel(state: DocumentReaderState, onPreview: (Float) -> Unit) {
    val pdf = state.document?.format == DocumentFormat.PDF
    val count = if (pdf) state.pageCount.takeIf { it > 0 } ?: state.document?.pageCount ?: 0
        else state.reflowableLocationCount.coerceIn(5, 25)
    val current = if (pdf) state.currentPage.coerceIn(0, (count - 1).coerceAtLeast(0))
        else (state.normalizedProgress * (count - 1)).roundToInt().coerceIn(0, count - 1)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = current)
    Column(Modifier.fillMaxSize()) {
        Text(if (pdf) "$count pages. Choose a page to preview its location."
            else "Reading locations. Choose one to preview text from this document.",
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(96.dp), state = grid, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp + readerImeBottomPadding())) {
            items(count = count.coerceAtLeast(0), key = { it }) { index ->
                val fraction = if (count > 1) index.toFloat() / (count - 1) else 0f
                val marked = pdf && state.bookmarks.any { (it.locator as? DocumentLocator.FixedPage)?.pageIndex == index }
                Column(Modifier.fillMaxWidth().heightIn(min = 80.dp)
                    .background(if (index == current) FolioTheme.colors.primary.copy(alpha = .12f)
                        else FolioTheme.colors.onSurface.copy(alpha = .04f), FolioSoftShape(12.dp))
                    .selectable(index == current, role = Role.Button, onClick = { onPreview(fraction) })
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (pdf) "Page ${index + 1}" else "${(fraction * 100).roundToInt()}%",
                        style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        ReaderLocationLabel(if (index == current) "Here" else if (pdf) "Preview" else "Location")
                        if (marked) Icon(Icons.Filled.Bookmark, "Bookmarked", Modifier.size(16.dp), FolioTheme.colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DocumentBookmarksPanel(
    state: DocumentReaderState,
    onPreview: (DocumentBookmark) -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + readerImeBottomPadding())) {
        item {
            Text(if (state.bookmarks.isEmpty()) "No bookmarks yet. Use the bookmark action to keep your reading location."
                else "Bookmarks in this document. Preview a location before reading there.",
                style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        items(state.bookmarks, key = { it.id }) { bookmark ->
            val label = bookmark.label ?: when (val locator = bookmark.locator) {
                is DocumentLocator.FixedPage -> "Page ${locator.pageIndex + 1}"
                is DocumentLocator.Reflowable -> if (reflowableLocatorFraction(locator) != null || reflowableChunkIndex(locator.sectionId) != null) {
                    val progress = reflowableLocatorProgress(locator, state.reflowableLocationCount, state.normalizedProgress)
                    "Location ${(progress * 100).roundToInt()}%"
                } else "Saved text location"
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = { onPreview(bookmark) })
                    .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ReaderLocationLabel("Preview bookmark")
                }
                IconButton(onClick = { onRemove(bookmark.id) }) {
                    Icon(Icons.Filled.DeleteOutline, "Remove bookmark", tint = FolioTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun DocumentPreviewPanel(preview: DocumentNavigationPreview, onReadHere: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
            .padding(bottom = readerImeBottomPadding()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ReaderLocationLabel("Preview. Your reading position has not changed.")
            Text(preview.label, style = FolioTheme.typography.titleLarge, color = FolioTheme.colors.onSurface)
            if (preview.target.locator is DocumentLocator.Reflowable) {
                Text(if (preview.loading) "Loading this passage..." else preview.excerpt ?: "No text excerpt is available at this location.",
                    style = FolioTheme.typography.bodyLarge, color = FolioTheme.colors.onSurface)
            } else {
                Text("Pages are rendered only when you read them. This preview uses the real page location.",
                    style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
            }
            Text("Return will restore ${documentNavigationLabel(preview.origin)}.",
                style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            TextButton(onClick = onReadHere, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read here") }
        }
    }
}

@Composable
internal fun DocumentPdfControlsPanel(state: DocumentReaderState, onMode: (DocumentReaderMode) -> Unit,
    onRotate: () -> Unit, onResetZoom: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        .padding(bottom = readerImeBottomPadding()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReaderLocationLabel("Fixed PDF pages")
        Text("Page flow", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurface)
        FolioSegmented(options = listOf("Single page", "Continuous"),
            selectedIndex = if (state.mode == DocumentReaderMode.SINGLE_PAGE) 0 else 1,
            onSelect = { onMode(if (it == 0) DocumentReaderMode.SINGLE_PAGE else DocumentReaderMode.CONTINUOUS) })
        TextButton(onClick = onRotate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, null, Modifier.size(20.dp))
            Text("Rotate pages (${state.rotationDegrees} degrees)", Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onResetZoom, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.ZoomOutMap, null, Modifier.size(20.dp))
            Text("Reset zoom", Modifier.padding(start = 8.dp))
        }
        Text("Pinch to zoom. Page text and layout are part of the PDF and cannot be restyled.",
            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
    }
}

@Composable
internal fun DocumentReturnBar(point: DocumentNavigationPoint, onReturn: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier) {
    Row(modifier.readerIsland(FolioSoftShape(12.dp)), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onReturn, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Return to ${documentNavigationLabel(point)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, "Keep this reading location", Modifier.size(18.dp), FolioTheme.colors.onSurfaceVariant)
        }
    }
}
