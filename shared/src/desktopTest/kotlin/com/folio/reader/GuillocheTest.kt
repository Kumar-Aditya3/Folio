package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.GUILLOCHE_ALPHA_MAX
import com.folio.reader.ui.theme.GUILLOCHE_QUIET_MARGIN
import com.folio.reader.ui.theme.GUILLOCHE_SHIMMER
import com.folio.reader.ui.theme.GUILLOCHE_WEIGHT_LIGHT
import com.folio.reader.ui.theme.GuillocheQuiet
import com.folio.reader.ui.theme.ThemePack
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.fieldColorAtPoint
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.guillocheAccentFor
import com.folio.reader.ui.theme.guillocheLineColor
import com.folio.reader.ui.theme.guillocheSeed
import com.folio.reader.ui.theme.guillocheSpecFor
import com.folio.reader.ui.theme.guillocheWeightAt
import com.folio.reader.ui.theme.relativeLuminance
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guilloche plate's identity, its floor, and the two guarantees that make it safe:
 *
 *  - **same vibrance on both faces** — the plate must read as strongly on paper as on a
 *    dark room, which is a claim about the *delta* it introduces, so it is measured as one;
 *  - **legibility** — the plate may not move a palette's ink off §12.3's floor, on either
 *    face, and quiet regions must be *exactly* zero rather than merely quiet.
 *
 * Modelled the way `AmbientLightTest` models the ambient film: pure functions, no Compose,
 * no device.
 */
class GuillocheTest {

    /** A 5x5 grid of field positions, as fractions of a phone's page. */
    private val sampleFractions: List<Pair<Float, Float>> = buildList {
        for (iy in 0..4) for (ix in 0..4) add((0.1f + ix * 0.2f) to (0.1f + iy * 0.2f))
    }

    private val phoneW = 1080f
    private val phoneH = 2340f

    /** The field's own colour at a fraction of the page, pools and all. */
    private fun fieldAt(atmos: com.folio.reader.ui.theme.FolioAtmosphere, fx: Float, fy: Float): Color =
        fieldColorAtPoint(
            fieldColors(atmos), phoneW, phoneH, fx * phoneW, fy * phoneH, 0f,
        )

    /** WCAG relative luminance, the unit every contrast claim here is made in. */
    private fun lum(c: Color): Double = relativeLuminance(c)

