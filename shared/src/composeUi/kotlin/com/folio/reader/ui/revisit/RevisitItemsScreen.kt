package com.folio.reader.ui.revisit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Highlight
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
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

// Each revisit type maps to one of the four palette-adaptive accent roles rather
// than a hardcoded hue. The accents are guaranteed legible on the surface, so the
// badge tint + icon read on any theme; the label is passed through
// rememberLegibleAccent so it stays readable on the low-alpha chip.
@Composable
private fun RevisitType.toBadge(): TypeBadge {
    val colors = FolioTheme.colors
    return when (this) {
        RevisitType.HIGHLIGHT -> TypeBadge(
            this, "Highlight", Icons.Filled.Highlight, colors.accentStreak
        )
        RevisitType.BOOKMARK -> TypeBadge(
            this, "Bookmark", Icons.Filled.Bookmark, colors.accentProgress
        )
        RevisitType.NOTE -> TypeBadge(
            this, "Note", Icons.AutoMirrored.Filled.Notes, colors.accentAnnotation
        )
        RevisitType.CHAPTER -> TypeBadge(
            this, "Chapter", Icons.AutoMirrored.Filled.MenuBook, colors.accentDiscovery
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisitItemsScreen(
    onBack: () -> Unit,
    onItemClick: (RevisitDisplayItem) -> Unit,
    viewModel: RevisitItemsViewModel
) {
    var filterType by remember { mutableStateOf<RevisitType?>(null) }
    // Build the flow once per filter, not per recomposition: collectAsState keys on the flow
    // instance, so an unremembered new instance would tear down and re-run the full per-item
    // resolution (getBook/getChapters/getHighlight/getBookmark/getNote…) on every recomposition.
    val itemsFlow = remember(filterType) { viewModel.unresolvedItems(filterType) }
    // null = the revisit store has not emitted yet (loading); an empty list is the real
    // "nothing to revisit". Distinguishing them is what stops the false "All caught up!".
    val items by itemsFlow.collectAsState(initial = null)

    Scaffold(
        topBar = {
            FolioTopBar(
                title = "Revisit",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                rail = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
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
            )
        }
    ) { padding ->
        val list = items
        when {
            // Still waiting on the first emission from the revisit store: show the shared
            // loading placeholder, not the false "All caught up!" empty state.
            list == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                LoadingPlaceholder()
            }
            list.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Filled.Check,
                    headline = "All caught up!",
                    body = "No items to revisit right now"
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(list, key = { it.revisitItem.id }) { item ->
                    RevisitCard(
                        item = item,
                        onItemClick = { onItemClick(item) },
                        onResolve = { viewModel.resolveItem(item.revisitItem.id) },
                        modifier = Modifier.animateItem()
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
    onResolve: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badge = item.revisitItem.type.toBadge()
    // The badge is the identity here, so it keeps its accent tint; the row itself
    // sits on the page with a hairline, like every other list in the app.
    Column(
        modifier = modifier.fillMaxWidth().clickable { onItemClick() }
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(badge.color.copy(alpha = 0.15f), com.folio.reader.ui.theme.FolioShapes.pill)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            badge.icon,
                            contentDescription = null,
                            tint = badge.color,
                            modifier = Modifier.width(15.dp).height(15.dp)
                        )
                        Text(
                            text = badge.label,
                            style = FolioTheme.typography.labelSmall,
                            color = rememberLegibleAccent(badge.color)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onResolve,
                    modifier = Modifier.minimumInteractiveComponentSize()
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .folioSunken(com.folio.reader.ui.theme.FolioShapes.inset)
                        .padding(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.Notes,
                            contentDescription = null,
                            tint = FolioTheme.colors.accentAnnotation,
                            modifier = Modifier.width(17.dp).height(17.dp)
                        )
                        Text(
                            text = item.note,
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
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
                    style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.onSurface,
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
        com.folio.reader.ui.components.FolioRule()
    }
}
