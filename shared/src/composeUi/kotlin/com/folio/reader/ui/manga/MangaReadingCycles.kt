package com.folio.reader.ui.manga

import com.folio.reader.database.ReadingCycleRepository
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.model.ReadingCycle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * §11.5 item 5: re-read tracking for manga, on the reading_cycles table
 * (book-only until now) with bookId = mangaId. Manga rows are local-only
 * bookkeeping — they never sync — and the surfaced number is completed passes
 * minus the first full read.
 *
 * The reconcile is stateless and idempotent; an open cycle is the "pass in
 * progress" lock:
 *  - bootstrap: everything read but no cycle recorded (pre-feature library)
 *    seeds completed cycle #1 exactly once;
 *  - a reader finish of the first story chapter with no open pass starts one
 *    (Continue always offers the first chapter of a fully-read series, so full
 *    re-read passes begin there; partial re-reads from a middle chapter don't);
 *  - a reader finish of the final story chapter closes the open pass;
 *  - detail-screen actions only ever bootstrap — they never close a pass, so
 *    toggling chapters off/on mid re-read cannot complete it early.
 * A final-chapter finish with no open pass counts only for one-shots (a single
 * chapter: re-reading it IS a pass), so idly re-entering the last page of a
 * series never inflates the count.
 */
internal suspend fun reconcileMangaReadingCycle(
    mangaId: String,
    chapterRepo: MangaChapterRepository,
    cycleRepo: ReadingCycleRepository,
    readerFinishedChapterId: String? = null,
    now: Instant = Clock.System.now(),
) {
    val chapters = chapterRepo.getChapters(mangaId)
    if (chapters.isEmpty()) return
    val allRead = chapters.all { it.read }
    val storyOrder = chapters.sortedWith(STORY_ORDER)
    val storyFirstId = storyOrder.first().id
    val storyLastId = storyOrder.last().id
    val open = cycleRepo.getCurrentCycle(mangaId)
    val completed = cycleRepo.getCyclesForBook(mangaId).count { it.isCompleted }

    if (allRead && completed == 0) {
        val seed = open ?: ReadingCycle(
            id = "mcycle-$mangaId-1",
            bookId = mangaId,
            cycleNumber = 1,
            startedAt = now,
        )
        cycleRepo.insertCycle(seed.finish(now, 1.0))
        return
    }

    if (readerFinishedChapterId != null && readerFinishedChapterId == storyLastId) {
        if (open != null) {
            cycleRepo.insertCycle(open.finish(now, 1.0))
        } else if (chapters.size == 1) {
            cycleRepo.insertCycle(
                ReadingCycle(
                    id = "mcycle-$mangaId-${completed + 1}",
                    bookId = mangaId,
                    cycleNumber = completed + 1,
                    startedAt = now,
                ).finish(now, 1.0)
            )
        }
        return
    }

    if (readerFinishedChapterId != null && readerFinishedChapterId == storyFirstId && open == null) {
        cycleRepo.insertCycle(
            ReadingCycle(
                id = "mcycle-$mangaId-${completed + 1}",
                bookId = mangaId,
                cycleNumber = completed + 1,
                startedAt = now,
            )
        )
    }
}