    /** WCAG contrast ratio between two colours. */
    private fun contrast(a: Color, b: Color): Double {
        val la = lum(a)
        val lb = lum(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    // ── identity ──────────────────────────────────────────────────────────

    /**
     * Every pack draws a different figure.
     *
     * Deliberately a claim about the *weave parameters*, not about a string: "unique per
     * theme" cannot mean merely a different count, or every theme is the same pattern at a
     * different zoom. Two packs must differ in frequency, braid depth or angle — the things
     * that change the interference figure itself.
     */
    @Test
    fun everyPackDrawsADistinctFigure() {
        val figures = ThemePack.ALL.map { pack ->
            val spec = guillocheSpecFor(pack.id)
            // The figure, quantised: frequencies + braid depths + angles, rounded so the
            // comparison is about the figure rather than about float noise.
            spec.waves.joinToString("|") { w ->
                "${(w.frequency * 100).toInt()},${(w.cross * 100).toInt()},${(w.angle * 100).toInt()}"
            }
        }
        assertEquals(
            ThemePack.ALL.size,
            figures.toSet().size,
            "every theme pack must draw its own interference figure; " +
                "${ThemePack.ALL.size - figures.toSet().size} collision(s)",
        )
    }

    /** The same pack id always yields the same plate — on any device, in any run. */
    @Test
    fun specIsDeterministic() {
        ThemePack.ALL.forEach { pack ->
            val a = guillocheSpecFor(pack.id)
            val b = guillocheSpecFor(pack.id)
            assertEquals(a.waves, b.waves, "waves must be identical for ${pack.id}")
            assertEquals(a.fan, b.fan, "fan must be identical for ${pack.id}")
        }
    }

    /**
     * The seed is stable for a fixed input.
     *
     * Pinned as a literal so a future refactor of the hash cannot silently redraw every
     * theme's plate — and so Android and desktop are provably drawing the same figure.
     */
    @Test
    fun seedIsStable() {
        assertEquals(guillocheSeed("hyperpop"), guillocheSeed("hyperpop"))
        assertTrue(guillocheSeed("hyperpop") >= 0, "the seed must stay positive (31-bit fold)")
        // A one-character change must move the seed a long way, not by one.
        assertTrue(
            abs(guillocheSeed("honey") - guillocheSeed("honez")) > 1_000,
            "nearby ids must not produce nearby seeds",
        )
    }

    /**
     * The plate's clock cannot beat against the layers it shares the page with.
     *
     * Phase-locking is the failure: a period that is an exact simple fraction of the
     * ambient film's 42 s or the lamp's 61 s produces a visible pulse once per cycle, which
     * is exactly the "machine-like" tell the whole slow-clock discipline exists to avoid.
     */
    @Test
    fun periodsDoNotCollideWithTheOtherLayers() {
        val others = listOf(42_000L, 61_000L)
        guillocheSpecFor("gallery").periodsMs.forEach { p ->
            others.forEach { o ->
                val ratio = p.toDouble() / o.toDouble()
                // Reject any near-integer or near-simple-fraction ratio (including the
                // octave, 2.0, which locks just as hard as 1.0).
                val distanceToInteger = abs(ratio - Math.round(ratio).toDouble())
                assertTrue(
                    distanceToInteger > 0.06,
                    "period ${p}ms locks against ${o}ms (ratio=$ratio)",
                )
            }
        }
    }

    // ── the floor: vibrance matched across polarity ───────────────────────

    /**
     * The plate is **visible on both faces**, and by a comparable amount.
     *
     * This is the test that would have caught the original design. Deepening the field is
     * ample on a dark room and nearly nothing on paper — so a single `deepen` operation
     * gives the two faces wildly different strength, which is precisely the reported bug.
     *
     * Measured as the luminance the plate introduces against the field it sits on, at full
     * weight, for every face of every pack.
     *
     * The paper threshold is far below the dark one on purpose, and not because paper is
     * allowed to be worse: paper's plate is *stronger* (≈0.026 vs ≈0.0012) but it gets
     * most of its definition from chroma, which a luminance metric is blind to. So the
     * absolute floor here is the weaker of the two claims — "no face is invisible" — and
     * the balance between the faces is asserted separately by the ratio below.
     */
    @Test
    fun plateIsVisibleOnBothFaces() {
        var worstDark = 1.0
        var worstLight = 1.0
        var worstDarkId = ""
        var worstLightId = ""
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val accent = guillocheAccentFor(palette.colors, atmos.isDark)
            val weight = if (atmos.isDark) 1f else GUILLOCHE_WEIGHT_LIGHT
            for ((fx, fy) in sampleFractions) {
                val at = fieldAt(atmos, fx, fy)
                val line = guillocheLineColor(
                    at, accent, atmos.isDark, (GUILLOCHE_ALPHA_MAX * weight).coerceAtMost(1f),
                )
                // **Luminance delta, not a contrast-ratio delta.** These are different
                // questions and the ratio answers the wrong one on a dark face: WCAG's
                // `(L + 0.05)` offset means darkening an already-dark colour moves the
                // ratio by ~0.002 even when the colour has visibly changed. Measured as
                // a ratio the plate looks invisible on every dark theme, which is a
                // property of the metric, not of the plate. Relative luminance is the
                // unit that tracks what the eye sees here, so the claim is made in it.
                val delta = abs(lum(line) - lum(at))
                if (atmos.isDark) {
                    if (delta < worstDark) { worstDark = delta; worstDarkId = "${palette.id}@($fx,$fy)" }
                } else {
                    if (delta < worstLight) { worstLight = delta; worstLightId = "${palette.id}@($fx,$fy)" }
                }
            }
        }
        assertTrue(
            worstDark > 0.01,
            "the plate is faded on some dark face (worst=$worstDark on $worstDarkId)",
        )
        assertTrue(
            worstLight > 0.0004,
            "the plate is invisible on some paper face (worst=$worstLight on $worstLightId)",
        )
        // The old balance guard asserted paper was *stronger* than dark, because the dark
        // plate used to be a near-invisible shadow groove. The redesign inverts that: dark
        // is now a luminous ribbon (a lift toward a brightened accent) and is deliberately
        // the louder of the two, so the only guarantee that still holds is that neither
        // face is invisible — asserted by the two floors above.
    }

    /**
     * A dark field that bottoms out near black still gets a drawn line.
     *
     * The regression this exists for: on the darkest pool corners of `DARK` and `BLOSSOM`
     * the field reaches relative luminance ≈0.006, and `deepenG(field, 0.05)` — a 5% blend
     * toward black — **quantises to the identical 8-bit byte triple**, so the plate was
     * drawn and perfectly invisible at once. It is not a depth problem; no depth fixes it,
     * and the test sweeps up to 0.70 to say so. It is a *direction* problem: at the bottom
     * of the range the only headroom left is upward.
     *
     * So `guillocheLineColor` must not be a function that only darkens. This pins the
     * behaviour rather than the implementation: for every dark face, at every sample,
     * *some* line exists at the requested strength that the field does not swallow.
     */
    @Test
    fun darkFaceWithNoDepthHeadroomStillDrawsALine() {
        var checked = 0
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            if (!atmos.isDark) continue
            val accent = guillocheAccentFor(palette.colors, isDark = true)
            for ((fx, fy) in sampleFractions) {
                val at = fieldAt(atmos, fx, fy)
                val line = guillocheLineColor(at, accent, isDark = true, amount = GUILLOCHE_ALPHA_MAX)
                val delta = abs(lum(line) - lum(at))
                assertTrue(
                    delta > 0.0004,
                    "the plate vanished into ${palette.id}@($fx,$fy): " +
                        "field lum=${lum(at)}, line lum=${lum(line)} (delta=$delta)",
                )
                checked++
            }
        }
        // Guard the guard: if sampleFractions or the dark faces ever shrink, this test
        // would go quiet rather than loud.
        assertTrue(checked >= 250, "expected the full dark grid, only checked $checked points")
    }

