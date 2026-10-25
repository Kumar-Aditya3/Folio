package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.*
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.components.*
import com.folio.reader.ui.manga.*
import com.folio.reader.ui.reader.ReaderTool
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MangaWorkspaceTest {
    private class Notes : MangaNoteRepository {
        val rows = ConcurrentHashMap<String, MangaNote>()
        override suspend fun upsert(note: MangaNote, emitSyncEvent: Boolean) { rows[note.id] = note }
        override suspend fun delete(noteId: String, emitSyncEvent: Boolean) { rows.remove(noteId) }
        override suspend fun get(noteId: String) = rows[noteId]
        override suspend fun notesForChapter(chapterId: String) = rows.values.filter { it.chapterId == chapterId }
        override fun observeNotesForChapter(chapterId: String) = flowOf(rows.values.filter { it.chapterId == chapterId })
        override suspend fun notesForManga(mangaId: String) = rows.values.filter { it.mangaId == mangaId }
        override fun observeNotesForManga(mangaId: String) = flowOf(rows.values.filter { it.mangaId == mangaId })
        override fun observeAllNotes() = flowOf(rows.values.toList())
    }

    @Test fun notesKeepTheLongPressedPagesChapterAcrossAWindowBoundary() = runBlocking<Unit> {
        fixture { vm, notes ->
            vm.saveNote("A thought on the next chapter", 2, null, chapterId = "next")
            withTimeout(5_000) { while (notes.rows.isEmpty()) delay(10) }
            assertEquals("next", notes.rows.values.single().chapterId)
            assertEquals(2, notes.rows.values.single().pageIndex)
        }
    }

    @Test fun mangaMapsInspectorsAndNotesShareTheWorkspaceAndNotesJumpToTheirPage() = SwingUtilities.invokeAndWait {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
        fixture { vm, notes ->
            notes.rows["kept"] = MangaNote("kept", "manga", "current", 4, "Notice how the frame holds the silence.")
            for (tool in listOf(ReaderTool.Pages, ReaderTool.Saved, ReaderTool.Settings)) {
                var jumped: MangaNote? = null
                val scene = ImageComposeScene(393, 852) {
                    FolioTheme.AppTheme(AppPalette.TEAL, motion = MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED)) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                                ReaderControls(mangaTitle = "A City in the Margins", chapterName = "Chapter 3",
                                    pageCount = 12, currentPage = 3, mode = MangaReaderMode.PAGED_RTL, rtl = true,
                                    bookmarked = true, onToggleBookmark = {}, onShowNotes = {}, onShowSettings = {}, onSeek = {}, onBack = {})
                                MangaReaderWorkspace(tool, vm, {}, {}, {}, {}, {}, { jumped = it })
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close(); Thread.sleep(10) } }
                try {
                    render()
                    val image = scene.render(frame++ * 100_000_000L)
                    try { File("build/visual-review/reader-experience/manga-${tool.name.lowercase()}.png")
                        .apply { parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                    } finally { image.close() }
                    if (tool == ReaderTool.Saved) {
                        scene.sendPointerEvent(PointerEventType.Press, Offset(160f, 195f))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(160f, 195f)); render()
                        assertEquals(4, jumped?.pageIndex, "The saved note did not seek its actual page")
                        assertEquals("current", jumped?.chapterId)
                    }
                } finally { scene.close() }
            }
        }
    }

    @Test fun savedNotesIncludeOtherChaptersButNeverAnotherMangaAndKeepTheirChapterOnTap() = SwingUtilities.invokeAndWait {
        fixture { vm, notes ->
            val earlier = MangaChapter("earlier", "manga", "/earlier", "Chapter 1")
            vm.allChapters.value += earlier
            notes.rows["current-note"] = MangaNote("current-note", "manga", "current", 4, "Current chapter thought")
            val earlierNote = MangaNote("earlier-note", "manga", "earlier", 4, "Earlier chapter thought")
            notes.rows[earlierNote.id] = earlierNote
            notes.rows["other-manga"] = MangaNote("other-manga", "another", "other", 4, "Another manga thought")
            var jumped: MangaNote? = null
            val scene = ImageComposeScene(393, 852) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    FolioGlassRoot(GlassCapabilities.None) {
                        MangaReaderWorkspace(ReaderTool.Saved, vm, {}, {}, {}, {}, {}, { jumped = it })
                    }
                }
            }
            var frame = 0L
            fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
            try {
                render()
                val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                fun hasText(text: String) = nodes.any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
                assertTrue(hasText("Current chapter thought"))
                assertTrue(hasText("Earlier chapter thought"))
                assertFalse(hasText("Another manga thought"))
                val row = nodes.single { node -> node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Earlier chapter thought" } }
                val point = row.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
                render()
                assertEquals(earlierNote, jumped)
            } finally { scene.close() }
        }
    }

    private inline fun fixture(block: (MangaReaderViewModel, Notes) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("folio-manga-workspace-").toFile()
        val notes = Notes()
        val vm = MangaReaderViewModel(MangaReaderProgressTest.FakeBackend(emptyMap()), null,
            MangaReaderProgressTest.FakeChapterRepo(), MangaReaderProgressTest.FakeMangaRepo(),
            MangaReaderProgressTest.FakeHistoryRepo(), notes, MangaReaderProgressTest.FakeSettingsRepo(),
            DesktopPlatform(root).fileSystem)
        try {
            val chapter = MangaChapter("current", "manga", "/chapter", "Chapter 3 · The long way home", totalPages = 12)
            vm.manga.value = MangaEntry("manga", 1, "Fixture", "/manga", "A City in the Margins")
            vm.chapter.value = chapter
            vm.allChapters.value = listOf(chapter)
            vm.localCount.value = 12; vm.localPage.value = 3
            vm.navList = listOf(chapter)
            // Pure fixture artwork. No source image is recoloured, fetched or full-size decoded.
            val page = BufferedImage(144, 220, BufferedImage.TYPE_INT_RGB)
            val g = page.createGraphics()
            try {
                g.color = Color(242, 242, 236); g.fillRect(0, 0, 144, 220)
                g.color = Color(35, 40, 43); g.fillRect(8, 8, 128, 90); g.fillRect(8, 108, 60, 104); g.fillRect(76, 108, 60, 104)
                g.color = Color(228, 228, 220); g.fillOval(18, 20, 62, 33)
            } finally { g.dispose() }
            val out = ByteArrayOutputStream(); ImageIO.write(page, "png", out)
            for (i in 0..7) vm.pageBytesPut("current:$i", out.toByteArray())
            block(vm, notes)
        } finally {
            vm.scope.cancel()
            val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalPath + File.separator
            check(root.canonicalPath.startsWith(temporaryRoot) && root.name.startsWith("folio-manga-workspace-"))
            root.deleteRecursively()
        }
    }
}
