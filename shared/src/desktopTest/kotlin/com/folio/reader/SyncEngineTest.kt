package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcDeviceRepository
import com.folio.reader.database.JdbcHighlightRepository
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
import com.folio.reader.firebase.FsNote
import com.folio.reader.firebase.FsQuote
import com.folio.reader.firebase.FsReadingPosition
import com.folio.reader.firebase.FsReadingSession
import com.folio.reader.firebase.FsRevisitItem
import com.folio.reader.firebase.FsSeries
import com.folio.reader.firebase.FsSettings
import com.folio.reader.firebase.FsTag
import com.folio.reader.model.Book
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.HighlightColor
import com.folio.reader.model.ReadingPosition
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.sync.FirestoreSync
import com.folio.reader.sync.NoopStorageSync
import com.folio.reader.sync.SyncConfig
import com.folio.reader.sync.SyncEngine
import com.folio.reader.sync.SyncOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end sync engine tests against the real SQLite repositories plus an
 * in-memory fake Firestore transport.
 *
 * Covers (logic level):
 *  - P4-03  queued local changes are pushed to the transport
 *  - P4-05  remote positions/annotations merge with last-write-wins semantics
 *  - P4-07  remote deletes propagate as soft deletes
 */
class SyncEngineTest {

    private class FakeFirestoreSync(
        private val selfDeviceId: String
    ) : FirestoreSync {
        val books = mutableListOf<FsBook>()
        val positions = mutableListOf<FsReadingPosition>()
        val highlights = mutableListOf<FsHighlight>()
        val notes = mutableListOf<FsNote>()
        val bookmarks = mutableListOf<FsBookmark>()
        val sessions = mutableListOf<FsReadingSession>()
        val collections = mutableListOf<FsCollection>()
        val series = mutableListOf<FsSeries>()
        val tags = mutableListOf<FsTag>()
        val quotes = mutableListOf<FsQuote>()
        val revisitItems = mutableListOf<FsRevisitItem>()

        var authenticated = false
            private set

        var isConnectedState: Boolean = true

        override fun isConnected(): Boolean = isConnectedState

        override fun authenticate() {
            authenticated = true
        }

        override fun upsertBook(book: FsBook) {
            books.removeAll { it.id == book.id }
            books.add(book)
        }

        override fun upsertPosition(position: FsReadingPosition) {
            positions.removeAll { it.bookId == position.bookId && it.deviceId == position.deviceId }
            positions.add(position)
        }

        override fun upsertHighlight(highlight: FsHighlight) {
            highlights.removeAll { it.id == highlight.id }
            highlights.add(highlight)
        }

        override fun upsertNote(note: FsNote) {
            notes.removeAll { it.id == note.id }
            notes.add(note)
        }

        override fun upsertBookmark(bookmark: FsBookmark) {
            bookmarks.removeAll { it.id == bookmark.id }
            bookmarks.add(bookmark)
        }

        override fun upsertSession(session: FsReadingSession) {
            sessions.removeAll { it.id == session.id }
            sessions.add(session)
        }

        override fun upsertSettings(settings: FsSettings) = Unit

        override fun upsertCollection(collection: FsCollection) {
            collections.removeAll { it.id == collection.id }
            collections.add(collection)
        }

        override fun upsertSeries(series: FsSeries) {
            this.series.removeAll { it.id == series.id }
            this.series.add(series)
        }

        override fun upsertTag(tag: FsTag) {
            tags.removeAll { it.id == tag.id }
            tags.add(tag)
        }

        override fun upsertQuote(quote: FsQuote) {
            quotes.removeAll { it.id == quote.id }
            quotes.add(quote)
        }

        override fun upsertRevisitItem(item: FsRevisitItem) {
            revisitItems.removeAll { it.id == item.id }
            revisitItems.add(item)
        }

        override fun fetchBooks(excludeDeviceId: String): List<FsBook> =
            books.filter { it.deviceId != excludeDeviceId }

        override fun fetchPositions(excludeDeviceId: String): List<FsReadingPosition> =
            positions.filter { it.deviceId != excludeDeviceId }

        override fun fetchHighlights(excludeDeviceId: String): List<FsHighlight> =
            highlights.filter { it.deviceId != excludeDeviceId }

        override fun fetchNotes(excludeDeviceId: String): List<FsNote> =
            notes.filter { it.deviceId != excludeDeviceId }

        override fun fetchBookmarks(excludeDeviceId: String): List<FsBookmark> =
            bookmarks.filter { it.deviceId != excludeDeviceId }

        override fun fetchSessions(excludeDeviceId: String): List<FsReadingSession> =
            sessions.filter { it.deviceId != excludeDeviceId }

        override fun fetchSettings(excludeDeviceId: String): FsSettings? = null

        override fun fetchCollections(excludeDeviceId: String): List<FsCollection> =
            collections.filter { it.deviceId != excludeDeviceId }

        override fun fetchSeries(excludeDeviceId: String): List<FsSeries> =
            series.filter { it.deviceId != excludeDeviceId }

        override fun fetchTags(excludeDeviceId: String): List<FsTag> =
            tags.filter { it.deviceId != excludeDeviceId }

        override fun fetchQuotes(excludeDeviceId: String): List<FsQuote> =
            quotes.filter { it.deviceId != excludeDeviceId }

        override fun fetchRevisitItems(excludeDeviceId: String): List<FsRevisitItem> =
            revisitItems.filter { it.deviceId != excludeDeviceId }
    }

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var bookRepo: JdbcBookRepository
    private lateinit var positionRepo: JdbcReadingPositionRepository
    private lateinit var highlightRepo: JdbcHighlightRepository
    private lateinit var noteRepo: JdbcNoteRepository
    private lateinit var bookmarkRepo: JdbcBookmarkRepository
    private lateinit var sessionRepo: JdbcReadingSessionRepository
    private lateinit var settingsRepo: JdbcSettingsRepository
    private lateinit var syncQueueRepo: JdbcSyncQueueRepository
    private lateinit var deviceRepo: JdbcDeviceRepository
    private lateinit var collectionRepo: JdbcCollectionRepository
    private lateinit var seriesRepo: JdbcSeriesRepository
    private lateinit var tagRepo: JdbcTagRepository
    private lateinit var quoteRepo: JdbcQuoteRepository
    private lateinit var revisitRepo: JdbcRevisitRepository

