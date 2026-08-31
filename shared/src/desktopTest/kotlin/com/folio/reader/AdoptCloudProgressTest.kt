package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcDeviceRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcQuoteRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcReadingSessionRepository
import com.folio.reader.database.JdbcRevisitRepository
import com.folio.reader.database.JdbcSeriesRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.database.JdbcSyncQueueRepository
import com.folio.reader.database.JdbcTagRepository
import com.folio.reader.firebase.FsBook
import com.folio.reader.firebase.FsBookmark
import com.folio.reader.firebase.FsCollection
import com.folio.reader.firebase.FsHighlight
import com.folio.reader.firebase.FsManga
import com.folio.reader.firebase.FsMangaCategory
import com.folio.reader.firebase.FsMangaChapter
import com.folio.reader.firebase.FsMangaNote
import com.folio.reader.firebase.FsNote
import com.folio.reader.firebase.FsQuote
import com.folio.reader.firebase.FsReadingPosition
import com.folio.reader.firebase.FsReadingSession
import com.folio.reader.firebase.FsRevisitItem
import com.folio.reader.firebase.FsSeries
import com.folio.reader.firebase.FsSettings
import com.folio.reader.firebase.FsTag
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.model.Book
import com.folio.reader.model.ReadingPosition
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.sync.FirestoreSync
import com.folio.reader.sync.NoopStorageSync
import com.folio.reader.sync.SyncConfig
import com.folio.reader.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Import-time cloud progress adoption: a freshly imported EPUB/manga must pick
 * up existing cloud progress instead of overwriting it with a zero position.
 */
class AdoptCloudProgressTest {

    private class FakeFirestore : FirestoreSync {
        val books = mutableListOf<FsBook>()
        val positions = mutableListOf<FsReadingPosition>()
        val mangaChapters = mutableListOf<FsMangaChapter>()

        override fun authenticate() = Unit
        override fun isConnected(): Boolean = true
        override fun upsertBook(book: FsBook) {}
        override fun upsertPosition(position: FsReadingPosition) {}
        override fun upsertHighlight(highlight: FsHighlight) {}
        override fun upsertNote(note: FsNote) {}
        override fun upsertBookmark(bookmark: FsBookmark) {}
        override fun upsertSession(session: FsReadingSession) {}
        override fun upsertSettings(settings: FsSettings) {}
        override fun upsertCollection(collection: FsCollection) {}
        override fun upsertSeries(series: FsSeries) {}
        override fun upsertTag(tag: FsTag) {}
        override fun upsertQuote(quote: FsQuote) {}
        override fun upsertRevisitItem(item: FsRevisitItem) {}
        override fun fetchBooks(): List<FsBook> = books.toList()
        override fun fetchPositions(): List<FsReadingPosition> = positions.toList()
        override fun fetchPositionsForBook(bookId: String): List<FsReadingPosition> = positions.filter { it.bookId == bookId }
        override fun fetchHighlights(): List<FsHighlight> = emptyList()
        override fun fetchNotes(): List<FsNote> = emptyList()
        override fun fetchBookmarks(): List<FsBookmark> = emptyList()
        override fun fetchSessions(sinceStartedAtMs: Long): List<FsReadingSession> = emptyList()
        override fun fetchCollections(): List<FsCollection> = emptyList()
        override fun fetchSeries(): List<FsSeries> = emptyList()
        override fun fetchTags(): List<FsTag> = emptyList()
        override fun fetchQuotes(): List<FsQuote> = emptyList()
        override fun fetchRevisitItems(): List<FsRevisitItem> = emptyList()
        override fun fetchSettings(): FsSettings? = null
        override fun upsertManga(manga: FsManga) {}
        override fun upsertMangaChapter(chapter: FsMangaChapter) {}
        override fun upsertMangaNote(note: FsMangaNote) {}
        override fun upsertMangaCategory(category: FsMangaCategory) {}
        override fun fetchManga(): List<FsManga> = emptyList()
        override fun fetchMangaChapters(): List<FsMangaChapter> = mangaChapters.toList()
        override fun fetchMangaNotes(): List<FsMangaNote> = emptyList()
        override fun fetchMangaCategories(): List<FsMangaCategory> = emptyList()
    }

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var bookRepo: JdbcBookRepository
    private lateinit var positionRepo: JdbcReadingPositionRepository
    private lateinit var settingsRepo: JdbcSettingsRepository
    private lateinit var syncQueueRepo: JdbcSyncQueueRepository
    private lateinit var mangaRepo: JdbcMangaRepository
    private lateinit var mangaChapterRepo: JdbcMangaChapterRepository
    private lateinit var fake: FakeFirestore
    private lateinit var engine: SyncEngine

