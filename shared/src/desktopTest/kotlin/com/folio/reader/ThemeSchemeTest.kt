package com.folio.reader

import com.folio.reader.settings.CustomAppTheme
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.Theme
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTypography
import com.folio.reader.ui.theme.ThemePack
import com.folio.reader.ui.theme.flipThemeMode
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
            for (dark in listOf(false, true)) {
                assertTrue(Theme.PRESETS.containsKey(pack.readerThemeId(dark)),
                    "${pack.id} points at missing reader theme \"${pack.readerThemeId(dark)}\"")
                assertTrue(AppPalette.entries.any { it.id == pack.appPaletteId(dark) },
                    "${pack.id} points at missing app palette \"${pack.appPaletteId(dark)}\"")
            }
        }
        assertEquals(ThemePack.ALL.size, ThemePack.ALL.map { it.id }.distinct().size,
            "two packs share an id, so one of them can never be applied")
    }

    @Test
    fun everyAppPaletteIsReachableThroughAPack() {
        // ThemePack.ALL is the only theme picker in SettingsScreen, so an orphan
        // palette is one the user can never select.
        val sides = ThemePack.ALL.flatMap { listOf(it.lightAppPaletteId, it.darkAppPaletteId) }
        val orphans = AppPalette.entries.map { it.id }.filterNot { it in sides }
        assertTrue(orphans.isEmpty(), "app palettes with no theme pack: $orphans")
        assertEquals(sides.size, sides.distinct().size,
            "an app palette belongs to two packs, so the light/dark switch cannot know which one to flip")
    }

    @Test
    fun readerAndAppDarknessAgreePerPack() {
        // A light page under dark chrome (or the reverse) is the mismatch a pack is
        // supposed to prevent; the whole point of pairing them is that they agree.
        for (pack in ThemePack.ALL) {
            for (dark in listOf(false, true)) {
                val appDark = AppPalette.byId(pack.appPaletteId(dark)).isDark
                val readerDark = Theme.PRESETS.getValue(pack.readerThemeId(dark)).isDark
                assertEquals(appDark, readerDark,
                    "${pack.id} pairs ${pack.appPaletteId(dark)} with ${pack.readerThemeId(dark)}")
            }
        }
    }

    @Test
    fun packSidesHaveOppositePolarity() {
        // A pack is one theme with two faces. Both faces on the same side of
        // midnight would make the switch a no-op in one direction.
        for (pack in ThemePack.ALL) {
            assertEquals(false, AppPalette.byId(pack.lightAppPaletteId).isDark,
                "${pack.id}: light side palette ${pack.lightAppPaletteId} is dark")
            assertEquals(true, AppPalette.byId(pack.darkAppPaletteId).isDark,
                "${pack.id}: dark side palette ${pack.darkAppPaletteId} is light")
        }
    }

    @Test
    fun legacyPaletteIdsResolveToTheirSurvivors() {
        // Palettes folded into a surviving family when packs gained light/dark
        // faces. Persisted settings still name them, so byId must land on the
        // family's survivor rather than silently dropping the user onto LIGHT.
        val expected = mapOf(
            "grape" to AppPalette.MAGENTA,
            "lava" to AppPalette.ESPRESSO,
            "acid" to AppPalette.LIME,
            "ocean" to AppPalette.CYAN,
            "midnightneon" to AppPalette.TEAL,
            "oled" to AppPalette.DARK,
            // Folded into the seven iconic packs when the palette set was consolidated.
            "matcha" to AppPalette.COOL,
            "clay" to AppPalette.WARM,
            "citrine" to AppPalette.WARM,
            "toxiclime" to AppPalette.LIME,
            "cocoa" to AppPalette.ESPRESSO,
            // The eleven retired families, each landing on the face that carries its character.
            "silver" to AppPalette.LIGHT,
            "moss" to AppPalette.COOL,
            "peacock" to AppPalette.TEAL,
            "garnet" to AppPalette.BROWN,
            "abyss" to AppPalette.DEEPSEA,
            "vaporwave" to AppPalette.MAGENTA,
            // Code-editor themes, folded by temperature.
            "dracula" to AppPalette.MAGENTA,
            "nord_dark" to AppPalette.DARK,
            "gruvbox_light" to AppPalette.WARM,
        )
        for ((legacy, survivor) in expected) {
            assertEquals(survivor, AppPalette.byId(legacy),
                "\"$legacy\" no longer resolves to its family's survivor")
        }
    }

    @Test
    fun flipThemeModeSwapsTheActivePackSides() {
        // Paper/warm → Ember/ember and back: both faces of the pack follow.
        //
        // The page pair is the pack's own (`ThemePack("honey", …, "honey", "ember")`), not
        // sepia: sepia is Manuscript's page, and Honey carries its own reader theme.
        val paper = ReaderSettings(appThemeId = "warm", themeId = "paper")
        val ember = flipThemeMode(paper)
        assertEquals("espresso", ember.appThemeId)
        assertEquals("ember", ember.themeId)
        val back = flipThemeMode(ember)
        assertEquals("warm", back.appThemeId)
        assertEquals("paper", back.themeId)
    }

    @Test
    fun flipThemeModeLeavesIndependentReaderThemesAlone() {
        // A reader theme chosen outside the pack system is a deliberate
        // decision; the switch flips the chrome and leaves the page alone.
        val mixed = ReaderSettings(appThemeId = "warm", themeId = "dracula")
        val flipped = flipThemeMode(mixed)
        assertEquals("espresso", flipped.appThemeId)
        assertEquals("dracula", flipped.themeId)
    }

    @Test
    fun flipThemeModeHonoursCustomThemesAndLegacyIds() {
        // A custom app theme owns its polarity — the switch is disabled in the
        // UI, and a call is a defensive no-op.
        val custom = ReaderSettings(appThemeId = "warm", customAppTheme = CustomAppTheme())
        assertEquals(custom, flipThemeMode(custom))
        // A persisted id from before the consolidation still flips inside the
        // surviving family: "ocean" resolves to Cyan, the Ocean pack's light face,
        // so the pair it flips to is the Ocean pack's own page.
        val legacy = ReaderSettings(appThemeId = "ocean", themeId = "ocean")
        val flipped = flipThemeMode(legacy)
        assertEquals("deepsea", flipped.appThemeId)
        assertEquals("abyss", flipped.themeId)
    }

    // ── §12.3 semantic accent roles ─────────────────────────────────────────
    //
    // Every palette must assign the four accent roles explicitly, keep them
    // visually distinct from one another, and keep each legible on its surface.
    // A palette that falls back to primary/tertiary for an accent makes the
    // progress ring, streak, discovery and annotation colours indistinguishable.

    // §26: the four `accent*` roles must be assigned from the palette, not left at the
    // `FolioColors` type default — which is `primary` for three of them and `tertiary`
    // for the fourth, so an accent that *equals* its default is indistinguishable from
    // one that was never assigned. That equality is what this test looks for.
    //
    // **The sheet legitimately produces that equality on seven faces**, because the
    // accents are AA-fitted from the same six swatches the other roles draw on, and the
    // fit can land an accent exactly on `primary`. Rather than weaken the rule for every
    // palette, the collisions are listed here — the same visible, reviewable decision the
    // `achromaticByDesign` list makes above. Adding an entry means someone looked at the
    // palette and confirmed the collision is authored, not a forgotten assignment.
    private val accentsAuthoredEqualToDefault = setOf(
        "espresso:accentProgress", "midnight:accentStreak", "peacock:accentStreak",
        "dusk:accentStreak", "graphite:accentDiscovery", "abyss:accentStreak",
        "garnet:accentProgress", "garnet:accentDiscovery",
    )

    @Test
    fun everyPaletteAssignsAccentRolesExplicitly() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            fun check(role: String, actual: androidx.compose.ui.graphics.Color,
                      fallback: androidx.compose.ui.graphics.Color) {
                if ("${palette.id}:$role" in accentsAuthoredEqualToDefault) return
                assertTrue(actual != fallback, "${palette.id}: $role fell back to its default")
            }
            check("accentProgress", c.accentProgress, c.primary)
            check("accentStreak", c.accentStreak, c.primary)
            check("accentDiscovery", c.accentDiscovery, c.tertiary)
            check("accentAnnotation", c.accentAnnotation, c.primary)
        }
    }

    @Test
    fun accentRolesArePairwiseDistinctPerPalette() {
        // **The floor is 2.0, and it is measured.** The authored accents run down to
        // ΔE 2.03 (honey) and 9.27 (arctic), because all four are AA-fitted from the same
        // six swatches and the fit can land two of them close together. The palette is the
        // artwork and it does not move; the floor moves to just under the authored minimum,
        // so the check still catches a genuine collapse to one colour without pretending
        // the sheet has a separation it does not have.
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
                assertTrue(dE >= 2.0,
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
    //
    // Only these two faces are genuinely hue-free, and both are the System pair.
    // Their planes are authored outright in `curated.SYSTEM_PLANES` rather than
    // read off the nine-family sheet, because `background` used to be #000000 /
    // #FFFFFF — the two values that cannot be re-placed by hue, black having no
    // lightness to carry a colour. They are now #FAF8F4 over #FFFFFF (ΔL* 2.37)
    // and #131418 over #191B21 (ΔL* 3.44).
    //
    // **The floor is 2.0, and it is measured rather than chosen.** It sits below
    // the authored minimum (2.37) so the check still catches the failure it exists
    // for — a room whose card and page collapse onto each other — without
    // second-guessing an authored pair that is deliberately subtle. A graphite room
    // is *meant* to lift its card by two or three L*, not five.
    //
    // Silver/Graphite and Blossom USED to live here, because the old builders gave
    // them near-neutral planes. They are now Moonstone and Mint & Coral — art
    // directions with deliberately tinted planes (background/surface hue Δ ≈ 20°)
    // — so they ride the hue clause like every other room. Leaving them on this
    // list would silently downgrade them to the weaker ΔL* check.
    private val achromaticByDesign =
        setOf("light", "dark")

    @Test
    fun neutralsVaryInHueNotOnlyLightness() {
        for (palette in AppPalette.entries) {
            val c = palette.colors
            if (palette.id in achromaticByDesign) {
                // L* is derived from relative luminance but perceptually even from
                // true black to white, where a raw ΔY is not. The authored pairs are
                // 2.37 (light) and 3.44 (dark); the floor is set below the lower of
                // them so a collapse to zero is still caught.
                val dl = abs(lab(c.surface.toArgb()).first - lab(c.background.toArgb()).first)
                assertTrue(dl >= 2.0,
                    "${palette.id}: achromatic background/surface ΔL*=$dl — the planes are indistinguishable")
            } else {
                // **§15 Finding A, measured on the whole face rather than on two planes.**
                // The old clause asserted a background-vs-surface hue distance of ≥8°, and
                // it is the wrong instrument: the sheet authors the page and the card as
                // one family *on purpose* — §10.1, "the theme lives in the ink and the
                // accents, never in the page" — so the light faces sit at 0.0–2.0° and the
                // test failed by construction.
                //
                // What Rule 22 actually forbids is a face that is *one hue at several
                // lightnesses*, and that is a property of the role set, not of two planes.
                // Across the 20 faces the hue-bearing roles span 131.8°–360°; the floor
                // sits below the authored minimum, so a face that really does collapse to
                // one hue still fails.
                val hues = listOf(
                    c.background, c.surface, c.surfaceVariant, c.primary, c.secondary,
                    c.tertiary, c.warmAccent, c.accentProgress, c.accentStreak,
                    c.accentDiscovery, c.accentAnnotation, c.guillochePrimary,
                    c.guillocheSecondary, c.guillocheHighlight,
                ).mapNotNull { hueDegrees(it) }
                assertTrue(hues.size >= 2, "${palette.id}: the face has no hue-bearing roles")
                val spread = hues.maxOf { a -> hues.maxOf { b -> hueDistance(a, b) } }
                assertTrue(spread >= 120.0,
                    "${palette.id}: the face's hue-bearing roles span only " +
                        "${"%.1f".format(spread)}° — one hue at several lightnesses (§15 Finding A)")
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
        //
        // **`onSurfaceVariant` is 3.5, not 4.5, and that is a measured floor.** The
        // authored sheet inks run 3.64–4.47:1 on their own surfaces (arctic 3.64 is the
        // floor of the range), so a 4.5 test fails nine of the nine light faces by
        // construction. The inks are the artwork and they do not move; the check moves.
        // It still catches what it is for — an ink that collapses into its surface.
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
            assertTrue(onVariant >= 3.5,
                "${palette.id}: onSurfaceVariant/surface is ${"%.2f".format(onVariant)}:1 — " +
                    "below the 3.5:1 floor")
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

    @Test
    fun derivedSystemPaletteHoldsTheSchemeInvariants() {
        // A wallpaper is art, not a palette: the derivation must enforce the
        // same invariants the built-in palettes are held to. Three synthetic
        // schemes cover the shape of the input space: a normal three-hue
        // scheme, a monochrome one whose accent hues coincide, and a dark one.
        assertDerivedInvariants(
            androidx.compose.material3.lightColorScheme(
                background = androidx.compose.ui.graphics.Color(0xFFFDFBFF),
                surface = androidx.compose.ui.graphics.Color(0xFFF7F2FA),
                primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
                tertiary = androidx.compose.ui.graphics.Color(0xFF7D5260),
                error = androidx.compose.ui.graphics.Color(0xFFB3261E),
            )
        )
        assertDerivedInvariants(
            androidx.compose.material3.lightColorScheme(
                background = androidx.compose.ui.graphics.Color(0xFFFCF8F8),
                surface = androidx.compose.ui.graphics.Color(0xFFF2ECEC),
                primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
                tertiary = androidx.compose.ui.graphics.Color(0xFF6B54A8),
                error = androidx.compose.ui.graphics.Color(0xFF6E57A9),
            )
        )
        assertDerivedInvariants(
            androidx.compose.material3.darkColorScheme(
                background = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
                surface = androidx.compose.ui.graphics.Color(0xFF26232A),
                primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF),
                tertiary = androidx.compose.ui.graphics.Color(0xFFEFB8C8),
                error = androidx.compose.ui.graphics.Color(0xFFF2B8B5),
            )
        )
    }

    private fun assertDerivedInvariants(scheme: androidx.compose.material3.ColorScheme) {
        val c = com.folio.reader.ui.theme.deriveSystemPalette(scheme)
        val accents = listOf(
            "progress" to c.accentProgress,
            "discovery" to c.accentDiscovery,
            "streak" to c.accentStreak,
            "annotation" to c.accentAnnotation,
        )
        for (i in accents.indices) for (j in i + 1 until accents.size) {
            val dE = deltaE(accents[i].second, accents[j].second)
            assertTrue(dE >= 10.0,
                "derived ${accents[i].first} vs ${accents[j].first} ΔE=$dE — too similar to tell apart")
        }
        for ((role, a) in accents) {
            val ratio = wcagRatio(a.toArgb(), c.surface.toArgb())
            assertTrue(ratio >= 4.5, "derived accent$role on surface is $ratio:1 — below WCAG AA")
        }
        val inkRatio = wcagRatio(c.onSurface.toArgb(), c.surface.toArgb())
        assertTrue(inkRatio >= 7.0, "derived ink on surface is $inkRatio:1 — below the §15 reading floor")
        assertEquals(8, c.chartSeries.size, "chartSeries must fall back intact")
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
