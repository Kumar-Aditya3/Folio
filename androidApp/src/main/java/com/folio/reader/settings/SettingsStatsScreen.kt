package com.folio.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.BookStatus
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One selectable row in an exclusion multi-select. */
private data class ExcludeOption(val scope: Scope, val id: String, val label: String)

/** Which multi-select is open; null when none. */
private enum class ExcludeDialog { BOOKS, TAGS_COLLECTIONS, SERIES, STATUSES, MANGA_CATEGORIES, SOURCES }

/**
 * §11.2 statistics exclusions (settings/stats): six multi-selects over one
 * `stats_exclusions` table. Resolution is one-way — an entity is excluded when
 * listed directly or through any group it belongs to; there is no per-entity
 * include-override (the user removes the group exclusion instead).
 */
@Composable
fun SettingsStatsScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    val repo = remember { com.folio.reader.database.JdbcStatsExclusionRepository(graph.database) }
    val exclusions by remember { repo.observeExclusions() }.collectAsState(initial = emptySet())

    var books by remember { mutableStateOf(emptyList<com.folio.reader.model.Book>()) }
    var tags by remember { mutableStateOf(emptyList<com.folio.reader.model.Tag>()) }
    var collections by remember { mutableStateOf(emptyList<com.folio.reader.model.Collection>()) }
    var series by remember { mutableStateOf(emptyList<com.folio.reader.model.Series>()) }
    var manga by remember { mutableStateOf(emptyList<com.folio.reader.manga.MangaEntry>()) }
    var categories by remember { mutableStateOf(emptyList<com.folio.reader.manga.MangaCategory>()) }

    LaunchedEffect(Unit) {
        books = graph.bookRepository.getAllBooks().first()
        tags = runCatching { graph.tagRepository.getAllTags().first() }.getOrDefault(emptyList())
        collections = graph.collectionRepository.getAllCollections().first()
        series = graph.seriesRepository.getAllSeries().first()
        manga = graph.mangaRepository.observeLibrary().first()
        categories = graph.mangaCategoryRepository.observeCategories().first()
    }

    var dialog by remember { mutableStateOf<ExcludeDialog?>(null) }
    fun toggle(scope: Scope, id: String) {
        val active = (scope to id) in exclusions
        navModel.activity.appScope.launch {
            runCatching { if (active) repo.remove(scope, id) else repo.add(scope, id) }
        }
    }

    val bookExcluded = exclusions.count { it.first == Scope.BOOK }
    val tagExcluded = exclusions.count { it.first == Scope.BOOK_TAG }
    val collectionExcluded = exclusions.count { it.first == Scope.BOOK_COLLECTION }
    val seriesExcluded = exclusions.count { it.first == Scope.BOOK_SERIES }
    val statusExcluded = exclusions.count { it.first == Scope.BOOK_STATUS }
    val mangaExcluded = exclusions.count { it.first == Scope.MANGA }
    val categoryExcluded = exclusions.count { it.first == Scope.MANGA_CATEGORY }
    val sourceExcluded = exclusions.count { it.first == Scope.MANGA_SOURCE }
    val sources = manga.groupBy { it.sourceId }
        .map { (sourceId, entries) ->
            ExcludeOption(Scope.MANGA_SOURCE, sourceId.toString(), entries.first().sourceName)
        }

    SettingsCategoryScaffold(title = "Statistics exclusions", onBack = onBack) {
        SettingsStatRow("Excluded books", "${bookExcluded} of ${books.size} books") { dialog = ExcludeDialog.BOOKS }
        SettingsStatRow(
            "Excluded book tags & collections",
            "$tagExcluded tags · $collectionExcluded collections"
        ) { dialog = ExcludeDialog.TAGS_COLLECTIONS }
        SettingsStatRow("Excluded book series", "$seriesExcluded of ${series.size} series") { dialog = ExcludeDialog.SERIES }
        SettingsStatRow("Excluded book statuses", "$statusExcluded of ${BookStatus.entries.size} statuses") {
            dialog = ExcludeDialog.STATUSES
        }
        SettingsStatRow(
            "Excluded manga & manga categories",
            "$mangaExcluded of ${manga.size} manga · $categoryExcluded categories"
        ) { dialog = ExcludeDialog.MANGA_CATEGORIES }
        SettingsStatRow("Excluded manga sources", "$sourceExcluded of ${sources.size} sources") { dialog = ExcludeDialog.SOURCES }
        Text(
            "Excluded titles leave the statistics and every Home suggestion — but stay in " +
                "your library, search and the reader, and keep recording progress.",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant
        )
    }

    dialog?.let { kind ->
        val options = when (kind) {
            ExcludeDialog.BOOKS -> books.map { ExcludeOption(Scope.BOOK, it.id, it.displayTitle) }
            ExcludeDialog.TAGS_COLLECTIONS ->
                tags.map { ExcludeOption(Scope.BOOK_TAG, it.id, it.name) } +
                    collections.map { ExcludeOption(Scope.BOOK_COLLECTION, it.id, it.name) }
            ExcludeDialog.SERIES -> series.map { ExcludeOption(Scope.BOOK_SERIES, it.id, it.name) }
            ExcludeDialog.STATUSES ->
                BookStatus.entries.map { ExcludeOption(Scope.BOOK_STATUS, it.name, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
            ExcludeDialog.MANGA_CATEGORIES ->
                manga.map { ExcludeOption(Scope.MANGA, it.id, it.title) } +
                    categories.map { ExcludeOption(Scope.MANGA_CATEGORY, it.id, it.name) }
            ExcludeDialog.SOURCES -> sources
        }
        ExclusionDialog(
            title = when (kind) {
                ExcludeDialog.BOOKS -> "Excluded books"
                ExcludeDialog.TAGS_COLLECTIONS -> "Excluded tags & collections"
                ExcludeDialog.SERIES -> "Excluded series"
                ExcludeDialog.STATUSES -> "Excluded statuses"
                ExcludeDialog.MANGA_CATEGORIES -> "Excluded manga & categories"
                ExcludeDialog.SOURCES -> "Excluded sources"
            },
            options = options,
            selected = exclusions,
            onToggle = { option -> toggle(option.scope, option.id) },
            onDismiss = { dialog = null }
        )
    }
}

/** Live count row — "12 of 148 books excluded" — opens the multi-select. */
@Composable
private fun SettingsStatRow(title: String, count: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = FolioTheme.typography.bodyLarge, color = FolioTheme.colors.onSurface)
        Text(
            count,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExclusionDialog(
    title: String,
    options: List<ExcludeOption>,
    selected: Set<Pair<Scope, String>>,
    onToggle: (ExcludeOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(options, key = { it.scope.name + it.id }) { option ->
                    val checked = (option.scope to option.id) in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(option) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(option) })
                        Text(
                            option.label,
                            style = FolioTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    )
}
