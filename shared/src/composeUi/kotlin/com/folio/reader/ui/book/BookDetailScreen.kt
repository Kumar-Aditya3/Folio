package com.folio.reader.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.folioBarTopInset

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
    // No `initial`: book is a StateFlow, so this resolves to the overload that reads
    // its current value on the first frame — the seeded book from the tapped cover,
    // when there is one — so the header (and the shared cover/title the morph flies
    // to) is composed before the enter transition starts.
    val book by viewModel.book.collectAsState()
    val readingEnvironment = com.folio.reader.experience.LocalReadingEnvironment.current
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
    val tagSuggestions by viewModel.tagSuggestions.collectAsState(initial = TagSuggestionState.Idle)
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

    // §13.9 masthead/overlay pattern, mirrored from HomeScreen: the page runs to the
    // very top of the window and the glass masthead floats *over* it, instead of the
    // old Scaffold that reserved a flat bar and left the artwork starting under it
    // (which read as "clipped from the top"). The field atmosphere painted at the app
    // root (MainActivity.folioField) shows through because nothing here paints an
    // opaque background — that is what makes this screen sit in the same environment
    // as Home/Library/Stats rather than on its own flat colour.
    val listState = rememberLazyListState()
    // The runway the masthead needs: status icons + bar row. Scrolling content pads
    // by this (below), so the hero genuinely passes behind the glass as it collapses.
    val topInset = folioBarTopInset()
    val density = LocalDensity.current
    // Collapse fraction, offset-derived exactly like Home's 160dp hero range: 0 at the
    // top (title lives in the hero, bar is bare) → 1 once the hero has scrolled a full
    // range away (title has migrated into the bar). Once the first item is gone the
    // hero is fully off-screen, so the fraction pins to 1.
    val collapse by remember(listState, density) {
        val rangePx = with(density) { 160.dp.toPx() }
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / rangePx).coerceIn(0f, 1f)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val b = book
        if (b == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                LoadingPlaceholder()
            }
        } else {
            LazyColumn(
                state = listState,
                // The backdrop the floating glass bar refracts — without a declared
                // source the masthead has nothing to bend and reads as a flat tint.
                modifier = Modifier
                    .fillMaxSize()
                    .folioBackdropSource().folioPageWash(),
                // Start the content below the masthead (top) and clear of the floating
                // nav capsule (bottom), the same contract Library/Stats honour via
                // LocalFolioTopInset / LocalFolioBarInset.
                contentPadding = PaddingValues(
                    top = topInset,
                    bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
                ),
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
                        onSuggestTags = if (viewModel.canSuggestTags) {
                            { viewModel.suggestTags() }
                        } else {
                            null
                        },
                        onCoverClick = onStartReading,
                        // Drives the finish projection from the reader's measured pace
                        // rather than a fixed wpm — same source the library rows and
                        // Home use, so the three surfaces agree about this book.
                        sessions = sessions,
                    )
                }

                // Auto-tagging proposals (ML_PLAN Phase 5 #2). Sits below the header rather
                // than inside the chip row: every suggestion carries a score and a reason,
                // and a chip has room for neither. It also has to be able to say "this book
                // is not indexed yet", which is a sentence, not a chip.
                if (tagSuggestions.isVisible) {
                    item {
                        // A distinct proposal block after the header — a change of
                        // section kind, so the full movement gap leads it.
                        Column {
                            Spacer(Modifier.height(FolioTokens.spaceMovement))
                            Box(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
                                TagSuggestionPanel(
                                    state = tagSuggestions,
                                    onApply = viewModel::applySuggestion,
                                    onApplyConfident = viewModel::applyConfidentSuggestions,
                                    onDismiss = viewModel::dismissSuggestions,
                                )
                            }
                        }
                    }
                }

                item {
                    // The card carries the page's side margin like every other block
                    // here; without it the panel ran under both screen edges and its
                    // rim was clipped away.
                    //
                    // Header/proposal → reading card is a change of section kind, so
                    // the movement gap (36dp) leads this block.
                    Column {
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                        Box(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
                            BookReadingSection(
                                sessions = sessions,
                                highlights = highlights,
                                totalWords = b.totalWords,
                                progress = b.normalizedProgress,
                            )
                        }
                    }
                }

                if (b.cloudState != CloudState.LOCAL_ONLY) {
                    item {
                        // Opens the status/stats grouping — a change of kind from the
                        // reading card above.
                        Column {
                            Spacer(Modifier.height(FolioTokens.spaceMovement))
                            CloudStatusSection(cloudState = b.cloudState)
                        }
                    }
                }

                item {
                    // A related line when the cloud-status line precedes it (beat, 20dp);
                    // otherwise the reading card is directly above, a change of kind
                    // (movement, 36dp).
                    val statsLeadingGap = if (b.cloudState != CloudState.LOCAL_ONLY) {
                        FolioTokens.spaceBeat
                    } else {
                        FolioTokens.spaceMovement
                    }
                    Column {
                        Spacer(Modifier.height(statsLeadingGap))
                        StatsRow(
                            sessionsCount = sessions.size,
                            wordsRead = sessions.sumOf { it.wordsRead },
                            highlightsCount = highlights.size,
                            bookmarksCount = bookmarks.size,
                            notesCount = notes.size
                        )
                    }
                }

                // Removed ReadingActionSection - just tap cover to read
            }
        }

        // The masthead floats above the page (zIndex) so the hero collapses *behind*
        // its glass. It carries the back affordance and the book's own actions, and —
        // mirroring Home's wordmark→title cross-fade — the book title fades up into the
        // bar across the same scroll that collapses the hero.
        Box(modifier = Modifier.align(Alignment.TopCenter).zIndex(1f)) {
            FolioTopBar(
                // Empty: the title is driven entirely by titleContent so it can be the
                // book's (scroll-revealed) name rather than a static screen label.
                title = "",
                collapse = collapse,
                navigationIcon = {
                    FloatingIconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                titleContent = {
                    // The collapsing book title. It is weightless until the hero has
                    // mostly gone (fraction > 0.4), then fades in over the back third of
                    // the scroll — the same timing Home uses to migrate the hero title
                    // into its masthead, so the two screens feel like one app.
                    val title = book?.title
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                alpha = ((collapse - 0.4f) / 0.6f).coerceIn(0f, 1f)
                            },
                        )
                    }
                },
                actions = {
                    if (readingEnvironment != null) b?.let { current ->
                        FloatingIconButton(onClick = { readingEnvironment.perform { openJournal(current.id) } }) {
                            Icon(Icons.Filled.Print, contentDescription = "Export reading journal")
                        }
                    }
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
                },
            )
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
            .minimumInteractiveComponentSize()
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
        CloudState.SYNC_ERROR -> "Cloud backup failed — try again" to FolioTheme.colors.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)
    ) {
        // Editorial kicker, as on every other section of the app — labels the region
        // rather than titling a box.
        FolioEyebrow(text = "Sync", accent = color)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
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
}
