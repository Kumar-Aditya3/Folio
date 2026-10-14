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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioMenu
import com.folio.reader.ui.components.FolioMenuItem
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.PageBlock
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberFolioChromeMaterial
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioSurfaceOpacity
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
    shape: Shape = RoundedCornerShape(FolioTokens.radiusReaderIsland),
): Modifier {
    val chrome = rememberFolioChromeMaterial()
    return this.folioVeil(
        shape = shape,
        elevation = FolioTokens.elevationVeil,
        fillColor = chrome.plane,
        fillAlpha = FolioTheme.readerVeilAlpha,
        weight = chrome.weight,
    )
}

/**
 * The narrowest a title capsule may get before the chrome sheds an action rather than letting
 * the title ellipsize further. Under this the capsule is spending its width on an ellipsis
 * rather than on a name, which is the state this bar exists to avoid.
 */
private val ReaderTitleCapsuleMin = 140.dp

/**
 * The capsule width at which the clock can ride beside the title without pushing the title under
 * [ReaderTitleCapsuleMin]. The clock is the one genuinely optional thing in the capsule, so where
 * there is not room for both, the clock is what goes.
 */
private val ReaderClockCapsuleMin = 200.dp

/** One reader island button — the Material touch target, and the unit the top bar budgets in. */
private val ReaderIslandButton = 48.dp

