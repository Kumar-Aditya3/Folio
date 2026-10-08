package com.folio.reader.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Re-tap-to-top for the four top-level tabs: tapping the already-selected nav
 * item scrolls that tab's content back to its top (standard mobile behaviour,
 * and the fix for the "silent no-op" perception — `launchSingleTop` makes the
 * navigation call itself a no-op, so the tap needs a side channel).
 *
 * Deliberately trivial (route-keyed timestamp, nothing more): each tab collects
 * [events] for its own route and animates its primary scrollable to the top.
 * A screen with no scrollable simply ignores the bus. Hosts that embed the
 * same tab screens (e.g. Stats inside the library tab area) reach it through
 * their own host scope, not through a nav-level scaffold.
 */
object FolioTabReselect {
    /** Route-keyed timestamp; only routes in the bottom bar are ever emitted. */
    private val bus = MutableStateFlow<Pair<String, Long>?>(null)

    /** Emits a reselect event for [route]. */
    fun select(route: String) {
        bus.value = route to System.currentTimeMillis()
    }

    /**
     * The event flow collectors should use — drops the initial `null` so a
     * freshly-composed tab does not teleport to the top on entry.
     */
    val events = bus.filterNotNull()
}