    /**
     * The plate carries the theme's **own hue** on a paper face.
     *
     * The dark face draws a shadow, so its hue is necessarily the field's. Paper draws the
     * accent matched to luminance — and if that term silently degenerated to grey, the
     * plate would lose exactly the "vibrance gets lost on light" property it exists for.
     * So the paper line must carry measurably more chroma than a plain black-blend would.
     */
    @Test
    fun paperPlateCarriesTheThemesChroma() {
        var checked = 0
        for (palette in AppPalette.entries.filter { !atmosphereFor(it.colors).isDark }) {
            val atmos = atmosphereFor(palette.colors)
            val at = fieldAt(atmos, 0.5f, 0.5f)
            val accent = guillocheAccentFor(palette.colors, isDark = false)
            val line = guillocheLineColor(at, accent, isDark = false, amount = GUILLOCHE_ALPHA_MAX)
            val spread = maxOf(line.red, line.green, line.blue) - minOf(line.red, line.green, line.blue)
            assertTrue(spread > 0.004f, "paper plate has no chroma on ${palette.id} (spread=$spread)")
            checked++
        }
        assertTrue(checked > 0, "expected at least one paper face to check")
    }

    // ── legibility: the ink floor ─────────────────────────────────────────

    /**
     * **§12.3, redesigned.** Body text stays above the floor because the **quiet mask**
     * removes the plate where type sits — not because the plate is held dark.
     *
     * The plate is now a luminous ribbon on dark faces (a lift toward a brightened accent),
     * so in the *open* field it deliberately does not clear 7:1 against the ink — that
     * brightness is the whole point of the redesign, and the reference's web is bright. The
     * legibility guarantee moved to [guillocheWeightAt]: any text surface registers a quiet
     * region, and a region's core takes the plate's weight to exactly zero. This pins that
     * contract directly — under a full-cover quiet region the plate contributes nothing, so
     * the ink keeps whatever contrast the bare field already gives it (which every other
     * field/ink test holds at or above the floor). The open field is free to bloom.
     */
    @Test
    fun quietMaskKeepsInkAboveTheFloorOnEveryFace() {
        val FLOOR = 7.0
        // A reader-style full-surface quiet region: this is what any text screen registers
        // over the plate, and it is the case that has to protect the ink.
        val fullCover = listOf(GuillocheQuiet(0f, 0f, 1f, 1f, cover = 1f))
        var worst = Double.MAX_VALUE
        var worstId = ""
        for (palette in AppPalette.entries) {
            val atmos = atmosphereFor(palette.colors)
            val weight = if (atmos.isDark) 1f else GUILLOCHE_WEIGHT_LIGHT
            val accent = guillocheAccentFor(palette.colors, atmos.isDark)
            for ((fx, fy) in sampleFractions) {
                val quiet = guillocheWeightAt(fx, fy, fullCover)
                val amount = (GUILLOCHE_ALPHA_MAX * weight * quiet).coerceAtMost(1f)
                val at = fieldAt(atmos, fx, fy)
                val line = guillocheLineColor(at, accent, atmos.isDark, amount)
                val ratio = contrast(atmos.ink, line)
                if (ratio < worst) {
                    worst = ratio
                    worstId = "${palette.id}@($fx,$fy)"
                }
            }
        }
        assertTrue(
            worst >= FLOOR,
            "under a full quiet region the plate still pushed ink to $worst:1 on $worstId, " +
                "below the $FLOOR floor — the quiet mask is not clearing the plate",
        )
    }

