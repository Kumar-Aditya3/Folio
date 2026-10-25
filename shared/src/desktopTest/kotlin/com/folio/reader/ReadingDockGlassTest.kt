package com.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.FolioArtworkGlassScene
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.LocalGlassBackdrop
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioReadingDockGlass
import com.folio.reader.ui.components.folioRoomSource
import com.folio.reader.ui.theme.FolioTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

class ReadingDockGlassTest {
    @Test
    fun dockSamplesLocalArtworkAndHasAReadableCapabilityFallback() {
        for (dark in listOf(false, true)) for (native in listOf(false, true)) {
            val base = ImageIO.read(ByteArrayInputStream(render(dark, native, false)))
            val bytes = render(dark, native, true)
            val glass = ImageIO.read(ByteArrayInputStream(bytes))
            for (y in listOf(100, 150, 200)) {
                val before = base.getRGB(125, y)
                val after = glass.getRGB(125, y)
                val difference = listOf(0, 8, 16).maxOf { shift ->
                    abs((before shr shift and 255) - (after shr shift and 255))
                }
                if (native) assertTrue(difference < 135,
                    "Artwork was replaced by an unrelated glass floor: dark=$dark diff=$difference")
            }
            if (native) {
                val a = glass.getRGB(125, 150)
                val b = glass.getRGB(175, 150)
                assertTrue(listOf(0, 8, 16).maxOf { shift ->
                    abs((a shr shift and 255) - (b shr shift and 255))
                } > 20, "The dock lost the artwork's color structure")
            }
            assertEquals(base.getRGB(12, 12), glass.getRGB(12, 12))
            assertEquals(base.getRGB(280, 270), glass.getRGB(280, 270))
            File("build/visual-review").apply { mkdirs() }
                .resolve("reading-dock-$dark-$native.png").writeBytes(bytes)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(dark: Boolean, native: Boolean, glass: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 300, height = 300) {
            FolioTheme.MaterialTheme(darkTheme = dark) {
                FolioGlassRoot(if (native) GlassCapabilities(true, true, true) else GlassCapabilities.None) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().folioRoomSource().drawWithCache {
                            val stripes = listOf(Color(0xFFDC684D), Color(0xFF4C78C1), Color(0xFF43BBA2))
                            onDrawBehind {
                                repeat(6) { index -> drawRect(stripes[index % 3],
                                    Offset(index * 50f, 0f), Size(50f, size.height)) }
                            }
                        })
                        val room = LocalGlassBackdrop.current
                        Box(Modifier.fillMaxSize().folioBackdropSource()) {
                            FolioArtworkGlassScene(moving = false) {
                                // Artwork has a sibling source inside its own registry; never sample the parent page.
                                assertNotSame(room, LocalGlassBackdrop.current)
                                Box(Modifier.fillMaxSize().folioRoomSource().drawWithCache {
                                    val stripes = listOf(Color(0xFFDC684D), Color(0xFF4C78C1), Color(0xFF43BBA2))
                                    onDrawBehind {
                                        repeat(6) { index -> drawRect(stripes[index % 3],
                                            Offset(index * 50f, 0f), Size(50f, size.height)) }
                                    }
                                })
                                if (glass) Box(Modifier.align(Alignment.Center).size(180.dp, 220.dp)
                                    .folioReadingDockGlass(RoundedCornerShape(20.dp), Color(0xFF57BEB7)))
                            }
                        }
                    }
                }
            }
        }
        try {
            repeat(5) { scene.render(it * 16_000_000L).close() }
            val image = scene.render(100_000_000L)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                try { return data.bytes } finally { data.close() }
            } finally { image.close() }
        } finally { scene.close() }
    }
}
