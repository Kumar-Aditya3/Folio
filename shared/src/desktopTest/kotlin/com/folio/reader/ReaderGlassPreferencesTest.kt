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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.reader.readerIsland
import com.folio.reader.ui.reader.readerOverlayColors
import com.folio.reader.ui.render.OverlayColors
import com.folio.reader.ui.render.OverlayUi
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

class ReaderGlassPreferencesTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun actualReaderIslandUsesTheReaderKnobAndCanBecomeSolid() {
        for (blur in listOf(false, true)) {
            val reader = mutableStateOf(.2f)
            val panel = mutableStateOf(.3f)
            val room = mutableStateOf(Color.Red)
            val scene = ImageComposeScene(300, 220) {
                FolioTheme.MaterialTheme(darkTheme = true,
                    opacity = FolioSurfaceOpacity(panel = panel.value, readerChrome = reader.value)) {
                    FolioGlassRoot(GlassCapabilities(blur, blur, blur)) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().folioRoomSource().background(room.value))
                            Box(Modifier.size(260.dp, 180.dp).readerIsland())
                        }
                    }
                }
            }
            var frame = 0L
            fun pixel(): Int {
                repeat(5) { scene.render(frame++ * 100_000_000L).close() }
                val image = scene.render(frame++ * 100_000_000L)
                try { return ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)).getRGB(130, 80) }
                finally { image.close() }
            }
            try {
                val before = pixel()
                panel.value = 1f
                assertEquals(before, pixel(), "The app panel knob leaked into the reader")
                room.value = Color.Blue
                assertNotEquals(before, pixel(), "Reader transparency did not reveal the backdrop")
                reader.value = 1f
                val blue = pixel()
                room.value = Color.Red
                val red = pixel()
                val difference = listOf(0, 8, 16).maxOf { abs((red shr it and 255) - (blue shr it and 255)) }
                assertEquals(0, difference, "Reader chrome leaked through at 100%: blur=$blur")
                reader.value = .2f
                assertEquals(before, pixel(), "Reader opacity did not update after reopening the range")
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun embeddedReaderOverlaysReadTheSameLiveOpacityAndSheen() {
        val opacity = mutableStateOf(.4f)
        val sheen = mutableStateOf(0f)
        var colors: OverlayColors? = null
        val scene = ImageComposeScene(20, 20) {
            FolioTheme.MaterialTheme(darkTheme = true, opacity = FolioSurfaceOpacity(readerChrome = opacity.value),
                sheen = FolioSheen(sheen.value)) { colors = readerOverlayColors() }
        }
        try {
            scene.render(0).close()
            val transparent = requireNotNull(colors)
            assertTrue(transparent.veilAlpha < .4f)
            val html = OverlayUi.toc(emptyList(), 0, 0f, transparent)
            assertTrue("background-image:none;" in html)
            assertTrue("backdrop-filter:blur(18px)" in html)
            opacity.value = 1f; sheen.value = 1f
            scene.render(100_000_000L).close()
            val solid = requireNotNull(colors)
            assertEquals(1f, solid.veilAlpha)
            assertEquals(1f, solid.sheen)
            val solidHtml = OverlayUi.toc(emptyList(), 0, 0f, solid)
            assertTrue("background-color:${solid.bg}ff" in solidHtml)
            assertTrue("background-image:linear-gradient" in solidHtml)
            assertFalse("backdrop-filter:blur" in solidHtml)
        } finally { scene.close() }
    }

    @Test fun updatingAnOpenPopupDoesNotReopenItOrAffectNestedMenuOwnership() {
        val controller = FolioOverlayController()
        var dismissals = 0
        val dialog = controller.showDialog(fillAlpha = .8f, onDismiss = { dismissals++ }) {}
        val menu = controller.showMenu(Rect.Zero, fillAlpha = .7f, onDismiss = {}) {}
        controller.updateFillAlpha(dialog, .3f)
        controller.updateBlurEnabled(dialog, false)
        assertEquals(listOf(dialog, menu), controller.entries.map { it.token })
        assertEquals(.3f, controller.entries.first().fillAlpha)
        assertEquals(.7f, controller.entries.last().fillAlpha)
        assertFalse(controller.entries.first().blurEnabled)
        assertTrue(controller.entries.last().blurEnabled)
        assertEquals(0, dismissals)
        controller.dismiss(menu)
        assertEquals(dialog, controller.entries.single().token)
        assertEquals(.3f, controller.entries.single().fillAlpha)
        controller.updateFillAlpha(menu, 1f)
        assertEquals(.3f, controller.entries.single().fillAlpha)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun vividnessChangesProjectedArtworkButPreservesTheBookCover() {
        val file = File("build/visual-review/vividness-cover.png").apply { parentFile.mkdirs() }
        val fixture = java.awt.image.BufferedImage(240, 360, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val canvas = fixture.createGraphics()
        try {
            canvas.color = java.awt.Color(35, 130, 190); canvas.fillRect(0, 0, 240, 360)
            canvas.color = java.awt.Color(225, 170, 65); canvas.fillOval(30, 35, 170, 170)
        } finally { canvas.dispose() }
        ImageIO.write(fixture, "png", file)
        val vibrancy = mutableStateOf(0f)
        val scene = ImageComposeScene(320, 400) {
            FolioTheme.MaterialTheme(darkTheme = true, vibrancy = FolioVibrancy(vibrancy.value)) {
                FolioGlassRoot(GlassCapabilities.None) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        FolioArtworkField(listOf(FolioArtwork("vividness", file.absolutePath)),
                            animated = false, enabled = true, modifier = Modifier.fillMaxSize())
                        FolioCoverPlate(file.absolutePath, "Fixture", "Reader", width = 80.dp,
                            haloStrength = 0f, elevation = 0.dp)
                    }
                }
            }
        }
        var frame = 0L
        fun pixels(wait: Boolean = false): Pair<Int, Int> {
            repeat(if (wait) 30 else 6) { scene.render(frame++ * 100_000_000L).close(); if (wait) Thread.sleep(25) }
            val image = scene.render(frame++ * 100_000_000L)
            try {
                val bitmap = ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes))
                return bitmap.getRGB(40, 60) to bitmap.getRGB(160, 130)
            } finally { image.close() }
        }
        try {
            val muted = pixels(true)
            vibrancy.value = 1f
            val vivid = pixels()
            assertEquals(muted.first, vivid.first, "The book cover was recoloured")
            assertNotEquals(muted.second, vivid.second, "The projected artwork ignored vividness")
            vibrancy.value = 0f
            assertEquals(muted, pixels(), "Vividness did not update live")
        } finally { scene.close() }
    }
}
