package com.folio.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.model.Chapter
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.reader.ReaderScreen
import com.folio.reader.ui.reader.LocalReaderWorkspaceOverlay
import com.folio.reader.ui.reader.ReaderWorkspaceOverlay
import com.folio.reader.ui.reader.ReaderWorkspaceLayout
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionTier
import com.folio.reader.ui.theme.resolveMotion
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReaderChromeViewportTest {
    @Test fun togglingNativeReaderControlsNeverResizesThePageDuringAnimation() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                for ((width, height) in listOf(800 to 600, 1280 to 800)) {
                    for (progress in listOf(false, true)) checkViewport(width, height, progress)
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun checkViewport(width: Int, height: Int, progress: Boolean) {
        val controls = mutableStateOf(true)
        val contents = mutableStateOf(false)
        val search = mutableStateOf(false)
        var workspaceVisible = false
        var dismissWorkspace: () -> Unit = {}
        val overlay = object : ReaderWorkspaceOverlay {
            @Composable override fun Show(visible: Boolean, onDismiss: () -> Unit, content: @Composable (ReaderWorkspaceLayout) -> Unit) {
                val dismiss by rememberUpdatedState(onDismiss)
                SideEffect { workspaceVisible = visible; dismissWorkspace = { dismiss() } }
            }
        }
        val motion = resolveMotion(MotionPreferences(reduceMotion = ReduceMotionMode.FULL), MotionTier.STANDARD, false)
        val scene = ImageComposeScene(width, height) {
            FolioTheme.MaterialTheme(darkTheme = false) {
                CompositionLocalProvider(LocalMotion provides motion, LocalReaderWorkspaceOverlay provides overlay) {
                // The loading placeholder uses the real page layout without creating
                // Chromium in this offscreen scene. The native host gets this same modifier.
                ReaderScreen(bookTitle = "Viewport check",
                    chapters = listOf(Chapter("chapter", "book", "Chapter 1", "chapter.xhtml", 0)),
                    currentChapterIndex = 0, chapterHtml = "", isLoadingContent = true,
                    loadError = null, coverPath = null, position = null,
                    settings = ReaderSettings(showProgress = progress), bookmarks = emptyList(),
                    highlights = emptyList(), notes = emptyList(), showControls = controls.value,
                    showToc = contents.value, showAnnotations = false, showSearch = search.value,
                    onChapterChange = {}, onBackPress = {},
                    onSearchClick = { search.value = true }, onCloseSearch = { search.value = false },
                    onBookmarkClick = {}, onSettingsClick = {},
                    onToggleControls = { controls.value = !controls.value }, onToggleToc = { contents.value = !contents.value },
                    onToggleAnnotations = {}, onRemoveBookmark = {}, onRemoveHighlight = {},
                    onRemoveNote = {}, onAddNote = {}, onScrollProgress = {})
                }
            }
        }
        var frame = 0L
        fun render() {
            Snapshot.sendApplyNotifications()
            scene.render(frame++ * 33_333_333L).close()
        }
        fun bounds(): Rect = scene.semanticsOwners
            .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            .single { it.config.getOrNull(SemanticsProperties.TestTag) == "reader-page" }.boundsInRoot
        fun toolsVisible() = scene.semanticsOwners
            .flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            .any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Reader tools") == true }
        try {
            repeat(24) { render() }
            val initial = bounds()
            assertTrue(toolsVisible())
            for (visible in listOf(false, true, false, true)) {
                controls.value = visible
                repeat(24) {
                    render()
                    assertEquals(initial, bounds(), "Page resized with controls=$visible, progress=$progress at frame $frame")
                }
                assertEquals(visible, toolsVisible(), "The chrome must actually show/hide while page geometry stays fixed")
            }
            for (visible in listOf(true, false, true, false)) {
                search.value = false
                contents.value = visible
                repeat(24) { render(); assertEquals(initial, bounds(), "Contents changed page geometry") }
                assertEquals(visible, workspaceVisible)
                search.value = visible
                contents.value = false
                repeat(24) { render(); assertEquals(initial, bounds(), "Search changed page geometry") }
                assertEquals(visible, workspaceVisible)
            }
            contents.value = true
            repeat(24) { render() }
            dismissWorkspace()
            repeat(24) { render() }
            assertEquals(false, contents.value, "Native dismissal must capture the open Contents state")
            contents.value = true
            repeat(24) { render() }
            contents.value = false
            search.value = true
            repeat(24) { render() }
            dismissWorkspace()
            repeat(24) { render() }
            assertEquals(false, search.value, "Native dismissal must refresh after switching tools")
        } finally { scene.close() }
    }
}
