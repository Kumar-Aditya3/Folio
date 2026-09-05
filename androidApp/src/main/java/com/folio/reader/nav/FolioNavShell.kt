package com.folio.reader.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.LocalFolioBarInset
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
                        // translucent: the page moving underneath is what tells the
                        // eye this is glass floating over it rather than a rounded
                        // slab parked at the bottom of the window.
                        .folioVeil(FolioShapes.pill, fillAlpha = 0.90f * FolioTheme.surfaceOpacity.navBar)
                        .height(FolioTokens.navFloatHeight)
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    folioNavBarItems.forEach { item ->
                        val isSelected = currentRoute == item.route ||
                            (item.route == FolioRoutes.MORE && currentRoute.startsWith("settings/"))

                        FolioNavItem(
                            item = item,
                            selected = isSelected,
                            onClick = {
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
    // between states rather than switching on a single frame.
    val pillFill by animateColorAsState(
        targetValue = if (selected) colors.accentProgress.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navPillFill",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) colors.accentProgress else colors.onSurfaceVariant,
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
            .width(width)
            .height(42.dp)
            .folioPressable(interaction, scaleTo = 0.93f)
            .clip(FolioShapes.pill)
            .background(pillFill, FolioShapes.pill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BadgedBox(badge = {
                if (item.badgeCount != null) {
                    Badge { Text(item.badgeCount.toString()) }
                }
            }) {
                Icon(
                    item.icon,
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
