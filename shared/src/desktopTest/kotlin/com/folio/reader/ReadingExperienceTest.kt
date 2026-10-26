package com.folio.reader

import com.folio.reader.database.*
import com.folio.reader.experience.*
import com.folio.reader.model.*
import com.folio.reader.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ReadingExperienceTest {
    private val source = ExportSource("h", "b", "A book", "An author", "A thought worth keeping.", "Chapter 1", "c", 0, "/0/1:0")
    @Test fun oldSettingsDecodeWithSilentDefaults() {
        val settings = Json.decodeFromString<ReaderSettings>("{\"fontSize\":19.0}")
        assertEquals(Soundscape.OFF, settings.soundscape.scene)
        assertEquals(ReadingExperienceData(), settings.readingExperience)
        assertNull(Json.decodeFromString<BookReaderSettings>("{}").soundscape)
    }
    @Test fun soundscapeUsesExistingBookOverrideSemantics() {
        val global = ReaderSettings()
        val rain = global.copy(soundscape = SoundscapePreferences(Soundscape.RAIN, timerMinutes = 30))
        val book = rain.toBookSettings()
        assertEquals(setOf("soundscape"), book.overriddenFields(global))
        assertEquals(rain.soundscape, book.toReaderSettings(global).soundscape)
        assertEquals(global.soundscape, book.clearing(setOf("soundscape")).toReaderSettings(global).soundscape)
        assertEquals(setOf("soundscape"), rain.changedFields(global))
    }
    @Test fun normalizingCannotMixTheSameSceneOrExceedLimits() {
        val p = SoundscapePreferences(Soundscape.RAIN, 5f, Soundscape.RAIN, -2f, 999).normalized()
        assertEquals(Soundscape.OFF, p.secondary); assertEquals(1f, p.primaryLevel)
        assertEquals(0f, p.secondaryLevel); assertEquals(180, p.timerMinutes)
    }
    @Test fun reviewsFollowIntervalsAndRemoveOnlyTheSchedule() = runBlocking {
        withStore { _, store ->
            store.schedule(source, 0)
            assertEquals(ReviewSchedule.DAY_MS, store.state.value.reviews.single().dueAt)
            store.review("h", "remember", 100)
            assertEquals(100 + 3 * ReviewSchedule.DAY_MS, store.state.value.reviews.single().dueAt)
            store.review("h", "again", 200)
            assertEquals(200 + ReviewSchedule.DAY_MS, store.state.value.reviews.single().dueAt)
            store.review("h", "remove", 300)
            assertTrue(store.state.value.reviews.isEmpty())
        }
    }
    @Test fun companionWritesPreserveConcurrentTypographyAndDeviceVolume() = runBlocking {
        withStore { settings, store ->
            coroutineScope {
                launch { repeat(10) { n -> settings.mergeGlobalSettings { it.copy(fontSize = 15f + n) } } }
                launch { store.schedule(source, 0); store.saveVolume(.17f) }
            }
            val saved = settings.getGlobalSettings()
            assertEquals(24f, saved.fontSize); assertEquals(1, saved.readingExperience.reviews.size)
            assertEquals(.17f, store.volume())
            assertFalse(Json.encodeToString(ReaderSettings.serializer(), saved).contains("reading_sound_volume"))
        }
    }
    @Test fun storedSchedulesSurviveRepositoryRecreation() = runBlocking {
        withStore { settings, store ->
            store.schedule(source, 123)
            val reopened = ReadingExperienceRepository(settings); reopened.refresh()
            assertEquals(store.state.value, reopened.state.value)
        }
    }
    @Test fun deletingAnAnnotationPrunesItsSchedule() = runBlocking {
        val root = Files.createTempDirectory("folio-reading-delete").toFile()
        val db = Database(java.io.File(root,"folio.db").absolutePath)
        try {
            val settings = JdbcSettingsRepository(db); val store = ReadingExperienceRepository(settings)
            val book = Book("b", "Book", epubHash = "hash", epubFileSize = 0)
            JdbcBookRepository(db).insertBook(book)
            val highlight = Highlight("h", "b", "c", 0, "/0/1:0", "/0/1:end", "A thought", deviceId = "test")
            val highlights = JdbcHighlightRepository(db); highlights.insertHighlight(highlight)
            store.schedule(source, 0); highlights.deleteHighlight("h"); store.refresh()
            assertTrue(store.state.value.reviews.isEmpty())
        } finally { db.close(); root.deleteRecursively() }
    }
    @Test fun mergePreservesLocalPresetsAndNewestReturnContext() {
        val position = ReadingPosition("b", "test", "c", 0, "/0/0:0")
        val local = ReadingExperienceData(presets = listOf(ReadingPreset("local", "Mine", BookReaderSettings())),
            returns = listOf(ReadingReturnContext("b", position, "One", lastLeftAt = 200)))
        val incoming = ReadingExperienceData(presets = listOf(ReadingPreset("other", "Other", BookReaderSettings())),
            returns = listOf(ReadingReturnContext("b", position, "Old", lastLeftAt = 100)))
        val merged = mergeReadingExperience(local, incoming)
        assertEquals(2, merged.presets.size); assertEquals("One", merged.returns.single().chapter)
    }
    @Test fun textPaginationPreservesWhitespaceUnicodeAndLongWords() {
        for (text in listOf("One.\n\nTwo.  Three.", "雨の読書。静かな時間。", "हमें यह पंक्ति याद है।", "مرحبا بالعالم", "word".repeat(100), "🙂 👩‍👩‍👧‍👧 end")) {
            val pages = paginateExportText(text) { it.length <= 12 }
            assertEquals(text, pages.joinToString("")); assertTrue(pages.all { it.isNotEmpty() })
            assertTrue(pages.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        }
    }
    @Test fun sequenceOrderUsesNumericPassageLocations() {
        assertTrue(exportPassageOrder("/0/2:0") < exportPassageOrder("/0/10:0"))
        assertTrue(exportPassageOrder("/2/12:0") < exportPassageOrder("/10/1:0"))
    }
    @Test fun journalEscapesContentAndRespectsSelections() {
        val malicious = source.copy(text = "<script>alert('x')</script>\n& more", note = "My <note>")
        val html = readingJournalHtml("<Title>", "Author &", listOf(malicious), JournalOptions(letter = true, associatedNotes = false))
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("size:Letter")); assertFalse(html.contains("My &lt;note&gt;"))
        assertTrue(readingJournalHtml("Book", "", listOf(source), JournalOptions(chapters = emptySet())).contains("No saved writing"))
        val reflection = source.copy(text = "My chapter reflection", isPersonalNote = true, isReflection = true)
        assertTrue(readingJournalHtml("Book", "", listOf(reflection), JournalOptions(standaloneNotes = false)).contains("My chapter reflection"))
        assertFalse(readingJournalHtml("Book", "", listOf(reflection), JournalOptions(reflections = false)).contains("My chapter reflection"))
    }
    @Test fun readingTimeUsesCrediblePersonalHistoryAndFallback() {
        val pos = ReadingPosition("b", "test", "c", 0, "")
        val chapter = Chapter("c", "b", "Chapter", "c.xhtml", 0, wordCount = 2000)
        val session = ReadingSession("s", "b", null, "test", Clock.System.now(), durationMs = 600_000, startPosition = pos, wordsRead = 2000)
        assertEquals(200, estimateReadingTime(listOf(session), "b", listOf(chapter), 0, .5, 10).wordsPerMinute)
        assertTrue(estimateReadingTime(listOf(session), "b", listOf(chapter), 0, .5, 10).personal)
        assertEquals(220, estimateReadingTime(emptyList(), "b", listOf(chapter), 0, .5, 10).wordsPerMinute)
    }
    @Test fun restoredAudioNeverAutoplaysAndCannotPlayInBackground() {
        val writes = AtomicInteger()
        val controller = SoundscapeController({ ShortArray(882) { 3000 } }, { object : PcmOutput {
            override fun start() = Unit
            override fun write(samples: ShortArray) { writes.incrementAndGet(); Thread.sleep(20) }
            override fun pause() = Unit
            override fun close() = Unit
        } })
        try {
            controller.restore(SoundscapePreferences(Soundscape.RAIN, timerMinutes = 1))
            assertFalse(controller.state.value.playing)
            controller.play(); assertFalse(controller.state.value.playing)
            controller.setEligible(true); controller.play()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (writes.get() < 5 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(writes.get() >= 5)
            controller.setEligible(false); assertFalse(controller.state.value.playing)
            val remaining = controller.state.value.remainingMs
            controller.setEligible(true); assertFalse(controller.state.value.playing)
            assertEquals(remaining, controller.state.value.remainingMs)
            controller.releaseOutput()
            assertEquals(remaining, controller.state.value.remainingMs, "Navigating away retains the in-process timer")
            assertEquals(Soundscape.RAIN, controller.state.value.preferences.scene)
            controller.off(); assertNull(controller.state.value.remainingMs)
        } finally { controller.close() }
    }
    @Test fun allBundledWavAssetsHaveExpectedFormatAndBoundedPeaks() {
        var total = 0
        for (scene in Soundscape.entries.drop(1)) {
            val bytes = javaClass.classLoader.getResourceAsStream("soundscapes/${scene.name.lowercase()}.wav")!!.use { it.readBytes() }
            total += bytes.size
            val samples = readSoundscapeWav(bytes)
            assertEquals(45 * 44100, samples.size)
            assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } < 30000)
            assertTrue(kotlin.math.abs(samples.last().toInt() - samples.first().toInt()) < 8000)
        }
        assertTrue(total < 15 * 1024 * 1024)
    }
    @Test fun audioChangesNeverRestyleTheReadingPage() {
        val settings = ReaderSettings()
        assertEquals(settings.forReaderContent(), settings.copy(soundscape = SoundscapePreferences(Soundscape.RAIN,
            primaryLevel = .6f, secondary = Soundscape.CAFE, secondaryLevel = .2f, timerMinutes = 30)).forReaderContent())
        assertNotEquals(settings.forReaderContent(), settings.copy(fontSize = settings.fontSize + 1).forReaderContent())
    }

    @Test fun controlsDoNotWaitForAssetLoading() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val complete = CountDownLatch(1)
        val opened = AtomicInteger()
        val controller = SoundscapeController({ entered.countDown(); unblock.await(3, TimeUnit.SECONDS); ShortArray(882) },
            { opened.incrementAndGet(); error("Cancelled loading must never open output") })
        try {
            controller.setEligible(true); controller.select(SoundscapePreferences(Soundscape.RAIN))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread {
                controller.setVolume(.7f); controller.restore(SoundscapePreferences(Soundscape.CAFE, primaryLevel = .3f))
                controller.pause(); controller.off(); controller.close(); complete.countDown()
            }.start()
            assertTrue(complete.await(300, TimeUnit.MILLISECONDS), "UI commands waited behind a blocked asset read")
            assertFalse(controller.state.value.playing); assertEquals(.7f, controller.state.value.volume)
            assertNull(controller.state.value.remainingMs)
        } finally { unblock.countDown(); controller.close() }
        assertEquals(0, opened.get())
    }

    @Test fun controlsDoNotWaitForDeviceWritesAndResourcesCloseOnWorker() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val complete = CountDownLatch(1); val closed = CountDownLatch(1)
        var closeThread = ""
        val controller = SoundscapeController({ ShortArray(882) { 1000 } }, { object : PcmOutput {
            override fun start() = Unit
            override fun write(samples: ShortArray) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS) }
            override fun pause() = Unit
            override fun close() { closeThread = Thread.currentThread().name; closed.countDown() }
        } })
        try {
            controller.setEligible(true); controller.select(SoundscapePreferences(Soundscape.RAIN))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread {
                repeat(100) { controller.setVolume(it / 100f) }
                controller.pause(); controller.off(); controller.close(); complete.countDown()
            }.start()
            assertTrue(complete.await(300, TimeUnit.MILLISECONDS), "UI commands waited behind a blocked device write")
            assertFalse(controller.state.value.playing)
        } finally { unblock.countDown(); controller.close() }
        assertTrue(closed.await(2, TimeUnit.SECONDS)); assertEquals("Folio soundscape", closeThread)
    }

    @Test fun mixerStartsOutputOnceAndReusesItsPcmBuffer() {
        val starts = AtomicInteger(); val writes = AtomicInteger()
        val buffers = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val controller = SoundscapeController({ ShortArray(882) { 1000 } }, { object : PcmOutput {
            override fun start() { starts.incrementAndGet() }
            override fun write(samples: ShortArray) { buffers += System.identityHashCode(samples); writes.incrementAndGet(); Thread.sleep(2) }
            override fun pause() = Unit
            override fun close() = Unit
        } })
        try {
            controller.setEligible(true); controller.select(SoundscapePreferences(Soundscape.RAIN))
            val deadline = System.nanoTime() + 2_000_000_000L
            while (writes.get() < 10 && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(writes.get() >= 10); assertEquals(1, starts.get()); assertEquals(1, buffers.size)
        } finally { controller.close() }
    }

    @Test fun aLateAssetFailureDoesNotCancelANewerSceneSelection() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val wrote = CountDownLatch(1)
        val controller = SoundscapeController({ scene ->
            if (scene == Soundscape.RAIN) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS); error("Old asset failed") }
            ShortArray(882) { 1000 }
        }, { object : PcmOutput {
            override fun start() = Unit
            override fun write(samples: ShortArray) { wrote.countDown(); Thread.sleep(2) }
            override fun pause() = Unit
            override fun close() = Unit
        } })
        try {
            controller.setEligible(true); controller.select(SoundscapePreferences(Soundscape.RAIN))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.select(SoundscapePreferences(Soundscape.FIREPLACE)); unblock.countDown()
            assertTrue(wrote.await(2, TimeUnit.SECONDS)); assertTrue(controller.state.value.playing)
            assertEquals(Soundscape.FIREPLACE, controller.state.value.preferences.scene); assertNull(controller.state.value.error)
        } finally { unblock.countDown(); controller.close() }
    }
    @Test fun playbackTimerFadesAndStopsWithoutAUiClock() {
        val lastPeak = AtomicInteger(9999)
        val writes = AtomicInteger()
        val controller = SoundscapeController({ ShortArray(882) { 3000 } }, { object : PcmOutput {
            override fun start() = Unit
            override fun write(samples: ShortArray) { writes.incrementAndGet(); lastPeak.set(samples.maxOf { kotlin.math.abs(it.toInt()) }) }
            override fun pause() = Unit
            override fun close() = Unit
        } })
        try {
            controller.setEligible(true)
            controller.select(SoundscapePreferences(Soundscape.RAIN, timerMinutes = 1))
            val deadline = System.nanoTime() + 10_000_000_000L
            while (controller.state.value.remainingMs != 0L && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(0L, controller.state.value.remainingMs)
            assertFalse(controller.state.value.playing)
            assertTrue(writes.get() >= 3000)
            assertTrue(lastPeak.get() <= 3, "The final audio block must approach silence")
        } finally { controller.close() }
    }
    private suspend fun withStore(block: suspend (JdbcSettingsRepository, ReadingExperienceRepository) -> Unit) {
        val root = Files.createTempDirectory("folio-reading-experience").toFile()
        val db = Database(java.io.File(root,"folio.db").absolutePath)
        try { val settings = JdbcSettingsRepository(db); block(settings, ReadingExperienceRepository(settings)) }
        finally { db.close(); root.deleteRecursively() }
    }
}
