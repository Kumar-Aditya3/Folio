package com.folio.reader.narration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.reader.FolioApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated emulator only. Downloads are explicit test actions, never app startup actions. */
@RunWith(AndroidJUnit4::class)
class NarrationDeliveryPlaybackTest {
    @Test fun chargingPreparationWaitsAndNeverCreditsUnplayedAudio() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val graph = (context.applicationContext as FolioApplication).graph
        val controller = graph.narration
        val original = controller.options.value
        val id = "charging-preparation-fixture"
        val chapter = com.folio.reader.model.Chapter("$id-chapter",id,"Preparation","chapter.xhtml",0,wordCount = 10)
        val epub = java.io.File(graph.platform.fileSystem.getBookEpubPath(id)).apply { parentFile!!.mkdirs() }
        java.util.zip.ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("chapter.xhtml"))
            zip.write("<html><body><p>The morning was bright, and the garden was quiet.</p></body></html>".toByteArray()); zip.closeEntry()
        }
        graph.bookRepository.insertBook(com.folio.reader.model.Book(id=id,title="Preparation fixture",epubHash=id,epubFileSize=epub.length()),false)
        graph.bookRepository.insertChapters(id,listOf(chapter))
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            withContext(Dispatchers.Main) {
                controller.stop(); controller.selectModel(NarrationModel.Phonon31M); controller.selectPhononVoice("default")
                controller.setOptions(original.copy(audioCache = true,prepareWhileCharging = true))
            }
            withTimeout(30_000) {
                controller.configuration.first { it.model == NarrationModel.Phonon31M && it.phononVoiceId == "default" && !it.changing }
                controller.options.first { it.prepareWhileCharging }
            }
            withContext(Dispatchers.Main) { controller.download(false) }
            withTimeout(600_000) { controller.pack.first { it.phase in setOf(VoicePackPhase.Installed,VoicePackPhase.Failed) } }
            assertEquals(controller.pack.value.error,VoicePackPhase.Installed,controller.pack.value.phase)
            withContext(Dispatchers.Main) { controller.setPronunciations(id,listOf(PronunciationRule("garden","orchard"))) }
            withTimeout(15_000) { withContext(Dispatchers.Main) { controller.pronunciations(id) }.first { it.singleOrNull()?.spoken == "orchard" } }
            shell("dumpsys battery set status 3")
            shell("dumpsys battery unplug")
            delay(1000)
            val before = NarrationActivity.totalPlaybackMs
            val position = controller.state.value.position
            withContext(Dispatchers.Main) { controller.prepareChapter(com.folio.reader.model.ReadingPosition(id,graph.deviceId,chapter.id,0,"")) }
            withTimeout(15_000) { controller.storage.first { it.preparing } }
            delay(1500)
            val work = androidx.work.WorkManager.getInstance(context)
            val waiting = work.getWorkInfosForUniqueWork(NarrationExtras.WORK).get().first()
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED,waiting.state)
            assertEquals(before,NarrationActivity.totalPlaybackMs)
            shell("dumpsys battery reset")
            val complete = withTimeout(180_000) {
                while (true) {
                    val info = withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(NarrationExtras.WORK).get().first() }
                    if (info.state.isFinished) return@withTimeout info
                    delay(500)
                }
                error("unreachable")
            }
            assertEquals(complete.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,complete.state)
            assertEquals(before,NarrationActivity.totalPlaybackMs)
            assertEquals(position,controller.state.value.position)
            val config = controller.configuration.value
            val key = controller.extras.cache.key(config,"The morning was bright, and the orchard was quiet.")
            assertEquals(true,withContext(Dispatchers.IO) { controller.extras.cache.replay(id,key) { true } })
        } finally {
            shell("dumpsys battery reset")
            withContext(Dispatchers.Main) { controller.cancelPreparation(); controller.setOptions(original); controller.forgetBook(id) }
            graph.bookRepository.deleteBook(id,false); graph.platform.fileSystem.deleteBookFiles(id)
        }
    }
    @Test fun optionalNarratorSynthesizesAndCachedReplayNeedsNoModel() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as FolioApplication).graph.narration
        val book = "delivery-cache-fixture"
        val original = controller.options.value
        try {
            withContext(Dispatchers.Main) {
                controller.stop()
                controller.selectModel(NarrationModel.Phonon31M)
                controller.selectPhononVoice("default")
            }
            withTimeout(30_000) { controller.configuration.first { it.model == NarrationModel.Phonon31M && !it.changing && it.phononVoiceId == "default" } }
            withContext(Dispatchers.Main) { controller.download(false) }
            withTimeout(600_000) { controller.pack.first { it.phase in setOf(VoicePackPhase.Installed,VoicePackPhase.Failed) } }
            assertEquals(controller.pack.value.error,VoicePackPhase.Installed,controller.pack.value.phase)
            val voice = PhononVoices.all.first()
            withContext(Dispatchers.Main) { controller.downloadNarrator(voice.id,false) }
            withTimeout(120_000) { controller.narratorPacks.first { it[voice.id]?.phase in setOf(VoicePackPhase.Installed,VoicePackPhase.Failed) } }
            assertEquals(controller.narratorPacks.value[voice.id]?.error,VoicePackPhase.Installed,controller.narratorPacks.value[voice.id]?.phase)
            withContext(Dispatchers.Main) {
                controller.selectPhononVoice(voice.id)
                controller.setOptions(original.copy(audioCache = true,prepareWhileCharging = false))
            }
            withTimeout(30_000) { controller.configuration.first { it.phononVoiceId == voice.id && !it.changing && it.narratorReady }; controller.options.first { it.audioCache } }
            val spoken = "The rain stopped, and the garden was quiet."
            val config = controller.configuration.value
            var generated = 0L
            withContext(Dispatchers.IO) { controller.extras.inference.withLock {
                controller.extras.Session(config,controller.installer.directory).use { session ->
                    assertTrue(session.render(book,spoken) { generated += it.size; true })
                }
                assertTrue(generated > 24_000)
                // A missing model directory proves the replay path never initializes an engine.
                controller.extras.Session(config,java.io.File(context.cacheDir,"missing-model-for-cache-test")).use { session ->
                    var replayed = 0L
                    assertTrue(session.render(book,spoken) { replayed += it.size; true })
                    assertEquals(generated,replayed)
                }
            } }
            val before = NarrationActivity.totalPlaybackMs
            val position = controller.state.value.position
            context.startActivity(Intent(context,androidx.activity.ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withContext(Dispatchers.Main) { controller.previewVoice() }
            withTimeout(15_000) { controller.storage.first { it.previewing } }
            delay(2000)
            withContext(Dispatchers.Main) { controller.stopPreview() }
            withTimeout(30_000) { controller.storage.first { !it.previewing } }
            assertEquals(before,NarrationActivity.totalPlaybackMs)
            assertEquals(position,controller.state.value.position)
        } finally {
            withContext(Dispatchers.Main) {
                controller.stopPreview()
                controller.selectPhononVoice("default")
                controller.setOptions(original)
                controller.forgetBook(book)
            }
        }
    }
}
