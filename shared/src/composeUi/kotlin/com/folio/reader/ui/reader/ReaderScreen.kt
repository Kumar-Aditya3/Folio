package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.render.rememberHtmlRenderer
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme

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
    onToggleToc: () -> Unit,
    onToggleAnnotations: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onScrollProgress: (Float) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit = {},
    onHighlightParagraph: ((paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onRetryChapter: (() -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    onResolveImage: suspend (chapterHref: String, src: String) -> String? = { _, _ -> null },
    onResolveResource: suspend (chapterHref: String, src: String) -> String? = onResolveImage,
    syncState: com.folio.reader.sync.SyncState? = null,
    onPageChange: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    onChapterEnd: () -> Unit = {}
) {
    val currentChapter = chapters.getOrNull(currentChapterIndex)
    var currentPage by remember(currentChapterIndex) { mutableStateOf(1) }
    var totalPages by remember(currentChapterIndex) { mutableStateOf(1) }
    var currentTime by remember { mutableStateOf("") }

    // Desktop's embedded browser is a heavyweight native window that paints over
    // Compose overlays, so the chrome reserves its own space there instead of
    // floating above the page (Android keeps the overlay layout).
    val occludes = com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()

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

    LaunchedEffect(Unit) {
        while (true) {
            val time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
            currentTime = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
            kotlinx.coroutines.delay(30_000)
        }
    }

    var showReaderPanel by remember { mutableStateOf(false) }
    // Bottom progress bar taps request a seek to a chapter fraction.
    var seekReq by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    var seekNonce by remember { mutableStateOf(0L) }

    // Chrome follows the reading theme (incl. per-book overrides) so bars and
    // panels never clash with the page on either platform.
    val readerThemePreset = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)

    fun Int.argbHex(): String = "#" + toUInt().toString(16).padStart(8, '0').drop(2)
    val overlayColors = com.folio.reader.ui.render.OverlayColors(
        bg = readerThemePreset.background.argbHex(),
        fg = readerThemePreset.primaryText.argbHex(),
        accent = readerThemePreset.progress.argbHex(),
        surface = readerThemePreset.surface.argbHex(),
        isDark = readerThemePreset.isDark
    )
    val quickFontNames = remember(settings.customFonts) {
        (listOf(
            "Calluna", "Comfortaa", "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
            "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
        ) + settings.customFonts.map { it.name }).distinct()
    }
    val overlayHtml = when {
        !occludes -> null
        showReaderPanel -> com.folio.reader.ui.render.OverlayUi.settings(
            fontSize = settings.fontSize,
            lineHeight = settings.lineHeight,
            margin = settings.margins.left,
            fontFamily = settings.fontFamily,
            fontOptions = quickFontNames,
            themeId = settings.themeId,
            themes = listOf("paper", "white", "sepia", "gray", "dark", "oled_black").map { id ->
                val t = com.folio.reader.settings.Theme.getPreset(id)
                Triple(id, t.name, t.background.argbHex())
            },
            c = overlayColors
        )
        showToc -> com.folio.reader.ui.render.OverlayUi.toc(
            chapters = chapters.map { it.title },
            current = currentChapterIndex,
            c = overlayColors
        )
        showAnnotations -> com.folio.reader.ui.render.OverlayUi.annotations(
            bookmarks = bookmarks.map {
                com.folio.reader.ui.render.OverlayUi.AnnotationRow("bm", it.id, it.label ?: "Spine ${it.spineIndex + 1}", "Bookmark")
            },
            highlights = highlights.filter { !it.isDeleted }.map {
                com.folio.reader.ui.render.OverlayUi.AnnotationRow("hl", it.id, it.selectedText.take(80).ifBlank { "(highlight)" }, "Highlight")
            },
            notes = notes.map {
                com.folio.reader.ui.render.OverlayUi.AnnotationRow("nt", it.id, it.content.take(80).ifBlank { "(note)" }, "Note")
            },
            c = overlayColors
        )
        else -> null
    }
    fun handleOverlayAction(a: String) {
        when {
            a == "close" -> {
                showReaderPanel = false
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

            a.startsWith("del:bm:") -> onRemoveBookmark(a.substringAfterLast(':'))
            a.startsWith("del:hl:") -> onRemoveHighlight(a.substringAfterLast(':'))
            a.startsWith("del:nt:") -> onRemoveNote(a.substringAfterLast(':'))
        }
    }

    FolioTheme.MaterialTheme(
        darkTheme = readerThemePreset.isDark,
        colors = FolioTheme.fromReaderTheme(
            readerThemePreset.background, readerThemePreset.surface, readerThemePreset.primaryText,
            readerThemePreset.secondaryText, readerThemePreset.link, readerThemePreset.progress,
            readerThemePreset.divider, readerThemePreset.isDark
        )
    ) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.folio.reader.ui.render.LocalOverlayHtml provides overlayHtml,
        com.folio.reader.ui.render.LocalOverlayAction provides { handleOverlayAction(it) }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
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
                modifier = Modifier.fillMaxSize().padding(contentInsets)
                    .then(if (occludes) Modifier else Modifier.statusBarsPadding()),
                position = position,
                onPageChange = { page, total ->
                    currentPage = page
                    totalPages = total
                    onPageChange(page, total)
                },
                onChapterEnd = onChapterEnd
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No chapters found for this book.",
                    style = FolioTheme.typography.bodyLarge,
                    color = FolioTheme.colors.onSurfaceVariant
                )
            }
        }

        // Floating sync indicator pill - manages its own visibility (hides when idle)
        if (syncState != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (showControls) 60.dp else 0.dp)
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
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
                color = FolioTheme.colors.surface.copy(alpha = 0.95f),
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
                            imageVector = Icons.Filled.ArrowBack,
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
                        if (occludes) {
                            IconButton(onClick = onToggleToc) {
                                Icon(Icons.Filled.Menu, contentDescription = "TOC", tint = FolioTheme.colors.onSurface)
                            }
                            IconButton(onClick = onToggleAnnotations) {
                                Icon(Icons.Filled.Create, contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
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
                                position?.let { pos -> bm.chapterId == pos.chapterId && bm.locator == pos.contentLocator } == true
                            }
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Bookmark",
                                tint = if (isBookmarked) FolioTheme.colors.primary else FolioTheme.colors.onSurface
                            )
                        }
                        IconButton(onClick = { showReaderPanel = !showReaderPanel }) {
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

        // Bottom progress bar overlay - slides over content
        androidx.compose.animation.AnimatedVisibility(
            visible = settings.showProgress && showControls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it }
        ) {
            BottomProgressBar(
                chapterTitle = currentChapter?.title ?: "",
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
                    bookmarks.any { it.chapterId == pos.chapterId && it.locator == pos.contentLocator }
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
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = FolioTheme.colors.onSurface)
                    }
                    HorizontalDivider(modifier = Modifier.width(32.dp), color = FolioTheme.colors.onSurface.copy(alpha = 0.2f))
                    IconButton(onClick = onToggleToc) {
                        Icon(Icons.Filled.Menu, contentDescription = "TOC", tint = FolioTheme.colors.onSurface)
                    }
                    IconButton(onClick = onToggleAnnotations) {
                        Icon(Icons.Filled.Create, contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
                    }
                    IconButton(onClick = onBookmarkClick) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this spot",
                            tint = if (isBookmarked) Color(0xFFFBC02D) else FolioTheme.colors.onSurface
                        )
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = FolioTheme.colors.onSurface)
                    }
                    IconButton(onClick = { showReaderPanel = !showReaderPanel }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reading settings", tint = FolioTheme.colors.onSurface)
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
                modifier = Modifier.align(Alignment.CenterEnd),
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
                modifier = Modifier.align(Alignment.CenterEnd),
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
                    onAddNote = onAddNote
                )
            }

            // Thorium-style reading settings panel: slides in from the right edge
            androidx.compose.animation.AnimatedVisibility(
                visible = showReaderPanel,
                modifier = Modifier.align(Alignment.CenterEnd),
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
private fun RenderHtmlBlocks(
    blocks: List<com.folio.reader.ui.render.HtmlBlock>,
    settings: com.folio.reader.settings.ReaderSettings,
    textColor: androidx.compose.ui.graphics.Color,
    chapterHref: String,
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveImage: suspend (chapterHref: String, src: String) -> String?,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit,
    highlights: List<Highlight> = emptyList(),
    modifier: Modifier = Modifier
) {
    val selColors = androidx.compose.foundation.text.selection.TextSelectionColors(
        handleColor = FolioTheme.colors.primary,
        backgroundColor = FolioTheme.colors.primary.copy(alpha = 0.25f)
    )
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides selColors
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                blocks.forEach { block ->
                    when (block) {
                        is com.folio.reader.ui.render.HtmlBlock.Text -> {
                            val fontFamily = com.folio.reader.ui.components.systemFontFamily(settings.fontFamily)
                            // Use Text (not ClickableText) to preserve paragraph styles from AnnotatedString
                            // ClickableText is deprecated and doesn't properly handle paragraph styles
                            val annotatedText = block.annotated.withHighlightBackgrounds(highlights)
                            var layoutResult by remember(annotatedText) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                            androidx.compose.material3.Text(
                                text = annotatedText,
                                style = TextStyle(
                                    fontFamily = fontFamily,
                                    fontSize = settings.fontSize.sp,
                                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight(settings.fontWeight),
                                    color = textColor
                                ),
                                onTextLayout = {
                                    layoutResult = it
                                    onTextLayout(it)
                                },
                                modifier = Modifier
                                    .readerWidth(settings.textWidth)
                                    .fillMaxWidth()
                                    .padding(vertical = (settings.fontSize * 0.35f * (settings.paragraphSpacing - 1f).coerceAtLeast(0f)).dp)
                                    .pointerInput(annotatedText) {
                                        detectTapGestures { pos ->
                                            layoutResult?.let { layout ->
                                                val offset = layout.getOffsetForPosition(pos)
                                                annotatedText.getStringAnnotations(tag = "url", start = offset, end = offset)
                                                    .firstOrNull()?.let { ann -> onLinkClick?.invoke(ann.item) } ?: onTap()
                                            } ?: onTap()
                                        }
                                    }
                            )
                        }
                        is com.folio.reader.ui.render.HtmlBlock.Image -> com.folio.reader.ui.components.EpubImage(
                            src = block.src,
                            resolve = { src -> onResolveImage(chapterHref, src) },
                            modifier = Modifier
                                .readerWidth(settings.textWidth)
                                .fillMaxWidth()
                                .padding(vertical = (settings.fontSize * 0.35f * (settings.paragraphSpacing - 1f).coerceAtLeast(0f)).dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaginatedChapterView(
    blocks: List<com.folio.reader.ui.render.HtmlBlock>,
    chapter: Chapter,
    settings: com.folio.reader.settings.ReaderSettings,
    textColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onTap: () -> Unit,
    onScrollFraction: (Float) -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveImage: suspend (chapterHref: String, src: String) -> String?,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit,
    highlights: List<Highlight>,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    onPageChange: (currentPage: Int, totalPages: Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(
            horizontal = settings.margins.left.dp,
            vertical = settings.margins.top.dp
        )
    ) {
        // Reflow from the available page dimensions and typography, not a fixed
        // character/block count. Pages are clipped columns, never scrolling documents.
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val pageChunks = remember(blocks, maxWidth, maxHeight, settings.fontSize, settings.lineHeight, settings.fontFamily) {
            val pageHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val pages = mutableListOf<List<com.folio.reader.ui.render.HtmlBlock>>()
            var current = mutableListOf<com.folio.reader.ui.render.HtmlBlock>()
            var usedHeight = 0
            for (block in blocks) {
                val blockHeight = when (block) {
                    is com.folio.reader.ui.render.HtmlBlock.Text -> textMeasurer.measure(
                        text = block.annotated,
                        style = TextStyle(fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineHeight).sp),
                        constraints = Constraints(maxWidth = width)
                    ).size.height.coerceAtLeast(1)
                    is com.folio.reader.ui.render.HtmlBlock.Image -> (pageHeight / 3).coerceAtLeast(with(density) { settings.fontSize.sp.roundToPx() } * 3)
                }
                if (current.isNotEmpty() && usedHeight + blockHeight > pageHeight) {
                    pages.add(current)
                    current = mutableListOf()
                    usedHeight = 0
                }
                current.add(block)
                usedHeight += blockHeight
            }
            if (current.isNotEmpty()) pages.add(current)
            pages.ifEmpty { listOf(emptyList()) }
        }
        val pagerState = rememberPagerState(pageCount = { pageChunks.size })
        val scope = rememberCoroutineScope()

        LaunchedEffect(pagerState.currentPage, pageChunks.size) {
            val page = (pagerState.currentPage + 1).coerceIn(1, pageChunks.size)
            onPageChange(page, pageChunks.size)
            onScrollFraction(page.toFloat() / pageChunks.size.toFloat())
        }

        Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { pageIndex ->
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.Start
            ) {
                RenderHtmlBlocks(
                    blocks = pageChunks.getOrElse(pageIndex) { emptyList() },
                    settings = settings,
                    textColor = textColor,
                    chapterHref = chapter.href,
                    onTap = onTap,
                    onLinkClick = onLinkClick,
                    onResolveImage = onResolveImage,
                    onTextLayout = onTextLayout,
                    highlights = highlights
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else if (hasPrevChapter && onPrevChapter != null) {
                            onPrevChapter()
                        }
                    }
                ) {
                    Text("←", style = TextStyle(fontSize = 18.sp, color = textColor))
                }

                Text(
                    text = "Page ${pagerState.currentPage + 1} of ${pageChunks.size}",
                    style = FolioTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f)
                )

                IconButton(
                    onClick = {
                        if (pagerState.currentPage < pageChunks.size - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else if (hasNextChapter && onNextChapter != null) {
                            onNextChapter()
                        }
                    }
                ) {
                    Text("→", style = TextStyle(fontSize = 18.sp, color = textColor))
                }
            }

            if (pageChunks.size > 1) {
                Slider(
                    value = pagerState.currentPage.toFloat(),
                    valueRange = 0f..(pageChunks.size - 1).toFloat(),
                    steps = (pageChunks.size - 2).coerceAtLeast(0),
                    onValueChange = { value ->
                        scope.launch { pagerState.scrollToPage(value.toInt()) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }
    }
}
}

@Composable
private fun TwoColumnChapterView(
    blocks: List<com.folio.reader.ui.render.HtmlBlock>,
    chapter: Chapter,
    settings: com.folio.reader.settings.ReaderSettings,
    textColor: androidx.compose.ui.graphics.Color,
    scrollState: androidx.compose.foundation.ScrollState,
    enabled: Boolean,
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveImage: suspend (chapterHref: String, src: String) -> String?,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit,
    highlights: List<Highlight>,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?
) {
    val leftBlocks = remember(blocks) { blocks.take((blocks.size + 1) / 2) }
    val rightBlocks = remember(blocks) { blocks.drop((blocks.size + 1) / 2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = enabled)
            .padding(horizontal = settings.margins.left.dp, vertical = settings.margins.top.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (settings.showChapterTitle) {
            Text(
                text = chapter.title,
                style = FolioTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = textColor,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                RenderHtmlBlocks(
                    blocks = leftBlocks,
                    settings = settings,
                    textColor = textColor,
                    chapterHref = chapter.href,
                    onTap = onTap,
                    onLinkClick = onLinkClick,
                    onResolveImage = onResolveImage,
                    onTextLayout = onTextLayout,
                    highlights = highlights,
                    modifier = Modifier.readerWidth(settings.textWidth)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(textColor.copy(alpha = 0.15f))
            )

            Column(modifier = Modifier.weight(1f)) {
                RenderHtmlBlocks(
                    blocks = rightBlocks,
                    settings = settings,
                    textColor = textColor,
                    chapterHref = chapter.href,
                    onTap = onTap,
                    onLinkClick = onLinkClick,
                    onResolveImage = onResolveImage,
                    onTextLayout = onTextLayout,
                    highlights = highlights,
                    modifier = Modifier.readerWidth(settings.textWidth)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasPrevChapter && onPrevChapter != null) {
                androidx.compose.material3.TextButton(onClick = onPrevChapter) {
                    Text("← Previous")
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            if (hasNextChapter && onNextChapter != null) {
                androidx.compose.material3.Button(onClick = onNextChapter) {
                    Text("Next →")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ContinuousChapterView(
    blocks: List<com.folio.reader.ui.render.HtmlBlock>,
    chapter: Chapter,
    settings: com.folio.reader.settings.ReaderSettings,
    textColor: androidx.compose.ui.graphics.Color,
    scrollState: androidx.compose.foundation.ScrollState,
    enabled: Boolean,
    html: String,
    layoutResult: androidx.compose.ui.text.TextLayoutResult?,
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveImage: suspend (chapterHref: String, src: String) -> String?,
    onLongPress: ((paragraphIndex: Int, selectedText: String) -> Unit)?,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit,
    highlights: List<Highlight>,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = enabled)
            .pointerInput(enabled, html) {
                if (!enabled) return@pointerInput
                detectTapGestures(onLongPress = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val text = layout.layoutInput.text
                    if (text.isEmpty()) return@detectTapGestures
                    val charOffset = layout.getOffsetForPosition(offset)
                        .coerceIn(0, text.length - 1)
                    val paragraphIndex = text.take(charOffset).count { it == '\n' }
                    val paraStart = text.lastIndexOf('\n', charOffset) + 1
                    val paraEnd = text.indexOf('\n', charOffset)
                        .let { if (it == -1) text.length else it }
                    val snippet = text.substring(paraStart, paraEnd)
                        .trim()
                        .take(160)
                    if (snippet.isNotBlank()) {
                        onLongPress?.invoke(paragraphIndex, snippet)
                    }
                })
            }
            .padding(horizontal = settings.margins.left.dp, vertical = settings.margins.top.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RenderHtmlBlocks(
            blocks = blocks,
            settings = settings,
            textColor = textColor,
            chapterHref = chapter.href,
            onTap = onTap,
            onLinkClick = onLinkClick,
            onResolveImage = onResolveImage,
            onTextLayout = onTextLayout,
            highlights = highlights,
            modifier = Modifier.readerWidth(settings.textWidth)
        )
        // Removed chapter navigation buttons for seamless flow
        Spacer(Modifier.height(64.dp)) // Extra space at chapter end for smooth transition
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
    onPageChange: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    onChapterEnd: () -> Unit = {}
) {
    val renderer = rememberHtmlRenderer(settings, onLinkClick)
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
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FolioTheme.colors.primary)
            }
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
                    // Cover chapter also carries real text — parse and render it properly
                    val coverBlocks = remember(html, settings) {
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
                    onTap = onTap,
                    onLinkClick = onLinkClick,
                    onResolveResource = onResolveResource,
                    onHighlightParagraph = onLongPress?.let { cb -> { idx, text -> cb(idx, text) } },
                    seekRequest = seekRequest
                )
            }
        }
    }
}


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
    val quickThemes = listOf("paper", "white", "sepia", "gray", "dark", "oled_black")
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
            .background(FolioTheme.colors.surface.copy(alpha = 0.95f))
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
        Text(
            text = "$currentPage / $totalPages",
            style = FolioTheme.typography.labelMedium,
            color = FolioTheme.colors.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )
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
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)

            LaunchedEffect(currentIndex) {
                if (currentIndex in chapters.indices) {
                    listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
                }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(chapters, key = { "${it.bookId}:${it.id}" }) { chapter ->
                    val index = chapters.indexOf(chapter)
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
    onAddNote: (String) -> Unit
) {
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var noteContent by remember { mutableStateOf("") }

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
        TextButton(
            onClick = { showAddNoteDialog = true },
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text("Add note")
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (bookmarks.isNotEmpty()) {
                item { SectionHeader("Bookmarks (${bookmarks.size})") }
                items(bookmarks, key = { "bm:${it.id}" }) { bookmark ->
                    AnnotationRow(
                        title = bookmark.label ?: "Page ${bookmark.spineIndex + 1}",
                        subtitle = "Spine ${bookmark.spineIndex}",
                        onDelete = { onRemoveBookmark(bookmark.id) }
                    )
                }
            }
            if (highlights.isNotEmpty()) {
                item { SectionHeader("Highlights (${highlights.size})") }
                items(highlights, key = { "hl:${it.id}" }) { highlight ->
                    AnnotationRow(
                        title = highlight.selectedText.take(80).ifBlank { "(empty)" },
                        subtitle = "Spine ${highlight.spineIndex}",
                        accentColor = Color(highlight.color.argb),
                        onDelete = { onRemoveHighlight(highlight.id) }
                    )
                }
            }
            if (notes.isNotEmpty()) {
                item { SectionHeader("Notes (${notes.size})") }
                items(notes, key = { "nt:${it.id}" }) { note ->
                    AnnotationRow(
                        title = note.content.take(80).ifBlank { "(empty)" },
                        subtitle = "Note",
                        onDelete = { onRemoveNote(note.id) }
                    )
                }
            }
            if (bookmarks.isEmpty() && highlights.isEmpty() && notes.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Bookmark spots, add highlights and notes while reading.",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showAddNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("Add note") },
            text = {
                OutlinedTextField(
                    value = noteContent,
                    onValueChange = { noteContent = it },
                    label = { Text("Note") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddNote(noteContent.trim())
                        noteContent = ""
                        showAddNoteDialog = false
                    },
                    enabled = noteContent.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = FolioTheme.typography.titleSmall,
        color = FolioTheme.colors.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun AnnotationRow(
    title: String,
    subtitle: String,
    accentColor: Color? = null,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (accentColor != null) {
            Box(Modifier.width(3.dp).height(32.dp).background(accentColor, RoundedCornerShape(2.dp)))
        } else {
            Icon(Icons.Filled.Star, contentDescription = null, tint = FolioTheme.colors.primary, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = FolioTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant)
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
}

private fun androidx.compose.ui.text.AnnotatedString.withHighlightBackgrounds(
    highlights: List<Highlight>
): androidx.compose.ui.text.AnnotatedString {
    if (highlights.isEmpty()) return this

    val builder = androidx.compose.ui.text.AnnotatedString.Builder().apply { append(this@withHighlightBackgrounds) }
    highlights.forEach { highlight ->
        val selectedText = highlight.selectedText
        if (selectedText.isBlank()) return@forEach

        var startIndex = text.indexOf(selectedText)
        while (startIndex >= 0) {
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = Color(highlight.color.argb).copy(alpha = 0.35f)
                ),
                start = startIndex,
                end = startIndex + selectedText.length
            )
            startIndex = text.indexOf(selectedText, startIndex + selectedText.length)
        }
    }
    return builder.toAnnotatedString()
}

private fun Modifier.readerWidth(textWidth: com.folio.reader.settings.TextWidth): Modifier {
    return when (textWidth) {
        com.folio.reader.settings.TextWidth.NARROW -> this.widthIn(max = 560.dp)
        com.folio.reader.settings.TextWidth.MEDIUM -> this.widthIn(max = 720.dp)
        com.folio.reader.settings.TextWidth.WIDE -> this.widthIn(max = 960.dp)
        com.folio.reader.settings.TextWidth.FULL -> this
        com.folio.reader.settings.TextWidth.CUSTOM -> this.widthIn(max = 1200.dp)
    }
}
