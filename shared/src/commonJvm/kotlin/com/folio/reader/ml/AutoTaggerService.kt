package com.folio.reader.ml

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ChapterIndexEntry
import com.folio.reader.database.SearchRepository
import com.folio.reader.database.TagRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Phase 5 #2's "composes with the existing `tagRepository`" clause, made concrete.
 *
 * [ZeroShotTagger] is the pure ranking half — candidates in, ranked suggestions out, no
 * database. This is the half that knows where candidates come from and what applying one
 * means, and keeping them apart is what lets the ranking be tested exhaustively with
 * `FakeEmbedder` while this stays thin enough to read.
 *
 * ### Applying is additive and never destructive
 *
 * [applyTag] adds one tag to one book. Nothing here removes a tag, because a tagger that can
 * un-tag a book is a tagger that can silently undo the reader's own work — and the reader's
 * tags are the *only* signal this feature has. The plan says "assign above a threshold"; it
 * does not say "and take away what the reader chose", and that asymmetry is deliberate.
 */
class AutoTaggerService(
    private val tagger: ZeroShotTagger,
    private val embedderFactory: EmbedderFactory,
    private val tagRepository: TagRepository,
    private val bookRepository: BookRepository,
    private val searchRepository: SearchRepository,
    /** [MlDispatchers.inference]; see [MlDispatchers] for why this is not the generic pool. */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
) {

    val model: EmbeddingModel get() = tagger.model

    /**
     * True when the embedding model is actually on disk.
     *
     * Asking the *factory* rather than probing the tagger: an earlier version wrapped a call
     * that cannot throw and always reported `true`, which meant the UI offered the feature on
     * a build with no model downloaded and then produced nothing when it was pressed.
     */
    suspend fun isAvailable(): Boolean = withContext(dispatcher) {
        embedderFactory.create()?.let { embedder ->
            runCatching { embedder.close() }
            true
        } ?: false
    }

    /**
     * This book's chapters, straight out of the FTS5 index.
     *
     * Reading the plain text back rather than re-parsing the EPUB is what makes this work on
     * any book that is already indexed — no EPUB access, no re-import, and the text is by
     * construction the same text the semantic index was built from.
     *
     * One bulk read, not one query per chapter. The earlier version called `searchInBook` with
     * each chapter's *title* as the query and took the first hit's `snippet` — which is both
     * the wrong data (a 12-token fragment is not the chapter) and O(chapters) round trips, and
     * it produced an entry list whose `content` was always empty. That is worse than useless
     * here: an empty content string still embeds to *something*, so the tagger would have
     * ranked tags against noise and reported a score as if it meant a chapter.
     */
    suspend fun chaptersFor(bookId: String): List<ChapterIndexEntry> = withContext(dispatcher) {
        runCatching { searchRepository.getChapterTexts(bookId) }
            .getOrDefault(emptyList())
    }

    /**
     * How much of the book the tagger can actually see, for the UI to explain itself with.
     *
     * A book whose chapters were never indexed has no text to embed, and the honest thing is
     * to say so rather than propose tags from nothing.
     */
    suspend fun coverage(bookId: String): Pair<Int, Int> = withContext(dispatcher) {
        val indexed = runCatching { searchRepository.getChapterTexts(bookId) }
            .getOrDefault(emptyList())
            .count { it.content.isNotBlank() }
        val total = runCatching { bookRepository.getChaptersForBook(bookId).size }.getOrDefault(0)
        indexed to total
    }

    /**
     * Proposes tags for one book.
     *
     * @param limit how many suggestions to return, already threshold-filtered by the tagger
     */
    suspend fun suggestForBook(
        bookId: String,
        chapters: List<ChapterIndexEntry>,
        limit: Int = ZeroShotTagger.DEFAULT_LIMIT,
    ): List<TagSuggestion> = withContext(dispatcher) {
        val all = runCatching { tagRepository.getAllTags().first() }.getOrDefault(emptyList())
        if (all.isEmpty()) return@withContext emptyList()
        val assigned = runCatching { tagRepository.getTagsForBook(bookId) }
            .getOrDefault(emptyList())
            .map { it.id }
            .toSet()

        val candidates = all.map { TagCandidate(id = it.id, name = it.name, alreadyAssigned = it.id in assigned) }
        tagger.suggestForBook(candidates, chapters, limit)
    }

    /** Adds one suggested tag. Returns false when the write did not happen. */
    suspend fun applyTag(bookId: String, tagId: String): Boolean = withContext(dispatcher) {
        runCatching { tagRepository.addTagToBook(bookId, tagId) }.isSuccess
    }

    /**
     * Applies every suggestion the tagger is reasonably sure of.
     *
     * `confident` rather than "everything above the threshold": bulk-applying the marginal
     * suggestions is how a reader ends up with a library tagged "fantasy" everywhere, and
     * there is no undo for a bulk write that looks like the app's own idea.
     */
    suspend fun applyConfident(bookId: String, suggestions: List<TagSuggestion>): Int =
        withContext(dispatcher) {
            var applied = 0
            suggestions.filter { it.confident }.forEach { suggestion ->
                if (applyTag(bookId, suggestion.candidate.id)) applied++
            }
            applied
        }

    /**
     * Creates a tag that does not exist yet and applies it.
     *
     * Zero-shot can only choose from the reader's existing vocabulary, which means a reader
     * with no tags gets no suggestions at all. Offering the *model's own* labels as
     * creatable tags would be inventing the reader's vocabulary for them, which is the thing
     * zero-shot was chosen to avoid — so this exists only so the UI can let the reader turn a
     * suggestion they agree with into a real tag, and the name always comes from a real tag
     * candidate, never from the model.
     */
    suspend fun createAndApply(bookId: String, tag: com.folio.reader.model.Tag): Boolean =
        withContext(dispatcher) {
            runCatching {
                tagRepository.insertTag(tag)
                tagRepository.addTagToBook(bookId, tag.id)
            }.isSuccess
        }
}
