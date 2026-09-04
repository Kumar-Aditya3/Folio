package com.folio.reader.ui.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

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
    pageSelection: Pair<Int, String>?,
    onHighlightParagraph: ((paragraphIndex: Int, selectedText: String) -> Unit)?,
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
                                onHighlightParagraph?.invoke(selected.first, selected.second)
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
    pageSelection: Pair<Int, String>?,
    onHighlightParagraph: ((paragraphIndex: Int, selectedText: String) -> Unit)?,
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
            .folioVeil(RoundedCornerShape(24.dp))
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

@Composable
fun BottomProgressBar(
    chapterTitle: String,
    currentPage: Int = 1,
    totalPages: Int = 1,
    onSeek: ((Float) -> Unit)? = null
) {
    val fraction = if (totalPages > 0) currentPage.toFloat() / totalPages else 0f
    // Same glass as the top bar, so the two ends of the reader chrome are the
    // same material; the raw 0.92 surface fill let page text bleed through.
    val accent = com.folio.reader.ui.components.rememberLegibleAccent(FolioTheme.colors.primary)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioVeil(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
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
                color = accent
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
                color = accent
            )
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
