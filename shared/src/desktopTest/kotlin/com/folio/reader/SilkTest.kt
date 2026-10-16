package com.folio.reader

import com.folio.reader.ui.theme.Silk
import com.folio.reader.ui.theme.silkVibranceFor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The silk field's contract, pinned.
 *
 * Every assertion here is one the Python oracle established by measurement and that the
 * Kotlin port could silently lose. The two implementations are compared against each other,
 * so a port that "looks right" but has a sign flipped in the width, or breaks the integer
 * `K_EPI` scroll that lets the roll close, fails here rather than on a device three builds later.
 */
class SilkTest {

    private val tau = (2.0 * PI).toFloat()
    private val aspect = 411f / 938f

    private fun surfaceAt(theta: Float, u: Float, p: Float): FloatArray {
        val out = FloatArray(2)
        Silk.surface(theta, u, 0, p, aspect, out, 0)
        return out
    }

    /**
     * The loop must close **exactly**, and for a structural reason: in the roll the only term
     * that depends on `p` is the mesh scroll `TWO_PI * K_EPI * p`, and `K_EPI` is an integer, so
     * the mesh advances a whole number of its own spatial periods per lap and lands back on
     * itself. Nothing else moves with `p`.
     *
     * This is the assertion that fails if anyone makes `K_EPI` non-integer or adds a `p` term
     * that is not a whole-period multiple (an "inward flow" that is not periodic cannot close:
     * the interference pattern would have to be destroyed at the centreline to return to its
     * start). The Python oracle made exactly that mistake and measured a 116/255 jump at the wrap.
     */
    @Test
    fun loopClosesExactly() {
        var worst = 0f
        for (u in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            var theta = 0f
            while (theta < tau) {
                val a = surfaceAt(theta, u, 0f)
                val b = surfaceAt(theta, u, 1f)
                worst = maxOf(worst, abs(a[0] - b[0]), abs(a[1] - b[1]))
                theta += tau / 97f
            }
        }
        assertTrue(worst < 1e-5f, "loop did not close: max |F(1)-F(0)| = $worst")
    }

    /**
     * Between any two phases the surface moves **only as far as the epicyclic term can carry
     * it** — at most `2 * EPS`.
     *
     * The first version of this test compared `Silk.width(theta, 0)` to itself, which is
     * trivially true: `width` takes no `p`, so the assertion could not fail no matter what
     * the code did. This one can. `L`, `w` and `n` carry no `p`, so `S(p) - S(p')` reduces to
     * `EPS * (g(p) - g(p'))`, and `g` is a unit vector, so the difference is bounded by
     * `2 * EPS = 0.012`. A reintroduced backbone drift moves the surface by up to `2 * AY =
     * 2.2` — about 180x the bound — so this catches it at every theta and u.
     */
    @Test
    fun onlyTheMeshMovesBetweenPhases() {
        var worst = 0f
        for (u in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (phases in arrayOf(0f to 0.5f, 0f to 0.25f, 0.3f to 0.8f)) {
                var theta = 0f
                while (theta < tau) {
                    val a = surfaceAt(theta, u, phases.first)
                    val b = surfaceAt(theta, u, phases.second)
                    worst = maxOf(worst, hypot(a[0] - b[0], a[1] - b[1]))
                    theta += tau / 53f
                }
            }
        }
        val bound = 2f * Silk.EPS
        assertTrue(
            worst <= bound * 1.001f,
            "surface moved $worst between phases, past the $bound the mesh alone can reach " +
                "— the structure is not frozen",
        )
    }

    /**
     * The signed width crosses zero exactly four times per turn — the four pinches where the
     * sheet twists and the edges swap sides.
     *
     * A `sqrt(kappa + cos^2)` width is never negative, so it touches zero but never crosses,
     * and the crisp X never forms. Counting crossings rather than minima is the point: minima
     * of |w| would also give four and would pass a ribbon that never twists.
     */
    @Test
    fun signedWidthCrossesZeroFourTimes() {
        var crossings = 0
        var prev = Silk.width(0f, 0)
        val steps = 20000
        for (i in 1..steps) {
            val w = Silk.width(i / steps.toFloat() * tau, 0)
            if ((prev < 0f) != (w < 0f)) crossings++
            prev = w
        }
        assertEquals(4, crossings, "the ribbon must twist exactly four times per turn")
    }

    /**
     * The vibrance band sweeps a whole number of times per loop, so it is back where it
     * started at `p = 1` — the same closure condition as every other phase term.
     */
    @Test
    fun vibranceBandCloses() {
        var worst = 0f
        for (u in floatArrayOf(0f, 0.5f, 1f)) {
            var theta = 0f
            while (theta < tau) {
                worst = maxOf(
                    worst,
                    abs(Silk.vibranceAt(theta, u, 0f, 1.3f) - Silk.vibranceAt(theta, u, 1f, 1.3f)),
                )
                theta += tau / 53f
            }
        }
        assertTrue(worst < 1e-5f, "vibrance band did not close: $worst")
    }

    /**
     * At the band's trough the multiplier is exactly 1, so a neutral stretch of the ribbon
     * is never tinted. Without this a theme's vibrance would raise the saturation of the
     * *whole* mesh, which is a global treatment rather than a travelling one.
     */
    @Test
    fun vibranceIsNeutralAtTheTrough() {
        var min = Float.MAX_VALUE
        var theta = 0f
        while (theta < tau) {
            min = minOf(min, Silk.vibranceAt(theta, 0f, 0f, 1.3f))
            theta += tau / 400f
        }
        assertTrue(abs(min - 1f) < 1e-3f, "trough should be neutral, got $min")
    }

    /**
     * The LOD scales **linearly** with the rendered ribbon width.
     *
     * The Python oracle multiplied by `W0` a second time, making the count scale as `W0^2` —
     * so its 150 px preview renders drew 12 strands where the 1080 px device drew 56, and
     * every visual judgement made from a preview was made against a different mesh than the
     * one that shipped. This asserts the linearity directly.
     */
    @Test
    fun strandCountIsLinearInRibbonWidth() {
        val a = Silk.strandCount(60f, ceiling = 1000)
        val b = Silk.strandCount(120f, ceiling = 1000)
        assertEquals(a * 2, b, "strand count must double when the ribbon doubles")
    }

    /** Every theme must carry a vibrance, and it must be a boost rather than a reduction. */
    @Test
    fun everyThemeHasAVibrance() {
        val ids = listOf(
            "classic", "paper", "forest", "ocean", "editorial", "blush", "acid",
        )
        for (id in ids) {
            val v = silkVibranceFor(id)
            assertTrue(v >= 1f, "$id has a vibrance below neutral: $v")
            // A boost large enough to clip a channel changes the hue rather than enriching
            // it, which is the one thing this must not do.
            assertTrue(v <= 1.6f, "$id has a vibrance high enough to clip: $v")
        }
        assertTrue(
            silkVibranceFor("classic") < silkVibranceFor("acid"),
            "a near-neutral room must want less vibrance than a saturated one",
        )
    }
}
