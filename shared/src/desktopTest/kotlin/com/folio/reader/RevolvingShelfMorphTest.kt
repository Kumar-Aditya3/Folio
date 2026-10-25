package com.folio.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioSharedElementProvider
import com.folio.reader.ui.components.FolioSharedElementScope
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.home.HomeItemKind
import com.folio.reader.ui.home.ReadingNowItem
import com.folio.reader.ui.home.RevolvingReadingShelf
import com.folio.reader.ui.home.RevolvingShelfState
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.MotionTier
import com.folio.reader.ui.theme.UiFonts
import com.folio.reader.ui.theme.resolveMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.IdentityHashMap
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalSharedTransitionApi::class)
class RevolvingShelfMorphTest {
    @BeforeTest fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/inter_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test fun libraryToHomeMorphLandsOnTheDrawnFrontCardWithoutOvershootOrHop() = onUi {
        for ((count, selected) in listOf(2 to 1, 3 to 1, 3 to 2)) {
            for (width in listOf(320, 393)) for (direction in LayoutDirection.entries) {
                val run = capture(Case(width, count, selected, direction))
                assertHarness(run)
                assertLanding(run)
                assertContinuous(run)
                report(run)
            }
        }
    }

    @Test fun reducedShelfMotionKeepsTheMorphLandingOnTheSamePhysicalCard() = onUi {
        for ((width, count, selected) in listOf(Triple(320, 2, 1), Triple(393, 3, 2))) {
            for (direction in LayoutDirection.entries) {
                // Desktop rememberMotionEnabled() is always true. LocalMotion.REDUCED exercises
                // the real shelf's zero-duration/no-rotation branch, not Android's animator gate.
                val run = capture(Case(width, count, selected, direction, reduced = true))
                assertHarness(run)
                assertLanding(run)
                assertContinuous(run)
                report(run)
            }
        }
    }

    @Test fun centeredSlotWithParentTranslationIsRejectedByTheSameRasterOracle() = onUi {
        for (direction in LayoutDirection.entries) {
            val run = capture(Case(393, 3, 2, direction), centeredSlotControl = true)
            assertHarness(run)
            val target = landingTarget(run)
            assertTrue(abs(target.center.x - run.frames.last().plate.center.x) > run.stage.width * .10f,
                "Control did not reproduce an untransformed shared target: ${run.case}")
            assertTrue(handoffDistance(run).first > run.stage.width * .08f,
                "Control did not produce a visible raster hop: ${run.case}")
            assertFailsWith<AssertionError> { assertLanding(run) }
            assertFailsWith<AssertionError> { assertContinuous(run) }
            report(run)
        }
    }

    private data class Case(
        val width: Int,
        val count: Int,
        val selected: Int,
        val direction: LayoutDirection,
        val reduced: Boolean = false,
    )

    private data class Frame(val time: Long, val plate: Rect, val pixels: Int,
        val shared: List<Rect>, val active: Boolean, val matched: Boolean)
    private data class Card(val bounds: Rect, val size: IntSize)
    private data class Run(val case: Case, val source: Rect, val stage: Rect, val card: Card,
        val frames: List<Frame>, val targets: List<Rect>, val initialBounds: List<Rect>, val control: Boolean)

