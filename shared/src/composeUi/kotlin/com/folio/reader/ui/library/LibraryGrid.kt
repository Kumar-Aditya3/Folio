package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Grid presentation of the books shelf (§6 split of LibraryScreen.kt).
 *
 * [finishEstimates] is §5.1's caption source, keyed by book id; a book absent
 * from the map renders no caption rather than a placeholder.
 */
@Composable
fun BookGrid(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean,
    finishEstimates: Map<String, String> = emptyMap()
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(books) { book ->
            BookCard(
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
fun BookCard(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteBook: (Book) -> Unit,
    finishEstimate: String? = null
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hovered) 1.04f else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 200,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "cardScale"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(
                if (isSelected) Modifier.border(
                    2.dp, FolioTheme.colors.primary, RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hovered) 8.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) FolioTheme.colors.primaryContainer else FolioTheme.colors.surface,
            contentColor = if (isSelected) FolioTheme.colors.onPrimaryContainer else FolioTheme.colors.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                BookCover(
                    coverPath = book.coverPath,
                    title = book.title,
                    author = book.authors.firstOrNull() ?: ""
                )
                BookOptionsDropdown(
                    book = book,
                    onBookClick = { onClick() },
                    onDeleteBook = onDeleteBook,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    iconTint = Color.White
                )
                // §5.1: progress decorates the cover as a ring. §2.6 forbids a ring
                // and a bar in the same view, so the bar that used to sit under the
                // cover is gone. A finished or untouched book shows nothing.
                if (book.normalizedProgress > 0.0 && book.normalizedProgress < 0.99) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(FolioTokens.space1)
                            .size(FolioTokens.ringSmall)
                            .clip(CircleShape)
                            .background(FolioTheme.colors.surface.copy(alpha = 0.85f))
                            .semantics {
                                contentDescription = "${book.progressPercent}% read"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ProgressRing(
                            progress = book.normalizedProgress.toFloat(),
                            modifier = Modifier.size(FolioTokens.ringSmall),
                            strokeWidth = 3f,
                            color = FolioTheme.colors.primary,
                            trackColor = FolioTheme.colors.outlineVariant
                        )
                    }
                }
            }

            // Title
            Text(
                text = book.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = FolioTheme.typography.labelLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            // Author
            Text(
                text = book.displayAuthor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            // Progress text
            if (book.normalizedProgress > 0) {
                Text(
                    text = "${book.progressPercent}%",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // §5.1: "~6 days left" — omitted entirely when no projection exists,
            // so a fresh book gets no placeholder row.
            finishEstimate?.let { estimate ->
                Text(
                    text = estimate,
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }

            // Status badge: progress already implies reading, so the badge only
            // flags notable states (paused, finished, abandoned).
            if (book.status != BookStatus.UNREAD && book.status != BookStatus.READING) {
                Surface(
                    modifier = Modifier.padding(top = 4.dp),
                    shape = RoundedCornerShape(50),
                    color = FolioTheme.colors.secondaryContainer
                ) {
                    Text(
                        text = book.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun BookOptionsDropdown(
    book: Book,
    onBookClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = FolioTheme.colors.onSurfaceVariant
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Book options",
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Book Details") },
                onClick = {
                    expanded = false
                    onBookClick(book)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete Book", color = FolioTheme.colors.error) },
                onClick = {
                    expanded = false
                    onDeleteBook(book)
                }
            )
        }
    }
}
