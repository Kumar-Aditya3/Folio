package com.folio.reader

import com.folio.reader.database.*
import com.folio.reader.model.*
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class ReadingTraceTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private fun highlight(id: String, deleted: Boolean = false) = Highlight(id, "book", "chapter", 2,
        "/2/f500", "/2/f600", "A saved passage", deviceId = "device", isDeleted = deleted)
    private fun session(id: String, from: Double, to: Double, minutes: Long = 12) = ReadingSession(
        id, "book", null, "device", now, now + minutes.minutes, minutes * 60_000,
        ReadingPosition("book", "device", "chapter", 2, "/2/f0"), startProgress = from,
        endProgress = to, isActive = false)

    @Test fun marksReflectSavedLocationsAndNotesWithoutInventingHistory() {
        val trace = readingTrace(.63f, 10, emptyList(), listOf(highlight("kept").copy(noteId = "note"),
            highlight("gone", true), highlight("kept")))
        assertEquals(0, trace.sessionCount)
        assertEquals(1, trace.passages.size)
        assertEquals(.25f, trace.passages.single().fraction, .0001f)
        assertTrue(trace.passages.single().hasNote)
        assertEquals("1 passage", trace.summary)
        assertTrue(readingTrace(0f, 0, emptyList(), emptyList()).spans.isEmpty())
    }

    @Test fun measuredHistoryIsFiniteBoundedAndIncludesRereading() {
        val inputs = (0..70).map { session("$it", .4, .6) } +
            session("reread", .8, .5) + session("bad", Double.NaN, .2) +
            session("empty", .1, .3, 0) + session("ancient", .1, .9).copy(startedAt = Instant.fromEpochMilliseconds(0))
        val trace = readingTrace(Float.NaN, 0, inputs, emptyList())
        assertEquals(0f, trace.progress)
        assertEquals(72, trace.sessionCount)
        assertEquals(48, trace.spans.size)
        assertTrue(trace.spans.any { it.from == .5f && it.to == .8f })
        assertTrue(trace.spans.all { it.from in 0f..1f && it.to in it.from..1f })
    }

    @Test fun savedMarksUseTheSameWordWeightedChapterBandsAsTheReader() {
        val chapters = listOf(Chapter("short", "book", "One", "one", 0, wordCount = 100),
            Chapter("long", "book", "Two", "two", 1, wordCount = 900))
        val trace = readingTrace(.6f, 2, emptyList(),
            listOf(highlight("kept").copy(chapterId = "long", spineIndex = 1, startLocator = "/1/f500")), chapters)
        assertEquals(.55f, trace.passages.single().fraction, .0001f)
    }

    @Test fun ninePairsRetainExistingIdsAndAddTwoRealReaderPairs() {
        assertEquals(9, ThemePack.ALL.size)
        assertEquals(18, AppPalette.entries.size)
        for (pack in ThemePack.ALL) {
            for (dark in listOf(false, true)) {
                val settings = com.folio.reader.settings.ReaderSettings(appThemeId = pack.appPaletteId(dark), themeId = pack.readerThemeId(dark))
                val back = flipThemeMode(flipThemeMode(settings))
                assertEquals(settings.appThemeId, back.appThemeId)
                assertEquals(settings.themeId, back.themeId)
            }
        }
        val vapor = AppPalette.MAGENTA.colors
        val synth = AppPalette.NEON.colors
        assertTrue(vapor.primary.red > vapor.primary.green)
        assertTrue(synth.primary.green > synth.primary.red)
        assertTrue(synth.primary.green - synth.primary.blue > .25f)
        val crimson = AppPalette.BROWN.colors
        assertTrue(crimson.primary.red > crimson.primary.green)
        assertTrue(crimson.accentDiscovery.green > crimson.accentDiscovery.red)
    }

    @Test fun categoricalChartsUseEachFacesAuthoredColors() {
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            assertEquals(8, colors.chartSeries.size)
            assertEquals(8, colors.chartSeries.distinct().size)
        }
        assertNotEquals(AppPalette.MAGENTA.colors.chartSeries, AppPalette.NEON.colors.chartSeries)
        for (palette in listOf(AppPalette.LIGHT, AppPalette.DARK))
            assertTrue(palette.colors.chartSeries.all { it.red == it.green && it.green == it.blue })
    }

    @Test fun savedAndDeletedPassagesRefreshOneOngoingTraceCollection() = runBlocking<Unit> {
        val directory = kotlin.io.path.createTempDirectory("folio-trace-").toFile()
        val db = Database(DesktopPlatform(directory).fileSystem.getDatabasePath())
        val observed = MutableStateFlow<Map<String, ReadingTrace>>(emptyMap())
        val collector = launch { ReadingTraceRepository(db).traces.collect { observed.value = it } }
        suspend fun awaitCount(count: Int) = withTimeout(5_000) {
            observed.first { it["book"]?.passages?.size == count }
        }
        try {
            JdbcBookRepository(db).insertBook(Book("book", "Small Worlds", epubHash = "hash", epubFileSize = 1,
                chapterCount = 10), emitSyncEvent = false)
            awaitCount(0)
            val repo = JdbcHighlightRepository(db)
            repo.insertHighlight(highlight("kept"), emitSyncEvent = false)
            awaitCount(1)
            val notes = JdbcNoteRepository(db)
            notes.insertNote(Note("note", "book", "chapter", 2, "/2/f500", "My note", deviceId = "device"), false)
            repo.updateHighlight(highlight("kept").copy(noteId = "note"), emitSyncEvent = false)
            withTimeout(5_000) { observed.first { it["book"]?.passages?.singleOrNull()?.hasNote == true } }
            notes.deleteNote("note", false)
            withTimeout(5_000) { observed.first { it["book"]?.passages?.singleOrNull()?.hasNote == false } }
            repo.deleteHighlight("kept", emitSyncEvent = false)
            awaitCount(0)
            repo.restoreHighlight("kept")
            awaitCount(1)
        } finally {
            collector.cancelAndJoin()
            db.close()
            val temporaryRoot = java.io.File(System.getProperty("java.io.tmpdir")).canonicalPath + java.io.File.separator
            check(directory.canonicalPath.startsWith(temporaryRoot) && directory.name.startsWith("folio-trace-"))
            directory.deleteRecursively()
        }
    }
}
