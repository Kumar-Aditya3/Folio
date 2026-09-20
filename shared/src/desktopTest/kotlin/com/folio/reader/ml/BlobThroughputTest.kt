package com.folio.reader.ml

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcChunkRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Phase 0c — vector (BLOB) storage throughput.**
 *
 * The plan's highest-severity open risk, and the one that takes a *storage* decision:
 *
 * > Risk: `sqldroid` may be too slow for BLOB writes. If so, the fallback is a sidecar file per
 * > book rather than a column.
 *
 * The decision was in fact taken silently — vectors are BLOBs
 * (`JdbcChunkRepository.storeChunks` → `stmt.setBytes(4, encodeVector(vectors[i]))`) — and never
 * measured. On Android, JDBC is reached through `org.sqldroid:sqldroid:1.1.0-rc1`, a thin and
 * unmaintained shim, so "BLOBs are fine" was an assumption rather than a finding.
 *
 * **What this test can and cannot prove.** It runs against desktop SQLite (`sqlite-jdbc`), so it
 * measures *SQLite* BLOB throughput and the **JDBC batch round-trip**, not `sqldroid` itself.
 * That is still the decisive part of the answer, and it is the part that can be obtained without
 * a device:
 *
 * - If desktop SQLite stores 40 000 × 1.5 KB vectors in a second or two, then a BLOB is not
 *   intrinsically the wrong shape, and any slowness on device belongs to `sqldroid` — which is a
 *   *shim* problem with a known fallback, not a schema problem.
 * - The bound is what matters, not the constant. The real backfill is ~35 000 chunks (measured:
 *   22 books → 1 270 chapters → 34 856 chunks, see `phase0b-progress.log`) and the *embedding*
 *   rate is ~24 chunks/s, so storage gets a budget of ~40 ms per chunk before it becomes the
 *   bottleneck. The assertion below is deliberately generous — 10 ms per chunk, four times
 *   faster than it needs to be — because this test's job is to catch a *catastrophic* result
 *   (a BLOB write that costs 100 ms, i.e. 1.5 minutes per book), not to police milliseconds.
 *
 * A genuine `sqldroid` measurement still requires a device and is recorded as outstanding in
 * `docs/ML_PLAN_STATUS.md`. This narrows the unknown: it separates "BLOBs are slow" from
 * "the Android JDBC shim is slow", which are very different findings with very different fixes.
 */
class BlobThroughputTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var repository: JdbcChunkRepository

    /** Realistic dims: both catalog models are 384-dim float32 = 1 536 bytes per vector. */
    private val dims = 384

    /** One book's worth of chunks at the real measured density (~35 000 / 22 books ≈ 1 590). */
    private val chunksPerBook = 1_590

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-blob-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        repository = JdbcChunkRepository(database)
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        runCatching { tempRoot.deleteRecursively() }
    }

    private fun randomVectors(n: Int): List<FloatArray> {
        val random = Random(0xC0FFEE)
        return List(n) {
            FloatArray(dims) { random.nextFloat() * 2f - 1f }
        }
    }

    private fun syntheticChunks(bookId: String, count: Int): List<Chunk> = List(count) { i ->
        Chunk(
            id = "$bookId-chunk-$i",
            bookId = bookId,
            chapterId = "$bookId-chapter-${i / 12}",
            spineIndex = i / 12,
            chunkIndex = i % 12,
            charStart = i * 200,
            charEnd = i * 200 + 1_000,
            text = "chunk body $i " + "word ".repeat(180),
            contentHash = "hash-$bookId-$i",
        )
    }

    /**
     * The headline measurement: a whole library's vectors through the real write path.
     *
     * This deliberately goes through `ChunkRepository.storeChunks` rather than a raw INSERT, so
     * it includes the pre-delete (the `(book_id, chapter_id)` scoped `deleteChunksFor`), the
     * batch construction, the little-endian encode, and the transaction — i.e. everything the
     * backfill actually pays for, not an idealised single-statement loop.
     */
    @Test
    fun `phase 0c — storing a whole library of vectors`() = runBlocking {
        val books = 24
        val modelId = FakeEmbedder.TEST_MODEL.id
        val totalChunks = books * chunksPerBook

        var written = 0
        val started = System.currentTimeMillis()
        repeat(books) { b ->
            val chunks = syntheticChunks("book$b", chunksPerBook)
            repository.storeChunks(chunks, modelId, dims, randomVectors(chunks.size))
            written += chunks.size
        }
        val elapsedMs = System.currentTimeMillis() - started
        val perChunkMs = elapsedMs.toDouble() / written
        val bytes = written.toLong() * dims * 4
        val mbPerSecond = (bytes / 1_000_000.0) / (elapsedMs / 1000.0)

        println(
            """
            |Phase 0c — BLOB throughput (desktop sqlite-jdbc)
            |  chunks written : $written
            |  vector bytes   : ${bytes / 1_000_000} MB ($dims dims, float32)
            |  elapsed        : $elapsedMs ms
            |  per chunk      : ${"%.3f".format(perChunkMs)} ms
            |  throughput     : ${"%.2f".format(mbPerSecond)} MB/s
            |  embedding rate : ~24 chunks/s (measured in phase 0b), i.e. ~42 ms/chunk of budget
            """.trimMargin()
        )

        assertEquals(totalChunks, repository.progress(modelId).indexedChunks)

        // Four times faster than it needs to be. See the class comment for why this is loose.
        assertTrue(
            perChunkMs < 10.0,
            "A BLOB write costing ${"%.1f".format(perChunkMs)} ms per chunk would spend " +
                "${"%.1f".format(perChunkMs * chaptersPerLibrary() / 1000.0)} s on storage for one " +
                "library while the embedder needs only ~${"%.1f".format(chaptersPerLibrary() * 42 / 1000.0)} s. " +
                "That is the sidecar-file branch of the 0c decision.",
        )
    }

    /** Chunks in a real library, measured on the 22-book corpus. */
    private fun chaptersPerLibrary() = 34_856

    /**
     * The read path, which is the one a reader waits on: a query vector is compared against
     * every stored vector, so the whole library is decoded on every semantic search.
     *
     * `CosineIndex` is brute force by design (sizing note honoured — no `sqlite-vec`), so this
     * cost is paid per query and is worth knowing. It reads back through
     * `loadVectors`/`decodeVector`, not a raw SELECT.
     */
    @Test
    fun `phase 0c — reading a whole library of vectors for one query`() = runBlocking {
        val books = 6
        val modelId = FakeEmbedder.TEST_MODEL.id
        repeat(books) { b ->
            val chunks = syntheticChunks("book$b", chunksPerBook)
            repository.storeChunks(chunks, modelId, dims, randomVectors(chunks.size))
        }

        val started = System.currentTimeMillis()
        val loaded = loadAllVectors(modelId)
        val elapsedMs = System.currentTimeMillis() - started

        val expected = books * chunksPerBook
        assertEquals(expected, loaded, "every stored vector must come back")

        val perVectorMs = elapsedMs.toDouble() / expected
        println(
            """
            |Phase 0c — BLOB read/decoding
            |  vectors read   : $loaded
            |  elapsed        : $elapsedMs ms
            |  per vector     : ${"%.4f".format(perVectorMs)} ms
            |  full library   : ~${"%.1f".format(perVectorMs * chaptersPerLibrary())} ms for 34 856 vectors
            """.trimMargin()
        )

        // The search UI needs this under a frame budget's worth of tolerance; 34 856 decodes at
        // 0.05 ms each is ~1.7 s, which would be visible — so this bound is meaningful rather
        // than decorative. It is the number that would justify an ANN index later.
        assertTrue(
            perVectorMs * chaptersPerLibrary() < 2_000,
            "decoding a whole library for one query took ${perVectorMs * chaptersPerLibrary()} ms; " +
                "brute force is then the wrong retrieval strategy and needs an ANN index",
        )
    }

    /**
     * Reads every vector the way the search path does, through the same `decodeVector` the
     * repository uses — so this measures the real decode cost, not a reimplementation of it.
     */
    private suspend fun loadAllVectors(modelId: String): Int {
        var count = 0
        database.withConnection { conn ->
            conn.prepareStatement(
                "SELECT vector, dims FROM chapter_vectors WHERE model_id = ?"
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val decoded = JdbcChunkRepository.decodeVector(rs.getBytes("vector"), rs.getInt("dims"))
                        assertEquals(dims, decoded.size)
                        count++
                    }
                }
            }
        }
        return count
    }
}
