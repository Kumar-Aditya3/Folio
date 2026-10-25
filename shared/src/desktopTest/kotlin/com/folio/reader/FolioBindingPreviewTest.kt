package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FallbackCover
import com.folio.reader.ui.theme.UiFonts
import com.folio.reader.ui.theme.FolioTheme
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test

/** A review artifact rendered by the actual Compose cover, rather than an HTML approximation. */
class FolioBindingPreviewTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderCoverContactSheet() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val scene = ImageComposeScene(width = 1080, height = 330) {
            FolioTheme.MaterialTheme {
                Row(
                    Modifier.background(Color(0xFFF1EDE5)).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (title in listOf("A Quiet Library", "The Art of Noticing", "Collected Stories",
                        "夜の図書館", "A Very Long Title That Should Wrap Naturally", "Field Notes")) {
                        Box(Modifier.size(160.dp, 280.dp)) {
                            FallbackCover(title, "Collected editions")
                        }
                    }
                }
            }
        }
        try {
            val image = scene.render()
            try {
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try {
                    File("build/visual-review").mkdirs()
                    File("build/visual-review/folio-bindings.png").writeBytes(data.bytes)
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }
}
