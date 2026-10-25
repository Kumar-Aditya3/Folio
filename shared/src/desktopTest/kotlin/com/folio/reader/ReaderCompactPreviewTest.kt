package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.reader.BottomPageBlock
import com.folio.reader.ui.reader.ReaderFloatingRail
import com.folio.reader.ui.reader.ReaderTopBar
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

@OptIn(ExperimentalComposeUiApi::class)
class ReaderCompactPreviewTest {
    @Test fun compactReaderAtPhoneWidthsAndBothAppearances() = SwingUtilities.invokeAndWait {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
        for (dark in listOf(false, true)) for (width in listOf(320, 393)) for (selected in listOf(false, true)) {
            val scene = ImageComposeScene(width, 800) {
                FolioTheme.MaterialTheme(darkTheme = dark) {
                    FolioGlassRoot(GlassCapabilities.None) {
                        Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                            Column(Modifier.padding(26.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text("The quiet between", style = FolioTheme.typography.headlineSmall)
                                repeat(8) { Text("The city grew quieter as the light moved across the windows. There was still time to stay a little longer.",
                                    style = FolioTheme.typography.bodyLarge) }
                            }
                            ReaderTopBar("The Long Way Home", false, false, null, null, {}, false, Color.Yellow,
                                {}, {}, {}, {}, {}, {}, echoesEnabled = true)
                            Box(Modifier.align(Alignment.CenterStart)) {
                                ReaderFloatingRail(if (selected) Triple("one", 0, "There was still time") else null,
                                    { _, _, _ -> }, {}, false, Color.Yellow, {}, {}, {}, {}, echoesEnabled = true)
                            }
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                BottomPageBlock("The quiet between", .63f, FolioTheme.colors.background,
                                    FolioTheme.colors.onSurface, "Page 124 of 196", pagesLeftLabel = "12 left", onSeek = {})
                            }
                        }
                    }
                }
            }
            try {
                repeat(8) { Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L).close() }
                val image = scene.render(900_000_000L)
                try { File("build/visual-review/compact-reader/${if (dark) "dark" else "light"}-$width-$selected.png").apply {
                    parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                } } finally { image.close() }
            } finally { scene.close() }
        }
    }
}
