@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioMenu
import com.folio.reader.ui.components.FolioMenuItem
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.PageBlock
import com.folio.reader.ui.components.folioReaderGlass
import com.folio.reader.ui.components.folioDataGlass
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberFolioChromeMaterial
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioSoftShape
import com.folio.reader.ui.theme.readerPanelVeilAlpha
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.rememberFolioHaptics
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import com.folio.reader.ui.theme.rememberMotionSpec

/**
 * The glass capsule every floating reader island is cut from — the back button, the title,
 * the action cluster and the bottom progress bar all share it, so the chrome reads as one
 * family of objects rather than ad-hoc panels. It is the chrome plane at the reader's own
 * (near-solid) alpha, because this glass sits over a WebView that cannot blur: legibility
 * comes from the capsule itself, and the page shows through the *gaps between* islands rather
 * than through the glass.
 */
@Composable
internal fun Modifier.readerIsland(
    shape: Shape = FolioSoftShape(FolioTokens.radiusReaderIsland),
): Modifier {
    return this.folioReaderGlass(shape, FolioTheme.readerVeilAlpha)
}

/** Same quiet material as Settings, controlled by the reader's own opacity preference. */
@Composable
internal fun Modifier.readerSettingsSheet(shape: Shape): Modifier =
    this.folioReaderGlass(shape, fillAlpha = FolioTheme.readerPanelVeilAlpha)

/** Paint a thin instrument strip inside the full-size action targets. */
@Composable
internal fun ReaderChromeGroup(
    modifier: Modifier = Modifier,
    single: Boolean = false,
    verticalInset: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().padding(vertical = verticalInset, horizontal = if (single) 8.dp else 0.dp)
            .readerIsland(FolioSoftShape(8.dp)))
        content()
    }
}

