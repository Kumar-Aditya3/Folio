package com.folio.reader.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.theme.FolioTheme

@Composable
internal fun BookHeaderSection(
    book: Book,
    series: Series?,
    collections: List<Collection>,
    tags: List<Tag>,
    sessionsCount: Int,
    wordsRead: Long,
    highlightsCount: Int,
    bookmarksCount: Int,
    notesCount: Int,
    onTagClick: (Tag) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onCollectionClick: (Collection) -> Unit,
    onCoverClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            CoverImage(book = book, onCoverClick = onCoverClick)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = book.title,
                    style = FolioTheme.typography.headlineSmall,
                    color = FolioTheme.colors.onSurface
                )

                book.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = FolioTheme.typography.titleMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }

                Text(
                    text = book.displayAuthor,
                    style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.primary,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProgressRing(
                        progress = book.normalizedProgress.toFloat(),
                        modifier = Modifier.size(56.dp),
                        strokeWidth = 5f
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${book.progressPercent}% complete",
                            style = FolioTheme.typography.labelLarge,
                            color = FolioTheme.colors.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = estimateReadingTime(book, wordsRead),
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        BookMetadataGrid(book = book)

        BookChipsRow(
            series = series,
            collections = collections,
            tags = tags,
            onTagClick = onTagClick,
            onSeriesClick = onSeriesClick,
            onCollectionClick = onCollectionClick
        )

        book.description?.takeIf { it.isNotBlank() }?.let {
            BookDescription(description = it)
        }
    }
}

@Composable
private fun CoverImage(book: Book, onCoverClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .width(140.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCoverClick() }
    ) {
        BookCover(
            coverPath = book.coverPath,
            title = book.title,
            author = book.authors.firstOrNull() ?: ""
        )
    }
}

@Composable
private fun BookMetadataGrid(book: Book) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = FolioTheme.colors.surface,
            contentColor = FolioTheme.colors.onSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetadataRow("Publisher", book.publisher ?: "—")
            MetadataRow("Language", book.language ?: "—")
            MetadataRow("ISBN", book.isbn ?: "—")
            MetadataRow("Pages/Chapters", "${book.chapterCount} chapters")
            MetadataRow("Words", formatCount(book.totalWords))
            book.publicationDate?.let {
                MetadataRow("Published", it.toString().take(10))
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = label,
            style = FolioTheme.typography.labelMedium,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookChipsRow(
    series: Series?,
    collections: List<Collection>,
    tags: List<Tag>,
    onTagClick: (Tag) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onCollectionClick: (Collection) -> Unit
) {
    val hasAny = series != null || collections.isNotEmpty() || tags.isNotEmpty()
    if (!hasAny) return

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        series?.let {
            AssistChip(
                onClick = { onSeriesClick(it) },
                label = { Text("Series: ${it.name}") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = FolioTheme.colors.primaryContainer,
                    labelColor = FolioTheme.colors.onPrimaryContainer
                )
            )
        }

        collections.forEach { collection ->
            AssistChip(
                onClick = { onCollectionClick(collection) },
                label = { Text(collection.name) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = FolioTheme.colors.tertiaryContainer,
                    labelColor = FolioTheme.colors.onTertiaryContainer
                )
            )
        }

        tags.forEach { tag ->
            SuggestionChip(
                onClick = { onTagClick(tag) },
                label = { Text(tag.name) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = FolioTheme.colors.secondaryContainer,
                    labelColor = FolioTheme.colors.onSecondaryContainer
                )
            )
        }
    }
}

private fun estimateReadingTime(book: Book, wordsRead: Long): String {
    val wpm = 220
    val remainingWords = (book.totalWords - wordsRead).coerceAtLeast(0L)
    val totalMinutes = remainingWords / wpm
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L && minutes == 0L -> "Finished"
        hours == 0L -> "${minutes}m left at ${wpm} wpm"
        else -> "${hours}h ${minutes}m left at ${wpm} wpm"
    }
}
