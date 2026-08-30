package com.folio.reader.ui.tags

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Highlight
import com.folio.reader.model.Tag
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TagCount(
    val tag: Tag,
    val bookCount: Int,
    val highlightCount: Int
)

data class TagDetailItem(
    val tag: Tag,
    val books: List<Book>,
    val highlights: List<HighlightWithBook>
)

data class HighlightWithBook(
    val highlight: Highlight,
    val book: Book
)

class TagManagerViewModel(
    private val getAllTags: suspend () -> List<Tag>,
    private val insertTag: suspend (Tag) -> Unit,
    private val updateTag: suspend (Tag) -> Unit,
    private val deleteTag: suspend (String) -> Unit,
    private val getTagsForBook: suspend (String) -> List<Tag>,
    private val getTagsForHighlight: suspend (String) -> List<Tag>,
    private val getBooksForTag: suspend (tagId: String) -> List<Book>,
    private val getHighlightsForTag: suspend (tagId: String) -> List<Highlight>,
    private val getBook: suspend (String) -> Book?
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _tags = flow {
        emit(loadTags())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private suspend fun loadTags(): List<TagCount> {
        val tags = getAllTags()
        return tags.map { tag ->
            val books = getBooksForTag(tag.id)
            val highlights = getHighlightsForTag(tag.id)
            TagCount(tag, books.size, highlights.size)
        }
    }

    val tags: kotlinx.coroutines.flow.StateFlow<List<TagCount>> = _tags

    fun refresh() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val loaded = loadTags()
            (_tags as MutableStateFlow).value = loaded
        }
    }

    suspend fun loadTagDetail(tagId: String): TagDetailItem? {
        val tag = getAllTags().find { it.id == tagId } ?: return null
        val books = getBooksForTag(tagId)
        val highlights = getHighlightsForTag(tagId).mapNotNull { hl ->
            val book = getBook(hl.bookId) ?: return@mapNotNull null
            HighlightWithBook(hl, book)
        }
        return TagDetailItem(tag, books, highlights)
    }

    fun createTag(name: String, color: Int?, onDone: () -> Unit = {}) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val newTag = Tag(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim(),
                color = color
            )
            insertTag(newTag)
            refresh()
            onDone()
        }
    }

    fun renameTag(tag: Tag, newName: String, onDone: () -> Unit = {}) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            updateTag(tag.copy(name = newName.trim()))
            refresh()
            onDone()
        }
    }

    fun changeTagColor(tag: Tag, newColor: Int?, onDone: () -> Unit = {}) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            updateTag(tag.copy(color = newColor))
            refresh()
            onDone()
        }
    }

    fun deleteTag(tagId: String, onDone: () -> Unit = {}) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            deleteTag(tagId)
            refresh()
            onDone()
        }
    }
}

