package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaFilter
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioCoverGridSkeleton
import com.folio.reader.ui.components.FolioCoverPaneSkeleton
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.components.folioTypeGround
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioBarTopInset
import kotlinx.coroutines.launch

@Composable
fun SourceBrowseScreen(
    viewModel: SourceBrowseViewModel,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val filterTemplate by viewModel.filterTemplate.collectAsState()
    var searchActive by remember { mutableStateOf(viewModel.state.value.query.isNotBlank()) }
    var searchQuery by remember { mutableStateOf(viewModel.state.value.query) }
    var showFilters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val safeGridIndex = viewModel.gridScrollIndex.coerceAtLeast(0)
    val safeGridOffset = viewModel.gridScrollOffset.coerceAtLeast(0)
    val gridState = remember(viewModel) { LazyGridState(safeGridIndex, safeGridOffset) }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.gridScrollIndex = gridState.firstVisibleItemIndex
            viewModel.gridScrollOffset = gridState.firstVisibleItemScrollOffset
        }
    }

    val headerState = rememberFolioHeaderState()
    // The masthead — bar plus the mode chips, and the search field while it is open —
    // overlays the grid rather than sitting above it, so covers pass under the glass.
    // A translucent veil over the page's own flat, unchanging field is
    // indistinguishable from a slightly different flat field, which is why the bar
    // read as invisible however its alpha was tuned.
    val barInset = folioBarTopInset()
    // A chip row's height is type-driven, so it is measured rather than guessed.
    // Measured on the furniture and not on the whole masthead: the bar grows a
    // hairline and a 10dp fade once it collapses, and a padding that tracked them
    // would walk every cover up the screen under the reader's finger.
    var furniturePx by remember { mutableStateOf(0) }
    val topInset = barInset + with(LocalDensity.current) { furniturePx.toDp() }

    Box(Modifier.fillMaxSize().folioBackdropSource().folioPageWash()) {
        when {
            // Opening a source: draw the grid before the source answers, at the same
            // adaptive columns the results will use. The first page then lands in
            // cells the reader has already seen instead of replacing a spinner with
            // a full screen of covers.
            state.loading && state.items.isEmpty() ->
                // Non-scrolling, so it takes the masthead's room as an outer padding:
                // content padding on a placeholder that does not scroll buys nothing.
                FolioCoverGridSkeleton(
                    modifier = Modifier.fillMaxSize().padding(top = topInset),
                )
            state.error != null && state.items.isEmpty() ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = topInset),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = FolioTheme.colors.error)
                        Spacer(Modifier.height(FolioTokens.space1))
                        Text(state.error ?: "", color = FolioTheme.colors.onSurface)
                        Spacer(Modifier.height(FolioTokens.space2))
                        Button(onClick = { viewModel.reload(state.mode, state.query, state.filters) }) {
                            Text("Retry")
                        }
                        Spacer(Modifier.height(FolioTokens.space2))
                        MangaChallengePrompt(sourceId = viewModel.source.id)
                    }
                }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = FolioTokens.coverGridMin),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = FolioTokens.space3,
                    top = topInset + FolioTokens.space3,
                    end = FolioTokens.space3,
                    bottom = FolioTokens.space3,
                ),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
            ) {
                items(state.items, key = { it.url + it.title }) { item ->
                    BrowseGridItem(
                        item = item,
                        viewModel = viewModel,
                        onClick = {
                            scope.launch {
                                val entry = viewModel.ensureEntry(item)
                                onOpenManga(entry.id)
                            }
                        },
                    )
                }
                if (state.hasNextPage) {
                    item {
                        LaunchedEffect(Unit) { viewModel.loadNextPage() }
                        // The next page arrives as more panes, so the footer is a pane
                        // rather than a spinner — the grid's rhythm carries on and the
                        // cell the reader is scrolling toward is already the right size.
                        FolioCoverPaneSkeleton()
                    }
                }
            }
        }

        // The furniture the screen hangs under the masthead, overlaid with it so
        // covers still run behind the glass. Its measured height — which grows and
        // shrinks as the search field opens and closes — is what the grid's top
        // padding clears.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = barInset)
                // Its own ground: the search field's Material container is transparent, so
                // covers passing under it would render straight through the query text.
                // The glass veil (not a flat opaque fill) keeps this strip consistent with
                // every other surface — the rows still frost through it instead of being
                // boxed out by an opaque rectangle. Bottom-only rounding so it meets the
                // screen edges cleanly under the masthead.
                .folioVeil(shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                .onSizeChanged { furniturePx = it.height },
        ) {
            if (searchActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = FolioTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search ${viewModel.source.name}") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(FolioTokens.space1))
                    IconButton(onClick = { viewModel.reload(BrowseMode.POPULAR, searchQuery, state.filters) }) {
                        Icon(Icons.Filled.Check, contentDescription = "Run search")
                    }
                }
                Spacer(Modifier.height(FolioTokens.space1))
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = FolioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1),
            ) {
                item {
                    FolioChip(
                        selected = state.mode == BrowseMode.POPULAR && state.query.isBlank(),
                        onClick = { viewModel.reload(BrowseMode.POPULAR, "", null) },
                        label = "Popular",
                    )
                }
                if (viewModel.source.supportsLatest) {
                    item {
                        FolioChip(
                            selected = state.mode == BrowseMode.LATEST && state.query.isBlank(),
                            onClick = { viewModel.reload(BrowseMode.LATEST, "", null) },
                            label = "Latest",
                        )
                    }
                }
                if (state.query.isNotBlank()) {
                    item {
                        FolioChip(selected = true, onClick = {}, label = "“${state.query}”")
                    }
                }
            }
            Spacer(Modifier.height(FolioTokens.space2))
        }

        FolioTopBar(
            title = viewModel.source.name,
            collapse = headerState.collapse,
            modifier = Modifier.align(Alignment.TopCenter),
            bottomRule = false,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { searchActive = !searchActive }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                if (filterTemplate.isNotEmpty()) {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filters")
                    }
                }
            },
        )
    }

    if (showFilters) {
        FilterSheet(
            template = filterTemplate,
            onApply = { filters ->
                showFilters = false
                viewModel.reload(state.mode, state.query, filters)
            },
            onDismiss = { showFilters = false },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowseGridItem(
    item: MangaBrowseItem,
    viewModel: SourceBrowseViewModel,
    onClick: () -> Unit,
) {
    var inLibrary by remember(item.url) { mutableStateOf(false) }
    LaunchedEffect(item.url) { inLibrary = viewModel.isInLibrary(item) }
    val backend = viewModel.backend
    val itemScope = rememberCoroutineScope()
    var pickMangaId by remember { mutableStateOf<String?>(null) }
    var pickInitial by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    itemScope.launch {
                        if (inLibrary) {
                            viewModel.removeFromLibrary(item)
                            inLibrary = false
                        } else {
                            val entry = viewModel.ensureEntry(item)
                            pickInitial = viewModel.categoriesFor(entry.id)
                            pickMangaId = entry.id
                        }
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(FolioTokens.coverPaneRatio)
                .glassPanel(RoundedCornerShape(FolioTokens.radiusChip)),
        ) {
            MangaCover(
                backend = backend,
                sourceId = viewModel.source.id,
                thumbnailUrl = item.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
            )
            if (inLibrary) {
                Icon(
                    Icons.Filled.LibraryAddCheck,
                    contentDescription = "In library",
                    // §12.3: "already in library" is a progress/ownership cue — it
                    // reads in accentProgress rather than the generic primary.
                    tint = FolioTheme.colors.accentProgress,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        // Issue 4: the fourth shelf cell, and the one the `folioTypeGround` pass missed.
        // BookCard, MangaGridItem and DocumentGridItem all wash the page's own colour under
        // their captions; this one did not, so a source's browse grid was the only shelf in
        // the app whose titles sat raw on the Mirage field. Same primitive, same strength,
        // full cell width — the leading Spacer becomes the wash's own top padding so the type
        // does not move.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .folioTypeGround()
                .padding(top = 6.dp, bottom = FolioTokens.space1),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (pickMangaId != null) {
        val categories by viewModel.allCategories.collectAsState()
        CategoryPickerDialog(
            categories = categories,
            initialSelected = pickInitial,
            onCreate = { viewModel.createCategoryNamed(it) },
            onApply = {
                viewModel.addToLibrary(item, it)
                inLibrary = true
            },
            onDismiss = { pickMangaId = null },
            title = "Add to library",
        )
    }
}

@Composable
private fun FilterSheet(
    template: List<MangaFilter>,
    onApply: (List<MangaFilter>) -> Unit,
    onDismiss: () -> Unit,
) {
    var filters by remember { mutableStateOf(template) }

    // §16 P5: the app's own in-window pane rather than Material's `AlertDialog` (see FolioDialog).
    com.folio.reader.ui.components.FolioDialog(
        expanded = true,
        onDismissRequest = onDismiss,
        title = "Filters",
    ) {
        LazyColumn(
            // Content is inset to match the pane title (FolioOverlayHost draws the title at
            // space3 horizontal); without this the filter controls run flush to the glass edge.
            modifier = Modifier
                .heightIn(max = 420.dp)
                .padding(horizontal = FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    filters.forEachIndexed { index, filter ->
                        FilterControl(filter = filter) { updated ->
                            filters = filters.toMutableList().also { it[index] = updated }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioTokens.space3)
                .padding(top = 12.dp, bottom = FolioTokens.space1),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onApply(filters) }) { Text("Apply") }
        }
    }
}

@Composable
private fun FilterControl(filter: MangaFilter, onChange: (MangaFilter) -> Unit) {
    val colors = FolioTheme.colors
    when (filter) {
        is MangaFilter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.titleSmall,
            // §12.3: filter header label uses the manga discovery hue, not primary.
            color = colors.accentDiscovery,
            modifier = Modifier.padding(top = 6.dp),
        )
        is MangaFilter.Separator -> Spacer(Modifier.height(2.dp))
        is MangaFilter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = { onChange(filter.copy(state = it)) },
            label = { Text(filter.name.ifBlank { "Text" }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is MangaFilter.CheckBox -> Row(
            modifier = Modifier.fillMaxWidth().clickable { onChange(filter.copy(state = !filter.state)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = filter.state, onCheckedChange = { onChange(filter.copy(state = it)) })
            Text(filter.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
        is MangaFilter.TriState -> Row(
            modifier = Modifier.fillMaxWidth().clickable { onChange(filter.copy(state = (filter.state + 1) % 3)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = when (filter.state) {
                    1 -> androidx.compose.ui.state.ToggleableState.Indeterminate
                    2 -> androidx.compose.ui.state.ToggleableState.On
                    else -> androidx.compose.ui.state.ToggleableState.Off
                },
                onClick = { onChange(filter.copy(state = (filter.state + 1) % 3)) },
            )
            Text(filter.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
        is MangaFilter.Select -> Column(Modifier.fillMaxWidth()) {
            Text(filter.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(filter.values.getOrNull(filter.state) ?: "Select")
                    }
                    com.folio.reader.ui.components.FolioMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        filter.values.forEachIndexed { index, value ->
                            DropdownMenuItem(
                                text = { Text(value) },
                                onClick = {
                                    expanded = false
                                    onChange(filter.copy(state = index))
                                },
                            )
                        }
                    }
                }
            }
        }
        is MangaFilter.Sort -> Column(Modifier.fillMaxWidth()) {
            Text(filter.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            filter.values.forEachIndexed { index, value ->
                val selected = filter.selection?.index == index
                val ascending = filter.selection?.ascending ?: true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newSelection = if (selected) {
                                MangaFilter.Sort.SortSelection(index, !ascending)
                            } else {
                                MangaFilter.Sort.SortSelection(index, true)
                            }
                            onChange(filter.copy(selection = newSelection))
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when {
                            selected && ascending -> Icons.Filled.ArrowUpward
                            selected -> Icons.Filled.ArrowDownward
                            else -> Icons.AutoMirrored.Filled.Sort
                        },
                        contentDescription = null,
                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) colors.primary else colors.onSurface,
                    )
                }
            }
        }
        is MangaFilter.Group -> Column(Modifier.fillMaxWidth()) {
            // §12.3: a filter-group heading is a section label, not a selection —
            // it uses the manga discovery hue instead of the generic primary.
            Text(filter.name, style = MaterialTheme.typography.titleSmall, color = colors.accentDiscovery)
            filter.filters.forEachIndexed { index, child ->
                FilterControl(child) { updated ->
                    onChange(filter.copy(filters = filter.filters.toMutableList().also { it[index] = updated }))
                }
            }
        }
    }
}
