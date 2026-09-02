package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                BookCover(
                    coverPath = book.coverPath,
                    title = book.title,
                    author = book.authors.firstOrNull() ?: "",
                    small = true
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    book.title,
                    style = FolioTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    book.displayAuthor,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (book.normalizedProgress > 0) {
                        Text(
                            "${book.progressPercent}%",
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary
                        )
                    }
                    if (book.status != BookStatus.UNREAD && book.status != BookStatus.READING) {
                        Text(
                            book.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant
                        )
                    }
                    // §5.1 caption; absent when no projection exists.
                    finishEstimate?.let {
                        Text(
                            it,
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // §2.6: rows use the bar, covers use the ring — never both.
            if (book.normalizedProgress > 0 && book.normalizedProgress < 1) {
                FolioProgressBar(
                    progress = book.normalizedProgress.toFloat(),
                    modifier = Modifier.width(60.dp),
                    color = FolioTheme.colors.primary
                )
            }

            BookOptionsDropdown(
                book = book,
                onBookClick = { onClick() },
                onDeleteBook = onDeleteBook,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(32.dp)
                .height(48.dp)
        ) {
            BookCover(
                coverPath = book.coverPath,
                title = book.title,
                author = book.authors.firstOrNull() ?: "",
                small = true
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(book.title, style = FolioTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val statusPart = if (book.status != BookStatus.UNREAD && book.status != BookStatus.READING)
                " · ${book.toCardData().statusLabel}" else ""
            // §5.1 caption appended only when a projection exists.
            val pacePart = finishEstimate?.let { " · $it" } ?: ""
            Text(
                "${book.displayAuthor} · ${book.progressPercent}%$statusPart$pacePart",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        BookOptionsDropdown(
            book = book,
            onBookClick = { onClick() },
            onDeleteBook = onDeleteBook,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}
