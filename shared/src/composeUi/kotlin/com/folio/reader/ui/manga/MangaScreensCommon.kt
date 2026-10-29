package com.folio.reader.ui.manga

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.components.FolioCoverRailSkeleton
import com.folio.reader.ui.components.FolioSourceSectionSkeleton
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.components.folioTypeGround
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

@Composable
internal fun SourceSearchResults(
    viewModel: BrowseViewModel,
    onOpenManga: (String) -> Unit,
    onOpenSource: (MangaSourceInfo) -> Unit,
) {
    val globalResults by viewModel.globalResults.collectAsState()
    val globalResultsOrdered by viewModel.globalResultsOrdered.collectAsState()
    val query by viewModel.globalQuery.collectAsState()
    val preparing by viewModel.preparingSources.collectAsState()
    val scope = rememberCoroutineScope()

    val safeIndex = viewModel.globalListScrollIndex.coerceAtLeast(0)
    val safeOffset = viewModel.globalListScrollOffset.coerceAtLeast(0)
    val listState = remember(viewModel) { LazyListState(safeIndex, safeOffset) }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.globalListScrollIndex = listState.firstVisibleItemIndex
            viewModel.globalListScrollOffset = listState.firstVisibleItemScrollOffset
        }
    }

    if (query.isBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Search every installed source at once.\nResults appear as each source answers.",
                style = MaterialTheme.typography.bodyMedium,
                color = FolioTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val finished = globalResults.count { !it.loading }
    val totalItems = globalResults.sumOf { it.items.size }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
    ) {
        item {
            // Issue 4: the one progress line on this screen, directly on the field. Same
            // ground the shelf captions take — the page's own lower ramp at under half
            // strength, dissolved at both ends, full width so it has no side edge.
            Text(
                when {
                    preparing && globalResults.isEmpty() -> "Preparing sources…"
                    finished < globalResults.size -> "Searching ${finished + 1}/${globalResults.size}…"
                    else -> "$totalItems results from ${globalResults.count { it.items.isNotEmpty() }} sources"
                },
                style = MaterialTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .folioTypeGround()
                    .padding(vertical = FolioTokens.space1),
            )
        }
        globalResultsOrdered.forEach { result ->
            item(key = "global-${result.source.id}") {
                Box(Modifier.animateItem()) {
                    GlobalSourceSection(
                        result = result,
                        viewModel = viewModel,
                        onViewAll = { onOpenSource(result.source) },
                        onOpenManga = { item ->
                            scope.launch {
                                val entry = viewModel.ensureEntry(result.source, item)
                                onOpenManga(entry.id)
                            }
                        },
                    )
                }
            }
        }
        // Before the installed-source list resolves there are no sections to draw
        // at all, and a lone "Preparing sources…" line over a blank page reads as
        // a dead end. Two headed rails stand in until the real sources arrive and
        // replace them, so the shape of the answer is there from the first frame.
        if (preparing && globalResults.isEmpty()) {
            items(2, key = { "preparing-$it" }) {
                FolioSourceSectionSkeleton()
            }
        }
        if (!preparing && finished == globalResults.size && totalItems == 0) {
            item {
                com.folio.reader.ui.components.EmptyState(
                    icon = Icons.Filled.Search,
                    headline = "No results across your sources"
                )
            }
        }
    }
}

@Composable
internal fun GlobalSourceSection(
    result: BrowseViewModel.GlobalSourceResult,
    viewModel: BrowseViewModel,
    onViewAll: () -> Unit,
    onOpenManga: (MangaBrowseItem) -> Unit,
) {
    com.folio.reader.ui.components.FolioHubSection(result.source.name, FolioTheme.colors.tertiary) {
        if (!result.loading || result.items.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(onClick = onViewAll) {
                Text(if (result.items.isEmpty()) "Open source" else "View all")
            }
        }
        val swapSpec = rememberMotionAnimation<Float>(MotionKey.MangaContentSwap)
        AnimatedContent(
            targetState = result.loading,
            // One pair from the table: the manga screens used 220/160 here and 200 elsewhere
            // for the same content swap.
            transitionSpec = { fadeIn(swapSpec) togetherWith fadeOut(swapSpec) },
            label = "source-section-${result.source.id}",
        ) { loading ->
            when {
                // The source's name is already on screen — what has not arrived is
                // its rail, so the placeholder is that rail at its real size. Covers
                // then land in plates the reader has already been looking at.
                loading -> FolioCoverRailSkeleton(count = 4)
                else -> if (result.items.isEmpty() && result.error != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = FolioTheme.colors.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            result.error ?: "Search failed",
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                } else LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                ) {
                    items(result.items, key = { it.url + it.title }) { item ->
                        Column(
                            modifier = Modifier
                                .width(FolioTokens.coverRailWidth)
                                .clickable { onOpenManga(item) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(FolioTokens.coverPaneRatio)
                                    .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                            ) {
                                MangaCover(
                                    backend = viewModel.backend,
                                    sourceId = result.source.id,
                                    thumbnailUrl = item.thumbnailUrl,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(FolioTokens.spaceHair))
                            Text(
                                item.title,
                                style = FolioTheme.typography.labelSmall,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Checkable category editor shared by the detail screen (single manga) and the
 * library selection mode (bulk). Creating a category inline selects it, so a
 * brand-new shelf can be filled without leaving the dialog.
 */
@Composable
internal fun CategoryPickerDialog(
    categories: List<com.folio.reader.manga.MangaCategory>,
    initialSelected: Set<String>?,
    onCreate: suspend (String) -> String?,
    onApply: suspend (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Categories",
) {
    com.folio.reader.ui.library.LibraryAssignmentDialog(
        title = title,
        choices = categories.map { it.id to it.name },
        initialSelected = initialSelected,
        kind = "category",
        onCreate = onCreate,
        onConfirm = onApply,
        onDismiss = onDismiss,
    )
}
