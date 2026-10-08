package com.folio.reader.ml

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcChunkRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.model.Chapter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B1: the relevance floor.
 *
 * The bug was not that the ranking was wrong — it was that there was no floor at all, so
 * `search` returned the nearest N chunks *unconditionally*. In embedding space there is
 * always a nearest chunk, and MiniLM puts all English prose in a narrow cone, so unrelated
 * passages score ~0.1-0.2 rather than 0: on the device the nonsense query
 * `motorcycle elevator refrigerator cryptocurrency` returned three confident hits from a
 * fantasy library. The reader was shown noise as a result and the UI could never say
 * "no match" on this path.
 *
 * These tests drive the *threshold*, so they need cosine scores they can choose — which
 * [FakeEmbedder]'s hashing vectoriser cannot express, because unrelated text there scores
 * ~0 and the floor would never fire. [ScriptedEmbedder] places the query vector and each
 * chunk vector at a picked angle instead, so "a hit at 0.42, a hit at 0.12" is stated
 * rather than hoped for.
 */
class SemanticRelevanceFloorTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var chunkRepository: JdbcChunkRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-floor-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        chunkRepository = JdbcChunkRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    // ---------- the floor itself ----------

    @Test
    fun `a query whose best hit sits on the noise band returns nothing`() = runBlocking {
        // Everything at ~0.18: unrelated prose against unrelated prose with MiniLM. Before the
        // fix this returned both chunks, and the reader saw two confident-looking results.
        val repository = repositoryWith(scores = listOf(0.18f, 0.11f))

        val hits = repository.search("motorcycle elevator refrigerator cryptocurrency", SearchMode.SEMANTIC)

        assertTrue(hits.isEmpty(), "expected no hits above the floor, got ${hits.map { it.score }}")
    }

    @Test
    fun `a hit above the floor but barely above it is not treated as an answer`() = runBlocking {
        // 0.38 clears MIN_SIMILARITY = 0.35 but not by MIN_MARGIN_OVER_FLOOR = 0.10, so it is a
        // nearest neighbour rather than a match. This is the case a bare floor would let
        // through: "there is something vaguely near your query" is not an answer.
        val repository = repositoryWith(scores = listOf(0.38f, 0.20f))

        val hits = repository.search("some vague phrase", SearchMode.SEMANTIC)

        assertTrue(hits.isEmpty(), "0.38 is within the margin of the floor and must not answer")
    }

    @Test
    fun `a genuine match above the floor answers, and keeps its good siblings`() = runBlocking {
        // The margin is over the *floor*, not over the runner-up, and that is deliberate: a
        // real query's top hits all describe the same subject and score within a few points,
        // so a runner-up margin would discard hits 3..20 of an entirely good search.
        val repository = repositoryWith(scores = listOf(0.72f, 0.68f, 0.61f, 0.55f))

        val hits = repository.search("a real question", SearchMode.SEMANTIC)

        assertEquals(4, hits.size, "all four are above the floor and the leader clears the margin")
        // Tolerance is one int8 quantisation step (1/127 ≈ 0.008), not float noise: the shipped
        // index is `QuantizedCosineIndex`, so a stored component carries at most that error. It is
        // far below the floor/margin (0.35 / 0.10) this test exercises, so the decision is
        // unaffected; only an exact-score assertion has to widen to match the real index.
        assertEquals(0.72f, hits.first().score, 0.01f)
    }

    @Test
    fun `hits below the floor are dropped even when a strong leader is present`() = runBlocking {
        // One strong hit and three on the noise band. The reader gets the hit, not the noise —
        // a floor applied only to the leader would still pad the list with unrelated passages.
        val repository = repositoryWith(scores = listOf(0.80f, 0.19f, 0.15f, 0.10f))

        val hits = repository.search("a real question", SearchMode.SEMANTIC)

        assertEquals(1, hits.size, "only the above-floor hit survives")
        // Widened to one int8 quantisation step for the shipped `QuantizedCosineIndex`; see the
        // sibling test above.
        assertEquals(0.80f, hits.first().score, 0.01f)
    }

    @Test
    fun `exact mode is untouched by the floor`() = runBlocking {
        // The floor is a *semantic* calibration — MiniLM's cone bias. BM25 already returns
        // nothing when nothing matches, so applying a cosine threshold to it would be
        // meaningless, and FULL_TEXT must keep returning whatever the index found.
        //
        // Indexed through the real `SearchIndexer`, because FULL_TEXT reads the FTS5 table and
        // not the chunk store: driving it through the embedding indexer would leave the index
        // empty and the test would fail for a reason unrelated to the floor.
        seedChapter("b1", "ch1", "a chapter mentioning motorcycle explicitly for the exact path")
        SearchIndexer(JdbcSearchRepository(database)).reindexBook(
            "b1",
            listOf(
                Chapter(
                    id = "ch1",
                    bookId = "b1",
                    title = "Chapter",
                    href = "ch1.xhtml",
                    spineIndex = 0,
                )
            ),
            mapOf("ch1.xhtml" to "<p>a chapter mentioning motorcycle explicitly for the exact path</p>"),
        )
        val repository = repositoryWith(scores = listOf(0.80f))

        val hits = repository.search("motorcycle", SearchMode.FULL_TEXT)

        assertEquals(1, hits.size, "FULL_TEXT delegates to BM25 and must not be floored")
    }

    // ---------- the three states the UI has to tell apart ----------

    @Test
    fun `canAnswer reports a loaded index so no-match and not-indexed stay distinct`() = runBlocking {
        // The floor created a new state — "indexed, but this query has no strong match" — and
        // the UI cannot say that without knowing whether anything was indexed at all. An empty
        // result used to mean both things at once.
        val indexed = repositoryWith(scores = listOf(0.18f))
        assertTrue(indexed.canAnswer(), "vectors exist for this model, so this repository can answer")

        // A *different* model id, because vectors are keyed by model: this is what a model the
        // reader has downloaded but not yet indexed looks like, and it must not be reported as
        // "no strong match".
        val otherModel = EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8
        val unindexed = SemanticSearchRepository(
            searchRepository = JdbcSearchRepository(database),
            chunkRepository = chunkRepository,
            embedderFactory = ScriptedFactoryFor(otherModel, emptyList()),
        )
        assertTrue(
            !unindexed.canAnswer(),
            "nothing indexed for this model — must not be mistaken for 'no strong match'",
        )
    }

    // ---------- harness ----------

    /**
     * Builds a repository over one seeded chapter whose chunks score exactly [scores].
     *
     * Three fixture constraints, each learned by getting it wrong first:
     *
     * - The chapter must clear `TextChunker.MIN_CHUNK_WORDS = 8` or it yields **zero chunks
     *   ever**, and the test passes by having nothing to find rather than by the floor working.
     * - Passages must be long enough that the chunker actually splits. With
     *   `DEFAULT_MAX_WORDS = 250` and `DEFAULT_OVERLAP_WORDS = 50` the stride is 200 words, so
     *   a chapter of `N` words yields roughly `N / 200` chunks. A short fixture collapses into a
     *   *single* chunk and then reports one hit for four scores — which looks like a ranking
     *   bug and is really a fixture that never made four chunks.
     * - Chunk text must **differ per chunk**. A chunk id is a content hash, so repeated
     *   identical filler collapses onto one id and the index holds a single entry.
     *
     * The per-passage word counts are what set the chunk count, so they are computed from the
     * chunker's own constants rather than left as a magic number: four passages at
     * `maxWords + 20` words give 1084 words, i.e. chunk starts at 0/200/400/600/800/1000 — six
     * chunks, of which the first four carry the script and the last two fall past it to the
     * below-floor default. Asserting four hits therefore tests the floor, not the arithmetic.
     */
    private suspend fun repositoryWith(scores: List<Float>): SemanticSearchRepository {
        val text = buildString {
            repeat(scores.size) { i ->
                append("passage$i ")
                // Past DEFAULT_MAX_WORDS so this passage closes its own chunk before the next
                // one starts. The overlap then makes the following chunk begin inside this one.
                repeat(TextChunker.DEFAULT_MAX_WORDS + 20) { w ->
                    append("w${(i * 31 + w) % 97} ")
                }
            }
        }
        seedChapter("b1", "ch1", text)
        // Embed through the real pipeline so the vectors the query ranks against are the ones
        // `storeChunks` wrote, not hand-placed rows.
        EmbeddingIndexer(chunkRepository, ScriptedFactory(scores)).indexChapters(
            "b1",
            listOf(com.folio.reader.database.ChapterIndexEntry("ch1", 0, "Chapter", text)),
        )
        return SemanticSearchRepository(
            searchRepository = JdbcSearchRepository(database),
            chunkRepository = chunkRepository,
            embedderFactory = ScriptedFactory(scores),
        )
    }

    private suspend fun seedChapter(bookId: String, chapterId: String, content: String) {
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                    "VALUES (?, ?, 0, 'Chapter', ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setString(3, content)
                stmt.executeUpdate()
            }
        }
    }

    private class ScriptedFactory(
        private val scores: List<Float>,
        private val descriptor: EmbeddingModel = EmbeddingModelCatalog.MINILM_L6_V2_INT8,
    ) : EmbedderFactory {
        override val model: EmbeddingModel get() = descriptor
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder =
            ScriptedEmbedder(descriptor, scores)
    }

    /** Names the model explicitly, for the "nothing indexed *for this model*" case. */
    private class ScriptedFactoryFor(
        override val model: EmbeddingModel,
        private val scores: List<Float>,
    ) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder =
            ScriptedEmbedder(model, scores)
    }

    /**
     * Places every text at a chosen cosine to a fixed query axis.
     *
     * For a target cosine `c` in [0, 1], the vector `(c, sqrt(1 - c^2), 0, ...)` scores exactly
     * `c` against the query `(1, 0, 0, ...)`. That makes the score a decision rather than an
     * accident of the fixture, which is the only way to test a threshold.
     *
     * Chunk vectors walk the script in order; a query is always the axis itself. The two are
     * distinguished by [EmbedKind], which is exactly how the real embedder distinguishes them —
     * so the fixture cannot accidentally make a query score against itself.
     */
    private class ScriptedEmbedder(
        override val model: EmbeddingModel,
        private val scores: List<Float>,
    ) : Embedder {
        private val cursor = AtomicInteger(0)

        override suspend fun embed(texts: List<String>, kind: EmbedKind): List<FloatArray> =
            texts.map {
                val dims = model.dims
                val vector = FloatArray(dims)
                if (kind == EmbedKind.QUERY) {
                    vector[0] = 1f
                } else {
                    val index = cursor.getAndIncrement()
                    // Past the script the chunk still gets a vector — at the floor-minus-a-bit
                    // band — so a fixture bug shows up as "nothing found" rather than as an
                    // exception masking the assertion.
                    val target = scores.getOrElse(index) { 0.10f }.coerceIn(0f, 1f)
                    vector[0] = target
                    val orthogonal = sqrt(1f - target * target)
                    if (dims > 1) vector[1] = orthogonal
                }
                vector
            }
    }
}
