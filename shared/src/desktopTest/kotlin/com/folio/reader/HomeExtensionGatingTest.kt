package com.folio.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowsePage
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaImageData
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home's first emission must not wait on the manga backend's source list.
 *
 * The bug this guards, measured on device 2026-09-17: Home's skeleton stayed up
 * for **7.2 seconds**, against 135ms for every local read it actually needs. The
 * cause was not any query being slow. `state` expanded EXTENSION exclusions via
 * `expandExtensionExclusions(rows, sourcesFlow.first())` *before* emitting, and
 * `sourcesFlow` is `MangaBackend.observeSources()`. On Android that is
 * `ExtensionManager.installedExtensionsFlow`, whose implementation is
 * `onStart { initialized.await() }.map { … }` — a flow that **emits nothing until
 * the whole extension runtime has finished loading** (a disk scan plus dex
 * classloading of every installed extension APK).
 *
 * So the first frame of Home was gated behind extension loading, for a source-id
 * mapping that is a no-op unless an EXTENSION-scoped exclusion exists, and that
 * the book pipeline never consults.
 *
 * These tests assert *ordering* — the property that was wrong. A test that
 * collected only the final state would pass either way, because the expansions do
 * eventually resolve.
 */
class HomeExtensionGatingTest {

    private fun book(id: String) = Book(
        id = id,
        title = "Book $id",
        authors = listOf("Author"),
        epubHash = "hash-$id",
        epubFileSize = 1024L,
        addedAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
        status = BookStatus.READING,
        totalWords = 50_000,
        totalCharacters = 250_000,
    )

