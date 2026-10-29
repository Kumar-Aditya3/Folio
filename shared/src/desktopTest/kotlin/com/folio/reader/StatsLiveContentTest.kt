package com.folio.reader

import com.folio.reader.database.*
import com.folio.reader.manga.*
import com.folio.reader.model.*
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import java.io.File
import kotlin.test.*

/** Source regressions for the source-only Stats follow-up; no APK is needed to run these later. */
class StatsLiveContentTest {
    private suspend fun withDatabase(block: suspend (Database, File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("folio-live-stats-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        try { block(db, root) } finally {
            db.close()
            val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalPath + File.separator
            check(root.canonicalPath.startsWith(temporaryRoot) && root.name.startsWith("folio-live-stats-"))
            root.deleteRecursively()
        }
    }

    private fun manga(id: String, library: Boolean = true, source: Long = 7) =
        MangaEntry(id, source, "Test source", id, "Manga $id", inLibrary = library)
    private fun session(id: String, minutes: Long, manga: Boolean = true) = ReadingSession(
        "$id-$minutes", id, null, "test", Clock.System.now(), durationMs = minutes * 60_000,
        startPosition = ReadingPosition(id, "test", "chapter", 0, if (manga) "manga-page-1" else "cfi"))

    @Test fun mostReadUsesTheWholeEligibleFeedAndUpdatesAfterLibraryAndCategoryChanges() = runBlocking<Unit> {
        withDatabase { db, _ ->
            val books = JdbcBookRepository(db)
            val mangas = JdbcMangaRepository(db)
            val categories = JdbcMangaCategoryRepository(db)
            val sessions = JdbcReadingSessionRepository(db)
            val exclusions = JdbcStatsExclusionRepository(db)
            val hidden = categories.create("Hidden", false)
            exclusions.add(Scope.MANGA_CATEGORY, hidden.id)
            exclusions.add(Scope.MANGA, "excluded")
            exclusions.add(Scope.MANGA_SOURCE, "17")
            repeat(9) { index ->
                val id = "book-$index"
                books.insertBook(Book(id, id, epubHash = id, epubFileSize = 1), false)
                sessions.insertSession(session(id, 50, manga = false), false)
            }
            repeat(5) { index ->
                val id = "kept-$index"
                mangas.upsert(manga(id), false)
                sessions.insertSession(session(id, (index + 1).toLong()), false)
            }
            for (entry in listOf(manga("outside", library = false), manga("excluded"), manga("source", source = 17))) {
                mangas.upsert(entry, false)
                sessions.insertSession(session(entry.id, 1000), false)
            }
            val vm = StatisticsViewModel(books, sessions, statsExclusionRepository = exclusions,
                mangaRepository = mangas, mangaCategoryRepository = categories)
            val visibleLease = vm.keepVisible()
            try {
            suspend fun awaitRanking(expected: List<String>): StatisticsUiState = withTimeout(5_000) {
                vm.sharedState.first { it.hasData && it.mostReadManga.map { m -> m.mangaId } == expected }
            }
            val ranked = awaitRanking(listOf("kept-4", "kept-3", "kept-2", "kept-1", "kept-0"))
            assertTrue(ranked.readingTime.none { it.isManga }, "All manga sit below the mixed eight-row cap in this fixture")
            mangas.setInLibrary("kept-4", false)
            awaitRanking(listOf("kept-3", "kept-2", "kept-1", "kept-0"))
            categories.assign("kept-3", setOf(hidden.id), false)
            awaitRanking(listOf("kept-2", "kept-1", "kept-0"))
            } finally { visibleLease.close() }
        }
    }

    @Test fun forecastCarriesTheBooksActualCoverPath() = runBlocking<Unit> {
        withDatabase { db, directory ->
            val cover = File(directory, "cover.png").apply {
                writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1sAAAAASUVORK5CYII="))
            }
            val books = JdbcBookRepository(db)
            val book = Book("covered", "A covered book", epubHash = "covered", epubFileSize = 1,
                status = BookStatus.READING, coverPath = cover.absolutePath).apply { updateProgress(.4) }
            books.insertBook(book, false)
            val vm = StatisticsViewModel(books, JdbcReadingSessionRepository(db))
            val state = withTimeout(5_000) { vm.sharedState.first { it.currentlyReading.isNotEmpty() } }
            assertEquals(cover.absolutePath, state.currentlyReading.single().coverPath)
        }
    }

    @Test fun mangaSummaryObservesLibraryRemovalAndNewReadingTime() = runBlocking<Unit> {
        withDatabase { db, _ ->
            val mangas = JdbcMangaRepository(db)
            val sessions = JdbcReadingSessionRepository(db)
            mangas.upsert(manga("live"), false)
            sessions.insertSession(session("live", 10), false)
            val current = MutableStateFlow<MangaStatistics?>(null)
            val collector = launch { JdbcMangaStatisticsRepository(db).observeStatistics().collect { current.value = it } }
            suspend fun awaitMinutes(minutes: Long) = withTimeout(5_000) { current.first { it?.totalReadMinutes == minutes } }
            try {
                awaitMinutes(10)
                mangas.setInLibrary("live", false)
                val removed = awaitMinutes(0)
                assertTrue(removed!!.topManga.isEmpty())
                mangas.setInLibrary("live", true)
                awaitMinutes(10)
                sessions.insertSession(session("live", 20), false)
                awaitMinutes(30)
            } finally { collector.cancelAndJoin() }
        }
    }
}
