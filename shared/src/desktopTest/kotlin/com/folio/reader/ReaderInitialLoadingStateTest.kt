package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcReadingSessionRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader must never present an empty chapter before one has been asked for.
 *
 * This guards the "This page is empty." flash on opening an EPUB, which survived
 * two earlier fixes because both were reasoned about from the UI side only:
 *
 * - Attempt 1 read `isLoading` as "a load is in flight". `_isLoadingContent`
 *   started **false**, so a real `isLoading = false` arrived with `html` empty and
 *   `loadError` null, which the empty branch cannot tell from an empty chapter.
 * - Attempt 2 latched a `sawLoading` flag from `isLoading` inside a
 *   `LaunchedEffect`. On the *first* composition `isLoading` was already `false`,
 *   so the effect body never ran and the latch stayed false on the frame that
 *   painted. An instrumented probe agreed with the fix because it logged from a
 *   later pass than the one that rendered — the probe watched the code converge
 *   instead of watching what was drawn.
 *
 * The fix is in the view model, so the test is too: a freshly constructed
 * [ReaderViewModel] must report loading, because a reader is only ever built in
 * order to open something. These assertions are about the *initial* value and the
 * released value, i.e. the two frames a reader actually sees.
 */
class ReaderInitialLoadingStateTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempRoot = createTempDir("folio-reader-loading-")
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun makeVm(content: String): ReaderViewModel {
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        return ReaderViewModel(
            bookRepository = JdbcBookRepository(database),
            positionRepository = JdbcReadingPositionRepository(database),
            sessionRepository = JdbcReadingSessionRepository(database),
            bookmarkRepository = JdbcBookmarkRepository(database),
            highlightRepository = JdbcHighlightRepository(database),
            noteRepository = JdbcNoteRepository(database),
            settingsRepository = JdbcSettingsRepository(database),
            chapterContentProvider = { _, _ -> content }
        )
    }

    /** A book with one chapter, so `openBook` has something to open. */
    private suspend fun seedBook(vm: ReaderViewModel) {
        val books = JdbcBookRepository(database)
        books.insertBook(
            com.folio.reader.model.Book(
                id = "book-1",
                title = "Loading Guard",
                epubHash = "hash-1",
                epubFileSize = 10L,
            ),
            emitSyncEvent = false,
        )
        books.insertChapters(
            "book-1",
            listOf(
                com.folio.reader.model.Chapter(
                    id = "ch-1",
                    bookId = "book-1",
                    spineIndex = 0,
                    href = "ch1.xhtml",
                    title = "One",
                )
            ),
        )
    }

    /**
     * The frame the reader sees first.
     *
     * A `MutableStateFlow` read before anyone writes it returns its initial value,
     * which is exactly what `collectAsState` does on the first composition. This
     * asserts it is `true`: with `false` the first painted frame had blank html,
     * no error and no load in flight, which is the empty state's signature.
     *
     * This is the assertion that would have caught both earlier attempts.
     */
    @Test
    fun a_freshly_constructed_reader_reports_loading() {
        val vm = makeVm("")
        // Read synchronously, the way `collectAsState` reads a StateFlow's
        // `value` on the first composition — `.first()` would resume later and
        // could observe a state the first frame never saw, which is precisely the
        // mistake the earlier probe made.
        assertEquals(
            true,
            vm.isLoadingContent.value,
            "A reader is constructed in order to load a book, so its initial state must be loading. " +
                "A `false` initial value is indistinguishable from a finished empty chapter and " +
                "flashes \"This page is empty.\" before any chapter has been asked for.",
        )
    }

    /**
     * And the flag is released once a load has actually reported.
     *
     * The companion to the test above: an initial `true` that never clears would
     * replace a flash with a permanent spinner. Opening a book must clear it.
     *
     * The assertion is about the *pair* — once loading has ended, the screen has
     * something to render and no ambiguity about which: either the chapter's html,
     * or a load error. What must never happen is the state the flash came from,
     * "not loading, no html, no error", which reads as a finished empty chapter.
     */
    @Test
    fun the_loading_flag_clears_once_a_chapter_has_loaded() = runBlocking {
        val vm = makeVm("<html><body><p>Chapter text.</p></body></html>")

        seedBook(vm)
        vm.openBook("book-1", deviceId = "test-device")

        val settled = waitFor(timeoutMs = 5_000) { !vm.isLoadingContent.value }
        assertTrue(settled, "the loading flag must clear once the loader has reported")
        assertTrue(
            vm.chapterHtml.value.isNotBlank() || vm.loadError.value != null,
            "once loading ends the screen must have either content or a reason there is none — " +
                "blank html with no error is the state that renders as \"This page is empty.\"",
        )
    }

    /**
     * A chapter that genuinely loads to nothing still clears the flag.
     *
     * This is the case the empty state exists for, and it has to remain reachable:
     * if a blank load left `isLoading` stuck true, the reader would spin forever
     * instead of offering the next page.
     */
    @Test
    fun a_blank_chapter_still_clears_the_loading_flag() = runBlocking {
        val vm = makeVm("")

        seedBook(vm)
        vm.openBook("book-1", deviceId = "test-device")

        val settled = waitFor(timeoutMs = 5_000) { !vm.isLoadingContent.value }
        assertTrue(settled, "a blank chapter is an answer, not a pending load")
    }

    /** Polls until [condition] holds or the budget runs out. */
    private suspend fun waitFor(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(20)
        }
        return condition()
    }
}
