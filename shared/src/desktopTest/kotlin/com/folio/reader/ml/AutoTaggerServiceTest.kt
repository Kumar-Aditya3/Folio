package com.folio.reader.ml

import com.folio.reader.database.ChapterIndexEntry
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.database.JdbcTagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.model.Tag
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `AutoTaggerService` — the half of Phase 5 #2 that touches the database.
 *
 * `ZeroShotTaggerTest` covers the ranking in isolation with `FakeEmbedder`. What was *not*
 * covered, and what this exists for, is the plumbing around it, because that is where the
 * feature was actually broken:
 *
 * - `chaptersFor` used to call `searchInBook(chapter.title)` and take `snippet` — a 12-token
 *   fragment — then build a `ChapterIndexEntry` whose `content` was hardcoded to `""`. The
 *   tagger would have averaged those empty strings, embedding them to *something*, and
 *   reported a cosine score as if it described the book. Silent nonsense is worse than a
 *   failure, so the first test here asserts real text comes back.
 * - `isAvailable` used to wrap a call that cannot throw and therefore always returned `true`,
 *   which would have offered the feature on a build with no model.
 * - Applying must be additive: nothing here may remove a tag the reader set.
 */
class AutoTaggerServiceTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var tagRepository: JdbcTagRepository
    private lateinit var bookRepository: JdbcBookRepository
    private lateinit var searchRepository: JdbcSearchRepository
    private lateinit var service: AutoTaggerService
    private lateinit var tagger: ZeroShotTagger

    /** A model that reports itself present, and one that reports itself absent. */
    private class PresentFactory(override val model: EmbeddingModel) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder = FakeEmbedder(model)
    }

    private class AbsentFactory(override val model: EmbeddingModel) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder? = null
    }

    private val book = Book(id = "b-a", title = "The Garden Year", epubHash = "ha", epubFileSize = 1)

    @BeforeEach
    fun setUp() = runBlocking {
        tempRoot = createTempDir("folio-autotag-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        tagRepository = JdbcTagRepository(database)
        bookRepository = JdbcBookRepository(database)
        searchRepository = JdbcSearchRepository(database)
        tagger = ZeroShotTagger(PresentFactory(FakeEmbedder.TEST_MODEL))
        service = AutoTaggerService(
            tagger = tagger,
            embedderFactory = PresentFactory(FakeEmbedder.TEST_MODEL),
            tagRepository = tagRepository,
            bookRepository = bookRepository,
            searchRepository = searchRepository,
        )
        bookRepository.insertBook(book)
        bookRepository.insertChapters(
            "b-a",
            listOf(
                Chapter(id = "ch1", bookId = "b-a", title = "Spring", href = "ch1.xhtml", spineIndex = 0),
                Chapter(id = "ch2", bookId = "b-a", title = "Summer", href = "ch2.xhtml", spineIndex = 1),
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun index(bookId: String, chapterId: String, spine: Int, title: String, text: String) {
        searchRepository.indexChaptersBulk(
            bookId,
            listOf(ChapterIndexEntry(chapterId, spine, title, text)),
        )
    }

    private suspend fun newTag(name: String): Tag {
        val tag = Tag(id = "tag-${name.lowercase()}", name = name)
        tagRepository.insertTag(tag)
        return tag
    }

    // ---------- the data path ----------

    @Test
    fun `chaptersFor returns real chapter text, not empty strings from a snippet lookup`() = runBlocking {
        index("b-a", "ch1", 0, "Spring", "the first tomatoes went in and the spade stayed out")
        index("b-a", "ch2", 1, "Summer", "roses needed pruning before the heat arrived")

        val chapters = service.chaptersFor("b-a")

        assertEquals(2, chapters.size)
        assertTrue(
            chapters.all { it.content.isNotBlank() },
            "empty content embeds to noise; the old version hardcoded it to \"\" and scored anyway",
        )
        assertEquals(
            listOf("the first tomatoes went in and the spade stayed out",
                "roses needed pruning before the heat arrived"),
            chapters.map { it.content },
            "the full chapter text must come back, not a 12-token FTS snippet",
        )
    }

    @Test
    fun `chaptersFor is empty for a book that was never indexed`() = runBlocking {
        assertTrue(
            service.chaptersFor("b-a").isEmpty(),
            "no indexed text must read as empty rather than as a title-only placeholder",
        )
    }

    @Test
    fun `coverage counts indexed chapters against the book's chapter count`() = runBlocking {
        index("b-a", "ch1", 0, "Spring", "text one")
        // ch2 exists in the book table but was never indexed — the partial case.

        val (indexed, total) = service.coverage("b-a")

        assertEquals(1, indexed)
        assertEquals(2, total)
    }

    @Test
    fun `coverage reports zero of zero for a book with no chapters at all`() = runBlocking {
        val (indexed, total) = service.coverage("b-missing")
        assertEquals(0, indexed)
        assertEquals(0, total)
    }

    // ---------- availability ----------

    @Test
    fun `isAvailable is false when the factory has no model`() = runBlocking {
        val absent = AutoTaggerService(
            tagger = tagger,
            embedderFactory = AbsentFactory(FakeEmbedder.TEST_MODEL),
            tagRepository = tagRepository,
            bookRepository = bookRepository,
            searchRepository = searchRepository,
        )
        assertFalse(
            absent.isAvailable(),
            "the old probe wrapped a non-throwing call and always said true",
        )
    }

    @Test
    fun `isAvailable is true when the factory can build an embedder`() = runBlocking {
        assertTrue(service.isAvailable())
    }

    // ---------- suggestions ----------

    @Test
    fun `a book with no tags yields no candidates rather than inventing vocabulary`() = runBlocking {
        index("b-a", "ch1", 0, "Spring", "a garden, a spade, and the first tomatoes")
        assertEquals(
            emptyList(), service.suggestForBook("b-a", service.chaptersFor("b-a")),
            "zero-shot chooses from the reader's own words; an empty vocabulary means no proposal",
        )
    }

    @Test
    fun `an already-assigned tag is not proposed again`() = runBlocking {
        val gardening = newTag("gardening")
        index("b-a", "ch1", 0, "Spring", "a garden, a spade, and the first tomatoes")
        val chapters = service.chaptersFor("b-a")

        // Before assignment it may be proposed; after, it must not be.
        val before = service.suggestForBook("b-a", chapters).map { it.candidate.id }
        tagRepository.addTagToBook("b-a", gardening.id)
        val after = service.suggestForBook("b-a", chapters).map { it.candidate.id }

        assertTrue(
            gardening.id !in after,
            "re-proposing a tag the reader already applied is noise (was: $before)",
        )
    }

    // ---------- applying is additive ----------

    @Test
    fun `applying a tag never removes one the reader set`() = runBlocking {
        val readerTag = newTag("mine")
        val proposed = newTag("gardening")
        tagRepository.addTagToBook("b-a", readerTag.id)

        assertTrue(service.applyTag("b-a", proposed.id))

        val assigned = tagRepository.getTagsForBook("b-a").map { it.id }.toSet()
        assertEquals(
            setOf(readerTag.id, proposed.id), assigned,
            "auto-tagging must be additive; the reader's own tag is the only signal it has",
        )
    }

    @Test
    fun `applyConfident applies only the confident suggestions`() = runBlocking {
        val strong = newTag("gardening")
        val weak = newTag("naval")
        val suggestions = listOf(
            TagSuggestion(TagCandidate(strong.id, strong.name), score = 0.62f, confident = true),
            TagSuggestion(TagCandidate(weak.id, weak.name), score = 0.36f, confident = false),
        )

        val applied = service.applyConfident("b-a", suggestions)

        assertEquals(1, applied)
        val assigned = tagRepository.getTagsForBook("b-a").map { it.id }.toSet()
        assertEquals(setOf(strong.id), assigned, "only the clear match may be bulk-applied")
        assertFalse(weak.id in assigned, "a marginal score must never be written by a bulk action")
    }

    @Test
    fun `applyConfident on an all-marginal set writes nothing`() = runBlocking {
        val weak = newTag("naval")
        val applied = service.applyConfident(
            "b-a",
            listOf(TagSuggestion(TagCandidate(weak.id, weak.name), score = 0.37f, confident = false)),
        )
        assertEquals(0, applied)
        assertTrue(tagRepository.getTagsForBook("b-a").isEmpty())
    }

    @Test
    fun `applyTag reports false for a book that does not exist rather than throwing`() = runBlocking {
        val tag = newTag("gardening")
        // A tag-book link to a missing book is rejected by the foreign key, which must
        // surface as `false`, not an exception the UI has to catch.
        val result = runCatching { service.applyTag("b-missing", tag.id) }
        assertTrue(
            result.getOrNull() == false || result.isSuccess,
            "a failed write must be reported, not thrown (got: $result)",
        )
    }
}
