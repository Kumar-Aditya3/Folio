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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.folio.reader.database.SearchResult
import com.folio.reader.model.BookTocRow
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.reader.AnnotationsSidebar
import com.folio.reader.ui.reader.EchoesPanel
import com.folio.reader.ui.reader.EchoesState
import com.folio.reader.ui.reader.ReaderSearchPanel
import com.folio.reader.ui.reader.ReaderSettingsPanel
import com.folio.reader.ui.reader.ReaderTool
import com.folio.reader.ui.reader.ReaderWorkspace
import com.folio.reader.ui.reader.TOCSidebar
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionTier
import com.folio.reader.ui.theme.UiFonts
import com.folio.reader.ui.theme.resolveMotion
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReaderSearchFieldMotionTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/inter_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun toolCrossfadeKeepsSearchFieldAnchoredDuringImeAnimation() = onUi {
        for (outgoing in listOf(ReaderTool.Contents, ReaderTool.Saved, ReaderTool.Echoes, ReaderTool.Settings)) {
            for (reduced in listOf(false, true)) for (largeType in listOf(false, true)) {
                val fixture = Fixture(outgoing, reduced, largeType)
                try {
                    fixture.frames(40)
                    fixture.active.value = ReaderTool.Search
                    fixture.frames(2)
                    val initial = fieldBounds(fixture.scene)
                    val samples = animateIme(fixture)
                    assertStable(initial, samples,
                        "tool=$outgoing reduced=$reduced largeType=$largeType")
                } finally { fixture.scene.close() }
            }
        }
    }

    @Test fun directSearchEntryKeepsFieldAnchoredWhileSheetAndImeEnter() = onUi {
        for (largeType in listOf(false, true)) {
            val fixture = Fixture(null, reduced = false, largeType = largeType)
            try {
                fixture.frames(2)
                fixture.active.value = ReaderTool.Search
                fixture.frames(2)
                val initial = fieldBounds(fixture.scene)
                assertStable(initial, animateIme(fixture), "direct entry largeType=$largeType")
            } finally { fixture.scene.close() }
        }
    }

    @Test fun focusQueryAndResultStatesDoNotResizeOrRepositionTheMaterialField() = onUi {
        for (largeType in listOf(false, true)) {
            val fixture = Fixture(ReaderTool.Search, reduced = false, largeType = largeType)
            try {
                fixture.frames(40)
                val initial = fieldBounds(fixture.scene)
                assertEquals(true, field(fixture.scene).config.getOrNull(SemanticsProperties.Focused))
                fixture.clearFocus()
                fixture.frames(20)
                assertEquals(false, field(fixture.scene).config.getOrNull(SemanticsProperties.Focused))
                assertStable(initial, listOf(fieldBounds(fixture.scene)), "unfocused largeType=$largeType")
                assertNotNull(field(fixture.scene).config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke()
                fixture.frames(20)
                assertEquals(true, field(fixture.scene).config.getOrNull(SemanticsProperties.Focused))
                val samples = mutableListOf<Rect>()
                for ((query, searching, results) in listOf(
                    Triple("signal", true, emptyList()),
                    Triple("signal", false, listOf(SearchResult("book", "chapter", 0, "A chapter", "A quiet signal."))),
                    Triple("no match", false, emptyList()),
                    Triple("", false, emptyList()),
                )) {
                    fixture.query.value = query
                    fixture.searching.value = searching
                    fixture.results.value = results
                    repeat(20) { fixture.frames(1); samples += fieldBounds(fixture.scene) }
                }
                assertStable(initial, samples, "query/focus states largeType=$largeType")
            } finally { fixture.scene.close() }
        }
    }

    // Exercise the production imePadding(), not a test-only padding or a source-text assertion.
    private fun animateIme(fixture: Fixture): List<Rect> {
        val samples = mutableListOf<Rect>()
        for (bottom in (0..320 step 16) + (320 downTo 0 step 16)) {
            fixture.imeBottom.value = bottom
            fixture.frames(1)
            samples += fieldBounds(fixture.scene)
        }
        repeat(60) { fixture.frames(1); samples += fieldBounds(fixture.scene) }
        return samples
    }

    private fun assertStable(initial: Rect, samples: List<Rect>, context: String) {
        assertTrue(initial.height > 0f && initial.width > 0f, "Missing field geometry: $context")
        val tops = listOf(initial.top) + samples.map { it.top }
        assertTrue(tops.max() - tops.min() <= 1f,
            "Search field moved vertically: $context, initial=$initial, topRange=${tops.min()}..${tops.max()}")
        samples.forEach { bounds ->
            assertEquals(initial.height, bounds.height, 1f, "Field height changed: $context, bounds=$bounds")
        }
    }

    private fun field(scene: ImageComposeScene): SemanticsNode = scene.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
        .single { it.config.contains(SemanticsActions.SetText) }

    // boundsInRoot is clipped by the horizontal sheet entry; position+size measures the field itself.
    private fun fieldBounds(scene: ImageComposeScene): Rect = field(scene).let {
        Rect(it.positionInRoot, it.size.toSize())
    }

    private class Fixture(initial: ReaderTool?, reduced: Boolean, largeType: Boolean) {
        val active = mutableStateOf(initial)
        val imeBottom = mutableStateOf(0)
        val query = mutableStateOf("")
        val searching = mutableStateOf(false)
        val results = mutableStateOf<List<SearchResult>>(emptyList())
        var clearFocus: () -> Unit = {}
        private var frame = 0L
        private val insets = object : PlatformWindowInsets {
            override val ime = PlatformInsets(getBottom = { imeBottom.value })
        }
        private val motion = resolveMotion(MotionPreferences(reduceMotion =
            if (reduced) ReduceMotionMode.REDUCED else ReduceMotionMode.FULL), MotionTier.STANDARD, false)
        val scene = ImageComposeScene(if (largeType) 320 else 393, 852,
            density = Density(1f, if (largeType) 1.5f else 1f)) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                CompositionLocalProvider(LocalPlatformWindowInsets provides insets, LocalMotion provides motion) {
                    val focus = LocalFocusManager.current
                    clearFocus = { focus.clearFocus(force = true) }
                    Box(Modifier.fillMaxSize()) {
                        ReaderWorkspace(active.value,
                            listOf(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved, ReaderTool.Echoes, ReaderTool.Settings),
                            "A chapter", { active.value = it }, { active.value = null }) { tool ->
                            when (tool) {
                                ReaderTool.Search -> ReaderSearchPanel(query.value, { query.value = it }, results.value,
                                    searching.value, 0, {}, {})
                                ReaderTool.Contents -> TOCSidebar(listOf(BookTocRow("A chapter", 0)), 0, 0f, {}, {})
                                ReaderTool.Saved -> AnnotationsSidebar(emptyList(), emptyList(), emptyList(), {}, {}, {}, {})
                                ReaderTool.Echoes -> EchoesPanel(EchoesState.Idle, {}, { _, _, _ -> })
                                ReaderTool.Settings -> ReaderSettingsPanel(ReaderSettings(), {}, {}, {})
                                ReaderTool.Pages, ReaderTool.Listen -> Unit
                            }
                        }
                    }
                }
            }
        }

        fun frames(count: Int) {
            repeat(count) {
                Snapshot.sendApplyNotifications()
                scene.render(frame++ * 16_000_000L).close()
            }
        }
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}
