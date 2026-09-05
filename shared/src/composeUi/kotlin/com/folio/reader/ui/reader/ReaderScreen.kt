package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.readerVeilAlpha

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
    onHighlightParagraph: ((paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onRetryChapter: (() -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    onResolveImage: suspend (chapterHref: String, src: String) -> String? = { _, _ -> null },
    onResolveResource: suspend (chapterHref: String, src: String) -> String? = onResolveImage,
    syncState: com.folio.reader.sync.SyncState? = null,
    onPageChange: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    onChapterEnd: () -> Unit = {},
    onChapterStart: () -> Unit = {},
    chapterChip: String? = null,
    onDismissChapterChip: () -> Unit = {},
    scopeControlEnabled: Boolean = false,
    overriddenFields: Set<String> = emptySet(),
    onWriteGlobal: ((ReaderSettings) -> Unit)? = null,
    onResetBook: (() -> Unit)? = null
) {
    val currentChapter = chapters.getOrNull(currentChapterIndex)
    var currentPage by remember(currentChapterIndex) { mutableStateOf(1) }
    var totalPages by remember(currentChapterIndex) { mutableStateOf(1) }

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
    // The page's live text selection. Highlighting is a chrome action because the
    // OS selection toolbar covers anything drawn near the text on phones.
    var pageSelection by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var clearSelTick by remember { mutableStateOf(0L) }
    // Highlight whose note is being written in the glass composer.
    var noteDraftFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentChapterIndex) { pageSelection = null }
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
        (listOf(
            "Calluna", "Comfortaa", "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
            "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
        ) + settings.customFonts.map { it.name }).distinct()
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
                onChapterChange = onChapterChange,
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
                showReaderPanel -> 280.dp
                else -> 0.dp
            }
            val contentInsets = if (occludes) {
                PaddingValues(
                    top = if (showControls) 56.dp else 0.dp,
                    bottom = if (showControls && settings.showProgress) 44.dp else 0.dp,
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
                onSelectionChanged = { idx, text ->
                    pageSelection = text?.let { idx to it }
                    // Reveal the chrome so Highlight is within reach the moment the
                    // reader selects a passage. A cleared selection leaves the chrome
                    // as the reader left it; a centre tap still hides it.
                    if (text != null) onShowControls()
                },
                clearSelectionRequest = clearSelTick,
                highlights = highlights,
                onRetry = onRetryChapter,
                onLinkClick = onLinkClick,
                onNextChapter = if (currentChapterIndex < chapters.size - 1) { { onChapterChange(currentChapterIndex + 1) } } else null,
                onPrevChapter = if (currentChapterIndex > 0) { { onChapterChange(currentChapterIndex - 1) } } else null,
                hasNextChapter = currentChapterIndex < chapters.size - 1,
                hasPrevChapter = currentChapterIndex > 0,
                onResolveImage = onResolveImage,
                onResolveResource = onResolveResource,
                seekRequest = seekReq,
                seekTargetRequest = seekTargetReq,
                modifier = Modifier.fillMaxSize().padding(contentInsets),
                position = position,
                onPageChange = { page, total ->
                    currentPage = page
                    totalPages = total
                    onPageChange(page, total)
                },
                onChapterEnd = onChapterEnd,
                onChapterStart = onChapterStart
            )
        } else {
            ReaderNoChapters(onBackPress = onBackPress)
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
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it }
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
                onToggleReaderPanel = toggleReaderPanel
            )
        }

        // Bottom progress bar overlay - slides over content
        androidx.compose.animation.AnimatedVisibility(
            visible = settings.showProgress && showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it }
        ) {
            BottomProgressBar(
                chapterTitle = if (settings.showChapterTitle) currentChapter?.title ?: "" else "",
                currentPage = currentPage,
                totalPages = totalPages,
                onSeek = { f ->
                    seekNonce += 1L
                    seekReq = f to seekNonce
                }
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
                onOpenAnnotations = openAnnotations
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
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            onChapterChange = onChapterChange,
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
