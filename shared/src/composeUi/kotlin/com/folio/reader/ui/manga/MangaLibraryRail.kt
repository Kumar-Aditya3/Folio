package com.folio.reader.ui.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaCategory
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The manga shelf's chrome: the Books/Manga/Documents switch, the category chips,
 * the search field, and the update notice.
 *
 * It renders in the Library masthead's rail slot, in the same slot the Books and
 * Documents chips use. It used to live inside [MangaLibraryScreen] as a row pinned
 * over the grid, which meant a mode switch changed the *structure* of the chrome:
 * the masthead reported no rail for Manga, so the incoming shelf was laid out
 * under the previous mode's rail height and jumped to the right one a frame later.
 * That one-frame re-layout is what read as the switch glitching. One rail for all
 * three modes makes the swap a dissolve and nothing else.
 *
 * The rail folds under the masthead on scroll like the other two, which is the
 * trade for that: Manga's chips no longer stay pinned while the grid moves.
 *
 * [leading] is the mode switch, handed in by the host so the rail leads with it
 * exactly as the Books rail does.
 */
@Composable
fun MangaLibraryRail(
    viewModel: MangaLibraryViewModel,
    searchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    sourcesAvailable: Boolean,
    onOpenDownloads: () -> Unit,
    leading: (@Composable () -> Unit)?,
) {
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategoryId.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val activeDownloads by viewModel.activeDownloadCount.collectAsState()
    val updatingLibrary by viewModel.updating.collectAsState()
    val newChapters by viewModel.lastNewChapters.collectAsState()
    val updatedSeriesCount by viewModel.recentlyUpdated.collectAsState()
    var manageCollectionsOpen by remember { mutableStateOf(false) }
    val railScope = rememberCoroutineScope()

    // The periodic library update is otherwise invisible; when it lands new
    // chapters, show the count for a few seconds, then fold the strip away again.
    var showNewChaptersNotice by remember { mutableStateOf(false) }
    LaunchedEffect(newChapters, updatedSeriesCount) {
        if (newChapters > 0) {
            showNewChaptersNotice = true
            delay(5000)
            showNewChaptersNotice = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (searchActive) {
            MangaSearchHeader(
                scope = searchScope,
                onScopeChange = { next ->
                    if (next != searchScope) viewModel.searchScope.value = next
                },
                query = query,
                onQueryChange = { viewModel.query.value = it },
                sourcesAvailable = sourcesAvailable,
                onClose = { onSearchActiveChange(false) },
                // The mode switch stays at the head of the row while searching: the
                // old header replaced the whole rail, which took the selector with it
                // and left search as the only way out.
                leading = leading,
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    item { leading() }
                }
                items(categories) { category ->
                    FolioChip(
                        selected = selectedCategory == category.id,
                        onClick = { viewModel.selectCategory(category.id) },
                        label = category.name,
                    )
                }
                item {
                    FolioChip(
                        selected = false,
                        onClick = { manageCollectionsOpen = true },
                        label = "Edit",
                    )
                }
                item {
                    // Queue entry point for the whole manga side (it replaced the
                    // overflow-menu item): a live count while anything is queued or
                    // downloading, plain otherwise so storage settings stay reachable.
                    FolioChip(
                        selected = false,
                        onClick = onOpenDownloads,
                        label = if (activeDownloads > 0) "Downloads · $activeDownloads" else "Downloads",
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (!searchActive) {
            AnimatedVisibility(
                visible = updatingLibrary || showNewChaptersNotice,
                enter = expandVertically() + fadeIn(tween(200)),
                exit = fadeOut(tween(300)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FolioTokens.space3)
                        .glassPanel(RoundedCornerShape(FolioTokens.radiusChip))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (updatingLibrary) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Checking for new chapters…",
                            style = FolioTheme.typography.labelMedium,
                            color = FolioTheme.colors.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            Icons.Filled.LibraryAddCheck,
                            contentDescription = null,
                            tint = FolioTheme.colors.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val chapterWord = if (newChapters == 1) "chapter" else "chapters"
                        Text(
                            if (updatedSeriesCount.size == 1) "$newChapters new $chapterWord"
                            else "$newChapters new $chapterWord in ${updatedSeriesCount.size} series",
                            style = FolioTheme.typography.labelMedium,
                            color = FolioTheme.colors.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (manageCollectionsOpen) {
        var newCategory by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manageCollectionsOpen = false },
            title = { Text("Categories") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newCategory,
                            onValueChange = { newCategory = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("New category") },
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                val name = newCategory.trim()
                                if (name.isNotBlank()) {
                                    railScope.launch { viewModel.createCategory(name) }
                                    newCategory = ""
                                }
                            },
                            enabled = newCategory.isNotBlank(),
                        ) { Text("Add") }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(categories) { category ->
                            CollectionRow(
                                category = category,
                                // Main holds the library together: it can only go once
                                // another category exists to take over.
                                canDelete = category.id != MangaCategory.MAIN_ID ||
                                    categories.size > 1,
                                onRename = { newName -> viewModel.renameCategory(category.id, newName) },
                                onDelete = { viewModel.deleteCategory(category.id) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { manageCollectionsOpen = false }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun MangaSearchHeader(
    scope: MangaSearchScope,
    onScopeChange: (MangaSearchScope) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    sourcesAvailable: Boolean,
    onClose: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    // Same adaptive contract as the Books/Documents rail (LibrarySearchRail):
    // wide window puts switch and field on one line; on phones the field takes
    // the full row with the switch folded to the row beneath. The scope
    // selector is a dropdown on the field's leading search icon — the old chip
    // row was the same second row that hid the books scopes on phones.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val scopeOptions = if (sourcesAvailable) listOf("In library", "All sources") else listOf("In library")
        val scopeIndex = if (scope == MangaSearchScope.SOURCES && sourcesAvailable) 1 else 0
        if (maxWidth >= 600.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(8.dp))
                    }
                    // One field for both scopes: the typed text survives scope
                    // switches; the pill matches the switch beside it.
                    com.folio.reader.ui.library.FolioSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = if (scope == MangaSearchScope.LIBRARY) "Search your library" else "Search all sources",
                        modifier = Modifier.weight(1f),
                        scopeOptions = scopeOptions,
                        scopeSelected = scopeIndex,
                        onScopeSelect = { index ->
                            onScopeChange(if (index == 1) MangaSearchScope.SOURCES else MangaSearchScope.LIBRARY)
                        },
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FolioTokens.space1),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FolioTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.folio.reader.ui.library.FolioSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = if (scope == MangaSearchScope.LIBRARY) "Search your library" else "Search all sources",
                        modifier = Modifier.weight(1f),
                        scopeOptions = scopeOptions,
                        scopeSelected = scopeIndex,
                        onScopeSelect = { index ->
                            onScopeChange(if (index == 1) MangaSearchScope.SOURCES else MangaSearchScope.LIBRARY)
                        },
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                    }
                }
                if (leading != null) {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = FolioTokens.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item(key = "switch") { leading() }
                    }
                }
            }
        }
    }
}
