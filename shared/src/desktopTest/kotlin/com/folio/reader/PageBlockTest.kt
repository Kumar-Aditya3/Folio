package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.PAGE_BLOCK_MIN_EDGE_SHARE
import com.folio.reader.ui.components.pageBlockBandFraction
import com.folio.reader.ui.components.pageBlockChapterStops
import com.folio.reader.ui.components.pageBlockGeometry
import com.folio.reader.ui.components.pageBlockPalette
import com.folio.reader.ui.components.pageBlockSeekTarget
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page block's pure geometry: the diegetic progress cue that replaced the
 * reader's bottom bar and its "3 / 12".
 *
 * The block is a codex seen edge-on, so the invariant that matters is physical —
 * two stacks of pages that always add up to the same block. If they ever gapped or
 * overlapped the cue would stop reading as one object and start reading as two bars,
 * which is exactly the widget it was built to replace. These tests pin that, the
 * minimum edge that keeps the cue from vanishing at both extremes, the RTL mirror,
 * and the book↔block mapping the seek gesture depends on.
 */
class PageBlockTest {

    /** The block's drawn thickness in the reader chrome, in dp — the unit the geometry works in here. */
    private val thickness = 18f

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    @Test
    fun unreadStackShrinksAsTheReadStackGrows() {
        var previousRead = -1f
        var previousUnread = Float.MAX_VALUE
        for (step in 0..100) {
            val geo = pageBlockGeometry(step / 100f, thickness)
            assertTrue(
                geo.readThickness >= previousRead,
                "read stack went backwards at $step%: ${geo.readThickness} after $previousRead"
            )
            assertTrue(
                geo.unreadThickness <= previousUnread,
                "unread stack grew at $step%: ${geo.unreadThickness} after $previousUnread"
            )
            previousRead = geo.readThickness
            previousUnread = geo.unreadThickness
        }
        // And not merely non-decreasing: a cue that stalled would be unreadable as
        // motion, so every step has to move both stacks.
        for (step in 1..100) {
            val before = pageBlockGeometry((step - 1) / 100f, thickness)
            val after = pageBlockGeometry(step / 100f, thickness)
            assertTrue(
                after.readThickness > before.readThickness,
                "read stack did not advance between ${step - 1}% and $step%"
            )
            assertTrue(
                after.unreadThickness < before.unreadThickness,
                "unread stack did not thin between ${step - 1}% and $step%"
            )
        }
    }

    @Test
    fun theExtremesAreExactInverses() {
        val empty = pageBlockGeometry(0f, thickness)
        val finished = pageBlockGeometry(1f, thickness)
        val edge = thickness * PAGE_BLOCK_MIN_EDGE_SHARE

        assertEquals(edge, empty.readThickness, 1e-3f)
        assertEquals(thickness - edge, empty.unreadThickness, 1e-3f)
        // At the last page the block is the mirror of the first one: everything read,
        // one leaf still standing under the right thumb.
        assertEquals(thickness - edge, finished.readThickness, 1e-3f)
        assertEquals(edge, finished.unreadThickness, 1e-3f)
        assertEquals(empty.readThickness, finished.unreadThickness, 1e-3f)
        assertEquals(empty.unreadThickness, finished.readThickness, 1e-3f)

        // Halfway the two stacks are equal, which is what makes the cue readable at a
        // glance without any numerals.
        val middle = pageBlockGeometry(0.5f, thickness)
        assertEquals(middle.readThickness, middle.unreadThickness, 1e-3f)
        assertEquals(thickness / 2f, middle.readThickness, 1e-3f)
    }

    @Test
    fun aMinimumEdgeSurvivesAtBothExtremes() {
        val floor = thickness * PAGE_BLOCK_MIN_EDGE_SHARE
        assertTrue(floor > 0f, "the minimum edge must be a visible thickness")
        assertTrue(PAGE_BLOCK_MIN_EDGE_SHARE < 0.5f, "a minimum edge at half the block would freeze the cue")
        for (step in 0..100) {
            val geo = pageBlockGeometry(step / 100f, thickness)
            assertTrue(
                geo.readThickness >= floor - 1e-4f,
                "read stack vanished at $step%: ${geo.readThickness}"
            )
            assertTrue(
                geo.unreadThickness >= floor - 1e-4f,
                "unread stack vanished at $step%: ${geo.unreadThickness}"
            )
        }
        // The default is what guarantees it: with no minimum edge the cue disappears
        // at both ends of the book.
        val noEdge = pageBlockGeometry(0f, thickness, minEdgeShare = 0f)
        assertEquals(0f, noEdge.readThickness, 1e-4f)
    }

