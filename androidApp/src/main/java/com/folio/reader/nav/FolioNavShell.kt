package com.folio.reader.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.folio.reader.ui.components.FolioTabReselect
import com.folio.reader.ui.components.FolioEdgeRole
import com.folio.reader.ui.components.folioSoftFill
import com.folio.reader.ui.components.folioDestinationAccent
import com.folio.reader.ui.components.folioFieldBottom
import com.folio.reader.ui.components.folioGlassPress
import com.folio.reader.ui.components.folioNavGlass
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.GlassSpec
import com.folio.reader.ui.components.chromeFill
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassBlurred
import com.folio.reader.ui.components.rememberFolioChromeMaterial
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.fillForm
import com.folio.reader.ui.theme.folioGuillocheQuiet
import com.folio.reader.ui.theme.folioSheenGain
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.navCapsuleFill
import com.folio.reader.ui.theme.rememberFolioHaptics
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.narration.narrationSwipeToDismiss

/**
 * Navigation as a **floating capsule** rather than a full-width Material bar.
 *
 * Why this is the right call here:
 *  - the page's atmosphere runs around it, so the app reads as one continuous
 *    environment instead of content-plus-toolbar;
 *  - it is glass (`folioVeil`) — the same material as everything else that sits
 *    *over* content: sheets, bars, panels;
 *  - the active item is marked by an accent pill **and** by its label appearing,
 *    so selection is legible by shape and by presence rather than by a few
 *    percent of tint.
 *
 * The capsule is anchored over the destination rather than occupying a layout
 * row, and the destination keeps its full height: the page passes *behind* the
 * glass. The runway it needs is published as [LocalFolioBarInset] so scrolling
 * surfaces can add it to their own `contentPadding`; padding the whole
 * destination instead left a dead band the capsule sat on, which is the opposite
 * of floating.
 *
 * `content()` stays at a single stable slot across bar↔non-bar transitions:
 * rendering the NavHost in two branches recreated it on every push and wiped each
 * destination's `rememberSaveable` state.
 */
