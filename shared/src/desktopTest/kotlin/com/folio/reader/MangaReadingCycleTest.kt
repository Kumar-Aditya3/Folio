package com.folio.reader

import com.folio.reader.database.ReadingCycleRepository
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaLastRead
import com.folio.reader.model.ReadingCycle
import com.folio.reader.ui.manga.reconcileMangaReadingCycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §11.5 item 5: manga re-read cycle reconcile. Fakes stand in for the chapter and
 * cycle repositories; every scenario drives the stateless reconcile the same way
 * the reader's save worker and the detail screen do.
 */
class MangaReadingCycleTest {

    class FakeChapterRepo : MangaChapterRepository {
        val chapters = LinkedHashMap<String, MangaChapter>()

        fun seed(mangaId: String, count: Int, allRead: Boolean = false): List<MangaChapter> {
            val list = (1..count).map { n ->
                MangaChapter(
                    id = "$mangaId-c$n",
                    mangaId = mangaId,
                    url = "$mangaId/c$n",
                    name = "Chapter $n",
                    chapterNumber = n.toFloat(),
                    sortOrder = n - 1,
                    read = allRead,
                )
            }
            list.forEach { chapters[it.id] = it }
            return list
        }

        fun markAll(read: Boolean) {
            chapters.keys.forEach { id -> chapters[id] = chapters[id]!!.copy(read = read) }
        }

        override suspend fun replaceChapters(mangaId: String, chapters: List<MangaChapter>) {
            chapters.forEach { this.chapters[it.id] = it }
        }

        override suspend fun getChapters(mangaId: String): List<MangaChapter> =
            chapters.values.filter { it.mangaId == mangaId }

        override suspend fun getChapter(chapterId: String): MangaChapter? = chapters[chapterId]

        override suspend fun markRead(chapterIds: List<String>, read: Boolean, emitSyncEvent: Boolean) {
            chapterIds.forEach { id -> chapters[id]?.let { chapters[id] = it.copy(read = read) } }
        }

        override suspend fun setBookmarked(chapterId: String, bookmarked: Boolean, emitSyncEvent: Boolean) {}
        override suspend fun saveProgress(chapterId: String, lastPage: Int, totalPages: Int, emitSyncEvent: Boolean) {}
        override suspend fun applyRemoteState(
            chapterId: String,
            read: Boolean,
            bookmarked: Boolean,
            lastPageRead: Int,
            updatedAt: Instant,
            emitSyncEvent: Boolean,
            totalPages: Int,
        ) {}

        override suspend fun setDownloadedPages(chapterId: String, pages: Int) {}
        override fun observeUnreadCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override fun observeProgress(): Flow<Map<String, Float>> = flowOf(emptyMap())
        override fun observeLastRead(): Flow<Map<String, MangaLastRead>> = flowOf(emptyMap())
        override fun observeDownloadedCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun markAllReadForManga(mangaId: String, read: Boolean) = markAll(read)
    }

    class FakeCycleRepo : ReadingCycleRepository {
        val cycles = mutableListOf<ReadingCycle>()

        override suspend fun insertCycle(cycle: ReadingCycle) {
            cycles.removeAll { it.id == cycle.id }
            cycles += cycle
        }

        override suspend fun updateCycle(cycle: ReadingCycle) = insertCycle(cycle)

        override suspend fun getCurrentCycle(bookId: String): ReadingCycle? =
            cycles.filter { it.bookId == bookId && it.finishedAt == null }.maxByOrNull { it.cycleNumber }

        override suspend fun getCyclesForBook(bookId: String): List<ReadingCycle> =
            cycles.filter { it.bookId == bookId }.sortedBy { it.cycleNumber }
    }

    private val chapterRepo = FakeChapterRepo()
    private val cycleRepo = FakeCycleRepo()
    private val mangaId = "manga-test"

    private fun reconcile(finishedChapterId: String? = null) = runBlocking {
        reconcileMangaReadingCycle(mangaId, chapterRepo, cycleRepo, finishedChapterId)
    }

    private fun completedCount() = runBlocking { cycleRepo.getCyclesForBook(mangaId).count { it.isCompleted } }

    // ---------- Bootstrap ----------

    @Test
    fun fullyReadSeriesWithNoHistorySeedsOneCompletionExactlyOnce() = runBlocking {
        chapterRepo.seed(mangaId, count = 3, allRead = true)
        reconcile()
        assertEquals(1, completedCount(), "pre-feature complete library bootstraps cycle #1")
        assertNull(cycleRepo.getCurrentCycle(mangaId))
        reconcile()
        reconcile(finishedChapterId = "${mangaId}-c3")
        assertEquals(1, completedCount(), "repeated bootstraps and final-page re-entries never inflate")
    }

