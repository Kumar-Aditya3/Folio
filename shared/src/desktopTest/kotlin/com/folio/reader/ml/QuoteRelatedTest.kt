package com.folio.reader.ml

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcChunkRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.ui.quotes.QuoteRelatedFinder
import com.folio.reader.ui.quotes.RelatedLookup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ML_PLAN Phase 5 #3 — "more like this", end to end minus the UI.
 *
 * The feature was listed as *backend only, zero callers*: `moreLikeThis` was implemented and
 * tested in isolation but nothing resolved a hit into something a reader could read, and
 * nothing decided what to say when the index had never been built. Those are exactly the two
 * things that break in practice, so they are what this covers:
 *
 * - a chunk hit resolves to a book title and a chapter title, at a `(bookId, chapterId)`
 *   identity — `chapter_id` is the EPUB manifest id and is not unique across the library;
 * - the source passage is excluded, so the top hit is not always the quote itself;
 * - an unindexed library reports `NotIndexed`, which is a different sentence from
 *   "nothing similar exists".
 */
class QuoteRelatedTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var chunkRepository: JdbcChunkRepository
    private lateinit var bookRepository: JdbcBookRepository
    private lateinit var finder: QuoteRelatedFinder
    private lateinit var indexer: EmbeddingIndexer

    /** Two books that both call a chapter `ch1` — the collision that matters everywhere else. */
    private val bookA = Book(id = "b-a", title = "The Will of the Many", epubHash = "ha", epubFileSize = 1)
    private val bookB = Book(id = "b-b", title = "Shadows Upon Time", epubHash = "hb", epubFileSize = 1)

    @BeforeEach
    fun setUp() = runBlocking {
        tempRoot = createTempDir("folio-related-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        chunkRepository = JdbcChunkRepository(database)
        bookRepository = JdbcBookRepository(database)
        indexer = EmbeddingIndexer(chunkRepository, FakeFactory(FakeEmbedder.TEST_MODEL))
        finder = QuoteRelatedFinder(
            semanticSearch = SemanticSearchRepository(
                searchRepository = JdbcSearchRepository(database),
                chunkRepository = chunkRepository,
                embedderFactory = FakeFactory(FakeEmbedder.TEST_MODEL),
            ),
            bookRepository = bookRepository,
        )
        bookRepository.insertBook(bookA)
        bookRepository.insertBook(bookB)
        bookRepository.insertChapters(
            "b-a",
            listOf(Chapter(id = "ch1", bookId = "b-a", title = "The Fall", href = "ch1.xhtml", spineIndex = 0)),
        )
        bookRepository.insertChapters(
            "b-b",
            listOf(Chapter(id = "ch1", bookId = "b-b", title = "Anaryan", href = "ch1.xhtml", spineIndex = 0)),
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private class FakeFactory(override val model: EmbeddingModel) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder = FakeEmbedder(model)
    }

    /**
     * Indexes a chapter through the real pipeline.
     *
     * `indexChapters` writes chunks *and* their vectors keyed on the model, which is what
     * `moreLikeThis` reads back — going through `storeChunks` directly would skip the
     * embedding and the test would prove nothing about the actual path.
     */
    private suspend fun seed(bookId: String, chapterId: String, spineIndex: Int, text: String) {
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO search_index (book_id, chapter_id, spine_index, title, content) " +
                    "VALUES (?, ?, ?, 'Chapter', ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setInt(3, spineIndex)
                stmt.setString(4, text)
                stmt.executeUpdate()
            }
        }
        val entry = com.folio.reader.database.ChapterIndexEntry(
            chapterId = chapterId,
            spineIndex = spineIndex,
            title = "Chapter",
            content = text,
        )
        indexer.indexChapters(bookId, listOf(entry))
    }

    @Test
    fun `an unindexed library reports NotIndexed rather than no results`() = runBlocking {
        val outcome = finder.related("grief over a dead friend", limit = 8)
        assertEquals(
            RelatedLookup.NotIndexed, outcome,
            "with no vectors stored, 'no similar passages' would be a claim the app cannot support",
        )
    }

    @Test
    fun `a hit resolves to its own book and chapter, not a book-local id`() = runBlocking {
        // Every seeded chapter needs at least `TextChunker.MIN_CHUNK_WORDS` (8) words, or
        // `indexChapters` stores nothing for it and the test silently loses its subject. The
        // first version of this fixture had a 7-word second book, which is how it failed:
        // `b-b/ch1` never produced a chunk, so the assertions below only ever saw one row.
        seed("b-a", "ch1", 0, "the vessel foundered and the sea took everything down with it")
        seed("b-b", "ch1", 0, "the sea took everything the vessel carried and kept it forever")
        seed("b-a", "ch2", 1, "a quiet kitchen in the early morning, bread and salt on the table")

        // Chunk off the chapter that is genuinely about the sea.
        val source = chunkRepository.loadVectors(FakeEmbedder.TEST_MODEL.id, FakeEmbedder.TEST_MODEL.dims)
            .first { it.first.bookId == "b-a" && it.first.chapterId == "ch1" }

        val outcome = finder.related(source.first.text, limit = 8, excludeChunkId = source.first.id)
        val ready = outcome as RelatedLookup.Ready

        assertTrue(ready.passages.isNotEmpty(), "a library with vectors must return candidates")
        ready.passages.forEach { passage ->
            assertTrue(passage.bookTitle.isNotBlank(), "book ${passage.bookId} resolved to no title")
        }

        // Every returned passage must carry the `(bookId, chapterId)` pair it was indexed
        // under — never a chapter id glommed onto the wrong book. Asserting the *pair* rather
        // than a flat chapter id is the point of the test: `b-a` and `b-b` both have a `ch1`,
        // and a resolution keyed on the id alone would still satisfy a bare `"ch1"` check.
        assertEquals(
            setOf("b-a" to "ch2", "b-b" to "ch1"),
            ready.passages.map { it.bookId to it.chapterId }.toSet(),
            "book B's ch1 is the near neighbour; book A's ch2 is the only other indexed chapter",
        )
        assertEquals(
            setOf("The Will of the Many", "Shadows Upon Time"),
            ready.passages.map { it.bookTitle }.toSet(),
            "each passage must resolve to its own book's title",
        )
        assertTrue(
            ready.passages.none { it.bookId == "b-a" && it.chapterId == "ch1" },
            "book A's ch1 is the source and must not be offered as its own neighbour",
        )
    }

    @Test
    fun `chapter titles are resolved by book, not by the manifest id alone`() = runBlocking {
        // Both books have a chapter literally called `ch1` with different titles. Resolving by
        // the id alone would hand one book's title to the other.
        //
        // Both texts must clear `TextChunker.MIN_CHUNK_WORDS` (8) or they index *nothing* and
        // the test passes vacuously by finding no candidates at all — which is exactly how this
        // test failed the first time it ran, with a 4-word first book.
        seed("b-a", "ch1", 0, "sea vessel water storm under a black sky and no harbour light")
        seed("b-b", "ch1", 0, "sea vessel water storm and a longer tail of extra words here today")

        val source = chunkRepository.loadVectors(FakeEmbedder.TEST_MODEL.id, FakeEmbedder.TEST_MODEL.dims)
            .first { it.first.bookId == "b-b" }

        val ready = finder.related(source.first.text, limit = 8, excludeChunkId = source.first.id)
            as RelatedLookup.Ready

        val fromA = ready.passages.filter { it.bookId == "b-a" }
        assertTrue(fromA.isNotEmpty(), "book A shares the id, so it must still be a candidate")
        assertEquals(
            listOf("The Fall"), fromA.map { it.chapterTitle },
            "b-a/ch1 is 'The Fall'; 'Anaryan' belongs to b-b and would be the wrong book",
        )
    }

    @Test
    fun `an empty or blank passage is a no-op rather than an embedder call`() = runBlocking {
        // Seeded past MIN_CHUNK_WORDS so the index genuinely has something in it: the assertion
        // is that a blank *input* short-circuits, not that an empty library returns nothing.
        // With a short seed this test passed for the wrong reason.
        seed("b-a", "ch1", 0, "sea vessel water storm under a black sky and no harbour light")
        val outcome = finder.related("   ", limit = 8)
        assertEquals(
            RelatedLookup.Ready(emptyList()), outcome,
            "a blank source should short-circuit, not embed whitespace",
        )
    }
}
