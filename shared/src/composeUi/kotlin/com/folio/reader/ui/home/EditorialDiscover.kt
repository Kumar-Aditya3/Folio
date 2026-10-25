package com.folio.reader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.ui.components.FolioArtwork
import com.folio.reader.ui.components.FolioArtworkScene
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.manga.MangaCoverPlate
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.flow.distinctUntilChanged

/** A settled shelf selection lights the spread; scrolling never changes card dimensions. */
@Composable
internal fun DiscoverCard(items: List<MangaDiscoverItem>, backend: MangaBackend,
    onOpenDiscover: (MangaDiscoverItem) -> Unit) {
    if (items.isEmpty()) return
    val scroll = rememberLazyListState()
    var focused by remember(items.map { "${it.sourceId}:${it.url}" }) { mutableIntStateOf(0) }
    LaunchedEffect(scroll) {
        snapshotFlow { if (scroll.isScrollInProgress) null else scroll.firstVisibleItemIndex }
            .distinctUntilChanged().collect { it?.let { index -> focused = index } }
    }
    val lead = items[focused.coerceIn(items.indices)]
    FolioArtworkScene(FolioArtwork("discover:${lead.sourceId}:${lead.url}", null,
        lead.sourceId, lead.thumbnailUrl), Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            FolioEyebrow("Discover", accent = FolioTheme.colors.accentDiscovery)
            Text("New from your sources", style = FolioTheme.typography.titleMedium,
                color = FolioTheme.colors.onSurface)
            LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(items, key = { _, item -> "${item.sourceId}:${item.url}" }) { index, item ->
                    val width = 92.dp
                    val interaction = rememberFolioInteraction()
                    Column(Modifier.width(width).folioPressable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onOpenDiscover(item) }.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MangaCoverPlate(backend, item.sourceId, item.thumbnailUrl, width = width,
                            elevation = FolioTokens.elevationRaised)
                        Text(item.title, style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.sourceName, style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
