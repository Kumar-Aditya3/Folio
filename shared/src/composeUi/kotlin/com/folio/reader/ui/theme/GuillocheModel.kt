package com.folio.reader.ui.theme

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The guilloche plate, as pure data (Rule 3: one source of truth).
 *
 * A guilloche is a family of parametric ornamental curves — the engraved line work on
 * a banknote, a passport, a watch dial. What makes it right as a background here is
 * that it is **pure line geometry with no fill**: it adds structure at low frequency
 * without adding luminance mass, which is exactly what a page of content can survive
 * being laid over.
 *
 * **The formation is a weave, not a family of rings.** An earlier draft of this file
 * described concentric rings (`fract(r * LINES)`), and that cannot work: a radial family
 * has even density by construction, so it reads as a target, never as a ribbon. What the
 * engraving actually is — and what the reference frame shows — is several long sine
 * waves crossing at different angles, superposed. Where two crests pass near each other
 * the strokes bunch into a **bright ribbon**; where they cancel, a calm gap. That
 * bunch-and-spread rhythm is the whole visual, and it comes from the *interference*, not
 * from any single wave.
 *
 * **Per theme, the interference figure differs.** That is the requirement — "unique for
 * every theme" cannot mean merely denser or sparser, or every theme looks like the same
 * pattern at a different zoom. So each wave's frequency and cross-modulation depth is
 * derived from a deterministic hash of the pack's id. Two themes do not share a figure.
 *
 * The seed is a hash of the pack id string rather than [Any.hashCode], which is not
 * stable across runs or platforms: a theme's plate must be the same plate on every
 * device, in every session, or it cannot be pinned by a test.
 */

/**
 * One wave in the weave, in normalised field space.
 *
 * The wave is a line family running at [angle], whose position is displaced by a second
 * (slower) wave running perpendicular to it — that cross term is what turns a set of
 * parallel stripes into ornamental line work.
 */
@Immutable
data class GuillocheWave(
    /** Frequency along the wave's own axis: how many strokes across the field. */
    val frequency: Float,
    /** Cross-modulation depth. 0 is a set of parallel stripes; higher braids them. */
    val cross: Float,
    /** The cross wave's own frequency — what the braid actually looks like. */
    val crossFreq: Float,
    /** Orientation, radians. */
    val angle: Float,
    /**
     * The stroke's **sharpness** — the shader's core exponent is `PLATE_LOBE_K * weight`.
     *
     * Authored, and it is now **the family's own number, unscaled**: `ch.weight` straight.
     * It used to be *derived* from the wave's frequency (`K = C / freq^2`) so that all three
     * tiers drew the same **pixel** gauge — the right goal for a needle, which has a gauge.
     * A broad lobe does not: it covers a *fraction* of its own period, so a shared exponent
     * already makes the coarse tier's wash wider in pixels than the fine tier's, which is
     * exactly the scale layering the three tiers are for. Compensating by frequency on top of
     * that would have *inverted* it.
     *
     * Note the sign is unchanged: a larger number is a *tighter* falloff, so MINIMAL (1.45)
     * draws the tightest lobe and CRYSTALLINE (0.72) the broadest.
     */
    val weight: Float,
    /** Contribution of this wave to the finished plate, 0..1. */
    val share: Float,
    /**
     * How far this wave's family creeps per lap of the plate's first slow phase, in
     * **whole stroke periods, signed**.
     *
     * Differing per wave is the point: a *phase walk* slides the interference pattern
     * through itself, so ribbons widen and re-form. Translating the whole plate instead
     * would read as scrolling wallpaper.
     *
     * **The integer is a correctness condition, not a tuning choice.** The clock is a
     * 0..2π *sawtooth* — `slowPhaseAt` ramps linearly and wraps — and the shader advances
     * the family by `drift * phase / 2π` inside a `fract`. A whole number of periods per
     * lap is exactly the condition under which the wrap is invisible: `fract(x + n) ==
     * fract(x)`. A fractional value steps the entire weave sideways once per lap, which
     * is the "snaps or pops at the wrap" the plan's A4 forbids, and it is a pop the eye
     * reads as a mechanism rather than as weather.
     */
    val drift: Float,
)

/**
 * The corner fan: a radial burst of fine lines where the plate is "struck".
 *
 * [x] and [y] are fractions of the field; the reference puts it in the top-right, and
 * this plate follows that — a single, confident asymmetry rather than a balanced
 * rosette, which would put a subject in the middle of the page.
 */
@Immutable
data class GuillocheFan(
    val x: Float,
    val y: Float,
    /**
     * How many rays — a **whole number**, and for the same class of reason
     * [GuillocheWave.drift] is.
     *
     * The shader strikes a ray at each integer crossing of `fract(atan2(v) / 2π * rays)`,
     * and `atan2` has a branch cut at ±π where its value jumps by a full turn. That jump
     * lands in the `fract` argument as `rays`, so a fractional count leaves a visible
     * seam radiating from the fan's centre — a drawn line where the burst should simply
     * continue around.
     */
    val rays: Float,
    /** How far the rays reach, as a fraction of the field diagonal. */
    val reach: Float,
    /** Weight of the fan relative to the weave, 0..1. */
    val share: Float,
    /**
     * The fan's slow rotation, in **whole ray sectors per lap, signed** — one sector
     * being `2π / rays`.
     *
     * Counted in sectors rather than radians so the turn is wrap-continuous: the shader
     * rotates by `spin * phase / rays`, so a lap advances the `fract` argument by exactly
     * `spin`, and the sawtooth's wrap is invisible. Expressed in radians instead, the fan
     * would jump by a fraction of a sector every 53 s.
     */
    val spin: Float,
)

/** The plate for one theme: a weave, a fan, and the clock the whole thing runs on. */
@Immutable
data class GuillocheSpec(
    val waves: List<GuillocheWave>,
    val fan: GuillocheFan,
    /** The two slow periods (ms) this plate's phases walk on. */
    val periodsMs: List<Long>,
    /**
     * §29: which **shape family** this plate is. The seeded numbers decide the
     * particulars; this decides what kind of figure they are particulars *of*.
     */
    val geometry: GuillocheGeometry = GuillocheGeometry.SWEEP,
)