private val TagPresetColors = listOf(
    0xFFF44336.toInt(),
    0xFFFF9800.toInt(),
    0xFFFFEB3B.toInt(),
    0xFF4CAF50.toInt(),
    0xFF00BCD4.toInt(),
    0xFF2196F3.toInt(),
    0xFF3F51B5.toInt(),
    0xFF9C27B0.toInt(),
    0xFFE91E63.toInt(),
    0xFF795548.toInt(),
    0xFF607D8B.toInt(),
    0xFF000000.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerScreen(
    onBack: () -> Unit,
    onHighlightClick: (Highlight, Book) -> Unit,
    onBookClick: (Book) -> Unit,
    viewModel: TagManagerViewModel
) {
    val scope = rememberCoroutineScope()
    val tags by viewModel.tags.collectAsState()
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    var selectedTagDetail by remember { mutableStateOf<TagDetailItem?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Tag?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Tag?>(null) }
    var showColorPickerFor by remember { mutableStateOf<Tag?>(null) }

    androidx.compose.runtime.LaunchedEffect(selectedTagId) {
        selectedTagDetail = null
        selectedTagId?.let { id ->
            selectedTagDetail = viewModel.loadTagDetail(id)
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                com.folio.reader.ui.components.FolioStatusBarBand()
                TopAppBar(
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    title = {
                        when {
                            selectedTagDetail != null -> {
                                Column {
                                    Text("Tag Details", fontWeight = FontWeight.Bold)
                                    selectedTagDetail?.let { detail ->
                                        Text(
                                            detail.tag.name,
                                            style = FolioTheme.typography.bodySmall,
                                            color = FolioTheme.colors.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            else -> Text("Tags", fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        if (selectedTagDetail != null) {
                            IconButton(onClick = {
                                selectedTagId = null
                                selectedTagDetail = null
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Back")
                            }
                        } else {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (selectedTagDetail == null) {
                            IconButton(onClick = { showCreateDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Create tag")
                            }
                        } else {
                            selectedTagDetail?.tag?.let { tag ->
                                IconButton(onClick = { showColorPickerFor = tag }) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                tag.color?.let { Color(it) } ?: FolioTheme.colors.primary,
                                                CircleShape
                                            )
                                    )
                                }
                                IconButton(onClick = { showEditDialog = tag }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                }
                                IconButton(onClick = { showDeleteDialog = tag }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = FolioTheme.colors.surface,
                        titleContentColor = FolioTheme.colors.onSurface
                    )
                )
            }
        }
    ) { padding ->
        when {
            selectedTagDetail != null -> TagDetailView(
                detail = selectedTagDetail!!,
                padding = padding,
                onHighlightClick = onHighlightClick,
                onBookClick = onBookClick
            )
            else -> TagListView(
                tags = tags,
                padding = padding,
                onTagClick = { tagCount ->
                    selectedTagId = tagCount.tag.id
                },
                onTagLongClick = { tagCount ->
                    showColorPickerFor = tagCount.tag
                },
                onEditClick = { tagCount -> showEditDialog = tagCount.tag },
                onDeleteClick = { tagCount -> showDeleteDialog = tagCount.tag }
            )
        }
    }

    if (showCreateDialog) {
        TagEditDialog(
            title = "Create Tag",
            initialName = "",
            initialColor = TagPresetColors.random(),
            onConfirm = { name, color ->
                viewModel.createTag(name, color)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    showEditDialog?.let { tag ->
        TagEditDialog(
            title = "Rename Tag",
            initialName = tag.name,
            initialColor = tag.color ?: TagPresetColors[0],
            onConfirm = { name, color ->
                viewModel.renameTag(tag, name)
                if (color != tag.color) {
                    viewModel.changeTagColor(tag, color)
                }
                showEditDialog = null
            },
            onDismiss = { showEditDialog = null }
        )
    }

    showDeleteDialog?.let { tag ->
        DeleteTagDialog(
            tag = tag,
            onConfirm = {
                viewModel.deleteTag(tag.id)
                showDeleteDialog = null
                if (selectedTagId == tag.id) {
                    selectedTagId = null
                    selectedTagDetail = null
                }
            },
            onDismiss = { showDeleteDialog = null }
        )
    }

    showColorPickerFor?.let { tag ->
        ColorPickerDialog(
            currentColor = tag.color,
            onColorSelected = { color ->
                viewModel.changeTagColor(tag, color) {
                    scope.launch {
                        selectedTagId?.let { id ->
                            selectedTagDetail = viewModel.loadTagDetail(id)
                        }
                    }
                }
                showColorPickerFor = null
            },
            onDismiss = { showColorPickerFor = null }
        )
    }
}

@Composable
private fun TagListView(
    tags: List<TagCount>,
    padding: PaddingValues,
    onTagClick: (TagCount) -> Unit,
    onTagLongClick: (TagCount) -> Unit,
    onEditClick: (TagCount) -> Unit,
    onDeleteClick: (TagCount) -> Unit
) {
    if (tags.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No tags yet", style = FolioTheme.typography.headlineSmall)
                Text(
                    "Tap + to create your first tag",
                    color = FolioTheme.colors.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(padding)
        ) {
            items(tags, key = { it.tag.id }) { tagCount ->
                TagListItem(
                    tagCount = tagCount,
                    onClick = { onTagClick(tagCount) },
                    onLongClick = { onTagLongClick(tagCount) },
                    onEditClick = { onEditClick(tagCount) },
                    onDeleteClick = { onDeleteClick(tagCount) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagListItem(
    tagCount: TagCount,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val tag = tagCount.tag
    val tagColor = tag.color?.let { Color(it) } ?: FolioTheme.colors.primary
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(tagColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            Color.White.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        },
        headlineContent = {
            Text(
                text = tag.name,
                style = FolioTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${tagCount.bookCount} book${if (tagCount.bookCount != 1) "s" else ""}",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                Text(
                    "${tagCount.highlightCount} highlight${if (tagCount.highlightCount != 1) "s" else ""}",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    )
}

@Composable
private fun TagDetailView(
    detail: TagDetailItem,
    padding: PaddingValues,
    onHighlightClick: (Highlight, Book) -> Unit,
    onBookClick: (Book) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(padding)
    ) {
        item {
            val tagColor = detail.tag.color?.let { Color(it) } ?: FolioTheme.colors.primary
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = tagColor.copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        detail.tag.name,
                        style = FolioTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = tagColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${detail.books.size} book${if (detail.books.size != 1) "s" else ""} \u00B7 ${detail.highlights.size} highlight${if (detail.highlights.size != 1) "s" else ""}",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }
        }

        if (detail.books.isNotEmpty()) {
            item {
                Text(
                    "Books",
                    style = FolioTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(detail.books, key = { it.id }) { book ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBookClick(book) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(60.dp)
                                .background(FolioTheme.colors.surfaceContainerHighest, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                book.title.take(2),
                                color = FolioTheme.colors.onSurfaceVariant,
                                style = FolioTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                book.title,
                                style = FolioTheme.typography.bodyLarge,
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
                        }
                    }
                }
            }
        }

        if (detail.highlights.isNotEmpty()) {
            item {
                Text(
                    "Highlights",
                    style = FolioTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(detail.highlights, key = { it.highlight.id }) { hw ->
                val hlColor = hw.highlight.effectiveColor.let { Color(it) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHighlightClick(hw.highlight, hw.book) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(hlColor, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "\u201C${hw.highlight.selectedText}\u201D",
                            style = FolioTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            hw.book.title,
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagEditDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    onConfirm: (String, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf<Int?>(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = FolioTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tag name") },
                    singleLine = true
                )

                Text(
                    "Color",
                    style = FolioTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ColorSwatch(
                            color = null,
                            selected = selectedColor == null,
                            onClick = { selectedColor = null }
                        )
                    }
                    items(TagPresetColors) { color ->
                        ColorSwatch(
                            color = color,
                            selected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedColor) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorPickerDialog(
    currentColor: Int?,
    onColorSelected: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(currentColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Color", style = FolioTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ColorSwatch(
                            color = null,
                            selected = selectedColor == null,
                            onClick = { selectedColor = null }
                        )
                    }
                    items(TagPresetColors) { color ->
                        ColorSwatch(
                            color = color,
                            selected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onColorSelected(selectedColor) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorSwatch(
    color: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                color = color?.let { Color(it) } ?: FolioTheme.colors.surfaceContainerHighest,
                shape = CircleShape
            )
            .then(
                if (selected) Modifier.background(
                    FolioTheme.colors.primaryContainer,
                    CircleShape
                ) else Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (color == null) {
            Text("\u00D8", style = FolioTheme.typography.labelLarge)
        } else if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = FolioTheme.colors.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DeleteTagDialog(
    tag: Tag,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete \u201C${tag.name}\u201D?", style = FolioTheme.typography.titleLarge)
        },
        text = {
            Text("This will remove the tag from all books and highlights. This cannot be undone.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
