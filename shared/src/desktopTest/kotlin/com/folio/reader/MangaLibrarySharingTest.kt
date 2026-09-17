package com.folio.reader

import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaRepository
import com.folio.reader.ui.manga.MangaLibraryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manga shelf subscribes to each of its repositories **once**.
 *
 * Every `stateIn` on a cold source is its own subscription. The library flow was
 * being collected twice — once for the list, once for the `ready` gate — and the
 * category flow twice, once for the chips and once inside `categoryReady`. Two
 * `stateIn`s over one query is two reads of the same table for one screen, paid on
 * the very frame the reader is waiting for.
 *
 * The assertions here are on the *subscription count*, which is the property that
 * was wrong and the one a future refactor would most easily reintroduce.
 *
 * The counting wrappers delegate to [MangaReaderProgressTest]'s fakes rather than
 * reimplementing the repositories, so a signature change breaks in one place.
 */
class MangaLibrarySharingTest {

    /** Delegates every call but `observeLibrary`, which it counts. */
    private class CountingMangaRepo(
        private val delegate: MangaReaderProgressTest.FakeMangaRepo =
            MangaReaderProgressTest.FakeMangaRepo(),
    ) : MangaRepository {
        val entries get() = delegate.entries
        var librarySubscriptions = 0
            private set

        override fun observeLibrary(): Flow<List<MangaEntry>> =
            delegate.observeLibrary().onStart { librarySubscriptions++ }

        override fun observeAll(): Flow<List<MangaEntry>> = delegate.observeAll()
        override suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean) =
            delegate.upsert(manga, emitSyncEvent)

        override suspend fun delete(mangaId: String, emitSyncEvent: Boolean) =
            delegate.delete(mangaId, emitSyncEvent)

        override suspend fun get(mangaId: String): MangaEntry? = delegate.get(mangaId)
        override suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry? =
            delegate.findBySourceUrl(sourceId, url)

        override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean) =
            delegate.setInLibrary(mangaId, inLibrary)

        override suspend fun setCoverPath(mangaId: String, coverPath: String?) =
            delegate.setCoverPath(mangaId, coverPath)

        override suspend fun touchLastRead(mangaId: String) = delegate.touchLastRead(mangaId)
    }

    /** Delegates every call but `observeCategories`, which it counts. */
    private class CountingCategoryRepo(
        private val delegate: MangaReaderProgressTest.FakeCategoryRepo =
            MangaReaderProgressTest.FakeCategoryRepo(),
    ) : MangaCategoryRepository {
        var categorySubscriptions = 0
            private set

        override fun observeCategories(): Flow<List<MangaCategory>> =
            delegate.observeCategories().onStart { categorySubscriptions++ }

        override suspend fun create(name: String, emitSyncEvent: Boolean): MangaCategory =
            delegate.create(name, emitSyncEvent)

        override suspend fun rename(id: String, name: String, emitSyncEvent: Boolean) =
            delegate.rename(id, name, emitSyncEvent)

        override suspend fun delete(id: String, emitSyncEvent: Boolean): Boolean =
            delegate.delete(id, emitSyncEvent)

        override suspend fun assign(mangaId: String, categoryIds: Set<String>, emitSyncEvent: Boolean) =
            delegate.assign(mangaId, categoryIds, emitSyncEvent)

        override suspend fun categoriesFor(mangaId: String): Set<String> = delegate.categoriesFor(mangaId)
        override suspend fun get(id: String): MangaCategory? = delegate.get(id)
        override suspend fun getCategoryByName(name: String): MangaCategory? =
            delegate.getCategoryByName(name)

        override fun observeCategoriesFor(mangaId: String): Flow<Set<String>> =
            delegate.observeCategoriesFor(mangaId)

        override fun observeMangaIdsInCategory(categoryId: String): Flow<Set<String>> =
            delegate.observeMangaIdsInCategory(categoryId)

        override suspend fun mangaIdsInCategory(categoryId: String): Set<String> =
            delegate.mangaIdsInCategory(categoryId)

        override suspend fun applyRemote(category: MangaCategory, mangaIds: Set<String>) =
            delegate.applyRemote(category, mangaIds)

        override suspend fun defaultCategory(): MangaCategory? = delegate.defaultCategory()
        override suspend fun ensureMembership(mangaId: String) = delegate.ensureMembership(mangaId)
        override suspend fun ensureSeeded() = delegate.ensureSeeded()
    }

    private fun vm(
        manga: MangaRepository,
        categories: MangaCategoryRepository = CountingCategoryRepo(),
    ) = MangaLibraryViewModel(
        backend = MangaReaderProgressTest.FakeBackend(emptyMap()),
        mangaRepo = manga,
        categoryRepo = categories,
        chapterRepo = MangaReaderProgressTest.FakeChapterRepo(),
        settingsRepo = MangaReaderProgressTest.FakeSettingsRepo(),
    )

    @Test
    fun the_library_is_subscribed_once_not_twice() = runBlocking {
        val manga = CountingMangaRepo()
        val model = vm(manga)

        withTimeout(10_000) { model.library.first() }
        withTimeout(10_000) { model.ready.first { it } }

        assertEquals(
            1, manga.librarySubscriptions,
            "the shelf and its ready gate must share one subscription, not read the table twice",
        )
    }

    @Test
    fun the_categories_are_subscribed_once_not_twice() = runBlocking {
        val categories = CountingCategoryRepo()
        val model = vm(CountingMangaRepo(), categories = categories)

        withTimeout(10_000) { model.categories.first() }
        withTimeout(10_000) { model.categoryReady.first { it } }

        assertEquals(
            1, categories.categorySubscriptions,
            "the chips and categoryReady must share one subscription",
        )
    }

    @Test
    fun ready_still_reports_the_library_having_landed() = runBlocking {
        val manga = CountingMangaRepo()
        manga.entries["m1"] = MangaEntry(
            id = "m1",
            sourceId = 1L,
            sourceName = "Test",
            url = "u",
            title = "T",
            // The shelf is the in-library subset, so the row has to be marked as
            // such or `library` legitimately stays empty and `ready` never flips.
            inLibrary = true,
        )
        val model = vm(manga)

        // Deriving `ready` from `library` must not weaken it — it is the same fact,
        // that an emission happened — so the shelf's first frame still waits for data
        // instead of flashing the empty state.
        assertTrue(
            withTimeout(10_000) { model.ready.first { it } },
            "ready must still flip once the library lands",
        )
    }
}
