@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.model.Book
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.COVER_HALO_STRENGTH_HERO
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioLogoMark
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSectionHead
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.FolioTabReselect
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.components.ReadingClimate
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioThemeRim
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.CLIMATE_LIGHT_MAX
import com.folio.reader.ui.components.lightFraction
import com.folio.reader.ui.components.phrase
import com.folio.reader.ui.components.readingClimate
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.rememberEntryState
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.tint
import com.folio.reader.ui.components.weekForecast
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.components.LocalGlassCapabilities
import com.folio.reader.ui.theme.FolioAmbientSource
import com.folio.reader.ui.theme.FolioCoverLightSource
import com.folio.reader.ui.components.rememberCoverLight
import com.folio.reader.ui.theme.folioLampSource
import com.folio.reader.ui.theme.FolioShapeFamily
import com.folio.reader.ui.theme.rememberFolioShape
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.LocalFolioPackId
import com.folio.reader.ui.theme.heroWashBorrow
import com.folio.reader.ui.theme.tameCover
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioLiquidGlass
import com.folio.reader.ui.theme.lightDirection
import com.folio.reader.ui.theme.paneFill
import com.folio.reader.ui.theme.rememberFolioPressFocal
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
    val motion = rememberMotionEnabled()
    // §13.5: the geometry-matched skeleton dissolves into the loaded content
    // rather than hard-cutting to it. Keyed on the loaded flag and motion-gated,
    // so reduce-motion still swaps instantly.
    Crossfade(
        targetState = state.loaded,
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "homeLoaded",
    ) { loaded ->
    when {
        !loaded -> HomeSkeleton(Modifier.padding(top = topInset))
        !state.hasBooks && !state.hasManga ->
            Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    headline = "Your library is empty — import an EPUB to start.",
                    action = { Button(onClick = onImportClick) { Text("Import a book") } }
                )
            }
        else -> {
            val listState = rememberLazyListState()
            // Re-tap on the Home nav item scrolls back to the anchor. Animated
            // when there is distance to cover; an instant hop when the list is
            // still composing (firstVisibleItemIndex unknown).
            LaunchedEffect(listState) {
                FolioTabReselect.events.collect { (route, _) ->
                    if (route == "home") {
                        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                            listState.animateScrollToItem(0)
                        }
                    }
                }
            }
            // §13.9 hero collapse: tracked 1:1 off the scroll offset — no spring,
            // no settle — over the first 160dp; reduce-motion keeps the hero full
            // size and simply scrolling away.
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
            // The reading weather, computed once for the whole page: it lights
            // the anchor, marks the ledger and phrases the week well's forecast.
            val climate = readingClimate(
                todayMinutes = state.todayMinutes,
                goalMinutes = state.goalMinutes,
                streakDays = state.streakDays,
                daysSinceLastRead = state.daysSinceLastRead,
            )
            // The hero tint is computed here so the host top bar can bleed the
            // same colour upward (§13.9); the hero itself re-derives it cheaply
            // from the accent cache. A manga with only a network thumbnail has no
            // local file to sample, so it lands on the fallback accent.
            val heroTint = if (state.coverTint && anchorItem != null) {
                rememberCoverAccent(anchorItem.coverPath, FolioTheme.colors.accentProgress)
            } else null
            val fallbackTint = FolioTheme.colors.accentProgress
            // The room is lit by the same cover, on its own switch: turning off
            // Home's *surface* tint need not turn off the environment, and vice
            // versa. Same cache key the hero above already samples, so this costs
            // no extra pixel work — the accent cache is keyed by cover path and
            // lives for the process.
            val ambientTint = if (state.ambientColor && anchorItem != null) {
                rememberCoverAccent(anchorItem.coverPath, fallbackTint)
            } else null
            FolioAmbientSource(ambientTint)
            // And the artwork's own light: where its brightness sits, what colour the
            // lit parts are, how much of it there is. This is what places the room's
            // lamp by the book instead of by its average.
            FolioCoverLightSource(
                if (state.ambientColor && anchorItem != null) {
                    rememberCoverLight(anchorItem.coverPath)
                } else {
                    null
                }
            )
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
                    // A tapped book cover opens its detail page, never the reader
                    // directly — the same rule the library shelf follows, so a cover
                    // means the same thing everywhere it appears. Reading starts from
                    // detail (tap the cover there). Opening straight into the reader
                    // from Home was the redundant "start reading on a thumbnail".
                    HomeItemKind.BOOK -> onOpenBookDetail(item.id)
                    HomeItemKind.MANGA -> {
                        val chapter = item.chapterId
                        if (chapter != null) onOpenMangaReader(item.id, chapter)
                        else onOpenMangaDetail(item.id)
                    }
                }
            }
            LazyColumn(
                state = listState,
                // §16: this is the backdrop the capsule and masthead blur. Every other
                // tab registers one and Home never did, so on Home the veil had nothing
                // to sample and painted its own `backgroundColor` ground instead — the
                // capsule read as a solid pill here and as glass everywhere else.
                modifier = Modifier.fillMaxSize().folioBackdropSource(),
                // No uniform arrangement: each block owns the space beneath it. No
                // bottom runway either — the closing well absorbs the floating
                // capsule's clearance itself (see [ThisWeekWell]).
                //
                // "Each block owns the space beneath it" is now true rather than
                // aspirational. Every gap below used to be `spaceMovement`, which
                // made the page a metronome: five equal bands that told the eye
                // nothing about what belonged with what. The gaps now group into
                // four movements, matching the crescendo this file's own header
                // comment describes:
                //
                //   I   the anchor · the ledger      (what am I reading?)
                //   II  continue · new chapters      (what am I partway through?)
                //   III discover · because-finished  (what next?)
                //   IV  the week well                (the closing rest)
                //
                // `spaceBeat` within a movement, `spaceMovement` between them.
                contentPadding = PaddingValues(top = topInset),
            ) {
                // ── Movement I — what am I reading right now ────────────────
                item {
                    ReadingNowAnchor(anchorItem, mangaBackend, collapse, heroTint, climate, openItem, onOpenLibrary)
                    Spacer(Modifier.height(FolioTokens.spaceBeat))
                }
                item {
                    HomeGlassCard(accent = FolioTheme.colors.accentStreak) { LedgerStrip(state, climate, onOpenStats, onOpenExclusions) }
                    // Movement I closes.
                    Spacer(Modifier.height(FolioTokens.spaceMovement))
                }
                // ── Movement II — pick up where you left off ───────────────
                // The shelf and "new chapters" answer one question between them
                // ("what am I partway through?"), so they are one movement with
                // two beats, and the break falls after them, where the page turns
                // from *resuming* to *discovering*.
                //
                // The gap is decided by **what actually renders**, not by position
                // in this file. Three of the five blocks here are conditional, and
                // two of them (new chapters, discover) need a manga backend — so on
                // a books-only library the naive version put the movement break on
                // a section that never rendered, and the continue shelf ended up a
                // single `spaceBeat` from "because you finished": a tight gap
                // joining two blocks that belong to *different* movements, which is
                // worse than the uniform spacing this is replacing. Each block
                // therefore asks whether its own movement still has a beat left to
                // run, and closes the movement itself when it does not.
                //
                // The shelf is the rest of the same ranked list — books and manga
                // interleaved by when they were last read, not grouped by format.
                val shelf = readingNow.drop(1)
                val hasNewChapters = state.newChapters.isNotEmpty() && mangaBackend != null
                val hasDiscover = state.discover.isNotEmpty() && mangaBackend != null
                val finishedTitle = state.becauseFinishedTitle
                val hasBecauseFinished = finishedTitle != null && state.candidates.size >= 2
                if (shelf.isNotEmpty()) {
                    item {
                        HomeGlassCard(accent = FolioTheme.colors.accentProgress) { ContinueShelf(shelf, mangaBackend, openItem, onOpenSourceWeb) }
                        Spacer(
                            Modifier.height(
                                // New chapters continues this movement; without it
                                // the shelf is the movement's last beat.
                                if (hasNewChapters) FolioTokens.spaceBeat
                                else FolioTokens.spaceMovement
                            )
                        )
                    }
                }
                // §11.4: "New chapters" sits after the books shelf; hidden at zero.
                if (hasNewChapters) {
                    item {
                        HomeGlassCard(accent = FolioTheme.colors.accentDiscovery) {
                            NewChaptersCard(state.newChapters, mangaBackend!!, onOpenMangaDetail)
                        }
                        // Movement II closes: resuming ends, discovery begins.
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                    }
                }
                // ── Movement III — find something new ──────────────────────
                // §11.4: Discover — LATEST from the most recently read manga's source.
                if (hasDiscover) {
                    item {
                        HomeGlassCard(accent = FolioTheme.colors.accentDiscovery) {
                            DiscoverCard(state.discover, mangaBackend!!, onOpenDiscover)
                        }
                        Spacer(
                            Modifier.height(
                                // "Because you finished" is this movement's second
                                // beat when it is present.
                                if (hasBecauseFinished) FolioTokens.spaceBeat
                                else FolioTokens.spaceMovement
                            )
                        )
                    }
                }
                if (finishedTitle != null && state.candidates.size >= 2) {
                    item {
                        HomeGlassCard(accent = FolioTheme.colors.accentAnnotation) {
                            BecauseYouFinishedShelf(finishedTitle, state.candidates, onOpenBookDetail)
                        }
                        // Movement III closes. The well is the closing rest.
                        Spacer(Modifier.height(FolioTokens.spaceMovement))
                    }
                }
                // ── Movement IV — the closing rest ─────────────────────────
                item { ThisWeekWell(state, climate, onOpenStats, LocalFolioBarInset.current) }
            }
        }
    }
    }
}

