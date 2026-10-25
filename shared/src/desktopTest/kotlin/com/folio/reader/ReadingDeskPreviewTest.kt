package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.home.HomeItemKind
import com.folio.reader.ui.home.HomeScreen
import com.folio.reader.ui.home.HomeUiState
import com.folio.reader.ui.home.ReadingNowItem
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat

class ReadingDeskPreviewTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderArtworkDeskWithRealCachedImagesAndLargeType() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val directory = File("build/visual-review").apply { mkdirs() }
        val covers = (0..2).map { index ->
            val file = File(directory, "artwork-fixture-$index.png")
            val image = java.awt.image.BufferedImage(240, 360, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val canvas = image.createGraphics()
            try {
                val hues = listOf(java.awt.Color(25, 100, 150), java.awt.Color(180, 70, 42), java.awt.Color(65, 110, 85))
                canvas.paint = java.awt.GradientPaint(0f, 0f, hues[index], 240f, 360f, java.awt.Color(230, 173, 97))
                canvas.fillRect(0, 0, 240, 360)
                canvas.color = java.awt.Color(245, 198, 100)
                canvas.fillOval(15 + index * 30, 25, 150, 150)
                canvas.color = java.awt.Color(20, 45, 65)
                canvas.fillPolygon(intArrayOf(0, 240, 240, 0), intArrayOf(230, 130, 360, 360), 4)
                canvas.color = java.awt.Color.WHITE
                canvas.font = java.awt.Font("Serif", java.awt.Font.BOLD, 24)
                canvas.drawString("SMALL WORLDS", 12, 285)
            } finally { canvas.dispose() }
            javax.imageio.ImageIO.write(image, "png", file)
            file.absolutePath
        }
        for (dark in listOf(false, true)) for (width in listOf(320, 393)) {
            val scene = ImageComposeScene(width = width, height = 852) {
                FolioTheme.AppTheme(palette = if (dark) AppPalette.DARK else AppPalette.LIGHT) {
                    FolioGlassRoot(GlassCapabilities(true, true, true)) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, if (width == 320) 1.4f else 1f)) {
                            Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                                HomeScreen(state = HomeUiState(loaded = true, hasBooks = true, readingDesk = true,
                                    readingNow = covers.mapIndexed { index, path ->
                                        ReadingNowItem(HomeItemKind.BOOK, "art-$index", "Small Worlds",
                                            "A. Reader", .63f, coverPath = path)
                                    }), onOpenBook = {}, onOpenBookDetail = {}, onImportClick = {}, topInset = 36.dp)
                            }
                        }
                    }
                }
            }
            try {
                repeat(20) { frame ->
                    scene.render(frame * 100_000_000L).close()
                    Thread.sleep(25)
                }
                val image = scene.render(2_100_000_000L)
                try {
                    File(directory, "home-artwork-${if (dark) "dark" else "light"}-$width.png").writeBytes(
                        image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                } finally { image.close() }
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderOneTwoAndThreeCoversAtNarrowPhoneWidth() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val directory = File("build/visual-review").apply { mkdirs() }
        for (count in 1..3) {
            val scene = ImageComposeScene(width = 320, height = 852) {
                FolioTheme.AppTheme(palette = AppPalette.LIGHT) {
                    Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                        HomeScreen(state = HomeUiState(loaded = true, hasBooks = true, readingDesk = true,
                            readingNow = listOf(
                                ReadingNowItem(HomeItemKind.BOOK, "one", "The Art of Noticing", "A. Reader", .63f),
                                ReadingNowItem(HomeItemKind.BOOK, "two", "Small Worlds", "A. Reader", .2f),
                                ReadingNowItem(HomeItemKind.BOOK, "three", "The City & the City", "A. Reader", .27f),
                            ).take(count)), onOpenBook = {}, onOpenBookDetail = {}, onImportClick = {}, topInset = 36.dp)
                    }
                }
            }
            try {
                repeat(8) { scene.render(it * 250_000_000L) }
                File(directory, "home-shelf-narrow-$count.png").writeBytes(
                    scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderActualHomeAtPhoneWidthsForComparison() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val directory = File("build/visual-review").apply { mkdirs() }
        for (desk in listOf(false, true)) for (dark in listOf(false, true)) {
            val scene = ImageComposeScene(width = 393, height = 852) {
                FolioTheme.AppTheme(palette = if (dark) AppPalette.DARK else AppPalette.LIGHT) {
                    Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                        HomeScreen(
                            state = HomeUiState(loaded = true, hasBooks = true, readingDesk = desk,
                                streakDays = 13, readingNow = listOf(
                                    ReadingNowItem(HomeItemKind.BOOK, "preview", "The Art of Noticing",
                                        "A. Reader", 0.63f),
                                    ReadingNowItem(HomeItemKind.BOOK, "next", "Small Worlds", "A. Reader", 0.2f),
                                    ReadingNowItem(HomeItemKind.BOOK, "third", "The City & the City", "A. Reader", 0.27f),
                                    ReadingNowItem(HomeItemKind.BOOK, "fourth", "A Place for Us", "A. Reader", 0.42f),
                                )),
                            onOpenBook = {}, onOpenBookDetail = {}, onImportClick = {}, topInset = 36.dp,
                        )
                    }
                }
            }
            try {
                repeat(8) { scene.render(it * 250_000_000L) }
                val image = scene.render(2_000_000_000L)
                File(directory, "home-${if (desk) "desk" else "classic"}-${if (dark) "dark" else "light"}.png")
                    .writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            } finally {
                scene.close()
            }
        }
    }
}