    @Test
    fun theStacksAlwaysSumToTheWholeBlock() {
        for (total in listOf(0f, 4f, thickness, 42.5f, 96f)) {
            for (step in 0..100) {
                val geo = pageBlockGeometry(step / 100f, total)
                assertEquals(
                    total,
                    geo.readThickness + geo.unreadThickness,
                    1e-3f,
                    "the stacks stopped filling the block at $step% of $total"
                )
                assertEquals(total, geo.totalThickness, 1e-4f)
                assertTrue(geo.readThickness >= 0f, "negative read stack at $step%")
                assertTrue(geo.unreadThickness >= 0f, "negative unread stack at $step%")
                assertTrue(geo.readThickness <= total + 1e-4f, "read stack overflowed the block at $step%")
            }
        }
    }

    @Test
    fun rightToLeftMirrorsTheBlock() {
        val ltr = pageBlockGeometry(0.25f, thickness, rtl = false)
        val rtl = pageBlockGeometry(0.25f, thickness, rtl = true)

        // Thickness is a property of the book, not of its script.
        assertEquals(ltr.readThickness, rtl.readThickness, 1e-4f)
        assertEquals(ltr.unreadThickness, rtl.unreadThickness, 1e-4f)
        assertEquals(ltr.readLines, rtl.readLines)
        assertEquals(ltr.unreadLines, rtl.unreadLines)

        // Left to right the read stack is the leading run; mirrored, it is the trailing one.
        assertEquals(0f, ltr.readStart, 1e-4f)
        assertEquals(0.25f, ltr.readEnd, 1e-4f)
        assertEquals(0.25f, ltr.unreadStart, 1e-4f)
        assertEquals(1f, ltr.unreadEnd, 1e-4f)
        assertEquals(0.75f, rtl.readStart, 1e-4f)
        assertEquals(1f, rtl.readEnd, 1e-4f)
        assertEquals(0f, rtl.unreadStart, 1e-4f)
        assertEquals(0.75f, rtl.unreadEnd, 1e-4f)
        assertEquals(0.25f, ltr.leaf, 1e-4f)
        assertEquals(0.75f, rtl.leaf, 1e-4f)

        // Neither direction may leave a gap or an overlap along the width.
        for (mirrored in listOf(false, true)) {
            val geo = pageBlockGeometry(0.62f, thickness, pageCountHint = 40, rtl = mirrored)
            assertEquals(0f, minOf(geo.readStart, geo.unreadStart), 1e-4f)
            assertEquals(1f, maxOf(geo.readEnd, geo.unreadEnd), 1e-4f)
            // The leaf is the seam: one stack ends there and the other starts there.
            assertEquals(geo.leaf, if (geo.mirrored) geo.unreadEnd else geo.readEnd, 1e-4f)
            assertEquals(geo.leaf, if (geo.mirrored) geo.readStart else geo.unreadStart, 1e-4f)
        }
    }

    @Test
    fun leafEdgesFollowThePitchAndThePageCount() {
        // A stack shows as many edges as it holds, capped by what fits at a legible pitch.
        val mid = pageBlockGeometry(0.5f, thickness, pageCountHint = 12)
        assertEquals(3, mid.readLines, "9dp at a 2.5dp pitch holds three edges")
        assertEquals(3, mid.unreadLines)

        // A two-leaf chapter is two leaves, however thick the stack is drawn.
        val pamphlet = pageBlockGeometry(0.5f, thickness, pageCountHint = 2)
        assertEquals(1, pamphlet.readLines)

        // A long book is capped by the pitch, not by its own page count.
        val novel = pageBlockGeometry(1f, thickness, pageCountHint = 400)
        assertEquals(6, novel.readLines, "15.48dp at a 2.5dp pitch holds six edges")
        assertEquals(1, novel.unreadLines, "the minimum edge still shows its one leaf")

        // An unknown page count still draws the leaf the reader is holding.
        val unknown = pageBlockGeometry(0.5f, thickness, pageCountHint = 0)
        assertEquals(1, unknown.readLines)

        // The ceiling keeps a thick block from hatching into noise.
        val capped = pageBlockGeometry(0.5f, 200f, pageCountHint = 5000, maxLines = 4)
        assertEquals(4, capped.readLines)
        assertEquals(4, capped.unreadLines)
    }

