package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.CustomThemeSettingsPanel

/**
 * Appearance destination: author the app's palette. Six colours are edited and
 * the remaining roles — including the page gradient and its three accent pools —
 * are derived from them.
 */
@Composable
fun SettingsCustomThemeScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    SettingsCategoryScaffold(title = "Custom theme", onBack = onBack) {
        CustomThemeSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) }
        )
    }
}
