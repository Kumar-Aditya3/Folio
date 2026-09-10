package com.folio.reader.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.folio.reader.model.Highlight
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.render.HtmlContentSurface
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import java.io.File

@Composable
fun DocumentReaderScreen(
    viewModel: DocumentReaderViewModel,
    settings: ReaderSettings = ReaderSettings(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var resetZoomKey by remember { mutableIntStateOf(0) }
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    Column(modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        if (state.controlsVisible) {
            DocumentReaderBar(state, onBack, viewModel::toggleBookmark)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val load = state.loadState) {
                DocumentReaderLoadState.Loading -> LoadingPlaceholder(Modifier.align(Alignment.Center))
                is DocumentReaderLoadState.Error -> EmptyState(
                    Icons.Default.BrokenImage,
                    errorHeadline(load.error.kind),
                    load.error.message,
                    Modifier.align(Alignment.Center)
                )
                is DocumentReaderLoadState.Ready -> when (val content = load.content) {
                    is DocumentReaderContent.Pdf -> FixedPageContentSurface(
                        content.path, state.document!!.id, state.currentPage, state.mode,
                        state.rotationDegrees, resetZoomKey, Modifier.fillMaxSize(), viewModel::onPdfOpened,
                        viewModel::setCurrentPage,
                        onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
                        onError = viewModel::reportError
                    )
                    is DocumentReaderContent.Reflowable -> HtmlContentSurface(
                        html = content.html,
                        chapterHref = content.chapterHref,
                        settings = settings,
                        position = state.reflowableLocator?.toSurfacePosition(state.document!!.id, state.normalizedProgress),
                        highlights = emptyList<Highlight>(),
                        enabled = true,
                        modifier = Modifier.fillMaxSize(),
                        onProgress = viewModel::updateReflowableProgress,
                        onPageChange = { _, _ -> },
                        onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
                        onLinkClick = null,
                        onResolveResource = { _, src -> resolveDocumentResource(viewModel, src) }
                    )
                }
            }
        }
        if (
            state.controlsVisible &&
            state.document?.format == com.folio.reader.model.DocumentFormat.PDF
        ) {
            DocumentReaderControls(
                state = state,
                onMode = {
                    viewModel.setMode(
                        if (state.mode == DocumentReaderMode.SINGLE_PAGE) {
                            DocumentReaderMode.CONTINUOUS
                        } else {
                            DocumentReaderMode.SINGLE_PAGE
                        }
                    )
                },
                onRotate = viewModel::rotateClockwise,
                onPage = viewModel::setCurrentPage,
                onResetZoom = { resetZoomKey++ }
            )
        }
    }
}

@Composable
private fun DocumentReaderControls(
    state: DocumentReaderState,
    onMode: () -> Unit,
    onRotate: () -> Unit,
    onPage: (Int) -> Unit,
    onResetZoom: () -> Unit
) {
    Column(Modifier.fillMaxWidth().folioVeil().padding(FolioTokens.space3)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onMode) {
                Icon(
                    if (state.mode == DocumentReaderMode.SINGLE_PAGE) Icons.Default.ViewAgenda else Icons.Default.GridView,
                    contentDescription = null
                )
                Text(if (state.mode == DocumentReaderMode.SINGLE_PAGE) "Continuous" else "Single page")
            }
            Text(
                if (state.pageCount > 0) "Page ${state.currentPage + 1} of ${state.pageCount}" else "Loading pages",
                color = FolioTheme.colors.onSurface
            )
            IconButton(onClick = onRotate) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate pages")
            }
        }
        if (state.pageCount > 1) {
            FolioSlider(
                value = state.currentPage.toFloat(),
                onValueChange = { onPage(it.toInt()) },
                valueRange = 0f..(state.pageCount - 1).toFloat(),
                steps = (state.pageCount - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.mode == DocumentReaderMode.SINGLE_PAGE) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Pinch to zoom. Swipe to change pages at fit.",
                    color = FolioTheme.colors.onSurfaceVariant,
                    style = FolioTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onResetZoom) {
                    Text("Reset zoom")
                }
            }
        }
    }
}

@Composable
private fun DocumentReaderBar(
    state: DocumentReaderState,
    onBack: () -> Unit,
    onBookmark: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().folioVeil().padding(horizontal = FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
    ) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(state.document?.title.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onBookmark) {
            Icon(if (state.isCurrentPositionBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Toggle bookmark")
        }
    }
}

private fun errorHeadline(kind: DocumentReaderErrorKind): String = when (kind) {
    DocumentReaderErrorKind.NOT_FOUND -> "Document not found"
    DocumentReaderErrorKind.MISSING_FILE -> "File missing"
    DocumentReaderErrorKind.CORRUPT -> "Document is damaged"
    DocumentReaderErrorKind.ENCRYPTED -> "Document is protected"
    DocumentReaderErrorKind.RENDER -> "Page could not be rendered"
    DocumentReaderErrorKind.IO -> "Document could not be opened"
}

private fun resolveDocumentResource(viewModel: DocumentReaderViewModel, source: String): String? =
    viewModel.resolveResource(source)