@Composable
fun FolioNavShell(
    navController: NavHostController,
    showBottomBar: Boolean,
    modifier: Modifier = Modifier,
    // §16 predictive back: 0 at rest, 1 at a fully-swiped back gesture. Drives
    // the capsule's recede; inert below API 33.
    backProgress: Float = 0f,
    content: @Composable () -> Unit
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: ""
    val narration = com.folio.reader.narration.LocalNarrationController.current
    val narrationState = narration?.state?.collectAsState()?.value
    val density = LocalDensity.current
    var narrationHeight by remember { mutableStateOf(76.dp) }
    val showNarration = narrationState?.hasBook == true && !currentRoute.startsWith("reader/") &&
        navController.currentBackStackEntry?.arguments?.getString("category") != "narration"
    // The runway the capsule needs: the OS gesture/nav inset, the capsule itself
    // and the air on both sides of it. Reserved once here so no screen has to
    // know a floating bar exists.
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomInset =
        systemBottomInset + FolioTokens.navFloatHeight + FolioTokens.navFloatInset * 2

    // Route tracking for the glass "stale backdrop" window on pushes (detail/reader): the incoming
    // screen's hazeSource registers a frame after the outgoing layer drops, so the capsule would
    // blur the previous screen for that gap. Short, capsule-only window; tab↔tab is left alone.
    val tabRoutes = remember { folioNavBarItems.map { it.route }.toSet() }
    fun isTabSurface(route: String) = route in tabRoutes || route.startsWith("settings/")
    var suppressBlur by remember { mutableStateOf(false) }
    var previousRoute by remember { mutableStateOf(currentRoute) }
    LaunchedEffect(currentRoute) {
        val from = previousRoute
        previousRoute = currentRoute
        if (from == currentRoute || (isTabSurface(from) && isTabSurface(currentRoute))) {
            return@LaunchedEffect
        }
        suppressBlur = true
        kotlinx.coroutines.delay(120)
        suppressBlur = false
    }

    // The room is last-writer-wins by design and a screen's declaration deliberately
    // survives its own dispose (see FolioAmbientTint) — so a book or manga detail's
    // preview tint kept colouring whichever tab came next, and More's bar read as
    // "whatever I visited last". Re-declaring the room's standing colour on every route
    // change puts every non-featuring destination (Stats, More, the books shelf) back
    // into the anchor's colour, while a screen that declares its own re-declares right
    // after this and still wins while it is visited. Null holder (ambient colour off,
    // desktop) makes this a no-op.
    val ambientTint = com.folio.reader.ui.theme.LocalFolioAmbientTint.current
    LaunchedEffect(currentRoute, ambientTint) {
        ambientTint?.to(ambientTint.canonical)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // §16 liquid glass: the backdrop registry is provided by the host
        // (MainActivity) so the status banner — a sibling of this shell — sees it
        // too. Screens attach the source itself to their scrolling child
        // (folioBackdropSource), never to an ancestor of the chrome: the
        // mastheads must sample the page, not themselves. A screen that never
        // attaches a source (the EPUB reader, over a native WebView surface)
        // leaves the registry empty and the glass falls back to specular + grain.
        // One stable slot for the destination, at full height. The capsule is a
        // sibling overlay *over* it, so the page runs behind the glass instead of
        // stopping at a reserved band — that band is what made the capsule read
        // as a bar with a margin, and it sliced shelves off mid-cover.
        //
        // The runway is published instead of imposed: scrolling surfaces add
        // LocalFolioBarInset to their own contentPadding, so their last row still
        // clears the glass while everything above it passes underneath.
        CompositionLocalProvider(
            LocalFolioBarInset provides (if (showBottomBar) contentBottomInset else if (showNarration) systemBottomInset + 8.dp else 0.dp) +
                if (showNarration) narrationHeight else 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }

        if (showNarration && narration != null) {
            com.folio.reader.ui.components.FolioGlassPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)
                .padding(bottom = if (showBottomBar) contentBottomInset else systemBottomInset + 8.dp)
                .narrationSwipeToDismiss(narrationState?.bookId, narration::stop),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                com.folio.reader.narration.NarrationTransport(narration, {
                    narrationState?.bookId?.let { id -> navController.navigate(FolioDestination.reader(id,
                        spine = narrationState.position?.spineIndex, locator = narrationState.position?.contentLocator)) { launchSingleTop = true } }
                }, modifier = Modifier.onSizeChanged { size -> narrationHeight = with(density) { size.height.toDp() + 12.dp } })
            }
        }
        if (showBottomBar) {
            // §16 glass tier: when the capsule is actually blurring the page
            // beneath it, its fill steps down from the designed 0.9 to ~0.60 —
            // blur is what fixes the text bleed-through the 0.9 was calibrated
            // against, so the page can finally show through the material and the
            // capsule reads as liquid glass instead of the same pill with better
            // anti-aliasing. No blur (pref off, low RAM, no backdrop): the
            // designed alpha, unchanged.
            //
            // On a destination change the new screen's hazeSource registers a
            // frame after the old one's backdrop layer is dropped, and the effect
            // samples the stale layer in that gap — the strip behind the capsule
            // showed the previous screen ("renders a bit later than the rest").
            //
            // Both the *fill* and the *blur layer* have to stand down for that
            // window, which is why this drives `glass.blurEnabled` on the veil
            // rather than only the alpha below. Previously it only lowered the
            // alpha while `folioVeil` independently recomputed
            // `caps.blur && backdrop != null` (still true) and kept blurring the
            // old screen underneath the opaque fill — the fill arrived on time but
            // the material behind it was a frame stale, which is exactly the
            // "background renders later" the reader sees.
            //
            // Only routes need this. A shelf swap inside one screen dissolves *in*
            // the registered source rather than swapping the node that owns it, so
            // there is never a stale layer to sample — and holding the blur off for
            // a mode switch would only add a capsule that goes opaque and clears.
            //
            // **Keep this window short.** It is the stale-backdrop gap, not the
            // transition: the material only has to be off while the *old* layer is
            // still registered. Widening it to cover the whole cross-fade (tried,
            // to save the backdrop recording's ~5ms a frame) makes the reader watch
            // the masthead and the capsule lose their glass and then regain it,
            // because at these fills the blur is what makes them read as material
            // rather than as a transparent strip. The frames are not worth it.
            // Tab↔tab keeps ONE stable backdrop — MainActivity provides the
            // registry once for all four tabs — so there is never a stale layer
            // to hide when the reader taps between Home/Library/…/More. Firing
            // the window there only snapped the capsule from blurred 0.60 glass
            // to a solid 0.90 pill and back for 120ms: the flash. The window is
            // for *pushes* (detail, reader) whose new hazeSource registers a
            // frame late; those are the non-tab-surface routes. Tab↔tab is handled
            // on the page side instead (see `tabSwapSuppress` hoisted above): the
            // capsule keeps its backdrop through a tab swap, only the mastheads drop it.
            // The chrome's fill alpha, read through the chrome seam rather than through the
            // capsule's own accessor. Byte-for-byte the same number — `chromeFill` *is* a
            // `navCapsuleFill` call — but it is now the same call the masthead makes, so the
            // two ends of the window cannot be given different alphas by anyone editing one
            // end. See FolioChromeMaterial.
            val capsuleFill = FolioTheme.surfaceOpacity.chromeFill(glassBlurred() && !suppressBlur)
            val selectedIndex = folioNavBarItems.indexOfFirst { item ->
                currentRoute == item.route ||
                    (item.route == FolioRoutes.MORE && currentRoute.startsWith("settings/"))
            }
            val atmos = FolioTheme.atmosphere
            // The same material the masthead is made of — one definition for both ends of the
            // window, see FolioChromeMaterial. The capsule keeps its own geometry (the pill, the
            // insets, the selection band) and its own wash mechanism; it takes only the colour.
            val chrome = rememberFolioChromeMaterial()
            // The glass this capsule is made of *in the room the reader is standing
            // in*: the palette's own chrome plane with the colour of the book being read
            // folded into its hue by the field model, at the atmosphere's own strength.
            //
            // It asks the shared chrome material, not `folioBarGlass`. Asking the bar was the
            // bug: the wash below is `BlendMode.Color`, which keeps the destination's luminance
            // and takes the source's hue and chroma outright, so the capsule rendered as the top
            // bar's hue at the capsule's lightness — and while `barGlassFor` still carried the
            // chrome accent's pull, its result never equalled `atmos.barGlass`, so the skip
            // branch below never fired either. The capsule was dyed with the masthead's colour
            // on every screen, lit or not, which is most of why the two ends of the window read
            // as one piece of furniture.
            val roomGlass = chrome.glass
            // A soft dissolve under the capsule, in the page's own field colour: content
            // scrolling out at the bottom fades instead of being cut off by the glass.
            // It is the same idea as the masthead gaining glass on scroll, at the other
            // end of the page — and it now asks the field model for its lower endpoint
            // rather than holding the palette's, so the page thickens toward the colour
            // the ground plane actually has. Same 0.80 alpha; only the hue moves.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(contentBottomInset)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to folioFieldBottom(atmos).copy(alpha = 0.80f),
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // §16 predictive back: the capsule recedes as the back
                    // gesture grows — the chrome folding out of the way of the
                    // page the reader is heading back to.
                    .graphicsLayer {
                        alpha = 1f - (backProgress * 0.6f).coerceIn(0f, 1f)
                        val scale = 1f - (backProgress * 0.10f).coerceIn(0f, 1f)
                        scaleX = scale
                        scaleY = scale
                    }
                    // The keyboard pushes the capsule up instead of covering it
                    // (edge-to-edge + adjustResize alone left it under the IME).
                    .imePadding()
                    // Bottom: OS gesture bar. Sides: display cutouts in landscape,
                    // then the app's own inset so the capsule never touches an edge.
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(
                        horizontal = FolioTokens.space3,
                        vertical = FolioTokens.navFloatInset,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        // Capped so the capsule stays a capsule on tablets and
                        // large windows instead of stretching into a bar again.
                        .widthIn(max = FolioTokens.navFloatMaxWidth)
                        // The capsule is glass over the page, and the page now carries a
                        // *vibrant* guilloche. Left alone, that bold web shows straight through
                        // the translucent pill and reads as a blocky texture — the same thing the
                        // hero avoids. Registering the capsule as a quiet region collapses the
                        // plate under its bounds, so the glass has a calm field to refract.
                        .folioGuillocheQuiet()
                        // A true pill (radius = half the height) and genuinely
                        // translucent: the page moving underneath is what tells
                        // the eye this is glass floating over it rather than a rounded
                        // slab parked at the bottom of the window.
                        // The preference *is* the fill alpha; its default (0.9) is the
                        // designed capsule, so 100% is a solid pill and the floor is a
                        // whisper of one — and under a real blur the tier thins it
                        // further. See FolioSurfaceOpacity.
                        .folioVeil(
                            FolioShapes.pill,
                            // The capsule is *chrome*, so it is made of the palette's chrome
                            // plane rather than of `veilFill`. `navigationSurface` is authored
                            // for all eighteen faces — a deliberate step between the card plane
                            // and the page plane — and until now nothing read it, so the one
                            // piece of furniture that is on screen at all times was painted from
                            // the same role as the content scrolling behind it. The alpha is
                            // still the preference's (`capsuleFill`), so this changes what the
                            // capsule is made of and never how solid it is.
                            fillColor = chrome.plane,
                            fillAlpha = capsuleFill,
                            // §16 cost model: the capsule is small, high-attention chrome
                            // that is on screen on every tab for the life of the session, so
                            // it takes the `control` class budget (0.5 — a quarter of the fill
                            // cost) rather than the coarser pane budget every card uses. See
                            // FolioGlassCost.
                            glass = GlassSpec.Control.copy(blurEnabled = !suppressBlur),
                            weight = chrome.weight,
                            // §18 — the capsule's edge is chrome's edge, which is the one
                            // the user named last ("even the bottom nav bar"). Its pill
                            // boundary was a 1dp hairline sitting exactly on the alpha
                            // discontinuity, with the outer half of a 3.5dp lit bevel
                            // painting a bright halo *outside* it. `Chrome` is the ladder's
                            // longest ramp and faintest rim, because this is the one
                            // surface that is on screen over live content at all times.
                            // See FolioEdgeRole.
                            edge = com.folio.reader.ui.components.FolioEdgeRole.Chrome,
                        )
                        // The capsule is glass *in* a room, so the room shows in it.
                        // The material underneath is the chrome plane (see `fillColor` above);
                        // the *book's* colour goes over it as a hue-only wash instead of into
                        // the fill, because the fill is a palette decision and the ambient is a
                        // runtime one. `BlendMode.Color` takes this colour's hue and chroma and
                        // the surface's own luminance, so the glass gains the book's colour and
                        // not a percent of opacity, and with no tint the wash *is* the palette's
                        // own plane — so the layer is skipped and an unlit tree draws what it
                        // always drew.
                        //
                        // The skip test compares against `navigationSurface` because
                        // `folioNavGlass` returns that plane byte-for-byte when the room is
                        // unlit. The old test compared a bar-glass wash against `barGlass`,
                        // which `barGlassFor` never returns, so it was never equal and the
                        // layer always drew.
                        .then(
                            if (roomGlass == chrome.plane) {
                                Modifier
                            } else {
                                Modifier.drawBehind {
                                    drawRect(roomGlass, blendMode = BlendMode.Color)
                                }
                            }
                        )
                        .height(FolioTokens.navFloatHeight)
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val navHaptics = rememberFolioHaptics()
                    folioNavBarItems.forEachIndexed { index, item ->
                        val isSelected = index == selectedIndex

                        FolioNavItem(
                            item = item,
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    // Re-tap on the selected tab scrolls its content
                                    // back to top — `launchSingleTop` makes the
                                    // navigation itself a silent no-op otherwise. A
                                    // lighter scrub tick, not the full commit click.
                                    navHaptics.play(FolioHaptic.ScrubTick)
                                    FolioTabReselect.select(item.route)
                                } else {
                                    // A real tab switch commits — the Living Paper
                                    // commit click, paired with the cover morph.
                                    navHaptics.play(FolioHaptic.Commit)
                                }
                                // Home IS the start destination. `popUpTo` is exclusive of
                                // its target, so Home is never popped and never gets a saved
                                // state of its own. Asking `restoreState` to restore one anyway
                                // let a stale/empty saved entry shadow the live Home and swallow
                                // the tap — which only cleared on an app restart (the saved-state
                                // cache resetting). Only Home showed it because only Home is the
                                // pop target. Non-start tabs keep save/restore so their scroll
                                // position survives a round trip.
                                navController.goToTopLevelTab(item.route)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One capsule item. Selected: a soft accent pill, accent icon, label visible.
 * Unselected: icon only in `onSurfaceVariant`. The width animates so the capsule
 * breathes as the selection moves rather than snapping — and freezes under
 * reduce-motion.
 *
 * Sizes are deliberately smaller than a Material bar's: at 20dp the icon reads as
 * a mark rather than a button face, which is what keeps the capsule from looking
 * like a toolbar that happens to have rounded ends.
 */
@Composable
private fun FolioNavItem(
    item: FolioNavBarItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val motion = rememberMotionEnabled()
    val interaction = rememberFolioInteraction()
    // §16: the pill lights where the thumb lands rather than flashing its whole
    // outline uniformly. Same interaction source the press and the tick already use.
    val focal = com.folio.reader.ui.theme.rememberFolioPressFocal(interaction)
    val targetWidth = if (selected) 86.dp else 50.dp
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
        } else {
            snap()
        },
        label = "navItemWidth",
    )
    // Icon size animates on the same spring as the box width, so the mark
    // settles into its selected size instead of popping 21dp -> 19dp in one frame.
    val iconSize by animateDpAsState(
        targetValue = if (selected) 19.dp else 21.dp,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
        } else {
            snap()
        },
        label = "navIconSize",
    )
    // Same idiom as FolioSegmented: the pill fill and the icon ink cross-fade
    // between states rather than switching on a single frame. §17 contrast: the
    // selected pill carries more of the accent than it did (0.20 vs 0.14), and
    // the unselected glyph is the *outlined* weight at reduced ink — selection
    // reads by weight and by saturation together, not by a tint a few percent
    // apart.
    //
    // The hue is the *destination's*, not the theme's single signature. One `primary`
    // across all four tabs is exactly what made the capsule byte-identical on Home,
    // Library, Stats and More; each now wears the accent role that names its room —
    // progress, discovery, streak, annotation — read from the one table the
    // masthead's segment switch also uses, so a tab and the control inside it cannot
    // disagree. Unknown routes fall back to `navigationSelected`, the palette's own chrome
    // accent — see below.
    //
    // The ink still passes the contrast guard: on the monochromatic packs (Silver,
    // and the pale lights where an accent sits within a hair of `surface`) a raw
    // saturated accent washes out against the glass capsule, and "which tab am I on"
    // has to survive every palette.
    //
    // A route the table does not name falls back to `navigationSelected` — the role each
    // face authors *for this exact question*, and which until now nothing read. It is the
    // right fallback rather than `primary`: a selection is chrome, and a tab that has no
    // named room should still be lit by the palette's own chrome accent rather than by the
    // same hue as the progress ring, the field's anchor pool and every other `primary` in
    // the app.
    val accent = folioDestinationAccent(item.route, fallback = colors.navigationSelected)
    // The pill is a *fill*, so it takes the accent's fill form rather than its ink form. The
    // four destination accents are AA-fitted for body text, and a role fitted as ink carries
    // 0.020-0.036 Oklab chroma as a fill -- present in the token, absent on screen. The hue is
    // untouched, so a tab still says which room it is and the segment switch still cannot
    // disagree with it. See [fillForm].
    val fillAccent = remember(accent) { fillForm(accent) }
    // The selected glyph and label sit *on* the pill, which is struck in the same accent
    // hue as `legibleAccent`. Tinting them with that same accent made the mark dissolve
    // into its own bed — the Home icon/label on the Home pill read as a flat colour field.
    // The pill is the background now, so the ink has to be chosen *against the pill*: a
    // high-contrast on-fill ink (near-black on a light accent, white on a dark one), the
    // same discipline the reader's selected pill uses (ReaderPanelCards onPrimary rule).
    val onPill = remember(fillAccent) {
        val l = 0.299f * fillAccent.red + 0.587f * fillAccent.green + 0.114f * fillAccent.blue
        if (l > 0.55f) Color(0xFF10131A) else Color.White
    }
    val pillFill by animateColorAsState(
        // Pushed above the contract band's top (0.65) to 0.76: at 0.60 the blurred veil under
        // the capsule bled through the chip and muddied the selected icon. A more solid pill
        // gives the glyph a clean, chromatic bed so it reads crisp.
        targetValue = if (selected) fillAccent.copy(alpha = 0.76f) else Color.Transparent,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navPillFill",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            onPill
        } else {
            colors.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = onPill,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navLabelColor",
    )
    Box(
        modifier = Modifier
            .width(width + 2.dp) // bleed into the inter-item gap: no dead columns
            .heightIn(min = 48.dp) // 42dp pill inside a 48dp minimum hit target
            .folioPressable(interaction, scaleTo = 0.93f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // Announce this as a tab and whether it is the current one, so TalkBack
            // says "selected" rather than leaving the state to tint alone.
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        contentAlignment = Alignment.Center,
    ) {
        // The visual pill stays exactly 42dp — the deliberate capsule aesthetic —
        // drawn on an inner child centered inside the larger hit target. §16: the
        // pill carries the liquid press (rim flash; the corner term is a no-op on
        // a pill by construction), so the flash tracks the capsule's own shape.
        //
        // §18: the fill is **feathered**, and this is the nested shape in the user's
        // screenshot. `clip(pill).background(accent, pill)` at 0.76 alpha is a hard-edged,
        // near-opaque 42dp pill sitting centred inside a 54dp capsule whose own edge the
        // whole §18 ladder exists to dissolve — 6dp of clearance all round, so the eye
        // gets two concentric outlines 6dp apart and reads the obvious thing. Feathering
        // it costs the selection nothing: the interior keeps the full 0.76 that the glyph
        // needs for a clean chromatic bed (that alpha was raised deliberately and is
        // untouched), and only the outermost ~3dp ramps, so the pill stops being an
        // outline and becomes a pool of accent the mark sits in.
        //
        // `Tile` is the right rung and not a guess: a 42dp short side puts
        // FOLIO_EDGE_MAX_SHARE's clamp at 5dp, so the ladder would cut anything longer
        // down to about this anyway, and a selection chip is exactly the small-chip case
        // the rung is named for.
        Box(
            modifier = Modifier
                .width(width)
                .height(42.dp)
                .clip(FolioShapes.pill)
                .folioSoftFill(FolioShapes.pill, pillFill, FolioEdgeRole.Tile)
                .folioGlassPress(interaction, FolioShapes.pill, focal)
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        if (selected) {
                            val y = size.height - 3.dp.toPx()
                            drawLine(iconTint.copy(alpha = .82f), Offset(size.width * .37f, y),
                                Offset(size.width * .63f, y), 1.4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(iconTint.copy(alpha = .34f), Offset(size.width * .44f, y - 3.dp.toPx()),
                                Offset(size.width * .63f, y - 3.dp.toPx()), .7.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgedBox(badge = {
                    if (item.badgeCount != null) {
                        Badge { Text(item.badgeCount.toString()) }
                    }
                }) {
                    Icon(
                        // §17 contrast: filled glyph when chosen, outlined when
                        // resting — the weight change is legible before the tint
                        // even lands.
                        if (selected) item.icon else item.outlinedIcon,
                        // When selected the label Text below is shown and read by
                        // TalkBack, so the icon must not repeat it — null keeps the
                        // glyph decorative and avoids the double read.
                        contentDescription = if (selected) null else item.label,
                        tint = iconTint,
                        modifier = Modifier.size(iconSize),
                    )
                }
                // The label's *slot* grows 0 -> full width in step with its alpha and
                // the box-width spring. Entering at full width in one frame used to
                // shove the centred icon toward the leading edge before the text was
                // even visible; expandHorizontally keeps the reflow continuous.
                AnimatedVisibility(
                    visible = selected,
                    enter = expandHorizontally(
                        animationSpec = if (motion) {
                            spring(stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)
                        } else {
                            snap()
                        },
                    ) + fadeIn(
                        animationSpec = if (motion) spring(stiffness = 900f) else snap(),
                    ),
                    exit = shrinkHorizontally(
                        animationSpec = if (motion) {
                            spring(stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)
                        } else {
                            snap()
                        },
                    ) + fadeOut(
                        animationSpec = if (motion) spring(stiffness = 900f) else snap(),
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = item.label,
                            style = FolioTheme.typography.labelSmall,
                            color = labelColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Jumps to a top-level tab with the same save/restore semantics as a bar tap. */
fun NavHostController.goToTopLevelTab(route: String) {
    // See the bar-tap handler: Home is the start destination and is never
    // popped, so restoring a saved state for it can swallow the navigation.
    // Keep restore for every other tab.
    val goingHome = route == FolioRoutes.HOME
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = !goingHome
    }
}
