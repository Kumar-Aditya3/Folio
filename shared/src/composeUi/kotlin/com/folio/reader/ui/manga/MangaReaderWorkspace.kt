package com.folio.reader.ui.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaNote
import com.folio.reader.ui.components.decodeCoverImage
import com.folio.reader.ui.reader.ReaderLocationLabel
import com.folio.reader.ui.reader.ReaderTool
import com.folio.reader.ui.reader.ReaderWorkspace
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MangaReaderWorkspace(
    active: ReaderTool?,
    viewModel: MangaReaderViewModel,
    onSelect: (ReaderTool) -> Unit,
    onDismiss: () -> Unit,
    onSeek: (Int) -> Unit,
    onOpenChapter: (MangaChapter) -> Unit,
    onEditNote: (MangaNote) -> Unit,
    onOpenNote: (MangaNote) -> Unit,
    layout: com.folio.reader.ui.reader.ReaderWorkspaceLayout? = null,
) {
    val chapter by viewModel.chapter.collectAsState()
    val mode by viewModel.mode.collectAsState()
    ReaderWorkspace(active, listOf(ReaderTool.Pages, ReaderTool.Saved, ReaderTool.Settings),
        chapter?.name.orEmpty(), onSelect, onDismiss, layout = layout) { tool ->
        when (tool) {
            ReaderTool.Pages -> MangaPageMap(viewModel, onSeek, onOpenChapter)
            ReaderTool.Saved -> MangaSavedNotes(viewModel, onOpenNote, onEditNote)
            ReaderTool.Settings -> MangaReadingInspector(mode, viewModel::setMode)
            else -> Unit
        }
    }
}

@Composable
internal fun MangaCachedPage(viewModel: MangaReaderViewModel, chapterId: String, local: Int,
    modifier: Modifier = Modifier) {
    var attempt by remember(viewModel, chapterId, local) { mutableIntStateOf(0) }
    var loading by remember(viewModel, chapterId, local) { mutableStateOf(true) }
    // Lazy grid cells and slider previews request their own artwork, sharing the reader's
    // encoded cache, in-flight requests and bounded fetch gate. Never move reading progress.
    val bitmap by produceState<ImageBitmap?>(null, viewModel, chapterId, local, attempt) {
        value = null
        loading = true
        try {
            val bytes = viewModel.resolvePreviewImage(chapterId, local)
            value = bytes?.let { withContext(Dispatchers.Default) { decodeCoverImage(it, 288) } }
        } finally {
            loading = false
        }
    }
    Box(modifier.background(FolioTheme.colors.onSurface.copy(alpha = .035f)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, "Page ${local + 1} preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
            color = FolioTheme.colors.accentProgress)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaderLocationLabel("${local + 1}".padStart(2, '0'))
            TextButton(onClick = { attempt++ }) { Text("Retry") }
        }
    }
}

@Composable
private fun MangaPageMap(viewModel: MangaReaderViewModel, onSeek: (Int) -> Unit, onOpenChapter: (MangaChapter) -> Unit) {
    val chapter by viewModel.chapter.collectAsState()
    val count by viewModel.localCount.collectAsState()
    val current by viewModel.localPage.collectAsState()
    var chapters by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { chapters = false }) { Text("Pages", color = if (!chapters) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant) }
            TextButton(onClick = { chapters = true }) { Text("Chapters", color = if (chapters) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            ReaderLocationLabel("${current + 1} / $count")
        }
        if (chapters) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(viewModel.navList, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenChapter(entry) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.name, Modifier.weight(1f), style = FolioTheme.typography.bodyMedium,
                            color = if (entry.id == chapter?.id) FolioTheme.colors.primary else FolioTheme.colors.onSurface)
                        if (entry.id == chapter?.id) Icon(Icons.Filled.Circle, "Current chapter",
                            Modifier.size(6.dp), FolioTheme.colors.accentProgress)
                    }
                }
            }
        } else {
            Text("Browse the artwork. Tap any page to read it.", style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), state = gridState,
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items((0 until count).toList(), key = { "${chapter?.id}:$it" }) { index ->
                    val selected = index == current
                    Column(Modifier.selectable(selected, role = Role.Button, onClick = { onSeek(index) }),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MangaCachedPage(viewModel, chapter?.id.orEmpty(), index,
                            Modifier.fillMaxWidth().aspectRatio(.68f).clip(FolioSoftShape(8.dp)).border(
                                if (selected) 2.dp else .75.dp,
                                if (selected) FolioTheme.colors.accentProgress else FolioTheme.colors.outlineVariant,
                                FolioSoftShape(8.dp)))
                        ReaderLocationLabel(if (selected) "${index + 1} · here" else "${index + 1}")
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaSavedNotes(viewModel: MangaReaderViewModel, onOpen: (MangaNote) -> Unit, onEdit: (MangaNote) -> Unit) {
    val manga by viewModel.manga.collectAsState()
    val chapters by viewModel.allChapters.collectAsState()
    val notes by remember(viewModel, manga?.id) {
        viewModel.noteRepo.observeNotesForManga(manga?.id.orEmpty())
    }.collectAsState(initial = emptyList())
    if (notes.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.EditNote, null, tint = FolioTheme.colors.accentAnnotation)
        Text("A place for your thoughts", style = FolioTheme.typography.titleMedium)
        Text("Long-press a page to keep a note. Saved notes bring you straight back to that page.",
            style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(notes, key = { it.id }) { note ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(note) }.padding(vertical = 12.dp)) {
                Text(chapters.firstOrNull { it.id == note.chapterId }?.name ?: "Chapter unavailable",
                    style = FolioTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
                ReaderLocationLabel("PAGE ${note.pageIndex + 1}")
                Text(note.content, style = FolioTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onEdit(note) }) { Text("Edit note") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { viewModel.deleteNote(note.id) }) {
                        Icon(Icons.Filled.DeleteOutline, "Delete note", Modifier.size(18.dp), FolioTheme.colors.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = FolioTheme.colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun MangaReadingInspector(mode: MangaReaderMode, onModeChange: (MangaReaderMode) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Reading flow", style = FolioTheme.typography.titleMedium)
        Text("Choose how the artwork moves. The page itself keeps its original colours.",
            style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
        listOf(
            Triple(MangaReaderMode.WEBTOON, "Continuous", "A vertical strip · scroll freely"),
            Triple(MangaReaderMode.PAGED_LTR, "Left to right", "One page at a time · forward to the right"),
            Triple(MangaReaderMode.PAGED_RTL, "Right to left", "One page at a time · forward to the left"),
            Triple(MangaReaderMode.PAGED_VERTICAL, "Vertical pages", "One page at a time · forward below"),
        ).forEach { (value, title, detail) ->
            val selected = value == mode
            Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = { onModeChange(value) })
                .border(.75.dp, if (selected) FolioTheme.colors.primary else FolioTheme.colors.outlineVariant, FolioSoftShape(14.dp))
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = FolioTheme.typography.titleSmall)
                    Text(detail, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
                }
                if (selected) Icon(Icons.Filled.Check, "Selected", Modifier.size(18.dp), FolioTheme.colors.primary)
            }
        }
        ReaderLocationLabel("Pinch to zoom · double-tap to reset")
    }
}
