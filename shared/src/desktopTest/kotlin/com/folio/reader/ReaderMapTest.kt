package com.folio.reader

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.folio.reader.model.BookTocRow
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingTrace
import com.folio.reader.model.activeTocIndex
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.reader.*
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReaderMapTest {
    private val chapters = listOf(
        Chapter("one", "book", "One", "one.xhtml", 0, wordCount = 100),
        Chapter("two", "book", "Two", "two.xhtml", 1, wordCount = 300),
    )

    @Test fun excerptsRetainRealParagraphIndicesAndNeverContainScriptOrMarkup() {
        val html = "<h1>Heading</h1><p> </p><p>First passage.</p><script>secret()</script><p>Last passage.</p>"
        val preview = assertNotNull(readerPassagePreview(html, 0f))
        assertEquals(1, preview.paragraphIndex)
        assertTrue(preview.text.startsWith("First passage."))
        assertFalse("secret" in preview.text)
        assertFalse("<p>" in preview.text)
        assertEquals(2, readerPassagePreview(html, 1f)?.paragraphIndex)
        assertEquals(2, readerPassagePreview(html, 0f, paragraph = 2)?.paragraphIndex)
        assertNull(readerPassagePreview("<img src='page.jpg'>", .5f))
        assertTrue(assertNotNull(readerPassagePreview("<p>${"word ".repeat(1000)}</p>", 0f)).text.length <= 600)
    }

    @Test fun previewLoadingDoesNotChangeTheLiveChapterWindowOrLoadingState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val html = MutableStateFlow("live chapter")
        val loading = MutableStateFlow(false)
        val error = MutableStateFlow<String?>("existing state")
        val reads = mutableListOf<String>()
        val loader = ReaderContentLoader(scope, { _, href ->
            reads += href
            "<p>A passage from $href.</p>"
        }, html, loading, error, { "book" }, { chapters }, { 0 }, { false })
        try {
            assertNotNull(loader.previewChapter(1, .5f, null))
            assertNotNull(loader.previewChapter(1, 0f, null))
            assertEquals(listOf("two.xhtml"), reads)
            assertEquals("live chapter", html.value)
            assertFalse(loading.value)
            assertEquals("existing state", error.value)
            assertTrue(loader.windowSections.value.isEmpty())
            assertNull(loader.windowRange.value)
        } finally { scope.cancel() }
    }

    @Test fun contextDockNeedsRoomForReadableTextAndLargerType() {
        assertFalse(readerWorkspaceLayout(393.dp, 852.dp).docked)
        assertTrue(readerWorkspaceLayout(1200.dp, 852.dp).docked)
        assertFalse(readerWorkspaceLayout(900.dp, 852.dp, 1.5f).docked)
        assertTrue(readerWorkspaceLayout(1600.dp, 852.dp, 1.5f).docked)
        assertTrue(readerWorkspaceLayout(320.dp, 360.dp).sheetHeight <= 336.dp)
    }

    @Test fun mapSelectionAndCancellationDoNotInvokeReadingNavigation() = SwingUtilities.invokeAndWait {
        val reads = mutableListOf<Int>()
        var dismissals = 0
        val scene = ImageComposeScene(393, 640) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                BookReadingMap("book", chapters, listOf(BookTocRow("One", 0), BookTocRow("Two", 1)),
                    listOf(0f, .25f, 1f), 0, .1f, null, ReadingTrace(),
                    { _, _, _ -> ReaderPassagePreview("A quiet passage.", 0) },
                    { chapter, _, _ -> reads += chapter }, { dismissals++ })
            }
        }
        try {
            var frame = 0L
            fun render() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
            render()
            val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            assertNotNull(nodes.first { it.config.contains(SemanticsActions.SetProgress) }
                .config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(.75f)
            render()
            val cancel = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Cancel" } == true }
            assertNotNull(cancel.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            assertEquals(0, dismissals, "Cancel collapses the preview drawer without closing the TOC")
            assertTrue(reads.isEmpty())
        } finally { scene.close() }
    }

    @Test fun mapActionsStayReachableWithLargeTypeAndShortWindows() = SwingUtilities.invokeAndWait {
        for ((width, height) in listOf(320 to 320, 393 to 852, 1200 to 852)) {
            val scene = ImageComposeScene(width, height, Density(1f, 1.5f)) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    ReaderWorkspace(ReaderTool.Contents, listOf(ReaderTool.Contents, ReaderTool.Saved, ReaderTool.Settings),
                        "Book", {}, {}) {
                        BookReadingMap("book", chapters, listOf(BookTocRow("One", 0), BookTocRow("Two", 1)),
                            listOf(0f, .25f, 1f), 0, .1f, null, ReadingTrace(),
                            { _, _, _ -> ReaderPassagePreview("A readable passage.", 0) }, { _, _, _ -> }, {})
                    }
                }
            }
            try {
                var frame = 0L
                fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
                fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                render()
                val chapter = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "One" } == true &&
                    it.config.contains(SemanticsActions.OnClick) }
                assertNotNull(chapter.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
                render()
                repeat(4) {
                    val read = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true &&
                        it.config.contains(SemanticsActions.OnClick) }
                    if (read == null || read.boundsInRoot.bottom > height || read.boundsInRoot.height < 48f) {
                        val list = nodes().first { it.config.contains(SemanticsActions.ScrollBy) }
                        assertNotNull(list.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, 96f)
                        render()
                    }
                }
                for (label in listOf("Cancel", "Read here")) {
                    val node = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                        it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= height,
                        "$label must remain inside the $width x $height viewport")
                    assertTrue(node.boundsInRoot.height >= 48f, "$label needs a 48dp target in $width x $height")
                }
            } finally { scene.close() }
        }
    }

    @Test fun tocOpensAtCurrentRowAndPreviewChangesPreserveManualScrolling() = SwingUtilities.invokeAndWait {
        val manyChapters = (0 until 80).map { Chapter("chapter-$it", "book", "Chapter $it", "$it.xhtml", it, wordCount = 100) }
        val stops = (0..80).map { it / 80f }
        val cases = listOf(
            manyChapters.mapIndexed { index, chapter -> BookTocRow(chapter.title.orEmpty(), index) },
            (0 until 80).map { BookTocRow("Section $it", 40, paragraph = it * 10, fraction = it / 80f) },
        )
        for (entries in cases) {
            val fraction = 40.401f / 80f
            val withinChapter = com.folio.reader.ui.components.pageBlockSeekTarget(stops, fraction).chapterFraction
            val currentRow = entries.activeTocIndex(40, withinChapter)
            val scene = ImageComposeScene(393, 640) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    BookReadingMap("book", manyChapters, entries, stops, 40, fraction, null, ReadingTrace(),
                        { _, _, _ -> ReaderPassagePreview("A chapter preview.", 0) }, { _, _, _ -> }, {})
                }
            }
            try {
                var frame = 0L
                fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
                fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                render()
                assertTrue(nodes().any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == entries[currentRow].title } == true &&
                        node.boundsInRoot.top >= 0 && node.boundsInRoot.top < 640
                }, "The current TOC row should be visible when the map opens")
                val list = nodes().first { it.config.contains(SemanticsActions.ScrollToIndex) }
                assertNotNull(list.config.getOrNull(SemanticsActions.ScrollToIndex)?.action).invoke(63)
                render()
                fun scrollPosition() = assertNotNull(nodes().first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
                val before = scrollPosition()
                assertTrue(before >= 62f, "The regression fixture must scroll away from the opening position")
                val row = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == entries[63].title } == true &&
                    it.config.contains(SemanticsActions.OnClick) }
                assertNotNull(row.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
                render()
                assertEquals(before, scrollPosition(), .001f, "Selecting a chapter must not reset TOC scrolling")
                assertNotNull(nodes().first { it.config.contains(SemanticsActions.SetProgress) }
                    .config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(.2f)
                render()
                assertEquals(before, scrollPosition(), .001f, "Scrub previews must not reset TOC scrolling")
            } finally { scene.close() }
        }
    }
}
