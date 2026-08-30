package com.folio.reader.manga

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sequential chapter download worker (Mihon-style download queue). Pages are stored on
 * disk under downloadsDir/&lt;mangaId&gt;/&lt;chapterId&gt;/ and served back to the reader by
 * [resolvePage] before falling through to the network backend.
 */
class MangaDownloadManager(
    private val backend: MangaBackend,
    private val downloadsRepo: MangaDownloadRepository,
    private val chapterRepo: MangaChapterRepository,
    private val downloadsDir: File,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            while (isActive) {
                val next = downloadsRepo.observeQueue().first()
                    .firstOrNull { it.status == MangaDownloadStatus.QUEUED }
                if (next == null) {
                    delay(1500)
                } else {
                    process(next)
                }
            }
        }
    }

    suspend fun queueChapter(mangaId: String, chapter: MangaChapter) {
        if (downloadsRepo.isChapterDownloaded(chapter.id)) return
        downloadsRepo.enqueue(
            MangaDownload(
                id = chapter.id,
                mangaId = mangaId,
                chapterId = chapter.id,
            )
        )
    }

    suspend fun cancel(downloadId: String) {
        val item = downloadsRepo.observeQueue().first().firstOrNull { it.id == downloadId } ?: return
        downloadsRepo.remove(downloadId)
        chapterDir(item.mangaId, item.chapterId)?.deleteRecursively()
    }

    private suspend fun process(item: MangaDownload) {
        val chapter = chapterRepo.getChapter(item.chapterId)
        if (chapter == null) {
            downloadsRepo.remove(item.id)
            return
        }
        val ref = MangaChapterRef(url = chapter.url, name = chapter.name, chapterNumber = chapter.chapterNumber)
        downloadsRepo.update(item.copy(status = MangaDownloadStatus.DOWNLOADING))

        try {
            val pages = backend.fetchPageList(item.mangaId.substringBefore(":").toLong(), ref)
            val dir = chapterDir(item.mangaId, item.chapterId)!!.apply { mkdirs() }
            var done = 0
            downloadsRepo.update(item.copy(status = MangaDownloadStatus.DOWNLOADING, totalPages = pages.size))

            for (page in pages) {
                val image = backend.fetchPageImage(item.mangaId.substringBefore(":").toLong(), ref, page)
                val ext = extensionFor(image.bytes)
                File(dir, "%03d.%s".format(page.index + 1, ext)).writeBytes(image.bytes)
                done++
                downloadsRepo.update(
                    item.copy(status = MangaDownloadStatus.DOWNLOADING, totalPages = pages.size, downloadedPages = done)
                )
            }

            chapterRepo.setDownloadedPages(chapter.id, pages.size)
            downloadsRepo.update(item.copy(status = MangaDownloadStatus.DOWNLOADED, totalPages = pages.size, downloadedPages = pages.size))
        } catch (e: Exception) {
            downloadsRepo.update(item.copy(status = MangaDownloadStatus.ERROR))
        }
    }

    fun chapterDir(mangaId: String, chapterId: String): File? {
        if (mangaId.isBlank() || chapterId.isBlank()) return null
        return File(downloadsDir, "${mangaId.sanitize()}/${chapterId.sanitize()}")
    }

    /** Reads a downloaded page from disk, or null when the chapter is not downloaded. */
    suspend fun readDownloadedPage(mangaId: String, chapterId: String, pageIndex: Int): ByteArray? {
        val dir = chapterDir(mangaId, chapterId) ?: return null
        if (!dir.isDirectory) return null
        val prefix = "%03d.".format(pageIndex + 1)
        val file = dir.listFiles().orEmpty().firstOrNull { it.name.startsWith(prefix) } ?: return null
        return file.readBytes()
    }

    suspend fun isChapterDownloaded(chapterId: String): Boolean =
        downloadsRepo.isChapterDownloaded(chapterId)

    private fun extensionFor(bytes: ByteArray): String = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
        bytes.size > 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "webp"
        bytes.size > 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "bmp"
        else -> "img"
    }

    private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9._:-]"), "_")
}
