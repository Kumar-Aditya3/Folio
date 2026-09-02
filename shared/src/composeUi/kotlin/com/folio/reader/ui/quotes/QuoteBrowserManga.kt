package com.folio.reader.ui.quotes

import androidx.compose.foundation.clickable
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = FolioTheme.colors.surface,
            contentColor = FolioTheme.colors.onSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                color = FolioTheme.colors.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Manga",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "\u201C${item.note.content}\u201D",
                style = FolioTheme.typography.quote,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = item.mangaTitle,
                style = FolioTheme.typography.titleSmall,
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
