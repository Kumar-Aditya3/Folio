package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The colour of what the reader is reading, hoisted to the room.
 *
 * The app has exactly one field — the ground plane at the root, under every screen
 * — and the room is supposed to be lit by the book. So the colour has to travel
 * *upward* from a screen to an ancestor of it, which a CompositionLocal cannot do:
 * locals flow down. This is the seam that inverts the direction. A screen writes
 * its featured cover with [FolioAmbientSource]; the root reads [shown] and hands it
 * to `folioField`.
 *
 * **Cost.** [shown] is read inside `folioField`'s draw cache, never in composition,
 * so a new cover costs the root one brush rebuild — not a recomposition of the
 * whole tree under it. The crossfade runs on its own for [AMBIENT_TINT_FADE_MS] and
 * then stops by itself, so the room keeps its ability to fall idle; the slow clock's
 * lesson (`SLOW_MOTION_TICK_MS`) is that a state which never settles is a state
 * that never lets the frame loop end.
 *
 * **Rule 19.** Under reduce-motion a new colour lands instantly, and the field
 * renders the same lit room with no travel between them.
 */
class FolioAmbientTint internal constructor(
    /** The colour the room is showing right now, mid-crossfade or settled. */
    val shown: State<Color?>,
    private val target: MutableState<Color?>,
    /**
     * The room's **standing colour** — what a destination that features nothing of its
     * own should wear. Declared by [FolioAmbientCanonical] (the anchor's owner, Home)
     * and re-declared by the nav shell on every route change, which is the other half
     * of the last-writer-wins contract: a screen's own [to] still wins while it is
     * visited, but it can no longer outlive its visit.
     */
    private val canonicalTarget: MutableState<Color?>,
) {
    /**
     * The colour the room was *asked* for, before the crossfade reaches it.
     *
     * For composition readers — a chart, a nav pill, a bar's glass. They want one
     * value per book change, not a new one per frame of a 550ms ramp, while [shown]
     * ticks at the frame rate for as long as the room is moving. Only the field
     * itself, which reads in the draw phase, wants [shown].
     */
    val requested: Color? get() = target.value

    /** The standing colour, or null for the palette's own. See [canonicalTarget]. */
    val canonical: Color? get() = canonicalTarget.value

    /** Ask the room to move to [color]. Null leaves it the palette's own. */
    fun to(color: Color?) {
        target.value = color
    }

    /** Declare the standing colour. See [FolioAmbientCanonical]. */
    fun setCanonical(color: Color?) {
        canonicalTarget.value = color
    }
}

/** How long the room takes to change its mind, in ms. */
private const val AMBIENT_TINT_FADE_MS = 550L

/**
 * Provided at the app root. An un-provided tree — desktop, a preview, a unit test —
 * leaves every [FolioAmbientSource] a no-op and every field the palette's own.
 */
val LocalFolioAmbientTint = compositionLocalOf<FolioAmbientTint?> { null }

@Composable
fun rememberFolioAmbientTint(): FolioAmbientTint {
    val target = remember { mutableStateOf<Color?>(null) }
    val shown = remember { mutableStateOf<Color?>(null) }
    val canonical = remember { mutableStateOf<Color?>(null) }
    val motion = rememberMotionEnabled()
    LaunchedEffect(target.value, motion) {
        val to = target.value
        val from = shown.value
        if (!motion || from == null || to == null || from == to) {
            shown.value = to
            return@LaunchedEffect
        }
        // Oklab, the same interpolation the hero's own tint uses, so a room crossing
        // between two covers passes through the colours a cover actually has rather
        // than through the muddy midpoint a channel-wise ramp lands on.
        val origin = withFrameNanos { it }
        var elapsed = 0L
        while (true) {
            elapsed = withFrameNanos { it } - origin
            val t = elapsed / (AMBIENT_TINT_FADE_MS * 1_000_000L).toFloat()
            if (t >= 1f) {
                shown.value = to
                break
            }
            shown.value = lerp(from, to, t)
        }
    }
    return remember(shown, target, canonical) { FolioAmbientTint(shown, target, canonical) }
}

/**
 * Declares that this screen's room is lit by [color] — the colour of its featured
 * cover. Call it from a screen body; the last writer wins, which is what makes the
 * room change as a tab does.
 *
 * Deliberately does not clear on dispose. A screen leaving would otherwise drop the
 * room back to the palette's neutral for the length of the transition it is
 * mid-way through, and a room that blinks while it is being crossed is a room that
 * reads as a bug.
 */
@Composable
fun FolioAmbientSource(color: Color?) {
    val tint = LocalFolioAmbientTint.current ?: return
    LaunchedEffect(color) { tint.to(color) }
}

/**
 * Declares the room's **standing colour** — what the shell resets to on every route
 * change, so a tab that features nothing of its own (Stats, More, the books shelf)
 * wears the anchor's colour instead of whichever screen wrote last.
 *
 * The last-writer-wins seam deliberately does not clear on dispose, which is right for
 * the crossfade and wrong for the long term: a book or manga detail's preview tint used
 * to keep colouring every tab that failed to declare its own, and More's bar became
 * "whatever I visited last". The canonical is the room's answer to *what were we doing
 * before the preview* — the colour of the book being read, which only the anchor's
 * owner (Home) knows. Declared alongside [FolioAmbientSource]; harmless anywhere the
 * holder is absent (ambient colour off, desktop, previews).
 */
@Composable
fun FolioAmbientCanonical(color: Color?) {
    val tint = LocalFolioAmbientTint.current ?: return
    LaunchedEffect(color) { tint.setCanonical(color) }
}

/**
 * The colour of what the reader is reading, for a surface that wants to *be* that
 * colour rather than sit in it — a chart, a nav item, a bar's glass.
 *
 * Returns [fallback] when the room is unlit or the tree provides no holder, which
 * includes desktop, every preview and every unit test. Reads the requested colour,
 * not the crossfading one: this recomposes once per book change instead of once
 * per frame of the room's ramp.
 */
@Composable
fun rememberFolioAmbientColor(fallback: Color): Color {
    val tint = LocalFolioAmbientTint.current ?: return fallback
    return tint.requested ?: fallback
}
