package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ml.EchoHit
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.readerVeilAlpha

/**
 * The Echoes side panel — cross-book resonant passages for the reader's current selection.
 *
 * Each result is a "land fragment": the same palette and shape language as the Atlas, tinted by
 * its source book's cover accent, so Echoes reads as pieces of the map surfacing beside the page.
 * Resonance (the cosine score) is shown as the fragment's brightness rather than a number — a
 * stronger echo is a warmer, more present card. The states are honest: a spinner while the
 * lookup runs, "No echoes found." when the library holds nothing comparable (a real answer, not
 * an error), and the fragment list otherwise.
 */
@Composable
internal fun EchoesPanel(
    state: EchoesState,
    onDismiss: () -> Unit,
    onOpenEcho: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit,
) {
    val panelShape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .folioVeil(panelShape, fillAlpha = FolioTheme.readerVeilAlpha)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Echoes", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
                Text(
                    "Resonant passages from your other books",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close echoes",
                    tint = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = FolioTheme.colors.outlineVariant)

        when (state) {
            is EchoesState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            is EchoesState.Empty, EchoesState.Idle -> Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    "No echoes found.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
            is EchoesState.Results -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.hits, key = { it.chunkId ?: (it.bookId + it.charStart) }) { hit ->
                    EchoFragmentCard(hit = hit, onOpenEcho = onOpenEcho)
                }
            }
        }
    }
}

/** One "land fragment": a book's resonant passage, tinted by its cover and lit by its resonance. */
@Composable
private fun EchoFragmentCard(
    hit: EchoHit,
    onOpenEcho: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit,
) {
    val accent = rememberCoverAccent(hit.coverPath, FolioTheme.colors.accentDiscovery)
    // Resonance → presence. The floor is MIN_SIMILARITY (0.35); map the useful band to a gentle
    // fill so a stronger echo reads as a warmer, more solid fragment.
    val resonance = ((hit.score - 0.35f) / 0.35f).coerceIn(0f, 1f)
    val fillAlpha = 0.10f + resonance * 0.14f
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = fillAlpha), shape)
            .clickable {
                onOpenEcho(hit.bookId, hit.spineIndex.takeIf { it >= 0 }, hit.chapterFraction.takeIf { it >= 0f })
            }
            .padding(vertical = 12.dp, horizontal = 12.dp),
    ) {
        // The fragment's ragged coast: a short accent bar down the leading edge.
        Box(Modifier.width(3.dp).height(40.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = hit.snippet,
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = hit.bookTitle.ifBlank { "Untitled" },
                style = FolioTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
