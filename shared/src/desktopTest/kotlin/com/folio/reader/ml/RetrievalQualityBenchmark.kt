package com.folio.reader.ml

import com.folio.reader.database.ChapterIndexEntry
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.importer.BookImporter
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 0b — retrieval quality on the real library. This is the measurement that decides
 * whether the whole semantic-search project is worth building, and which model it uses.
 *
 * Compares, on the same corpus and the same queries:
 *
 * | ranker | what it answers |
 * |---|---|
 * | BM25 AND | today's shipped behaviour (`tokenizeForFts` ANDs every term) |
 * | BM25 OR | a fair lexical baseline — what BM25 *could* do for a natural-language query |
 * | MiniLM | English embeddings, 22 MB int8 |
 * | e5-small | multilingual embeddings, 113 MB int8 |
 * | RRF (OR + MiniLM) | does hybrid actually beat lexical alone? |
 *
 * The BM25-OR row exists because measuring only against today's AND behaviour would
 * flatter the hybrid: an AND-of-prefixes query returns almost nothing for a natural
 * language paraphrase, so "hybrid beats BM25" would be true but meaningless. The decision
 * that matters is hybrid versus a *competently implemented* lexical baseline.
 *
 * Metrics are chapter-level, because that is the granularity the app's FTS5 index and its
 * search UI already work at: embeddings rank chunks, and each chunk is mapped to its
 * owning chapter so both rankers produce a comparable list of chapters.
 *
 * Kill criterion from ML_PLAN.md: if RRF is not clearly better than BM25 alone, stop.
 */
class RetrievalQualityBenchmark {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var importer: BookImporter
    private lateinit var searchRepository: JdbcSearchRepository

    private val corpusDir = File("testbooks").absoluteFile
        .takeIf { it.isDirectory } ?: File("..", "testbooks")

    /** `-Dfolio.bench.books=N` limits the corpus; default is everything present. */
    private val bookLimit: Int = System.getProperty("folio.bench.books")?.toIntOrNull() ?: Int.MAX_VALUE

    /** `-Dfolio.bench.e5=false` skips the 113 MB multilingual row. */
    private val runE5: Boolean = System.getProperty("folio.bench.e5")?.toBooleanStrictOrNull() ?: true

    /** Wall-clock origin for the progress log. */
    private val startedAt: Long = System.currentTimeMillis()

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-bench-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        searchRepository = JdbcSearchRepository(database)
        importer = BookImporter(
            platform = platform,
            epubParser = com.folio.reader.epub.EpubParser(platform),
            bookRepository = JdbcBookRepository(database),
            positionRepository = JdbcReadingPositionRepository(database),
            searchIndexer = SearchIndexer(searchRepository),
            hashUtil = platform.hasher,
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        runCatching { tempRoot.deleteRecursively() }
    }

