package com.folio.reader.ui.revisit

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.RevisitItem
import com.folio.reader.model.RevisitType
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class RevisitDisplayItem(
    val revisitItem: RevisitItem,
    val book: Book?,
    val manga: MangaEntry? = null,
    val chapter: Chapter?,
    val sourceSnippet: String,
    val note: String?
)

class RevisitItemsViewModel(
    private val getUnresolvedRevisitItems: () -> kotlinx.coroutines.flow.Flow<List<RevisitItem>>,
    private val resolveRevisitItem: suspend (String) -> Unit,
    private val getBook: suspend (String) -> Book?,
    private val getChaptersForBook: suspend (String) -> List<Chapter>,
    private val getHighlight: suspend (String) -> Highlight?,
    private val getBookmark: suspend (String) -> Bookmark?,
    private val getNote: suspend (String) -> Note?,
    // Manga side (§11.5): revisit rows whose bookId is a mangaId resolve through these when non-null.
    private val getManga: (suspend (String) -> MangaEntry?)? = null,
    private val getMangaChapters: (suspend (String) -> List<MangaChapter>)? = null,
    private val getMangaNote: (suspend (String) -> MangaNote?)? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun unresolvedItems(filterType: RevisitType? = null): Flow<List<RevisitDisplayItem>> {
        return getUnresolvedRevisitItems()
            .map { items -> if (filterType != null) items.filter { it.type == filterType } else items }
            .map { items ->
                items.mapNotNull { item ->
                    val book = getBook(item.bookId)
                    val manga = if (book == null) getManga?.invoke(item.bookId) else null
                    if (book == null && manga == null) return@mapNotNull null
                    val chapters = if (book != null) getChaptersForBook(item.bookId) else emptyList()
                    val chapter = chapters.find { it.id == item.chapterId }
                    val result = when (item.type) {
                        RevisitType.HIGHLIGHT -> {
                            val hl = getHighlight(item.sourceId)
                            Pair(hl?.selectedText.orEmpty(), hl?.noteId?.let { getNote(it)?.content } ?: item.note)
                        }
                        RevisitType.BOOKMARK -> {
                            if (book != null) {
                                val bm = getBookmark(item.sourceId)
                                Pair(bm?.label ?: chapter?.title ?: "Bookmark", item.note)
                            } else {
                                val mc = getMangaChapters?.invoke(item.bookId).orEmpty().find { it.id == item.sourceId }
                                Pair(mc?.name ?: "Bookmark", item.note)
                            }
                        }
                        RevisitType.NOTE -> {
                            if (book != null) {
                                val n = getNote(item.sourceId)
                                Pair(n?.content.orEmpty(), item.note)
                            } else {
                                Pair(getMangaNote?.invoke(item.sourceId)?.content.orEmpty(), item.note)
                            }
                        }
                        RevisitType.CHAPTER -> {
                            Pair(chapter?.title ?: "Chapter", item.note)
                        }
                    }
                    val snippet = result.first
                    val note = result.second
                    RevisitDisplayItem(item, book, manga, chapter, snippet, note)
                }
            }
            .flowOn(Dispatchers.IO)
    }

    fun resolveItem(itemId: String) {
        scope.launch {
            resolveRevisitItem(itemId)
        }
    }
}

private data class TypeBadge(
    val type: RevisitType,
    val label: String,
    val icon: ImageVector,
    val color: Color
)

private fun RevisitType.toBadge(): TypeBadge = when (this) {
    RevisitType.HIGHLIGHT -> TypeBadge(
        this, "Highlight", Icons.Filled.Highlight, Color(0xFFFFC107)
    )
    RevisitType.BOOKMARK -> TypeBadge(
        this, "Bookmark", Icons.Filled.Bookmark, Color(0xFF2196F3)
    )
    RevisitType.NOTE -> TypeBadge(
        this, "Note", Icons.Filled.Notes, Color(0xFF4CAF50)
    )
    RevisitType.CHAPTER -> TypeBadge(
        this, "Chapter", Icons.Filled.MenuBook, Color(0xFF9C27B0)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisitItemsScreen(
    onBack: () -> Unit,
    onItemClick: (RevisitDisplayItem) -> Unit,
    viewModel: RevisitItemsViewModel
) {
    var filterType by remember { mutableStateOf<RevisitType?>(null) }
    val items by viewModel.unresolvedItems(filterType).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                com.folio.reader.ui.components.FolioStatusBarBand()
                TopAppBar(
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    title = {
                        Column {
                            Text("Revisit", fontWeight = FontWeight.Bold)
                            if (items.isNotEmpty()) {
                                Text(
                                    "${items.size} item${if (items.size > 1) "s" else ""}",
                                    style = FolioTheme.typography.labelSmall,
                                    color = FolioTheme.colors.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = FolioTheme.colors.surface,
                        titleContentColor = FolioTheme.colors.onSurface
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RevisitType.values().forEach { type ->
                        val badge = type.toBadge()
                        val selected = filterType == type
                        FilterChip(
                            selected = selected,
                            onClick = { filterType = if (selected) null else type },
                            label = { Text(badge.label) },
                            leadingIcon = {
                                Icon(
                                    badge.icon,
                                    contentDescription = null,
                                    tint = badge.color,
                                    modifier = Modifier.width(18.dp).height(18.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.width(64.dp).height(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("All caught up!", style = FolioTheme.typography.headlineSmall)
                    Text(
                        "No items to revisit right now",
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(items, key = { it.revisitItem.id }) { item ->
                    RevisitCard(
                        item = item,
                        onItemClick = { onItemClick(item) },
                        onResolve = { viewModel.resolveItem(item.revisitItem.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RevisitCard(
    item: RevisitDisplayItem,
    onItemClick: () -> Unit,
    onResolve: () -> Unit
) {
    val badge = item.revisitItem.type.toBadge()
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onItemClick() },
        colors = CardDefaults.cardColors(
            containerColor = FolioTheme.colors.surface,
            contentColor = FolioTheme.colors.onSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = badge.color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            badge.icon,
                            contentDescription = null,
                            tint = badge.color,
                            modifier = Modifier.width(16.dp).height(16.dp)
                        )
                        Text(
                            text = badge.label,
                            style = FolioTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = badge.color
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onResolve,
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp).height(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resolve", style = FolioTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (item.sourceSnippet.isNotBlank()) {
                Text(
                    text = item.sourceSnippet,
                    style = FolioTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (item.note != null && item.note.isNotBlank()) {
                Surface(
                    color = FolioTheme.colors.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Notes,
                            contentDescription = null,
                            tint = FolioTheme.colors.onSecondaryContainer,
                            modifier = Modifier.width(18.dp).height(18.dp)
                        )
                        Text(
                            text = item.note,
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSecondaryContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.book?.title ?: item.manga?.title ?: "",
                    style = FolioTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                item.chapter?.let { chapter ->
                    Text(
                        text = " \u00B7 ${chapter.title}",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
