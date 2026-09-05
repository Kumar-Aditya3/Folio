package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.folio.reader.settings.CustomAppTheme

/**
 * Expands a six-colour [CustomAppTheme] into the full [FolioColors] role set.
 *
 * Only the roles that *carry meaning* are authored; every structural role — inks,
 * variants, outlines, containers — is derived from them, because those roles are
 * relationships, not choices. `onSurfaceVariant` is not a colour a user should
 * pick, it is "the ink, muted toward the surface it sits on", and deriving it is
 * the only way a hand-made palette stays legible.
 *
 * Roles with no sensible derivation (error, secondary/tertiary containers, the
 * inverse family, chart hues) fall back to the built-in palette of the same
 * polarity, so a custom dark theme borrows dark-theme defaults rather than
 * light-theme ones.
 *
 * Nothing here touches the atmosphere: [atmosphereFor] reads `background`,
 * `surface`, `surfaceVariant` and the three accents, so the field gradient and
 * its colour pools regenerate from the custom colours automatically.
 */
fun CustomAppTheme.toFolioColors(): FolioColors {
    val bg = Color(background)
    val sf = Color(surface)
    val p = Color(primary)
    val dark = isDark
    val fallback = if (dark) DarkFolioColors else LightFolioColors

    // One ink for the whole theme. Picking it from polarity rather than from the
    // background's exact hue avoids tinted greys that read as a printing fault.
    val ink = if (dark) Color(0xFFE8EAED) else Color(0xFF202124)
    val extreme = if (dark) Color.White else Color.Black

    return fallback.copy(
        primary = p,
        onPrimary = if (luminance(p) > 0.5f) Color(0xFF10131A) else Color.White,
        primaryContainer = if (dark) lerp(p, sf, 0.68f) else lerp(p, Color.White, 0.78f),
        onPrimaryContainer = if (dark) lerp(p, Color.White, 0.72f) else lerp(p, Color.Black, 0.62f),
        background = bg,
        onBackground = ink,
        surface = sf,
        onSurface = ink,
        // Planes separate by lightness, one step at a time — the same ladder the
        // built-in palettes use (§15 Rule 22).
        surfaceVariant = lerp(sf, extreme, if (dark) 0.06f else 0.05f),
        onSurfaceVariant = lerp(ink, sf, 0.38f),
        surfaceContainerHighest = lerp(sf, extreme, if (dark) 0.11f else 0.10f),
        outline = lerp(ink, sf, 0.55f),
        outlineVariant = lerp(ink, sf, 0.78f),
        inverseSurface = ink,
        inverseOnSurface = bg,
        inversePrimary = if (dark) lerp(p, Color.Black, 0.35f) else lerp(p, Color.White, 0.45f),
        // The OS status ground: dark themes deepen their own field, light themes
        // take the ink so the icons drawn over it stay visible.
        statusBar = if (dark) lerp(bg, Color.Black, 0.30f) else ink,
        accentProgress = Color(accentProgress),
        accentDiscovery = Color(accentDiscovery),
        accentStreak = Color(accentStreak),
        // Annotation is the only accent with no slot in the editor; sitting it
        // between primary and streak keeps it distinct from all three pools.
        accentAnnotation = lerp(p, Color(accentStreak), 0.5f)
    )
}

/** Seeds the editor from an existing pack, so authoring starts from a real theme. */
fun customAppThemeFrom(palette: AppPalette): CustomAppTheme {
    val c = palette.colors
    return CustomAppTheme(
        name = palette.label,
        background = c.background.toArgbInt(),
        surface = c.surface.toArgbInt(),
        primary = c.primary.toArgbInt(),
        accentProgress = c.accentProgress.toArgbInt(),
        accentDiscovery = c.accentDiscovery.toArgbInt(),
        accentStreak = c.accentStreak.toArgbInt()
    )
}

/** Same weighting the atmosphere uses, so lighting and ink agree on polarity. */
private fun luminance(c: Color): Float =
    c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f

/**
 * `Color.toArgb()` lives in the Android/Skia layer of the graphics artifact; this
 * keeps the conversion in common code and drops any float precision we don't need.
 */
fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
