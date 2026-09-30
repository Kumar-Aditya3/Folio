package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.RIM_ALPHA_GAIN
import com.folio.reader.ui.components.RIM_BEAM_SPAN
import com.folio.reader.ui.components.RIM_HEAD_ALPHA
import com.folio.reader.ui.components.RIM_PARKED_HEAD
import com.folio.reader.ui.components.RIM_PERIOD_MS
import com.folio.reader.ui.components.RIM_SETTLE_MS
import com.folio.reader.ui.components.RIM_SPAN_GAIN
import com.folio.reader.ui.components.rimBeamColors
import com.folio.reader.ui.components.rimBeamShape
import com.folio.reader.ui.components.rimParkedHead
import com.folio.reader.ui.components.rimSpeedGain
import com.folio.reader.ui.components.rimSpeedLevel
import com.folio.reader.ui.components.rimVelocityLevel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards for the heroes' travelling rim (`FolioThemeRim.kt`).
 *
 * The rim is drawn on the Home hero and on each shelf's featured entry — books and
 * manga — and the failures here are the ones nobody sees before it ships. A rim that
 * lights more than one arc, or whose neighbouring samples differ enough to show as a
 * cut, reads as decoration duplicating itself instead of as one light moving. Neither
 * is catchable by watching the app: a doubled beam looks *better* in any single still
 * frame, which is how it ships.
 *
 * The rest is Rule 19 (§13.2): an effect that silently does nothing is a bug. The beam
 * must actually reach full brightness somewhere, and it must light exactly one arc in
 * both of the hues it is themed on without either of them looking like it belongs to a
 * different surface.
 *
 * The second half of the file guards the two things scroll speed and reading progress
 * may *not* do. Speed may make the one light bigger and brighter; it may not add a
 * second mover, re-colour the beam, or leave the rim stuck lit after the shelf has
 * stopped. Progress may move where the light rests; it may not move how fast it
 * travels.
 */
class FolioThemeRimTest {

    private val accent = Color(0xFFE0A94F)
    private val counter = Color(0xFF6FC2B3)

    /** Exactly one arc. This is the guard the earlier cut could not pass: a gradient
     *  laid along a rotating *chord* of a closed outline crosses the edge twice, so it
     *  could only ever draw two mirrored streaks. */
    @Test
    fun beamLightsExactlyOneArc() {
        val lit = rimBeamColors(0.5f, accent, counter).map { it.alpha > 0.01f }
        var runs = 0
        for (i in lit.indices) {
            if (lit[i] && !lit[(i - 1 + lit.size) % lit.size]) runs++
        }
        assertEquals(1, runs, "the beam lights $runs separate arcs at once")
        val width = lit.count { it }
        assertTrue(width in 8..lit.size / 2, "the arc is $width of ${lit.size} samples wide")
    }

    /** No step between neighbours big enough to read as a cut in the travelling light. */
    @Test
    fun beamHasNoHardEdge() {
        for (turn in listOf(0f, 0.17f, 0.5f, 0.83f, 0.99f)) {
            val colors = rimBeamColors(turn, accent, counter)
            for (i in colors.indices) {
                // Modulo, so the seam where the sweep's last sample meets its first is
                // measured like every other pair rather than waved through.
                val step = abs(colors[i].alpha - colors[(i + 1) % colors.size].alpha)
                assertTrue(step < 0.2f, "alpha steps $step between samples at turn $turn")
            }
        }
    }

    /** Lights properly, dies to nothing, and is brightest exactly where it was told. */
    @Test
    fun beamPeaksWhereTheClockSaysAndFadesToNothing() {
        val alphas = rimBeamColors(0.7f, accent, counter).map { it.alpha }
        val peak = alphas.max()
        assertTrue(peak > 0.6f, "the rim never lights: peak alpha $peak")
        assertEquals(0f, alphas.min(), absoluteTolerance = 1e-3f, "the arc leaves a residue all round the plate")
        val centre = alphas.indexOf(peak) / alphas.size.toFloat()
        assertTrue(abs(centre - 0.7f) < 0.03f, "the arc's brightest point is at $centre, not the 0.7 given")
    }

