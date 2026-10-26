package com.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.ui.components.FolioDialog
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.FolioOverlayController
import com.folio.reader.ui.components.FolioOverlayHost
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.LocalFolioOverlay
import com.folio.reader.ui.library.BookItemMenu
import com.folio.reader.ui.library.DocumentItemMenu
import com.folio.reader.ui.manga.MangaItemMenu
import com.folio.reader.ui.theme.FolioTheme
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LibraryMenuHandoffTest {
    @Test
    fun retiringAMenuPreservesItsReplacementAndTheUnderlyingDialog() {
        val controller = FolioOverlayController()
        val parent = controller.showDialog(onDismiss = {}) {}
        val menu = controller.showMenu(Rect.Zero, onDismiss = {}) {}
        val confirmation = controller.showDialog(onDismiss = {}) {}
        controller.retireMenu(menu)
        assertEquals(listOf(parent, confirmation), controller.entries.map { it.token })
        controller.retireMenu(menu)
        assertEquals(listOf(parent, confirmation), controller.entries.map { it.token })
        controller.dismiss(parent)
        assertTrue(controller.entries.isEmpty())
    }

    @Test
    fun libraryActionConfirmationsSurviveMenuCleanupInEitherCompositionOrder() = SwingUtilities.invokeAndWait {
        listOf("manga", "books", "documents").forEach { kind ->
            listOf(true, false).forEach { dialogFirst ->
                val menuOpen = mutableStateOf(true)
                val confirmationOpen = mutableStateOf(false)
                var commits = 0
                val controller = FolioOverlayController()
                val scene = ImageComposeScene(400, 700) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            CompositionLocalProvider(LocalFolioOverlay provides controller) {
                                Box(Modifier.fillMaxSize()) {
                                    val confirmation: @Composable () -> Unit = {
                                        if (confirmationOpen.value) FolioDialog(true, { confirmationOpen.value = false }, title = "Confirm action") {
                                            Text("Ready to remove")
                                            TextButton(onClick = { commits++; confirmationOpen.value = false }) { Text("Confirm") }
                                        }
                                    }
                                    if (dialogFirst) confirmation()
                                    val dismiss = { menuOpen.value = false }
                                    val remove = { confirmationOpen.value = true }
                                    when (kind) {
                                        "manga" -> MangaItemMenu(menuOpen.value, dismiss, {}, {}, {}, {}, remove)
                                        "books" -> BookItemMenu(menuOpen.value, dismiss, {}, {}, remove)
                                        else -> DocumentItemMenu(menuOpen.value, dismiss, {}, {}, {}, remove)
                                    }
                                    if (!dialogFirst) confirmation()
                                    FolioOverlayHost()
                                }
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() } }
                fun tap(label: String) {
                    val node = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.single {
                        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
                    }
                    scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
                    scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
                    render()
                }
                try {
                    render()
                    tap(when (kind) { "manga" -> "Remove from library"; "books" -> "Delete book"; else -> "Delete document" })
                    assertTrue(confirmationOpen.value, "$kind confirmation was dismissed by menu cleanup (dialogFirst=$dialogFirst)")
                    assertEquals(0, commits)
                    assertEquals(1, controller.entries.size)
                    tap("Confirm")
                    assertEquals(1, commits)
                    assertTrue(controller.entries.isEmpty())
                } finally { scene.close() }
            }
        }
    }
}
