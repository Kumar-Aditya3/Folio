package com.folio.reader.ui.quotes

import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.NoteType
import com.folio.reader.model.Quote
import com.folio.reader.model.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuoteDisplayItem(
    val quote: Quote,
    val book: Book,
    val chapter: Chapter?,
    val highlight: Highlight?,
    val note: Note?,
    val tags: List<Tag>
)

/**
 * Panel state for "more like this".
 *
 * Distinct from [RelatedLookup] because this is the *screen's* state — it has a loading and
 * an idle case that a lookup result does not. Collapsing them is how a UI ends up showing
 * "no similar passages" while the search is still running.
 */
sealed interface RelatedState {
    data object Idle : RelatedState
    data object Loading : RelatedState
    data class Ready(val passages: List<RelatedPassage>) : RelatedState

    /** No embedding model on disk, or no vectors stored for the library yet. */
    data object NotIndexed : RelatedState
    data class Failed(val message: String) : RelatedState
}

class QuoteBrowserViewModel(
    private val getAllQuotes: () -> kotlinx.coroutines.flow.Flow<List<Quote>>,
    private val getBook: suspend (String) -> Book?,
    private val getChaptersForBook: suspend (String) -> List<Chapter>,
    private val getHighlight: suspend (String) -> Highlight?,
    private val getNote: suspend (String) -> Note?,
    private val getTagsForHighlight: suspend (String) -> List<Tag>,
    private val getAllBooks: () -> kotlinx.coroutines.flow.Flow<List<Book>>,
    private val getAllTags: suspend () -> List<Tag>,
    private val addTagToHighlight: suspend (String, String) -> Unit = { _, _ -> },
    private val removeTagFromHighlight: suspend (String, String) -> Unit = { _, _ -> },
    /**
     * "More like this" retrieval (ML_PLAN Phase 5 #3). Optional: without it the affordance
     * is simply not offered, which is how the hub behaves on a build with no model and is
     * also how every existing test keeps working unchanged.
     */
    private val findRelated: (suspend (text: String, limit: Int) -> RelatedLookup)? = null,
    // Manga side (§11.5): null deps keep the hub book-only, as on desktop before wiring.
    private val observeAllMangaNotes: (() -> Flow<List<MangaNote>>)? = null,
    private val getManga: suspend (String) -> MangaEntry? = { null },
    private val getMangaChapters: suspend (String) -> List<MangaChapter> = { emptyList() }
) {
    // One VM-owned scope, cancelled by close(). The two data flows are shared with
    // WhileSubscribed so they stop querying when the hub is off-screen, instead of the old
    // pair of inline never-cancelled Eagerly scopes that collected process-wide forever.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun close() {
        scope.cancel()
    }

    /** Bumped after a tag edit so open hub queries re-resolve highlight tags. */
    private val tagsRevision = MutableStateFlow(0)

    /**
     * Live state of the "more like this" panel.
     *
     * Held as state rather than returned from a suspend call so the screen can render the
     * loading state, and so the lookup survives recomposition while the reader scrolls.
     */
    val relatedState = MutableStateFlow<RelatedState>(RelatedState.Idle)

    /** The quote the panel is currently showing neighbours of; null when closed. */
    val relatedSource = MutableStateFlow<String?>(null)

    /** True when this build can do the lookup at all. */
    val canFindRelated: Boolean get() = findRelated != null
    enum class ViewMode { GRID, LIST }

    data class FilterState(
        val bookId: String? = null,
        val tagIds: Set<String> = emptySet(),
        val searchQuery: String = ""
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawQuotes = flowOf(Unit)
        .flatMapLatest { getAllQuotes() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allBooksState = getAllBooks().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The full resolved item set, computed once per data change (quotes/books/tag edit) — NOT per
     * filter keystroke. Each keystroke's [filteredDisplayItems] is a cheap in-memory predicate over
     * this shared list, so typing no longer re-resolves every quote (≈5 DB reads each) N times.
     */
    private val resolvedItems: kotlinx.coroutines.flow.StateFlow<List<QuoteDisplayItem>> =
        combine(rawQuotes, allBooksState, tagsRevision) { quotes, books, _ -> quotes to books }
            .map { (quotes, books) ->
                withContext(Dispatchers.IO) {
                    // Resolve each distinct book's chapters once, and fetch the highlight once
                    // (the note is derived from it) instead of the old double getHighlight.
                    val chaptersByBook = HashMap<String, List<Chapter>>()
                    quotes.mapNotNull { quote ->
                        val book = books.find { it.id == quote.bookId } ?: getBook(quote.bookId) ?: return@mapNotNull null
                        val chapters = chaptersByBook.getOrPut(quote.bookId) { getChaptersForBook(quote.bookId) }
                        val chapter = chapters.find { it.id == quote.chapterId }
                        val highlight = quote.highlightId.takeIf { it.isNotBlank() }?.let { getHighlight(it) }
                        // A highlight auto-creates a shadow quote (id = "quote-<highlightId>")
                        // that outlives it: `quotes` has no is_deleted column, so soft-deleting
                        // the highlight left its text sitting here forever. Filtered, not
                        // deleted — restoreHighlight has to keep working.
                        if (quote.highlightId.isNotBlank() && (highlight == null || highlight.isDeleted)) {
                            return@mapNotNull null
                        }
                        val note = highlight?.noteId?.let { getNote(it) }
                            ?: if (quote.note != null) Note(
                                id = "inline-${quote.id}",
                                bookId = quote.bookId,
                                chapterId = quote.chapterId,
                                spineIndex = highlight?.spineIndex,
                                locator = highlight?.startLocator,
                                content = quote.note,
                                type = NoteType.HIGHLIGHT_NOTE,
                                deviceId = quote.deviceId
                            ) else null
                        val tags = quote.highlightId.takeIf { it.isNotBlank() }?.let { getTagsForHighlight(it) } ?: emptyList()
                        QuoteDisplayItem(quote, book, chapter, highlight, note, tags)
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun filteredDisplayItems(filter: FilterState): kotlinx.coroutines.flow.Flow<List<QuoteDisplayItem>> =
        resolvedItems.map { items ->
            items.filter { item ->
                // Parenthesized: elvis binds looser than &&, so the old
                // `?: true && ...` shape dropped tag/search filters whenever
                // a book filter was active.
                (filter.bookId == null || item.book.id == filter.bookId) &&
                (filter.tagIds.isEmpty() || item.tags.any { it.id in filter.tagIds }) &&
                (filter.searchQuery.isBlank() ||
                    item.quote.text.contains(filter.searchQuery, ignoreCase = true) ||
                    item.book.title.contains(filter.searchQuery, ignoreCase = true) ||
                    (item.note?.content?.contains(filter.searchQuery, ignoreCase = true) == true))
            }
        }

    fun allBooks(): List<Book> = allBooksState.value

    suspend fun allTags(): List<Tag> = getAllTags()

    /** Diffs the picker's selection against current highlight tags, then refreshes the hub. */
    fun updateHighlightTags(highlightId: String, selected: Set<String>) {
        scope.launch {
            val current = getTagsForHighlight(highlightId).map { it.id }.toSet()
            (current - selected).forEach { removeTagFromHighlight(highlightId, it) }
            (selected - current).forEach { addTagToHighlight(highlightId, it) }
            tagsRevision.value += 1
        }
    }

    /** Manga reader notes rendered as cards; empty when deps are absent or a book/tag filter hides them. */
    fun mangaItems(filter: FilterState): Flow<List<MangaQuoteItem>> {
        val observe = observeAllMangaNotes ?: return flowOf(emptyList())
        return observe().map { notes ->
            if (filter.bookId != null || filter.tagIds.isNotEmpty()) return@map emptyList()
            withContext(Dispatchers.IO) {
                notes.mapNotNull { note ->
                    val manga = getManga(note.mangaId) ?: return@mapNotNull null
                    if (filter.searchQuery.isNotBlank() &&
                        !note.content.contains(filter.searchQuery, ignoreCase = true) &&
                        !manga.title.contains(filter.searchQuery, ignoreCase = true)
                    ) return@mapNotNull null
                    val chapterTitle = getMangaChapters(note.mangaId).find { it.id == note.chapterId }?.name
                    MangaQuoteItem(note, manga.id, manga.title, chapterTitle)
                }
            }
        }
    }

    /**
     * Nearest neighbours of a saved passage — ML_PLAN Phase 5 #3.
     *
     * A highlight is exactly the right item for this: it is a passage the reader already
     * decided was worth keeping, so "find me more of whatever this is" is a question they
     * have already implicitly asked. The lookup is by the quote's own text, so it needs no
     * new model and no new index — only the vectors Phase 3 already wrote.
     *
     * Runs on [scope], which is `Dispatchers.Default`: the plan's rule is that no ML
     * call may run on the composition dispatcher, and an embed of a 250-word passage is
     * exactly the kind of work that made `rememberCoverAccent` cost 10 ms per shelf.
     */
    fun findRelatedFor(item: QuoteDisplayItem) {
        val lookup = findRelated ?: return
        val text = item.quote.text.trim()
        if (text.isEmpty()) return

        relatedSource.value = item.quote.id
        relatedState.value = RelatedState.Loading
        scope.launch {
            val outcome = runCatching { lookup(text, RELATED_LIMIT) }
            relatedState.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is RelatedLookup.Ready -> RelatedState.Ready(result.passages)
                        RelatedLookup.NotIndexed -> RelatedState.NotIndexed
                        is RelatedLookup.Failed -> RelatedState.Failed(result.message)
                    }
                },
                onFailure = { RelatedState.Failed(it.message ?: "The lookup failed.") },
            )
        }
    }

    fun closeRelated() {
        relatedSource.value = null
        relatedState.value = RelatedState.Idle
    }

    companion object {
        /** Suggestions returned by "more like this". Eight is a list, not a search result page. */
        const val RELATED_LIMIT = 8
    }
}
