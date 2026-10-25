package com.folio.reader.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentFormat
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.theme.LocalFolioSurfaceOpacity
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DocumentBarGeometryTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/inter_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun documentCapsulesPaintFortyDpWhileRetainingFortyEightDpActions() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                for (width in listOf(320, 393)) for (direction in LayoutDirection.entries) {
                    for (fontScale in listOf(1f, 1.5f)) for (pdf in listOf(true, false)) {
                        val document = Document("doc", "Document title", "doc.pdf", if (pdf) DocumentFormat.PDF else DocumentFormat.DOCX,
                            "", "", 1L)
                        val scene = ImageComposeScene(width, 100, density = Density(1f, fontScale), layoutDirection = direction) {
                            FolioTheme.MaterialTheme(darkTheme = true) {
                                FolioGlassRoot(GlassCapabilities.None) {
                                    CompositionLocalProvider(LocalFolioSurfaceOpacity provides FolioSurfaceOpacity(readerChrome = 1f)) {
                                        Box(Modifier.fillMaxSize().background(Color.Red)) {
                                            DocumentReaderBar(DocumentReaderState(document = document), {}, {},
                                                onOpenSettings = if (pdf) null else ({ }))
                                        }
                                    }
                                }
                            }
                        }
                        val raster = Bitmap()
                        try {
                            repeat(6) { scene.render(it * 100_000_000L).close() }
                            val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                            val action = nodes.single { it.config.contains(SemanticsActions.OnClick) &&
                                "Back" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInRoot
                            assertEquals(48f, action.width, .1f)
                            assertEquals(48f, action.height, .1f)
                            val image = scene.render(700_000_000L)
                            try {
                                raster.allocPixels(ImageInfo(width, 100, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                                check(image.readPixels(raster))
                                val pixels = requireNotNull(raster.readPixels(raster.imageInfo, raster.rowBytes, 0, 0))
                                val x = action.center.x.roundToInt()
                                val colored = (0 until 80).filter { y ->
                                    val offset = y * raster.rowBytes + x * 4
                                    val red = pixels[offset].toInt() and 255
                                    val green = pixels[offset + 1].toInt() and 255
                                    red < 230 || green > 30
                                }
                                assertTrue(colored.isNotEmpty())
                                assertEquals(40, colored.last() - colored.first() + 1,
                                    "Document-only painted strip is too thin: $width/$direction/$fontScale/PDF=$pdf")
                            } finally { image.close() }
                        } finally { raster.close(); scene.close() }
                    }
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