    /**
     * A clock restart must change nothing. Compared with a tolerance, not by equality:
     * the wrap is `x - floor(x)`, and 1.15f - 1f is not bit-identical to 0.15f, so an
     * exact check would fail on a difference of one part in ten million.
     */
    @Test
    fun beamIsTheSamePictureAtTheTopAndBottomOfAWholeTurn() {
        val atZero = rimBeamColors(0f, accent, counter)
        val atOne = rimBeamColors(1f, accent, counter)
        for (i in atZero.indices) {
            val a = atZero[i]
            val b = atOne[i]
            val drift = maxOf(
                abs(a.red - b.red),
                abs(a.green - b.green),
                abs(a.blue - b.blue),
                abs(a.alpha - b.alpha),
            )
            assertTrue(drift < 0.01f, "the beam changes by $drift when the turn wraps at sample $i")
        }
    }

    /**
     * Both hues survive, and neither looks foreign. The trailing tip is the FIRST lit
     * sample — `behind` grows with the sample index, so the arc's tail is at its start.
     */
    @Test
    fun trailTintsTowardTheThemeWithoutTakingOverTheSurface() {
        val lit = beamLitSamples(0.5f, rimBeamShape(0f))
        val trail = lit.first()
        assertTrue(
            distance(trail, accent) > 0.02f,
            "the trail carries none of the theme hue, so a book hero and a manga hero edge the same",
        )
        assertTrue(
            distance(trail, counter) > distance(trail, accent),
            "the trail is the raw theme colour — a stripe that belongs to nothing on the plate",
        )
        val middle = lit[lit.size / 2]
        assertTrue(
            distance(middle, accent) < distance(middle, counter),
            "the lit part of the arc is not the surface's own hue",
        )
    }

    /** §13.4, and the reason the 12s turn was cut: a hero's own motion lives here. */
    @Test
    fun rimTurnSitsInsideTheSlowMotionBand() {
        assertTrue(RIM_PERIOD_MS in 18_000L..30_000L, "the rim circuits in ${RIM_PERIOD_MS}ms")
    }

    // ── speed ───────────────────────────────────────────────────────────────────

    /**
     * The identity, exactly. A shelf that is not moving must draw the beam that
     * shipped before speed existed — sample for sample, channel for channel, with no
     * tolerance. `FolioAmbient` holds the same standard ("exact at Neutral, so the
     * identity is byte-for-byte"): a `0f * gain` term that leaves a residue of 1e-8,
     * or lands on -0f, is a picture nobody looked at being shipped as one nobody
     * changed.
     */
    @Test
    fun aStillShelfDrawsTheBeamThatShippedBeforeSpeed() {
        val (span, headAlpha) = rimBeamShape(0f)
        assertEquals(RIM_BEAM_SPAN, span, "rest widens the arc at all")
        assertEquals(RIM_HEAD_ALPHA, headAlpha, "rest lifts the arc's brightness at all")
        for (turn in listOf(0f, 0.31f, 0.7f, 0.99f)) {
            val implicit = rimBeamColors(turn, accent, counter)
            val explicit = rimBeamColors(turn, accent, counter, span, headAlpha)
            for (i in implicit.indices) {
                assertEquals(implicit[i], explicit[i], "rest differs from the shipped beam at $i")
            }
        }
    }

    /**
     * The two budgets, stated as the ceilings they are. Past these the rim stops
     * reading as a light getting stronger: at a full turn of lit outline the whole
     * edge glows, and at alpha near 1 on the crisp ring the edge becomes a drawn
     * border — a second object on the plate, which is the one thing the whole design
     * of this effect exists to avoid.
     */
    @Test
    fun fullSpeedStaysInsideTheLightAndNeverBecomesABorder() {
        val (span, headAlpha) = rimBeamShape(1f)
        // 0.45, not 0.5: past half the turn the lit run wraps and meets its own tail,
        // which is how a single beam becomes two.
        assertTrue(span <= 0.45f, "a fling opens the arc to $span of a turn — it wraps")
        assertTrue(headAlpha <= 0.90f, "a fling lifts the head to $headAlpha — the edge reads as a border")
        assertTrue(span > RIM_BEAM_SPAN, "speed does nothing at all")
        assertTrue(headAlpha > RIM_HEAD_ALPHA, "speed does nothing at all")
    }

