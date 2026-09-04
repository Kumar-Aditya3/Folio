package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.TypographySettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Type for prose books. The live preview sits above the controls (see
 * [SettingsCategoryScaffold]) so a slider is visibly connected to something, and
 * the override count answers the other half of "what does this even do?": books
 * already opened keep their own type until they are told otherwise.
 */
@Composable
fun SettingsTypographyScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    var overrides by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = graph.settingsRepository.getGlobalSettings() }
    }
    // Recounted after every edit, because a book only counts as overriding once
    // its snapshot differs from the current defaults. Delayed so dragging a
    // slider does not run one pass per frame.
    LaunchedEffect(reloadTick, navModel.globalSettings) {
        delay(300)
        overrides = withContext(Dispatchers.IO) { booksOverriding(graph, TYPE_FIELDS).size }
    }

    SettingsCategoryScaffold(
        title = "Typography",
        onBack = onBack,
        livePreviewSettings = navModel.globalSettings
    ) {
        TypographySettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            overrideCount = overrides,
            onApplyToOpenedBooks = {
                navModel.activity.appScope.launch {
                    withContext(Dispatchers.IO) { clearBookOverrides(graph, TYPE_FIELDS) }
                    reloadTick++
                }
            }
        )
    }
}
