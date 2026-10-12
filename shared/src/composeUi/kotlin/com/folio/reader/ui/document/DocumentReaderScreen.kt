@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.document

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Highlight
import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.normalized
import com.folio.reader.settings.toReaderSettings
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioSheetDragToDismiss
import com.folio.reader.ui.components.pageFoxing
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.library.DocumentThumbnail
import com.folio.reader.ui.reader.readerIsland
import com.folio.reader.ui.render.HtmlContentSurface
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import java.io.File
import kotlin.math.roundToInt

@Composable
fun DocumentReaderScreen(
    viewModel: DocumentReaderViewModel,
    /**
     * The document's own reading settings — typography, page and page theme —
     * kept separate from the book defaults. Edited in place by the in-reader
     * panel ([DocumentSettingsPanel]) through [onSettingsChange].
     */
    settings: DocumentSettings = DocumentSettings(),
    /**
     * The global **book** row, used only as the base for the app-level fields a
     * document does not own (eye protection, the ambient tint, editorial
     * styling, the vibrancy/sheen knobs). Documents no longer inherit a book's
     * typography or page theme — that was the bug this feature fixes — but the
     * device-level atmosphere is still one setting for the whole app.
     */
    globalSettings: ReaderSettings = ReaderSettings(),
    onSettingsChange: (DocumentSettings) -> Unit = {},
    onOpenFullSettings: () -> Unit = {},
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
    var showSettingsPanel by remember { mutableStateOf(false) }
    val occludes = com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()
    val isPdf = state.document?.format == com.folio.reader.model.DocumentFormat.PDF
    // A PDF never shows the document panel — it has no typography or page settings to
    // apply. Guarding the flag itself (not only the island) also closes a panel that was
    // open when the reader lands on a PDF, e.g. after a re-open.
    val settingsPanelVisible = showSettingsPanel && !isPdf

    // The rendering surfaces speak `ReaderSettings`; the document row supplies the
    // document-owned half and the global book row the app-level half.
    //
    // **A PDF is excluded on purpose.** A PDF is a fixed page the renderer paints as
    // bitmaps: typeface, size, line/paragraph spacing, margins, text width, alignment,
    // hyphenation and the reflowable layout mode cannot touch it, so offering them would
    // be a panel of dead controls. The PDF path therefore keeps the global reader row —
    // exactly what it used before documents had settings of their own — and the
    // in-reader document panel is not shown for it (see the settings island below).
    val effective = remember(settings, globalSettings, isPdf) {
        if (isPdf) globalSettings else settings.toReaderSettings(globalSettings)
    }

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
    val readerTheme = effective.customTheme
        ?: com.folio.reader.settings.Theme.getPreset(effective.themeId)
    val paper = Color(readerTheme.background)
    val ink = Color(readerTheme.primaryText)
    val showBlock = showBottom && effective.showProgress
    val topInset = if (occludes && state.controlsVisible) 56.dp else 0.dp
    val bottomInset = if (occludes && showBottom) {
        val block = if (effective.showProgress) 40.dp else 0.dp
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

    // The reading page is opaque and full-bleed, so the ground plane cannot be seen while this
    // route is up. Claim the occlusion so the field leaves the composition and Mirage's
    // per-vsync clock stops, instead of animating a full-screen shader behind an opaque page.
    com.folio.reader.ui.theme.FolioFieldOccluder()

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
                            effective.layoutMode.normalized == com.folio.reader.settings.LayoutMode.CONTINUOUS
                        if (useWindow) {
                            HtmlContentSurface(
                                sections = state.reflowableWindow,
                                windowed = true,
                                anchorChapterId = state.reflowableAnchorChapterId,
                                settings = effective,
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
                            settings = effective,
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
        if ((!occludes || isPdf) && effective.showProgress) {
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
            DocumentReaderBar(
                state,
                onBack,
                viewModel::toggleBookmark,
                morphDocumentId,
                // Null for a PDF: a fixed page has no typography or page settings to
                // offer, so the island is not drawn at all rather than opening a panel
                // of controls that cannot move.
                onOpenSettings = if (isPdf) null else ({ showSettingsPanel = true }),
            )
        }

        AnimatedVisibility(
            visible = showBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            // The bottom child is a floating island: it takes the nav-bar inset and the uniform
            // edge margin itself, so the wrapper stays a plain full-width slot and the capsule
            // hovers above the nav bar with the page showing through the margin on every side.
            val onSeekProgress: (Float) -> Unit = { fraction ->
                viewModel.seekToProgress(fraction)
                if (!isPdf) {
                    seekNonce++
                    seekRequest = fraction to seekNonce.toLong()
                }
            }
            Column(Modifier.fillMaxWidth()) {
                if (isPdf) {
                    // The single-page/continuous toggle row and the progress block are cut from
                    // ONE continuous glass sheet, now a floating island: a single rounded capsule
                    // (the chrome plane at the reader's near-solid alpha) that hovers above the nav
                    // bar with a uniform margin on every side. The children are drawn `bare` (no
                    // glass, no inset) and contribute only content, so the two rows read as one
                    // plate — the toggle no longer sits as a square panel on the progress bar's
                    // shoulders, and the page shows through the margin, not through the glass.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(
                                start = FolioTokens.insetReaderIsland,
                                end = FolioTokens.insetReaderIsland,
                                bottom = FolioTokens.insetReaderIsland,
                            )
                            .readerIsland(RoundedCornerShape(FolioTokens.radiusReaderIsland))
                            .padding(vertical = 6.dp)
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
                            onResetZoom = { resetZoomKey++ },
                        )
                        if (showBlock) {
                            DocumentPageBlock(
                                state = state,
                                isPdf = isPdf,
                                paper = paper,
                                ink = ink,
                                bare = true,
                                onSeek = onSeekProgress,
                            )
                        }
                    }
                } else if (showBlock) {
                    DocumentPageBlock(
                        state = state,
                        isPdf = isPdf,
                        paper = paper,
                        ink = ink,
                        onSeek = onSeekProgress,
                    )
                }
            }
        }

        // In-reader document settings — a scrim plus the end-edge sheet, mirroring the
        // EPUB reader's quick-settings panel. Composed last so it sits over both the page
        // and the reader chrome. The panel writes the *document* defaults; "All settings"
        // hands off to the Settings hub.
        AnimatedVisibility(
            visible = settingsPanelVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // The palette's own ambient shadow, not pure black, so the scrim
                    // reads as depth on light themes instead of a hole.
                    .background(FolioTheme.atmosphere.shadowAmbient.copy(alpha = 0.35f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { showSettingsPanel = false })
                    }
            )
        }
        AnimatedVisibility(
            visible = settingsPanelVisible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .statusBarsPadding()
                .navigationBarsPadding(),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            Box(Modifier.folioSheetDragToDismiss { showSettingsPanel = false }) {
                DocumentSettingsPanel(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onDismiss = { showSettingsPanel = false },
                    onOpenFullSettings = {
                        showSettingsPanel = false
                        onOpenFullSettings()
                    },
                    extraFonts = globalSettings.customFonts.map { it.name },
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
    onResetZoom: () -> Unit,
) {
    Row(
        // No glass or inset here: the controls are drawn inside the shared chrome sheet (see the
        // caller), which supplies the single continuous plane and the nav-bar inset. The row only
        // insets its content horizontally so it lines up with the progress block beneath it.
        Modifier.fillMaxWidth()
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
    bare: Boolean = false,
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
        bare = bare,
        onSeek = onSeek
    )
}

@Composable
private fun DocumentReaderBar(
    state: DocumentReaderState,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    morphDocumentId: String? = null,
    onOpenSettings: (() -> Unit)? = null
) {
    // Floating islands, like the EPUB reader: a back capsule, a title capsule and a bookmark
    // capsule hovering below the status bar with a uniform margin, so the page breathes through
    // the gaps. Each capsule is the shared `readerIsland` glass (the chrome plane and weight at
    // this reader's near-solid alpha), so the document reader is the same material family as the
    // EPUB and manga chrome rather than a different product.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                horizontal = FolioTokens.insetReaderIsland,
                vertical = FolioTokens.insetReaderIsland,
            ),
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.insetReaderIsland),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.readerIsland(CircleShape)) {
            IconButton(onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = FolioTheme.colors.onSurface)
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .readerIsland()
                .heightIn(min = 48.dp)
                .padding(horizontal = FolioTokens.space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                state.document?.title.orEmpty(),
                style = FolioTheme.typography.titleMedium,
                color = FolioTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
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
        }
        Box(Modifier.readerIsland(CircleShape)) {
            IconButton(onBookmark) {
                Icon(
                    if (state.isCurrentPositionBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    "Toggle bookmark",
                    tint = FolioTheme.colors.onSurface
                )
            }
        }
        if (onOpenSettings != null) {
            Box(Modifier.readerIsland(CircleShape)) {
                IconButton(onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        "Document settings",
                        tint = FolioTheme.colors.onSurface
                    )
                }
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
