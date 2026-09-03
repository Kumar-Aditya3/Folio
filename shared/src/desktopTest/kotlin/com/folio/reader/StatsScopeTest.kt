package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcStatsExclusionRepository
import com.folio.reader.model.BookStatus
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.statistics.Scope
import com.folio.reader.statistics.StatsScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §11.2 stats-exclusion resolver: one table, one resolver, one predicate. The
 * rule under test is deliberately one-way — an entity is excluded when listed
 * directly or when ANY group it belongs to is listed; there is no
 * include-anyway override. Also covers the JDBC repository roundtrip backing
 * the resolver's input set (add / observe / remove, persisted across reopen).
 */
class StatsScopeTest {

    private fun scopeOf(vararg pairs: Pair<Scope, String>) = StatsScope(pairs.toSet())

    private fun includes(
        scope: StatsScope,
        bookId: String = "b1",
        tagIds: Set<String> = emptySet(),
        collectionIds: Set<String> = emptySet(),
        seriesId: String? = null,
        status: BookStatus = BookStatus.READING
    ): Boolean = scope.includesBook(bookId, tagIds, collectionIds, seriesId, status)

    @Test
    fun `empty scope includes every book and manga`() {
        val scope = scopeOf()
        assertTrue(includes(scope))
        assertTrue(includes(scope, bookId = "b2", tagIds = setOf("t1"), seriesId = "s1", status = BookStatus.ABANDONED))
        assertTrue(scope.includesManga("m1", setOf("cat1"), 42L))
    }

    @Test
    fun `book listed directly is excluded, others stay`() {
        val scope = scopeOf(Scope.BOOK to "b1")
        assertFalse(includes(scope, bookId = "b1"))
        assertTrue(includes(scope, bookId = "b2"))
    }

    @Test
    fun `tag exclusion removes every book carrying the tag`() {
        val scope = scopeOf(Scope.BOOK_TAG to "t-dnf")
        assertFalse(includes(scope, bookId = "b1", tagIds = setOf("t-dnf")))
        assertFalse(includes(scope, bookId = "b2", tagIds = setOf("t-other", "t-dnf")))
        assertTrue(includes(scope, bookId = "b3", tagIds = setOf("t-other")))
        assertTrue(includes(scope, bookId = "b4"))
    }

    @Test
    fun `collection exclusion removes every book in the collection`() {
        val scope = scopeOf(Scope.BOOK_COLLECTION to "c-guilty")
        assertFalse(includes(scope, bookId = "b1", collectionIds = setOf("c-guilty")))
        assertTrue(includes(scope, bookId = "b2", collectionIds = setOf("c-proud")))
    }

    @Test
    fun `book in an excluded and a non-excluded group is still excluded`() {
        // One-way rule: the other, non-excluded group must NOT pull the book
        // back in. There is no per-entity include-override — the user removes
        // the group exclusion instead.
        val tagScope = scopeOf(Scope.BOOK_TAG to "t-excluded")
        assertFalse(
            includes(tagScope, bookId = "b1", tagIds = setOf("t-excluded", "t-fine"), collectionIds = setOf("c-kept"))
        )
        val collectionScope = scopeOf(Scope.BOOK_COLLECTION to "c-excluded")
        assertFalse(
            includes(collectionScope, bookId = "b2", tagIds = setOf("t-fine"), collectionIds = setOf("c-excluded", "c-kept"))
        )
    }

    @Test
    fun `series exclusion removes every book in that series`() {
        val scope = scopeOf(Scope.BOOK_SERIES to "s-expanse")
        // Every member resolves its own seriesId, so the group dies as a whole.
        assertFalse(includes(scope, bookId = "b1", seriesId = "s-expanse", status = BookStatus.FINISHED))
        assertFalse(includes(scope, bookId = "b2", seriesId = "s-expanse", status = BookStatus.READING))
        assertFalse(includes(scope, bookId = "b3", seriesId = "s-expanse", status = BookStatus.UNREAD))
        assertTrue(includes(scope, bookId = "b4", seriesId = "s-other"))
        assertTrue(includes(scope, bookId = "b5", seriesId = null))
    }

