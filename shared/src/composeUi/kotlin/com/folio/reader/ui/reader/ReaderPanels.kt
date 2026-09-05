package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha

private fun Int.argbHex(): String = "#" + toUInt().toString(16).padStart(8, '0').drop(2)

/**
 * Builds the page-side glass overlay HTML for platforms whose embedded browser
 * occludes Compose overlays; null everywhere else. Mirrors which panel is open.
 */
internal fun readerOverlayHtml(
    occludes: Boolean,
    showReaderPanel: Boolean,
    showToc: Boolean,
    showAnnotations: Boolean,
    noteDraftFor: String?,
    settings: ReaderSettings,
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    notes: List<Note>,
    quickFontNames: List<String>,
    readerThemePreset: com.folio.reader.settings.Theme,
    overlayColors: com.folio.reader.ui.render.OverlayColors,
    chapterLabel: (spineIndex: Int?, chapterId: String?) -> String
): String? = when {
    !occludes -> null
    showReaderPanel -> com.folio.reader.ui.render.OverlayUi.settings(
        fontSize = settings.fontSize,
        lineHeight = settings.lineHeight,
        margin = settings.margins.left,
        fontFamily = settings.fontFamily,
        fontOptions = quickFontNames,
        themeId = settings.themeId,
        layoutMode = settings.layoutMode.normalized.name,
        themes = com.folio.reader.settings.Theme.PICKER.map { t ->
            Triple(t.id, t.name, t.background.argbHex())
        },
        highlightColors = readerThemePreset.highlightColors.map { it.argbHex() },
        highlightIndex = settings.highlightColorIndex,
        c = overlayColors
    )
    showToc -> com.folio.reader.ui.render.OverlayUi.toc(
        chapters = chapters.map { it.title },
        current = currentChapterIndex,
        c = overlayColors
    )
    noteDraftFor != null -> {
        val hl = highlights.firstOrNull { it.id == noteDraftFor }
        com.folio.reader.ui.render.OverlayUi.noteComposer(
            highlightId = noteDraftFor ?: "",
            quote = hl?.selectedText?.take(280) ?: "",
            existing = hl?.noteId?.let { nid -> notes.firstOrNull { it.id == nid }?.content } ?: "",
            c = overlayColors
        )
    }

    showAnnotations -> com.folio.reader.ui.render.OverlayUi.annotations(
        bookmarks = bookmarks.map {
            com.folio.reader.ui.render.OverlayUi.AnnotationRow("bm", it.id, it.label?.takeIf { l -> l.isNotBlank() } ?: "Bookmark", chapterLabel(it.spineIndex, it.chapterId))
        },
        highlights = highlights.filter { !it.isDeleted }.map { h ->
            com.folio.reader.ui.render.OverlayUi.AnnotationRow(
                kind = "hl", id = h.id,
                title = h.selectedText.take(80).ifBlank { "(highlight)" },
                sub = chapterLabel(h.spineIndex, h.chapterId),
                note = h.noteId?.let { nid -> notes.firstOrNull { it.id == nid && !it.isDeleted }?.content },
                noteId = h.noteId,
                canNote = true
            )
        },
        // Only notes no highlight owns; linked ones render under their highlight.
        notes = notes.filter { n -> highlights.none { it.noteId == n.id } }.map {
            com.folio.reader.ui.render.OverlayUi.AnnotationRow("nt", it.id, it.content.take(80).ifBlank { "(note)" }, chapterLabel(it.spineIndex, it.chapterId))
        },
        c = overlayColors
    )
    else -> null
}

@Composable
fun TOCSidebar(
    chapters: List<Chapter>,
    currentIndex: Int,
    onChapterClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Glass, because it sits over the page. A 26dp leading sweep so the panel
    // reads as sliding in from the edge rather than being pasted on.
    val tocShape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
    // Monochromatic palettes put `primary` within a hair of `surface`, so the
    // current-chapter marker has to pass the contrast guard rather than trust the
    // raw accent. Each theme keeps its own hue; only unreadable values move.
    val accent = rememberLegibleAccent(FolioTheme.colors.primary)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .folioVeil(tocShape, fillAlpha = FolioTheme.readerVeilAlpha)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
                Text(
                    "Contents",
                    style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.onSurface
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(color = FolioTheme.colors.outlineVariant)

            val initialScrollIndex = (currentIndex - 1).coerceAtLeast(0)
            // Positioned on open only. While the panel stays open the list belongs
            // to the user — re-scrolling on chapter changes is what made it jump.
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(chapters, key = { _, chapter -> "${chapter.bookId}:${chapter.id}" }) { index, chapter ->
                    val isCurrent = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(index) }
                            // The current row gets a wash of its own so the state is
                            // visible without relying on the 3dp marker alone.
                            .background(
                                if (isCurrent) accent.copy(alpha = 0.12f) else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current-chapter accent bar
                        Box(
                            Modifier
                                .size(width = 3.dp, height = 18.dp)
                                .background(
                                    if (isCurrent) accent else Color.Transparent,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = chapter.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = FolioTheme.typography.bodySmall.copy(
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isCurrent) accent else FolioTheme.colors.onSurface
                        )
                    }
                }
            }
        }
}