    @Test
    fun bootstrapClosesTheOpenPassForAFullyReadSeries() = runBlocking {
        val chapters = chapterRepo.seed(mangaId, count = 3)
        reconcile(finishedChapterId = chapters[0].id)
        assertEquals(1, cycleRepo.getCurrentCycle(mangaId)?.cycleNumber, "pass 1 is open mid-story")
        chapterRepo.markAll(read = true)
        reconcile()
        assertEquals(1, completedCount(), "mark-all-read on an open first pass completes it")
        assertNull(cycleRepo.getCurrentCycle(mangaId))
    }

    // ---------- Reader-driven pass lifecycle ----------

    @Test
    fun readerPassOpensOnTheFirstChapterAndClosesOnTheFinalChapter() = runBlocking {
        val chapters = chapterRepo.seed(mangaId, count = 3)
        reconcile(finishedChapterId = chapters[0].id)
        val open = cycleRepo.getCurrentCycle(mangaId)
        assertEquals(1, open?.cycleNumber, "the first finished chapter opens pass 1")
        reconcile(finishedChapterId = chapters[1].id)
        assertEquals(1, cycleRepo.getCurrentCycle(mangaId)?.cycleNumber, "mid-pass finishes keep the same open cycle")
        reconcile(finishedChapterId = chapters[2].id)
        assertEquals(1, completedCount(), "finishing the story-final chapter completes pass 1")
        assertNull(cycleRepo.getCurrentCycle(mangaId))
        assertEquals(0, completedCount() - 1, "the first completed pass is not a re-read")
    }

    @Test
    fun reReadPassOpensAgainAndDetailActionsNeverCloseItEarly() = runBlocking {
        val chapters = chapterRepo.seed(mangaId, count = 3, allRead = true)
        reconcile()
        assertEquals(1, completedCount())

        // Pass 2 begins: chapters stay read=true, so the reader re-finish events drive it.
        reconcile(finishedChapterId = chapters[0].id)
        assertEquals(2, cycleRepo.getCurrentCycle(mangaId)?.cycleNumber, "a re-read of a non-final chapter opens pass 2")

        // Detail-screen toggles (no finish event) must never complete an open pass,
        // even though every chapter still reads as read from pass 1.
        reconcile()
        reconcile(finishedChapterId = chapters[1].id)
        assertNull(cycleRepo.getCurrentCycle(mangaId)?.finishedAt, "toggles cannot close a re-read early")

        reconcile(finishedChapterId = chapters[2].id)
        assertEquals(2, completedCount(), "pass 2 completes on the final chapter")
        assertEquals(1, completedCount() - 1, "surfaced re-read count")
    }

    @Test
    fun reEnteringTheFinalPageWithoutAPassDoesNotCount() = runBlocking {
        val chapters = chapterRepo.seed(mangaId, count = 3, allRead = true)
        reconcile()
        reconcile(finishedChapterId = chapters[0].id)
        reconcile(finishedChapterId = chapters[2].id)
        assertEquals(2, completedCount())

        reconcile(finishedChapterId = chapters[2].id)
        reconcile(finishedChapterId = chapters[1].id)
        assertEquals(2, completedCount(), "final-page re-entries outside a pass never inflate")
        assertNull(cycleRepo.getCurrentCycle(mangaId), "no stray open pass is left behind")
    }

    @Test
    fun partialPassesStartingFromAMiddleChapterNeverRecord() = runBlocking {
        val chapters = chapterRepo.seed(mangaId, count = 3)
        reconcile(finishedChapterId = chapters[1].id)
        reconcile(finishedChapterId = chapters[2].id)
        assertEquals(0, completedCount(), "re-reading from a middle chapter is not a pass")
        assertNull(cycleRepo.getCurrentCycle(mangaId), "no pass opens without the first chapter")
    }

    @Test
    fun oneShotReReadsCountEachPass() = runBlocking {
        val chapters = chapterRepo.seed(mangaId, count = 1)
        reconcile(finishedChapterId = chapters[0].id)
        assertEquals(1, completedCount(), "the single chapter is also the final chapter")
        reconcile(finishedChapterId = chapters[0].id)
        assertEquals(2, completedCount(), "re-reading a one-shot is a full pass")
        reconcile(finishedChapterId = chapters[0].id)
        assertEquals(3, completedCount())
    }

    // ---------- Detail-action semantics ----------

    @Test
    fun markAllReadOnAFreshLibraryBootstrapsOnce() = runBlocking {
        chapterRepo.seed(mangaId, count = 4)
        reconcile()
        assertEquals(0, completedCount(), "unread series records nothing")
        chapterRepo.markAll(read = true)
        reconcile()
        assertEquals(1, completedCount())
        chapterRepo.markAll(read = false)
        chapterRepo.markAll(read = true)
        reconcile()
        reconcile()
        assertEquals(1, completedCount(), "toggle off/on replays never bootstrap again")
        assertTrue(cycleRepo.getCyclesForBook(mangaId).all { it.bookId == mangaId }, "rows are keyed by manga id")
    }
}
