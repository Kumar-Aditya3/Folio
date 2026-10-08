package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Regions of the page where the guilloche plate must be **exactly absent**.
 *
 * The plate is a line pattern, and a line pattern behind text is not a contrast problem —
 * it is a *shape* problem. A thin glyph stroke landing on a thin plate stroke loses its
 * edge in one word and not the next, and the eye reads that ragged edge as "dirty" rather
 * than as "patterned". `INK_FLOOR_WITH_MARGIN` cannot see this: it asserts a ratio, and a
 * ratio is genuinely fine. So the guarantee has to be categorical — zero, not faint — and
 * it has to be declarable by whichever surface knows where its text is.
 *
 * **Direction of travel.** A CompositionLocal flows *down*, and the plate is drawn at the
 * app root — far above the screens whose text it must avoid. So this is the seam that
 * inverts the direction, the same way [LocalFolioAmbientTint] lets a screen light the room
 * it stands in: a screen registers its text regions, the root reads them in the plate's
 * draw pass.
 *
 * **Collection, not replacement.** [LocalFolioAmbientTint] is last-writer-wins because a
 * room has one colour. Quiet regions are the opposite: a page has as many as it has slabs
 * of text, and they must all apply — including a full-page region published by the reader
 * *alongside* a per-panel one. So registrations accumulate, and they compose
 * multiplicatively in [guillocheWeightAt], which makes "quieter than the quietest" the
 * only possible direction of error.
 *
 * **Cost.** The set is a `MutableState<List<…>>` read in the plate's *draw* phase, so
 * registering a region rebuilds one brush; it never recomposes the tree under the root.
 * Registration happens on layout, which is once per screen entry — not per frame.
 *
 * **Rule 19.** An un-provided tree — desktop, every preview, every unit test — makes
 * [folioGuillocheQuiet] a no-op and the plate is drawn everywhere, which is the intended
 * look for a page that has declared no text.
 */
class FolioGuillocheQuiet internal constructor(
    /** The regions in force right now. Fractions of the field, in root order. */
    val regions: State<List<GuillocheQuiet>>,
    private val slots: MutableState<Map<Any, GuillocheQuiet>>,
) {
    internal fun register(key: Any, region: GuillocheQuiet) {
        if (slots.value[key] == region) return
        slots.value = slots.value + (key to region)
    }

    internal fun unregister(key: Any) {
        if (key !in slots.value) return
        slots.value = slots.value - key
    }
}

/** Provided at the app root, beside the field and the plate. */
val LocalFolioGuillocheQuiet = compositionLocalOf<FolioGuillocheQuiet?> { null }

/**
 * Whether the guilloche plate is actually being drawn behind the current subtree.
 *
 * Provided at the app root alongside [LocalFolioGuillocheQuiet], and `false` by default so
 * a preview, a test or a surface outside the app root answers honestly.
 *
 * This exists because the plate is not painted on a reading route — "paper stays paper" is
 * structural, gated at the root rather than asked of each reader. A surface cannot infer
 * that from anything local to itself, and the distinction is load-bearing for the liquid
 * glass gel: refraction is only worth its (substantial) second full-surface shader pass
 * when there is high-frequency detail behind the rim to bend. Over the reader's smooth
 * page it degenerates into a painted rectangle, which is exactly why `refractBackdrop`
 * defaults off. So surfaces that want the gel must ask this first.
 */
val LocalFolioGuillocheActive = compositionLocalOf { false }

/**
 * The theme pack whose plate is currently on the ground plane, or `""` outside the app root.
 *
 * Provided at the root from the same value the plate's own `packId` is built from, so the
 * gel's refraction and the engraving it is refracting are derived from one identity. A gel
 * that guessed the pack — or used a default figure — would bend a plate the page is not
 * painting, which is worse than not bending one at all: the rim would show ornament that
 * lines up with nothing.
 */
val LocalFolioPackId = compositionLocalOf { "" }

/**
 * The user's per-theme background-animation override map (`ReaderSettings.fieldOverrides`),
 * provided at the app root beside [LocalFolioPackId] so [FolioThemeBackground] can resolve the
 * effective [FolioFieldChoice] without reaching the settings layer. Keyed on the same pack id
 * [LocalFolioPackId] carries, so no new id-space is introduced. Empty default = every pack uses
 * its designed animation.
 */
