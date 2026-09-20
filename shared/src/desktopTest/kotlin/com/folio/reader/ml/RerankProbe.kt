package com.folio.reader.ml

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.importer.BookImporter
import com.folio.reader.importer.SearchIndexer
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.LongBuffer
import java.nio.file.Path
import kotlin.test.Test

/**
 * Reranker prototype on `original.epub`: retrieve with Arctic (150-word chunks), then re-score the
 * top candidates with a cross-encoder that reads query+passage *together*, and print the raw vs
 * reranked top-5 side by side.
 *
 * The probe ([MalazanQualitativeProbe]) showed Arctic's cosine compresses into a 0.65–0.72 band and
 * cannot separate a bullseye from a near-miss — the "vaguely matches" complaint. A cross-encoder is
 * the standard fix: it is far more discriminative because it attends across the pair rather than
 * comparing two independent summaries. This measures whether it actually reorders Malazan results
 * for the better *before* any of it is wired into the app.
 *
 * Model: `Xenova/ms-marco-MiniLM-L-6-v2` (BERT cross-encoder, 1 logit = relevance), fetched to
 * `.models/reranker/`. Gated behind `-PfolioBench`; self-skips if fixtures are missing.
 * Report: `.dbg/phase0/rerank-probe.md`.
 */
class RerankProbe {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var importer: BookImporter
    private lateinit var searchRepository: JdbcSearchRepository

    private val epub = File("original.epub").absoluteFile
        .takeIf { it.isFile } ?: File("..", "original.epub").absoluteFile

    private fun modelsFile(name: String): File =
        listOf(File(".models/reranker"), File("..", ".models/reranker"))
            .map { File(it, name) }.firstOrNull { it.isFile } ?: File(".models/reranker/$name")

    private val startedAt = System.currentTimeMillis()