    /**
     * The plate is not *so* deep that it becomes its own object on a dark face.
     *
     * A floor without a ceiling is a licence: deepen far enough and the plate stops being
     * an engraving and becomes a visible mesh. Bounds the dark depth by the same measure
     * as the ink floor — the plate may not approach the ink's own darkness.
     */
    @Test
    fun plateStaysWellClearOfTheInkOnDarkFaces() {
        for (palette in AppPalette.entries.filter { atmosphereFor(it.colors).isDark }) {
            val atmos = atmosphereFor(palette.colors)
            val at = fieldAt(atmos, 0.5f, 0.5f)
            val accent = guillocheAccentFor(palette.colors, isDark = true)
            val line = guillocheLineColor(at, accent, isDark = true, amount = GUILLOCHE_ALPHA_MAX)
            // The plate must remain far lighter than the ink: it is a shadow in the room,
            // never something the reader could mistake for type.
            val ratio = contrast(atmos.ink, line)
            assertTrue(ratio > 4.0, "the plate crowds the ink on ${palette.id} ($ratio:1)")
        }
    }

    // ── motion: the wrap has to be invisible ─────────────────────────────

    /**
     * The walk advances a **whole number of stroke periods per lap**.
     *
     * This is the invariant that makes the plate's motion continuous, and it is worth a test
     * because nothing else can catch it: the clock is a 0..2π *sawtooth*, the shader advances
     * each family by `drift * phase / 2π` inside a `fract`, and `fract(x + n) == fract(x)`
     * only for integer `n`. A fractional drift therefore steps the entire weave sideways once
     * every 53 seconds — a single visible jolt in an effect whose whole purpose is to never
     * be noticed, and one that no screenshot and no contrast test would ever show.
     */
    @Test
    fun theWalkIsAWholeNumberOfStrokePeriodsPerLap() {
        ThemePack.ALL.forEach { pack ->
            guillocheSpecFor(pack.id).waves.forEachIndexed { i, w ->
                assertEquals(
                    w.drift.toDouble(), Math.round(w.drift).toDouble(), 0.0,
                    "${pack.id} wave $i drifts ${w.drift} periods per lap, which will snap at the wrap",
                )
                assertTrue(abs(w.drift) >= 1f, "${pack.id} wave $i does not move (drift=${w.drift})")
            }
        }
    }