@Composable
fun AnnotationsSidebar(
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    notes: List<Note>,
    onDismiss: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSetHighlightNote: (highlightId: String, content: String) -> Unit = { _, _ -> },
    chapterLabel: (spineIndex: Int?, chapterId: String?) -> String = { spine, _ -> "Spine ${(spine ?: 0) + 1}" },
    onJump: (kind: String, id: String) -> Unit = { _, _ -> }
) {
    var noteDraftFor by remember { mutableStateOf<String?>(null) }
    var noteContent by remember { mutableStateOf("") }
    // A dismissed (not cancelled) dialog keeps its unsaved draft for this highlight.
    var draftKeptFor by remember { mutableStateOf<String?>(null) }
    val noteById = notes.filter { !it.isDeleted }.associateBy { it.id }
    // Notes no highlight owns — everything made before notes lived on highlights.
    val orphanNotes = notes.filter { n -> !n.isDeleted && highlights.none { it.noteId == n.id } }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .folioVeil(
                RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp),
                fillAlpha = FolioTheme.readerVeilAlpha,
            )
            .padding(vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Annotations",
                style = FolioTheme.typography.titleLarge,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close annotations",
                    tint = FolioTheme.colors.onSurfaceVariant
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (bookmarks.isNotEmpty()) {
                item { SectionHeader("Bookmarks (${bookmarks.size})", Icons.Filled.Bookmark) }
                items(bookmarks, key = { "bm:${it.id}" }) { bookmark ->
                    AnnotationRow(
                        title = bookmark.label ?: "Page ${bookmark.spineIndex + 1}",
                        subtitle = chapterLabel(bookmark.spineIndex, bookmark.chapterId),
                        leadingIcon = Icons.Filled.Bookmark,
                        onClick = { onJump("bm", bookmark.id) },
                        onDelete = { onRemoveBookmark(bookmark.id) }
                    )
                }
            }
            if (highlights.isNotEmpty()) {
                item { SectionHeader("Highlights (${highlights.size})", Icons.Filled.Highlight) }
                items(highlights.filter { !it.isDeleted }, key = { "hl:${it.id}" }) { highlight ->
                    val linked = highlight.noteId?.let { noteById[it] }
                    AnnotationRow(
                        title = highlight.selectedText.take(80).ifBlank { "(empty)" },
                        subtitle = chapterLabel(highlight.spineIndex, highlight.chapterId),
                        accentColor = Color(highlight.effectiveColor),
                        note = linked?.content,
                        onNote = {
                            if (draftKeptFor != highlight.id) noteContent = linked?.content ?: ""
                            draftKeptFor = null
                            noteDraftFor = highlight.id
                        },
                        onClick = { onJump("hl", highlight.id) },
                        onDelete = { onRemoveHighlight(highlight.id) }
                    )
                }
            }
            if (orphanNotes.isNotEmpty()) {
                item { SectionHeader("Notes (${orphanNotes.size})", Icons.Filled.Notes) }
                items(orphanNotes, key = { "nt:${it.id}" }) { note ->
                    AnnotationRow(
                        title = note.content.take(80).ifBlank { "(empty)" },
                        subtitle = chapterLabel(note.spineIndex, note.chapterId),
                        leadingIcon = Icons.Filled.Notes,
                        onClick = { onJump("nt", note.id) },
                        onDelete = { onRemoveNote(note.id) }
                    )
                }
            }
            if (bookmarks.isEmpty() && highlights.none { !it.isDeleted } && orphanNotes.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Bookmark spots, then select text to highlight and write a note on it.",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    noteDraftFor?.let { highlightId ->
        val passage = highlights.firstOrNull { it.id == highlightId }?.selectedText.orEmpty()
        NoteComposerDialog(
            passage = passage,
            noteContent = noteContent,
            onNoteChange = { noteContent = it },
            onSave = {
                onSetHighlightNote(highlightId, noteContent.trim())
                noteContent = ""
                draftKeptFor = null
                noteDraftFor = null
            },
            onDismiss = { draftKeptFor = noteDraftFor; noteDraftFor = null },
            onCancel = { noteContent = ""; draftKeptFor = null; noteDraftFor = null }
        )
    }
}

@Composable
private fun SectionHeader(text: String, icon: ImageVector? = null) {
    // `secondary` is a fill role in several palettes and lands close to the veil,
    // so section headers take the guarded value.
    val label = rememberLegibleAccent(
        FolioTheme.colors.secondary,
        fallback = FolioTheme.colors.onSurfaceVariant,
        minRatio = 3.0,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = label
            )
        }
        Text(
            text = text,
            style = FolioTheme.typography.titleSmall,
            color = label
        )
    }
}

@Composable
private fun AnnotationRow(
    title: String,
    subtitle: String,
    accentColor: Color? = null,
    leadingIcon: ImageVector? = null,
    note: String? = null,
    onNote: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onDelete: () -> Unit
) {
    val accent = rememberLegibleAccent(FolioTheme.colors.primary)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FolioTokens.radiusControl))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (accentColor != null) {
            Box(Modifier.width(3.dp).height(32.dp).background(accentColor, RoundedCornerShape(2.dp)))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        onNote?.let { action ->
            TextButton(
                onClick = action,
                modifier = Modifier.size(width = 56.dp, height = 32.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = if (note.isNullOrBlank()) "Note" else "Edit",
                    style = FolioTheme.typography.labelSmall,
                    color = accent
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
        if (!note.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(start = 13.dp, end = 12.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(IntrinsicSize.Min)
                        .background(accent, RoundedCornerShape(1.dp))
                )
                Text(
                    text = note,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
