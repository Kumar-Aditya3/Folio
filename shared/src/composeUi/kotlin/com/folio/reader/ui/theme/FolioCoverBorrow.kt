package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * How much of a surface's finished colour the book being read is allowed to supply.
 *
 * The complaint this answers is not "the cover colour is wrong" — it is that the
 * cover *replaces* the palette the user chose. Every screen looked like a different
 * app because a jacket's hue arrived through four independent channels at once, and
 * each channel had been tuned on its own and was individually defensible. Nothing
 * added them up. The hero's mesh and its tint wash are the clearest case: each was a
 * "low alpha", and stacked they were a painted slab.
 *
 * So the allowance is stated once, per surface, and the channels divide it. A channel
 * that wants more of the cover has to take it from another channel, in this file,
 * where the sum is visible.
 *
 * **The currency is the fraction of a finished pixel that is the cover's own colour.**
 * For a layer drawn at alpha `a` in a colour that is already the cover's, that is
 * simply `a`. For a layer drawn at `a` in a colour that borrows `b` of the way toward
 * the cover, it is `a × b`. Keeping one unit is what makes the channels comparable —
 * and what makes [tameCover] necessary, because a channel can also exceed its
 * allowance without exceeding its alpha, by borrowing a colour more violent than the
 * palette owns.
 *
 * This is a ceiling, not a mute. [FolioLight.hueRange] already governs the room's
 * lamp per pack, and the field's [FolioAtmosphere.fieldTintStrength] is bounded by
 * what the ink survives; this file governs the surfaces that had no authority at all.
 */

/**
 * The hero plane: at most this much of it may be the jacket's colour.
 *
 * **This is per polarity, and the reason is the whole bug.** A fraction of a surface's
 * finished colour is not a fraction of what the eye sees. On paper `raisedFill` is
 * `liftG(surface, 0.55f)` — already 55% white — so eleven percent of a saturated amber
 * nudges it by a few units and reads as light. On a dark face the same eleven percent is
 * laid over a pane near (25,22,30), and amber at #D4A017 pulls it to about (45,37,29):
 * the same allowance, four times the shift, and the result is a brown wash across the
 * whole card rather than a lit surface. One ceiling for both faces is what made this
 * survive five rounds of tuning — every number was checked against the face that hides it.
 *
 * The dark allowance is set so the *shift* matches paper's, not the fraction: a bright
 * cover colour over a near-black pane moves it by roughly `(212-25) * alpha`, so 0.032
 * lands around six units, which is the order paper's 0.108 produces.
 */
const val HERO_COVER_CEILING_DARK = 0.07f
const val HERO_COVER_CEILING_PAPER = 0.24f

/**
 * The wash's share of the ceiling. It gets the largest slice because it is the one
 * channel with a direction — it falls off across the plane and reads as light from the
 * book rather than as a tint over it.
 */
const val HERO_WASH_SHARE = 0.45f

/**
 * The mesh's share. Smaller on purpose: the mesh's cover pool sits under two palette
 * accent pools that drift across it, so where they coincide the cover is already
 * being added to, and an allowance sized for the whole plane overpays the overlap.
 */
const val HERO_MESH_SHARE = 0.30f

/**
 * Unspent. Left as headroom rather than redistributed, so a future channel has
 * somewhere to come from without the ceiling itself becoming the thing that moves.
 */
const val HERO_COVER_HEADROOM = 0.25f

/** The wash's allowance on a surface of [dark]'s lightness. */
fun heroWashBorrow(dark: Boolean): Float =
    (if (dark) HERO_COVER_CEILING_DARK else HERO_COVER_CEILING_PAPER) * HERO_WASH_SHARE

/** The mesh's cover pool alpha on a surface of [dark]'s lightness. */
fun heroMeshBorrow(dark: Boolean): Float =
    (if (dark) HERO_COVER_CEILING_DARK else HERO_COVER_CEILING_PAPER) * HERO_MESH_SHARE

/** How far a cover's chroma may exceed the palette's own, as a multiple of it. */
const val COVER_CHROMA_ENVELOPE = 1.15f

/**
 * Max-minus-min channel spread — the cheapest thing that behaves like chroma, on the
 * same straight-line sRGB footing as [mixG] rather than a second Oklab round-trip.
 *
 * Internal, not private, because the hero and the guard test both have to read it and
 * must not fork a second copy of it (Rule 3).
 */
fun chromaSpread(c: Color): Float = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

/**
 * Pulls [cover]'s colour back inside the palette's own chroma envelope.
 *
 * A jacket's sampled average carries the jacket's *violence* along with its hue, and
 * it is the violence that reads as "the thumbnail is overwriting my theme": a neon
 * cover does not merely make the room pink, it makes the room more saturated than any
 * palette in the app chose to be. Hue is information about the book; chroma past the
 * palette's own is noise about the printing.
 *
 * Clamped toward the colour's own grey, so lightness and hue direction both survive
 * exactly and only the intensity moves — the same trick [desaturate] plays on the
 * field, which is why neither can trade away contrast.
 */
fun tameCover(cover: Color, palette: Color): Color {
    val ceiling = chromaSpread(palette) * COVER_CHROMA_ENVELOPE
    val spread = chromaSpread(cover)
    if (spread <= ceiling || spread <= 0f) return cover
    val grey = (cover.red + cover.green + cover.blue) / 3f
    val k = ceiling / spread
    return Color(
        red = grey + (cover.red - grey) * k,
        green = grey + (cover.green - grey) * k,
        blue = grey + (cover.blue - grey) * k,
    )
}
