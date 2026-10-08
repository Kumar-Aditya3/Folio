package com.folio.reader.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract of the within-class shape families.
 *
 * A grammar that can vary a silhouette has to say which of the old rules still hold
 * after it has, so every assertion here is one of the doctrine's load-bearing claims
 * restated as arithmetic over the family table:
 *
 *  1. a seed resolves to one silhouette, always — nothing may drift between frames,
 *     processes or re-sorts;
 *  2. the variation is real (a family that produces one shape is a flag that does
 *     nothing, and one that produces noise is not a designed grammar either);
 *  3. no corner ever leaves its budget, or the 0..50dp the vocabulary lives in;
 *  4. the pinned classes hand back the shipped [FolioShapes] *instances*, so "flag off"
 *     and "no change" are literally the same object;
 *  5. Rule 13's blur test survives the dice: a hero's wide pair never converges on its
 *     narrow pair, static or mid-flight;
 *  6. both endpoints of the animated forms, and reduce-motion, land on documented static
 *     values.
 *
 * Corner values rather than [androidx.compose.ui.graphics.Shape]s are what gets asserted:
 * `RoundedCornerShape` exposes neither its corners nor `equals`, and `createOutline` needs
 * a Density. [folioCornersFor] is the single place the grammar is applied, so asserting on
 * it is asserting on what every shape here is built from.
 */
class FolioShapeFamilyTest {

    private val allRows: List<Pair<String, FolioShapeRow>> = listOf(
        "hero" to FolioShapeFamily.hero,
        "heroBleed" to FolioShapeFamily.heroBleed,
        "card" to FolioShapeFamily.card,
        "inset" to FolioShapeFamily.inset,
        "callout" to FolioShapeFamily.callout,
        "plate" to FolioShapeFamily.plate,
        "plateSmall" to FolioShapeFamily.plateSmall,
        "pill" to FolioShapeFamily.pill,
        "chip" to FolioShapeFamily.chip,
        "edgeStart" to FolioShapeFamily.edgeStart,
        "sheet" to FolioShapeFamily.sheet,
        "nav" to FolioShapeFamily.nav,
    )

    private fun FolioCorners.at(corner: Int): Float =
        when (corner) {
            0 -> topStart
            1 -> topEnd
            2 -> bottomEnd
            else -> bottomStart
        }.value

    private fun FolioCorners.label(): String =
        "${at(0)}/${at(1)}/${at(2)}/${at(3)}dp"

    @Test
    fun `a seed and a class resolve to one silhouette forever`() {
        // The seed is an item's identity, so a silhouette that drifted between reads would
        // shake a shelf under a stationary finger. 200 reads per (seed, class).
        for (klass in FolioShapeClass.entries) {
            for (seed in listOf(0, 1, 7, 42, -1, -9001, 123456789, Int.MAX_VALUE, Int.MIN_VALUE)) {
                val row = FolioShapeFamily[klass]
                val first = folioCornersFor(seed, row)
                var drifted: FolioCorners? = null
                repeat(200) {
                    val again = folioCornersFor(seed, row, 1f)
                    if (again != first) drifted = again
                }
                assertTrue(
                    drifted == null,
                    "${klass.name}/$seed: the silhouette moved between reads — $first became $drifted",
                )
            }
        }
    }

    @Test
    fun `the free classes actually produce more than one silhouette`() {
        // The floors sit far under what the budgets can express (~2400 combinations for a
        // card alone), so they fail when the solver collapses, not when a hash shuffles.
        for ((name, row, floor) in listOf(
            Triple("hero", FolioShapeFamily.hero, 24),
            Triple("card", FolioShapeFamily.card, 12),
            Triple("inset", FolioShapeFamily.inset, 6),
            Triple("callout", FolioShapeFamily.callout, 10),
        )) {
            val seen = (0 until 2000).mapTo(mutableSetOf()) { folioCornersFor(it, row) }
            assertTrue(
                seen.size >= floor,
                "$name produced ${seen.size} distinct silhouettes over 2000 seeds, below the " +
                    "floor of $floor — the family is one shape wearing a flag",
            )
            val varied = seen.count { it != row.base }
            assertTrue(
                varied >= floor / 2,
                "$name: only $varied of ${seen.size} silhouettes differ from the base " +
                    "${row.base.label()} — the budget is not being spent",
            )
        }
    }

