package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.folio.reader.model.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ReadingTraceInteractionTest {
    @Test fun openingATraceShowsItsPassagesWithoutAnExternalPopup() = SwingUtilities.invokeAndWait {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
        val controller = FolioOverlayController()
        val trace = ReadingTrace(.63f, listOf(ReadingTraceSpan(.1f, .63f, 30f)),
            listOf(ReadingTracePassage("passage", "chapter", 2, .25f, "The city grew quieter as the light moved across the windows.", true)), 1, 30)
        val source = flowOf(mapOf("book" to trace))
        var opened: Pair<String, String>? = null
        val scene = ImageComposeScene(393, 852) {
            FolioTheme.AppTheme(AppPalette.TEAL) {
                FolioGlassRoot(GlassCapabilities.None) {
                    CompositionLocalProvider(LocalFolioOverlay provides controller) {
                        Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                            FolioReadingTraceProvider(source, { id, passage -> opened = id to passage.id }) {
                                Box(Modifier.padding(20.dp)) { FolioReadingTrace("book", "Small Worlds", .63f) }
                            }
                            FolioOverlayHost()
                        }
                    }
                }
            }
        }
        var frame = 0L
        fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
        try {
            render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(180f, 50f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(180f, 50f))
            render()
            assertTrue(controller.isOpen, "The reading trace did not open its shared material sheet")
            assertNull(opened)
            val image = scene.render(frame++ * 100_000_000L)
            try {
                File("build/visual-review/reading-trace/passage-sheet.png").apply { parentFile.mkdirs() }
                    .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { image.close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(180f, 480f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(180f, 480f))
            render()
            assertEquals("book" to "passage", opened, "The saved passage did not follow its book/highlight deep link")
            assertFalse(controller.isOpen, "The trace sheet stayed open after following a passage")
        } finally { scene.close() }
    }
}
