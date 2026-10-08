package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.TransparencySettingsPanel

/**
 * Appearance destination: how solid the app's glass surfaces are. Four knobs
 * rather than one, because the top bar and nav sit over the app's own page while
 * reader chrome sits over a page being read.
 */
@Composable
fun SettingsTransparencyScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(title = "Transparency", onBack = onBack) {
        TransparencySettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) }
        )
    }
}
