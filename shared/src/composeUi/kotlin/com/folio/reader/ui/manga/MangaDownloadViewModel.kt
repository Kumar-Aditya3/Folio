package com.folio.reader.ui.manga

import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDownloadManager
import com.folio.reader.manga.MangaDownloadRepository
import com.folio.reader.manga.MangaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------- Downloads ----------

class DownloadsViewModel(
    private val downloadRepo: MangaDownloadRepository,
    private val mangaRepo: MangaRepository,
    private val chapterRepo: MangaChapterRepository,
    private val downloadManager: MangaDownloadManager,
) {
    val scope = mangaVmScope()

    val queue = downloadRepo.observeQueue()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val storageDescription = MutableStateFlow(downloadManager.storageDescription())

    fun refreshStorageDescription() {
        storageDescription.value = downloadManager.storageDescription()
    }

    val mangaTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Chapter names for queue rows, resolved as chapters enter the queue. */
    val chapterNames = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        scope.launch {
            mangaRepo.observeAll().collect { list ->
                mangaTitles.value = list.associate { it.id to it.title }
            }
        }
        scope.launch {
            queue.collect { q ->
                val known = chapterNames.value
                val missing = q.map { it.chapterId }.distinct().filter { it !in known }
                if (missing.isNotEmpty()) {
                    val resolved = missing.mapNotNull { id ->
                        chapterRepo.getChapter(id)?.let { id to it.name }
                    }
                    chapterNames.value = known + resolved
                }
            }
        }
    }

    fun cancel(downloadId: String) {
        scope.launch { downloadManager.cancel(downloadId) }
    }

    /** Re-queues a failed download: partial files are cleared and the chapter re-enqueues. */
    fun retry(downloadId: String) {
        scope.launch {
            val item = queue.value.firstOrNull { it.id == downloadId } ?: return@launch
            downloadManager.cancel(downloadId)
            val chapter = chapterRepo.getChapter(item.chapterId) ?: return@launch
            downloadManager.queueChapter(item.mangaId, chapter)
        }
    }

    fun clearFinished() {
        scope.launch { downloadRepo.clearFinished() }
    }
}
