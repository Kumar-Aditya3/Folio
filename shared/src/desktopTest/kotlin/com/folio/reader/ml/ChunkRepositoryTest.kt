package com.folio.reader.ml

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcChunkRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage-layer tests for ML_PLAN Phase 2.
 *
 * The properties that matter here are the ones that make a model swap or a re-import
 * survivable: vectors must round-trip bit-exactly, models must not contaminate each
 * other, and a dimension change must invalidate rather than silently corrupt.
 */
class ChunkRepositoryTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var repository: JdbcChunkRepository

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-chunks-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        repository = JdbcChunkRepository(database)
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun chunk(
        id: String = "c1",
        bookId: String = "b1",
        chapterId: String = "ch1",
        index: Int = 0,
        text: String = "a passage of prose",
    ) = Chunk(
        id = id,
        bookId = bookId,
        chapterId = chapterId,
        spineIndex = index,
        chunkIndex = index,
        charStart = index * 100,
        charEnd = index * 100 + text.length,
        text = text,
        contentHash = TextChunker.sha256(text),
    )

    private fun vector(vararg values: Float) = values

    @Test
    fun `vector encoding round-trips bit-exactly`() {
        val original = floatArrayOf(0f, 1f, -1f, 0.5f, -0.25f, 1e-8f, 3.4028235e38f, -3.4028235e38f)
        val encoded = JdbcChunkRepository.encodeVector(original)
        assertEquals(original.size * 4, encoded.size)
        val decoded = JdbcChunkRepository.decodeVector(encoded, original.size)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `vectors are stored little-endian regardless of platform`() {
        // 1.0f is 0x3F800000; little-endian puts the least significant byte first.
        val bytes = JdbcChunkRepository.encodeVector(floatArrayOf(1.0f))
        assertContentEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), bytes)
    }

    @Test
    fun `chunks and vectors survive a store and load`() = runBlocking {
        val chunks = listOf(chunk("c1", index = 0), chunk("c2", index = 1))
        repository.storeChunks(chunks, "model-a", 3, listOf(vector(1f, 0f, 0f), vector(0f, 1f, 0f)))

        val loaded = repository.loadVectors("model-a", 3)
        assertEquals(2, loaded.size)
        val byId = loaded.associateBy { it.first.id }
        assertNotNull(byId["c1"])
        assertEquals("a passage of prose", byId.getValue("c1").first.text)
        assertContentEquals(floatArrayOf(1f, 0f, 0f), byId.getValue("c1").second)
        assertContentEquals(floatArrayOf(0f, 1f, 0f), byId.getValue("c2").second)
    }

    @Test
    fun `chunk offsets and metadata are preserved`() = runBlocking {
        repository.storeChunks(
            listOf(chunk("c1", bookId = "book-9", chapterId = "chapter-9", index = 3)),
            "model-a", 2, listOf(vector(1f, 0f)),
        )
        val loaded = repository.loadVectors("model-a", 2).single().first
        assertEquals("book-9", loaded.bookId)
        assertEquals("chapter-9", loaded.chapterId)
        assertEquals(3, loaded.spineIndex)
        assertEquals(3, loaded.chunkIndex)
        assertEquals(300, loaded.charStart)
        assertEquals(300 + "a passage of prose".length, loaded.charEnd)
    }

    @Test
    fun `a different model's vectors are not returned`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1")), "model-a", 2, listOf(vector(1f, 0f)))
        repository.storeChunks(listOf(chunk("c1")), "model-b", 2, listOf(vector(0f, 1f)))

        val forA = repository.loadVectors("model-a", 2)
        val forB = repository.loadVectors("model-b", 2)
        assertEquals(1, forA.size)
        assertEquals(1, forB.size)
        assertContentEquals(floatArrayOf(1f, 0f), forA.single().second)
        assertContentEquals(floatArrayOf(0f, 1f), forB.single().second)
    }

    /**
     * Chunk ids are content-derived, so two models hold the *same* chunk id. Every delete on a
     * model-scoped path has to carry `model_id` with it, or it takes the other model's vectors
     * down too — which would look like "semantic search silently got worse" rather than like a bug.
     */
    @Test
    fun `deleting one model's chunks leaves another model's vectors alone`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1")), "model-a", 2, listOf(vector(1f, 0f)))
        repository.storeChunks(listOf(chunk("c1")), "model-b", 2, listOf(vector(0f, 1f)))

        repository.deleteChunksForBook("b1", "model-a")

        assertTrue(repository.loadVectors("model-a", 2).isEmpty())
        val surviving = repository.loadVectors("model-b", 2)
        assertEquals(1, surviving.size)
        assertContentEquals(floatArrayOf(0f, 1f), surviving.single().second)
    }

    @Test
    fun `re-storing a chapter for one model does not evict another model's vectors`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1")), "model-a", 2, listOf(vector(1f, 0f)))
        repository.storeChunks(listOf(chunk("c1")), "model-b", 2, listOf(vector(0f, 1f)))
        // Re-store model-a. Its pre-delete must stay inside model-a.
        repository.storeChunks(listOf(chunk("c1")), "model-a", 2, listOf(vector(0.5f, 0.5f)))

        assertEquals(1, repository.loadVectors("model-b", 2).size)
        val forA = repository.loadVectors("model-a", 2)
        assertEquals(1, forA.size)
        assertContentEquals(floatArrayOf(0.5f, 0.5f), forA.single().second)
    }

    @Test
    fun `a dimension mismatch yields nothing rather than mixed vectors`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1")), "model-a", 2, listOf(vector(1f, 0f)))
        // Asking for the same model at a different width must not return the old vectors.
        assertTrue(repository.loadVectors("model-a", 4).isEmpty())
        assertEquals(1, repository.loadVectors("model-a", 2).size)
    }

    @Test
    fun `re-storing a chapter replaces its chunks instead of accumulating`() = runBlocking {
        repository.storeChunks(
            listOf(chunk("old-1", chapterId = "ch1"), chunk("old-2", chapterId = "ch1")),
            "model-a", 2, listOf(vector(1f, 0f), vector(0f, 1f)),
        )
        assertEquals(2, repository.chunkCount("model-a"))

        // The chapter's text changed, so its chunk ids changed with it.
        repository.storeChunks(listOf(chunk("new-1", chapterId = "ch1")), "model-a", 2, listOf(vector(1f, 1f)))

        assertEquals(1, repository.chunkCount("model-a"))
        assertEquals(listOf("new-1"), repository.loadVectors("model-a", 2).map { it.first.id })
    }

    @Test
    fun `deleting a book removes its chunks and vectors`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1", bookId = "b1")), "model-a", 2, listOf(vector(1f, 0f)))
        repository.storeChunks(listOf(chunk("c2", bookId = "b2")), "model-a", 2, listOf(vector(0f, 1f)))

        repository.deleteChunksForBook("b1")

        assertEquals(1, repository.chunkCount("model-a"))
        assertEquals(listOf("c2"), repository.loadVectors("model-a", 2).map { it.first.id })
    }

    @Test
    fun `deleting a model leaves other models intact`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1")), "model-a", 2, listOf(vector(1f, 0f)))
        repository.storeChunks(listOf(chunk("c1")), "model-b", 2, listOf(vector(0f, 1f)))

        repository.deleteChunksForModel("model-a")

        assertTrue(repository.loadVectors("model-a", 2).isEmpty())
        assertEquals(1, repository.loadVectors("model-b", 2).size)
    }

    @Test
    fun `indexedChunkIds reports what a chapter already has`() = runBlocking {
        repository.storeChunks(
            listOf(chunk("c1", chapterId = "ch1"), chunk("c2", chapterId = "ch1")),
            "model-a", 2, listOf(vector(1f, 0f), vector(0f, 1f)),
        )
        assertEquals(setOf("c1", "c2"), repository.indexedChunkIds("b1", "ch1", "model-a"))
        assertTrue(repository.indexedChunkIds("b1", "ch1", "model-b").isEmpty())
        // Same chapter id, different book: must not be visible through the other book.
        assertTrue(repository.indexedChunkIds("b2", "ch1", "model-a").isEmpty())
    }

    @Test
    fun `a corrupt blob is skipped rather than poisoning the index`() = runBlocking {
        repository.storeChunks(listOf(chunk("c1")), "model-a", 4, listOf(vector(1f, 2f, 3f, 4f)))
        // Simulate a truncated write.
        database.withConnection { conn ->
            conn.prepareStatement("UPDATE chapter_vectors SET vector = ? WHERE chunk_id = 'c1'").use { stmt ->
                stmt.setBytes(1, byteArrayOf(1, 2, 3))
                stmt.executeUpdate()
            }
        }
        assertTrue(repository.loadVectors("model-a", 4).isEmpty())
    }

    /**
     * The backfill's slice query has two properties that are invisible in its results and
     * only show up as the backfill crawling. Both were wrong, and both were found by
     * measuring the installed release rather than the test suite, so they get a guard.
     */
    @Test
    fun `backfill slice query is indexed and does not sort`() = runBlocking {
        // Enough rows that the planner is choosing under realistic conditions.
        repeat(300) { i ->
            repository.storeChunks(
                listOf(chunk("c$i", chapterId = "ch$i")), "model-a", 2, listOf(vector(1f, 0f)),
            )
        }
        repeat(300) { i ->
            database.withConnection { conn ->
                conn.prepareStatement(
                    "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                        "VALUES ('b1', ?, ?, 'Chapter', 'some indexed prose for chapter ' || ? || " +
                        "' with enough words in it to be chunked')"
                ).use { stmt ->
                    stmt.setString(1, "ch$i")
                    stmt.setInt(2, i)
                    stmt.setInt(3, i)
                    stmt.executeUpdate()
                }
            }
        }

        val plan = database.withConnection { conn ->
            conn.prepareStatement(
                "EXPLAIN QUERY PLAN " + JdbcChunkRepository.BACKFILL_SLICE_SQL
            ).use { stmt ->
                stmt.setString(1, "model-a")
                stmt.setInt(2, 40)
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(4)) }
                }
            }
        }.joinToString("\n")

        // `search_index` is a standalone FTS5 table: ordering by a non-FTS column forces a
        // temp B-tree over every row, including each chapter's full `content`. Measured at
        // 2 452 ms against 62 ms per slice on a 2 656-chapter corpus.
        assertFalse(
            plan.contains("TEMP B-TREE"),
            "the slice query must not sort a materialised FTS5 table; plan was:\n$plan",
        )
        // Without this index the probe walks every chunk of the model, and the cost grows as
        // the backfill proceeds (405 ms per slice at 2 000 chapters left, 890 ms at 40).
        assertTrue(
            plan.contains("idx_chunks_book_chapter_model"),
            "the NOT EXISTS probe must use the composite index; plan was:\n$plan",
        )
    }

    @Test
    fun `backfill slice returns chapters in import order and skips indexed ones`() = runBlocking {
        database.withConnection { conn ->
            listOf("ch1", "ch2", "ch3").forEachIndexed { i, id ->
                conn.prepareStatement(
                    "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                        "VALUES ('b1', ?, ?, 'Chapter', " +
                        "'a chapter with comfortably more than eight words in its body')"
                ).use { stmt ->
                    stmt.setString(1, id)
                    stmt.setInt(2, i)
                    stmt.executeUpdate()
                }
            }
        }
        repository.storeChunks(listOf(chunk("c2", chapterId = "ch2")), "model-a", 2, listOf(vector(1f, 0f)))

        assertEquals(
            listOf("ch1", "ch3"),
            repository.chaptersMissingVectors("model-a", 40).map { it.chapterId },
        )
    }

    /**
     * A chapter under `TextChunker.MIN_CHUNK_WORDS` can never produce a chunk, so offering it
     * as backfill work means the backfill never finishes and the progress readout never reaches
     * its total. One real 2 656-chapter library stalled at "2 240 of 2 656" this way.
     */
    @Test
    fun `chapters too short to chunk are not offered as work or counted in the total`() = runBlocking {
        database.withConnection { conn ->
            listOf(
                "chLong" to "this chapter has well over the eight words a chunk needs to exist",
                "chShort" to "Part One",
                "chTiny" to "I",
            ).forEachIndexed { i, (id, content) ->
                conn.prepareStatement(
                    "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                        "VALUES ('b1', ?, ?, 'Chapter', ?)"
                ).use { stmt ->
                    stmt.setString(1, id)
                    stmt.setInt(2, i)
                    stmt.setString(3, content)
                    stmt.executeUpdate()
                }
            }
        }

        assertEquals(1, repository.searchableChapterCount())
        assertEquals(
            listOf("chLong"),
            repository.chaptersMissingVectors("model-a", 40).map { it.chapterId },
        )

        // And once the long one is done, there is genuinely nothing left — the backfill can
        // report itself complete instead of re-offering the short chapters forever.
        repository.storeChunks(
            listOf(chunk("cLong", chapterId = "chLong")), "model-a", 2, listOf(vector(1f, 0f)),
        )
        assertTrue(repository.chaptersMissingVectors("model-a", 40).isEmpty())
        assertEquals(1, repository.progress("model-a").indexedChapters)
        assertEquals(1, repository.progress("model-a").totalChapters)
    }

    /**
     * `chapter_id` is **book-local**, not globally unique.
     *
     * `EpubParser` sets `Chapter.id = manifestItem.id` (`EpubParser.kt:428`), which is the EPUB
     * manifest identifier — `ch1`, `html_0`, `item_12`. Nothing stops two books from using the
     * same one, and in practice every book produced by the same tool reuses the same scheme, so
     * a real 22-book library collides thousands of times.
     *
     * Keying "is this chapter indexed?" on `chapter_id` alone therefore makes the second book's
     * chapter look already-done the moment the first one is indexed. The consequences are not
     * cosmetic:
     *
     * - the slice query returns nothing, so `backfillSlice` reports `complete` and the worker
     *   exits immediately — which is why the "Resume indexing" button did nothing at all;
     * - `COUNT(DISTINCT chapter_id)` is smaller than the number of indexed chapters, so the
     *   readout stuck at "2 246 of 2 561" and could never reach its total;
     * - worst of all, those chapters are *silently never embedded*, so part of the library is
     *   invisible to semantic search while the UI claims the index is progressing.
     */
    @Test
    fun `chapters that share an id across two books are tracked separately`() = runBlocking {
        database.withConnection { conn ->
            // Same chapter_id, two different books — exactly what the EPUB manifest gives us.
            listOf("b1" to 0, "b2" to 1).forEach { (bookId, spine) ->
                conn.prepareStatement(
                    "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                        "VALUES (?, 'ch1', ?, 'Chapter', " +
                        "'a chapter with comfortably more than eight words in its body')"
                ).use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.setInt(2, spine)
                    stmt.executeUpdate()
                }
            }
        }

        // Only the first book's chapter is indexed.
        repository.storeChunks(
            listOf(chunk("c-b1", bookId = "b1", chapterId = "ch1")),
            "model-a", 2, listOf(vector(1f, 0f)),
        )

        assertEquals(
            listOf("b2"),
            repository.chaptersMissingVectors("model-a", 40).map { it.bookId },
            "the second book's chapter must still be offered as work",
        )
        assertEquals(1, repository.progress("model-a").indexedChapters)
        assertEquals(2, repository.progress("model-a").totalChapters)

        // And once both are done the backfill can genuinely report itself complete.
        repository.storeChunks(
            listOf(chunk("c-b2", bookId = "b2", chapterId = "ch1")),
            "model-a", 2, listOf(vector(0f, 1f)),
        )
        assertTrue(repository.chaptersMissingVectors("model-a", 40).isEmpty())
        assertEquals(2, repository.progress("model-a").indexedChapters)
    }

    @Test
    fun `a model with no recorded recipe reports none`() {
        // The signal the backfill uses to decide it is looking at a fresh install.
        runBlocking { assertNull(repository.chunkRecipe("model-a")) }
    }

    @Test
    fun `recording a recipe is idempotent and readable`() {
        val recipe = chunkingRecipe(maxWords = 173, overlapWords = 50)
        runBlocking {
            repository.recordChunkRecipe("model-a", recipe)
            repository.recordChunkRecipe("model-a", recipe)
            assertEquals(recipe, repository.chunkRecipe("model-a"))
        }
    }

    @Test
    fun `a changed recipe is detectable per model`() {
        // The chunk window is not part of the model id, so this comparison is the only thing
        // standing between a window change and two generations of chunks in the same index.
        runBlocking {
            repository.recordChunkRecipe("model-a", chunkingRecipe(250, 50))
            repository.recordChunkRecipe("model-b", chunkingRecipe(173, 50))

            assertEquals(chunkingRecipe(250, 50), repository.chunkRecipe("model-a"))
            assertEquals(chunkingRecipe(173, 50), repository.chunkRecipe("model-b"))
            assertNotEquals(repository.chunkRecipe("model-a"), repository.chunkRecipe("model-b"))
        }
    }

    @Test
    fun `deleting a model's chunks clears its vectors and leaves other models alone`() {
        // The destructive half of the recipe check. It must be scoped to one model: switching
        // models is supposed to be lossless, and only the stale window's rows may go.
        runBlocking {
            repository.storeChunks(
                listOf(chunk("c-a", text = "stale window passage")),
                "model-a", 2, listOf(vector(0f, 1f)),
            )
            repository.storeChunks(
                listOf(chunk("c-b", text = "current window passage")),
                "model-b", 2, listOf(vector(1f, 0f)),
            )

            repository.deleteChunksForModel("model-a")

            assertEquals(0, repository.chunkCount("model-a"), "model-a's chunks must be gone")
            assertEquals(0, repository.progress("model-a").indexedChapters)
            assertEquals(1, repository.chunkCount("model-b"), "model-b must be untouched")
            assertEquals(1, repository.progress("model-b").indexedChapters)
        }
    }

    @Test
    fun `the recipe string changes only when a window parameter changes`() {
        // A recipe that drifted without the window changing would re-index the library on every
        // launch; one that failed to drift would serve two generations of chunks.
        assertEquals(chunkingRecipe(250, 50), chunkingRecipe(250, 50))
        assertNotEquals(chunkingRecipe(250, 50), chunkingRecipe(173, 50))
        assertNotEquals(chunkingRecipe(250, 50), chunkingRecipe(250, 25))
    }
}