    @Test
    fun `status exclusion removes abandoned DNF books`() {
        val scope = scopeOf(Scope.BOOK_STATUS to BookStatus.ABANDONED.name)
        assertFalse(includes(scope, bookId = "b1", status = BookStatus.ABANDONED))
        assertTrue(includes(scope, bookId = "b2", status = BookStatus.READING))
        assertTrue(includes(scope, bookId = "b3", status = BookStatus.FINISHED))
        // The row keeps working without maintenance: a newly abandoned book is
        // excluded by the same single row.
        assertFalse(includes(scope, bookId = "b4", status = BookStatus.ABANDONED))
    }

    @Test
    fun `manga source exclusion removes everything from that source`() {
        val scope = scopeOf(Scope.MANGA_SOURCE to "7")
        assertFalse(scope.includesManga("m1", emptySet(), 7L))
        assertFalse(scope.includesManga("m2", setOf("cat1"), 7L))
        assertTrue(scope.includesManga("m3", emptySet(), 8L))
    }

    @Test
    fun `manga id and category exclusion`() {
        val scope = scopeOf(Scope.MANGA to "m1", Scope.MANGA_CATEGORY to "cat-isekai")
        assertFalse(scope.includesManga("m1", emptySet(), 1L))
        assertFalse(scope.includesManga("m2", setOf("cat-isekai"), 1L))
        assertTrue(scope.includesManga("m2", setOf("cat-other"), 1L))
        assertTrue(scope.includesManga("m3", emptySet(), 1L))
    }

    // ── JDBC repository backing the resolver's input set ─────────────────────

    @Test
    fun `stats exclusion repository add observe remove roundtrip persists across reopen`() = runBlocking {
        val tempRoot = createTempDir("folio-stats-exclusions-")
        try {
            val platform = DesktopPlatform(tempRoot)
            val path = platform.fileSystem.getDatabasePath()
            val db = Database(path)
            val repo = JdbcStatsExclusionRepository(db)

            assertEquals(emptySet(), repo.observeExclusions().first())

            repo.add(Scope.BOOK, "b1")
            repo.add(Scope.BOOK, "b1") // duplicate is a no-op, PK (scope, target_id)
            repo.add(Scope.BOOK_STATUS, BookStatus.ABANDONED.name)
            repo.add(Scope.MANGA_SOURCE, "7")
            assertEquals(
                setOf(Scope.BOOK to "b1", Scope.BOOK_STATUS to BookStatus.ABANDONED.name, Scope.MANGA_SOURCE to "7"),
                repo.observeExclusions().first()
            )

            repo.remove(Scope.MANGA_SOURCE, "7")
            repo.remove(Scope.MANGA_SOURCE, "7") // removing an absent row is a no-op
            assertEquals(
                setOf(Scope.BOOK to "b1", Scope.BOOK_STATUS to BookStatus.ABANDONED.name),
                repo.observeExclusions().first()
            )

            // Rows live in the shared database file, not in memory: a fresh
            // Database (and repository) over the same path still sees them.
            db.close()
            val second = Database(path)
            try {
                val reopened = JdbcStatsExclusionRepository(second)
                assertEquals(
                    setOf(Scope.BOOK to "b1", Scope.BOOK_STATUS to BookStatus.ABANDONED.name),
                    reopened.observeExclusions().first()
                )
                reopened.remove(Scope.BOOK, "b1")
                assertEquals(setOf(Scope.BOOK_STATUS to BookStatus.ABANDONED.name), reopened.observeExclusions().first())
            } finally {
                second.close()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `observeExclusions re-emits to a live collector after add and remove`() = runBlocking {
        // The exclusions screen collects ONE flow instance while writing through
        // the same repository — the tick must appear without re-subscribing.
        val tempRoot = createTempDir("folio-stats-exclusions-live-")
        try {
            val platform = DesktopPlatform(tempRoot)
            val repo = JdbcStatsExclusionRepository(Database(platform.fileSystem.getDatabasePath()))
            val snapshots = Channel<Set<Pair<Scope, String>>>(Channel.UNLIMITED)
            val job = launch { repo.observeExclusions().take(3).collect { snapshots.send(it) } }
            try {
                assertEquals(emptySet(), snapshots.receive())
                repo.add(Scope.BOOK, "b1")
                assertEquals(setOf(Scope.BOOK to "b1"), snapshots.receive())
                repo.remove(Scope.BOOK, "b1")
                assertEquals(emptySet(), snapshots.receive())
            } finally {
                job.cancel()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
