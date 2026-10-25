package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.manga.MangaReaderMode
import com.folio.reader.ui.manga.ReaderControls
import com.folio.reader.ui.reader.readerIsland
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** Covers WebView/occluded readers as well as readers with a captured Compose page. */
@OptIn(ExperimentalComposeUiApi::class)
class ReaderHostTransparencyTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun bookAndDocumentControlsTransmitWithoutACapturedPage() = checkHosts(manga = false)

    @Test fun mangaTopAndBottomBarsFollowTheReaderSetting() = checkHosts(manga = true)

    @Test fun hostedReaderDialogsKeepTheWebViewFallbackAndLiveOpacity() {
        val opacity = mutableStateOf(.2f)
        val page = mutableStateOf(Color.Red)
        val controller = FolioOverlayController()
        val scene = ImageComposeScene(393, 400) {
            FolioTheme.MaterialTheme(darkTheme = true,
                opacity = FolioSurfaceOpacity(readerChrome = opacity.value)) {
                FolioGlassRoot(GlassCapabilities(true, true, true)) {
                    CompositionLocalProvider(LocalFolioOverlay provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().folioRoomSource().background(Color.Gray))
                            Box(Modifier.fillMaxSize().background(page.value))
                            FolioSuppressibleBackdrop(suppress = true) {
                                FolioDialog(expanded = true, onDismissRequest = {},
                                    fillAlpha = FolioTheme.readerPanelVeilAlpha, readerMaterial = true) {
                                    Box(Modifier.size(220.dp, 180.dp))
                                }
                            }
                            FolioOverlayHost()
                        }
                    }
                }
            }
        }
        var frame = 0L
        fun pixel(): Int {
            repeat(8) { scene.render(frame++ * 100_000_000L).close() }
            val image = scene.render(frame++ * 100_000_000L)
            try {
                return ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes))
                    .getRGB(196, 200)
            } finally { image.close() }
        }
        fun transmission(alpha: Float): Int {
            opacity.value = alpha
            page.value = Color.Red
            val red = pixel()
            page.value = Color.Blue
            val blue = pixel()
            return listOf(0, 8, 16).maxOf { abs((red shr it and 255) - (blue shr it and 255)) }
        }
        try {
            val low = transmission(.2f)
            assertFalse(controller.entries.single().blurEnabled, "Rehosting lost the WebView material policy")
            assertTrue(controller.entries.single().readerMaterial, "Rehosting lost the clean reader material")
            val token = controller.entries.single().token
            val middle = transmission(.6f)
            assertTrue(low > 20 && middle < low, "Hosted reader opacity did not respond: $low / $middle")
            assertEquals(0, transmission(1f), "Hosted reader dialog still transmits at 100%")
            assertEquals(low, transmission(.2f), "The open reader dialog did not update live")
            assertEquals(token, controller.entries.single().token, "Updating opacity reopened the dialog")
        } finally { scene.close() }
    }

    private fun checkHosts(manga: Boolean) {
        for (blur in listOf(false, true)) for (source in listOf("missing", "unrelated", "page")) {
            val opacity = mutableStateOf(.2f)
            val page = mutableStateOf(Color.Red)
            val scene = ImageComposeScene(393, 400) {
                FolioTheme.MaterialTheme(darkTheme = true,
                    opacity = FolioSurfaceOpacity(readerChrome = opacity.value)) {
                    FolioGlassRoot(GlassCapabilities(blur, blur, blur)) {
                        Box(Modifier.fillMaxSize()) {
                            if (source == "unrelated") {
                                Box(Modifier.fillMaxSize().folioRoomSource().background(Color.Gray))
                            }
                            Box(Modifier.fillMaxSize()
                                .then(if (source == "page") Modifier.folioBackdropSource() else Modifier)
                                .background(page.value))
                            if (manga) {
                                ReaderControls(mangaTitle = "M", chapterName = "C", pageCount = 20,
                                    currentPage = 3, mode = MangaReaderMode.PAGED_LTR, rtl = false,
                                    bookmarked = false, onToggleBookmark = {}, onShowNotes = {},
                                    onShowSettings = {}, onSeek = {}, onBack = {})
                            } else {
                                // Match the WebView reader scope; PDF retains the captured page.
                                FolioSuppressibleBackdrop(suppress = source != "page") {
                                    Box(Modifier.size(260.dp, 180.dp).readerIsland())
                                }
                            }
                        }
                    }
                }
            }
            var frame = 0L
            fun pixels(label: String): List<Int> {
                repeat(6) { scene.render(frame++ * 100_000_000L).close() }
                val image = scene.render(frame++ * 100_000_000L)
                try {
                    val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                    if (source == "missing" && page.value == Color.Red) {
                        File("build/visual-review/reader-hosts/${if (manga) "manga" else "book-docx"}-$label.png")
                            .apply { parentFile.mkdirs(); writeBytes(bytes) }
                    }
                    val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
                    // Sample inside the floating strips, away from their deliberate edge gaps.
                    return if (manga) listOf(bitmap.getRGB(12, 24), bitmap.getRGB(12, bitmap.height - 24))
                        else listOf(bitmap.getRGB(130, 80))
                } finally { image.close() }
            }
            fun transmission(alpha: Float): List<Int> {
                opacity.value = alpha
                page.value = Color.Red
                val red = pixels("${(alpha * 100).toInt()}")
                page.value = Color.Blue
                val blue = pixels("blue")
                return red.zip(blue).map { (r, b) ->
                    listOf(0, 8, 16).maxOf { abs((r shr it and 255) - (b shr it and 255)) }
                }
            }
            try {
                val context = "manga=$manga blur=$blur source=$source"
                val low = transmission(.2f)
                val middle = transmission(.6f)
                val solid = transmission(1f)
                low.indices.forEach { index ->
                    assertTrue(low[index] > 20, "No page transmission at low opacity: $context bar=$index, $low")
                    assertTrue(middle[index] < low[index], "Opacity did not progressively increase: $context bar=$index")
                    assertEquals(0, solid[index], "100% still transmits: $context bar=$index")
                }
                assertEquals(low, transmission(.2f), "Live opacity did not return to its original rendering: $context")
            } finally { scene.close() }
        }
    }
}