/**
 * §29 — a theme's **geometry family**: the shape its fibres make.
 *
 * This is the answer to the one complaint a palette change cannot answer. Swapping
 * `guillochePrimary` from teal to violet changes the *colour* of the same figure; the
 * user's brief is explicit that the *figure* must change — "not the same guilloche,
 * different colour". So a plate is no longer one weave with seeded jitter: it is one of
 * nine genuinely different constructions, and the seed only decides the particulars
 * within that construction.
 *
 * The families are not nine arbitrary number sets. Each is a different *mathematical
 * character*, which is why they read as different drawings rather than as different
 * zooms of one drawing:
 *
 * | family | theme | the construction |
 * |---|---|---|
 * | [SWEEP] | Aurora Ocean | long sheets, barely warped — a slow tidal ruling |
 * | [ORBITAL] | Nebula Electric | curvature about a centre: spiral trajectories |
 * | [ORGANIC] | Bioluminescent Forest | two incommensurate slow waves — uneven, alive |
 * | [EDITORIAL] | Terracotta Tide | near-straight and broad: a printed plate, calm |
 * | [CRYSTALLINE] | Arctic Glass | **faceted** — triangle waves, so the lines corner |
 * | [RAYS] | Solar Eclipse | strong directional rays fanning out of the light |
 * | [ORNAMENTAL] | Jade Noir | deep braid plus a second harmonic: loops and crossings |
 * | [PLAYFUL] | Mint & Coral | springy, higher-frequency arcs |
 * | [MINIMAL] | Moonstone | very few, very long, elegant lines |
 *
 * The warp each family applies lives in the AGSL `familyWarp` (see
 * `GuillocheShader.android.kt`); the numbers that *size* the weave live in
 * [GuillocheGeometry.character], here, because they are what `GuillocheTest` can pin.
 */
enum class GuillocheGeometry {
    SWEEP,
    ORBITAL,
    ORGANIC,
    EDITORIAL,
    CRYSTALLINE,
    RAYS,
    ORNAMENTAL,
    PLAYFUL,
    MINIMAL,
}

/**
 * How a family sizes the weave, on top of the seed.
 *
 * Deliberately numbers and not code: they are what `GuillocheTest` asserts on, and they
 * are the only part of the geometry the Kotlin side can see. The *warp* — the part that
 * turns a family from a density into a shape — is in the shader, because it is per
 * fragment.
 */
internal data class GuillocheCharacter(
    /** Multiplier on the weave's frequencies. High = more, finer fibres. */
    val frequency: Float,
    /** Multiplier on the braid depth. High = the strokes wander much further. */
    val braid: Float,
    /** Multiplier on the stroke weight. High = thicker. */
    val weight: Float,
    /** How much of the corner fan survives, 0..1. */
    val fan: Float,
)

internal val GuillocheGeometry.character: GuillocheCharacter
    get() = when (this) {
        // Long, wide, slow. The sheets are the subject; nothing should cut across them.
        GuillocheGeometry.SWEEP -> GuillocheCharacter(0.80f, 0.62f, 1.00f, 0.85f)
        // Curvature is the point, so the braid runs at full depth and the weave is middling.
        GuillocheGeometry.ORBITAL -> GuillocheCharacter(1.05f, 1.00f, 0.85f, 0.55f)
        // Uneven and alive: a deeper braid and a slightly finer weave.
        GuillocheGeometry.ORGANIC -> GuillocheCharacter(1.15f, 1.25f, 1.05f, 0.70f)
        // Broad and nearly straight — the calmest plate in the set, so the fan is full.
        GuillocheGeometry.EDITORIAL -> GuillocheCharacter(0.62f, 0.55f, 1.30f, 0.95f)
        // Fine and faceted: many thin straight-ish lines, almost no braid.
        GuillocheGeometry.CRYSTALLINE -> GuillocheCharacter(1.60f, 0.45f, 0.72f, 0.50f)
        // Rays are the subject, so the weave steps back and the fan takes the weight.
        GuillocheGeometry.RAYS -> GuillocheCharacter(0.95f, 0.40f, 0.90f, 1.35f)
        // The busiest figure: deep braid and a dense weave, so crossings become ornament.
        GuillocheGeometry.ORNAMENTAL -> GuillocheCharacter(1.35f, 1.60f, 0.80f, 0.75f)
        // Springy and light — high frequency, strong wander, thin strokes.
        GuillocheGeometry.PLAYFUL -> GuillocheCharacter(1.20f, 1.35f, 0.95f, 0.80f)
        // One or two very long lines. Everything is scaled down except the weight.
        GuillocheGeometry.MINIMAL -> GuillocheCharacter(0.50f, 0.40f, 1.45f, 0.35f)
    }

/**
 * Which family a pack draws.
 *
 * Keyed on the **pack id**, not the palette id, because `guillocheSpecFor` is called
 * with the pack (`"lagoon"`), and because the two faces of a pack are one theme and must
 * therefore draw one figure. The nine §26 families map onto the eleven packs the way
 * `FOLIO_ART_DIRECTIONS` maps them onto palettes; `gallery` and `system` are the two
 * packs with no §26 family of their own, so they borrow the two calmest constructions.
 */
private val PACK_GEOMETRY: Map<String, GuillocheGeometry> = mapOf(
    "lagoon" to GuillocheGeometry.SWEEP,          // Aurora Ocean
    "abyss" to GuillocheGeometry.SWEEP,
    "dawn" to GuillocheGeometry.ORBITAL,          // Nebula Electric
    "dusk" to GuillocheGeometry.ORBITAL,
    "moss" to GuillocheGeometry.ORGANIC,          // Bioluminescent Forest
    "peacock" to GuillocheGeometry.ORGANIC,
    "manuscript" to GuillocheGeometry.EDITORIAL,  // Terracotta Tide
    "espresso" to GuillocheGeometry.EDITORIAL,
    "arctic" to GuillocheGeometry.CRYSTALLINE,    // Arctic Glass
    "midnight" to GuillocheGeometry.CRYSTALLINE,
    "honey" to GuillocheGeometry.RAYS,            // Solar Eclipse
    "ember" to GuillocheGeometry.RAYS,
    "rouge" to GuillocheGeometry.ORNAMENTAL,      // Jade Noir
    "garnet" to GuillocheGeometry.ORNAMENTAL,
    "sakura" to GuillocheGeometry.PLAYFUL,        // Mint & Coral Editorial
    "blossom" to GuillocheGeometry.PLAYFUL,
    "silver" to GuillocheGeometry.MINIMAL,        // Moonstone
    "graphite" to GuillocheGeometry.MINIMAL,
    "cyberpunk" to GuillocheGeometry.CRYSTALLINE,  // the neon city's circuitry
    "neonday" to GuillocheGeometry.CRYSTALLINE,
    "vice" to GuillocheGeometry.PLAYFUL,            // neon signage, palm chrome
    "viceday" to GuillocheGeometry.PLAYFUL,
    "aurum" to GuillocheGeometry.ORNAMENTAL,        // baroque gold leaf
    "champagne" to GuillocheGeometry.ORNAMENTAL,
    // The two packs with no §26 family: the achromatic Gallery/Dark pair borrows the
    // printed-plate calm (it is the app's default look and should not shout), and the
    // dynamic System pack borrows the tidal sweep (it must sit under any wallpaper).
    "gallery" to GuillocheGeometry.EDITORIAL,
)

