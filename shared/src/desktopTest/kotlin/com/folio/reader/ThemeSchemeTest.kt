package com.folio.reader

import com.folio.reader.settings.Theme
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTypography
import com.folio.reader.ui.theme.ThemePack
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The theme scheme contract. Reader presets, app palettes and the packs that pair
 * them are three parallel registries keyed by hand-written strings, and each one
 * has already drifted once: a preset absent from the picker lists is unselectable,
 * a palette absent from ThemePack.ALL is unreachable, and a pack pointing at a
 * missing id silently falls back to Paper. These are the invariants the UI relies
 * on to stay honest when a theme is added.
 */
class ThemeSchemeTest {

    @Test
    fun everyPresetIsWellFormed() {
        for ((key, theme) in Theme.PRESETS) {
            assertEquals(key, theme.id, "preset declared under key \"$key\" carries id \"${theme.id}\"")
            assertEquals(8, theme.highlightColors.size, "${theme.id} must declare exactly 8 highlight colours")
            assertTrue(theme.name.isNotBlank(), "${theme.id} has no display name")
        }
    }

    @Test
    fun everyPresetIsSelectable() {
        // A preset the pickers do not list cannot be chosen on either platform.
        assertEquals(Theme.PRESETS.keys.toSet(), Theme.PICKER.map { it.id }.toSet())
    }

    @Test
    fun everyPackResolvesOnBothSides() {
        for (pack in ThemePack.ALL) {
            assertTrue(Theme.PRESETS.containsKey(pack.readerThemeId),
                "${pack.id} points at missing reader theme \"${pack.readerThemeId}\"")
            assertTrue(AppPalette.entries.any { it.id == pack.appPaletteId },
                "${pack.id} points at missing app palette \"${pack.appPaletteId}\"")
        }
        assertEquals(ThemePack.ALL.size, ThemePack.ALL.map { it.id }.distinct().size,
            "two packs share an id, so one of them can never be applied")
    }

    @Test
    fun everyAppPaletteIsReachableThroughAPack() {
        // ThemePack.ALL is the only theme picker in SettingsScreen, so an orphan
        // palette is one the user can never select.
        val packed = ThemePack.ALL.map { it.appPaletteId }.toSet()
        val orphans = AppPalette.entries.map { it.id }.filterNot { it in packed }
        assertTrue(orphans.isEmpty(), "app palettes with no theme pack: $orphans")
    }

    @Test
    fun readerAndAppDarknessAgreePerPack() {
        // A light page under dark chrome (or the reverse) is the mismatch a pack is
        // supposed to prevent; the whole point of pairing them is that they agree.
        for (pack in ThemePack.ALL) {
            val appDark = AppPalette.byId(pack.appPaletteId).isDark
            val readerDark = Theme.PRESETS.getValue(pack.readerThemeId).isDark
            assertEquals(appDark, readerDark, "${pack.id} pairs ${pack.appPaletteId} with ${pack.readerThemeId}")
        }
    }

    // ── §12.3 semantic accent roles ─────────────────────────────────────────
    //
    // Every palette must assign the four accent roles explicitly, keep them
    // visually distinct from one another, and keep each legible on its surface.
    // A palette that falls back to primary/tertiary for an accent makes the
    // progress ring, streak, discovery and annotation colours indistinguishable.

