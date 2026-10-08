package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioGlassPlate

/**
 * §16.2 — **the lens**. The layer that makes the material read as glass instead of as tint.
 *
 * Everything the app had before this was *paint*: a translucent fill, a hairline, a painted
 * sheen, an additive AGSL film, and a "refraction" pass that bent a closed-form
 * reproduction of the field rather than the field itself. None of those move what is
 * behind the surface, and a pane that does not move its background is a tint — which is
 * what it looked like, because that is what it was.
 *
 * Glass is not a colour. It is a *displacement*. The single strongest cue, ahead of blur,
 * ahead of specular, ahead of hue, is that the room visibly bends and compresses as it
 * passes under the edge of the slab. This modifier is that cue and nothing else:
 *
 *  1. **Edge refraction.** A rounded-rect SDF of the caller's own silhouette gives both a
 *     depth into the slab and an outward normal. The normal displaces the sample point
 *     *outward*, by the slope of a quarter-round fillet — so the last few pixels before the
 *     rim sweep in content from beyond the surface and squeeze it against the edge. That
 *     compression is the whole effect; it is why a real glass tile has a bright crowded
 *     band at its border and a flat sticker does not.
 *  2. **Dispersion.** Red, green and blue are displaced by slightly different amounts, so
 *     the crowded band splits into colour the way a bevel does. This is the term the old
 *     gel already had and could never show, because three samples of a smooth ramp land on
 *     the same colour. Against the real room — an animated shader field, a lamp bloom,
 *     drifting silk — they do not.
 *  3. **Fresnel and the far wall.** Grazing angles reflect: the rim brightens toward
 *     [highlight] on the lit side and falls into its own thickness on the other, both
 *     driven by the same normal rather than by a gradient guessed from the bounding box.
 *
 * The body is left alone — alpha ramps to zero a short way in — so the frost underneath
 * still carries it. Clear at the edge, frosted through the middle: that is the division a
 * real bevelled slab has, and it is why this composes with the Haze pass rather than
 * replacing it.
 *
 * Costs nothing where it cannot run. AGSL is Android 33+, so desktop and older devices get
 * the receiver back unchanged and the existing bevel/sheen fallback stands.
 *
 * @param plate the room, already captured — see [com.folio.reader.ui.components.glassRoomPlate].
 *   Null means nothing to bend, and the modifier stands down.
 * @param thickness how far into the slab the bevel runs. The band the whole effect lives in.
 * @param refraction peak displacement at the rim. Past roughly a third of [thickness] the
 *   band starts reading as a smeared outline rather than as a lens.
 * @param dispersion per-channel displacement spread, as a fraction of [refraction].
 */
@Composable
expect fun Modifier.folioGlassLens(
    plate: FolioGlassPlate?,
    shape: Shape,
    tint: Color = Color.Unspecified,
    highlight: Color = Color.White,
    lightX: Float = 0f,
    lightY: Float = -1f,
    thickness: Dp = FolioGlassLens.thickness,
    refraction: Dp = FolioGlassLens.refraction,
    dispersion: Float = FolioGlassLens.dispersion,
    specular: Float = FolioGlassLens.specular,
    depth: Float = FolioGlassLens.depth,
    intensity: Float = 1f,
    enabled: Boolean = true,
): Modifier

/**
 * The lens's tuning, in one place so the band is the same width on every surface that wears
 * it — Rule 3. These are the numbers the look was set with, and callers are not expected to
 * override them: the implementation already clamps the band to a fraction of the shorter
 * side (and scales the throw with it), so a 54dp nav capsule and a 180dp card share one
 * tuning instead of needing two.
 */
object FolioGlassLens {
    /**
     * 26dp. Wide enough that the compression has room to be seen as compression, narrow
     * enough that it stays an *edge* — much past this and the band becomes an inset frame,
     * which is the "rectangle inside the card" this codebase has fought before.
     */
    val thickness: Dp = 26.dp

    /**
     * 9dp of peak displacement. Set by what the room actually contains: the field's
     * structure runs at roughly this scale, so this is the throw that moves a *recognisable*
     * feature rather than blurring one. Half of it reads as a tint again; double reads as a
     * smear.
     */
    val refraction: Dp = 9.dp

    /**
     * 0.16 — the red/blue split as a fraction of the throw. Low, because dispersion that
     * announces itself reads as chromatic aberration, i.e. as a lens *fault*. At this value
     * it is only ever felt as the edge being alive.
     */
    const val dispersion: Float = 0.16f

    /** Fresnel rim gain. The lit edge catching; the anti-sun edge keeps a sixth of it. */
    const val specular: Float = 0.5f

    /** How far the slab's far wall falls into its own thickness. The weight term. */
    const val depth: Float = 0.3f
}
