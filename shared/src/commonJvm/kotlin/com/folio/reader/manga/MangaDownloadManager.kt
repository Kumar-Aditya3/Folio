package com.folio.reader.manga

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sequential chapter download worker (Mihon-style download queue). Pages are stored on
 * disk under "<mangaId>/<chapterId>/" inside the active [MangaDownloadStorage] and served
 * back to the reader by [readDownloadedPage] before falling through to the network backend.
 * The storage location can be changed at runtime via [switchStorage], which migrates
 * existing chapters to the new home.
 */
class MangaDownloadManager(
    private val backend: MangaBackend,
    private val downloadsRepo: MangaDownloadRepository,
    private val chapterRepo: MangaChapterRepository,
    initialStorage: MangaDownloadStorage,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loop: Job? = null

    @Volatile
    private var storage: MangaDownloadStorage = initialStorage

    fun storageDescription(): String = storage.describe()

    /**
     * Moves manga downloads to [newStorage]: every existing chapter is copied over and
     * verified before the sources are deleted, and the active storage flips only after
     * the whole migration succeeds. Returns false (leaving everything as-is) when the
     * target cannot be used, so callers must not persist the new location in that case.
     */
    suspend fun switchStorage(
        newStorage: MangaDownloadStorage,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val old = storage
        if (old === newStorage || old.describe() == newStorage.describe()) return@withContext true
        runCatching { migrate(old, newStorage, onProgress) }
            .onSuccess {
                storage = newStorage
                // A user-picked folder lives on shared storage, where Android's media
                // scanner would sweep every page of every chapter into the phone's
                // gallery. An empty `.nomedia` at the root tells it not to. Only
                // written when absent, because this also runs on every launch when a
                // stored location is restored. Best effort: failing to write the
                // marker is no reason to refuse a folder the migration already
                // proved works.
                runCatching {
                    if (NOMEDIA !in newStorage.listFiles("")) {
                        newStorage.write("", NOMEDIA, ByteArray(0))
                    }
                }
            }
            .isSuccess
    }

    private suspend fun migrate(
        from: MangaDownloadStorage,
        to: MangaDownloadStorage,
        onProgress: suspend (Int, Int) -> Unit,
    ) {
        val chapters = from.listSubDirs("").flatMap { mangaDir ->
            from.listSubDirs(mangaDir).map { chapterDir -> "$mangaDir/$chapterDir" }
        }
        val copied = mutableListOf<String>()
        try {
            chapters.forEachIndexed { index, path ->
                if (!to.ensureDir(path)) throw IllegalStateException("Cannot create folder in new location")
                val names = from.listFiles(path)
                names.forEach { name ->
                    val bytes = from.readFirst(path, name)
                        ?: throw IllegalStateException("Cannot read $path/$name from old location")
                    if (!to.write(path, name, bytes)) throw IllegalStateException("Cannot write $path/$name to new location")
                }
                if (to.listFiles(path).size < names.size) throw IllegalStateException("Copy of $path is incomplete")
                copied.add(path)
                onProgress(index + 1, chapters.size)
            }
        } catch (e: Exception) {
            copied.forEach { to.deleteDir(it) }
            throw e
        }
        chapters.forEach { from.deleteDir(it) }
    }

    /**
     * Starts the queue loop. Idempotent — a second loop running alongside the first
     * would process the same chapters twice.
     */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            resumeInterrupted()
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

    /** Ends the loop. The queue is durable, so [start] resumes it where it stopped. */
    fun stop() {
        loop?.cancel()
        loop = null
    }

    /**
     * Puts back the chapters a frozen or killed process left behind. The loop only
     * picks up QUEUED rows, so a row interrupted mid-flight stayed DOWNLOADING
     * forever, and the socket timeout that fires while Android holds the process
     * frozen had usually already written the same chapter off as ERROR — the
     * "Failed · timed out" a reader sees after closing the app mid-download. Both go
     * back in the queue once per launch; [process] skips the pages already on disk,
     * so this resumes a chapter instead of restarting it.
     */
    private suspend fun resumeInterrupted() {
        for (item in downloadsRepo.observeQueue().first()) {
            when (item.status) {
                MangaDownloadStatus.DOWNLOADING, MangaDownloadStatus.ERROR ->
                    downloadsRepo.update(item.copy(status = MangaDownloadStatus.QUEUED, error = null))

                else -> Unit
            }
        }
    }

    suspend fun queueChapter(mangaId: String, chapter: MangaChapter) {
        // Local-source chapters are already fully on disk; queueing one would extract
        // the archive page-by-page into duplicate files with zero offline benefit.
        if (mangaId.substringBefore(":").toLongOrNull() == LOCAL_SOURCE_ID) return
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
        deleteChapterDownload(item.mangaId, item.chapterId)
    }

    private suspend fun process(item: MangaDownload) {
        val chapter = chapterRepo.getChapter(item.chapterId)
        if (chapter == null) {
            downloadsRepo.remove(item.id)
            return
        }
        val ref = MangaChapterRef(url = chapter.url, name = chapter.name, chapterNumber = chapter.chapterNumber)
        val path = chapterPath(item.mangaId, item.chapterId)
        if (path == null) {
            downloadsRepo.remove(item.id)
            return
        }
        downloadsRepo.update(item.copy(status = MangaDownloadStatus.DOWNLOADING, error = null))

        try {
            val sourceId = item.mangaId.substringBefore(":").toLong()
            val pages = withRetries { backend.fetchPageList(sourceId, ref) }
            if (!storage.ensureDir(path)) throw IllegalStateException("Cannot create download folder")
            // Pages an interrupted run already wrote are kept, so resuming a chapter
            // costs only the pages that are genuinely missing.
            val onDisk = storage.listNonEmptyFiles(path)
            val stored: (Int) -> Boolean = { index -> onDisk.any { it.startsWith("%03d.".format(index + 1)) } }
            var done = pages.count { stored(it.index) }
            downloadsRepo.update(
                item.copy(status = MangaDownloadStatus.DOWNLOADING, totalPages = pages.size, downloadedPages = done, error = null)
            )

            for (page in pages) {
                if (stored(page.index)) continue
                val image = withRetries { backend.fetchPageImage(sourceId, ref, page) }
                val ext = extensionFor(image.bytes)
                if (!storage.write(path, "%03d.%s".format(page.index + 1, ext), image.bytes)) {
                    throw IllegalStateException("Cannot write page ${page.index + 1} to storage")
                }
                done++
                downloadsRepo.update(
                    item.copy(status = MangaDownloadStatus.DOWNLOADING, totalPages = pages.size, downloadedPages = done)
                )
            }

            chapterRepo.setDownloadedPages(chapter.id, pages.size)
            downloadsRepo.update(item.copy(status = MangaDownloadStatus.DOWNLOADED, totalPages = pages.size, downloadedPages = pages.size, error = null))
        } catch (e: Exception) {
            downloadsRepo.update(
                item.copy(status = MangaDownloadStatus.ERROR, error = e.message?.takeIf { it.isNotBlank() } ?: "Download failed")
            )
        }
    }

    private fun chapterPath(mangaId: String, chapterId: String): String? {
        if (mangaId.isBlank() || chapterId.isBlank()) return null
        return "${mangaId.sanitize()}/${chapterId.sanitize()}"
    }

    /**
     * Fetching a page fails routinely for reasons that have nothing to do with the
     * chapter — a radio handover, a source rate limit, or the socket timeout firing
     * while Android held the process frozen. Retrying keeps a 40-page chapter from
     * being written off over one bad page; only the last attempt's failure escapes.
     */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var failure: Throwable? = null
        for (attempt in 1..FETCH_ATTEMPTS) {
            val result = runCatching { block() }
            if (result.isSuccess) return result.getOrThrow()
            failure = result.exceptionOrNull()
            // Cancelling the queue is a decision, not a transient failure.
            if (failure is CancellationException) throw failure
            if (attempt < FETCH_ATTEMPTS) delay(FETCH_BACKOFF_MS * attempt)
        }
        throw failure ?: IllegalStateException("Download failed")
    }

    /** Removes a chapter's downloaded pages from the active location. */
    suspend fun deleteChapterDownload(mangaId: String, chapterId: String) {
        val path = chapterPath(mangaId, chapterId) ?: return
        withContext(Dispatchers.IO) {
            storage.deleteDir(path)
        }
        downloadsRepo.remove(chapterId)
    }

    /** Reads a downloaded page from disk, or null when the chapter is not downloaded. */
    suspend fun readDownloadedPage(mangaId: String, chapterId: String, pageIndex: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val path = chapterPath(mangaId, chapterId) ?: return@withContext null
            val prefix = "%03d.".format(pageIndex + 1)
            storage.readFirst(path, prefix)
        }

    /** How many page files a chapter has on disk (0 when not downloaded). */
    suspend fun downloadedPageCount(mangaId: String, chapterId: String): Int =
        withContext(Dispatchers.IO) {
            val path = chapterPath(mangaId, chapterId) ?: return@withContext 0
            // Empty files are excluded: the reader treats a non-zero count as "this
            // chapter can be served offline", and a half-written page cannot be.
            storage.listNonEmptyFiles(path).size
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

    private companion object {
        /** Android's marker for "media scanner, skip this directory tree". */
        const val NOMEDIA = ".nomedia"

        /** Attempts per page fetch, and the pause before each retry. */
        const val FETCH_ATTEMPTS = 3
        const val FETCH_BACKOFF_MS = 1500L
    }
}
