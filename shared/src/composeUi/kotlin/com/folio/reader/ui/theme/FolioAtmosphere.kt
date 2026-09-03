package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * A palette is a colour set; an **atmosphere** is an environment. This derives
 * the second from the first so every theme gains depth, lighting and a shadow
 * character without hand-authoring 30 more colour tables.
 *
 * Everything here is computed from the palette's own roles, so a new palette
 * inherits an atmosphere for free and the §15 contrast tests keep governing the
 * inputs.
 *
 * The model: one light source above and slightly leading (top-start), two or
 * three low-alpha colour pools that give the page a temperature, and a shadow
 * whose colour is the palette's own darkness rather than pure black. Dark
 * palettes get light from *emission* (rims brighten) and light palettes get it
 * from *occlusion* (shadows deepen) — the same reason a photograph of a lamp and
 * a photograph of paper are lit differently.
 */
@Immutable
data class FolioAtmosphere(
    /** True when the field is dark enough that rims read as emitted light. */
    val isDark: Boolean,
    /** Page-level gradient stops, top to bottom, already alpha-composited. */
    val fieldTop: Color,
    val fieldBottom: Color,
    /** Large ambient colour pools behind content. Never more than three. */
    val pools: List<Color>,
    /** Alpha a pool is drawn at. Dark fields tolerate more; paper needs less. */
    val poolAlpha: Float,
    /** Ambient shadow colour — the palette's darkness, not black. */
    val shadowAmbient: Color,
    /** Spot (directional) shadow colour. */
    val shadowSpot: Color,
    /** Multiplier on every elevation in the app: dark themes need less. */
    val shadowScale: Float,
    /** Top-edge light catch on a raised surface. */
    val rimLight: Color,
    /** Bottom-edge occlusion on a raised surface. */
    val rimShade: Color,
    /** Fill for a surface that sits *above* the page. */
    val raisedFill: Color,
    /** Fill for a surface pressed *into* the page. */
    val sunkenFill: Color,
    /** Translucent fill for bars, nav and sheets that sit over content. */
    val veilFill: Color,
    /** Hairline colour for structural rules and dividers. */
    val hairline: Color,
)

/**
 * Perceptual lightness, cheap. Used only to pick a lighting model, so the
 * sRGB-weighted approximation is sufficient and avoids a LAB conversion on
 * every recomposition.
 */
private fun luminanceOf(c: Color): Float =
    c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f

/** Pushes a colour toward its own saturated form without changing hue. */
private fun deepen(c: Color, amount: Float): Color =
    lerp(c, Color.Black, amount)

private fun lift(c: Color, amount: Float): Color =
    lerp(c, Color.White, amount)

/**
 * Derives the atmosphere for [colors]. Pure and cheap — remembered per palette
 * at the theme root, so screens read it for free.
 */
fun atmosphereFor(colors: FolioColors): FolioAtmosphere {
    val bgLuma = luminanceOf(colors.background)
    val dark = bgLuma < 0.45f

    // The field is the page itself: a vertical wash from a slightly lifted top
    // (where the light is) to a slightly deepened bottom. Kept under ~4% so it
    // never reads as a gradient — only as air.
    val fieldTop = if (dark) {
        lerp(colors.background, colors.surface, 0.55f)
    } else {
        lift(colors.background, 0.035f)
    }
    val fieldBottom = if (dark) {
        deepen(colors.background, 0.22f)
    } else {
        lerp(colors.background, colors.surfaceVariant, 0.45f)
    }

    // Colour pools come from the semantic accents, not primary, so a theme's
    // atmosphere carries the same hues its data visuals do (Rule 14).
    val pools = listOf(
        colors.accentProgress,
        colors.accentDiscovery,
        colors.accentStreak,
    )

    return FolioAtmosphere(
        isDark = dark,
        fieldTop = fieldTop,
        fieldBottom = fieldBottom,
        pools = pools,
        // Paper shows tint far more readily than a dark field absorbs it.
        poolAlpha = if (dark) 0.16f else 0.085f,
        // A shadow is absence of light, so it takes the palette's own deepest
        // neutral. Pure black on a warm cream page reads as a hole.
        shadowAmbient = if (dark) Color.Black else deepen(colors.onSurfaceVariant, 0.35f),
        shadowSpot = if (dark) Color.Black else deepen(colors.onSurface, 0.15f),
        shadowScale = if (dark) 0.55f else 1f,
        // Dark surfaces emit at the rim; light surfaces catch a white sheen.
        rimLight = if (dark) {
            lift(colors.surface, 0.30f).copy(alpha = 0.55f)
        } else {
            Color.White.copy(alpha = 0.85f)
        },
        rimShade = if (dark) {
            Color.Black.copy(alpha = 0.35f)
        } else {
            deepen(colors.outline, 0.10f).copy(alpha = 0.28f)
        },
        raisedFill = if (dark) {
            lerp(colors.surface, colors.surfaceVariant, 0.35f)
        } else {
            lift(colors.surface, 0.55f)
        },
        sunkenFill = if (dark) {
            deepen(colors.background, 0.35f)
        } else {
            lerp(colors.surfaceVariant, colors.background, 0.25f)
        },
        veilFill = if (dark) {
            deepen(colors.surface, 0.20f).copy(alpha = 0.82f)
        } else {
            lift(colors.surface, 0.35f).copy(alpha = 0.86f)
        },
        hairline = if (dark) {
            lift(colors.outline, 0.05f).copy(alpha = 0.30f)
        } else {
            colors.outline.copy(alpha = 0.42f)
        },
    )
}

/** The active atmosphere. Provided at the theme root; derived if absent. */
val FolioTheme.atmosphere: FolioAtmosphere
    @Composable
    get() {
        val colors = LocalFolioColors.current
        return remember(colors) { atmosphereFor(colors) }
    }
