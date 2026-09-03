package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.theme.FolioTheme

/**
 * List and compact-list presentations of the books shelf (§6 split of LibraryScreen.kt).
 *
 * [finishEstimates] is §5.1's caption source, keyed by book id; a book absent from
 * the map renders no caption rather than a placeholder.
 */
@Composable
fun BookList(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean,
    finishEstimates: Map<String, String> = emptyMap()
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = com.folio.reader.ui.theme.FolioTokens.space2, bottom = com.folio.reader.ui.theme.FolioTokens.spaceMovement)
    ) {
        items(books) { book ->
            BookListItem(
                book = book,
                isSelected = book.id in selectedBooks,
                isSelectionMode = isSelectionMode,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
                onDeleteBook = { onDeleteBook(book) },
                finishEstimate = finishEstimates[book.id]
            )
        }
    }
}

/**
 * A list-mode shelf row: plate, type, hairline. Selection tints the row's
 * background rather than swapping a card colour, so the artwork stays legible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteBook: (Book) -> Unit,
    finishEstimate: String? = null
) {
    val interaction = com.folio.reader.ui.components.rememberFolioInteraction()
    val colors = FolioTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioPressable(interaction, scaleTo = 0.99f)
                .background(
                    if (isSelected) colors.primary.copy(alpha = 0.14f)
                    else androidx.compose.ui.graphics.Color.Transparent
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(
                    horizontal = com.folio.reader.ui.theme.FolioTokens.gutter,
                    vertical = com.folio.reader.ui.theme.FolioTokens.space2,
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.folio.reader.ui.components.FolioCoverPlate(
                coverPath = book.coverPath,
                title = book.title,
                author = book.displayAuthor,
                width = com.folio.reader.ui.theme.FolioTokens.coverInline,
                shape = com.folio.reader.ui.theme.FolioShapes.plateSmall,
                elevation = 5.dp,
                small = true,
            )

            Spacer(modifier = Modifier.width(com.folio.reader.ui.theme.FolioTokens.space3))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = FolioTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    book.displayAuthor,
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // One caption line, ordered by usefulness: progress, then the
                // projection, then a notable status.
                val caption = when {
                    book.normalizedProgress > 0 && finishEstimate != null ->
                        "${book.progressPercent}% · $finishEstimate"
                    book.normalizedProgress > 0 -> "${book.progressPercent}%"
                    book.status != BookStatus.UNREAD && book.status != BookStatus.READING ->
                        book.status.name.lowercase().replaceFirstChar { it.uppercase() }
                    else -> null
                }
                if (caption != null) {
                    Text(
                        caption,
                        style = FolioTheme.typography.labelSmall,
                        color = if (book.normalizedProgress > 0) colors.accentProgress
                        else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // §2.6: rows use the bar, plates use the seam — never both here.
            if (book.normalizedProgress > 0 && book.normalizedProgress < 1) {
                FolioProgressBar(
                    progress = book.normalizedProgress.toFloat(),
                    modifier = Modifier.width(52.dp),
                    color = colors.accentProgress
                )
            }

            BookOptionsDropdown(
                book = book,
                onBookClick = { onClick() },
                onDeleteBook = onDeleteBook,
            )
        }
        com.folio.reader.ui.components.FolioRule(
            modifier = Modifier.padding(horizontal = com.folio.reader.ui.theme.FolioTokens.gutter)
        )
    }
}

@Composable
fun BookCompactList(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean,
    finishEstimates: Map<String, String> = emptyMap()
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = com.folio.reader.ui.theme.FolioTokens.space2, bottom = com.folio.reader.ui.theme.FolioTokens.spaceMovement)
    ) {
        items(books) { book ->
            BookCompactItem(
                book = book,
                isSelected = book.id in selectedBooks,
                isSelectionMode = isSelectionMode,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
                onDeleteBook = { onDeleteBook(book) },
                finishEstimate = finishEstimates[book.id]
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCompactItem(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteBook: (Book) -> Unit,
    finishEstimate: String? = null
) {
    val colors = FolioTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(
                    if (isSelected) colors.primary.copy(alpha = 0.14f)
                    else androidx.compose.ui.graphics.Color.Transparent
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(
                    horizontal = com.folio.reader.ui.theme.FolioTokens.gutter,
                    vertical = com.folio.reader.ui.theme.FolioTokens.space1,
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.folio.reader.ui.components.FolioCoverPlate(
                coverPath = book.coverPath,
                title = book.title,
                author = book.displayAuthor,
                width = 30.dp,
                shape = com.folio.reader.ui.theme.FolioShapes.plateSmall,
                elevation = 3.dp,
                small = true,
            )

            Spacer(Modifier.width(com.folio.reader.ui.theme.FolioTokens.space3))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = FolioTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusPart = if (book.status != BookStatus.UNREAD && book.status != BookStatus.READING)
                    " · ${book.toCardData().statusLabel}" else ""
                // §5.1 caption appended only when a projection exists.
                val pacePart = finishEstimate?.let { " · $it" } ?: ""
                Text(
                    "${book.displayAuthor} · ${book.progressPercent}%$statusPart$pacePart",
                    style = FolioTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            BookOptionsDropdown(
                book = book,
                onBookClick = { onClick() },
                onDeleteBook = onDeleteBook,
            )
        }
        com.folio.reader.ui.components.FolioRule(
            modifier = Modifier.padding(horizontal = com.folio.reader.ui.theme.FolioTokens.gutter)
        )
    }
}
