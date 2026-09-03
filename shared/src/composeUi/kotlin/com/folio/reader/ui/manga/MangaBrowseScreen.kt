package com.folio.reader.ui.manga

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch

@Composable
fun MangaBrowseScreen(
    viewModel: BrowseViewModel,
    onOpenSource: (MangaSourceInfo, String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sources by viewModel.sources.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val refreshing by viewModel.refreshingIndex.collectAsState()
    val globalQuery by viewModel.globalQuery.collectAsState()
    val globalResults by viewModel.globalResults.collectAsState()
    val globalResultsOrdered by viewModel.globalResultsOrdered.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()
    val preparing by viewModel.preparingSources.collectAsState()
    var queryText by remember { mutableStateOf(viewModel.globalQuery.value) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        FolioTopBar(
            title = "Browse",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search all sources")
                }
                if (viewModel.supportsExtensions) {
                    IconButton(onClick = { viewModel.refreshIndex() }) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh extension index")
                        }
                    }
                }
            },
        )

        if (searchActive) {
            LaunchedEffect(searchActive, queryText) {
                kotlinx.coroutines.delay(350)
                val trimmed = queryText.trim()
                viewModel.globalSearch(if (trimmed.length >= 2) trimmed else "")
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FolioTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search all sources") },
                    singleLine = true,
                )
                Spacer(Modifier.width(FolioTokens.space1))
                IconButton(onClick = { queryText = "" }, enabled = queryText.isNotBlank()) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
            Spacer(Modifier.height(FolioTokens.space1))
        }

        if (searchActive && globalQuery.isNotBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
            ) {
                val finished = globalResults.count { !it.loading }
                item {
                    Text(
                        when {
                            preparing && globalResults.isEmpty() -> "Preparing sources…"
                            finished < globalResults.size -> "Searching ${finished + 1}/${globalResults.size}…"
                            else -> "${globalResults.sumOf { it.items.size }} results from ${globalResults.count { it.items.isNotEmpty() }} sources"
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
                                onViewAll = { onOpenSource(result.source, globalQuery) },
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
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                item {
                    Text(
                        "SOURCES",
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.primary,
                    )
                }
                sources.filter { it.isLocal }.forEach { source ->
                    item(key = "src-${source.id}") {
                        SourceRow(source = source, onClick = { onOpenSource(source, "") })
                    }
                }
                val englishSources = sources.filter { !it.isLocal && it.lang == "en" }
                val otherSources = sources.filter { !it.isLocal && it.lang != "en" }
                if (englishSources.isNotEmpty()) {
                    item(key = "hdr-en") {
                        Text(
                            "ENGLISH",
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary,
                            modifier = Modifier.padding(top = FolioTokens.space2),
                        )
                    }
                    englishSources.forEach { source ->
                        item(key = "src-${source.id}") {
                            SourceRow(source = source, onClick = { onOpenSource(source, "") })
                        }
                    }
                }
                if (otherSources.isNotEmpty()) {
                    item(key = "hdr-other") {
                        Text(
                            "OTHER LANGUAGES",
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary,
                            modifier = Modifier.padding(top = FolioTokens.space2),
                        )
                    }
                    otherSources.forEach { source ->
                        item(key = "src-${source.id}") {
                            SourceRow(source = source, onClick = { onOpenSource(source, "") })
                        }
                    }
                }
                if (viewModel.supportsExtensions) {
                    item {
                        Spacer(Modifier.height(FolioTokens.space2))
                        Text(
                            "EXTENSIONS",
                            style = MaterialTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary,
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                                .clickable(onClick = onOpenExtensions)
                                .padding(FolioTokens.space3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Extension, contentDescription = null, tint = FolioTheme.colors.primary)
                            Spacer(Modifier.width(FolioTokens.space2))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Manage extensions",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = FolioTheme.colors.onSurface,
                                )
                                Text(
                                    "${extensions.count { it.isInstalled }} installed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FolioTheme.colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Spacer(Modifier.height(FolioTokens.space2))
                        Text(
                            "Extensions run on Android only. On desktop you can read local manga " +
                                "(CBZ/ZIP/folders) imported into the library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: MangaSourceInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
            .clickable(onClick = onClick)
            .padding(FolioTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (source.isLocal) Icons.Filled.MenuBook else Icons.Filled.Explore,
            contentDescription = null,
            tint = FolioTheme.colors.primary,
        )
        Spacer(Modifier.width(FolioTokens.space2))
        Column(Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (source.isLocal) "CBZ, ZIP and image folders" else source.lang.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = FolioTheme.colors.onSurfaceVariant)
    }
}
