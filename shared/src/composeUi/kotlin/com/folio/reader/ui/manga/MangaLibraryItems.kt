package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaEntry
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere

@Composable
internal fun CollectionRow(
    category: com.folio.reader.manga.MangaCategory,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(category.name) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            androidx.compose.material3.TextButton(
                onClick = { if (name.isNotBlank()) onRename(name.trim()); editing = false },
            ) { Text("Save") }
        } else {
            Text(
                category.name,
                style = MaterialTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = { editing = true }) { Text("Rename") }
            androidx.compose.material3.IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (canDelete) "Remove" else "Create another category before removing this one",
                    tint = if (canDelete) FolioTheme.colors.error else FolioTheme.colors.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/**
 * A manga shelf entry, in the same object language as the books shelf: a plate
 * with a contact shadow, type beneath, no card. Read-through titles sit back at
 * 0.86 alpha the way finished books do, so the shelf has depth.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MangaGridItem(
    manga: MangaEntry,
    backend: MangaBackend,
    unreadCount: Int,
    progress: Float,
    fullyRead: Boolean,
    lastRead: com.folio.reader.manga.MangaLastRead?,
    downloadedCount: Int,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onCategories: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val atmos = FolioTheme.atmosphere
    val interaction = rememberFolioInteraction()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioPressable(interaction)
            .graphicsLayer { alpha = if (fullyRead) 0.86f else 1f }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .shadow(
                    elevation = 8.dp * atmos.shadowScale,
                    shape = FolioShapes.plate,
                    ambientColor = atmos.shadowAmbient,
                    spotColor = atmos.shadowSpot,
                )
                .clip(FolioShapes.plate),
        ) {
            MangaCover(
                backend = backend,
                sourceId = manga.sourceId,
                thumbnailUrl = manga.thumbnailUrl,
                coverPath = manga.coverPath,
                modifier = Modifier.fillMaxSize(),
                dimmed = fullyRead,
            )
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(FolioTheme.colors.primary.copy(alpha = 0.32f))
                        .border(2.dp, FolioTheme.colors.primary, FolioShapes.plate)
                )
            }
            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) FolioTheme.colors.primary else Color.Black.copy(alpha = 0.45f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (selected) FolioTheme.colors.onPrimary else Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(FolioTheme.colors.primary, FolioShapes.pill)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = unreadCount.toString(),
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onPrimary,
                        )
                    }
                }
                if (downloadedCount > 0) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Downloaded",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(15.dp),
                    )
                }
            }
            // Progress as a seam on the plate's foot — identical to the books shelf.
            if (progress > 0f && progress < 1f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(FolioTheme.colors.accentProgress)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.40f))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Categories…") },
                        leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                        onClick = { menuOpen = false; onCategories() },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all as read") },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                        onClick = { menuOpen = false; onMarkRead(true) },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all as unread") },
                        leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                        onClick = { menuOpen = false; onMarkRead(false) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
        Spacer(Modifier.height(FolioTokens.space2))
        Text(
            text = manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = FolioTheme.typography.labelMedium,
            color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = manga.sourceName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = FolioTheme.typography.labelSmall,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        // One caption line, matching the books shelf exactly.
        if (progress > 0f) {
            Text(
                text = "${(progress * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.accentProgress,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MangaListItem(
    manga: MangaEntry,
    backend: MangaBackend,
    unreadCount: Int,
    progress: Float,
    fullyRead: Boolean,
    compact: Boolean,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onCategories: () -> Unit,
) {
    val percent = (progress * 100).toInt()
    val statusLabel = when {
        fullyRead -> "Read"
        progress > 0f -> "Reading · $percent%"
        else -> if (unreadCount > 0) "Unread" else ""
    }
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.padding(start = 16.dp).width(32.dp).height(48.dp)) {
                MangaCover(
                    backend = backend,
                    sourceId = manga.sourceId,
                    thumbnailUrl = manga.thumbnailUrl,
                    coverPath = manga.coverPath,
                    modifier = Modifier.fillMaxSize(),
                    dimmed = fullyRead,
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    manga.title,
                    style = FolioTheme.typography.bodyMedium,
                    color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(manga.sourceName, statusLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MangaRowOptions(
                fullyRead = fullyRead,
                onMarkRead = onMarkRead,
                onRemove = onRemove,
                onCategories = onCategories,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    } else {
        // A ruled row, not a card: the plate carries the object language and the
        // hairline carries the separation.
        val rowInteraction = rememberFolioInteraction()
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .folioPressable(rowInteraction, scaleTo = 0.99f)
                    .combinedClickable(
                        interactionSource = rowInteraction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(FolioTokens.coverInline)
                        .height(FolioTokens.coverInline * FolioTokens.coverAspect)
                        .clip(FolioShapes.plateSmall)
                ) {
                    MangaCover(
                        backend = backend,
                        sourceId = manga.sourceId,
                        thumbnailUrl = manga.thumbnailUrl,
                        coverPath = manga.coverPath,
                        modifier = Modifier.fillMaxSize(),
                        dimmed = fullyRead,
                    )
                    if (progress > 0f && progress < 1f) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress)
                                    .height(2.dp)
                                    .background(FolioTheme.colors.accentProgress)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(FolioTokens.space3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        manga.title,
                        style = FolioTheme.typography.titleSmall,
                        color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(manga.sourceName, statusLabel).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (unreadCount > 0) {
                    Text(
                        "$unreadCount",
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.accentDiscovery,
                    )
                    Spacer(Modifier.width(FolioTokens.space1))
                }
                MangaRowOptions(
                    fullyRead = fullyRead,
                    onMarkRead = onMarkRead,
                    onRemove = onRemove,
                    onCategories = onCategories,
                )
            }
            com.folio.reader.ui.components.FolioRule(
                modifier = Modifier.padding(horizontal = FolioTokens.gutter)
            )
        }
    }
}

@Composable
private fun MangaRowOptions(
    fullyRead: Boolean,
    onMarkRead: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = FolioTheme.colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Categories…") },
                leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                onClick = { open = false; onCategories() },
            )
            DropdownMenuItem(
                text = { Text("Mark all as read") },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                onClick = { open = false; onMarkRead(true) },
            )
            DropdownMenuItem(
                text = { Text("Mark all as unread") },
                leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                onClick = { open = false; onMarkRead(false) },
            )
            DropdownMenuItem(
                text = { Text("Remove from library") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}
