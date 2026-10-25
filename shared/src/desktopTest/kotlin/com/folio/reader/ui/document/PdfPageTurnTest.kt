package com.folio.reader.ui.document

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.LayoutDirection
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import java.io.File
import javax.swing.SwingUtilities
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PdfPageTurnTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/inter_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun tapsAnimateBothPagesAndOnlyCommitTheSettledTurn() = onUi {
        for (direction in LayoutDirection.entries) {
            val fixture = Fixture(initial = 1, direction = direction)
            try {
                fixture.settle()
                assertTrue(fixture.colors().green > 1_000)
                assertTrue(fixture.commits.isEmpty(), "Resume must not write page zero")
                fixture.tap(if (direction == LayoutDirection.Ltr) 286f else 34f)
                val frames = (0..39).map { fixture.frame() }
                assertTrue(frames.any { it.green > 500 && it.blue > 500 }, "Turn hard-replaced the page: $direction")
                assertEquals(listOf(2), fixture.commits)
                assertEquals(2, fixture.current.value)
                assertTrue(frames.last().blue > 1_000 && frames.last().green == 0)
                fixture.tap(if (direction == LayoutDirection.Ltr) 34f else 286f)
                val back = (0..39).map { fixture.frame() }
                assertTrue(back.any { it.blue > 500 && it.green > 500 })
                assertEquals(listOf(2, 1), fixture.commits)
                fixture.tap(160f)
                fixture.settle()
                assertEquals(1, fixture.chromeTaps)
                assertEquals(listOf(2, 1), fixture.commits)
            } finally { fixture.close() }
        }
    }

    @Test fun externalSeekDoesNotEchoAndBoundsDoNotOvershoot() = onUi {
        val fixture = Fixture(initial = 2)
        try {
            fixture.settle()
            fixture.tap(286f)
            fixture.settle()
            assertTrue(fixture.commits.isEmpty())
            fixture.current.value = 0
            fixture.settle()
            assertTrue(fixture.colors().red > 1_000)
            assertTrue(fixture.commits.isEmpty(), "Explicit seek must not become a second persistence event")
            fixture.tap(34f)
            fixture.settle()
            assertTrue(fixture.commits.isEmpty())
            fixture.tap(286f)
            repeat(40) { fixture.frame() }
            assertEquals(listOf(1), fixture.commits)
        } finally { fixture.close() }
    }

    @Test fun horizontalSwipeFollowsTheFingerWithoutDoubleTurning() = onUi {
        val fixture = Fixture(initial = 0)
        try {
            fixture.settle()
            fixture.scene.sendPointerEvent(PointerEventType.Press, Offset(270f, 200f), type = PointerType.Touch)
            fixture.frame()
            repeat(10) { step ->
                fixture.scene.sendPointerEvent(PointerEventType.Move, Offset(270f - (step + 1) * 22f, 200f), type = PointerType.Touch)
                fixture.frame()
            }
            val dragging = fixture.colors()
            assertTrue(dragging.red > 500 && dragging.green > 500, "A swipe must visually move before release")
            fixture.scene.sendPointerEvent(PointerEventType.Release, Offset(50f, 200f), type = PointerType.Touch)
            repeat(45) { fixture.frame() }
            assertEquals(listOf(1), fixture.commits, "Pager and page handler must not both turn")
            assertEquals(1, fixture.current.value)
        } finally { fixture.close() }
    }

    private class Fixture(initial: Int, direction: LayoutDirection = LayoutDirection.Ltr) {
        val current = mutableStateOf(initial)
        val commits = mutableListOf<Int>()
        var chromeTaps = 0
        private var clock = 0L
        private val cache = PixelBudgetLruCache<FixedPageCacheKey, ImageBitmap>(96L * 1024 * 1024) {
            it.width.toLong() * it.height * 4
        }
        private val bitmaps = listOf(Color.Red, Color.Green, Color.Blue).map { color ->
            ImageBitmap(296, 456).also { Canvas(it).drawRect(0f, 0f, 296f, 456f, Paint().apply { this.color = color }) }
        }
        private val document = object : FixedPageDocument {
            override val pageCount = 3
            override suspend fun render(request: FixedPageRenderRequest) = bitmaps[request.pageIndex]
            override fun close() = Unit
        }
        val scene: ImageComposeScene
        init {
            bitmaps.forEachIndexed { page, image ->
                cache.put(FixedPageCacheKey("test-pdf", page, 296, 456, 0, false), image)
            }
            scene = ImageComposeScene(320, 480, layoutDirection = direction) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalReaderPaper provides Color.Black) {
                        PagedPdfPages(document, "test-pdf", current.value, 0, 0, cache, Modifier,
                            onCurrentPageChanged = { commits += it; current.value = it },
                            onTap = { chromeTaps++ }, onError = { error("Unexpected render error: $it") })
                    }
                }
            }
        }
        fun frame(): Colors {
            Snapshot.sendApplyNotifications()
            val image = scene.render(clock++ * 16_000_000L)
            val raster = Bitmap()
            try {
                raster.allocPixels(ImageInfo(320, 480, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                check(image.readPixels(raster))
                val pixels = requireNotNull(raster.readPixels(raster.imageInfo, raster.rowBytes, 0, 0))
                var red = 0; var green = 0; var blue = 0
                for (y in 40 until 440 step 4) for (x in 4 until 316 step 4) {
                    val offset = y * raster.rowBytes + x * 4
                    val r = pixels[offset].toInt() and 255
                    val g = pixels[offset + 1].toInt() and 255
                    val b = pixels[offset + 2].toInt() and 255
                    if (r > 204 && g < 51 && b < 51) red++
                    if (g > 204 && r < 51 && b < 51) green++
                    if (b > 204 && r < 51 && g < 51) blue++
                }
                return Colors(red, green, blue)
            } finally { raster.close(); image.close() }
        }
        fun colors() = frame()
        fun settle() { repeat(30) { frame() } }
        fun tap(x: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 200f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 200f))
        }
        fun close() = scene.close()
    }

    private data class Colors(val red: Int, val green: Int, val blue: Int)
    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
