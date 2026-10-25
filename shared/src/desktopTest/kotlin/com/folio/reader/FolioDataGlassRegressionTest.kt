package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.reader.readerIsland
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual data-glass pixels, including content that is not in the Haze registry. */
@OptIn(ExperimentalComposeUiApi::class)
class FolioDataGlassRegressionTest {
    @BeforeTest fun fonts() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
    }

    @Test fun referenceShapeContainsInteriorPointsAtReportedPanelHeight() {
        val touch = Path()
        val intersection = Path()
        for (size in listOf(Size(864f, 6500f), Size(972f, 8192f))) {
            val outline = FolioMaterialShapes.reference.createOutline(size, LayoutDirection.Ltr, Density(2.75f))
            val path = (outline as Outline.Generic).path
            for (y in (128 until size.height.toInt() - 128 step 64)) {
                for (x in listOf(88f, size.width / 2, size.width - 88f)) {
                    // Compose's generic-outline hit test intersects this 0.01px box, not a row rect.
                    touch.reset()
                    intersection.reset()
                    touch.addRect(Rect(x - .005f, y - .005f, x + .005f, y + .005f))
                    assertTrue(intersection.op(path, touch, PathOperation.Intersect),
                        "Clip intersection failed: size=$size point=($x,$y)")
                    assertTrue(!intersection.isEmpty,
                        "Clip rejected an interior tap: size=$size point=($x,$y)")
                }
            }
        }
    }

    @Test fun diagnosticMaterialBypassesKeepThePanelTintAndInteriorContent() = onUi {
        for (dark in listOf(false, true)) {
            val scene = scene(320, dark, "glass", mutableStateOf(.6f), sheen = mutableStateOf(0f)) {
                Box(Modifier.fillMaxSize().folioRoomSource().background(Color.Gray))
                FolioGlassPanel(Modifier.offset(8.dp, 8.dp).size(144.dp, 224.dp),
                    contentPadding = PaddingValues(0.dp), blurEnabled = false) {}
                FolioGlassPanel(Modifier.offset(168.dp, 8.dp).size(144.dp, 224.dp),
                    contentPadding = PaddingValues(0.dp), blurEnabled = false, clipEnabled = false) {}
            }
            try {
                val image = scene.pixels()
                assertEquals(image.getRGB(80, 120), image.getRGB(240, 120),
                    "Disabling only the clip changed the tint: dark=$dark")
                assertEquals(image.getRGB(8, 8), image.getRGB(168, 8),
                    "Unclipped diagnostic material escaped its drawn shape: dark=$dark")
            } finally { scene.close() }
        }
    }

    @Test fun liveDataOpacityTransmitsTheUnregisteredPageAtRootAndNestedDepths() = onUi {
        for (width in listOf(320, 393)) {
            val mask = surfaceMask(width)
            for (dark in listOf(false, true)) for (mode in listOf("glass", "minimal", "off"))
                for (nested in listOf(false, true)) {
                    val opacity = mutableStateOf(.2f)
                    val page = mutableStateOf(Color.Red)
                    val scene = scene(width, dark, mode, opacity) {
                        Box(Modifier.fillMaxSize().folioRoomSource().background(Color.Gray))
                        // Deliberately not a source: a full-strength room replay hides this page.
                        Box(Modifier.fillMaxSize().background(page.value))
                        dataPanel(width, nested)
                    }
                    try {
                        val context = "width=$width dark=$dark mode=$mode nested=$nested"
                        var first: BufferedImage? = null
                        val levels = listOf(.2f, .6f, .9f, 1f, .2f).map { alpha ->
                            opacity.value = alpha
                            page.value = Color.Red
                            val red = scene.pixels()
                            first?.let { assertOutsideUnchanged(it, red, mask, context) }
                                ?: run { first = red }
                            if (alpha == .2f) assertContentEquals(first!!.argb(), red.argb(), "$context failed round-trip")
                            page.value = Color.Blue
                            val blue = scene.pixels()
                            distance(red.getRGB(width / 2, 130), blue.getRGB(width / 2, 130))
                        }
                        assertTrue(levels[0] > 40, "$context still replaces the direct page: $levels")
                        assertTrue(levels[0] > levels[1] && levels[1] > levels[2] && levels[2] > 0,
                            "$context opacity must increase throughout the live range: $levels")
                        assertEquals(0, levels[3], "$context leaked at 100%")
                        assertEquals(levels[0], levels[4], "$context did not restore transmission")
                    } finally { scene.close() }
                }
        }
    }

    @Test fun diffusionRatherThanOnlyTintFollowsTheLiveOpacity() = onUi {
        for (width in listOf(320, 393)) for (dark in listOf(false, true))
            for (mode in listOf("glass", "minimal", "off")) for (nested in listOf(false, true)) {
                val opacity = mutableStateOf(.2f)
                val scene = scene(width, dark, mode, opacity, sheen = mutableStateOf(0f)) {
                    Box(Modifier.fillMaxSize().folioRoomSource().sharpEdge())
                    dataPanel(width, nested)
                }
                try {
                    val ratios = listOf(.2f, .6f, .9f, .2f).map { alpha ->
                        opacity.value = alpha
                        val image = scene.pixels()
                        val x = width / 2
                        val jump = (110..160).sumOf { y -> distance(image.getRGB(x - 1, y), image.getRGB(x, y)) }
                        val spread = (110..160).sumOf { y -> distance(image.getRGB(x - 20, y), image.getRGB(x + 20, y)) }
                        assertTrue(spread > 100, "The sharp/blurred edge fixture lost contrast")
                        jump.toFloat() / spread
                    }
                    val context = "width=$width dark=$dark mode=$mode nested=$nested ratios=$ratios"
                    if (mode == "glass") {
                        assertTrue(ratios[0] > ratios[1] + .08f && ratios[1] > ratios[2] + .08f,
                            "Diffusion strength was independent of opacity: $context")
                        assertTrue(ratios[2] < .75f, "A tint alone preserved the hard edge: $context")
                    } else {
                        assertTrue(ratios.take(3).all { it > .95f }, "Minimal/off unexpectedly diffused: $context")
                    }
                    assertEquals(ratios[0], ratios[3], .01f, "Diffusion failed live round-trip: $context")
                } finally { scene.close() }
            }
    }

    @Test fun embeddedDataUsesThePublishedParentPlaneWithoutRecapturingIt() = onUi {
        for (dark in listOf(false, true)) for (mode in listOf("glass", "minimal", "off")) {
            val opacity = mutableStateOf(.2f)
            val page = mutableStateOf(Color.Red)
            var parent = Color.Unspecified
            val scene = scene(320, dark, mode, opacity) {
                Box(Modifier.fillMaxSize().folioRoomSource().background(Color.Gray))
                parent = folioDataGlassPlane()
                FolioGlassPanel(Modifier.offset(16.dp, 16.dp).size(288.dp, 224.dp)) {
                    // Opaque content inside the owner must not be replaced by a second capture.
                    Box(Modifier.size(240.dp, 160.dp).background(page.value)) {
                        FolioGlassPanel(Modifier.fillMaxSize(), contentPadding = PaddingValues(0.dp)) {}
                    }
                }
            }
            try {
                for (alpha in listOf(.2f, .9f, 1f, .2f)) for (color in listOf(Color.Red, Color.Blue)) {
                    opacity.value = alpha
                    page.value = color
                    val actual = scene.pixels().getRGB(150, 130)
                    val expected = parent.copy(alpha = parent.alpha * .25f).compositeOver(color).toArgb()
                    assertTrue(distance(expected, actual) <= 2,
                        "Nested material did not inherit its parent's tint/fill: dark=$dark mode=$mode alpha=$alpha expected=$expected actual=$actual")
                }
            } finally { scene.close() }
        }
    }

    @Test fun texturedRoomContrastIsDiffusedRatherThanMerelyTinted() = onUi {
        for (width in listOf(320, 393)) for (dark in listOf(false, true))
            for (mode in listOf("glass", "minimal", "off")) for (nested in listOf(false, true)) {
                val opacity = mutableStateOf(.2f)
                val scene = scene(width, dark, mode, opacity, sheen = mutableStateOf(0f)) {
                    Box(Modifier.fillMaxSize().folioRoomSource().drawWithCache {
                        onDrawBehind {
                            drawRect(Color.White)
                            for (y in 0 until size.height.toInt() step 8) for (x in 0 until size.width.toInt() step 8) {
                                if ((x / 8 + y / 8) % 2 == 0) drawRect(Color.Black, Offset(x.toFloat(), y.toFloat()), Size(8f, 8f))
                            }
                        }
                    })
                    dataPanel(width, nested)
                }
                try {
                    val ratios = listOf(.2f, .6f, .9f, 1f, .2f).map { alpha ->
                        opacity.value = alpha
                        val image = scene.pixels()
                        val black = mutableListOf<Int>()
                        val white = mutableListOf<Int>()
                        for (y in 112 until 176 step 8) for (x in 64 until width - 64 step 8) {
                            val red = image.getRGB(x + 4, y + 4) shr 16 and 255
                            if ((x / 8 + y / 8) % 2 == 0) black.add(red) else white.add(red)
                        }
                        val contrast = abs(white.average() - black.average())
                        val fill = FolioSurfaceOpacity(panel = alpha).panelFill(mode == "glass")
                        if (alpha == 1f) {
                            assertTrue(contrast <= 1.0, "A solid data panel still exposed texture")
                            0.0
                        } else {
                            // Remove tint attenuation from the oracle; tint alone resolves to 1.
                            val painted = (1f - fill) * if (nested) (1f - fill * .25f) else 1f
                            contrast / (255f * painted)
                        }
                    }
                    val context = "width=$width dark=$dark mode=$mode nested=$nested ratios=$ratios"
                    if (mode == "glass") {
                        assertTrue(ratios[0] > ratios[1] + .08 && ratios[1] > ratios[2] + .08,
                            "Texture diffusion did not track the opacity: $context")
                        assertTrue(ratios[2] < .75, "Texture was only tinted, not diffused: $context")
                    } else {
                        assertTrue(ratios.take(3).all { abs(it - 1.0) < .06 }, "Minimal/off unexpectedly diffused texture: $context")
                    }
                    assertEquals(ratios[0], ratios[4], .01, "Texture diffusion did not restore: $context")
                } finally { scene.close() }
            }
    }

    @Test fun readerDiffusionAlsoTransmitsTheDirectPageInsteadOfAnIndependentCapture() = onUi {
        for (dark in listOf(false, true)) for (mode in listOf("glass", "off")) {
            val opacity = mutableStateOf(.2f)
            val page = mutableStateOf(Color.Red)
            val scene = scene(320, dark, mode, opacity) {
                Box(Modifier.fillMaxSize().folioRoomSource().background(Color.Green))
                // A valid reader source distinct from both the room and the visible page.
                Box(Modifier.fillMaxSize().folioBackdropSource().background(Color.Gray))
                Box(Modifier.fillMaxSize().background(page.value))
                Box(Modifier.offset(16.dp, 16.dp).size(288.dp, 224.dp).readerIsland())
            }
            try {
                val levels = listOf(.2f, .6f, .9f, 1f, .2f).map { alpha ->
                    opacity.value = alpha
                    page.value = Color.Red
                    val red = scene.pixels().getRGB(150, 130)
                    page.value = Color.Blue
                    distance(red, scene.pixels().getRGB(150, 130))
                }
                assertTrue(levels[0] > 40 && levels[0] > levels[1] && levels[1] > levels[2] && levels[2] > 0,
                    "Reader diffusion ignored the direct page: dark=$dark mode=$mode levels=$levels")
                assertEquals(0, levels[3], "Reader glass leaked at 100%")
                assertEquals(levels[0], levels[4], "Reader diffusion did not restore")
            } finally { scene.close() }
        }
    }

    @Test fun finishIsContinuousAcrossTheWholeClippedShapeAndBehindOpaqueInk() = onUi {
        for (width in listOf(320, 393)) {
            val mask = surfaceMask(width)
            for (dark in listOf(false, true)) for (pack in listOf("", "copper", "acid")) {
                val sheen = mutableStateOf(0f)
                val ink = Color(0xffb21d65)
                val scene = scene(width, dark, "off", mutableStateOf(.9f), sheen, pack) {
                    Box(Modifier.fillMaxSize().sharpEdge())
                    FolioGlassPanel(Modifier.offset(16.dp, 16.dp).size((width - 32).dp, 224.dp)) {
                        Text("MMMM HHHH", color = ink, fontSize = 24.sp)
                        Box(Modifier.size(120.dp, 8.dp).background(ink))
                    }
                }
                try {
                    val off = scene.pixels()
                    sheen.value = 1f
                    val full = scene.pixels()
                    val context = "width=$width dark=$dark pack=$pack"
                    assertOutsideUnchanged(off, full, mask, context)
                    for (quarter in 0..3) {
                        val changed = (24 until width - 24).sumOf { x ->
                            (20 + quarter * 54 until 20 + (quarter + 1) * 54).count { y ->
                                off.getRGB(x, y) != ink.toArgb() && off.getRGB(x, y) != full.getRGB(x, y)
                            }
                        }
                        assertTrue(changed > 100, "Finish was confined to an internal band: $context quarter=$quarter changed=$changed")
                    }
                    // The old top-quarter/56dp cap ended here regardless of gradient direction.
                    for (y in listOf(71, 72, 73)) {
                        val changed = (48 until width - 48).count { x -> off.getRGB(x, y) != full.getRGB(x, y) }
                        assertTrue(changed > 20, "Finish has a flat interior cutoff: $context y=$y")
                    }
                    val cutJump = (48 until width - 48).map { x ->
                        listOf(0, 8, 16).maxOf { shift ->
                            fun channel(image: BufferedImage, y: Int) = image.getRGB(x, y) shr shift and 255
                            abs((channel(full, 71) - channel(off, 71)) - (channel(full, 72) - channel(off, 72)))
                        }
                    }.average()
                    assertTrue(cutJump <= 2.0, "Finish cuts a rectangle inside its outline: $context jump=$cutJump")
                    var inkPixels = 0
                    for (y in 35..100) for (x in 35 until width - 35) if (off.getRGB(x, y) == ink.toArgb()) {
                        inkPixels++
                        assertEquals(off.getRGB(x, y), full.getRGB(x, y), "Finish washed opaque text/ink: $context x=$x y=$y")
                    }
                    assertTrue(inkPixels > 100, "The text/ink fixture was not drawn")
                    assertContentEquals(full.argb(), scene.pixels().argb(), "Finish introduced an idle clock: $context")
                    sheen.value = 0f
                    assertContentEquals(off.argb(), scene.pixels().argb(), "Finish did not restore live: $context")
                } finally { scene.close() }
            }
        }
    }

    @Test fun aNestedCardDoesNotOwnAnotherFinishOrSpectralLine() = onUi {
        for (dark in listOf(false, true)) for (pack in listOf("", "copper", "acid")) {
            val sheen = mutableStateOf(0f)
            val scene = scene(320, dark, "glass", mutableStateOf(.9f), sheen, pack) {
                Box(Modifier.fillMaxSize().folioRoomSource().sharpEdge())
                FolioGlassPanel(Modifier.offset(16.dp, 16.dp).size(288.dp, 224.dp), contentPadding = PaddingValues(24.dp)) {
                    // This masks only the owner's finish; any sheen here belongs to the child.
                    Box(Modifier.size(240.dp, 160.dp).background(Color(0xff304956))) {
                        FolioGlassPanel(Modifier.fillMaxSize(), contentPadding = PaddingValues(0.dp)) {}
                    }
                }
            }
            try {
                val off = scene.pixels()
                sheen.value = 1f
                val full = scene.pixels()
                assertContentEquals(off.getRGB(40, 40, 240, 160, null, 0, 240),
                    full.getRGB(40, 40, 240, 160, null, 0, 240),
                    "Child repeated its parent's finish/catch: dark=$dark pack=$pack")
                val changed = (24..295).sumOf { x -> (205..230).count { y -> off.getRGB(x, y) != full.getRGB(x, y) } }
                assertTrue(changed > 100, "The owning surface lost its finish: dark=$dark pack=$pack")
            } finally { scene.close() }
        }
    }

    private fun scene(width: Int, dark: Boolean, mode: String, opacity: MutableState<Float>,
        sheen: MutableState<Float> = mutableStateOf(1f), pack: String = "",
        content: @Composable BoxScope.() -> Unit): RasterScene {
        return RasterScene(ImageComposeScene(width, 260) {
            FolioTheme.MaterialTheme(darkTheme = dark,
                opacity = FolioSurfaceOpacity(panel = opacity.value, readerChrome = opacity.value),
                sheen = FolioSheen(sheen.value)) {
                CompositionLocalProvider(LocalFolioPackId provides pack) {
                    val enabled = mode != "off"
                    FolioGlassRoot(GlassCapabilities(enabled, enabled, enabled), inFlowGlass = mode != "minimal") {
                        Box(Modifier.fillMaxSize(), content = content)
                    }
                }
            }
        })
    }

    @Composable private fun dataPanel(width: Int, nested: Boolean) {
        FolioGlassPanel(Modifier.offset(16.dp, 16.dp).size((width - 32).dp, 224.dp), contentPadding = PaddingValues(24.dp)) {
            if (nested) FolioGlassPanel(Modifier.size((width - 80).dp, 160.dp), contentPadding = PaddingValues(0.dp)) {}
        }
    }

    private fun Modifier.sharpEdge() = drawWithCache {
        onDrawBehind {
            drawRect(Color.White)
            // Keep the oracle's edge on an integer pixel at both 320 and 393 widths.
            drawRect(Color.Black, Offset.Zero, Size((size.width.toInt() / 2).toFloat(), size.height))
        }
    }

    private fun surfaceMask(width: Int): BufferedImage {
        val scene = RasterScene(ImageComposeScene(width, 260) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.offset(16.dp, 16.dp).size((width - 32).dp, 224.dp)
                    .clip(FolioMaterialShapes.reference).background(Color.White))
            }
        })
        try { return scene.pixels() } finally { scene.close() }
    }

    private fun assertOutsideUnchanged(a: BufferedImage, b: BufferedImage, mask: BufferedImage, context: String) {
        for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask.getRGB(x, y) == Color.Black.toArgb()) {
            assertEquals(a.getRGB(x, y), b.getRGB(x, y), "Material escaped its shape: $context x=$x y=$y")
        }
    }

    private fun distance(a: Int, b: Int) = listOf(0, 8, 16).maxOf { shift ->
        abs((a shr shift and 255) - (b shr shift and 255))
    }

    private fun BufferedImage.argb() = getRGB(0, 0, width, height, null, 0, width)

    private class RasterScene(private val scene: ImageComposeScene) {
        private var frame = 0L
        fun pixels(): BufferedImage {
            repeat(8) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 16_000_000L).close() }
            val image = scene.render(frame++ * 16_000_000L)
            try {
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try { return ImageIO.read(ByteArrayInputStream(data.bytes)) } finally { data.close() }
            } finally { image.close() }
        }
        fun close() = scene.close()
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}
