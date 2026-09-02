package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.GeneralSettingsPanel

/**
 * Appearance destination: theme packs, typeface pairing, publisher fonts and
 * custom font import. The manga download location lives on the Backup & data
 * screen instead.
 */
@Composable
fun SettingsThemesScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(title = "Themes", onBack = onBack) {
        GeneralSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            onImportFont = { navModel.callbacks.onImportFont() },
            mangaDownloadsLocation = null
        )
    }
}
