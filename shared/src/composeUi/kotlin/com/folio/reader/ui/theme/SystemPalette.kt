package com.folio.reader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.folio.reader.settings.CustomAppTheme
import com.folio.reader.ui.components.legibleOn
import com.folio.reader.ui.components.rgbToHsv
import kotlin.math.max
import kotlin.math.min

/**
 * §16 Material You: the platform's wallpaper-derived schemes, (light, dark),
 * or null where the platform cannot derive them (below API 31, and always on
 * desktop — Rule 1). Provided by the Android root; the theme picker reads it
 * to decide whether the System pack is offered, and the host resolves
 * "system"/"systemdark" ids against it before falling back to the palette
 * entries. Wallpaper changes broadcast a configuration change, which recreates
 * the provider — the schemes are recomputed, never cached across them.
 */
val LocalDynamicSchemes = staticCompositionLocalOf<Pair<ColorScheme, ColorScheme>?> { null }

/** The platform source of [LocalDynamicSchemes]. */
@Composable
expect fun rememberDynamicSchemes(): Pair<ColorScheme, ColorScheme>?

/**
 * §16 Material You: derives the app's colour system from a wallpaper-derived
 * [ColorScheme] (`dynamicLightColorScheme` / `dynamicDarkColorScheme` on API 31+).
 *
 * The wallpaper is art, not a palette: Material You hands over hues that can be
 * nearly identical, or nearly invisible on their own surfaces. So nothing passes
 * through raw — the same rules the built-in palettes are held to
 * (ThemeSchemeTest) are enforced here by construction:
 *
 *  - the three accents come from the scheme's primary/tertiary/error hues, kept
 *    pairwise-distinct (clashing hues rotate apart), then forced to ≥ 4.5:1 on
 *    the surface through the shared [legibleOn] guard;
 *  - the ink is forced to ≥ 7:1 on the surface (the §15 reading floor);
 *  - the derived annotation (the lerp of primary and streak in
 *    `toFolioColors`) is checked for distinctness and nudged if a wallpaper
 *    landed it on another accent.
 *
 * Pure and deterministic: desktop tests feed synthetic schemes (a normal
 * blue-green scheme, a monochrome one whose three hues coincide, a dark one) and
 * assert the invariants — no device required.
 */
fun deriveSystemPalette(scheme: ColorScheme): FolioColors {
    val bg = scheme.background
    val sf = scheme.surface
    val dark = relativeLuminance(sf) < 0.5

    // Primary anchors the accent set; the other roles come from the scheme's
    // tertiary and error hues and must earn their places (see [reconciled]).
    val primary = scheme.primary
    val ink = if (dark) Color(0xFFE8EAED) else Color(0xFF202124)

    val legibleProgress = legibleOn(primary, sf, ink)
    val legibleDiscovery = reconciled(scheme.tertiary, listOf(primary, legibleProgress), sf, ink)
    val legibleStreak = reconciled(
        scheme.error,
        listOf(primary, legibleProgress, legibleDiscovery),
        sf, ink,
    )

    val colors = CustomAppTheme(
        name = "System",
        background = bg.toArgbInt(),
        surface = sf.toArgbInt(),
        primary = primary.toArgbInt(),
        accentProgress = legibleProgress.toArgbInt(),
        accentDiscovery = legibleDiscovery.toArgbInt(),
        accentStreak = legibleStreak.toArgbInt(),
    ).toFolioColors()

    // `toFolioColors` sits annotation between primary and streak; on a
    // two-hue wallpaper that midpoint can collide with an accent, so it gets
    // the same distinctness treatment as the authored three.
    val legibleAnnotation = reconciled(
        colors.accentAnnotation,
        listOf(primary, legibleProgress, legibleDiscovery, legibleStreak),
        sf, ink,
    )

    // The reading floor: the fixed inks must clear 7:1 on the dynamic surface;
    // a wallpaper whose surface sits near mid-grey is the one case where they
    // don't, so they are pushed toward their extreme until they do.
    val onInk = inkPushedToFloor(ink, sf, dark)

    return colors.copy(
        accentAnnotation = legibleAnnotation,
        onBackground = onInk,
        onSurface = onInk,
    )
}

/**
 * One accent, forced to earn its place: it must be tellable apart from every
 * [kept] colour (ΔE ≥ 10, the same bar ThemeSchemeTest holds the built-in
 * palettes to) while staying legible on the surface. The contrast blend toward
 * the ink can pull two rotated hues back together, so distinctness is re-checked
 * *after* the guard, and a clashing candidate rotates (90° steps, then re-guard)
 * until it clears both bars.
 */
private fun reconciled(candidate: Color, kept: List<Color>, sf: Color, ink: Color): Color {
    var rotated = candidate
    for (offset in listOf(0f, 90f, 180f, 270f)) {
        if (offset > 0f) rotated = rotateHue(rotated, offset)
        val legible = legibleOn(rotated, sf, ink)
        if (kept.all { deltaE(it, legible) >= 10.0 }) return legible
    }
    return legibleOn(rotated, sf, ink)
}

/** Rotates a colour's hue by [degrees], keeping its saturation and value. */
private fun rotateHue(color: Color, degrees: Float): Color {
    val (h, s, v) = rgbToHsv(color.toArgbInt())
    return Color.hsv(hue = (h + degrees) % 360f, saturation = s, value = v)
}

/** CIE76 colour difference — the same measure ThemeSchemeTest asserts with. */
private fun deltaE(a: Color, b: Color): Double {
    val (l1, a1, b1) = lab(a)
    val (l2, a2, b2) = lab(b)
    return kotlin.math.sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
}

/** sRGB → CIELAB. */
private fun lab(c: Color): Triple<Double, Double, Double> {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    val r = channel(c.red)
    val g = channel(c.green)
    val b = channel(c.blue)
    val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    val y = r * 0.2126 + g * 0.7152 + b * 0.0722
    val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
    fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
    val fx = f(x); val fy = f(y); val fz = f(z)
    return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

/** The §15 floor: ≥ 7:1 on the surface, pushing toward the polarity's extreme. */
private fun inkPushedToFloor(ink: Color, surface: Color, dark: Boolean): Color {
    val extreme = if (dark) Color.White else Color.Black
    var current = ink
    if (contrastRatioOrMin(current, surface) >= 7.0) return current
    for (step in 1..20) {
        current = lerp(ink, extreme, step / 20f)
        if (contrastRatioOrMin(current, surface) >= 7.0) return current
    }
    return extreme
}

private fun contrastRatioOrMin(a: Color, b: Color): Double {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    fun lum(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    val la = lum(a)
    val lb = lum(b)
    val lighter = max(la, lb)
    val darker = min(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}
