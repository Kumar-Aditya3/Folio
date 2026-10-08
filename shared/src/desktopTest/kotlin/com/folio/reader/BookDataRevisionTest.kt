package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcBookRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3/U2: the book flows re-query on bookDataRevision, so a single ongoing
 * collection sees inserts and status changes land without any screen remount
 * (the refreshTick hack this replaces).
 */
class BookDataRevisionTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database
    private lateinit var repo: JdbcBookRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-book-revision-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
        repo = JdbcBookRepository(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun awaitTrue(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), "Timed out: $message")
    }

    @Test
    fun ongoingCollectionSeesInsertAndStatusChange() = runBlocking {
        val received = mutableListOf<List<Book>>()
        val collector = launch {
            repo.getAllBooks().collect { received += it }
        }
        try {
            awaitTrue("first emission arrives") { received.isNotEmpty() }
            assertTrue(received.first().isEmpty())

            repo.insertBook(
                Book(id = "b1", title = "Reactive", epubHash = "h1", epubFileSize = 1L),
                emitSyncEvent = false
            )
            awaitTrue("insert re-emits on the same collection") {
                received.lastOrNull()?.any { it.id == "b1" } == true
            }

            repo.setBookStatus("b1", BookStatus.FINISHED)
            awaitTrue("status change re-emits") {
                received.lastOrNull()?.any { it.id == "b1" && it.status == BookStatus.FINISHED } == true
            }

            repo.deleteBook("b1", emitSyncEvent = false)
            awaitTrue("delete re-emits an empty list") {
                received.lastOrNull()?.isEmpty() == true
            }
            assertEquals(0, received.last().size)
        } finally {
            collector.cancel()
        }
    }
}