/** A compact shared reading strip. Android opens the workspace; occluding hosts retain a menu. */
@Composable
internal fun ReaderTopBar(
    bookTitle: String,
    showClock: Boolean,
    occludes: Boolean,
    pageSelection: Triple<String, Int, String>?,
    onHighlightParagraph: ((chapterId: String, paragraphIndex: Int, selectedText: String) -> Unit)?,
    onSelectionConsumed: () -> Unit,
    isBookmarked: Boolean,
    bookmarkColor: Color,
    onBackPress: () -> Unit,
    onSearchClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenAnnotations: () -> Unit,
    onToggleReaderPanel: () -> Unit,
    echoesEnabled: Boolean = false,
    onOpenEchoes: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ReaderChromeGroup(Modifier.fillMaxWidth(), verticalInset = 4.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp), FolioTheme.colors.onSurface)
                }
                Text(bookTitle, style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (showClock) Text(com.folio.reader.ui.components.rememberClockTime(),
                    style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp))
                val haptics = rememberFolioHaptics()
                IconButton(onClick = { haptics.play(FolioHaptic.Confirm); onBookmarkClick() }) {
                    Icon(if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        if (isBookmarked) "Remove bookmark" else "Bookmark this spot", Modifier.size(19.dp),
                        if (isBookmarked) FolioTheme.colors.accentAnnotation else FolioTheme.colors.onSurface)
                }
                // A single stable entry opens the shared workspace. All five tools are one tap away.
                Box {
                    IconButton(onClick = onOpenToc) {
                        Icon(Icons.Filled.MoreVert, "Reader tools", Modifier.size(20.dp), FolioTheme.colors.onSurface)
                    }
                    if (occludes) FolioMenu(menuOpen, { menuOpen = false },
                        fillAlpha = FolioTheme.readerPanelVeilAlpha, readerMaterial = true) {
                        FolioMenuItem(text = { Text("Contents") }, onClick = { menuOpen = false; onOpenToc() })
                        FolioMenuItem(text = { Text("Search") }, onClick = { menuOpen = false; onSearchClick() })
                        FolioMenuItem(text = { Text("Saved passages") }, onClick = { menuOpen = false; onOpenAnnotations() })
                        FolioMenuItem(text = { Text("Reading settings") }, onClick = { menuOpen = false; onToggleReaderPanel() })
                        pageSelection?.let { selected ->
                            FolioMenuItem(text = { Text("Keep selection") }, onClick = {
                                menuOpen = false
                                onHighlightParagraph?.invoke(selected.first, selected.second, selected.third)
                                onSelectionConsumed()
                            })
                            if (echoesEnabled) FolioMenuItem(text = { Text("Find echoes") }, onClick = {
                                menuOpen = false; onOpenEchoes()
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Left floating controls rail (skipped on occluding platforms — the
 * heavyweight browser window would cover it; the top bar carries the actions).
 *
 * It carries Search, Contents and Annotations. Search is here rather than in the top bar so the
 * bar can spend its width on the book's name; see [ReaderTopBar].
 */
@Composable
internal fun ReaderFloatingRail(
    pageSelection: Triple<String, Int, String>?,
    onHighlightParagraph: ((chapterId: String, paragraphIndex: Int, selectedText: String) -> Unit)?,
    onSelectionConsumed: () -> Unit,
    isBookmarked: Boolean,
    bookmarkColor: Color,
    onBookmarkClick: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenAnnotations: () -> Unit,
    onSearchClick: () -> Unit,
    echoesEnabled: Boolean = false,
    onOpenEchoes: () -> Unit = {}
) {
    Box(
        modifier = Modifier.padding(start = 8.dp).width(48.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.matchParentSize().padding(horizontal = 8.dp).readerIsland(FolioSoftShape(10.dp)))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            // The rail is the reader's whole action set, and it grows as the reader's settings do
            // (Echoes) — on a short viewport, which is any phone held sideways, the stack can
            // outrun the screen. Scrolling rather than clipping means the last action is still
            // reachable there; in portrait the column never exceeds its bound and never scrolls.
            // Only vertical drags are claimed, so the reader's horizontal page-turn gestures are
            // untouched.
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Selection actions join the rail only while there is a passage to act on.
            // Keeping two disabled cells on screen made the resting reader unnecessarily tall.
            val selected = pageSelection
            val highlightHaptics = rememberFolioHaptics()
            if (selected != null) {
            ReaderRailButton(
                onClick = {
                    highlightHaptics.play(FolioHaptic.Confirm)
                    onHighlightParagraph?.invoke(selected.first, selected.second, selected.third)
                    onSelectionConsumed()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Highlight,
                    modifier = Modifier.size(20.dp),
                    contentDescription = "Highlight selection",
                    tint = FolioTheme.colors.onSurface
                )
            }
            if (echoesEnabled) {
                val echoesHaptics = rememberFolioHaptics()
                ReaderRailButton(
                    onClick = {
                        echoesHaptics.play(FolioHaptic.Confirm)
                        onOpenEchoes()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        modifier = Modifier.size(20.dp),
                        contentDescription = "Find echoes of this passage",
                        tint = FolioTheme.colors.onSurface
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
                FolioRule(modifier = Modifier.width(18.dp))
            }
            }
            // Search lives here rather than in the top bar. The rail is the reader's action rail
            // and is gated only on the chrome being shown, so Search keeps a home in the thumb
            // zone while the top bar spends its width on the book's name instead of a fourth
            // glyph. The top bar falls back to carrying it wherever no rail is drawn.
            ReaderRailButton(onClick = onSearchClick) {
                Icon(Icons.Filled.Search, modifier = Modifier.size(20.dp), contentDescription = "Search", tint = FolioTheme.colors.onSurface)
            }
            ReaderRailButton(onClick = onOpenToc) {
                Icon(Icons.AutoMirrored.Filled.Toc, modifier = Modifier.size(20.dp), contentDescription = "Contents", tint = FolioTheme.colors.onSurface)
            }
            ReaderRailButton(onClick = onOpenAnnotations) {
                Icon(Icons.AutoMirrored.Filled.Notes, modifier = Modifier.size(20.dp), contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
            }
            // Bookmark intentionally omitted from the left rail — it lives in the top
            // bar; a second copy here was redundant.
        }
    }
}

/** Fixed rail cell: the hit target and visual center share one coordinate space. */
@Composable
private fun ReaderRailButton(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}

/**
 * The bottom chrome's height with the page block alone: an 18dp block inside a
 * 34dp band, so the thumb never has to hit a hairline to seek.
 */
internal val pageBlockChromeHeight: Dp = 34.dp

/**
 * [pageBlockChromeHeight] plus the chapter line under it. This is what a platform
 * whose page paints over Compose has to reserve, and what the end-of-chapter chip
 * clears.
 */
internal val pageBlockChromeHeightWithChapter: Dp = 56.dp

/**
 * The reader's bottom chrome: the **page block**, and the chapter line under it
 * when the reader has asked for one.
 *
 * This replaced a progress bar and a "3 / 12" readout. The bar was chrome *about*
 * the page; the block is the page — a codex seen edge-on, the read stack thickening
 * under the left thumb while the unread stack thins under the right, with the leaf
 * the reader is on standing proud between them. It is also the seek affordance: the
 * band takes the same tap and drag the scrubber did, so nothing was stranded by
 * losing the bar.
 *
 * [fraction] is the **whole book**, never the chapter — a block that emptied at
 * every chapter boundary would be a lie about how much of the book is left. The
 * chapter structure is still legible: [chapterStops] are struck as notches and the
 * chapter the reader is inside carries a faint wash.
 *
 * [paper] and [ink] are the *reader* theme's, not the app palette's: the reader
 * re-themes itself independently of the app, and the block is cut from the page the
 * reader is holding.
 *
 * [stateLabel] is the position announced to a screen reader. The block carries no
 * numerals, so this is where the information the "3 / 12" used to give lives.
 */
@Composable
fun BottomPageBlock(
    chapterTitle: String,
    fraction: Float,
    paper: Color,
    ink: Color,
    stateLabel: String,
    pageCountHint: Int = 0,
    chapterStops: List<Float> = emptyList(),
    /**
     * A visible "N left" readout, e.g. "12 pages left" in this chapter. The leaf block is a
     * shape, not a number, and continuous mode has no page turns to count — so the numeral was
     * the missing "how much is left" the reader asked for. Null hides the line.
     */
    pagesLeftLabel: String? = null,
    /**
     * When true the block draws no glass of its own and takes no nav-bar inset: it is being
     * stacked inside an outer chrome sheet (e.g. the PDF reader's controls+progress plate) that
     * already supplies the single continuous glass and the bottom inset. The block then
     * contributes only its content, so the two stacked rows read as one plate with one rounded
     * top instead of a square-bottomed row sitting on the block's rounded shoulders.
     */
    bare: Boolean = false,
    /**
     * Editorial (expressive) reading look. When false (the calm default) the "pages left" chip
     * reads neutral — a quiet neutral ground with onSurfaceVariant numerals — so the chrome carries
     * no accent colour; when true it restores the accent-tinted "forward motion" pill (Rule 14).
     */
    editorial: Boolean = false,
    readingTrace: com.folio.reader.model.ReadingTrace? = null,
    readingTraceId: String? = null,
    onSeek: ((Float) -> Unit)? = null,
    onPreview: ((Float?) -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpenMap == null) Modifier else Modifier.pointerInput(onOpenMap) {
                var dragged = 0f
                var opened = false
                detectVerticalDragGestures(onDragStart = { dragged = 0f; opened = false }) { change, amount ->
                    dragged += amount
                    if (!opened && dragged < -24.dp.toPx()) {
                        opened = true
                        change.consume()
                        onOpenMap()
                    }
                }
            })
            // The redesigned bottom chrome is a floating island, not a welded bar: it sits above
            // the nav bar and insets from both side edges and the bottom by one [insetReaderIsland],
            // so the page shows through a uniform margin on every side. The old "fill to the
            // physical bottom edge" rule (which existed to kill an *accidental* show-through gap
            // under a full-width bar) no longer applies — the margin here is deliberate and even.
            .then(
                if (bare) {
                    Modifier
                } else {
                    Modifier
                        .navigationBarsPadding()
                        .padding(
                            start = FolioTokens.insetReaderIsland,
                            end = FolioTokens.insetReaderIsland,
                            bottom = FolioTokens.insetReaderIsland,
                        )
                        .readerIsland(FolioSoftShape(FolioTokens.radiusReaderIsland))
                }
            )
            .padding(
                horizontal = FolioTokens.space3,
                vertical = if (bare) 0.dp else 4.dp,
            )
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (readingTrace != null) com.folio.reader.ui.components.ReadingTraceLine(
            readingTrace,
            Modifier.weight(1f).height(pageBlockChromeHeight),
            onSeek = onSeek,
            sharedId = readingTraceId,
            onPreview = onPreview,
        ) else PageBlock(
            fraction = fraction,
            modifier = Modifier
                .weight(1f)
                .height(pageBlockChromeHeight),
            paper = paper,
            ink = ink,
            // Rule 14: forward motion is accentProgress, never primary. Guarded
            // against the paper inside PageBlock, because the leaf is a fine line
            // drawn on the page's own material.
            accent = FolioTheme.colors.accentProgress,
            pageCountHint = pageCountHint,
            chapterStops = chapterStops,
            stateLabel = stateLabel,
            onSeek = onSeek,
            onPreview = onPreview
        )
        if (onOpenMap != null) IconButton(onClick = onOpenMap) {
            Icon(Icons.AutoMirrored.Filled.Toc, "Open reading map", Modifier.size(19.dp), FolioTheme.colors.onSurfaceVariant)
        }
        }
        if (chapterTitle.isNotBlank() || pagesLeftLabel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chapterTitle,
                    style = FolioTheme.typography.labelMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pagesLeftLabel != null) {
                    // A soft pill rather than bare text: it reads as a distinct chip from the
                    // chapter title beside it. Calm by default — a neutral ground with
                    // onSurfaceVariant numerals, so it never competes with the page's ink — and
                    // only wears the "forward motion" accent (Rule 14) in the editorial look.
                    Text(
                        text = pagesLeftLabel,
                        style = FolioTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (editorial) FolioTheme.colors.accentProgress else FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** Floating sync indicator pill — manages its own visibility (hides when idle). */
@Composable
internal fun BoxScope.ReaderSyncPill(showControls: Boolean, syncState: com.folio.reader.sync.SyncState) {
    // The pill's own row. This was a literal 220 ms that ignored reduce-motion outright — the pill
    // slid whether or not the reader had asked for less motion.
    val pillTop by animateDpAsState(
        targetValue = if (showControls) 60.dp else 0.dp,
        animationSpec = rememberMotionAnimation<Dp>(MotionKey.SyncPill),
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
