package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaCategoryRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaEntry
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: removing a manga from the library is a soft delete (the row survives
 * so history / downloads / progress persist), but it must still drop the manga's
 * category memberships. Otherwise a later re-add of the same deterministic id
 * ("<sourceId>:<url>") silently resurrects the categories the manga had before
 * removal — the "deleted manga come back in their old shelf" bug.
 */
class MangaRemoveClearsCategoriesTest {

    private lateinit var tempRoot: File
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-manga-remove-categories-")
        database = Database(DesktopPlatform(tempRoot).fileSystem.getDatabasePath())
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    @Test
    fun removingFromLibraryClearsMembershipsSoReAddStartsOnDefaultShelf() = runBlocking {
        val categoryRepo = JdbcMangaCategoryRepository(database)
        // Wire removal exactly like the app graph does.
        val mangaRepo = JdbcMangaRepository(
            database,
            onRemovedFromLibrary = { id -> categoryRepo.assign(id, emptySet()) },
        )

        categoryRepo.ensureSeeded() // seeds Main
        val fantasy = categoryRepo.create("Fantasy")

        val mangaId = "7:/manga/xyz"
        mangaRepo.upsert(
            MangaEntry(
                id = mangaId, sourceId = 7L, sourceName = "S", url = "/manga/xyz",
                title = "Deterministic", inLibrary = true, initialized = true,
            ),
            emitSyncEvent = false,
        )
        categoryRepo.assign(mangaId, setOf(fantasy.id))
        assertEquals(setOf(fantasy.id), categoryRepo.categoriesFor(mangaId))

        // Remove from library (soft delete) — memberships must be cleared.
        mangaRepo.setInLibrary(mangaId, false)
        assertTrue(
            categoryRepo.categoriesFor(mangaId).isEmpty(),
            "removal must clear category memberships",
        )
        assertFalse(
            mangaId in categoryRepo.mangaIdsInCategory(fantasy.id),
            "Fantasy must no longer list the removed manga",
        )

        // Re-add the SAME manga id, then let the add path seed its default shelf.
        mangaRepo.setInLibrary(mangaId, true)
        categoryRepo.ensureMembership(mangaId)

        assertEquals(
            setOf(MangaCategory.MAIN_ID),
            categoryRepo.categoriesFor(mangaId),
            "a re-added manga must land on Main, not resurrect Fantasy",
        )
        assertFalse(
            mangaId in categoryRepo.mangaIdsInCategory(fantasy.id),
            "Fantasy must stay empty after the manga is re-added",
        )
    }
}
