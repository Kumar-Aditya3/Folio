package com.folio.reader.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBackPress: () -> Unit,
    onStartReading: () -> Unit,
    onEditClick: () -> Unit = {},
    onDeleteClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onTagClick: (Tag) -> Unit = {},
    onSeriesClick: (Series) -> Unit = {},
    onCollectionClick: (Collection) -> Unit = {},
    viewModel: BookDetailViewModel
) {
    val book by viewModel.book.collectAsState(initial = null)
    val sessions by viewModel.sessions.collectAsState(initial = emptyList())
    val highlights by viewModel.highlights.collectAsState(initial = emptyList())
    val bookmarks by viewModel.bookmarks.collectAsState(initial = emptyList())
    val notes by viewModel.notes.collectAsState(initial = emptyList())
    val series by viewModel.series.collectAsState(initial = null)
    val collections by viewModel.collections.collectAsState(initial = emptyList())
    val tags by viewModel.tags.collectAsState(initial = emptyList())
    val availableTags by viewModel.availableTags.collectAsState(initial = emptyList())
    val availableSeries by viewModel.availableSeries.collectAsState(initial = emptyList())
    val availableCollections by viewModel.availableCollections.collectAsState(initial = emptyList())
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMetadataEditor by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }

    if (showTagPicker) {
        com.folio.reader.ui.tags.TagPickerDialog(
            tags = availableTags,
            assignedTagIds = tags.mapTo(mutableSetOf()) { it.id },
            onDismiss = { showTagPicker = false },
            onSave = { ids ->
                viewModel.updateBookTags(ids)
                showTagPicker = false
            }
        )
    }

    book?.let { currentBook ->
        if (showMetadataEditor) {
            BookMetadataEditorDialog(
                book = currentBook,
                assignedCollectionIds = collections.mapTo(mutableSetOf()) { it.id },
                series = availableSeries,
                collections = availableCollections,
                onDismiss = { showMetadataEditor = false },
                onSave = { title, subtitle, authors, publisher, language, isbn, description, seriesId, seriesNumber, collectionIds, newSeriesName, newCollectionName ->
                    viewModel.saveMetadata(
                        title, subtitle, authors, publisher, language, isbn, description,
                        seriesId, seriesNumber, collectionIds, newSeriesName, newCollectionName
                    )
                    showMetadataEditor = false
                }
            )
        }
    }

    if (showDeleteConfirm) {
        com.folio.reader.ui.components.ConfirmDialog(
            title = "Delete Book",
            message = "Are you sure you want to delete '${book?.title}'? This will remove the book and its local files.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = { onDeleteClick?.invoke() },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    Scaffold(
        containerColor = FolioTheme.colors.background,
        // No bar: the controls sit directly on the artwork as small glass discs, so
        // the cover reads as the top of the screen instead of starting under a panel.
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                com.folio.reader.ui.components.FolioStatusBarBand()
                Row(
                    modifier = Modifier.fillMaxWidth().height(FolioTokens.barHeight)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                FloatingIconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                if (onShareClick != null) {
                    FloatingIconButton(onClick = onShareClick) {
                        Icon(Icons.Filled.Share, contentDescription = "Share EPUB")
                    }
                }
                FloatingIconButton(onClick = {
                    showMetadataEditor = true
                    onEditClick()
                }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit metadata")
                }
                if (onDeleteClick != null) {
                    FloatingIconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete book",
                            tint = FolioTheme.colors.error
                        )
                    }
                }
                }
            }
        }
    ) { innerPadding ->
        val b = book
        if (b == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                LoadingPlaceholder()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    BookHeaderSection(
                        book = b,
                        series = series,
                        collections = collections,
                        tags = tags,
                        sessionsCount = sessions.size,
                        wordsRead = sessions.sumOf { it.wordsRead },
                        highlightsCount = highlights.size,
                        bookmarksCount = bookmarks.size,
                        notesCount = notes.size,
                        onTagClick = onTagClick,
                        onSeriesClick = onSeriesClick,
                        onCollectionClick = onCollectionClick,
                        onAddTags = { showTagPicker = true },
                        onCoverClick = onStartReading
                    )
                }

                item {
                    // The card carries the page's side margin like every other block
                    // here; without it the panel ran under both screen edges and its
                    // rim was clipped away.
                    Box(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
                        BookReadingSection(
                            sessions = sessions,
                            highlights = highlights,
                            totalWords = b.totalWords,
                            progress = b.normalizedProgress,
                        )
                    }
                }

                if (b.cloudState != CloudState.LOCAL_ONLY) {
                    item {
                        CloudStatusSection(cloudState = b.cloudState)
                    }
                }

                item {
                    StatsRow(
                        sessionsCount = sessions.size,
                        wordsRead = sessions.sumOf { it.wordsRead },
                        highlightsCount = highlights.size,
                        bookmarksCount = bookmarks.size,
                        notesCount = notes.size
                    )
                }

                // Removed ReadingActionSection - just tap cover to read

                item {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Small glass disc control that sits on the artwork instead of in a toolbar. */
@Composable
private fun FloatingIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(FolioTheme.colors.surface.copy(alpha = 0.42f))
            .border(1.dp, FolioTheme.colors.outline.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun CloudStatusSection(cloudState: CloudState) {
    val (label, color) = when (cloudState) {
        CloudState.SYNCED -> "Backed up to cloud" to FolioTheme.colors.primary
        CloudState.UPLOADING, CloudState.UPLOADING_PROGRESS -> "Uploading to cloud…" to FolioTheme.colors.secondary
        CloudState.DOWNLOADING, CloudState.DOWNLOADING_PROGRESS -> "Downloading from cloud…" to FolioTheme.colors.secondary
        CloudState.REMOTE_ONLY -> "Available in cloud (not on this device)" to FolioTheme.colors.secondary
        CloudState.LOCAL_ONLY -> "On this device only" to FolioTheme.colors.secondary
        CloudState.SYNC_ERROR -> "Cloud backup failed — try again" to MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}
