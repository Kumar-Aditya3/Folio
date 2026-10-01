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
            strengthDark = 0.18f, strengthLight = 0.12f, hueRange = 0f,
        )
    }
}

/**
 * The eighteen authored lights.
 *
 * Grouped by what each pack is *for*. A quiet reading pack keeps its light where it
 * always was and lends almost no colour; a loud pack puts its source somewhere with
 * intent and lets the book take the hue, because those themes exist to look like
 * somewhere and a shelf of covers should be able to say so.
 */
internal val FOLIO_LIGHTS: Map<String, FolioLight> = mapOf(
    // ── Quiet readers: the page is the point. Low, off-centre, barely borrowed.
    "system" to FolioLight(0.30f, 0.18f, 0.55f, 0.12f, 0.07f, 0.70f),
    "gallery" to FolioLight(0.28f, 0.16f, 0.35f, 0.10f, 0.07f, 0.30f),
    "manuscript" to FolioLight(0.24f, 0.22f, 0.25f, 0.10f, 0.06f, 0.20f),
    "silver" to FolioLight(0.30f, 0.16f, 0.30f, 0.08f, 0.06f, 0.25f),
    "arctic" to FolioLight(0.32f, 0.14f, 0.45f, 0.11f, 0.07f, 0.40f),
    "clay" to FolioLight(0.26f, 0.26f, 0.25f, 0.09f, 0.06f, 0.25f),
    "citrine" to FolioLight(0.62f, 0.16f, 0.35f, 0.10f, 0.07f, 0.35f),
    "rouge" to FolioLight(0.52f, 0.14f, 0.40f, 0.11f, 0.07f, 0.45f),

    // ── Colourful but composed: a named hue, a settled position, half the colour
    //    on loan to the shelf.
    "matcha" to FolioLight(0.26f, 0.18f, 0.40f, 0.12f, 0.07f, 0.45f),
    "moss" to FolioLight(0.24f, 0.22f, 0.40f, 0.12f, 0.07f, 0.45f),
    "lagoon" to FolioLight(0.26f, 0.20f, 0.55f, 0.14f, 0.08f, 0.60f),
    "sakura" to FolioLight(0.30f, 0.16f, 0.45f, 0.12f, 0.08f, 0.60f),
    "honey" to FolioLight(0.28f, 0.18f, 0.35f, 0.11f, 0.07f, 0.40f),
    "dawn" to FolioLight(0.70f, 0.14f, 0.50f, 0.12f, 0.08f, 0.55f),
    "sherbet" to FolioLight(0.62f, 0.20f, 0.55f, 0.13f, 0.08f, 0.65f),

    // ── Loud by design: the source sits up and behind the content, and the book is
    //    welcome to take the hue.
    "bubblegum" to FolioLight(0.50f, 0.18f, 0.70f, 0.15f, 0.10f, 0.85f),
    "hyperpop" to FolioLight(0.50f, 0.14f, 0.75f, 0.17f, 0.10f, 0.90f),
    "rainbow" to FolioLight(0.50f, 0.18f, 0.85f, 0.17f, 0.09f, 1.00f),
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
