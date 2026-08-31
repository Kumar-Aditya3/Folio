package com.folio.reader.ui.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.locatorFraction
import com.folio.reader.model.locatorsMatch
import com.folio.reader.settings.normalized
import com.folio.reader.model.spotLocator
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

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
    onChapterStart: () -> Unit = {}
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

    // Panels sit in reserved space on occluding platforms; a horizontal slide
    // into that strip reads as janky, so fade there and slide elsewhere.
    val panelEnter = if (occludes) {
        androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200))
    } else {
        androidx.compose.animation.slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
    }
    val panelExit = if (occludes) {
        androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
    } else {
        androidx.compose.animation.slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
    }

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

    fun Int.argbHex(): String = "#" + toUInt().toString(16).padStart(8, '0').drop(2)
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
        isDark = appIsDark
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
    val overlayHtml = when {
        !occludes -> null
        showReaderPanel -> com.folio.reader.ui.render.OverlayUi.settings(
            fontSize = settings.fontSize,
            lineHeight = settings.lineHeight,
            margin = settings.margins.left,
            fontFamily = settings.fontFamily,
            fontOptions = quickFontNames,
            themeId = settings.themeId,
            layoutMode = settings.layoutMode.normalized.name,
            themes = com.folio.reader.settings.Theme.PICKER.map { t ->
                Triple(t.id, t.name, t.background.argbHex())
            },
            highlightColors = readerThemePreset.highlightColors.map { it.argbHex() },
            highlightIndex = settings.highlightColorIndex,
            c = overlayColors
        )
        showToc -> com.folio.reader.ui.render.OverlayUi.toc(
            chapters = chapters.map { it.title },
            current = currentChapterIndex,
            c = overlayColors
        )
        noteDraftFor != null -> {
            val hl = highlights.firstOrNull { it.id == noteDraftFor }
            com.folio.reader.ui.render.OverlayUi.noteComposer(
                highlightId = noteDraftFor ?: "",
                quote = hl?.selectedText?.take(280) ?: "",
                existing = hl?.noteId?.let { nid -> notes.firstOrNull { it.id == nid }?.content } ?: "",
                c = overlayColors
            )
        }

        showAnnotations -> com.folio.reader.ui.render.OverlayUi.annotations(
            bookmarks = bookmarks.map {
                com.folio.reader.ui.render.OverlayUi.AnnotationRow("bm", it.id, it.label?.takeIf { l -> l.isNotBlank() } ?: "Bookmark", chapterLabel(it.spineIndex, it.chapterId))
            },
            highlights = highlights.filter { !it.isDeleted }.map { h ->
                com.folio.reader.ui.render.OverlayUi.AnnotationRow(
                    kind = "hl", id = h.id,
                    title = h.selectedText.take(80).ifBlank { "(highlight)" },
                    sub = chapterLabel(h.spineIndex, h.chapterId),
                    note = h.noteId?.let { nid -> notes.firstOrNull { it.id == nid && !it.isDeleted }?.content },
                    noteId = h.noteId,
                    canNote = true
                )
            },
            // Only notes no highlight owns; linked ones render under their highlight.
            notes = notes.filter { n -> highlights.none { it.noteId == n.id } }.map {
                com.folio.reader.ui.render.OverlayUi.AnnotationRow("nt", it.id, it.content.take(80).ifBlank { "(note)" }, chapterLabel(it.spineIndex, it.chapterId))
            },
            c = overlayColors
        )
        else -> null
    }
    /**
     * Opens the spot a bookmark/highlight/note was taken at: switches chapters when
     * needed, then lands on the target. [markId] points at a painted highlight so the
     * jump is exact (older rows only stored paragraph 0 because the selection index
     * was read after the click collapsed it); otherwise the paragraph or a chapter
     * fraction is used.
     */
    fun jumpToLocation(spineIndex: Int?, chapterId: String?, locator: String?, markId: String? = null) {
        val index = chapters.indexOfFirst { spineIndex != null && it.spineIndex == spineIndex }
            .takeIf { it >= 0 }
            ?: chapters.indexOfFirst { chapterId != null && it.id == chapterId }.takeIf { it >= 0 }
            ?: return
        val para = locator?.paragraphFromLocator()
        val frac = locator?.locatorFraction()
        // Carried all the way to the page so a jump degrades mark -> paragraph ->
        // fraction, never to the top of the chapter.
        val tail = "${para ?: ""}:${frac ?: ""}"
        val target = when {
            markId != null && markId.matches(Regex("[A-Za-z0-9_-]+")) -> "h:$markId:$tail"
            para != null -> "p:$para"
            else -> null
        }
        val fraction = if (target == null) frac else null
        if (index == currentChapterIndex) {
            seekNonce++
            when {
                target != null -> seekTargetReq = target to seekNonce
                fraction != null -> seekReq = fraction to seekNonce
            }
        } else {
            pendingJump = Triple(index, target, fraction)
            onChapterChange(index)
        }
    }

    fun jumpToAnnotation(kind: String, id: String) {
        when (kind) {
            "bm" -> bookmarks.firstOrNull { it.id == id }
                ?.let { jumpToLocation(it.spineIndex, it.chapterId, it.locator) }
            "hl" -> highlights.firstOrNull { it.id == id }
                ?.let { jumpToLocation(it.spineIndex, it.chapterId, it.startLocator, markId = it.id) }
            "nt" -> notes.firstOrNull { it.id == id }
                ?.let { jumpToLocation(it.spineIndex, it.chapterId, it.locator) }
        }
        if (showAnnotations) onToggleAnnotations()
    }

    fun handleOverlayAction(a: String) {
        when {
            a == "close" -> {
                showReaderPanel = false
                noteDraftFor = null
                if (showToc) onToggleToc()
                if (showAnnotations) onToggleAnnotations()
            }

            a == "allsettings" -> {
                showReaderPanel = false
                onSettingsClick()
            }

            a.startsWith("toc:") -> {
                val i = a.substringAfter(':').toIntOrNull() ?: return
                // Stay open: the reader re-syncs the highlight in place and the
                // TOC only closes when the user taps the backdrop or X.
                onChapterChange(i)
            }

            a.startsWith("set:size:") -> onSettingsChange(
                settings.copy(fontSize = a.substringAfterLast(':').toFloatOrNull() ?: settings.fontSize)
            )

            a.startsWith("set:lh:") -> onSettingsChange(
                settings.copy(lineHeight = a.substringAfterLast(':').toFloatOrNull() ?: settings.lineHeight)
            )

            a.startsWith("set:mg:") -> a.substringAfterLast(':').toFloatOrNull()?.let { m ->
                onSettingsChange(settings.copy(margins = settings.margins.copy(left = m, right = m)))
            }

            a.startsWith("set:font:") -> {
                val name = runCatching {
                    java.net.URLDecoder.decode(a.substringAfter("set:font:"), "UTF-8")
                }.getOrNull()
                if (!name.isNullOrBlank()) onSettingsChange(settings.copy(fontFamily = name))
            }

            a.startsWith("set:theme:") -> {
                val id = a.substringAfterLast(':')
                if (com.folio.reader.settings.Theme.PRESETS.containsKey(id)) {
                    onSettingsChange(settings.copy(themeId = id, customTheme = null))
                }
            }

            a.startsWith("set:hlcolor:") -> {
                val idx = a.substringAfterLast(':').toIntOrNull() ?: return
                onSettingsChange(settings.copy(highlightColorIndex = idx))
            }

            a.startsWith("set:layout:") -> {
                val mode = a.substringAfterLast(':')
                val layout = com.folio.reader.settings.LayoutMode.entries.firstOrNull { it.name == mode }
                if (layout != null) onSettingsChange(settings.copy(layoutMode = layout))
            }

            a.startsWith("note:") -> {
                val id = a.substringAfterLast(':')
                if (highlights.any { it.id == id }) noteDraftFor = id
            }

            a.startsWith("savenote:") -> {
                val rest = a.substringAfter(':')
                val id = rest.substringBefore(':')
                val encoded = rest.substringAfter(':', "")
                val text = runCatching {
                    java.net.URLDecoder.decode(encoded, "UTF-8")
                }.getOrNull().orEmpty().trim()
                if (text.isNotBlank()) onSetHighlightNote(id, text)
                noteDraftFor = null
            }

            a.startsWith("ann:") -> {
                val kind = a.substringAfter(':').substringBefore(':')
                val id = a.substringAfterLast(':')
                jumpToAnnotation(kind, id)
            }

            a.startsWith("del:bm:") -> onRemoveBookmark(a.substringAfterLast(':'))
            a.startsWith("del:hl:") -> onRemoveHighlight(a.substringAfterLast(':'))
            a.startsWith("del:nt:") -> onRemoveNote(a.substringAfterLast(':'))
        }
    }

    // Reader chrome is app-themed: the reading theme paints the page only. The
    // inherited palette is passed through untouched so the user's chosen app theme
    // survives into the bars, panels and in-page overlays.
    FolioTheme.MaterialTheme(darkTheme = appIsDark, colors = appColors) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.folio.reader.ui.render.LocalOverlayHtml provides overlayHtml,
        com.folio.reader.ui.render.LocalOverlayAction provides { handleOverlayAction(it) }
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                com.folio.reader.ui.components.EmptyState(
                    icon = Icons.Filled.MenuBook,
                    headline = "No chapters found for this book",
                    body = "The file may be corrupt or its structure could not be parsed.",
                    action = {
                        OutlinedButton(onClick = onBackPress) {
                            Text("Back to library")
                        }
                    }
                )
            }
        }

        // Floating sync indicator pill - manages its own visibility (hides when idle)
        if (syncState != null) {
            val pillTop by animateDpAsState(
                targetValue = if (showControls) 60.dp else 0.dp,
                animationSpec = tween(220),
                label = "sync-pill-top"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = pillTop)
            ) {
                com.folio.reader.ui.components.SyncIndicator(syncState = syncState)
            }
        }

        // Top bar overlay - slides over content
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it }
        ) {
            Column(Modifier.fillMaxWidth()) {
                com.folio.reader.ui.components.FolioStatusBarBand()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    color = FolioTheme.colors.surface.copy(alpha = 0.92f),
                    shadowElevation = 4.dp
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    IconButton(onClick = onBackPress) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FolioTheme.colors.onSurface
                        )
                    }

                    // Book title (center)
                    Text(
                        text = bookTitle,
                        style = FolioTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Right-side action icons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (settings.showClock) {
                            Text(
                                text = com.folio.reader.ui.components.rememberClockTime(),
                                style = FolioTheme.typography.labelMedium,
                                color = FolioTheme.colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                        }
                        if (occludes) {
                            // The rail is not drawn on occluding platforms, so the
                            // highlight action lives here: dull until the page has a
                            // live selection.
                            val selected = pageSelection
                            IconButton(
                                enabled = selected != null,
                                onClick = {
                                    if (selected != null) {
                                        onHighlightParagraph?.invoke(selected.first, selected.second)
                                        pageSelection = null
                                        clearSelTick++
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Highlight,
                                    contentDescription = if (selected != null) "Highlight selection" else "Select text to highlight",
                                    tint = if (selected != null) FolioTheme.colors.onSurface
                                    else FolioTheme.colors.onSurface.copy(alpha = 0.32f)
                                )
                            }
                            IconButton(onClick = {
                                val opening = !showToc
                                onToggleToc()
                                if (opening) showReaderPanel = false
                            }) {
                                Icon(Icons.Filled.Toc, contentDescription = "Contents", tint = FolioTheme.colors.onSurface)
                            }
                            IconButton(onClick = {
                                val opening = !showAnnotations
                                onToggleAnnotations()
                                if (opening) showReaderPanel = false
                            }) {
                                Icon(Icons.Filled.Notes, contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
                            }
                        }
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = FolioTheme.colors.onSurface
                            )
                        }
                        IconButton(onClick = onBookmarkClick) {
                            val isBookmarked = bookmarks.any { bm ->
                                position?.let { pos -> bm.chapterId == pos.chapterId && locatorsMatch(bm.locator, pos.spotLocator()) } == true
                            }
                            Icon(
                                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = "Bookmark",
                                tint = if (isBookmarked) Color(readerThemePreset.bookmark) else FolioTheme.colors.onSurface
                            )
                        }
                        IconButton(onClick = {
                            val opening = !showReaderPanel
                            showReaderPanel = opening
                            if (opening) {
                                if (showToc) onToggleToc()
                                if (showAnnotations) onToggleAnnotations()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = FolioTheme.colors.onSurface
                            )
                        }
                    }
                }
                }
            }
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
            val isBookmarked = remember(position, bookmarks) {
                position?.let { pos ->
                    bookmarks.any { it.chapterId == pos.chapterId && locatorsMatch(it.locator, pos.spotLocator()) }
                } ?: false
            }

            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(56.dp)
                    .glassPanel(RoundedCornerShape(20.dp))
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Highlight lives here rather than floating by the selection: the
                    // OS selection toolbar covers anything drawn near the text. Dull
                    // until there is a selection, then it brightens to invite the tap.
                    val selected = pageSelection
                    IconButton(
                        enabled = selected != null,
                        onClick = {
                            if (selected != null) {
                                onHighlightParagraph?.invoke(selected.first, selected.second)
                                pageSelection = null
                                clearSelTick++
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Highlight,
                            contentDescription = if (selected != null) "Highlight selection" else "Select text to highlight",
                            tint = if (selected != null) FolioTheme.colors.onSurface
                            else FolioTheme.colors.onSurface.copy(alpha = 0.32f)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.width(32.dp), color = FolioTheme.colors.onSurface.copy(alpha = 0.2f))
                    IconButton(onClick = {
                        val opening = !showToc
                        onToggleToc()
                        if (opening) showReaderPanel = false
                    }) {
                        Icon(Icons.Filled.Toc, contentDescription = "Contents", tint = FolioTheme.colors.onSurface)
                    }
                    IconButton(onClick = {
                        val opening = !showAnnotations
                        onToggleAnnotations()
                        if (opening) showReaderPanel = false
                    }) {
                        Icon(Icons.Filled.Notes, contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
                    }
                    IconButton(onClick = onBookmarkClick) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this spot",
                            tint = if (isBookmarked) Color(readerThemePreset.bookmark) else FolioTheme.colors.onSurface
                        )
                    }
                }
            }
        }
        }

        // Global scrim for sidebar sheets: only fades in/out statically, does not slide!
        // Skipped on occluding platforms where panels sit beside the page.
        if (!occludes) {
        androidx.compose.animation.AnimatedVisibility(
            visible = showToc || showAnnotations || showReaderPanel,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            if (showToc) onToggleToc()
                            if (showAnnotations) onToggleAnnotations()
                            showReaderPanel = false
                        })
                    }
            )
        }
        }

        // Side panels: Compose overlays on Android; page-side glass overlays on
        // desktop (the heavyweight browser would cover any Compose overlay).
        if (!occludes) {
            // TOC sidebar: slide in from the end edge
            androidx.compose.animation.AnimatedVisibility(
                visible = showToc,
                modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding(),
                enter = panelEnter,
                exit = panelExit
            ) {
                TOCSidebar(
                    chapters = chapters,
                    currentIndex = currentChapterIndex,
                    onChapterClick = { index ->
                        onChapterChange(index)
                    },
                    onDismiss = onToggleToc
                )
            }

            // Annotations sidebar: slide in from the end edge
            androidx.compose.animation.AnimatedVisibility(
                visible = showAnnotations,
                modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding(),
                enter = panelEnter,
                exit = panelExit
            ) {
                AnnotationsSidebar(
                    bookmarks = bookmarks,
                    highlights = highlights,
                    notes = notes,
                    onDismiss = onToggleAnnotations,
                    onRemoveBookmark = onRemoveBookmark,
                    onRemoveHighlight = onRemoveHighlight,
                    onRemoveNote = onRemoveNote,
                    onSetHighlightNote = onSetHighlightNote,
                    chapterLabel = { spine, chapterId -> chapterLabel(spine, chapterId) },
                    onJump = { kind, id -> jumpToAnnotation(kind, id) }
                )
            }

            // Thorium-style reading settings panel: slides in from the right edge
            androidx.compose.animation.AnimatedVisibility(
                visible = showReaderPanel,
                modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding(),
                enter = panelEnter,
                exit = panelExit
            ) {
                ReaderSettingsPanel(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onDismiss = { showReaderPanel = false },
                    onOpenFullSettings = {
                        showReaderPanel = false
                        onSettingsClick()
                    }
                )
            }
        }
        }
        }
    }
}
@Composable
fun ChapterContent(
    chapter: Chapter,
    html: String,
    isLoading: Boolean,
    loadError: String? = null,
    coverPath: String? = null,
    settings: ReaderSettings,
    enabled: Boolean,
    onTap: () -> Unit,
    onScrollFraction: (Float) -> Unit,
    onLinkClick: ((String) -> Unit)? = null,
    onLongPress: ((paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onSelectionChanged: ((paragraphIndex: Int, selectedText: String?) -> Unit)? = null,
    clearSelectionRequest: Long? = null,
    onRetry: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    hasNextChapter: Boolean = false,
    hasPrevChapter: Boolean = false,
    onResolveImage: suspend (chapterHref: String, src: String) -> String? = { _, _ -> null },
    onResolveResource: suspend (chapterHref: String, src: String) -> String? = onResolveImage,
    highlights: List<Highlight> = emptyList(),
    modifier: Modifier = Modifier,
    position: ReadingPosition? = null,
    seekRequest: Pair<Float, Long>? = null,
    seekTargetRequest: Pair<String, Long>? = null,
    onPageChange: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    onChapterEnd: () -> Unit = {},
    onChapterStart: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val readerTheme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
    val bgColor = Color(readerTheme.background)
    val textColor = Color(readerTheme.primaryText)
    // Only the cover chapter scrolls via Compose; browser chapters own their
    // scroll position and progress reporting through the page-side bridge.
    val isCoverChapter = coverPath != null && chapter.spineIndex == 0
    var lastReported by remember { mutableStateOf(-1f) }
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var scrollRestored by remember { mutableStateOf(false) }
    var chapterEndReported by remember(chapter.id) { mutableStateOf(false) }
    var userCrossedEnd by remember(chapter.id) { mutableStateOf(false) }

    LaunchedEffect(chapter.id) {
        if (!isCoverChapter) return@LaunchedEffect
        // Restore scroll position from saved position if this is the correct chapter
        val shouldRestore = position != null &&
            position.chapterId == chapter.id &&
            position.scrollOffset > 0.0 &&
            !scrollRestored

        if (shouldRestore) {
            scrollRestored = true
        } else {
            scrollState.scrollTo(0)
        }
        lastReported = -1f
    }

    // Restore scroll position once content is measured and scrollable
    LaunchedEffect(scrollState.maxValue) {
        if (!isCoverChapter) return@LaunchedEffect
        if (scrollState.maxValue > 0 && position != null &&
            position.chapterId == chapter.id &&
            position.scrollOffset > 0.0 &&
            scrollRestored) {
            val targetOffset = (position.scrollOffset * scrollState.maxValue).toInt().coerceIn(0, scrollState.maxValue)
            scrollState.scrollTo(targetOffset)
            lastReported = position.scrollOffset.toFloat()
        } else if (scrollState.maxValue > 0 && lastReported < 0f) {
            // Content is now scrollable, report initial 0% position
            lastReported = 0f
            onScrollFraction(0f)
        }
    }

    // Once content is measured and scrollState.maxValue > 0, report initial position
    LaunchedEffect(scrollState.maxValue) {
        if (!isCoverChapter) return@LaunchedEffect
        if (scrollState.maxValue > 0 && lastReported < 0f) {
            // Content is now scrollable, report initial 0% position
            lastReported = 0f
            onScrollFraction(0f)
        }
    }

    // Report scroll progress (more responsive - every 0.5% change)
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (!isCoverChapter) return@LaunchedEffect
        // Wait for content to be measured before reporting progress
        if (scrollState.maxValue <= 0) {
            return@LaunchedEffect
        }

        val fraction = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
        val percentHalf = ((fraction * 100) * 2).toInt() // Track in 0.5% increments
        val lastPercentHalf = ((lastReported * 100) * 2).toInt()

        if (percentHalf != lastPercentHalf || lastReported < 0f) {
            lastReported = fraction
            onScrollFraction(fraction)
        }
    }

    // End notifications are guarded here because scroll events can repeat.
    LaunchedEffect(scrollState.value, scrollState.maxValue, hasNextChapter) {
        if (!isCoverChapter) return@LaunchedEffect
        if (userCrossedEnd && !chapterEndReported && scrollState.maxValue > 0 &&
            scrollState.value >= scrollState.maxValue - 8) {
            chapterEndReported = true
            onChapterEnd()
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().background(bgColor).pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.position != it.previousPosition }) userCrossedEnd = true
                }
            }
        }
    ) {
        when {
            isLoading -> com.folio.reader.ui.components.LoadingPlaceholder(modifier = Modifier.fillMaxSize())
            // Real error from the loader (missing file, parse failure) — not a
            // transient blank, so opening a book no longer flashes this state.
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Warning,
                        contentDescription = "Error",
                        tint = FolioTheme.colors.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "Failed to load chapter",
                        style = FolioTheme.typography.titleMedium,
                        color = FolioTheme.colors.onSurface
                    )
                    Text(
                        loadError,
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    onRetry?.let {
                        androidx.compose.material3.Button(
                            onClick = it,
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
            // Spine 0 is the cover page in most EPUBs — render the real cover
            // image instead of its (usually image-only, text-stripped) HTML.
            coverPath != null && chapter.spineIndex == 0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState, enabled = enabled)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(onTap = { onTap() })
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                com.folio.reader.ui.components.BookCover(
                    coverPath = coverPath,
                    title = chapter.title,
                    author = "",
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .height(600.dp)
                )
                if (html.isNotBlank() && html.length > 40) {
                    // Cover chapter also carries real text — parse and render it properly.
                    // Rendering is heavy; keep it off the composition/main thread.
                    var coverBlocks by remember(html, settings) {
                        mutableStateOf<List<com.folio.reader.ui.render.HtmlBlock>>(emptyList())
                    }
                    LaunchedEffect(html, settings) {
                        coverBlocks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching {
                                val renderer = com.folio.reader.ui.render.HtmlRenderer(
                                    settings = settings,
                                    linkColor = Color(0xFF1A73E8)
                                )
                                renderer.renderToBlocks(html, TextStyle(fontSize = settings.fontSize.sp))
                            }.getOrElse {
                                emptyList()
                            }
                        }
                    }

                    if (coverBlocks.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .padding(top = 24.dp, start = 32.dp, end = 32.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            coverBlocks.forEach { block ->
                                when (block) {
                                    is com.folio.reader.ui.render.HtmlBlock.Text -> {
                                        val fontFamily = com.folio.reader.ui.components.systemFontFamily(settings.fontFamily)
                                        Text(
                                            // Keep paragraph styles: EPUB title pages commonly use
                                            // explicit centered headings alongside non-centered text.
                                            text = block.annotated,
                                            style = TextStyle(
                                                fontFamily = fontFamily,
                                                fontSize = settings.fontSize.sp,
                                                lineHeight = (settings.fontSize * settings.lineHeight).sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight(settings.fontWeight),
                                                color = textColor
                                            ),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)
                                        )
                                    }
                                    is com.folio.reader.ui.render.HtmlBlock.Image -> {
                                        // Skip images on cover page as the main cover is already shown
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                if (hasNextChapter && onNextChapter != null) {
                    androidx.compose.material3.Button(onClick = onNextChapter) { Text("Start reading →") }
                }
                Spacer(Modifier.height(24.dp))
            }
            html.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "This page is empty.",
                        style = FolioTheme.typography.bodyMedium,
                        color = textColor
                    )
                    if (hasNextChapter && onNextChapter != null) {
                        androidx.compose.material3.Button(onClick = onNextChapter) { Text("Next page →") }
                    }
                }
            }
            else -> {
                // Browser owns all rendering — no Compose fallback (it broke scroll/pagination flow).
                val chapterHighlights = remember(highlights, chapter.id) {
                    highlights.filter { it.chapterId == chapter.id && !it.isDeleted }
                }
                com.folio.reader.ui.render.HtmlContentSurface(
                    html = html,
                    chapterHref = chapter.href,
                    settings = settings,
                    position = position,
                    highlights = chapterHighlights,
                    enabled = enabled,
                    modifier = Modifier.fillMaxSize(),
                    onProgress = onScrollFraction,
                    onPageChange = onPageChange,
                    onChapterEnd = onChapterEnd,
                    onChapterStart = onChapterStart,
                    onTap = onTap,
                    onLinkClick = onLinkClick,
                    onResolveResource = onResolveResource,
                    onHighlightParagraph = onLongPress?.let { cb -> { idx, text -> cb(idx, text) } },
                    onSelectionChanged = onSelectionChanged,
                    clearSelectionRequest = clearSelectionRequest,
                    seekRequest = seekRequest,
                    seekTargetRequest = seekTargetRequest
                )
            }
        }
    }
}


