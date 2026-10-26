package com.folio.reader.narration

import com.folio.reader.database.*
import com.folio.reader.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

class NarrationCoreTest {
    @Test fun historicalListeningKeepsItsEarnedCivilDateAfterTravel() {
        val p=ReadingPosition("b","d","c",0,"")
        val session=ReadingSession("s","b",null,"d",kotlinx.datetime.Instant.parse("2026-10-26T23:00:00Z"),startPosition=p,
            activity=SessionActivity(timeZoneId="Asia/Kolkata"))
        assertEquals("2026-10-27",session.activityDateTime(kotlinx.datetime.TimeZone.of("America/Los_Angeles")).date.toString())
        assertEquals("2026-10-26",session.copy(activity=null).activityDateTime(kotlinx.datetime.TimeZone.of("America/Los_Angeles")).date.toString())
    }
    @Test fun listeningSplitsAtLocalMidnightWithoutLosingTime() {
        val slices=splitListeningInterval(kotlinx.datetime.Instant.parse("2026-10-26T18:30:00.500Z"),1000,kotlinx.datetime.TimeZone.of("Asia/Kolkata"))
        assertEquals(listOf(500L,500L),slices.map { it.durationMs })
        assertEquals(listOf("2026-10-26","2026-10-27"),slices.map { it.date.toString() })
        assertTrue(splitListeningInterval(Clock.System.now(),0,kotlinx.datetime.TimeZone.UTC).isEmpty())
    }
    @Test fun speechPreservesHeadingsShortFragmentsEntitiesAndUnicode() {
        val text = NarrationText.prepare("<h1>A beginning</h1><p>Yes.</p><p>A &amp; B met 👩🏽‍🚀. <em>They smiled.</em></p><nav>Do not read</nav><p hidden>Hidden</p>")
        assertEquals(listOf("A beginning","Yes.","A & B met 👩🏽‍🚀.","They smiled."),text.passages.map { it.text })
        assertTrue(text.passages.zipWithNext().all { (a,b) -> a.end <= b.start })
        assertTrue(text.passages.all { it.end <= text.length })
        assertTrue(text.html.contains("data-folio-narration"))
    }
    @Test fun nestedBlocksNeverRepeatOrReorderParentText() {
        val text = NarrationText.prepare("<div>First.<p>Second <b>and third</b>.</p>Fourth.</div>")
        assertEquals("First. Second and third. Fourth.",text.passages.joinToString(" ") { it.text })
        assertEquals(text.passages.map { it.blockId },NarrationText.prepare("<div>First.<p>Second <b>and third</b>.</p>Fourth.</div>").passages.map { it.blockId })
    }
    @Test fun longSentencesAreBoundedWithoutDroppedWords() {
        val words = (1..400).map { "word$it" }
        val text = NarrationText.prepare("<p>${words.joinToString(" ")}</p>")
        assertTrue(text.passages.all { it.text.length <= 360 })
        assertEquals(words,text.passages.flatMap { it.text.split(Regex("\\s+")) })
    }
    @Test fun duplicateSentencesRetainSeparateSourceRanges() {
        val text = NarrationText.prepare("<p>Hello again. Hello again.</p>")
        assertEquals(2,text.passages.size)
        assertEquals(text.passages[0].hash,text.passages[1].hash)
        assertTrue(text.passages[0].end <= text.passages[1].start)
    }
    @Test fun clockCountsElapsedPlaybackAtDoubleSpeed() {
        val clock = ListeningClock()
        clock.sample(0,0,false,2f)
        assertEquals(1000L,clock.sample(1000,48_000,true,2f))
        assertEquals(1000L,clock.sample(2000,96_000,true,2f))
    }
    @Test fun queuedAudioAndPausedTimeEarnNothing() {
        val clock = ListeningClock()
        clock.sample(0,0,false,1f)
        assertEquals(0L,clock.sample(1000,0,true,1f))
        assertEquals(0L,clock.sample(2000,24_000,false,1f))
        assertEquals(0L,clock.sample(3000,24_000,true,1f))
        assertEquals(100L,clock.sample(3100,26_400,true,1f))
    }
    @Test fun delayedObserverStillCreditsOnlyTheAudioThatPlayed() {
        val clock=ListeningClock()
        clock.sample(0,0,false,1f)
        assertEquals(3000L,clock.sample(5000,72_000,true,1f))
    }
    @Test fun partialUnderrunOnlyCreditsPresentedAudio() {
        val clock = ListeningClock()
        clock.sample(0,0,false,1f)
        assertEquals(10L,clock.sample(100,240,true,1f))
    }
    @Test fun unsignedPlaybackHeadWrapDoesNotLoseConsumption() {
        val clock = ListeningClock()
        clock.sample(0,0xfffffff0L,false,1f)
        assertEquals(1L,clock.sample(1,8,true,1f))
        assertEquals(0x1_0000_0008L,clock.presentedFrames)
    }
    @Test fun installationInspectionIsOfflineAndRequiresPublishedMarker() {
        val root = kotlin.io.path.createTempDirectory("folio-pack-").toFile()
        try {
            var validations = 0
            val installer = VoicePackInstaller(root,true,{ validations++ })
            assertFalse(installer.isInstalled())
            assertEquals(VoicePackPhase.NotInstalled,installer.state.value.phase)
            assertEquals(0,validations)
            assertEquals(65_051_089L,PhononPack.sizeBytes)
        } finally { root.deleteRecursively() }
    }
    @Test fun removingVoicePackKeepsUnrelatedFilesAndNeedsNoNetwork() = runBlocking {
        val root=kotlin.io.path.createTempDirectory("folio-pack-remove-").toFile()
        try {
            val directory=File(root,PhononPack.revision).apply { mkdirs() }
            PhononPack.assets.forEach { asset -> java.io.RandomAccessFile(File(directory,asset.name),"rw").use { it.setLength(asset.bytes) } }
            File(directory,"installed").writeText(PhononPack.revision)
            val sentinel=File(root,"unrelated").apply { writeText("keep") }
            val installer=VoicePackInstaller(root,true,{ error("Removal must not initialize a model") },{ error("Removal must not use the network") })
            assertEquals(VoicePackPhase.Installed,installer.state.value.phase)
            installer.remove()
            assertFalse(installer.isInstalled())
            assertEquals(VoicePackPhase.NotInstalled,installer.state.value.phase)
            assertEquals("keep",sentinel.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun persistenceCommitsHeardPositionAndAudioStatisticsTogether() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-narration-db-").toFile()
        val db = Database(File(root,"folio.db").path)
        try {
            JdbcBookRepository(db).insertBook(Book(id="book",title="A book",epubFileSize=0,epubHash="test"),false)
            val start = ReadingPosition("book","device","chapter",0,"/0/0:0")
            val heard = start.copy(normalizedProgress=.25,characterOffset=120)
            val session = ReadingSession("voice-span","book",null,"device",Clock.System.now(),durationMs=60_000,
                startPosition=start,endPosition=heard,endProgress=.25,isActive=false,
                activity=SessionActivity(listeningMs=60_000,listenedWords=150,sittingId="sitting"))
            val persistence = NarrationPersistence(db)
            persistence.save(NarrationCheckpoint(heard,"b1",1,"hash",completed=true),session,false)
            assertEquals(120,persistence.load("book")!!.position.characterOffset)
            assertFalse(persistence.load("book")!!.bookCompleted,"A completed sentence is not a completed book")
            assertEquals(.25,JdbcBookRepository(db).getBook("book")!!.normalizedProgress)
            try {
                NarrationActivity.ownerDeviceId = "device"
                NarrationActivity.ownerBookId = "book"
                JdbcReadingPositionRepository(db).upsertPosition(start,false)
                JdbcBookRepository(db).updateNormalizedProgress("book",0.0)
                assertEquals(120,JdbcReadingPositionRepository(db).getPosition("book","device")!!.characterOffset)
                assertEquals(.25,JdbcBookRepository(db).getBook("book")!!.normalizedProgress)
                // A remote device's history remains syncable while this device listens.
                JdbcReadingPositionRepository(db).upsertPosition(start.copy(deviceId="other"),false)
                assertNotNull(JdbcReadingPositionRepository(db).getPosition("book","other"))
            } finally { NarrationActivity.ownerBookId = null; NarrationActivity.ownerDeviceId = null }

            val saved = JdbcReadingSessionRepository(db).getSessionsForBook("book").first().single()
            assertEquals(60_000L,saved.activity!!.listeningMs)
            assertNull(saved.wordsPerMinute,"Listening must not inflate visual reading pace")
            // An old-client summary rewrite must retain locally known modality evidence.
            JdbcReadingSessionRepository(db).insertSession(saved.copy(activity=null),false)
            assertEquals(150L,JdbcReadingSessionRepository(db).getSessionsForBook("book").first().single().activity!!.listenedWords)
        } finally { db.close(); root.deleteRecursively() }
    }
    @Test fun newSessionMetadataRoundTripsAndOldSessionDefaultsRemainVisual() {
        // A live-clock default can compare equal during encoding and be omitted, then
        // decode to a later instant. This fixture tests modality, independently of clock resolution.
        val at = kotlinx.datetime.Instant.parse("2026-10-01T12:00:00Z")
        val p = ReadingPosition("b","d","c",0,"",updatedAt=at)
        val session = ReadingSession("s","b",null,"d",at,startPosition=p,
            activity=SessionActivity(listeningMs=1000,listenedWords=12,sittingId="s"))
        assertEquals(session,Json.decodeFromString(ReadingSession.serializer(),Json.encodeToString(ReadingSession.serializer(),session)))
        val old = session.copy(activity=null)
        assertNull(Json.decodeFromString(ReadingSession.serializer(),Json.encodeToString(ReadingSession.serializer(),old)).activity)
    }
    @Test fun traceDoesNotCreditSeekGapsAndGroupsOneSitting() {
        val p = ReadingPosition("b","d","c",0,"")
        fun span(id:String,from:Double,to:Double) = ReadingSession(id,"b",null,"d",Clock.System.now(),durationMs=60_000,
            startPosition=p,startProgress=from,endProgress=to,activity=SessionActivity(listeningMs=60_000,sittingId="one"))
        val trace = readingTrace(.8f,1,listOf(span("a",.1,.2),span("b",.7,.8)),emptyList())
        assertEquals(2,trace.spans.size)
        assertEquals(1,trace.sessionCount)
        assertEquals(2L,trace.minutes)
        assertTrue(trace.spans.none { it.from < .5f && it.to > .5f })
    }
}
