package com.folio.reader.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation bar items (bottom app bar) – exactly 4 items (§3.4 FOLIO_IMPLEMENTATION_SPEC).
 */
data class FolioNavBarItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int? = null
)

/**
 * The 4 bottom bar items: Home, Library, Stats, More (§3.3).
 */
val folioNavBarItems = listOf(
    FolioNavBarItem(
        route = FolioRoutes.HOME,
        icon = Icons.Filled.Home,
        label = "Home"
    ),
    FolioNavBarItem(
        route = FolioRoutes.LIBRARY,
        icon = Icons.Filled.MenuBook,
        label = "Library"
    ),
    FolioNavBarItem(
        route = FolioRoutes.STATS,
        icon = Icons.Filled.BarChart,
        label = "Stats"
    ),
    FolioNavBarItem(
        route = FolioRoutes.MORE,
        icon = Icons.Filled.MoreHoriz,
        label = "More"
    )
)
