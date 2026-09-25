package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * §17 contrast pass — the ink roles, pushed toward the palette's own extreme.
 *
 * The §15 ramps were tuned to clear their floors (onSurface ≥ 7:1, variant
 * ≥ 4.5:1, ΔE between them ≥ 12), and a palette that clears a floor by a wide
 * margin and one that clears it by a hair look identical to the tests but not
 * to the eye: titles sit a step short of the page's own darkness, and the
 * whole theme reads mid-grey — "bland within the scope of a single theme".
 * This derivation moves every palette the rest of the way to §15.2's *target*
 * column (primary ink ≥ 10:1) in one place, so all ~30 palettes, custom themes
 * and the Material You derivation inherit it (Rule 3: extend the seam, never
 * fork per palette).
 *
 * What moves and what does not:
 *  - [FolioColors.onSurface] and [FolioColors.onBackground] are lerped
 *    [INK_DEEPEN_FRACTION] toward the palette's own extreme — black on a light
 *    field, white on a dark one, decided by surface luminance exactly as
 *    [atmosphereFor] decides its lighting model. Contrast only ever rises;
 *    hue is preserved because the extreme is achromatic; an achromatic ink
 *    stays achromatic, so the Rule 22 allowlist palettes keep their character.
 *  - [FolioColors.onSurfaceVariant] now rides the same push. It carries most of
 *    the app's secondary text, and a few light palettes (Arctic, Sakura,
 *    Silver) authored it a hair under the 4.5:1 AA floor (about 3.99, 4.44
 *    and 4.49), so lerping it the same [INK_DEEPEN_FRACTION] toward the
 *    palette's own extreme lifts it back over the line. It travels the same
 *    fraction as the primary ink, so contrast only rises and the primary ink
 *    stays strictly ahead of the secondary; the ordering holds even as the
 *    absolute gap tightens.
 *  - Everything else — surfaces, accents, outlines — is untouched: those have
 *    their own pinned roles, and this pass is about type hierarchy and
 *    selected-state ink, not a restyle.
 *
 * Pure on purpose: the derivation is pinned by `InkDeepeningTest` (monotone
 * contrast, untouched roles, achromatic stability) rather than by eye, and it
 * is applied exactly once, at [FolioTheme.MaterialTheme] — the one seam every
 * themed tree flows through, previews and settings cards included.
 */

/** How far the primary ink travels toward its palette's extreme, 0..1. */
const val INK_DEEPEN_FRACTION = 0.35f

/**
 * The ink roles of [colors], deepened. See the file doc; identity when the
 * ink already sits at its extreme.
 */
fun deepenInkRoles(colors: FolioColors): FolioColors {
    val extreme = inkExtreme(colors.surface)
    fun push(c: Color): Color = lerp(c, extreme, INK_DEEPEN_FRACTION)
    return colors.copy(
        onSurface = push(colors.onSurface),
        onBackground = push(colors.onBackground),
        onSurfaceVariant = push(colors.onSurfaceVariant),
    )
}

/** The achromatic pole a dark surface's ink travels toward. */
internal fun inkExtreme(surface: Color): Color =
    if (luminanceOf(surface) < 0.5f) Color.White else Color.Black

/** Relative luminance, the same sRGB weighting `atmosphereFor` uses. */
private fun luminanceOf(c: Color): Float =
    c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f
