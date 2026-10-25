package com.folio.reader.ui.home

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import com.folio.reader.ui.manga.MangaCoverPlate

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaNewChapterBadge
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
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
internal fun MangaPlate(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String?,
    width: Dp,
    modifier: Modifier = Modifier,
    elevation: Dp = FolioTokens.elevationVeil,
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

// §11.4 manga Continue reading used to live here as its own shelf. It is gone on
// purpose: manga now rank alongside books in Home's Reading now (see
// ReadingNowItem), so a second "Continue reading" heading further down the page
// would have been the same question asked twice.
//
// MangaPlate survives because the merged shelf and anchor still need a
// network-loaded cover in the plate language.

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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            Box(Modifier.width(88.dp).height(66.dp)) {
                badges.take(3).asReversed().forEachIndexed { index, badge ->
                    MangaCoverPlate(backend, badge.sourceId, badge.thumbnailUrl, badge.coverPath, width = 36.dp,
                        modifier = Modifier.offset(x = (index * 22).dp, y = (index * 3).dp)
                            .graphicsLayer { rotationZ = (index - 1) * 6f })
                }
            }
            Column {
                FolioEyebrow("New chapters", accent = FolioTheme.colors.accentDiscovery)
                Text("${badges.sumOf { it.newChapterCount }} chapters on your shelf",
                    style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(FolioTokens.space2))
        badges.forEachIndexed { index, badge ->
            if (index > 0) FolioRule()
            val interaction = rememberFolioInteraction()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .folioPressable(interaction)
                    .clickable(interactionSource = interaction, indication = null) {
                        onOpenMangaDetail(badge.mangaId)
                    }
                    .padding(vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MangaPlate(
                    backend = backend,
                    sourceId = badge.sourceId,
                    thumbnailUrl = badge.thumbnailUrl,
                    coverPath = badge.coverPath,
                    width = 38.dp,
                    elevation = FolioTokens.elevationPanel,
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
