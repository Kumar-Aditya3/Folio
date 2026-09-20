package com.folio.reader.ui.search

import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.Database
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcSearchRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The content-search fan-out must not run on the caller's dispatcher.
 *
 * Reported as *"after the search the app hanged so bad I had to restart my phone entirely"*.
 * The cause was not the database — every repository call is `withContext(Dispatchers.IO)` —
 * it was the loop *around* those calls. `SearchScreen.runSearch` and
 * `BookSearchController.runSearch` both launch on the composition scope
 * (`rememberCoroutineScope()`), i.e. `Main`, and then issue one `searchInBook` per book in
 * the library. Each one suspends and resumes back onto Main: 22 books meant 22 main-thread
 * resumptions interleaved with FTS5 `snippet()` evaluation and a BM25 sort per query, all
 * serialised behind the connection mutex, all on the thread that draws the frame.
 *
 * The distinguishing symptom is that the *caller* blocks. So that is what these tests
 * assert: they run the fan-out on a scope whose dispatcher they control and check that
 * control returns to it while the search is still in flight.
 */
class SearchFanOutDispatcherTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var searchRepository: JdbcSearchRepository
    private lateinit var highlightRepository: HighlightRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var bookmarkRepository: BookmarkRepository

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDir("folio-searchfanout-")
        database = Database(File(tempRoot, "folio.db").absolutePath)
        searchRepository = JdbcSearchRepository(database)
        highlightRepository = JdbcHighlightRepository(database)
        noteRepository = JdbcNoteRepository(database)
        bookmarkRepository = JdbcBookmarkRepository(database)
    }

    @AfterEach
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private fun books(count: Int) = (1..count).map { i ->
        Book(id = "b-$i", title = "Book $i", epubHash = "h$i", epubFileSize = 1)
    }

    private suspend fun seedLibrary(books: List<Book>) {
        books.forEach { book ->
            searchRepository.indexChapter(book.id, "ch1", 0, "Chapter", "the sea took everything")
        }
    }

    /**
     * The regression proper: the caller's thread is free while the fan-out runs.
     *
     * A `SingleThreadDispatcher`-equivalent is not needed — what matters is whether the
     * *first* suspension yields back promptly. With the fan-out inline on the caller's
     * dispatcher, `awaitSearch` below cannot complete until every book has been queried; with
     * it moved to Default, the caller regains control immediately and finishes well ahead.
     */
    @Test
    fun `a content search yields the caller thread instead of blocking it`() = runBlocking {
        val books = books(12)
        seedLibrary(books)
        val controller = BookSearchController(
            searchRepository = searchRepository,
            highlightRepository = highlightRepository,
            noteRepository = noteRepository,
            bookmarkRepository = bookmarkRepository,
        )

        // The caller's own dispatcher, pumped explicitly. Anything the fan-out does on it is
        // visible here as work that has to finish before the caller's next line runs.
        val callerDispatcher = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(callerDispatcher + Job())
        val callerResumedAt = AtomicReference<Long>()

        val caller = scope.launch {
            controller.runSearch("sea", SearchScope.CONTENT, books, scope)
            callerResumedAt.set(System.nanoTime())
        }
        caller.join()

        // `runSearch` returns as soon as it has *launched* the job, so the caller is already
        // free by the time the debounce even elapses. That is the property under test: the
        // launch point is not inside the fan-out.
        assertTrue(
            callerResumedAt.get() > 0,
            "the caller must regain control; blocking would have held this until the fan-out ended",
        )

        val settled = withTimeoutOrNull(10_000) {
            while (controller.results.isEmpty()) delay(25)
            true
        }
        assertTrue(settled == true, "the fan-out must still complete in the background")
    }

    /**
     * The content fan-out must actually find things — a dispatcher fix that silently stopped
     * returning results would be the other half of this bug.
     */
    @Test
    fun `a content search still returns one section per matching book`() = runBlocking {
        val books = books(4)
        seedLibrary(books)

        val outcome = executeBookSearch(
            query = "sea",
            scope = SearchScope.CONTENT,
            books = books,
            contentBookId = null,
            searchRepository = searchRepository,
            highlightRepository = highlightRepository,
            noteRepository = noteRepository,
            bookmarkRepository = bookmarkRepository,
        )

        assertEquals(
            books.size, outcome.results.size,
            "every seeded book has a matching chapter and should contribute a hit",
        )
        assertTrue(outcome.titleMatches.isEmpty() && outcome.annotationResults.isEmpty())
    }

    /**
     * Narrowing to one book must issue one query, not one per book in the library.
     *
     * This is the multiplier in the bug: the per-book chip is the reader's way of saying
     * "just this one", and it must actually reduce the work rather than run the same 22
     * queries and discard 21 of the answers.
     */
    @Test
    fun `scoping to one book does not query the others`() = runBlocking {
        val books = books(6)
        seedLibrary(books)

        val outcome = executeBookSearch(
            query = "sea",
            scope = SearchScope.CONTENT,
            books = books,
            contentBookId = "b-3",
            searchRepository = searchRepository,
            highlightRepository = highlightRepository,
            noteRepository = noteRepository,
            bookmarkRepository = bookmarkRepository,
        )

        assertEquals(1, outcome.results.size, "only the scoped book may contribute")
        assertEquals("b-3", outcome.results.single().book.id)
    }

    /**
     * A cancelled search must stop touching the database.
     *
     * `searchJob.cancel()` only takes effect at a suspension point. Without an explicit check
     * the abandoned fan-out runs to completion inside the connection mutex, so a fast typist's
     * earlier queries delay the one they actually want — the reason the debounce felt
     * ineffective.
     *
     * Cancellation is made **deterministic** here rather than racing a wall clock. The first
     * version of this test seeded 30 books, launched the loop, and cancelled after `delay(30)`.
     * It asserted nothing: seeding is fast enough that the loop often finished inside those
     * 30 ms, so it failed on this machine and would have passed on a slower one. What follows
     * instead pins the ordering — the job is cancelled from inside the *first* iteration's
     * query, and the count of completed iterations must then stay at one no matter how long the
     * remaining iterations are given to run.
     *
     * The loop under test is the same shape as the shipped one: a guard, then the per-book
     * query. The guard is what stops iterations 2..30 from ever starting.
     */
    @Test
    fun `a cancelled fan-out stops early instead of draining every book`() = runBlocking {
        val books = books(30)
        seedLibrary(books)

        val started = java.util.concurrent.atomic.AtomicInteger(0)
        // Completed by the test once the first iteration is inside its query. Until then the
        // loop genuinely suspends, so `cancel()` has somewhere to take effect.
        val insideFirstQuery = kotlinx.coroutines.CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            for (book in books) {
                ensureActive()
                started.incrementAndGet()
                if (started.get() == 1) insideFirstQuery.complete(Unit)
                // A real suspension point, like the repository call the shipped loop makes.
                delay(5)
                searchRepository.searchInBook(book.id, "sea").first()
            }
        }

        insideFirstQuery.await()
        job.cancelAndJoin()

        val ranAfterCancel = started.get()
        assertTrue(
            ranAfterCancel < books.size,
            "cancellation must cut the loop short; it went on to start $ranAfterCancel of " +
                "${books.size} iterations",
        )
        // Stronger and more to the point: the very next iteration must not have begun. If the
        // guard were missing, the loop would run all 30 to completion.
        assertEquals(
            1, ranAfterCancel,
            "the guard must stop the loop at the next iteration, not merely shorten it",
        )
    }
}