/** A pack's geometry family. An unknown id (a preview, a test) gets the neutral sweep. */
fun guillocheGeometryFor(packId: String): GuillocheGeometry =
    PACK_GEOMETRY[packId] ?: GuillocheGeometry.SWEEP

/**
 * The most the plate may be worth, as premultiplied alpha.
 *
 * **0.48, and it is a legibility number, not a taste number.** The reference frame's
 * pattern is clearly legible rather than subliminal, so the first draft's 0.05 — chosen
 * to be safely invisible — was too quiet, and the 0.34 that followed still read on device
 * as *fading into the background* rather than as an engraving. For calibration: the
 * field's own pool ceiling is `POOL_ALPHA_CEILING` (0.24) and the daylight wash is
 * `DAYLIGHT_WASH_ALPHA_MAX` (0.06), which `DaylightTest` proves cannot move a WCAG ratio
 * off its floor. The plate is *sparse* — the fibre profile lands ~67% of the page at zero
 * coverage and only ~8% bright — so its ceiling may sit above the pools' without the
 * page's mean luminance following it.
 *
 * What bounds this number is not the pools but
 * `GuillocheTest.plateStaysWellClearOfTheInkOnDarkFaces`. That test is exact, not a
 * proxy: `guillocheLineColor(field, glow, amount = α)` *is* the composited thread at a
 * full-strength core, because mixing field→glow by α is the same arithmetic as
 * compositing glow at α over the field. So the constant is the plate's visible ceiling and
 * the test measures what the eye gets. Swept across all 22 palettes on the *brightest
 * possible* field — a bound the real field, sampled at its centre, cannot exceed — the
 * worst palette lands at **~4.4:1** here, against the 4.0 floor. That is the wall: at 0.50
 * the same palette falls to 4.18:1 and at 0.54 to 3.79:1. **So the plate is within ~10% of
 * the brightest it may be under this invariant, and any further "brighter" has to relax the
 * invariant rather than raise this number.**
 *
 * Raising it is what makes the threads bright rather than dull. Measured on the same worst
 * palette, the composited thread goes from **7.7x the room's luminance at 0.34 to ~12.4x at
 * 0.48** — a 60% brighter thread — and it is the only dial that moves brightness much: the
 * glow term is damped by the mix factor, so pushing [GUILLOCHE_DARK_GLOW] from 0.40 to 0.80
 * buys only ~16%.
 *
 * A higher number is affordable *because* [folioGuillocheQuiet] takes text regions to
 * exactly zero — not in spite of it. Raise this and the quiet regions are what keep the
 * page legible, so the two constants move together.
 */
const val GUILLOCHE_ALPHA_MAX = 0.48f

/**
 * The plate's structural frequency, as a fraction of the historical authored range.
 *
 * **Low on purpose, and it is the dial that decides "shade" vs "lines".** The wave
 * frequencies were authored at ≈4.6–22 periods across a phone, which is a fine lattice —
 * and a lattice at any amplitude reads as *lines*, because the eye finds the periodicity
 * even when the strokes are faint. The references do not have one. Contrast-stretched, the
 * concept art's in-phone field is flat to 1–2/255 with a lag-1 autocorrelation of **+0.6**,
 * i.e. neighbouring pixels are alike: broad soft washes and a glow pool, no ruling.
 *
 * At 0.16 the coarse sheet falls to well under one period and the fine hatch to a handful,
 * so the three tiers stop being a weave and become three overlapping large-scale gradients.
 * That is what makes the field read as a *faint moving shade*: the motion is still there
 * (the drift and the breath are untouched) but there is no edge anywhere for the eye to
 * lock onto. Measured against the concept, edge hardness drops **0.035 → 0.002** and the
 * block-to-block variation **0.090 → 0.007** — the concept's own character.
 *
 * Paired with [GUILLOCHE_ALPHA_MAX] this is the whole of the "lines vs shades" fix: the
 * profile was broadened in the shader (see `waveLine`), the sum is soft-clipped rather than
 * clamped (see `plateAt`), the gain was taken down, and this lowers the frequency. Any one
 * of them alone leaves a lattice — a faint needle is still a line.
 */
internal const val GUILLOCHE_STRUCTURE_SCALE = 0.16f

/**
 * How far inside a quiet region the plate must already be at zero, as a fraction of the
 * shorter side. A hard edge would draw a rectangle of "no pattern" across the page and
 * the eye would find that seam immediately; the margin lets the plate dissolve before it
 * reaches the text.
 */
const val GUILLOCHE_QUIET_MARGIN = 0.10f

/**
 * The plate's own sweep of the slow clock.
 *
 * Both chosen to be far from the layers it shares the page with — the ambient film's
 * 42 s orbit and the lamp's 61 s breath — so nothing beats against anything else into a
 * visible pulse. A period *ratio* close to a small fraction is the failure mode: 42 and
 * 84 would phase-lock once a minute.
 */
val GUILLOCHE_PERIODS_MS = listOf(53_000L, 79_000L)

/**
 * The glass film's own sweep of the slow clock — "the animated edge".
 *
 * Deliberately a third distinct period rather than a reuse of either guilloche figure or of
 * the 42 s / 61 s of the film and the lamp: four independent periods that share no small
 * integer ratio cannot phase-lock into a visible beat, which is what a "restrained but
 * alive" room depends on. 142 s is slow enough that the rim's travel reads as a change in
 * the light rather than as motion — the reference's own pace for a live edge — and it is
 * the value in the 90–180 s window whose ratio to *all four* other periods clears the
 * nearest small-integer ratio (up to 5) by the widest margin, 6.9%. The obvious round
 * numbers do not: 110 s sits 3.8% from 2/1 against the plate's 53 s, and 120 s sits 2.4%
 * from 2/1 against the lamp's 61 s. Roughly 1420 samples per lap at `SLOW_MOTION_TICK_MS`
 * = 100 ms. The margin is pinned by `GlassTravelTest`.
 */
val GLASS_TRAVEL_PERIOD_MS = listOf(142_000L)

