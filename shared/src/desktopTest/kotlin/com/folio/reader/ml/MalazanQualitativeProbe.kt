package com.folio.reader.ml

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
import kotlin.test.Test

/**
 * Qualitative retrieval probe on one large book (`original.epub` in the repo root).
 *
 * Unlike [RetrievalQualityBenchmark] there are **no gold labels** — the point is to eyeball, on a
 * corpus the reader knows, whether meaning queries surface the right passage, and whether a smaller
 * chunk window sharpens that. For each query it prints the top-5 chapters (cosine score + chapter
 * index + snippet) for **Arctic at the shipped ~350-word window vs Arctic at a 150-word window**,
 * then a MiniLM section for reference. There is no pass/fail metric; judgement is the reader's.
 *
 * Gated behind `-PfolioBench` (see shared/build.gradle.kts) and self-skips when the epub or the
 * Arctic fixture is absent. Report: `.dbg/phase0/malazan-probe.md`.
 */
class MalazanQualitativeProbe {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var importer: BookImporter
    private lateinit var searchRepository: JdbcSearchRepository

    private val epub = File("original.epub").absoluteFile
        .takeIf { it.isFile } ?: File("..", "original.epub").absoluteFile

    private val startedAt = System.currentTimeMillis()

    /** Meaning-first queries. These are the axis under test — paraphrases with no shared keywords. */
    private val queries = listOf(
        "a soldier who refuses to die and keeps returning to the fight",
        "a god betrayed by his own worshippers and left to rot",
        "grief so heavy it seems to turn a person to stone",
        "an assassin torn between the guild and a friend",
        "compassion shown to a broken enemy on the battlefield",
        "an ancient race that remembers when the world was young",
        "a promise made to the dead that binds the living",
        "power that corrupts the one forced to wield it",
        "two weary veterans joking in the dark before a hopeless battle",
        "a city besieged where the sky itself becomes a weapon",
    )

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-malazan-")
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
    fun `qualitative probe on original epub`() = runBlocking {
        assumeTrue(epub.isFile, "original.epub not found in repo root")
        val arcticModel = TestModelFixtures.arcticModel()
        val arcticVocab = TestModelFixtures.arcticVocab()
        assumeTrue(arcticModel != null && arcticVocab != null, TestModelFixtures.missingHint("Arctic-S"))

        progress("import start: ${epub.name}")
        val imported = importer.importEpub(epub.absolutePath)
        assumeTrue(imported.isSuccess, "import failed: ${imported.exceptionOrNull()?.message}")

        val chapters = database.withConnection { conn ->
            conn.prepareStatement(
                "SELECT book_id, chapter_id, spine_index, title, content FROM search_index"
            ).use { st ->
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ChapterText(
                                rs.getString("book_id"), rs.getString("chapter_id"),
                                rs.getInt("spine_index"), rs.getString("title"), rs.getString("content"),
                            )
                        )
                    }
                }
            }
        }
        assumeTrue(chapters.isNotEmpty(), "import produced no indexed chapters")
        progress("imported: ${chapters.size} chapters")

        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val out = StringBuilder()
        out.appendLine("# Malazan qualitative probe — ${epub.name}")
        out.appendLine()
        out.appendLine("Chapters: ${chapters.size}. No gold labels — judge relevance yourself.")
        out.appendLine("Score = cosine (higher is closer). One row per chapter, best chunk shown.")
        out.appendLine()

        // Arctic, two windows, so the chunk-size hypothesis is visible per query.
        val arctic = OnnxEmbedder.wordPiece(
            EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8, arcticModel!!, arcticVocab!!,
            threads = threads, enableCpuMemArena = true,
        )
        val shipped = EmbeddingModelCatalog.SNOWFLAKE_ARCTIC_EMBED_S_INT8.maxChunkWords
        val arcticShipped = buildIndex("arctic@$shipped", chapters, arctic, shipped, shipped / 4)
        val arcticSmall = buildIndex("arctic@150", chapters, arctic, 150, 30)
        out.appendLine("## Arctic — $shipped-word vs 150-word chunks")
        out.appendLine()
        for (q in queries) {
            out.appendLine("### \"$q\"")
            out.appendLine()
            renderColumn(out, "Arctic $shipped-word", arctic, arcticShipped, q)
            renderColumn(out, "Arctic 150-word", arctic, arcticSmall, q)
            out.appendLine()
        }
        arctic.close()

        // MiniLM reference, if present.
        val miniModel = TestModelFixtures.miniLmModel()
        val miniVocab = TestModelFixtures.miniLmVocab()
        if (miniModel != null && miniVocab != null) {
            val mini = OnnxEmbedder.wordPiece(
                EmbeddingModelCatalog.MINILM_L6_V2_INT8, miniModel, miniVocab, threads = threads,
            )
            val mw = EmbeddingModelCatalog.MINILM_L6_V2_INT8.maxChunkWords
            val miniIdx = buildIndex("minilm@$mw", chapters, mini, mw, mw / 4)
            out.appendLine("## MiniLM reference ($mw-word chunks)")
            out.appendLine()
            for (q in queries) {
                out.appendLine("### \"$q\"")
                out.appendLine()
                renderColumn(out, "MiniLM", mini, miniIdx, q)
                out.appendLine()
            }
            mini.close()
        }

        val report = File(reportDir(), "malazan-probe.md")
        report.writeText(out.toString())
        progress("DONE -> ${report.absolutePath}")
        println(out)
    }

    private data class Built(val index: CosineIndex, val chunkById: Map<String, Chunk>)

    private suspend fun buildIndex(
        label: String, chapters: List<ChapterText>, embedder: Embedder, maxWords: Int, overlap: Int,
    ): Built {
        val chunks = chapters.flatMap { c ->
            TextChunker.chunk(c.content, c.bookId, c.chapterId, c.spineIndex, maxWords, overlap)
        }
        val index = CosineIndex(embedder.model.dims)
        val started = System.currentTimeMillis()
        var done = 0
        chunks.chunked(32).forEach { batch ->
            val vecs = embedder.embed(batch.map { it.text }, EmbedKind.PASSAGE)
            batch.forEachIndexed { i, ch -> index.add(ch.id, vecs[i]) }
            done += batch.size
            if (done % 1600 == 0) progress("$label: $done/${chunks.size} chunks")
        }
        progress("$label: ${chunks.size} chunks in ${System.currentTimeMillis() - started} ms")
        return Built(index, chunks.associateBy { it.id })
    }

    private suspend fun renderColumn(
        out: StringBuilder, label: String, embedder: Embedder, built: Built, query: String,
    ) {
        val vec = embedder.embed(listOf(query), EmbedKind.QUERY).first()
        val seen = LinkedHashSet<String>()
        out.appendLine("**$label**")
        out.appendLine()
        var shown = 0
        for (hit in built.index.search(vec, limit = 40)) {
            val chunk = built.chunkById[hit.chunkId] ?: continue
            if (!seen.add(chunk.bookId + "\u0000" + chunk.chapterId)) continue // one row per chapter
            val snippet = chunk.text.replace(Regex("\\s+"), " ").trim().take(160)
            out.appendLine("- %.3f  ch#%d  %s…".format(hit.score, chunk.spineIndex, snippet))
            if (++shown >= 5) break
        }
        out.appendLine()
    }

    private fun reportDir(): File {
        val inModule = File(".dbg/phase0").absoluteFile
        val dir = if (inModule.parentFile.exists()) inModule else File("..", ".dbg/phase0").absoluteFile
        runCatching { dir.mkdirs() }
        return dir
    }

    private fun progress(message: String) {
        val line = "[%6.1fs] %s".format((System.currentTimeMillis() - startedAt) / 1000.0, message)
        println("[malazan] $line")
        runCatching { File(reportDir(), "malazan-probe-progress.log").appendText(line + "\n") }
    }

    private data class ChapterText(
        val bookId: String, val chapterId: String, val spineIndex: Int, val title: String, val content: String,
    )
}
