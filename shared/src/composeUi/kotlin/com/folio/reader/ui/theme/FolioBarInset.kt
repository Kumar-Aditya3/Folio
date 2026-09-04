package com.folio.reader.ui.theme

import androidx.compose.runtime.compositionLocalOf
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