/**
 * The one surface every secondary Home block now shares.
 *
 * Home used to render most of its sections with no container at all — figures,
 * lists and rails sat straight on the page while only the anchor and the week
 * well were raised. Those bare blocks each took the room's light as whatever was
 * behind them, so the screen read as a patchwork rather than one material. This
 * wraps a block in the same glass pane (`folioRaised` + `paneFill()`) the anchor
 * and well already use, so every block is one consistent frame. `folioRaised`
 * clips to its shape, so an edge-running rail is cleanly contained by the card
 * instead of bleeding off the page.
 */
@Composable
internal fun HomeGlassCard(
    modifier: Modifier = Modifier,
    accent: Color = FolioTheme.colors.accentProgress,
    content: @Composable ColumnScope.() -> Unit,
) {
    val plane = FolioTheme.atmosphere.paneFill()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter)
            .folioRaised(
                shape = FolioShapes.hero,
                accent = accent,
                fill = plane,
                // Home's glass blocks are items in the page, not overlays: the room shows
                // through them but nothing passes behind them. The flagship hero below keeps
                // the full material — it is the one surface on this screen that genuinely
                // floats over the page.
                tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
            )
            .padding(FolioTokens.space3),
    ) {
        // Claim the content as nested. This is the hole the "boxes inside boxes" report came
        // through: the depth that quiets a nested surface is carried by a CompositionLocal, and
        // a *modifier* cannot provide one to the tree it decorates (see LocalFolioSurfaceDepth's
        // own note) — it is composed in the parent's scope. So every container that applies a
        // material by modifier and then hosts arbitrary content has to say so explicitly, or
        // everything inside it believes it is sitting on the page and spends the full material:
        // its own shadow, its own full-strength hairline, its own bevel. Three of Home's cards
        // are built this way, which is exactly where the nested rectangles were visible.
        //
        // Publishing the plane too means a nested surface is made of *this* card's material
        // rather than its own tier, so the division between them is never two different
        // materials meeting. See LocalFolioSurfaceFill.
        com.folio.reader.ui.theme.FolioSurfaceContents(plane) {
            Column(content = content)
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
    climate: ReadingClimate?,
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
    // The jacket's hue, clamped to the palette's own chroma: a sampled cover carries
    // its printing's violence along with its hue, and it is the violence that reads
    // as the thumbnail overwriting the theme. Tamed once so every channel inherits it.
    val coverTint = heroTint?.let { tameCover(it, colors.primary) }
    val tint = coverTint ?: colors.accentProgress
    val fraction = collapse.value
    // The reading weather burns through the plane's lighting: the cover still
    // owns the hue (§13.3), but the climate scales how brightly it is lit — a
    // warm spell lifts the gradient, a dry spell lets the room go dim. The
    // collapse fade below keeps its own calibrated floor.
    val climateLight = climate?.lightFraction() ?: 0.30f
    // The wash's allowance, scaled to the surface it lands on. Paper's pane is already
    // 55% white and shrugs a bright jacket off; a near-black pane takes the same
    // fraction as a brown wash over the whole card, so the dark face is given a
    // fraction sized for the shift it produces rather than the fraction itself.
    val washBorrow = heroWashBorrow(FolioTheme.atmosphere.isDark)
    // The wash's starting alpha at the plane's lit corner: its budgeted share scaled by
    // the climate (a warm spell lights it, a dry spell dims it) and faded as the hero
    // collapses. Pulled out so the eased gradient stops below can taper from it.
    val washAlpha = androidx.compose.ui.util.lerp(
        washBorrow * (climateLight / CLIMATE_LIGHT_MAX),
        washBorrow * 0.35f,
        fraction,
    )
    // §13.7: the room's virtual-sun direction, so the liquid-glass specular lands
    // on the same edge the raised rim catches.
    val heroLight = LocalFolioDaylight.current.lightDirection()
    val interaction = rememberFolioInteraction()
    // Where the thumb lands on the hero. The AGSL film gathers its specular here; the
    // raised sheen and the mesh under it are daylight-only, as they were.
    val heroFocal = rememberFolioPressFocal(interaction)
    // The anchor's silhouette is its own. The hero family varies the two wide corners
    // per book, so a returning reader's page is not the same rectangle it was
    // yesterday's, while the class stays unmistakably a hero (§12.1 Rule 13's blur
    // test) and the leading edge stays square — that is where the cover bleeds off the
    // plane, and a radius there would open the field behind it. Seeded on the id, not
    // the list position, because the anchor changes book on every finish and a
    // position-seeded shape would restyle the whole page to mark the occasion.
    val heroShape = rememberFolioShape(item.id.hashCode(), FolioShapeFamily.heroBleed)
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
                shape = heroShape,
                accent = tint,
                elevation = FolioTokens.elevationRaised,
                // A pane, not a panel: the room shows through the hero, which is what
                // the refraction along its rim has been trying to say. The shadow, the
                // rim and the sheen are unchanged, so the card keeps its depth.
                fill = FolioTheme.atmosphere.paneFill(),
            )
            // §13.7 / M7: the AGSL liquid-glass on the app's flagship hero — a
            // film under the mesh and the gradient (and so under the cover and
            // type), and a gel that bends the *room* at the hero's rim: the page
            // behind the card, reproduced from the field's own closed form and
            // rolled over at its edge. API 33+, the liquid-glass preference on and
            // a backdrop published at the root, or it is a no-op and the mesh +
            // raised sheen carry the surface. Lit by the room's own virtual sun so
            // its specular agrees with the rim catch.
            //
            // R1: the gel is on. It was held off because the hero's backdrop was a
            // smooth vertical ramp plus three soft pools, where a lens rim has no
            // high-frequency detail to separate and the dispersion lands three samples
            // on one colour — a painted rectangle, not glass. The guilloche now
            // engraves a figure on that ramp, which is the detail the refraction was
            // always waiting for, so the hero is finally a surface it pays on.
            .folioLiquidGlass(
                accent = tint,
                highlight = FolioTheme.atmosphere.rimLight,
                lightX = heroLight.first,
                lightY = heroLight.second,
                enabled = LocalGlassCapabilities.current.specular,
                refractBackdrop = true,
                focal = heroFocal,
                shape = heroShape,
                // The engraving this hero stands on, asked for rather than assumed: the gel
                // evaluates the plate's own figure so the ribbons it bends are the ribbons
                // the page painted.
                platePackId = LocalFolioPackId.current,
                plateFieldTop = FolioTheme.atmosphere.fieldTop,
                plateFieldBottom = FolioTheme.atmosphere.fieldBottom,
            )
            // The silk is no longer drawn here: it is the app's single ground plane now
            // (MainActivity's `folioSilkBackground`), showing through this hero like every
            // other surface. The hero keeps only its own glass, wash and rim.
            .background(
                // Eased falloff, not a two-stop fade. The old wash reached transparent
                // by 0.65 of the diagonal and then held flat; the slope change where the
                // fade met the flat drew a faint rectangle edge across the plane — a Mach
                // band the eye reads as a box inside the card. These stops taper the
                // slope to zero at the far corner, so the wash vanishes without ever
                // laying down a line. Same top-corner light origin and the same ceiling
                // alpha (`washAlpha`); only the tail's shape changed.
                Brush.linearGradient(
                    0f to tint.copy(alpha = washAlpha),
                    0.35f to tint.copy(alpha = washAlpha * 0.45f),
                    0.6f to tint.copy(alpha = washAlpha * 0.16f),
                    0.82f to tint.copy(alpha = washAlpha * 0.04f),
                    1f to Color.Transparent,
                ),
                heroShape,
            )
            // The anchor's themed rim: a light travelling the plane's own
            // silhouette in the cover's hue against the discovery counter-hue.
            // Last of the background passes so it sits on the glass, and behind
            // the content so the overhanging plate occludes the arc under it.
            .folioThemeRim(
                shape = heroShape,
                counterAccent = colors.accentDiscovery,
                // No velocity: this hero's scroll is a list offset reported through
                // `setProgress`, not consumed deltas, so a rate measured here would
                // be a rate of something else. Progress still parks the light.
                parkedAt = item.progress,
            )
            .clickable(interactionSource = interaction, indication = null) { onOpen(item) }
            .padding(
                start = FolioTokens.gutter,
                end = FolioTokens.space3,
                top = FolioTokens.space3,
                bottom = FolioTokens.space3,
            )
    ) {
        // The flagship hero is a surface hosting arbitrary content, so it has to claim that
        // content as nested for the same reason HomeGlassCard does: a modifier cannot provide
        // a CompositionLocal to the tree it decorates. Without this, the cover plate, the
        // progress seam and the caption row all believe they are sitting on the page and each
        // spends a full edge inside the hero's own — the box-inside-a-box in the report.
        com.folio.reader.ui.theme.FolioSurfaceContents(FolioTheme.atmosphere.paneFill()) {
        Column {
            // The card's masthead. "Reading now" used to be the first line of the
            // text column, tucked beside the cover where it competed with the title
            // for the same leading edge and read as a caption *to* the title rather
            // than a label *for* the card. Lifted out, it spans the plane's full
            // measure and names the whole composition: the eye meets "what this card
            // is", then the artwork, then the title — instead of three stacked text
            // sizes arriving at once in one narrow column.
            //
            // It also buys the title room. The text column beside the cover is a
            // fixed 222dp (`coverAnchor * coverAspect`) and the eyebrow was spending
            // ~16dp of it, which is part of what pushed a long three-line title into
            // an ellipsis. The outer Column is a plain stack, so adding a child here
            // cannot disturb the `SpaceBetween` distribution inside the text column —
            // that still has its three blocks and keeps the dead-band fix below.
            // The label names the card, it does not report the book, so it wears the
            // *discovery* accent rather than the cover's own tint. One variable used
            // to drive the eyebrow, the rule, the figure, the chevron and the seam,
            // which is how the largest card on the screen ended up a single colour at
            // five alphas. The three hues here are the same three the ledger and the
            // week well use below, so the page reads as one coordinated scheme and
            // not as one hue.
            FolioEyebrow("Reading now", accent = colors.accentDiscovery)
            Spacer(Modifier.height(FolioTokens.space2))
            Row(verticalAlignment = Alignment.Top) {
                // The plate overhangs the plane's leading edge — a negative offset,
                // so the artwork sits in front of the surface holding it.
                Box(
                    modifier = Modifier
                        .offset(x = -(FolioTokens.gutter - FolioTokens.space1))
                        // The room is lit from this artwork: the lamp's centre is the
                        // cover's own, so the light reads as coming off the book.
                        .folioLampSource()
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
                            elevation = FolioTokens.elevationRaised,
                        )
                    } else {
                        FolioCoverPlate(
                            coverPath = item.coverPath,
                            title = item.title,
                            author = item.subtitle,
                            width = FolioTokens.coverAnchor,
                            halo = tint,
                            // Named, not defaulted: this is the only plate whose halo
                            // lands on a card face instead of in the room, and leaving
                            // it to the default drew paper's strength on any theme.
                            haloStrength = COVER_HALO_STRENGTH_HERO,
                            elevation = FolioTokens.elevationRaised,
                            // §17: Home's anchor hands the cover to the book detail
                            // (or the manga detail) the same way the shelves do, so
                            // the morph is available from every surface a cover
                            // appears on rather than only from the Library tab.
                            //
                            // `contentSize`: Home's anchor is `coverAnchor` (148dp)
                            // and every destination is smaller, so this is the side
                            // that hands a size change over. Under the API default
                            // the cover keeps its own size inside shrinking bounds and
                            // snaps into place at the end — `contentSize` keeps the
                            // artwork the size of the box around it.
                            modifier = Modifier.sharedElementOrNoop(
                                FolioSharedKeys.bookCover(item.id),
                                placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
                            ),
                            // The title beside it is paired, so the fallback must not
                            // repeat it.
                            suppressFallbackText = true,
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
                        Text(
                            item.title,
                            style = FolioTheme.typography.headlineMedium,
                            color = colors.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.sharedTextOrNoop(
                                FolioSharedKeys.bookTitle(item.id)
                            )
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
                        // The seam under the meta line is structure, not the book: it
                        // takes the streak accent, which is the same hue the ledger's
                        // figures and the goal ring wear a few hundred pixels below.
                        FolioRule(accent = colors.accentStreak)
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
                        // `Standard`, not `Quiet`. This is the one number the anchor
                        // exists to report — "how far into this book am I" — and it
                        // was set at the *smallest* of the three scales
                        // (`headlineSmall`, 24sp), identical to the meta line above it
                        // and to the ledger figures below the card. A key figure drawn
                        // at supporting-text size is why this composition read as
                        // evenly weighted: nothing in it was allowed to be big, so the
                        // eye had no destination after the cover.
                        //
                        // Be precise about what this costs, because the three scales
                        // are coarse and there is nothing between them: `Standard` is
                        // `displaySmall` at **36sp**, which is *larger* than the
                        // title's `headlineMedium` (28sp) — so by glyph height the
                        // percentage now outsizes the book's name. That is accepted
                        // rather than overlooked, because type size is not visual mass:
                        // the title is a two-to-three-line block and this is a two-digit
                        // numeral, so the title still holds far more area and keeps the
                        // card's subject. What the step buys is a single hard focal
                        // point against that soft block — the magazine "big numeral"
                        // treatment — which is the contrast the anchor had none of.
                        //
                        // Deliberately not `Hero` (`displayMedium`, 45sp): at that size
                        // the numeral beats the title on mass as well as on height, and
                        // a progress readout would genuinely outrank the book.
                        emphasis = FigureScale.Standard,
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
}

/**
 * **The ledger.** The quiet beat after the anchor: today's minutes and the streak
 * set as two small figures directly on the page — no container, nothing competing.
 * A hairline rule under them is the only structure, which is precisely why the
 * anchor above keeps all the weight. The goal ring is the one addition, and it stays
 * absent until there is progress to show.
 *
 * Met goals are marked by the figure switching to `accentStreak` and a small
 * ring appearing beside it, not by a coloured box: celebration should feel like
 * emphasis, not like a notification.
 *
 * The reading weather rides the rule's trailing edge — one small phrase in the
 * climate's own tint (the streak accent in a warm spell, dimmed toward ink in a
 * dry one), so the page states its climate in a word or two rather than another
 * figure. Absent when there is no history to have weather from.
 *
 * Tapping opens Stats. Rule 8's exclusion notice keeps its own row beneath.
 */
@Composable
private fun LedgerStrip(
    state: HomeUiState,
    climate: ReadingClimate?,
    onOpenStats: () -> Unit,
    onOpenExclusions: () -> Unit
) {
    val colors = FolioTheme.colors
    val motion = rememberMotionEnabled()
    val met = state.goalMinutes > 0 && state.todayMinutes >= state.goalMinutes
    val goalFraction = if (state.goalMinutes > 0) {
        (state.todayMinutes.toFloat() / state.goalMinutes).coerceIn(0f, 1f)
    } else 0f
    // The ledger settles rather than snaps: the minutes count up toward today's
    // total, and the single goal ring fills toward the goal fraction while its
    // colour warms to the streak accent as the goal is met — instead of swapping
    // to a separate full ring and flipping the accent instantly. All motion-gated,
    // so reduce-motion lands on the final value and hue at once.
    val animatedTodayMinutes by animateIntAsState(
        targetValue = state.todayMinutes.toInt(),
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "todayMinutes",
    )
    val ringProgress by animateFloatAsState(
        targetValue = goalFraction,
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "goalRingProgress",
    )
    val ringColor by animateColorAsState(
        targetValue = if (met) (climate?.tint() ?: colors.accentStreak)
        else (climate?.tint() ?: colors.accentProgress),
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "goalRingColor",
    )
    val ringTrack by animateColorAsState(
        targetValue = if (met) colors.accentStreak.copy(alpha = 0.18f)
        else colors.accentProgress.copy(alpha = 0.14f),
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "goalRingTrack",
    )
    val statsInteraction = rememberFolioInteraction()
    val exclusionsInteraction = rememberFolioInteraction()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioPressable(statsInteraction, scaleTo = 0.99f)
                .clickable(
                    interactionSource = statsInteraction,
                    indication = null,
                    onClick = onOpenStats,
                ),
            verticalAlignment = Alignment.Bottom
        ) {
            FolioFigure(
                value = animatedTodayMinutes.toString(),
                unit = "of ${state.goalMinutes} min",
                label = "Today",
                accent = if (met) colors.accentStreak else colors.onSurface,
                emphasis = FigureScale.Quiet,
            )
            // The strip reads as two poles rather than a left-hand huddle. Both
            // figures used to sit together at the leading edge with the ring shoved
            // to the far right by a weight spacer, which left a wide dead band in
            // the middle and made the ring look stranded. The streak belongs with
            // the ring — they are the same idea, continuity against today's goal —
            // so the elastic gap now sits *between* the two ideas instead of after
            // both of them: "today" anchors the leading edge, "streak + ring" forms
            // the trailing cluster, and the space between them is the composition
            // rather than leftover room.
            //
            // Both figures stay `Quiet` on purpose. This block is the deliberate
            // rest after the anchor (see the header note), so the change is where
            // things sit, never how loud they are — the hero keeps all the weight.
            Spacer(Modifier.weight(1f))
            FolioFigure(
                value = if (state.streakDays > 0) state.streakDays.toString() else "—",
                unit = if (state.streakDays == 1) "day" else "days",
                label = "Streak",
                accent = if (state.streakDays > 0) colors.accentStreak else colors.onSurfaceVariant,
                emphasis = FigureScale.Quiet,
            )
            if (met || goalFraction > 0f) {
                Spacer(Modifier.width(FolioTokens.space2))
                ProgressRing(
                    progress = ringProgress,
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3f,
                    color = ringColor,
                    trackColor = ringTrack,
                )
            }
        }
        Spacer(Modifier.height(FolioTokens.space2))
        FolioRule()
        if (climate != null) {
            Text(
                climate.phrase(state.streakDays, state.daysSinceLastRead ?: 0),
                style = FolioTheme.typography.labelSmall,
                color = climate.tint(),
                modifier = Modifier.padding(top = FolioTokens.spaceHair),
            )
        }
        if (state.exclusionsActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .folioPressable(exclusionsInteraction, scaleTo = 0.99f)
                    .clickable(
                        interactionSource = exclusionsInteraction,
                        indication = null,
                        onClick = onOpenExclusions,
                    )
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
    Column(modifier = Modifier.fillMaxWidth()) {
        FolioSectionHead(
            title = "Continue reading",
        )
        Spacer(Modifier.height(FolioTokens.space3))
        LazyRow(
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            items(items.size, key = { "${items[it].kind}:${items[it].id}" }) { index ->
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
                            // `contentSize` for the same reason the anchor above
                            // uses it: `coverShelf` is 84dp and the detail header's
                            // plate is `coverFeature` (112dp), so this pair crosses a
                            // size change and the cover must resize with its box
                            // rather than after it.
                            modifier = Modifier.sharedElementOrNoop(
                                FolioSharedKeys.bookCover(entry.id),
                                placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
                            ),
                            suppressFallbackText = true,
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
                // >=48dp thumb target around the 18dp glyph, so the overflow is
                // reliably tappable rather than an 18dp pinpoint.
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .clickable { onMenuOpenChange(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Open on ${entry.sourceName}",
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                com.folio.reader.ui.components.FolioMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
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
            // Theme-aware scrim (matches the library grid/document seams) so the
            // seam stays visible on dark covers and dark themes, where a fixed
            // black track vanished.
            .background(FolioTheme.colors.onSurface.copy(alpha = 0.15f))
            .semantics {
                contentDescription = "${(progress * 100).toInt()}% read"
            }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            items(candidates.size, key = { candidates[it].id }) { index ->
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
                        elevation = FolioTokens.elevationPanel,
                        small = true,
                        // This rail's tap goes to the book detail, so its plate
                        // hands on like the shelf's does. `contentSize` because
                        // `coverInline * 1.25f` is smaller than the detail header's
                        // `coverFeature`, so this pair crosses a size change.
                        modifier = Modifier.sharedElementOrNoop(
                            FolioSharedKeys.bookCover(book.id),
                            placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
                        ),
                        suppressFallbackText = true,
                    )
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        book.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedTextOrNoop(
                            FolioSharedKeys.bookTitle(book.id)
                        ),
                    )
                }
            }
        }
    }
}

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
