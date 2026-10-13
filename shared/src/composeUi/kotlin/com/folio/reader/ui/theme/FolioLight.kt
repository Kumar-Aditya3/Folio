package com.folio.reader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * A theme's own light, authored per [ThemePack].
 *
 * The lamp before this one was a single default laid over every theme: one position,
 * one strength, coloured by whatever cover was open. That is an override, not an
 * atmosphere — it does not matter what `Honey` or `Abyss` decided, the same light
 * arrived on top of it and the theme's character vanished underneath. So the light
 * becomes a property each pack authors, the way a pack already authors its colours.
 *
 * **The unit is the pack, not the palette.** A pack is one theme with two faces —
 * `Honey` and `Ember` are the same idea at morning and at night — so a light is
 * authored once per pack and its two faces differ only in how hard the source burns
 * and how deep the far side falls, which is the one thing a dark field and paper
 * genuinely disagree about.
 *
 * **The hue is not authored, and that is deliberate.** A theme's light is the
 * theme's own `primary`: `Toxic Lime` glows acid green and `Abyss` glows teal because
 * that is what they already are, not because somebody picked an angle for each. The
 * judgement left to a pack is where its light comes from, how strong it is, and how
 * much of its own colour it is willing to lend the book — [hueRange]. That is the
 * whole argument of this file: author the taste, derive the colour.
 *
 * **Legibility is not authored either.** [strength] is an ambition; [FolioLamp] solves
 * for the most of it that the theme's own ink survives, so no pack can author itself
 * illegible.
 */
@Immutable
data class FolioLight(
    /** Where the source sits, as fractions of the field. */
    val sourceX: Float,
    val sourceY: Float,
    /** How far the light leans toward the cover's own position, 0..1. */
    val lean: Float,
    /** Peak bloom alpha on a dark face. */
    val strengthDark: Float,
    /** Peak bloom alpha on a light face — paper shows light far more readily. */
    val strengthLight: Float,
    /** How much of the theme's own hue the book may take over, 0..1. */
    val hueRange: Float,
) {
    fun strength(dark: Boolean): Float = if (dark) strengthDark else strengthLight

    companion object {
        /**
         * The resting room, for a tree that provides no pack: light from the upper
         * leading third, in the theme's own colour, borrowing nothing from a cover.
         */
        val Default = FolioLight(
            sourceX = 0.30f, sourceY = 0.20f, lean = 0f,
            // P9: strengthDark 0.18 -> 0.13. See FOLIO_LIGHTS.
            strengthDark = 0.13f, strengthLight = 0.12f, hueRange = 0f,
        )
    }
}

/**
 * The ten authored lights — one per [ThemePack].
 *
 * Grouped by what each pack is *for*. A quiet reading pack keeps its light where it
 * always was and lends almost no colour; a loud pack puts its source somewhere with
 * intent and lets the book take the hue, because those themes exist to look like
 * somewhere and a shelf of covers should be able to say so.
 *
 * **P9 — every `strengthDark` came down by roughly a third.** The lamp is drawn *over* the
 * finished ground plane (MainActivity stacks `folioLamp` on top of `FolioThemeBackground`), its
 * colour is the theme's `primary` pulled toward its own pure hue and then lifted, and it is
 * composited as a plain mix — so on a near-black page it is the single brightest thing in the
 * room by construction. `solvedPeak` only guarantees the *ink* survives it (7.4:1), and against
 * a near-white dark-face ink that ceiling is nowhere near binding, so the authored ambition was
 * what actually shipped. The seats, leans and hue ranges are untouched: the light stays where
 * each pack put it and lends exactly what it lent, it is simply no longer a bulb in a dark room.
 * Every pair still satisfies `strengthLight <= strengthDark`.
 */
internal val FOLIO_LIGHTS: Map<String, FolioLight> = mapOf(
    // ── Quiet readers: the page is the point. Low, off-centre, barely borrowed.
    "gallery" to FolioLight(0.28f, 0.16f, 0.35f, 0.075f, 0.07f, 0.30f),
    "manuscript" to FolioLight(0.24f, 0.22f, 0.25f, 0.07f, 0.06f, 0.20f),
    "silver" to FolioLight(0.30f, 0.16f, 0.30f, 0.065f, 0.06f, 0.25f),
    "arctic" to FolioLight(0.32f, 0.14f, 0.45f, 0.08f, 0.07f, 0.40f),
    "rouge" to FolioLight(0.52f, 0.14f, 0.40f, 0.08f, 0.07f, 0.45f),

    // ── Colourful but composed: a named hue, a settled position, half the colour
    //    on loan to the shelf.
    "moss" to FolioLight(0.22f, 0.24f, 0.40f, 0.085f, 0.07f, 0.45f),
    "lagoon" to FolioLight(0.26f, 0.20f, 0.55f, 0.095f, 0.08f, 0.60f),
    "sakura" to FolioLight(0.34f, 0.15f, 0.45f, 0.085f, 0.08f, 0.60f),
    "honey" to FolioLight(0.28f, 0.18f, 0.35f, 0.08f, 0.07f, 0.40f),
    "dawn" to FolioLight(0.70f, 0.14f, 0.50f, 0.085f, 0.08f, 0.55f),

    // ── Loud rooms: the light *is* the subject. These three packs exist to look like
    //    somewhere — a neon city, a Miami evening, a lit chandelier — so each puts its
    //    source where that place puts it and lends the shelf most of its colour.
    //
    //    P7: they had no entry at all, so all three fell through to FolioLight.Default
    //    and got the quiet resting room: a neon theme lit like a reading room, which is
    //    exactly the failure this table exists to prevent ("they would get the quiet
    //    default and nobody would notice on a device").
    "cyberpunk" to FolioLight(0.18f, 0.12f, 0.60f, 0.095f, 0.085f, 0.70f),
    "vice" to FolioLight(0.78f, 0.22f, 0.55f, 0.09f, 0.08f, 0.65f),
    "aurum" to FolioLight(0.50f, 0.14f, 0.32f, 0.085f, 0.075f, 0.45f),
)

/**
 * The authored light for one palette, found through its pack.
 *
 * Resolved through [ThemePack] rather than keyed on the 36 faces: a pack's two faces
 * are one theme, and the only thing that should differ between them is how hard the
 * source burns — which [FolioLight.strength] decides from polarity at the call site.
 */
fun folioLightFor(palette: AppPalette): FolioLight {
    val pack = ThemePack.ALL.firstOrNull {
        it.lightAppPaletteId == palette.id || it.darkAppPaletteId == palette.id
    } ?: return FolioLight.Default
    return FOLIO_LIGHTS[pack.id] ?: FolioLight.Default
}

/**
 * The active light, provided at the theme root.
 *
 * Null means no authored light — an un-provided tree, a preview, a test — and the
 * lamp then uses [FolioLight.Default], which is deliberately quiet.
 */
val LocalFolioLight = compositionLocalOf<FolioLight?> { null }
