package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class QuietSignalTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/inter_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
    private fun pixels(scene: ImageComposeScene, time: Long): java.awt.image.BufferedImage {
        val image = scene.render(time)
        try { return ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)) }
        finally { image.close() }
    }

    @Test fun reducedMotionDockSettlesAndStillOpensImmediately() = onUi {
        var opened = 0
        val reduced = resolveMotion(MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED), MotionTier.STANDARD, false)
        val scene = ImageComposeScene(320, 200) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                CompositionLocalProvider(LocalMotion provides reduced) {
                    Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                        FolioActionDock("Continue reading", "63% complete", { opened++ }, progress = .63f)
                    }
                }
            }
        }
        try {
            repeat(10) { Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L).close() }
            val a = pixels(scene, 1_100_000_000L)
            val b = pixels(scene, 2_500_000_000L)
            assertContentEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width),
                b.getRGB(0, 0, b.width, b.height, null, 0, b.width), "Reduced-motion dock kept animating at rest")
            scene.sendPointerEvent(PointerEventType.Press, Offset(150f, 60f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(150f, 60f))
            scene.render(2_600_000_000L).close()
            assertEquals(1, opened, "Visual feedback delayed or swallowed the reading action")
        } finally { scene.close() }
    }

    @Test fun instrumentSliderKeepsNativeValueMapping() = onUi {
        val value = mutableStateOf(.25f)
        var bounds = Rect.Zero
        val scene = ImageComposeScene(320, 150) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    FolioSlider(value.value, { value.value = it },
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() })
                }
            }
        }
        var frame = 0L
        fun render() { repeat(5) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
        fun tap(fraction: Float) {
            val point = Offset(bounds.left + bounds.width * fraction, bounds.center.y)
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        try {
            render(); tap(.8f); assertTrue(value.value > .65f)
            tap(.2f); assertTrue(value.value < .35f)
        } finally { scene.close() }
    }

    @Test fun spectralEdgeHonorsSheenWithoutChangingPanelFill() = onUi {
        fun render(sheen: Float, dark: Boolean, ground: Color = Color(0xff263f49)): java.awt.image.BufferedImage {
            val scene = ImageComposeScene(300, 220) {
                FolioTheme.MaterialTheme(darkTheme = dark, sheen = FolioSheen(sheen)) {
                    FolioGlassRoot(GlassCapabilities.None) {
                        Box(Modifier.fillMaxSize().background(ground)) {
                            FolioGlassPanel(Modifier.size(260.dp, 180.dp)) {}
                        }
                    }
                }
            }
            try {
                repeat(6) { Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L).close() }
                return pixels(scene, 700_000_000L)
            } finally { scene.close() }
        }
        for (dark in listOf(false, true)) {
            val off = render(0f, dark)
            val full = render(1f, dark)
            val changed = (24..230).sumOf { x -> (0..3).count { y -> off.getRGB(x, y) != full.getRGB(x, y) } }
            assertTrue(changed > 20, "Spectral edge ignored the metallic-sheen slider")
            fun channel(image: java.awt.image.BufferedImage, shift: Int) = image.getRGB(130, 80) shr shift and 255
            val centerLight = listOf(0, 8, 16).maxOf { kotlin.math.abs(channel(off, it) - channel(full, it)) }
            assertTrue(centerLight in 1..12, "Whole-surface finish must remain subtle rather than an opaque internal plate: $centerLight")
            val darkGround = listOf(0f, 1f).map { render(it, dark, Color.Black) }
            val lightGround = listOf(0f, 1f).map { render(it, dark, Color.White) }
            for (shift in listOf(0, 8, 16)) {
                val before = channel(lightGround[0], shift) - channel(darkGround[0], shift)
                val after = channel(lightGround[1], shift) - channel(darkGround[1], shift)
                assertTrue(kotlin.math.abs(before - after) <= 2, "Sheen changed the panel's backdrop transmission")
            }
            val target = File("build/visual-review/quiet-signal/edge-${if (dark) "dark" else "light"}.png")
            target.parentFile.mkdirs(); ImageIO.write(full, "png", target)
        }
    }
}
