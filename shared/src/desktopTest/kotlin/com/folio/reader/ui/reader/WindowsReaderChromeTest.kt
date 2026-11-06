package com.folio.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.folio.reader.ui.theme.FolioTheme
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Canvas
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** These scenes never create Chromium, a native inspector, or a second GL window. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class WindowsReaderChromeTest {
    @Test fun focusShortcutConsumesOneUnmodifiedGestureAndLeavesTextAndOtherWindowsAlone() {
        var active = false
        var toggles = 0
        val handler = DesktopReaderFocusHandler({ active }) { toggles++ }
        val source = Canvas()
        fun key(id: Int, modifiers: Int = 0) = KeyEvent(source, id, 0, modifiers, KeyEvent.VK_F, 'f')
        assertFalse(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        active = true
        for (modifier in listOf(KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK, KeyEvent.META_DOWN_MASK, KeyEvent.SHIFT_DOWN_MASK)) {
            assertFalse(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, modifier)))
        }
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertTrue(handler.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'f')))
        active = false
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)))
        assertEquals(1, toggles)
        assertFalse(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        active = true
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertEquals(2, toggles)
    }

    @Test fun directToolsAndNavigationRemainAccessibleAtConstrainedWidthsAndLargeType() = onUi {
        for (width in listOf(600, 800, 1440)) for (fontScale in listOf(1f, 1.5f, 2f)) {
            Fixture(width, fontScale = fontScale).use { fixture ->
                fixture.state.value = fixture.state.value.copy(showClock = true, progressLabel = "12 pages left")
                fixture.render()
                assertEquals(56f, fixture.tag("windows-reader-topbar").boundsInRoot.height)
                assertEquals(64f, fixture.tag("windows-reader-bottombar").boundsInRoot.height)
                val title = fixture.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == fixture.state.value.bookTitle } == true }
                assertTrue(title.boundsInRoot.width > 0f, "Book title was crowded out at $width / $fontScale")
                assertInside(title.boundsInRoot, width.toFloat(), 56f, "Book title")
                val descriptions = listOf("Back to library", "Contents", "Search", "Saved", "Settings",
                    "Bookmark this page", "Focus", "Previous page", "Next page", "Reading position")
                descriptions.forEach { description ->
                    val node = fixture.description(description)
                    assertFalse(node.config.contains(SemanticsProperties.Disabled), description)
                    assertInside(node.boundsInRoot, width.toFloat(), 320f, "$description at $width / $fontScale")
                    assertTrue(node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f, description)
                }
                fixture.click("Contents")
                fixture.click("Search")
                fixture.click("Saved")
                fixture.click("Settings")
                assertEquals(listOf(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved, ReaderTool.Settings), fixture.selected)
                fixture.click("Bookmark this page")
                assertEquals(1, fixture.bookmarks)
                assertNotNull(fixture.description("Remove bookmark").config.getOrNull(SemanticsProperties.Selected))
            }
        }
    }

    @Test fun disabledEdgesAndLoadingNeverInvokePageTurnsOrSeek() = onUi {
        Fixture(600).use { fixture ->
            fixture.state.value = fixture.state.value.copy(canGoBack = false)
            fixture.render()
            assertTrue(fixture.description("Previous page").config.contains(SemanticsProperties.Disabled))
            fixture.pointerClick("Previous page")
            fixture.pointerClick("Next page")
            assertEquals(0, fixture.previous)
            assertEquals(1, fixture.next)
            fixture.state.value = fixture.state.value.copy(canGoBack = true, canGoForward = false)
            fixture.render()
            assertTrue(fixture.description("Next page").config.contains(SemanticsProperties.Disabled))
            fixture.pointerClick("Previous page")
            fixture.pointerClick("Next page")
            assertEquals(1, fixture.previous)
            assertEquals(1, fixture.next)
            fixture.state.value = fixture.state.value.copy(ready = false, canGoForward = true)
            fixture.render()
            for (label in listOf("Previous page", "Next page", "Reading position")) {
                assertTrue(fixture.description(label).config.contains(SemanticsProperties.Disabled), label)
                fixture.pointerClick(label)
            }
            assertEquals(1, fixture.previous)
            assertEquals(1, fixture.next)
            assertTrue(fixture.seeks.isEmpty())
        }
    }

    @Test fun focusAlwaysLeavesShowControlsAndNeverChangesTheReservedPageBounds() = onUi {
        Fixture(600, fontScale = 2f).use { fixture ->
            fixture.render()
            val page = fixture.tag("test-native-page").boundsInRoot
            repeat(3) {
                fixture.click("Focus")
                assertEquals(page, fixture.tag("test-native-page").boundsInRoot)
                assertFalse(fixture.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Contents") == true })
                assertFalse(fixture.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Next page") == true })
                assertInside(fixture.description("Show controls").boundsInRoot, 600f, 320f, "Show controls")
                fixture.click("Show controls")
                assertEquals(page, fixture.tag("test-native-page").boundsInRoot)
                assertNotNull(fixture.description("Contents").config.getOrNull(SemanticsActions.OnClick))
            }
            fixture.state.value = fixture.state.value.copy(showProgress = false)
            fixture.render()
            assertEquals(page, fixture.tag("test-native-page").boundsInRoot)
            assertFalse(fixture.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "windows-reader-scrubber" })
            assertNotNull(fixture.description("Next page").config.getOrNull(SemanticsActions.OnClick))
        }
    }

    @Test fun scrubberPreviewsLocallyAndCommitsExactlyOnceOnRelease() = onUi {
        Fixture(800).use { fixture ->
            fixture.render()
            val bounds = fixture.tag("windows-reader-scrubber").boundsInRoot
            fun point(fraction: Float) = Offset(bounds.left + 10f + (bounds.width - 20f) * fraction, bounds.center.y)
            fixture.scene.sendPointerEvent(PointerEventType.Press, point(.25f))
            fixture.render()
            for (fraction in listOf(.35f, .5f, .75f)) {
                fixture.scene.sendPointerEvent(PointerEventType.Move, point(fraction))
                fixture.render()
            }
            assertTrue(fixture.seeks.isEmpty(), "A pointer preview must not navigate or reload the native document")
            assertEquals(.75f, fixture.description("Reading position").config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
            assertEquals(.2f, fixture.state.value.fraction)
            fixture.scene.sendPointerEvent(PointerEventType.Release, point(.75f))
            fixture.render()
            assertEquals(1, fixture.seeks.size)
            assertEquals(.75f, fixture.seeks.single(), .01f)
            assertEquals(.2f, fixture.description("Reading position").config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
            val setProgress = assertNotNull(fixture.description("Reading position").config.getOrNull(SemanticsActions.SetProgress)?.action)
            assertTrue(setProgress(.4f))
            assertEquals(2, fixture.seeks.size)
            assertEquals(.4f, fixture.seeks.last(), .001f)
        }
    }

    @Test fun hidingOrLosingReadinessCancelsAnInFlightScrubWithoutCommitting() = onUi {
        for (hide in listOf(false, true)) Fixture(600).use { fixture ->
            fixture.render()
            val point = fixture.tag("windows-reader-scrubber").boundsInRoot.center
            fixture.scene.sendPointerEvent(PointerEventType.Press, point)
            fixture.render()
            fixture.state.value = if (hide) fixture.state.value.copy(controlsVisible = false)
                else fixture.state.value.copy(ready = false)
            fixture.render()
            fixture.scene.sendPointerEvent(PointerEventType.Release, point)
            fixture.render()
            assertTrue(fixture.seeks.isEmpty(), "Cancelled scrub committed: hidden=$hide")
        }
    }

    @Test fun inspectorKeepsAReadableHeaderAndEveryToolReachableWithoutPhoneTabs() = onUi {
        for (fontScale in listOf(1f, 2f)) {
            val active = mutableStateOf(ReaderTool.Contents)
            var closed = 0
            var embedded = false
            val tools = listOf(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved, ReaderTool.Echoes, ReaderTool.Listen, ReaderTool.Settings)
            val scene = ImageComposeScene(352, 480) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        WindowsReaderChrome.Workspace(active.value, tools, "A very long chapter title", { active.value = it }, { closed++ }) {
                            embedded = LocalReaderWorkspace.current
                            Box(Modifier.fillMaxSize().testTag("tool-${it.label}"))
                        }
                    }
                }
            }
            try {
                var frame = 0L
                fun render() { repeat(3) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 33_333_333L).close() } }
                fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                render()
                assertTrue(embedded, "Provided tool content must inherit the existing workspace local")
                for (tool in tools) {
                    val button = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(tool.label) == true }
                    assertInside(button.boundsInRoot, 352f, 480f, tool.label)
                    assertNotNull(button.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
                    render()
                    assertEquals(tool, active.value)
                    assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "tool-${tool.label}" })
                }
                val close = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Close reader tools") == true }
                assertNotNull(close.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
                assertEquals(1, closed)
            } finally { scene.close() }
        }
    }

    @Test fun nativeInspectorBoundsStayInsideClientAreaAndClearBothFixedChromeStrips() {
        val insets = Insets(30, 8, 8, 8)
        for (owner in listOf(Rectangle(100, 200, 600, 480), Rectangle(-1400, -40, 800, 640), Rectangle(0, 0, 240, 300))) {
            for (scale in listOf(1f, 1.25f, 2f)) {
                val bounds = assertNotNull(desktopReaderWorkspaceBounds(owner, insets, scale, scale, 2f))
                val client = Rectangle(owner.x + insets.left, owner.y + insets.top,
                    owner.width - insets.left - insets.right, owner.height - insets.top - insets.bottom)
                assertTrue(client.contains(bounds), "$bounds must fit $client")
                assertEquals(client.x + client.width, bounds.x + bounds.width)
                assertTrue(bounds.y >= client.y + (56f * scale).toInt())
                assertTrue(bounds.y + bounds.height <= client.y + client.height - (64f * scale).toInt())
            }
        }
        assertEquals(null, desktopReaderWorkspaceBounds(Rectangle(0, 0, 100, 100), insets))
        assertEquals(null, desktopReaderWorkspaceBounds(Rectangle(0, 0, 0, 480), insets))
        assertEquals(null, desktopReaderWorkspaceBounds(Rectangle(0, 0, 600, 480), insets, Float.NaN))
    }

    private class Fixture(width: Int, fontScale: Float = 1f) : AutoCloseable {
        val state = mutableStateOf(DesktopReaderChromeState("A book title that should ellipsize rather than crowd controls",
            "A chapter title that is intentionally long", .2f, "12 / 80", "20%", true, true, true, true,
            false, true, true, false, null))
        val selected = mutableListOf<ReaderTool>()
        val seeks = mutableListOf<Float>()
        var previous = 0
        var next = 0
        var bookmarks = 0
        private var frame = 0L
        private val actions = DesktopReaderChromeActions({}, { previous++ }, { next++ },
            { bookmarks++; state.value = state.value.copy(bookmarked = !state.value.bookmarked) },
            { state.value = state.value.copy(controlsVisible = !state.value.controlsVisible) },
            { selected += it; state.value = state.value.copy(activeTool = it) }, { seeks += it })
        val scene = ImageComposeScene(width, 320) {
            FolioTheme.MaterialTheme(darkTheme = false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Column(Modifier.fillMaxSize()) {
                        WindowsReaderChrome.TopBar(state.value, actions, Modifier)
                        Box(Modifier.weight(1f).fillMaxSize().testTag("test-native-page"))
                        WindowsReaderChrome.BottomBar(state.value, actions, Modifier)
                    }
                }
            }
        }
        fun render() { repeat(3) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 33_333_333L).close() } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
        fun tag(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun description(label: String) = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        fun click(label: String) { assertNotNull(description(label).config.getOrNull(SemanticsActions.OnClick)?.action).invoke(); render() }
        fun pointerClick(label: String) {
            val point = description(label).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        override fun close() = scene.close()
    }

    private fun assertInside(bounds: Rect, width: Float, height: Float, message: String) {
        assertTrue(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height, "$message: $bounds")
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
