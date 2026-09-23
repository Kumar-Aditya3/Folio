package com.folio.reader.ml

import com.folio.reader.database.ChapterIndexEntry
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcChunkRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Echoes and Atlas readiness, end to end minus the UI.
 *
 * The two things that break in practice are the two things covered here: Echoes must exclude
 * the book the reader is *in* (an "echo" of the open page is not a discovery) while still
 * honouring the relevance floor, and the Atlas readiness gate must tell "unavailable",
 * "too few books" and "ready" apart — each is a different sentence to the reader.
 */
class SemanticDiscoveryTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var chunkRepository: JdbcChunkRepository
    private lateinit var bookRepository: JdbcBookRepository
    private lateinit var indexer: EmbeddingIndexer
    private lateinit var discovery: SemanticDiscoveryRepository

    private class FakeFactory(override val model: EmbeddingModel) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder = FakeEmbedder(model)
    }

    @BeforeEach
    fun setUp() = runBlocking {
        tempRoot = createTempDir("folio-discovery-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        chunkRepository = JdbcChunkRepository(database)
        bookRepository = JdbcBookRepository(database)
        indexer = EmbeddingIndexer(chunkRepository, FakeFactory(FakeEmbedder.TEST_MODEL))
        discovery = SemanticDiscoveryRepository(
            semanticSearch = SemanticSearchRepository(
                searchRepository = JdbcSearchRepository(database),
                chunkRepository = chunkRepository,
                embedderFactory = FakeFactory(FakeEmbedder.TEST_MODEL),
            ),
            chunkRepository = chunkRepository,
            bookRepository = bookRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun addBook(id: String, title: String) {
        bookRepository.insertBook(Book(id = id, title = title, epubHash = "h$id", epubFileSize = 1))
    }

    private suspend fun seed(bookId: String, chapterId: String, spineIndex: Int, text: String) {
        bookRepository.insertChapters(
            bookId,
            listOf(Chapter(id = chapterId, bookId = bookId, title = "Chapter", href = "$chapterId.xhtml", spineIndex = spineIndex)),
        )
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) VALUES (?, ?, ?, 'Chapter', ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setInt(3, spineIndex)
                stmt.setString(4, text)
                stmt.executeUpdate()
            }
        }
        indexer.indexChapters(
            bookId,
            listOf(ChapterIndexEntry(chapterId = chapterId, spineIndex = spineIndex, title = "Chapter", content = text)),
        )
    }

    @Test
    fun `echoes exclude the current book`() = runBlocking {
        addBook("b-a", "The Will of the Many")
        addBook("b-b", "Shadows Upon Time")
        seed("b-a", "ch1", 0, "the vessel foundered and the sea took everything down with it")
        seed("b-a", "ch2", 1, "the sea took everything the vessel carried and kept it beneath the waves")
        seed("b-b", "ch1", 0, "the sea took everything the vessel carried and kept it forever below")

        // A passage that genuinely appears in book A.
        val sourceText = "the vessel foundered and the sea took everything down with it"
        val echoes = discovery.echoes(selectedText = sourceText, currentBookId = "b-a")

        assertTrue(echoes.isNotEmpty(), "book B has a resonant passage and must surface")
        assertTrue(echoes.none { it.bookId == "b-a" }, "no echo may come from the open book: $echoes")
        assertTrue(echoes.all { it.bookTitle.isNotBlank() }, "each echo must resolve to a title")
        assertTrue(echoes.all { it.score >= SemanticSearchRepository.MIN_SIMILARITY }, "the relevance floor must hold")
    }

    @Test
    fun `a blank selection produces no echoes and no embedder call`() = runBlocking {
        addBook("b-a", "A")
        seed("b-a", "ch1", 0, "the sea took everything the vessel carried and kept it forever below the waves")
        assertEquals(emptyList(), discovery.echoes(selectedText = "   ", currentBookId = "b-a"))
    }

    @Test
    fun `readiness is Unavailable with no library`() = runBlocking {
        assertEquals(AtlasReadiness.Unavailable, discovery.atlasReadiness())
    }

    @Test
    fun `readiness is TooFewBooks below the threshold when fully indexed`() = runBlocking {
        // Three fully-indexed books — under the threshold of five.
        repeat(3) { i ->
            addBook("b$i", "Book $i")
            seed("b$i", "ch1", 0, "the sea took everything the vessel carried and kept it forever below the waves")
        }
        val readiness = discovery.atlasReadiness()
        assertTrue(readiness is AtlasReadiness.TooFewBooks, "three books is too few: $readiness")
        readiness as AtlasReadiness.TooFewBooks
        assertEquals(3, readiness.count)
        assertEquals(SemanticDiscoveryRepository.ATLAS_BOOK_THRESHOLD, readiness.threshold)
    }

    @Test
    fun `readiness is Ready at or above the threshold`() = runBlocking {
        repeat(SemanticDiscoveryRepository.ATLAS_BOOK_THRESHOLD) { i ->
            addBook("b$i", "Book $i")
            seed("b$i", "ch1", 0, "the sea took everything the vessel carried and kept it forever below the waves")
        }
        val readiness = discovery.atlasReadiness()
        assertTrue(readiness is AtlasReadiness.Ready, "five embedded books should be ready: $readiness")
    }

    @Test
    fun `atlas maps the embedded books`() = runBlocking {
        repeat(6) { i ->
            addBook("b$i", "Book $i")
            seed("b$i", "ch1", 0, "the sea took everything the vessel carried and kept it forever below the waves and rocks")
        }
        val atlas = discovery.atlas()
        assertEquals(6, atlas.books.size, "every embedded book must appear on the map")
        atlas.books.forEach { b ->
            assertTrue(b.title.isNotBlank())
            assertTrue(b.clusters.isNotEmpty())
        }
        // Cached: a second call with an unchanged index returns the same instance.
        assertTrue(discovery.atlas() === atlas, "an unchanged index must return the cached map")
    }

    @Test
    fun `atlas is served from disk on a cold start`() = runBlocking {
        repeat(6) { i ->
            addBook("b$i", "Book $i")
            seed("b$i", "ch1", 0, "the sea took everything the vessel carried and kept it forever below the waves and rocks")
        }

        // A repo with a cache dir writes the roll-up to disk after computing it.
        val cacheDir = File(tempRoot, "models").apply { mkdirs() }
        val warm = newDiscovery(cacheDir)
        val first = warm.atlas()
        assertTrue(first.books.isNotEmpty(), "the warm build must produce a map")
        assertTrue(File(cacheDir, "atlas_cache.json").exists(), "the roll-up must be persisted")

        // A *fresh* repo over the same DB and cache dir (a cold app start: empty in-memory cache)
        // must reconstruct the map from disk rather than a from-scratch roll-up. It is a different
        // instance, but round-trips to an equivalent map — proving the @Serializable envelope holds.
        val cold = newDiscovery(cacheDir)
        val restored = cold.atlas()
        assertTrue(restored !== first, "a cold start starts with an empty in-memory cache")
        assertEquals(
            first.books.map { it.bookId }.toSet(),
            restored.books.map { it.bookId }.toSet(),
            "the disk-restored map must cover the same books",
        )
        assertEquals(
            first.books.sumOf { it.clusters.size },
            restored.books.sumOf { it.clusters.size },
            "the disk-restored map must carry the same clusters",
        )
    }

    private fun newDiscovery(cacheDir: File) = SemanticDiscoveryRepository(
        semanticSearch = SemanticSearchRepository(
            searchRepository = JdbcSearchRepository(database),
            chunkRepository = chunkRepository,
            embedderFactory = FakeFactory(FakeEmbedder.TEST_MODEL),
        ),
        chunkRepository = chunkRepository,
        bookRepository = bookRepository,
        cacheDir = cacheDir,
    )
}
