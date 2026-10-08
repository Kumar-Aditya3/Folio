package com.folio.reader

import com.folio.reader.ui.theme.GLASS_TRAVEL_KAPPA
import com.folio.reader.ui.theme.GLASS_TRAVEL_KAPPA_MEAN
import com.folio.reader.ui.theme.GLASS_TRAVEL_PERIOD_MS
import com.folio.reader.ui.theme.GUILLOCHE_PERIODS_MS
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage R3 — "the animated edge", as a contract.
 *
 * The film's own AGSL cannot be run here (it is Android-only and compiled by the runtime), so
 * what this pins is the *arithmetic* the AGSL is written against, restated in Kotlin. That is
 * the point: the claim "the time term moves the terms but never enlarges them" is a claim
 * about three formulas, and formulas are testable even when the shader that hosts them is not.
 * If someone widens `rimTravel` past a mean of 1, or lets the glint's life exceed 1, or swaps
 * the ray slide for an amplitude change, these fail — and each of those would be a silent
 * brightening of a surface that may have body text sitting on it.
 *
 * The two rules being defended:
 *  - **A5b, at every instant.** The plate's quiet regions already bound the *pattern*; the film
 *    is bounded by `intensity`, and the travel must not spend past that bound at any phase, not
 *    just on average. An average-preserving term that spikes at one moment is still a
 *    regression.
 *  - **One tempo, no beat.** Four layers share the slow clock. Any two periods with a small
 *    integer ratio will phase-lock into a visible pulse; the periods are chosen far apart, and
 *    that choice is only worth having if it is written down where a refactor can break it.
 */
class GlassTravelTest {

    /** The AGSL's raised cosine remapped to `[0, 1]` — `travel01` in GlassShader.android.kt. */
    private fun travel01(p: Float): Float = 0.5f - 0.5f * cos(p)

    /**
     * The AGSL's rim travel — the von Mises kernel on the angle difference, divided by its
     * own mean so the mean over a lap is exactly 1.
     */
    private fun rimTravel(theta: Float, phase: Float, kappa: Float = GLASS_TRAVEL_KAPPA): Float =
        exp(kappa * (cos(theta - phase) - 1f)) / GLASS_TRAVEL_KAPPA_MEAN

    /** The AGSL's glint life. */
    private fun glintLife(phase: Float): Float = 0.35f + 0.65f * travel01(phase + 1.1f)

    /** The AGSL's ray fringe offset — a phase shift, so it slides rather than brightens. */
    private fun rayOffset(phase: Float): Float = cos(phase) * 0.22f

    private val twoPi = 6.2831855f
    private fun samples(n: Int = 720): List<Float> = (0 until n).map { it * twoPi / n }

    // ── the redistribution contract ─────────────────────────────────────────

    @Test
    fun `the rim travels rather than brightening`() {
        // THE central claim of Stage R3. The raw kernel peaks at 1 but averages only ~0.234,
        // so handing it to the rim weight would brighten the edge 4.3x *on average* — an
        // addition wearing a rotation's clothes, and exactly the "extravagant" failure the
        // brief rules out. The division by the kernel's own mean is what makes it a
        // redistribution: the rim's total light along the silhouette is now phase-invariant.
        //
        // Which is what this checks: the mean is 1 at *every* theta, i.e. rotating the
        // highlight around the edge never changes how much light the edge carries in total.
        for (theta in samples(48)) {
            val mean = samples(720).map { rimTravel(theta, it) }.average()
            assertTrue(
                abs(mean - 1.0) < 0.02,
                "rimTravel mean must stay ~1 at theta=$theta, was $mean — the rim is a " +
                    "redistribution, so anything above 1 is the edge getting brighter",
            )
        }
    }

    @Test
    fun `the normaliser is the kernel's actual mean`() {
        // The reciprocal has to match the kernel it divides, or the mean drifts off 1 in one
        // direction and the "redistribution" claim quietly becomes false. This pins the
        // constant the AGSL carries against the integral it is supposed to be.
        val measured = samples(20_000).map { exp(GLASS_TRAVEL_KAPPA * (cos(it) - 1f)) }.average()
        assertTrue(
            abs(measured - GLASS_TRAVEL_KAPPA_MEAN) < 0.001,
            "GLASS_TRAVEL_KAPPA_MEAN=$GLASS_TRAVEL_KAPPA_MEAN but the kernel means $measured",
        )
    }

    @Test
    fun `the rim never exceeds its own peak`() {
        // The normalised peak is 1/mean ~= 4.27, reached only at the travelling point. The
        // invariant that matters is not "below 4.27" but "the *same* 4.27 at every phase":
        // the brightest a fragment on the silhouette ever gets does not depend on time, so a
        // still frame and a live one are bounded identically. That is what makes it safe to
        // sit body text on a hero without checking what the clock is doing.
        val peak = 1f / GLASS_TRAVEL_KAPPA_MEAN
        for (phase in samples(96)) {
            val atPeak = rimTravel(phase, phase)
            assertTrue(abs(atPeak - peak) < 0.02f, "peak should be $peak, was $atPeak")
            for (theta in samples(96)) {
                assertTrue(
                    rimTravel(theta, phase) <= peak + 0.02f,
                    "rimTravel exceeded its peak at $theta/$phase",
                )
            }
        }
    }

