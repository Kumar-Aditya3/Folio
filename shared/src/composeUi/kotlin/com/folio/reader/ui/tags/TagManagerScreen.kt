package com.folio.reader.ui.tags

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Highlight
import com.folio.reader.model.Tag
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val deleteTagById: suspend (String) -> Unit,
    private val getTagsForBook: suspend (String) -> List<Tag>,
    private val getTagsForHighlight: suspend (String) -> List<Tag>,
    private val getBooksForTag: suspend (tagId: String) -> List<Book>,
    private val getHighlightsForTag: suspend (tagId: String) -> List<Highlight>,
    private val getBook: suspend (String) -> Book?
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    private val _tags = MutableStateFlow<List<TagCount>>(emptyList())

    init {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _tags.value = loadTags()
        }
    }

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
            _tags.value = loadTags()
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
            // Calls the repo lambda; a bare deleteTag(tagId) would recurse into this function.
            deleteTagById(tagId)
            refresh()
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerScreen(
    onBack: () -> Unit,
    onHighlightClick: (Highlight, Book) -> Unit,
    onBookClick: (Book) -> Unit,
    viewModel: TagManagerViewModel
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                viewModel.createTag(name, color) { notify("Tag \"${name.trim()}\" created") }
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
                viewModel.renameTag(tag, name) { notify("Tag renamed") }
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
                viewModel.deleteTag(tag.id) { notify("Tag \"${tag.name}\" deleted") }
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
                    modifier = Modifier.animateItem(),
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val tag = tagCount.tag
    val tagColor = tag.color?.let { Color(it) } ?: FolioTheme.colors.primary
    ListItem(
        modifier = modifier
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
