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
    /**
     * Ground under the OS status icons. Follows the field, not a fixed ink band:
     * a light palette gets a light ground (with dark icons over it), a dark one
     * gets a dark ground. Drawn with the alpha carried here.
     */
    val barScrim: Color,
    /**
     * Glass fill for the app's top bar and nav. Deliberately far more
     * transparent than [veilFill] — a bar over *Compose* content should let that
     * content show through, and only the reader's native surface needs opacity.
     */
    val barGlass: Color,
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

// ── Region tinting (straight-line sRGB channel math, kept deterministic so the
// ThemeSchemeTest-adjacent contrast checks can replicate it exactly) ─────────
// The problem these solve: raised/panel/sunken all derive from surface±lightness,
// so within one theme a hero, a card and a well read as the same hue at three
// brightnesses. Tinting the raised plane toward the palette's primary and the
// sunken plane toward its tertiary (the counter-accent) gives each region its
// own temperature — iconic — while every tint is drawn from the theme's own
// roles, so it still blends. matchLuma pins the tinted result back to the
// untinted base's luminance, so text contrast on each plane is unchanged.
private fun mixG(a: Color, b: Color, t: Float): Color = Color(
    red = (a.red + (b.red - a.red) * t).coerceIn(0f, 1f),
    green = (a.green + (b.green - a.green) * t).coerceIn(0f, 1f),
    blue = (a.blue + (b.blue - a.blue) * t).coerceIn(0f, 1f),
)

private fun liftG(c: Color, amount: Float): Color = mixG(c, Color.White, amount)

private fun deepenG(c: Color, amount: Float): Color = mixG(c, Color.Black, amount)

private fun matchLuma(c: Color, target: Float): Color {
    val l = luminanceOf(c)
    if (l <= 0.0001f) return c
    val k = target / l
    return Color(
        red = (c.red * k).coerceIn(0f, 1f),
        green = (c.green * k).coerceIn(0f, 1f),
        blue = (c.blue * k).coerceIn(0f, 1f),
    )
}

/** Pulls [base] toward [toward] in hue, then restores base luminance. */
private fun tintFill(base: Color, toward: Color, amount: Float): Color =
    matchLuma(mixG(base, toward, amount), luminanceOf(base))

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
        // Raised planes (the floating heroes) carry a primary-hue tint, so the
        // showcase surface reads as the theme's signature colour — not just a
        // brighter card. Luminance is pinned to the untinted lift, so ink keeps
        // its contrast.
        raisedFill = if (dark) {
            tintFill(mixG(colors.surface, colors.surfaceVariant, 0.35f), colors.primary, 0.12f)
        } else {
            tintFill(liftG(colors.surface, 0.55f), colors.primary, 0.12f)
        },
        // Sunken planes (charts, wells, heatmaps) lean toward the tertiary
        // counter-accent, so a recessed region reads as a different temperature
        // from both the resting card and the raised hero.
        sunkenFill = if (dark) {
            tintFill(deepenG(colors.background, 0.35f), colors.tertiary, 0.14f)
        } else {
            tintFill(mixG(colors.surfaceVariant, colors.background, 0.25f), colors.tertiary, 0.14f)
        },
        // Reader controls can sit directly above a page rendered by a native
        // surface. Keep the palette's surface character, but make the veil
        // effectively opaque so page text never competes with menu text.
        veilFill = if (dark) {
            deepen(colors.surface, 0.20f).copy(alpha = 0.97f)
        } else {
            lift(colors.surface, 0.35f).copy(alpha = 0.98f)
        },
        // The status icons need a ground, but a fixed dark ink band put a black
        // stripe across the top of every light theme. Take it from the field
        // instead: paper gets a paper-white ground and dark icons, a dark field
        // keeps its deep ground and light icons.
        //
        // Held low on purpose. Nothing scrolls *under* this band — the app bars sit
        // above their content, so what the scrim covers is the page's own field,
        // which already contrasts with the icons the OS draws over it. Anything
        // heavier is a painted band, and that band was most of what made the
        // masthead read as a lid.
        barScrim = if (dark) {
            deepen(colors.background, 0.30f).copy(alpha = 0.46f)
        } else {
            lift(colors.surface, 0.72f).copy(alpha = 0.42f)
        },
        // Bars sit over Compose content, so they can be actual glass. Three rules
        // here, all learned the hard way:
        //
        //  - the tint is taken from the *field*, not from `surface`. A surface-
        //    coloured bar over a background-coloured page is a different object
        //    from the page no matter how low its alpha goes, which is exactly what
        //    made the collapsed masthead read as a grey lid;
        //  - the alpha stays nearer a third than a half. Past that the fill starts
        //    describing its own rectangle, and the eye reads a rectangle as a panel;
        //  - depth is carried by the hairline and the fade below it (FolioTopBar),
        //    never by making the fill heavier.
        barGlass = if (dark) {
            lerp(fieldTop, colors.surface, 0.35f).copy(alpha = 0.36f)
        } else {
            lift(fieldTop, 0.42f).copy(alpha = 0.40f)
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
