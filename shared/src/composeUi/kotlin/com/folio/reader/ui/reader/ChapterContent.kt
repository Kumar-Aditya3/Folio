package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.settings.ReaderSettings

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
    onLongPress: ((chapterId: String, paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onSelectionChanged: ((chapterId: String, paragraphIndex: Int, selectedText: String?) -> Unit)? = null,
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
    /** Fired on the browser surface's first real paint; drives the morph handoff. */
    onContentReady: () -> Unit = {},
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

    /**
     * Whether the loader has reported for this chapter yet.
     *
     * The empty state must be reachable only when a load has genuinely finished
     * and produced nothing. Getting there took three attempts, and the two wrong
     * ones are worth keeping on record:
     *
     * 1st (wrong): infer it from `isLoading` alone. `_isLoadingContent` started
     * `false` in the view model, so the screen consumed a real `isLoading = false`
     * with `html` still empty and `loadError` still null — indistinguishable from a
     * genuinely empty chapter, and every EPUB open flashed "This page is empty.".
     *
     * 2nd (wrong): latch `sawLoading` in a `LaunchedEffect(chapter.id, isLoading)`.
     * That looked like it worked, and an instrumented probe agreed — but the probe
     * logged from a later pass than the one that painted. On the *first*
     * composition `isLoading` was already `false` (the view model's `false`
     * default), so the effect body never ran, the latch stayed false, and the
     * first painted frame still took the empty branch. The probe re-ran after the
     * latch had latched and reported the state the code was *about* to reach.
     *
     * 3rd (this): make `_isLoadingContent` honest — it starts **true** and every
     * loader path clears it — so the first composition sees `isLoading = true` and
     * the latch below is actually reachable. The latch then means precisely "this
     * chapter's load has reported", which is the question the empty branch asks.
     * Keyed on [chapter.id] so moving to the next chapter re-arms it rather than
     * inheriting the previous chapter's arrival.
     */
    var sawLoading by remember(chapter.id) { mutableStateOf(false) }
    LaunchedEffect(chapter.id, isLoading) {
        if (isLoading) sawLoading = true
    }
    val contentArrived = html.isNotBlank() || sawLoading || isCoverChapter

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
                val retry = onRetry
                com.folio.reader.ui.components.EmptyState(
                    icon = Icons.Filled.Warning,
                    headline = "Failed to load chapter",
                    body = loadError,
                    action = if (retry != null) {
                        { androidx.compose.material3.Button(onClick = retry) { Text("Retry") } }
                    } else {
                        null
                    }
                )
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
                // This branch is Compose, not the browser surface, so no page-load
                // callback arrives — signal readiness once it composes so opening
                // straight onto the cover chapter still dissolves the morph plate.
                LaunchedEffect(chapter.id) { onContentReady() }
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
                                    linkColor = Color(readerTheme.link)
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
                            // Resolved once for the cover, not per block (font lookup is cached but
                            // this was still a map hit + call per text block per recomposition).
                            val coverFontFamily = com.folio.reader.ui.components.systemFontFamily(settings.fontFamily)
                            coverBlocks.forEach { block ->
                                when (block) {
                                    is com.folio.reader.ui.render.HtmlBlock.Text -> {
                                        val fontFamily = coverFontFamily
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
            // A blank chapter inside a window flows past as an empty section;
            // only a blank single-chapter load is a dead end with a button.
            //
            // Blank *before* the content has arrived is not an empty chapter, it is
            // a chapter still on its way — see [contentArrived]. Rendering the dead
            // end in that window is what flashed "This page is empty." on every
            // EPUB open; the spinner is the honest state until the loader reports.
            html.isBlank() && !windowed && !contentArrived ->
                com.folio.reader.ui.components.LoadingPlaceholder(modifier = Modifier.fillMaxSize())
            html.isBlank() && !windowed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val next = onNextChapter
                com.folio.reader.ui.components.EmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    headline = "This page is empty.",
                    action = if (hasNextChapter && next != null) {
                        { androidx.compose.material3.Button(onClick = next) { Text("Next page →") } }
                    } else {
                        null
                    }
                )
            }
            else -> {
                // Browser owns all rendering — no Compose fallback (it broke scroll/pagination flow).
                val sectionIds = remember(sections) { sections.map { it.chapterId }.toSet() }
                val visibleHighlights = remember(highlights, chapter.id, sections) {
                    if (windowed) highlights.filter { it.chapterId in sectionIds && !it.isDeleted }
                    else highlights.filter { it.chapterId == chapter.id && !it.isDeleted }
                }
                com.folio.reader.ui.render.HtmlContentSurface(
                    sections = if (windowed) documentSections else listOf(
                        com.folio.reader.ui.render.ReaderSection(
                            spineIndex = -1,
                            chapterId = chapter.id,
                            href = chapter.href,
                            html = html
                        )
                    ),
                    windowed = windowed,
                    anchorChapterId = chapter.id,
                    settings = settings,
                    position = position,
                    highlights = visibleHighlights,
                    enabled = enabled,
                    modifier = Modifier.fillMaxSize(),
                    onProgress = onScrollFraction,
                    onPageChange = onPageChange,
                    onVisibleSection = onVisibleSection,
                    onExtendForward = onExtendForward,
                    onExtendBackward = onExtendBackward,
                    windowOp = windowOp,
                    onWindowOpApplied = onWindowOpApplied,
                    onChapterEnd = onChapterEnd,
                    onChapterStart = onChapterStart,
                    onTap = onTap,
                    onLinkClick = onLinkClick,
                    onResolveResource = onResolveResource,
                    onHighlightParagraph = onLongPress?.let { cb -> { chapterId, idx, text -> cb(chapterId, idx, text) } },
                    onSelectionChanged = onSelectionChanged,
                    clearSelectionRequest = clearSelectionRequest,
                    seekRequest = seekRequest,
                    seekTargetRequest = seekTargetRequest,
                    onContentReady = onContentReady
                )
            }
        }
    }
}

/** Shown when a book's file parsed but exposed no readable chapters. */
@Composable
internal fun ReaderNoChapters(onBackPress: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        com.folio.reader.ui.components.EmptyState(
            icon = Icons.AutoMirrored.Filled.MenuBook,
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
