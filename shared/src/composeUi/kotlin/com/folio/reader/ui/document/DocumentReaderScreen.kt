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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Highlight
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioStatusBarBand
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.pageFoxing
import com.folio.reader.ui.render.HtmlContentSurface
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha
import java.io.File
import kotlin.math.roundToInt

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
    // The page block is cut from the *reader's* paper, not the app palette, so it
    // reads against whatever preset the document is being shown in.
    val readerTheme = settings.customTheme
        ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
    val paper = Color(readerTheme.background)
    val ink = Color(readerTheme.primaryText)
    val showBlock = showBottom && settings.showProgress
    val topInset = if (occludes && state.controlsVisible) 56.dp else 0.dp
    val bottomInset = if (occludes && showBottom) {
        val block = if (settings.showProgress) 40.dp else 0.dp
        if (isPdf) block + 56.dp else block
    } else 0.dp

    com.folio.reader.ui.components.ReaderSystemBars(state.controlsVisible)
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }

    // §16: the fixed-page path renders pure Compose, so its strip is a real blur
    // backdrop for the chrome. The reflowable path is a native surface on Android
    // (WebView) — no source attaches there, and the chrome falls back to specular
    // + grain, exactly like the EPUB reader.
    val pageGlassSource = if (!occludes) Modifier.folioBackdropSource() else Modifier

    Box(modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(PaddingValues(top = topInset, bottom = bottomInset))
                .then(pageGlassSource)
        ) {
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
                        // A reflowed document is one section — same shape a chapter
                        // window uses, with no chapter identity of its own.
                        sections = listOf(
                            com.folio.reader.ui.render.ReaderSection(
                                spineIndex = -1,
                                chapterId = DOCUMENT_REFLOWABLE_SECTION,
                                href = content.chapterHref,
                                html = content.html
                            )
                        ),
                        windowed = false,
                        anchorChapterId = null,
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

        // Foxing: the document's outer margins wear a little, and the wear
        // concentrates on the edge the reader's thumb has been working. Drawn only
        // where Compose can composite over the page: on desktop the reflowable path
        // is a heavyweight browser window that paints over Compose layers, while the
        // PDF path renders its own bitmaps and so still takes the cue.
        if ((!occludes || isPdf) && settings.showProgress) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .pageFoxing(
                        fraction = state.normalizedProgress.toFloat(),
                        paper = paper,
                        ink = ink
                    )
            )
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
                if (showBlock) {
                    DocumentPageBlock(
                        state = state,
                        isPdf = isPdf,
                        paper = paper,
                        ink = ink,
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

/**
 * The document reader's page block. A document is one span — no chapters to notch —
 * and its own top bar already carries the title, so the chapter line is left empty
 * and the chrome is a bare block: more document on screen for the same cue.
 *
 * `normalizedProgress` is already whole-document here, so the block and the drag
 * agree with no translation: [onSeek] is handed exactly the fraction the block shows.
 */
@Composable
private fun DocumentPageBlock(
    state: DocumentReaderState,
    isPdf: Boolean,
    paper: Color,
    ink: Color,
    onSeek: (Float) -> Unit
) {
    val pageCount = state.pageCount.coerceAtLeast(1)
    val currentPage = (if (isPdf) state.currentPage + 1 else state.currentPage).coerceIn(1, pageCount)
    val fraction = state.normalizedProgress.toFloat()
    // The block carries no numerals, so the page count the "5 / 12" used to print is
    // announced instead — without this a screen reader loses the document's place.
    val label = buildString {
        append("${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}% read")
        if (pageCount > 1) append(", page $currentPage of $pageCount")
        append(". Drag to jump elsewhere in the document.")
    }
    com.folio.reader.ui.reader.BottomPageBlock(
        chapterTitle = "",
        fraction = fraction,
        paper = paper,
        ink = ink,
        stateLabel = label,
        pageCountHint = pageCount,
        onSeek = onSeek
    )
}

@Composable
private fun DocumentReaderBar(
    state: DocumentReaderState,
    onBack: () -> Unit,
    onBookmark: () -> Unit
) {
    // Same chrome contract as the EPUB and manga readers: the inked status band
    // over the page, then a 56dp glass row with rounded feet, so the back arrow,
    // the centred title and the bookmark all sit on one aligned baseline. The
    // old bar was a bare Row with no band, no height and no shape — its icons
    // hugged the screen edge uncentred, and it read as a different product.
    Column(Modifier.fillMaxWidth()) {
        FolioStatusBarBand(inkBand = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioVeil(
                    shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                    elevation = FolioTokens.elevationVeil,
                    fillAlpha = FolioTheme.readerVeilAlpha,
                )
                .height(56.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FolioTheme.colors.onSurface)
            }
            Text(
                state.document?.title.orEmpty(),
                style = FolioTheme.typography.titleMedium,
                color = FolioTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onBookmark) {
                Icon(
                    if (state.isCurrentPositionBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    "Toggle bookmark",
                    tint = FolioTheme.colors.onSurface
                )
            }
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
