package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Series
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.theme.FolioTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Book) -> Unit,
    onBookDetailClick: (Book) -> Unit,
    onImportClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onStatsClick: () -> Unit = {},
    onTagManagerClick: () -> Unit = {},
    onQuoteBrowserClick: () -> Unit = {},
    onRevisitClick: () -> Unit = {},
    onDeleteBooks: (Set<String>) -> Unit = {},
    onSetBookStatus: (Set<String>, BookStatus) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel,
    syncState: com.folio.reader.sync.SyncState? = null,
    onSyncNow: () -> Unit = {}
) {
    var viewMode by remember { mutableStateOf(LibraryViewModel.ViewMode.GRID) }
    var sortBy by remember { mutableStateOf(LibraryViewModel.SortBy.LAST_OPENED) }
    var sortAscending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LibraryViewModel.FilterState()) }
    var selectedBooks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var seriesFilterOpen by remember { mutableStateOf(false) }
    var collectionFilterOpen by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }

    if (bookToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Delete Book") },
            text = { Text("Are you sure you want to delete '${bookToDelete?.title}'? This will remove the book and its local files.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        bookToDelete?.let { onDeleteBooks(setOf(it.id)) }
                        bookToDelete = null
                    }
                ) {
                    Text("Delete", color = FolioTheme.colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val allSeries by viewModel.allSeries().collectAsState(initial = emptyList())
    val allCollections by viewModel.allCollections().collectAsState(initial = emptyList())

    val books by viewModel.filteredBooks(
        LibraryViewModel.LibraryState(
            viewMode = viewMode,
            sortBy = sortBy,
            sortAscending = sortAscending,
            filter = filter,
            selectedBookIds = selectedBooks,
            isSelectionMode = isSelectionMode
        )
    ).collectAsState(initial = null as List<Book>?)

    fun toggleSelection(bookId: String) {
        selectedBooks = if (bookId in selectedBooks) selectedBooks - bookId else selectedBooks + bookId
        isSelectionMode = selectedBooks.isNotEmpty()
    }

    if (isSelectionMode) {
        // Selection top bar with bulk actions
        Column(modifier = Modifier.statusBarsPadding()) {
            com.folio.reader.ui.components.FolioTopBar(
                title = "${selectedBooks.size} selected",
                navigationIcon = {
                    IconButton(onClick = {
                        selectedBooks = emptySet()
                        isSelectionMode = false
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onSetBookStatus(selectedBooks, BookStatus.READING)
                        selectedBooks = emptySet(); isSelectionMode = false
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Mark as Reading")
                    }
                    IconButton(onClick = {
                        onSetBookStatus(selectedBooks, BookStatus.FINISHED)
                        selectedBooks = emptySet(); isSelectionMode = false
                    }) {
                        Icon(Icons.Filled.Done, contentDescription = "Mark as Finished")
                    }
                    IconButton(onClick = {
                        onDeleteBooks(selectedBooks)
                        selectedBooks = emptySet(); isSelectionMode = false
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete selected",
                            tint = FolioTheme.colors.error
                        )
                    }
                }
            )
            LibraryContent(
                books = books,
                viewMode = viewMode,
                sortBy = sortBy,
                sortAscending = sortAscending,
                filter = filter,
                allSeries = allSeries,
                allCollections = allCollections,
                selectedBooks = selectedBooks,
                isSelectionMode = true,
                onViewMode = { viewMode = it },
                onSortChange = { sortBy = it },
                onDirectionChange = { sortAscending = it },
                onFilterChange = { filter = it },
                onSeriesFilterOpen = { seriesFilterOpen = it },
                onCollectionFilterOpen = { collectionFilterOpen = it },
                seriesFilterOpen = seriesFilterOpen,
                collectionFilterOpen = collectionFilterOpen,
                onBookClick = { if (isSelectionMode) toggleSelection(it.id) else onBookDetailClick(it) },
                onBookLongClick = { toggleSelection(it.id) },
                onDeleteBook = { bookToDelete = it },
                onImportClick = onImportClick
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            com.folio.reader.ui.components.FolioTopBar(
                title = "Folio",
                actions = {
                    // Sync status badge (only when sync is configured)
                    if (syncState != null) {
                        com.folio.reader.ui.components.SyncStatusBadge(
                            syncState = syncState,
                            onClick = onSyncNow
                        )
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onImportClick) {
                        Icon(Icons.Filled.Add, contentDescription = "Import")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Statistics") },
                                onClick = { overflowOpen = false; onStatsClick() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Tags") },
                                onClick = { overflowOpen = false; onTagManagerClick() }
                            )
                            DropdownMenuItem(
                                text = { Text("Revisit Items") },
                                onClick = { overflowOpen = false; onRevisitClick() }
                            )
                        }
                    }
                }
            )

            LibraryContent(
                books = books,
                viewMode = viewMode,
                sortBy = sortBy,
                sortAscending = sortAscending,
                filter = filter,
                allSeries = allSeries,
                allCollections = allCollections,
                selectedBooks = selectedBooks,
                isSelectionMode = false,
                onViewMode = { viewMode = it },
                onSortChange = { sortBy = it },
                onDirectionChange = { sortAscending = it },
                onFilterChange = { filter = it },
                onSeriesFilterOpen = { seriesFilterOpen = it },
                onCollectionFilterOpen = { collectionFilterOpen = it },
                seriesFilterOpen = seriesFilterOpen,
                collectionFilterOpen = collectionFilterOpen,
                onBookClick = { onBookDetailClick(it) },
                onBookLongClick = { toggleSelection(it.id) },
                onDeleteBook = { bookToDelete = it },
                onImportClick = onImportClick
            )
        }
    }
}

@Composable
private fun LibraryContent(
    books: List<Book>?,
    viewMode: LibraryViewModel.ViewMode,
    sortBy: LibraryViewModel.SortBy,
    sortAscending: Boolean,
    filter: LibraryViewModel.FilterState,
    allSeries: List<Series>,
    allCollections: List<FolioCollection>,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean,
    onViewMode: (LibraryViewModel.ViewMode) -> Unit,
    onSortChange: (LibraryViewModel.SortBy) -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onFilterChange: (LibraryViewModel.FilterState) -> Unit,
    onSeriesFilterOpen: (Boolean) -> Unit,
    onCollectionFilterOpen: (Boolean) -> Unit,
    seriesFilterOpen: Boolean,
    collectionFilterOpen: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onImportClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // View mode & sort bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            viewModeButton("Grid", LibraryViewModel.ViewMode.GRID, viewMode, onViewMode)
            viewModeButton("List", LibraryViewModel.ViewMode.LIST, viewMode, onViewMode)
            viewModeButton("Compact", LibraryViewModel.ViewMode.COMPACT, viewMode, onViewMode)

            Spacer(modifier = Modifier.weight(1f))

            SortMenu(sortBy, sortAscending, onSortChange, onDirectionChange)
        }

        // Fixed 4dp separation between toolbar row and chips row on all screen sizes.
        // Do not use flexible/weighted spacers or extra vertical padding here.
        Spacer(modifier = Modifier.height(4.dp))

        // Filter chips: status + series + collection (horizontally scrollable)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.statuses.contains(BookStatus.READING),
                    onClick = {
                        onFilterChange(
                            if (filter.statuses.contains(BookStatus.READING)) filter.copy(statuses = filter.statuses - BookStatus.READING)
                            else filter.copy(statuses = filter.statuses + BookStatus.READING)
                        )
                    },
                    label = "Reading"
                )
            }
            item {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.statuses.contains(BookStatus.FINISHED),
                    onClick = {
                        onFilterChange(
                            if (filter.statuses.contains(BookStatus.FINISHED)) filter.copy(statuses = filter.statuses - BookStatus.FINISHED)
                            else filter.copy(statuses = filter.statuses + BookStatus.FINISHED)
                        )
                    },
                    label = "Finished"
                )
            }
            item {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.statuses.contains(BookStatus.UNREAD),
                    onClick = {
                        onFilterChange(
                            if (filter.statuses.contains(BookStatus.UNREAD)) filter.copy(statuses = filter.statuses - BookStatus.UNREAD)
                            else filter.copy(statuses = filter.statuses + BookStatus.UNREAD)
                        )
                    },
                    label = "Unread"
                )
            }

            item {
                Box {
                    com.folio.reader.ui.components.FolioChip(
                        selected = filter.seriesId != null,
                        onClick = { onSeriesFilterOpen(true) },
                        label = allSeries.firstOrNull { it.id == filter.seriesId }?.name ?: "Series"
                    )
                    DropdownMenu(
                        expanded = seriesFilterOpen,
                        onDismissRequest = { onSeriesFilterOpen(false) },
                        offset = DpOffset(0.dp, 36.dp)
                    ) {
                        DropdownMenuItem(text = { Text("All series") }, onClick = {
                            onFilterChange(filter.copy(seriesId = null)); onSeriesFilterOpen(false)
                        })
                        allSeries.forEach { series ->
                            DropdownMenuItem(text = { Text(series.name) }, onClick = {
                                onFilterChange(filter.copy(seriesId = series.id)); onSeriesFilterOpen(false)
                            })
                        }
                        if (allSeries.isEmpty()) {
                            DropdownMenuItem(text = { Text("No series yet") }, onClick = { onSeriesFilterOpen(false) })
                        }
                    }
                }
            }

            item {
                Box {
                    com.folio.reader.ui.components.FolioChip(
                        selected = filter.collectionId != null,
                        onClick = { onCollectionFilterOpen(true) },
                        label = allCollections.firstOrNull { it.id == filter.collectionId }?.name ?: "Collections"
                    )
                    DropdownMenu(
                        expanded = collectionFilterOpen,
                        onDismissRequest = { onCollectionFilterOpen(false) },
                        offset = DpOffset(0.dp, 36.dp)
                    ) {
                        DropdownMenuItem(text = { Text("All collections") }, onClick = {
                            onFilterChange(filter.copy(collectionId = null)); onCollectionFilterOpen(false)
                        })
                        allCollections.forEach { collection ->
                            DropdownMenuItem(text = { Text(collection.name) }, onClick = {
                                onFilterChange(filter.copy(collectionId = collection.id)); onCollectionFilterOpen(false)
                            })
                        }
                        if (allCollections.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No collections yet") },
                                onClick = { onCollectionFilterOpen(false) })
                        }
                    }
                }
            }

            if (filter.hasFilters()) {
                item {
                    // Must be a chip, not a TextButton: the button is taller, and being
                    // the only conditional item it changed the row's measured height as it
                    // scrolled in and out of composition, nudging every other chip.
                    com.folio.reader.ui.components.FolioChip(
                        selected = false,
                        onClick = { onFilterChange(LibraryViewModel.FilterState()) },
                        label = "Clear"
                    )
                }
            }
        }

        if (books == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(modifier = Modifier.width(96.dp))
            }
        } else if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("No books in library", style = FolioTheme.typography.headlineSmall)
                    Text("Import your first EPUB to get started", color = FolioTheme.colors.onSurfaceVariant)
                    Button(onClick = onImportClick) {
                        Text("Import EPUB")
                    }
                }
            }
        } else {
            when (viewMode) {
                LibraryViewModel.ViewMode.GRID -> BookGrid(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode
                )

                LibraryViewModel.ViewMode.LIST -> BookList(
                    books,
                    onBookClick,
                    onBookLongClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode
                )

                LibraryViewModel.ViewMode.COMPACT -> BookCompactList(
                    books,
                    onBookClick,
                    onDeleteBook,
                    selectedBooks,
                    isSelectionMode
                )
            }
        }
    }
}

