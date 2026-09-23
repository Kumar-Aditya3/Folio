package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.database.JdbcBookmarkRepository
import com.folio.reader.database.JdbcHighlightRepository
import com.folio.reader.database.JdbcNoteRepository
import com.folio.reader.database.JdbcReadingPositionRepository
import com.folio.reader.database.JdbcReadingSessionRepository
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.model.Book
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B0 second half: the reader panel's "All books" write. The panel paints
 * effective (book-merged) values, so the write must be scoped to the fields the
 * user actually changed — a book's untouched overrides (fontSize, theme) must
 * not leak into the global defaults every other book follows.
 */
class ReaderQuickSettingsNoLeakTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var bookRepo: JdbcBookRepository
    private lateinit var positionRepo: JdbcReadingPositionRepository
    private lateinit var sessionRepo: JdbcReadingSessionRepository
    private lateinit var bookmarkRepo: JdbcBookmarkRepository
    private lateinit var highlightRepo: JdbcHighlightRepository
    private lateinit var noteRepo: JdbcNoteRepository
    private lateinit var settingsRepo: JdbcSettingsRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempRoot = createTempDir("folio-quick-settings-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        bookRepo = JdbcBookRepository(database)
        positionRepo = JdbcReadingPositionRepository(database)
        sessionRepo = JdbcReadingSessionRepository(database)
        bookmarkRepo = JdbcBookmarkRepository(database)
        highlightRepo = JdbcHighlightRepository(database)
        noteRepo = JdbcNoteRepository(database)
        settingsRepo = JdbcSettingsRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun makeVm(): ReaderViewModel = ReaderViewModel(
        bookRepository = bookRepo,
        positionRepository = positionRepo,
        sessionRepository = sessionRepo,
        bookmarkRepository = bookmarkRepo,
        highlightRepository = highlightRepo,
        noteRepository = noteRepo,
        settingsRepository = settingsRepo,
        chapterContentProvider = { _, _ -> "" }
    )

    private suspend fun awaitTrue(message: String, timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), "Timed out: $message")
    }

    private suspend fun openBookWithOverrides(vm: ReaderViewModel) {
        settingsRepo.saveGlobalSettings(
            ReaderSettings(themeId = "paper", fontSize = 18f, lineHeight = 1.5f),
            emitSyncEvent = false
        )
        bookRepo.insertBook(Book(id = "b1", title = "Leak Guard", epubHash = "hash-1", epubFileSize = 10L))
        // This book overrides the global defaults; the panel will paint these values.
        settingsRepo.saveBookSettings(
            "b1",
            BookReaderSettings(fontSize = 24f, themeId = "dusk", lineHeight = 1.9f)
        )
        vm.openBook("b1", "device-1")
        awaitTrue("book settings are loaded") {
            vm.effective().fontSize == 24f && vm.effective().themeId == "dusk" && vm.effective().lineHeight == 1.9f
        }
    }

    @Test
    fun allBooksWriteDoesNotLeakBookOverrides() = runBlocking {
        val vm = makeVm()
        openBookWithOverrides(vm)

        // The panel passes effective-derived values; the user changed only line height.
        val newSettings = vm.effective().copy(lineHeight = 2.1f)
        vm.updateGlobalSettings(newSettings)
        awaitTrue("global write lands") { settingsRepo.getGlobalSettings().lineHeight == 2.1f }

        val global = settingsRepo.getGlobalSettings()
        assertEquals(2.1f, global.lineHeight, "the edited field must be applied")
        assertEquals(18f, global.fontSize, "book's fontSize override must not leak into globals")
        assertEquals("paper", global.themeId, "book's themeId override must not leak into globals")

        // This book keeps its untouched overrides; the edited one follows the new default.
        val book = settingsRepo.getBookSettings("b1")!!
        assertEquals(24f, book.fontSize)
        assertEquals("dusk", book.themeId)
        assertNull(book.lineHeight, "the edited field's override must be cleared")
        vm.closeBook()
    }

    @Test
    fun allBooksWriteAppliesFieldsTheUserChanged() = runBlocking {
        val vm = makeVm()
        openBookWithOverrides(vm)

        val newSettings = vm.effective().copy(fontSize = 20f)
        vm.updateGlobalSettings(newSettings)
        awaitTrue("global write lands") { settingsRepo.getGlobalSettings().fontSize == 20f }

        val global = settingsRepo.getGlobalSettings()
        assertEquals(20f, global.fontSize, "an explicit edit must reach the globals")
        assertEquals(1.5f, global.lineHeight, "unrelated fields keep the stored values")

        val book = settingsRepo.getBookSettings("b1")!!
        assertNull(book.fontSize, "the edited field's override must be cleared")
        assertEquals("dusk", book.themeId, "untouched overrides survive")
        vm.closeBook()
    }

    /**
     * The Comfort rows (Eye protection and its warmth) are global-only: they have
     * no per-book counterpart, so they reach the defaults row through the same
     * global write. Measured against the per-book override vocabulary the change
     * set came back empty, `withFieldsFrom(emptySet(), …)` returned its receiver
     * untouched, and the switch snapped straight back — the reported "does not
     * toggle". The same write must still not promote this book's overrides.
     */
    @Test
    fun globalOnlyComfortToggleReachesTheDefaultsRow() = runBlocking {
        val vm = makeVm()
        openBookWithOverrides(vm)

        assertFalse(vm.effective().eyeProtection, "eye protection is off by default")

        vm.updateGlobalSettings(vm.effective().copy(eyeProtection = true))
        awaitTrue("the toggle lands in the globals") { settingsRepo.getGlobalSettings().eyeProtection }
        assertTrue(vm.effective().eyeProtection, "the panel paints the new value")

        vm.updateGlobalSettings(vm.effective().copy(eyeProtectionIntensity = 0.8f))
        awaitTrue("warmth lands in the globals") {
            settingsRepo.getGlobalSettings().eyeProtectionIntensity == 0.8f
        }

        // The book's untouched overrides survive, and no override was invented for
        // a field the book cannot override.
        val global = settingsRepo.getGlobalSettings()
        assertEquals(true, global.eyeProtection)
        assertEquals(18f, global.fontSize, "book's fontSize override must not leak into globals")
        assertEquals("paper", global.themeId, "book's themeId override must not leak into globals")
        val book = settingsRepo.getBookSettings("b1")!!
        assertEquals(24f, book.fontSize)
        assertEquals("dusk", book.themeId)
        assertNull(book.lineHeight)

        // And back off again — a toggle that only latches one way is still broken.
        vm.updateGlobalSettings(vm.effective().copy(eyeProtection = false))
        awaitTrue("the toggle clears again") { !settingsRepo.getGlobalSettings().eyeProtection }
        assertFalse(vm.effective().eyeProtection)
        vm.closeBook()
    }
}
