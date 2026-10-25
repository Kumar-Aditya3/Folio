package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.*
import com.folio.reader.model.*
import com.folio.reader.settings.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.reader.*
import com.folio.reader.ui.render.ReaderCss
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ReaderExperienceTest {
    private var frame = 0L
    @Test fun scrubbingPreviewsWithoutWritingPositionAndCommitsExactlyOnce() = onUi {
        for (trace in listOf(false, true)) for (rtl in listOf(false, true)) {
            val commits = mutableListOf<Float>()
            val previews = mutableListOf<Float?>()
            val scene = ImageComposeScene(320, 80) {
                FolioTheme.MaterialTheme(darkTheme = true) {
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides
                        if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        if (trace) ReadingTraceLine(ReadingTrace(progress = .2f), Modifier.fillMaxSize(),
                            onSeek = { commits += it }, onPreview = { previews += it })
                        else PageBlock(.2f, Modifier.fillMaxSize(), onSeek = { commits += it },
                            onPreview = { previews += it }, rtl = rtl)
                    }
                }
            }
            try {
                render(scene)
                scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 40f))
                render(scene)
                scene.sendPointerEvent(PointerEventType.Move, Offset(224f, 40f))
                render(scene)
                assertTrue(commits.isEmpty(), "Preview wrote a reading position: trace=$trace rtl=$rtl")
                assertTrue(previews.any { it != null }, "No local preview was emitted")
                scene.sendPointerEvent(PointerEventType.Release, Offset(224f, 40f))
                render(scene)
                assertEquals(1, commits.size)
                assertEquals(if (rtl) .3f else .7f, commits.single(), .015f)
                assertNull(previews.last(), "The preview did not dismiss after commit")
                commits.clear()
                scene.sendPointerEvent(PointerEventType.Press, Offset(90f, 40f))
                render(scene)
                scene.setContent { Box(Modifier.fillMaxSize()) }
                render(scene)
                assertTrue(commits.isEmpty(), "Disposing a preview committed a cancelled gesture")
                assertNull(previews.last())
            } finally { scene.close() }
        }
    }

    @Test fun workingPaneOpacityRemainsMonotonicAndHasAnExactSolidEndpoint() {
        assertEquals(1f, readerWorkspaceFill(1f))
        assertEquals(0f, readerWorkspaceFill(0f))
        assertTrue(readerWorkspaceFill(.9f) > .95f)
        assertTrue((0..100).map { readerWorkspaceFill(it / 100f) }.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun chapterCompositionIsNormalizedOnlyAndWorksInBothRenderingPaths() {
        for (mode in FormattingMode.entries) {
            val settings = ReaderSettings(formattingMode = mode)
            val paged = ReaderCss.styleSheet(settings, 1, { it }, "")
            val continuous = ReaderCss.shadowStyleSheet(settings)
            for (sheet in listOf(paged, continuous)) {
                assertEquals(mode == FormattingMode.NORMALIZED, "h1::after{content:''" in sheet)
                if (mode == FormattingMode.NORMALIZED) {
                    assertTrue("font-family:'FolioDisplay',sans-serif !important;font-weight:500" in sheet)
                    assertTrue("break-after:avoid" in sheet)
                    assertTrue("body h1,body h2{font-family:" in sheet, "Display type must outrank normalized body-heading font rules")
                }
            }
        }
    }

    @Test fun nativeReaderAndWorkspaceAcrossEveryThemeAndNarrowLargeType() = onUi {
        installFonts()
        for (palette in AppPalette.entries) {
            renderFixture(palette, 393, 1f, ReaderTool.Saved)
            renderFixture(palette, 393, 1f, ReaderTool.Settings)
        }
        renderFixture(AppPalette.COBALT, 320, 1.45f, ReaderTool.Contents)
        renderFixture(AppPalette.PORCELAIN, 320, 1.45f, ReaderTool.Settings)
    }

    @Test fun toolsSwitchInsideOneWorkspaceAndDismissWithoutInvokingNavigation() = onUi {
        val active = mutableStateOf<ReaderTool?>(ReaderTool.Contents)
        val selected = mutableListOf<ReaderTool>()
        val scene = ImageComposeScene(393, 800) {
            FolioTheme.AppTheme(AppPalette.TEAL, motion = MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED)) {
                ReaderWorkspace(active.value, listOf(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved, ReaderTool.Settings),
                    "Small Worlds", onSelect = { selected += it; active.value = it }, onDismiss = { active.value = null }) {
                    Box(Modifier.fillMaxSize()) { Text("Workspace ${it.label}") }
                }
            }
        }
        try {
            render(scene)
            clickTool(scene, "Search")
            assertEquals(ReaderTool.Search, active.value)
            clickTool(scene, "Saved")
            assertEquals(ReaderTool.Saved, active.value)
            clickTool(scene, "Close reader tools")
            assertNull(active.value)
            assertEquals(listOf(ReaderTool.Search, ReaderTool.Saved), selected)
        } finally { scene.close() }
    }

    private fun renderFixture(palette: AppPalette, width: Int, scale: Float, tool: ReaderTool) {
        val controller = FolioOverlayController()
        val scene = ImageComposeScene(width, 852, density = Density(1f, scale)) {
            FolioTheme.AppTheme(palette, motion = MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED)) {
                FolioGlassRoot(GlassCapabilities.None) {
                    CompositionLocalProvider(LocalFolioOverlay provides controller) {
                        Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                            Column(Modifier.padding(horizontal = 26.dp, vertical = 96.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("The quiet between", style = FolioTheme.typography.headlineMedium)
                                repeat(8) { Text("The city grew quieter as the light moved across the windows. There was still time to stay a little longer.",
                                    style = FolioTheme.typography.bodyLarge) }
                            }
                            ReaderTopBar("Small Worlds", false, false, null, null, {}, false, Color.Yellow,
                                {}, {}, {}, {}, {}, {})
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                BottomPageBlock("The quiet between", .63f, FolioTheme.colors.background,
                                    FolioTheme.colors.onSurface, "Page 124 of 196", pagesLeftLabel = "12 left",
                                    readingTrace = ReadingTrace(.63f, listOf(ReadingTraceSpan(.1f, .63f, 30f))), onSeek = {})
                            }
                            ReaderWorkspace(tool, listOf(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved, ReaderTool.Echoes, ReaderTool.Settings),
                                "Small Worlds · The quiet between", {}, {}) { current ->
                                when (current) {
                                    ReaderTool.Settings -> ReaderSettingsPanel(ReaderSettings(themeId = palette.id), {}, {}, {}, scopeControlEnabled = true)
                                    ReaderTool.Contents -> TOCSidebar(listOf(BookTocRow("The quiet between", 0),
                                        BookTocRow("A city in the margins", 1), BookTocRow("All the things we carry with us", 2)), 1, .4f, {}, {})
                                    else -> AnnotationsSidebar(emptyList(), listOf(Highlight("one", "book", "chapter", 1,
                                        "/1/2:0", "/1/2:end", "There was still time to stay a little longer. Perhaps that was what the city had been trying to say.", deviceId = "test")),
                                        emptyList(), {}, {}, {}, {}, chapterLabel = { _, _ -> "A city in the margins" })
                                }
                            }
                            FolioOverlayHost()
                        }
                    }
                }
            }
        }
        try {
            render(scene)
            val image = scene.render(frame++ * 100_000_000L)
            try { File("build/visual-review/reader-experience/${palette.id}-$width-$scale-${tool.name.lowercase()}.png")
                .apply { parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            } finally { image.close() }
        } finally { scene.close() }
    }

    private fun installFonts() {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
    }
    private fun render(scene: ImageComposeScene) {
        repeat(10) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 100_000_000L).close() }
    }
    private fun tap(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)); render(scene)
    }
    private fun clickTool(scene: ImageComposeScene, label: String) {
        val node = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                it.config.contains(SemanticsActions.OnClick)
        }
        assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        render(scene)
    }
    private fun onUi(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