    private val selfDeviceId = "self-device"

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-adopt-")
        val platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        bookRepo = JdbcBookRepository(database)
        positionRepo = JdbcReadingPositionRepository(database)
        val highlightRepo = JdbcHighlightRepository(database)
        val noteRepo = JdbcNoteRepository(database)
        val bookmarkRepo = JdbcBookmarkRepository(database)
        val sessionRepo = JdbcReadingSessionRepository(database)
        settingsRepo = JdbcSettingsRepository(database)
        syncQueueRepo = JdbcSyncQueueRepository(database)
        val deviceRepo = JdbcDeviceRepository(database)
        val collectionRepo = JdbcCollectionRepository(database)
        val seriesRepo = JdbcSeriesRepository(database)
        val tagRepo = JdbcTagRepository(database)
        val quoteRepo = JdbcQuoteRepository(database)
        val revisitRepo = JdbcRevisitRepository(database)
        mangaRepo = JdbcMangaRepository(database)
        mangaChapterRepo = JdbcMangaChapterRepository(database)
        runBlocking {
            settingsRepo.saveGlobalSettings(
                ReaderSettings(
                    firebaseApiKey = "test-api-key",
                    firebaseProjectId = "test-project",
                    cloudSyncEnabled = true
                )
            )
        }
        fake = FakeFirestore()
        engine = SyncEngine(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            syncRepository = syncQueueRepo,
            bookRepository = bookRepo,
            positionRepository = positionRepo,
            highlightRepository = highlightRepo,
            noteRepository = noteRepo,
            bookmarkRepository = bookmarkRepo,
            sessionRepository = sessionRepo,
            settingsRepository = settingsRepo,
            deviceRepository = deviceRepo,
            collectionRepository = collectionRepo,
            seriesRepository = seriesRepo,
            tagRepository = tagRepo,
            quoteRepository = quoteRepo,
            revisitRepository = revisitRepo,
            firestoreSync = fake,
            storageSync = NoopStorageSync,
            config = SyncConfig(autoSync = false),
            deviceId = selfDeviceId,
            mangaRepository = mangaRepo,
            mangaChapterRepository = mangaChapterRepo,
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun localBook(id: String, hash: String) = Book(
        id = id,
        title = "Reimported Book",
        epubHash = hash,
        epubFileSize = 1024
    )

    private fun cloudPosition(
        bookId: String,
        deviceId: String,
        progress: Double,
        updatedAtMs: Long
    ) = FsReadingPosition(
        bookId = bookId,
        deviceId = deviceId,
        chapterId = "ch-$deviceId",
        spineIndex = 3,
        contentLocator = "epubcfi(/6/$deviceId)",
        normalizedProgress = progress,
        chapterProgress = progress,
        updatedAt = updatedAtMs
    )

    @Test
    fun adoptSeedsPositionsFromCloudMatchAndUpdatesBookProgress() = runBlocking {
        val hash = "epub-hash-1"
        fake.books.add(
            FsBook(
                id = "cloud-book-id",
                title = "Cloud Book",
                epubHash = hash,
                epubFileSize = 1024,
                addedAt = 0L,
                deviceId = "phone-device"
            )
        )
        fake.positions.add(cloudPosition("cloud-book-id", "phone-device", 0.4, updatedAtMs = 1_000))
        fake.positions.add(cloudPosition("cloud-book-id", "pc-device", 0.75, updatedAtMs = 2_000))

        bookRepo.insertBook(localBook("local-book-id", hash), emitSyncEvent = false)

        val adopted = engine.adoptCloudProgressForBook("local-book-id", hash)

        assertEquals(2, adopted)
        val phone = positionRepo.getPosition("local-book-id", "phone-device")
        val pc = positionRepo.getPosition("local-book-id", "pc-device")
        assertEquals(0.4, phone!!.normalizedProgress)
        assertEquals(0.75, pc!!.normalizedProgress)
        assertEquals("ch-phone-device", phone.chapterId)
        val book = bookRepo.getBook("local-book-id")!!
        assertEquals(0.75, book.normalizedProgress)
        // Silent adoption must not echo back into the outbox.
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "adoption must not enqueue sync events")
    }

