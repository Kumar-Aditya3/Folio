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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.folio.reader.model.Book
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.FormattingSettingsPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The formatting-panel fields went global in 937069c; books already opened
 * before that still carry snapshotted values for them, which is what the
 * review list counts and clears.
 */
private val LEGACY_FORMATTING_FIELDS = setOf("alignment", "formattingMode", "hyphenation")

@Composable
fun SettingsFormattingScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    var legacyBooks by remember { mutableStateOf<List<Book>>(emptyList()) }
    var showReview by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = graph.settingsRepository.getGlobalSettings() }
        legacyBooks = legacyFormattingBooks(navModel)
    }

    SettingsCategoryScaffold(
        title = "Formatting",
        onBack = onBack,
        livePreviewSettings = navModel.globalSettings
    ) {
        FormattingSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            overrideCount = legacyBooks.size,
            onReviewOverrides = { showReview = true }
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
                        legacyFormattingBooks(navModel)
                    }
                }
            },
            onDismiss = { showReview = false }
        )
    }
}

private suspend fun legacyFormattingBooks(navModel: FolioNavModelImpl): List<Book> {
    val books = runCatching { navModel.graph.bookRepository.getAllBooks().first() }
        .getOrDefault(emptyList())
    return books.filter { book ->
        val stored = runCatching { navModel.graph.settingsRepository.getBookSettings(book.id) }.getOrNull()
        stored != null &&
            (stored.alignment != null || stored.formattingMode != null || stored.hyphenation != null)
    }
}

@Composable
private fun FormattingOverridesDialog(
    books: List<Book>,
    onResetBook: (Book) -> Unit,
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
