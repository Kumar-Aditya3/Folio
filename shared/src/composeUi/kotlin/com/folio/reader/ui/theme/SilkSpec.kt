package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Per-theme vibrance for the silk field: how much richer the travelling band makes the mesh
 * at its centre. 1.0 is neutral.
 *
 * ## Why this is a per-theme number and not a constant
 *
 * A single treatment applied to twenty palettes is not "per theme" — it is one theme with
 * twenty colour substitutions, which is the exact shape of the architecture this codebase
 * already rejected (`Theme -> primaryColor -> tint everything`). Vibrance has to vary because
 * the palettes do not have the same headroom: Graphite and Silver are near-neutral, and
 * pushing their saturation invents colour the palette never had — the same failure the
 * atmosphere work hit on mintcoral, forest and ocean, where a full-saturation slab appeared
 * on a hue the reference never carried. A theme built on saturated hues has room.
 *
 * ## Why the number is small
 *
 * The band scales the strand's distance from its own grey, and the mesh is already
 * accumulated additively, so the effect compounds: three overlapping strands at 1.35 each
 * clip long before the band reaches its centre. Values are kept in a band where the *hue*
 * survives the boost — a vibrance that clips a channel does not enrich the colour, it
 * changes it, which is the one thing this must not do.
 *
 * Keyed on the pack id, like [guillocheSpecFor], so a theme's silk is the same silk on every
 * device and in every session, and can be pinned by a test.
 */
internal fun silkVibranceFor(packId: String): Float = when (packId) {
    // Near-neutral rooms: almost none. Their whole character is the absence of chroma.
    "graphite", "silver" -> 1.06f
    // Cool, low-chroma rooms. A little, or the mesh reads as grey.
    "arctic", "midnight", "abyss" -> 1.16f
    // The rest of the dark rooms carry real hue and have headroom for it.
    "dark", "dusk", "espresso", "ember", "peacock", "blossom", "garnet", "cyberpunk", "vice", "aurum" -> 1.26f
    // Light rooms. The mesh is drawn at a far lower alpha over paper, so it needs more
    // before it is visible at all — but not so much that it stops reading as paper.
    "light", "warm", "honey", "moss", "dawn", "lagoon", "rouge", "sakura", "neonday", "viceday", "champagne" -> 1.20f
    else -> 1.18f
}

/**
 * One theme's silk, assembled from the live palette and atmosphere.
 *
 * The four colours are the theme's **own** roles rather than a fixed set, because the field's
 * job is to be the room's silk — the same relationship `atmosphereFor` has to the palette. In
 * particular `warmAccent` is the theme's warm pole, not a literal warm colour: on a cool
 * theme the warm edge is the cool theme's own counter-hue, which is what stops every theme's
 * silk having the same pink edge.
 *
 * All five are converted to **linear** light here, once, at build time. The field accumulates
 * additively and applies the OETF once at the end; mixing sRGB values would darken every
 * overlap, which is exactly the error the oracle's first colour pass made.
 */
internal fun silkSpecFor(
    colors: FolioColors,
    atmosphere: FolioAtmosphere,
    packId: String,
): SilkSpec = SilkSpec(
    bg = atmosphere.fieldBottom.toLinear3(),
    cool = colors.primaryAccent.toLinear3(),
    mid = colors.tertiaryAccent.toLinear3(),
    warm = colors.warmAccent.toLinear3(),
    hot = colors.textPrimary.toLinear3(),
    vibrance = silkVibranceFor(packId),
)

/**
 * sRGB -> linear light, per channel.
 *
 * The same transfer function the oracle uses (`srgb_to_linear`), and deliberately the same
 * constants: the two implementations are compared against each other, so a "tidier"
 * approximation here would show up as a colour difference that is nobody's bug.
 */
private fun Color.toLinear3(): FloatArray = floatArrayOf(
    srgbToLinear(red),
    srgbToLinear(green),
    srgbToLinear(blue),
)

private fun srgbToLinear(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

private fun Float.pow(e: Float): Float = kotlin.math.exp(e * kotlin.math.ln(this))