@Composable
private fun viewModeButton(
    label: String,
    mode: LibraryViewModel.ViewMode,
    current: LibraryViewModel.ViewMode,
    onClick: (LibraryViewModel.ViewMode) -> Unit
) {
    com.folio.reader.ui.components.FolioChip(
        selected = current == mode,
        onClick = { onClick(mode) },
        label = label
    )
}

@Composable
private fun SortMenu(
    sortBy: LibraryViewModel.SortBy,
    sortAscending: Boolean,
    onSortChange: (LibraryViewModel.SortBy) -> Unit,
    onDirectionChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val friendlyNames = mapOf(
        LibraryViewModel.SortBy.LAST_OPENED to "Recently opened",
        LibraryViewModel.SortBy.DATE_ADDED to "Date added",
        LibraryViewModel.SortBy.TITLE to "Title",
        LibraryViewModel.SortBy.AUTHOR to "Author",
        LibraryViewModel.SortBy.PROGRESS to "Progress",
        LibraryViewModel.SortBy.READING_TIME to "Length",
        LibraryViewModel.SortBy.COMPLETION_DATE to "Completion date",
        LibraryViewModel.SortBy.FILE_SIZE to "File size"
    )

    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = friendlyNames[sortBy] ?: sortBy.name,
                style = FolioTheme.typography.labelLarge,
                color = FolioTheme.colors.onSurfaceVariant
            )
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = "Sort options",
                tint = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            friendlyNames.keys.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = friendlyNames[option] ?: option.name,
                            fontWeight = if (option == sortBy) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (option == sortBy) FolioTheme.colors.primary else FolioTheme.colors.onSurface
                        )
                    },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                    leadingIcon = {
                        if (option == sortBy) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = FolioTheme.colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (sortAscending) "Ascending" else "Descending") },
                onClick = { onDirectionChange(!sortAscending) },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = if (sortAscending) FolioTheme.colors.onSurfaceVariant else FolioTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
