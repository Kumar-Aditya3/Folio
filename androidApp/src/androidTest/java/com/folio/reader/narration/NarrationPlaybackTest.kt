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
    @Test fun directServiceEntryPointIsPrivateToFolio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getServiceInfo(android.content.ComponentName(context,NarrationPlaybackService::class.java),0)
        assertFalse("Other apps must not start private-book speech through raw service intents",info.exported)
    }

    @Test fun nativeSpeechSurvivesScreenOffAndPersistsOnlyPresentedProgress() = runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val graph=(context.applicationContext as FolioApplication).graph
        val controller=graph.narration
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
        val report=File(context.filesDir,"narration-test-report.txt").apply { writeText("real_native_model=true\n") }
        try {
            assertNotEquals(VoicePackPhase.Unsupported,controller.pack.value.phase)
            withContext(Dispatchers.Main) { controller.download(false) }
            withTimeout(240_000) { controller.pack.first { it.phase in setOf(VoicePackPhase.Installed,VoicePackPhase.Failed) } }
            assertEquals(controller.pack.value.error,VoicePackPhase.Installed,controller.pack.value.phase)
            val started=android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { controller.start(ReadingPosition(id,graph.deviceId,chapter.id,0,"")) }
            withTimeout(180_000) { controller.state.first { it.phase in setOf(NarrationPhase.Playing,NarrationPhase.Error) } }
            assertEquals(controller.state.value.error,NarrationPhase.Playing,controller.state.value.phase)
            report.appendText("first_presented_ms=${android.os.SystemClock.elapsedRealtime()-started}\n")
            val before=NarrationActivity.totalPlaybackMs
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            delay(12_000)
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
            val idleRelease=launch(Dispatchers.Main,CoroutineStart.UNDISPATCHED) { controller.service!!.coordinator.halt(false) }
            withContext(Dispatchers.Main) { controller.play() }
            idleRelease.join()
            withTimeout(45_000) { controller.state.first { it.phase in setOf(NarrationPhase.Playing,NarrationPhase.Error) } }
            assertEquals(controller.state.value.error,NarrationPhase.Playing,controller.state.value.phase)
            withContext(Dispatchers.Main) { controller.pause() }
            withTimeout(20_000) { controller.state.first { it.phase==NarrationPhase.Paused } }
            val sessions=graph.sessionRepository.getSessionsForBook(id).first()
            assertTrue(sessions.sumOf { it.activity?.listeningMs ?: 0 }>3000)
            assertNotNull(NarrationPersistence(graph.database).load(id))
            // Exercise the actual system notification dismissal PendingIntent, not a direct stop call.
            val notification=context.getSystemService(NotificationManager::class.java).activeNotifications
                .first { it.id==715 }.notification
            assertNotNull("A notification swipe must stop narration",notification.deleteIntent)
            notification.deleteIntent.send()
            withTimeout(20_000) { controller.state.first { !it.hasBook } }
            withTimeout(20_000) { while(controller.service!=null) delay(100) }
            assertNotNull("Dismissal preserves the listening checkpoint",NarrationPersistence(graph.database).load(id))
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==715 })
            report.appendText("screen_off_notification_focus_pause_persistence=passed\n")
        } finally {
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            withContext(Dispatchers.Main) { controller.stop() }
            withTimeout(20_000) { controller.state.first { !it.hasBook } }
        }
    }
}
