@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.document

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Highlight
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.FolioStatusBarBand
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.pageFoxing
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.library.DocumentThumbnail
import com.folio.reader.ui.render.HtmlContentSurface
import com.folio.reader.ui.theme.FolioShapes
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
    modifier: Modifier = Modifier,
    /**
     * §17 morph landing: the document id, so the thumbnail tapped in the library
     * has somewhere to land. Non-null is the normal case — unlike the EPUB reader
     * this path renders pure Compose on both platforms (the PDF surface paints its
     * own bitmaps), so Compose controls what is on screen and there is no seam to
     * be cautious about.
     *
     * Only used while the document is still loading; see [DocumentThumbnail].
     */
    morphDocumentId: String? = null
) {
    val state by viewModel.state.collectAsState()
    var resetZoomKey by remember { mutableIntStateOf(0) }
    var seekNonce by remember { mutableIntStateOf(0) }
    var seekRequest by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val occludes = com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()
    val isPdf = state.document?.format == com.folio.reader.model.DocumentFormat.PDF

    // §17 morph landing / real-first-paint gate, mirroring the EPUB reader
    // (ReaderScreen.kt:177-216,667-698). The reflowable (DOCX/HTML) surface is a
    // WebView on Android: when `loadState` flipped to Ready — merely the HTML
    // string having been read from disk — the loading/morph plate was dropped
    // BEFORE that WebView had painted, so the reader saw a blank/paper frame and
    // the text then hard-cut in and reflowed. `contentPainted` instead flips on
    // the surface's REAL first paint (onContentReady → onPageFinished on Android /
    // the main frame's onLoadEnd on desktop), so the cover holds over the blank
    // frame and dissolves only once there is text underneath. Keyed on the
    // document id so a later open re-arms it. The PDF path paints its own Compose
    // bitmaps (no browser seam) and so lands as soon as it is Ready.
    val morphDocId = state.document?.id
    var contentPainted by remember(morphDocId) { mutableStateOf(false) }
    var plateGone by remember(morphDocId) { mutableStateOf(false) }
    val morphMotion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val reflowableAwaitingPaint =
        (state.loadState as? DocumentReaderLoadState.Ready)?.content is DocumentReaderContent.Reflowable &&
            !contentPainted
    val coverHeld = when (state.loadState) {
        is DocumentReaderLoadState.Loading -> true
        is DocumentReaderLoadState.Ready -> reflowableAwaitingPaint
        is DocumentReaderLoadState.Error -> false
    }
    // graphicsLayer alpha (not a recompose) so the fade runs on the render thread
    // while the shared-element bounds settle underneath it, exactly like the EPUB
    // plate's `plateAlpha`. Opaque (1f) while the cover is held, dissolving to 0f
    // once the content underneath has landed.
    val plateAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (coverHeld) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (morphMotion) FolioTokens.motionStandard.toInt() else 0,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "documentMorphPlateDissolve",
    )
    // Drop the plate once it has fully faded (or immediately under reduce-motion)
    // so the shared cover key is released rather than lingering for the session.
    LaunchedEffect(coverHeld, plateAlpha, morphMotion) {
        if (!coverHeld && (plateAlpha <= 0.001f || !morphMotion)) plateGone = true
    }
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

    // §16: the fixed-page (PDF) path renders pure Compose, so its strip is a real
    // blur backdrop for the chrome. The reflowable path is a native surface on Android
    // (WebView): Haze cannot sample a WebView's own render surface, and attaching a
    // backdrop source over it made the bars re-capture the WebView every frame as they
    // faded in — the reader flickered whenever the in-reader chrome came on. So the
    // source is attached ONLY for the fixed-page path; over the reflowable WebView the
    // chrome falls back to specular + grain, exactly like the EPUB reader (which, per
    // FolioNavShell, never attaches a source over its WebView either).
    val pageGlassSource = if (!occludes && isPdf) Modifier.folioBackdropSource() else Modifier

    Box(modifier.fillMaxSize().background(FolioTheme.colors.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(PaddingValues(top = topInset, bottom = bottomInset))
                .then(pageGlassSource)
        ) {
            when (val load = state.loadState) {
                // The loading UI (placeholder + morph plate) is drawn by the
                // persistent cover overlay below, so it can survive the flip to
                // Ready and hold over the reflowable surface until it has painted.
                DocumentReaderLoadState.Loading -> Unit
                is DocumentReaderLoadState.Error -> EmptyState(
                    Icons.Default.BrokenImage,
                    errorHeadline(load.error.kind),
                    load.error.message,
                    Modifier.align(Alignment.Center)
                )
                is DocumentReaderLoadState.Ready -> when (val content = load.content) {
                    is DocumentReaderContent.Pdf -> CompositionLocalProvider(
                        // The single-page ground follows the reader's paper, like the
                        // foxing and page block, instead of the app surfaceVariant.
                        LocalReaderPaper provides paper
                    ) {
                        FixedPageContentSurface(
                            content.path, state.document!!.id, state.currentPage, state.mode,
                            state.rotationDegrees, resetZoomKey, Modifier.fillMaxSize(), viewModel::onPdfOpened,
                            viewModel::setCurrentPage,
                            onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
                            onError = viewModel::reportError
                        )
                    }
                    is DocumentReaderContent.Reflowable -> {
                        // Windowed rendering (continuous layout only) mirrors the EPUB
                        // reader: the document is split into ordered chunks fed as a
                        // STABLE window, grown/trimmed via WindowOp as the reader
                        // scrolls, so a large/image-heavy doc never overruns the
                        // WebView tile budget (the Android continuous dim/undim +
                        // image flicker). Two guards keep it from regressing other
                        // paths:
                        //  • Paged layout keeps the single-section whole-document path
                        //    — MulticolEngine paginates the whole doc and never asks
                        //    for an extension, so a window there would strand the
                        //    reader in the first few chunks.
                        //  • Only the non-occluding (Android WebView) surface windows.
                        //    The occluding desktop JCEF surface assembles windows in
                        //    Shadow-DOM mode with an empty reader shadow sheet, which
                        //    would drop block-level reader styling for these
                        //    style-less generated docs; it has ample memory and no
                        //    tile-budget problem, so it keeps the fully-styled
                        //    single-section path.
                        val useWindow = !occludes &&
                            state.reflowableWindow.isNotEmpty() &&
                            settings.layoutMode.normalized == com.folio.reader.settings.LayoutMode.CONTINUOUS
                        if (useWindow) {
                            HtmlContentSurface(
                                sections = state.reflowableWindow,
                                windowed = true,
                                anchorChapterId = state.reflowableAnchorChapterId,
                                settings = settings,
                                // scrollOffset is the section-local seed within the
                                // anchor chunk; chapterId names that anchor.
                                position = com.folio.reader.model.ReadingPosition(
                                    bookId = state.document!!.id,
                                    deviceId = "document-reader",
                                    chapterId = state.reflowableAnchorChapterId.orEmpty(),
                                    spineIndex = 0,
                                    contentLocator = "",
                                    characterOffset = 0,
                                    normalizedProgress = state.normalizedProgress.coerceIn(0.0, 1.0),
                                    chapterProgress = state.reflowableScrollOffset.toDouble().coerceIn(0.0, 1.0),
                                    scrollOffset = state.reflowableScrollOffset.toDouble().coerceIn(0.0, 1.0),
                                ),
                                highlights = emptyList<Highlight>(),
                                enabled = true,
                                modifier = Modifier.fillMaxSize(),
                                onProgress = viewModel::updateReflowableProgress,
                                onPageChange = viewModel::updateReflowablePage,
                                onVisibleSection = viewModel::onReflowableVisibleSection,
                                onExtendForward = { viewModel.extendReflowableWindow(true) },
                                onExtendBackward = { viewModel.extendReflowableWindow(false) },
                                windowOp = state.reflowableWindowOp,
                                onWindowOpApplied = viewModel::onReflowableWindowOpApplied,
                                onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
                                onLinkClick = null,
                                onResolveResource = { _, src -> resolveDocumentResource(viewModel, src) },
                                seekRequest = seekRequest,
                                onContentReady = { contentPainted = true }
                            )
                        } else HtmlContentSurface(
                            // Fallback: a reflowed document as one section — the same
                            // shape a chapter window uses, with no chapter identity of
                            // its own. Used for paged layout and if chunking yields
                            // nothing.
                            sections = listOf(
                                com.folio.reader.ui.render.FixedLayoutDetector.detect(content.html).let { fxl ->
                                    com.folio.reader.ui.render.ReaderSection(
                                        spineIndex = -1,
                                        chapterId = DOCUMENT_REFLOWABLE_SECTION,
                                        href = content.chapterHref,
                                        html = content.html,
                                        isFixedLayout = fxl.isFixedLayout,
                                        fxlWidth = fxl.width,
                                        fxlHeight = fxl.height,
                                    )
                                }
                            ),
                            windowed = false,
                            anchorChapterId = null,
                            settings = settings,
                            position = state.reflowableLocator?.toSurfacePosition(state.document!!.id, state.normalizedProgress),
                            highlights = emptyList<Highlight>(),
                            enabled = true,
                            modifier = Modifier.fillMaxSize(),
                            // Single-section: the surface reports whole-document
                            // progress, so store it directly (no section-local
                            // conversion, unlike the windowed branch above).
                            onProgress = viewModel::updateReflowableGlobalProgress,
                            onPageChange = viewModel::updateReflowablePage,
                            onTap = { viewModel.setControlsVisible(!state.controlsVisible) },
                            onLinkClick = null,
                            onResolveResource = { _, src -> resolveDocumentResource(viewModel, src) },
                            seekRequest = seekRequest,
                            // Real first paint of the WebView/JCEF surface: dissolve the
                            // cover overlay on genuine paint rather than on the HTML
                            // string having been read (see the `contentPainted` note
                            // above). May fire more than once; assigning true is idempotent.
                            onContentReady = { contentPainted = true }
                        )
                    }
                }
            }

            // §17 morph landing + real-first-paint cover, mirroring ReaderScreen's
            // FolioCoverPlate overlay (ReaderScreen.kt:667-698). Composed AFTER the
            // content `when` so it sits over the reflowable surface: it holds opaque
            // over the loading/blank frame, then dissolves once the text beneath it
            // has genuinely painted (contentPainted → coverHeld → plateAlpha), and is
            // dropped after the fade (plateGone) so it never keeps a second copy of
            // the cover key alive for the session.
            //
            // On desktop the reflowable surface is a heavyweight JCEF window that
            // paints over Compose, so this plate is occluded there and cannot cover
            // it; that seam is instead closed by the surface's own opacity reveal
            // (see HtmlContentSurface.desktop.kt readerBridgeJs). This overlay is the
            // Android fix; the desktop fix is the deferred body reveal.
            if (!plateGone) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = plateAlpha }
                        // An opaque backdrop the colour of the reader's own paper, so
                        // the blank/paper surface frame under it never shows through
                        // while the cover is held.
                        .background(paper)
                ) {
                    LoadingPlaceholder(Modifier.align(Alignment.Center))
                    // Uses the library's own DocumentThumbnail so the plate the file
                    // was tapped on is the plate that lands — a landing drawn any
                    // other way would be the one surface in the morph that did not
                    // match its source.
                    val landingDocument = state.document
                    if (morphDocumentId != null && landingDocument != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                // Publish the shared cover key ONLY while the cover is
                                // still held (the fly-in); once the content has landed
                                // the plate keeps composing through its dissolve but
                                // drops the key, so its copy never overlaps the library
                                // shelf's on a back-to-Library pop (the "two live copies
                                // of one key" hazard the registry cannot resolve — see
                                // FolioSharedElements / ReaderScreen.kt:201-216).
                                .then(
                                    if (coverHeld) {
                                        Modifier.sharedElementOrNoop(FolioSharedKeys.documentCover(morphDocumentId))
                                    } else {
                                        Modifier
                                    }
                                )
                                .width(180.dp)
                                .height(240.dp)
                                .clip(FolioShapes.plate),
                        ) {
                            DocumentThumbnail(
                                document = landingDocument,
                                modifier = Modifier.fillMaxSize(),
                                fallbackWithFilename = true,
                                suppressFallbackCaption = true,
                            )
                        }
                    }
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
            DocumentReaderBar(state, onBack, viewModel::toggleBookmark, morphDocumentId)
        }

        AnimatedVisibility(
            visible = showBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Column(Modifier.navigationBarsPadding()) {
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
        Modifier.fillMaxWidth()
            .folioVeil(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = FolioTokens.space3),
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
    onBookmark: () -> Unit,
    morphDocumentId: String? = null
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
                modifier = Modifier
                    .weight(1f)
                    // §17: paired with the library tile's title, so the file's name
                    // arrives with its plate rather than appearing once the reader
                    // has already opened.
                    .then(
                        if (morphDocumentId != null) {
                            Modifier.sharedTextOrNoop(
                                FolioSharedKeys.documentTitle(morphDocumentId)
                            )
                        } else {
                            Modifier
                        }
                    )
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
