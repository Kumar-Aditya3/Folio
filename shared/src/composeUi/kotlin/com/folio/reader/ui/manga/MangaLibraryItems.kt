package com.folio.reader.ui.manga

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaEntry
import com.folio.reader.ui.theme.FolioTheme

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
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) FolioTheme.colors.primaryContainer else FolioTheme.colors.surface,
        ),
    ) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            MangaCover(
                backend = backend,
                sourceId = manga.sourceId,
                thumbnailUrl = manga.thumbnailUrl,
                coverPath = manga.coverPath,
                modifier = Modifier.fillMaxSize(),
                dimmed = fullyRead,
            )
            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            if (selected) FolioTheme.colors.primary else FolioTheme.colors.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(
                        if (selected) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (selected) FolioTheme.colors.onPrimary else FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(FolioTheme.colors.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = unreadCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = FolioTheme.colors.onPrimary,
                        )
                    }
                }
                if (downloadedCount > 0) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Downloaded",
                        tint = FolioTheme.colors.tertiary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(16.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(26.dp)
                    .background(FolioTheme.colors.surface.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    tint = FolioTheme.colors.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp),
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
        if (progress > 0f) {
            com.folio.reader.ui.components.FolioProgressBar(
                progress = progress,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = FolioTheme.colors.primary,
            )
        }
        Text(
            text = manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = FolioTheme.typography.labelLarge,
            color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            text = manga.sourceName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (progress > 0f) {
            Text(
                text = "${(progress * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp))) {
                    MangaCover(
                        backend = backend,
                        sourceId = manga.sourceId,
                        thumbnailUrl = manga.thumbnailUrl,
                        coverPath = manga.coverPath,
                        modifier = Modifier.fillMaxSize(),
                        dimmed = fullyRead,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        manga.title,
                        style = FolioTheme.typography.titleMedium,
                        color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(manga.sourceName, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (statusLabel.isNotBlank()) {
                        Text(
                            statusLabel,
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                }
                if (progress > 0f) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.width(60.dp),
                        progress = progress,
                        color = FolioTheme.colors.primary,
                    )
                }
                MangaRowOptions(
                    fullyRead = fullyRead,
                    onMarkRead = onMarkRead,
                    onRemove = onRemove,
                    onCategories = onCategories,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
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
