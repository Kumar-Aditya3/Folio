package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.folio.reader.database.*
import com.folio.reader.manga.MangaEntry
import com.folio.reader.model.*
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.*
import com.folio.reader.ui.statistics.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class ReadingRoomPolishTest {
    private fun book(id: String) = Book(id, "Book $id", epubHash = id, epubFileSize = 1,
        status = BookStatus.READING, lastOpenedAt = Clock.System.now())
    private fun manga(id: String) = MangaEntry(id, 8L, "Source", id, "Manga $id", inLibrary = true)
    private fun session(id: String, minutes: Long, manga: Boolean = false) = ReadingSession(
        "$id-$minutes-$manga", id, null, "test", Clock.System.now(), durationMs = minutes * 60_000,
        startPosition = ReadingPosition(id, "test", "chapter", 0, if (manga) "manga-page-1" else "cfi"))

    @Test fun mangaAndBooksWithTheSameIdRemainSeparateNamedAllocations() {
        val rows = readingTimeEntries(listOf(session("same", 10), session("same", 35, true)),
            listOf(book("same")), listOf(manga("same")))
        assertEquals(listOf("Manga same", "Book same"), rows.map { it.title })
        assertEquals(listOf(35L, 10L), rows.map { it.minutes })
        assertEquals(0L, readingTimeRemainder(rows, 45))
        assertEquals(10L, readingTimeRemainder(rows.take(1), 45))
    }

    @Test fun mangaAttributionAndExclusionsUseTheSameWindowAsTheTotal() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("folio-allocation-").toFile()
        val database = Database(DesktopPlatform(directory).fileSystem.getDatabasePath())
        try {
            val books = JdbcBookRepository(database)
            val mangas = JdbcMangaRepository(database)
            val sessions = JdbcReadingSessionRepository(database)
            val exclusions = JdbcStatsExclusionRepository(database)
            books.insertBook(book("b"), false)
            mangas.upsert(manga("m"), false)
            sessions.insertSession(session("b", 10), false)
            sessions.insertSession(session("m", 35, true), false)
            val vm = StatisticsViewModel(books, sessions, statsExclusionRepository = exclusions,
                mangaRepository = mangas)
            val first = withTimeout(10_000) { vm.state.first() }
            assertEquals(listOf("Manga m", "Book b"), first.readingTime.map { it.title })
            assertEquals(45L, first.timeThisYearMs / 60_000)
            assertEquals(listOf("b"), first.topBooks.map { it.id })
            assertEquals(1, first.booksOpened)
            assertEquals(2, first.readingTitlesOpened)
            assertEquals(0L, readingTimeRemainder(first.readingTime, 45))
            exclusions.add(Scope.MANGA, "m")
            val filtered = withTimeout(10_000) { vm.state.first() }
            assertEquals(listOf("Book b"), filtered.readingTime.map { it.title })
            assertEquals(10L, filtered.timeThisYearMs / 60_000)
            assertEquals(10L, filtered.readingTimeTotalMinutes)
            assertEquals(0L, readingTimeRemainder(filtered.readingTime, 10))
            exclusions.remove(Scope.MANGA, "m")
            exclusions.add(Scope.BOOK, "b")
            val onlyManga = withTimeout(10_000) { vm.state.first() }
            assertEquals(listOf("Manga m"), onlyManga.readingTime.map { it.title })
            assertEquals(35L, onlyManga.readingTimeTotalMinutes)
            exclusions.add(Scope.MANGA_SOURCE, "8")
            val empty = withTimeout(10_000) { vm.state.first() }
            assertTrue(empty.readingTime.isEmpty())
            assertEquals(0L, empty.readingTimeTotalMinutes)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test fun extensionReadsStayOutOfLibraryAllocationIncludingTheRemainder() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("folio-browse-time-").toFile()
        val database = Database(DesktopPlatform(directory).fileSystem.getDatabasePath())
        try {
            val books = JdbcBookRepository(database)
            val mangas = JdbcMangaRepository(database)
            val sessions = JdbcReadingSessionRepository(database)
            books.insertBook(book("b"), false)
            mangas.upsert(manga("outside").copy(inLibrary = false), false)
            sessions.insertSession(session("b", 10), false)
            sessions.insertSession(session("outside", 25, true), false)
            val vm = StatisticsViewModel(books, sessions, mangaRepository = mangas)
            suspend fun check(inLibrary: Boolean) {
                val state = withTimeout(10_000) { vm.state.first() }
                assertEquals(35L, state.timeThisYearMs / 60_000)
                assertEquals(if (inLibrary) listOf("Manga outside", "Book b") else listOf("Book b"),
                    state.readingTime.map { it.title })
                assertEquals(if (inLibrary) 35L else 10L, state.readingTimeTotalMinutes)
                assertEquals(if (inLibrary) 2 else 1, state.readingLibrarySize)
                assertEquals(0L, readingTimeRemainder(state.readingTime, state.readingTimeTotalMinutes))
            }
            check(false)
            mangas.setInLibrary("outside", true)
            check(true)
            mangas.setInLibrary("outside", false)
            check(false)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test fun allocationRemainderContainsOnlyHiddenEligibleLibraryTitles() {
        val library = (1..9).map { book("$it") }
        val sessions = library.map { session(it.id, 10) } +
            session("removed-book", 100) + session("outside-manga", 200, true)
        val rows = readingTimeEntries(sessions, library,
            listOf(manga("outside-manga").copy(inLibrary = false)))
        assertEquals(9, rows.size)
        assertEquals(90L, rows.sumOf { it.minutes })
        assertEquals(10L, readingTimeRemainder(rows.take(8), rows.sumOf { it.minutes }))
    }

    @Test fun deskPreviewDoesNotRemoveTitlesOrCapTheHomeQueueAtSix() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("folio-continue-").toFile()
        val database = Database(DesktopPlatform(directory).fileSystem.getDatabasePath())
        try {
            val books = JdbcBookRepository(database)
            repeat(30) { books.insertBook(book("book-$it"), false) }
            val vm = HomeViewModel(books, JdbcReadingSessionRepository(database))
            val state = withTimeout(10_000) { vm.state.first { it.loaded } }
            assertEquals(30, state.readingNow.size)
            assertEquals(29, homeContinueItems(state.readingNow, true).size)
            val selected = state.readingNow[1].shelfKey()
            val queue = homeContinueItems(state.readingNow, true, selected)
            assertFalse(queue.any { it.shelfKey() == selected })
            assertTrue(queue.any { it.id == state.readingNow.first().id })
            assertEquals(3, revolvingShelfItems(state.readingNow).size)
            assertEquals(29, homeContinueItems(state.readingNow, false).size)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun weekChartIsCompactAndTappingThePlotChangesSelection() {
        val week = (1..7).map { StatDay(LocalDate(2026, 10, it), it * 10L) }
        var height = 0
        val scene = ImageComposeScene(393, 500) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().onGloballyPositioned { height = it.size.height }) { WeekChart(week) }
            }
        }
        try {
            repeat(8) { scene.render(it * 200_000_000L).close() }
            assertTrue(height in 150..300, "Week chart grew to $height px")
            val before = scene.render(2_000_000_000L).let { image ->
                try { image.encodeToData(EncodedImageFormat.PNG)!!.bytes } finally { image.close() }
            }
            scene.sendPointerEvent(PointerEventType.Press, Offset(22f, 90f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(22f, 90f))
            val after = scene.render(2_100_000_000L).let { image ->
                try { image.encodeToData(EncodedImageFormat.PNG)!!.bytes } finally { image.close() }
            }
            assertFalse(before.contentEquals(after), "Tapping another day did not update the chart")
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun statsCardTransmitsTheRoomAndHonorsThePanelOpacityKnob() {
        fun pixel(room: Color, opacity: Float, blur: Boolean): Int {
            val scene = ImageComposeScene(300, 240) {
                FolioTheme.MaterialTheme(darkTheme = true, opacity = FolioSurfaceOpacity(panel = opacity)) {
                    FolioGlassRoot(GlassCapabilities(blur, blur, blur)) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().folioRoomSource().background(room))
                            StatsGlassCard { Spacer(Modifier.fillMaxWidth().height(160.dp)) }
                        }
                    }
                }
            }
            try {
                repeat(6) { scene.render(it * 150_000_000L).close() }
                val bitmap = scene.render(1_000_000_000L)
                try { return ImageIO.read(ByteArrayInputStream(bitmap.encodeToData(EncodedImageFormat.PNG)!!.bytes)).getRGB(150, 80) }
                finally { bitmap.close() }
            } finally { scene.close() }
        }
        for (blur in listOf(false, true)) {
            assertNotEquals(pixel(Color.Red, .35f, blur), pixel(Color.Blue, .35f, blur), "Stats became opaque")
            assertEquals(pixel(Color.Red, 1f, blur), pixel(Color.Blue, 1f, blur), "Solid preference was ignored")
        }
    }
}
