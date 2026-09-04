package com.folio.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.settings.SettingsLivePreview
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/** Route values for `settings/{category}` (§3.5 FOLIO_IMPLEMENTATION_SPEC). */
object FolioSettingsCategory {
    const val THEMES = "themes"
    const val DEFAULTS = "defaults"
    const val TYPOGRAPHY = "typography"
    const val LAYOUT = "layout"
    const val FORMATTING = "formatting"
    const val READING = "reading"
    const val CLOUD_SYNC = "cloud_sync"
    const val ADVANCED = "advanced"
    const val STATS = "stats"
    const val MANGA = "manga"
}

/**
 * Shared shell for a pushed settings category screen: masthead, then the panel
 * directly on the page.
 *
 * The panel used to sit inside a `FolioSectionCard`, which boxed content that was
 * already the entire screen — a container around the only thing present adds no
 * information and costs 32dp of measure. Controls now sit on the field, and any
 * live preview keeps its own surface because a preview genuinely *is* a separate
 * object.
 *
 * The preview leads. Beneath the controls it was below the fold on a phone: the
 * Typography screen has seven of them, so every slider moved something the reader
 * could not see, and the whole category looked inert. Above them, one drag proves
 * what the screen is for.
 */
@Composable
fun SettingsCategoryScaffold(
    title: String,
    onBack: () -> Unit,
    livePreviewSettings: ReaderSettings? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        FolioTopBar(
            title = title,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FolioTokens.gutter,
                end = FolioTokens.gutter,
                top = FolioTokens.space2,
                bottom = FolioTokens.spaceMovement,
            ),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceMovement)
        ) {
            if (livePreviewSettings != null) {
                item { SettingsLivePreview(livePreviewSettings) }
            }
            item { content() }
        }
    }
}
