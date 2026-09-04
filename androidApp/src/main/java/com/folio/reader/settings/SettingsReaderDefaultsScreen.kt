package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.manga.KEY_MANGA_READER_DEFAULT_MODE
import com.folio.reader.ui.manga.MangaReaderMode
import com.folio.reader.ui.settings.ReaderDefaultsPanel

@Composable
fun SettingsReaderDefaultsScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching {
            navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings()
            navModel.mangaDefaultMode = MangaReaderMode.entries.firstOrNull {
                it.name == navModel.graph.settingsRepository.getRaw(KEY_MANGA_READER_DEFAULT_MODE)
            } ?: MangaReaderMode.WEBTOON
        }
    }
    SettingsCategoryScaffold(title = "Reader defaults", onBack = onBack) {
        ReaderDefaultsPanel(
            settings = navModel.globalSettings,
            mangaDefaultMode = navModel.mangaDefaultMode,
            onSettingsChange = navModel::updateSettings,
            onMangaDefaultModeChange = navModel::updateMangaDefaultMode,
        )
    }
}