val LocalFolioFieldOverrides = compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun rememberFolioGuillocheQuiet(): FolioGuillocheQuiet {
    val slots = remember { mutableStateOf<Map<Any, GuillocheQuiet>>(emptyMap()) }
    val regions = remember { mutableStateOf<List<GuillocheQuiet>>(emptyList()) }
    // Publish a flat list, in a stable order, so the plate's draw pass iterates a plain
    // list rather than a map's entry set (which allocates an iterator per frame).
    val holder = remember(slots, regions) {
        FolioGuillocheQuiet(regions, slots)
    }
    // Recompute the flat list from the slots. Deriving rather than appending is what keeps
    // overlapping regions from accumulating duplicates as a screen re-lays out.
    androidx.compose.runtime.LaunchedEffect(slots.value) {
        regions.value = slots.value.values.toList()
    }
    return holder
}

/**
 * Declares that this surface's contents must not have the plate behind them.
 *
 * Apply it to the **text**, not the screen: given a slab of bare-field prose, a section
 * label, an empty state or a figure's caption, this registers the node's own bounds and
 * the plate dissolves out of that rectangle with a soft margin. Applied to a whole screen
 * it silences the plate for the whole screen — which is exactly how the reader keeps its
 * paper plain, and it is deliberately the same call rather than a separate concept.
 *
 * The bounds are measured, not passed: a node knows where it ended up, and [onGloballyPositioned]
 * writes a value read in the draw phase so scrolling a label across the plate costs one
 * redraw rather than a recomposition.
 *
 * @param cover how completely to remove the plate here. 1 (the default) is a genuinely
 *   plain surface; a value between 0 and 1 *thins* the plate instead of removing it, which
 *   is available for a surface that would rather stay continuous across the page.
 */
@Composable
fun Modifier.folioGuillocheQuiet(cover: Float = 1f): Modifier {
    val holder = LocalFolioGuillocheQuiet.current ?: return this
    if (cover <= 0f) return this
    val key = remember { Any() }
    val clamped = cover.coerceIn(0f, 1f)
    return this.onGloballyPositioned { coordinates ->
        val region = coordinates.toQuietRegion(clamped) ?: return@onGloballyPositioned
        holder.register(key, region)
    }.then(
        // Deregister on dispose, so a region cannot outlive the text it was protecting —
        // a stale rectangle would silence the plate over a part of the page that no longer
        // has anything on it, and the gap would be unexplained from the outside.
        Modifier.also {
            androidx.compose.runtime.DisposableEffect(key) {
                onDispose { holder.unregister(key) }
            }
        },
    )
}

/**
 * A node's bounds as a quiet region, in field fractions.
 *
 * The field is the root surface and these bounds are in root pixels, so the two agree
 * without a transform — which is the same assumption the glass's refraction gel makes
 * when it measures a pane's [LayoutCoordinates.positionInRoot] to bend the room behind it.
 * That assumption holds under a `graphicsLayer`: `boundsInRoot`/`positionInRoot` are both
 * defined through `localToRoot`, which composes every layer transform up to the root. A
 * registered region therefore follows a scaled or translated node rather than sitting at
 * its untransformed layout box. A zero-area node is skipped rather than registered: it
 * would silence nothing and cost a region in every draw pass.
 */
private fun LayoutCoordinates.toQuietRegion(cover: Float): GuillocheQuiet? {
    if (!isAttached) return null
    val bounds: Rect = boundsInRoot()
    // The root's own size, which is what "fraction of the field" is measured against.
    val rootSize = size
    val rootParent = parentLayoutCoordinates ?: return null
    val field = rootParent.size
    val fw = field.width.toFloat()
    val fh = field.height.toFloat()
    if (fw <= 0f || fh <= 0f) return null
    if (bounds.width <= 0f || bounds.height <= 0f) return null
    val left = (bounds.left / fw).coerceIn(0f, 1f)
    val top = (bounds.top / fh).coerceIn(0f, 1f)
    val right = (bounds.right / fw).coerceIn(0f, 1f)
    val bottom = (bounds.bottom / fh).coerceIn(0f, 1f)
    if (right <= left || bottom <= top) return null
    // `rootSize` is read so an unattached-but-measured node is still skipped above rather
    // than silently producing a region from stale coordinates.
    if (rootSize.width <= 0 || rootSize.height <= 0) return null
    return GuillocheQuiet(left, top, right, bottom, cover)
}
