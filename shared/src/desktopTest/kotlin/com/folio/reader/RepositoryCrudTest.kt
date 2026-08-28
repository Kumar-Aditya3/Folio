package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcCollectionRepository
import com.folio.reader.database.JdbcDeviceRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcSeriesRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.database.JdbcSyncQueueRepository
import com.folio.reader.database.Device
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.HighlightColor
import com.folio.reader.model.Note
import com.folio.reader.model.NoteType
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.Series
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.sync.SyncOperation
import com.folio.reader.sync.SyncQueueItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
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
 * CRUD roundtrips for every repository against a real temp-dir SQLite database.
 *
 * Covers (logic level):
 *  - P2-02  highlight/note/bookmark persistence incl. soft delete + restore
 *  - P1-08/P1-09  collections and series book membership
 *  - P1-16  position upsert per (book, device)
 *  - sync queue lifecycle and device registry
 */
class RepositoryCrudTest {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database

    private lateinit var books: JdbcBookRepository
    private lateinit var highlights: JdbcHighlightRepository
    private lateinit var notes: JdbcNoteRepository
    private lateinit var bookmarks: JdbcBookmarkRepository
    private lateinit var collections: JdbcCollectionRepository
    private lateinit var seriesRepo: JdbcSeriesRepository
    private lateinit var positions: JdbcReadingPositionRepository
    private lateinit var settingsRepo: JdbcSettingsRepository
    private lateinit var syncQueue: JdbcSyncQueueRepository
    private lateinit var devices: JdbcDeviceRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-crud-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        books = JdbcBookRepository(database)
        highlights = JdbcHighlightRepository(database)
        notes = JdbcNoteRepository(database)
        bookmarks = JdbcBookmarkRepository(database)
        collections = JdbcCollectionRepository(database)
        seriesRepo = JdbcSeriesRepository(database)
        positions = JdbcReadingPositionRepository(database)
        settingsRepo = JdbcSettingsRepository(database)
        syncQueue = JdbcSyncQueueRepository(database)
        devices = JdbcDeviceRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun testBook(id: String = "book-1") = Book(
        id = id,
        title = "Test Book",
        epubHash = "hash-$id",
        epubFileSize = 1234L
    )

    private fun testHighlight(id: String = "hl-1") = Highlight(
        id = id,
        bookId = "book-1",
        chapterId = "ch-2",
        spineIndex = 2,
        startLocator = "/2/4/1:0",
        endLocator = "/2/4/1:42",
        selectedText = "the quick brown fox",
        color = HighlightColor.TEAL,
        deviceId = "dev-a"
    )

    private suspend fun seedBook(id: String = "book-1") {
        books.insertBook(testBook(id))
    }

    @Test
    fun `highlight insert update soft delete restore roundtrip`() = runBlocking {
        seedBook()
        val hl = testHighlight()

        highlights.insertHighlight(hl)

        val loaded = highlights.getHighlight(hl.id)
        assertNotNull(loaded)
        assertEquals("the quick brown fox", loaded.selectedText)
        assertEquals(HighlightColor.TEAL.argb, loaded.effectiveColor)

        val recolored = loaded.copy(color = HighlightColor.PURPLE, updatedAt = Clock.System.now())
        highlights.updateHighlight(recolored)
        assertEquals(HighlightColor.PURPLE, highlights.getHighlight(hl.id)?.color)

        // visible flow excludes deleted rows
        highlights.deleteHighlight(hl.id)
        assertTrue(highlights.getHighlightsForBook("book-1").first().isEmpty())
        assertEquals(loaded.id, highlights.getDeletedHighlights("book-1").singleOrNull()?.id)

        highlights.restoreHighlight(hl.id)
        assertTrue(highlights.getHighlightsForBook("book-1").first().any { it.id == hl.id })
        assertFalse(highlights.getHighlight(hl.id)!!.isDeleted)
    }

    @Test
    fun `note insert update delete restore roundtrip`() = runBlocking {
        seedBook()
        val note = Note(
            id = "note-1",
            bookId = "book-1",
            chapterId = "ch-1",
            spineIndex = 1,
            locator = "/1/4/2:10",
            content = "Look this up later",
            type = NoteType.CHAPTER_NOTE,
            deviceId = "dev-a"
        )

        notes.insertNote(note)
        val loaded = notes.getNote(note.id)
        assertNotNull(loaded)
        assertEquals(NoteType.CHAPTER_NOTE, loaded.type)
        assertEquals("Look this up later", loaded.content)

        notes.updateNote(loaded.copy(content = "Updated content"))
        assertEquals("Updated content", notes.getNote(note.id)?.content)

        notes.deleteNote(note.id)
        assertTrue(notes.getNotesForBook("book-1").first().isEmpty())
        notes.restoreNote(note.id)
        assertTrue(notes.getNotesForBook("book-1").first().any { it.id == note.id })
    }

    @Test
    fun `bookmark insert delete restore roundtrip`() = runBlocking {
        seedBook()
        val bm = Bookmark(
            id = "bm-1",
            bookId = "book-1",
            chapterId = "ch-3",
            spineIndex = 3,
            locator = "/3/2/1:0",
            label = "Chapter 3 start",
            deviceId = "dev-b"
        )

        bookmarks.insertBookmark(bm)
        val loaded = bookmarks.getBookmark(bm.id)
        assertNotNull(loaded)
        assertEquals("Chapter 3 start", loaded.label)

        bookmarks.deleteBookmark(bm.id)
        assertTrue(bookmarks.getBookmarksForBook("book-1").first().isEmpty())
        assertEquals(1, bookmarks.getDeletedBookmarks("book-1").size)

        bookmarks.restoreBookmark(bm.id)
        assertEquals(listOf(bm.id), bookmarks.getBookmarksForBook("book-1").first().map { it.id })
    }