    @Test
    fun degenerateInputsStayInsideTheBlock() {
        val nan = pageBlockGeometry(Float.NaN, thickness)
        assertEquals(0f, nan.fraction, 1e-4f)
        val negative = pageBlockGeometry(-3f, thickness)
        assertEquals(0f, negative.fraction, 1e-4f)
        val pastTheEnd = pageBlockGeometry(7f, thickness)
        assertEquals(1f, pastTheEnd.fraction, 1e-4f)
        val noRoom = pageBlockGeometry(0.4f, -12f)
        assertEquals(0f, noRoom.totalThickness, 1e-4f)
        assertEquals(0f, noRoom.readThickness, 1e-4f)
        assertEquals(0f, noRoom.unreadThickness, 1e-4f)
        assertEquals(0, noRoom.readLines)
        val unmeasured = pageBlockGeometry(0.4f, Float.NaN)
        assertEquals(0f, unmeasured.totalThickness, 1e-4f)
        // A minimum edge at half the block is clamped: past that the cue could not move.
        val clamped = pageBlockGeometry(0.9f, thickness, minEdgeShare = 1f)
        assertEquals(thickness / 2f, clamped.readThickness, 1e-3f)
    }

    @Test
    fun chapterStopsAreWordWeightedAndSpanTheBook() {
        val stops = pageBlockChapterStops(listOf(1_000L, 3_000L, 2_000L))
        assertEquals(4, stops.size, "one stop more than there are chapters")
        assertEquals(0f, stops.first(), 1e-4f)
        assertEquals(1f, stops.last(), 1e-4f)
        assertEquals(1f / 6f, stops[1], 1e-3f)
        assertEquals(4f / 6f, stops[2], 1e-3f)
        for (i in 1 until stops.size) {
            assertTrue(stops[i] >= stops[i - 1], "chapter stops went backwards at $i: $stops")
        }

        assertEquals(listOf(0f, 1f), pageBlockChapterStops(listOf(500L)))
        assertEquals(listOf(0f, 1f), pageBlockChapterStops(emptyList()))
    }

    @Test
    fun chapterStopsFallBackToUniformWeighting() {
        // No word counts is not the same as an empty book: the chapters still have to
        // divide the block, or the notches would all pile up at the start.
        val stops = pageBlockChapterStops(listOf(0L, 0L, 0L))
        assertEquals(4, stops.size)
        assertEquals(1f / 3f, stops[1], 1e-3f)
        assertEquals(2f / 3f, stops[2], 1e-3f)
        assertEquals(1f, stops[3], 1e-4f)

        // A negative count is treated as no words rather than as negative length.
        val guarded = pageBlockChapterStops(listOf(-5L, 10L))
        assertEquals(3, guarded.size)
        assertEquals(0f, guarded[1], 1e-4f)
        assertEquals(1f, guarded[2], 1e-4f)
    }

    @Test
    fun aSeekLandsWhereTheCueSaysItWill() {
        val stops = pageBlockChapterStops(listOf(1_000L, 3_000L, 2_000L))
        for (chapter in 0..2) {
            val places = if (chapter == 2) listOf(0f, 0.25f, 0.5f, 0.75f, 1f) else listOf(0f, 0.25f, 0.5f, 0.75f)
            for (place in places) {
                val shown = pageBlockBandFraction(stops, chapter, chapter, place)
                val seek = pageBlockSeekTarget(stops, shown)
                assertEquals(chapter, seek.chapterIndex, "a drag to $shown left chapter $chapter")
                assertEquals(place, seek.chapterFraction, 1e-3f, "a drag to $shown missed the place in the chapter")
            }
        }
    }

