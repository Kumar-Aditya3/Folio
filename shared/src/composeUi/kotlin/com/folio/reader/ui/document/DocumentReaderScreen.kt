package com.folio.reader.ui.document

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Highlight
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.EmptyState
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
    var seekNonce by remember { mutableIntStateOf(0) }
    var seekRequest by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val occludes = com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()
    val isPdf = state.document?.format == com.folio.reader.model.DocumentFormat.PDF
    val showBottom = state.controlsVisible && state.loadState is DocumentReaderLoadState.Ready
    val topInset = if (occludes && state.controlsVisible) 56.dp else 0.dp
    val bottomInset = if (occludes && showBottom) {
        if (isPdf) 132.dp else 50.dp
    } else 0.dp

    com.folio.reader.ui.components.ReaderSystemBars(state.controlsVisible)
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }

    Box(modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        Box(Modifier.fillMaxSize().padding(PaddingValues(top = topInset, bottom = bottomInset))) {
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
                        onPageChange = viewModel::updateReflowablePage,
                        onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
                        onLinkClick = null,
                        onResolveResource = { _, src -> resolveDocumentResource(viewModel, src) },
                        seekRequest = seekRequest
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it }
        ) {
            DocumentReaderBar(state, onBack, viewModel::toggleBookmark)
        }

        AnimatedVisibility(
            visible = showBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Column {
                if (isPdf) {
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
                        onResetZoom = { resetZoomKey++ }
                    )
                }
                DocumentProgressBar(
                    state = state,
                    isPdf = isPdf,
                    onSeek = { fraction ->
                        viewModel.seekToProgress(fraction)
                        if (!isPdf) {
                            seekNonce++
                            seekRequest = fraction to seekNonce.toLong()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentReaderControls(
    state: DocumentReaderState,
    onMode: () -> Unit,
    onRotate: () -> Unit,
    onResetZoom: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().folioVeil().padding(horizontal = FolioTokens.space3),
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
        IconButton(onClick = onRotate) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate pages")
        }
        if (state.mode == DocumentReaderMode.SINGLE_PAGE) {
            TextButton(onClick = onResetZoom) { Text("Reset zoom") }
        }
    }
}

@Composable
private fun DocumentProgressBar(
    state: DocumentReaderState,
    isPdf: Boolean,
    onSeek: (Float) -> Unit
) {
    val currentPage = if (isPdf) state.currentPage + 1 else state.currentPage.coerceAtLeast(1)
    val totalPages = state.pageCount.coerceAtLeast(1)
    com.folio.reader.ui.reader.BottomProgressBar(
        chapterTitle = state.document?.title.orEmpty(),
        currentPage = currentPage.coerceAtMost(totalPages),
        totalPages = totalPages,
        progress = state.normalizedProgress.toFloat(),
        onSeek = onSeek
    )
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
