package com.folio.reader.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.model.Book
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioLogoMark
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Home, recomposed as a reading room rather than a dashboard.
 *
 * The old screen was four `FolioSectionCard`s of identical weight, so nothing
 * answered "what am I reading?" faster than anything else. The new order is a
 * deliberate crescendo and rest:
 *
 * 1. **The anchor** — one book, one cover at 148dp overhanging the edge of a
 *    raised asymmetric plane, lit by its own dominant colour. Unmissable.
 * 2. **The ledger** — goal and streak as small figures directly on the page, no
 *    container at all. The deliberate quiet beat after the anchor.
 * 3. **Shelves** — continue-reading and discovery as rows of cover plates that
 *    run past the gutter, headed by type instead of boxed.
 * 4. **The week** — a sunken well, so the one chart on the screen reads as cut
 *    *into* the page while the anchor floats above it.
 *
 * Nothing was removed: every element, tap target and exclusion notice the old
 * layout carried is still here, re-ranked.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenBook: (String) -> Unit,
    onOpenBookDetail: (String) -> Unit,
    onImportClick: () -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenExclusions: () -> Unit = {},
    mangaBackend: MangaBackend? = null,
    onOpenMangaDetail: (String) -> Unit = {},
    onOpenMangaReader: (String, String) -> Unit = { _, _ -> },
    onOpenSourceWeb: (String) -> Unit = {},
    onOpenDiscover: (MangaDiscoverItem) -> Unit = {},
    onHeroCollapse: (Float, String?, Color?) -> Unit = { _, _, _ -> },
    topInset: Dp = 0.dp, // the masthead floats over the page; the host sizes the gap
) {
    when {
        !state.loaded -> HomeSkeleton(Modifier.padding(top = topInset))
        !state.hasBooks && !state.hasManga ->
            Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.MenuBook,
                    headline = "Your library is empty — import an EPUB to start.",
                    action = { Button(onClick = onImportClick) { Text("Import a book") } }
                )
            }
        else -> {
            val listState = rememberLazyListState()
            // §13.9 hero collapse: tracked 1:1 off the scroll offset — no spring,
            // no settle — over the first 160dp; reduce-motion keeps the hero full
            // size and simply scrolling away.
            val motion = rememberMotionEnabled()
            val density = LocalDensity.current
            val collapseRange = with(density) { 160.dp.toPx() }
            val collapse = remember(motion, collapseRange) {
                derivedStateOf {
                    when {
                        !motion -> 0f
                        listState.firstVisibleItemIndex > 0 -> 1f
                        else -> (listState.firstVisibleItemScrollOffset / collapseRange).coerceIn(0f, 1f)
                    }
                }
            }
            // §11.4: a manga card cannot draw its cover without a backend, so on
            // desktop the merged list degrades to exactly the books-only shelf it
            // was before rather than rendering empty plates.
            val readingNow = if (mangaBackend == null) {
                state.readingNow.filter { it.kind == HomeItemKind.BOOK }
            } else {
                state.readingNow
            }
            val anchorItem = readingNow.firstOrNull()
            // The hero tint is computed here so the host top bar can bleed the
            // same colour upward (§13.9); the hero itself re-derives it cheaply
            // from the accent cache. A manga with only a network thumbnail has no
            // local file to sample, so it lands on the fallback accent.
            val heroTint = if (state.coverTint && anchorItem != null) {
                rememberCoverAccent(anchorItem.coverPath, FolioTheme.colors.accentProgress)
            } else null
            val fallbackTint = FolioTheme.colors.accentProgress
            // §13.9: the collapse is reported *continuously*, not as a flip at the
            // end. The bar cross-fades "Folio" out and the book's title in over the
            // same 160dp the hero recedes across, so the two halves of the effect
            // move together instead of the title snapping in after the fact.
            LaunchedEffect(anchorItem?.id, heroTint, fallbackTint) {
                snapshotFlow { collapse.value }.collect { fraction ->
                    onHeroCollapse(fraction, anchorItem?.title, heroTint ?: fallbackTint)
                }
            }
            // One tap contract for both libraries: a book opens in the reader, a
            // manga opens its last-read chapter, and a manga with no chapter yet
            // opens its detail screen instead of failing silently.
            val openItem: (ReadingNowItem) -> Unit = { item ->
                when (item.kind) {
                    HomeItemKind.BOOK -> onOpenBook(item.id)
                    HomeItemKind.MANGA -> {
                        val chapter = item.chapterId
                        if (chapter != null) onOpenMangaReader(item.id, chapter)
                        else onOpenMangaDetail(item.id)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // No uniform arrangement: each block owns the space beneath it. No
                // bottom runway either — the closing well absorbs the floating
                // capsule's clearance itself (see [ThisWeekWell]).
                contentPadding = PaddingValues(top = topInset),
            ) {
                item {
                    ReadingNowAnchor(anchorItem, mangaBackend, collapse, heroTint, openItem, onOpenLibrary)
                    Spacer(Modifier.height(FolioTokens.spaceBeat))
                }
                item {
                    LedgerStrip(state, onOpenStats, onOpenExclusions)
                    Spacer(Modifier.height(FolioTokens.spaceMovement))
                }
                // The shelf is the rest of the same ranked list — books and manga
                // interleaved by when they were last read, not grouped by format.
                val shelf = readingNow.drop(1)
                if (shelf.isNotEmpty()) {
                    item {
                        ContinueShelf(shelf, mangaBackend, openItem, onOpenSourceWeb)
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                    }
                }
                // §11.4: "New chapters" sits after the books shelf; hidden at zero.
                if (state.newChapters.isNotEmpty() && mangaBackend != null) {
                    item {
                        NewChaptersCard(state.newChapters, mangaBackend, onOpenMangaDetail)
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                    }
                }
                // §11.4: Discover — LATEST from the most recently read manga's source.
                if (state.discover.isNotEmpty() && mangaBackend != null) {
                    item {
                        DiscoverCard(state.discover, mangaBackend, onOpenDiscover)
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                    }
                }
                val finishedTitle = state.becauseFinishedTitle
                if (finishedTitle != null && state.candidates.size >= 2) {
                    item {
                        BecauseYouFinishedShelf(finishedTitle, state.candidates, onOpenBookDetail)
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                    }
                }
                item { ThisWeekWell(state, onOpenStats, LocalFolioBarInset.current) }
            }
        }
    }
}

