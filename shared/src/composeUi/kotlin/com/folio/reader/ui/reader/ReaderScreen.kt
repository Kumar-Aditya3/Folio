@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.reader

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.locatorsMatch
import com.folio.reader.model.spotLocator
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.pageBlockBandFraction
import com.folio.reader.ui.components.pageBlockChapterStops
import com.folio.reader.ui.components.pageBlockSeekTarget
import com.folio.reader.ui.components.pageFoxing
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha
import com.folio.reader.ui.theme.rememberFolioHaptics
import kotlin.math.roundToInt

/**
 * Words per estimated page in continuous mode, which has no real page turns.
 *
 * ~275 words is the rough count of a mass-market paperback page, and near what a paginated
 * e-reader shows at a default type size — so the "N pages left" estimate reads as plausible to
 * someone used to either. It is only ever an estimate; paged mode uses the real page counters.
 */
private const val WORDS_PER_PAGE = 275

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookTitle: String,
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    chapterHtml: String,
    isLoadingContent: Boolean,
    loadError: String?,
    coverPath: String?,
    position: ReadingPosition?,
    settings: ReaderSettings,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    notes: List<Note>,
    showControls: Boolean,
    showToc: Boolean,
    showAnnotations: Boolean,
    /** Echoes side panel: selection-driven cross-book resonant passages. */
    showEchoes: Boolean = false,
    echoesState: EchoesState = EchoesState.Idle,
    /** App-level Semantic-discovery flag; the Echoes action only lights when true and a selection exists. */
    echoesEnabled: Boolean = false,
    onOpenEchoes: (selectedText: String) -> Unit = {},
    onCloseEchoes: () -> Unit = {},
    /** Fired on the first text selection so the embedder/index can warm before an Echoes tap. */
    onPrewarmEchoes: () -> Unit = {},
    onOpenEcho: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit = { _, _, _ -> },
    onChapterChange: (Int) -> Unit,
    onBackPress: () -> Unit,
    onSearchClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleControls: () -> Unit,
    onShowControls: () -> Unit = {},
    onToggleToc: () -> Unit,
    onToggleAnnotations: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onSetHighlightNote: (highlightId: String, content: String) -> Unit = { _, _ -> },
    onScrollProgress: (Float) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit = {},
    onHighlightParagraph: ((chapterId: String, paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onRetryChapter: (() -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    onResolveImage: suspend (chapterHref: String, src: String) -> String? = { _, _ -> null },
    onResolveResource: suspend (chapterHref: String, src: String) -> String? = onResolveImage,
    syncState: com.folio.reader.sync.SyncState? = null,
    onPageChange: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    onChapterEnd: () -> Unit = {},
    onChapterStart: () -> Unit = {},
    /** Continuous-mode chapter window: the chapters rendered together, plus its plumbing. */
    sections: List<com.folio.reader.ui.render.ReaderSection> = emptyList(),
    /** The window document as loaded — extensions inject into it without rebuilding. */
    documentSections: List<com.folio.reader.ui.render.ReaderSection> = emptyList(),
    windowed: Boolean = false,
    windowOp: com.folio.reader.ui.render.WindowOp? = null,
    onVisibleSection: (spineIndex: Int) -> Unit = {},
    onExtendForward: () -> Unit = {},
    onExtendBackward: () -> Unit = {},
    onWindowOpApplied: (nonce: Long) -> Unit = {},
    chapterChip: String? = null,
    onDismissChapterChip: () -> Unit = {},
    scopeControlEnabled: Boolean = false,
    overriddenFields: Set<String> = emptySet(),
    onWriteGlobal: ((ReaderSettings) -> Unit)? = null,
    onResetBook: (() -> Unit)? = null,
    /**
     * §17 morph landing. When non-null the reader holds a plate carrying the
     * book's cover key in the middle of the page while the first chapter loads, so
     * the cover tapped on a shelf has somewhere to land and the reader appears to
     * open *from* that cover rather than replacing the page with a spinner.
     *
     * Null — the default — disables the whole thing and the reader is byte-identical
     * to before. That is deliberate: the arriving content here is a native browser
     * surface on Android, which Compose cannot composite over, so the plate and the
     * page can only agree about when the handoff happens by luck. It is opt-in
     * behind a settings flag until that seam is measured on a device.
     */
    morphBookId: String? = null,
    /**
     * Where to land inside the opening chapter, as a fraction in [0, 1] of its text, or null for
     * the chapter top. Set by a search deep-link so a result opens at the matched passage rather
     * than the chapter's start; applied once, after the first chapter's content is on screen (the
     * view model has already positioned to the target chapter), by issuing the same fraction seek
     * the progress bar and annotation jumps use.
     */
    initialSeekFraction: Float? = null
) {
    val currentChapter = chapters.getOrNull(currentChapterIndex)
    var currentPage by remember(currentChapterIndex) { mutableStateOf(1) }
    var totalPages by remember(currentChapterIndex) { mutableStateOf(1) }
    val pageHaptics = rememberFolioHaptics()
    // The HTML bridge reports position on every progress tick, not only on turns,
    // and the first report per chapter is the initial settle. Gate the page-turn
    // tick so it fires only on a genuine page change after that first report.
    var pageReported by remember(currentChapterIndex) { mutableStateOf(false) }

    // §17 morph landing: the book id to land the tapped cover on. Held until the
    // page has genuinely PAINTED, not merely until the HTML string is ready.
    //
    // The old gate (`isLoadingContent || chapterHtml.isBlank()`) dropped the plate
    // the instant the loader produced non-blank HTML — but the browser surface
    // that paints that HTML had not been handed the document yet, so the plate
    // vanished onto a blank paper frame and the text hard-cut in a beat later.
    // That gap was the reported flash. `contentPainted` flips on the surface's
    // real first-paint (onPageFinished / onLoadEnd, via ChapterContent's
    // onContentReady), so the plate now covers the blank frame and dissolves only
    // once there is text underneath it. Keyed on the book so a later chapter turn
    // never re-arms it.
    var contentPainted by remember(morphBookId) { mutableStateOf(false) }
    // The plate stays composed through its dissolve, not just until paint: it holds
    // opaque over the loading/blank frame, then fades out once the text underneath
    // has painted, so the reader sees the cover melt into the page instead of the
    // page hard-cutting in. `plateGone` latches when the fade finishes, which is
    // what finally drops the second copy of the cover key.
    var plateGone by remember(morphBookId) { mutableStateOf(false) }
    val morphMotion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val plateAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (contentPainted) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (morphMotion) FolioTokens.motionStandard.toInt() else 0,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "morphPlateDissolve",
    )
    // Drop the plate once it has fully faded (or at once under reduce-motion), so
    // the cover key is released rather than lingering for the whole session.
    LaunchedEffect(contentPainted, plateAlpha, morphMotion) {
        if (contentPainted && (plateAlpha <= 0.001f || !morphMotion)) plateGone = true
    }
    val morphLanding = morphBookId?.takeIf {
        !plateGone && currentChapter != null
    }
    // The shared-element cover key is published ONLY during the fly-in — before the
    // text paints. The moment it has painted, the plate stops being a shared element
    // and finishes as a plain dissolving overlay.
    //
    // Why: the reader and the shelf both publish `book_cover:$id`. If the reader
    // kept its copy registered through the whole dissolve (until plateGone), a quick
    // back-to-Library pop would have the reader's plate AND the shelf cell both
    // holding the same key live at once — the "two live copies of one key" case the
    // registry cannot resolve (see FolioSharedElements' LocalSharedElementsSuppressed
    // note), which stranded the home/library cover morph. Dropping the key at paint
    // keeps the reader morph from ever overlapping the shelf's. The fly-in (450ms
    // motionMorph) all but always finishes before the WebView's first paint, so the
    // morph itself is unaffected.
    val morphKey = morphBookId?.takeIf {
        !contentPainted && currentChapter != null
    }

    // Desktop's embedded browser is a heavyweight native window that paints over
    // Compose overlays, so the chrome reserves its own space there instead of
    // floating above the page (Android keeps the overlay layout).
    val occludes = com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()

    // System bars follow the reader chrome (Android): visible with the overlay,
    // immersive when it's hidden. No-op on desktop.
    com.folio.reader.ui.components.ReaderSystemBars(showControls)

    var showReaderPanel by remember { mutableStateOf(false) }
    // Bottom progress bar taps request a seek to a chapter fraction.
    var seekReq by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    var seekNonce by remember { mutableStateOf(0L) }
    // The page's live text selection (carries the chapter the paragraph sits in —
    // a windowed selection may not be the anchor's chapter). Highlighting is a
    // chrome action because the OS selection toolbar covers anything drawn near
    // the text on phones.
    var pageSelection by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var clearSelTick by remember { mutableStateOf(0L) }
    // Highlight whose note is being written in the glass composer.
    var noteDraftFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentChapterIndex) { pageSelection = null }
    // Warm the Echoes embedder/index the moment the reader first selects text, so a later Echoes
    // tap is near-instant rather than paying a cold ONNX session + index build. Idempotent in the VM.
    val hasSelectionForEchoes = pageSelection?.third?.isNotBlank() == true
    LaunchedEffect(hasSelectionForEchoes, echoesEnabled) {
        if (hasSelectionForEchoes && echoesEnabled) onPrewarmEchoes()
    }
    // Annotation jumps: (target chapter index, seek target, chapter fraction). Released
    // only once that chapter is on screen, else the seek would move the old chapter.
    var pendingJump by remember { mutableStateOf<Triple<Int, String?, Float?>?>(null) }
    var seekTargetReq by remember { mutableStateOf<Pair<String, Long>?>(null) }

    LaunchedEffect(pendingJump, currentChapterIndex, chapterHtml, isLoadingContent) {
        val jump = pendingJump ?: return@LaunchedEffect
        if (currentChapterIndex != jump.first) return@LaunchedEffect
        if (isLoadingContent || chapterHtml.isBlank()) return@LaunchedEffect
        pendingJump = null
        seekNonce++
        val target = jump.second
        val fraction = jump.third
        when {
            target != null -> seekTargetReq = target to seekNonce
            fraction != null -> seekReq = fraction to seekNonce
        }
    }

    // Search deep-link landing. Fires once, when the opening chapter's content is first on
    // screen — the view model has already positioned to the target chapter via its start
    // override, so a chapter-local fraction seek here lands the passage. Guarded by a one-shot
    // flag so a later chapter turn does not re-trigger it, and skipped for the null default so
    // an ordinary open still restores the saved position untouched.
    var initialSeekApplied by remember { mutableStateOf(false) }
    LaunchedEffect(initialSeekFraction, chapterHtml, isLoadingContent) {
        if (initialSeekApplied) return@LaunchedEffect
        val fraction = initialSeekFraction ?: return@LaunchedEffect
        if (isLoadingContent || chapterHtml.isBlank()) return@LaunchedEffect
        initialSeekApplied = true
        seekNonce++
        seekReq = fraction.coerceIn(0f, 1f) to seekNonce
    }

    // ── The page block (diegetic progress) ──────────────────────────────────────
    // Chapter ends along the block, word-weighted so a long chapter takes the length
    // of block it is worth. The block is whole-book on purpose: one that emptied at
    // every chapter boundary would be a lie about how much of the book is left.
    val chapterStops = remember(chapters) {
        pageBlockChapterStops(chapters.map { it.wordCount })
    }
    // The span of the book the page surface is actually rendering: one chapter in
    // paged mode, the whole chapter window in continuous mode. The fraction the
    // surface reports — and the fraction a seek is given — are fractions of *that*
    // document, so expressing both in this span is what keeps the cue and the
    // gesture agreeing with each other.
    val renderedSpan = remember(chapters, sections, documentSections, windowed, currentChapterIndex) {
        val rendered: List<com.folio.reader.ui.render.ReaderSection> = when {
            !windowed -> emptyList()
            documentSections.isNotEmpty() -> documentSections
            else -> sections
        }
        val found = rendered.mapNotNull { section ->
            chapters.indexOfFirst { it.id == section.chapterId }.takeIf { it >= 0 }
                ?: chapters.indexOfFirst { section.spineIndex >= 0 && it.spineIndex == section.spineIndex }
                    .takeIf { it >= 0 }
        }
        if (found.isEmpty()) currentChapterIndex..currentChapterIndex else found.min()..found.max()
    }
    // The band for the chapter-local fraction is the *visible* chapter, not the whole rendered
    // span. `chapterProgress` is measured within the one section on screen (both paged and
    // continuous report section-local), so mapping it across the multi-chapter continuous window
    // put a "halfway through chapter 5" reading at the midpoint of the 3..7 band instead — the
    // skewed continuous progress. In paged mode the rendered span *is* the current chapter, so this
    // is identical there; only continuous is corrected.
    val bookFraction = pageBlockBandFraction(
        stops = chapterStops,
        bandFirst = currentChapterIndex,
        bandLast = currentChapterIndex,
        bandFraction = position?.chapterProgress?.toFloat()
            ?: if (totalPages > 0) (currentPage.toFloat() / totalPages).coerceIn(0f, 1f) else 0f,
    )
    val showChapterLine = settings.showChapterTitle && !currentChapter?.title.isNullOrBlank()
    // The "how much is left" readout, mode-aware. The leaf block is a shape, not a number, so
    // this is the numeral the reader asked for — and it is what was missing in continuous mode:
    // that layout has no page turns, so `currentPage`/`totalPages` stay 1/1 and a pages-left
    // count is meaningless there. `chapterProgress` is reported section-local in *both* modes,
    // so the in-chapter percentage is the honest, always-available figure for continuous reading;
    // paged reading keeps the concrete "N pages left" it can actually count.
    val chapterFrac = (position?.chapterProgress ?: 0.0).coerceIn(0.0, 1.0)
    // Pages left in the current chapter, in both layouts.
    //
    // Paged mode counts real pages (`currentPage`/`totalPages`). Continuous mode has no page
    // turns, so a page is *estimated* from the chapter's word count at ~[WORDS_PER_PAGE] words a
    // page — the same figure a paginated e-reader lands near — and the reader's scroll fraction
    // picks the current one. It is an estimate, but it is the pages readout the reader asked for
    // rather than a bare percentage, and it stays honest: it never shows more pages left than the
    // chapter has, and it reads "Last page" as the chapter end approaches.
    val pagesLeftLabel: String? = run {
        val (cur, total) = if (windowed) {
            val words = (currentChapter?.wordCount ?: 0L).toInt()
            val est = ((words + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE).coerceAtLeast(1)
            (((chapterFrac * est).toInt() + 1).coerceIn(1, est)) to est
        } else {
            currentPage to totalPages
        }
        if (total <= 1) null else {
            val left = (total - cur).coerceAtLeast(0)
            when (left) {
                0 -> "Last page"
                1 -> "1 page left"
                else -> "$left pages left"
            }
        }
    }
    // The block carries no numerals, so the position the "3 / 12" used to print is
    // announced instead — without this a screen reader loses the reader's place.
    val pageBlockLabel = buildString {
        append("${(bookFraction * 100).roundToInt()}% read")
        if (chapters.size > 1) append(", chapter ${currentChapterIndex + 1} of ${chapters.size}")
        append(". Drag to jump elsewhere in the book.")
    }
    // The band spans the whole book, so a drag can land in any chapter. Inside the
    // rendered span the seek goes straight to the page; outside it the chapter is
    // loaded first and the seek waits for it — the path annotation jumps already use.
    // A drag emits on every pointer move, and a chapter change flushes the position
    // and can reload content, so a foreign chapter is asked for once: the pending
    // jump keeps the latest fraction and lands it when the chapter is on screen.
    val seekToBookFraction: (Float) -> Unit = { bookTarget ->
        val landing = pageBlockSeekTarget(chapterStops, bookTarget)
        if (landing.chapterIndex in renderedSpan) {
            val start = chapterStops.getOrElse(renderedSpan.first) { 0f }
            val width = chapterStops.getOrElse(renderedSpan.last + 1) { 1f } - start
            seekNonce += 1L
            seekReq = (if (width > 0f) ((bookTarget - start) / width).coerceIn(0f, 1f) else 0f) to seekNonce
        } else {
            val alreadyAsked = pendingJump?.first == landing.chapterIndex
            val alreadyThere = landing.chapterIndex == currentChapterIndex
            if (!alreadyAsked && !alreadyThere) {
                pendingJump = Triple(landing.chapterIndex, null, landing.chapterFraction)
                onChapterChange(landing.chapterIndex)
            }
        }
    }

    /**
     * A Contents tap or chapter turn aimed at a neighbour already inside the loaded window
     * changes no document, so `setChapter` returns without reloading and the reader never
     * moves. The move is a seek, and `pendingJump` is the only thing that issues one —
     * annotation jumps already route through it, so chapter taps do too. The "p:0" target is
     * scoped to the target's own section by the surface, landing the reader at its top.
     */
    fun jumpToChapter(index: Int) {
        if (index != currentChapterIndex) pendingJump = Triple(index, "p:0", null)
        onChapterChange(index)
    }

    // The reading theme paints the page and nothing else. Chrome and overlays are app
    // surfaces, so they take the app palette — this is what kept the two from bleeding
    // into each other in opposite directions.
    val readerThemePreset = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)

    fun Color.hex(): String = "#" + toArgb().toUInt().toString(16).padStart(8, '0').drop(2)
    val appColors = FolioTheme.colors
    val appIsDark = appColors.background.red * 0.2126f +
            appColors.background.green * 0.7152f +
            appColors.background.blue * 0.0722f < 0.45f
    val overlayColors = com.folio.reader.ui.render.OverlayColors(
        bg = appColors.surface.hex(),
        fg = appColors.onSurface.hex(),
        accent = appColors.primary.hex(),
        surface = appColors.surfaceVariant.hex(),
        isDark = appIsDark,
        veilAlpha = FolioTheme.readerVeilAlpha
    )
    val quickFontNames = remember(settings.customFonts) {
        (platformBaseReaderFonts() + settings.customFonts.map { it.name }).distinct()
    }
    fun chapterLabel(spineIndex: Int?, chapterId: String?): String =
        chapters.firstOrNull { spineIndex != null && it.spineIndex == spineIndex }?.title
            ?: chapters.firstOrNull { chapterId != null && it.id == chapterId }?.title
            ?: if (spineIndex == null && chapterId == null) "Whole book" else "Spine ${(spineIndex ?: 0) + 1}"
    val overlayHtml = readerOverlayHtml(
        occludes = occludes,
        showReaderPanel = showReaderPanel,
        showToc = showToc,
        showAnnotations = showAnnotations,
        noteDraftFor = noteDraftFor,
        settings = settings,
        chapters = chapters,
        currentChapterIndex = currentChapterIndex,
        bookmarks = bookmarks,
        highlights = highlights,
        notes = notes,
        quickFontNames = quickFontNames,
        readerThemePreset = readerThemePreset,
        overlayColors = overlayColors,
        chapterLabel = { spine, chapterId -> chapterLabel(spine, chapterId) }
    )

    fun jumpToLocation(spineIndex: Int?, chapterId: String?, locator: String?, markId: String? = null) =
        readerJumpToLocation(
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            spineIndex = spineIndex,
            chapterId = chapterId,
            locator = locator,
            markId = markId,
            onSeek = { target, fraction ->
                seekNonce++
                when {
                    target != null -> seekTargetReq = target to seekNonce
                    fraction != null -> seekReq = fraction to seekNonce
                }
            },
            onPendingJump = { pendingJump = it },
            onChapterChange = onChapterChange
        )

    fun jumpToAnnotation(kind: String, id: String) =
        readerJumpToAnnotation(
            kind = kind,
            id = id,
            bookmarks = bookmarks,
            highlights = highlights,
            notes = notes,
            annotationsVisible = showAnnotations,
            onToggleAnnotations = onToggleAnnotations,
            jump = { spine, chapterId, locator, markId -> jumpToLocation(spine, chapterId, locator, markId) }
        )

    val isBookmarked = remember(position, bookmarks) {
        position?.let { pos ->
            bookmarks.any { it.chapterId == pos.chapterId && locatorsMatch(it.locator, pos.spotLocator()) }
        } ?: false
    }
    val selectionConsumed: () -> Unit = { pageSelection = null; clearSelTick++ }
    val openToc: () -> Unit = {
        val opening = !showToc
        onToggleToc()
        if (opening) showReaderPanel = false
    }
    val openAnnotations: () -> Unit = {
        val opening = !showAnnotations
        onToggleAnnotations()
        if (opening) showReaderPanel = false
    }
    val toggleReaderPanel: () -> Unit = {
        val opening = !showReaderPanel
        showReaderPanel = opening
        if (opening) {
            if (showToc) onToggleToc()
            if (showAnnotations) onToggleAnnotations()
            if (showEchoes) onCloseEchoes()
        }
    }
    // Echoes opens from the current selection; the VM runs the cross-book lookup and closes the
    // other panels. The reader panel is local state here, so close it too.
    val openEchoesAction: () -> Unit = {
        val text = pageSelection?.third
        if (!text.isNullOrBlank()) {
            showReaderPanel = false
            onOpenEchoes(text)
            pageSelection = null
        }
    }

    // Reader chrome is app-themed: the reading theme paints the page only. The
    // inherited palette is passed through untouched so the user's chosen app theme
    // survives into the bars, panels and in-page overlays.
    FolioTheme.MaterialTheme(darkTheme = appIsDark, colors = appColors) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.folio.reader.ui.render.LocalOverlayHtml provides overlayHtml,
        com.folio.reader.ui.render.LocalOverlayAction provides { a ->
            handleReaderOverlayAction(
                a = a,
                settings = settings,
                highlights = highlights,
                onClose = {
                    showReaderPanel = false
                    noteDraftFor = null
                    if (showToc) onToggleToc()
                    if (showAnnotations) onToggleAnnotations()
                },
                onOpenAllSettings = {
                    showReaderPanel = false
                    onSettingsClick()
                },
                onChapterChange = { jumpToChapter(it) },
                onSettingsChange = onSettingsChange,
                onSaveNote = { id, text ->
                    if (text.isNotBlank()) onSetHighlightNote(id, text)
                    noteDraftFor = null
                },
                onComposeNote = { noteDraftFor = it },
                onJumpAnnotation = { kind, id -> jumpToAnnotation(kind, id) },
                onRemoveBookmark = onRemoveBookmark,
                onRemoveHighlight = onRemoveHighlight,
                onRemoveNote = onRemoveNote
            )
        }
    ) {
        // The paper behind the page belongs to the reading theme; only the bars,
        // panels and overlays are app chrome. Painting this with the app palette made
        // any uncovered strip of paper change colour with the app theme.
        Box(modifier = Modifier.fillMaxSize().background(Color(readerThemePreset.background))) {
        // Main content - fills entire screen, overlays positioned absolutely
        if (currentChapter != null) {
            val reservedEnd = if (occludes) 0.dp else when {
                showToc -> 260.dp
                showAnnotations -> 300.dp
                showEchoes -> 320.dp
                showReaderPanel -> 280.dp
                else -> 0.dp
            }
            val contentInsets = if (occludes) {
                PaddingValues(
                    top = if (showControls) 56.dp else 0.dp,
                    bottom = if (showControls && settings.showProgress) {
                        if (showChapterLine) pageBlockChromeHeightWithChapter else pageBlockChromeHeight
                    } else 0.dp,
                    end = reservedEnd
                )
            } else {
                PaddingValues(0.dp)
            }
            ChapterContent(
                chapter = currentChapter,
                html = chapterHtml,
                isLoading = isLoadingContent,
                loadError = loadError,
                coverPath = coverPath,
                settings = settings,
                enabled = !showToc && !showAnnotations,
                onTap = onToggleControls,
                onScrollFraction = onScrollProgress,
                onLongPress = onHighlightParagraph,
                onSelectionChanged = { chapterId, idx, text ->
                    pageSelection = text?.let { Triple(chapterId, idx, it) }
                    // Reveal the chrome so Highlight is within reach the moment the
                    // reader selects a passage. A cleared selection leaves the chrome
                    // as the reader left it; a centre tap still hides it.
                    if (text != null) onShowControls()
                },
                clearSelectionRequest = clearSelTick,
                highlights = highlights,
                onRetry = onRetryChapter,
                onLinkClick = onLinkClick,
                onNextChapter = if (currentChapterIndex < chapters.size - 1) { { jumpToChapter(currentChapterIndex + 1) } } else null,
                onPrevChapter = if (currentChapterIndex > 0) { { jumpToChapter(currentChapterIndex - 1) } } else null,
                hasNextChapter = currentChapterIndex < chapters.size - 1,
                hasPrevChapter = currentChapterIndex > 0,
                onResolveImage = onResolveImage,
                onResolveResource = onResolveResource,
                seekRequest = seekReq,
                seekTargetRequest = seekTargetReq,
                modifier = Modifier.fillMaxSize().padding(contentInsets),
                position = position,
                onPageChange = { page, total ->
                    if (pageReported && page != currentPage) {
                        pageHaptics.play(FolioHaptic.PageTurn)
                    }
                    pageReported = true
                    currentPage = page
                    totalPages = total
                    onPageChange(page, total)
                },
                onChapterEnd = onChapterEnd,
                onChapterStart = onChapterStart,
                sections = sections,
                documentSections = documentSections,
                windowed = windowed,
                windowOp = windowOp,
                onVisibleSection = onVisibleSection,
                onExtendForward = onExtendForward,
                onExtendBackward = onExtendBackward,
                onWindowOpApplied = onWindowOpApplied,
                onContentReady = { contentPainted = true }
            )
        } else {
            ReaderNoChapters(onBackPress = onBackPress)
        }

        // §17 morph landing. Composed after the page surface, so it sits *over*
        // it: the plate arrives with the cover the reader tapped, holds opaque over
        // the blank/loading frame, then dissolves once the text beneath it has
        // genuinely painted (contentPainted → plateAlpha). Dropped after the fade so
        // it never keeps a second copy of the cover key alive for the session.
        if (morphLanding != null) {
            com.folio.reader.ui.components.FolioCoverPlate(
                coverPath = coverPath,
                title = bookTitle,
                author = "",
                // The cover key is the one the shelf published, so the plate the
                // reader tapped *is* the plate that lands here. `fillMaxWidth(null)`
                // rather than `fillMaxWidth(fraction)`: the plate needs a bounded
                // width to derive its height from, and an exact-width fill would
                // squash the trim on a wide window.
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = FolioTokens.gutter)
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    // Dissolve on real first-paint. graphicsLayer alpha (not a
                    // recompose) so the fade runs on the render thread while the
                    // shared-element bounds settle underneath it.
                    .graphicsLayer { alpha = plateAlpha }
                    // Shared key only during the fly-in (morphKey); once painted the
                    // plate keeps composing (morphLanding) but drops the key, so its
                    // copy never overlaps the shelf's during a back-to-Library pop.
                    .then(
                        if (morphKey != null) {
                            Modifier.sharedElementOrNoop(FolioSharedKeys.bookCover(morphKey))
                        } else Modifier
                    ),
                width = null,
                halo = null,
                suppressFallbackText = true,
            )
        }

        // Foxing: the page's outer margins wear a little, and the wear concentrates
        // on the edge the reader's thumb has been working, so the page looks handled
        // rather than printed. It carries no pointer modifier, so it never steals a
        // tap from the page, and it is skipped where the page is a heavyweight native
        // window that paints over Compose layers — there it would cost a layer and
        // never appear.
        if (!occludes && settings.showProgress) {
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .pageFoxing(
                        fraction = bookFraction,
                        paper = Color(readerThemePreset.background),
                        ink = Color(readerThemePreset.primaryText)
                    )
            )
        }

        // Floating sync indicator pill - manages its own visibility (hides when idle)
        if (syncState != null) {
            ReaderSyncPill(showControls = showControls, syncState = syncState)
        }

        // End-of-chapter summary chip (§5.3)
        ReaderChapterChipHost(
            chip = chapterChip,
            onDismiss = onDismissChapterChip,
            aboveBar = settings.showProgress && showControls,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Top bar overlay - slides over content
        //
        // The transitions are explicit `tween`s on [FolioTokens.motionStandard],
        // not the default spring. With no spec, `AnimatedVisibility` uses Compose's
        // default *spring*, whose start time comes from the frame clock — so on the
        // reader's first frames, where the chapter decode, the window build and the
        // WebView load all land together, a dropped frame can leave the bar pinned
        // at its start value (fully off-screen, since the slide begins at `-it`)
        // until the clock catches up. That reads as "the chrome arrives a second
        // late" rather than "the chrome slides in", and it is worst on exactly the
        // heavy first frame the reader always has.
        //
        // Rule 19: with motion off the bars render their final static form, so the
        // transition is skipped entirely rather than run at zero duration.
        val chromeMotion = com.folio.reader.ui.theme.rememberMotionEnabled()
        val chromeSpec = androidx.compose.animation.core.tween<Float>(
            durationMillis = com.folio.reader.ui.theme.FolioTokens.motionStandard.toInt(),
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        )
        // The slide animates an offset, so it needs its own spec: the fade's
        // `tween<Float>` will not unify with `FiniteAnimationSpec<IntOffset>`.
        val chromeSlideSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(
            durationMillis = com.folio.reader.ui.theme.FolioTokens.motionStandard.toInt(),
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = if (chromeMotion) {
                androidx.compose.animation.fadeIn(chromeSpec) +
                    androidx.compose.animation.slideInVertically(chromeSlideSpec) { -it }
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (chromeMotion) {
                androidx.compose.animation.fadeOut(chromeSpec) +
                    androidx.compose.animation.slideOutVertically(chromeSlideSpec) { -it }
            } else {
                androidx.compose.animation.ExitTransition.None
            }
        ) {
            ReaderTopBar(
                bookTitle = bookTitle,
                showClock = settings.showClock,
                occludes = occludes,
                pageSelection = pageSelection,
                onHighlightParagraph = onHighlightParagraph,
                onSelectionConsumed = selectionConsumed,
                isBookmarked = isBookmarked,
                bookmarkColor = Color(readerThemePreset.bookmark),
                onBackPress = onBackPress,
                onSearchClick = onSearchClick,
                onBookmarkClick = onBookmarkClick,
                onOpenToc = openToc,
                onOpenAnnotations = openAnnotations,
                onToggleReaderPanel = toggleReaderPanel,
                echoesEnabled = echoesEnabled,
                onOpenEchoes = openEchoesAction
            )
        }

        // Bottom chrome overlay - slides over content. The page block, not a bar:
        // position carried by the material of the page itself. Same explicit spec
        // and Rule 19 skip as the top bar above.
        androidx.compose.animation.AnimatedVisibility(
            visible = settings.showProgress && showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (chromeMotion) {
                androidx.compose.animation.fadeIn(chromeSpec) +
                    androidx.compose.animation.slideInVertically(chromeSlideSpec) { it }
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (chromeMotion) {
                androidx.compose.animation.fadeOut(chromeSpec) +
                    androidx.compose.animation.slideOutVertically(chromeSlideSpec) { it }
            } else {
                androidx.compose.animation.ExitTransition.None
            }
        ) {
            BottomPageBlock(
                chapterTitle = if (showChapterLine) currentChapter?.title ?: "" else "",
                fraction = bookFraction,
                paper = Color(readerThemePreset.background),
                ink = Color(readerThemePreset.primaryText),
                stateLabel = pageBlockLabel,
                pageCountHint = totalPages,
                chapterStops = chapterStops,
                pagesLeftLabel = if (settings.showProgress) pagesLeftLabel else null,
                onSeek = seekToBookFraction
            )
        }

        // Left floating controls panel (skipped on occluding platforms — the
        // heavyweight browser window would cover it; the top bar carries the actions).
        if (!occludes) {
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 300,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 300)
            ),
            exit = androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 300)
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 300)
            ),
            modifier = Modifier.align(Alignment.CenterStart).statusBarsPadding()
        ) {
            ReaderFloatingRail(
                pageSelection = pageSelection,
                onHighlightParagraph = onHighlightParagraph,
                onSelectionConsumed = selectionConsumed,
                isBookmarked = isBookmarked,
                bookmarkColor = Color(readerThemePreset.bookmark),
                onBookmarkClick = onBookmarkClick,
                onOpenToc = openToc,
                onOpenAnnotations = openAnnotations,
                echoesEnabled = echoesEnabled,
                onOpenEchoes = openEchoesAction
            )
        }
        }

        // Side panels (scrim included): Compose overlays on Android; page-side
        // glass overlays on desktop (the heavyweight browser would cover any
        // Compose overlay).
        ReaderSidePanels(
            occludes = occludes,
            showToc = showToc,
            showAnnotations = showAnnotations,
            showReaderPanel = showReaderPanel,
            showEchoes = showEchoes,
            echoesState = echoesState,
            onCloseEchoes = onCloseEchoes,
            onOpenEcho = onOpenEcho,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            onChapterChange = { jumpToChapter(it) },
            onToggleToc = onToggleToc,
            onToggleAnnotations = onToggleAnnotations,
            bookmarks = bookmarks,
            highlights = highlights,
            notes = notes,
            onRemoveBookmark = onRemoveBookmark,
            onRemoveHighlight = onRemoveHighlight,
            onRemoveNote = onRemoveNote,
            onSetHighlightNote = onSetHighlightNote,
            chapterLabel = { spine, chapterId -> chapterLabel(spine, chapterId) },
            onJumpToAnnotation = { kind, id -> jumpToAnnotation(kind, id) },
            settings = settings,
            onSettingsChange = onSettingsChange,
            onDismissReaderPanel = { showReaderPanel = false },
            onOpenFullSettings = {
                showReaderPanel = false
                onSettingsClick()
            },
            scopeControlEnabled = scopeControlEnabled,
            overriddenFields = overriddenFields,
            onWriteGlobal = onWriteGlobal,
            onResetBook = onResetBook
        )
        }
        }
    }
}
