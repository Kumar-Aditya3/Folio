package com.folio.reader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaNewChapterBadge
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.manga.MangaCover
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * §11.4 Home's manga cards. They render only when a [MangaBackend] is wired
 * (Android runtime); desktop's default params never reach these paths.
 */

/**
 * §11.4: manga Continue reading — same layout language as the books card (cover,
 * ring, title, caption), cap 3 upstream. The overflow item is the source deep
 * link; the primary tap always opens the reader.
 */
@Composable
internal fun MangaContinueCard(
    items: List<MangaContinueItem>,
    backend: MangaBackend,
    onOpenReader: (String, String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSourceWeb: (String) -> Unit
) {
    FolioSectionCard(title = "Continue reading", accent = FolioTheme.colors.accentDiscovery) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            items(items.size) { index ->
                val item = items[index]
                var menuOpen by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .width(FolioTokens.listCoverMin)
                        .clickable {
                            val chapter = item.chapterId
                            if (chapter != null) onOpenReader(item.mangaId, chapter)
                            else onOpenDetail(item.mangaId)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(FolioTokens.listCoverMin)
                            .height(120.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        MangaCover(
                            backend = backend,
                            sourceId = item.sourceId,
                            thumbnailUrl = item.thumbnailUrl,
                            coverPath = item.coverPath,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(FolioTokens.space1))
                    ProgressRing(
                        progress = item.progress,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3f
                    )
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        item.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurface,
                        maxLines = 1,
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
                    if (item.webUrl != null) {
                        Box {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Open on ${item.sourceName}",
                                tint = FolioTheme.colors.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
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

/**
 * §11.4: up to 6 library manga with new chapters, newest check first, each
 * showing its count. Same layout language as the manga library — cover, title,
 * caption — with the count in the discovery accent.
 */
@Composable
internal fun NewChaptersCard(
    badges: List<MangaNewChapterBadge>,
    backend: MangaBackend,
    onOpenMangaDetail: (String) -> Unit
) {
    FolioSectionCard(title = "New chapters", accent = FolioTheme.colors.accentDiscovery) {
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            badges.forEach { badge ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMangaDetail(badge.mangaId) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MangaCover(
                        backend = backend,
                        sourceId = badge.sourceId,
                        thumbnailUrl = badge.thumbnailUrl,
                        coverPath = badge.coverPath,
                        modifier = Modifier
                            .width(40.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(FolioTokens.space2))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            badge.title,
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "+${badge.newChapterCount} " +
                                if (badge.newChapterCount == 1) "chapter" else "chapters",
                            style = FolioTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FolioTheme.colors.accentDiscovery
                        )
                    }
                }
            }
        }
    }
}

/**
 * §11.4 Discover: LATEST browse hits not already in the library. Tapping persists
 * the entry for the detail screen without adding it to the library (the browse
 * screen's contract).
 */
@Composable
internal fun DiscoverCard(
    items: List<MangaDiscoverItem>,
    backend: MangaBackend,
    onOpenDiscover: (MangaDiscoverItem) -> Unit
) {
    FolioSectionCard(title = "Discover", accent = FolioTheme.colors.accentDiscovery) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            items(items.size) { index ->
                val item = items[index]
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .clickable { onOpenDiscover(item) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(132.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        MangaCover(
                            backend = backend,
                            sourceId = item.sourceId,
                            thumbnailUrl = item.thumbnailUrl,
                            coverPath = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        item.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "From ${item.sourceName}",
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
