package com.folio.reader.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The room the floating navigation capsule needs at the bottom of a top-level
 * screen.
 *
 * The capsule is glass **over** content (see `FolioNavShell`), so the page must
 * run underneath it. Padding the destination away from the bottom edge instead
 * left a dead band below the last row: the capsule then read as a bar with a
 * margin, and shelves were sliced off mid-cover at the band's edge.
 *
 * Scrolling surfaces add this to their own `contentPadding`, so the last item
 * can still be scrolled clear of the glass while everything else passes behind
 * it. `0.dp` by default — desktop, the readers and any screen without the
 * capsule pay nothing.
 */
val LocalFolioBarInset = compositionLocalOf { 0.dp }

/**
 * The same arrangement at the top edge, for the masthead.
 *
 * A screen whose content is laid out *beside* its bar — `Column { bar; content }`
 * — has nothing behind the bar but the page's own flat field, and a veil over an
 * unchanging background is indistinguishable from a slightly different
 * background. That is why the glass read as invisible no matter how it was tuned.
 * Overlaying the bar and padding the scroller by this much lets real content pass
 * under it, which is the only thing glass can actually refract.
 */
val LocalFolioTopInset = compositionLocalOf { 0.dp }

/**
 * The masthead's at-rest height: the OS status icons, the bar row, and — when the
 * screen hangs a `rail` under its title — that rail plus the 6dp it is padded by.
 *
 * At-rest on purpose. Once content scrolls the bar grows a hairline and a 10dp
 * glass fade; a `contentPadding` that tracked them would translate every item
 * 11dp mid-scroll. In the overlay that growth paints over content which is
 * already moving, which is what the fade is for.
 *
 * Desktop resolves `WindowInsets.statusBars` to zero, so this is `barHeight` there.
 */
@Composable
fun folioBarTopInset(railHeight: Dp = 0.dp): Dp {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val rail = if (railHeight > 0.dp) railHeight + 6.dp else 0.dp
    return status + FolioTokens.barHeight + rail
}
