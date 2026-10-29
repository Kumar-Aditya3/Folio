@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.manga

import com.folio.reader.ui.components.*
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaDownloadStatus
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaStatus
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.components.FolioRowListSkeleton
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.components.folioPanel
import com.folio.reader.ui.components.folioTypeGround
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.folioBarTopInset
import com.folio.reader.ui.theme.FolioShapes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

@Composable
fun MangaDetailScreen(
    viewModel: MangaDetailViewModel,
    backend: MangaBackend,
    downloadsAvailable: Boolean,
    onRead: (MangaEntry, MangaChapter) -> Unit,
    onBack: () -> Unit,
    onRemoveFromLibrary: ((MangaEntry) -> Unit)? = null,
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

    // Categories and tags ride in the masthead's rail slot instead of as a row of their
    // own, so they fold up under the bar on scroll and the chapter list is the only
    // full-bleed content — which is what makes the bar's glass actually visible.
    var railPx by remember { mutableIntStateOf(0) }
    val rail: (@Composable () -> Unit)? =
        if (!m.inLibrary) null else ({
            Box(Modifier.onSizeChanged { railPx = it.height }) {
                val myCategories = remember(allCategories, myCategoryIds) {
                    allCategories.filter { it.id in myCategoryIds.orEmpty() }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = FolioTokens.space3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(myCategories, key = { "c:${it.id}" }) { category ->
                        FolioChip(selected = true, onClick = { categoryPickerOpen = true }, label = category.name)
                    }
                    item {
                        FolioChip(selected = false, onClick = { categoryPickerOpen = true }, label = "Categories")
                    }
                    items(tags, key = { "t:${it.id}" }) { tag ->
                        FolioChip(selected = true, onClick = { tagPickerOpen = true }, label = tag.name)
                    }
                    item {
                        FolioChip(selected = false, onClick = { tagPickerOpen = true }, label = "Tags")
                    }
                }
            }
        })
    // At-rest, deliberately: the rail folds away as the bar collapses, and a shrinking
    // inset would drag every chapter row up mid-scroll.
    val topInset = folioBarTopInset(with(LocalDensity.current) { railPx.toDp() })
    // Resume sits in the action stack with the other series-level buttons, and hands
    // itself to the masthead as it slides under it. The handoff is driven by where the
    // inline button actually is — not by the bar's collapse fraction, which finishes
    // after 56dp of scroll while the button is still mid-page — so the two copies
    // cross-fade exactly at the bar's edge and resuming is never absent from the screen.
    //
    // Held in a float state read only inside layout/draw lambdas: reading it in
    // composition would recompose the whole bar on every scroll frame.
    val density = LocalDensity.current
    val barBottomPx = with(density) { topInset.toPx() }
    val handoffPx = with(density) { 56.dp.toPx() }
    val resumeLiftPx = with(density) { 12.dp.toPx() }
    val resumeTopPx = remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val resumeMorph: () -> Float = {
        val gap = resumeTopPx.floatValue - barBottomPx
        ((handoffPx - gap) / handoffPx).coerceIn(0f, 1f)
    }
    val continueLabel = remember(chapters) { if (chapters.any { it.read }) "Continue" else "Start" }
    val hasUnread = remember(chapters) { chapters.any { !it.read } }
    val launchContinue: () -> Unit = {
        scope.launch {
            val target = viewModel.nextChapterToRead() ?: displayChapters.firstOrNull()
            if (target != null) {
                viewModel.recordHistory(target.id)
                onRead(m, target)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Back clears the chapter selection instead of popping the screen —
        // same rule the three library shelves follow.
        com.folio.reader.ui.components.FolioBackHandler(
            enabled = chapterSelectionMode
        ) { viewModel.clearChapterSelection() }
        if (chapterSelectionMode) {
            // Chapter selection swaps the regular chrome for bulk actions in the same
            // bar — no extra block, no layout shift below.
            FolioTopBar(
                title = "${selectedChapterIds.size} selected",
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
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
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Mark unread")
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
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                collapse = headerState.collapse,
                rail = rail,
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
                    // Resume unfurls into the bar as the inline copy slides under it: the
                    // slot's width tracks the handoff so the title yields room instead of a
                    // button appearing on top of it. Clipped horizontally and scaled rather
                    // than translated, so nothing spills past the slot mid-morph.
                    Box(
                        modifier = Modifier
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val width = (placeable.width * resumeMorph()).roundToInt()
                                layout(width, placeable.height) {
                                    placeable.placeRelative(width - placeable.width, 0)
                                }
                            }
                            .graphicsLayer {
                                val f = resumeMorph()
                                alpha = f
                                val scale = 0.92f + 0.08f * f
                                scaleX = scale
                                scaleY = scale
                            },
                    ) {
                        ResumeReadingButton(
                            label = continueLabel,
                            enabled = displayChapters.isNotEmpty(),
                            // Only the copy the user can actually see responds, so the
                            // collapsed slot can never take a tap meant for the title.
                            onClick = { if (resumeMorph() > 0.5f) launchContinue() },
                        )
                    }
                    IconButton(onClick = {
                        if (m.inLibrary) {
                            onRemoveFromLibrary?.invoke(m) ?: viewModel.toggleInLibrary()
                        } else {
                            categoryPrompt = true
                        }
                    }) {
                        Icon(
                            imageVector = if (m.inLibrary) Icons.Filled.LibraryAddCheck else Icons.Filled.LibraryAdd,
                            contentDescription = if (m.inLibrary) "In library — tap to remove" else "Add to library",
                            tint = if (m.inLibrary) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                    // Everything that is not "keep reading" — refresh, ordering, the chapter
                    // filter, bulk marks — sits behind one overflow, grouped by dividers.
                    Box {
                        var moreOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        com.folio.reader.ui.components.FolioMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (refreshing) "Refreshing…" else "Refresh chapters") },
                                leadingIcon = {
                                    if (refreshing) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Filled.Refresh, contentDescription = null)
                                    }
                                },
                                onClick = { moreOpen = false; viewModel.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (sortAscending) "Oldest first" else "Newest first") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                                onClick = { moreOpen = false; viewModel.toggleSort() },
                            )
                            HorizontalDivider()
                            ChapterFilter.entries.forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.label) },
                                    leadingIcon = {
                                        if (chapterFilter == filter) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = { moreOpen = false; viewModel.setChapterFilter(filter) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Mark all as read") },
                                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                onClick = { moreOpen = false; viewModel.markAllRead(true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Mark all as unread") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                onClick = { moreOpen = false; viewModel.markAllRead(false) },
                            )
                        }
                    }
                },
            )
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

        // Adding is staged until the shelf selection is confirmed.
        if (categoryPrompt) {
            CategoryPickerDialog(
                categories = allCategories,
                initialSelected = allCategories.takeIf { it.isNotEmpty() }?.let { categories ->
                    setOf((categories.firstOrNull { it.id == com.folio.reader.manga.MangaCategory.MAIN_ID }
                        ?: categories.first()).id)
                },
                onCreate = { name -> viewModel.createCategory(name) },
                onApply = { viewModel.setCategories(it, addToLibrary = true) },
                onDismiss = { categoryPrompt = false },
                title = "Add to library",
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

        val swapSpec = rememberMotionAnimation<Float>(MotionKey.MangaContentSwap)
        androidx.compose.animation.AnimatedVisibility(
            visible = refreshNoticeVisible && refreshNotice != null,
            enter = fadeIn(swapSpec),
            exit = fadeOut(swapSpec),
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).fillMaxWidth(),
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
                        .padding(top = topInset + FolioTokens.space2)
                        .glassPanel(RoundedCornerShape(FolioTokens.radiusChip))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(headerState.nestedScrollConnection)
                    .folioBackdropSource().folioPageWash(),
            contentPadding = PaddingValues(
                start = FolioTokens.space3,
                top = topInset + FolioTokens.space3,
                end = FolioTokens.space3,
                bottom = FolioTokens.space3,
            ),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            item(key = "title-scene") {
                FolioArtworkScene(FolioArtwork("manga:${m.id}", m.coverPath, m.sourceId, m.thumbnailUrl),
                    Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = FolioTokens.space3),
                        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val coverWidth = (maxWidth * .36f).coerceAtMost(132.dp)
                            val cover: @Composable () -> Unit = {
                                MangaCoverPlate(backend, m.sourceId, m.thumbnailUrl, m.coverPath,
                                    width = coverWidth, haloStrength = .08f,
                                    modifier = Modifier.sharedElementOrNoop(FolioSharedKeys.mangaCover(m.id),
                                        placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize)
                                        .clickable(enabled = chapters.isNotEmpty(),
                                            onClickLabel = "$continueLabel reading", onClick = launchContinue))
                            }
                            val identity: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FolioEyebrow(m.sourceName, accent = FolioTheme.colors.accentDiscovery)
                                    Text(m.title, style = FolioTheme.typography.headlineMedium,
                                        color = FolioTheme.colors.onSurface,
                                        modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.mangaTitle(m.id)))
                                    listOfNotNull(m.author, m.artist).distinct().forEach {
                                        Text(it, style = FolioTheme.typography.bodyMedium,
                                            color = FolioTheme.colors.onSurfaceVariant)
                                    }
                                    Text(when (m.status) {
                                        MangaStatus.ONGOING -> "Ongoing"
                                        MangaStatus.COMPLETED -> "Completed"
                                        MangaStatus.LICENSED -> "Licensed"
                                        MangaStatus.PUBLISHING_FINISHED -> "Publishing finished"
                                        MangaStatus.CANCELLED -> "Cancelled"
                                        MangaStatus.ON_HIATUS -> "On hiatus"
                                        MangaStatus.UNKNOWN -> m.sourceName
                                    }, style = FolioTheme.typography.labelMedium,
                                        color = FolioTheme.colors.onSurfaceVariant)
                                }
                            }
                            if (maxWidth < 310.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f)
                                Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) { cover(); identity() }
                            else Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3),
                                verticalAlignment = Alignment.CenterVertically) {
                                cover(); Box(Modifier.weight(1f)) { identity() }
                            }
                        }
                    ResumeReadingButton(
                        label = "$continueLabel reading",
                        subtitle = "${chapters.count { !it.read }} unread · ${chapters.size} chapters",
                        progress = if (chapters.isEmpty()) 0f else chapters.count { it.read }.toFloat() / chapters.size,
                        enabled = displayChapters.isNotEmpty(),
                        onClick = { if (resumeMorph() < 0.5f) launchContinue() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { resumeTopPx.floatValue = it.positionInWindow().y }
                            .graphicsLayer {
                                val f = resumeMorph()
                                alpha = 1f - f
                                translationY = -resumeLiftPx * f
                            },
                    )
                    }
                }
            }

            item {
                FolioReferencePanel {
                    if (m.inLibrary) {
                        // Bedded like Start: the toggle reads as a surface with glass
                        // behind it, not an outline floating on nothing (see
                        // [MangaPanelAction] below).
                        MangaPanelAction(
                            icon = Icons.Filled.LibraryAddCheck,
                            label = "In library — tap to remove",
                            onClick = {
                                onRemoveFromLibrary?.invoke(m)
                                    ?: viewModel.toggleInLibrary()
                            },
                        )
                    } else {
                        Button(
                            onClick = {
                                categoryPrompt = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.LibraryAdd, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add to library", maxLines = 1)
                        }
                    }
                    // Between the library toggle and the download queue: resuming is a
                    // series-level action like its neighbours, not chrome. It reports its own
                    // position so the masthead knows when to take over, and lifts as it fades
                    // so the two copies read as one control moving rather than two swapping.

                    // Downloads are online-source-only: a local series is already on disk in
                    // full, and queueing it would just extract the archive into duplicate
                    // page files. Queues every unread chapter; disabled once caught up so it
                    // never dead-clicks.
                    if (downloadsAvailable && !m.isLocal) {
                        MangaPanelAction(
                            icon = Icons.Filled.Download,
                            label = "Download unread",
                            enabled = hasUnread,
                            onClick = { viewModel.downloadUnread() },
                        )
                        // Once anything is queued or in flight, offer to abandon the whole
                        // active queue in one tap — the series-level counterpart to the
                        // per-row cancel. Finished downloads aren't counted here, so this
                        // disappears again as soon as the queue drains.
                        val activeDownloads = remember(downloadStates) {
                            downloadStates.values.count { it.status != MangaDownloadStatus.DOWNLOADED }
                        }
                        if (activeDownloads > 0) {
                            MangaPanelAction(
                                icon = Icons.Filled.Close,
                                label = "Cancel downloads ($activeDownloads)",
                                onClick = { viewModel.cancelAllDownloads() },
                            )
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(message, color = FolioTheme.colors.error, style = FolioTheme.typography.bodyMedium)
                }
                item {
                    MangaChallengePrompt(sourceId = manga?.sourceId)
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
                                Text(genre, style = FolioTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (!m.description.isNullOrBlank()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    var hasOverflow by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The book detail screen's description material (see
                            // BookDescription): the flat, nesting-aware panel — not text
                            // floating on the field and not a floating veil.
                            .folioPanel(FolioShapes.inset)
                            .clickable(enabled = expanded || hasOverflow) { expanded = !expanded }
                            .padding(16.dp),
                    ) {
                        Text(
                            "Description",
                            style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.onSurface,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            m.description!!,
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) hasOverflow = it.hasVisualOverflow },
                        )
                        // A subtle, tappable hint so the collapsed blurb does not read as
                        // dead text; only shown once the description actually overflows.
                        if (expanded || hasOverflow) {
                            Text(
                                text = if (expanded) "Show less" else "Show more",
                                style = FolioTheme.typography.labelMedium,
                                color = FolioTheme.colors.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
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
                                style = FolioTheme.typography.bodyMedium,
                                color = FolioTheme.colors.onSurface,
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = FolioTheme.typography.labelMedium,
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
                // Memoized: this section otherwise re-scans (and re-does a toLocalDateTime per read
                // chapter) on every recomposition of the ~16-flow screen.
                val readCounts = remember(chapters) {
                    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
                    val weekAgo = kotlinx.datetime.Clock.System.now()
                        .toLocalDateTime(zone).date.minus(kotlinx.datetime.DatePeriod(days = 7))
                    val read = chapters.count { it.read }
                    val readThisWeek = chapters.count { it.read && it.updatedAt.toLocalDateTime(zone).date >= weekAgo }
                    read to readThisWeek
                }
                MangaReadingSection(
                    sessions = sessions,
                    chaptersRead = readCounts.first,
                    chaptersReadThisWeek = readCounts.second,
                    reReads = reReads,
                )
            }

            item {
                // Issue 4: a bare letterspaced kicker directly on the animated field. It is a
                // label for the rows under it, not a card, so it takes `folioTypeGround` — the
                // page's own lower ramp colour at under half strength, dissolved at both ends —
                // exactly as the shelf cells' captions do. Full width so the wash has no side
                // edge to read as the lip of a box.
                Text(
                    "${chapters.size} CHAPTERS",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .folioTypeGround()
                        .padding(vertical = FolioTokens.space1),
                )
            }

            if (chapters.isEmpty() && refreshing) {
                item {
                    // Opening a manga from a source: the chapter list is the page, so
                    // stand rows in at their real height. The list then fills in rather
                    // than replacing a spinner with content and jumping the scroll.
                    FolioRowListSkeleton(rows = 6)
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

@Composable
private fun ResumeReadingButton(
    label: String,
    enabled: Boolean,
    subtitle: String = "",
    progress: Float? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (progress == null) Button(onClick = onClick, enabled = enabled, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp)) {
        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp)); Text(label, maxLines = 1)
    } else FolioActionDock(label, subtitle, onClick, modifier, progress = progress,
        enabled = enabled, accent = FolioTheme.colors.accentDiscovery)

}

/**
 * A series-level action on the book detail screen's row material (see BookDetailHeader's
 * metadata well): the sunken recess rather than a floating veil, so "In library — tap to
 * remove" and "Download unread" read as structure in the page. Start keeps its own Button
 * material, as on the book page.
 */
@Composable
private fun MangaPanelAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FolioShapes.inset)
            .folioReference(FolioShapes.inset)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            maxLines = 1,
            style = FolioTheme.typography.labelLarge,
            color = if (enabled) FolioTheme.colors.onSurface else FolioTheme.colors.onSurfaceVariant,
        )
    }
}
