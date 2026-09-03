package com.folio.reader.nav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled

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
 * The capsule still occupies its own row in a Column rather than overlaying the
 * content: an overlay would hide the last list item on every screen, and fixing
 * that would mean bottom padding duplicated into a dozen scroll containers. The
 * row's background is transparent, so the field shows through and the capsule
 * still reads as floating.
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

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            content()
        }

        if (showBottomBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = FolioTokens.space3,
                        vertical = FolioTokens.navFloatInset,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .folioVeil(FolioShapes.nav)
                        .height(FolioTokens.navFloatHeight)
                        .padding(horizontal = 6.dp),
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
 * One capsule item. Selected: accent pill, accent icon, label visible. Unselected:
 * icon only in `onSurfaceVariant`. The width animates so the capsule breathes as
 * the selection moves rather than snapping — and freezes under reduce-motion.
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
    val targetWidth = if (selected) 94.dp else 56.dp
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
        } else {
            snap()
        },
        label = "navItemWidth",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (motion) spring(stiffness = 900f) else snap(),
        label = "navLabelAlpha",
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(46.dp)
            .folioPressable(interaction, scaleTo = 0.93f)
            .clip(FolioShapes.pill)
            .background(
                if (selected) colors.accentProgress.copy(alpha = 0.16f) else Color.Transparent,
                FolioShapes.pill,
            )
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
                    tint = if (selected) colors.accentProgress else colors.onSurfaceVariant,
                    modifier = Modifier.size(if (selected) 22.dp else 24.dp),
                )
            }
            if (labelAlpha > 0f) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.label,
                    style = FolioTheme.typography.labelMedium,
                    color = colors.accentProgress,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                )
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
