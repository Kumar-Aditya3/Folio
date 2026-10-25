package com.folio.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.FolioGlassCost
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.GlassSpec
import com.folio.reader.ui.components.folioGlassQuality
import com.folio.reader.ui.components.folioRoomSource
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

class FolioOpticalGlassTest {
    @Test
    fun qualitySpendsLessDuringScrollAndRestoresDetailAtRest() {
        for (budget in listOf(FolioGlassCost.floor, FolioGlassCost.pane, FolioGlassCost.control,
            Float.NaN, Float.POSITIVE_INFINITY, -1f, 1f)) {
            val resting = folioGlassQuality(budget, moving = false)
            val scrolling = folioGlassQuality(budget, moving = true)
            assertTrue(resting in 0f..1f && scrolling in 0f..1f)
            assertTrue(scrolling < resting)
            assertEquals(resting, folioGlassQuality(budget, moving = false))
        }
        assertTrue(folioGlassQuality(FolioGlassCost.control, false) > folioGlassQuality(FolioGlassCost.floor, false))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun opticalMaterialChangesActualBackdropPixels() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val directory = File("build/visual-review").apply { mkdirs() }
        for (dark in listOf(true, false)) {
            val before = render(GlassCapabilities.None, dark)
            val after = render(GlassCapabilities(blur = true, noise = true, specular = true), dark)
            val mode = if (dark) "dark" else "light"
            File(directory, "glass-painted-$mode.png").writeBytes(before)
            File(directory, "glass-optical-$mode.png").writeBytes(after)
            val a = ImageIO.read(ByteArrayInputStream(before))
            val b = ImageIO.read(ByteArrayInputStream(after))
            var changed = 0
            for (y in 130 until 300) for (x in 220 until 740) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
            }
            assertTrue(changed > 10_000, "$mode: glass must visibly change the panel, changed=$changed")
            // A tint alone changes colours but preserves a hard book-spine edge.
            // Find one above the panel, then require the corresponding edge inside
            // the panel to spread across pixels rather than remain a single jump.
            fun distance(left: Int, right: Int): Int = listOf(0, 8, 16).sumOf { shift ->
                kotlin.math.abs(((left shr shift) and 255) - ((right shr shift) and 255))
            }
            val edge = (250..350).maxBy { x -> distance(a.getRGB(x - 1, 130), a.getRGB(x, 130)) }
            val jump = distance(b.getRGB(edge - 1, 285), b.getRGB(edge, 285))
            val spread = distance(b.getRGB(edge - 8, 285), b.getRGB(edge + 8, 285))
            assertTrue(spread > 2 && jump < spread * 0.45f,
                "$mode: diffusion must soften the edge, not merely tint it: jump=$jump spread=$spread")
            // The effect must remain bounded rather than recolouring the whole scene.
            assertEquals(a.getRGB(10, 10), b.getRGB(10, 10))
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(capabilities: GlassCapabilities, dark: Boolean): ByteArray {
        if (System.getenv("FOLIO_GLASS_WINDOW_CAPTURE") == "1") return renderWindow(capabilities, dark)
        val scene = ImageComposeScene(width = 960, height = 540) { GlassPreviewScene(capabilities, dark) }
        try {
            // Let demand-based source registration settle before taking the final frame.
            repeat(3) { frame -> scene.render(frame * 16_000_000L).close() }
            val image = scene.render(48_000_000L)
            try {
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try { return data.bytes } finally { data.close() }
            } finally { image.close() }
        } finally { scene.close() }
    }
    @Composable
    private fun GlassPreviewScene(capabilities: GlassCapabilities, dark: Boolean) {
        val ink = if (dark) Color(0xFFF3EADC) else Color(0xFF202D37)
        val plane = if (dark) Color(0xFF182A38) else Color(0xFFF2EDE3)
        val accent = if (dark) Color(0xFFE8C591) else Color(0xFF62432C)
            FolioTheme.MaterialTheme(darkTheme = dark) {
                FolioGlassRoot(capabilities) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().folioRoomSource().drawWithCache {
                            val background = Brush.linearGradient(listOf(Color(0xFF112F3E), Color(0xFF452C48)))
                            onDrawBehind {
                                drawRect(background)
                                for (i in 0..11) {
                                    drawRect(
                                        listOf(Color(0xFFB06F4E), Color(0xFF668C86), Color(0xFF656284))[i % 3],
                                        Offset(90f + i * 68f, 125f + (i % 3) * 12f), Size(48f, 330f),
                                    )
                                    drawLine(Color(0xFFE3C390), Offset(100f + i * 68f, 170f),
                                        Offset(128f + i * 68f, 170f), 2f)
                                }
                            }
                        })
                        Text("Folio / Your private library", color = Color(0xFFF3EADC), fontSize = 28.sp,
                            modifier = Modifier.padding(44.dp))
                        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(34.dp)) {
                            Column(Modifier.size(520.dp, 170.dp)
                                .folioVeil(shape = RoundedCornerShape(28.dp), fillColor = plane,
                                    fillAlpha = 0.68f)
                                .padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Return to your story", color = ink, fontSize = 26.sp)
                                Text("Chapter twelve · 18 minutes remaining", color = ink.copy(alpha = 0.9f), fontSize = 16.sp)
                                Text("Continue reading  →", color = accent, fontSize = 16.sp)
                            }
                            Row(Modifier.size(520.dp, 64.dp)
                                .folioVeil(shape = RoundedCornerShape(32.dp), fillColor = plane,
                                    fillAlpha = 0.55f, glass = GlassSpec.Control)
                                .padding(horizontal = 32.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                for (label in listOf("Home", "Library", "Insights", "More")) {
                                    Text(label, color = ink, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }

    }

    /** Opt-in native-window capture; ordinary CI keeps the deterministic offscreen render. */
    private fun renderWindow(capabilities: GlassCapabilities, dark: Boolean): ByteArray {
        val ready = CountDownLatch(1)
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                isUndecorated = true
                isAlwaysOnTop = true
                contentPane.preferredSize = Dimension(960, 540)
                setContent {
                    GlassPreviewScene(capabilities, dark)
                    LaunchedEffect(Unit) {
                        repeat(6) { withFrameNanos { } }
                        ready.countDown()
                    }
                }
                pack()
                setSize(960, 540)
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
        try {
            check(ready.await(20, TimeUnit.SECONDS)) { "Native preview did not render" }
            println("Folio native glass preview: renderApi=${window.renderApi}, size=${window.size}")
            Thread.sleep(250)
            val capture = Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size))
            return ByteArrayOutputStream().use { output ->
                ImageIO.write(capture, "png", output)
                output.toByteArray()
            }
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

}