/**
 * The von Mises concentration of the rim's travelling highlight, and the reciprocal of the
 * kernel's own mean over a lap.
 *
 * `exp(κ(cos θθ − 1))` peaks at exactly 1 — so it can never be brighter than the edge was —
 * but its *mean* is only ~0.234 at `κ = 3.2`. Handing that straight to the rim weight would
 * brighten the edge by `1/0.234 ≈ 4.3×` on average: the "extravagant" failure, arrived at
 * by accident. Multiplying by the reciprocal restores the mean to exactly 1, so the term
 * **redistributes** the existing rim budget around the silhouette and adds nothing. Higher κ
 * = a narrower, more pronounced travelling arc; the normaliser moves with it, so the mean
 * stays 1 whatever the concentration.
 */
internal const val GLASS_TRAVEL_KAPPA = 3.2f
internal const val GLASS_TRAVEL_KAPPA_MEAN = 0.23427f

/**
 * How far the plate's whole coverage may **dim** on its slowest breath, 0..1.
 *
 * The term that makes the engraving read as weather rather than as wallpaper: a single
 * very slow swell across the entire plate, so the pattern seems to pass through the page
 * instead of being printed on it. Driven by *both* slow phases at once (53 s and 79 s),
 * which share no small-integer ratio, so the swell never arrives twice at the same place
 * in the weave's own walk.
 *
 * **It can only ever subtract.** The shader applies it as `1 - depth * (1 - cos)/2`,
 * which is bounded to `[1 - depth, 1]` — so the plate's strongest moment is still exactly
 * [GUILLOCHE_ALPHA_MAX] and every contrast claim proved at that ceiling still holds. A
 * term that could *brighten* past the ceiling would invalidate `plateCannotPushInkBelowTheFloor`
 * and would have to be re-proved; this one is safe by construction, which is why it is
 * written as a dimming rather than as a symmetric ±.
 *
 * 0.14 — about an eighth. Below ~0.08 it is indistinguishable from steady; much above
 * ~0.2 the plate starts to visibly fade in and out, which draws the eye to the effect and
 * fails the plan's "nothing may be the thing you look at" test.
 */
const val GUILLOCHE_SHIMMER = 0.14f

/** The number of waves in the weave. Three is the ceiling the register can carry. */
internal const val GUILLOCHE_WAVES = 3


// ── the per-pack seed ──────────────────────────────────────────────────────
//
// A small deterministic PRNG over the pack id. It must be a *string* hash with fixed
// arithmetic — a 32-bit FNV-1a, integer-only — because Kotlin's String.hashCode is
// specified but `Any.hashCode` of a data class is not, and because the same plate has to
// come out on desktop (where a test pins it) and on Android (where it is drawn).

private const val FNV_OFFSET = 2_166_136_261L
private const val FNV_PRIME = 16_777_619L

/** FNV-1a over the pack id, truncated to 31 bits so it stays positive on every platform. */
internal fun guillocheSeed(packId: String): Int {
    var h = FNV_OFFSET
    for (c in packId) {
        h = h xor (c.code.toLong() and 0xFF)
        h = (h * FNV_PRIME) and 0xFFFF_FFFFL
    }
    // Fold to 31 bits and mix once more, so ids that differ only in a late character
    // still land far apart in the streams below.
    var x = (h xor (h ushr 31)).toInt() and 0x7FFF_FFFF
    x = x xor (x ushr 15)
    x *= 0x2545_F491
    x = x xor (x ushr 13)
    return x and 0x7FFF_FFFF
}

/**
 * A tiny deterministic stream: splits a 31-bit seed into a sequence of floats in
 * [0, 1). Written out rather than using `Random(seed)`, because the platform `Random`
 * implementations are not required to agree between Android and the JVM — and a plate
 * that looks different on the device than the test says it does is exactly the class of
 * bug the hash exists to prevent.
 */
private class GuillocheStream(seed: Int) {
    private var state: Int = if (seed == 0) 0x1234_567 else seed

    fun next(): Float {
        // xorshift32: cheap, integer-only, identical everywhere.
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x and 0x7FFF_FFFF
        if (state == 0) state = 0x1234_567
        return (state and 0xFFFF).toFloat() / 65_536f
    }

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * next()
}

/**
 * The plate for one theme.
 *
 * The authored part is the *lean*: [FolioLight] already says where a pack's light comes
 * from and how much of its own hue it lends, which is the pack's statement about its own
 * character. The seed decides the specifics — frequencies, braid depths, weights — so two
 * packs of similar character still do not share a figure.
 *
 * [sourceX]/[sourceY] set the weave's base orientation, so the plate's lines run *with*
 * the theme's light rather than being pasted over it. That is why the reference's curves
 * feel lit rather than drawn on.
 *
 * A quiet pack (low [FolioLight.lean], low [FolioLight.hueRange]) gets a sparser, calmer
 * plate; a loud one gets a denser, more braided figure. This is derived, not authored a
 * second time — the pack already said what it is.
 */
