package com.folio.reader.ui.manga

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaStatus
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.launch

@Composable
fun MangaDetailScreen(
    viewModel: MangaDetailViewModel,
    backend: MangaBackend,
    downloadsAvailable: Boolean,
    onRead: (MangaEntry, MangaChapter) -> Unit,
    onBack: () -> Unit,
) {
    val manga by viewModel.manga.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val reReads by viewModel.reReads.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val chapterFilter by viewModel.chapterFilter.collectAsState()
    val chapterSelectionMode by viewModel.chapterSelectionMode.collectAsState()
    val selectedChapterIds by viewModel.selectedChapterIds.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val refreshNotice by viewModel.refreshNotice.collectAsState()
    var refreshNoticeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(refreshNotice) {
        if (refreshNotice != null) {
            refreshNoticeVisible = true
            kotlinx.coroutines.delay(2700)
            refreshNoticeVisible = false
            viewModel.refreshNotice.value = null
        }
    }
    val scope = rememberCoroutineScope()
    var filterOpen by remember { mutableStateOf(false) }
    val allCategories by viewModel.allCategories.collectAsState()
    val myCategoryIds by viewModel.myCategoryIds.collectAsState()
    var categoryPickerOpen by remember { mutableStateOf(false) }
    var categoryPrompt by remember { mutableStateOf(false) }
    val tags by viewModel.tags.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    var tagPickerOpen by remember { mutableStateOf(false) }

    val displayChapters = remember(chapters, sortAscending, chapterFilter) {
        val filtered = viewModel.applyFilter(chapters)
        if (sortAscending) filtered else filtered.reversed()
    }

    val m = manga
    if (m == null) {
        com.folio.reader.ui.components.LoadingPlaceholder(modifier = Modifier.fillMaxSize())
        return
    }

    // The chapter list reports its scroll to the masthead, which gains its glass and
    // takes the manga's title as the identity block below scrolls out of sight — the
    // same migration Home's hero makes into its bar.
    val headerState = com.folio.reader.ui.components.rememberFolioHeaderState()
    Column(Modifier.fillMaxSize().nestedScroll(headerState.nestedScrollConnection)) {
        if (chapterSelectionMode) {
            // Chapter selection swaps the regular chrome for bulk actions in the same
            // bar — no extra block, no layout shift below.
            FolioTopBar(
                title = "${selectedChapterIds.size} selected",
                navigationIcon = {
                    IconButton(onClick = { viewModel.clearChapterSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.selectAllChapters() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                    }
                    IconButton(onClick = { viewModel.bulkMarkRead(true) }) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Mark read")
                    }
                    IconButton(onClick = { viewModel.bulkMarkRead(false) }) {
                        Icon(Icons.Filled.MenuBook, contentDescription = "Mark unread")
                    }
                    if (downloadsAvailable && !m.isLocal) {
                        IconButton(onClick = { viewModel.bulkDownload() }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                    }
                    IconButton(onClick = { viewModel.bulkDeleteDownloads() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete downloads", tint = FolioTheme.colors.error)
                    }
                },
            )
        } else {
        FolioTopBar(
            // The title/author/status block below already carries the identity; a top-bar
            // title just repeats it — until that block scrolls away, at which point the
            // title migrates up here.
            title = "",
            collapse = headerState.collapse,
            titleContent = {
                Text(
                    text = m.title,
                    style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        alpha = ((headerState.collapse - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = {
                    if (!m.inLibrary) categoryPrompt = true
                    viewModel.toggleInLibrary()
                }) {
                    Icon(
                        imageVector = if (m.inLibrary) Icons.Filled.LibraryAddCheck else Icons.Filled.LibraryAdd,
                        contentDescription = if (m.inLibrary) "In library — tap to remove" else "Add to library",
                        tint = if (m.inLibrary) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = { viewModel.toggleSort() }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort order")
                }
                Box {
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter chapters")
                    }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                        ChapterFilter.entries.forEach { f ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (chapterFilter == f) {
                                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(f.label)
                                    }
                                },
                                onClick = {
                                    filterOpen = false
                                    viewModel.setChapterFilter(f)
                                },
                            )
                        }
                    }
                }
                Box {
                    var moreOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { moreOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Mark all as read") },
                            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                            onClick = { moreOpen = false; viewModel.markAllRead(true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark all as unread") },
                            leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                            onClick = { moreOpen = false; viewModel.markAllRead(false) },
                        )
                    }
                }
            },
        )
        }

        if (m.inLibrary) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = FolioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(allCategories.filter { it.id in myCategoryIds }) { category ->
                    FolioChip(selected = true, onClick = { categoryPickerOpen = true }, label = category.name)
                }
                item {
                    FolioChip(selected = false, onClick = { categoryPickerOpen = true }, label = "Categories")
                }
                items(tags) { tag ->
                    FolioChip(selected = true, onClick = { tagPickerOpen = true }, label = tag.name)
                }
                item {
                    FolioChip(selected = false, onClick = { tagPickerOpen = true }, label = "Tags")
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (categoryPickerOpen) {
            CategoryPickerDialog(
                categories = allCategories,
                initialSelected = myCategoryIds,
                onCreate = { name -> viewModel.createCategory(name) },
                onApply = { viewModel.setCategories(it) },
                onDismiss = { categoryPickerOpen = false },
            )
        }

        // Adding to the library asks for the shelf right away; dismissing keeps the
        // default shelf so a manga is never left without a home.
        if (categoryPrompt) {
            CategoryPickerDialog(
                categories = allCategories,
                initialSelected = myCategoryIds,
                onCreate = { name -> viewModel.createCategory(name) },
                onApply = { viewModel.setCategories(it) },
                onDismiss = { categoryPrompt = false },
            )
        }

        if (tagPickerOpen) {
            com.folio.reader.ui.tags.TagPickerDialog(
                tags = allTags,
                assignedTagIds = tags.mapTo(mutableSetOf()) { it.id },
                onDismiss = { tagPickerOpen = false },
                onSave = { ids ->
                    viewModel.updateMangaTags(ids)
                    tagPickerOpen = false
                },
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = refreshNoticeVisible && refreshNotice != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = refreshNotice.orEmpty(),
                    style = FolioTheme.typography.labelLarge,
                    color = FolioTheme.colors.onSurface,
                    modifier = Modifier
                        .padding(top = FolioTokens.space2)
                        .glassPanel(RoundedCornerShape(FolioTokens.radiusChip))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .aspectRatio(0.68f)
                            .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                    ) {
                        MangaCover(
                            backend = backend,
                            sourceId = m.sourceId,
                            thumbnailUrl = m.thumbnailUrl,
                            coverPath = m.coverPath,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = FolioTheme.colors.onSurface,
                        )
                        listOfNotNull(m.author, m.artist).distinct().forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
                        }
                        Text(
                            when (m.status) {
                                MangaStatus.ONGOING -> "Ongoing"
                                MangaStatus.COMPLETED -> "Completed"
                                MangaStatus.LICENSED -> "Licensed"
                                MangaStatus.PUBLISHING_FINISHED -> "Publishing finished"
                                MangaStatus.CANCELLED -> "Cancelled"
                                MangaStatus.ON_HIATUS -> "On hiatus"
                                MangaStatus.UNKNOWN -> m.sourceName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = FolioTheme.colors.primary,
                        )
                    }
                }
            }

            item {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                ) {
                    if (m.inLibrary) {
                        OutlinedButton(
                            onClick = { viewModel.toggleInLibrary() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.LibraryAddCheck, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("In library — tap to remove", maxLines = 1)
                        }
                    } else {
                        Button(
                            onClick = {
                                categoryPrompt = true
                                viewModel.toggleInLibrary()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.LibraryAdd, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add to library", maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val target = viewModel.nextChapterToRead()
                                        ?: displayChapters.firstOrNull()
                                    if (target != null) {
                                        viewModel.recordHistory(target.id)
                                        onRead(m, target)
                                    }
                                }
                            },
                            enabled = displayChapters.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (chapters.any { it.read }) "Continue" else "Start reading",
                                maxLines = 1,
                            )
                        }
                        // Downloads only make sense for online sources: a local series is
                        // already on disk in full, and queueing it would just extract the
                        // archive into duplicate per-page files.
                        if (downloadsAvailable && !m.isLocal) {
                            // Queues every unread chapter; disabled when everything is read
                            // so a fully-caught-up series doesn't dead-click.
                            OutlinedButton(
                                onClick = { viewModel.downloadUnread() },
                                enabled = chapters.any { !it.read },
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Download unread", maxLines = 1)
                            }
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(message, color = FolioTheme.colors.error, style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    // A bot check is not a network error: it needs a browser view, and
                    // clearing it reloads the chapter list by itself.
                    MangaChallengePrompt(onCleared = { viewModel.refresh() })
                }
            }

            if (m.genres.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(m.genres) { genre ->
                            Box(
                                modifier = Modifier
                                    .background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(FolioTokens.radiusChip))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(genre, style = MaterialTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (!m.description.isNullOrBlank()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        m.description!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }

            // Compact per-manga stats — derived from the chapter list already in scope.
            if (chapters.isNotEmpty()) {
                item {
                    val readCount = chapters.count { it.read }
                    val totalCount = chapters.size
                    val progress = if (totalCount > 0) readCount.toFloat() / totalCount else 0f
                    com.folio.reader.ui.components.FolioSectionCard(
                        title = "Progress",
                        accent = FolioTheme.colors.accentProgress
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$readCount of $totalCount chapters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FolioTheme.colors.onSurface,
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = FolioTheme.colors.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        com.folio.reader.ui.components.FolioProgressBar(
                            progress = progress,
                            color = FolioTheme.colors.primary,
                        )
                    }
                }
            }

            item {
                val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
                val weekAgo = kotlinx.datetime.Clock.System.now()
                    .toLocalDateTime(zone).date.minus(kotlinx.datetime.DatePeriod(days = 7))
                MangaReadingSection(
                    sessions = sessions,
                    chaptersRead = chapters.count { it.read },
                    chaptersReadThisWeek = chapters.count { it.read && it.updatedAt.toLocalDateTime(zone).date >= weekAgo },
                    reReads = reReads,
                )
            }

            item {
                Text(
                    "${chapters.size} CHAPTERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = FolioTheme.colors.primary,
                )
            }

            if (chapters.isEmpty() && refreshing) {
                item {
                    Box(Modifier.fillMaxWidth().padding(FolioTokens.space4), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(displayChapters, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    download = downloadStates[chapter.id],
                    downloadsAvailable = downloadsAvailable && !m.isLocal,
                    selected = chapter.id in selectedChapterIds,
                    inSelectionMode = chapterSelectionMode,
                    onRead = {
                        if (chapterSelectionMode) viewModel.toggleChapterSelection(chapter.id)
                        else { viewModel.recordHistory(chapter.id); onRead(m, chapter) }
                    },
                    onLongClick = { viewModel.toggleChapterSelection(chapter.id) },
                    onToggleRead = { viewModel.toggleRead(chapter) },
                    onMarkPrevious = { viewModel.markPreviousAsRead(chapter) },
                    onToggleBookmark = { viewModel.toggleBookmark(chapter) },
                    onDownload = { viewModel.download(chapter) },
                    onCancelDownload = { viewModel.cancelDownload(chapter) },
                    onDeleteDownload = { viewModel.deleteDownload(chapter) },
                )
            }
        }
    }
}
