package com.folio.reader.ui.book

import com.folio.reader.database.BookRepository
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SeriesRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.ml.AutoTaggerService
import com.folio.reader.ml.TagSuggestion
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.UUID

class BookDetailViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val seriesRepository: SeriesRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository,
    /**
     * Phase 5 #2. Null on a build with no embedding model wired, in which case the reader
     * never sees the suggestion affordance rather than seeing one that does nothing.
     */
    private val autoTagger: AutoTaggerService? = null,
) {
    private val _book = MutableStateFlow<Book?>(null)
    private val _sessions = MutableStateFlow<List<ReadingSession>>(emptyList())
    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _series = MutableStateFlow<Series?>(null)
    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    private val _availableTags = MutableStateFlow<List<Tag>>(emptyList())
    private val _availableSeries = MutableStateFlow<List<Series>>(emptyList())
    private val _availableCollections = MutableStateFlow<List<Collection>>(emptyList())

    // A StateFlow, not a bare Flow: the screen reads it with the no-initial
    // collectAsState overload, which paints the *current* value (a seed, if one was
    // handed over) on the destination's very first frame. As a plain Flow the read
    // fell back to `initial = null` and the seeded book only arrived a frame later —
    // the cover morph had no target when the flight began, so the thumbnail zoomed
    // out and the header text flashed in late. See seed()/BookHandoff.
    val book: StateFlow<Book?> = _book.asStateFlow()
    val sessions: Flow<List<ReadingSession>> = _sessions.asStateFlow()
    val highlights: Flow<List<Highlight>> = _highlights.asStateFlow()
    val bookmarks: Flow<List<Bookmark>> = _bookmarks.asStateFlow()
    val notes: Flow<List<Note>> = _notes.asStateFlow()
    val series: Flow<Series?> = _series.asStateFlow()
    val collections: Flow<List<Collection>> = _collections.asStateFlow()
    val tags: Flow<List<Tag>> = _tags.asStateFlow()
    val availableTags: Flow<List<Tag>> = _availableTags.asStateFlow()
    val availableSeries: Flow<List<Series>> = _availableSeries.asStateFlow()
    val availableCollections: Flow<List<Collection>> = _availableCollections.asStateFlow()

    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentBookId: String? = null

    /**
     * Pre-populate the book from the tapped list item, before any DB read. Must
     * run during composition (not a LaunchedEffect) so the header — and with it
     * the shared cover/title the §17 morph flies to — is composed on the very
     * first frame of the detail destination, i.e. before the enter transition
     * starts. Seeded a frame late (from an effect) the morph target does not
     * exist when the flight begins and the cover has nothing to land on.
     */
    fun seed(book: Book) {
        if (_book.value == null) _book.value = book
    }

    fun loadBook(bookId: String) {
        currentBookId = bookId
        viewModelScope.launch {
            val loadedBook = bookRepository.getBook(bookId)
            _book.value = loadedBook

            _series.value = loadedBook?.seriesId?.let { seriesRepository.getSeries(it) }
            _availableSeries.value = seriesRepository.getAllSeries().first()
            _availableCollections.value = collectionRepository.getAllCollections().first()

            launch {
                sessionRepository.getSessionsForBook(bookId).collect { _sessions.value = it }
            }
            launch {
                bookmarkRepository.getBookmarksForBook(bookId).collect { _bookmarks.value = it }
            }
            launch {
                highlightRepository.getHighlightsForBook(bookId).collect { _highlights.value = it }
            }
            launch {
                noteRepository.getNotesForBook(bookId).collect { _notes.value = it }
            }
            launch {
                _collections.value = collectionRepository.getCollectionsForBook(bookId)
            }
            launch {
                _tags.value = tagRepository.getTagsForBook(bookId)
            }
            launch {
                _availableTags.value = tagRepository.getAllTags().first()
            }
        }
    }

    fun saveMetadata(
        title: String,
        subtitle: String,
        authors: String,
        publisher: String,
        language: String,
        isbn: String,
        description: String,
        seriesId: String?,
        seriesNumber: String,
        collectionIds: Set<String>,
        newSeriesName: String,
        newCollectionName: String
    ) {
        val bookId = currentBookId ?: return
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) return

        viewModelScope.launch {
            val current = bookRepository.getBook(bookId) ?: return@launch
            val createdSeries = newSeriesName.trim().takeIf { it.isNotEmpty() }?.let { name ->
                seriesRepository.getSeriesByName(name) ?: Series(id = UUID.randomUUID().toString(), name = name)
                    .also { seriesRepository.insertSeries(it) }
            }
            val createdCollection = newCollectionName.trim().takeIf { it.isNotEmpty() }?.let { name ->
                collectionRepository.getCollectionByName(name) ?: Collection(id = UUID.randomUUID().toString(), name = name)
                    .also { collectionRepository.insertCollection(it) }
            }
            val selectedSeriesId = createdSeries?.id ?: seriesId
            val selectedCollectionIds = collectionIds + listOfNotNull(createdCollection?.id)
            val number = seriesNumber.trim().toDoubleOrNull()

            val updated = current.copy(
                title = normalizedTitle,
                subtitle = subtitle.blankToNull(),
                authors = authors.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                publisher = publisher.blankToNull(),
                language = language.blankToNull(),
                isbn = isbn.blankToNull(),
                description = description.blankToNull(),
                seriesId = selectedSeriesId,
                seriesNumber = number,
                updatedAt = Clock.System.now()
            ).also { it.updateProgress(current.normalizedProgress) }
            bookRepository.updateBook(updated)

            // Full membership replace (the manga picker's assign contract). An
            // empty selection falls back to the default collection, so a book
            // can be moved off Main but never left without a shelf.
            val targetCollectionIds = selectedCollectionIds.ifEmpty {
                collectionRepository.defaultCollection()?.let { default -> setOf(default.id) } ?: emptySet()
            }
            if (targetCollectionIds.isNotEmpty()) {
                collectionRepository.assign(bookId, targetCollectionIds)
            }
            refreshMetadata(bookId)
        }
    }

    private suspend fun refreshMetadata(bookId: String) {
        val refreshed = bookRepository.getBook(bookId)
        _book.value = refreshed
        _series.value = refreshed?.seriesId?.let { seriesRepository.getSeries(it) }
        _collections.value = collectionRepository.getCollectionsForBook(bookId)
        _availableSeries.value = seriesRepository.getAllSeries().first()
        _availableCollections.value = collectionRepository.getAllCollections().first()
    }

    /** Diff-assigns tags the same way saveMetadata syncs collections. */
    fun updateBookTags(selectedIds: Set<String>) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val currentIds = tagRepository.getTagsForBook(bookId).mapTo(mutableSetOf()) { it.id }
            (currentIds - selectedIds).forEach { tagRepository.removeTagFromBook(bookId, it) }
            (selectedIds - currentIds).forEach { tagRepository.addTagToBook(bookId, it) }
            _tags.value = tagRepository.getTagsForBook(bookId)
            _availableTags.value = tagRepository.getAllTags().first()
        }
    }

    // ---------- Auto-tagging (ML_PLAN Phase 5 #2) ----------

    private val _tagSuggestions = MutableStateFlow<TagSuggestionState>(TagSuggestionState.Idle)

    /** The suggestion panel's state. Idle means "not asked yet"; the panel is closed. */
    val tagSuggestions: Flow<TagSuggestionState> = _tagSuggestions.asStateFlow()

    /** True when there is a tagger *and* a model on disk — the button is only shown then. */
    val canSuggestTags: Boolean get() = autoTagger != null

    /**
     * Proposes tags for this book.
     *
     * Runs the whole thing — chapter read, embedding, cosine — off the UI thread, which is why
     * this is a `viewModelScope.launch` into a suspend function rather than anything inline.
     * `FakeEmbedder`-backed tests exercise the ranking directly; this is the wiring.
     *
     * The result is deliberately a *proposal*. Nothing is assigned here: `applySuggestion` is
     * the only writer, and it is only ever called from a tap. The plan says "assign above a
     * threshold", but the tags are the reader's own vocabulary and a silent bulk write has no
     * undo — so the threshold decides what is *offered*, and the reader decides what is kept.
     */
    fun suggestTags() {
        val bookId = currentBookId ?: return
        val tagger = autoTagger ?: return
        viewModelScope.launch {
            if (!tagger.isAvailable()) {
                _tagSuggestions.value = TagSuggestionState.Unavailable(
                    "Download the embedding model in Settings → Semantic search first."
                )
                return@launch
            }
            _tagSuggestions.value = TagSuggestionState.Loading
            val chapters = tagger.chaptersFor(bookId)
            val (indexed, total) = tagger.coverage(bookId)
            if (indexed == 0) {
                // Not "no tags matched" — there was nothing to match against. Saying the
                // former would blame the reader's tag list for a missing index.
                _tagSuggestions.value = TagSuggestionState.NotIndexed(total)
                return@launch
            }
            val suggestions = tagger.suggestForBook(bookId, chapters)
            _tagSuggestions.value = if (suggestions.isEmpty()) {
                TagSuggestionState.NoMatch
            } else {
                TagSuggestionState.Ready(suggestions)
            }
        }
    }

    /** Applies one suggestion. Additive — it never removes a tag the reader set. */
    fun applySuggestion(suggestion: TagSuggestion) {
        val bookId = currentBookId ?: return
        val tagger = autoTagger ?: return
        viewModelScope.launch {
            val applied = tagger.applyTag(bookId, suggestion.candidate.id)
            if (applied) {
                _tags.value = tagRepository.getTagsForBook(bookId)
                // Drop it from the panel so it cannot be applied twice and the list visibly
                // reflects the write.
                _tagSuggestions.value = _tagSuggestions.value.withoutSuggestion(suggestion)
            }
        }
    }

    /** Applies every suggestion the tagger was reasonably sure of. */
    fun applyConfidentSuggestions() {
        val bookId = currentBookId ?: return
        val tagger = autoTagger ?: return
        val ready = _tagSuggestions.value as? TagSuggestionState.Ready ?: return
        viewModelScope.launch {
            tagger.applyConfident(bookId, ready.suggestions)
            _tags.value = tagRepository.getTagsForBook(bookId)
            _tagSuggestions.value = TagSuggestionState.Ready(
                ready.suggestions.filterNot { it.confident }
            )
        }
    }

    fun dismissSuggestions() {
        _tagSuggestions.value = TagSuggestionState.Idle
    }

    private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }
}

/**
 * The suggestion panel's state.
 *
 * [NotIndexed] and [NoMatch] are separate cases on purpose, and the same distinction
 * `RelatedLookup` makes: "this book has not been indexed" is a prerequisite the reader can
 * fix, while "none of your tags matched" is an answer about their vocabulary. Collapsing them
 * into an empty list would tell the reader their tags are wrong when the real problem is that
 * they have not built the index.
 */
sealed interface TagSuggestionState {
    data object Idle : TagSuggestionState
    data object Loading : TagSuggestionState
    data class Ready(val suggestions: List<TagSuggestion>) : TagSuggestionState
    data object NoMatch : TagSuggestionState
    data class NotIndexed(val totalChapters: Int) : TagSuggestionState
    data class Unavailable(val reason: String) : TagSuggestionState

    val isVisible: Boolean get() = this !is Idle
}

private fun TagSuggestionState.withoutSuggestion(applied: TagSuggestion): TagSuggestionState =
    if (this is TagSuggestionState.Ready) {
        copy(suggestions = suggestions.filterNot { it.candidate.id == applied.candidate.id })
    } else {
        this
    }