/**
 * Folio writes content locators as "/{spineIndex}/{paragraphIndex}:{offset}"
 * (see addHighlight / addBookmark), so the paragraph step is the last segment.
 */
private fun String.paragraphFromLocator(): Int? =
    substringAfterLast('/').substringBefore(':').trimEnd(')').toIntOrNull()

/** Smallest reader text size in sp — unchanged; users liked the low end. */
private const val MIN_FONT_SIZE_SP = 12f

/**
 * Largest reader text size in sp. Lowered from 36sp: even mid-range sizes looked
 * oversized on phones, so the top of the slider was brought down to 24sp.
 */
private const val MAX_FONT_SIZE_SP = 24f

/**
 * Thorium-style quick reading settings: font size stepper, typeface dropdown,
 * a scrollable theme slider with live mini previews, line spacing and margins
 * — applied live via [onSettingsChange].
 */
@Composable
fun ReaderSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    onOpenFullSettings: () -> Unit
) {
    var fontMenuOpen by remember { mutableStateOf(false) }
    // Calluna and Comfortaa ship with the app (see BundledFonts) and appear both
    // here and via the customFonts entries; the rest resolve to system fonts.
    val fonts = listOf(
        "Calluna", "Comfortaa", "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
        "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
    )
    val availableFonts = (fonts + settings.customFonts.map { it.name }).distinct()
    fun actualFontName(font: String): String =
        settings.customFonts.firstOrNull { it.name == font }?.familyName ?: font
    val quickThemes = com.folio.reader.settings.Theme.PICKER.map { it.id }
    val panelShape = RoundedCornerShape(topStart = 20.dp)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .glassPanel(panelShape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Reading settings", style = FolioTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        // Font size: A− / slider / A+
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Text size", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(
                    onClick = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                    modifier = Modifier.background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(8.dp))
                ) { Text("A−", style = FolioTheme.typography.titleSmall) }
                androidx.compose.material3.Slider(
                    value = settings.fontSize,
                    onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
                    valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                    modifier = Modifier.background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(8.dp))
                ) { Text("A+", style = FolioTheme.typography.titleMedium) }
            }
        }

        QuickChoiceRow(
            label = "Layout",
            options = listOf(
                "CONTINUOUS" to "Scroll",
                "PAGINATED" to "Page"
            ),
            selected = settings.layoutMode.normalized.name,
            onSelect = { name ->
                val mode = com.folio.reader.settings.LayoutMode.entries.firstOrNull { it.name == name }
                if (mode != null) onSettingsChange(settings.copy(layoutMode = mode))
            }
        )

        // Font family dropdown
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Typeface", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fontMenuOpen = true }
                        .background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        actualFontName(settings.fontFamily),
                        style = FolioTheme.typography.bodyMedium,
                        fontFamily = com.folio.reader.ui.components.systemFontFamily(
                            actualFontName(settings.fontFamily)
                        )
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = fontMenuOpen,
                    onDismissRequest = { fontMenuOpen = false },
                    modifier = Modifier
                        .width(248.dp)
                        .background(Color.Transparent)
                        .glassPanel(RoundedCornerShape(8.dp))
                ) {
                    availableFonts.forEach { font ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    actualFontName(font),
                                    fontFamily = com.folio.reader.ui.components.systemFontFamily(actualFontName(font)),
                                    fontWeight = if (font == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (font == settings.fontFamily) FolioTheme.colors.primary
                                    else FolioTheme.colors.onSurface
                                )
                            },
                            onClick = {
                                onSettingsChange(settings.copy(fontFamily = font))
                                fontMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

            // Theme: scrollable/draggable vertical slider of live mini previews.
            // Opens scrolled to the active theme; tap a preview to apply it.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val selectedThemeId = settings.customTheme?.id ?: settings.themeId
                Text("Theme", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                val listState = rememberLazyListState()
                val selectedIndex = quickThemes.indexOf(selectedThemeId).coerceAtLeast(0)
                LaunchedEffect(selectedThemeId) {
                    listState.scrollToItem(index = selectedIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 236.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp)
                ) {
                    items(
                        count = quickThemes.size,
                        key = { quickThemes[it] }
                    ) { index ->
                        val id = quickThemes[index]
                        val theme = com.folio.reader.settings.Theme.getPreset(id)
                        val selected = id == selectedThemeId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemePreviewCard(
                                theme = theme,
                                selected = selected,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSettingsChange(settings.copy(themeId = id, customTheme = null)) }
                                    )
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = FolioTheme.colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Highlight colour: picked from the active theme's own palette, so the
            // wash always stays inside the theme's contrast budget.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Highlight", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId))
                        .highlightColors.forEachIndexed { index, argb ->
                            val selected = index == settings.highlightColorIndex
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(argb))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) FolioTheme.colors.primary else FolioTheme.colors.outline,
                                        shape = RoundedCornerShape(7.dp)
                                    )
                                    .clickable {
                                        onSettingsChange(settings.copy(highlightColorIndex = index))
                                    }
                            )
                        }
                }
            }

            // Light — the device's own screen brightness. Dimming the page instead
            // washed the ink, and brightness is a property of the hardware, not a
            // reading preference worth storing or syncing.
            val brightness = com.folio.reader.ui.components.rememberScreenBrightness()
            if (brightness.supported) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Light", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                        Text("${(brightness.value * 100).toInt()}%", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.primary)
                    }
                    androidx.compose.material3.Slider(
                        value = brightness.value,
                        onValueChange = { brightness.set(it) },
                        valueRange = 0.05f..1f
                    )
                }
            }

            // Line spacing
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Line spacing", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    Text("%.1f".format(settings.lineHeight), style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.primary)
                }
                androidx.compose.material3.Slider(
                    value = settings.lineHeight,
                    onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
                    valueRange = 1f..3f
                )
            }

            // Margins
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Margins", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    Text("${settings.margins.left.toInt()} dp", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.primary)
                }
                androidx.compose.material3.Slider(
                    value = settings.margins.left,
                    onValueChange = {
                        onSettingsChange(settings.copy(margins = settings.margins.copy(left = it, right = it)))
                    },
                    valueRange = 0f..64f
                )
            }

            // All settings — frosted glass pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(RoundedCornerShape(12.dp))
                    .clickable { onOpenFullSettings() }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "All settings",
                    style = FolioTheme.typography.labelLarge,
                    color = FolioTheme.colors.primary
                )
            }
        }
    }

