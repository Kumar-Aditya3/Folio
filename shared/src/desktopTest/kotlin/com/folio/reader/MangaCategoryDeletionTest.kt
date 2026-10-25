package com.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.database.Database
import com.folio.reader.database.JdbcMangaCategoryRepository
import com.folio.reader.database.JdbcMangaChapterRepository
import com.folio.reader.database.JdbcMangaDownloadRepository
import com.folio.reader.database.JdbcMangaNoteRepository
import com.folio.reader.database.JdbcMangaRepository
import com.folio.reader.firebase.FsManga
import com.folio.reader.firebase.FsMangaCategory
import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaDownload
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaNote
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.FolioOverlayController
import com.folio.reader.ui.components.FolioOverlayHost
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.LocalFolioOverlay
import com.folio.reader.ui.manga.MangaLibraryRail
import com.folio.reader.ui.manga.MangaLibraryViewModel
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MangaCategoryDeletionTest {
    private data class Event(val type: String, val id: String, val operation: String, val payload: String)
    private lateinit var root: File
    private lateinit var database: Database
    private lateinit var categories: JdbcMangaCategoryRepository
    private lateinit var manga: JdbcMangaRepository
    private val events = CopyOnWriteArrayList<Event>()

    @BeforeTest
    fun setUp() = runBlocking {
        root = kotlin.io.path.createTempDirectory("folio-category-delete-").toFile()
        database = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        database.onEntityChanged = { type, id, operation, payload -> events += Event(type, id, operation, payload) }
        categories = JdbcMangaCategoryRepository(database)
        manga = JdbcMangaRepository(database)
        categories.ensureSeeded()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
        events.clear()
    }

    private suspend fun seed(id: String, categoryIds: Set<String>, inLibrary: Boolean = true) {
        manga.upsert(MangaEntry(id, 1, "S", "/$id", id, inLibrary = inLibrary), false)
        categories.assign(id, categoryIds, emitSyncEvent = false)
    }

    @Test
    fun exclusiveMembersLeaveLibraryWhileSharedMembersAndOtherShelvesSurvive() = runBlocking {
        val fantasy = categories.create("Fantasy")
        seed("exclusive-one", setOf(fantasy.id))
        seed("exclusive-two", setOf(fantasy.id))
        seed("shared", setOf(fantasy.id, MangaCategory.MAIN_ID))
        seed("unrelated", setOf(MangaCategory.MAIN_ID))
        seed("already-removed", setOf(fantasy.id), inLibrary = false)
        events.clear()

        assertTrue(categories.delete(fantasy.id, removeExclusiveManga = true))

        assertFalse(requireNotNull(manga.get("exclusive-one")).inLibrary)
        assertFalse(requireNotNull(manga.get("exclusive-two")).inLibrary)
        assertFalse(requireNotNull(manga.get("already-removed")).inLibrary)
        assertTrue(requireNotNull(manga.get("shared")).inLibrary)
        assertTrue(requireNotNull(manga.get("unrelated")).inLibrary)
        assertEquals(emptySet(), categories.categoriesFor("exclusive-one"))
        assertEquals(setOf(MangaCategory.MAIN_ID), categories.categoriesFor("shared"))
        assertNull(categories.get(fantasy.id))
        categories.ensureSeeded()
        assertEquals(setOf("shared", "unrelated"), categories.mangaIdsInCategory(MangaCategory.MAIN_ID))
        val removedEvents = events.filter { it.type == "manga" }
        assertEquals(setOf("exclusive-one", "exclusive-two"), removedEvents.map { it.id }.toSet())
        removedEvents.forEach {
            assertEquals("UPSERT", it.operation)
            assertFalse(Json.decodeFromString(FsManga.serializer(), it.payload).favorite)
        }
        val tombstone = events.single { it.type == "manga_category" && it.id == fantasy.id }
        assertEquals("DELETE", tombstone.operation)
        assertTrue(Json.decodeFromString(FsMangaCategory.serializer(), tombstone.payload).isDeleted)
    }

    @Test
    fun categoryRemovalKeepsProgressNotesAndDownloadedPages() = runBlocking {
        val fantasy = categories.create("Fantasy")
        seed("kept-data", setOf(fantasy.id))
        val chapters = JdbcMangaChapterRepository(database)
        chapters.replaceChapters("kept-data", listOf(MangaChapter("chapter", "kept-data", "/chapter", "Chapter", downloadedPages = 4)))
        chapters.saveProgress("chapter", 2, 4, emitSyncEvent = false)
        val notes = JdbcMangaNoteRepository(database)
        notes.upsert(MangaNote("note", "kept-data", "chapter", 2, "Keep this"), false)
        val downloads = JdbcMangaDownloadRepository(database)
        downloads.enqueue(MangaDownload("download", "kept-data", "chapter"))
        val page = File(root, "downloads/page.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }

        assertTrue(categories.delete(fantasy.id, removeExclusiveManga = true))

        assertFalse(requireNotNull(manga.get("kept-data")).inLibrary)
        assertEquals(2, chapters.getChapter("chapter")?.lastPageRead)
        assertEquals(4, chapters.getChapter("chapter")?.downloadedPages)
        assertEquals("Keep this", notes.get("note")?.content)
        assertEquals(listOf("download"), downloads.observeQueue().first().map { it.id })
        assertTrue(page.exists())
    }

    @Test
    fun soleMainCategoryCannotBeDeletedOrRemoveItsManga() = runBlocking {
        seed("main-only", setOf(MangaCategory.MAIN_ID))
        events.clear()
        assertFalse(categories.delete(MangaCategory.MAIN_ID, removeExclusiveManga = true))
        assertNotNull(categories.get(MangaCategory.MAIN_ID))
        assertTrue(requireNotNull(manga.get("main-only")).inLibrary)
        assertEquals(setOf(MangaCategory.MAIN_ID), categories.categoriesFor("main-only"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun deletingMainWithAnotherCategoryUsesTheSameExclusiveMemberRule() = runBlocking {
        val other = categories.create("Other")
        seed("main-only", setOf(MangaCategory.MAIN_ID))
        seed("shared-main", setOf(MangaCategory.MAIN_ID, other.id))
        assertTrue(categories.delete(MangaCategory.MAIN_ID, removeExclusiveManga = true))
        assertFalse(requireNotNull(manga.get("main-only")).inLibrary)
        assertTrue(requireNotNull(manga.get("shared-main")).inLibrary)
        assertEquals(setOf(other.id), categories.categoriesFor("shared-main"))
    }

    @Test
    fun remoteCategoryTombstoneDoesNotGuessLibraryRemovalBeforeMangaDocumentsArrive() = runBlocking {
        val fantasy = categories.create("Fantasy")
        seed("remote-member", setOf(fantasy.id))
        events.clear()
        assertTrue(categories.delete(fantasy.id, emitSyncEvent = false))
        assertTrue(requireNotNull(manga.get("remote-member")).inLibrary)
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedCategoryDeleteRollsBackFlagsAndMembershipsWithoutSyncEvents() = runBlocking {
        val fantasy = categories.create("Fantasy")
        seed("rollback", setOf(fantasy.id))
        database.withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TRIGGER refuse_category_delete BEFORE DELETE ON manga_categories BEGIN SELECT RAISE(ABORT, 'Delete refused'); END")
            }
        }
        events.clear()
        val revision = database.mangaDataRevision.value
        assertFailsWith<SQLException> { categories.delete(fantasy.id, removeExclusiveManga = true) }
        assertNotNull(categories.get(fantasy.id))
        assertTrue(requireNotNull(manga.get("rollback")).inLibrary)
        assertEquals(setOf(fantasy.id), categories.categoriesFor("rollback"))
        assertEquals(revision, database.mangaDataRevision.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun deleteIconWarnsFirstCancelKeepsEverythingAndVisibleDeleteCommits() {
        val fantasy = runBlocking { categories.create("Fantasy").also { seed("confirmed", setOf(it.id)) } }
        val vm = MangaLibraryViewModel(MangaReaderProgressTest.FakeBackend(emptyMap()), manga, categories,
            MangaReaderProgressTest.FakeChapterRepo(), MangaReaderProgressTest.FakeSettingsRepo())
        try {
            runBlocking { withTimeout(5_000) { vm.categories.first { it.size == 2 } } }
            SwingUtilities.invokeAndWait {
                val controller = FolioOverlayController()
                val scene = ImageComposeScene(393, 852) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            CompositionLocalProvider(LocalFolioOverlay provides controller) {
                                Box(Modifier.fillMaxSize()) {
                                    MangaLibraryRail(vm, false, {}, false, {}, null)
                                    FolioOverlayHost()
                                }
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close(); Thread.sleep(10) } }
                fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                fun action(text: String) = nodes().single { node -> node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
                fun tap(node: SemanticsNode) {
                    scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
                    scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
                    render()
                }
                fun removeFantasy() = nodes().filter { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Remove")
                }.maxBy { it.boundsInRoot.top }
                try {
                    render()
                    tap(action("Edit"))
                    tap(removeFantasy())
                    assertTrue(nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                        it.text.contains("Manga belonging only to this category will be removed")
                    } })
                    assertTrue(runBlocking { requireNotNull(manga.get("confirmed")).inLibrary })
                    tap(action("Cancel"))
                    assertNotNull(runBlocking { categories.get(fantasy.id) })
                    tap(removeFantasy())
                    tap(action("Delete"))
                    assertNull(runBlocking { categories.get(fantasy.id) })
                    assertFalse(runBlocking { requireNotNull(manga.get("confirmed")).inLibrary })
                } finally { scene.close() }
            }
        } finally { vm.scope.cancel() }
    }
}
