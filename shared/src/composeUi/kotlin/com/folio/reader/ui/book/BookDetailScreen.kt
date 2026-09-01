package com.folio.reader.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.components.StatCard
import com.folio.reader.ui.components.finishEstimate
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
    val availableSeries by viewModel.availableSeries.collectAsState(initial = emptyList())
    val availableCollections by viewModel.availableCollections.collectAsState(initial = emptyList())
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMetadataEditor by remember { mutableStateOf(false) }

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
            val estimate = finishEstimate(b.totalWords, b.normalizedProgress, sessions)
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
                        onCoverClick = onStartReading
                    )
                }

                if (estimate != null) {
                    item {
                        Text(
                            estimate,
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
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

@Composable
private fun BookMetadataEditorDialog(
    book: Book,
    assignedCollectionIds: Set<String>,
    series: List<Series>,
    collections: List<Collection>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String?, String, Set<String>, String, String) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var subtitle by remember(book.id) { mutableStateOf(book.subtitle.orEmpty()) }
    var authors by remember(book.id) { mutableStateOf(book.authors.joinToString(", ")) }
    var publisher by remember(book.id) { mutableStateOf(book.publisher.orEmpty()) }
    var language by remember(book.id) { mutableStateOf(book.language.orEmpty()) }
    var isbn by remember(book.id) { mutableStateOf(book.isbn.orEmpty()) }
    var description by remember(book.id) { mutableStateOf(book.description.orEmpty()) }
    var selectedSeriesId by remember(book.id) { mutableStateOf(book.seriesId) }
    var seriesNumber by remember(book.id) { mutableStateOf(book.seriesNumber?.toString().orEmpty()) }
    var selectedCollectionIds by remember(book.id) { mutableStateOf(assignedCollectionIds) }
    var newSeriesName by remember(book.id) { mutableStateOf("") }
    var newCollectionName by remember(book.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), isError = title.isBlank())
                OutlinedTextField(subtitle, { subtitle = it }, label = { Text("Subtitle") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(authors, { authors = it }, label = { Text("Authors (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(publisher, { publisher = it }, label = { Text("Publisher") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(language, { language = it }, label = { Text("Language") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(isbn, { isbn = it }, label = { Text("ISBN") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Text("Series", style = FolioTheme.typography.titleSmall)
                TextButton(onClick = { selectedSeriesId = null }) { Text(if (selectedSeriesId == null) "No series" else "Remove series") }
                series.forEach { item ->
                    AssistChip(
                        onClick = { selectedSeriesId = item.id },
                        label = { Text(if (selectedSeriesId == item.id) "✓ ${item.name}" else item.name) }
                    )
                }
                OutlinedTextField(newSeriesName, { newSeriesName = it }, label = { Text("Create series") }, modifier = Modifier.fillMaxWidth())
                if (selectedSeriesId != null || newSeriesName.isNotBlank()) {
                    OutlinedTextField(seriesNumber, { seriesNumber = it }, label = { Text("Series number") }, modifier = Modifier.fillMaxWidth())
                }
                Text("Collections", style = FolioTheme.typography.titleSmall)
                collections.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.id in selectedCollectionIds,
                            onCheckedChange = { checked ->
                                selectedCollectionIds = if (checked) selectedCollectionIds + item.id else selectedCollectionIds - item.id
                            }
                        )
                        Text(item.name)
                    }
                }
                OutlinedTextField(newCollectionName, { newCollectionName = it }, label = { Text("Create collection") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title, subtitle, authors, publisher, language, isbn, description, selectedSeriesId, seriesNumber, selectedCollectionIds, newSeriesName, newCollectionName)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
private fun BookHeaderSection(
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

@Composable
private fun BookDescription(description: String) {
    val plainDescription = remember(description) {
        description
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Description",
                style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = plainDescription,
                style = FolioTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
            if (plainDescription.length > 300) {
                TextButton(
                    onClick = { expanded = !expanded }
                ) {
                    Text(if (expanded) "Show less" else "Read more")
                }
            }
        }
    }
}

@Composable
private fun ReadingActionSection(
    book: Book,
    onStartReading: () -> Unit
) {
    val hasStarted = book.normalizedProgress > 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStartReading,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (hasStarted) "Continue Reading" else "Start Reading",
                style = FolioTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatsRow(
    sessionsCount: Int,
    wordsRead: Long,
    highlightsCount: Int,
    bookmarksCount: Int,
    notesCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Reading Stats",
            style = FolioTheme.typography.titleMedium,
            color = FolioTheme.colors.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = "Sessions",
                value = "$sessionsCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
            StatCard(
                title = "Words",
                value = formatCount(wordsRead),
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = "Highlights",
                value = "$highlightsCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
            StatCard(
                title = "Bookmarks",
                value = "$bookmarksCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
            StatCard(
                title = "Notes",
                value = "$notesCount",
                modifier = Modifier.weight(1f),
                color = FolioTheme.colors.primary
            )
        }
    }
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = FolioTheme.colors.surfaceVariant,
            contentColor = FolioTheme.colors.onSurfaceVariant
        )
    ) {
        content()
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

private fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
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
            .padding(horizontal = 16.dp),
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
