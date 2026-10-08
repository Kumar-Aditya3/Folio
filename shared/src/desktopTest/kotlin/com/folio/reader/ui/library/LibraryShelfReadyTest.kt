package com.folio.reader.ui.library

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.SeriesRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The books shelf's "category bleed on a fresh start", as a contract.
 *
 * `selectedCollectionId` opens `null`, and a null selection means "no membership
 * filter" — so a shelf drawn before the remembered collection resolves shows the
 * *whole* library (every collection's books) and then snaps to the collection the
 * reader was last in. The host gates on [LibraryViewModel.shelfReady] to hold the
 * skeleton across that window; these pin the shape of that signal and the fact that
 * once it settles the shelf shows exactly one collection. Mirrors the manga shelf's
 * `categoryReady` tests.
 */
class LibraryShelfReadyTest {

    private fun book(id: String) =
        Book(id = id, title = "Title $id", epubHash = "hash-$id", epubFileSize = 1024L)

    private fun viewModel(
        books: List<Book>,
        collections: List<Collection>,
        membership: Map<String, Set<String>>,
        remembered: String? = null,
        /** Production always has one (Main, seeded by `ensureSeeded`); returning null hid the race. */
        default: Collection? = null,
        /** How long the collections table takes to answer. 0 makes the read synchronous-ish and
         *  hides the cold-start window entirely. */
        firstReadDelayMs: Long = 0L,
        /** Pass a map you keep a reference to when the test must see what was persisted. */
        settings: MutableMap<String, String> = mutableMapOf(),
    ): LibraryViewModel {
        if (remembered != null) settings["library.books.collection"] = remembered
        return LibraryViewModel(
            bookRepository = FakeBookRepo(books),
            collectionRepository = FakeCollectionRepo(collections, membership, default, firstReadDelayMs),
            seriesRepository = FakeSeriesRepo(),
            settingsRepository = FakeSettingsRepo(settings),
        )
    }

    @Test
    fun shelfIsNotReadyUntilTheRememberedCollectionRestores() = runBlocking {
        val vm = viewModel(
            books = listOf(book("b1"), book("b2")),
            collections = listOf(
                Collection(id = "main", name = "Main", sortOrder = -1),
                Collection(id = "reading", name = "Reading"),
            ),
            membership = mapOf("main" to setOf("b1"), "reading" to setOf("b2")),
            remembered = "reading",
        )

        // shelfReady is a Lazily-shared StateFlow: it produces nothing until something
        // collects it, so a test that only read `.value` would observe the initial
        // `false` forever. Collect it for the duration.
        val collector = launch { vm.shelfReady.collect {} }
        try {
            // Not settled at construction: the remembered id has not been read yet, so a
            // first frame drawn now would be the unfiltered shelf.
            assertFalse(
                vm.shelfReady.value,
                "the shelf must not be drawable before the collection resolves",
            )
            awaitCondition("shelf settles onto the remembered collection") { vm.shelfReady.value }
            assertEquals("reading", vm.selectedCollectionId.value)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun settledShelfShowsOnlyTheSelectedCollectionNeverTheWholeLibrary() = runBlocking {
        val vm = viewModel(
            books = listOf(book("b1"), book("b2")),
            collections = listOf(
                Collection(id = "main", name = "Main", sortOrder = -1),
                Collection(id = "reading", name = "Reading"),
            ),
            membership = mapOf("main" to setOf("b1"), "reading" to setOf("b2")),
            remembered = "reading",
        )
        val collector = launch { vm.shelfReady.collect {} }
        try {
            awaitCondition("shelf settles onto the remembered collection") { vm.shelfReady.value }

            // Once the selection has landed, the shelf resolves to exactly the reading
            // collection — b1 (Main only) must never bleed through. `filteredBooks`
            // emits `emptySet` (size 0) while a selection is mid-resolve and the whole
            // library (size 2) only while *no* collection is selected, which the
            // shelfReady gate hides; the settled, filtered answer is the single-book set.
            val shown = withTimeout(8000) {
                vm.filteredBooks(LibraryViewModel.LibraryState()).first { it.size == 1 }
            }
            assertEquals(listOf("b2"), shown.map { it.id })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun libraryWithNoCollectionsIsSettledImmediately() = runBlocking {
        // The other half of the gate: with no collections to select there is nothing to
        // restore, and waiting would hold the screen on a skeleton forever. An empty
        // library is a legitimate first-frame answer, not an unresolved one.
        val vm = viewModel(
            books = emptyList(),
            collections = emptyList(),
            membership = emptyMap(),
        )
        val collector = launch { vm.shelfReady.collect {} }
        try {
            awaitCondition("no collections means nothing to wait for") { vm.shelfReady.value }
        } finally {
            collector.cancel()
        }
    }

    /**
     * The hole that survived two hardening passes: `collections` is a `stateIn` whose
     * *placeholder* is `emptyList()`, which is byte-identical to a genuinely collection-less
     * library. The gate's `|| list.isEmpty()` escape hatch therefore opened on frame 1 of
     * every cold start — exactly when the JDBC read behind it is slowest — and the shelf drew
     * the unfiltered seed anyway. An empty answer must be a *loaded* empty answer.
     */
    @Test
    fun coldStartHoldsTheShelfWhileTheCollectionsTableHasNotAnswered() = runBlocking {
        val vm = viewModel(
            books = listOf(book("b1"), book("b2")),
            collections = listOf(
                Collection(id = "main", name = "Main", sortOrder = -1),
                Collection(id = "reading", name = "Reading"),
            ),
            membership = mapOf("main" to setOf("b1"), "reading" to setOf("b2")),
            remembered = "reading",
            default = Collection(id = "main", name = "Main", sortOrder = -1),
            firstReadDelayMs = 400,
        )
        val collector = launch { vm.shelfReady.collect {} }
        try {
            delay(60)
            assertFalse(
                vm.shelfReady.value,
                "a collections read that has not answered is not an empty library",
            )
            awaitCondition("the shelf settles once the table answers") { vm.shelfReady.value }
        } finally {
            collector.cancel()
        }
    }

    /**
     * The restore collector watched `collections`, so the placeholder frame passed its
     * `list.none { it.id == current }` test and it raced the remembered-id read against
     * `defaultCollection()`. Main winning is not only the wrong shelf: `selectCollection`
     * *writes* the choice back, so the reader's remembered shelf was destroyed by the start
     * that got it wrong. This is why the bleed looked intermittent — it was a race, not a state.
     */
    @Test
    fun rememberedShelfWinsOverTheDefaultWhenTheTableAnswersLate() = runBlocking {
        val settings = mutableMapOf<String, String>()
        val vm = viewModel(
            books = listOf(book("b1"), book("b2")),
            collections = listOf(
                Collection(id = "main", name = "Main", sortOrder = -1),
                Collection(id = "reading", name = "Reading"),
            ),
            membership = mapOf("main" to setOf("b1"), "reading" to setOf("b2")),
            remembered = "reading",
            default = Collection(id = "main", name = "Main", sortOrder = -1),
            firstReadDelayMs = 400,
            settings = settings,
        )
        val collector = launch { vm.shelfReady.collect {} }
        try {
            awaitCondition("the shelf settles onto the remembered collection") { vm.shelfReady.value }
            assertEquals("reading", vm.selectedCollectionId.value)
            assertEquals(
                "reading", settings["library.books.collection"],
                "the default must not overwrite the shelf the reader left",
            )
        } finally {
            collector.cancel()
        }
    }

    /**
     * `filteredBooks` answered "no collection selected" with *no filter at all*, so the shelf
     * flow itself emitted the whole library during the startup window and the gate was the only
     * thing hiding it. The unfiltered answer has to go, not just be hidden: it is also the
     * `collectAsState` seed, so any gap in the gate paints it.
     */
    @Test
    fun unsettledShelfNeverAnswersWithTheWholeLibrary() = runBlocking {
        val vm = viewModel(
            books = listOf(book("b1"), book("b2")),
            collections = listOf(Collection(id = "main", name = "Main", sortOrder = -1)),
            membership = mapOf("main" to setOf("b1")),
            firstReadDelayMs = 30_000,
        )
        val shown = withTimeout(8_000) {
            vm.filteredBooks(LibraryViewModel.LibraryState()).first()
        }
        assertTrue(
            shown.isEmpty(),
            "with no shelf resolved the answer must be empty, not every collection's books (got ${shown.map { it.id }})",
        )
    }

    private suspend fun awaitCondition(message: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out: $message")
            delay(10)
        }
    }

    // ── fakes ──────────────────────────────────────────────────────────────────

    private class FakeBookRepo(books: List<Book>) : BookRepository {
        val all = MutableStateFlow(books)
        override fun getAllBooks(): Flow<List<Book>> = all
        override fun getCurrentlyReading(): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getFinishedBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getUnreadBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getBooksByStatus(status: BookStatus): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getBooksBySeries(seriesId: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun getBooksByCollection(collectionId: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override fun searchBooks(query: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override suspend fun getBook(bookId: String): Book? = all.value.find { it.id == bookId }
        override suspend fun insertBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun updateBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun deleteBook(bookId: String, emitSyncEvent: Boolean) {}
        override suspend fun getBookByEpubHash(hash: String): Book? = null
        override suspend fun getBookByIsbn(isbn: String): Book? = null
        override suspend fun insertChapters(bookId: String, chapters: List<Chapter>) {}
        override suspend fun getChaptersForBook(bookId: String): List<Chapter> = emptyList()
        override suspend fun markOpened(bookId: String) {}
        override suspend fun setBookStatus(bookId: String, status: BookStatus) {}
        override suspend fun setCloudState(bookId: String, cloudState: CloudState) {}
        override suspend fun updateNormalizedProgress(bookId: String, progress: Double) {}
    }

    private class FakeSeriesRepo : SeriesRepository {
        override suspend fun insertSeries(series: Series, emitSyncEvent: Boolean) {}
        override suspend fun updateSeries(series: Series, emitSyncEvent: Boolean) {}
        override suspend fun deleteSeries(seriesId: String) {}
        override fun getAllSeries(): Flow<List<Series>> = MutableStateFlow(emptyList())
        override suspend fun getSeries(seriesId: String): Series? = null
        override suspend fun getSeriesByName(name: String): Series? = null
        override suspend fun getBooksInSeries(seriesId: String): Flow<List<Book>> = MutableStateFlow(emptyList())
    }

    private class FakeCollectionRepo(
        initial: List<Collection>,
        private val membership: Map<String, Set<String>>,
        private val default: Collection? = null,
        private val firstReadDelayMs: Long = 0L,
    ) : CollectionRepository {
        private val state = MutableStateFlow(initial)

        /**
         * The shape production has and the old fake did not: a read that has not answered
         * yet. `JdbcCollectionRepository.getAllCollections()` is `bookDataRevision.map { JDBC }`,
         * so its `stateIn` placeholder is observable for the whole round trip — which is what
         * the shelf's gate and its restore collector must not mistake for an answer.
         */
        override fun getAllCollections(): Flow<List<Collection>> = flow {
            if (firstReadDelayMs > 0) delay(firstReadDelayMs)
            emitAll(state)
        }
        override fun observeBookIdsInCollection(collectionId: String): Flow<Set<String>> =
            MutableStateFlow(membership[collectionId] ?: emptySet())
        override suspend fun bookIdsInCollection(collectionId: String): Set<String> =
            membership[collectionId] ?: emptySet()
        override suspend fun defaultCollection(): Collection? = default
        override suspend fun ensureSeeded() {}
        override suspend fun insertCollection(collection: Collection, emitSyncEvent: Boolean) {}
        override suspend fun updateCollection(collection: Collection, emitSyncEvent: Boolean) {}
        override suspend fun deleteCollection(collectionId: String): Boolean = false
        override suspend fun getCollectionByName(name: String): Collection? = null
        override suspend fun getCollectionsForBook(bookId: String): List<Collection> = emptyList()
        override suspend fun addBookToCollection(bookId: String, collectionId: String) {}
        override suspend fun removeBookFromCollection(bookId: String, collectionId: String) {}
        override suspend fun createCollection(name: String): Collection = Collection(id = name, name = name)
        override suspend fun renameCollection(id: String, name: String) {}
        override suspend fun getCollection(id: String): Collection? = state.value.firstOrNull { it.id == id }
        override suspend fun assign(bookId: String, collectionIds: Set<String>) {}
        override fun observeCollectionsFor(bookId: String): Flow<Set<String>> = MutableStateFlow(emptySet())
        override suspend fun ensureMembership(bookId: String) {}
    }

    private class FakeSettingsRepo(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : SettingsRepository {
        override suspend fun getGlobalSettings(): ReaderSettings = ReaderSettings()
        override suspend fun saveGlobalSettings(settings: ReaderSettings, emitSyncEvent: Boolean) {}
        override suspend fun getBookSettings(bookId: String): BookReaderSettings? = null
        override suspend fun saveBookSettings(bookId: String, settings: BookReaderSettings) {}
        override suspend fun deleteBookSettings(bookId: String) {}
        override suspend fun setRaw(key: String, value: String) { values[key] = value }
        override suspend fun getRaw(key: String): String? = values[key]
    }
}
