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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch

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
            Text(
                when {
                    preparing && globalResults.isEmpty() -> "Preparing sources…"
                    finished < globalResults.size -> "Searching ${finished + 1}/${globalResults.size}…"
                    else -> "$totalItems results from ${globalResults.count { it.items.isNotEmpty() }} sources"
                },
                style = MaterialTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant,
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
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                result.source.name,
                style = MaterialTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.items.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = onViewAll) { Text("View all") }
            }
        }
        Spacer(Modifier.height(FolioTokens.space1))
        AnimatedContent(
            targetState = result.loading,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "source-section-${result.source.id}",
        ) { loading ->
            when {
                loading -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                items(3) {
                    Column(Modifier.width(96.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.68f)
                                .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .background(
                                    FolioTheme.colors.surfaceVariant,
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
            }
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
                        style = MaterialTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            } else LazyRow(
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                items(result.items, key = { it.url + it.title }) { item ->
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .clickable { onOpenManga(item) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.68f)
                                .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
                        ) {
                            MangaCover(
                                backend = viewModel.backend,
                                sourceId = result.source.id,
                                thumbnailUrl = item.thumbnailUrl,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelSmall,
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
    initialSelected: Set<String>,
    onCreate: suspend (String) -> String?,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    val scope = rememberCoroutineScope()
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var newName by remember { mutableStateOf("") }

    // Taps apply immediately — membership is written on every change, no confirm step.
    fun apply(next: Set<String>) {
        selected = next
        onApply(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (categories.isEmpty()) {
                    Text(
                        "No categories yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                apply(
                                    if (category.id in selected) selected - category.id
                                    else selected + category.id,
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = category.id in selected,
                            onCheckedChange = {
                                apply(if (it) selected + category.id else selected - category.id)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            if (name.isNotBlank()) {
                                scope.launch {
                                    onCreate(name)?.let { apply(selected + it) }
                                }
                                newName = ""
                            }
                        },
                        enabled = newName.trim().isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
