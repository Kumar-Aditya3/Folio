package com.folio.reader.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.folio.reader.ui.components.FolioTabReselect
import com.folio.reader.ui.components.folioFieldBottom
import com.folio.reader.ui.components.chromeFill
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.navCapsuleFill
import com.folio.reader.ui.theme.rememberFolioHaptics
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
    val readerSurface = currentRoute in setOf(FolioRoutes.READER, FolioRoutes.MANGA_READER, FolioRoutes.DOCUMENT_READER)
    val showNarration = narrationState?.hasBook == true && !readerSurface &&
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
            val selectedIndex = folioNavBarItems.indexOfFirst { item ->
                currentRoute == item.route ||
                    (item.route == FolioRoutes.MORE && currentRoute.startsWith("settings/"))
            }
            val atmos = FolioTheme.atmosphere
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
                val navHaptics = rememberFolioHaptics()
                com.folio.reader.ui.components.FolioNavigationCapsule(
                    items = folioNavBarItems,
                    selectedIndex = selectedIndex,
                    suppressBlur = suppressBlur,
                    onSelect = { index ->
                        val item = folioNavBarItems[index]
                        if (index == selectedIndex) {
                            navHaptics.play(FolioHaptic.ScrubTick)
                            FolioTabReselect.select(item.route)
                        } else {
                            navHaptics.play(FolioHaptic.Commit)
                        }
                        navController.goToTopLevelTab(item.route)
                    },
                )
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
