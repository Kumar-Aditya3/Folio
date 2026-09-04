package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.manga.KEY_MANGA_READER_DEFAULT_MODE
import com.folio.reader.ui.manga.MangaReaderMode
import com.folio.reader.ui.settings.ReaderDefaultsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the per-item inheritance rule is stated and, when something is not
 * following it, undone. The counts are recomputed after every edit and after
 * every reset, so the screen never claims a book is out of step once it isn't.
 */
@Composable
fun SettingsReaderDefaultsScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    var bookOverrides by remember { mutableIntStateOf(0) }
    var mangaOverrides by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching {
            navModel.globalSettings = graph.settingsRepository.getGlobalSettings()
            navModel.mangaDefaultMode = MangaReaderMode.entries.firstOrNull {
                it.name == graph.settingsRepository.getRaw(KEY_MANGA_READER_DEFAULT_MODE)
            } ?: MangaReaderMode.WEBTOON
        }
    }
    LaunchedEffect(reloadTick, navModel.globalSettings, navModel.mangaDefaultMode) {
        delay(300)
        val books = withContext(Dispatchers.IO) {
            booksOverriding(graph, ALL_INHERITED_FIELDS).size
        }
        val manga = withContext(Dispatchers.IO) { mangaOverridingMode(graph).size }
        bookOverrides = books
        mangaOverrides = manga
    }

    SettingsCategoryScaffold(title = "Reader defaults", onBack = onBack) {
        ReaderDefaultsPanel(
            settings = navModel.globalSettings,
            mangaDefaultMode = navModel.mangaDefaultMode,
            onSettingsChange = navModel::updateSettings,
            onMangaDefaultModeChange = navModel::updateMangaDefaultMode,
            bookOverrideCount = bookOverrides,
            onApplyToOpenedBooks = {
                navModel.activity.appScope.launch {
                    withContext(Dispatchers.IO) { clearBookOverrides(graph, ALL_INHERITED_FIELDS) }
                    reloadTick++
                }
            },
            mangaOverrideCount = mangaOverrides,
            onApplyToOpenedManga = {
                navModel.activity.appScope.launch {
                    withContext(Dispatchers.IO) { clearMangaModes(graph) }
                    reloadTick++
                }
            },
        )
    }
}
