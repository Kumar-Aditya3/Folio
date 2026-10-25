package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.*
import com.folio.reader.ui.document.DocumentSettingsPanel
import com.folio.reader.ui.reader.ReaderSettingsPanel
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ReaderSettingsSheetTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun unblurredSheetsKeepThePreferenceBodyRatherThanTheDiffusionDiscount() {
        assertEquals(.9f, readerPanelAlpha(.9f, canBlur = false))
        assertEquals(FolioSurfaceOpacity.PANEL_GLASS_BLURRED, readerPanelAlpha(.9f, canBlur = true), .00001f)
        for (blur in listOf(false, true)) {
            assertEquals(1f, readerPanelAlpha(1f, blur))
            val samples = (10..100).map { readerPanelAlpha(it / 100f, blur) }
            assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun actualBookAndDocumentSheetsStayReadableAndFollowOnlyTheReaderKnob() = onUiThread {
        for (dark in listOf(false, true)) for (document in listOf(false, true)) for (blur in listOf(false, true)) {
            val opacity = mutableStateOf(.9f)
            val appOpacity = mutableStateOf(.2f)
            val page = mutableStateOf(Color.Red)
            val scene = ImageComposeScene(393, 852, density = Density(1f)) {
                FolioTheme.MaterialTheme(darkTheme = dark,
                    opacity = FolioSurfaceOpacity(panel = appOpacity.value, readerChrome = opacity.value)) {
                    FolioGlassRoot(GlassCapabilities(blur, blur, blur)) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().folioRoomSource().background(page.value))
                            FolioSuppressibleBackdrop(suppress = true) {
                                Sheet(document, Modifier.align(Alignment.CenterEnd))
                            }
                        }
                    }
                }
            }
            var frame = 0L
            fun pixel(): Int {
                repeat(8) {
                    Snapshot.sendApplyNotifications()
                    scene.render(frame++ * 100_000_000L).close()
                }
                val image = scene.render(frame++ * 100_000_000L)
                try {
                    val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                    if (dark && !document && blur) {
                        File("build/visual-review/reader-settings-sheet/pixel-${opacity.value}-${if (page.value == Color.Red) "red" else "blue"}.png")
                            .apply { parentFile.mkdirs(); writeBytes(bytes) }
                    }
                    return ImageIO.read(ByteArrayInputStream(bytes))
                        .getRGB(if (document) 121 else 65, 300) // Clear body margin, outside the opaque page specimen.
                } finally { image.close() }
            }
            fun transmission(alpha: Float): Int {
                opacity.value = alpha
                page.value = Color.Red; val red = pixel()
                page.value = Color.Blue; val blue = pixel()
                return listOf(0, 8, 16).maxOf { abs((red shr it and 255) - (blue shr it and 255)) }
            }
            try {
                val context = "document=$document dark=$dark deviceBlur=$blur"
                val designed = transmission(.9f)
                assertTrue(designed <= 30, "Default body lets sharp page ink dominate: $context, delta=$designed")
                val before = pixel()
                appOpacity.value = 1f
                assertEquals(before, pixel(), "App panel preference leaked into the reader sheet: $context")
                val low = transmission(.2f)
                assertTrue(low > 100, "Low opacity stopped responding: $context, delta=$low")
                assertEquals(0, transmission(1f), "Solid sheet leaks the page: $context")
                assertEquals(low, transmission(.2f), "Live sheet opacity failed to restore: $context")
            } finally { scene.close() }
        }
    }

    @Test fun renderActualSheetsOverPageTextInBothThemesAndLargeType() = onUiThread {
        val output = File("build/visual-review/reader-settings-sheet").apply { mkdirs() }
        for (dark in listOf(false, true)) for (document in listOf(false, true)) for (width in listOf(393, 320)) {
            val scene = ImageComposeScene(width, 1000, density = Density(1f, if (width == 320) 1.4f else 1f)) {
                FolioTheme.MaterialTheme(darkTheme = dark, opacity = FolioSurfaceOpacity(readerChrome = .9f)) {
                    FolioGlassRoot(GlassCapabilities(true, true, true)) {
                        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF1E242A) else Color(0xFFF6F0E5))) {
                            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                repeat(9) { Text("The room grew quiet as she returned to the page. Beyond the window, " +
                                    "the last light settled on the shelves. Every story has a place to begin again.",
                                    color = if (dark) Color(0xFFD4CDBE) else Color(0xFF36352D),
                                    style = FolioTheme.typography.bodyLarge) }
                            }
                            Box(Modifier.fillMaxSize().background(FolioTheme.atmosphere.shadowAmbient.copy(alpha = .35f)))
                            FolioSuppressibleBackdrop(suppress = true) {
                                Sheet(document, Modifier.align(Alignment.CenterEnd))
                            }
                        }
                    }
                }
            }
            try {
                repeat(10) {
                    Snapshot.sendApplyNotifications()
                    scene.render(it * 100_000_000L).close()
                }
                val image = scene.render(1_100_000_000L)
                try {
                    File(output, "${if (document) "document" else "book"}-${if (dark) "dark" else "light"}-$width.png")
                        .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                } finally { image.close() }
            } finally { scene.close() }
        }
    }

    @Test fun tappingTheReaderTypefaceFieldShowsASelectableMenu() = onUiThread {
        val controller = FolioOverlayController()
        val settings = mutableStateOf(ReaderSettings())
        val scene = ImageComposeScene(393, 852, density = Density(1f)) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                FolioGlassRoot(GlassCapabilities.None) {
                    CompositionLocalProvider(LocalFolioOverlay provides controller) {
                        Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                            FolioSuppressibleBackdrop(suppress = true) {
                                Box(Modifier.align(Alignment.CenterEnd)) {
                                    ReaderSettingsPanel(settings.value, { settings.value = it }, {}, {}, scopeControlEnabled = true)
                                }
                            }
                            FolioOverlayHost()
                        }
                    }
                }
            }
        }
        var frame = 0L
        fun render() {
            repeat(10) {
                Snapshot.sendApplyNotifications()
                scene.render(frame++ * 100_000_000L).close()
            }
        }
        fun tap(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            render()
        }
        try {
            render()
            val field = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.first { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == settings.value.fontFamily } == true
            }.boundsInRoot.center
            tap(field.x, field.y)
            assertFalse(controller.isOpen, "Typeface choices escaped into a detached popup")
            val image = scene.render(frame++ * 100_000_000L)
            try {
                File("build/visual-review/reader-settings-sheet/typeface-open.png")
                    .apply { parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            } finally { image.close() }
            val nextFont = com.folio.reader.ui.reader.platformBaseReaderFonts()[1]
            val choice = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.first { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == nextFont } == true
            }.boundsInRoot.center
            tap(choice.x, choice.y)
            assertEquals(com.folio.reader.ui.reader.platformBaseReaderFonts()[1], settings.value.fontFamily,
                "Visible font choice did not apply to the reader")
        } finally { scene.close() }
    }

    @Composable private fun Sheet(document: Boolean, modifier: Modifier) {
        Box(modifier) {
            if (document) DocumentSettingsPanel(DocumentSettings(), {}, {}, {})
            else ReaderSettingsPanel(ReaderSettings(), {}, {}, {}, scopeControlEnabled = true)
        }
    }

    @Test fun androidGenericTypefaceChoicesAreVisibleAndSelectableInsideTheDrawer() = onUiThread {
        val selected = mutableStateOf("Serif")
        val scene = ImageComposeScene(280, 400, density = Density(1f)) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).padding(16.dp)) {
                    com.folio.reader.ui.reader.ReaderTypefacePicker(selected.value,
                        listOf("Serif", "Sans Serif", "Monospace"), { selected.value = it })
                }
            }
        }
        var frame = 0L
        fun render() {
            repeat(10) {
                Snapshot.sendApplyNotifications()
                scene.render(frame++ * 100_000_000L).close()
            }
        }
        fun tap(y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(120f, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(120f, y))
            render()
        }
        try {
            render(); tap(38f)
            val image = scene.render(frame++ * 100_000_000L)
            try {
                File("build/visual-review/reader-settings-sheet/typeface-android-choices.png")
                    .apply { parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            } finally { image.close() }
            tap(146f)
            assertEquals("Sans Serif", selected.value)
            tap(38f); tap(198f)
            assertEquals("Monospace", selected.value)
        } finally { scene.close() }
    }

    private fun onUiThread(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}
