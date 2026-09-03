package com.folio.reader.ui.quotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaNote
import com.folio.reader.ui.theme.FolioTheme

/** A manga reader note shown alongside book quotes in the annotation hub (§11.5). */
data class MangaQuoteItem(
    val note: MangaNote,
    val mangaId: String,
    val mangaTitle: String,
    val chapterTitle: String?
)

@Composable
internal fun MangaQuoteCard(
    item: MangaQuoteItem,
    onClick: () -> Unit
) {
    // Same pull-quote language as book quotes, marked "Manga" by a tertiary pill
    // rather than by a different container.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        com.folio.reader.ui.components.FolioCallout(accent = FolioTheme.colors.tertiary) {
            Box(
                modifier = Modifier
                    .background(
                        FolioTheme.colors.tertiary.copy(alpha = 0.16f),
                        com.folio.reader.ui.theme.FolioShapes.pill,
                    )
            ) {
                Text(
                    text = "Manga",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.tertiary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }

            Text(
                text = "\u201C${item.note.content}\u201D",
                style = FolioTheme.typography.quote,
                color = FolioTheme.colors.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = item.mangaTitle,
                style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.chapterTitle ?: "Chapter"} \u00B7 page ${item.note.pageIndex + 1}",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
