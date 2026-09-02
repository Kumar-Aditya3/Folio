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
 * Shared shell for a pushed settings category screen: status band + top bar,
 * one section card with the panel, optional live preview below.
 */
@Composable
fun SettingsCategoryScaffold(
    title: String,
    onBack: () -> Unit,
    livePreviewSettings: ReaderSettings? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FolioTheme.colors.background)
    ) {
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
            contentPadding = PaddingValues(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            item {
                FolioSectionCard { content() }
            }
            if (livePreviewSettings != null) {
                item { SettingsLivePreview(livePreviewSettings) }
            }
        }
    }
}