    private fun capture(case: Case, centeredSlotControl: Boolean = false): Run {
        val fixture = Fixture(case, centeredSlotControl)
        try {
            repeat(4) { fixture.render(measure = false) }
            val source = fixture.nodes().single {
                it.config.getOrNull(SemanticsProperties.TestTag) == SOURCE_TAG
            }.boundsInRoot
            val samples = mutableListOf(fixture.render())
            fixture.home.value = true
            repeat(48) { samples += fixture.render() }
            val nodes = fixture.nodes()
            val stage = nodes.single {
                "Revolving reading shelf" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
            val front = nodes.single {
                it.config.getOrNull(SemanticsProperties.Selected) == true &&
                    "Open details for ${fixture.items[case.selected].title}" in
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
            assertEquals("${case.selected + 1} of ${case.count}",
                stage.config.getOrNull(SemanticsProperties.StateDescription), case.toString())
            assertEquals(case.selected, fixture.controller.selectedIndex, case.toString())
            if (!centeredSlotControl) {
                assertEquals(case.count, nodes.count {
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description ->
                        description.startsWith("Open details for ") || description.startsWith("Bring Folio morph ")
                    }
                }, "The actual shelf must contain all covers: $case")
            }
            return Run(case, source, stage.boundsInRoot, Card(front.boundsInRoot, front.size), samples,
                fixture.probe.targets.toList(), fixture.probe.initialBounds.toList(), centeredSlotControl)
        } finally { fixture.close() }
    }

    private fun assertHarness(run: Run) {
        val context = run.case.toString()
        assertTrue(run.frames.count { it.active && it.matched && it.shared.isNotEmpty() } >= 8,
            "No real paired shared-element flight was measured: $context, frames=${run.frames}")
        assertTrue(!run.frames.first().active && !run.frames.last().active, "Morph did not settle: $context")
        assertEquals(116f, run.source.width, 1f, "Fixture is the Library's featured plate: $context")
        assertEquals(run.case.width - 48f, run.stage.width, 1f, "Shelf gutter/constraints: $context")
        val coverWidth = minOf(run.stage.width * .5f, 176f)
        assertEquals(coverWidth.roundToInt(), run.card.size.width, "Actual fixed cover width: $context")
        assertEquals((coverWidth * FolioTokens.coverAspect).roundToInt(), run.card.size.height,
            "Actual fixed cover height: $context")
        assertEquals(coverWidth * FolioTokens.coverAspect + 28f, run.stage.height, 1f,
            "Actual stage height: $context")
        val direction = if (run.case.direction == LayoutDirection.Rtl) -1f else 1f
        val restingX = run.stage.left + ((run.stage.width.roundToInt() - run.card.size.width) / 2) +
            (-.13f * run.stage.width * direction).roundToInt() + run.card.size.width / 2f
        assertEquals(restingX, run.frames.last().plate.center.x, 2f,
            "Drawn front card lost the shelf's offset resting pose: $context")
        assertEquals(run.stage.top + 14f + run.card.size.height / 2f,
            run.frames.last().plate.center.y, 2f, "Drawn front card left its resting height: $context")
        assertTrue(run.initialBounds.any { abs(it.center.x - run.source.center.x) <= 1f &&
            abs(it.center.y - run.source.center.y) <= 1f }, "Wrong shared source: $context")
        for (frame in run.frames) {
            assertTrue(frame.pixels > 10_000 && frame.pixels / (frame.plate.width * frame.plate.height) > .80f,
                "Artwork missing, clipped, or duplicated at ${frame.time}: $context, frame=$frame")
        }
        assertEquals(run.card.bounds.center.x, run.frames.last().plate.center.x, 2f,
            "Raster did not measure the final selected cover: $context")
        assertEquals(run.card.bounds.center.y, run.frames.last().plate.center.y, 2f,
            "Raster did not measure the final selected cover: $context")
    }

    private fun landingTarget(run: Run): Rect = assertNotNull(run.targets.lastOrNull {
        abs(it.width - run.card.size.width) <= 1f && abs(it.height - run.card.size.height) <= 1f
    }, "No shelf-sized lookahead target: ${run.case}, targets=${run.targets}")

    private fun assertLanding(run: Run) {
        val target = landingTarget(run)
        val finalPlate = run.frames.last().plate
        // Rotation expands the raster AABB but cannot move its centre. Comparing centres and
        // separately checking measured size avoids confusing the retained -1.5 degree tilt with x.
        assertEquals(finalPlate.center.x, target.center.x, 2f,
            "Shared lookahead aimed at an untransformed slot, not the drawn front card: ${run.case}, " +
                "target=$target, final=$finalPlate")
        assertEquals(finalPlate.center.y, target.center.y, 2f,
            "Shared target and drawn front card disagree vertically: ${run.case}")
        run.targets.filter { abs(it.width - target.width) <= 1f && abs(it.height - target.height) <= 1f }
            .forEach {
                assertEquals(target.center.x, it.center.x, 1f, "Morph target moved: ${run.case}")
                assertEquals(target.center.y, it.center.y, 1f, "Morph target moved: ${run.case}")
            }
        run.frames.takeLast(8).forEach {
            assertEquals(finalPlate.center.x, it.plate.center.x, 1f, "Post-flight lateral drift: ${run.case}")
            assertEquals(finalPlate.center.y, it.plate.center.y, 1f, "Post-flight vertical drift: ${run.case}")
        }
    }

    private fun assertContinuous(run: Run) {
        val first = run.frames.first().plate.center
        val last = run.frames.last().plate.center
        for ((name, values, from, to) in listOf(
            Axis("x", run.frames.map { it.plate.center.x }, first.x, last.x),
            Axis("y", run.frames.map { it.plate.center.y }, first.y, last.y),
        )) {
            val direction = if (to >= from) 1f else -1f
            assertTrue(abs(to - from) > 20f, "Fixture does not exercise $name travel: ${run.case}")
            values.forEachIndexed { index, value ->
                assertTrue(value >= minOf(from, to) - 2f && value <= maxOf(from, to) + 2f,
                    "Drawn cover overshot $name at frame $index: ${run.case}, endpoints=$from..$to, values=$values")
                if (index > 0) assertTrue((value - values[index - 1]) * direction >= -2f,
                    "Drawn cover reversed/hopped on $name at frame $index: ${run.case}, values=$values")
            }
        }
        run.frames.filter { it.active && it.matched }.forEach { frame ->
            frame.shared.forEach { bounds ->
                assertEquals(bounds.center.x, frame.plate.center.x, 2f,
                    "Rendered artwork detached from shared bounds on x: ${run.case}, frame=$frame")
                assertEquals(bounds.center.y, frame.plate.center.y, 2f,
                    "Rendered artwork detached from shared bounds on y: ${run.case}, frame=$frame")
            }
        }
        val (dx, dy) = handoffDistance(run)
        assertTrue(dx <= 3f && dy <= 3f,
            "Overlay-to-front-card handoff hopped: ${run.case}, dx=$dx, dy=$dy")
    }

    private data class Axis(val name: String, val values: List<Float>, val from: Float, val to: Float)

    private fun handoffDistance(run: Run): Pair<Float, Float> {
        val index = run.frames.indexOfLast { it.active }
        assertTrue(index in 1 until run.frames.lastIndex, "No overlay handoff: ${run.case}")
        val before = run.frames[index].plate.center
        val after = run.frames[index + 1].plate.center
        return abs(before.x - after.x) to abs(before.y - after.y)
    }

    private fun report(run: Run) {
        println("RevolvingShelfMorphTest ${run.case} control=${run.control} " +
            "target=${landingTarget(run)} raster=${run.frames.last().plate} " +
            "flightFrames=${run.frames.count { it.active }} handoff=${handoffDistance(run)}")
    }

    // SharedContentState in 1.12.1 has no public bounds accessor. Observe the production
    // modifier's spec and clip callbacks, forwarding all parameters and the original clip unchanged.
    private class Probe(val key: Any) {
        val states = mutableSetOf<SharedTransitionScope.SharedContentState>()
        val targets = mutableListOf<Rect>()
        val initialBounds = mutableListOf<Rect>()
        val frameBounds = mutableMapOf<SharedTransitionScope.SharedContentState, Rect>()
    }

    private class ObservingScope(private val real: SharedTransitionScope, private val probe: Probe) :
        SharedTransitionScope by real {
        private val transforms = IdentityHashMap<BoundsTransform, BoundsTransform>()
        private val clips = mutableMapOf<Pair<SharedTransitionScope.SharedContentState,
            SharedTransitionScope.OverlayClip>, SharedTransitionScope.OverlayClip>()

        override fun Modifier.sharedElement(
            sharedContentState: SharedTransitionScope.SharedContentState,
            animatedVisibilityScope: AnimatedVisibilityScope,
            boundsTransform: BoundsTransform,
            placeholderSize: SharedTransitionScope.PlaceholderSize,
            renderInOverlayDuringTransition: Boolean,
            zIndexInOverlay: Float,
            clipInOverlayDuringTransition: SharedTransitionScope.OverlayClip,
        ): Modifier {
            val tracked = sharedContentState.key == probe.key
            if (tracked) probe.states += sharedContentState
            val transform = if (!tracked) boundsTransform else transforms.getOrPut(boundsTransform) {
                BoundsTransform { initial, target ->
                    probe.initialBounds += initial
                    probe.targets += target
                    boundsTransform.createAnimationSpec(initial, target)
                }
            }
            val clip = if (!tracked) clipInOverlayDuringTransition else
                clips.getOrPut(sharedContentState to clipInOverlayDuringTransition) {
                    object : SharedTransitionScope.OverlayClip {
                        override fun getClipPath(state: SharedTransitionScope.SharedContentState, bounds: Rect,
                            layoutDirection: LayoutDirection, density: Density): Path? {
                            probe.frameBounds[state] = bounds
                            return clipInOverlayDuringTransition.getClipPath(state, bounds, layoutDirection, density)
                        }
                    }
                }
            return with(real) {
                this@sharedElement.sharedElement(sharedContentState, animatedVisibilityScope, transform,
                    placeholderSize, renderInOverlayDuringTransition, zIndexInOverlay, clip)
            }
        }
    }

    private inner class Fixture(val case: Case, private val control: Boolean) {
        val home = mutableStateOf(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RevolvingShelfState(case.count, case.selected, scope)
        private val artwork = Artwork(case)
        val items = (0 until case.count).map { index ->
            ReadingNowItem(HomeItemKind.BOOK, "shelf-morph-$index", "Folio morph $index", "Fixture author",
                .35f, coverPath = artwork.paths[index])
        }
        val probe = Probe(FolioSharedKeys.bookCover(items[case.selected].id))
        private val motion = resolveMotion(MotionPreferences(reduceMotion =
            if (case.reduced) ReduceMotionMode.REDUCED else ReduceMotionMode.FULL), MotionTier.STANDARD, false)
        private val raster = Bitmap().apply {
            check(allocPixels(ImageInfo(case.width, 852, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)))
        }
        private var clock = 0L
        private var transitionScope: SharedTransitionScope? = null
        val scene = ImageComposeScene(case.width, 852, density = Density(1f), layoutDirection = case.direction) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                CompositionLocalProvider(LocalMotion provides motion) {
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        transitionScope = this
                        val observing = remember(this) { ObservingScope(this, probe) }
                        FolioSharedElementProvider(observing, tabMorphActive = true) {
                            val duration = motion[MotionKey.TabDissolve].durationMs.toInt()
                            AnimatedContent(home.value, Modifier.fillMaxSize(), transitionSpec = {
                                ContentTransform(fadeIn(tween(duration), initialAlpha = 1f),
                                    fadeOut(keyframes { durationMillis = duration; 0f at 0 }), sizeTransform = null)
                            }, label = "Library to revolving shelf") { showHome ->
                                FolioSharedElementScope(this) {
                                    Box(Modifier.fillMaxSize().background(Color(0xFF151515))) {
                                        if (showHome) {
                                            Box(Modifier.fillMaxWidth().absoluteOffset(y = 160.dp)
                                                .padding(horizontal = 24.dp)) {
                                                if (control) CenteredSlotControl(items[case.selected], case)
                                                else RevolvingReadingShelf(items, controller, null, {}, {})
                                            }
                                        } else {
                                            val item = items[case.selected]
                                            FolioCoverPlate(item.coverPath, item.title, item.subtitle,
                                                Modifier.offset(x = 36.dp, y = 376.dp).testTag(SOURCE_TAG)
                                                    .sharedElementOrNoop(FolioSharedKeys.bookCover(item.id)),
                                                width = 116.dp, elevation = FolioTokens.elevationRaised)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap {
            it.getAllSemanticsNodes(mergingEnabled = true)
        }

        fun render(measure: Boolean = true): Frame {
            Snapshot.sendApplyNotifications()
            probe.frameBounds.clear()
            val time = clock++ * 16_000_000L
            val image = scene.render(time)
            try {
                if (!measure) return Frame(time, Rect.Zero, 0, emptyList(), false, false)
                check(image.readPixels(raster)) { "Scene raster readback failed: $case" }
                val pixels = requireNotNull(raster.readPixels(raster.imageInfo, raster.rowBytes, 0, 0))
                var left = case.width
                var top = 852
                var right = -1
                var bottom = -1
                var count = 0
                for (y in 0 until 852) for (x in 0 until case.width) {
                    val offset = y * raster.rowBytes + x * 4
                    val r = pixels[offset].toInt() and 255
                    val g = pixels[offset + 1].toInt() and 255
                    val b = pixels[offset + 2].toInt() and 255
                    if (r > 110 && b > 100 && g < 70 && r - g > 70 && b - g > 70 && abs(r - b) < 65) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                        count++
                    }
                }
                check(count > 0) { "No fixture artwork rendered at $time: $case" }
                return Frame(time, Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f), count,
                    probe.frameBounds.values.toList(), transitionScope?.isTransitionActive == true,
                    probe.states.any { it.isMatchFound })
            } finally { image.close() }
        }

        fun close() {
            try { scene.close() } finally {
                controller.stop()
                scope.cancel()
                raster.close()
                artwork.close()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CenteredSlotControl(item: ReadingNowItem, case: Case) {
        val width = minOf((case.width - 48) * .5f, 176f).dp
        val height = width * FolioTokens.coverAspect
        val direction = if (case.direction == LayoutDirection.Rtl) -1f else 1f
        // Deliberately wrong only in this fixture: the old parent-layer x leaves lookahead
        // at the centred slot. The production shelf is never substituted in the positive tests.
        Layout(modifier = Modifier.fillMaxWidth().height(height + 28.dp).semantics {
            contentDescription = "Revolving reading shelf"
            stateDescription = "${case.selected + 1} of ${case.count}"
        }, content = {
            Box(Modifier.clickable(role = Role.Button) {}.semantics(mergeDescendants = true) {
                selected = true
                contentDescription = "Open details for ${item.title}"
            }) {
                FolioCoverPlate(item.coverPath, item.title, item.subtitle,
                    Modifier.sharedElementOrNoop(FolioSharedKeys.bookCover(item.id)), width = width,
                    elevation = FolioTokens.elevationRaised)
            }
        }) { measurables, constraints ->
            val cover = measurables.single().measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
            layout(constraints.maxWidth, constraints.maxHeight) {
                cover.placeWithLayer((constraints.maxWidth - cover.width) / 2, 14.dp.roundToPx()) {
                    translationX = -.13f * constraints.maxWidth * direction
                    rotationZ = -1.5f * direction
                    scaleX = 1f
                    scaleY = 1f
                }
            }
        }
    }

    // Seed only these paths in the production image cache. There is no file/network IO,
    // decode coroutine, entry fade, sleep, or dependence on covers installed on this machine.
    private class Artwork(case: Case) {
        val paths = (0 until case.count).map { "__folio_morph_test__/$case/$it" }
        private val owner = Class.forName("com.folio.reader.ui.components.BookCoverKt")
        private val put = owner.getDeclaredMethod("cachePut", String::class.java, ImageBitmap::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private val cache = owner.getDeclaredField("coverCache").apply { isAccessible = true }.get(null)
            as MutableMap<String, ImageBitmap>
        private val bytes = owner.getDeclaredField("coverCacheBytes").apply { isAccessible = true }

        init {
            paths.forEachIndexed { index, path ->
                val bitmap = ImageBitmap(176, 264)
                Canvas(bitmap).drawRect(Rect(0f, 0f, 176f, 264f), Paint().apply {
                    color = if (index == case.selected) Color(0xFFF010E0)
                        else if (index == 0) Color(0xFF10B9E8) else Color(0xFF20D56A)
                })
                put.invoke(null, path, bitmap)
            }
        }

        fun close() = synchronized(cache) {
            var removed = 0L
            paths.forEach { path -> cache.remove(path)?.let { removed += it.width.toLong() * it.height * 4L } }
            bytes.setLong(null, bytes.getLong(null) - removed)
        }
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    private companion object { const val SOURCE_TAG = "library-featured-morph-source" }
}
