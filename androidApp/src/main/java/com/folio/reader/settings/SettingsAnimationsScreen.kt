package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.AnimationsSettingsPanel

/**
 * Animations destination: one preset, seven levers, the reduce-motion mode, and which animation
 * the living room wears. Everything that moves in the app is decided here, which is why it is its
 * own destination rather than a section inside Transparency.
 */
@Composable
fun SettingsAnimationsScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(title = "Animations", onBack = onBack, materialPreviewSettings = navModel.globalSettings) {
        AnimationsSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) }
        )
    }
}