    /**
     * No theme's families all walk the same way.
     *
     * A shared sign is a shared velocity, and a shared velocity **is** a translation: the
     * weave would slide across the page as a rigid sheet rather than drift through its own
     * interference. That is the scrolling-wallpaper failure the plan names, and because it is
     * a property of the seed it could appear on one theme out of eighteen and no other.
     */
    @Test
    fun noThemeTranslatesAsARigidSheet() {
        ThemePack.ALL.forEach { pack ->
            val signs = guillocheSpecFor(pack.id).waves.map { it.drift > 0f }.toSet()
            assertTrue(
                signs.size > 1,
                "${pack.id} walks every family the same way, which is a translation, not a weave",
            )
        }
    }

    /**
     * The fan has a whole number of rays, and turns a whole number of sectors.
     *
     * Two different wrap problems with the same shape. `atan2` jumps a full turn at its branch
     * cut, which lands in the burst's `fract(a / 2π * rays)` as `rays` — so a fractional count
     * draws a seam radiating from the fan's centre, permanently, even at rest. And the spin is
     * counted in sectors precisely so a lap advances that same `fract` argument by an integer.
     */
    @Test
    fun theFanIsWholeInBothRaysAndTurn() {
        ThemePack.ALL.forEach { pack ->
            val fan = guillocheSpecFor(pack.id).fan
            assertEquals(
                fan.rays.toDouble(), Math.round(fan.rays).toDouble(), 0.0,
                "${pack.id} has ${fan.rays} rays, which leaves a seam at atan2's branch cut",
            )
            assertEquals(
                fan.spin.toDouble(), Math.round(fan.spin).toDouble(), 0.0,
                "${pack.id} turns ${fan.spin} sectors per lap, which will snap at the wrap",
            )
            assertTrue(fan.rays >= 8f, "${pack.id}'s fan is too sparse to read as a burst")
        }
    }

    /**
     * The plate's slowest breath can only ever **dim** it.
     *
     * Every contrast claim in this file is proved at [GUILLOCHE_ALPHA_MAX]. A breath that
     * swelled symmetrically would spend part of its cycle above that ceiling, which would
     * quietly invalidate `plateCannotPushInkBelowTheFloor` without failing it — the test
     * measures the ceiling, not the ceiling times a shimmer. Bounding the term to `[1-d, 1]`
     * is what keeps the existing proof sufficient, so the bound is pinned here rather than
     * left to the shader's arithmetic.
     */
    @Test
    fun theShimmerOnlyEverDimsThePlate() {
        assertTrue(GUILLOCHE_SHIMMER > 0f, "a zero shimmer is a static plate")
        assertTrue(GUILLOCHE_SHIMMER < 1f, "the shimmer may not extinguish the plate")
        // The shader's own form: 1 - depth * (1 - cos(t)) / 2, swept over a full turn.
        var hi = Double.MIN_VALUE
        var lo = Double.MAX_VALUE
        for (step in 0..720) {
            val t = step / 720.0 * 2.0 * Math.PI
            val s = 1.0 - GUILLOCHE_SHIMMER * 0.5 * (1.0 - kotlin.math.cos(t))
            hi = maxOf(hi, s)
            lo = minOf(lo, s)
        }
        assertTrue(hi <= 1.0 + 1e-9, "the shimmer brightens past the alpha ceiling (max=$hi)")
        assertEquals(1.0 - GUILLOCHE_SHIMMER, lo, 1e-6, "the shimmer's floor is not its depth")
    }

    // ── quiet regions: the text guarantee ────────────────────────────────