/**
 * The reader's top bar, redesigned as floating islands: a back capsule, a title capsule and an
 * action capsule, each hovering below the status bar with a uniform margin so the page breathes
 * through the gaps. The old full-width slab welded to the status band is gone. On occluding
 * platforms the rail is not drawn, so the action island carries the selection actions inline
 * while a selection is live, and folds Contents and Annotations into a single overflow menu.
 *
 * The bar **budgets its width before it draws**. The title capsule is this row's only weighted
 * child, so it is the only one that *can* yield: every island beside it is measured at its full
 * intrinsic width first, which means an unfixed bar lets the title absorb the entire shortfall
 * and ellipsize toward nothing. So the available width is measured here and the chrome is chosen
 * to fit the title rather than the other way round — Search rides in the left rail where there is
 * one and folds into the overflow where there is not, and the clock only rides when the capsule
 * can still give the title its floor.
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
    onToggleReaderPanel: () -> Unit,
    echoesEnabled: Boolean = false,
    onOpenEchoes: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                horizontal = FolioTokens.insetReaderIsland,
                vertical = FolioTokens.insetReaderIsland,
            ),
    ) {
        // Settle the row's width before drawing it — see the note on this composable. Every
        // island here is measured at its intrinsic width first, so the title capsule's slot has
        // to be worked out from the chrome that will sit beside it.
        val islandGap = FolioTokens.insetReaderIsland
        // The action island's resting set: Bookmark and Settings always, plus the overflow
        // wherever the rail is not drawn — there it is the only way to reach Contents and
        // Annotations.
        val overflowButton = if (occludes) 1 else 0
        // Search rides in the left rail on platforms that draw one, so the top bar only carries
        // it where there is no rail, and there only while doing so still leaves the title its
        // floor. It is deliberately *not* moved to the bottom block: that block is gated on
        // `settings.showProgress`, and search must not be one display preference away from
        // disappearing.
        val searchInline = occludes && maxWidth >=
            ReaderIslandButton * (3 + overflowButton) + islandGap * 2 + ReaderTitleCapsuleMin
        val actionCount = 2 + overflowButton + if (searchInline) 1 else 0
        val capsuleWidth =
            maxWidth - ReaderIslandButton - islandGap * 2 - ReaderIslandButton * actionCount
        // The clock is unweighted, so it is measured *before* the title: left to itself the
        // supplement takes width from the content. It rides only when the capsule can afford
        // both.
        val clockRides = showClock && capsuleWidth >= ReaderClockCapsuleMin

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(islandGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left island — back. A circular capsule: one glyph, so it reads as a button.
            Box(Modifier.readerIsland(CircleShape)) {
                IconButton(onClick = onBackPress) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = FolioTheme.colors.onSurface,
                    )
                }
            }

            // Center island — title (+ clock). Stretches to fill the gap between the side
            // islands; the margins to either end keep it reading as a floating pill rather
            // than a bar.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .readerIsland()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = FolioTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = bookTitle,
                    style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (clockRides) {
                    Text(
                        text = com.folio.reader.ui.components.rememberClockTime(),
                        style = FolioTheme.typography.labelMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            // Right island — actions. The frequent ones ride inline; the rest fold into one
            // overflow so the island stays a few glyphs wide, not the old seven-icon bar.
            Row(
                modifier = Modifier.readerIsland(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (occludes) {
                    // The rail is not drawn on occluding platforms, so the selection actions
                    // live here — but only while there is a live selection, so the resting
                    // island is small.
                    val selected = pageSelection
                    if (selected != null) {
                        val highlightHaptics = rememberFolioHaptics()
                        IconButton(onClick = {
                            highlightHaptics.play(FolioHaptic.Confirm)
                            onHighlightParagraph?.invoke(selected.first, selected.second, selected.third)
                            onSelectionConsumed()
                        }) {
                            Icon(
                                Icons.Filled.Highlight,
                                contentDescription = "Highlight selection",
                                tint = FolioTheme.colors.onSurface,
                            )
                        }
                        if (echoesEnabled) {
                            val echoesHaptics = rememberFolioHaptics()
                            IconButton(onClick = {
                                echoesHaptics.play(FolioHaptic.Confirm)
                                onOpenEchoes()
                            }) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = "Find echoes of this passage",
                                    tint = FolioTheme.colors.onSurface,
                                )
                            }
                        }
                    }
                }
                // Where the rail carries Search the bar does not — the two would be a
                // redundant pair, and the rail's is the one in the thumb zone. Where there is
                // no rail, Search rides here while the width allows it and otherwise drops
                // into the overflow below.
                if (searchInline) {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = FolioTheme.colors.onSurface,
                        )
                    }
                }
                val bookmarkMotion = rememberMotionEnabled()
                val bookmarkSpec = rememberMotionAnimation<Float>(MotionKey.BookmarkGlyphSwap)
                val bookmarkHaptics = rememberFolioHaptics()
                IconButton(onClick = {
                    bookmarkHaptics.play(FolioHaptic.Confirm)
                    onBookmarkClick()
                }) {
                    AnimatedContent(
                        targetState = isBookmarked,
                        transitionSpec = {
                            if (bookmarkMotion) {
                                fadeIn(bookmarkSpec) togetherWith fadeOut(bookmarkSpec)
                            } else {
                                EnterTransition.None togetherWith ExitTransition.None
                            }
                        },
                        label = "bookmark-glyph",
                    ) { bookmarked ->
                        Icon(
                            imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark this spot",
                            tint = if (bookmarked) bookmarkColor else FolioTheme.colors.onSurface,
                        )
                    }
                }
                IconButton(onClick = onToggleReaderPanel) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = FolioTheme.colors.onSurface,
                    )
                }
                if (occludes) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More actions",
                                tint = FolioTheme.colors.onSurface,
                            )
                        }
                        FolioMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            // Reader-owned menu, but a pop-up: pinned opaque, off the knobs.
                            fillAlpha = FolioSurfaceOpacity.POPUP_OPAQUE,
                        ) {
                            if (!searchInline) {
                                FolioMenuItem(
                                    text = { Text("Search") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Search, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onSearchClick()
                                    },
                                )
                            }
                            FolioMenuItem(
                                text = { Text("Contents") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Toc, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenToc()
                                },
                            )
                            FolioMenuItem(
                                text = { Text("Annotations") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenAnnotations()
                                },
                            )
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
    val chrome = rememberFolioChromeMaterial()
    Box(
        modifier = Modifier
            .padding(start = 12.dp)
            .width(56.dp)
            // The chrome material at the reader's own alpha, like the reader's two bars —
            // the rail is chrome too, so it cannot be made of the card plane while the bar
            // above it is made of the chrome one. See FolioChromeMaterial.
            .folioVeil(
                FolioShapes.card,
                fillColor = chrome.plane,
                fillAlpha = FolioTheme.readerVeilAlpha,
                weight = chrome.weight,
            )
            .padding(vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // The rail is the reader's whole action set, and it grows as the reader's settings do
            // (Echoes) — on a short viewport, which is any phone held sideways, the stack can
            // outrun the screen. Scrolling rather than clipping means the last action is still
            // reachable there; in portrait the column never exceeds its bound and never scrolls.
            // Only vertical drags are claimed, so the reader's horizontal page-turn gestures are
            // untouched.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Highlight lives here rather than floating by the selection: the
            // OS selection toolbar covers anything drawn near the text. Dull
            // until there is a selection, then it brightens to invite the tap.
            val selected = pageSelection
            val highlightHaptics = rememberFolioHaptics()
            IconButton(
                enabled = selected != null,
                onClick = {
                    if (selected != null) {
                        highlightHaptics.play(FolioHaptic.Confirm)
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
            if (echoesEnabled) {
                val echoesHaptics = rememberFolioHaptics()
                IconButton(
                    enabled = selected != null,
                    onClick = {
                        if (selected != null) {
                            echoesHaptics.play(FolioHaptic.Confirm)
                            onOpenEchoes()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = if (selected != null) "Find echoes of this passage" else "Select text to find echoes",
                        tint = if (selected != null) FolioTheme.colors.onSurface
                        else FolioTheme.colors.onSurface.copy(alpha = 0.32f)
                    )
                }
            }
            FolioRule(modifier = Modifier.width(32.dp))
            // Search lives here rather than in the top bar. The rail is the reader's action rail
            // and is gated only on the chrome being shown, so Search keeps a home in the thumb
            // zone while the top bar spends its width on the book's name instead of a fourth
            // glyph. The top bar falls back to carrying it wherever no rail is drawn.
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = FolioTheme.colors.onSurface)
            }
            IconButton(onClick = onOpenToc) {
                Icon(Icons.AutoMirrored.Filled.Toc, contentDescription = "Contents", tint = FolioTheme.colors.onSurface)
            }
            IconButton(onClick = onOpenAnnotations) {
                Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Annotations", tint = FolioTheme.colors.onSurface)
            }
            // Bookmark intentionally omitted from the left rail — it lives in the top
            // bar; a second copy here was redundant.
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
    onSeek: ((Float) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                        .readerIsland(RoundedCornerShape(FolioTokens.radiusReaderIsland))
                }
            )
            .padding(
                horizontal = FolioTokens.space3,
                vertical = if (bare) 0.dp else 10.dp,
            )
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
                    .padding(top = 6.dp),
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
                            .padding(start = 12.dp)
                            .background(
                                if (editorial) FolioTheme.colors.accentProgress.copy(alpha = 0.14f)
                                else FolioTheme.colors.onSurface.copy(alpha = 0.06f),
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