fun guillocheSpecFor(packId: String, light: FolioLight = FolioLight.Default): GuillocheSpec {
    val seed = guillocheSeed(packId)
    val rng = GuillocheStream(seed)

    // §29: the family first, because it is the *shape* the seed's numbers are shaped by.
    // The seed decides the particulars; the family decides what kind of figure they make.
    val geometry = guillocheGeometryFor(packId)
    val ch = geometry.character

    // The theme's own light direction, as the base angle the weave runs along. When a
    // pack authors no lean (the default tree, a preview, a test) this is the neutral
    // upper-leading diagonal, which is where every other material's light already is.
    val base = atan2f(light.sourceY - 0.5f, light.sourceX - 0.5f)

    // Character, 0..1. A pack that leans hard on the reader's own colour is a pack that
    // wants to be seen, so it earns a busier plate.
    val character = ((light.lean + light.hueRange) * 0.5f).coerceIn(0f, 1f)

    // Which way the plate drifts. Seeded, so a theme's weather is its own.
    val walkSign = if (seed and 1 == 0) 1f else -1f

    val waves = (0 until GUILLOCHE_WAVES).map { i ->
        // Frequencies **ascend by roughly 1.9x per tier**, so the plate has genuine scale
        // layering: a structural sheet carries the composition, a mid filigree rides it, and
        // a fine hatch gives it surface. The tiers stay far apart, which is what makes them
        // read as *layers* rather than as one dense ruling.
        //
        // **The absolute level is set by [GUILLOCHE_STRUCTURE_SCALE], and it is low on
        // purpose.** The numbers below are the historical ones (≈4.6–22 periods across a
        // phone); the scale takes the coarse sheet to well under one period and the fine
        // hatch to a handful. That is not a retreat from "fibre-sized" — it is what the
        // references actually show. A contrast-stretched high-pass of the concept art's
        // in-phone field is flat to 1–2/255 with **no periodic ruling anywhere**: the field
        // is a soft wash under a glow pool, and the engraving is invisible beneath it. At
        // the old level the plate drew a fine lattice across the whole page, which is
        // exactly the "defined lines" the design is meant not to have.
        //
        // The three tiers still share one lobe **shape** (the exponent below is the
        // family's, the same on all three waves), so each covers the same *fraction* of its
        // own period and the scale layering survives the rescale.
        val freqBase = when (i) {
            0 -> rng.range(4.6f, 6.4f)
            1 -> rng.range(9.0f, 12.5f)
            else -> rng.range(16.0f, 22.0f)
        } * ch.frequency * GUILLOCHE_STRUCTURE_SCALE
        // The braid: how much a perpendicular wave displaces this one — the term that
        // makes a straight ruling undulate into a flowing ribbon. This is the dial that
        // decides "weave" vs "ruled lines". It used to collapse toward zero on quiet packs
        // (the multiplier floored at 0.7 when character≈0), which is exactly why low-lean
        // rooms rendered as straight diagonal rules instead of the reference's flowing,
        // interwoven veil. The braid is now strong *by construction* — the character term
        // only adds busyness on top of a floor that already curves every stroke hard.
        val cross = rng.range(1.9f, 3.1f) * (1.0f + 0.45f * character) * ch.braid
        GuillocheWave(
            frequency = freqBase,
            cross = cross,
            crossFreq = rng.range(0.8f, 1.9f),
            // Waves fan out around the theme's light direction rather than stacking on
            // top of each other, which is what opens the crossings into ribbons.
            angle = base + (i - 1) * rng.range(0.42f, 0.78f),
            // **The family's sharpness, straight.** This used to be solved from the frequency
            // (`K = C / freq^2`) to put every tier on one *pixel* thread gauge. That was right
            // for a needle and is wrong for a lobe: a lobe covers a *fraction of its own
            // period*, so one exponent across the three tiers already yields 1/freq pixel
            // widths — the coarse sheet's wash genuinely wider than the fine hatch's, which is
            // the scale layering these tiers exist to give. Solving it again from `freq` would
            // have cancelled that and made the finest tier the widest.
            //
            // The seeded jitter is what keeps two packs of the same family from drawing a
            // byte-identical plate — the same reason the frequencies and braid depths are
            // seeded. It consumes exactly one `next()`, as the range it replaces did, so no
            // other seeded value in the figure moves.
            weight = ch.weight * rng.range(0.85f, 1.15f),
            // **Steeply graded, not near-equal.** Three near-equal weaves crossing at three
            // angles is a *plaid*; the references show one flow with two fainter ones
            // underneath, which is what makes the field read as a shade rather than a
            // lattice. Measured: at [1.0, 0.84, 0.64] the rendered field is a diamond
            // crosshatch at any frequency; at [1.0, 0.45, 0.25] the coarse sheet carries
            // the field and the other two register as depth.
            share = when (i) {
                0 -> 1.0f
                1 -> 0.45f
                else -> 0.25f
            },
            // Each wave walks at its own rate, so the interference drifts rather than
            // sliding as a rigid sheet.
            //
            // **The magnitude rises with the wave index on purpose.** The three layers'
            // frequencies span about 4x, so a shared count of periods-per-lap would have
            // the filigree creeping at a quarter of the structure's speed. `i + 1` periods
            // against `freq` makes the three *spatial* speeds comparable — one medium
            // drifting, rather than three sheets at three speeds, which is the difference
            // between weather and a parallax effect.
            drift = (i + 1).toFloat() * waveWalkSign(i, walkSign, rng.next()),
        )
    }

    // The fan sits where the pack's light comes from, which is why it reads as a burst
    // *of* that light rather than a decoration dropped in a corner. Clamped into the
    // field so a pack that authors its source off-centre cannot put the fan off-screen.
    val fanX = light.sourceX.coerceIn(0.72f, 0.92f).let { if (it < 0.5f) 0.86f else it }
    val fanY = light.sourceY.coerceIn(0.06f, 0.22f)
    val fan = GuillocheFan(
        x = fanX,
        y = fanY,
        // Rounded to a whole ray: `atan2`'s branch cut would otherwise leave a seam
        // through the burst. See GuillocheFan.rays.
        //
        // **Never scaled by the family.** `rays` is a wrap-correctness count, not a taste
        // dial, and `GuillocheTest` floors it at 8 so the burst cannot read as a few
        // strokes. A family that wants less fan scales its `reach` and `share` instead.
        rays = (20f + 14f * character).let { it + rng.range(-2f, 2f) }.roundToInt().toFloat(),
        reach = (0.44f + 0.16f * character) * ch.fan.coerceAtLeast(0.5f),
        share = (0.72f + 0.28f * character) * ch.fan,
        // Two ray sectors per lap, signed by the seed so some themes' fans turn one way
        // and some the other — the same asymmetry the reference has. Sectors rather than
        // radians so the turn survives the sawtooth's wrap; see GuillocheFan.spin. Two
        // rather than one because a single sector on a ~20-ray fan is 18 degrees in 53 s,
        // slow enough to read as static.
        spin = if (seed and 1 == 0) 2f else -2f,
    )

    return GuillocheSpec(waves = waves, fan = fan, periodsMs = GUILLOCHE_PERIODS_MS, geometry = geometry)
}

/** `atan2` without the platform's `Float` overload ambiguity, in radians. */
private fun atan2f(y: Float, x: Float): Float = kotlin.math.atan2(y, x)

/**
 * Which direction one wave's family walks, given the pack's own [base] direction.
 *
 * Wave 1 is forced *against* wave 0 rather than seeded, and that is the whole reason this
 * is a function instead of a sign bit. Three same-signed walks share a common velocity,
 * and a common velocity **is** a translation: the weave would slide across the page as a
 * rigid sheet, which is the scrolling-wallpaper failure the plan's A4 names and the exact
 * opposite of a phase walk. One guaranteed disagreement makes the families slide *through*
 * each other, so a ribbon bunches in one place while another dissolves — the motion the
 * reference actually has.
 *
 * [draw] is consumed on every branch, so the stream position — and therefore every
 * frequency and angle drawn after it — does not depend on which wave is being built.
 */
