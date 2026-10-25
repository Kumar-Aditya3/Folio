package com.folio.reader.ui.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import com.folio.reader.database.*
import com.folio.reader.ml.*
import com.folio.reader.model.Book
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.test.*

class SearchEntryWorkTest {
    private lateinit var directory: File
    private lateinit var db: Database
    private lateinit var chunks: JdbcChunkRepository
    private lateinit var books: JdbcBookRepository
    private lateinit var search: JdbcSearchRepository

    @BeforeTest fun setup() {
        directory = kotlin.io.path.createTempDirectory("folio-search-entry-").toFile()
        db = Database(File(directory, "test.db").absolutePath)
        chunks = JdbcChunkRepository(db)
        books = JdbcBookRepository(db)
        search = JdbcSearchRepository(db)
    }

    @AfterTest fun cleanup() {
        db.close()
        check(directory.name.startsWith("folio-search-entry-"))
        directory.deleteRecursively()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun openingAndClosingTitleSearchDoesNotTouchVectorsOrCreateAModel() = runBlocking {
        val reads = AtomicInteger()
        val creates = AtomicInteger()
        val observed = object : ChunkRepository by chunks {
            override suspend fun countVectors(modelId: String, dims: Int, bookId: String?): Int {
                reads.incrementAndGet()
                return chunks.countVectors(modelId, dims, bookId)
            }
        }
        val semantic = SemanticSearchRepository(search, observed, factory(creates))
        val controller = BookSearchController(search, JdbcHighlightRepository(db), JdbcNoteRepository(db),
            JdbcBookmarkRepository(db), semantic)
        val active = mutableStateOf(false)
        lateinit var scene: ImageComposeScene
        var frame = 0L
        fun render() = SwingUtilities.invokeAndWait {
            repeat(6) {
                Snapshot.sendApplyNotifications()
                scene.render(frame++ * 100_000_000L).close()
            }
        }
        SwingUtilities.invokeAndWait { scene = ImageComposeScene(20, 20) { BookSearchSession(controller, active.value) } }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            render()
            active.value = true; render()
            val book = Book("book", "A book", epubHash = "hash", epubFileSize = 1)
            controller.runSearch("book", SearchScope.TITLES, listOf(book), scope)
            withTimeout(3_000) { controller.searchJob!!.join() }
            assertEquals(listOf(book), controller.titleMatches)
            val pendingQuery = scope.launch { awaitCancellation() }
            controller.searchJob = pendingQuery
            active.value = false; render()
            withTimeout(3_000) { pendingQuery.join() }
            assertTrue(pendingQuery.isCancelled, "Closing search left its query running")
            assertEquals(0, reads.get(), "Opening a title filter loaded the semantic index")
            assertEquals(0, creates.get(), "Opening a title filter initialized an embedding model")
            assertFalse(controller.searching)
        } finally {
            scope.cancel()
            SwingUtilities.invokeAndWait { scene.close() }
            semantic.release()
        }
    }

    @Test fun canceledVectorStreamingReleasesTheConnectionForTheNextBookRead() = runBlocking {
        books.insertBook(Book("book", "A book", epubHash = "hash", epubFileSize = 1))
        val model = FakeEmbedder.TEST_MODEL
        val rows = (0..127).map { Chunk("c$it", "book", "chapter", 0, it,
            it * 10, it * 10 + 10, "page text", "hash$it") }
        chunks.storeChunks(rows, model.id, model.dims,
            rows.map { FloatArray(model.dims).also { vector -> vector[0] = 1f } })
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val visited = AtomicInteger()
        val guarded = object : ChunkRepository by chunks {
            override suspend fun forEachVectorMetadata(modelId: String, dims: Int, bookId: String?,
                action: (ChunkMeta, FloatArray) -> Unit) {
                chunks.forEachVectorMetadata(modelId, dims, bookId) { meta, vector ->
                    if (visited.incrementAndGet() == 1) {
                        entered.countDown()
                        check(proceed.await(5, TimeUnit.SECONDS))
                    }
                    action(meta, vector)
                }
            }
        }
        val repository = SemanticSearchRepository(search, guarded, factory(AtomicInteger()))
        val load = launch(Dispatchers.Default) { repository.preload() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "The load did not enter the real JDBC stream")
            val reader = async(Dispatchers.IO) { books.getBook("book") }
            load.cancel()
            proceed.countDown()
            withTimeout(3_000) { load.join() }
            assertEquals("book", withTimeout(3_000) { reader.await() }?.id)
            assertEquals(1, visited.get(), "Canceled search decoded the remainder of the library under the DB mutex")
            assertFalse(repository.isLoaded, "A canceled partial index was published")
        } finally {
            proceed.countDown()
            load.cancelAndJoin()
            repository.release()
        }
    }

    @Test fun nativeModelCleanupDoesNotRunOnTheCallingThread() = runBlocking {
        val closeThread = AtomicLong(-1)
        val factory = object : EmbedderFactory {
            override val model = FakeEmbedder.TEST_MODEL
            override suspend fun create(threads: Int?, sweep: Boolean): Embedder =
                object : Embedder by FakeEmbedder() {
                    override fun close() { closeThread.set(Thread.currentThread().id) }
                }
        }
        val repository = SemanticSearchRepository(search, chunks, factory)
        repository.warm()
        val caller = Thread.currentThread().id
        repository.releaseEmbedder()
        assertTrue(closeThread.get() > 0)
        assertNotEquals(caller, closeThread.get(), "Native session teardown blocked the caller's thread")
    }

    private fun factory(creates: AtomicInteger) = object : EmbedderFactory {
        override val model = FakeEmbedder.TEST_MODEL
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder {
            creates.incrementAndGet()
            return FakeEmbedder()
        }
    }
}