    private class InstantBookRepo(books: List<Book>) : BookRepository {
        private val all = MutableStateFlow(books)
        override fun getAllBooks(): Flow<List<Book>> = all
        override fun getCurrentlyReading(): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == BookStatus.READING } }
        override fun getFinishedBooks(): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == BookStatus.FINISHED } }
        override suspend fun getBook(bookId: String): Book? = all.value.find { it.id == bookId }
        override suspend fun insertBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun updateBook(book: Book, emitSyncEvent: Boolean) {}
        override suspend fun deleteBook(bookId: String, emitSyncEvent: Boolean) {}
        override fun getBooksByStatus(status: BookStatus): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == status } }
        override fun getBooksBySeries(seriesId: String): Flow<List<Book>> =
            all.map { list -> list.filter { it.seriesId == seriesId } }
        override fun getBooksByCollection(collectionId: String): Flow<List<Book>> =
            MutableStateFlow(emptyList())
        override fun getUnreadBooks(): Flow<List<Book>> =
            all.map { list -> list.filter { it.status == BookStatus.UNREAD } }
        override fun searchBooks(query: String): Flow<List<Book>> = MutableStateFlow(emptyList())
        override suspend fun getBookByEpubHash(hash: String): Book? = null
        override suspend fun getBookByIsbn(isbn: String): Book? = null
        override suspend fun insertChapters(bookId: String, chapters: List<Chapter>) {}
        override suspend fun getChaptersForBook(bookId: String): List<Chapter> = emptyList()
        override suspend fun markOpened(bookId: String) {}
        override suspend fun setBookStatus(bookId: String, status: BookStatus) {}
        override suspend fun setCloudState(bookId: String, cloudState: CloudState) {}
        override suspend fun updateNormalizedProgress(bookId: String, progress: Double) {}
    }

    private class InstantSessionRepo : ReadingSessionRepository {
        override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) {}
        override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> =
            MutableStateFlow(emptyList())
        override suspend fun getActiveSession(bookId: String): ReadingSession? = null
        override suspend fun getSessionsForDateRange(start: Instant, end: Instant): List<ReadingSession> =
            emptyList()
        override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> = emptyList()
        override suspend fun maxStartedAtExcludingDevice(deviceId: String): Instant? = null
        override fun observeSessionsSince(from: Instant): Flow<List<ReadingSession>> =
            MutableStateFlow(emptyList())
    }

    /**
     * Exclusions that answer at once and contain an EXTENSION row.
     *
     * The row matters: it is what makes the expansion take the interesting branch
     * instead of short-circuiting on `pkgs.isEmpty()`. With it present the old
     * code had to await the source list before it could emit anything at all.
     */
    private class ExtensionExclusionRepo : StatsExclusionRepository {
        override fun observeExclusions(): Flow<Set<Pair<Scope, String>>> =
            MutableStateFlow(setOf(Scope.EXTENSION to "eu.kanade.tachiyomi.extension.en.mangadex"))
        override suspend fun add(scope: Scope, targetId: String) {}
        override suspend fun remove(scope: Scope, targetId: String) {}
    }

    private class EmptyMangaRepo : MangaRepository {
        override suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean) {}
        override suspend fun delete(mangaId: String, emitSyncEvent: Boolean) {}
        override suspend fun get(mangaId: String): MangaEntry? = null
        override suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry? = null
        override fun observeLibrary(): Flow<List<MangaEntry>> = MutableStateFlow(emptyList())
        override fun observeAll(): Flow<List<MangaEntry>> = MutableStateFlow(emptyList())
        override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean) {}
        override suspend fun setCoverPath(mangaId: String, coverPath: String?) {}
        override suspend fun touchLastRead(mangaId: String) {}
    }

    /**
     * A backend whose source list never emits — standing in for Android's
     * extension manager before `initExtensions()` has finished.
     *
     * Every other member is a stub; only [observeSources] is on the path under
     * test.
     */
    private class SilentSourcesBackend : MangaBackend {
        override val supportsExtensions: Boolean = true
        override fun observeSources(): Flow<List<MangaSourceInfo>> = flow {
            // Never emits: exactly the pre-init behaviour of the real flow.
            kotlinx.coroutines.awaitCancellation()
        }
        override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> = emptyList()
        override suspend fun fetchBrowse(
            sourceId: Long,
            page: Int,
            mode: BrowseMode,
            query: String,
            filters: List<MangaFilter>?,
        ): MangaBrowsePage = throw UnsupportedOperationException("not used")
        override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail =
            throw UnsupportedOperationException("not used")
        override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> =
            emptyList()
        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> =
            emptyList()
        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData = throw UnsupportedOperationException("not used")
        override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? = null
        override fun observeExtensions(): Flow<List<ExtensionEntry>> = MutableStateFlow(emptyList())
        override suspend fun refreshExtensionIndex() {}
        override suspend fun getRepos(): List<MangaRepoInfo> = emptyList()
        override suspend fun setRepos(repos: List<MangaRepoInfo>) {}
        override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> =
            MutableStateFlow(ExtensionInstallStep.Installed)
        override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> =
            MutableStateFlow(ExtensionInstallStep.Installed)
        override fun uninstallExtension(pkgName: String) {}
        override suspend fun trustExtension(
            pkgName: String,
            versionCode: Long,
            signatureHash: String,
        ) {}
        override suspend fun getExtensionIcon(pkgName: String): ByteArray? = null
        override suspend fun setShowNsfwSources(enabled: Boolean) {}
        override suspend fun getShowNsfwSources(): Boolean = false
    }

    /**
     * The property under test: with the backend's source list hung, Home still
     * reaches `loaded = true`.
     *
     * This is the regression guard for the 7.2s skeleton. Before the fix the
     * expansion sat on the emission path, so `state` could not produce `loaded`
     * at all while the source list was silent, and this times out.
     */
    @Test
    fun loaded_arrives_while_the_source_list_never_emits() = runBlocking {
        val vm = HomeViewModel(
            bookRepository = InstantBookRepo(listOf(book("a"))),
            sessionRepository = InstantSessionRepo(),
            statsExclusionRepository = ExtensionExclusionRepo(),
            mangaRepository = EmptyMangaRepo(),
            mangaBackend = SilentSourcesBackend(),
        )

        // No backend release. If Home waits on the source list, this never completes.
        val loaded = withTimeout(10_000) { vm.state.first { it.loaded } }
        assertTrue(loaded.loaded, "Home must report loaded without the source list answering")
        assertTrue(loaded.hasBooks, "the book pipeline's own facts must be present")
    }

    /**
     * The exclusion rows are still honoured on the fast path.
     *
     * Deferring the expansion must not defer the *gating*: a DIRECT row has no
     * dependency on the source list, so Home's first emission has to apply it. An
     * EXTENSION row names a package that no book can match, so leaving it
     * unexpanded for one emission is safe — and the book it would have hidden is
     * not a book at all.
     */
    @Test
    fun direct_book_exclusions_still_gate_the_first_emission() = runBlocking {
        val repo = object : StatsExclusionRepository {
            override fun observeExclusions(): Flow<Set<Pair<Scope, String>>> =
                MutableStateFlow(setOf(Scope.BOOK to "hidden"))
            override suspend fun add(scope: Scope, targetId: String) {}
            override suspend fun remove(scope: Scope, targetId: String) {}
        }
        val vm = HomeViewModel(
            bookRepository = InstantBookRepo(listOf(book("hidden"), book("shown"))),
            sessionRepository = InstantSessionRepo(),
            statsExclusionRepository = repo,
            mangaRepository = EmptyMangaRepo(),
            mangaBackend = SilentSourcesBackend(),
        )

        val loaded = withTimeout(10_000) { vm.state.first { it.loaded } }
        assertTrue(loaded.exclusionsActive, "the exclusion must be reported as active")
        assertTrue(
            loaded.readingNow.none { it.id == "hidden" },
            "a directly excluded book must not reach the surface",
        )
    }
}
