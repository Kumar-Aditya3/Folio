package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentFormat
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.*
import com.folio.reader.ui.document.*
import com.folio.reader.ui.reader.*
import com.folio.reader.ui.settings.GlassTreatmentPicker
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

class InterfacePolishRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun readingChromeAndModesAtNarrowAndLargeTextSizes() {
        val fonts = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                val directory = File("build/visual-review/interface-polish").apply { mkdirs() }
                for (dark in listOf(false, true)) for (width in listOf(320, 393)) for (mode in 0..2) {
                    val settings = ReaderSettings(themeId = if (dark) "ember" else "paper", liquidGlassEffects = mode != 2, minimalLiquidGlass = mode == 1)
                    val document = Document("preview", "The Architecture of a Quiet Reading Life — notes and observations",
                        "reading-life.docx", DocumentFormat.DOCX, "application/octet-stream", "hash", 1024)
                    val scene = ImageComposeScene(width, 1000, density = Density(1f, if (width == 320) 1.4f else 1f)) {
                        FolioTheme.AppTheme(palette = if (dark) AppPalette.ESPRESSO else AppPalette.WARM) {
                            FolioGlassRoot(if (mode == 2) GlassCapabilities.None else GlassCapabilities(true, true, true), inFlowGlass = mode == 0) {
                                Box(Modifier.fillMaxSize()) {
                                Box(Modifier.matchParentSize().folioRoomSource().folioField())
                                Column(Modifier.fillMaxSize()) {
                                    DocumentReaderBar(DocumentReaderState(document = document), {}, {}, onOpenSettings = {}, onOpenPages = {})
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        GlassTreatmentPicker(settings, {})
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            FolioActionButton({}) { Text("Install") }
                                            FolioActionButton({}) { Text("Retry") }
                                        }
                                        FolioInputField("", {}, label = { Text("Repository URL") }, placeholder = { Text("https://…") })
                                        Box(Modifier.fillMaxWidth().height(180.dp).folioVeil(tier = FolioGlassTier.InFlow).padding(18.dp)) {
                                            Text("A quiet, finished panel in every glass mode.", color = FolioTheme.colors.onSurface,
                                                style = FolioTheme.typography.bodyLarge)
                                        }
                                    }
                                    Box(Modifier.fillMaxWidth().weight(1f)) {
                                        ReaderLoadingPage(settings)
                                        ReaderProgressRail(.38f, listOf(0f, .25f, .6f, 1f),
                                            Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().padding(vertical = 24.dp),
                                            androidx.compose.ui.graphics.Color(com.folio.reader.settings.Theme.getPreset(settings.themeId).primaryText))
                                    }
                                    BottomPageBlock("A place to begin", .38f, FolioTheme.colors.background, FolioTheme.colors.onSurface,
                                        "38% read", pagesLeftLabel = "38% · ~4 min in chapter", onSeek = {}, onOpenMap = {})
                                }
                                }
                            }
                        }
                    }
                    try {
                        repeat(4) { scene.render(it * 250_000_000L).close() }
                        scene.render(1_000_000_000L).use { image ->
                            File(directory, "${if (dark) "dark" else "light"}-$width-mode$mode.png")
                                .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        }
                    } finally { scene.close() }
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
