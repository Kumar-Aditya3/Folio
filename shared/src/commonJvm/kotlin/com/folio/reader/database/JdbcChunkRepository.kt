package com.folio.reader.database

import com.folio.reader.ml.Chunk
import com.folio.reader.ml.TextChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.PreparedStatement

/**
 * JDBC implementation of [ChunkRepository], shared by Android and desktop.
 *
 * Vectors are float32 little-endian BLOBs. Little-endian is fixed explicitly rather than
 * using the platform's native order: the same database can be produced on one machine and
 * read on another (desktop backup -> Android restore), and a native-order blob would
 * silently decode as garbage.
 */
class JdbcChunkRepository(private val db: Database) : ChunkRepository {

    override suspend fun storeChunks(
        chunks: List<Chunk>,
        modelId: String,
        dims: Int,
        vectors: List<FloatArray>,
    ) {
        require(chunks.size == vectors.size) {
            "chunk/vector count mismatch: ${chunks.size} vs ${vectors.size}"
        }
        if (chunks.isEmpty()) return

        db.withTransaction { conn ->
            // A chapter's chunk set can change shape between runs (different text, or a
            // different chunker), so the old rows go before the new ones land. Without this
            // the chapter keeps orphaned chunks from the previous revision.
            //
            // Keyed by (bookId, chapterId) and not by chapterId alone: `chapter_id` is the EPUB
            // manifest id, which is book-local, so an id-only delete made storing one book's
            // `ch1` silently destroy every other book's `ch1` chunks and vectors.
            val chapters = chunks.map { it.bookId to it.chapterId }.distinct()
            deleteChunksFor(conn, chapters, modelId)

            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO chapter_chunks
                    (id, book_id, chapter_id, spine_index, chunk_index,
                     char_start, char_end, text, content_hash, model_id, dims)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                for (chunk in chunks) {
                    stmt.setString(1, chunk.id)
                    stmt.setString(2, chunk.bookId)
                    stmt.setString(3, chunk.chapterId)
                    stmt.setInt(4, chunk.spineIndex)
                    stmt.setInt(5, chunk.chunkIndex)
                    stmt.setInt(6, chunk.charStart)
                    stmt.setInt(7, chunk.charEnd)
                    stmt.setString(8, chunk.text)
                    stmt.setString(9, chunk.contentHash)
                    stmt.setString(10, modelId)
                    stmt.setInt(11, dims)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }

            conn.prepareStatement(
                "INSERT OR REPLACE INTO chapter_vectors (chunk_id, model_id, dims, vector) VALUES (?, ?, ?, ?)"
            ).use { stmt ->
                chunks.forEachIndexed { i, chunk ->
                    stmt.setString(1, chunk.id)
                    stmt.setString(2, modelId)
                    stmt.setInt(3, dims)
                    stmt.setBytes(4, encodeVector(vectors[i]))
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        // After the transaction, so the progress flows re-query against committed data.
        db.bumpChunkData()
    }

    /**
     * Deletes the chunks for [chapters] (as `bookId to chapterId` pairs) under [modelId].
     *
     * The pair is the unit, not the chapter id. `chapter_id` is the EPUB manifest identifier,
     * which is book-local — two books routinely both call a chapter `ch1` — so matching on it
     * alone made storing one book's chapter delete another book's chunks and vectors. On a
     * 22-book library that silently evicted books from the index as they were indexed, which
     * is also why the backfill believed it was finished: the evicted chapters had no chunks,
     * so the "missing" query reported nothing left to do.
     */
    private fun deleteChunksFor(
        conn: Connection,
        chapters: List<Pair<String, String>>,
        modelId: String,
    ) {
        if (chapters.isEmpty()) return
        // SQLite has no array binding; a bounded list keeps the statement cacheable enough and
        // the chunker never produces thousands of chapters in one call. Row values
        // (`(a, b) IN (VALUES (?, ?), ...)`) need SQLite 3.15+, which is Android 8+.
        chapters.chunked(200).forEach { group ->
            val rowValues = group.joinToString(",") { "(?,?)" }

            fun bindPairs(stmt: java.sql.PreparedStatement) {
                stmt.setString(1, modelId)
                var i = 2
                group.forEach { (bookId, chapterId) ->
                    stmt.setString(i++, bookId)
                    stmt.setString(i++, chapterId)
                }
            }

            conn.prepareStatement(
                "SELECT id FROM chapter_chunks " +
                    "WHERE model_id = ? AND (book_id, chapter_id) IN (VALUES $rowValues)"
            ).use { select ->
                bindPairs(select)
                val ids = select.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
                deleteVectorsByIds(conn, ids, modelId)
            }
            conn.prepareStatement(
                "DELETE FROM chapter_chunks " +
                    "WHERE model_id = ? AND (book_id, chapter_id) IN (VALUES $rowValues)"
            ).use { stmt ->
                bindPairs(stmt)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Deletes vector rows for [chunkIds].
     *
     * [modelId] scopes the delete to a single model, and must be passed on every path that is
     * itself model-scoped: chunk ids are content-derived and therefore shared between models, so
     * an unscoped delete would take another model's vectors out along with the ones being
     * replaced. Passing null is only correct when the whole book is going away.
     */
    private fun deleteVectorsByIds(conn: Connection, chunkIds: List<String>, modelId: String? = null) {
        chunkIds.chunked(200).forEach { group ->
            if (group.isEmpty()) return@forEach
            val placeholders = group.joinToString(",") { "?" }
            val scope = if (modelId != null) " AND model_id = ?" else ""
            conn.prepareStatement(
                "DELETE FROM chapter_vectors WHERE chunk_id IN ($placeholders)$scope"
            ).use { stmt ->
                group.forEachIndexed { i, id -> stmt.setString(i + 1, id) }
                if (modelId != null) stmt.setString(group.size + 1, modelId)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun deleteChunksForBook(bookId: String) {
        db.withTransaction { conn ->
            val ids = conn.prepareStatement("SELECT id FROM chapter_chunks WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            deleteVectorsByIds(conn, ids)
            conn.prepareStatement("DELETE FROM chapter_chunks WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeUpdate()
            }
        }
        db.bumpChunkData()
    }

    override suspend fun deleteChunksForBook(bookId: String, modelId: String) {
        db.withTransaction { conn ->
            val ids = conn.prepareStatement(
                "SELECT id FROM chapter_chunks WHERE book_id = ? AND model_id = ?"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, modelId)
                stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            deleteVectorsByIds(conn, ids, modelId)
            conn.prepareStatement("DELETE FROM chapter_chunks WHERE book_id = ? AND model_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, modelId)
                stmt.executeUpdate()
            }
        }
        db.bumpChunkData()
    }

    override suspend fun deleteChunksForModel(modelId: String) {
        db.withTransaction { conn ->
            conn.prepareStatement(
                "DELETE FROM chapter_vectors WHERE model_id = ?"
            ).use { stmt -> stmt.setString(1, modelId); stmt.executeUpdate() }
            conn.prepareStatement(
                "DELETE FROM chapter_chunks WHERE model_id = ?"
            ).use { stmt -> stmt.setString(1, modelId); stmt.executeUpdate() }
        }
        db.bumpChunkData()
    }

    override suspend fun loadVectors(modelId: String, dims: Int, bookId: String?): List<Pair<Chunk, FloatArray>> =
        db.withConnection { conn ->
            val bookScope = if (bookId != null) " AND c.book_id = ?" else ""
            conn.prepareStatement(
                """
                SELECT c.id, c.book_id, c.chapter_id, c.spine_index, c.chunk_index,
                       c.char_start, c.char_end, c.text, c.content_hash, v.vector, v.dims
                FROM chapter_chunks c
                JOIN chapter_vectors v ON v.chunk_id = c.id AND v.model_id = c.model_id
                WHERE c.model_id = ? AND v.dims = ?$bookScope
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setInt(2, dims)
                if (bookId != null) stmt.setString(3, bookId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val blob = rs.getBytes("vector") ?: continue
                            // A row whose blob length disagrees with its declared dims is
                            // corrupt; skip it rather than let it poison the whole index.
                            if (blob.size != dims * FLOAT_BYTES) continue
                            add(
                                Chunk(
                                    id = rs.getString("id"),
                                    bookId = rs.getString("book_id"),
                                    chapterId = rs.getString("chapter_id"),
                                    spineIndex = rs.getInt("spine_index"),
                                    chunkIndex = rs.getInt("chunk_index"),
                                    charStart = rs.getInt("char_start"),
                                    charEnd = rs.getInt("char_end"),
                                    text = rs.getString("text"),
                                    contentHash = rs.getString("content_hash"),
                                ) to decodeVector(blob, dims)
                            )
                        }
                    }
                }
            }
        }

    override suspend fun loadVectorMetadata(
        modelId: String,
        dims: Int,
        bookId: String?,
    ): List<Pair<ChunkMeta, FloatArray>> =
        db.withConnection { conn ->
            val bookScope = if (bookId != null) " AND c.book_id = ?" else ""
            conn.prepareStatement(
                """
                SELECT c.id, c.book_id, c.chapter_id, c.spine_index,
                       c.char_start, c.char_end, v.vector, v.dims
                FROM chapter_chunks c
                JOIN chapter_vectors v ON v.chunk_id = c.id AND v.model_id = c.model_id
                WHERE c.model_id = ? AND v.dims = ?$bookScope
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setInt(2, dims)
                if (bookId != null) stmt.setString(3, bookId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val blob = rs.getBytes("vector") ?: continue
                            if (blob.size != dims * FLOAT_BYTES) continue
                            add(
                                ChunkMeta(
                                    id = rs.getString("id"),
                                    bookId = rs.getString("book_id"),
                                    chapterId = rs.getString("chapter_id"),
                                    spineIndex = rs.getInt("spine_index"),
                                    charStart = rs.getInt("char_start"),
                                    charEnd = rs.getInt("char_end"),
                                ) to decodeVector(blob, dims)
                            )
                        }
                    }
                }
            }
        }

    override suspend fun loadVectorMetadataSampled(
        modelId: String,
        dims: Int,
        maxChunks: Int,
    ): List<Pair<ChunkMeta, FloatArray>> =
        db.withConnection { conn ->
            val total = count(conn, "SELECT COUNT(*) FROM chapter_chunks WHERE model_id = ?", modelId)
            // Keep every `stride`-th row plus the first row of each new book. Ordering by book_id
            // groups the stream so the "first of each book" guarantee is one cheap comparison, and
            // the global stride makes larger books contribute proportionally more of the sample.
            val stride = if (maxChunks <= 0 || total <= maxChunks) 1 else (total + maxChunks - 1) / maxChunks
            conn.prepareStatement(
                """
                SELECT c.id, c.book_id, c.chapter_id, c.spine_index,
                       c.char_start, c.char_end, v.vector, v.dims
                FROM chapter_chunks c
                JOIN chapter_vectors v ON v.chunk_id = c.id AND v.model_id = c.model_id
                WHERE c.model_id = ? AND v.dims = ?
                ORDER BY c.book_id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setInt(2, dims)
                stmt.executeQuery().use { rs ->
                    val out = ArrayList<Pair<ChunkMeta, FloatArray>>(minOf(total, maxChunks) + 16)
                    var lastBook: String? = null
                    var row = 0
                    while (rs.next()) {
                        val bookId = rs.getString("book_id")
                        val newBook = bookId != lastBook
                        lastBook = bookId
                        val keep = stride == 1 || newBook || (row % stride == 0)
                        row++
                        if (!keep) continue
                        val blob = rs.getBytes("vector") ?: continue
                        if (blob.size != dims * FLOAT_BYTES) continue
                        out.add(
                            ChunkMeta(
                                id = rs.getString("id"),
                                bookId = bookId,
                                chapterId = rs.getString("chapter_id"),
                                spineIndex = rs.getInt("spine_index"),
                                charStart = rs.getInt("char_start"),
                                charEnd = rs.getInt("char_end"),
                            ) to decodeVector(blob, dims)
                        )
                    }
                    out
                }
            }
        }

    override suspend fun chunkTexts(ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return db.withConnection { conn ->
            // Chunked IN-list: SQLite caps host parameters (default 999), and a returned hit set
            // is small anyway, so this stays one or two statements rather than a giant query.
            val result = HashMap<String, String>(ids.size)
            ids.chunked(400).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT id, text FROM chapter_chunks WHERE id IN ($placeholders)"
                ).use { stmt ->
                    batch.forEachIndexed { i, id -> stmt.setString(i + 1, id) }
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) result[rs.getString("id")] = rs.getString("text")
                    }
                }
            }
            result
        }
    }

    override suspend fun indexedChunkIds(
        bookId: String,
        chapterId: String,
        modelId: String,
    ): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT id FROM chapter_chunks " +
                    "WHERE book_id = ? AND chapter_id = ? AND model_id = ?"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setString(3, modelId)
                stmt.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override suspend fun chunkCount(modelId: String): Int =
        db.withConnection { conn -> count(conn, "SELECT COUNT(*) FROM chapter_chunks WHERE model_id = ?", modelId) }

    override suspend fun embeddedBookCount(modelId: String): Int =
        db.withConnection { conn ->
            count(conn, "SELECT COUNT(DISTINCT book_id) FROM chapter_chunks WHERE model_id = ?", modelId)
        }

    override suspend fun modelChunkCounts(): Map<String, Int> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT model_id, COUNT(*) AS n FROM chapter_chunks GROUP BY model_id"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getString("model_id"), rs.getInt("n")) }
                }
            }
        }

    override suspend fun chunkRecipe(modelId: String): String? =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT recipe FROM chunk_recipes WHERE model_id = ?").use { stmt ->
                stmt.setString(1, modelId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    override suspend fun recordChunkRecipe(modelId: String, recipe: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO chunk_recipes (model_id, recipe) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setString(2, recipe)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Chapters the semantic index can actually hold anything for.
     *
     * Not every chapter in `search_index` can be chunked: `TextChunker` drops anything under
     * [TextChunker.MIN_CHUNK_WORDS] words, and a 2 656-chapter library had ~416 of them
     * (part titles, epigraphs, one-line interstitials). Counting those in the total meant the
     * readout could never reach it — it sat at "2 240 of 2 656" and the button never became
     * "Index complete", because the remaining 416 produce no chunks no matter how often they
     * are retried.
     *
     * ### Why this is cached, and why that is not an optimisation
     *
     * `HAS_ENOUGH_WORDS` counts spaces, so it cannot use an index: SQLite reads the full `content`
     * of **every** chapter and scans it twice, ~100 MB of text per evaluation on a 304 MB library.
     * `observeProgress` used to call this on **every** [Database.chunkDataRevision] emission, i.e.
     * once per backfill slice, which is why indexing hung the entire phone rather than merely
     * keeping it busy — the scan competed with the embedding for CPU, IO and page cache, and the
     * native heap left no room for the kernel to absorb it.
     *
     * The count changes only when a chapter's text is inserted, rewritten or deleted, so it is
     * cached against [Database.searchTextRevision] and recomputed only then. A backfill writes
     * thousands of chunks over many minutes and bumps `searchTextRevision` **zero** times, so
     * throughout an entire indexing run this now costs one scan instead of one per slice.
     */
    override suspend fun searchableChapterCount(): Int {
        val revision = db.searchTextRevision.value
        synchronized(searchableCountLock) {
            cachedSearchableCount?.let { (rev, value) ->
                if (rev == revision) return value
            }
        }
        val computed = db.withConnection { conn ->
            count(conn, "SELECT COUNT(*) FROM search_index s WHERE $HAS_ENOUGH_WORDS", null)
        }
        // Re-read the revision after the query: if a `search_index` write landed while it ran, the
        // cached value is already stale and must not be stored under the older revision, or the
        // new text would stay invisible until some unrelated write bumped the revision again.
        val after = db.searchTextRevision.value
        synchronized(searchableCountLock) {
            if (after == revision) cachedSearchableCount = revision to computed
        }
        return computed
    }

    private val searchableCountLock = Any()
    private var cachedSearchableCount: Pair<Long, Int>? = null


    /**
     * How many chapters have vectors, counted as `(book_id, chapter_id)` pairs.
     *
     * `COUNT(DISTINCT chapter_id)` was wrong for the same reason as everything else keyed on
     * the chapter id: the id is book-local, so two books sharing `ch1` collapsed to one and the
     * readout could never reach `searchableChapterCount()`. On the test library that showed up
     * as a permanent "2 246 of 2 561".
     */
    override suspend fun indexedChapterCount(modelId: String): Int =
        db.withConnection { conn ->
            count(
                conn,
                "SELECT COUNT(*) FROM (" +
                    "SELECT DISTINCT book_id, chapter_id FROM chapter_chunks WHERE model_id = ?" +
                    ")",
                modelId,
            )
        }

    override suspend fun chaptersMissingVectors(modelId: String, limit: Int): List<ChapterRef> =
        db.withConnection { conn ->
            conn.prepareStatement(BACKFILL_SLICE_SQL).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ChapterRef(
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

    /**
     * Live progress for the settings readout.
     *
     * Collects [Database.chunkDataRevision] (bumped by every chunk write) rather than
     * `bookDataRevision`, which nothing here bumps — that mismatch is what once made a working
     * backfill look stalled.
     *
     * ### Cost
     *
     * Each emission runs [progress], which is three `COUNT` queries. Two are cheap indexed counts;
     * the third, [searchableChapterCount], is a full-library text scan that cannot use an index.
     *
     * This note previously argued the cost was "already bounded" because `chunkDataRevision` is a
     * `StateFlow` and therefore conflates, so a collector slower than the writer skips intermediate
     * revisions. **That reasoning was wrong in the case that mattered.** Conflation only skips
     * revisions that arrive *while a computation is in flight*; it does not slow a collector down
     * to the writer's pace. The settings screen is a live collector with nothing else to do, so it
     * consumed every revision as it arrived and paid a full-library scan per backfill slice — the
     * collector was never the slow side, so there was nothing to conflate away.
     *
     * Hence the fix is on the query, not on the flow: [searchableChapterCount] now caches against
     * [Database.searchTextRevision], which a backfill does not bump, so an indexing run costs one
     * scan in total instead of one per slice. `conflate()` here is still a no-op (the compiler
     * says so), and the flow is unchanged.
     *
     * The original note's closing caution stands and is worth keeping: this must not be collected
     * from a screen that is not visible, because the price is paid on every emission regardless.
     */
    override fun observeProgress(modelId: String): Flow<IndexProgress> =
        db.chunkDataRevision.map { progress(modelId) }

    override suspend fun progress(modelId: String): IndexProgress = IndexProgress(
        indexedChapters = indexedChapterCount(modelId),
        totalChapters = searchableChapterCount(),
        indexedChunks = chunkCount(modelId),
        modelId = modelId,
    )

    private fun count(conn: Connection, sql: String, arg: String?): Int =
        conn.prepareStatement(sql).use { stmt ->
            if (arg != null) stmt.setString(1, arg)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
        if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
    }

    companion object {
        private const val FLOAT_BYTES = 4

        /**
         * True when a chapter is long enough for [TextChunker] to make a chunk of it.
         *
         * SQLite has no word count, but this is exact rather than an estimate:
         * `SearchIndexer.extractPlainText` collapses every run of whitespace to a single space
         * and trims, so spaces are precisely the word separators. It always refers to
         * `search_index` by the alias `s`.
         *
         * This matters because a chapter under [TextChunker.MIN_CHUNK_WORDS] words yields no
         * chunks *ever*. Left in the work list it is offered again on every pass, so the
         * backfill can neither finish nor report itself finished: one real library stalled at
         * "2 240 of 2 656" with 416 unchunkable chapters permanently "missing".
         */
        internal val HAS_ENOUGH_WORDS =
            "(length(s.content) - length(replace(s.content, ' ', '')) + 1) >= " +
                TextChunker.MIN_CHUNK_WORDS

        /**
         * One backfill slice: chapters that have no chunks for [modelId] yet.
         *
         * Two things here are load-bearing for performance, and both are guarded by
         * `ChunkRepositoryTest.backfill slice query is indexed and does not sort` because
         * neither shows up as a wrong answer — only as the backfill crawling:
         *
         * 1. `ORDER BY s.rowid`, not `ORDER BY s.book_id, s.spine_index`. `search_index` is a
         *    standalone FTS5 table, which indexes tokens rather than columns, so there is no
         *    B-tree on those two columns. Ordering by them made SQLite materialise every row
         *    — each chapter's full `content` included — into a temp B-tree before `LIMIT`
         *    applied: 2 452 ms versus 62 ms for the same slice on a 2 656-chapter corpus.
         *    `rowid` is FTS5's storage order, so ordering by it is free and still
         *    deterministic (import order).
         * 2. The correlated subquery needs `idx_chunks_book_chapter_model`. With only the
         *    single-column indexes present the planner picks `idx_chunks_model` and every probe
         *    walks every chunk of that model, so the cost grows as the backfill proceeds:
         *    405 ms per slice with 2 000 chapters left, 890 ms with 40 left. With the composite
         *    index it is a covering lookup and flat (~55 ms).
         *
         * The probe also matches on `book_id`, which is correctness rather than performance.
         * `chapter_id` is the EPUB manifest id — book-local, so two books both call a chapter
         * `ch1`. Matching on the id alone meant that once any book's `ch1` was indexed, every
         * other book's `ch1` looked done: those chapters were never embedded, and once the last
         * one was masked the query returned nothing, `backfillSlice` reported `complete`, and
         * the worker exited in about a second. That is the whole of the "Resume indexing does
         * nothing" bug.
         */
        internal val BACKFILL_SLICE_SQL = """
            SELECT s.book_id, s.chapter_id, s.spine_index, s.title, s.content
            FROM search_index s
            WHERE $HAS_ENOUGH_WORDS
              AND NOT EXISTS (
                SELECT 1 FROM chapter_chunks c
                WHERE c.book_id = s.book_id
                  AND c.chapter_id = s.chapter_id
                  AND c.model_id = ?
            )
            ORDER BY s.rowid
            LIMIT ?
        """.trimIndent()

        fun encodeVector(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(vector)
            return buffer.array()
        }

        fun decodeVector(bytes: ByteArray, dims: Int): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(dims)
            buffer.asFloatBuffer().get(out)
            return out
        }
    }
}