    @Test
    fun theEndOfOneChapterIsTheStartOfTheNext() {
        val stops = pageBlockChapterStops(listOf(1_000L, 3_000L, 2_000L))
        val endOfFirst = pageBlockBandFraction(stops, 0, 0, 1f)
        assertEquals(stops[1], endOfFirst, 1e-4f)
        val seek = pageBlockSeekTarget(stops, endOfFirst)
        assertEquals(1, seek.chapterIndex, "the boundary belongs to the chapter that starts there")
        assertEquals(0f, seek.chapterFraction, 1e-4f)
        // The last page of the book still resolves inside the last chapter.
        val finished = pageBlockSeekTarget(stops, 1f)
        assertEquals(2, finished.chapterIndex)
        assertEquals(1f, finished.chapterFraction, 1e-3f)
    }

    @Test
    fun aRenderedWindowMapsOntoTheWholeBook() {
        val stops = pageBlockChapterStops(listOf(1_000L, 3_000L, 2_000L))
        // Continuous mode renders several chapters at once: half of that document is
        // not half of the book, and the block must not claim otherwise.
        val wholeWindow = pageBlockBandFraction(stops, 0, 2, 0.5f)
        assertEquals(0.5f, wholeWindow, 1e-4f)
        val secondChapter = pageBlockBandFraction(stops, 1, 1, 0.5f)
        assertEquals(1f / 6f + 0.5f * 0.5f, secondChapter, 1e-3f)
        val pairOfChapters = pageBlockBandFraction(stops, 1, 2, 1f)
        assertEquals(1f, pairOfChapters, 1e-4f)

        // Out-of-range bands and unmeasurable fractions stay inside the book.
        assertEquals(1f, pageBlockBandFraction(stops, 9, 99, 1f), 1e-4f)
        assertEquals(0f, pageBlockBandFraction(stops, -4, -1, 0f), 1e-4f)
        val unmeasured = pageBlockBandFraction(stops, 1, 1, Float.NaN)
        assertEquals(stops[1], unmeasured, 1e-4f)
        for (step in 0..100) {
            val mapped = pageBlockBandFraction(stops, 0, 2, step / 100f)
            assertTrue(mapped in 0f..1f, "the window mapping left the book at $step%: $mapped")
        }
        // A single-chapter document is its own whole book.
        val solo = pageBlockChapterStops(listOf(800L))
        assertEquals(0.37f, pageBlockBandFraction(solo, 0, 0, 0.37f), 1e-4f)
        // And with no stops at all the fraction passes through untouched.
        assertEquals(0.42f, pageBlockBandFraction(emptyList(), 0, 0, 0.42f), 1e-4f)
        assertEquals(0, pageBlockSeekTarget(emptyList(), 0.42f).chapterIndex)
        assertEquals(0.42f, pageBlockSeekTarget(emptyList(), 0.42f).chapterFraction, 1e-4f)
    }

    @Test
    fun thePaperIsLitByItsOwnModel() {
        val darkPaper = Color(0xFF121212)
        val darkInk = Color(0xFFE8EAED)
        val lightPaper = Color(0xFFFFF8EC)
        val lightInk = Color(0xFF3B2A14)

        // A dark page gets its depth from emission: the fore-edge brightens above the
        // paper and the body falls away below it.
        val dark = pageBlockPalette(darkPaper, darkInk)
        assertTrue(luminance(dark.slabTop) > luminance(darkPaper), "a dark page must emit at its fore-edge")
        assertTrue(luminance(dark.slabBase) < luminance(darkPaper), "a dark page must still fall away at the base")
        assertTrue(luminance(dark.rim) > luminance(dark.slabTop), "the rim is the brightest thing on a dark page")
        assertTrue(luminance(dark.striae) > luminance(darkPaper), "leaf edges are lit, not inked, on a dark page")
        assertEquals(Color.Black, dark.foxing, "the only wear a black page can show is the absence of light")

        // A light page gets it from occlusion: a sheen on the fore-edge, shadow toward
        // the ink at the base, and ink for the leaf edges.
        val light = pageBlockPalette(lightPaper, lightInk)
        assertTrue(luminance(light.slabTop) > luminance(lightPaper), "a light page must catch a sheen")
        assertTrue(luminance(light.slabBase) < luminance(lightPaper), "a light page must deepen into occlusion")
        assertTrue(luminance(light.striae) < luminance(lightPaper), "leaf edges are inked on a light page")
        assertTrue(luminance(light.foxing) < luminance(lightPaper), "foxing is wear, so it darkens paper")
        assertTrue(luminance(light.outline) < luminance(light.slabTop), "the silhouette must separate the block from its ground")
    }
}
