package com.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import com.folio.reader.ui.components.FolioCategoryChip
import com.folio.reader.ui.components.FolioCategoryRail
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.FolioOverlayController
import com.folio.reader.ui.components.FolioOverlayHost
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.LocalFolioOverlay
import com.folio.reader.ui.theme.FolioTheme
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class FolioCategoryRailTest {
    @Test
    fun holdAndDragCommitsOrderOnlyOnReleaseInLtrAndRtl() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { rtl ->
            Fixture(rtl).use { fixture ->
                fixture.render()
                val from = fixture.action("Main").boundsInRoot.center
                val target = fixture.action("Research").boundsInRoot
                // Cross the target midpoint rather than relying on exact pixel equality.
                val to = target.center + Offset(target.width * .25f * if (rtl) -1 else 1, 0f)
                fixture.hold(from)
                fixture.scene.sendPointerEvent(PointerEventType.Move, to)
                fixture.render()
                assertTrue(fixture.orders.isEmpty(), "The drag saved before release")
                fixture.scene.sendPointerEvent(PointerEventType.Release, to)
                fixture.render()
                assertEquals(listOf("reading", "research", "main"), fixture.orders.single(), "rtl=$rtl")
                assertTrue(fixture.controller.entries.isEmpty(), "Dragging opened the category menu")
            }
        }
    }

    @Test
    fun holdingWithoutDraggingOffersRenameAndConfirmedDelete() = SwingUtilities.invokeAndWait {
        Fixture().use { fixture ->
            fixture.render()
            fixture.hold(fixture.action("Reading").boundsInRoot.center)
            fixture.scene.sendPointerEvent(PointerEventType.Release, fixture.action("Reading").boundsInRoot.center)
            fixture.render()
            fixture.tap("Rename")
            val field = fixture.nodes().single { it.config.contains(SemanticsActions.SetText) }
            field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Favourites"))
            fixture.render()
            fixture.tap("Save")
            assertEquals("Favourites", fixture.categories.value.single { it.id == "reading" }.name)
            fixture.hold(fixture.action("Favourites").boundsInRoot.center)
            fixture.scene.sendPointerEvent(PointerEventType.Release, fixture.action("Favourites").boundsInRoot.center)
            fixture.render()
            fixture.tap("Delete category")
            assertTrue(fixture.deletions.isEmpty())
            fixture.tap("Cancel")
            assertTrue(fixture.deletions.isEmpty())
            fixture.hold(fixture.action("Favourites").boundsInRoot.center)
            fixture.scene.sendPointerEvent(PointerEventType.Release, fixture.action("Favourites").boundsInRoot.center)
            fixture.render()
            fixture.tap("Delete category")
            fixture.tap("Delete")
            assertEquals(listOf("reading"), fixture.deletions)
        }
    }

    private class Fixture(rtl: Boolean = false) : AutoCloseable {
        val categories = mutableStateOf(listOf(FolioCategoryChip("main", "Main"),
            FolioCategoryChip("reading", "Reading"), FolioCategoryChip("research", "Research")))
        val orders = mutableListOf<List<String>>()
        val deletions = mutableListOf<String>()
        val controller = FolioOverlayController()
        val scene = ImageComposeScene(600, 700) {
            val view = LocalViewConfiguration.current
            val quickHold = remember(view) { object : ViewConfiguration by view { override val longPressTimeoutMillis = 30L } }
            FolioTheme.MaterialTheme(darkTheme = true) {
                FolioGlassRoot(GlassCapabilities.None) {
                    CompositionLocalProvider(LocalFolioOverlay provides controller,
                        LocalViewConfiguration provides quickHold,
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Box(Modifier.fillMaxSize()) {
                            FolioCategoryRail(categories.value, "main", {},
                                onReorder = { ids -> orders += ids; categories.value = ids.map { id -> categories.value.single { it.id == id } } },
                                onRename = { id, name -> categories.value = categories.value.map { if (it.id == id) it.copy(name = name) else it } },
                                onDelete = { id -> deletions += id; categories.value = categories.value.filterNot { it.id == id } },
                                onEdit = {},
                            )
                            FolioOverlayHost()
                        }
                    }
                }
            }
        }
        private var frame = 0L
        fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
        fun action(label: String) = nodes().single { it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
        fun hold(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            render()
            Thread.sleep(80)
            render()
        }
        fun tap(label: String) {
            val point = action(label).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        override fun close() = scene.close()
    }
}
