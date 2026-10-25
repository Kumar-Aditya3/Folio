package com.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.model.BookTocRow
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingTrace
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.reader.BookReadingMap
import com.folio.reader.ui.reader.ReaderChapterPreviewDrawer
import com.folio.reader.ui.reader.ReaderPassagePreview
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionTier
import com.folio.reader.ui.theme.resolveMotion
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReaderTocDrawerTest {
    @Test fun chapterTapOpensItsOwnDrawerAndOnlyReadHereCommits() = onUi {
        val reads = mutableListOf<Triple<Int, Float, Int?>>()
        val chapters = listOf(Chapter("chapter", "book", "Chapter", "chapter.xhtml", 0, wordCount = 1000))
        val entries = listOf(BookTocRow("First section", 0, 5, .1f), BookTocRow("Second section", 0, 15, .3f))
        val motion = resolveMotion(MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED), MotionTier.STANDARD, false)
        val scene = ImageComposeScene(393, 700) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                CompositionLocalProvider(LocalMotion provides motion) {
                    BookReadingMap("book", chapters, entries, listOf(0f, 1f), 0, .1f, null, ReadingTrace(),
                        { _, _, paragraph -> ReaderPassagePreview("Passage at paragraph $paragraph", paragraph) },
                        { index, fraction, paragraph -> reads += Triple(index, fraction, paragraph) }, {})
                }
            }
        }
        try {
            var frame = 0L
            fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            fun button(label: String) = nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                    node.config.contains(SemanticsActions.OnClick) && !node.config.contains(SemanticsProperties.Disabled)
            }
            fun click(label: String) { assertNotNull(button(label).config.getOrNull(SemanticsActions.OnClick)?.action).invoke(); render() }
            render()
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true })
            click("First section")
            assertTrue(reads.isEmpty())
            assertTrue(button("Read here").boundsInRoot.top >= button("First section").boundsInRoot.bottom)
            click("Second section")
            assertTrue(reads.isEmpty())
            assertTrue(button("Read here").boundsInRoot.top >= button("Second section").boundsInRoot.bottom)
            assertEquals(1, nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true })
            click("Cancel")
            assertTrue(reads.isEmpty())
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true })
            click("Second section")
            click("Read here")
            assertEquals(listOf(Triple<Int, Float, Int?>(0, .3f, 15)), reads)
        } finally { scene.close() }
    }

    @Test fun drawersAnimateDisclosureAndHaveNoOutgoingActions() = onUi {
        val open = mutableStateOf(false)
        val scene = ImageComposeScene(393, 400) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    ReaderChapterPreviewDrawer(open.value, ReaderPassagePreview("A passage", 0), false, true, {}, {})
                }
            }
        }
        fun render(ms: Long) { Snapshot.sendApplyNotifications(); scene.render(ms * 1_000_000L).close() }
        try {
            render(0)
            open.value = true
            render(16)
            val entering = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            val enteringRead = entering.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true }
            assertTrue(enteringRead.boundsInRoot.height < 48f, "The drawer should reveal its contents progressively")
            render(500)
            val before = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            assertTrue(before.any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true })
            open.value = false
            render(516)
            val outgoing = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            assertFalse(outgoing.any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Read here" } == true })
            render(1000)
        } finally { scene.close() }
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