    @Test
    fun everyPaletteAssignsAccentRolesExplicitly() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            assertTrue(c.accentProgress != c.primary,
                "${palette.id}: accentProgress fell back to primary")
            assertTrue(c.accentStreak != c.primary,
                "${palette.id}: accentStreak fell back to primary")
            assertTrue(c.accentDiscovery != c.tertiary,
                "${palette.id}: accentDiscovery fell back to tertiary")
            assertTrue(c.accentAnnotation != c.primary,
                "${palette.id}: accentAnnotation fell back to primary")
        }
    }

    @Test
    fun accentRolesArePairwiseDistinctPerPalette() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            val accents = mapOf(
                "progress" to c.accentProgress,
                "streak" to c.accentStreak,
                "discovery" to c.accentDiscovery,
                "annotation" to c.accentAnnotation,
            )
            val roles = accents.keys.toList()
            for (i in roles.indices) for (j in i + 1 until roles.size) {
                val a = accents.getValue(roles[i])
                val b = accents.getValue(roles[j])
                val dE = deltaE(a, b)
                assertTrue(dE >= 10.0,
                    "${palette.id}: accent${roles[i]} vs accent${roles[j]} ΔE=$dE — too similar to tell apart")
            }
        }
    }

    @Test
    fun accentRolesMeetContrastOnTheirSurface() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            val pairs = listOf(
                "progress" to (c.accentProgress to c.surface),
                "streak" to (c.accentStreak to c.surface),
                "discovery" to (c.accentDiscovery to c.surface),
                "annotation" to (c.accentAnnotation to c.surface),
            )
            for ((role, fgBg) in pairs) {
                val (fg, bg) = fgBg
                val ratio = wcagRatio(fg.toArgb(), bg.toArgb())
                assertTrue(ratio >= 4.5,
                    "${palette.id}: accent$role on surface is $ratio:1 — below WCAG AA")
            }
        }
    }

    @Test
    fun chartSeriesPaletteIsUsableForMultiSeriesCharts() {
        // §12.6: every palette carries the shared reader-highlighter chart hues;
        // the first six — the genre breakdown's cap — must be pairwise tellable
        // apart, or adjacent genre rows would read as the same series.
        for (palette in AppPalette.entries) {
            val series = palette.colors.chartSeries
            assertTrue(series.size == 8,
                "${palette.id}: chartSeries must carry 8 hues, got ${series.size}")
            for (i in 0 until 6) for (j in i + 1 until 6) {
                val dE = deltaE(series[i], series[j])
                assertTrue(dE >= 10.0,
                    "${palette.id}: chartSeries[$i] vs [$j] ΔE=$dE — genre rows would collide")
            }
        }
    }

    // ── §15 Rules 22–24: neutral hue variance, text split, weight ladder ───

    // §15.5: palettes that are achromatic by design satisfy Rule 22 through the
    // luminance clause instead of hue rotation. The list lives here, not in
    // Theme.kt, so adding a palette to it is a visible, reviewable decision.
    // DARK joins OLED: a #000000 base cannot carry hue at all.
    // GRAPHITE is the deliberate zero-hue palette (planes separate by ΔL* ≈ 4.4).
    // BLOSSOM pairs a true-black background with a warm-shifted surface (ΔL* ≈ 5.1):
    // the background carries no hue, so the pair rides the luminance clause too.
    private val achromaticByDesign = setOf("light", "dark", "oled", "graphite", "blossom")

    @Test
    fun neutralsVaryInHueNotOnlyLightness() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            if (palette.id in achromaticByDesign) {
                // L* is derived from relative luminance but perceptually even from
                // true black to white, where a raw ΔY is not — the spec's own
                // Graphite background/surface pair sits at ΔL* ≈ 4.4, while a 0.04
                // raw ΔY would push OLED's card plane to mid-grey and destroy the
                // true-black theme the spec preserves.
                val dl = abs(lab(c.surface.toArgb()).first - lab(c.background.toArgb()).first)
                assertTrue(dl >= 4.0,
                    "${palette.id}: achromatic background/surface ΔL*=$dl — the planes are indistinguishable")
            } else {
                val hb = hueDegrees(c.background)
                val hs = hueDegrees(c.surface)
                assertTrue(hb != null && hs != null,
                    "${palette.id}: background or surface is achromatic but not in the allowlist — " +
                        "add it to achromaticByDesign or give the ramp a hue")
                val d = hueDistance(hb!!, hs!!)
                assertTrue(d >= 8.0,
                    "${palette.id}: background/surface hue Δ=${"%.1f".format(d)}° — one hue at several lightnesses (§15 Finding A)")
            }
        }
    }

    @Test
    fun primaryAndSecondaryTextAreDistinct() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            assertTrue(c.onSurface != c.onSurfaceVariant,
                "${palette.id}: onSurface and onSurfaceVariant are the same colour")
            val dE = deltaE(c.onSurface, c.onSurfaceVariant)
            assertTrue(dE >= 12.0,
                "${palette.id}: onSurface vs onSurfaceVariant ΔE=$dE — secondary text competes with primary")
        }
    }

    @Test
    fun contrastTargetsPerPalette() {
        // §15.2's minimum column, enforced on every palette; the target column
        // (≥10:1, 5.5–7:1, 2–3:1) is what ramp tuning aims for above these floors.
        for (palette in AppPalette.entries) {
            val c = palette.colors
            fun ratio(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) =
                wcagRatio(fg.toArgb(), bg.toArgb())
            val onSurface = ratio(c.onSurface, c.surface)
            val onBackground = ratio(c.onBackground, c.background)
            val onVariant = ratio(c.onSurfaceVariant, c.surface)
            val outline = ratio(c.outline, c.surface)
            assertTrue(onSurface >= 7.0,
                "${palette.id}: onSurface/surface is ${"%.2f".format(onSurface)}:1 — below the 7:1 floor")
            assertTrue(onBackground >= 7.0,
                "${palette.id}: onBackground/background is ${"%.2f".format(onBackground)}:1 — below the 7:1 floor")
            assertTrue(onVariant >= 4.5,
                "${palette.id}: onSurfaceVariant/surface is ${"%.2f".format(onVariant)}:1 — below WCAG AA")
            assertTrue(outline >= 1.5,
                "${palette.id}: outline/surface is ${"%.2f".format(outline)}:1 — card rims vanish")
            assertTrue(onVariant < onSurface,
                "${palette.id}: onSurfaceVariant (${onVariant}:1) must sit below onSurface (${onSurface}:1) — " +
                    "secondary text is deliberately subordinate")
        }
    }

    @Test
    fun typographySpansAtLeastThreeWeights() {
        // Rule 24 + §15.3.1: display lightest, labels heaviest — the ladder that
        // makes editorial hierarchy, not twelve styles of W600.
        val t = FolioTypography()
        for (style in listOf(t.displayLarge, t.displayMedium, t.displaySmall)) {
            assertEquals(FontWeight.W300, style.fontWeight, "display styles must use the lightest weight (W300)")
        }
        for (style in listOf(t.headlineLarge, t.headlineMedium, t.headlineSmall,
                t.titleLarge, t.titleMedium, t.titleSmall)) {
            assertEquals(FontWeight.W600, style.fontWeight, "headline/title styles stay semibold")
        }
        for (style in listOf(t.bodyLarge, t.bodyMedium, t.bodySmall)) {
            assertEquals(FontWeight.Normal, style.fontWeight, "body styles stay regular")
        }
        for (style in listOf(t.labelLarge, t.labelMedium, t.labelSmall)) {
            assertEquals(FontWeight.W700, style.fontWeight, "label styles must use the heaviest weight (W700)")
        }
        val span = setOf(t.displaySmall.fontWeight, t.titleLarge.fontWeight,
            t.bodyMedium.fontWeight, t.labelLarge.fontWeight)
        assertTrue(span.size >= 3, "type scale spans ${span.size} weights — Rule 24 wants at least three")
    }

    // ── colour math ─────────────────────────────────────────────────────────

    private fun deltaE(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val (l1, a1, b1) = lab(a.toArgb())
        val (l2, a2, b2) = lab(b.toArgb())
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    /** sRGB → CIELAB, returning (L, a, b). */
    private fun lab(argb: Int): Triple<Double, Double, Double> {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(argb shr 16 and 0xFF)
        val g = channel(argb shr 8 and 0xFF)
        val b = channel(argb and 0xFF)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun wcagRatio(fg: Int, bg: Int): Double {
        val l1 = relLum(fg)
        val l2 = relLum(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    /** HSV hue in degrees [0, 360), or null when the colour carries no hue (≤ 1/255 of channel span). */
    private fun hueDegrees(c: androidx.compose.ui.graphics.Color): Double? {
        val mx = maxOf(c.red, c.green, c.blue)
        val mn = minOf(c.red, c.green, c.blue)
        if (mx - mn < 1.0 / 255.0) return null
        val d = mx - mn
        return when (mx) {
            c.red -> 60.0 * (((c.green - c.blue) / d + 6.0) % 6.0)
            c.green -> 60.0 * ((c.blue - c.red) / d + 2.0)
            else -> 60.0 * ((c.red - c.green) / d + 4.0)
        }
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b)
        return minOf(d, 360.0 - d)
    }

    private fun relLum(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int {
        val c = this
        return (0xFF shl 24) or
            ((kotlin.math.round(c.red * 255).toInt() and 0xFF) shl 16) or
            ((kotlin.math.round(c.green * 255).toInt() and 0xFF) shl 8) or
            (kotlin.math.round(c.blue * 255).toInt() and 0xFF)
    }
}