    @Test
    fun adoptKeepsNewerLocalRowsAndOnlyAdoptsOlderOnes() = runBlocking {
        val hash = "epub-hash-2"
        fake.books.add(
            FsBook(
                id = "cloud-book-2",
                title = "Cloud Book 2",
                epubHash = hash,
                epubFileSize = 1024,
                addedAt = 0L,
                deviceId = "phone-device"
            )
        )
        fake.positions.add(cloudPosition("cloud-book-2", "phone-device", 0.4, updatedAtMs = 1_000))
        fake.positions.add(cloudPosition("cloud-book-2", "pc-device", 0.9, updatedAtMs = 5_000))

        bookRepo.insertBook(localBook("local-2", hash), emitSyncEvent = false)
        // Local row newer than the cloud row for the same device must survive.
        positionRepo.upsertPosition(
            ReadingPosition(
                bookId = "local-2",
                deviceId = "phone-device",
                chapterId = "local-ch",
                spineIndex = 9,
                contentLocator = "epubcfi(/6/local)",
                normalizedProgress = 0.6,
                updatedAt = Instant.fromEpochMilliseconds(3_000)
            ),
            emitSyncEvent = false
        )

        val adopted = engine.adoptCloudProgressForBook("local-2", hash)

        assertEquals(1, adopted)
        val phone = positionRepo.getPosition("local-2", "phone-device")!!
        assertEquals("local-ch", phone.chapterId, "newer local row must not be overwritten")
        assertEquals(0.6, phone.normalizedProgress)
        val pc = positionRepo.getPosition("local-2", "pc-device")!!
        assertEquals(0.9, pc.normalizedProgress)
    }

    @Test
    fun adoptReturnsZeroWhenCloudHasNoMatch() = runBlocking {
        fake.books.add(
            FsBook(
                id = "cloud-book-3",
                title = "Other Book",
                epubHash = "different-hash",
                epubFileSize = 1024,
                addedAt = 0L,
                deviceId = "phone-device"
            )
        )
        fake.positions.add(cloudPosition("cloud-book-3", "phone-device", 0.5, updatedAtMs = 1_000))
        bookRepo.insertBook(localBook("local-3", "my-hash"), emitSyncEvent = false)

        assertEquals(0, engine.adoptCloudProgressForBook("local-3", "my-hash"))
        assertEquals(null, positionRepo.getPosition("local-3", "phone-device"))
    }

    @Test
    fun adoptIgnoresDeletedCloudBooks() = runBlocking {
        val hash = "epub-hash-4"
        fake.books.add(
            FsBook(
                id = "cloud-book-4",
                title = "Deleted Book",
                epubHash = hash,
                epubFileSize = 1024,
                addedAt = 0L,
                deviceId = "phone-device",
                isDeleted = true
            )
        )
        fake.positions.add(cloudPosition("cloud-book-4", "phone-device", 0.5, updatedAtMs = 1_000))
        bookRepo.insertBook(localBook("local-4", hash), emitSyncEvent = false)

        assertEquals(0, engine.adoptCloudProgressForBook("local-4", hash))
    }

    @Test
    fun adoptMangaAppliesCloudReadStateToExistingChapters() = runBlocking {
        val mangaId = "local:My Series"
        mangaRepo.upsert(
            MangaEntry(
                id = mangaId,
                sourceId = com.folio.reader.manga.LOCAL_SOURCE_ID,
                sourceName = "Local manga",
                url = "My Series",
                title = "My Series",
                inLibrary = true,
                initialized = true,
            ),
            emitSyncEvent = false
        )
        mangaChapterRepo.replaceChapters(
            mangaId,
            listOf(
                MangaChapter(
                    id = "$mangaId/ch-1",
                    mangaId = mangaId,
                    url = "ch-1",
                    name = "Chapter 1",
                    updatedAt = Instant.fromEpochMilliseconds(1_000)
                ),
                MangaChapter(
                    id = "$mangaId/ch-2",
                    mangaId = mangaId,
                    url = "ch-2",
                    name = "Chapter 2",
                    updatedAt = Instant.fromEpochMilliseconds(9_000)
                )
            )
        )
        fake.mangaChapters.add(
            FsMangaChapter(
                id = "$mangaId/ch-1",
                mangaId = mangaId,
                url = "ch-1",
                name = "Chapter 1",
                read = true,
                lastPageRead = 12,
                totalPages = 20,
                updatedAt = 5_000,
                deviceId = "phone-device"
            )
        )
        // Chapter 2 carries local user state that is newer than the cloud row —
        // it must survive last-write-wins.
        mangaChapterRepo.saveProgress("$mangaId/ch-2", lastPage = 5, totalPages = 18, emitSyncEvent = false)
        fake.mangaChapters.add(
            FsMangaChapter(
                id = "$mangaId/ch-2",
                mangaId = mangaId,
                url = "ch-2",
                name = "Chapter 2",
                read = true,
                lastPageRead = 3,
                totalPages = 18,
                updatedAt = 2_000,
                deviceId = "phone-device"
            )
        )

        val adopted = engine.adoptCloudProgressForManga(mangaId)

        assertEquals(1, adopted)
        val ch1 = mangaChapterRepo.getChapter("$mangaId/ch-1")!!
        assertTrue(ch1.read)
        assertEquals(12, ch1.lastPageRead)
        assertEquals(20, ch1.totalPages)
        val ch2 = mangaChapterRepo.getChapter("$mangaId/ch-2")!!
        assertTrue(!ch2.read, "chapter with newer local user state keeps its state")
        assertEquals(5, ch2.lastPageRead)
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "adoption must not enqueue sync events")
    }
}
