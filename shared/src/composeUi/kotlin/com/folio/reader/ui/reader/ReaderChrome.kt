package com.folio.reader.ui.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.PageBlock
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha

/**
 * The reader's top bar: back, title, clock and the right-side actions. On
 * occluding platforms the rail is not drawn, so the bar carries the TOC,
 * annotations and highlight actions too.
 */
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
    onToggleReaderPanel: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        com.folio.reader.ui.components.FolioStatusBarBand(inkBand = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Glass, like every other surface that sits over content. Squared
                // top corners so it meets the status band cleanly.
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
                color = FolioTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Right-side action icons
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showClock) {
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
                                onHighlightParagraph?.invoke(selected.first, selected.second, selected.third)
                                onSelectionConsumed()
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
                    IconButton(onClick = onOpenToc) {
                        Icon(Icons.Filled.Toc, contentDescription = "Contents", tint = FolioTheme.colors.onSurface)
                    }
                    IconButton(onClick = onOpenAnnotations) {
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
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) bookmarkColor else FolioTheme.colors.onSurface
                    )
                }
                IconButton(onClick = onToggleReaderPanel) {
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

/**
 * Left floating controls rail (skipped on occluding platforms — the
 * heavyweight browser window would cover it; the top bar carries the actions).
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
    onOpenAnnotations: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(start = 12.dp)
            .width(56.dp)
            .folioVeil(RoundedCornerShape(24.dp), fillAlpha = FolioTheme.readerVeilAlpha)
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
                        onHighlightParagraph?.invoke(selected.first, selected.second, selected.third)
                        onSelectionConsumed()
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
            IconButton(onClick = onOpenToc) {
                Icon(Icons.Filled.Toc, contentDescription = "Contents", tint = FolioTheme.colors.onSurface)
            }
            IconButton(onClick = onOpenAnnotations) {
                Icon(Icons.Filled.Notes, contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
            }
            IconButton(onClick = onBookmarkClick) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this spot",
                    tint = if (isBookmarked) bookmarkColor else FolioTheme.colors.onSurface
                )
            }
        }
    }
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
    onSeek: ((Float) -> Unit)? = null
) {
    // Same glass as the top bar, so the two ends of the reader chrome are the same
    // material; the raw 0.92 surface fill let page text bleed through.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioVeil(
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                fillAlpha = FolioTheme.readerVeilAlpha,
            )
            .padding(horizontal = 14.dp)
    ) {
        PageBlock(
            fraction = fraction,
            modifier = Modifier
                .fillMaxWidth()
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
            onSeek = onSeek
        )
        if (chapterTitle.isNotBlank() || pagesLeftLabel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
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
                    // A soft accent pill rather than bare text: it reads as a distinct chip from
                    // the chapter title beside it, and the tinted ground gives the numeral the
                    // "forward motion" accent (Rule 14) without competing with the page's ink.
                    Text(
                        text = pagesLeftLabel,
                        style = FolioTheme.typography.labelMedium,
                        color = FolioTheme.colors.accentProgress,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .background(
                                FolioTheme.colors.accentProgress.copy(alpha = 0.14f),
                                RoundedCornerShape(percent = 50),
                            )
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Floating sync indicator pill — manages its own visibility (hides when idle). */
@Composable
internal fun BoxScope.ReaderSyncPill(showControls: Boolean, syncState: com.folio.reader.sync.SyncState) {
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
