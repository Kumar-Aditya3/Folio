package com.folio.reader.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.AnnotatedString
import com.folio.reader.manga.MangaCategory
import com.folio.reader.model.Collection
import com.folio.reader.model.DocumentCategory
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.FolioOverlayController
import com.folio.reader.ui.components.FolioOverlayHost
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.LocalFolioOverlay
import com.folio.reader.ui.manga.CategoryPickerDialog
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.CompletableDeferred
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LibraryAssignmentDialogTest {
    @Test
    fun everyPickerStagesChangesAndItsVisibleConfirmButtonWorks() = onUiThread {
        for (kind in listOf("manga", "books", "documents", "import")) {
            Fixture(kind).use { fixture ->
                fixture.render()
                fixture.tap("Research")
                assertTrue(fixture.committed.isEmpty(), "$kind wrote a checkbox change before confirmation")
                fixture.tap("Confirm")
                assertEquals(listOf(setOf(fixture.mainId, "research")), fixture.committed, kind)
                assertFalse(fixture.open.value, "$kind did not dismiss after saving")
            }
        }
    }

    @Test
    fun cancellingEveryPickerDiscardsItsMembershipDraft() = onUiThread {
        for (kind in listOf("manga", "books", "documents", "import")) {
            Fixture(kind).use { fixture ->
                fixture.render()
                fixture.tap("Research")
                fixture.tap("Cancel")
                assertTrue(fixture.committed.isEmpty(), "$kind saved a cancelled draft")
                assertFalse(fixture.open.value)
            }
        }
    }

    @Test
    fun delayedInitialMembershipLoadsOnceWithoutResettingLaterEdits() = onUiThread {
        Fixture(initiallyLoading = true).use { fixture ->
            fixture.render()
            assertTrue(fixture.action("Confirm").config.contains(SemanticsProperties.Disabled))
            fixture.initial.value = setOf(fixture.mainId)
            fixture.render()
            fixture.tap("Research")
            fixture.initial.value = emptySet()
            fixture.render()
            fixture.tap("Confirm")
            assertEquals(listOf(setOf(fixture.mainId, "research")), fixture.committed)
        }
    }

    @Test
    fun confirmationWaitsForSaveAndRejectsRepeatedClicks() = onUiThread {
        Fixture().use { fixture ->
            val gate = CompletableDeferred<Unit>()
            var attempts = 0
            fixture.save = { ids ->
                attempts++
                gate.await()
                fixture.committed += ids
            }
            fixture.render()
            val click = assertNotNull(fixture.action("Confirm").config.getOrNull(SemanticsActions.OnClick)?.action)
            click()
            click()
            fixture.render()
            assertEquals(1, attempts)
            assertTrue(fixture.open.value)
            assertTrue(fixture.action("Cancel").config.contains(SemanticsProperties.Disabled))
            gate.complete(Unit)
            fixture.render()
            assertEquals(1, fixture.committed.size)
            assertFalse(fixture.open.value)
        }
    }

    @Test
    fun failedSaveKeepsTheDraftOpenForRetry() = onUiThread {
        Fixture().use { fixture ->
            fixture.save = { error("Storage unavailable") }
            fixture.render()
            fixture.tap("Research")
            fixture.tap("Confirm")
            assertTrue(fixture.open.value)
            assertTrue(fixture.nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Storage unavailable" }
            })
            fixture.save = { fixture.committed += it }
            fixture.tap("Confirm")
            assertEquals(listOf(setOf(fixture.mainId, "research")), fixture.committed)
        }
    }

    @Test
    fun inlineCreationFinishesBeforeConfirmAndDoesNotResetSelections() = onUiThread {
        Fixture().use { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.create = { name ->
                gate.await()
                fixture.choices.value += "new" to name
                "new"
            }
            fixture.render()
            fixture.tap("Research")
            val field = fixture.nodes().single { it.config.contains(SemanticsActions.SetText) }
            assertNotNull(field.config.getOrNull(SemanticsActions.SetText)?.action)(AnnotatedString("New shelf"))
            fixture.render()
            val create = fixture.nodes().single { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Create category")
            }
            fixture.tap(create)
            assertTrue(fixture.action("Confirm").config.contains(SemanticsProperties.Disabled))
            gate.complete(Unit)
            fixture.render()
            fixture.tap("Confirm")
            assertEquals(listOf(setOf(fixture.mainId, "research", "new")), fixture.committed)
        }
    }

    private class Fixture(val kind: String = "manga", initiallyLoading: Boolean = false) : AutoCloseable {
        val mainId = when (kind) {
            "manga" -> MangaCategory.MAIN_ID
            "documents" -> DocumentCategory.MAIN_ID
            else -> Collection.MAIN_ID
        }
        val open = mutableStateOf(true)
        val initial = mutableStateOf<Set<String>?>(if (initiallyLoading) null else setOf(mainId))
        val choices = mutableStateOf(listOf(mainId to "Main", "research" to "Research"))
        val committed = mutableListOf<Set<String>>()
        var save: suspend (Set<String>) -> Unit = { committed += it }
        var create: suspend (String) -> String? = { "new" }
        private val controller = FolioOverlayController()
        private var frame = 0L
        private val scene = ImageComposeScene(393, 852) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                FolioGlassRoot(GlassCapabilities.None) {
                    CompositionLocalProvider(LocalFolioOverlay provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            if (open.value) {
                                when (kind) {
                                    "manga" -> CategoryPickerDialog(
                                        choices.value.map { (id, name) -> MangaCategory(id, name) }, initial.value,
                                        { create(it) }, { save(it) }, { open.value = false },
                                    )
                                    "documents" -> DocumentCategoryPickerDialog(
                                        choices.value.map { (id, name) -> DocumentCategory(id, name) }, requireNotNull(initial.value),
                                        { create(it) }, { save(it) }, { open.value = false },
                                    )
                                    "books" -> BookCollectionPickerDialog(
                                        choices.value.map { (id, name) -> Collection(id, name) }, requireNotNull(initial.value),
                                        { create(it) }, { save(it) }, { open.value = false },
                                    )
                                    "import" -> BookImportCollectionsDialog(
                                        2, choices.value.map { (id, name) -> Collection(id, name) },
                                        { create(it) }, { save(it) }, { open.value = false },
                                    )
                                }
                            }
                            FolioOverlayHost()
                        }
                    }
                }
            }
        }

        fun render() {
            repeat(10) {
                Snapshot.sendApplyNotifications()
                scene.render(frame++ * 100_000_000L).close()
            }
        }

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }

        fun action(text: String): SemanticsNode = nodes().single { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        fun tap(text: String) = tap(action(text))

        fun tap(node: SemanticsNode) {
            val point = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }

        override fun close() = scene.close()
    }

    private fun onUiThread(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
