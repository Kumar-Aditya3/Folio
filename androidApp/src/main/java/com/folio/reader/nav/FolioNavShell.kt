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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.folio.reader.ui.components.FolioTabReselect
import com.folio.reader.ui.components.folioGlassPress
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassBlurred
import com.folio.reader.ui.components.navSweepAlpha
import com.folio.reader.ui.components.navSweepBand
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.navCapsuleFill
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity

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
    containerColor: Color = MaterialTheme.colorScheme.surface,
    // §16 predictive back: 0 at rest, 1 at a fully-swiped back gesture. Drives
    // the capsule's recede; inert below API 33.
    backProgress: Float = 0f,
    // In-screen identity that swaps content without a route change (the
    // library's Books/Manga/Documents mode). The capsule's blur-hold keys on
    // this too, or the mode switch flashes the previous shelf's backdrop.
    screenKey: String = "",
    content: @Composable () -> Unit
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: ""
    // The runway the capsule needs: the OS gesture/nav inset, the capsule itself
    // and the air on both sides of it. Reserved once here so no screen has to
    // know a floating bar exists.
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomInset =
        systemBottomInset + FolioTokens.navFloatHeight + FolioTokens.navFloatInset * 2

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
            LocalFolioBarInset provides if (showBottomBar) contentBottomInset else 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
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
            // For the first frames after a route change the capsule falls back to
            // the designed near-opaque fill, which hides the gap entirely.
            var suppressBlur by remember { mutableStateOf(false) }
            LaunchedEffect(currentRoute, screenKey) {
                suppressBlur = true
                kotlinx.coroutines.delay(120)
                suppressBlur = false
            }
            val capsuleFill = FolioTheme.surfaceOpacity.navCapsuleFill(glassBlurred() && !suppressBlur)
            // §17 liquid selection: the tab the reader lands on sends one
            // specular band across the capsule's glass in the direction of
            // travel — the selection's own motion, caught by the material it
            // moves through. Drawn under the items (drawWithCache before the
            // Row's children), read in the draw phase only; skipped entirely
            // under reduce-motion and on the very first composition.
            val motion = rememberMotionEnabled()
            val selectedIndex = folioNavBarItems.indexOfFirst { item ->
                currentRoute == item.route ||
                    (item.route == FolioRoutes.MORE && currentRoute.startsWith("settings/"))
            }
            val sweep = remember { Animatable(1f) }
            var sweepDirection by remember { mutableFloatStateOf(1f) }
            var lastSelected by remember { mutableStateOf(selectedIndex) }
            LaunchedEffect(selectedIndex, motion) {
                if (selectedIndex != lastSelected) {
                    if (motion) {
                        sweepDirection = if (selectedIndex > lastSelected) 1f else -1f
                        sweep.snapTo(0f)
                        sweep.animateTo(
                            1f,
                            tween(FolioTokens.motionLiquidSweep.toInt(), easing = FastOutSlowInEasing),
                        )
                    } else {
                        sweep.snapTo(1f)
                    }
                    lastSelected = selectedIndex
                }
            }
            val sweepLight = FolioTheme.atmosphere.rimLight
            // A soft dissolve under the capsule, in the page's own field colour: content
            // scrolling out at the bottom fades instead of being cut off by the glass.
            // It is the same idea as the masthead gaining glass on scroll, at the other
            // end of the page.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(contentBottomInset)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to FolioTheme.atmosphere.fieldBottom.copy(alpha = 0.80f),
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
                        // A true pill (radius = half the height) and genuinely
                        // translucent: the page moving underneath is what tells
                        // the eye this is glass floating over it rather than a rounded
                        // slab parked at the bottom of the window.
                        // The preference *is* the fill alpha; its default (0.9) is the
                        // designed capsule, so 100% is a solid pill and the floor is a
                        // whisper of one — and under a real blur the tier thins it
                        // further. See FolioSurfaceOpacity.
                        .folioVeil(FolioShapes.pill, fillAlpha = capsuleFill)
                        // §17 liquid selection: the travelling specular band, over
                        // the veil's own material and under the items.
                        .drawWithCache {
                            onDrawBehind {
                                val progress = sweep.value
                                if (progress < 1f) {
                                    val band = size.width * 0.45f
                                    val x = navSweepBand(progress, size.width, band, sweepDirection)
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0f to Color.Transparent,
                                            0.5f to sweepLight.copy(
                                                alpha = sweepLight.alpha * 0.38f * navSweepAlpha(progress),
                                            ),
                                            1f to Color.Transparent,
                                            startX = x,
                                            endX = x + band,
                                        ),
                                    )
                                }
                            }
                        }
                        .height(FolioTokens.navFloatHeight)
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    folioNavBarItems.forEachIndexed { index, item ->
                        val isSelected = index == selectedIndex

                        FolioNavItem(
                            item = item,
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    // Re-tap on the selected tab scrolls its content
                                    // back to top — `launchSingleTop` makes the
                                    // navigation itself a silent no-op otherwise.
                                    FolioTabReselect.select(item.route)
                                }
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
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
    val pillFill by animateColorAsState(
        targetValue = if (selected) colors.accentProgress.copy(alpha = 0.20f) else Color.Transparent,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navPillFill",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            colors.accentProgress
        } else {
            colors.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = colors.accentProgress,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navLabelColor",
    )
    Box(
        modifier = Modifier
            .width(width + 2.dp) // bleed into the inter-item gap: no dead columns
            .heightIn(min = 48.dp) // 42dp pill inside a 48dp minimum hit target
            .folioPressable(interaction, scaleTo = 0.93f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The visual pill stays exactly 42dp — the deliberate capsule aesthetic —
        // drawn on an inner child centered inside the larger hit target. §16: the
        // pill carries the liquid press (rim flash; the corner term is a no-op on
        // a pill by construction), so the flash tracks the capsule's own shape.
        Box(
            modifier = Modifier
                .width(width)
                .height(42.dp)
                .clip(FolioShapes.pill)
                .background(pillFill, FolioShapes.pill)
                .folioGlassPress(interaction, FolioShapes.pill),
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
                        contentDescription = item.label,
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
    navigate(route) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
