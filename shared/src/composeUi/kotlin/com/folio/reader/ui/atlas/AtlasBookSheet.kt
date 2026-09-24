package com.folio.reader.ui.atlas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ml.AtlasBook
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.theme.FolioTheme

/** A related book, resolved from a similarity [com.folio.reader.ml.AtlasEdge] for the sheet. */
internal data class AtlasRelated(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val accent: Color,
)

private val SHEET_INK = Color(0xFFF1F0FA)
private val SHEET_SUB = Color(0xFFAEB2D8)
private val SHEET_TOP = Color(0xFF161A34)
private val SHEET_BOTTOM = Color(0xFF090B16)

/**
 * The cosmic selected-book sheet. Deliberately not a Material bottom sheet: it draws its own
 * near-black indigo surface with a thin accent rim so it reads as part of the galaxy rather than a
 * white card dropped on top, and it never scrims the map behind it. Collapsed it shows the cover,
 * title, author, progress and the primary actions; dragged (or tapped) up it reveals the book's
 * themes, a representative passage and its constellation neighbours. Dragging down dismisses it.
 *
 * It animates in from below only when a book is selected — never on first load.
 */
@Composable
internal fun AtlasBookSheet(
    book: AtlasBook?,
    author: String?,
    accent: Color,
    related: List<AtlasRelated>,
    themes: List<String>,
    passage: String?,
    description: String? = null,
    genre: String? = null,
    isBridge: Boolean = false,
    onOpen: (String) -> Unit,
    onReadPassage: () -> Unit,
    onSelectRelated: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Retain the last selected book across the exit animation so the sheet doesn't blank mid-slide.
    val lastBook = remember { mutableStateOf<AtlasBook?>(null) }
    if (book != null) lastBook.value = book
    val shown = lastBook.value

    AnimatedVisibility(
        visible = book != null,
        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        if (shown == null) return@AnimatedVisibility
        var expanded by remember(shown.bookId) { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(SHEET_TOP.copy(alpha = 0.97f), SHEET_BOTTOM.copy(alpha = 0.98f))))
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.4f), Color.Transparent)),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                )
                .animateContentSize(tween(280))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle — tap to expand/collapse, drag up to expand, drag down to dismiss.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(shown.bookId) {
                        var dy = 0f
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dy > 90f) onDismiss() else if (dy < -60f) expanded = true
                                dy = 0f
                            },
                            onVerticalDrag = { _, d -> dy += d },
                        )
                    }
                    .clickable { expanded = !expanded }
                    .padding(top = 10.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.28f))
                        .width(38.dp)
                        .height(4.dp),
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 52.dp, height = 74.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.18f)),
                ) {
                    BookCover(
                        coverPath = shown.coverPath,
                        title = shown.title,
                        author = author.orEmpty(),
                        small = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = shown.title.ifBlank { "Untitled" },
                        style = FolioTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = SHEET_INK,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!author.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = author,
                            style = FolioTheme.typography.bodySmall,
                            color = SHEET_SUB,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!genre.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        GenreChip(genre, accent, isBridge)
                    }
                    if (themes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        ThemeChips(themes.take(3), accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(shown.readFraction, accent)
                }
            }

            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "\u201C" + description.trim().take(160).trim() + "\u201D",
                    style = FolioTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Light, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = SHEET_INK.copy(alpha = 0.82f),
                    maxLines = if (expanded) 5 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(
                    label = "Open",
                    filled = true,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpen(shown.bookId) },
                )
                if (passage != null || themes.isNotEmpty()) {
                    SheetButton(
                        label = "Read a passage",
                        filled = false,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onClick = onReadPassage,
                    )
                }
            }

            if (expanded) {
                if (!passage.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("A PASSAGE")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "\u201C" + passage.trim().take(220).trim() + "\u201D",
                        style = FolioTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Light),
                        color = SHEET_INK.copy(alpha = 0.82f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (related.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("NEARBY IN YOUR UNIVERSE")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 8.dp),
                    ) {
                        items(related, key = { it.id }) { r ->
                            RelatedCard(r) { onSelectRelated(r.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float, accent: Color) {
    val f = fraction.coerceIn(0f, 1f)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(f.coerceAtLeast(0.01f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent),
            ) {}
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                f <= 0f -> "Not started"
                f >= 0.999f -> "Finished"
                else -> "${(f * 100).toInt()}% read"
            },
            style = FolioTheme.typography.labelSmall,
            color = SHEET_SUB,
        )
    }
}

@Composable
private fun GenreChip(genre: String, accent: Color, bridge: Boolean) {
    val ink = lerp(accent, Color.White, 0.45f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.22f))
                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = genre,
                    style = FolioTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (bridge) {
            Text(
                text = "· bridges genres",
                style = FolioTheme.typography.labelSmall,
                color = SHEET_SUB,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ThemeChips(themes: List<String>, accent: Color) {
    val ink = lerp(accent, Color.White, 0.35f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.forEach { t ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = t,
                    style = FolioTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = FolioTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
        color = SHEET_SUB.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SheetButton(
    label: String,
    filled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val base = if (filled) {
        Modifier.background(accent.copy(alpha = 0.92f), shape)
    } else {
        Modifier
            .background(Color.White.copy(alpha = 0.05f), shape)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(base)
            .clickable(onClick = onClick)
            .height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FolioTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) pickReadableInk(accent) else SHEET_INK,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun RelatedCard(r: AtlasRelated, onClick: () -> Unit) {
    Column(
        Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 66.dp, height = 94.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(r.accent.copy(alpha = 0.18f))
                .border(1.dp, r.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
        ) {
            BookCover(
                coverPath = r.coverPath,
                title = r.title,
                author = r.author,
                small = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = r.title.ifBlank { "Untitled" },
            style = FolioTheme.typography.labelSmall,
            color = SHEET_SUB,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp),
        )
    }
}

/** Black or white text, whichever reads better on [bg]. */
private fun pickReadableInk(bg: Color): Color {
    val l = bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f
    return if (l > 0.6f) Color(0xFF0A0C18) else Color(0xFFF5F6FF)
}
