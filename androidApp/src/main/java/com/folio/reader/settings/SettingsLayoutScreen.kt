package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.LayoutSettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The measure of a prose page: column width and margins, with the same
 * per-book inheritance rule the Typography screen carries. */
@Composable
fun SettingsLayoutScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    var overrides by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = graph.settingsRepository.getGlobalSettings() }
    }
    LaunchedEffect(reloadTick, navModel.globalSettings) {
        delay(300)
        overrides = withContext(Dispatchers.IO) { booksOverriding(graph, MEASURE_FIELDS).size }
    }

    SettingsCategoryScaffold(
        title = "Layout",
        onBack = onBack,
        livePreviewSettings = navModel.globalSettings
    ) {
        LayoutSettingsPanel(
            settings = navModel.globalSettings,
            onSettingsChange = { navModel.updateSettings(it) },
            overrideCount = overrides,
            onApplyToOpenedBooks = {
                navModel.activity.appScope.launch {
                    withContext(Dispatchers.IO) { clearBookOverrides(graph, MEASURE_FIELDS) }
                    reloadTick++
                }
            }
        )
    }
}