    private val queries = listOf(
        "a soldier who refuses to die and keeps returning to the fight",
        "a god betrayed by his own worshippers and left to rot",
        "grief so heavy it seems to turn a person to stone",
        "an assassin torn between the guild and a friend",
        "compassion shown to a broken enemy on the battlefield",
        "power that corrupts the one forced to wield it",
        "two weary veterans joking in the dark before a hopeless battle",
        "a city besieged where the sky itself becomes a weapon",
    )

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-rerank-")
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
    fun `rerank prototype on original epub`() = runBlocking {
        assumeTrue(epub.isFile, "original.epub not found")
        val arcticModel = TestModelFixtures.arcticModel()
        val arcticVocab = TestModelFixtures.arcticVocab()
        assumeTrue(arcticModel != null && arcticVocab != null, TestModelFixtures.missingHint("Arctic-S"))
        val ceModel = modelsFile("ms-marco-minilm-l6-v2-quantized.onnx")
        val ceTokenizer = modelsFile("ms-marco-minilm-l6-v2-tokenizer.json")
        assumeTrue(ceModel.isFile && ceTokenizer.isFile, "cross-encoder not in .models/reranker/")

        progress("import start: ${epub.name}")
        assumeTrue(importer.importEpub(epub.absolutePath).isSuccess, "import failed")
        val chapters = database.withConnection { conn ->
            conn.prepareStatement("SELECT book_id, chapter_id, spine_index, content FROM search_index").use { st ->
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ChapterText(rs.getString("book_id"), rs.getString("chapter_id"),
                                rs.getInt("spine_index"), rs.getString("content"))
                        )
                    }
                }
            }
        }
        assumeTrue(chapters.isNotEmpty(), "no chapters")
        progress("imported ${chapters.size} chapters")

        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val arctic = OnnxEmbedder.wordPiece(
            EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8, arcticModel!!, arcticVocab!!,
            threads = threads, enableCpuMemArena = true,
        )
        val chunks = chapters.flatMap { c ->
            TextChunker.chunk(c.content, c.bookId, c.chapterId, c.spineIndex, 150, 30)
        }
        val chunkById = chunks.associateBy { it.id }
        val index = CosineIndex(arctic.model.dims)
        var done = 0
        chunks.chunked(32).forEach { batch ->
            val vecs = arctic.embed(batch.map { it.text }, EmbedKind.PASSAGE)
            batch.forEachIndexed { i, ch -> index.add(ch.id, vecs[i]) }
            done += batch.size
            if (done % 3200 == 0) progress("arctic@150: $done/${chunks.size}")
        }
        progress("index built: ${chunks.size} chunks")

        val ce = CrossEncoder(ceModel, ceTokenizer)
        val out = StringBuilder()
        out.appendLine("# Reranker prototype — ${epub.name}")
        out.appendLine()
        out.appendLine("Retrieval: Arctic, 150-word chunks. Rerank: ms-marco-MiniLM-L-6-v2 cross-encoder over top 40.")
        out.appendLine("Left = raw Arctic cosine order. Right = cross-encoder order (logit; higher = more relevant).")
        out.appendLine()

        for (q in queries) {
            val vec = arctic.embed(listOf(q), EmbedKind.QUERY).first()
            val hits = index.search(vec, limit = 40)
            val candidates = hits.mapNotNull { h -> chunkById[h.chunkId]?.let { it to h.score } }
            val reranked = candidates
                .map { (chunk, cos) -> Triple(chunk, cos, ce.score(q, chunk.text.take(1400))) }
                .sortedByDescending { it.third }

            out.appendLine("## \"$q\"")
            out.appendLine()
            out.appendLine("**Arctic cosine (raw)**")
            out.appendLine()
            renderChapters(out, candidates.map { Row(it.first, it.second) })
            out.appendLine("**Cross-encoder reranked**")
            out.appendLine()
            renderChapters(out, reranked.map { Row(it.first, it.third) })
            out.appendLine()
        }
        arctic.close()
        ce.close()

        val report = File(reportDir(), "rerank-probe.md")
        report.writeText(out.toString())
        progress("DONE -> ${report.absolutePath}")
        println(out)
    }

    private data class Row(val chunk: Chunk, val score: Float)

    private fun renderChapters(out: StringBuilder, rows: List<Row>) {
        val seen = LinkedHashSet<String>()
        var shown = 0
        for (r in rows) {
            if (!seen.add(r.chunk.bookId + "\u0000" + r.chunk.chapterId)) continue
            val snippet = r.chunk.text.replace(Regex("\\s+"), " ").trim().take(150)
            out.appendLine("- %.3f  ch#%d  %s…".format(r.score, r.chunk.spineIndex, snippet))
            if (++shown >= 5) break
        }
        out.appendLine()
    }

    /** Raw ONNX cross-encoder: (query, passage) -> single relevance logit. */
    private class CrossEncoder(modelFile: File, tokenizerJson: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        private val tok = HuggingFaceTokenizer.newInstance(Path.of(tokenizerJson.absolutePath))
        private val hasTypeIds = session.inputNames.contains("token_type_ids")

        fun score(query: String, passage: String): Float {
            val enc = tok.encode(query, passage)
            val ids = enc.ids
            val n = ids.size.coerceAtMost(512)
            val idsL = LongArray(n) { ids[it] }
            val attL = LongArray(n) { enc.attentionMask.getOrElse(it) { 1L } }
            val typL = LongArray(n) { enc.typeIds.getOrElse(it) { 0L } }
            val shape = longArrayOf(1L, n.toLong())
            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(idsL), shape)
                inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(attL), shape)
                if (hasTypeIds) inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(typL), shape)
                session.run(inputs, setOf(session.outputNames.first())).use { res ->
                    val t = res.get(0) as OnnxTensor
                    return t.floatBuffer.get(0)
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        }

        override fun close() {
            runCatching { session.close() }
            runCatching { tok.close() }
        }
    }

    private fun reportDir(): File {
        val inModule = File(".dbg/phase0").absoluteFile
        val dir = if (inModule.parentFile.exists()) inModule else File("..", ".dbg/phase0").absoluteFile
        runCatching { dir.mkdirs() }
        return dir
    }

    private fun progress(message: String) {
        val line = "[%6.1fs] %s".format((System.currentTimeMillis() - startedAt) / 1000.0, message)
        println("[rerank] $line")
        runCatching { File(reportDir(), "rerank-probe-progress.log").appendText(line + "\n") }
    }

    private data class ChapterText(
        val bookId: String, val chapterId: String, val spineIndex: Int, val content: String,
    )
}
