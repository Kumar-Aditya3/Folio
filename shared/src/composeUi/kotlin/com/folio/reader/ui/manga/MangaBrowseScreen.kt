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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import com.folio.reader.ui.components.FolioMenuItem as DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.folio.reader.ui.components.FolioInputField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.components.FolioSourceSectionSkeleton
import com.folio.reader.ui.components.FolioHubHeading
import com.folio.reader.ui.components.FolioHubRow
import com.folio.reader.ui.components.FolioHubSection
import com.folio.reader.ui.components.FolioHubGroupItem
import com.folio.reader.ui.library.FolioSearchField
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.folioTypeGround
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioBarTopInset
import kotlinx.coroutines.launch

@Composable
fun MangaBrowseScreen(
    viewModel: BrowseViewModel,
    onOpenSource: (MangaSourceInfo, String) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sources by viewModel.visibleSources.collectAsState()
    val allSources by viewModel.sources.collectAsState()
    val availableLanguages by viewModel.availableLanguages.collectAsState()
    val enabledLanguages by viewModel.sourceLanguages.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val loadErrors by viewModel.extensionLoadErrors.collectAsState()
    val refreshing by viewModel.refreshingIndex.collectAsState()
    val globalQuery by viewModel.globalQuery.collectAsState()
    val globalResults by viewModel.globalResults.collectAsState()
    val globalResultsOrdered by viewModel.globalResultsOrdered.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()
    val preparing by viewModel.preparingSources.collectAsState()
    var queryText by remember { mutableStateOf(viewModel.globalQuery.value) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val headerState = rememberFolioHeaderState()
    // The masthead is overlaid on the list rather than stacked above it, so source
    // rows pass under its glass. A translucent veil over the page's own flat,
    // unchanging field is indistinguishable from a slightly different flat field,
    // which is why the bar read as invisible however its alpha was tuned.
    val barInset = folioBarTopInset()
    // The furniture this screen hangs under the bar — here only the search field,
    // and only while it is open. Measured, not guessed, and measured on the
    // furniture alone: the bar grows a hairline and a 10dp fade once it collapses,
    // and a padding that tracked them would walk every row up the screen under the
    // reader's finger.
    var furniturePx by remember { mutableStateOf(0) }
    val topInset = barInset + with(LocalDensity.current) { furniturePx.toDp() }

    if (searchActive) {
        LaunchedEffect(searchActive, queryText) {
            kotlinx.coroutines.delay(350)
            val trimmed = queryText.trim()
            viewModel.globalSearch(if (trimmed.length >= 2) trimmed else "")
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (searchActive && globalQuery.isNotBlank()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection)
                    .folioBackdropSource().folioPageWash(),
                contentPadding = PaddingValues(
                    start = FolioTokens.gutter,
                    top = topInset + FolioTokens.space2,
                    end = FolioTokens.gutter,
                    bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
            ) {
                val finished = globalResults.count { !it.loading }
                item { MangaChallengePrompt() }
                item {
                    Text(
                        when {
                            preparing && globalResults.isEmpty() -> "Preparing sources…"
                            finished < globalResults.size -> "Searching ${finished + 1}/${globalResults.size}…"
                            else -> "${globalResults.sumOf { it.items.size }} results from ${globalResults.count { it.items.isNotEmpty() }} sources"
                        },
                        style = FolioTheme.typography.labelSmall,
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
                // Resolving the installed-source list happens before any section
                // exists, so without this the reader gets "Preparing sources…" over a
                // blank page. Headed rails stand in and are replaced by the real
                // sections as each one registers.
                if (preparing && globalResults.isEmpty()) {
                    items(2, key = { "preparing-$it" }) {
                        FolioSourceSectionSkeleton()
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection)
                    .folioBackdropSource().folioPageWash(),
                contentPadding = PaddingValues(
                    start = FolioTokens.gutter,
                    top = topInset + FolioTokens.space2,
                    end = FolioTokens.gutter,
                    bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item { MangaChallengePrompt() }
                val local = sources.filter { it.isLocal }
                if (local.isNotEmpty()) {
                    item(key = "local-heading") { BrowseGroupHeading("On this device", first = true) }
                    itemsIndexed(local, key = { _, source -> "src-${source.id}" }) { index, source ->
                        FolioHubGroupItem(index == 0, index == local.lastIndex) {
                            SourceRow(source, last = index == local.lastIndex, onClick = { onOpenSource(source, "") })
                        }
                    }
                }
                val grouped = sources.filterNot { it.isLocal }.groupBy { it.lang.lowercase() }.toList()
                    .sortedWith(compareByDescending<Pair<String, List<MangaSourceInfo>>> { it.first == "en" }
                        .thenBy { languageLabel(it.first) })
                grouped.forEachIndexed { groupIndex, (lang, entries) ->
                    val group = entries.sortedBy { it.name.lowercase() }
                    item(key = "hdr-$lang") { BrowseGroupHeading(languageLabel(lang), first = local.isEmpty() && groupIndex == 0) }
                    itemsIndexed(group, key = { _, source -> "src-${source.id}" }) { index, source ->
                        FolioHubGroupItem(index == 0, index == group.lastIndex) {
                            SourceRow(source, last = index == group.lastIndex, onClick = { onOpenSource(source, "") })
                        }
                    }
                }
                val hiddenCount = allSources.count { source -> !source.isLocal && sources.none { it.id == source.id } }
                if (hiddenCount > 0) item(key = "lang-hint") {
                    TextButton(onClick = { viewModel.setSourceLanguages(availableLanguages.toSet()) }) {
                        Text("Show $hiddenCount sources hidden by language")
                    }
                }
                val attention = unavailableBrowseExtensions(extensions, allSources)
                if (attention.isNotEmpty() || loadErrors.isNotEmpty()) item(key = "source-attention") {
                    Spacer(Modifier.height(FolioTokens.spaceMovement))
                    FolioHubSection("Needs attention", FolioTheme.colors.error) {
                        attention.forEach { entry ->
                            FolioHubRow(Icons.Filled.Extension, entry.name,
                                if (entry.isUntrusted) "Review trust to enable this source" else "No available sources · check this extension",
                                FolioTheme.colors.error, last = entry == attention.last() && loadErrors.isEmpty(), onClick = onOpenExtensions)
                        }
                        loadErrors.forEachIndexed { index, error ->
                            FolioHubRow(Icons.Filled.Extension, "Extension could not load", error,
                                FolioTheme.colors.error, last = index == loadErrors.lastIndex, onClick = onOpenExtensions)
                        }
                    }
                }
                if (viewModel.supportsExtensions) item(key = "manage-extensions") {
                    Spacer(Modifier.height(FolioTokens.spaceMovement))
                    FolioHubSection("Extensions", FolioTheme.colors.tertiary) {
                        FolioHubRow(Icons.Filled.Extension, "Manage extensions",
                            "${extensions.count { it.isInstalled }} installed · add or update sources",
                            FolioTheme.colors.tertiary, last = true, onClick = onOpenExtensions)
                    }
                } else item(key = "extensions-unavailable") {
                    Text("Extensions run on Android. Import CBZ, ZIP or image folders to read manga on this device.",
                        style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = FolioTokens.spaceMovement))
                }

            }
        }

        FolioTopBar(
            title = "Browse",
            collapse = if (searchActive) 0f else headerState.collapse,
            modifier = Modifier.align(Alignment.TopCenter),
            bottomRule = false,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleSearch() }) {
                    Icon(if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchActive) "Close search" else "Search all sources")
                }
                if (availableLanguages.isNotEmpty() || viewModel.supportsExtensions) {
                    // The language filter is what keeps one site from filling the list
                    // with a row per language it publishes.
                    Box {
                        IconButton(onClick = { languageMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Source options")
                        }
                        com.folio.reader.ui.components.FolioMenu(
                            expanded = languageMenuOpen,
                            onDismissRequest = { languageMenuOpen = false },
                        ) {
                            if (viewModel.supportsExtensions) DropdownMenuItem(
                                text = { Text(if (refreshing) "Refreshing catalog…" else "Refresh catalog") }, enabled = !refreshing,
                                onClick = { viewModel.refreshIndex(); languageMenuOpen = false })
                            val allOn = enabledLanguages.containsAll(availableLanguages)
                            if (availableLanguages.isNotEmpty()) DropdownMenuItem(
                                text = { Text(if (allOn) "Only my languages" else "All languages") },
                                onClick = {
                                    viewModel.setSourceLanguages(
                                        if (allOn) defaultSourceLanguages() else availableLanguages.toSet(),
                                    )
                                },
                            )
                            if (availableLanguages.isNotEmpty()) HorizontalDivider()
                            availableLanguages.forEach { lang ->
                                val on = lang in enabledLanguages
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            languageLabel(lang),
                                            color = if (on) FolioTheme.colors.primary else FolioTheme.colors.onSurface,
                                        )
                                    },
                                    leadingIcon = {
                                        if (on) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = FolioTheme.colors.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                    },
                                    // Stays open: picking languages is a several-taps job.
                                    onClick = { viewModel.toggleSourceLanguage(lang) },
                                )
                            }
                        }
                    }
                }
            },
            rail = {
                Box(Modifier.fillMaxWidth().onSizeChanged { furniturePx = it.height }) {
                    if (searchActive) Box(Modifier.fillMaxWidth().padding(horizontal = FolioTokens.gutter, vertical = 12.dp)) {
                        FolioSearchField(queryText, { queryText = it }, "Search all sources", Modifier.fillMaxWidth())
                    }
                }
            },
        )
    }
}

@Composable
internal fun BrowseGroupHeading(title: String, first: Boolean = false) {
    FolioHubHeading(title, FolioTheme.colors.tertiary,
        Modifier.padding(top = if (first) 0.dp else FolioTokens.spaceMovement, bottom = FolioTokens.space2))
}

@Composable
internal fun SourceRow(source: MangaSourceInfo, last: Boolean = true, onClick: () -> Unit) {
    FolioHubRow(
        icon = if (source.isLocal) Icons.AutoMirrored.Filled.MenuBook else Icons.Filled.Explore,
        title = source.name,
        subtitle = when {
            source.isLocal -> "CBZ, ZIP and image folders"
            source.supportsLatest -> "Popular titles and latest chapters"
            else -> "Browse the catalog"
        },
        accent = FolioTheme.colors.tertiary, last = last, onClick = onClick)
}
