package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.LayoutSettingsPanel

@Composable
fun SettingsLayoutScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(
        title = "Layout",
        onBack = onBack,
        livePreviewSettings = navModel.globalSettings
    ) {
        LayoutSettingsPanel(navModel.globalSettings) { navModel.updateSettings(it) }
    }
}