/** A label with equal-width tappable options; the current one is accent-filled. */
@Composable
private fun QuickChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, title) ->
                val isSel = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSel) FolioTheme.colors.primary else FolioTheme.colors.surfaceVariant
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = FolioTheme.typography.labelMedium,
                        color = if (isSel) FolioTheme.colors.onPrimary else FolioTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * A miniature "page" rendered in the theme's own colors — heading, body lines
 * and an accent bar — so each entry in the theme slider shows how reading will
 * actually look before it is applied.
 */
@Composable
private fun ThemePreviewCard(theme: com.folio.reader.settings.Theme, selected: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(theme.background))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FolioTheme.colors.primary else FolioTheme.colors.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(7.dp)
                    .background(Color(theme.headingText), RoundedCornerShape(4.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(theme.secondaryText).copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth(0.88f)
                    .height(4.dp)
                    .background(Color(theme.secondaryText).copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth(0.66f)
                    .height(4.dp)
                    .background(Color(theme.secondaryText).copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            )
        }
        // Accent chip showing the theme's progress color
        Box(
            Modifier
                .size(width = 6.dp, height = 34.dp)
                .background(Color(theme.progress), RoundedCornerShape(3.dp))
        )
    }
}

@Composable
fun BottomProgressBar(
    chapterTitle: String,
    currentPage: Int = 1,
    totalPages: Int = 1,
    onSeek: ((Float) -> Unit)? = null
) {
    val fraction = if (totalPages > 0) currentPage.toFloat() / totalPages else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FolioTheme.colors.surface.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .then(
                    if (onSeek != null) Modifier.pointerInput(fraction) {
                        detectTapGestures(
                            onPress = { off ->
                                onSeek((off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                            }
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            com.folio.reader.ui.components.FolioProgressBar(
                progress = fraction,
                color = FolioTheme.colors.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (chapterTitle.isNotBlank()) {
                Text(
                    text = chapterTitle,
                    style = FolioTheme.typography.labelMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Text(
                text = "$currentPage / $totalPages",
                style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.primary
            )
        }
    }
}

@Composable
fun TOCSidebar(
    chapters: List<Chapter>,
    currentIndex: Int,
    onChapterClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val tocShape = RoundedCornerShape(topStart = 20.dp)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .glassPanel(tocShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
                Text(
                    "Contents",
                    style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.onSurface
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(color = FolioTheme.colors.outline.copy(alpha = 0.5f))

            val initialScrollIndex = (currentIndex - 1).coerceAtLeast(0)
            // Positioned on open only. While the panel stays open the list belongs
            // to the user — re-scrolling on chapter changes is what made it jump.
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(chapters, key = { _, chapter -> "${chapter.bookId}:${chapter.id}" }) { index, chapter ->
                    val isCurrent = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(index) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current-chapter accent bar
                        Box(
                            Modifier
                                .size(width = 3.dp, height = 18.dp)
                                .background(
                                    if (isCurrent) FolioTheme.colors.primary else Color.Transparent,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = chapter.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = FolioTheme.typography.bodySmall.copy(
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isCurrent) FolioTheme.colors.primary else FolioTheme.colors.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
}

@Composable
fun AnnotationsSidebar(
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    notes: List<Note>,
    onDismiss: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSetHighlightNote: (highlightId: String, content: String) -> Unit = { _, _ -> },
    chapterLabel: (spineIndex: Int?, chapterId: String?) -> String = { spine, _ -> "Spine ${(spine ?: 0) + 1}" },
    onJump: (kind: String, id: String) -> Unit = { _, _ -> }
) {
    var noteDraftFor by remember { mutableStateOf<String?>(null) }
    var noteContent by remember { mutableStateOf("") }
    // A dismissed (not cancelled) dialog keeps its unsaved draft for this highlight.
    var draftKeptFor by remember { mutableStateOf<String?>(null) }
    val noteById = notes.filter { !it.isDeleted }.associateBy { it.id }
    // Notes no highlight owns — everything made before notes lived on highlights.
    val orphanNotes = notes.filter { n -> !n.isDeleted && highlights.none { it.noteId == n.id } }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .glassPanel(RoundedCornerShape(topStart = 20.dp))
            .padding(vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Annotations", style = FolioTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close annotations")
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (bookmarks.isNotEmpty()) {
                item { SectionHeader("Bookmarks (${bookmarks.size})", Icons.Filled.Bookmark) }
                items(bookmarks, key = { "bm:${it.id}" }) { bookmark ->
                    AnnotationRow(
                        title = bookmark.label ?: "Page ${bookmark.spineIndex + 1}",
                        subtitle = chapterLabel(bookmark.spineIndex, bookmark.chapterId),
                        leadingIcon = Icons.Filled.Bookmark,
                        onClick = { onJump("bm", bookmark.id) },
                        onDelete = { onRemoveBookmark(bookmark.id) }
                    )
                }
            }
            if (highlights.isNotEmpty()) {
                item { SectionHeader("Highlights (${highlights.size})", Icons.Filled.Highlight) }
                items(highlights.filter { !it.isDeleted }, key = { "hl:${it.id}" }) { highlight ->
                    val linked = highlight.noteId?.let { noteById[it] }
                    AnnotationRow(
                        title = highlight.selectedText.take(80).ifBlank { "(empty)" },
                        subtitle = chapterLabel(highlight.spineIndex, highlight.chapterId),
                        accentColor = Color(highlight.effectiveColor),
                        note = linked?.content,
                        onNote = {
                            if (draftKeptFor != highlight.id) noteContent = linked?.content ?: ""
                            draftKeptFor = null
                            noteDraftFor = highlight.id
                        },
                        onClick = { onJump("hl", highlight.id) },
                        onDelete = { onRemoveHighlight(highlight.id) }
                    )
                }
            }
            if (orphanNotes.isNotEmpty()) {
                item { SectionHeader("Notes (${orphanNotes.size})", Icons.Filled.Notes) }
                items(orphanNotes, key = { "nt:${it.id}" }) { note ->
                    AnnotationRow(
                        title = note.content.take(80).ifBlank { "(empty)" },
                        subtitle = chapterLabel(note.spineIndex, note.chapterId),
                        leadingIcon = Icons.Filled.Notes,
                        onClick = { onJump("nt", note.id) },
                        onDelete = { onRemoveNote(note.id) }
                    )
                }
            }
            if (bookmarks.isEmpty() && highlights.none { !it.isDeleted } && orphanNotes.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Bookmark spots, then select text to highlight and write a note on it.",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    noteDraftFor?.let { highlightId ->
        val passage = highlights.firstOrNull { it.id == highlightId }?.selectedText.orEmpty()
        AlertDialog(
            onDismissRequest = { draftKeptFor = noteDraftFor; noteDraftFor = null },
            title = { Text("Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (passage.isNotBlank()) {
                        Text(
                            text = "“${passage.take(200)}”",
                            style = FolioTheme.typography.quote.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedTextField(
                        value = noteContent,
                        onValueChange = { noteContent = it },
                        label = { Text("Your note") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetHighlightNote(highlightId, noteContent.trim())
                        noteContent = ""
                        draftKeptFor = null
                        noteDraftFor = null
                    },
                    enabled = noteContent.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteContent = ""; draftKeptFor = null; noteDraftFor = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = FolioTheme.colors.secondary
            )
        }
        Text(
            text = text,
            style = FolioTheme.typography.titleSmall,
            color = FolioTheme.colors.secondary
        )
    }
}

@Composable
private fun AnnotationRow(
    title: String,
    subtitle: String,
    accentColor: Color? = null,
    leadingIcon: ImageVector? = null,
    note: String? = null,
    onNote: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FolioTokens.radiusControl))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (accentColor != null) {
            Box(Modifier.width(3.dp).height(32.dp).background(accentColor, RoundedCornerShape(2.dp)))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = FolioTheme.colors.primary, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = FolioTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        onNote?.let { action ->
            TextButton(
                onClick = action,
                modifier = Modifier.size(width = 56.dp, height = 32.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = if (note.isNullOrBlank()) "Note" else "Edit",
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.primary
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
        if (!note.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(start = 13.dp, end = 12.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(IntrinsicSize.Min)
                        .background(FolioTheme.colors.primary, RoundedCornerShape(1.dp))
                )
                Text(
                    text = note,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