    private val selfDeviceId = "self-device"

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-sync-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        bookRepo = JdbcBookRepository(database)
        positionRepo = JdbcReadingPositionRepository(database)
        highlightRepo = JdbcHighlightRepository(database)
        noteRepo = JdbcNoteRepository(database)
        bookmarkRepo = JdbcBookmarkRepository(database)
        sessionRepo = JdbcReadingSessionRepository(database)
        settingsRepo = JdbcSettingsRepository(database)
        syncQueueRepo = JdbcSyncQueueRepository(database)
        deviceRepo = JdbcDeviceRepository(database)
        collectionRepo = JdbcCollectionRepository(database)
        seriesRepo = JdbcSeriesRepository(database)
        tagRepo = JdbcTagRepository(database)
        quoteRepo = JdbcQuoteRepository(database)
        revisitRepo = JdbcRevisitRepository(database)
        // Seed credentials so performSync() credential gate passes — mirrors a user who has
        // entered Firebase API key + project ID in Settings > Advanced.
        runBlocking {
            settingsRepo.saveGlobalSettings(
                com.folio.reader.settings.ReaderSettings(
                    firebaseApiKey = "test-api-key",
                    firebaseProjectId = "test-project",
                    cloudSyncEnabled = true
                )
            )
        }
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun makeEngine(fake: FakeFirestoreSync): SyncEngine = SyncEngine(
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
        config = SyncConfig(autoSync = true, syncIntervalMinutes = 1),
        deviceId = selfDeviceId
    )

    private fun seedBook(id: String = "book-1"): Book = Book(
        id = id,
        title = "Sync Book",
        epubHash = "hash-$id",
        epubFileSize = 10L
    )

    @Test
    fun `queued local changes are pushed to the transport`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        val highlight = Highlight(
            id = "hl-push",
            bookId = book.id,
            chapterId = "c1",
            spineIndex = 1,
            startLocator = "/1/1:0",
            endLocator = "/1/1:9",
            selectedText = "push me",
            color = HighlightColor.BLUE,
            deviceId = selfDeviceId
        )
        highlightRepo.insertHighlight(highlight)
        syncQueueRepo.enqueueSync(
            "highlight",
            highlight.id,
            SyncOperation.CREATE,
            Json.encodeToString(Highlight.serializer(), highlight)
        )

        engine.syncOnce()

