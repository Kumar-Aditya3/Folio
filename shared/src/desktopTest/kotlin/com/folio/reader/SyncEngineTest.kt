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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val settingsPayloads = mutableListOf<FsSettings>()

        /** Cursor of the most recent fetchSessions call (null = never called). */
        var lastSessionCursor: Long? = null
            private set

        var authenticated = false
            private set

        var isConnectedState: Boolean = true

        /** Artificial slowdown applied to pushes, for exit-path timing tests. */
        var pushDelayMs: Long = 0L

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
            if (conflictOnce) {
                conflictOnce = false
                throw java.io.IOException("HTTP 412 from PATCH: currentDocument.updateTime precondition failed")
            }
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

        override fun upsertSettings(settings: FsSettings) {
            settingsPayloads.add(settings)
        }

        override fun upsertCollection(collection: FsCollection) {
            if (pushDelayMs > 0) Thread.sleep(pushDelayMs)
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

        override fun fetchBooks(): List<FsBook> = books.toList()

        override fun fetchPositions(): List<FsReadingPosition> = positions.toList()

        override fun fetchPositionsForBook(bookId: String): List<FsReadingPosition> = positions.filter { it.bookId == bookId }

        override fun fetchHighlights(): List<FsHighlight> = highlights.toList()

        override fun fetchNotes(): List<FsNote> = notes.toList()

        override fun fetchBookmarks(): List<FsBookmark> = bookmarks.toList()

        override fun fetchSessions(sinceStartedAtMs: Long): List<FsReadingSession> {
            lastSessionCursor = sinceStartedAtMs
            return sessions.filter { it.startedAt > sinceStartedAtMs }
        }

        override fun fetchCollections(): List<FsCollection> = collections.toList()

        override fun fetchSeries(): List<FsSeries> = series.toList()

        override fun fetchTags(): List<FsTag> = tags.toList()

        override fun fetchQuotes(): List<FsQuote> = quotes.toList()

        override fun fetchRevisitItems(): List<FsRevisitItem> = revisitItems.toList()

        val manga = mutableListOf<FsManga>()
        val mangaChapters = mutableListOf<FsMangaChapter>()
        val mangaNotes = mutableListOf<FsMangaNote>()
        val mangaCategories = mutableListOf<FsMangaCategory>()

        override fun upsertManga(m: FsManga) {
            manga.removeAll { it.id == m.id }
            manga.add(m)
        }

        override fun upsertMangaChapter(chapter: FsMangaChapter) {
            mangaChapters.removeAll { it.id == chapter.id }
            mangaChapters.add(chapter)
        }

        override fun upsertMangaNote(note: FsMangaNote) {
            mangaNotes.removeAll { it.id == note.id }
            mangaNotes.add(note)
        }

        override fun upsertMangaCategory(category: FsMangaCategory) {
            mangaCategories.removeAll { it.id == category.id }
            mangaCategories.add(category)
        }

        override fun fetchManga(): List<FsManga> = manga.toList()

        override fun fetchMangaChapters(): List<FsMangaChapter> = mangaChapters.toList()

        override fun fetchMangaNotes(): List<FsMangaNote> = mangaNotes.toList()

        override fun fetchMangaCategories(): List<FsMangaCategory> = mangaCategories.toList()

        /** Settings document served by fetchSettings (null = none in the cloud). */
        var remoteSettings: FsSettings? = null

        /** When true, the next highlight push fails like a Firestore 412 precondition. */
        var conflictOnce = false

        override fun fetchSettings(): FsSettings? = remoteSettings
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

    private fun makeEngine(fake: FakeFirestoreSync, syncRepo: com.folio.reader.database.SyncRepository = syncQueueRepo): SyncEngine = SyncEngine(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        syncRepository = syncRepo,
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

    /** Polls until [condition] holds or fails the test after [timeoutMs]. */
    private suspend fun awaitTrue(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            kotlinx.coroutines.delay(50)
        }
        assertTrue(condition(), message)
    }

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
        // The one-time backfill queued this device's stale LIVE copy of the same id.
        // A blind push would have uploaded it over the fresher tombstone, resurrecting
        // the annotation in the cloud; the push must yield to the newer remote state.
        assertTrue(
            fake.highlights.single().isDeleted,
            "stale backfilled copy must not clobber the remote tombstone"
        )
    }

    @Test
    fun `delete made elsewhere reaches the device that created the annotation`() = runBlocking {
        // Regression for the user-visible bug. deviceId is the permanent creator, so a
        // delete applied on another device arrives as a tombstone still naming THIS
        // device. The old transport filtered out documents whose deviceId equalled this
        // device's, so the tombstone was discarded and the delete never propagated. This
        // device must fetch it, soft-delete its own copy, and not re-push the stale copy.
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        val local = Highlight(
            id = "hl-cross",
            bookId = book.id,
            chapterId = "c1",
            spineIndex = 1,
            startLocator = "/1/1:0",
            endLocator = "/1/1:9",
            selectedText = "created here, deleted elsewhere",
            color = HighlightColor.GREEN,
            deviceId = selfDeviceId,
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
                deviceId = selfDeviceId,
                isDeleted = true
            )
        )

        engine.syncOnce()

        assertTrue(
            highlightRepo.getHighlightsForBook(book.id).first().isEmpty(),
            "creating device must drop an annotation deleted elsewhere"
        )
        assertEquals(
            local.id,
            highlightRepo.getDeletedHighlights(book.id).singleOrNull()?.id,
            "creating device must record the delete as a soft delete"
        )
        assertTrue(
            fake.highlights.single().isDeleted,
            "creating device must not resurrect the tombstone with its stale copy"
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
    fun `own device entries converge with the cloud like any other`() = runBlocking {
        // The old transport filtered out documents created by this device, so a
        // tombstone for one of this device's own annotations could never arrive —
        // deletions and edits made elsewhere never propagated. Documents are now
        // fetched regardless of creator; convergence is decided by updatedAt, and a
        // locally-missing copy of our own document is restored from the cloud.
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
        val restored = highlightRepo.getHighlightsForBook(book.id).first()
        assertEquals("hl-mine", restored.singleOrNull()?.id, "own cloud document must be restored, not filtered out")
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "restoring a remote document must not re-enqueue it")
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
        awaitTrue("pending sync must push on app open") {
            fake.collections.any { it.id == "col-open" }
        }
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
        awaitTrue("pending sync must push on app close") {
            fake.collections.any { it.id == "col-close" }
        }
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
        kotlinx.coroutines.delay(300)

        assertTrue(fake.collections.none { it.id == "col-close-offline" }, "Sync must not run on close when disconnected")
    }

    @Test
    fun `exit sync does not block the caller`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        fake.pushDelayMs = 2_000
        val engine = makeEngine(fake)

        collectionRepo.insertCollection(Collection(id = "col-slow", name = "Slow Push"))
        syncQueueRepo.enqueueSync(
            "collection",
            "col-slow",
            SyncOperation.CREATE,
            Json.encodeToString(Collection.serializer(), Collection(id = "col-slow", name = "Slow Push"))
        )

        fake.isConnectedState = true
        val start = System.currentTimeMillis()
        engine.syncOnAppClose()
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 800, "exit sync must not block the UI thread; took ${elapsed}ms")
        awaitTrue("slow push must still complete in the background") {
            fake.collections.any { it.id == "col-slow" }
        }
    }

    @Test
    fun `settings push strips credentials from the cloud payload`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val seeded = ReaderSettings(
            fontSize = 22f,
            firebaseApiKey = "AIzaSECRETKEY",
            firebaseProjectId = "test-project",
            syncAccountEmail = "reader@example.com",
            syncAccountPassword = "hunter2",
            cloudSyncEnabled = true
        )
        settingsRepo.saveGlobalSettings(seeded)
        syncQueueRepo.enqueueSync(
            "settings",
            "global",
            SyncOperation.UPSERT,
            Json.encodeToString(ReaderSettings.serializer(), seeded)
        )

        engine.syncOnce()

        assertEquals(1, fake.settingsPayloads.size, "settings must still sync")
        val global = fake.settingsPayloads.single().global
        assertFalse(global.contains("hunter2"), "password must never reach the cloud")
        assertFalse(global.contains("AIzaSECRETKEY"), "API key must never reach the cloud")
        assertFalse(global.contains("reader@example.com"), "account email must never reach the cloud")
        assertTrue(global.contains("fontSize"), "reading preferences themselves must still sync")
    }

    @Test
    fun `settings are not pushed when settings sync is disabled`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        settingsRepo.saveGlobalSettings(
            ReaderSettings(
                firebaseApiKey = "k",
                firebaseProjectId = "test-project",
                cloudSyncEnabled = true,
                syncSettings = false
            )
        )
        syncQueueRepo.enqueueSync(
            "settings",
            "global",
            SyncOperation.UPSERT,
            Json.encodeToString(ReaderSettings.serializer(), ReaderSettings())
        )

        engine.syncOnce()

        assertTrue(fake.settingsPayloads.isEmpty(), "syncSettings=false must stop the settings push")
    }

    @Test
    fun `sessions fetch uses incremental cursor from known remote history`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)
        val book = seedBook()
        bookRepo.insertBook(book)
        val now = Clock.System.now().toEpochMilliseconds()

        // A session already applied from another device anchors the cursor.
        sessionRepo.insertSession(
            com.folio.reader.model.ReadingSession(
                id = "known-other",
                bookId = book.id,
                cycleId = null,
                deviceId = "phone-2",
                startedAt = Instant.fromEpochMilliseconds(now - 3_600_000L),
                endedAt = Instant.fromEpochMilliseconds(now - 3_000_000L),
                durationMs = 600_000L,
                startPosition = com.folio.reader.model.ReadingPosition(
                    bookId = book.id, deviceId = "phone-2", chapterId = "c0",
                    spineIndex = 0, contentLocator = ""
                ),
                isActive = false
            ),
            emitSyncEvent = false
        )

        val positionJson = Json.encodeToString(
            com.folio.reader.model.ReadingPosition.serializer(),
            com.folio.reader.model.ReadingPosition(
                bookId = book.id, deviceId = "phone-2", chapterId = "c0",
                spineIndex = 0, contentLocator = ""
            )
        )
        // One session far older than the skew margin, one brand new.
        fake.sessions.add(
            FsReadingSession(
                id = "old-one", bookId = book.id, deviceId = "phone-2",
                startedAt = now - 30L * 3_600_000L, endedAt = now - 30L * 3_600_000L + 60_000L,
                durationMs = 60_000L, startPosition = positionJson, isActive = false
            )
        )
        fake.sessions.add(
            FsReadingSession(
                id = "new-one", bookId = book.id, deviceId = "phone-2",
                startedAt = now + 60_000L, endedAt = now + 120_000L,
                durationMs = 60_000L, startPosition = positionJson, isActive = false
            )
        )

        engine.syncOnce()

        val cursor = fake.lastSessionCursor
        assertNotNull(cursor, "engine must pass an incremental cursor")
        assertEquals(
            now - 3_600_000L - 24L * 3_600_000L,
            cursor,
            "cursor = newest known remote session minus the skew margin"
        )
        val applied = sessionRepo.getSessionsForBook(book.id).first().map { it.id }
        assertTrue("new-one" in applied, "session newer than the cursor must be applied")
        assertTrue("old-one" !in applied, "session older than the cursor must not be re-fetched")
    }

    /** Fails the first enqueue of one entity type, then behaves like the delegate. */
    private class FlakyEnqueue(
        private val delegate: com.folio.reader.database.SyncRepository,
        private val failEntityType: String
    ) : com.folio.reader.database.SyncRepository {
        var failOnce = true

        override suspend fun enqueueSync(
            entityType: String,
            entityId: String,
            operation: SyncOperation,
            payload: String
        ) {
            if (failOnce && entityType == failEntityType) {
                failOnce = false
                throw java.io.IOException("simulated enqueue failure")
            }
            delegate.enqueueSync(entityType, entityId, operation, payload)
        }

        override suspend fun getPendingSync(limit: Int) = delegate.getPendingSync(limit)
        override suspend fun getPendingSyncCount() = delegate.getPendingSyncCount()
        override suspend fun recoverStaleSyncing(before: Instant) = delegate.recoverStaleSyncing(before)
        override suspend fun markSyncing(id: String) = delegate.markSyncing(id)
        override suspend fun markSynced(id: String) = delegate.markSynced(id)
        override suspend fun markError(id: String) = delegate.markError(id)
        override suspend fun clearSynced(before: Instant) = delegate.clearSynced(before)
        override fun getSyncState() = delegate.getSyncState()
        override suspend fun updateSyncState(state: com.folio.reader.sync.SyncState) =
            delegate.updateSyncState(state)
    }

    @Test
    fun `backfill retries when the first attempt fails`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake, FlakyEnqueue(syncQueueRepo, "highlight"))

        val book = seedBook()
        bookRepo.insertBook(book)
        highlightRepo.insertHighlight(
            Highlight(
                id = "hl-backfill",
                bookId = book.id,
                chapterId = "c1",
                spineIndex = 1,
                startLocator = "/1/1:0",
                endLocator = "/1/1:9",
                selectedText = "pre-sync highlight",
                color = HighlightColor.YELLOW,
                deviceId = selfDeviceId
            ),
            emitSyncEvent = false
        )

        engine.syncOnce()
        assertNull(
            settingsRepo.getRaw("annotations_backfilled_at"),
            "a failed backfill must not stamp the done flag"
        )

        engine.syncOnce()
        assertNotNull(
            settingsRepo.getRaw("annotations_backfilled_at"),
            "retried backfill must stamp the done flag once it succeeds"
        )
        assertTrue(
            fake.highlights.any { it.id == "hl-backfill" },
            "retried backfill must reach the cloud"
        )
    }

    @Test
    fun `book deletion pushes a tombstone to the cloud`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)
        engine.syncOnce()
        assertTrue(fake.books.any { it.id == book.id && !it.isDeleted }, "book must sync first")

        bookRepo.deleteBook(book.id)
        // Production wires Database.onEntityChanged to the outbox; the test DB does
        // not, so queue the delete the same way the app would.
        syncQueueRepo.enqueueSync("book", book.id, SyncOperation.DELETE, "{}")
        engine.syncOnce()

        val tombstone = fake.books.single { it.id == book.id }
        assertTrue(tombstone.isDeleted, "deletion must propagate as a tombstone")
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "tombstone push must drain the queue")
    }

    @Test
    fun `remote tombstone deletes the local book without echo`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)

        fake.books.add(
            FsBook(
                id = book.id,
                title = "",
                epubHash = book.epubHash,
                epubFileSize = 0,
                addedAt = 0,
                updatedAt = Clock.System.now().toEpochMilliseconds() + 60_000L,
                deviceId = "tablet-1",
                isDeleted = true
            )
        )

        engine.syncOnce()

        assertNull(bookRepo.getBook(book.id), "remote tombstone must remove the local book")
        assertTrue(
            syncQueueRepo.getPendingSync(10).isEmpty(),
            "applying a tombstone must not re-enqueue a delete"
        )
    }

    @Test
    fun `remote settings apply once and keep local credentials`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        settingsRepo.saveGlobalSettings(
            ReaderSettings(
                fontSize = 18f,
                firebaseApiKey = "LOCAL-KEY",
                firebaseProjectId = "local-project",
                syncAccountEmail = "local@example.com",
                syncAccountPassword = "local-pass",
                cloudSyncEnabled = true
            )
        )

        val remotePrefs = ReaderSettings(fontSize = 21f, lineHeight = 2.2f)
        fake.remoteSettings = FsSettings(
            userId = "",
            global = Json.encodeToString(ReaderSettings.serializer(), remotePrefs),
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            deviceId = "tablet-1"
        )

        engine.syncOnce()

        val applied = settingsRepo.getGlobalSettings()
        assertEquals(21f, applied.fontSize, "remote reading preferences must apply")
        assertEquals(2.2f, applied.lineHeight)
        assertEquals("LOCAL-KEY", applied.firebaseApiKey, "local credentials must be preserved")
        assertEquals("local-pass", applied.syncAccountPassword)

        // A second cycle must not re-apply or echo the same document.
        val appliedAt = settingsRepo.getRaw("remote_settings_applied_at")
        assertNotNull(appliedAt, "applied watermark must be recorded")
        fake.settingsPayloads.clear()
        engine.syncOnce()
        assertTrue(
            fake.settingsPayloads.isEmpty(),
            "an applied settings document must never be echoed back to the cloud"
        )
        assertEquals(appliedAt, settingsRepo.getRaw("remote_settings_applied_at"))
    }

    @Test
    fun `settings apply is skipped when settings sync is disabled`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        settingsRepo.saveGlobalSettings(ReaderSettings(fontSize = 18f, syncSettings = false))
        fake.remoteSettings = FsSettings(
            userId = "",
            global = Json.encodeToString(ReaderSettings.serializer(), ReaderSettings(fontSize = 21f)),
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            deviceId = "tablet-1"
        )

        engine.syncOnce()

        assertEquals(18f, settingsRepo.getGlobalSettings().fontSize, "syncSettings=false must block apply")
    }

    @Test
    fun `tag and collection edits converge by updatedAt`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val older = Clock.System.now().minus(kotlin.time.Duration.parse("PT2H")).toEpochMilliseconds()
        val newer = Clock.System.now().plus(kotlin.time.Duration.parse("PT1H")).toEpochMilliseconds()

        tagRepo.insertTag(
            com.folio.reader.model.Tag(
                id = "tag-1", name = "Old name", createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(older),
                updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(older)
            ),
            emitSyncEvent = false
        )
        collectionRepo.insertCollection(
            com.folio.reader.model.Collection(
                id = "col-1", name = "Old collection",
                createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(older),
                updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(older)
            ),
            emitSyncEvent = false
        )

        fake.tags.add(
            FsTag(id = "tag-1", name = "Renamed elsewhere", color = null, createdAt = older, deviceId = "tablet-1", updatedAt = newer)
        )
        fake.collections.add(
            FsCollection(id = "col-1", name = "Renamed collection", color = null, sortOrder = 0, createdAt = older, deviceId = "tablet-1", updatedAt = newer)
        )

        engine.syncOnce()

        assertEquals("Renamed elsewhere", tagRepo.getAllTags().first().single { it.id == "tag-1" }.name)
        assertEquals("Renamed collection", collectionRepo.getAllCollections().first().single { it.id == "col-1" }.name)
        assertTrue(
            syncQueueRepo.getPendingSync(10).none { it.entityType == "tag" || it.entityType == "collection" },
            "remote-applied edits must not echo into the outbox"
        )
    }

    @Test
    fun `a conflicted push recovers from the fresher remote copy`() = runBlocking {
        val fake = FakeFirestoreSync(selfDeviceId)
        val engine = makeEngine(fake)

        val book = seedBook()
        bookRepo.insertBook(book)
        val stale = com.folio.reader.model.Highlight(
            id = "hl-conflict",
            bookId = book.id,
            chapterId = "c1",
            spineIndex = 1,
            startLocator = "/1/1:0",
            endLocator = "/1/1:9",
            selectedText = "stale local text",
            color = HighlightColor.YELLOW,
            deviceId = selfDeviceId,
            updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(
                Clock.System.now().minus(kotlin.time.Duration.parse("PT2H")).toEpochMilliseconds()
            )
        )
        highlightRepo.insertHighlight(stale, emitSyncEvent = false)
        syncQueueRepo.enqueueSync(
            "highlight", stale.id, SyncOperation.UPSERT,
            Json.encodeToString(com.folio.reader.model.Highlight.serializer(), stale)
        )

        // The cloud already holds a fresher version of the same highlight.
        val freshMs = Clock.System.now().toEpochMilliseconds()
        fake.highlights.add(
            FsHighlight(
                id = stale.id, bookId = book.id, chapterId = "c1", spineIndex = 1,
                startLocator = "/1/1:0", endLocator = "/1/1:9",
                selectedText = "fresher remote text",
                createdAt = stale.createdAt.toEpochMilliseconds(),
                updatedAt = freshMs,
                deviceId = selfDeviceId
            )
        )
        fake.conflictOnce = true

        engine.syncOnce()

        // The 412-style failure marks the item errored; the fresher remote copy was
        // applied locally in the same cycle.
        assertEquals("fresher remote text", highlightRepo.getHighlight(stale.id)?.selectedText)

        // Next cycle: the retried push sees the fresher remote and yields, draining
        // the queue without resurrecting the stale text.
        engine.syncOnce()
        assertTrue(syncQueueRepo.getPendingSync(10).isEmpty(), "stale push must drain after convergence")
        assertEquals("fresher remote text", fake.highlights.single { it.id == stale.id }.selectedText)
    }
}
