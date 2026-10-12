package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * The field-occlusion counter: routes that cover the ground plane completely and opaquely
 * claim the window, and the field stands down while the claim is live.
 *
 * This outlived the Mirage AGSL field it was built for, because the silk field it now serves
 * is the same kind of cost at a smaller scale: a per-tick full-screen redraw underneath
 * something that hides every pixel of it — and the field is the source of the §16 room rung,
 * so while it animates it also re-blurs every glass surface in the app. A reader route freezes
 * both by claiming the window: [FolioThemeBackground] parks the silk's drift for exactly as
 * long as the claim holds, which costs one composition read and no per-frame work at all.
 *
 * A composition local only flows *down* and the field is an ancestor, so this is the same
 * upward-write seam [LocalFolioGuillocheQuiet] and [LocalFolioLampAnchor] use: the covering
 * surface registers, the root reads.
 *
 * A **counter** rather than a flag, so two overlapping claims cannot have the first one to
 * leave reveal the field under the second. Null (every un-provided tree: desktop, previews,
 * tests) means nothing can register and the field always draws, which is the pre-existing
 * behaviour exactly.
 */
val LocalFolioFieldOcclusion = compositionLocalOf<MutableState<Int>?> { null }

/** Builds the counter. Provided at the app root beside the other field locals. */
@Composable
fun rememberFolioFieldOcclusion(): MutableState<Int> = remember { mutableStateOf(0) }

/**
 * Declares that this route covers the ground plane completely and opaquely, so the field can
 * stop drawing while it is on screen.
 *
 * Call it from the composition of a route whose background is genuinely opaque over the whole
 * window — the reader page, a full-screen viewer. It is **not** for a sheet, a dialog, a
 * translucent scrim or anything that lets the room show through at the edges: those still want
 * the field behind them, and claiming otherwise shows up as the background losing its motion.
 *
 * The claim is released on dispose, so it cannot outlive its route.
 */
@Composable
fun FolioFieldOccluder() {
    val occlusion = LocalFolioFieldOcclusion.current ?: return
    DisposableEffect(occlusion) {
        occlusion.value = occlusion.value + 1
        onDispose { occlusion.value = (occlusion.value - 1).coerceAtLeast(0) }
    }
}

/**
 * Whether the field has anything to draw for: false exactly while some route has claimed the
 * whole window with [FolioFieldOccluder].
 *
 * Read in **composition** deliberately, which is the opposite of the discipline every phase in
 * this package keeps for a *clock*. The difference is that this is not a clock: it changes once
 * per route entry, and what it controls is whether the field's drift is parked while nothing
 * can see it. (It used to switch the Mirage composable out of the tree entirely, because a
 * `LaunchedEffect` vsync loop only stops when it leaves the composition; the silk field parks
 * instead — freezing its phases is the same saving for a tick-sampled canvas.)
 */
@Composable
fun rememberFolioFieldVisible(): Boolean {
    val occlusion = LocalFolioFieldOcclusion.current ?: return true
    val visible: State<Boolean> = remember(occlusion) {
        derivedStateOf { occlusion.value <= 0 }
    }
    return visible.value
}
