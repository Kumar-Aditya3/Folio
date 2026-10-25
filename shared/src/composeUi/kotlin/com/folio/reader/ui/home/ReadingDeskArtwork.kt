package com.folio.reader.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folio.reader.ui.components.FolioArtwork
import com.folio.reader.ui.components.FolioArtworkField
import kotlin.math.abs

internal fun deskArtworkWeight(index: Int, position: Float, count: Int): Float =
    (1f - abs(shelfRelative(index, position, count))).coerceIn(0f, 1f)

@Composable
internal fun ReadingDeskArtwork(items: List<ReadingNowItem>, controller: RevolvingShelfState,
    animated: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    FolioArtworkField(items.map { FolioArtwork(it.shelfKey(), it.coverPath,
        it.sourceId.takeIf { _ -> it.kind == HomeItemKind.MANGA }, it.thumbnailUrl) },
        weight = { deskArtworkWeight(it, controller.position, items.size) },
        offset = { shelfRelative(it, controller.position, items.size) },
        // Keep the travelling artwork visible without flooding the glass with cover colour.
        animated = animated, enabled = enabled, strength = .52f, modifier = modifier)
}
