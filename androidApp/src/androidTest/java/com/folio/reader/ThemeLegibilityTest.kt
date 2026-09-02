package com.folio.reader

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.settings.Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * §8.2 ThemeLegibilityTest — `paper`, `midnightneon`, `rainbow` must carry no
 * text with contrast below 4.5:1 (WCAG AA). Reader themes are evaluated on
 * their page-ink roles; app palettes on the chrome text roles, all against the
 * surfaces they are actually painted on.
 */
class ThemeLegibilityTest {

    @get:Rule val compose = createFolioComposeRule()

    private fun relLum(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val l1 = relLum(fg)
        val l2 = relLum(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private fun assertReadable(pairs: List<Pair<String, Double>>, floor: Double = 4.5) {
        val failures = pairs.filter { it.second < floor }
        assertTrue(
            "Contrast below $floor:1 — " +
                failures.joinToString { "${it.first}=${"%.2f".format(it.second)}" },
            failures.isEmpty(),
        )
    }

    @Test
    fun paperReaderThemeTextIsReadable() {
        val paper = Theme.getPreset("paper")
        assertReadable(
            listOf(
                "primaryText on background" to ratio(paper.primaryText, paper.background),
                "primaryText on surface" to ratio(paper.primaryText, paper.surface),
                "secondaryText on background" to ratio(paper.secondaryText, paper.background),
                "headingText on background" to ratio(paper.headingText, paper.background),
                "link on background" to ratio(paper.link, paper.background),
            )
        )
    }

    private fun assertChromeReadable(palette: AppPalette) {
        val c = palette.colors
        fun argb(color: androidx.compose.ui.graphics.Color) = color.toArgb()
        assertReadable(
            listOf(
                "onBackground on background" to ratio(argb(c.onBackground), argb(c.background)),
                "onSurface on surface" to ratio(argb(c.onSurface), argb(c.surface)),
                "onSurface on surfaceVariant" to ratio(argb(c.onSurface), argb(c.surfaceVariant)),
                "onSurfaceVariant on surface" to ratio(argb(c.onSurfaceVariant), argb(c.surface)),
                "onSurfaceVariant on surfaceVariant" to ratio(argb(c.onSurfaceVariant), argb(c.surfaceVariant)),
            )
        )
    }

    @Test
    fun midnightNeonChromeTextIsReadable() {
        assertChromeReadable(AppPalette.byId("midnightneon"))
    }

    @Test
    fun rainbowChromeTextIsReadable() {
        assertChromeReadable(AppPalette.byId("rainbow"))
    }
}
