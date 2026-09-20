package com.folio.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
private enum class ExcludeDialog { BOOKS, TAGS_COLLECTIONS, SERIES, STATUSES, MANGA_CATEGORIES, SOURCES, EXTENSIONS }

/**
 * §11.2 statistics exclusions (settings/stats): seven multi-selects over one
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
    var extensions by remember { mutableStateOf(emptyList<com.folio.reader.manga.ExtensionEntry>()) }

    LaunchedEffect(Unit) {
        books = graph.bookRepository.getAllBooks().first()
        tags = runCatching { graph.tagRepository.getAllTags().first() }.getOrDefault(emptyList())
        collections = graph.collectionRepository.getAllCollections().first()
        series = graph.seriesRepository.getAllSeries().first()
        manga = graph.mangaRepository.observeLibrary().first()
        categories = graph.mangaCategoryRepository.observeCategories().first()
        // Installed extensions only: an uninstalled one cannot feed Discover
        // and has no manga left to exclude from the statistics.
        extensions = runCatching { graph.mangaBackend.observeExtensions().first() }
            .getOrDefault(emptyList())
            .filter { it.isInstalled }
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
    val extensionExcluded = exclusions.count { it.first == Scope.EXTENSION }
    val sources = manga.groupBy { it.sourceId }
        .map { (sourceId, entries) ->
            ExcludeOption(Scope.MANGA_SOURCE, sourceId.toString(), entries.first().sourceName)
        }
    val extensionOptions = extensions.map {
        ExcludeOption(Scope.EXTENSION, it.pkgName, it.lang?.let { lang -> "${it.name} ($lang)" } ?: it.name)
    }

    SettingsCategoryScaffold(title = "Exclusions", onBack = onBack) {
        // One editorial lead instead of a trailing footnote: it sets up what every row below
        // does before the reader taps one, rather than explaining after the fact.
        Text(
            "Excluded titles leave your statistics and every Home suggestion — but stay in your " +
                "library, search and the reader, and keep recording progress. Excluding a manga, " +
                "source or extension also keeps it out of Discover.",
            style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurfaceVariant,
        )

        ExclusionSection("Books", FolioTheme.colors.accentProgress) {
            ExclusionRow("Books", "$bookExcluded of ${books.size}") { dialog = ExcludeDialog.BOOKS }
            ExclusionRow("Tags & collections", "$tagExcluded tags · $collectionExcluded collections") {
                dialog = ExcludeDialog.TAGS_COLLECTIONS
            }
            ExclusionRow("Series", "$seriesExcluded of ${series.size}") { dialog = ExcludeDialog.SERIES }
            ExclusionRow("Statuses", "$statusExcluded of ${BookStatus.entries.size}", last = true) {
                dialog = ExcludeDialog.STATUSES
            }
        }

        ExclusionSection("Manga", FolioTheme.colors.accentDiscovery) {
            ExclusionRow(
                "Manga & categories",
                "$mangaExcluded of ${manga.size} · $categoryExcluded categories"
            ) { dialog = ExcludeDialog.MANGA_CATEGORIES }
            ExclusionRow("Sources", "$sourceExcluded of ${sources.size}") { dialog = ExcludeDialog.SOURCES }
            ExclusionRow("Extensions", "$extensionExcluded of ${extensionOptions.size}", last = true) {
                dialog = ExcludeDialog.EXTENSIONS
            }
        }
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
            ExcludeDialog.EXTENSIONS -> extensionOptions
        }
        ExclusionDialog(
            title = when (kind) {
                ExcludeDialog.BOOKS -> "Excluded books"
                ExcludeDialog.TAGS_COLLECTIONS -> "Excluded tags & collections"
                ExcludeDialog.SERIES -> "Excluded series"
                ExcludeDialog.STATUSES -> "Excluded statuses"
                ExcludeDialog.MANGA_CATEGORIES -> "Excluded manga & categories"
                ExcludeDialog.SOURCES -> "Excluded sources"
                ExcludeDialog.EXTENSIONS -> "Excluded extensions"
            },
            options = options,
            selected = exclusions,
            onToggle = { option -> toggle(option.scope, option.id) },
            onDismiss = { dialog = null }
        )
    }
}

/** An accent-eyebrowed group of exclusion rows, in the app's editorial section style. */
@Composable
private fun ExclusionSection(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Column {
        com.folio.reader.ui.components.FolioEyebrow(title, accent = accent)
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        content()
    }
}

/**
 * One exclusion row: a name, its live count, a chevron, and a hairline rule beneath it — the
 * same anatomy the More hub rows use, rather than the bare space-between line this screen had.
 */
@Composable
private fun ExclusionRow(title: String, count: String, last: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = FolioTheme.typography.titleSmall,
            color = FolioTheme.colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            count,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = FolioTheme.colors.onSurfaceVariant,
        )
    }
    if (!last) com.folio.reader.ui.components.FolioRule()
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
