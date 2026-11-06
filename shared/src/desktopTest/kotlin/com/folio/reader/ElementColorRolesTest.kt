package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ElementColorRolesTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun navigationKeepsOneSignatureAcrossAllDestinationsAndFallbacks() {
        for (palette in AppPalette.entries) {
            var expected = Color.Unspecified
            var destinations = emptyList<Color>()
            val scene = ImageComposeScene(16, 16) {
                FolioTheme.AppTheme(palette, daylight = FolioDaylight.Neutral) {
                    expected = FolioTheme.elements.action
                    destinations = listOf("home", "library", "stats", "more", "Books", "Manga", "Documents", "future-route")
                        .map { folioDestinationAccent(it, fallback = FolioTheme.colors.accentAnnotation) }
                    Box(Modifier.fillMaxSize())
                }
            }
            try {
                scene.render(0).close()
                for (accent in destinations) assertEquals(expected, accent, "${palette.id}: destination recolored selection")
            } finally { scene.close() }
        }
        // User-created monochrome palettes still remain neutral; shipped faces now
        // use complementary interaction colors even when their grounds are neutral.
        for (palette in listOf(AppPalette.LIGHT, AppPalette.DARK)) {
            val monochrome = palette.colors.copy(primary = Color(0xFF888888))
            val fill = elementColorsFor(folioRenderedColors(monochrome)).navigationSelection
            assertEquals(fill.red, fill.green)
            assertEquals(fill.green, fill.blue)
        }
    }

    @Test fun everyFaceHasAColoredSelectionThatOpposesItsBarMaterial() {
        fun hueDistance(a: Color, b: Color): Float {
            val angle = kotlin.math.abs(oklabHue(a) - oklabHue(b)) % 360f
            return minOf(angle, 360f - angle)
        }
        for (palette in AppPalette.entries) {
            val colors = folioRenderedColors(palette.colors)
            val elements = elementColorsFor(colors)
            val field = atmosphereFor(colors)
            for (ground in listOf(field.fieldTop, field.fieldBottom, colors.background)) {
                for (opacity in listOf(.6f, .9f)) {
                    val bar = elements.chrome.copy(alpha = opacity).compositeOver(ground)
                    val selection = elements.navigationSelection.compositeOver(bar)
                    assertTrue(oklabChroma(selection) >= .04f, "${palette.label}: selection lost its distinct color")
                    if (oklabChroma(bar) >= .015f) {
                        assertTrue(hueDistance(selection, bar) >= 65f,
                            "${palette.label}: selected navigation is another shade of its bar")
                    }
                }
            }
            val accents = listOf(colors.accentProgress, colors.accentStreak,
                colors.accentDiscovery, colors.accentAnnotation)
            for (i in accents.indices) for (j in i + 1 until accents.size) {
                assertTrue(hueDistance(accents[i], accents[j]) >= 40f,
                    "${palette.label}: supporting accents differ only in shade")
            }
        }
    }

    @Test fun newPacksFlipBothAppAndReaderWithoutReplacingLegacyCitrine() {
        for (pack in ThemePack.ALL.filter { it.id in setOf("risograph", "celadon", "mineral") }) {
            val light = com.folio.reader.settings.ReaderSettings(appThemeId = pack.lightAppPaletteId,
                themeId = pack.lightReaderThemeId)
            val dark = flipThemeMode(light)
            assertEquals(pack.darkAppPaletteId, dark.appThemeId)
            assertEquals(pack.darkReaderThemeId, dark.themeId)
            assertEquals(light, flipThemeMode(dark))
            for (id in listOf(pack.lightReaderThemeId, pack.darkReaderThemeId)) {
                val reader = com.folio.reader.settings.Theme.PRESETS.getValue(id)
                val paper = Color(reader.background)
                assertTrue(contrast(Color(reader.primaryText), paper) >= 7f, "$id reader body")
                assertTrue(contrast(Color(reader.secondaryText), paper) >= 4.5f, "$id reader metadata")
                assertTrue(contrast(Color(reader.link), paper) >= 4.5f, "$id reader link")
            }
        }
        assertEquals(AppPalette.WARM, AppPalette.byId("citrine"))
    }

    @Test fun customThemesDeriveMaterialsFromTheirOwnPlanes() {
        for (palette in listOf(AppPalette.COOL, AppPalette.MAGENTA)) {
            val custom = customAppThemeFrom(palette).toFolioColors()
            val fallback = if (palette.isDark) AppPalette.DARK.colors else AppPalette.LIGHT.colors
            assertNotEquals(fallback.paperSurface, custom.paperSurface)
            assertNotEquals(fallback.referenceSurface, custom.referenceSurface)
            assertNotEquals(fallback.chromeSurface, custom.chromeSurface)
            val e = elementColorsFor(folioRenderedColors(custom))
            for (fill in listOf(e.paper, e.reference, e.chrome)) {
                assertTrue(contrast(e.ink, fill) >= 4.5f)
                assertTrue(contrast(e.mutedInk, fill) >= 4.5f)
            }
        }
    }

    @Test fun allThemeFacesPairMaterialAndControlInksWithTheirActualFills() {
        for (palette in AppPalette.entries) {
            val colors = folioRenderedColors(palette.colors)
            val e = elementColorsFor(colors)
            for ((name, fill) in listOf("paper" to e.paper, "reference" to e.reference,
                "inset" to e.inset, "chrome" to e.chrome, "selection" to e.selection, "feature" to e.feature)) {
                assertTrue(contrast(e.ink, fill) >= 4.5f, "${palette.id} $name body ink")
                assertTrue(contrast(e.mutedInk, fill) >= 4.5f, "${palette.id} $name metadata ink")
            }
            assertTrue(contrast(e.onAction, e.action) >= 4.5f, "${palette.id} filled action")
            assertTrue(contrast(e.onSelection, e.selection) >= 4.5f, "${palette.id} selection")
            assertTrue(contrast(e.actionInk, e.reference) >= 4.5f, "${palette.id} secondary action")
            assertTrue(contrast(e.onNavigationSelection, e.navigationSelection.compositeOver(e.chrome)) >= 4.5f,
                "${palette.id} navigation label")
            val field = atmosphereFor(colors)
            for (ground in listOf(field.fieldTop, field.fieldBottom, colors.background)) {
                for ((fill, alpha) in listOf(e.reference to .9f, e.reference to .62f,
                    e.chrome to .9f, e.chrome to .5f)) {
                    val composite = fill.copy(alpha = alpha).compositeOver(ground)
                    assertTrue(contrast(e.ink, composite) >= 4.5f, "${palette.id} composited body ink")
                    assertTrue(contrast(e.mutedInk, composite) >= 4.5f, "${palette.id} composited metadata")
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun sectionsAndArtworkCannotRecolorReferencePanelsOrSettledReadControls() {
        val fonts = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        for (palette in listOf(AppPalette.LIGHT, AppPalette.COOL, AppPalette.MAGENTA)) {
            fun render(section: Color, artwork: Color): ByteArray {
                val scene = ImageComposeScene(360, 440) {
                    FolioTheme.AppTheme(palette, daylight = FolioDaylight.Neutral) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            FolioSection(section) {
                                Column(Modifier.fillMaxSize().background(FolioTheme.colors.background).padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FolioReferencePanel { Text("Reading history") }
                                    FolioChip(true, {}, "Selected")
                                    FolioActionDock("Read", "Chapter four", {}, accent = artwork)
                                    FolioSlider(.4f, {})
                                }
                            }
                        }
                    }
                }
                try {
                    repeat(5) { scene.render(it * 250_000_000L).close() }
                    return scene.render(2_000_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
                } finally { scene.close() }
            }
            assertContentEquals(render(Color.Red, Color.Blue), render(Color.Green, Color.Magenta),
                "${palette.id}: a section or cover changed functional element colors")
        }
    }
}
