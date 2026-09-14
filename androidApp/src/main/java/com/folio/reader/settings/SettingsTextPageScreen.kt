package com.folio.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.TextAndPageSettingsPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text & page: the Typography, Layout and Formatting screens merged (§14.2).
 * One live preview above the controls answers "what does this do?" for all
 * three sections at once, one override count covers everything per-book, and
 * the legacy formatting review (books snapshotted before those fields went
 * global) keeps its place at the bottom of the panel.
 */
@Composable
fun SettingsTextPageScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    var overrides by remember { mutableIntStateOf(0) }
    var legacyBooks by remember { mutableStateOf<List<com.folio.reader.model.Book>>(emptyList()) }
    var showReview by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = graph.settingsRepository.getGlobalSettings() }
        legacyBooks = legacyFormattingBooks(graph)
    }
    // Recounted after every edit, because a book only counts as overriding once
    // its snapshot differs from the current defaults. Delayed so dragging a
    // slider does not run one pass per frame.
    LaunchedEffect(reloadTick, navModel.globalSettings) {
        delay(300)
        overrides = withContext(Dispatchers.IO) {
            booksOverriding(graph, TYPE_FIELDS + MEASURE_FIELDS).size
        }
    }

    SettingsCategoryScaffold(
        title = "Text & page",
        onBack = onBack,
        livePreviewSettings = navModel.globalSettings
    ) {
        TextAndPageSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            overrideCount = overrides,
            onApplyToOpenedBooks = {
                navModel.activity.appScope.launch {
                    withContext(Dispatchers.IO) { clearBookOverrides(graph, TYPE_FIELDS + MEASURE_FIELDS) }
                    reloadTick++
                }
            },
            legacyFormattingCount = legacyBooks.size,
            onReviewLegacyFormatting = { showReview = true }
        )
    }

    if (showReview) {
        FormattingOverridesDialog(
            books = legacyBooks,
            onResetBook = { book ->
                navModel.activity.appScope.launch {
                    legacyBooks = withContext(Dispatchers.IO) {
                        val stored = runCatching { graph.settingsRepository.getBookSettings(book.id) }.getOrNull()
                        if (stored != null) {
                            runCatching {
                                graph.settingsRepository.saveBookSettings(
                                    book.id,
                                    stored.clearing(LEGACY_FORMATTING_FIELDS)
                                )
                            }
                        }
                        legacyFormattingBooks(graph)
                    }
                }
            },
            onDismiss = { showReview = false }
        )
    }
}

/**
 * The formatting-panel fields went global in 937069c; books already opened
 * before that still carry snapshotted values for them, which is what the
 * review list counts and clears.
 */
internal val LEGACY_FORMATTING_FIELDS = setOf("alignment", "formattingMode", "hyphenation")

internal suspend fun legacyFormattingBooks(graph: com.folio.reader.AppGraph): List<com.folio.reader.model.Book> {
    val books = runCatching { graph.bookRepository.getAllBooks().first() }
        .getOrDefault(emptyList())
    return books.filter { book ->
        val stored = runCatching { graph.settingsRepository.getBookSettings(book.id) }.getOrNull()
        stored != null &&
            (stored.alignment != null || stored.formattingMode != null || stored.hyphenation != null)
    }
}

@Composable
private fun FormattingOverridesDialog(
    books: List<com.folio.reader.model.Book>,
    onResetBook: (com.folio.reader.model.Book) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FolioTokens.radiusCard))
                .background(FolioTheme.colors.surface)
                .padding(FolioTokens.space3)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            Text("Per-book formatting overrides", style = FolioTheme.typography.titleMedium)
            Text(
                "These books kept their own formatting values from before these settings went " +
                    "global. Reset a book so it follows the defaults above.",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant
            )
            books.forEach { book ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        book.title,
                        style = FolioTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onResetBook(book) }) { Text("Reset") }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