private fun waveWalkSign(index: Int, base: Float, draw: Float): Float = when (index) {
    0 -> base
    1 -> -base
    else -> if (draw < 0.5f) base else -base
}

/**
 * A region where the plate must be **exactly absent** — not faint, absent.
 *
 * "Faint" is not good enough behind text, and the reason is not contrast. The ink floor
 * asserts a *ratio*; it cannot see that a thin glyph stroke landing on a thin plate
 * stroke loses its edge in one word and not the next. That ragged edge is read as
 * "dirty", not as "patterned", and it is the specific failure a line pattern behind text
 * produces where a soft gradient does not.
 *
 * So a region carries [cover], and inside it the plate's own weight is multiplied to
 * zero over [GUILLOCHE_QUIET_MARGIN]. Cover 1 means "plain page" (the reader); cover 0
 * is a no-op and is never stored.
 */
@Immutable
data class GuillocheQuiet(
    /** Fractions of the field. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** How completely to remove the plate here, 0..1. */
    val cover: Float = 1f,
) {
    init {
        require(right >= left && bottom >= top) {
            "GuillocheQuiet must be normalised, got ($left,$top)-($right,$bottom)"
        }
    }
}

/**
 * The plate's weight at a point, given the quiet regions in force.
 *
 * Returns 0 inside any region's core, easing to 1 by [GUILLOCHE_QUIET_MARGIN] outside it,
 * so the plate dissolves into the quiet zone rather than ending at a drawn line. Regions
 * compose multiplicatively: two overlapping quiet zones are quieter than either alone,
 * and a full-surface region (the reader) silences the plate everywhere.
 *
 * Pure and region-based on purpose — it is the whole of the text-legibility guarantee, so
 * it must be testable without Compose or a device.
 */
fun guillocheWeightAt(
    x: Float,
    y: Float,
    regions: List<GuillocheQuiet>,
    margin: Float = GUILLOCHE_QUIET_MARGIN,
): Float {
    if (regions.isEmpty()) return 1f
    var w = 1f
    for (r in regions) {
        if (r.cover <= 0f) continue
        // Distance *outside* the rectangle, in field fractions.
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        val outside = maxOf(dx, dy)
        // 0 well inside, 1 well outside. A zero-size margin would be a hard cut, which
        // would draw the rectangle; the floor keeps the ease finite.
        val ease = if (margin <= 0f) 0f else (outside / margin).coerceIn(0f, 1f)
        // Smoothstep, so the dissolve has no visible start or end.
        val kept = ease * ease * (3f - 2f * ease)
        w *= 1f - r.cover * (1f - kept)
    }
    return w.coerceIn(0f, 1f)
}

/**
 * The plate's colour, per theme, per face — **the structural tier**.
 *
 * Kept as a single-accent entry point because that is what `GuillocheTest` measures
 * against: every visibility and legibility claim in that file is a claim about
 * [guillocheLineColor] driven by *one* accent, and keeping this function means those
 * claims still describe something real.
 *
 * It is now the first of three. See [guillocheTiersFor] for why.
 */
fun guillocheAccentFor(colors: FolioColors, isDark: Boolean): androidx.compose.ui.graphics.Color =
    guillocheTiersFor(colors, isDark).first()

/**
 * The plate's **three authored hues**, in tier order — structural, secondary, highlight.
 *
 * This is the second half of "not the same guilloche, different colour". A plate drawn in
 * one hue reads as a monochrome wash no matter how good the geometry is; a plate drawn in
 * three reads as a *field* — where the wide structural sheets cross, the eye sees the
 * primary hue; where the fine filigree rides on top, it sees the highlight. The theme
 * authored those three hues for exactly this (§18's `guillochePrimary`/`Secondary`/
 * `Highlight`), and until now nothing consumed them.
 *
 * Both faces take the same three roles, so a pack's two sides are recognisably one theme;
 * only the saturation differs, because paper has nothing but chroma to distinguish the
 * line from the page (its luminance is pinned by `matchLuma`), while a dark face also gets
 * the lift toward white that `guillocheLineColor` applies.
 *
 * **The hue the single-accent path uses is `guillochePrimary`, and that is a change.**
 * The old source was `accentAnnotation` — chosen because it was "the one authored hue the
 * environment does not already spend". Now the theme authors a hue *for the guilloche*,
 * which is strictly better information: it is the hue the art direction intends the
 * engraved field to be, rather than a role borrowed from somewhere else.
 */
fun guillocheTiersFor(
    colors: FolioColors,
    isDark: Boolean,
): List<androidx.compose.ui.graphics.Color> {
    val boost = if (isDark) 1.15f else 1.25f
    // Each thread keeps the palette's own *strength* — the boost, and with it the chroma and
    // lightness the role was authored to land at — and only its **hue** is bounded to the
    // room's. That split is the point: the defect was never that the threads were too weak or
    // too strong, it was that they were the wrong colour. `terracotta`/Espresso's
    // `guillocheSecondary` is a teal at **+158°** from its own page hue, so the plate drew
    // cyan hairlines across a warm brown room. See [MAX_GUILLOCHE_HUE_DEV] for the bound.
    //
    // Boosted first, because `saturate` works in HSV and the boost can move the Oklab hue a
    // little — the bound has to be measured against the colour that is actually drawn.
    val lifted = listOf(
        colors.guillochePrimary,
        colors.guillocheSecondary,
        colors.guillocheHighlight,
    ).map { saturate(it, boost) }

    // A room with no hue (the two System faces — see [roomHue]) has nothing to clash with, so
    // there is no bound to apply and the threads keep their authored hues.
    val room = roomHue(colors)
    if (room.isNaN()) return lifted

    // **The set is re-anchored, not each thread clamped.** A per-thread clamp is the obvious
    // form and it is wrong in a way that is invisible until a test catches it: when all three
    // authored hues sit beyond the bound on the *same side* of the room, each clamps to the
    // same `room ± dev` and the three threads become one colour. `GuillocheTest
    // .paperPlateCarriesTheThemesChroma` caught exactly that on the System light face (spread
    // collapsed to 1/255, one 8-bit step). Scaling the whole set of offsets preserves their
    // order and their *relative* spacing, so the threads stay three at any bound, and the
    // widest one lands exactly at the bound rather than the set being squeezed unevenly.
    val offsets = lifted.map { signedHueDelta(oklabHue(it), room) }
    val span = offsets.maxOf { abs(it) }
    val scale = if (span <= MAX_GUILLOCHE_HUE_DEV || span == 0f) 1f
    else MAX_GUILLOCHE_HUE_DEV / span
    return lifted.mapIndexed { i, c ->
        withHue(c, (room + offsets[i] * scale + 360f) % 360f)
    }
}

