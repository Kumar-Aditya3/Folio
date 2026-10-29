package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.TransparencySettingsPanel

/**
 * Appearance destination for app chrome, reader controls and home-screen widgets.
 */
@Composable
fun SettingsTransparencyScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(title = "Transparency", onBack = onBack) {
        TransparencySettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            showWidgetControls = true,
        )
    }
}
