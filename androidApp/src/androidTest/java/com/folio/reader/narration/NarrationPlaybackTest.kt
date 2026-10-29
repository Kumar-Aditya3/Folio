package com.folio.reader.narration

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.reader.FolioApplication
import com.folio.reader.MainActivity
import com.folio.reader.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Run only in the isolated emulator. The explicit download below is the opt-in action. */
@RunWith(AndroidJUnit4::class)
class NarrationPlaybackTest {
    @Test fun invalidKokoroPackFailsWithoutCallingNativeGettersOnNullHandle() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"invalid-kokoro-fixture").apply { mkdirs() }
        try {
            val result=runCatching { KokoroNative(directory,3).use {} }
            assertTrue("An invalid pack must report a recoverable error",result.isFailure)
        } finally { directory.deleteRecursively() }
    }
    @Test fun directServiceEntryPointIsPrivateToFolio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getServiceInfo(android.content.ComponentName(context,NarrationPlaybackService::class.java),0)
        assertFalse("Other apps must not start private-book speech through raw service intents",info.exported)
    }

    @Test fun nativeSpeechSurvivesScreenOffAndPersistsOnlyPresentedProgress() = exercise(NarrationModel.Phonon31M)
    @Test fun kokoroSurvivesScreenOffModelSwitchAndNotificationDismissal() = exercise(NarrationModel.Kokoro82M)

    private fun exercise(model: NarrationModel) = runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val graph=(context.applicationContext as FolioApplication).graph
        val controller=graph.narration
        withContext(Dispatchers.Main) {
            controller.selectModel(model)
            if (model == NarrationModel.Kokoro82M) controller.selectVoice(3)
        }
        withTimeout(30_000) { controller.configuration.first {
            it.model == model && !it.changing && (model != NarrationModel.Kokoro82M || it.voiceId == 3)
        } }
        val id="narration-instrumented-fixture"
        val chapter=Chapter("$id-chapter",id,"A walk in the garden","chapter.xhtml",0,wordCount=500)
        val epub=File(graph.platform.fileSystem.getBookEpubPath(id)).apply { parentFile!!.mkdirs() }
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("chapter.xhtml"))
            zip.write(("<html><body><h1>A walk in the garden</h1>"+(1..35).joinToString("") { "<p>The morning was bright and clear. We followed the quiet path through the garden, listening to the birds.</p>" }+"</body></html>").toByteArray())
            zip.closeEntry()
        }
        graph.bookRepository.insertBook(Book(id=id,title="Narration test",epubHash=id,epubFileSize=epub.length()),false)
        graph.bookRepository.insertChapters(id,listOf(chapter))
        context.startActivity(Intent(context,androidx.activity.ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1500)
        val report=File(context.filesDir,"narration-${model.name}-test-report.txt").apply { writeText("real_native_model=true\n") }
        try {
            assertNotEquals(VoicePackPhase.Unsupported,controller.pack.value.phase)
            withContext(Dispatchers.Main) { controller.download(false) }
            withTimeout(600_000) { controller.pack.first { it.phase in setOf(VoicePackPhase.Installed,VoicePackPhase.Failed) } }
            assertEquals(controller.pack.value.error,VoicePackPhase.Installed,controller.pack.value.phase)
            val started=android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { controller.start(ReadingPosition(id,graph.deviceId,chapter.id,0,"")) }
            withTimeout(180_000) { controller.state.first { it.phase in setOf(NarrationPhase.Playing,NarrationPhase.Error) } }
            assertEquals(controller.state.value.error,NarrationPhase.Playing,controller.state.value.phase)
            report.appendText("first_presented_ms=${android.os.SystemClock.elapsedRealtime()-started}\n")
            val before=NarrationActivity.totalPlaybackMs
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            // CPU emulation can synthesize Kokoro slower than real time. Verify that
            // new passages continue with the display off, without calling this a speed benchmark.
            val screenOffWindow = if (model == NarrationModel.Kokoro82M) 45_000L else 12_000L
            delay(screenOffWindow)
            report.appendText("screen_off_window_ms=$screenOffWindow\n")
            report.appendText("screen_off_played_ms=${NarrationActivity.totalPlaybackMs-before}\n")
            assertTrue("Screen-off playback must advance",NarrationActivity.totalPlaybackMs>before+3000)
            val notifications=context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue("Media notification must be present",notifications.any { it.notification.extras.containsKey("android.mediaSession") })
            val manager=context.getSystemService(android.media.AudioManager::class.java)
            val focus=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener({},android.os.Handler(android.os.Looper.getMainLooper())).build()
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,manager.requestAudioFocus(focus))
            withTimeout(15_000) { controller.state.first { it.phase==NarrationPhase.Interrupted } }
            val interrupted=NarrationActivity.totalPlaybackMs
            delay(1200)
            assertEquals(interrupted,NarrationActivity.totalPlaybackMs)
            manager.abandonAudioFocusRequest(focus)
            withTimeout(30_000) { controller.state.first { it.phase==NarrationPhase.Playing } }
            manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            withTimeout(20_000) { controller.state.first { it.phase==NarrationPhase.Paused } }
            val paused=NarrationActivity.totalPlaybackMs
            delay(1500)
            assertEquals(paused,NarrationActivity.totalPlaybackMs)
            val original=controller.state.value.position!!.characterOffset
            withContext(Dispatchers.Main) { controller.skip(true) }
            withTimeout(30_000) { controller.state.first { it.phase==NarrationPhase.Paused && (it.position?.characterOffset ?: 0)>original } }
            val next=controller.state.value.position!!.characterOffset
            withContext(Dispatchers.Main) { controller.skip(false) }
            withTimeout(30_000) { controller.state.first { it.phase==NarrationPhase.Paused && (it.position?.characterOffset ?: next)<next } }
            assertEquals("Paused seeks must earn no time",paused,NarrationActivity.totalPlaybackMs)
            // Reproduce a user pressing Play while idle cleanup still owns the output.
            val idleRelease=launch(Dispatchers.Main,CoroutineStart.UNDISPATCHED) { controller.service!!.coordinator.releasePausedResources() }
            withContext(Dispatchers.Main) { controller.play() }
            idleRelease.join()
            withTimeout(45_000) { controller.state.first { it.phase in setOf(NarrationPhase.Playing,NarrationPhase.Error) } }
            assertEquals(controller.state.value.error,NarrationPhase.Playing,controller.state.value.phase)
            withContext(Dispatchers.Main) { controller.pause() }
            withTimeout(20_000) { controller.state.first { it.phase==NarrationPhase.Paused } }
            val sessions=graph.sessionRepository.getSessionsForBook(id).first()
            assertTrue(sessions.sumOf { it.activity?.listeningMs ?: 0 }>3000)
            assertNotNull(NarrationPersistence(graph.database).load(id))
            if (model == NarrationModel.Kokoro82M) {
                val heard = controller.state.value.position!!.characterOffset
                withContext(Dispatchers.Main) { controller.selectModel(NarrationModel.Phonon40M) }
                withTimeout(30_000) { controller.configuration.first { it.model == NarrationModel.Phonon40M && !it.changing } }
                assertEquals(NarrationPhase.Paused,controller.state.value.phase)
                assertEquals(heard,controller.state.value.position!!.characterOffset)
                assertEquals(VoicePackPhase.NotInstalled,controller.pack.value.phase)
                assertNull(NarrationActivity.ownerBookId)
                // Exercise Java ORT after Sherpa has loaded its C API in this process.
                // This catches native version/ABI conflicts that compile-time checks miss.
                val embedding = com.folio.reader.ml.EmbeddingModelCatalog.MINILM_L6_V2_INT8
                val downloader = com.folio.reader.ml.ModelDownloader(graph.platform.fileSystem,graph.platform.hasher)
                val file = downloader.ensureModel(embedding).getOrThrow()
                withContext(Dispatchers.IO) {
                    com.folio.reader.ml.openOnnxModel(file.absolutePath,2,useXnnpack = false).use { engine ->
                        val result = engine.run(longArrayOf(101,1996,2800,102),longArrayOf(1,1,1,1),
                            longArrayOf(0,0,0,0),1,4)
                        assertEquals(384,result.hiddenSize)
                        assertTrue(result.values.isNotEmpty() && result.values.all { it.isFinite() })
                    }
                }
                report.appendText("shared_onnx_embedding_inference=passed\n")
                withContext(Dispatchers.Main) { controller.selectModel(model); controller.selectVoice(21) }
                withTimeout(30_000) { controller.configuration.first { it.model == model && it.voiceId == 21 && !it.changing } }
                assertEquals(VoicePackPhase.Installed,controller.pack.value.phase)
                withContext(Dispatchers.Main) { controller.play() }
                withTimeout(90_000) { controller.state.first { it.phase in setOf(NarrationPhase.Playing,NarrationPhase.Error) } }
                assertEquals(controller.state.value.error,NarrationPhase.Playing,controller.state.value.phase)
                delay(1200)
                withContext(Dispatchers.Main) { controller.pause() }
                withTimeout(20_000) { controller.state.first { it.phase == NarrationPhase.Paused } }
                val restored = withContext(Dispatchers.Main) { AndroidNarrationController(context,graph) }
                try {
                    withTimeout(20_000) { restored.state.first { it.bookId == id } }
                    assertEquals(model,restored.configuration.value.model)
                    assertEquals(21,restored.configuration.value.voiceId)
                    assertEquals(NarrationPhase.Paused,restored.state.value.phase)
                    assertEquals(id,restored.state.value.position!!.bookId)
                } finally { restored.scope.cancel() }
                report.appendText("model_switch_and_british_voice=passed\n")
            }
            // Exercise the actual system notification dismissal PendingIntent, not a direct stop call.
            val notification=context.getSystemService(NotificationManager::class.java).activeNotifications
                .first { it.id==715 }.notification
            assertNotNull("A notification swipe must stop narration",notification.deleteIntent)
            notification.deleteIntent.send()
            withTimeout(20_000) { controller.state.first { !it.hasBook } }
            withTimeout(20_000) { while(controller.service!=null) delay(100) }
            assertNotNull("Dismissal preserves the listening checkpoint",NarrationPersistence(graph.database).load(id))
            val afterDismiss = withContext(Dispatchers.Main) { AndroidNarrationController(context,graph) }
            try {
                withContext(Dispatchers.Main) { kotlinx.coroutines.yield() }
                delay(500)
                assertFalse("A dismissed player must stay hidden on cold start", afterDismiss.state.value.hasBook)
                assertTrue(afterDismiss.preferences.getBoolean("playerDismissed", false))
            } finally { afterDismiss.scope.cancel() }
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==715 })
            report.appendText("screen_off_notification_focus_pause_persistence=passed\n")
        } finally {
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            withContext(Dispatchers.Main) { controller.stop() }
            withTimeout(20_000) { controller.state.first { !it.hasBook } }
        }
    }
}
