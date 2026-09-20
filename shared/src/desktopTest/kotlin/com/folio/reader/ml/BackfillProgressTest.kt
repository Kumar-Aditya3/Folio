package com.folio.reader.ml

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcChunkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The progress readout has to move *while* the backfill runs.
 *
 * `ChunkRepository.observeProgress` is what the Semantic search panel renders, and it recomputes
 * on a database revision flow. It used to collect `bookDataRevision` — which nothing in
 * `JdbcChunkRepository` ever bumped. The panel therefore only recomputed when some unrelated
 * library write happened to fire, so a backfill that was working perfectly showed a number that
 * never changed.
 *
 * That is worth its own test because of how it failed: the symptom was indistinguishable from a
 * stalled worker, and it sent a long investigation after WorkManager, job backoff and the slice
 * query when the defect was in the display. `BackfillLoopTest` proves the loop drains the
 * library; this proves you can *see* it doing so.
 */
class BackfillProgressTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var repository: JdbcChunkRepository

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-progress-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        repository = JdbcChunkRepository(database)
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun chunk(id: String, bookId: String, chapterId: String) = Chunk(
        id = id,
        bookId = bookId,
        chapterId = chapterId,
        spineIndex = 0,
        chunkIndex = 0,
        charStart = 0,
        charEnd = 16,
        text = "a passage of prose",
        contentHash = TextChunker.sha256("a passage of prose"),
    )

    @Test
    fun `progress emits on every chunk write, without any book write`() = runBlocking {
        val modelId = FakeEmbedder.TEST_MODEL.id

        // A live collector, exactly as the panel holds one while it is open.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = mutableListOf<Int>()
        scope.launch { repository.observeProgress(modelId).collect { seen.add(it.indexedChapters) } }
        // Let the collector subscribe before the first write.
        delay(100)
        val baseline = seen.size

        repeat(3) { i ->
            repository.storeChunks(
                listOf(chunk("c$i", bookId = "b1", chapterId = "ch$i")),
                modelId, 2, listOf(floatArrayOf(1f, 0f)),
            )
            delay(60)
        }
        scope.cancel()

        assertTrue(
            seen.size >= baseline + 3,
            "each storeChunks must push a progress update; saw $seen",
        )
        assertTrue(
            seen.contains(3),
            "the readout must reach the true indexed count; saw $seen",
        )
    }

    /**
     * The counterpart: `bookDataRevision` must stay untouched, or every slice would re-query
     * `getAllBooks()` and the whole collection/series graph once every few seconds.
     */
    @Test
    fun `chunk writes do not invalidate the book library flows`() = runBlocking {
        val before = database.bookDataRevision.value
        repository.storeChunks(
            listOf(chunk("c1", bookId = "b1", chapterId = "ch1")),
            FakeEmbedder.TEST_MODEL.id, 2, listOf(floatArrayOf(1f, 0f)),
        )
        assertTrue(
            database.bookDataRevision.value == before,
            "chunk writes must not bump bookDataRevision",
        )
    }
}
