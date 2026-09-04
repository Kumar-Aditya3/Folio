package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.settings.readableLabel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@Composable
internal fun ReaderSettingsDialog(
    mode: MangaReaderMode,
    onModeChange: (MangaReaderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Reading mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderModeChip(
                        label = MangaReaderMode.WEBTOON.readableLabel(),
                        selected = mode == MangaReaderMode.WEBTOON,
                        onClick = { onModeChange(MangaReaderMode.WEBTOON) },
                    )
                    ReaderModeChip(
                        label = MangaReaderMode.PAGED_LTR.readableLabel(),
                        selected = mode == MangaReaderMode.PAGED_LTR,
                        onClick = { onModeChange(MangaReaderMode.PAGED_LTR) },
                    )
                    ReaderModeChip(
                        label = MangaReaderMode.PAGED_RTL.readableLabel(),
                        selected = mode == MangaReaderMode.PAGED_RTL,
                        onClick = { onModeChange(MangaReaderMode.PAGED_RTL) },
                    )
                    ReaderModeChip(
                        label = MangaReaderMode.PAGED_VERTICAL.readableLabel(),
                        selected = mode == MangaReaderMode.PAGED_VERTICAL,
                        onClick = { onModeChange(MangaReaderMode.PAGED_VERTICAL) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ReaderModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ReaderControls(
    mangaTitle: String,
    chapterName: String,
    pageCount: Int,
    currentPage: Int,
    mode: MangaReaderMode,
    rtl: Boolean,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onShowNotes: () -> Unit,
    onShowSettings: () -> Unit,
    onSeek: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val overlayColor = Color.Black.copy(alpha = 0.72f)

    Column(Modifier.fillMaxSize()) {
        com.folio.reader.ui.components.FolioStatusBarBand(inkBand = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(overlayColor)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    mangaTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    chapterName,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "Bookmark chapter",
                    tint = if (bookmarked) Color(0xFF8B9DC3) else Color.White,
                )
            }
            IconButton(onClick = onShowNotes) {
                Icon(
                    imageVector = Icons.Filled.EditNote,
                    contentDescription = "Notes",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onShowSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Reader settings",
                    tint = Color.White,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(overlayColor)
                .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val lastPage = (pageCount - 1).coerceAtLeast(0)
            // Progress runs in the reading direction: RTL mirrors the slider and
            // swaps the end labels so the filled side is where the reader is going.
            Text(
                if (rtl) "$pageCount" else "${currentPage + 1}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            FolioSlider(
                value = if (rtl) (lastPage - currentPage).toFloat() else currentPage.toFloat(),
                onValueChange = { onSeek(if (rtl) lastPage - it.toInt() else it.toInt()) },
                valueRange = 0f..lastPage.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = FolioTokens.space2),
                accent = Color.White,
                background = Color.Black,
                inactive = Color.White.copy(alpha = 0.28f),
            )
            Text(
                if (rtl) "${currentPage + 1}" else "$pageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun PageActionsDialog(
    pageIndex: Int,
    onNote: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page ${pageIndex + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNote)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.EditNote,
                        contentDescription = null,
                        tint = FolioTheme.colors.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Add a note", style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSave)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = FolioTheme.colors.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Save page to Downloads", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NoteDialog(
    pageIndex: Int,
    existing: com.folio.reader.manga.MangaNote?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(existing?.content ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note — page ${pageIndex + 1}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(text.trim())
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NotesListDialog(
    viewModel: MangaReaderViewModel,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf<List<com.folio.reader.manga.MangaNote>>(emptyList()) }
    val revision by viewModel.notesRevision.collectAsState()
    val colors = FolioTheme.colors

    LaunchedEffect(revision) { notes = viewModel.chapterNotes() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapter notes") },
        text = {
            if (notes.isEmpty()) {
                Text(
                    "No notes yet. Long-press a page to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notes.forEach { note ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Page ${note.pageIndex + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { viewModel.deleteNote(note.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete note",
                                    tint = colors.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
