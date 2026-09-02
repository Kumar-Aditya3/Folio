package com.folio.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.AdvancedSettingsPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Backup & data destination: backup/restore, annotation export, and where
 * downloaded manga chapters live.
 */
@Composable
fun SettingsAdvancedScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = graph.settingsRepository.getGlobalSettings() }
    }
    LaunchedEffect(Unit) {
        if (navModel.mangaDownloadsLocation.isEmpty()) {
            navModel.mangaDownloadsLocation = graph.mangaDownloadManager.storageDescription()
        }
    }
    SettingsCategoryScaffold(title = "Backup & data", onBack = onBack) {
        AdvancedSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            onImportFont = { navModel.callbacks.onImportFont() },
            onExportBackup = { navModel.callbacks.onExportBackup() },
            onImportBackup = { navModel.callbacks.onImportBackup() },
            onExportAnnotations = { format ->
                navModel.annotationFormat = format
                navModel.callbacks.onExportAnnotations(format)
            }
        )
        HorizontalDivider(color = FolioTheme.colors.outlineVariant)
        MangaDownloadsLocationRow(navModel)
    }
}

@Composable
private fun MangaDownloadsLocationRow(navModel: FolioNavModelImpl) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)
    ) {
        Text("Manga downloads", style = FolioTheme.typography.bodyLarge, color = FolioTheme.colors.onSurface)
        Text(
            "Where downloaded chapters are stored.",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = FolioTheme.colors.onSurfaceVariant
            )
            Spacer(Modifier.width(FolioTokens.space2))
            Text(
                navModel.mangaDownloadsLocation,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { navModel.callbacks.onPickMangaDownloadsLocation() }) {
                Text("Change", style = FolioTheme.typography.labelMedium)
            }
        }
    }
}
