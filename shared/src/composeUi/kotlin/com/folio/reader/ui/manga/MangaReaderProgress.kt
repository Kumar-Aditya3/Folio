package com.folio.reader.ui.manga

import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

    // ---------- Serialized progress persistence ----------

    /**
     * Reading position writes funnel through one channel consumed by one coroutine, so
     * snapshots always land in the order the reader saw them. The previous fire-and-forget
     * launches raced the DB mutex and a stale page could overwrite a newer one.
     */
internal data class ProgressSnapshot(
        val chapterId: String,
        val lastPage: Int,
        val totalPages: Int,
        val finished: Boolean,
    )

internal fun MangaReaderViewModel.startSession(manga: MangaEntry, chapter: MangaChapter) {
        val repo = sessionRepo ?: return
        val now = kotlinx.datetime.Clock.System.now()
        val position = com.folio.reader.model.ReadingPosition(
            bookId = manga.id,
            deviceId = "",
            chapterId = chapter.id,
            spineIndex = 0,
            contentLocator = "manga-page:0",
        )
        val session = com.folio.reader.model.ReadingSession(
            id = java.util.UUID.randomUUID().toString(),
            bookId = manga.id,
            cycleId = null,
            deviceId = "",
            startedAt = now,
            startPosition = position,
        )
        activeSession = session
        scope.launch { runCatching { repo.insertSession(session) } }
    }

internal fun MangaReaderViewModel.currentSnapshot(): ProgressSnapshot? {
        val slot = activeSlot() ?: return null
        val total = slot.pages.size
        if (total <= 0) return null
        val local = (currentIndex.value - slot.startIndex).coerceIn(0, total - 1)
        return ProgressSnapshot(slot.chapter.id, local, total, finished = local >= total - 1)
    }

internal fun MangaReaderViewModel.ensureSaveWorker() {
        if (saveWorker?.isActive == true) return
        saveWorker = scope.launch {
            for (snapshot in progressQueue) {
                progressMutex.withLock { writeSnapshot(snapshot) }
            }
        }
    }

internal suspend fun MangaReaderViewModel.writeSnapshot(snapshot: ProgressSnapshot) {
        chapterRepo.saveProgress(snapshot.chapterId, snapshot.lastPage, snapshot.totalPages)
        if (snapshot.finished) {
            val existing = chapterRepo.getChapter(snapshot.chapterId)
            if (existing != null && !existing.read) {
                chapterRepo.markRead(listOf(snapshot.chapterId), true)
            }
        }
        mangaRepo.touchLastRead(mangaId)
    }

    /** Queues the current position; ordering is preserved by the single save worker. */
internal fun MangaReaderViewModel.enqueueProgress() {
        val snapshot = currentSnapshot() ?: return
        ensureSaveWorker()
        progressQueue.trySend(snapshot)
    }

    /**
     * Chapters the reader moved forward past are finished by definition: their pages all
     * scrolled by. Finalizing here (instead of only on the active slot's last page) is
     * what keeps webtoon boundary crossings from stranding a chapter as unread.
     * [from] is inclusive, [to] exclusive — only the slots actually passed.
     */
internal fun MangaReaderViewModel.finalizePassedSlots(from: Int, to: Int) {
        val passed = withSlots { subList(from.coerceAtLeast(0), to.coerceAtMost(size)).toList() }
        for (slot in passed) {
            if (slot.pages.isEmpty() || !finalizedChapters.add(slot.chapter.id)) continue
            ensureSaveWorker()
            progressQueue.trySend(
                ProgressSnapshot(slot.chapter.id, slot.pages.size - 1, slot.pages.size, finished = true)
            )
        }
    }
