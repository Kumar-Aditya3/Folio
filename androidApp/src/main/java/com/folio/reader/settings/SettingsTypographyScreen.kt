package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.TypographySettingsPanel

@Composable
fun SettingsTypographyScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(
        title = "Typography",
        onBack = onBack,
        livePreviewSettings = navModel.globalSettings
    ) {
        TypographySettingsPanel(navModel.globalSettings) { navModel.updateSettings(it) }
    }
}