    @Test
    fun `no corner ever leaves its budget or the vocabulary's ceiling`() {
        for (variation in listOf(0f, 0.25f, 0.5f, 1f, 2f, 100f)) {
            for ((name, row) in allRows) {
                for (seed in -400..400) {
                    val solved = folioCornersFor(seed, row, variation)
                    for (corner in 0..3) {
                        val v = solved.at(corner)
                        val home = row.base.at(corner)
                        val budget = row.delta.at(corner)
                        assertTrue(
                            abs(v - home) <= budget + 1e-4f,
                            "$name/$seed at variation $variation: corner $corner is $v dp, " +
                                "${abs(v - home)} dp from its base $home and past the " +
                                "${budget} dp budget",
                        )
                        assertTrue(
                            v >= 0f && v <= FolioMaxCorner.value,
                            "$name/$seed at variation $variation: corner $corner is $v dp, " +
                                "outside the 0..${FolioMaxCorner.value} dp legal radius",
                        )
                        // Whole dp only: a third of a dp is not a silhouette, it is fuzz.
                        assertTrue(
                            abs(v - v.toInt()) < 1e-4f,
                            "$name/$seed: corner $corner solved to $v dp, not a whole dp",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `pinned classes hand back the shipped instance`() {
        // Not "the same corners" — the same object. That is what makes the rollout
        // off-switch free, and what keeps `pill means tappable` from becoming relative.
        val pinned = mapOf(
            FolioShapeClass.PLATE to FolioShapes.plate,
            FolioShapeClass.CONTROL to FolioShapes.pill,
            FolioShapeClass.SHEET to FolioShapes.sheet,
            FolioShapeClass.NAV to FolioShapes.nav,
        )
        for (seed in 0 until 500) {
            for ((klass, shipped) in pinned) {
                val shape = folioShapeFor(seed, klass)
                assertTrue(
                    shape === shipped,
                    "${klass.name} produced $shape rather than the shipped vocabulary " +
                        "instance $shipped for seed $seed",
                )
                assertTrue(
                    klass.pinned,
                    "${klass.name} is pinned by value but the table calls it free",
                )
            }
            // The variants with no class of their own are pinned the same way.
            assertTrue(
                folioShapeFor(seed, FolioShapeFamily.plateSmall) === FolioShapes.plateSmall,
                "plateSmall moved for seed $seed — a cover's radius is content, not chrome",
            )
            assertTrue(
                folioShapeFor(seed, FolioShapeFamily.chip) === FolioShapes.chip,
                "chip moved for seed $seed",
            )
        }
        assertEquals(
            setOf(
                FolioShapeClass.HERO,
                FolioShapeClass.CARD,
                FolioShapeClass.INSET,
                FolioShapeClass.CALLOUT,
            ),
            FolioShapeFamily.free,
            "the free/pinned split moved away from the four relationship-defined classes: " +
                FolioShapeFamily.free,
        )
    }

    @Test
    fun `a hero never converges on a card`() {
        // §12.1 Rule 13's benchmark, measured. Blurred to 8px, the only thing that survives
        // of a hero is the gap between its wide pair and its narrow pair, and the clamp in
        // the solver is all that stands between the dice and that gap — so it is checked
        // over every seed rather than argued from the budget table.
        val row = FolioShapeFamily.hero
        val guard = FolioShapeFamily.heroGuard.value
        assertTrue(
            heroGap(row, row.base) >= guard,
            "the hero's own base ${row.base.label()} does not satisfy its guard: the gap is " +
                "${heroGap(row, row.base)} dp and the rule needs $guard dp",
        )
        var bound = 0
        for (seed in -5000..5000) {
            for (variation in listOf(1f, 0.5f)) {
                val solved = folioCornersFor(seed, row, variation)
                val gap = heroGap(row, solved)
                assertTrue(
                    gap >= guard - 1e-3f,
                    "seed $seed at variation $variation gave ${solved.label()}: the wide pair " +
                        "clears the narrow pair by only $gap dp, under Rule 13's $guard dp — " +
                        "that hero reads as a card",
                )
                if (gap <= guard + 1e-3f) bound++
            }
        }
        assertTrue(
            bound > 0,
            "the guard never bound in 20020 solves, so it is decoration rather than a constraint",
        )
    }

    /** min(wide pair) − max(narrow pair), the wide pair read off the row's own base. */
    private fun heroGap(row: FolioShapeRow, corners: FolioCorners): Float {
        val wide = (0..3).filter { row.widePair[it] }.minOf { corners.at(it) }
        val narrow = (0..3).filterNot { row.widePair[it] }.maxOf { corners.at(it) }
        return wide - narrow
    }

    @Test
    fun `a hero and a card from the same book do not rhyme`() {
        // The per-class salt, as a range argument: the hero's corners can never reach a
        // card's values, so an identical pattern across the two classes means the salts
        // had been dropped and both classes were rolling the same dice.
        for (seed in 0..2000) {
            val hero = folioCornersFor(seed, FolioShapeFamily.hero)
            val card = folioCornersFor(seed, FolioShapeFamily.card)
            assertTrue(
                hero != card && hero.at(0) > card.at(0),
                "seed $seed rhymed a hero $hero with a card $card",
            )
        }
    }

    @Test
    fun `card stays looser than inset and a bled hero keeps its open edge`() {
        // "Embedded things read smaller than the surface holding them", plus the reason the
        // leading corners of heroBleed and edgeStart are masked rather than merely cheap:
        // 0dp there is an edge that does not exist, and opening a radius lets the page show
        // through the cover that is supposed to run off it.
        for (seed in 0..2000) {
            val card = folioCornersFor(seed, FolioShapeFamily.card)
            val inset = folioCornersFor(seed, FolioShapeFamily.inset)
            assertTrue(
                card.at(0) > inset.at(0) && card.at(2) > inset.at(2),
                "seed $seed: card ${card.label()} no longer reads looser than inset ${inset.label()}",
            )
            for ((name, row) in listOf(
                "heroBleed" to FolioShapeFamily.heroBleed,
                "edgeStart" to FolioShapeFamily.edgeStart,
            )) {
                val solved = folioCornersFor(seed, row)
                for (leading in listOf(0, 3)) {
                    assertEquals(
                        0f,
                        solved.at(leading),
                        "$name/$seed: corner $leading opened a ${solved.at(leading)}dp radius " +
                            "into the side that bleeds off the window",
                    )
                }
            }
            // The callout's rule edge stays flat at any seed, while its trailing pair travels.
            val callout = folioCornersFor(seed, FolioShapeFamily.callout)
            assertTrue(
                callout.at(1) > callout.at(0) + 4f,
                "seed $seed: callout ${callout.label()} lost its flat-leading-edge silhouette",
            )
        }
    }

    @Test
    fun `variation zero is the shipped shape by instance`() {
        for (seed in 0..600) {
            for (klass in FolioShapeClass.entries) {
                val row = FolioShapeFamily[klass]
                val off = folioShapeFor(seed, klass, 0f)
                assertTrue(
                    off === row.baseShape,
                    "${klass.name} at variation 0f is $off, not the shipped ${row.baseShape}",
                )
                assertTrue(
                    row.base === folioCornersFor(seed, row, 0f),
                    "${klass.name} at variation 0f re-solved to " +
                        "${folioCornersFor(seed, row, 0f).label()} instead of returning its base",
                )
            }
            // Out-of-range budgets are clamped, not honoured.
            assertTrue(
                folioShapeFor(seed, FolioShapeClass.CARD, -1f) === FolioShapes.card,
                "a negative variation produced something other than the base",
            )
        }
    }

    @Test
    fun `the animated forms land on the static endpoints they document`() {
        for (seed in 0..600) {
            for ((name, row) in allRows) {
                // Fraction 0 is the vocabulary value — by instance, always.
                val start = folioShapeForming(seed, row, 0f)
                assertTrue(
                    start === row.baseShape,
                    "$name at fraction 0 is $start, not the base ${row.base.label()}",
                )
                // Fraction 1 is the static family value: one and the same derivation, so it
                // must be the base instance exactly when the static solve is.
                val solved = folioCornersFor(seed, row, 1f)
                val settled = folioShapeForming(seed, row, 1f)
                assertTrue(
                    (settled === row.baseShape) == solved.sameShapeAs(row.base),
                    "$name at fraction 1 is $settled while the static solve is $solved " +
                        "(base ${row.base.label()}) — the ramp does not end on the static value",
                )
                // Reduce-motion skips the travel: it lands on the settled silhouette, not on
                // the neutral end and not on a third value.
                val frozen = folioShapeForming(seed, row, 0.3f, motion = false)
                assertTrue(
                    (frozen === row.baseShape) == solved.sameShapeAs(row.base),
                    "$name under reduce-motion at fraction 0.3 is $frozen, not the static " +
                        "family value $solved",
                )
                // Off the switch the whole ramp is the base — an animated off state is free.
                assertTrue(
                    folioShapeForming(seed, row, 0.3f, variation = 0f) === row.baseShape,
                    "$name at variation 0f animated to something other than the base",
                )
            }
        }
        // A cross-class morph — a hero collapsing into the masthead — starts and ends on
        // the two shapes the caller could have asked for statically.
        val hero = FolioShapeFamily.hero
        val bar = FolioShapeFamily.edgeStart
        for (seed in 0..300) {
            val heroSolved = folioCornersFor(seed, hero, 1f)
            val barSolved = folioCornersFor(seed, bar, 1f)
            assertTrue(
                (folioShapeMorph(seed, hero, bar, 0f) === hero.baseShape) ==
                    heroSolved.sameShapeAs(hero.base),
                "seed $seed: the morph's start is not the hero's static shape $heroSolved",
            )
            assertTrue(
                (folioShapeMorph(seed, hero, bar, 1f) === bar.baseShape) ==
                    barSolved.sameShapeAs(bar.base),
                "seed $seed: the morph's end is not the bar's static shape $barSolved",
            )
            assertTrue(
                (folioShapeMorph(seed, hero, bar, 0.4f, motion = false) === bar.baseShape) ==
                    barSolved.sameShapeAs(bar.base),
                "seed $seed: reduce motion parked the morph mid-flight",
            )
        }
    }

    @Test
    fun `a mid-flight hero still satisfies every rule a static hero does`() {
        // The animated form is allowed to paint a decorative path mid-travel, so the
        // intermediate silhouettes are rendered geometry and are governed too.
        val row = FolioShapeFamily.hero
        val guard = FolioShapeFamily.heroGuard.value
        for (seed in 0..400) {
            val solved = folioCornersFor(seed, row, 1f)
            for (step in 0..10) {
                val f = step / 10f
                val corners = row.base.lerpTo(solved, f)
                for (corner in 0..3) {
                    val v = corners.at(corner)
                    assertTrue(
                        v >= -1e-4f && v <= FolioMaxCorner.value + 1e-4f,
                        "seed $seed at fraction $f put corner $corner at $v dp",
                    )
                    assertTrue(
                        abs(v - solved.at(corner)) <= abs(solved.at(corner) - row.base.at(corner)) + 1e-3f,
                        "seed $seed at fraction $f put corner $corner at $v dp, past the " +
                            "${solved.at(corner)} dp it is travelling to from ${row.base.at(corner)}",
                    )
                }
                assertTrue(
                    heroGap(row, corners) >= guard - 1e-3f,
                    "seed $seed at fraction $f gave ${corners.label()}: a mid-flight hero " +
                        "converged on a card (gap ${heroGap(row, corners)} dp, needs $guard)",
                )
            }
        }
    }

    @Test
    fun `the static call is free where the doctrine demands it`() {
        // A pinned class and a switched-off free class must both cost zero shapes. Returning
        // the same object across seeds is the only externally visible proof of that, since
        // a freshly built RoundedCornerShape would be a different one.
        for (seed in 0..200) {
            assertTrue(
                folioShapeFor(seed, FolioShapeClass.NAV) ===
                    folioShapeFor(seed + 1, FolioShapeClass.NAV),
                "nav rebuilt its shape for seed $seed",
            )
            assertTrue(
                folioShapeFor(seed, FolioShapeClass.HERO, 0f) ===
                    folioShapeFor(seed + 99, FolioShapeClass.HERO, 0f),
                "the off switch rebuilt a shape for seed $seed",
            )
        }
    }
}
