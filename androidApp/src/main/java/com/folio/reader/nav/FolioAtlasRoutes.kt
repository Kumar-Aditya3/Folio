package com.folio.reader.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.ui.atlas.AtlasScreen
import com.folio.reader.ui.atlas.AtlasViewModel
import com.folio.reader.ui.book.BookHandoff
import com.folio.reader.work.EmbeddingBackfillScheduler

/**
 * Hosts the Atlas full-screen map. Mirrors [SearchRoute]: reads the discovery repo off the graph,
 * drives a per-visit [AtlasViewModel], and wires the two taps — a book region opens Book Detail
 * (seeding [BookHandoff] so the cover morph has a target), an exemplar deep-links into the reader.
 */
@Composable
fun AtlasRoute(
    navModel: FolioNavModelImpl,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenReaderAt: (String, Int?, Float?) -> Unit,
) {
    val graph = navModel.graph
    val context = LocalContext.current
    val viewModel = remember { AtlasViewModel(graph.semanticDiscoveryRepository) }
    val state by viewModel.state.collectAsState()
    val exemplarTexts by viewModel.exemplarTexts.collectAsState()
    val refinedLabels by viewModel.refinedLabels.collectAsState()
    val books by remember { graph.bookRepository.getAllBooks() }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) { viewModel.load() }
    DisposableEffect(viewModel) { onDispose { viewModel.dispose() } }

    AtlasScreen(
        state = state,
        exemplarTexts = exemplarTexts,
        refinedLabels = refinedLabels,
        authorByBook = books.associate { it.id to it.displayAuthor },
        descriptionByBook = books.mapNotNull { b -> b.description?.let { b.id to it } }.toMap(),
        onBack = onBack,
        onOpenBook = { bookId ->
            // Seed the handoff so Book Detail's shared cover exists on its first frame.
            books.firstOrNull { it.id == bookId }?.let { BookHandoff.offer(it) }
            onOpenBook(bookId)
        },
        onOpenExemplar = { bookId, spine, frac -> onOpenReaderAt(bookId, spine, frac) },
        onLoadExemplars = { ids -> viewModel.loadExemplars(ids) },
        onRefineBooks = { books -> viewModel.refineVisibleBooks(books) },
        onRunBackfill = { EmbeddingBackfillScheduler.runNow(context) },
    )
}
