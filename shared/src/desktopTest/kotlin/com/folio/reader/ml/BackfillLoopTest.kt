package com.folio.reader.ml

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcChunkRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backfill **loop**, not the slice query.
 *
 * `ChunkRepositoryTest` covers `chaptersMissingVectors` and the storage layer, but nothing
 * exercised the thing that actually has to terminate: the `while` in
 * `EmbeddingBackfillWorker.doWork`, which repeatedly asks for a slice until the indexer reports
 * `complete`.
 *
 * That gap is why the feature could ship broken twice over. On device the readout sat at
 * "2 246 of 2 561" and the worker appeared to stop after a handful of chapters, and there was no
 * test that could say whether the loop was wrong or the *progress display* was wrong. It turned
 * out to be the display (see `BackfillProgressTest`), but only this test can prove the loop
 * itself drains the library.
 *
 * The corpus deliberately reuses chapter ids across books, because that is what real EPUBs do
 * and it is the shape that broke the slice query in the first place.
 */
class BackfillLoopTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var repository: JdbcChunkRepository

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-backfill-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        repository = JdbcChunkRepository(database)
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    /** Mirrors the worker's factory: the model is "on disk" as far as the indexer is concerned. */
    private class FakeFactory(
        override val model: EmbeddingModel,
        private val poison: (String) -> Boolean = { false },
    ) : EmbedderFactory {
        var createCount = 0
            private set

        override suspend fun create(threads: Int?, sweep: Boolean): Embedder {
            createCount++
            return FakeEmbedder(model, poison = poison)
        }
    }

    private suspend fun seedChapter(
        bookId: String,
        chapterId: String,
        spineIndex: Int,
        words: Int,
        marker: String? = null,
    ) {
        // Space-separated, exactly as `SearchIndexer.extractPlainText` leaves it — the
        // `HAS_ENOUGH_WORDS` predicate counts spaces, so the fixture has to look the same.
        val content = (1..words).joinToString(" ") { "word$it" } +
            if (marker != null) " $marker" else ""
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                    "VALUES (?, ?, ?, 'Chapter', ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setInt(3, spineIndex)
                stmt.setString(4, content)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Runs the worker's loop verbatim — same slice size, same three exit conditions — and
     * reports why it stopped.
     */
    private data class LoopOutcome(
        val rounds: Int,
        val indexedChapters: Int,
        val failedChapters: Int,
        val stopReason: String,
    )

    private suspend fun runBackfillLoop(
        indexer: EmbeddingIndexer,
        limit: Int = 40,
        maxRounds: Int = 2_000,
    ): LoopOutcome {
        var indexed = 0
        var failed = 0
        var rounds = 0
        var reason = "maxRounds"
        while (rounds < maxRounds) {
            rounds++
            val slice = indexer.backfillSlice(limit)
            if (slice.modelMissing) {
                reason = "modelMissing"
                break
            }
            indexed += slice.indexedChapters
            failed += slice.failedChapters
            if (slice.complete) {
                reason = "complete"
                break
            }
            if (slice.indexedChapters == 0) {
                reason = "noProgress"
                break
            }
        }
        return LoopOutcome(rounds, indexed, failed, reason)
    }

    @Test
    fun `the backfill loop drains the whole library`() = runBlocking {
        // 12 books x 30 chapters = 360 chapters, all reusing the same chapter ids, which is
        // what `EpubParser` produces (`id = manifestItem.id`).
        val books = 12
        val chaptersPerBook = 30
        repeat(books) { b ->
            repeat(chaptersPerBook) { c ->
                seedChapter(bookId = "book$b", chapterId = "ch$c", spineIndex = c, words = 60)
            }
        }
        val expected = books * chaptersPerBook
        assertEquals(expected, repository.searchableChapterCount())

        val factory = FakeFactory(FakeEmbedder.TEST_MODEL)
        val indexer = EmbeddingIndexer(repository, factory)

        val outcome = runBackfillLoop(indexer)

        assertEquals("complete", outcome.stopReason, "the loop must finish, not give up")
        assertEquals(expected, outcome.indexedChapters, "every chapter must be indexed")
        // 360 chapters at 40 per slice is 9 full slices, then a 10th that reports complete.
        assertEquals(10, outcome.rounds)
        assertEquals(expected, repository.progress(FakeEmbedder.TEST_MODEL.id).indexedChapters)
        assertEquals(expected, repository.progress(FakeEmbedder.TEST_MODEL.id).totalChapters)
        assertTrue(
            repository.chaptersMissingVectors(FakeEmbedder.TEST_MODEL.id, 40).isEmpty(),
            "nothing may be left over",
        )
    }

    @Test
    fun `a resumed backfill does no duplicate work`() = runBlocking {
        repeat(5) { b ->
            repeat(20) { c -> seedChapter(bookId = "book$b", chapterId = "ch$c", spineIndex = c, words = 40) }
        }
        val factory = FakeFactory(FakeEmbedder.TEST_MODEL)
        val indexer = EmbeddingIndexer(repository, factory)

        // First pass, interrupted after one slice.
        val first = indexer.backfillSlice(40)
        assertEquals(40, first.indexedChapters)
        assertTrue(!first.complete)

        // Second pass must pick up the remaining 60 and then report complete.
        val outcome = runBackfillLoop(indexer)
        assertEquals("complete", outcome.stopReason)
        assertEquals(60, outcome.indexedChapters)
        assertEquals(100, repository.progress(FakeEmbedder.TEST_MODEL.id).indexedChapters)
    }

    /**
     * The regression that started all of this: a slice of 40 chapters drawn from books that
     * share chapter ids must still hand back 40 *distinct* chapters, and the loop must be able
     * to reach `complete`. Before the `(book_id, chapter_id)` fix the slice returned nothing
     * once one book's ids were indexed, so the loop exited on round 1 with `complete` while 300
     * chapters were unembedded.
     */
    @Test
    fun `a slice is not masked by another book sharing its chapter ids`() = runBlocking {
        repeat(10) { b ->
            repeat(40) { c -> seedChapter(bookId = "book$b", chapterId = "ch$c", spineIndex = c, words = 30) }
        }
        val factory = FakeFactory(FakeEmbedder.TEST_MODEL)
        val indexer = EmbeddingIndexer(repository, factory)

        val firstSlice = indexer.backfillSlice(40)
        assertEquals(40, firstSlice.indexedChapters, "the first slice must be full")
        assertTrue(!firstSlice.complete, "400 chapters cannot be done in one slice")

        val outcome = runBackfillLoop(indexer)
        assertEquals("complete", outcome.stopReason)
        assertEquals(400, repository.progress(FakeEmbedder.TEST_MODEL.id).indexedChapters)
    }

    /**
     * The device failure, reproduced.
     *
     * On device the readout froze at "2 322 of 2 561" and every forced run created a fresh
     * 15-minute-backoff job without advancing a single chapter. The cause is a **poison
     * chapter**: `backfillSlice` aborted on the first exception, and because the failing
     * chapter is never stored, `chaptersMissingVectors` offered it first on *every* subsequent
     * slice. One unindexable chapter blocked the remaining 239 permanently.
     *
     * The poisoned chapter is seeded first so it has the lowest rowid, which is the order the
     * slice query selects in — the worst case, and the one the device hit.
     */
    @Test
    fun `a chapter that always fails does not stall the slice`() = runBlocking {
        repeat(3) { b ->
            repeat(20) { c ->
                seedChapter(
                    bookId = "book$b",
                    chapterId = "ch$c",
                    spineIndex = c,
                    words = 40,
                    // Only this one chapter's text trips the embedder.
                    marker = if (b == 0 && c == 0) "poison" else null,
                )
            }
        }

        val factory = FakeFactory(FakeEmbedder.TEST_MODEL) { it.contains("poison") }
        val indexer = EmbeddingIndexer(repository, factory)

        // Before the fix this call threw, taking the whole slice with it.
        val slice = indexer.backfillSlice(40)
        assertEquals(39, slice.indexedChapters, "the other 39 chapters in the slice must land")
        assertEquals(1, slice.failedChapters, "the poison chapter must be reported, not swallowed")
        assertTrue(slice.firstError != null, "the cause must be carried out for logging")
        assertEquals(39, repository.progress(FakeEmbedder.TEST_MODEL.id).indexedChapters)

        // And the loop still terminates rather than retrying the same chapter forever. The
        // standalone slice above already stored 39, so the loop only has the remaining 20 to do
        // — the invariant that matters is the library total.
        val outcome = runBackfillLoop(indexer)
        assertEquals("complete", outcome.stopReason, "the loop must finish, not retry forever")
        assertEquals(20, outcome.indexedChapters)
        assertTrue(outcome.failedChapters >= 1, "the poison chapter is offered again and fails again")
        assertEquals(
            59,
            repository.progress(FakeEmbedder.TEST_MODEL.id).indexedChapters,
            "all 59 indexable chapters must be done; only the poison one may be missing",
        )
    }

    /**
     * The guard against the opposite mistake: skipping failures must not turn a broken embedder
     * into an infinite retry loop. If nothing can be indexed, the loop has to stop.
     */
    @Test
    fun `a library where nothing can be indexed terminates instead of spinning`() = runBlocking {
        repeat(5) { c -> seedChapter(bookId = "book0", chapterId = "ch$c", spineIndex = c, words = 40) }

        val factory = FakeFactory(FakeEmbedder.TEST_MODEL) { true }
        val indexer = EmbeddingIndexer(repository, factory)

        val outcome = runBackfillLoop(indexer)

        assertEquals("complete", outcome.stopReason)
        assertEquals(0, outcome.indexedChapters)
        assertEquals(1, outcome.rounds, "a fully failing library must not be re-sliced")
    }
}
