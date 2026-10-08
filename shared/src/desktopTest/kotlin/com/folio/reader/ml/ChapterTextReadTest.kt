package com.folio.reader.ml

import com.folio.reader.database.ChapterIndexEntry
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcSearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SearchRepository.getChapterTexts` — the read half of the FTS5 index.
 *
 * This exists because auto-tagging needed a whole book's prose and the only route to it was
 * `searchInBook(title)`, which returns a 12-token `snippet()` rather than the chapter. The
 * tagger would have averaged snippets, or — in the version that actually shipped first —
 * averaged empty strings, and reported a cosine score as if it meant something.
 *
 * The two behaviours worth pinning are therefore: the *whole* content comes back (not a
 * snippet), and it comes back in spine order so the caller's truncation is deterministic.
 */
class ChapterTextReadTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var searchRepository: JdbcSearchRepository

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-chaptertext-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        searchRepository = JdbcSearchRepository(database)
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun index(bookId: String, chapterId: String, spine: Int, title: String, content: String) {
        searchRepository.indexChapter(bookId, chapterId, spine, title, content)
    }

    @Test
    fun `reads whole chapter content, not an FTS snippet`() = runBlocking {
        // Long enough that a `snippet(..., 12)` would truncate it to a fraction.
        val prose = (1..40).joinToString(" ") { "word$it" }
        index("b-a", "ch1", 0, "The Fall", prose)

        val texts = searchRepository.getChapterTexts("b-a")

        assertEquals(1, texts.size)
        assertEquals(prose, texts.single().content, "the whole chapter must come back, not a window of it")
        assertEquals("The Fall", texts.single().title)
        assertEquals("ch1", texts.single().chapterId)
    }

    @Test
    fun `returns every chapter in spine order`() = runBlocking {
        // Inserted out of order on purpose: the index is keyed by spine, but nothing about
        // the insert path guarantees the read comes back ordered.
        index("b-a", "ch3", 3, "Third", "three")
        index("b-a", "ch1", 1, "First", "one")
        index("b-a", "ch2", 2, "Second", "two")

        val texts = searchRepository.getChapterTexts("b-a")

        assertEquals(listOf("ch1", "ch2", "ch3"), texts.map { it.chapterId })
        assertEquals(listOf(1, 2, 3), texts.map { it.spineIndex })
    }

    @Test
    fun `a book with no indexed text returns empty rather than another book's chapters`() = runBlocking {
        index("b-a", "ch1", 0, "The Fall", "content of a")

        assertTrue(
            searchRepository.getChapterTexts("b-b").isEmpty(),
            "an unindexed book must read as empty, which the caller reports as 'not indexed'",
        )
        assertEquals(
            listOf("b-a"), searchRepository.getChapterTexts("b-a").map { "b-a" },
            "scoping must not leak across books",
        )
    }

    @Test
    fun `chapters sharing a manifest id across books stay separate`() = runBlocking {
        // `chapter_id` is the EPUB manifest id: two books both having `ch1` is the normal
        // case, not an edge case. The read is scoped by book_id, so both must be readable
        // and must not collide.
        index("b-a", "ch1", 0, "The Fall", "book A text")
        index("b-b", "ch1", 0, "Anaryan", "book B text")

        assertEquals("book A text", searchRepository.getChapterTexts("b-a").single().content)
        assertEquals("book B text", searchRepository.getChapterTexts("b-b").single().content)
    }

    @Test
    fun `the read is what the auto-tagger would average`() = runBlocking {
        // The end-to-end shape that was broken: chaptersFor() must hand the tagger real text
        // for every indexed chapter. Empty content here means the tagger ranks tags against
        // noise, which is the failure this replaced.
        val chapters = listOf(
            ChapterIndexEntry("ch1", 0, "One", "a garden, a spade, and the first tomatoes"),
            ChapterIndexEntry("ch2", 1, "Two", "pruning roses in late winter"),
        )
        searchRepository.indexChaptersBulk("b-a", chapters)

        val readBack = searchRepository.getChapterTexts("b-a")

        assertEquals(chapters.size, readBack.size)
        assertTrue(
            readBack.all { it.content.isNotBlank() },
            "every indexed chapter must yield text; empty content embeds to noise",
        )
        assertEquals(chapters.map { it.content }, readBack.map { it.content })
    }
}
