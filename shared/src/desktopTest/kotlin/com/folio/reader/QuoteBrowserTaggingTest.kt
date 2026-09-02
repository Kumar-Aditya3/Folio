package com.folio.reader

import com.folio.reader.model.Book
import com.folio.reader.model.Highlight
import com.folio.reader.model.Quote
import com.folio.reader.model.Tag
import com.folio.reader.ui.quotes.QuoteBrowserViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §12 remainder: highlight tag editing from the Quotes hub, plus the filter
 * regression where an active book filter silently dropped the tag and search
 * filters (elvis-binds-looser-than-&&).
 */
class QuoteBrowserTaggingTest {

    private val books = listOf(
        Book(id = "b-1", title = "Alpha", epubHash = "h1", epubFileSize = 1),
        Book(id = "b-2", title = "Beta", epubHash = "h2", epubFileSize = 1),
    )

    private val quotes = listOf(
        Quote(id = "q-1", bookId = "b-1", chapterId = "c-1", highlightId = "hl-1", text = "the sea", deviceId = "d"),
        Quote(id = "q-2", bookId = "b-2", chapterId = "c-1", highlightId = "hl-2", text = "the sea", deviceId = "d"),
        Quote(id = "q-3", bookId = "b-1", chapterId = "c-1", highlightId = "", text = "mountains", deviceId = "d"),
    )

    /** Highlight links: q-1 and q-2 point at tagged highlights, q-3 has none. */
    private val highlightTags = mutableMapOf(
        "hl-1" to mutableListOf("tag-a"),
        "hl-2" to mutableListOf<String>(),
    )

    private val tagNames = mapOf(
        "tag-a" to "Favourites",
        "tag-b" to "Research",
    )

    private fun newVm() = QuoteBrowserViewModel(
        getAllQuotes = { flowOf(quotes) },
        getBook = { id -> books.find { it.id == id } },
        getChaptersForBook = { emptyList() },
        getHighlight = { id ->
            Highlight(
                id = id,
                bookId = "b-1",
                chapterId = "c-1",
                spineIndex = 0,
                startLocator = "",
                endLocator = "",
                selectedText = "s",
                deviceId = "d",
            )
        },
        getNote = { null },
        getTagsForHighlight = { id -> (highlightTags[id] ?: emptyList()).map { Tag(it, tagNames[it] ?: it) } },
        getAllBooks = { flowOf(books) },
        getAllTags = { tagNames.map { (id, name) -> Tag(id, name) } },
        addTagToHighlight = { hl, tag -> highlightTags.getOrPut(hl) { mutableListOf() }.add(tag) },
        removeTagFromHighlight = { hl, tag -> highlightTags[hl]?.remove(tag) },
    )

    private suspend fun awaitItems(
        vm: QuoteBrowserViewModel,
        filter: QuoteBrowserViewModel.FilterState,
        message: String,
        timeoutMs: Long = 8000,
        predicate: (List<String>) -> Boolean,
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ids = vm.filteredDisplayItems(filter).first().map { it.quote.id }
            if (predicate(ids)) return ids
            if (System.currentTimeMillis() > deadline) fail("Timed out: $message (last=$ids)")
            delay(25)
        }
    }

    @Test
    fun updateHighlightTagsDiffsAndTheHubRerenders() = runBlocking {
        val vm = newVm()
        vm.updateHighlightTags("hl-1", setOf("tag-b"))

        awaitItems(vm, QuoteBrowserViewModel.FilterState(), message = "hub re-resolves tags after the edit") { ids ->
            ids.contains("q-1") && highlightTags["hl-1"] == mutableListOf("tag-b")
        }
        val tags = vm.filteredDisplayItems(QuoteBrowserViewModel.FilterState())
            .first().first { it.quote.id == "q-1" }.tags
        assertEquals(listOf("tag-b"), tags.map { it.id }, "q-1 shows the swapped tag, not the stale one")

        // Saving the same selection again is a no-op (diff against current).
        vm.updateHighlightTags("hl-1", setOf("tag-b"))
        assertEquals(mutableListOf("tag-b"), highlightTags["hl-1"], "no churn on redundant save")
    }

    @Test
    fun bookFilterRespectsSearchAndTagFilters() = runBlocking {
        val vm = newVm()

        awaitItems(vm, QuoteBrowserViewModel.FilterState(), message = "unfiltered hub shows all quotes") {
            it.size == 3
        }

        val searched = awaitItems(
            vm, QuoteBrowserViewModel.FilterState(bookId = "b-1", searchQuery = "sea"),
            message = "book+search narrow together"
        ) { it == listOf("q-1") }
        assertEquals(listOf("q-1"), searched, "search must still apply under a book filter")

        val tagged = awaitItems(
            vm, QuoteBrowserViewModel.FilterState(bookId = "b-1", tagIds = setOf("tag-b")),
            message = "book+tag narrow together"
        ) { it.isEmpty() }
        assertTrue(tagged.isEmpty(), "q-1 has tag-a, not tag-b, so nothing matches")
    }

    @Test
    fun quotesWithoutHighlightsNeverShowTagFilters() = runBlocking {
        val vm = newVm()
        val tagFiltered = awaitItems(vm, QuoteBrowserViewModel.FilterState(tagIds = setOf("tag-a")), message = "tag filter applies") {
            it == listOf("q-1")
        }
        assertEquals(listOf("q-1"), tagFiltered, "tag-a matches only q-1")
        val bothTags = awaitItems(vm, QuoteBrowserViewModel.FilterState(tagIds = setOf("tag-a", "tag-b")), message = "untagged q-3 is excluded") {
            it == listOf("q-1")
        }
        assertEquals(listOf("q-1"), bothTags, "q-3 has no highlight, so it never matches tag filters")
    }
}
