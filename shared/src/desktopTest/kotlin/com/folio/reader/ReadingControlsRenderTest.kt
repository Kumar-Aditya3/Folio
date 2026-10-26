package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.experience.*
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.reader.ReaderSelectionBar
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

class ReadingControlsRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun settingsAndSelectionRemainReadableAtNarrowLargeTextSizes() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                val directory = File("build/visual-review/beautiful-exports").apply { mkdirs() }
                for (dark in listOf(false,true)) for (width in listOf(320,393)) {
                    val controller = SoundscapeController({ ShortArray(882) }, { error("A visual preview never opens an audio device") })
                    val prefs = SoundscapePreferences(Soundscape.RAIN, secondary = Soundscape.FIREPLACE, timerMinutes = 30)
                    controller.restore(prefs)
                    val session = ReaderExperienceSession(null,controller)
                    val scene = ImageComposeScene(width, 1500, density = Density(1f, if (width == 320) 1.4f else 1f)) {
                        FolioTheme.AppTheme(palette = if (dark) AppPalette.DARK else AppPalette.LIGHT) {
                            CompositionLocalProvider(LocalReaderExperience provides session) {
                                Column(Modifier.fillMaxSize().background(FolioTheme.colors.background).verticalScroll(rememberScrollState()).padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    ReaderSelectionBar("A passage", {}, {}, {}, {}, {})
                                    ReaderSoundscapeSettings(ReaderSettings(soundscape = prefs), {})
                                    ReaderTimeSettings()
                                }
                            }
                        }
                    }
                    try {
                        repeat(3) { scene.render(it * 250_000_000L).close() }
                        scene.render(1_000_000_000L).use { image ->
                            File(directory,"controls-${if (dark) "dark" else "light"}-$width.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        }
                    } finally { scene.close(); controller.close() }
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
