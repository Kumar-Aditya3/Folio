@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.manga

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
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
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioRightClick
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
 * The featured manga entry: a wide, image-led composition where the cover sits at
 * `coverFeature` beside its own typography and throws a halo onto the page behind
 * it. Mirrors [FeaturedShelfEntry] for books so the manga shelf has the same
 * visual hierarchy — the one you're reading comes forward.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FeaturedMangaShelfEntry(
    manga: MangaEntry,
    backend: MangaBackend,
    unreadCount: Int,
    progress: Float,
    fullyRead: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onCategories: () -> Unit,
) {
    val accent = rememberCoverAccent(manga.coverPath, FolioTheme.colors.accentProgress)
    val interaction = rememberFolioInteraction()
    var menuOpen by remember { mutableStateOf(false) }
    val inProgress = progress > 0f && progress < 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .folioPressable(interaction, scaleTo = 0.985f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { if (isSelectionMode) onLongClick() else menuOpen = true },
            )
            .folioRightClick { if (!isSelectionMode) menuOpen = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCoverPlate(
            backend = backend,
            sourceId = manga.sourceId,
            thumbnailUrl = manga.thumbnailUrl,
            coverPath = manga.coverPath,
            modifier = Modifier.sharedElementOrNoop(FolioSharedKeys.mangaCover(manga.id)),
            width = FolioTokens.coverFeature,
            halo = accent,
            elevation = FolioTokens.elevationRaised,
            dimmed = fullyRead,
            overlay = {
                if (isSelected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(FolioTheme.colors.primary.copy(alpha = 0.32f))
                            .border(2.dp, FolioTheme.colors.primary, FolioShapes.plate)
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(FolioTheme.colors.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = FolioTheme.colors.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            },
        )
        Spacer(Modifier.width(FolioTokens.space4))
        Column(modifier = Modifier.weight(1f)) {
            FolioEyebrow("Reading", accent = accent)
            Spacer(Modifier.height(3.dp))
            Text(
                text = manga.title,
                style = FolioTheme.typography.titleLarge,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = manga.sourceName,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(FolioTokens.space2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = FolioTheme.typography.titleSmall,
                    color = accent,
                )
                if (unreadCount > 0) {
                    Spacer(Modifier.width(FolioTokens.space2))
                    Text(
                        text = "$unreadCount unread",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.accentDiscovery,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
            if (inProgress) {
                FolioProgressBar(progress = progress, color = accent)
            }
        }
        MangaItemMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            onOpen = onClick,
            onSelect = onLongClick,
            onCategories = onCategories,
            onMarkRead = onMarkRead,
            onRemove = onRemove,
        )
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
    val interaction = rememberFolioInteraction()
    val inProgress = progress > 0f && progress < 1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioPressable(interaction)
            .graphicsLayer { alpha = if (fullyRead) 0.86f else 1f }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { if (inSelectionMode) onLongClick() else menuOpen = true },
            )
            .folioRightClick { if (!inSelectionMode) menuOpen = true },
    ) {
        // §17 shared element: the plate the shelf shows is the plate the series'
        // own page shows, so it travels rather than being redrawn there.
        MangaCoverPlate(
            backend = backend,
            sourceId = manga.sourceId,
            thumbnailUrl = manga.thumbnailUrl,
            coverPath = manga.coverPath,
            // A grid cell sizes the plate, not the other way round. Left at the
            // default the plate takes FolioTokens.coverShelf — a fixed width — and
            // stops tracking the adaptive column, which is exactly the case the
            // `width` parameter documents as needing null.
            width = null,
            dimmed = fullyRead,
            modifier = Modifier.sharedElementOrNoop(FolioSharedKeys.mangaCover(manga.id)),
            overlay = {
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
                if (inProgress) {
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
            },
        )
        Spacer(Modifier.height(FolioTokens.space2))
        Text(
            text = manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = FolioTheme.typography.labelMedium,
            color = if (fullyRead) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.onSurface,
            modifier = Modifier.fillMaxWidth()
                .sharedTextOrNoop(FolioSharedKeys.mangaTitle(manga.id)),
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
        if (inProgress) {
            Text(
                text = "${(progress * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.accentProgress,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        MangaItemMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            onOpen = onClick,
            onSelect = onLongClick,
            onCategories = onCategories,
            onMarkRead = onMarkRead,
            onRemove = onRemove,
        )
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
    var menuOpen by remember { mutableStateOf(false) }
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (inSelectionMode) onLongClick() else menuOpen = true }
                )
                .folioRightClick { if (!inSelectionMode) menuOpen = true },
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
            MangaItemMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                onOpen = onClick,
                onSelect = onLongClick,
                onCategories = onCategories,
                onMarkRead = onMarkRead,
                onRemove = onRemove,
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
                        onLongClick = { if (inSelectionMode) onLongClick() else menuOpen = true },
                    )
                    .folioRightClick { if (!inSelectionMode) menuOpen = true }
                    .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(FolioTokens.coverInline)
                        .height(FolioTokens.coverInline * FolioTokens.coverAspect)
                        // §17: the list shelf hands the same plate to the detail
                        // page the grid does, so both view modes morph.
                        .sharedElementOrNoop(FolioSharedKeys.mangaCover(manga.id))
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
                        modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.mangaTitle(manga.id)),
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
                MangaItemMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    onOpen = onClick,
                    onSelect = onLongClick,
                    onCategories = onCategories,
                    onMarkRead = onMarkRead,
                    onRemove = onRemove,
                )
            }
            com.folio.reader.ui.components.FolioRule(
                modifier = Modifier.padding(horizontal = FolioTokens.gutter)
            )
        }
    }
}

/**
 * The one context menu a manga carries, opened by long-press (touch) or
 * right-click (mouse). The old ⋮ badge and the long-press selection jump
 * revealed two different, partial action sets; this is the union behind a
 * single gesture — the primary action, the item commands, and the door into
 * bulk selection.
 */
@Composable
fun MangaItemMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onCategories: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DropdownMenuItem(
            text = { Text("Open") },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { onDismissRequest(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text("Categories…") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
            onClick = { onDismissRequest(); onCategories() },
        )
        DropdownMenuItem(
            text = { Text("Mark all as read") },
            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            onClick = { onDismissRequest(); onMarkRead(true) },
        )
        DropdownMenuItem(
            text = { Text("Mark all as unread") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
            onClick = { onDismissRequest(); onMarkRead(false) },
        )
        DropdownMenuItem(
            text = { Text("Select") },
            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
            onClick = { onDismissRequest(); onSelect() },
        )
        DropdownMenuItem(
            text = { Text("Remove from library") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDismissRequest(); onRemove() },
        )
    }
}
