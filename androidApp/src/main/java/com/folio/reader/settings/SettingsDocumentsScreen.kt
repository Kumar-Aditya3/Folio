package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.DocumentDefaultsPanel

/**
 * The dedicated **document** settings destination — the typography, page and
 * page theme a PDF, Word or HTML document opens with, kept separate from the
 * book defaults on [SettingsReaderDefaultsScreen].
 *
 * Reachable from the More hub ("Documents") and from the document reader's own
 * in-reader settings panel ("All settings"), so the two ways a reader gets here
 * — browsing Settings, or mid-document — land on the same screen.
 */
@Composable
fun SettingsDocumentsScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    // Refresh the row on entry so the controls reflect the stored document
    // defaults rather than a snapshot taken at app start.
    LaunchedEffect(Unit) {
        runCatching {
            navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings()
        }
    }

    SettingsCategoryScaffold(title = "Documents", onBack = onBack) {
        DocumentDefaultsPanel(
            settings = navModel.globalSettings.documentSettings,
            pageMode = navModel.globalSettings.documentReaderMode,
            onSettingsChange = { updated ->
                navModel.updateSettings(navModel.globalSettings.copy(documentSettings = updated))
            },
            onPageModeChange = { mode ->
                navModel.updateSettings(navModel.globalSettings.withDocumentReaderMode(mode))
            },
            extraFonts = navModel.globalSettings.customFonts.map { it.name },
        )
    }
}
