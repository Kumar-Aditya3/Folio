package com.folio.reader.narration

import java.nio.file.Files
import kotlin.test.*

class NarrationDeliveryTest {
    @Test fun deletingABookPrunesOnlyItsOwnPronunciations() = kotlinx.coroutines.runBlocking<Unit> {
        val root = Files.createTempDirectory("folio-pronunciation").toFile()
        val db = com.folio.reader.database.Database(com.folio.reader.platform.DesktopPlatform(root).fileSystem.getDatabasePath())
        try {
            val books = com.folio.reader.database.JdbcBookRepository(db)
            val settings = com.folio.reader.database.JdbcSettingsRepository(db)
            for (id in listOf("book","other")) {
                books.insertBook(com.folio.reader.model.Book(id,id,epubHash=id,epubFileSize=1),false)
                settings.setRaw("narration.pronunciations:$id","a saved pronunciation")
            }
            books.deleteBook("book",false)
            assertNull(settings.getRaw("narration.pronunciations:book"))
            assertEquals("a saved pronunciation",settings.getRaw("narration.pronunciations:other"))
        } finally { db.close(); root.deleteRecursively() }
    }
    @Test fun sceneSeparatorsBecomeSilenceWithoutInventingWordsOrAnchors() {
        val document = NarrationText.prepare("<p>Before.</p><p>* * *</p><p>After.</p>")
        assertEquals(listOf("Before.","After."),document.passages.map { it.text })
        assertEquals(700,document.passages.first().pauseAfterMs)
        assertTrue(document.passages[1].start > document.passages[0].end)
        assertFalse(document.html.contains("data-folio-narration=\"b1\""))
    }
    @Test fun groupsSentencesWithinTheSameSourceAnchorOnly() {
        val original = NarrationText.prepare("<h1>Morning</h1><p>The rain stopped. She opened the door.</p><p>Outside, it was quiet.</p>")
        val plan = NarrationDelivery.plan(original,NarrationOptions())
        assertEquals(3,plan.passages.size)
        assertEquals("The rain stopped. She opened the door.",plan.passages[1].text)
        assertEquals(original.passages[1].start,plan.passages[1].start)
        assertEquals(original.passages[2].end,plan.passages[1].end)
        assertEquals(original.passages[1].blockId,plan.passages[1].blockId)
        assertEquals(original.html,plan.html)
        assertEquals(500,plan.passages[0].pauseAfterMs)
        assertEquals(240,plan.passages[1].pauseAfterMs)
        assertEquals(original.passages.size,NarrationDelivery.plan(original,NarrationOptions(paragraphPhrasing=false)).passages.size)
        assertTrue(NarrationDelivery.plan(original,NarrationOptions(structuralPauses=false)).passages.all { it.pauseAfterMs == 0 })
    }
    @Test fun pronunciationIsLiteralBoundedAndDoesNotCascade() {
        val rules = listOf(PronunciationRule("Dune","Arrakis"),PronunciationRule("Arrakis","a rack iss"),PronunciationRule("A.B.","ay bee"))
        assertEquals("Arrakis, a rack iss; unduned. ay bee",NarrationDelivery.spoken("Dune, Arrakis; unduned. A.B.",rules))
        assertEquals("dunegrass Arrakis",NarrationDelivery.spoken("dunegrass DUNE",rules))
        assertEquals(1,NarrationDelivery.sanitize(listOf(PronunciationRule(" Name ","naym"),PronunciationRule("name","other"),PronunciationRule("","x"))).size)
    }
    @Test fun clauseSplitsPreserveEveryWordAndNeverExceedTheBound() {
        val text = (1..120).joinToString(" ") { "word$it" } + ", " + (121..200).joinToString(" ") { "word$it" }
        val passages = NarrationDelivery.plan(NarrationText.prepare("<p>$text</p>"),NarrationOptions()).passages
        assertTrue(passages.all { it.text.length <= 360 })
        assertEquals(text,passages.joinToString(" ") { it.text })
    }
    @Test fun cachePublishesOnlyCompleteAudioAndKeysSeparateVoicesAndCorrections() {
        val root = Files.createTempDirectory("folio-audio").toFile()
        try {
            val cache = NarrationAudioCache(root)
            val config = NarrationConfiguration()
            val key = cache.key(config,"Hello")
            assertNull(cache.replay("book",key) { true })
            cache.writer("book",key)!!.use { it.append(floatArrayOf(.25f,-.5f,1f)) }
            assertNull(cache.replay("book",key) { true })
            cache.writer("book",key)!!.use { it.append(floatArrayOf(.25f,-.5f,1f)); cache.trim(); it.commit() }
            val samples = mutableListOf<Float>()
            assertEquals(true,cache.replay("book",key) { samples.addAll(it.toList()); true })
            assertEquals(3,samples.size)
            assertEquals(.25f,samples[0],.0001f)
            assertNotEquals(key,cache.key(config.copy(phononVoiceId="other"),"Hello"))
            assertNotEquals(key,cache.key(config,"Hallo"))
            assertNull(cache.replay("other-book",key) { true })
            assertEquals(false,cache.replay("book",key) { false })
            cache.clear("book"); assertEquals(0,cache.bytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun cacheRejectsCorruptionTrimsAndCannotRepublishAfterClearing() {
        val root = Files.createTempDirectory("folio-audio").toFile()
        try {
            val cache = NarrationAudioCache(root)
            val key = cache.key(NarrationConfiguration(),"Test")
            val orphan = java.io.File(root,"abandoned/old.part").apply { parentFile.mkdirs(); writeBytes(ByteArray(100)) }
            cache.trim(); assertFalse(orphan.exists())
            cache.writer("book",key)!!.use { it.append(FloatArray(100)); it.commit() }
            root.walkTopDown().first { it.extension == "pcm" }.appendBytes(byteArrayOf(1))
            assertNull(cache.replay("book",key) { fail("Corrupt data reached playback") })
            val writer = cache.writer("book",key)!!
            writer.append(FloatArray(100)); cache.clear(); writer.commit()
            assertEquals(0,cache.bytes())
            cache.maximumBytes = 20
            cache.writer("book",key)!!.use { it.append(FloatArray(100)); it.commit() }
            assertEquals(0,cache.bytes())
        } finally { root.deleteRecursively() }
    }
}
