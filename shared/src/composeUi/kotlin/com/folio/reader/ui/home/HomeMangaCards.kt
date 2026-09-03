package com.folio.reader.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaNewChapterBadge
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.manga.MangaCover
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere

/**
 * §11.4 Home's manga sections, in the redesigned shelf language.
 *
 * They render only when a [MangaBackend] is wired (Android runtime); desktop's
 * default params never reach these paths. Manga covers arrive over the network via
 * [MangaCover], so they take the plate *shape* and contact shadow directly here
 * rather than through `FolioCoverPlate`, which is file-backed.
 */

/** Plate treatment for a network-loaded manga cover: same object language, own loader. */
@Composable
private fun MangaPlate(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String?,
    width: Dp,
    modifier: Modifier = Modifier,
    elevation: Dp = 8.dp,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val atmos = FolioTheme.atmosphere
    Box(
        modifier = modifier
            .width(width)
            .height(width * FolioTokens.coverAspect)
            .shadow(
                elevation = elevation * atmos.shadowScale,
                shape = FolioShapes.plate,
                ambientColor = atmos.shadowAmbient,
                spotColor = atmos.shadowSpot,
            )
            .clip(FolioShapes.plate)
    ) {
        MangaCover(
            backend = backend,
            sourceId = sourceId,
            thumbnailUrl = thumbnailUrl,
            coverPath = coverPath,
            modifier = Modifier.fillMaxSize()
        )
        overlay?.invoke(this)
    }
}

/**
 * §11.4 manga Continue reading — a shelf matching the books shelf exactly, with
 * progress as a seam on the plate's foot. The overflow item is the only path to
 * the source's web page; the primary tap always opens the reader.
 */
@Composable
internal fun MangaContinueCard(
    items: List<MangaContinueItem>,
    backend: MangaBackend,
    onOpenReader: (String, String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSourceWeb: (String) -> Unit
) {
    Column {
        FolioSectionHead(
            title = "Continue reading",
            eyebrow = "Manga",
            accent = FolioTheme.colors.accentDiscovery,
            modifier = Modifier.padding(horizontal = FolioTokens.gutter),
        )
        Spacer(Modifier.height(FolioTokens.space3))
        LazyRow(
            contentPadding = PaddingValues(start = FolioTokens.gutter, end = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            items(items.size) { index ->
                val item = items[index]
                var menuOpen by remember { mutableStateOf(false) }
                val interaction = rememberFolioInteraction()
                Column(
                    modifier = Modifier
                        .width(FolioTokens.coverShelf)
                        .folioPressable(interaction)
                        .clickable(interactionSource = interaction, indication = null) {
                            val chapter = item.chapterId
                            if (chapter != null) onOpenReader(item.mangaId, chapter)
                            else onOpenDetail(item.mangaId)
                        }
                ) {
                    MangaPlate(
                        backend = backend,
                        sourceId = item.sourceId,
                        thumbnailUrl = item.thumbnailUrl,
                        coverPath = item.coverPath,
                        width = FolioTokens.coverShelf,
                        overlay = {
                            if (item.progress > 0f) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .background(Color.Black.copy(alpha = 0.35f))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(item.progress)
                                            .height(3.dp)
                                            .background(FolioTheme.colors.accentProgress)
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(FolioTokens.space2))
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = FolioTheme.typography.labelMedium,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            item.caption?.let { caption ->
                                Text(
                                    caption,
                                    style = FolioTheme.typography.labelSmall,
                                    color = FolioTheme.colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (item.webUrl != null) {
                            Box {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Open on ${item.sourceName}",
                                    tint = FolioTheme.colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { menuOpen = true }
                                )
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Open on ${item.sourceName}") },
                                        onClick = {
                                            menuOpen = false
                                            onOpenSourceWeb(item.webUrl)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * §11.4 "New chapters": up to 6 library manga with unread chapters, newest check
 * first. A **hairline-ruled list**, not a card of rows — the plate, the title and
 * the count in the discovery accent, separated by rules. Lists do not need boxes.
 */
@Composable
internal fun NewChaptersCard(
    badges: List<MangaNewChapterBadge>,
    backend: MangaBackend,
    onOpenMangaDetail: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        FolioEyebrow("New chapters", accent = FolioTheme.colors.accentDiscovery)
        Spacer(Modifier.height(FolioTokens.space2))
        badges.forEachIndexed { index, badge ->
            if (index > 0) FolioRule()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMangaDetail(badge.mangaId) }
                    .padding(vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MangaPlate(
                    backend = backend,
                    sourceId = badge.sourceId,
                    thumbnailUrl = badge.thumbnailUrl,
                    coverPath = badge.coverPath,
                    width = 38.dp,
                    elevation = 4.dp,
                )
                Spacer(Modifier.width(FolioTokens.space3))
                Text(
                    badge.title,
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(FolioTokens.space2))
                Text(
                    "+${badge.newChapterCount}",
                    style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.accentDiscovery
                )
            }
        }
    }
}

/**
 * §11.4 Discover: LATEST browse hits not already in the library. The smallest
 * shelf on the page — a step below "because you finished", because an unowned
 * suggestion is the least committed thing Home shows. Tapping persists the entry
 * for the detail screen without adding it to the library (the browse contract).
 */
@Composable
internal fun DiscoverCard(
    items: List<MangaDiscoverItem>,
    backend: MangaBackend,
    onOpenDiscover: (MangaDiscoverItem) -> Unit
) {
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioEyebrow("Discover", accent = FolioTheme.colors.accentDiscovery)
            Spacer(Modifier.height(3.dp))
            Text(
                "New from your sources",
                style = FolioTheme.typography.titleMedium,
                color = FolioTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(FolioTokens.space3))
        LazyRow(
            contentPadding = PaddingValues(start = FolioTokens.gutter, end = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            items(items.size) { index ->
                val item = items[index]
                val interaction = rememberFolioInteraction()
                Column(
                    modifier = Modifier
                        .width(FolioTokens.coverInline * 1.35f)
                        .folioPressable(interaction)
                        .clickable(interactionSource = interaction, indication = null) {
                            onOpenDiscover(item)
                        }
                ) {
                    MangaPlate(
                        backend = backend,
                        sourceId = item.sourceId,
                        thumbnailUrl = item.thumbnailUrl,
                        coverPath = null,
                        width = FolioTokens.coverInline * 1.35f,
                        elevation = 5.dp,
                    )
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        item.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.sourceName,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
