package com.folio.reader.ml

import com.folio.reader.database.ChapterIndexEntry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ML_PLAN Phase 5 #2 — zero-shot auto-tagging.
 *
 * The plan's specified shape is "embed the candidate tag names once, cosine against the book
 * vector, assign above a threshold", and every one of those three clauses has a way to go
 * wrong that looks like success:
 *
 * - **The book vector.** A 150-chapter book does not fit in 256 tokens, so "embed the book"
 *   silently means "embed its first chapter" unless the caller averages chapters instead.
 *   [a book vector is the mean of its chapters, not its opening words] pins that.
 * - **The threshold.** Unrelated English prose with MiniLM scores ~0.1-0.2, not ~0, so a
 *   threshold in that band tags *everything*. [an unrelated tag is not assigned] pins that.
 * - **The already-assigned case.** A tag the book already carries is not a suggestion, and
 *   showing it as one makes the list look like it did nothing.
 *
 * `FakeEmbedder` is a real hashing vectoriser rather than a stub, so these assertions are
 * about ranking behaviour and not merely about the plumbing running.
 */
class ZeroShotTaggerTest {

    private class FakeFactory(override val model: EmbeddingModel = FakeEmbedder.TEST_MODEL) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder = FakeEmbedder(model)
    }

    private fun tagger(threshold: Float = 0f) = ZeroShotTagger(FakeFactory(), threshold = threshold)

    private fun chapter(text: String, id: String = "c-1") =
        ChapterIndexEntry(chapterId = id, spineIndex = 0, title = "Chapter", content = text)

    @Test
    fun `a tag sharing the book's vocabulary outranks one that shares nothing`() = runBlocking {
        val suggestions = tagger().suggestFor(
            candidates = listOf(
                TagCandidate("t-1", "naval adventure"),
                TagCandidate("t-2", "gardening"),
            ),
            text = "the ship sailed into open water, the naval crew rigged the sails for adventure",
        )

        assertTrue(suggestions.isNotEmpty(), "at least the matching tag must survive a zero threshold")
        assertEquals(
            "naval adventure", suggestions.first().candidate.name,
            "the tag whose words are in the passage must lead a tag with no shared vocabulary",
        )
    }

    @Test
    fun `an unrelated tag is not assigned`() = runBlocking {
        // The default threshold, not zero. This is the assertion that would fail if the
        // threshold were set inside the ~0.1-0.2 band that unrelated prose actually scores.
        val suggestions = ZeroShotTagger(FakeFactory()).suggestFor(
            candidates = listOf(TagCandidate("t-1", "quantum chromodynamics")),
            text = "the ship sailed into open water and the naval crew rigged the sails",
        )

        assertTrue(
            suggestions.isEmpty(),
            "an unrelated tag scored ${suggestions.firstOrNull()?.score}; the default threshold " +
                "must exclude the band that unrelated English prose occupies",
        )
    }

    @Test
    fun `a tag the book already has is not suggested`() = runBlocking {
        val suggestions = tagger().suggestFor(
            candidates = listOf(
                TagCandidate("t-1", "naval adventure", alreadyAssigned = true),
                TagCandidate("t-2", "naval adventure extra"),
            ),
            text = "the ship sailed into open water, the naval crew rigged the sails for adventure",
        )

        assertTrue(
            suggestions.none { it.candidate.id == "t-1" },
            "an already-applied tag is not a suggestion, even when it scores highest",
        )
    }

    @Test
    fun `a book vector is the mean of its chapters, not its opening words`() = runBlocking {
        // Chapter 1 is a naval prologue; chapters 2..n are the actual subject. Embedding the
        // concatenation would truncate before chapter 2 and never see the theme at all.
        val chapters = buildList {
            add(chapter("the ship sailed into open water and the crew rigged sails", "c-1"))
            repeat(8) { i ->
                add(chapter("the kitchen garden grew tomatoes, basil and quiet herbs", "c-${i + 2}"))
            }
        }

        val suggestions = tagger().suggestForBook(
            candidates = listOf(
                TagCandidate("t-1", "naval adventure"),
                TagCandidate("t-2", "gardening and herbs"),
            ),
            chapters = chapters,
        )

        assertTrue(suggestions.isNotEmpty(), "the mean should still match the dominant subject")
        assertEquals(
            "gardening and herbs", suggestions.first().candidate.name,
            "eight gardening chapters outweigh one naval prologue; a first-chapter-only " +
                "vector would return the opposite",
        )
    }

    @Test
    fun `scores are ordered and the leader is marked confident only by a margin`() = runBlocking {
        val suggestions = tagger().suggestForBook(
            candidates = listOf(
                TagCandidate("t-1", "gardening"),
                TagCandidate("t-2", "herbs"),
                TagCandidate("t-3", "kitchen"),
                TagCandidate("t-4", "a tag about absolutely nothing related at all"),
            ),
            chapters = listOf(
                chapter("the kitchen garden grew tomatoes, basil and quiet herbs all summer"),
                chapter("more kitchen garden herbs and tomatoes in the quiet garden", "c-2"),
            ),
        )

        assertEquals(
            suggestions.map { it.score }, suggestions.map { it.score }.sortedDescending(),
            "suggestions must come back ordered by score",
        )
        assertTrue(
            suggestions.count { it.confident } <= 1,
            "at most one suggestion may be presented as the answer",
        )
        suggestions.drop(1).forEach {
            assertTrue(!it.confident, "'confident' on a runner-up would make the flag meaningless")
        }
    }

    @Test
    fun `an empty candidate list or an empty book is a no-op`() = runBlocking {
        assertEquals(
            emptyList(),
            tagger().suggestFor(emptyList(), text = "anything at all"),
            "no candidate tags means no work and no embedder call",
        )
        assertEquals(
            emptyList(),
            tagger().suggestForBook(listOf(TagCandidate("t-1", "naval")), chapters = emptyList()),
            "a book with no indexed chapters cannot be tagged",
        )
    }
}
