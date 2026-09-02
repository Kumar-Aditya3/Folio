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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
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
    // Manga side (§11.5): null deps keep the hub book-only, as on desktop before wiring.
    private val observeAllMangaNotes: (() -> Flow<List<MangaNote>>)? = null,
    private val getManga: suspend (String) -> MangaEntry? = { null },
    private val getMangaChapters: suspend (String) -> List<MangaChapter> = { emptyList() }
) {
    private val editScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bumped after a tag edit so open hub queries re-resolve highlight tags. */
    private val tagsRevision = MutableStateFlow(0)
    enum class ViewMode { GRID, LIST }

    data class FilterState(
        val bookId: String? = null,
        val tagIds: Set<String> = emptySet(),
        val searchQuery: String = ""
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawQuotes = flowOf(Unit)
        .flatMapLatest { getAllQuotes() }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    private val allBooksState = getAllBooks().stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun filteredDisplayItems(filter: FilterState): kotlinx.coroutines.flow.Flow<List<QuoteDisplayItem>> {
        return channelFlow {
            combine(rawQuotes, allBooksState, tagsRevision) { quotes, books, _ ->
                Pair(quotes, books)
            }.collect { (quotes, books) ->
                val items = withContext(Dispatchers.IO) {
                    quotes.mapNotNull { quote ->
                        val book = books.find { it.id == quote.bookId } ?: getBook(quote.bookId) ?: return@mapNotNull null
                        val chapters = getChaptersForBook(quote.bookId)
                        val chapter = chapters.find { it.id == quote.chapterId }
                        val highlight = quote.highlightId.takeIf { it.isNotBlank() }?.let { getHighlight(it) }
                        val note = quote.highlightId.takeIf { it.isNotBlank() }?.let { getHighlight(it)?.noteId }?.let { getNote(it) }
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
                    }.filter { item ->
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
                send(items)
            }
        }
    }

    fun allBooks(): List<Book> = allBooksState.value

    suspend fun allTags(): List<Tag> = getAllTags()

    /** Diffs the picker's selection against current highlight tags, then refreshes the hub. */
    fun updateHighlightTags(highlightId: String, selected: Set<String>) {
        editScope.launch {
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
}