/**
 * How far the *widest* guilloche thread may sit from the room's hue, in degrees.
 *
 * **This is a measured bound, and the measurement is of the whole set rather than of one
 * thread.** The reference concepts say the plate is a *lightness* modulation in the room's
 * hue — lighter threads over the room's own colour, never a second colour — and the numbers
 * agree: measuring every line pixel in the two clean field bands of all nine mockups, the
 * rendered threads span **p10–p90 ≈ 8–22°** on the light faces and **7–20°** on the dark ones
 * (three outliers are bands that clip the week card's bars rather than the plate). A span of
 * ~20° is a bound of 10° per side.
 *
 * Swept against those concepts, the worst-thread hue error over the 18 faces is:
 *
 *    bound   0°     8°    10°    20°    40°   unbounded
 *    error  12.1   18.3   20.1   27.9   43.1      100.6
 *
 * The 12.1° at bound 0 is the *irreducible* term — the concept's own rendered line sits that
 * far from the palette's `backgroundBase` hue, mostly because the line is a composite over a
 * field the ambient pools have already tinted. Everything above it is error the bound is
 * adding. The bound is therefore set at **10°**: it admits the concept's measured span, and
 * the 8° of error it costs above the floor is the price of the three threads remaining three
 * distinguishable colours (which the concept shows and which the plate's whole construction
 * depends on — see [guillocheTiersFor]).
 *
 * Unbounded, the authored roles run to ±176° from the room, which is what produced teal and
 * violet threads on warm rooms across the dark faces.
 *
 * **§9 raises it to 55°.** The reasoning above is a measurement of the *old* palettes, whose
 * three guilloche hues were derived near-neighbours of the page and needed the bound to stop
 * them clashing. The sheet authors them instead — `jade`'s light face carries jade `#3E7565`,
 * gold `#B99A52` and navy `#30485A`, which are three genuinely different colours a designer
 * chose to have on the plate. At 10° two of those three collapse onto the room and the plate
 * loses the thing the sheet painted it for. 55° admits them while still refusing the ±176°
 * runaway: the set is re-anchored rather than clamped (see [guillocheTiersFor]), so the three
 * threads keep their order and their relative spacing at any bound, and the widest one lands
 * exactly at the bound.
 */
internal const val MAX_GUILLOCHE_HUE_DEV = 55f

/**
 * How strong the plate is right under a light theme's ink — the two bounds that make
 * "same vibrance on both faces" true rather than hopeful.
 *
 * A single alpha cannot serve both faces, because the two faces have different headroom:
 * a dark room's ink is light and has almost nothing to fear from a darker line, while
 * paper's ink is dark and every step down spends its margin. So the *colour* differs by
 * face (above) and the *weight* differs too — but the **perceived strength** is matched,
 * which is what the requirement actually asks for.
 */
const val GUILLOCHE_WEIGHT_DARK = 1.0f

/**
 * The plate's weight multiplier on a paper face.
 *
 * Above 1 because paper needs *more* coverage to read at the same intensity: a
 * luminance-pinned chromatic line has far less apparent contrast than a shadow cut into
 * a dark room. Raising this is paid for in [GUILLOCHE_LIGHT_DEPTH] staying small — the
 * test that pins the ink floor is what decides whether this number is affordable, and it
 * is the number to lower first if paper ever feels heavy.
 */
const val GUILLOCHE_WEIGHT_LIGHT = 1.35f

/** The plate's weight multiplier for a face. */
fun guillocheWeightFor(isDark: Boolean): Float =
    if (isDark) GUILLOCHE_WEIGHT_DARK else GUILLOCHE_WEIGHT_LIGHT

/**
 * The plate's own colour at a point, **polarity-aware**.
 *
 * The plate must carry *the same vibrance* on a dark face and on a paper face — that is
 * the requirement, and it is the reason this function cannot be one operation. The two
 * faces fail in opposite directions:
 *
 * - On a **dark** field the plate is a **shadow cut into paint**: deepen the field
 *   colour. Deeper is more visible, and it moves *away* from the light ink, so contrast
 *   only improves. This was the original design and it is correct — for dark faces.
 * - On a **paper** field the same deepen is a mistake twice over. Visually, black at low
 *   alpha over near-white is the *least* chromatic direction available — the pattern goes
 *   grey and dies, which is precisely "less vibrance on light". And mechanically, moving
 *   downward on a light field is moving *toward* nothing good: it eats the headroom that
 *   dark ink needs.
 *
 * So on paper the plate is drawn with the theme's **own colour, matched back to the
 * field's luminance**: `matchLuma(tint, lumaOf(field))` then deepened only slightly. That
 * keeps the line's hue and chroma (so the vibrance is *colour*, not darkness) while
 * pinning the luminance so the line cannot change the contrast band at all. This is the
 * exact trick `FolioAtmosphere` already uses for its raised/sunken planes — *"matchLuma
 * pins the tinted result back to the untinted base's luminance, so text contrast on each
 * plane is unchanged"* — applied to the plate.
 *
 * @param fieldColor the field's colour at this point (from `fieldColorAtPoint`).
 * @param accent the theme's own colour for the plate (its `primary`/`tertiary`), which is
 *   what makes the plate the *theme's* colour rather than a black-and-white pattern.
 * @param isDark which face we are on. Decided by the caller from the atmosphere, once per
 *   palette, rather than re-derived per fragment.
 * @param amount how strong the plate is here, 0..1 (already includes the quiet weight).
 */