    /** One light, at every speed and in every position. The doubling test re-run across
     *  the matrix the modulation actually visits. */
    @Test
    fun speedNeverSplitsTheBeam() {
        for (level in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val (span, headAlpha) = rimBeamShape(level)
            for (turn in listOf(0f, 0.17f, 0.5f, 0.83f, 0.99f)) {
                val colors = rimBeamColors(turn, accent, counter, span, headAlpha)
                val lit = colors.map { it.alpha > 0.01f }
                var runs = 0
                for (i in lit.indices) {
                    if (lit[i] && !lit[(i - 1 + lit.size) % lit.size]) runs++
                }
                assertEquals(1, runs, "at level $level turn $turn the beam lights $runs arcs")
                val litCount = lit.count { it }
                assertTrue(
                    colors.size - litCount >= colors.size * 0.40f,
                    "at level $level only $litCount of ${colors.size} samples are dark — the whole edge is glowing",
                )
                for (i in colors.indices) {
                    val step = abs(colors[i].alpha - colors[(i + 1) % colors.size].alpha)
                    assertTrue(step < 0.2f, "at level $level the arc shows a cut: $step between samples")
                }
            }
        }
    }

    /**
     * Speed may change how much of the outline is lit and how bright it is. It may not
     * introduce a colour. Everything the beam can ever show is a mix of the surface's
     * own hue and the theme role it trails into, so every channel must stay between
     * those two — a colour outside that box is a hue no other part of the surface
     * speaks, which is the exact defect "they need to blend in, not separate" names.
     */
    @Test
    fun speedCannotIntroduceAHueTheSurfaceDoesNotAlreadyCarry() {
        val lo = floatArrayOf(
            minOf(accent.red, counter.red),
            minOf(accent.green, counter.green),
            minOf(accent.blue, counter.blue),
        )
        val hi = floatArrayOf(
            maxOf(accent.red, counter.red),
            maxOf(accent.green, counter.green),
            maxOf(accent.blue, counter.blue),
        )
        for (level in listOf(0f, 0.5f, 1f)) {
            val (span, headAlpha) = rimBeamShape(level)
            for (color in rimBeamColors(0.42f, accent, counter, span, headAlpha)) {
                val channels = floatArrayOf(color.red, color.green, color.blue)
                for (c in channels.indices) {
                    assertTrue(
                        channels[c] in (lo[c] - 1e-3f)..(hi[c] + 1e-3f),
                        "channel $c is ${channels[c]} at level $level, outside the surface's own pair",
                    )
                }
            }
        }
    }

    /** The brightness budget touches alpha and nothing else — a lift that also moved
     *  hue would fail the test above only by luck of the sampling. */
    @Test
    fun liftingTheHeadChangesOnlyAlpha() {
        val (span, rest) = rimBeamShape(0f)
        val lifted = rimBeamColors(0.61f, accent, counter, span, rest * (1f + RIM_ALPHA_GAIN))
        val atRest = rimBeamColors(0.61f, accent, counter, span, rest)
        for (i in atRest.indices) {
            val a = atRest[i]
            val b = lifted[i]
            assertEquals(a.red, b.red, "sample $i changed hue when the head lifted")
            assertEquals(a.green, b.green, "sample $i changed hue when the head lifted")
            assertEquals(a.blue, b.blue, "sample $i changed hue when the head lifted")
            assertTrue(b.alpha >= a.alpha, "sample $i went dimmer as the head lifted")
            // The ratio is only measurable where one 8-bit step is small: Compose
            // stores alpha as a byte, so a sample at 0.05 alpha can only ever be
            // 13 or 14/255 and the ratio of two such samples is off by four percent
            // whatever the code does. Near the arc's ends the envelope, not the lift,
            // sets the number — and the monotone assertion above already covers them.
            if (a.alpha > 0.2f) {
                assertTrue(
                    abs(b.alpha / a.alpha - (1f + RIM_ALPHA_GAIN)) < 0.05f,
                    "sample $i's alpha scaled by ${b.alpha / a.alpha}, not ${1f + RIM_ALPHA_GAIN}",
                )
            }
        }
    }