/**
 * **The anchor.** One book, stated as an object in a room.
 *
 * Composition: a raised asymmetric plane inset from the trailing edge only, with
 * the cover plate *overhanging its leading edge* by 12dp so the artwork breaks the
 * container instead of sitting inside it. Title sits beside the cover, not beneath
 * — large, in the display face, ranged left against the cover's edge. Progress is
 * integrated as a hairline seam across the plane's foot with the percentage set as
 * a figure, so it reads as part of the composition rather than metadata.
 *
 * The cover's own dominant colour does three jobs at once: it lights the plane's
 * top rim, throws a halo behind the plate, and tints the plane's gradient. That is
 * the whole "cover as design material" idea in one place — and it is scoped to this
 * one composable, so a single cover never recolours the app.
 *
 * §13.9 collapse is preserved: the cover scales 1.0→0.62 toward the bar and the
 * tint fades 0.30→0.10, tracked 1:1 with the finger, frozen under reduce-motion.
 */
@Composable
private fun ReadingNowAnchor(
    item: ReadingNowItem?,
    backend: MangaBackend?,
    collapse: State<Float>,
    heroTint: Color?,
    onOpen: (ReadingNowItem) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val colors = FolioTheme.colors
    if (item == null) {
        // §12.9 designed empty: an invitation set in type, not an empty card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioTokens.gutter)
                .padding(top = FolioTokens.spaceBeat),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            FolioEyebrow("Reading now", accent = colors.accentProgress)
            Text(
                "Nothing in progress.",
                style = FolioTheme.typography.displaySmall,
                color = colors.onSurface,
            )
            Text(
                "Pick something from your library and it will anchor this page.",
                style = FolioTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenLibrary) { Text("Open library") }
        }
        return
    }
    val tint = heroTint ?: colors.accentProgress
    val fraction = collapse.value
    val interaction = rememberFolioInteraction()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Inset on the trailing side only: the plane runs off the leading edge,
            // which is what makes it read as a spread rather than a card.
            .padding(end = FolioTokens.gutter, top = FolioTokens.space2)
            // §13.9: the anchor recedes as **one object** — it steps back a little,
            // lifts, and dissolves while its title migrates into the bar. Scaling
            // only the cover inside a plane that kept its full size is what made the
            // artwork slide away from its own container and open a hole in the middle
            // of the card: the animation and the surface disagreed.
            .graphicsLayer {
                val scale = androidx.compose.ui.util.lerp(1f, 0.96f, fraction)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.3f, 0f)
                translationY = -fraction * 12.dp.toPx()
                alpha = androidx.compose.ui.util.lerp(1f, 0.32f, fraction)
            }
            .folioPressable(interaction)
            .folioRaised(
                shape = FolioShapes.heroBleed,
                accent = tint,
                elevation = FolioTokens.elevationRaised,
            )
            .background(
                Brush.linearGradient(
                    0f to tint.copy(alpha = androidx.compose.ui.util.lerp(0.30f, 0.10f, fraction)),
                    0.65f to Color.Transparent,
                ),
                FolioShapes.heroBleed,
            )
            .clickable(interactionSource = interaction, indication = null) { onOpen(item) }
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.space3,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            )
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                // The plate overhangs the plane's leading edge — a negative offset,
                // so the artwork sits in front of the surface holding it.
                Box(
                    modifier = Modifier
                        .offset(x = -(FolioTokens.gutter - FolioTokens.space1))
                ) {
                    // Manga plates load over the network and books load from a file:
                    // one switch, both in the same object language.
                    if (item.kind == HomeItemKind.MANGA && backend != null) {
                        MangaPlate(
                            backend = backend,
                            sourceId = item.sourceId,
                            thumbnailUrl = item.thumbnailUrl,
                            coverPath = item.coverPath,
                            width = FolioTokens.coverAnchor,
                            elevation = 16.dp,
                        )
                    } else {
                        FolioCoverPlate(
                            coverPath = item.coverPath,
                            title = item.title,
                            author = item.subtitle,
                            width = FolioTokens.coverAnchor,
                            halo = tint,
                            elevation = 16.dp,
                        )
                    }
                }
                Spacer(Modifier.width(FolioTokens.space2))
                // Matched to the plate's height and distributed, so the title sits at
                // the cover's head and the figure at its foot — no dead band between
                // them, which is what an unbalanced Column produced here.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(FolioTokens.coverAnchor * FolioTokens.coverAspect),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceHair)) {
                        FolioEyebrow("Reading now", accent = tint)
                        Text(
                            item.title,
                            style = FolioTheme.typography.headlineMedium,
                            color = colors.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                item.subtitle,
                                style = FolioTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // Progress as a figure at the composition's foot; the estimate is
                    // its caption and the seam below spans the full measure. Manga
                    // project off unread chapters instead of words, phrased the same
                    // way, so the anchor reads identically either way.
                    // The band the old composition left empty. `SpaceBetween` on a
                    // 222dp column with a two-line title and a one-line author opened
                    // a ~70dp hole in the middle of the card, which is why the anchor
                    // read as unfinished. It now carries the one fact the card was
                    // missing — when this was last picked up — over a hairline in the
                    // cover's own colour, which also ties the type back to the plate.
                    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                        FolioRule(accent = tint)
                        val meta = listOfNotNull(
                            lastActivityLabel(item.lastActivity),
                            if (item.kind == HomeItemKind.MANGA) item.caption else null,
                        )
                        if (meta.isNotEmpty()) {
                            Text(
                                meta.joinToString("  \u00b7  "),
                                style = FolioTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    FolioFigure(
                        value = "${(item.progress * 100).toInt()}",
                        unit = "%",
                        caption = item.estimate ?: "Just started",
                        accent = tint,
                        emphasis = FigureScale.Quiet,
                    )
                }
            }
            Spacer(Modifier.height(FolioTokens.space3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Continue",
                    style = FolioTheme.typography.labelLarge,
                    color = tint,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(FolioTokens.space3))
                // §2.6: one progress form in this view — the seam.
                FolioProgressBar(
                    progress = item.progress,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * **The ledger.** The quiet beat after the anchor: today's minutes and the streak
 * set as two small figures directly on the page — no container, no ring, nothing
 * competing. A hairline rule under them is the only structure, which is precisely
 * why the anchor above keeps all the weight.
 *
 * Met goals are marked by the figure switching to `accentStreak` and a small
 * ring appearing beside it, not by a coloured box: celebration should feel like
 * emphasis, not like a notification.
 *
 * Tapping opens Stats. Rule 8's exclusion notice keeps its own row beneath.
 */
@Composable
private fun LedgerStrip(state: HomeUiState, onOpenStats: () -> Unit, onOpenExclusions: () -> Unit) {
    val colors = FolioTheme.colors
    val met = state.goalMinutes > 0 && state.todayMinutes >= state.goalMinutes
    val goalFraction = if (state.goalMinutes > 0) {
        (state.todayMinutes.toFloat() / state.goalMinutes).coerceIn(0f, 1f)
    } else 0f
    Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenStats),
            verticalAlignment = Alignment.Bottom
        ) {
            FolioFigure(
                value = state.todayMinutes.toString(),
                unit = "of ${state.goalMinutes} min",
                label = "Today",
                accent = if (met) colors.accentStreak else colors.onSurface,
                emphasis = FigureScale.Quiet,
            )
            Spacer(Modifier.width(FolioTokens.spaceMovement))
            FolioFigure(
                value = if (state.streakDays > 0) state.streakDays.toString() else "—",
                unit = if (state.streakDays == 1) "day" else "days",
                label = "Streak",
                accent = if (state.streakDays > 0) colors.accentStreak else colors.onSurfaceVariant,
                emphasis = FigureScale.Quiet,
            )
            Spacer(Modifier.weight(1f))
            if (met) {
                ProgressRing(
                    progress = 1f,
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3f,
                    color = colors.accentStreak,
                    trackColor = colors.accentStreak.copy(alpha = 0.18f),
                )
            } else if (goalFraction > 0f) {
                ProgressRing(
                    progress = goalFraction,
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3f,
                    color = colors.accentProgress,
                    trackColor = colors.accentProgress.copy(alpha = 0.14f),
                )
            }
        }
        Spacer(Modifier.height(FolioTokens.space2))
        FolioRule()
        if (state.exclusionsActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenExclusions)
                    .padding(vertical = FolioTokens.space2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Some titles are excluded — review",
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * **A shelf.** Covers on a rail that runs off the trailing edge of the screen —
 * the row's content padding starts at the gutter and ends nowhere, so the shelf
 * visibly continues past the frame. That single decision is what separates a
 * shelf from a boxed carousel.
 *
 * Progress lives *on* the plate as a seam across its foot rather than as a ring
 * below it, so the covers form an unbroken line and the eye reads artwork first.
 *
 * §11.4: books and manga share the rail. The caption is the only place they
 * diverge — a book states its percentage, a manga states its backlog and how long
 * that backlog will take — and a manga keeps its overflow route to the source's
 * web page, which has no book equivalent.
 */
@Composable
private fun ContinueShelf(
    items: List<ReadingNowItem>,
    backend: MangaBackend?,
    onOpen: (ReadingNowItem) -> Unit,
    onOpenSourceWeb: (String) -> Unit
) {
    Column {
        FolioSectionHead(
            title = "Continue reading",
            modifier = Modifier.padding(horizontal = FolioTokens.gutter),
        )
        Spacer(Modifier.height(FolioTokens.space3))
        LazyRow(
            contentPadding = PaddingValues(start = FolioTokens.gutter, end = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            items(items.size) { index ->
                val entry = items[index]
                val isManga = entry.kind == HomeItemKind.MANGA
                val interaction = rememberFolioInteraction()
                var menuOpen by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .width(FolioTokens.coverShelf)
                        .folioPressable(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null
                        ) { onOpen(entry) }
                ) {
                    if (isManga && backend != null) {
                        MangaPlate(
                            backend = backend,
                            sourceId = entry.sourceId,
                            thumbnailUrl = entry.thumbnailUrl,
                            coverPath = entry.coverPath,
                            width = FolioTokens.coverShelf,
                            overlay = { ProgressSeam(entry.progress) },
                        )
                    } else {
                        FolioCoverPlate(
                            coverPath = entry.coverPath,
                            title = entry.title,
                            author = entry.subtitle,
                            width = FolioTokens.coverShelf,
                            small = true,
                            overlay = { ProgressSeam(entry.progress) },
                        )
                    }
                    Spacer(Modifier.height(FolioTokens.space2))
                    ShelfCaption(entry, isManga, menuOpen, { menuOpen = it }, onOpenSourceWeb)
                }
            }
        }
    }
}

/**
 * The two lines under a shelf plate: the title, then the one number that matters
 * for that kind. A book's percentage is its own progress; a manga's is its
 * backlog, so the manga caption is the projection ("12 unread · ~4 days") tinted
 * `accentDiscovery` — the same hue the rest of the manga surfaces use.
 *
 * The overflow lives here rather than on the plate so a mis-tap never opens a
 * browser instead of the reader.
 */
@Composable
private fun ShelfCaption(
    entry: ReadingNowItem,
    isManga: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenSourceWeb: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.caption ?: "${(entry.progress * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = if (isManga) {
                    FolioTheme.colors.accentDiscovery
                } else {
                    FolioTheme.colors.accentProgress
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val web = entry.webUrl
        if (isManga && web != null) {
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Open on ${entry.sourceName}",
                    tint = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onMenuOpenChange(true) }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    DropdownMenuItem(
                        text = { Text("Open on ${entry.sourceName}") },
                        onClick = {
                            onMenuOpenChange(false)
                            onOpenSourceWeb(web)
                        }
                    )
                }
            }
        }
    }
}

