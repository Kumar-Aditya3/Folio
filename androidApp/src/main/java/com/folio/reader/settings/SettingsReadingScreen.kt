package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.ReadingSettingsPanel

@Composable
fun SettingsReadingScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(title = "Reader behavior", onBack = onBack) {
        ReadingSettingsPanel(navModel.globalSettings) { navModel.updateSettings(it) }
    }
}
