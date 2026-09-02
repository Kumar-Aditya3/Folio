package com.folio.reader.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.folio.reader.ui.quotes.QuoteBrowserScreen
import com.folio.reader.ui.quotes.QuoteBrowserViewModel
import com.folio.reader.ui.revisit.RevisitItemsScreen
import com.folio.reader.ui.revisit.RevisitItemsViewModel
import com.folio.reader.ui.tags.TagManagerScreen
import com.folio.reader.ui.tags.TagManagerViewModel
import kotlinx.coroutines.flow.first

@Composable
fun TagsRoute(
    navModel: FolioNavModelImpl,
    onBack: () -> Unit,
    onOpenBookDetail: (String) -> Unit,
    onOpenReader: (String) -> Unit
) {
    val graph = navModel.graph
    TagManagerScreen(
        onBack = onBack,
        onHighlightClick = { _, book -> onOpenReader(book.id) },
        onBookClick = { book -> onOpenBookDetail(book.id) },
        viewModel = remember {
            TagManagerViewModel(
                getAllTags = { graph.tagRepository.getAllTags().first() },
                insertTag = { graph.tagRepository.insertTag(it) },
                updateTag = { graph.tagRepository.updateTag(it) },
                deleteTag = { graph.tagRepository.deleteTag(it) },
                getTagsForBook = { graph.tagRepository.getTagsForBook(it) },
                getTagsForHighlight = { graph.tagRepository.getTagsForHighlight(it) },
                getBooksForTag = { graph.tagRepository.getBooksForTag(it) },
                getHighlightsForTag = { graph.tagRepository.getHighlightsForTag(it) },
                getBook = { graph.bookRepository.getBook(it) }
            )
        }
    )
}

@Composable
fun QuotesRoute(navModel: FolioNavModelImpl, onBack: () -> Unit, onOpenReader: (String) -> Unit) {
    val graph = navModel.graph
    QuoteBrowserScreen(
        onBack = onBack,
        onQuoteClick = { item -> onOpenReader(item.book.id) },
        viewModel = remember {
            QuoteBrowserViewModel(
                getAllQuotes = { graph.quoteRepository.getAllQuotes() },
                getBook = { graph.bookRepository.getBook(it) },
                getChaptersForBook = { graph.bookRepository.getChaptersForBook(it) },
                getHighlight = { graph.highlightRepository.getHighlight(it) },
                getNote = { graph.noteRepository.getNote(it) },
                getTagsForHighlight = { graph.tagRepository.getTagsForHighlight(it) },
                getAllBooks = { graph.bookRepository.getAllBooks() },
                getAllTags = { graph.tagRepository.getAllTags().first() }
            )
        }
    )
}

@Composable
fun RevisitRoute(navModel: FolioNavModelImpl, onBack: () -> Unit, onOpenReader: (String) -> Unit) {
    val graph = navModel.graph
    RevisitItemsScreen(
        onBack = onBack,
        onItemClick = { item -> onOpenReader(item.book.id) },
        viewModel = remember {
            RevisitItemsViewModel(
                getUnresolvedRevisitItems = { graph.revisitRepository.getUnresolvedRevisitItems() },
                resolveRevisitItem = { graph.revisitRepository.resolveRevisitItem(it) },
                getBook = { graph.bookRepository.getBook(it) },
                getChaptersForBook = { graph.bookRepository.getChaptersForBook(it) },
                getHighlight = { graph.highlightRepository.getHighlight(it) },
                getBookmark = { graph.bookmarkRepository.getBookmark(it) },
                getNote = { graph.noteRepository.getNote(it) }
            )
        }
    )
}
