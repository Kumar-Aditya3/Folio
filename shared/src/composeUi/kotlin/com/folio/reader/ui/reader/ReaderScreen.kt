@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.reader

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.folio.reader.model.Bookmark
import com.folio.reader.model.BookTocRow
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.locatorsMatch
import com.folio.reader.model.spotLocator
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.forReaderContent
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.pageBlockBandFraction
import com.folio.reader.ui.components.pageBlockChapterStops
import com.folio.reader.ui.components.pageBlockSeekTarget
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha
import com.folio.reader.ui.theme.rememberFolioHaptics
import kotlin.math.roundToInt
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

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
    /**
     * The book's own nav/NCX Contents rows. Empty falls back to one row per chapter,
     * which is what a book with a chapter-per-file spine shows anyway.
     */
    bookToc: List<BookTocRow> = emptyList(),
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
    /** When set (opened from the Stats "kept passages" card), scroll to this highlight once loaded. */
    targetHighlightId: String? = null,
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
    /** In-reader search sheet: the field, its hits (nearest first) and whether a query is running. */
    showSearch: Boolean = false,
    searchQuery: String = "",
    searchResults: List<com.folio.reader.database.SearchResult> = emptyList(),
    searching: Boolean = false,
    onSearchQueryChange: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
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
    onNoteSelection: ((chapterId: String, paragraphIndex: Int, selectedText: String, note: String) -> Unit)? = null,
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
    initialSeekFraction: Float? = null,
    onPreviewChapter: suspend (Int, Float, Int?) -> ReaderPassagePreview? = { _, _, _ -> null },
    initialLocator: String? = null,
    reflectionInvitation: com.folio.reader.experience.ReflectionInvitation? = null,
    onSaveReflection: (String) -> Unit = {},
    onDismissReflection: () -> Unit = {},
) {
    val readingExperience = com.folio.reader.experience.rememberReaderExperienceSession(bookTitle, coverPath, position, chapters, currentChapterIndex, settings, highlights, notes)
    val contentSettings = remember(settings) { settings.forReaderContent() }
    LaunchedEffect(currentChapterIndex, position?.bookId) {
        kotlinx.coroutines.delay(800)
        readingExperience.latestExcerpt = onPreviewChapter(currentChapterIndex, (position?.chapterProgress ?: 0.0).toFloat(), null)?.text.orEmpty()
    }
    androidx.compose.runtime.CompositionLocalProvider(com.folio.reader.experience.LocalReaderExperience provides readingExperience) {

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
    val narration = com.folio.reader.narration.LocalNarrationController.current
    val narrationState = narration?.state?.collectAsState()?.value ?: com.folio.reader.narration.NarrationState()
    val narratingHere = narrationState.hasBook && narrationState.bookId == position?.bookId
    var showListen by remember { mutableStateOf(false) }
    var listenOrigin by remember { mutableStateOf<ReadingPosition?>(null) }
    var showEchoesWorkspace by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Float?>(null) }
    var returnOrigin by remember(position?.bookId) { mutableStateOf<ReadingPosition?>(null) }
    var selectionNote by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var selectionNoteText by remember { mutableStateOf("") }
    var selectionNoteDraftKey by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var selectionNoteAnchor by remember { mutableStateOf<Rect?>(null) }
    var noteInWorkspace by remember { mutableStateOf(false) }
    fun keepOrigin() { if (returnOrigin == null) returnOrigin = position }

    // Bottom progress bar taps request a seek to a chapter fraction.
    var seekReq by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    var seekNonce by remember { mutableStateOf(0L) }
    // The page's live text selection (carries the chapter the paragraph sits in —
    // a windowed selection may not be the anchor's chapter). Highlighting is a
    // chrome action because the OS selection toolbar covers anything drawn near
    // the text on phones.
    var pageSelection by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var pageSelectionBounds by remember { mutableStateOf<com.folio.reader.ui.render.ReaderSelectionBounds?>(null) }
    var readerBounds by remember { mutableStateOf(Rect.Zero) }
    var pageViewport by remember { mutableStateOf(Rect.Zero) }
    var selectionToolbarSize by remember { mutableStateOf(IntSize.Zero) }
    val clipboard = LocalClipboardManager.current
    var clearSelTick by remember { mutableStateOf(0L) }
    // Highlight whose note is being written in the glass composer.
    var noteDraftFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentChapterIndex) { pageSelection = null; pageSelectionBounds = null }
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
    // The chapter a jump aimed at, with the fraction that row's own heading sits at. `goToChapter`
    // reseeds `chapterProgress` to 0.0 for a jump and the page's first report arrives a beat later, so
    // in that window a heading-less book — several Contents rows sharing one spine file — has no
    // fraction left to break the tie, and the lit row fell back to the PREVIOUS chapter's last one:
    // tapping "Chapter Twenty-Four" lit "Twenty-Two". Consulted only for the chapter it was set for,
    // and only until the page reports a position of its own.
    var jumpSeed by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    LaunchedEffect(position?.chapterProgress) {
        if ((position?.chapterProgress ?: 0.0) > 0.0) jumpSeed = null
    }
    // The chapter a live seek request belongs to, or MIN_VALUE when none is live. A page-turn
    // that carries no jump must drop a leftover request — it would otherwise re-fire against the
    // freshly loaded chapter and seek it to "p:0", stomping the end-seed that "back from a
    // chapter's start" depends on (the intermittent land-on-first-page). But it must not drop a
    // request issued FOR the chapter now on screen: a Contents tap aimed at a chapter already
    // inside the loaded window changes the index without reloading anything, so both happen in
    // the same frame as the navigation, and issuing the seek and clearing it from two effects
    // made the second discard the first. That is why release and clear live in ONE effect.
    var seekOwnedBy by remember { mutableStateOf(Int.MIN_VALUE) }

    fun issueTargetSeek(target: String) {
        seekNonce++
        seekOwnedBy = currentChapterIndex
        seekTargetReq = target to seekNonce
    }

    fun issueFractionSeek(fraction: Float) {
        seekNonce++
        seekOwnedBy = currentChapterIndex
        seekReq = fraction.coerceIn(0f, 1f) to seekNonce
    }

    LaunchedEffect(pendingJump, currentChapterIndex, chapterHtml, isLoadingContent) {
        val jump = pendingJump
        if (jump == null) {
            if (seekOwnedBy != currentChapterIndex) {
                seekReq = null
                seekTargetReq = null
                seekOwnedBy = Int.MIN_VALUE
            }
            return@LaunchedEffect
        }
        // Released only once the target is on screen, else the seek would move the old chapter.
        if (currentChapterIndex != jump.first) return@LaunchedEffect
        if (isLoadingContent || chapterHtml.isBlank()) return@LaunchedEffect
        pendingJump = null
        jump.second?.let { issueTargetSeek(it) } ?: jump.third?.let { issueFractionSeek(it) }
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
        issueFractionSeek(fraction)
    }

    // ── The page block (diegetic progress) ──────────────────────────────────────
    // Chapter ends along the block, word-weighted so a long chapter takes the length
    // of block it is worth. The block is whole-book on purpose: one that emptied at
    // every chapter boundary would be a lie about how much of the book is left.
    val chapterStops = remember(chapters) {
        pageBlockChapterStops(chapters.map { it.wordCount })
    }
    var scrubPassage by remember(seekPreview) { mutableStateOf<ReaderPassagePreview?>(null) }
    var scrubLoading by remember(seekPreview) { mutableStateOf(seekPreview != null) }
    val previewProvider by rememberUpdatedState(onPreviewChapter)
    LaunchedEffect(seekPreview, chapters) {
        scrubPassage = null
        val fraction = seekPreview
        if (fraction == null) { scrubLoading = false; return@LaunchedEffect }
        scrubLoading = true
        try {
            kotlinx.coroutines.delay(100)
            val landing = pageBlockSeekTarget(chapterStops, fraction)
            scrubPassage = previewProvider(landing.chapterIndex, landing.chapterFraction, null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            scrubPassage = null
        } finally { scrubLoading = false }
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
    val liveChapterFrac = position?.takeIf { it.chapterId == currentChapter?.id }
        ?.chapterProgress?.coerceIn(0.0, 1.0) ?: 0.0
    val chapterFrac = jumpSeed?.takeIf { it.first == currentChapterIndex && liveChapterFrac == 0.0 }
        ?.second ?: liveChapterFrac
    val bookFraction = pageBlockBandFraction(
        stops = chapterStops,
        bandFirst = currentChapterIndex,
        bandLast = currentChapterIndex,
        bandFraction = chapterFrac.toFloat(),
    )
    val tracePassages = remember(highlights, notes, chapters) {
        val liveNotes = notes.filterNot { it.isDeleted }.mapTo(HashSet()) { it.id }
        val marked = highlights.map { if (it.noteId != null && it.noteId !in liveNotes) it.copy(noteId = null) else it }
        com.folio.reader.model.readingTrace(0f, (chapters.maxOfOrNull { it.spineIndex } ?: 0) + 1,
            emptyList(), marked, chapters).passages
    }
    val showChapterLine = settings.showChapterTitle && !currentChapter?.title.isNullOrBlank()
    // Show the whole-book percentage alongside a measure the current layout knows:
    // concrete pages after pagination settles, estimated reading time for continuous text.
    val pagesLeftLabel: String = buildString {
        append("${(bookFraction * 100).roundToInt()}%")
        if (windowed) {
            val wordsLeft = ((currentChapter?.wordCount ?: 0L) * (1.0 - chapterFrac)).toLong().coerceAtLeast(0)
            if (wordsLeft > 0) append(" · ~${(wordsLeft + 219) / 220} min in chapter")
            else if ((currentChapter?.wordCount ?: 0L) > 0L) append(" · Chapter end")
        } else if (pageReported) {
            val left = (totalPages - currentPage).coerceAtLeast(0)
            append(if (left == 0) " · Last page" else " · $left ${if (left == 1) "page" else "pages"} left")
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
        keepOrigin()
        val landing = pageBlockSeekTarget(chapterStops, bookTarget)
        if (landing.chapterIndex in renderedSpan) {
            val start = chapterStops.getOrElse(renderedSpan.first) { 0f }
            val width = chapterStops.getOrElse(renderedSpan.last + 1) { 1f } - start
            issueFractionSeek(if (width > 0f) ((bookTarget - start) / width).coerceIn(0f, 1f) else 0f)
        } else {
            val alreadyAsked = pendingJump?.first == landing.chapterIndex
            val alreadyThere = landing.chapterIndex == currentChapterIndex
            if (!alreadyAsked && !alreadyThere) {
                pendingJump = Triple(landing.chapterIndex, null, landing.chapterFraction)
                if (landing.chapterFraction > 0f) {
                    jumpSeed = landing.chapterIndex to landing.chapterFraction.toDouble()
                }
                onChapterChange(landing.chapterIndex)
            }
        }
    }

    /**
     * A Contents tap or chapter turn aimed at a neighbour already inside the loaded window
     * changes no document, so `setChapter` returns without reloading and the reader never
     * moves. The move is a seek, and `pendingJump` is the only thing that issues one —
     * annotation jumps already route through it, so chapter taps do too. The seek target is
     * scoped to the target's own section by the surface, landing the reader at that paragraph.
     *
     * [seekParagraph] is a 0-based `<p>` ordinal within the target chapter (the `p:<n>` seek
     * grammar). It is non-zero only for heading-less books whose Contents was recovered by
     * scanning several chapters out of one spine file; 0 is the chapter top for every normal jump.
     * When the target is the chapter already on screen a pendingJump would never fire (the index
     * does not change), so that case issues the seek directly.
     */
    fun jumpToChapter(index: Int, seekParagraph: Int = 0, seedFraction: Double = 0.0, sourceLocator: String? = null) {
        val target = sourceLocator ?: "p:$seekParagraph"
        if (index != currentChapterIndex) {
            pendingJump = Triple(index, target, null)
        } else {
            issueTargetSeek(target)
        }
        if (seedFraction > 0.0) jumpSeed = index to seedFraction
        onChapterChange(index)
    }

    var lastHeard by remember { mutableStateOf<ReadingPosition?>(null) }
    SideEffect { if (narratingHere) lastHeard = narrationState.position }
    LaunchedEffect(narratingHere) {
        val heard = lastHeard
        if (!narratingHere && heard?.bookId == position?.bookId && narrationState.phase == com.folio.reader.narration.NarrationPhase.Idle) {
            val index = chapters.indexOfFirst { it.spineIndex == heard?.spineIndex }
            if (index >= 0 && heard != null) jumpToChapter(index,heard.paragraphIndex,heard.chapterProgress,heard.contentLocator.takeIf { it.startsWith("n:") })
            lastHeard = null
        }
    }

    val narrationWindowVisible = com.folio.reader.ui.theme.LocalFolioWindowVisible.current
    LaunchedEffect(narrationState.position?.spineIndex, narrationState.blockId, narrationState.followText, narrationWindowVisible) {
        if (narrationWindowVisible && narratingHere && narrationState.followText) {
            val index = chapters.indexOfFirst { it.spineIndex == narrationState.position?.spineIndex }
            if (index >= 0 && index != currentChapterIndex) jumpToChapter(index)
        }
    }

    // The reading theme paints the page and nothing else. Chrome and overlays are app
    // surfaces, so they take the app palette — this is what kept the two from bleeding
    // into each other in opposite directions.
    val readerThemePreset = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)

    val appColors = FolioTheme.colors
    val overlayColors = readerOverlayColors()
    val appIsDark = overlayColors.isDark
    val quickFontNames = remember(settings.customFonts) {
        (platformBaseReaderFonts() + settings.customFonts.map { it.name }).distinct()
    }
    fun chapterLabel(spineIndex: Int?, chapterId: String?): String =
        chapters.firstOrNull { spineIndex != null && it.spineIndex == spineIndex }?.title
            ?: chapters.firstOrNull { chapterId != null && it.id == chapterId }?.title
            ?: if (spineIndex == null && chapterId == null) "Whole book" else "Spine ${(spineIndex ?: 0) + 1}"
    // One list for both Contents surfaces: the book's own navigation when it has
    // any, else every chapter in spine order.
    val tocEntries = bookToc.ifEmpty { chapters.mapIndexed { index, ch -> BookTocRow(ch.title, index) } }
    val overlayHtml = readerOverlayHtml(
        occludes = false,
        showReaderPanel = showReaderPanel,
        showToc = showToc,
        showAnnotations = showAnnotations,
        noteDraftFor = noteDraftFor,
        settings = settings,
        tocEntries = tocEntries,
        currentChapterIndex = currentChapterIndex,
        currentFraction = chapterFrac.toFloat(),
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
                target?.let { issueTargetSeek(it) } ?: fraction?.let { issueFractionSeek(it) }
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

    // Opened from the Stats "kept passages" card: once the book has loaded and the target
    // highlight is in the list, scroll to it exactly once — the same jump the in-reader
    // annotations panel performs. Keyed on the id so it re-arms only for a new target, not
    // on every scroll tick.
    var jumpedToTargetHighlight by remember(targetHighlightId) { mutableStateOf(false) }
    var jumpedToInitialLocator by remember(initialLocator) { mutableStateOf(false) }
    LaunchedEffect(initialLocator, targetHighlightId, isLoadingContent, chapterHtml, currentChapterIndex) {
        val locator = initialLocator ?: return@LaunchedEffect
        if (targetHighlightId != null || jumpedToInitialLocator || isLoadingContent || chapterHtml.isBlank()) return@LaunchedEffect
        val chapter = currentChapter ?: return@LaunchedEffect
        jumpToLocation(chapter.spineIndex, chapter.id, locator)
        jumpedToInitialLocator = true
    }
    LaunchedEffect(targetHighlightId, highlights, isLoadingContent, chapterHtml) {
        val hid = targetHighlightId ?: return@LaunchedEffect
        if (jumpedToTargetHighlight) return@LaunchedEffect
        if (isLoadingContent || chapterHtml.isBlank()) return@LaunchedEffect
        if (highlights.none { it.id == hid }) return@LaunchedEffect
        jumpToAnnotation("hl", hid)
        jumpedToTargetHighlight = true
    }

    val isBookmarked = remember(position, bookmarks) {
        position?.let { pos ->
            bookmarks.any { it.chapterId == pos.chapterId && locatorsMatch(it.locator, pos.spotLocator()) }
        } ?: false
    }
    val selectionConsumed: () -> Unit = { pageSelection = null; pageSelectionBounds = null; clearSelTick++ }
    fun beginSelectionNote(inWorkspace: Boolean) {
        val selected = pageSelection ?: return
        if (onNoteSelection == null) return
        if (selectionNoteDraftKey != selected) selectionNoteText = ""
        selectionNoteDraftKey = selected
        selectionNote = selected
        selectionNoteAnchor = pageSelectionBounds?.let { readerSelectionRect(it, pageViewport) }
        noteInWorkspace = inWorkspace
        if (inWorkspace) {
            showReaderPanel = false
            if (!showAnnotations) onToggleAnnotations()
        }
        selectionConsumed()
    }
    fun closeSelectionNote(cancel: Boolean = false) {
        selectionNote = null
        if (cancel) { selectionNoteDraftKey = null; selectionNoteText = "" }
        if (noteInWorkspace && showAnnotations) onToggleAnnotations()
        noteInWorkspace = false
    }
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
            if (showSearch) onCloseSearch()
        }
    }
    // The magnifier opens the search sheet. It is the same end-edge surface as Contents, so it has
    // to close the reading-settings panel too — that one is local state here, which the view
    // model's own mutual exclusion cannot reach.
    val openSearchAction: () -> Unit = {
        showReaderPanel = false
        onSearchClick()
    }
    // Echoes opens from the current selection; the VM runs the cross-book lookup and closes the
    // other panels. The reader panel is local state here, so close it too.
    val openEchoesAction: () -> Unit = {
        val text = pageSelection?.third
        if (!text.isNullOrBlank()) {
            showReaderPanel = false
            onOpenEchoes(text)
            selectionConsumed()
        }
    }

    // Reader chrome is app-themed: the reading theme paints the page only. The
    // inherited palette is passed through untouched so the user's chosen app theme
    // survives into the bars, panels and in-page overlays.
    // WebView pages cannot be captured by Haze. Use the translucent painted material,
    // rather than sampling the app field or selecting an optical pass with no input.
    com.folio.reader.ui.components.FolioSuppressibleBackdrop(suppress = true) {
    FolioTheme.MaterialTheme(darkTheme = appIsDark, colors = appColors) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.folio.reader.ui.render.LocalOverlayHtml provides overlayHtml,
        com.folio.reader.ui.render.LocalOverlayAction provides { a ->
            if (a.startsWith("sel:")) {
                when (a.removePrefix("sel:")) {
                    "keep" -> pageSelection?.let { selected ->
                        onHighlightParagraph?.invoke(selected.first, selected.second, selected.third); selectionConsumed()
                    }
                    "note" -> beginSelectionNote(true)
                    "export" -> pageSelection?.let { selected ->
                        readingExperience.environment?.exportAnchor = pageSelectionBounds?.let { readerSelectionRect(it, pageViewport) }
                        readingExperience.exportSelection(selected.first, selected.second, selected.third); selectionConsumed()
                    }
                    "copy" -> pageSelection?.let { clipboard.setText(AnnotatedString(it.third)); selectionConsumed() }
                }
            } else handleReaderOverlayAction(
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
                onChapterChange = { i, p ->
                    val row = tocEntries.firstOrNull { it.chapterIndex == i && it.paragraph == p }
                    jumpToChapter(i, p, row?.fraction?.toDouble() ?: 0.0)
                },
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
        //
        // Because that paper is opaque and full-bleed, the app's animated ground plane is
        // invisible for the whole of this route. Claiming the occlusion takes the field out of
        // the composition, which is the only thing that stops Mirage's per-vsync clock — a
        // draw-time skip would leave a full-rate frame request alive behind an opaque page.
        com.folio.reader.ui.theme.FolioFieldOccluder()
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(readerThemePreset.background))
            .onGloballyPositioned { readerBounds = it.boundsInWindow() }) {
        val workspaceLayout = readerWorkspaceLayout(maxWidth, maxHeight, LocalDensity.current.fontScale)
        val workspaceOpen = showListen || showToc || showAnnotations || showSearch || showReaderPanel || showEchoes || showEchoesWorkspace || (noteInWorkspace && selectionNote != null)
        val reservedEnd = if (workspaceOpen && workspaceLayout.docked) workspaceLayout.dockWidth else 0.dp
        // The reader's chrome and in-reader panels wear the calm glass unless the
        // reader is in editorial styling: dimmed, hue-stripped light catches so the
        // islands stop reading as shiny and book-edge-tinted. Scoped to the reader
        // subtree, so every other surface keeps its full material (Rule 19).
        androidx.compose.runtime.CompositionLocalProvider(
            com.folio.reader.ui.theme.LocalFolioReaderCalmGlass provides !settings.editorialReading
        ) {
        // Main content - fills entire screen, overlays positioned absolutely
        if (currentChapter != null) {
            val chromeBottom = if (occludes && showControls && settings.showProgress)
                (if (showChapterLine) pageBlockChromeHeightWithChapter else pageBlockChromeHeight) + 14.dp else 0.dp
            val nativePaneBottom = if (occludes && workspaceOpen && !workspaceLayout.docked)
                workspaceLayout.sheetHeight + 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
            val quietActionsBottom = if (occludes && !workspaceOpen && (readingExperience.targetFinished || reflectionInvitation != null)) 240.dp else 0.dp
            val contentInsets = PaddingValues(top = if (occludes && showControls) 56.dp else 0.dp,
                bottom = maxOf(chromeBottom, nativePaneBottom, quietActionsBottom), end = reservedEnd)
            ChapterContent(
                chapter = currentChapter,
                html = chapterHtml,
                isLoading = isLoadingContent,
                loadError = loadError,
                coverPath = coverPath,
                settings = contentSettings,
                enabled = workspaceLayout.docked || (!showToc && !showAnnotations),
                onTap = onToggleControls,
                onScrollFraction = onScrollProgress,
                onLongPress = onHighlightParagraph,
                onSelectionChanged = { chapterId, idx, text ->
                    pageSelection = text?.let { Triple(chapterId, idx, it) }
                    // Reveal the chrome so Highlight is within reach the moment the
                    // reader selects a passage. A cleared selection leaves the chrome
                    // as the reader left it; a centre tap still hides it.
                    if (text != null && occludes) onShowControls()
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
                modifier = Modifier.fillMaxSize().padding(contentInsets).onGloballyPositioned { pageViewport = it.boundsInWindow() },
                onSelectionBounds = { pageSelectionBounds = it },
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

        // Eye-protection: a warm amber wash over the reading surface, painted
        // below the chrome so the controls stay neutral. Global comfort setting;
        // intensity scales the warmth. Decorative only (no pointer modifier), so
        // it never intercepts taps to the page beneath.
        if (settings.eyeProtection) {
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color(0xFFFF8A3D).copy(
                            alpha = (settings.eyeProtectionIntensity * 0.35f).coerceIn(0f, 0.35f)
                        )
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
                modifier = Modifier.padding(end = reservedEnd),
                bookTitle = bookTitle,
                showClock = settings.showClock,
                occludes = occludes,
                pageSelection = pageSelection,
                onHighlightParagraph = onHighlightParagraph,
                onSelectionConsumed = selectionConsumed,
                isBookmarked = isBookmarked,
                bookmarkColor = Color(readerThemePreset.bookmark),
                onBackPress = onBackPress,
                onSearchClick = openSearchAction,
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
            visible = settings.showProgress && showControls && !workspaceOpen,
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
                editorial = settings.editorialReading,
                readingTraceId = position?.bookId ?: morphBookId,
                readingTrace = (com.folio.reader.ui.components.LocalReadingTraces.current[position?.bookId ?: morphBookId ?: ""]
                    ?: com.folio.reader.model.ReadingTrace()).copy(
                    progress = bookFraction,
                    passages = tracePassages),
                onPreview = { seekPreview = it },
                onSeek = seekToBookFraction,
                onOpenMap = openToc,
            )
        }

        if (!occludes && settings.showProgress && contentPainted && !workspaceOpen) {
            ReaderProgressRail(bookFraction, chapterStops,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().statusBarsPadding()
                    .padding(top = 68.dp, bottom = 110.dp),
                ink = Color(readerThemePreset.primaryText))
        }

        if (!occludes) {
            val selection = pageSelection
            val selectionVisible = selection != null && !showToc && !showAnnotations && !showSearch && !showReaderPanel && !showEchoes
            var lastSelection by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
            var lastSelectionAnchor by remember { mutableStateOf<Rect?>(null) }
            if (selectionVisible) {
                lastSelection = selection
                lastSelectionAnchor = pageSelectionBounds?.let { readerSelectionRect(it, pageViewport) }
            }
                val anchor = lastSelectionAnchor
                val gap = with(LocalDensity.current) { 24.dp.toPx() }
                val toolbarModifier = if (anchor != null) {
                    Modifier.align(Alignment.TopStart).offset {
                        val point = readerSelectionToolbarPosition(anchor, pageViewport, selectionToolbarSize, gap)
                        androidx.compose.ui.unit.IntOffset(point.x - readerBounds.left.roundToInt(), point.y - readerBounds.top.roundToInt())
                    }
                } else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)
                val origin = if (anchor != null && readerSelectionToolbarPosition(anchor, pageViewport, selectionToolbarSize, gap).y < anchor.top)
                    TransformOrigin(.5f, 1f) else TransformOrigin(.5f, 0f)
                AnimatedReaderSelectionTools(selectionVisible,
                    toolbarModifier.onSizeChanged { if (it != IntSize.Zero) selectionToolbarSize = it }, origin) {
                    val displayed = lastSelection
                    if (displayed != null) ReaderSelectionBar(displayed.third,
                        onHighlight = {
                            if (selectionVisible) {
                                onHighlightParagraph?.invoke(displayed.first, displayed.second, displayed.third)
                                selectionConsumed()
                            }
                        },
                        onNote = onNoteSelection?.let { { if (selectionVisible) beginSelectionNote(workspaceLayout.docked) } },
                        onEchoes = if (echoesEnabled) openEchoesAction else null,
                        onExport = { if (selectionVisible) { readingExperience.environment?.exportAnchor = anchor; readingExperience.exportSelection(displayed.first, displayed.second, displayed.third); selectionConsumed() } },
                        onListen = narration?.let { controller -> { if (selectionVisible) {
                            val chapter = chapters.firstOrNull { it.id == displayed.first }
                            position?.copy(chapterId = displayed.first, spineIndex = chapter?.spineIndex ?: 0,
                                paragraphIndex = displayed.second, characterOffset = 0, contentLocator = "selection:${displayed.second}")?.let { selectedPosition ->
                                listenOrigin = selectedPosition
                                if (controller.pack.value.phase == com.folio.reader.narration.VoicePackPhase.Installed &&
                                    (!controller.state.value.hasBook || controller.state.value.bookId == selectedPosition.bookId)) controller.start(selectedPosition)
                                showListen = true
                            }
                            selectionConsumed()
                        } } },
                        onDismiss = selectionConsumed)
                }
            val preview = seekPreview
            if (preview != null) {
                val landing = pageBlockSeekTarget(chapterStops, preview)
                ReaderNavigationPreview(chapters.getOrNull(landing.chapterIndex)?.title.orEmpty(),
                    "${landing.chapterIndex + 1} / ${chapters.size}  ·  ${(preview * 100).roundToInt()}%",
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp),
                    passage = scrubPassage?.text, loading = scrubLoading)
            } else if (returnOrigin != null && showControls && selection == null) {
                val origin = returnOrigin!!
                ReaderReturnButton(chapterLabel(origin.spineIndex, origin.chapterId), onClick = {
                    jumpToLocation(origin.spineIndex, origin.chapterId, origin.spotLocator())
                    returnOrigin = null
                }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
            }
            selectionNote?.takeIf { !noteInWorkspace }?.let { selected ->
                NoteComposerDialog(passage = selected.third, noteContent = selectionNoteText,
                    onNoteChange = { selectionNoteText = it },
                    onSave = {
                        onNoteSelection?.invoke(selected.first, selected.second, selected.third, selectionNoteText.trim())
                        selectionNote = null; selectionNoteDraftKey = null; selectionNoteText = ""; selectionConsumed()
                    },
                    onDismiss = { selectionNote = null },
                    onCancel = { closeSelectionNote(true) }, anchor = selectionNoteAnchor, autoFocus = true)
            }
        }

        // Side panels (scrim included): Compose overlays on Android; page-side
        // glass overlays on desktop (the heavyweight browser would cover any
        // Compose overlay).
        val editingSelection = selectionNote
        val readingModal = readingExperience.environment?.exports != null || readingExperience.environment?.journal != null ||
            readingExperience.showReminder || readingExperience.returnCard != null || readingExperience.reflectionEditor
        if (!workspaceOpen && !readingModal) com.folio.reader.experience.ReaderSoundAtmosphere(readingExperience, Modifier.matchParentSize())
        if (!workspaceOpen && !narratingHere) com.folio.reader.experience.ReaderPauseActions(readingExperience, reflectionInvitation, onBackPress,
            onDismissReflection, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = 100.dp))
        ReaderSidePanels(
            showListen = showListen,
            onCloseListen = { showListen = false; listenOrigin = null },
            listenContent = narration?.let { controller -> { com.folio.reader.narration.NarrationPanel(controller, listenOrigin ?: position) } },
            occludes = occludes,
            showToc = showToc,
            showAnnotations = showAnnotations,
            showReaderPanel = showReaderPanel,
            showEchoes = showEchoes || showEchoesWorkspace,
            echoesEnabled = echoesEnabled,
            echoesState = echoesState,
            onCloseEchoes = { showEchoesWorkspace = false; onCloseEchoes() },
            onOpenEcho = onOpenEcho,
            showSearch = showSearch,
            searchQuery = searchQuery,
            searchResults = searchResults,
            searching = searching,
            onSearchQueryChange = onSearchQueryChange,
            // A hit is a chapter, so it lands the way a Contents row does — and the sheet gets out
            // of the way, because the reader asked to see the page, not the results.
            onSearchResultClick = { hit ->
                keepOrigin()
                jumpToChapter(hit.spineIndex)
                onCloseSearch()
            },
            onCloseSearch = onCloseSearch,
            tocEntries = tocEntries,
            currentChapterIndex = currentChapterIndex,
            currentFraction = chapterFrac.toFloat(),
            onTocRowClick = { row -> keepOrigin(); jumpToChapter(row.chapterIndex, row.paragraph, row.fraction.toDouble()); onToggleToc() },
            onToggleToc = onToggleToc,
            onToggleAnnotations = { if (showAnnotations) { selectionNote = null; noteInWorkspace = false }; onToggleAnnotations() },
            bookmarks = bookmarks,
            highlights = highlights,
            notes = notes,
            onRemoveBookmark = onRemoveBookmark,
            onRemoveHighlight = onRemoveHighlight,
            onRemoveNote = onRemoveNote,
            onSetHighlightNote = onSetHighlightNote,
            chapterLabel = { spine, chapterId -> chapterLabel(spine, chapterId) },
            onJumpToAnnotation = { kind, id -> keepOrigin(); jumpToAnnotation(kind, id) },
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
            onResetBook = onResetBook,
            onSelectTool = { tool ->
                showListen = false
                showReaderPanel = false
                showEchoesWorkspace = false
                if (showToc) onToggleToc()
                if (showAnnotations) onToggleAnnotations()
                if (showSearch) onCloseSearch()
                if (showEchoes) onCloseEchoes()
                when (tool) {
                    ReaderTool.Listen -> showListen = true
                    ReaderTool.Contents -> onToggleToc()
                    ReaderTool.Saved -> onToggleAnnotations()
                    ReaderTool.Search -> onSearchClick()
                    ReaderTool.Settings -> showReaderPanel = true
                    ReaderTool.Echoes -> showEchoesWorkspace = true
                    ReaderTool.Pages -> Unit
                }
            },
            layout = workspaceLayout,
            noteEditor = if (noteInWorkspace && editingSelection != null) { {
                val selected = editingSelection
                ReaderNoteEditor(selected.third, selectionNoteText, { selectionNoteText = it },
                    onSave = {
                        onNoteSelection?.invoke(selected.first, selected.second, selected.third, selectionNoteText.trim())
                        closeSelectionNote(true)
                    }, onCancel = { closeSelectionNote(true) }, embedded = true, autoFocus = true)
            } } else null,
            contents = {
                val origin = returnOrigin
                val originIndex = origin?.let { saved -> chapters.indexOfFirst { it.id == saved.chapterId || it.spineIndex == saved.spineIndex } }
                val originFraction = originIndex?.takeIf { it >= 0 }?.let {
                    pageBlockBandFraction(chapterStops, it, it, origin!!.chapterProgress.toFloat())
                }
                BookReadingMap(position?.bookId ?: morphBookId.orEmpty(), chapters, tocEntries, chapterStops,
                    currentChapterIndex, bookFraction, originFraction,
                    (com.folio.reader.ui.components.LocalReadingTraces.current[position?.bookId.orEmpty()]
                        ?: com.folio.reader.model.ReadingTrace()).copy(progress = bookFraction, passages = tracePassages),
                    onPreviewChapter = onPreviewChapter,
                    onRead = { chapter, fraction, paragraph ->
                        keepOrigin()
                        if (paragraph != null) jumpToChapter(chapter, paragraph, fraction.toDouble())
                        else seekToBookFraction(pageBlockBandFraction(chapterStops, chapter, chapter, fraction))
                        if (showToc) onToggleToc()
                    }, onDismiss = { if (showToc) onToggleToc() })
            }
        )
        }
        }
        }
    }
    }

    com.folio.reader.experience.ReaderCompanionHost(readingExperience, onBackPress,
        reflectionInvitation, onSaveReflection, onDismissReflection,
        onOpenReturn = { context -> jumpToLocation(context.position.spineIndex, context.position.chapterId, context.position.spotLocator()) })
    }
}