    @Test
    fun `phase 0b — retrieval quality against the shipped BM25 path`() = runBlocking {
        val miniLmModel = TestModelFixtures.miniLmModel()
        val miniLmVocab = TestModelFixtures.miniLmVocab()
        assumeTrue(
            miniLmModel != null && miniLmVocab != null,
            TestModelFixtures.missingHint("MiniLM int8 model + vocab"),
        )

        // ---------- 1. Corpus ----------
        val books = corpusDir.listFiles { f -> f.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name }?.take(bookLimit) ?: emptyList()
        assumeTrue(books.isNotEmpty(), "no EPUBs found in $corpusDir")

        runCatching { File(reportDir(), "phase0b-progress.log").writeText("") }
        progress(
            "start: ${books.size} books, e5=${if (runE5) "on" else "off"}, " +
                "bookLimit=${if (bookLimit == Int.MAX_VALUE) "all" else bookLimit.toString()}"
        )

        val importStart = System.currentTimeMillis()
        var imported = 0
        for (file in books) {
            val result = importer.importEpub(file.absolutePath)
            if (result.isSuccess) imported++
        }
        val importMs = System.currentTimeMillis() - importStart
        progress("import done: $imported/${books.size} books in ${importMs} ms")

        // ---------- 2. Chapter text, straight out of the FTS5 index ----------
        // Reading it back rather than re-extracting guarantees the benchmark sees exactly
        // the text the shipped search path indexes.
        val chapters = database.withConnection { conn ->
            conn.prepareStatement(
                "SELECT book_id, chapter_id, spine_index, title, content FROM search_index"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ChapterText(
                                    bookId = rs.getString("book_id"),
                                    chapterId = rs.getString("chapter_id"),
                                    spineIndex = rs.getInt("spine_index"),
                                    title = rs.getString("title"),
                                    content = rs.getString("content"),
                                )
                            )
                        }
                    }
                }
            }
        }
        assertTrue(chapters.isNotEmpty(), "import produced no indexed chapters")

        // ---------- 3. Chunk ----------
        val chunkStart = System.currentTimeMillis()
        val chunks = chapters.flatMap { chapter ->
            TextChunker.chunk(
                text = chapter.content,
                bookId = chapter.bookId,
                chapterId = chapter.chapterId,
                spineIndex = chapter.spineIndex,
            )
        }
        val chunkMs = System.currentTimeMillis() - chunkStart
        assertTrue(chunks.isNotEmpty(), "chunking produced nothing")
        progress("chunking done: ${chapters.size} chapters -> ${chunks.size} chunks in ${chunkMs} ms")

        // ---------- 4. Gold labels ----------
        // Keyed by (bookId, chapterId), not chapterId alone. `chapter_id` is the EPUB manifest
        // id, which is only unique within a book: measured on this 22-book corpus, 1272
        // chapters carry just 847 distinct ids — `titlepage` alone appears in 13 books — so
        // keying on it would let a hit in one book satisfy a gold label in another and inflate
        // every ranker's score.
        val normalizedContent = chapters.associate { c ->
            chapterKey(c.bookId, c.chapterId) to RetrievalGoldSet.normalizeForMatch(c.content)
        }
        val gold = RetrievalGoldSet.queries.map { q ->
            // A negative has no needle, and `contains("")` is true of every chapter — so this
            // branch is load-bearing, not a shortcut. Without it every negative would be "found" in
            // every chapter and would score as a perfect hit.
            if (!q.hasTarget) return@map q to emptySet<String>()
            val needle = RetrievalGoldSet.normalizeForMatch(q.needle)
            val owners = normalizedContent.filterValues { it.contains(needle) }.keys
            q to owners
        }
        val missing = gold.filter { it.first.hasTarget && it.second.isEmpty() }
        assertTrue(
            missing.isEmpty(),
            "gold needles not found in the indexed corpus — the fixture is broken, not the retrieval:\n" +
                missing.joinToString("\n") { "  '${it.first.needle.take(80)}'" },
        )
        progress(
            "gold labels resolved: ${RetrievalGoldSet.scored.size} scored queries, every needle found; " +
                "${RetrievalGoldSet.negatives.size} negatives with no target",
        )

        // ---------- 5. Embeddings ----------
        val minilm = OnnxEmbedder.wordPiece(
            EmbeddingModelCatalog.MINILM_L6_V2_INT8,
            miniLmModel!!,
            miniLmVocab!!,
            threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
            // Matches the app; see the Arctic call site below for why this is `true`.
            enableCpuMemArena = true,
        )
        val embedStart = System.currentTimeMillis()
        val miniLmVectors = embedAll(minilm, chunks.map { it.text }, "MiniLM-L6-v2")
        val embedMs = System.currentTimeMillis() - embedStart
        progress("MiniLM embedding done in ${embedMs} ms for ${chunks.size} chunks")

        val minilmIndex = CosineIndex(EmbeddingModelCatalog.MINILM_L6_V2_INT8.dims)
        chunks.forEachIndexed { i, c -> minilmIndex.add(c.id, miniLmVectors[i]) }
        val chunkById = chunks.associateBy { it.id }

        // ---------- 5b. Arctic-S, the model the Android build ships ----------
        //
        // This row exists because the app's relevance floor is a *cosine threshold*, and a threshold
        // is only meaningful against a particular model's distribution. Calibrating it on MiniLM and
        // shipping Arctic would be calibrating on the wrong cone. Arctic-S is also the wider window
        // (512 tokens against MiniLM's 256), so it is the model whose passages are *not* truncated by
        // the shared 250-word chunking — see the note in the report header.
        var arcticIndex: CosineIndex? = null
        var arcticEmbedMs = -1L
        val arcticModel = TestModelFixtures.arcticModel()
        val arcticVocab = TestModelFixtures.arcticVocab()
        var arctic: OnnxEmbedder? = null
        if (arcticModel != null && arcticVocab != null) {
            progress("arctic starting: the model the device ships")
            val opened = OnnxEmbedder.wordPiece(
                EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8,
                arcticModel,
                arcticVocab,
                threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                // The arena is **on**, matching the app — a reversal of what this line used to say.
                // The old reasoning (a sweep sees thousands of distinct shapes, so an arena only
                // ever grows) was right about an *unbucketed* sweep and wrong as a remedy:
                // `WordPieceTokenizer` now rounds every batch's width up to a bucket, so a sweep
                // presents a handful of shapes and reuse is what keeps the peak small.
                //
                // Stated honestly: the arena was **never isolated** from the actual fix. The
                // 1.6 GB → 2.8 GB regression observed when the arena was truly disabled was measured
                // while the forward pass was still 62 rows wide — one whole chapter, the batching
                // bug — so it is confounded with that. What *is* measured is the combination: a pass
                // bounded to `EmbeddingIndexer.batch` rows plus bucketed widths held the device at a
                // flat ~610 MB across 60+ passes, against 4.38 GB in one unbounded pass. Arena-on is
                // the ORT default and the app's setting, so the benchmark matches the app; if the two
                // settings are ever separated, this is the line that should carry the measurement.
                //
                // Kept explicit rather than dropped for the default, because this is the benchmark's
                // statement of which ORT configuration it measured.
                enableCpuMemArena = true,
            )
            val started = System.currentTimeMillis()
            val vectors = embedAll(opened, chunks.map { it.text }, "snowflake-arctic-embed-s")
            arcticEmbedMs = System.currentTimeMillis() - started
            progress("arctic embedding done in ${arcticEmbedMs} ms for ${chunks.size} chunks")
            arcticIndex = CosineIndex(EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8.dims)
            chunks.forEachIndexed { i, c -> arcticIndex!!.add(c.id, vectors[i]) }
            arctic = opened
        } else {
            progress("arctic skipped: fixture missing (looked for arctic-s-quantized.onnx)")
        }

        // Multilingual row, only when its fixtures are present.
        //
        // The session is opened once and kept until both phases of the e5 row are done. It used
        // to be closed after the passage sweep and re-opened for the 20 query embeddings, which
        // re-paid the whole 118 MB session construction — model load, graph optimisation, arena
        // setup — to embed twenty sentences. The e5 row is already the dominant cost here.
        var e5Index: CosineIndex? = null
        var e5EmbedMs = -1L
        val e5Model = TestModelFixtures.e5Model()
        val e5TokenizerJson = TestModelFixtures.e5TokenizerJson()
        var e5: OnnxEmbedder? = null
        var e5Adapter: DjlTokenizerAdapter? = null
        if (!runE5) {
            progress("e5 skipped (-Dfolio.bench.e5=false) — the BM25/MiniLM rows still answer the kill criterion")
        }
        if (runE5 && e5Model != null && e5TokenizerJson != null) {
            progress("e5 starting: the dominant cost of this benchmark")
            val adapter = DjlTokenizerAdapter.fromTokenizerJson(e5TokenizerJson)
            val opened = OnnxEmbedder(
                descriptor = EmbeddingModelCatalog.MULTILINGUAL_E5_SMALL_INT8,
                modelFile = e5Model,
                tokenizer = adapter,
                threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                enableCpuMemArena = false,
            )
            val started = System.currentTimeMillis()
            val vectors = embedAll(opened, chunks.map { it.text }, "multilingual-e5-small")
            e5EmbedMs = System.currentTimeMillis() - started
            progress(
                "e5 embedding done in ${e5EmbedMs} ms for ${chunks.size} chunks " +
                    "(session init ${opened.sessionInitMillis} ms)"
            )
            e5Index = CosineIndex(EmbeddingModelCatalog.MULTILINGUAL_E5_SMALL_INT8.dims)
            chunks.forEachIndexed { i, c -> e5Index!!.add(c.id, vectors[i]) }
            e5 = opened
            e5Adapter = adapter
        }

        // ---------- 6. Rankers ----------
        // Each ranker is materialised as query -> ordered chapter ids before any scoring,
        // so every ranker is evaluated over exactly the same 20 queries and the models are
        // loaded once rather than per query.
        val queries = gold.map { it.first }
        val goldByQuery: Map<String, Set<String>> = queries.indices.associate { i ->
            queries[i].query to gold[i].second
        }

        val ranked = LinkedHashMap<String, Map<String, List<String>>>()

        val bm25AndRanked = HashMap<String, List<String>>()
        val bm25OrRanked = HashMap<String, List<String>>()
        for (q in queries) {
            bm25AndRanked[q.query] = rankBm25And(q.query)
            bm25OrRanked[q.query] = rankBm25Or(q.query)
        }
        ranked["BM25 (AND, shipped)"] = bm25AndRanked
        ranked["BM25 (OR, fair)"] = bm25OrRanked

        val miniLmRanked = HashMap<String, List<String>>()
        val miniLmScored = HashMap<String, List<Pair<String, Float>>>()
        for (q in queries) {
            // One embed call, reused for both the ranking and the score readout. `rankSemantic` and
            // `rankSemanticScored` would otherwise embed every query twice.
            val scored = rankSemanticScored(q.query, minilm, minilmIndex, chunkById)
            miniLmScored[q.query] = scored
            miniLmRanked[q.query] = scored.map { it.first }
        }
        ranked["MiniLM-L6-v2"] = miniLmRanked

        val arcticRanked = HashMap<String, List<String>>()
        val arcticScored = HashMap<String, List<Pair<String, Float>>>()
        if (arcticIndex != null) {
            val opened = arctic!!
            for (q in queries) {
                val scored = rankSemanticScored(q.query, opened, arcticIndex, chunkById)
                arcticScored[q.query] = scored
                arcticRanked[q.query] = scored.map { it.first }
            }
            ranked["snowflake-arctic-embed-s"] = arcticRanked
            opened.close()
            arctic = null
        }

        if (e5Index != null) {
            // Reuses the session opened for the passage sweep; see the note above.
            val opened = e5!!
            val e5Ranked = HashMap<String, List<String>>()
            for (q in queries) {
                e5Ranked[q.query] = rankSemantic(q.query, opened, e5Index, chunkById)
            }
            ranked["multilingual-e5-small"] = e5Ranked
            opened.close()
            e5 = null
        }

        // The lexical weight is a **sweep, not a value**, because the point of this report is to
        // choose it from evidence rather than from an argument. `lex x1.0` is the textbook
        // equal-weight fusion, and it is the row that produced 29% paraphrase recall against
        // MiniLM's 43% — fusion losing a third of the semantic ranker's paraphrase performance
        // while winning the lexical case outright. The lighter weights are the candidates.
        for (weight in listOf(1.0, 0.5, 0.25)) {
            ranked["RRF (BM25-AND + MiniLM, lex x$weight)"] = queries.associate { q ->
                q.query to RrfFusion.fusePair(
                    bm25AndRanked[q.query] ?: emptyList(),
                    miniLmRanked[q.query] ?: emptyList(),
                    lexicalWeight = weight,
                ).map { it.chunkId }
            }
        }
        ranked["RRF (BM25-OR + MiniLM, lex x1.0)"] = queries.associate { q ->
            q.query to RrfFusion.fusePair(
                bm25OrRanked[q.query] ?: emptyList(),
                miniLmRanked[q.query] ?: emptyList(),
                lexicalWeight = 1.0,
            ).map { it.chunkId }
        }
        // The same sweep against the shipping model, so the weight is chosen for Arctic and not
        // inherited from a model the app does not use.
        if (arcticIndex != null) {
            for (weight in listOf(1.0, 0.5, 0.25)) {
                ranked["RRF (BM25-AND + arctic, lex x$weight)"] = queries.associate { q ->
                    q.query to RrfFusion.fusePair(
                        bm25AndRanked[q.query] ?: emptyList(),
                        arcticRanked[q.query] ?: emptyList(),
                        lexicalWeight = weight,
                    ).map { it.chunkId }
                }
            }
        }
        if (e5Index != null) {
            val e5Ranked = ranked.getValue("multilingual-e5-small")
            ranked["RRF (BM25-OR + e5, lex x1.0)"] = queries.associate { q ->
                q.query to RrfFusion.fusePair(
                    bm25OrRanked[q.query] ?: emptyList(),
                    e5Ranked[q.query] ?: emptyList(),
                    lexicalWeight = 1.0,
                ).map { it.chunkId }
            }
        }

        val results: Map<String, List<QueryOutcome>> = ranked.mapValues { (_, byQuery) ->
            queries.map { q -> score(byQuery[q.query] ?: emptyList(), goldByQuery[q.query] ?: emptySet()) }
        }
        progress("rankers materialised: ${ranked.keys.joinToString(", ")}")

        val report = buildReport(
            books = books.size, imported = imported, chapters = chapters.size, chunks = chunks.size,
            distinctChapterIds = chapters.map { it.chapterId }.distinct().size,
            importMs = importMs, chunkMs = chunkMs, embedMs = embedMs, e5EmbedMs = e5EmbedMs,
            arcticEmbedMs = arcticEmbedMs,
            results = results, queries = queries,
            scoredByModel = buildMap {
                put("MiniLM-L6-v2", miniLmScored)
                if (arcticScored.isNotEmpty()) put("snowflake-arctic-embed-s", arcticScored)
            },
            goldByQuery = goldByQuery,
        )
        println(report)
        writeReport(report)
        progress("DONE — report written to .dbg/phase0/phase0b-retrieval.md")

        // The harness asserts only that it measured something. Go/no-go is a human call on
        // the numbers above, per ML_PLAN.md.
        // Release the native sessions *before* asserting, not after. `close()` is idempotent, so
        // the e5 block having already closed its session is fine; what this guards is the
        // failure path — a failed assertion here would otherwise strand both models' native
        // memory for the rest of the suite, which is how a 36 GB working set was reached.
        runCatching { e5?.close() }
        runCatching { e5Adapter?.close() }
        runCatching { minilm.close() }
        runCatching { arctic?.close() }

        // The harness asserts only that it measured something. Go/no-go is a human call on
        // the numbers above, per ML_PLAN.md.
        assertTrue(results.values.all { it.size == queries.size })
    }

    // ---------- ranking helpers ----------

    /**
     * Library-unique chapter identity.
     *
     * `chapter_id` alone is not unique — it is the EPUB manifest id, so it repeats across
     * books. Every ranker and the gold set must agree on this key or the comparison is
     * meaningless.
     */
    private fun chapterKey(bookId: String, chapterId: String): String = "$bookId\u0000$chapterId"

    private suspend fun rankBm25And(query: String): List<String> =
        searchRepository.search(query).first()
            .map { chapterKey(it.bookId, it.chapterId) }
            .distinct()

    /**
     * Lexical baseline with the terms OR-ed rather than AND-ed, which is what a
     * natural-language query needs. Terms are quoted so FTS5 treats punctuation safely.
     */
    private suspend fun rankBm25Or(query: String): List<String> {
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .distinct()
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "\"$it\"" }
        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT book_id, chapter_id FROM search_index
                WHERE search_index MATCH ?
                ORDER BY bm25(search_index)
                LIMIT 200
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, match)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(chapterKey(rs.getString("book_id"), rs.getString("chapter_id")))
                        }
                    }
                }
            }
        }.distinct()
    }

    /**
     * Embeds the query as a QUERY (not a passage — the e5 family is trained with distinct
     * prefixes) and maps the ranked chunks back to their owning chapters.
     */
    private suspend fun rankSemantic(
        query: String,
        embedder: Embedder,
        index: CosineIndex,
        chunkById: Map<String, Chunk>,
    ): List<String> {
        val vector = embedder.embed(listOf(query), EmbedKind.QUERY).first()
        return index.search(vector, limit = 200)
            .mapNotNull { hit ->
                chunkById[hit.chunkId]?.let { chunkKey -> chapterKey(chunkKey.bookId, chunkKey.chapterId) }
            }
            .distinct()
    }

    /**
     * The semantic ranking with the cosine score retained, one entry per chapter.
     *
     * Exists because the app does not use a bare ranking — `SemanticSearchRepository` applies a
     * relevance floor and a margin *over that floor* to the leader, and returns nothing at all when
     * the leader is not far enough above it. A benchmark that only measured ranks would report hits
     * the app would silently discard, so the scores have to be visible.
     *
     * A chapter's score is its **best** chunk's score. That is what the app compares: it ranks
     * chunks and reports the top one, so a chapter is as strong as its strongest passage rather than
     * as weak as its average.
     */
    private suspend fun rankSemanticScored(
        query: String,
        embedder: Embedder,
        index: CosineIndex,
        chunkById: Map<String, Chunk>,
    ): List<Pair<String, Float>> {
        val vector = embedder.embed(listOf(query), EmbedKind.QUERY).first()
        // `search` returns descending by score, so the first time a chapter is seen is its best
        // chunk — and insertion order into a LinkedHashMap then *is* the ranking order.
        val best = LinkedHashMap<String, Float>()
        for (hit in index.search(vector, limit = 200)) {
            val chunk = chunkById[hit.chunkId] ?: continue
            val key = chapterKey(chunk.bookId, chunk.chapterId)
            val prior = best[key]
            if (prior == null || hit.score > prior) best[key] = hit.score
        }
        return best.entries.map { it.key to it.value }
    }

    /**
     * Embeds every passage, reporting throughput as it goes.
     *
     * This loop is the whole cost of the benchmark — tens of thousands of chunks through a
     * transformer — so it is also the only place where a progress line is worth having.
     */
    private suspend fun embedAll(
        embedder: Embedder,
        texts: List<String>,
        label: String,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>(texts.size)
        val batches = texts.chunked(EMBED_BATCH)
        val started = System.currentTimeMillis()
        batches.forEachIndexed { i, batch ->
            out.addAll(embedder.embed(batch, EmbedKind.PASSAGE))
            val last = i == batches.lastIndex
            if (last || (i + 1) % PROGRESS_EVERY_BATCHES == 0) {
                val elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1)
                val perSecond = out.size * 1000.0 / elapsedMs
                val etaSeconds = if (perSecond <= 0.0) 0.0 else (texts.size - out.size) / perSecond
                progress(
                    "$label: ${out.size}/${texts.size} chunks, " +
                        "%.1f chunks/s, elapsed %.0fs, eta %.0fs".format(
                            perSecond, elapsedMs / 1000.0, etaSeconds,
                        )
                )
            }
        }
        return out
    }

    private fun score(ranked: List<String>, gold: Set<String>): QueryOutcome {
        val firstHit = ranked.indexOfFirst { it in gold }
        return QueryOutcome(
            hitAt5 = ranked.take(5).any { it in gold },
            hitAt10 = ranked.take(10).any { it in gold },
            reciprocalRank = if (firstHit >= 0 && firstHit < 10) 1.0 / (firstHit + 1) else 0.0,
        )
    }

    private data class QueryOutcome(val hitAt5: Boolean, val hitAt10: Boolean, val reciprocalRank: Double)

    // ---------- reporting ----------

    private fun buildReport(
        books: Int, imported: Int, chapters: Int, chunks: Int, distinctChapterIds: Int,
        importMs: Long, chunkMs: Long, embedMs: Long, e5EmbedMs: Long, arcticEmbedMs: Long,
        results: Map<String, List<QueryOutcome>>,
        queries: List<RetrievalGoldSet.GoldQuery>,
        scoredByModel: Map<String, Map<String, List<Pair<String, Float>>>>,
        goldByQuery: Map<String, Set<String>>,
    ): String = buildString {
        appendLine("# Phase 0b — retrieval quality on the real library")
        appendLine()
        appendLine("Corpus: $imported/$books books imported, $chapters chapters, $chunks chunks")
        appendLine(
            "Chapter ids: $chapters rows but only $distinctChapterIds distinct — the gap is " +
                "EPUB manifest ids reused across books (`titlepage`, `id12`, …), which is why " +
                "every chapter identity below is (bookId, chapterId) and not the id alone."
        )
        appendLine("Import ${importMs} ms | chunking ${chunkMs} ms | MiniLM embedding ${embedMs} ms" +
            (if (arcticEmbedMs >= 0) " | arctic embedding $arcticEmbedMs ms" else "") +
            (if (e5EmbedMs >= 0) " | e5 embedding $e5EmbedMs ms" else ""))
        appendLine()
        appendLine(
            "**Chunking note, read before comparing to the app.** These chunks are built with " +
                "`TextChunker`'s *defaults* (250 words, 50 overlap), while the app chunks at the " +
                "window the selected model derives — 173 words for MiniLM, 350 for Arctic-S. The " +
                "consequence is asymmetric and worth knowing: 250 words is ~330 WordPiece tokens, " +
                "which is **over MiniLM's 256-token ceiling**, so MiniLM's rows below describe " +
                "truncated passages and understate what the app achieves. Arctic-S's 512-token " +
                "ceiling is not exceeded at this width, so its rows are closer to production. " +
                "Comparing the two models here is fair; reading either as an absolute production " +
                "number is not."
        )
        appendLine()
        appendLine("| ranker | recall@5 | recall@10 | MRR@10 |")
        appendLine("|---|---|---|---|")
        for ((name, outcomes) in results) {
            val r5 = outcomes.count { it.hitAt5 }.toDouble() / outcomes.size
            val r10 = outcomes.count { it.hitAt10 }.toDouble() / outcomes.size
            val mrr = outcomes.map { it.reciprocalRank }.average()
            appendLine("| $name | ${pct(r5)} | ${pct(r10)} | ${"%.3f".format(mrr)} |")
        }
        appendLine()
        appendLine("## By query kind")
        appendLine()
        appendLine("| ranker | paraphrase recall@10 | lexical recall@10 |")
        appendLine("|---|---|---|")
        for ((name, outcomes) in results) {
            val para = outcomes.filterIndexed { i, _ -> queries[i].kind == RetrievalGoldSet.GoldQuery.Kind.PARAPHRASE }
            val lex = outcomes.filterIndexed { i, _ -> queries[i].kind == RetrievalGoldSet.GoldQuery.Kind.LEXICAL }
            appendLine(
                "| $name | ${pct(para.count { it.hitAt10 }.toDouble() / para.size)} | " +
                    "${pct(lex.count { it.hitAt10 }.toDouble() / lex.size)} |"
            )
        }
        appendLine()
        appendLine("## Per query")
        appendLine()
        appendLine("| query | kind | " + results.keys.joinToString(" | ") { it.take(18) } + " |")
        appendLine("|---|" + "---|".repeat(results.size + 1))
        queries.forEachIndexed { i, q ->
            val cells = results.values.joinToString(" | ") { outcomes ->
                val o = outcomes[i]
                when {
                    o.hitAt5 -> "5"
                    o.hitAt10 -> "10"
                    else -> "miss"
                }
            }
            appendLine("| ${q.query.take(52)} | ${q.kind.name.lowercase()} | $cells |")
        }
        appendLine()
        appendLine("Cells are the rank band of the first gold hit: 5 = top 5, 10 = top 10, miss = not found.")
        appendLine()
        appendLine("## The app's relevance floor, applied to the same scores")
        appendLine()
        // Read off the app's own constants rather than restated. The whole point of this section is
        // to report what the shipped threshold *does* to these rankings, so a copy that drifts from
        // the real value would make the section confidently wrong — and the value is expected to be
        // retuned from this very table, which is when a copy would go stale.
        val floor = SemanticSearchRepository.MIN_SIMILARITY
        val margin = SemanticSearchRepository.MIN_MARGIN_OVER_FLOOR
        appendLine(
            "Everything above is a *ranking*. The app never shows a ranking: " +
                "`SemanticSearchRepository` drops every candidate below `MIN_SIMILARITY = $floor` and " +
                "then requires the leader to clear that floor by `MIN_MARGIN_OVER_FLOOR = $margin`, " +
                "returning **nothing at all** otherwise. So a query whose best passage scores " +
                "${"%.2f".format(floor + 0.02f)} is a hit in the tables above and no result at all in " +
                "the app. Chapter score = its best chunk, which is the value the app compares."
        )
        for ((modelName, scored) in scoredByModel) {
            appendLine()
            appendLine("### $modelName")
            appendLine()
            appendLine("| query | kind | top-1 | best gold | app answers? |")
            appendLine("|---|---|---|---|---|")
            var goldAnswered = 0
            var goldInMarginBand = 0
            var goldBelowFloor = 0
            queries.forEachIndexed { i, q ->
                val ranking = scored.getOrDefault(q.query, emptyList())
                val top1 = ranking.firstOrNull()?.second
                val goldKeys = goldByQuery[q.query] ?: emptySet()
                val bestGold = ranking.filter { it.first in goldKeys }.maxOfOrNull { it.second }
                if (bestGold != null) {
                    when {
                        bestGold >= floor + margin -> goldAnswered++
                        bestGold >= floor -> goldInMarginBand++
                        else -> goldBelowFloor++
                    }
                }
                appendLine(
                    "| ${q.query.take(46)} | ${q.kind.name.lowercase()} | " +
                        "${top1?.let { "%.3f".format(it) } ?: "-"} | " +
                        "${bestGold?.let { "%.3f".format(it) } ?: "-"} | " +
                        "${if ((top1 ?: 0f) >= floor + margin) "yes" else "**no**"} |"
                )
            }
            appendLine()
            appendLine(
                "Of ${queries.size} queries: **$goldAnswered** have a gold passage the app would " +
                    "return; **$goldInMarginBand** have a gold passage above the floor that the " +
                    "*margin* discards (${"%.2f".format(floor)}–${"%.2f".format(floor + margin)}); " +
                    "**$goldBelowFloor** have none above the floor."
            )
            appendLine()
            appendLine(
                "The middle bucket is the one to read carefully: retrieval worked and the answer was " +
                    "thrown away by a threshold rather than by a model. If it is large, the margin is " +
                    "costing real recall and should be set from this distribution — the floor alone " +
                    "already makes \"no match\" reachable, which is what the margin was added for."
            )
        }

        // ---------- Can any threshold separate a match from a non-match? ----------
        //
        // The floor is one scalar applied to scores that are **not calibrated across queries**, so
        // the useful question is not "what value should it be" but "does a value exist". Two
        // distributions answer that, and they are different measurements on purpose:
        //
        //  - **positives** — the *best-gold* score, the strongest correct signal present. A query
        //    whose gold scores 0.2 is one the model genuinely cannot answer; no threshold recovers
        //    it, so it is a loss at every operating point and must not be counted as the floor's
        //    fault.
        //  - **negatives** — the *top-1* score, the strongest signal present when nothing is
        //    correct. A threshold has to exceed every one of these to refuse the query.
        //
        // This section exists because the shipped floor was calibrated on MiniLM while the device
        // runs Arctic-S, and the two put English prose in cones of different width — a threshold
        // read off one is not a threshold for the other. The observed failure was a nonsense query
        // ("motorcycle elevator refrigerator cryptocurrency") returning three results on device.
        appendLine()
        appendLine("## Can one threshold separate a match from a non-match?")
        appendLine()
        val negativeQueries = queries.filter { !it.hasTarget }
        if (negativeQueries.isEmpty()) {
            appendLine(
                "The gold set has no negative queries, so this cannot be answered — which is the " +
                    "only reason they were added. A floor needs both distributions."
            )
        } else {
            for ((modelName, scored) in scoredByModel) {
                val negTop1 = negativeQueries.mapNotNull { scored[it.query]?.firstOrNull()?.second }
                val posBest = queries.filter { it.hasTarget }.mapNotNull { q ->
                    val goldKeys = goldByQuery[q.query] ?: emptySet()
                    scored[q.query]?.filter { it.first in goldKeys }?.maxOfOrNull { it.second }
                }
                if (negTop1.isEmpty() || posBest.isEmpty()) continue
                val highestNegative = negTop1.max()
                val lowestPositive = posBest.min()
                appendLine()
                appendLine("### $modelName")
                appendLine()
                appendLine(
                    "**${negTop1.size} negatives**, top-1 ranging " +
                        "${"%.3f".format(negTop1.min())}–${"%.3f".format(highestNegative)} · " +
                        "**${posBest.size} positives**, best-gold ranging " +
                        "${"%.3f".format(lowestPositive)}–${"%.3f".format(posBest.max())}"
                )
                appendLine()
                appendLine(
                    if (highestNegative <= lowestPositive) {
                        "**A separating threshold exists**, anywhere in " +
                            "[${"%.3f".format(highestNegative)}, ${"%.3f".format(lowestPositive)}] — " +
                            "every nonsense query scores below every query that has an answer."
                    } else {
                        "**No threshold separates them.** The strongest nonsense query " +
                            "(${"%.3f".format(highestNegative)}) outscores the weakest real one " +
                            "(${"%.3f".format(lowestPositive)}), so the two distributions overlap " +
                            "and any single value either answers a nonsense query or refuses a real " +
                            "one. The table below is then the cost of each operating point rather " +
                            "than a search for a free one."
                    }
                )
                appendLine()
                appendLine("| threshold | positives answered | negatives refused |")
                appendLine("|---|---|---|")
                for (t in listOf(0.15f, 0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f)) {
                    val answered = posBest.count { it >= t }
                    val refused = negTop1.count { it < t }
                    appendLine(
                        "| ${"%.2f".format(t)} | " +
                            "$answered/${posBest.size} (${pct(answered.toDouble() / posBest.size)}) | " +
                            "$refused/${negTop1.size} (${pct(refused.toDouble() / negTop1.size)}) |"
                    )
                }
            }
            appendLine()
            appendLine(
                "Read the sweep as a trade, not a search for the best row: `positives answered` is " +
                    "recall and falls as the threshold rises, `negatives refused` is precision and " +
                    "rises. The shipped gate is " +
                    "`MIN_SIMILARITY + MIN_MARGIN_OVER_FLOOR` = " +
                    "${"%.2f".format(floor + margin)}."
            )
        }
    }

    private fun pct(v: Double) = "%.0f%%".format(v * 100)

    private fun writeReport(report: String) {
        runCatching {
            val file = File(reportDir(), "phase0b-retrieval.md")
            file.writeText(report)
            println("Phase 0b report written to $file")
        }
    }

    /**
     * Where the benchmark writes its evidence. Resolved relative to the repo root, because
     * Gradle runs a module's tests with the *module* directory as the working directory.
     */
    private fun reportDir(): File {
        val inModule = File(".dbg/phase0").absoluteFile
        val dir = if (inModule.parentFile.exists()) inModule else File("..", ".dbg/phase0").absoluteFile
        runCatching { dir.mkdirs() }
        return dir
    }

    /**
     * Records a line to `.dbg/phase0/phase0b-progress.log` **and** to stdout.
     *
     * The file is the point. Gradle buffers a test worker's stdout and flushes it only when the
     * test finishes, so a run killed at the tool timeout — which is how this benchmark died four
     * times — leaves nothing whatsoever to read. An append-only file is written as it goes and
     * survives the kill, which turns "killed at 36 minutes, learned nothing" into "killed at 36
     * minutes, was 71% through e5 at 340 chunks/s".
     */
    private fun progress(message: String) {
        val line = "[%6.1fs] %s".format((System.currentTimeMillis() - startedAt) / 1000.0, message)
        println("[0b] $line")
        runCatching { File(reportDir(), "phase0b-progress.log").appendText(line + "\n") }
    }

    private data class ChapterText(
        val bookId: String,
        val chapterId: String,
        val spineIndex: Int,
        val title: String,
        val content: String,
    )

    private companion object {
        const val EMBED_BATCH = 32

        /** Report embedding throughput every N batches — often enough to see the rate, rare enough not to spam. */
        const val PROGRESS_EVERY_BATCHES = 20
    }
}
