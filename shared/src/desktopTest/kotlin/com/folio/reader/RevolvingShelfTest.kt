package com.folio.reader

import androidx.compose.runtime.BroadcastFrameClock
import com.folio.reader.ui.home.HomeItemKind
import com.folio.reader.ui.home.ReadingNowItem
import com.folio.reader.ui.home.RevolvingShelfState
import com.folio.reader.ui.home.revolvingShelfItems
import com.folio.reader.ui.home.shelfDestination
import com.folio.reader.ui.home.shelfIndex
import com.folio.reader.ui.home.shelfKey
import com.folio.reader.ui.home.shelfPose
import com.folio.reader.ui.home.deskArtworkWeight
import com.folio.reader.ui.home.shelfReleaseTarget
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.motionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevolvingShelfTest {
    @Test
    fun artworkWeightsBlendContinuouslyAcrossWrapAndSmallLibraries() {
        for (count in 1..3) for (frame in -100..100) {
            val position = if (count == 2) (frame / 10f).coerceIn(0f, 1f) else frame / 10f
            val weights = (0 until count).map { deskArtworkWeight(it, position, count) }
            assertEquals(1f, weights.sum(), .0001f)
            assertTrue(weights.all { it in 0f..1f })
        }
        assertEquals(.5f, deskArtworkWeight(2, 2.5f, 3), .0001f)
        assertEquals(.5f, deskArtworkWeight(0, 2.5f, 3), .0001f)
        assertEquals(deskArtworkWeight(0, -.001f, 3), deskArtworkWeight(0, 2.999f, 3), .0001f)
    }

    @Test
    fun frontAndNeighborsStayLegibleThroughoutARevolution() {
        for (count in 1..3) for (width in listOf(260f, 360f, 560f)) {
            for (frame in -60..60) for (index in 0 until count) {
                val pose = shelfPose(index, frame / 10f, count, width, true)
                assertTrue(listOf(pose.x, pose.scale, pose.rotation, pose.depth).all { it.isFinite() })
                assertTrue(pose.scale in .58f..1f)
                assertTrue(abs(pose.rotation) <= 36f)
                assertTrue(abs(pose.x) < width / 2)
                val halfCover = minOf(width * .50f, 176f) * pose.scale / 2
                assertTrue(width / 2 + pose.x - halfCover >= 0f, "Cover left the leading edge")
                assertTrue(width / 2 + pose.x + halfCover <= width, "Cover left the trailing edge")
            }
        }
        val center = shelfPose(0, 0f, 3, 360f, true)
        val left = shelfPose(2, 0f, 3, 360f, true)
        val right = shelfPose(1, 0f, 3, 360f, true)
        assertEquals(-46.8f, center.x, .001f)
        assertEquals(0f, center.rotation, .001f)
        assertTrue(center.scale > left.scale)
        assertTrue(left.x > 0f && right.x > left.x, "Upcoming covers should retain the trailing stack")
        assertEquals(left.scale, right.scale, .001f)
        assertTrue(center.depth > right.depth)
        assertEquals(0f, shelfPose(1, 0f, 3, 360f, false).rotation)
    }

    @Test
    fun releaseProjectionUsesVelocityWithoutUnboundedSpins() {
        assertEquals(1f, shelfReleaseTarget(.1f, 6f, 3))
        assertEquals(-1f, shelfReleaseTarget(-.1f, -6f, 3))
        assertEquals(0f, shelfReleaseTarget(.1f, Float.NaN, 3))
        assertEquals(1f, shelfReleaseTarget(.1f, 1000f, 3))
        assertEquals(0f, shelfReleaseTarget(-.4f, -1000f, 2))
        assertEquals(1f, shelfReleaseTarget(1f, 1000f, 2))
        assertEquals(2, shelfIndex(-1f, 3))
        assertEquals(0, shelfIndex(3f, 3))
        assertEquals(-1f, shelfDestination(0f, 2, 3))
        assertEquals(3f, shelfDestination(2f, 0, 3))
    }

    @Test
    fun bookAndMangaIdsCanCoincideWithoutLosingACover() {
        fun book(id: String, kind: HomeItemKind = HomeItemKind.BOOK) = ReadingNowItem(kind, id, id, "", 0f)
        val book = book("same")
        val manga = book("same", HomeItemKind.MANGA)
        val shelf = revolvingShelfItems(listOf(book, book, manga, book("third"), book("fourth")))
        assertEquals(3, shelf.size)
        assertEquals(listOf(book, manga, book("third")), shelf)
        assertTrue(book.shelfKey() != manga.shelfKey())
    }

    @Test
    fun rapidTurnsAccumulateAndANewDragCancelsTheOldAnimation() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        val state = RevolvingShelfState(3, 0, scope)
        val spec = motionProfile(MotionKey.ReadingShelfTurn)
        var time = 0L
        fun frames(count: Int) { repeat(count) { time += 16_666_667L; clock.sendFrame(time) } }
        try {
            state.step(1, spec, true)
            frames(3)
            state.step(1, spec, true)
            frames(120)
            assertEquals(2, state.selectedIndex)
            assertFalse(state.moving)
            state.step(1, spec, true)
            frames(3)
            state.beginDrag()
            state.dragBy(-.25f)
            val held = state.position
            frames(120)
            assertEquals(held, state.position)
            assertTrue(state.moving)
            state.release(0f, spec, true)
            frames(120)
            assertFalse(state.moving)
            assertEquals(state.position.toInt().toFloat(), state.position)
        } finally { scope.cancel() }
    }

    @Test
    fun reducedMotionAndSmallLibrariesRemainFullyUsable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val spec = motionProfile(MotionKey.ReadingShelfTurn)
        try {
            val state = RevolvingShelfState(3, 0, scope)
            state.select(2, spec, false)
            assertEquals(2, state.selectedIndex)
            assertFalse(state.moving)
            state.step(1, spec.copy(durationMs = 0, spring = null), true)
            assertEquals(0, state.selectedIndex)
            val pair = RevolvingShelfState(2, 0, scope)
            pair.beginDrag()
            pair.dragBy(-10f)
            assertEquals(0f, pair.position)
            pair.dragBy(20f)
            assertEquals(1f, pair.position)
            pair.release(20f, spec, false)
            assertEquals(1, pair.selectedIndex)
            val single = RevolvingShelfState(1, 0, scope)
            single.step(1, spec, false)
            assertEquals(0f, single.position)
            assertFalse(single.moving)
        } finally { scope.cancel() }
    }
}
