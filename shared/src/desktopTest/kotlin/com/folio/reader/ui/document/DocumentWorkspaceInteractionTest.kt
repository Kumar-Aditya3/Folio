package com.folio.reader.ui.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.reader.ReaderTool
import com.folio.reader.ui.theme.FolioTheme
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DocumentWorkspaceInteractionTest {
    @Test fun cancelAndReadHereHaveDifferentEffects() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                val origin = DocumentNavigationPoint(DocumentLocator.FixedPage(pageIndex = 0), 0.0)
                val preview = DocumentNavigationPreview(origin,
                    DocumentNavigationPoint(DocumentLocator.FixedPage(pageIndex = 2), .5), "Page 3")
                var state by mutableStateOf(DocumentReaderState(
                    document = Document("doc", "Document", "doc.pdf", DocumentFormat.PDF, "application/pdf", "hash", 10),
                    loadState = DocumentReaderLoadState.Ready(DocumentReaderContent.Pdf("not-decoded")),
                    pageCount = 5, preview = preview))
                var cancels = 0
                var reads = 0
                val scene = ImageComposeScene(393, 852) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        DocumentReaderWorkspace(ReaderTool.Pages, state, {}, {}, {}, {},
                            onCancelPreview = { cancels++; state = state.copy(preview = null) },
                            onReadHere = { reads++ }, onRemoveBookmark = {}, settings = DocumentSettings(),
                            onSettingsChange = {}, onOpenFullSettings = {}, globalSettings = ReaderSettings(),
                            onMode = {}, onRotate = {}, onResetZoom = {})
                    }
                }
                try {
                    fun render() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L).close() } }
                    fun click(label: String) {
                        val node = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.first {
                            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                                it.config.contains(SemanticsActions.OnClick)
                        }
                        assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
                    }
                    render()
                    click("Cancel")
                    assertEquals(1, cancels)
                    assertEquals(0, reads)
                    assertNull(state.preview)
                    state = state.copy(preview = preview)
                    render()
                    click("Read here")
                    assertEquals(1, reads)
                    assertEquals(1, cancels)
                } finally { scene.close() }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
