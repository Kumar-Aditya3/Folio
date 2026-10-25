package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.Theme
import com.folio.reader.ui.components.*
import com.folio.reader.ui.settings.AppearanceMockup
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoherentMaterialsTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun everyPanelFillPreservesItsDesignedPointAndReachesSolid() {
        for (designed in listOf(.38f, .62f, .82f, .94f)) {
            assertEquals(designed, FolioSurfaceOpacity(panel = .9f).panelKnobScale(designed), .00001f)
            assertEquals(1f, FolioSurfaceOpacity(panel = 1f).panelKnobScale(designed))
            val samples = (1..100).map { FolioSurfaceOpacity(panel = it / 100f).panelKnobScale(designed) }
            assertTrue(samples.zipWithNext().all { (a, b) -> b >= a }, "Fill must not reverse direction")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun liveOpacityChangesReachEveryNewSurfaceWithBlurOffOrMinimal() {
        for (dark in listOf(false, true)) for (mode in listOf("glass", "minimal", "off")) {
            for (kind in listOf("settings", "reference", "inline", "library", "reading")) {
                val opacity = mutableStateOf(.2f)
                val room = mutableStateOf(Color.Red)
                val scene = ImageComposeScene(300, 220) {
                    FolioTheme.MaterialTheme(darkTheme = dark, opacity = FolioSurfaceOpacity(panel = opacity.value)) {
                        val enabled = mode != "off"
                        FolioGlassRoot(GlassCapabilities(enabled, enabled, enabled), inFlowGlass = mode != "minimal") {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().folioRoomSource().background(room.value))
                                Surface(kind)
                            }
                        }
                    }
                }
                var frame = 0L
                fun pixel(): Int {
                    repeat(5) { scene.render(frame++ * 100_000_000L).close() }
                    val image = scene.render(frame++ * 100_000_000L)
                    try {
                        return ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes))
                            .getRGB(130, 80)
                    } finally { image.close() }
                }
                fun transmission(): Int {
                    room.value = Color.Red; val red = pixel()
                    room.value = Color.Blue; val blue = pixel()
                    return listOf(0, 8, 16).maxOf { abs((red shr it and 255) - (blue shr it and 255)) }
                }
                try {
                    val low = transmission()
                    opacity.value = .9f
                    val designed = transmission()
                    opacity.value = 1f
                    val solid = transmission()
                    val case = "$kind/$mode/dark=$dark"
                    assertTrue(low > 20, "$case lost transmission at low opacity: $low")
                    assertTrue(designed < low, "$case ignored live opacity: $low -> $designed")
                    assertEquals(0, solid, "$case leaked the backdrop at 100%")
                    opacity.value = .2f
                    assertEquals(low, transmission(), "$case did not restore after lowering opacity")
                } finally { scene.close() }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun homeWeekStaysOpenEvenAtFullPanelOpacity() {
        for (desk in listOf(false, true)) for (dark in listOf(false, true))
            for (opacity in listOf(.2f, .9f, 1f)) {
                val scene = ImageComposeScene(300, 240) {
                    FolioTheme.MaterialTheme(darkTheme = dark, opacity = FolioSurfaceOpacity(panel = opacity)) {
                        FolioGlassRoot(GlassCapabilities(true, true, true)) {
                            Box(Modifier.fillMaxSize().background(Color(0xff263f49))) {
                                com.folio.reader.ui.home.ThisWeekWell(
                                    com.folio.reader.ui.home.HomeUiState(readingDesk = desk), null, {})
                            }
                        }
                    }
                }
                try {
                    repeat(5) { scene.render(it * 100_000_000L).close() }
                    val image = scene.render(600_000_000L)
                    try {
                        val pixels = ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes))
                        assertEquals(pixels.getRGB(290, 230), pixels.getRGB(150, 150),
                            "Home weekly graph gained an enclosing surface: desk=$desk dark=$dark opacity=$opacity")
                    } finally { image.close() }
                } finally { scene.close() }
            }
    }

    @Composable private fun Surface(kind: String) {
        val modifier = Modifier.size(260.dp, 180.dp)
        when (kind) {
            "settings" -> FolioGlassPanel(modifier) {}
            "reference" -> FolioReferencePanel(modifier) {}
            "inline" -> Box(modifier.folioReference())
            "library" -> Box(modifier.folioShelfHero(FolioTheme.colors.accentProgress))
            "reading" -> Box(modifier.folioReadingDockGlass(FolioMaterialShapes.dock, FolioTheme.colors.accentProgress))
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun transparencyPreviewSamplesItsOwnRoomRatherThanTheSettingsBehindIt() {
        fun pixels(background: Color, dark: Boolean): List<Int> {
            val scene = ImageComposeScene(320, 340) {
                FolioTheme.MaterialTheme(darkTheme = dark) {
                    FolioGlassRoot(GlassCapabilities(true, true, true)) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().folioRoomSource().background(background))
                            AppearanceMockup(FolioTheme.colors, dark, FolioSurfaceOpacity(panel = .25f), Theme.getPreset("paper"))
                        }
                    }
                }
            }
            try {
                repeat(8) { scene.render(it * 100_000_000L).close() }
                val image = scene.render(1_000_000_000L)
                try {
                    val bitmap = ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes))
                    return listOf(105, 155, 205).map { bitmap.getRGB(55, it) }
                } finally { image.close() }
            } finally { scene.close() }
        }
        for (dark in listOf(false, true)) assertEquals(pixels(Color.Red, dark), pixels(Color.Blue, dark))
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun readingGlassRespondsToTheSheenPreference() {
        fun render(sheen: Float): java.awt.image.BufferedImage {
            val scene = ImageComposeScene(300, 220) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalFolioSheen provides FolioSheen(sheen)) {
                        FolioGlassRoot(GlassCapabilities(true, true, true)) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().folioRoomSource().background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xff244c71), Color(0xffd7a456)))))
                                Surface("reading")
                            }
                        }
                    }
                }
            }
            try {
                repeat(8) { scene.render(it * 100_000_000L).close() }
                val image = scene.render(1_000_000_000L)
                try { return ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)) }
                finally { image.close() }
            } finally { scene.close() }
        }
        val full = render(1f)
        val off = render(0f)
        val changed = (1 until 259).sumOf { x -> (1 until 179).count { y -> full.getRGB(x, y) != off.getRGB(x, y) } }
        assertTrue(changed > 10, "The reading glass ignored metallic sheen")
        assertEquals(full.getRGB(290, 210), off.getRGB(290, 210), "Sheen changed the room instead of the surface")
    }

}