fun BookGrid(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(books) { book ->
            BookCard(
                book = book,
                isSelected = book.id in selectedBooks,
                isSelectionMode = isSelectionMode,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
                onDeleteBook = { onDeleteBook(book) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteBook: (Book) -> Unit
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hovered) 1.04f else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 200,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "cardScale"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(
                if (isSelected) Modifier.border(
                    2.dp, FolioTheme.colors.primary, RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hovered) 8.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) FolioTheme.colors.primaryContainer else FolioTheme.colors.surface,
            contentColor = if (isSelected) FolioTheme.colors.onPrimaryContainer else FolioTheme.colors.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                BookCover(
                    coverPath = book.coverPath,
                    title = book.title,
                    author = book.authors.firstOrNull() ?: ""
                )
                BookOptionsDropdown(
                    book = book,
                    onBookClick = { onClick() },
                    onDeleteBook = onDeleteBook,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    iconTint = Color.White
                )
            }

            // Progress bar
            if (book.normalizedProgress > 0 && book.normalizedProgress < 1) {
                com.folio.reader.ui.components.FolioProgressBar(
                    progress = book.normalizedProgress.toFloat(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = FolioTheme.colors.primary
                )
            }

            // Title
            Text(
                text = book.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = FolioTheme.typography.labelLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            // Author
            Text(
                text = book.displayAuthor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            // Progress text
            if (book.normalizedProgress > 0) {
                Text(
                    text = "${book.progressPercent}%",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Status badge
            if (book.status != BookStatus.UNREAD) {
                Surface(
                    modifier = Modifier.padding(top = 4.dp),
                    shape = RoundedCornerShape(50),
                    color = FolioTheme.colors.secondaryContainer
                ) {
                    Text(
                        text = book.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun BookList(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(books) { book ->
            BookListItem(
                book = book,
                isSelected = book.id in selectedBooks,
                isSelectionMode = isSelectionMode,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
                onDeleteBook = { onDeleteBook(book) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteBook: (Book) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                BookCover(
                    coverPath = book.coverPath,
                    title = book.title,
                    author = book.authors.firstOrNull() ?: "",
                    small = true
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    book.title,
                    style = FolioTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    book.displayAuthor,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (book.normalizedProgress > 0) {
                        Text(
                            "${book.progressPercent}%",
                            style = FolioTheme.typography.labelSmall,
                            color = FolioTheme.colors.primary
                        )
                    }
                    Text(
                        book.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }

            // Progress bar
            if (book.normalizedProgress > 0 && book.normalizedProgress < 1) {
                LinearProgressIndicator(
                    modifier = Modifier.width(60.dp),
                    progress = book.normalizedProgress.toFloat(),
                    color = FolioTheme.colors.primary
                )
            }

            BookOptionsDropdown(
                book = book,
                onBookClick = { onClick() },
                onDeleteBook = onDeleteBook,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun BookCompactList(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(books) { book ->
            BookCompactItem(
                book = book,
                isSelected = book.id in selectedBooks,
                isSelectionMode = isSelectionMode,
                onClick = { onBookClick(book) },
                onDeleteBook = { onDeleteBook(book) }
            )
        }
    }
}

@Composable
fun BookCompactItem(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onDeleteBook: (Book) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(32.dp)
                .height(48.dp)
        ) {
            BookCover(
                coverPath = book.coverPath,
                title = book.title,
                author = book.authors.firstOrNull() ?: "",
                small = true
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(book.title, style = FolioTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${book.displayAuthor} · ${book.progressPercent}% · ${book.status.name}",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        BookOptionsDropdown(
            book = book,
            onBookClick = { onClick() },
            onDeleteBook = onDeleteBook,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
private fun BookOptionsDropdown(
    book: Book,
    onBookClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = FolioTheme.colors.onSurfaceVariant
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Book options",
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Book Details") },
                onClick = {
                    expanded = false
                    onBookClick(book)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete Book", color = FolioTheme.colors.error) },
                onClick = {
                    expanded = false
                    onDeleteBook(book)
                }
            )
        }
    }
}
