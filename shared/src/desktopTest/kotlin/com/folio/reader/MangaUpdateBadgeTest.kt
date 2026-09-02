package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.database.JdbcMangaUpdateRepository
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowsePage
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaImageData
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.statistics.Scope
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §11.4 "New chapters" Home rows: the badge query joins manga_library, so
 * orphaned update-state rows (manga removed from the library) never surface,
 * only in-library titles show, ordering is newest-check-first with a 6 cap,
 * and the §11.2 manga exclusions hide excluded titles (§12.9).
 */
class MangaUpdateBadgeTest {

    private lateinit var tempRoot: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var repo: JdbcMangaUpdateRepository

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-manga-badges-")
        platform = DesktopPlatform(tempRoot)
        database = Database(platform.fileSystem.getDatabasePath())
        repo = JdbcMangaUpdateRepository(
            db = database,
            mangaRepository = JdbcMangaRepository(database),
            chapterRepository = JdbcMangaChapterRepository(database),
            backend = StubBackend(),
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        tempRoot.deleteRecursively()
    }

    private suspend fun insertManga(
        id: String,
        sourceId: Long = 10L,
        title: String = id,
        favorite: Int = 1,
    ) {
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO manga_library (id, source_id, source_name, url, title, favorite, added_at, updated_at) " +
                    "VALUES (?, ?, 'Src', ?, ?, ?, 0, 0)"
            ).use { st ->
                st.setString(1, id)
                st.setLong(2, sourceId)
                st.setString(3, "/manga/$id")
                st.setString(4, title)
                st.setInt(5, favorite)
                st.executeUpdate()
            }
        }
    }

    private suspend fun insertState(mangaId: String, count: Int, checkedAtMs: Long) {
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO manga_update_state (manga_id, last_checked_at, new_chapter_count, last_error) " +
                    "VALUES (?, ?, ?, NULL)"
            ).use { st ->
                st.setString(1, mangaId)
                st.setLong(2, checkedAtMs)
                st.setInt(3, count)
                st.executeUpdate()
            }
        }
    }

    private suspend fun insertCategoryMap(mangaId: String, categoryId: String) {
        database.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO manga_category_map (manga_id, category_id) VALUES (?, ?)"
            ).use { st ->
                st.setString(1, mangaId)
                st.setString(2, categoryId)
                st.executeUpdate()
            }
        }
    }

    @Test
    fun `badges join the library - orphans and out-of-library rows never surface`() = runBlocking {
        insertManga("in-library", favorite = 1)
        insertManga("removed", favorite = 0)
        insertState("in-library", 3, 1000)
        insertState("removed", 2, 2000)
        insertState("orphaned", 1, 3000)

        val badges = repo.getNewChapterBadges()

        assertEquals(listOf("in-library"), badges.map { it.mangaId })
        assertEquals(3, badges.single().newChapterCount)
        assertEquals("in-library", badges.single().title)
        Unit
    }

    @Test
    fun `badges are newest-check-first and capped at six`() = runBlocking {
        for (i in 1..8) {
            insertManga("m$i")
            insertState("m$i", 1, checkedAtMs = i.toLong() * 1000)
        }

        val badges = repo.getNewChapterBadges()

        assertEquals(6, badges.size)
        assertEquals((8 downTo 3).map { "m$it" }, badges.map { it.mangaId })
        Unit
    }

    @Test
    fun `manga exclusions hide badges - direct, source and category`() = runBlocking {
        insertManga("a", sourceId = 10L)
        insertManga("b", sourceId = 20L)
        insertCategoryMap("a", "cat-1")
        insertState("a", 4, 1000)
        insertState("b", 5, 2000)

        assertEquals(listOf("b"), repo.getNewChapterBadges(setOf(Scope.MANGA to "a")).map { it.mangaId })
        // A source exclusion hides only that source's titles, not the whole card.
        assertEquals(listOf("b"), repo.getNewChapterBadges(setOf(Scope.MANGA_SOURCE to "10")).map { it.mangaId })
        assertEquals(listOf("a"), repo.getNewChapterBadges(setOf(Scope.MANGA_SOURCE to "20")).map { it.mangaId })
        assertEquals(listOf("b"), repo.getNewChapterBadges(setOf(Scope.MANGA_CATEGORY to "cat-1")).map { it.mangaId })
        Unit
    }

    /** Badge queries never touch the backend; every member is an inert stub. */
    private class StubBackend : MangaBackend {
        override val supportsExtensions: Boolean = false
        override fun observeSources(): Flow<List<MangaSourceInfo>> = flowOf(emptyList())
        override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> = emptyList()
        override suspend fun fetchBrowse(
            sourceId: Long,
            page: Int,
            mode: BrowseMode,
            query: String,
            filters: List<MangaFilter>?,
        ): MangaBrowsePage = MangaBrowsePage(emptyList(), false)

        override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail =
            MangaDetail(url = mangaUrl, title = "Stub")

        override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> = emptyList()
        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> = emptyList()
        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData = MangaImageData(ByteArray(0), "image/png")

        override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? = null
        override fun observeExtensions(): Flow<List<ExtensionEntry>> = flowOf(emptyList())
        override suspend fun refreshExtensionIndex() {}
        override suspend fun getRepos(): List<MangaRepoInfo> = emptyList()
        override suspend fun setRepos(repos: List<MangaRepoInfo>) {}
        override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun uninstallExtension(pkgName: String) {}
        override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {}
        override suspend fun getExtensionIcon(pkgName: String): ByteArray? = null
        override suspend fun setShowNsfwSources(enabled: Boolean) {}
        override suspend fun getShowNsfwSources(): Boolean = false
    }
}