    @Test
    fun `collection membership add remove and cascade delete`() = runBlocking {
        val b1 = testBook("b1")
        val b2 = testBook("b2")
        books.insertBook(b1)
        books.insertBook(b2)

        val col = Collection(id = "col-1", name = "Favourites")
        collections.insertCollection(col)
        assertEquals(listOf("Favourites"), collections.getAllCollections().first().map { it.name })
        assertEquals(col.id, collections.getCollectionByName("favourites")?.id)

        collections.addBookToCollection("b1", "col-1")
        collections.addBookToCollection("b2", "col-1")

        val inCol = collections.getCollectionsForBook("b1").map { it.id }
        assertEquals(listOf("col-1"), inCol.filter { it == "col-1" })

        collections.removeBookFromCollection("b1", "col-1")
        assertFalse(collections.getCollectionsForBook("b1").any { it.id == "col-1" })
        assertTrue(collections.getCollectionsForBook("b2").any { it.id == "col-1" })

        collections.addBookToCollection("b1", "col-1")
        collections.deleteCollection("col-1")
        assertTrue(collections.getAllCollections().first().isEmpty())
        assertTrue(collections.getCollectionsForBook("b2").isEmpty())
    }

    @Test
    fun `series insert get and book association`() = runBlocking {
        val s = Series(id = "series-1", name = "The Expanse", sortOrder = 1)
        seriesRepo.insertSeries(s)

        val loaded = seriesRepo.getSeries(s.id)
        assertNotNull(loaded)
        assertEquals("The Expanse", loaded.name)
        assertEquals(s.id, seriesRepo.getSeriesByName("the expanse")?.id)

        val book = testBook("b-ex").copy(seriesId = s.id, seriesNumber = 3.0)
        books.insertBook(book)
        val inSeries = seriesRepo.getBooksInSeries(s.id).first()
        assertEquals(listOf("b-ex"), inSeries.map { it.id })

        seriesRepo.deleteSeries(s.id)
        assertNull(seriesRepo.getSeries(s.id))
    }

    @Test
    fun `position upsert keeps latest per book-device pair`() = runBlocking {
        seedBook()
        val base = ReadingPosition(
            bookId = "book-1",
            deviceId = "phone",
            chapterId = "c0",
            spineIndex = 0,
            contentLocator = "/0/2:5"
        )
        positions.upsertPosition(base)
        positions.upsertPosition(base.copy(spineIndex = 9, contentLocator = "/9/1:99"))

        val all = positions.getAllPositionsForBook("book-1")
        assertEquals(1, all.size, "upsert must replace the row for the same (book, device)")
        assertEquals(9, all.single().spineIndex)
    }

    @Test
    fun `settings key-value store roundtrip`() = runBlocking {
        database.setSettings("k1", "v1")
        assertEquals("v1", database.getSettings("k1"))
        database.setSettings("k1", "v2")
        assertEquals("v2", database.getSettings("k1"))
    }

    @Test
    fun `sync queue full lifecycle`() = runBlocking {
        val item = SyncQueueItem(
            id = "q1",
            entityType = "highlight",
            entityId = "hl-1",
            operation = SyncOperation.CREATE,
            payload = "{}",
            createdAt = Clock.System.now(),
            retryCount = 0,
            lastAttemptAt = null,
            status = com.folio.reader.sync.SyncStatus.PENDING
        )
        syncQueue.enqueueSync(item.entityType, item.entityId, item.operation, item.payload)

        val queuedId = syncQueue.getPendingSync(10).single().id
        assertEquals("highlight", syncQueue.getPendingSync(10).single().entityType)

        syncQueue.markSyncing(queuedId)
        assertTrue(syncQueue.getPendingSync(10).isEmpty(), "SYNCING items must not re-enqueue")

        syncQueue.markError(queuedId)
        val retried = syncQueue.getPendingSync(10)
        assertEquals(1, retried.size, "ERROR items are retried")

        syncQueue.markError(queuedId)
        val again = syncQueue.getPendingSync(10).single()
        assertTrue(again.retryCount >= 2, "retry count must increment, was ${again.retryCount}")

        syncQueue.clearSynced(Clock.System.now())
        assertEquals(1, syncQueue.getPendingSync(10).size, "clearSynced must not drop pending retries")

        syncQueue.markSyncing(queuedId)
        syncQueue.markSynced(queuedId)
        assertTrue(syncQueue.getPendingSync(10).isEmpty())
    }

    @Test
    fun `device registry upsert get deactivate`() = runBlocking {
        val device = Device(
            id = "dev-x",
            name = "Pixel",
            platform = "Android",
            appVersion = "1.0.0",
            lastSeenAt = Clock.System.now(),
            isCurrent = false,
            isActive = true
        )
        devices.upsertDevice(device)
        assertEquals("Pixel", devices.getDevice("dev-x")?.name)

        devices.deactivateDevice("dev-x")
        assertEquals(false, devices.getDevice("dev-x")?.isActive)

        val updated = device.copy(name = "Pixel 8", lastSeenAt = Clock.System.now())
        devices.upsertDevice(updated)
        assertEquals("Pixel 8", devices.getDevice("dev-x")?.name)
        assertEquals(1, devices.getAllDevices().first().size)
    }
}
