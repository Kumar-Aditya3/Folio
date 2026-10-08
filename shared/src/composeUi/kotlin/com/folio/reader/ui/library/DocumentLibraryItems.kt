@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.library

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentFormat
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.COVER_TARGET_WIDTH_PX
import com.folio.reader.ui.components.decodeCoverImage
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioRightClick
import com.folio.reader.ui.components.folioTypeGround
import com.folio.reader.ui.components.rememberEntryProgress
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun DocumentGrid(
    items: List<DocumentLibraryItem>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    onOpen: (Document) -> Unit,
    onDelete: (Document) -> Unit,
    onCategories: (Document) -> Unit,
    onToggleSelection: (String) -> Unit
) {
    val entry = rememberEntryProgress(items.map { it.document.id })
    // Re-tap on the Library nav item scrolls the shelf back to its top.
    val gridScroll = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(gridScroll) {
        com.folio.reader.ui.components.FolioTabReselect.events.collect { (route, _) ->
            if (route == "library" &&
                (gridScroll.firstVisibleItemIndex > 0 || gridScroll.firstVisibleItemScrollOffset > 0)
            ) {
                gridScroll.animateScrollToItem(0)
            }
        }
    }
    LazyVerticalGrid(
        state = gridScroll,
        columns = GridCells.Adaptive(minSize = FolioTokens.coverGridMin),
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = entry },
        contentPadding = PaddingValues(
            start = FolioTokens.gutter,
            end = FolioTokens.gutter,
            top = FolioTokens.space3 + LocalFolioTopInset.current,
            bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current
        ),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceBeat),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
    ) {
        items(items, key = { it.document.id }) { item ->
            DocumentGridItem(
                item = item,
                selected = item.document.id in selectedIds,
                isSelectionMode = isSelectionMode,
                onOpen = {
                    if (isSelectionMode) onToggleSelection(item.document.id) else onOpen(item.document)
                },
                onLongClick = { onToggleSelection(item.document.id) },
                onDelete = { onDelete(item.document) },
                onCategories = { onCategories(item.document) }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DocumentListItem(
    item: DocumentLibraryItem,
    selected: Boolean,
    isSelectionMode: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onCategories: () -> Unit
) {
    val document = item.document
    val interaction = rememberFolioInteraction()
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Only the selection tint fills the row; unselected rows sit on the
                // shelf plate like a book, not an opaque `surface` strip.
                //
                // Issue 4: shaped, and inset to the plate's own column. The tint had no
                // shape argument, so a selected row painted a hard square-cornered slab
                // edge-to-edge over the field. The row's horizontal gutter moved outside
                // the fill rather than being added to it, so the type does not move.
                .padding(horizontal = FolioTokens.gutter)
                .then(
                    if (selected) {
                        Modifier.background(
                            FolioTheme.colors.primary.copy(alpha = 0.12f),
                            FolioShapes.inset,
                        )
                    } else {
                        Modifier
                    }
                )
                .folioPressable(interaction, scaleTo = 0.99f)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = { if (isSelectionMode) onLongClick() else menuOpen = true }
                )
                .folioRightClick { if (!isSelectionMode) menuOpen = true }
                .padding(vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DocumentThumbnail(
                document = document,
                modifier = Modifier.size(width = 48.dp, height = 64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FolioTheme.atmosphere.sunkenFill)
                    // §17: the list row hands the plate on like the grid cell, so
                    // both view modes morph into the reader.
                    .sharedElementOrNoop(FolioSharedKeys.documentCover(document.id))
            )
            Spacer(Modifier.width(FolioTokens.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    document.title,
                    style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.documentTitle(document.id)),
                )
                Text(document.originalFilename, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(document.primaryCaption(item.isLocalFileMissing), style = FolioTheme.typography.labelSmall, color = if (item.isLocalFileMissing) FolioTheme.colors.error else FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(document.secondaryCaption(), style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (document.normalizedProgress > 0.0) {
                FolioProgressBar(
                    progress = document.normalizedProgress.coerceIn(0.0, 1.0).toFloat(),
                    modifier = Modifier.width(52.dp).semantics { contentDescription = "${document.progressPercent()}% read" },
                    color = FolioTheme.colors.accentProgress
                )
            }
            DocumentItemMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                onOpen = onOpen,
                onSelect = onLongClick,
                onCategories = onCategories,
                onDelete = onDelete
            )
        }
        com.folio.reader.ui.components.FolioRule(Modifier.padding(horizontal = FolioTokens.gutter))
    }
}

@Composable
private fun FormatBadge(format: DocumentFormat) {
    // §12.3: a format badge is a category/type marker, not a primary action — it
    // carries the tertiary hue so the document library reads with its own accent
    // rather than echoing the app-wide primary on every row.
    Text(
        text = format.name,
        style = FolioTheme.typography.labelSmall,
        color = FolioTheme.colors.tertiary,
        modifier = Modifier
            .clip(FolioShapes.pill)
            .background(FolioTheme.colors.tertiary.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

// PDF page thumbnails, bounded by decoded bytes (access-order LRU) rather than growing without
// limit — every distinct PDF opened otherwise pinned its full-size thumbnail forever.
private const val DOC_THUMB_CACHE_MAX_BYTES = 24L * 1024L * 1024L
private val documentThumbnailCache = LinkedHashMap<String, ImageBitmap>(32, 0.75f, true)
private var documentThumbnailBytes = 0L
private fun docThumbBytesOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height.toLong() * 4L
private fun documentThumbnailGet(path: String): ImageBitmap? =
    synchronized(documentThumbnailCache) { documentThumbnailCache[path] }
private fun documentThumbnailPut(path: String, bitmap: ImageBitmap) {
    synchronized(documentThumbnailCache) {
        documentThumbnailCache.put(path, bitmap)?.let { documentThumbnailBytes -= docThumbBytesOf(it) }
        documentThumbnailBytes += docThumbBytesOf(bitmap)
        val itr = documentThumbnailCache.entries.iterator()
        while (documentThumbnailBytes > DOC_THUMB_CACHE_MAX_BYTES && documentThumbnailCache.size > 1 && itr.hasNext()) {
            val e = itr.next()
            documentThumbnailBytes -= docThumbBytesOf(e.value)
            itr.remove()
        }
    }
}

/**
 * [suppressFallbackCaption] drops the filename under the format badge when there
 * is no page thumbnail. Morph destinations set it: the paired title is flying in
 * under a shared key, and the filename is the same string the title is derived
 * from, so the plate would otherwise repeat it at a second size for the length of
 * the morph. The [FormatBadge] stays — it is how the plate still reads as a
 * document rather than a blank tile.
 *
 * Internal rather than private: the document reader composes this same plate as
 * its morph landing, and a landing that was drawn by a different component would
 * be the one surface in the morph that did not match its source.
 */
@Composable
internal fun DocumentThumbnail(
    document: Document,
    modifier: Modifier = Modifier,
    fallbackWithFilename: Boolean = false,
    suppressFallbackCaption: Boolean = false
) {
    val path = document.thumbnailPath.takeIf {
        document.format == DocumentFormat.PDF && !it.isNullOrBlank()
    }
    var bitmap by remember(path) {
        mutableStateOf(path?.let { documentThumbnailGet(it) })
    }

    LaunchedEffect(path) {
        if (path == null || bitmap != null) return@LaunchedEffect
        bitmap = try {
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (file.isFile && file.length() > 0) {
                    decodeCoverImage(file.readBytes(), COVER_TARGET_WIDTH_PX)?.also {
                        documentThumbnailPut(path, it)
                    }
                } else {
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val thumbnail = bitmap
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "First page of ${document.title}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(FolioTokens.space2)
            ) {
                FormatBadge(document.format)
                if (fallbackWithFilename && !suppressFallbackCaption) {
                    Spacer(Modifier.height(FolioTokens.space2))
                    Text(
                        document.originalFilename,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The one context menu a document carries, opened by long-press (touch) or
 * right-click (mouse) — the union of the old ⋮ menu and the door into bulk
 * selection behind a single gesture.
 */
@Composable
private fun DocumentItemMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onCategories: () -> Unit,
    onDelete: () -> Unit
) {
    com.folio.reader.ui.components.FolioMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DropdownMenuItem(
            text = { Text("Open") },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onDismissRequest(); onOpen() }
        )
        DropdownMenuItem(
            text = { Text("Categories") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onDismissRequest(); onCategories() }
        )
        DropdownMenuItem(
            text = { Text("Select") },
            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onDismissRequest(); onSelect() }
        )
        DropdownMenuItem(
            text = { Text("Delete document", color = FolioTheme.colors.error) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = FolioTheme.colors.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = { onDismissRequest(); onDelete() }
        )
    }
}

private fun Document.primaryCaption(missing: Boolean): String = when {
    missing -> "Local file missing"
    format == DocumentFormat.PDF && pageCount != null -> "${format.name} · ${formatBytes(byteSize)} · $pageCount pages"
    else -> "${format.name} · ${formatBytes(byteSize)}"
}

private fun Document.secondaryCaption(): String {
    val opened = lastOpenedAt?.let { "Opened ${formatDate(it)}" } ?: "Never opened"
    return "Imported ${formatDate(importedAt)} · $opened · ${progressPercent()}%"
}

private fun Document.progressPercent(): Int =
    (normalizedProgress.coerceIn(0.0, 1.0) * 100).toInt()

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val shown = if (value >= 10) value.toInt().toString() else ((value * 10).toInt() / 10.0).toString()
    return "$shown ${units[unit]}"
}

private fun formatDate(instant: Instant): String =
    instant.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DocumentGridItem(
    item: DocumentLibraryItem,
    selected: Boolean,
    isSelectionMode: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onCategories: () -> Unit
) {
    val document = item.document
    val interaction = rememberFolioInteraction()
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // No opaque card fill: like BookCard, the title sits straight on the
            // shelf atmosphere, not a solid `surface` rectangle. The cover below
            // carries its own plate, so the only container fill is the selection
            // tint — and even that is translucent so the shelf shows through.
            .then(
                if (selected) {
                    Modifier
                        .clip(FolioShapes.plate)
                        .background(FolioTheme.colors.primary.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .folioPressable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
                onLongClick = { if (isSelectionMode) onLongClick() else menuOpen = true }
            )
            .folioRightClick { if (!isSelectionMode) menuOpen = true }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f)
                .border(1.dp, FolioTheme.atmosphere.hairline, FolioShapes.plate)
                .clip(FolioShapes.plate)
                .background(FolioTheme.atmosphere.sunkenFill)
                // §17: the plate flies to the document reader's landing.
                .sharedElementOrNoop(FolioSharedKeys.documentCover(document.id))
                .semantics {
                    contentDescription = if (item.isLocalFileMissing) {
                        "${document.title}, ${document.format.name} document, local file missing"
                    } else {
                        "${document.title}, ${document.format.name} document, ${document.progressPercent()}% read"
                    }
                }
        ) {
            DocumentThumbnail(
                document = document,
                modifier = Modifier.fillMaxSize(),
                fallbackWithFilename = true,
                // The title below is the paired one; the filename is the same
                // string, so the plate must not draw it too.
                suppressFallbackCaption = true,
            )
            if (document.normalizedProgress > 0.0) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(FolioTheme.colors.onSurface.copy(alpha = 0.15f))
                ) {
                    Box(
                        Modifier.fillMaxWidth(document.normalizedProgress.coerceIn(0.0, 1.0).toFloat())
                            .height(3.dp).background(FolioTheme.colors.accentProgress)
                    )
                }
            }
        }
        // Same wash as BookCard and MangaGridItem. A document cell is the worst case
        // on the shelf — three stacked caption lines at labelSmall, the smallest type
        // in the app, over a moving Mirage bloom — so this is the cell the ground was
        // actually needed for. Still the page's own colour, still under half strength,
        // still dissolved at both ends: the field drifts through the captions, it is
        // merely dimmed where they are.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .folioTypeGround()
                .padding(top = FolioTokens.space2, bottom = FolioTokens.space1),
        ) {
            Text(
                document.title,
                style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.documentTitle(document.id)),
            )
            Text(document.primaryCaption(item.isLocalFileMissing), style = FolioTheme.typography.labelSmall, color = if (item.isLocalFileMissing) FolioTheme.colors.error else FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(document.secondaryCaption(), style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DocumentItemMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            onOpen = onOpen,
            onSelect = onLongClick,
            onCategories = onCategories,
            onDelete = onDelete
        )
    }
}

@Composable
fun DocumentList(
    items: List<DocumentLibraryItem>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    onOpen: (Document) -> Unit,
    onDelete: (Document) -> Unit,
    onCategories: (Document) -> Unit,
    onToggleSelection: (String) -> Unit
) {
    val entry = rememberEntryProgress(items.map { it.document.id })
    // §16 P6 (issue 4): the same shelf surface DocumentGrid got — see DocumentGrid. The P6
    // pass only reached the grid renderers, so the documents *list* was bare type over the
    // Mirage field while the documents *grid* sat on a plate.
    val listScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        state = listScroll,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = entry },
        contentPadding = PaddingValues(
            top = FolioTokens.space2 + LocalFolioTopInset.current,
            bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current
        )
    ) {
        items(items, key = { it.document.id }) { item ->
            DocumentListItem(
                item = item,
                selected = item.document.id in selectedIds,
                isSelectionMode = isSelectionMode,
                onOpen = {
                    if (isSelectionMode) onToggleSelection(item.document.id) else onOpen(item.document)
                },
                onLongClick = { onToggleSelection(item.document.id) },
                onDelete = { onDelete(item.document) },
                onCategories = { onCategories(item.document) }
            )
        }
    }
}
