package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.RIM_PERIOD_MS
import com.folio.reader.ui.components.rimBeamColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards for the heroes' travelling rim (`FolioThemeRim.kt`).
 *
 * The rim is drawn app-wide — the book hero, the manga hero, each library grid cell — and the
 * failures here are the ones nobody sees before it ships. A rim that lights more than one arc, or
 * whose neighbouring samples differ enough to show as a cut, reads as decoration duplicating itself
 * instead of as one light moving. Neither is catchable by watching the app: a doubled beam looks
 * *better* in any single still frame, which is how it ships.
 *
 * The rest is Rule 19 (§13.2): an effect that silently does nothing is a bug. The beam must actually
 * reach full brightness somewhere, and it must light exactly one arc in both of the hues it is themed
 * on without either of them looking like it belongs to a different surface.
 */
class FolioThemeRimTest {

    private val accent = Color(0xFFE0A94F)
    private val counter = Color(0xFF6FC2B3)

    /**
     * Exactly one arc. This is the guard the earlier cut could not pass: a gradient
     * laid along a rotating *chord* of a closed outline crosses the edge twice, so it
     * could only ever draw two mirrored streaks.
     */
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
        fun distance(a: Color, b: Color) =
            maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        val lit = rimBeamColors(0.5f, accent, counter).filter { it.alpha > 0.01f }
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
}
