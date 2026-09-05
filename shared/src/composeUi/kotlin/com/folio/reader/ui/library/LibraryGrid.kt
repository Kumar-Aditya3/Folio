package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset

/**
 * The books shelf, rebuilt as a **shelf** rather than a grid of database rows.
 *
 * Three decisions carry it:
 *
 * 1. **No cards.** A card around a cover competes with the artwork it contains.
 *    Covers now sit directly on the page as plates with contact shadows, and the
 *    metadata is type beneath them — the composition a printed shelf actually has.
 * 2. **Variable weight.** The first in-progress book spans the full width as a
 *    *featured* entry with a cover-derived halo; everything after it is a standard
 *    shelf entry. A grid where every cell is identical cannot express that one of
 *    these books is the one you are reading tonight.
 * 3. **Active books come forward.** In-progress titles render at full strength;
 *    finished and untouched ones sit back slightly. Hierarchy between active and
 *    inactive, not just sort order.
 *
 * Sorting, filtering, selection and the overflow menu are untouched.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // The feature slot goes to the first in-progress book in the current (already
    // sorted and filtered) list, so it always reflects the user's own ordering
    // rather than a second opinion about relevance. Suppressed during selection,
    // where every row must be the same target.
    val featured = remember(books) {
        books.firstOrNull { it.normalizedProgress > 0.0 && it.normalizedProgress < 0.99 }
    }
    val showFeature = featured != null && !isSelectionMode
    val rest = remember(books, featured, showFeature) {
        if (showFeature) books.filter { it.id != featured!!.id } else books
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 116.dp),
        contentPadding = PaddingValues(
            start = FolioTokens.gutter,
            end = FolioTokens.gutter,
            top = FolioTokens.space3 + LocalFolioTopInset.current,
            bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
        ),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceBeat),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
    ) {
        if (showFeature) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FeaturedShelfEntry(
                    book = featured!!,
                    finishEstimate = finishEstimates[featured.id],
                    onClick = { onBookClick(featured) },
                    onLongClick = { onBookLongClick(featured) },
                    onDeleteBook = onDeleteBook,
                )
            }
        }
        items(rest) { book ->
            BookCard(
                book = book,
                isSelected = book.id in selectedBooks,
                isSelectionMode = isSelectionMode,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
                onDeleteBook = onDeleteBook,
                finishEstimate = finishEstimates[book.id]
            )
        }
    }
}

/**
 * The featured entry: a wide, image-led composition where the cover sits at
 * `coverFeature` beside its own typography and throws a halo onto the page behind
 * it. Deliberately *not* a card — the halo and the plate's shadow do the
 * separating, so the page stays continuous.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeaturedShelfEntry(
    book: Book,
    finishEstimate: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteBook: (Book) -> Unit,
) {
    val accent = rememberCoverAccent(book.coverPath, FolioTheme.colors.accentProgress)
    val interaction = rememberFolioInteraction()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .folioPressable(interaction, scaleTo = 0.985f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioCoverPlate(
            coverPath = book.coverPath,
            title = book.title,
            author = book.displayAuthor,
            width = FolioTokens.coverFeature,
            halo = accent,
            elevation = 14.dp,
        )
        Spacer(Modifier.width(FolioTokens.space4))
        Column(modifier = Modifier.weight(1f)) {
            FolioEyebrow("Reading", accent = accent)
            Spacer(Modifier.height(3.dp))
            Text(
                text = book.title,
                style = FolioTheme.typography.titleLarge,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.displayAuthor,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(FolioTokens.space2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${book.progressPercent}%",
                    style = FolioTheme.typography.titleSmall,
                    color = accent,
                )
                if (finishEstimate != null) {
                    Spacer(Modifier.width(FolioTokens.space2))
                    Text(
                        text = finishEstimate,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
            FolioProgressBar(progress = book.normalizedProgress.toFloat(), color = accent)
        }
        BookOptionsDropdown(
            book = book,
            onBookClick = { onClick() },
            onDeleteBook = onDeleteBook,
        )
    }
}

/**
 * A standard shelf entry: the plate, then type beneath it, ranged left. No card,
 * no centred text, no hover scale.
 *
 * Selection is expressed as an accent rim *on the plate* plus a check mark rather
 * than by recolouring a card container, so a selected book still shows its
 * artwork. Inactive books (unread or finished) sit back at 0.86 alpha so the shelf
 * has a foreground and a background.
 */
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
    val interaction = rememberFolioInteraction()
    val inProgress = book.normalizedProgress > 0.0 && book.normalizedProgress < 0.99
    val colors = FolioTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioPressable(interaction)
            .graphicsLayer { alpha = if (inProgress) 1f else 0.86f }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        FolioCoverPlate(
            coverPath = book.coverPath,
            title = book.title,
            author = book.displayAuthor,
            // null width: the plate fills the grid cell and derives its height from
            // the printed trim, so a wide column never squashes the cover.
            width = null,
            overlay = {
                if (isSelected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(colors.primary.copy(alpha = 0.32f))
                            .border(2.dp, colors.primary, FolioShapes.plate)
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = colors.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                } else if (!isSelectionMode) {
                    BookOptionsDropdown(
                        book = book,
                        onBookClick = { onClick() },
                        onDeleteBook = onDeleteBook,
                        modifier = Modifier.align(Alignment.TopEnd),
                        iconTint = Color.White
                    )
                }
                // §5.1: progress as a seam on the plate's foot. §2.6 holds — one
                // progress form per view, and the ring is gone from the shelf.
                if (inProgress) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .semantics {
                                contentDescription = "${book.progressPercent}% read"
                            }
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(book.normalizedProgress.toFloat())
                                .height(3.dp)
                                .background(colors.accentProgress)
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(FolioTokens.space2))
        Text(
            text = book.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = FolioTheme.typography.labelMedium,
            color = colors.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = book.displayAuthor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = FolioTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        // One caption line only: the percentage while reading, the estimate when
        // there is one, the status when the book is notable. Three stacked captions
        // under a 116dp cover is what made the old grid feel like a form.
        val caption = when {
            inProgress && finishEstimate != null -> "${book.progressPercent}% · $finishEstimate"
            inProgress -> "${book.progressPercent}%"
            book.status != BookStatus.UNREAD && book.status != BookStatus.READING ->
                book.status.name.lowercase().replaceFirstChar { it.uppercase() }
            else -> null
        }
        if (caption != null) {
            Text(
                text = caption,
                style = FolioTheme.typography.labelSmall,
                color = if (inProgress) colors.accentProgress else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
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