    @Test
    fun `the rim is a narrow arc not a wash`() {
        // The travelling highlight has to read as a *place* on the silhouette, not a general
        // brightening of the whole edge — otherwise the "animation" is invisible and all the
        // user sees is a slightly hotter rim. Measured as a ratio to the peak, which is what
        // the eye actually compares: a quarter turn away must be a small fraction of the
        // travelling point, and just off it must still be most of the way there. The ratio is
        // normaliser-independent, so widening kappa cannot sneak past this.
        val peak = rimTravel(0f, 0f)
        val away = rimTravel(0f, 1.5707964f) / peak // theta - phase = pi/2
        assertTrue(away < 0.12f, "a quarter turn away should be near-zero, was $away")
        val near = rimTravel(0f, 0.35f) / peak
        assertTrue(near > 0.75f, "just off the peak should still be bright, was $near")
    }

    @Test
    fun `the glint can never brighten past its own maximum`() {
        // [0.35, 1.0]. The lower bound is a *life*, not a fade to nothing: a star that
        // disappears and returns is the effect, but one that brightens past 1 would break the
        // frame the pre-R3 film was bounded to.
        for (phase in samples(720)) {
            val life = glintLife(phase)
            assertTrue(life in 0.35f..1.0001f, "glint life out of range at $phase: $life")
        }
        // And it is genuinely alive: it must visit both ends of the range.
        val all = samples(720).map(::glintLife)
        assertTrue(all.min() < 0.40f, "the glint never dims — the travel is dead")
        assertTrue(all.max() > 0.99f, "the glint never reaches full — the travel is too timid")
    }

    @Test
    fun `the ray sheen slides instead of brightening`() {
        // A phase offset in the sine's argument can only move the fringes. Restated: the set of
        // *values* the term takes is identical at every phase, so its extremes are constant.
        // If someone moved the offset outside the sine (a multiplier), this fails.
        //
        // The sampling has to be fine enough to actually reach the supremum, because this
        // assertion is about the term's *extremes* and a coarse grid simply misses them — a
        // 0.05 step undershoots the peak by 1.2e-3, which is 100x the tolerance and would fail
        // for a reason that has nothing to do with the shader. 0.0005 lands within 2e-8.
        fun raysAt(across: Float, phase: Float): Float {
            val s = 0.5f + 0.5f * sin(across * 7f + rayOffset(phase))
            return s * s * 0.5f
        }
        val grid = (0 until 20_000).map { it * 0.0005f }
        for (phase in samples(24)) {
            val values = grid.map { raysAt(it, phase) }
            assertTrue(
                abs(values.max() - 0.5f) < 1e-4f,
                "ray peak drifted at $phase: ${values.max()}",
            )
            assertTrue(values.min() >= -1e-5f, "ray went negative at $phase: ${values.min()}")
        }
    }

    // ── the clock ────────────────────────────────────────────────────────────

    @Test
    fun `the glass period cannot beat against the plate`() {
        // Four layers share the slow clock. A small integer period ratio phase-locks into a
        // visible pulse once a minute, which is precisely the "extravagant" failure mode. The
        // rule: no ratio within 5% of a ratio of small integers (up to 5).
        //
        // This is not hypothetical — 110 s, the first value chosen for this constant, sits
        // 3.8% from 2/1 against the plate's 53 s and 3.3% from 2/1 against the lamp's 61 s.
        // The margin that 142 s holds is the reason it is 142 and not a round number.
        val glass = GLASS_TRAVEL_PERIOD_MS.single()
        val others = GUILLOCHE_PERIODS_MS + listOf(42_000L, 61_000L) // film orbit, lamp breath
        for (other in others) {
            val ratio = glass.toDouble() / other.toDouble()
            for (p in 1..5) {
                for (q in 1..5) {
                    val target = p.toDouble() / q.toDouble()
                    val closeness = abs(ratio - target) / target
                    assertTrue(
                        closeness > 0.05,
                        "glass=$glass vs other=$other is within 5% of $p/$q " +
                            "(ratio $ratio) — the two layers will beat",
                    )
                }
            }
        }
    }

    @Test
    fun `the glass period is slow enough to read as light not motion`() {
        // Under ~90 s the rim starts reading as a moving object rather than a change in the
        // light. Over ~180 s a lap is slower than a session's attention and the edge looks dead.
        val glass = GLASS_TRAVEL_PERIOD_MS.single()
        assertTrue(glass in 90_000L..180_000L, "glass travel period is $glass ms")
        assertEquals(1, GLASS_TRAVEL_PERIOD_MS.size, "one period, one edge")
    }

    @Test
    fun `the parked phase is one complete lap`() {
        // Reduce-motion parks at 2π, not 0. The distinction matters: 0 is a *particular point*
        // in the lap (the rim budget is concentrated somewhere specific), 2π is the same figure
        // the clock reaches at the end of a lap. Parking at 2π asserts "finished", not
        // "unstarted" — and the two agree only because the kernel is periodic, which is worth
        // pinning: a future non-periodic travel term would silently make the parked state a
        // *different* still frame from the model's own t=0.
        val parked = 6.2831855f
        // Equivalent to 0 modulo the trig the shader uses, which is the point.
        assertTrue(abs(cos(parked) - 1f) < 1e-5f, "parked phase is a whole lap")
        // And it is the *same* still frame the model's own t=0 produces, so a reduce-motion
        // screen and a rendered still agree.
        for (theta in samples(48)) {
            assertTrue(
                abs(rimTravel(theta, parked) - rimTravel(theta, 0f)) < 1e-4f,
                "the parked still must equal the model's own t=0 at theta=$theta",
            )
        }
    }
}
