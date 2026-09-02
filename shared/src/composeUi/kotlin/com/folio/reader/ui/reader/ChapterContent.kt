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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.theme.FolioTheme

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
                        imageVector = Icons.Filled.Warning,
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

/** Shown when a book's file parsed but exposed no readable chapters. */
@Composable
internal fun ReaderNoChapters(onBackPress: () -> Unit) {
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
