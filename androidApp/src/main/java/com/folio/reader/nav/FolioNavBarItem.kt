package com.folio.reader.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation bar items (bottom app bar) – exactly 4 items (§3.4 FOLIO_IMPLEMENTATION_SPEC).
 *
 * §17 contrast: each item carries both icon weights. The selected tab draws the
 * filled glyph in the full accent; the unselected tabs draw the outlined glyph
 * dimmed — so selection reads by *weight and ink*, not only by a tint a few
 * percent apart, which is what made the capsule read as four equal marks with
 * a highlight rather than one chosen destination.
 */
data class FolioNavBarItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int? = null,
    /** The resting glyph; defaults to the filled one for callers that don't care. */
    val outlinedIcon: ImageVector = icon
)

/**
 * The 4 bottom bar items: Home, Library, Stats, More (§3.3).
 */
val folioNavBarItems = listOf(
    FolioNavBarItem(
        route = FolioRoutes.HOME,
        icon = Icons.Filled.Home,
        outlinedIcon = Icons.Outlined.Home,
        label = "Home"
    ),
    FolioNavBarItem(
        route = FolioRoutes.LIBRARY,
        icon = Icons.AutoMirrored.Filled.MenuBook,
        outlinedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        label = "Library"
    ),
    FolioNavBarItem(
        route = FolioRoutes.STATS,
        icon = Icons.Filled.BarChart,
        outlinedIcon = Icons.Outlined.BarChart,
        label = "Stats"
    ),
    FolioNavBarItem(
        route = FolioRoutes.MORE,
        icon = Icons.Filled.MoreHoriz,
        outlinedIcon = Icons.Outlined.MoreHoriz,
        label = "More"
    )
)