    /** The quadratic is the reason this can ship: a drag is most of a scroll session,
     *  and it must not put the rim in a state nobody reviewed. Only a fling arrives. */
    @Test
    fun aDragIsInvisibleAndOnlyAFlingArrives() {
        val oneFrame = 16_666_667L
        val drag = rimSpeedLevel(30f, oneFrame)
        val fling = rimSpeedLevel(200f, oneFrame)
        assertTrue(rimSpeedGain(drag) < 0.1f, "a finger drag already opens the beam by ${rimSpeedGain(drag)}")
        assertEquals(1f, fling, "a real fling should saturate the level")
        assertEquals(1f, rimSpeedGain(fling))
        assertEquals(0f, rimSpeedLevel(0f, oneFrame), "no travel still lit the rim")
        // Direction is not speed: a fling upward is exactly as fast as one downward,
        // and the rim must not care which way the reader is going.
        assertEquals(rimSpeedLevel(40f, oneFrame), rimSpeedLevel(-40f, oneFrame))
    }

    /**
     * The settle is provable rather than asymptotic. `rimVelocityLevel` is a pure
     * function of how long ago the shelf last moved, precisely so it needs no
     * coroutine to reach zero — a scheduled decay would die with the featured cell
     * when the grid disposes it and leave the rim lit.
     */
    @Test
    fun theBeamIsProvablyAtRestOnceTheShelfStops() {
        assertEquals(0f, rimVelocityLevel(1f, RIM_SETTLE_MS), "still lit at the end of the window")
        assertEquals(0f, rimVelocityLevel(1f, RIM_SETTLE_MS * 4L), "still lit long after the shelf stopped")
        assertEquals(0f, rimVelocityLevel(0f, 0L), "a resting shelf is not already at rest")
        assertEquals(1f, rimVelocityLevel(1f, 0L), "the envelope eats the speed of a live fling")
        val mid = rimVelocityLevel(1f, RIM_SETTLE_MS / 2L)
        assertTrue(mid in 0f..1f && mid < 1f, "the decay does not fall monotonically: $mid at half age")
        assertTrue(
            rimVelocityLevel(1f, RIM_SETTLE_MS - 1L) < 0.01f,
            "the envelope ends on a step instead of fading into rest",
        )
    }

    // ── progress ────────────────────────────────────────────────────────────────

    /** A surface that reports no progress — or a book not started — parks exactly
     *  where the rim has always parked. */
    @Test
    fun noProgressParksWhereTheRimAlwaysDid() {
        assertEquals(RIM_PARKED_HEAD, rimParkedHead(null), "an absent progress moved the light")
        assertEquals(RIM_PARKED_HEAD, rimParkedHead(0f), "an unstarted book moved the light")
    }

    /**
     * Progress is a rest position, not a speed. Compared *cyclically*, because the
     * head wraps at a whole turn and a plain greater-than is meaningless across the
     * seam. The swing is capped at half a turn precisely so a 0% book and a 100% one
     * cannot land on the same point by wrapping — the light has to keep meaning
     * something all the way to the end.
     */
    @Test
    fun progressMovesTheRestPointWithoutWrappingOrRacing() {
        val start = rimParkedHead(0f)
        for (p in listOf(0.25f, 0.5f, 0.75f)) {
            val moved = cyclicDistance(start, rimParkedHead(p))
            assertTrue(
                abs(moved - p * 0.5f) < 1e-4f,
                "at $p through the book the light has moved $moved of a turn, not ${p * 0.5f}",
            )
        }
        val end = rimParkedHead(1f)
        assertNotEquals(start, end, "a finished book parks where an unstarted one does")
        assertTrue(
            cyclicDistance(start, end) <= 0.5f + 1e-4f,
            "progress swings the light past half a turn",
        )
        assertEquals(end, rimParkedHead(2f), "progress past 1.0 is not clamped")
        assertEquals(start, rimParkedHead(-1f), "progress below 0 is not clamped")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Lit samples in arc order, tail first. */
    private fun beamLitSamples(turn: Float, shape: Pair<Float, Float>) =
        rimBeamColors(turn, accent, counter, shape.first, shape.second).filter { it.alpha > 0.01f }

    private fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    /** Distance between two positions on a closed turn, so the seam at 1.0 is not a cliff. */
    private fun cyclicDistance(a: Float, b: Float): Float {
        val d = abs(a - b)
        return minOf(d, 1f - d)
    }
}