fun guillocheLineColor(
    fieldColor: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    isDark: Boolean,
    amount: Float,
): androidx.compose.ui.graphics.Color {
    val a = amount.coerceIn(0f, 1f)
    return if (isDark) {
        // A **luminous engraving**, not a shadow groove. The reference's plate reads as a
        // bright cyan-white web of light cut *into* the dark field — ribbons that are
        // lighter than the room, not darker. The old branch deepened toward black, which
        // on a mid-dark navy moves almost no luminance and is why the plate read as a
        // faded smudge. So the line now lifts toward a brightened accent (the pack's
        // `primary`, pushed partway to white), which is what makes it vibrant.
        //
        // Legibility of body text that sits *on* the field is no longer this function's
        // job — it is the quiet mask's. A registered text region collapses `amount` to
        // near zero where type sits (see GuillocheQuiet), so the open field may bloom at
        // full strength while the page under a label stays clean. The ink-floor tests are
        // relaxed to match: they guard that the line is not literally the ink, not that a
        // background texture clears 7:1 under text it is masked away from.
        val glow = mixG(accent, androidx.compose.ui.graphics.Color.White, GUILLOCHE_DARK_GLOW)
        mixG(fieldColor, glow, (a * GUILLOCHE_DARK_LIFT).coerceIn(0f, 1f))
    } else {
        // On paper the line carries the theme's hue, not its darkness. Luminance is
        // pinned to the field so the plate cannot move the contrast band; only chroma
        // and a whisper of depth distinguish the line from the page.
        val hueMatched = matchLuma(
            mixG(fieldColor, accent, a * GUILLOCHE_LIGHT_TINT),
            luminanceOf(fieldColor),
        )
        // A slight deepening on top of the luma match, so the line still reads as *drawn*
        // on paper rather than as a chroma-only shimmer. Bounded well under the dark
        // depth, because on paper every step down spends dark ink's headroom.
        deepenG(hueMatched, a * GUILLOCHE_LIGHT_DEPTH)
    }
}

/**
 * How deep the plate may cut on a dark face at full weight.
 *
 * **No longer shapes the dark line** — the dark branch is a *lift* toward the brightened
 * accent, not a deepen toward black (see [guillocheLineColor]). Kept, and still fed to the
 * shader as the `darkDepth` uniform, because the shared GLSL block declares it and SkSL
 * strips an unread uniform from the active list — its setter would then throw. The shader
 * keeps it alive with a `line += (darkDepth + minVisibleDelta) * 0.0` no-op. It remains
 * meaningful only to the paper path's shared declaration.
 */
internal const val GUILLOCHE_DARK_DEPTH = 0.42f

/**
 * How far the luminous line lifts toward the brightened accent at full weight.
 *
 * This is now the dark branch's *only* term — the line is light cut into the dark, and
 * this is how much of it. 0.95: at full strength the ribbon is almost entirely the glow
 * colour, which is what gives the reference's bright, confident web. Legibility under
 * body text is the quiet mask's job (it collapses `amount` where type sits), not this
 * constant's, so it is free to be bold.
 */
internal const val GUILLOCHE_DARK_LIFT = 0.95f

/**
 * How far the accent is pushed toward white to make the ribbon's *glow* colour.
 *
 * The reference's web is a bright cyan-white, but the brief is **glowing threads in the
 * theme's distinct chroma** — so the ribbon has to stay recognisably the pack's own colour.
 * Whitening is the one term that spends that identity, and it buys very little: 0.30 of the
 * way to white keeps ~70% of the accent's chroma while still reading as light rather than
 * paint, where the previous 0.50 washed the hue down to about half.
 *
 * **This is the *colour* dial; [GUILLOCHE_ALPHA_MAX] is the *brightness* dial.** They are
 * not interchangeable. The glow term is damped by the mix factor (`cover * darkLift`, ≈ 0.46
 * at a core), so pushing this from 0.40 to 0.80 brightens the *rendered* thread by only
 * ~16%, where raising the alpha ceiling from 0.34 to 0.48 brightens it by ~60% — and the
 * whitening *costs* chroma the whole way. So the brightness budget goes to the alpha and
 * this stays low, which is what lets the thread be bright and chromatic at once.
 *
 * It still trades against `plateStaysWellClearOfTheInkOnDarkFaces` — the whiter the ribbon,
 * the closer it climbs to the (light) ink it must stay clear of — but only mildly, and
 * *lowering* it buys contrast rather than spending it. Measured on the worst palette at the
 * 0.46 ceiling it was swept against: 0.25 → 4.68:1, 0.30 → 4.63:1, 0.40 → 4.52:1,
 * 0.50 → 4.40:1.
 */
internal const val GUILLOCHE_DARK_GLOW = 0.30f

/**
 * The smallest luminance delta this plate counts as *visible*.
 *
 * The number the dark branch's headroom check is made against, and the floor
 * `GuillocheTest.plateIsVisibleOnBothFaces` asserts — deliberately one constant, so the
 * model cannot decide a cut is worth drawing on a standard the test does not hold it to.
 *
 * 4e-4 in WCAG relative luminance. For scale: 1/255 of a mid-grey is ~1.5e-3, so this is
 * roughly a quarter of one 8-bit step at the middle of the range — comfortably above the
 * ~1e-5 float ripple that a failed deepen leaves behind, and below the ~1.2e-3 that the
 * lift branch actually lands.
 */
internal const val GUILLOCHE_MIN_VISIBLE_DELTA = 4.0e-4

/**
 * The same threshold, as the `Float` the AGSL uniform API needs.
 *
 * Separate rather than a `Double` constant converted at each use, so there is exactly one
 * place that knows the two are the same number and the shader cannot drift from the model
 * by a rounding step. See [GUILLOCHE_MIN_VISIBLE_DELTA] for what it means.
 */
internal const val GUILLOCHE_MIN_VISIBLE_DELTA_F = 4.0e-4f

/**
 * The luminance gap between two colours, on WCAG's scale.
 *
 * Pure and tiny on purpose: it exists so the model's visibility test and the test suite's
 * visibility claim are the same arithmetic rather than two similar-looking ones. Uses
 * [relativeLuminance] (the WCAG `Double`), not `luminanceOf` (the palette helper's `Float`),
 * because [GUILLOCHE_MIN_VISIBLE_DELTA] is quoted in WCAG units and the test measures in
 * `relativeLuminance` — comparing across the two would be a silent unit mismatch.
 */
internal fun luminanceDelta(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double =
    kotlin.math.abs(relativeLuminance(a) - relativeLuminance(b))

/**
 * How much of the theme's accent colour the plate carries on a paper face, at full weight.
 *
 * This is where paper gets its vibrance from: colour, not darkness. High (0.85) because
 * it is free — the luminance is pinned by [matchLuma] immediately afterwards, so this
 * number buys saturation and costs nothing.
 */
internal const val GUILLOCHE_LIGHT_TINT = 0.85f

/**
 * The slight extra depth on paper, at full weight.
 *
 * This is the only term on a light face that actually moves luminance, and it moves it in
 * the direction that spends dark ink's headroom — so it is the paper path's cost centre.
 * 0.34: enough that the line reads as *drawn* on the page rather than as a chroma-only
 * shimmer. It is deliberately still under the dark face's depth, but no longer a quarter
 * of it (the earlier 0.10/0.42 pair) — at a tenth the line dissolved into the paper, which
 * is the same "fades into the background" failure the dark face had.
 */
internal const val GUILLOCHE_LIGHT_DEPTH = 0.34f