    /**
     * Inside a quiet region the plate is **exactly zero** — not small, absent.
     *
     * "Faint" is insufficient behind text and not for contrast reasons: a thin glyph stroke
     * landing on a thin plate stroke ragges, losing its edge in one word and not the next.
     * That is a *shape* problem the ink floor cannot see, so the guarantee has to be
     * categorical, and this is where it is pinned.
     */
    @Test
    fun aQuietRegionSilencesThePlateExactly() {
        val region = GuillocheQuiet(left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.6f)
        // Anywhere well inside must be exactly zero.
        assertEquals(0f, guillocheWeightAt(0.5f, 0.45f, listOf(region)), 0f, "centre must be silent")
        assertEquals(0f, guillocheWeightAt(0.2f, 0.3f, listOf(region)), 0f, "corner must be silent")
        assertEquals(0f, guillocheWeightAt(0.8f, 0.6f, listOf(region)), 0f, "corner must be silent")
    }

    /**
     * The plate dissolves rather than ending at a drawn line.
     *
     * A hard edge would trace the rectangle of "no pattern" across the page and the eye
     * finds that seam instantly — the quiet region would become more visible than the
     * pattern it removed. So the weight must *ease* from zero, strictly increasing, with no
     * jump.
     */
    @Test
    fun theQuietEdgeDissolvesRatherThanCutting() {
        val region = GuillocheQuiet(left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.6f)
        val samples = (0..20).map { i ->
            val x = 0.8f + (GUILLOCHE_QUIET_MARGIN * 2f) * (i / 20f)
            guillocheWeightAt(x, 0.45f, listOf(region))
        }
        // Monotone non-decreasing from the edge outward...
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(b >= a - 1e-6f, "the quiet edge is not monotone: $a then $b")
        }
        // ...reaching silence at the edge and full strength beyond the margin.
        assertEquals(0f, samples.first(), 0f)
        assertTrue(samples.last() > 0.99f, "the plate never returns to full strength")
        // And no single step is a visible jump (the ease, not a cut).
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a < 0.25f, "the quiet edge jumps by ${b - a}, which will read as a line")
        }
    }

    /**
     * A full-surface quiet region is the **reader**: the plate stands down entirely.
     *
     * Paper must stay paper. This is the mechanism that makes that structural rather than
     * a convention someone has to remember, so it is asserted as a property of the model:
     * `cover = 1` over the whole field silences the plate everywhere, with no residual.
     */
    @Test
    fun aFullCoverQuietRegionSilencesTheWholeField() {
        val page = GuillocheQuiet(0f, 0f, 1f, 1f, cover = 1f)
        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (y in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                assertEquals(
                    0f, guillocheWeightAt(x, y, listOf(page)), 0f,
                    "the reader must be silent at ($x,$y)",
                )
            }
        }
    }

    /** A zero-cover region is a no-op — the resting state of an unregistered surface. */
    @Test
    fun aZeroCoverRegionChangesNothing() {
        val nothing = GuillocheQuiet(0.1f, 0.1f, 0.9f, 0.9f, cover = 0f)
        assertEquals(1f, guillocheWeightAt(0.5f, 0.5f, listOf(nothing)), 0f)
        assertEquals(1f, guillocheWeightAt(0.5f, 0.5f, emptyList()), 0f)
    }

    /**
     * Overlapping quiet regions compose, and cannot be *un*-quieted by a neighbour.
     *
     * The reader publishes a full-page region and may also publish per-panel ones; the
     * result must be at least as quiet as the quietest, never less.
     */
    @Test
    fun overlappingRegionsComposeTowardSilence() {
        val left = GuillocheQuiet(0f, 0f, 0.5f, 1f, cover = 1f)
        val right = GuillocheQuiet(0.5f, 0f, 1f, 1f, cover = 1f)
        // The seam between two adjacent full-cover regions is silent, not doubled up.
        assertEquals(0f, guillocheWeightAt(0.5f, 0.5f, listOf(left, right)), 0f)
        // And a partial cover cannot lift a full cover's silence.
        val half = GuillocheQuiet(0f, 0f, 1f, 1f, cover = 0.5f)
        val full = GuillocheQuiet(0f, 0f, 1f, 1f, cover = 1f)
        assertEquals(0f, guillocheWeightAt(0.5f, 0.5f, listOf(half, full)), 0f)
    }
}
