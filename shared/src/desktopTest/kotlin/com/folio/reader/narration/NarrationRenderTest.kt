package com.folio.reader.narration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.folio.reader.model.ReadingPosition
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

class NarrationRenderTest {
    private class Preview(phase: VoicePackPhase, playing: Boolean, model: NarrationModel) : NarrationController {
        override val configuration = MutableStateFlow(NarrationConfiguration(model))
        override val options = MutableStateFlow(NarrationOptions(audioCache = true,prepareWhileCharging = true))
        override val storage = MutableStateFlow(NarrationStorageState(bytes = 12_000_000))
        override val pack = MutableStateFlow(VoicePackState(phase,32_000_000,model.downloadBytes))
        override val state = MutableStateFlow(if (playing) NarrationState(phase=NarrationPhase.Playing,
            bookId="fixture", title="The Secret Garden",chapterTitle="Chapter Four",passage="The path led through the garden, where the trees were beginning to turn gold.",
            position=ReadingPosition("fixture","device","chapter",0,""),followText=true) else NarrationState())
        override fun download(wifiOnly:Boolean) = Unit
        override fun cancelDownload() = Unit
        override fun removePack() = Unit
        override fun start(position:ReadingPosition) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun skip(forward:Boolean) = Unit
        override fun setSpeed(speed:Float) = Unit
        override fun setFollowText(follow:Boolean) = Unit
        override fun setSleepTimer(minutes:Int?,endOfChapter:Boolean) = Unit
        override fun visualActivity(bookId:String) = Unit
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun narrationFitsExistingThemeAtNarrowAndLargeTextSizes() {
        var failure:Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                val output=File("build/visual-review/narration").apply { mkdirs() }
                for (dark in listOf(false,true)) for (width in listOf(320,393))
                    for (model in NarrationModel.entries)
                    for (phase in listOf(VoicePackPhase.NotInstalled,VoicePackPhase.Downloading,VoicePackPhase.Installed)) {
                        val controller=Preview(phase,phase==VoicePackPhase.Installed,model)
                        val scene=ImageComposeScene(width,if(width==320) 4200 else 2200,density=Density(1f,if(width==320) 2f else 1f)) {
                            FolioTheme.AppTheme(palette=if(dark) AppPalette.DARK else AppPalette.LIGHT) {
                                Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                                    NarrationPanel(controller,ReadingPosition("fixture","device","chapter",0,""))
                                }
                            }
                        }
                        try {
                            repeat(3) { scene.render(it*250_000_000L).close() }
                            scene.render(1_000_000_000L).use {
                                File(output,"${model.name}-${if(dark) "dark" else "light"}-$width-$phase.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                            }
                            if (phase == VoicePackPhase.Installed) {
                                scene.sendPointerEvent(PointerEventType.Scroll,Offset(width/2f,500f),scrollDelta = Offset(0f,10_000f))
                                repeat(10) { scene.render(1_100_000_000L+it*100_000_000L).close() }
                                scene.render(2_200_000_000L).use {
                                    File(output,"${model.name}-${if(dark) "dark" else "light"}-$width-settings.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                                }
                            }
                        } finally { scene.close() }
                    }
            } catch(error:Throwable) { failure=error }
        }
        failure?.let { throw it }
    }
}