        assertTrue(fake.authenticated, "engine must authenticate before pushing")
        assertEquals(1, fake.highlights.size, "queued highlight must be uploaded")
        assertEquals("push me", fake.highlights.single().selectedText)
        assertEquals(HighlightColor.BLUE.argb, fake.highlights.single().color)
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "queue must be drained after push")
    }

    @Test
    fun `remote position wins over stale local copy - lww`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        // Stale local copy of phone-2's position
        positionRepo.upsertPosition(
            ReadingPosition(
                bookId = book.id,
                deviceId = "phone-2",
                chapterId = "c0",
                spineIndex = 0,
                contentLocator = "/0/1:0",
                normalizedProgress = 0.1,
                updatedAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 3_600_000L)
            )
        )

        // Newer remote version
        fake.positions.add(
            FsReadingPosition(
                bookId = book.id,
                deviceId = "phone-2",
                chapterId = "c5",
                spineIndex = 5,
                contentLocator = "/5/2:77",
                normalizedProgress = 0.66,
                updatedAt = Clock.System.now().toEpochMilliseconds()
            )
        )

        engine.syncOnce()

        val merged = positionRepo.getPosition(book.id, "phone-2")
        assertNotNull(merged)
        assertEquals(5, merged.spineIndex, "newer remote position must overwrite stale local one")
        assertEquals(0.66, merged.normalizedProgress, 0.0001)

        val updatedBook = bookRepo.getBook(book.id)
        assertNotNull(updatedBook)
        assertEquals(0.66, updatedBook.normalizedProgress, 0.0001, "Book normalizedProgress must update on remote position pull")
    }

    @Test
    fun `local position newer than remote survives - lww`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        val freshLocal = ReadingPosition(
            bookId = book.id,
            deviceId = "phone-2",
            chapterId = "c9",
            spineIndex = 9,
            contentLocator = "/9/1:0",
            updatedAt = Clock.System.now()
        )
        positionRepo.upsertPosition(freshLocal)

        fake.positions.add(
            FsReadingPosition(
                bookId = book.id,
                deviceId = "phone-2",
                chapterId = "c0",
                spineIndex = 0,
                contentLocator = "/0/1:0",
                updatedAt = Clock.System.now().toEpochMilliseconds() - 60_000L
            )
        )

        engine.syncOnce()

        val kept = positionRepo.getPosition(book.id, "phone-2")
        assertNotNull(kept)
        assertEquals(9, kept.spineIndex, "fresh local position must not be overwritten by stale remote")
    }

    @Test
    fun `remote delete propagates as soft delete`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        val local = Highlight(
            id = "hl-del",
            bookId = book.id,
            chapterId = "c1",
            spineIndex = 1,
            startLocator = "/1/1:0",
            endLocator = "/1/1:9",
            selectedText = "delete me remotely",
            color = HighlightColor.YELLOW,
            deviceId = "tablet-1",
            updatedAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 7_200_000L)
        )
        highlightRepo.insertHighlight(local)

        fake.highlights.add(
            FsHighlight(
                id = local.id,
                bookId = local.bookId,
                chapterId = local.chapterId,
                spineIndex = local.spineIndex,
                startLocator = local.startLocator,
                endLocator = local.endLocator,
                selectedText = local.selectedText,
                createdAt = local.createdAt.toEpochMilliseconds(),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                deviceId = "tablet-1",
                isDeleted = true
            )
        )

        engine.syncOnce()

        assertTrue(highlightRepo.getHighlightsForBook(book.id).first().isEmpty())
        assertEquals(
            local.id,
            highlightRepo.getDeletedHighlights(book.id).singleOrNull()?.id,
            "remote delete must surface as local soft delete"
        )
    }

    @Test
    fun `remote changes do not re-enter the upload queue`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)
        val book = seedBook()
        bookRepo.insertBook(book)
        fake.highlights.add(
            FsHighlight(
                id = "remote-only",
                bookId = book.id,
                chapterId = "c1",
                spineIndex = 1,
                startLocator = "/1/1:0",
                endLocator = "/1/1:9",
                selectedText = "remote",
                createdAt = Clock.System.now().toEpochMilliseconds(),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                deviceId = "tablet-1"
            )
        )

        engine.syncOnce()

        assertNotNull(highlightRepo.getHighlight("remote-only"))
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "remote writes must not be uploaded again")
    }

    @Test
    fun `own device entries are not re-downloaded`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        fake.highlights.add(
            FsHighlight(
                id = "hl-mine",
                bookId = book.id,
                chapterId = "c1",
                spineIndex = 1,
                startLocator = "/1/1:0",
                endLocator = "/1/1:9",
                selectedText = "mine",
                createdAt = Clock.System.now().toEpochMilliseconds(),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                deviceId = selfDeviceId
            )
        )

        engine.syncOnce()
        assertTrue(highlightRepo.getHighlightsForBook(book.id).first().isEmpty())
    }

    @Test
    fun `collections and quotes push and merge across devices`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        // Push side: a locally created collection reaches the transport
        collectionRepo.insertCollection(Collection(id = "col-9", name = "Vacation reads"))
        syncQueueRepo.enqueueSync(
            "collection",
            "col-9",
            SyncOperation.CREATE,
            Json.encodeToString(Collection.serializer(), Collection(id = "col-9", name = "Vacation reads"))
        )

        // Pull side: another device shares a quote we do not have yet
        val book = seedBook("book-q")
        bookRepo.insertBook(book)
        fake.quotes.add(
            com.folio.reader.firebase.FsQuote(
                id = "quote-remote",
                bookId = book.id,
                chapterId = "c2",
                highlightId = "hl-x",
                text = "So we beat on, boats against the current.",
                createdAt = Clock.System.now().toEpochMilliseconds(),
                deviceId = "tablet-2"
            )
        )

        engine.syncOnce()

        assertTrue(fake.collections.any { it.id == "col-9" && it.name == "Vacation reads" }, "collection must be pushed")
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "queue drained")
        assertEquals(
            1,
            quoteRepo.getAllQuotes().first().size,
            "remote quote must merge into local database"
        )
        assertEquals("So we beat on, boats against the current.", quoteRepo.getAllQuotes().first().single().text)
    }

    @Test
    fun `isApiConnected reflects transport connectivity status`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        fake.isConnectedState = true
        assertTrue(engine.isApiConnected(), "Should return true when transport is connected")

        fake.isConnectedState = false
        assertTrue(!engine.isApiConnected(), "Should return false when transport is disconnected")
    }

    @Test
    fun `syncOnAppOpen executes background sync when API is connected`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        collectionRepo.insertCollection(Collection(id = "col-open", name = "Open Sync Reads"))
        syncQueueRepo.enqueueSync(
            "collection",
            "col-open",
            SyncOperation.CREATE,
            Json.encodeToString(Collection.serializer(), Collection(id = "col-open", name = "Open Sync Reads"))
        )

        fake.isConnectedState = true
        engine.syncOnAppOpen()
        kotlinx.coroutines.delay(200)

        assertTrue(fake.collections.any { it.id == "col-open" }, "Pending sync must push on app open")
    }

    @Test
    fun `syncOnAppOpen skips sync when API is disconnected`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        collectionRepo.insertCollection(Collection(id = "col-open-offline", name = "Offline Reads"))
        syncQueueRepo.enqueueSync(
            "collection",
            "col-open-offline",
            SyncOperation.CREATE,
            Json.encodeToString(Collection.serializer(), Collection(id = "col-open-offline", name = "Offline Reads"))
        )

        fake.isConnectedState = false
        engine.syncOnAppOpen()
        kotlinx.coroutines.delay(200)

        assertTrue(fake.collections.none { it.id == "col-open-offline" }, "Sync must not run when disconnected")
    }

    @Test
    fun `syncOnAppClose executes sync when API is connected`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        collectionRepo.insertCollection(Collection(id = "col-close", name = "Close Sync Reads"))
        syncQueueRepo.enqueueSync(
            "collection",
            "col-close",
            SyncOperation.CREATE,
            Json.encodeToString(Collection.serializer(), Collection(id = "col-close", name = "Close Sync Reads"))
        )

        fake.isConnectedState = true
        engine.syncOnAppClose()

        assertTrue(fake.collections.any { it.id == "col-close" }, "Pending sync must push on app close")
    }

    @Test
    fun `syncOnAppClose skips sync when API is disconnected`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        collectionRepo.insertCollection(Collection(id = "col-close-offline", name = "Offline Close"))
        syncQueueRepo.enqueueSync(
            "collection",
            "col-close-offline",
            SyncOperation.CREATE,
            Json.encodeToString(Collection.serializer(), Collection(id = "col-close-offline", name = "Offline Close"))
        )

        fake.isConnectedState = false
        engine.syncOnAppClose()

        assertTrue(fake.collections.none { it.id == "col-close-offline" }, "Sync must not run on close when disconnected")
    }
}
