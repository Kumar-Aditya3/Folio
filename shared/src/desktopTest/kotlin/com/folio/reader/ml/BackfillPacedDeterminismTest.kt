package com.folio.reader.ml

import com.folio.reader.database.ChunkMeta
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcChunkRepository
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lock on what the sweep's rate gate is allowed to change: **when** it works, never **what** it
 * computes.
 *
 * [SweepPace] is a throttle, and a throttle that altered the index would be worse than the jank it
 * cures — the reader would get different search results, different echoes and a different map
 * depending on whether they happened to be looking at the screen while it built. So the claim is
 * made structurally and then asserted: the only thing the pace does is call `embed` the same number
 * of times, with the same chunk lists in the same order, and sleep between them.
 *
 * Two independent temp databases run the same corpus, and both the *encoder calls* and the *stored
 * rows* are compared. The call list is what proves the batch widths did not shift — a throttle that
 * quietly repacked batches could still store plausible vectors while changing which text lands in
 * which row.
 *
 * Free of ONNX, a model file and a device: [FakeEmbedder] is the deterministic hashing vectoriser,
 * so any difference found here is a difference in the sweep, not in a quantised encoder.
 */
class BackfillPacedDeterminismTest {

    private data class Run(
        /** Every forward pass requested, in order — the batch widths and their sequence. */
        val passes: List<Pair<List<String>, EmbedKind>>,
        /** The library as stored, sorted so the comparison is about content, not SQL row order. */
        val stored: List<Pair<ChunkMeta, List<Float>>>,
        /** The holds the pace took, so "it paused" is proven without timing anything. */
        val pauses: List<Long>,
    )

    /** Shares one call list across sessions, because the factory opens a fresh embedder per slice. */
    private class RecordingFactory(
        override val model: EmbeddingModel,
        private val calls: MutableList<Pair<List<String>, EmbedKind>>,
    ) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder =
            FakeEmbedder(model, calls = calls)
    }

    /**
     * Seeds a fixed corpus into its own temp database and sweeps it with [pace], recording every
     * hold [pace] takes into [pauses].
     */
    private suspend fun seedAndSweep(pace: SweepPace, pauses: MutableList<Long>): Run {
        val root = createTempDir("folio-paced-")
        val database = Database(File(root, "folio.db").absolutePath)
        try {
            // 3 books x 20 chapters of 60 words. The fake model's 64-token ceiling gives a window of
            // a few dozen words, so a chapter yields more than one chunk and a slice more than one
            // forward pass — the only shape that can show a pause count at all.
            repeat(3) { b ->
                repeat(20) { c ->
                    val content = (1..60).joinToString(" ") { "w${b}_${c}_$it" }
                    database.withConnection { conn ->
                        conn.prepareStatement(
                            "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                                "VALUES (?, ?, ?, 'Chapter', ?)"
                        ).use { stmt ->
                            stmt.setString(1, "book$b")
                            stmt.setString(2, "ch$c")
                            stmt.setInt(3, c)
                            stmt.setString(4, content)
                            stmt.executeUpdate()
                        }
                    }
                }
            }

            val repository = JdbcChunkRepository(database)
            val passes = mutableListOf<Pair<List<String>, EmbedKind>>()
            val indexer = EmbeddingIndexer(repository, RecordingFactory(FakeEmbedder.TEST_MODEL, passes))

            var indexed = 0
            var rounds = 0
            while (rounds < 50) {
                rounds++
                val slice = indexer.backfillSlice(40, pace = pace)
                indexed += slice.indexedChapters
                if (slice.complete || slice.indexedChapters == 0) break
            }
            assertEquals(60, indexed, "the sweep must still index the whole corpus, paced or not")

            val stored = repository
                .loadVectorMetadata(FakeEmbedder.TEST_MODEL.id, FakeEmbedder.TEST_MODEL.dims)
                .map { (meta, vector) -> meta to vector.toList() }
                .sortedWith(compareBy({ it.first.bookId }, { it.first.chapterId }, { it.first.charStart }))

            return Run(passes.toList(), stored, pauses.toList())
        } finally {
            runCatching { database.close() }
            root.deleteRecursively()
        }
    }

    /** A paced run whose holds are recorded, so [Run.pauses] and the pace agree by construction. */
    private suspend fun pacedRun(readerInApp: Boolean): Run {
        val pauses = mutableListOf<Long>()
        return seedAndSweep(
            SweepPace(readerInApp = { readerInApp }, sleep = { pauses.add(it) }),
            pauses,
        )
    }

    @Test
    fun `a paced sweep asks the encoder for exactly the same passes`() = runBlocking {
        val unpaced = seedAndSweep(SweepPace.None, mutableListOf())
        val paced = pacedRun(readerInApp = true)

        assertTrue(
            unpaced.passes.size > 2,
            "a corpus that produces one pass cannot prove anything about batching",
        )
        assertEquals(
            unpaced.passes,
            paced.passes,
            "the rate gate must not repack, reorder, re-split or add to a batch",
        )
    }

    @Test
    fun `a paced sweep stores byte-identical vectors`() = runBlocking {
        val unpaced = seedAndSweep(SweepPace.None, mutableListOf())
        val paced = pacedRun(readerInApp = true)

        assertEquals(unpaced.stored.size, paced.stored.size, "the same rows must exist")
        assertTrue(unpaced.stored.isNotEmpty())
        unpaced.stored.forEachIndexed { i, (meta, vector) ->
            val (otherMeta, otherVector) = paced.stored[i]
            assertEquals(meta, otherMeta, "chunk boundaries must not move at row $i")
            assertEquals(vector, otherVector, "vector at row $i (${meta.id}) must be identical")
        }
    }

    @Test
    fun `the pace pauses once per forward pass while the reader is in the app`() = runBlocking {
        val paced = pacedRun(readerInApp = true)

        // One hold per pass, including a flush's last one. Short means the gate has started skipping
        // work; over means it is sleeping for a pass that never ran.
        assertEquals(paced.passes.size, paced.pauses.size, "every forward pass must be followed by exactly one hold")
        assertTrue(paced.pauses.isNotEmpty(), "a sweep with the reader in the app must pause at all")
        assertTrue(paced.pauses.all { it >= SweepPace.MIN_PAUSE_MS }, "no pause may fall under the usable-gap floor")
    }

    @Test
    fun `the pace stays out of the way when the reader has left`() = runBlocking {
        val away = pacedRun(readerInApp = false)

        // Away is where the sweep runs flat out, and the ColorOS evidence in EmbeddingBackfillWorker
        // is why: yielding inside a frozen process buys nothing, it just makes the index slower.
        assertTrue(away.pauses.isEmpty(), "away from the reader the sweep must not pause")
        assertTrue(away.passes.isNotEmpty(), "and it must still do the work")
    }
}
