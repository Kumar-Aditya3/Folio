package com.folio.reader.nav

import androidx.compose.runtime.Composable

/**
 * Provides the screen content composables for each navigation destination.
 * Implemented by the activity's nav model which wires real screens with their
 * view models and repositories (§3.2 FOLIO_IMPLEMENTATION_SPEC).
 */
interface FolioNavModel {
    val graph: com.folio.reader.AppGraph

    @Composable
    fun homeContent()

    @Composable
    fun libraryContent(
        onOpenReader: (String) -> Unit,
        onOpenBookDetail: (String) -> Unit,
        onOpenSearch: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenTags: () -> Unit,
        onOpenQuotes: () -> Unit,
        onOpenRevisit: () -> Unit,
        onOpenMangaBrowse: () -> Unit,
        onOpenMangaExtensions: () -> Unit,
        onOpenMangaHistory: () -> Unit,
        onOpenMangaDetail: (String) -> Unit,
        onOpenMangaDownloads: () -> Unit,
        onOpenMangaSource: (Long, String) -> Unit
    )

    @Composable
    fun statsContent(onOpenBookDetail: (String) -> Unit)

    @Composable
    fun moreContent(
        onOpenSettings: (String) -> Unit,
        onOpenTags: () -> Unit,
        onOpenQuotes: () -> Unit,
        onOpenRevisit: () -> Unit,
        onOpenExtensions: () -> Unit,
        onOpenDownloads: () -> Unit,
        onOpenHistory: () -> Unit
    )

    @Composable
    fun readerContent(
        bookId: String,
        targetSpineIndex: Int?,
        onBack: () -> Unit,
        onOpenSearch: () -> Unit,
        onOpenSettings: () -> Unit
    )

    @Composable
    fun bookDetailContent(
        bookId: String,
        onBack: () -> Unit,
        onStartReading: () -> Unit,
        onOpenTags: () -> Unit
    )

    @Composable
    fun searchContent(onBack: () -> Unit, onOpenReader: (String, Int?) -> Unit)

    @Composable
    fun settingsContent(category: String, onBack: () -> Unit)

    @Composable
    fun tagsContent(onBack: () -> Unit, onOpenBookDetail: (String) -> Unit, onOpenReader: (String) -> Unit)

    @Composable
    fun quotesContent(onBack: () -> Unit, onOpenReader: (String) -> Unit)

    @Composable
    fun revisitContent(onBack: () -> Unit, onOpenReader: (String) -> Unit)

    @Composable
    fun mangaDetailContent(mangaId: String, onBack: () -> Unit, onRead: (String) -> Unit)

    @Composable
    fun mangaReaderContent(mangaId: String, chapterId: String, onBack: () -> Unit, onNextChapter: (String) -> Unit)

    @Composable
    fun mangaSourceBrowseContent(sourceId: Long, query: String, onBack: () -> Unit, onOpenManga: (String) -> Unit)

    @Composable
    fun mangaBrowseContent(onBack: () -> Unit, onOpenSource: (Long, String) -> Unit, onOpenExtensions: () -> Unit, onOpenManga: (String) -> Unit)

    @Composable
    fun extensionsContent(onBack: () -> Unit)

    @Composable
    fun mangaDownloadsContent(onBack: () -> Unit)

    @Composable
    fun mangaHistoryContent(onBack: () -> Unit, onOpenManga: (String) -> Unit)
}

/**
 * Activity-level callbacks the nav layer cannot own (document pickers, dialogs,
 * sharing). Defaults are no-ops so routes stay shippable before wiring is done.
 */
interface FolioNavCallbacks {
    fun onImportEpubs() {}
    fun onImportMangaArchives() {}
    fun onImportMangaFolder() {}
    fun onImportMangaChoice() {}
    fun onImportMangaBackup() {}
    fun onExportMangaBackup() {}
    fun onImportFont() {}
    fun onExportBackup() {}
    fun onImportBackup() {}
    fun onExportAnnotations(format: String) {}
    fun onPickMangaDownloadsLocation() {}
    fun onShareEpub(bookId: String) {}
}
