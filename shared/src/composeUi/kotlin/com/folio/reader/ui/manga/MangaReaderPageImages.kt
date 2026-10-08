package com.folio.reader.ui.manga

import com.folio.reader.manga.MangaChapterRef
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

// Reader page-image bytes: cache, deduped fetch, prefetch scheduling, save-to-disk.

private const val PAGE_BYTES_CACHE_MAX = 48L * 1024 * 1024
private const val IMAGE_PREFETCH_AHEAD = 6
private const val IMAGE_PREFETCH_NEXT_PAGES = 4

suspend fun MangaReaderViewModel.resolvePageImage(index: Int): ByteArray? = resolvePageBytes(index)

    /** Downloaded page, prefetched bytes, or a live source fetch — deduped per page key. */
internal suspend fun MangaReaderViewModel.resolvePageBytes(index: Int): ByteArray? {
        val slot = slotForIndex(index) ?: return null
        val local = index - slot.startIndex
        val page = slot.pages.getOrNull(local) ?: return null
        val key = pageKey(index)
        val cached = synchronized(pageBytes) { pageBytes[key] }
        if (cached != null) return cached
        val deferred = synchronized(inflightBytes) {
            inflightBytes.getOrPut(key) {
                scope.async {
                    try {
                        val bytes = downloadManager?.readDownloadedPage(mangaId, slot.chapter.id, local)
                            ?: run {
                                val ref = MangaChapterRef(
                                    url = slot.chapter.url,
                                    name = slot.chapter.name,
                                    chapterNumber = slot.chapter.chapterNumber,
                                )
                                backend.fetchPageImage(sourceId, ref, page).bytes
                            }
                        if (bytes != null) pageBytesPut(key, bytes)
                        bytes
                    } catch (_: Throwable) {
                        null
                    } finally {
                        synchronized(inflightBytes) { inflightBytes.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }

internal fun MangaReaderViewModel.pageBytesPut(key: String, bytes: ByteArray) {
        synchronized(pageBytes) {
            pageBytes[key]?.let { pageBytesTotal -= it.size }
            pageBytes[key] = bytes
            pageBytesTotal += bytes.size
            while (pageBytesTotal > PAGE_BYTES_CACHE_MAX && pageBytes.isNotEmpty()) {
                val eldest = pageBytes.entries.firstOrNull() ?: break
                pageBytes.remove(eldest.key)
                pageBytesTotal -= eldest.value.size
            }
        }
    }

    /**
     * Position-aware image prefetch: pages ahead in the current chapter first, then the
     * opening pages of the next chapter(s), then the tail of the previous one. Bounded
     * by [imageGate]; replacing the job cancels fetches the reader has moved away from.
     */
internal fun MangaReaderViewModel.scheduleImagePrefetch() {
        imagePrefetchJob?.cancel()
        imagePrefetchJob = scope.launch {
            val from = currentIndex.value
            val slot = slotForIndex(from) ?: return@launch
            val targets = mutableListOf<Int>()
            var i = from + 1
            var ahead = 0
            while (ahead < IMAGE_PREFETCH_AHEAD && slotForIndex(i) == slot) {
                targets += i
                i++
                ahead++
            }
            val snapshot = withSlots { toList() }
            val slotIdx = snapshot.indexOf(slot)
            for (nextIdx in slotIdx + 1..minOf(slotIdx + 2, snapshot.lastIndex)) {
                val next = snapshot[nextIdx]
                for (j in 0 until minOf(IMAGE_PREFETCH_NEXT_PAGES, next.pages.size)) {
                    targets += next.startIndex + j
                }
            }
            if (slotIdx > 0) {
                val prev = snapshot[slotIdx - 1]
                for (j in maxOf(0, prev.pages.size - 2) until prev.pages.size) {
                    targets += prev.startIndex + j
                }
            }
            targets.forEach { idx ->
                launch {
                    imageGate.withPermit {
                        val key = pageKey(idx)
                        val present = synchronized(pageBytes) { pageBytes.containsKey(key) }
                        if (!present) resolvePageBytes(idx)
                    }
                }
            }
        }
    }

suspend fun MangaReaderViewModel.savePage(index: Int): String? {
        val bytes = resolvePageImage(index) ?: return null
        val slot = slotForIndex(index) ?: return null
        val local = index - slot.startIndex
        val mangaTitle = manga.value?.title?.ifBlank { "manga" } ?: "manga"
        val chapterName = slot.chapter.name.ifBlank { "chapter" }
        val safe = Regex("[^A-Za-z0-9 ._()-]")
        val base = "${mangaTitle.take(60)} - ${chapterName.take(40)} - p${local + 1}"
            .replace(safe, "_").trim()
        return runCatching {
            fileSystem.exportToDownloads("$base.${imageExtensionFor(bytes)}", bytes)
        }.getOrNull()
    }

private fun imageExtensionFor(bytes: ByteArray): String = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
        bytes.size > 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "webp"
        else -> "img"
    }