/** The plate's progress seam: 3dp across its foot, over a scrim so pale art still shows it. */
@Composable
private fun BoxScope.ProgressSeam(progress: Float) {
    if (progress <= 0f) return
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.Black.copy(alpha = 0.35f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .background(FolioTheme.colors.accentProgress)
        )
    }
}

/**
 * **Discovery shelf.** Same rail language as [ContinueShelf] but a step down in
 * scale and led by an eyebrow rather than a heading, because a suggestion should
 * not shout as loudly as the book you are actually reading. Tinted
 * `accentDiscovery` so the theme's own discovery hue marks the section.
 */
@Composable
private fun BecauseYouFinishedShelf(
    finishedTitle: String,
    candidates: List<Book>,
    onOpenBookDetail: (String) -> Unit
) {
    val discovery = FolioTheme.colors.accentDiscovery
    Column {
        Column(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            FolioEyebrow("Because you finished", accent = discovery)
            Spacer(Modifier.height(3.dp))
            Text(
                finishedTitle,
                style = FolioTheme.typography.titleLarge,
                color = FolioTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(FolioTokens.space3))
        LazyRow(
            contentPadding = PaddingValues(start = FolioTokens.gutter, end = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            items(candidates.size) { index ->
                val book = candidates[index]
                val interaction = rememberFolioInteraction()
                Column(
                    modifier = Modifier
                        .width(FolioTokens.coverInline * 1.25f)
                        .folioPressable(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null
                        ) { onOpenBookDetail(book.id) }
                ) {
                    FolioCoverPlate(
                        coverPath = book.coverPath,
                        title = book.title,
                        author = book.displayAuthor,
                        width = FolioTokens.coverInline * 1.25f,
                        shape = FolioShapes.plateSmall,
                        elevation = 5.dp,
                        small = true,
                    )
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        book.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * **The week.** The only chart on Home, and the only *sunken* surface — cut into
 * the page while the anchor floats above it, so the screen reads as having a
 * genuine top and bottom rather than one plane of cards.
 *
 * Edge-to-edge on purpose: the well spans the full width with only the type inset,
 * which gives the sparkline room and keeps the bottom of the screen from becoming
 * a fourth card. Rule 16 still holds — this is Home's own sparkline, not the Stats
 * bar chart.
 */
@Composable
private fun ThisWeekWell(state: HomeUiState, onOpenStats: () -> Unit, bottomInset: Dp = 0.dp) {
    val colors = FolioTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioSunken(FolioShapes.edgeStart)
            .clickable(onClick = onOpenStats)
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.gutter,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            )
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            FolioFigure(
                value = formatMinutes(state.week.sumOf { it.minutes }),
                label = "This week",
                emphasis = FigureScale.Quiet,
                accent = colors.accentProgress,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${state.startedThisWeek} started · ${state.finishedThisWeek} finished",
                style = FolioTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(FolioTokens.space3))
        WeekSparkline(
            week = state.week,
            modifier = Modifier.fillMaxWidth().height(FolioTokens.sparkHeight * 1.4f)
        )
        // The floating capsule's clearance, held *inside* the well. The page therefore
        // ends on a surface rather than on dead scroll, and the leaf sits in the band
        // to the leading side of the capsule as a closing mark.
        if (bottomInset > 0.dp) {
            Box(
                modifier = Modifier.fillMaxWidth().height(bottomInset),
                contentAlignment = Alignment.CenterStart,
            ) {
                FolioLogoMark(
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { alpha = 0.28f }
                )
            }
        }
    }
}

@Composable
private fun WeekSparkline(week: List<StatDay>, modifier: Modifier = Modifier) {
    val color = FolioTheme.colors.accentProgress
    // §13.5: the line trims in once per window (keyed on dates, not minutes),
    // drawn in the canvas phase so nothing recomposes per frame.
    val entry = rememberEntryState(week.map { it.date })
    Canvas(modifier = modifier) {
        if (week.size < 2) return@Canvas
        val progress = entry.value
        val max = week.maxOf { it.minutes }.coerceAtLeast(1L)
        val stepX = size.width / (week.size - 1)
        val points = week.mapIndexed { index, day ->
            Offset(index * stepX, size.height * (1f - day.minutes.toFloat() / max))
        }
        val areaPath = Path().apply {
            moveTo(0f, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(size.width, size.height)
            close()
        }
        val linePath = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        clipRect(right = size.width * progress) {
            drawPath(
                areaPath,
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent))
            )
            drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        // The dot marks today — the last day of the trailing week.
        if (progress >= 1f) points.last().let { drawCircle(color, radius = 3.dp.toPx(), center = it) }
    }
}

private fun formatMinutes(total: Long): String =
    if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"

/**
 * "When did I last pick this up?" in the fewest words that are still true.
 *
 * Null for an item that has never been opened, so the anchor's middle band stays out
 * of the way rather than claiming a date it does not have.
 */
private fun lastActivityLabel(instant: Instant): String? {
    if (instant == Instant.DISTANT_PAST) return null
    val days = (Clock.System.now() - instant).inWholeDays
    return when {
        days <= 0L -> "Read today"
        days == 1L -> "Read yesterday"
        days < 7L -> "Read $days days ago"
        days < 14L -> "Read last week"
        days < 60L -> "Read ${days / 7} weeks ago"
        days < 365L -> "Read ${days / 30} months ago"
        else -> "Read over a year ago"
    }
}
