package com.folio.reader.ui.theme

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The silk ribbon field, as pure geometry and colour — no Compose types, so the desktop
 * suite can pin it (Rule 3: one source of truth).
 *
 * This is a port of the Python oracle (`.dbg/silk/silk_ref.py`) that the effect was designed
 * and measured in. Everything the oracle established is carried across verbatim, including
 * the three corrections that the oracle's own acceptance run forced:
 *
 *  - the width is **signed**, so the sheet actually twists and the edges swap sides at each
 *    pinch. A `sqrt(kappa + cos^2)` width is never negative, so the ribbon only narrows and
 *    the crisp X never appears.
 *  - the u=1 edge is mixed toward HOT by a **small** fraction. The spec called it "the
 *    cool-white edge" and mixed 60% of `#FFF2EA` — a *warm* white — which turns the edge
 *    violet-grey. Measured on the edges alone: hue 285 / sat 0.28.
 *  - a ruling takes its colour from the ribbon's **body**, not from the mean of the two
 *    edges. Averaging WARM and COOL in linear space gives a magenta; the rulings alone
 *    measured hue 307.
 *
 * ## The structure is frozen; only the mesh rolls
 *
 * `K_BACKBONE`, `K_PINCH`, `K_N` and `K_RULING` are all zero, so the Lissajous backbone, the
 * width's phase, the brightness envelope and the ruling shear are all constant in `p`. With
 * every one of them at zero the field is **bit-identical** at every phase, which is what
 * makes "the structure is frozen" exact rather than approximate. One term is left alive:
 *
 *  - [K_EPI] — the mesh travels (rolls) along the ribbon
 *
 * ## The roll — the chosen motion
 *
 * The mesh does not breathe in and out; it **rolls**, like the rim of a wheel that is fixed
 * in place but turning toward the viewer. Two things produce that read:
 *
 *  - a **static** edge bow, [EDGE_BOW] `* |2u-1|`, which has no `p` and so is part of the
 *    frozen structure. It bows the fringes outward at the border, so when the whole field
 *    scrolls the border leads the centre — the "rim rolling toward you" look.
 *  - the whole fringe field scrolls rigidly in `p` via `2*pi*K_EPI*p`.
 *
 * Because the only `p` term is the integer `K_EPI` scroll, the loop closes exactly: `fract`
 * of a whole number of spatial periods is itself. (The earlier in/out "breathe" term,
 * `INWARD_AMP*sin(2*pi*p)*|2u-1|`, was dropped in favour of this; a rolling motion was the
 * read the user chose.)
 *
 * ## The rotating thread gradient
 *
 * Each strand is shaded along its length by [threadShade], a lit→shadow gradient keyed to the
 * mesh phase itself. Because that phase carries the `2*pi*K_EPI*p` scroll, the lit band
 * travels along every filament as the field rolls, so each thread reads as a round strand
 * turning and catching the light rather than a flat line. See [shadedColour]/[shadedAlpha].
 */
internal object Silk {

    // ── the frozen structure ─────────────────────────────────────────────────
    /** Ribbons. One, which is the geometry the reference and the user both settled on. */
    const val R = 1
    /** Lissajous frequencies. fx=2 / fy=3 is the chosen figure; it closes after one turn. */
    const val FX = 2f
    const val FY = 3f
    const val AX_FRAC = 0.90f
    /** > 1 on purpose: the ribbons leave the frame top and bottom rather than fitting inside. */
    const val AY = 1.10f
    private val DELTA = floatArrayOf(0f, TWO_PI / 3f, 2f * TWO_PI / 3f)
    private val GAMMA = floatArrayOf(0f, 0.7f, 1.4f)
    private val PHI = floatArrayOf(0f, 1.1f, 2.3f)
    /** Half-width, normalised. Signed — see the class note. */
    const val W0 = 0.14f
    /** Epicyclic spatial frequency. 34 bumps around the ribbon: this is the mesh. */
    const val M_FREQ = 34f
    /** Epicyclic phase shear per unit u — what makes neighbouring strands interfere. */
    const val C_FREQ = 1.7f
    /** Epicyclic displacement, ~4% of W0. */
    const val EPS = 0.006f

    // ── the frozen envelope ──────────────────────────────────────────────────
    private val N_HARM = intArrayOf(1, 2, 3, 5)
    /** E^BETA. Higher is peakier bands; 4.0 is the measured value. */
    const val BETA = 4.0f
    /**
     * The 12 envelope phases, SplitMix64 seed 1337, one uniform draw per phase x 2*pi.
     * Authored constants, not computed, so the figure is identical on every device.
     */
    private val PSI = arrayOf(
        floatArrayOf(4.483122f, 4.994561f, 1.282503f, 2.920932f),
        floatArrayOf(1.439687f, 4.163606f, 2.302567f, 0.588848f),
        floatArrayOf(5.535523f, 1.395522f, 4.864267f, 0.639519f),
    )

    // ── colour across the width ──────────────────────────────────────────────
    /** The warm edge occupies u in [0, W_WARM_EXT]. These set the light budget. */
    const val W_WARM_EXT = 0.06f
    const val W_MID_LO = 0.15f
    const val W_MID_HI = 0.35f
    const val HOT_CORE = 4.0f
    const val HOT_MIX = 0.5f
    /** HOT's share of the u=1 edge. The spec's 0.6 is a warm white on a "cool" edge. */
    const val EDGE_COOL_HOT = 0.2f
    /** The u a ruling takes its colour from. Null would mean the WARM/COOL mean. */
    const val RULING_U = 0.5f

    // ── the one live term ────────────────────────────────────────────────────
    /**
     * Mesh travel along the ribbon, in whole spatial periods per loop.
     *
     * The mesh advances `K_EPI / M_FREQ` of the way around the ribbon per loop. At the
     * spec's `K_EPI = 1` that is **2.9%** — imperceptible, and it made the first attempt at
     * this animation look completely static. 8 gives 24% of the ribbon per loop.
     *
     * Integer, so the loop closes: `fract` of a whole number of periods is itself.
     */
    const val K_EPI = 8f

    /**
     * The static edge bow, in radians of peak phase shift at the ribbon's edges.
     *
     * No `p`: this is part of the frozen structure. It bows the fringes outward at the border
     * (`|2u-1|` peaks at both edges, zero on the centreline), so when the whole mesh scrolls
     * via [K_EPI] the border leads the centre and the sheet reads as a wheel-rim rolling
     * toward the viewer rather than sliding flat. This replaced the dropped in/out "breathe".
     */
    const val EDGE_BOW = 18.0f

    // ── the rotating thread gradient ──────────────────────────────────────────
    /** How far a thread's lit crest pushes toward the HOT highlight. */
    const val THREAD_LIFT = 0.55f
    /** How dark a thread's shadow side gets (fraction of its own colour). */
    const val THREAD_FLOOR = 0.30f
    /** Minimum alpha on a thread's shadow side; the lit crest reaches full strength. */
    const val THREAD_AMIN = 0.40f

    // ── the vibrance travel ──────────────────────────────────────────────────
    /**
     * How many times the vibrance band sweeps the ribbon per loop.
     *
     * One. The user's read is "the vibrance travelling slowly per mesh, essentially slowly
     * rotating", and a slow rotation is one lap per loop — not a fast shimmer. Integer, so
     * the band returns to its start at `p = 1` and the loop closes.
     */
    const val K_VIB = 1f
    /**
     * How far the vibrance band lags across the ribbon's width, in turns.
     *
     * Zero would make the band a straight stripe across the width, which reads as a wipe
     * rather than a turn. A small positive value tilts it, so the band arrives at the cool
     * edge after the warm one and the sheet reads as rotating rather than sliding.
     */
    const val VIB_SHEAR = 0.22f

    // ── derived ──────────────────────────────────────────────────────────────
    private const val TAU_OVER_DEN = 1f / (1f + 1f / 2f + 1f / 3f + 1f / 5f)

    /** `L_j(theta, p)`. Constant in `p` — the structure is frozen. */
    fun centreline(theta: Float, j: Int, aspect: Float, out: FloatArray, o: Int) {
        val ax = AX_FRAC * aspect
        out[o] = ax * sin(FX * theta + DELTA[j % 3])
        out[o + 1] = AY * sin(FY * theta + GAMMA[j % 3])
    }

    /** `T_j`, analytic. Never finite differences — the normal is derived from this. */
    private fun tangent(theta: Float, j: Int, aspect: Float, out: FloatArray, o: Int) {
        val ax = AX_FRAC * aspect
        out[o] = FX * ax * cos(FX * theta + DELTA[j % 3])
        out[o + 1] = FY * AY * cos(FY * theta + GAMMA[j % 3])
    }

    private val tBuf = FloatArray(2)

    /** `n_j = (-T_y, T_x) / max(|T|, 1e-4)`. */
    private fun normal(theta: Float, j: Int, aspect: Float, out: FloatArray, o: Int) {
        tangent(theta, j, aspect, tBuf, 0)
        val mag = max(hypot(tBuf[0], tBuf[1]), 1e-4f)
        out[o] = -tBuf[1] / mag
        out[o + 1] = tBuf[0] / mag
    }

    /**
     * `w_j(theta)` — **signed**. Zero at each pinch, which is what swaps the edges over.
     * Constant in `p` because `K_PINCH` is zero.
     */
    fun width(theta: Float, j: Int): Float = W0 * cos(2f * theta + PHI[j % 3])

    /**
     * `phi(theta, u, p)` — the scalar mesh phase. The `2*pi*c*u` shear makes neighbouring
     * strands interfere; the static `EDGE_BOW * |2u-1|` bows the fringes out at the border;
     * the `2*pi*K_EPI*p` scroll rolls the whole field along the ribbon. Exposed so the
     * renderer can read the same phase for the rotating thread gradient (see [threadShade]).
     */
    fun meshPhase(theta: Float, u: Float, p: Float): Float =
        M_FREQ * theta + TWO_PI * K_EPI * p + TWO_PI * C_FREQ * u +
            EDGE_BOW * abs(2f * u - 1f)

    /** `g(theta, u, p)` — the unit epicyclic vector at [meshPhase]. */
    fun epicyclic(theta: Float, u: Float, p: Float, out: FloatArray, o: Int) {
        val ph = meshPhase(theta, u, p)
        out[o] = cos(ph)
        out[o + 1] = sin(ph)
    }

    /**
     * The thread's lit→shadow weight at a given [meshPhase], in `[0,1]`. Because the phase
     * carries the `2*pi*K_EPI*p` scroll, the crest travels along each filament as the field
     * rolls, so a thread reads as a round strand turning in the light rather than a flat line.
     */
    fun threadShade(ph: Float): Float = 0.5f + 0.5f * cos(ph)

    private val lBuf = FloatArray(2)
    private val nBuf = FloatArray(2)
    private val gBuf = FloatArray(2)

    /**
     * `S_j(theta, u, p) = L_j + (2u-1) * w_j * n_j + eps * g`, in normalised field space
     * where x is in `[-aspect, aspect]` and y in `[-1, 1]`.
     */
    fun surface(theta: Float, u: Float, j: Int, p: Float, aspect: Float, out: FloatArray, o: Int) {
        centreline(theta, j, aspect, lBuf, 0)
        normal(theta, j, aspect, nBuf, 0)
        val w = width(theta, j)
        epicyclic(theta, u, p, gBuf, 0)
        val m = 2f * u - 1f
        out[o] = lBuf[0] + m * w * nBuf[0] + EPS * gBuf[0]
        out[o + 1] = lBuf[1] + m * w * nBuf[1] + EPS * gBuf[1]
    }

    /**
     * `B_j = E^BETA`, the brightness envelope. Constant in `p` because `K_N` is zero.
     *
     * Returned rather than stored so the envelope and the geometry can never drift apart:
     * every caller that wants brightness asks for it at the same theta it placed a strand.
     */
    fun envelope(theta: Float, j: Int): Float {
        val psi = PSI[j % 3]
        var num = 0f
        for (i in N_HARM.indices) {
            num += (1f / N_HARM[i]) * cos(N_HARM[i] * theta + psi[i])
        }
        val e = (0.5f + 0.5f * num * TAU_OVER_DEN).coerceIn(0f, 1f)
        return e.pow(BETA)
    }

    /** The three width weights. These ARE the light budget, not a decoration on it. */
    fun widthWeights(u: Float): Triple<Float, Float, Float> {
        val wWarm = 1f - smoothstep(u, 0f, W_WARM_EXT)
        val wCool = smoothstep(u, W_MID_LO, W_MID_HI)
        return Triple(wWarm, 1f - wWarm - wCool, wCool)
    }

    /**
     * The vibrance multiplier at `(theta, p)`: 1 at the band's far side, `spec.vibrance` at
     * its centre, sweeping once per loop so it reads as the mesh turning.
     *
     * Applied to saturation rather than to alpha, because the point is that the *same* light
     * comes back richer, not that more of it arrives — a travelling brightness would fight
     * the frozen envelope, and the envelope is frozen precisely so the light does not move.
     */
    fun vibranceAt(theta: Float, u: Float, p: Float, vibrance: Float): Float {
        val band = 0.5f + 0.5f * cos(theta - TWO_PI * K_VIB * p + TWO_PI * VIB_SHEAR * u)
        return 1f + (vibrance - 1f) * band
    }

    /**
     * `C(u, B, theta, p)` — the strand colour in **linear** space, vibrance included.
     *
     * Linear, not sRGB: the field is accumulated additively and tone-mapped once at the end,
     * and mixing sRGB values would darken every overlap.
     */
    fun colourAt(
        u: Float,
        b: Float,
        theta: Float,
        p: Float,
        spec: SilkSpec,
        out: FloatArray,
        o: Int,
    ) {
        val (wWarm, wMid, wCool) = widthWeights(u)
        val hotMix = HOT_MIX * b.pow(HOT_CORE)
        val vib = vibranceAt(theta, u, p, spec.vibrance)
        for (c in 0 until 3) {
            val base = wWarm * spec.warm[c] + wMid * spec.mid[c] + wCool * spec.cool[c]
            val hot = spec.hot[c]
            val mixed = base * (1f - hotMix) + hot * hotMix
            // Vibrance scales the distance from the strand's own grey, so a saturated strand
            // gets richer without a neutral one being tinted.
            val grey = (mixed + mixed + mixed) / 3f
            out[o + c] = (grey + (mixed - grey) * vib).coerceAtLeast(0f)
        }
    }

    /**
     * Shade a base linear colour by a thread's lit→shadow weight `s` (from [threadShade]):
     * the lit crest pushes toward HOT by [THREAD_LIFT], the shadow side falls to [THREAD_FLOOR]
     * of the strand's own colour. This is what makes each filament read as a turning strand.
     */
    fun shadedColour(base: FloatArray, bo: Int, s: Float, spec: SilkSpec, out: FloatArray, o: Int) {
        for (c in 0 until 3) {
            val lit = base[bo + c] * (1f - THREAD_LIFT) + spec.hot[c] * THREAD_LIFT
            val shadow = base[bo + c] * THREAD_FLOOR
            out[o + c] = (shadow + (lit - shadow) * s).coerceAtLeast(0f)
        }
    }

    /** A thread's alpha after the rotating gradient: shadow side dims to [THREAD_AMIN]. */
    fun shadedAlpha(alpha: Float, s: Float): Float = alpha * (THREAD_AMIN + (1f - THREAD_AMIN) * s)

    /** The u=1 edge: COOL pulled toward HOT. A small fraction — see the class note. */
    fun coolEdgeColour(spec: SilkSpec, b: Float, out: FloatArray, o: Int) {
        val hotMix = HOT_MIX * b.pow(HOT_CORE)
        for (c in 0 until 3) {
            val base = spec.cool[c] * (1f - EDGE_COOL_HOT) + spec.hot[c] * EDGE_COOL_HOT
            out[o + c] = base * (1f - hotMix) + spec.hot[c] * hotMix
        }
    }

    /** The u=0 edge, with the same HOT mix the body gets. */
    fun warmEdgeColour(spec: SilkSpec, b: Float, out: FloatArray, o: Int) {
        val hotMix = HOT_MIX * b.pow(HOT_CORE)
        for (c in 0 until 3) {
            out[o + c] = spec.warm[c] * (1f - hotMix) + spec.hot[c] * hotMix
        }
    }

    /**
     * Strand opacity at brightness `b`.
     *
     * The oracle's values were `0.02 + 0.16b` against `0.02 + 1.00b` for the edges, and they
     * produced a dense mesh *there* because the oracle accumulated into a float buffer and
     * tone-mapped once. Compose composites with `BlendMode.Plus` into the destination, with
     * no tone map, so the same numbers leave the body far below the eye's threshold while the
     * edges sit at full strength — and the ribbon reads as **thin outlines** rather than as a
     * body of light. Rebalanced: the body carries the ribbon, the edges only draw its rim.
     */
    fun strandAlpha(b: Float): Float = A_STRAND_0 + A_STRAND_B * b

    /** Edge opacity. Deliberately no longer 6x a strand's ceiling — that was the wire look. */
    fun edgeAlpha(b: Float): Float = A_EDGE_0 + A_EDGE_B * b

    // Luminescence lift: with the feathered multi-pass draw each thread's energy is spread
    // across a soft cross-section (dimmer peak than a crisp hairline), so the body alphas are
    // raised to keep the mesh reading as *lit* silk rather than a faint smoke.
    const val A_STRAND_0 = 0.06f
    const val A_STRAND_B = 0.46f
    const val A_EDGE_0 = 0.03f
    const val A_EDGE_B = 0.58f

    /**
     * Strand count from the ribbon's **rendered** width, so a thin ribbon does not draw
     * sub-pixel strands that alias into a smear.
     *
     * Note this is the *rendered* width in pixels, not `W0`: an earlier version of the
     * Python oracle multiplied by `W0` a second time, which made the count scale as `W0^2`
     * and left the preview renders with 12 strands where the device got 56.
     */
    fun strandCount(ribbonWidthPx: Float, ceiling: Int = 56, minSpacingPx: Float = 1.5f): Int =
        (ribbonWidthPx / minSpacingPx).toInt().coerceIn(4, ceiling)

    /** Total radiance is held constant across the LOD, so the budget cannot shift with it. */
    fun alphaScale(m: Int, ceiling: Int = 56): Float = ceiling / m.toFloat()

    fun smoothstep(x: Float, e0: Float, e1: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * One theme's silk: the four palette colours the field draws with, in **linear** space, plus
 * that theme's vibrance.
 *
 * [vibrance] is per-theme because the requirement is that the effect belongs to the theme
 * rather than being a fixed treatment applied to twenty different palettes. A near-neutral
 * theme (Graphite, Silver) wants almost none — pushing its saturation produces colour the
 * palette never had, which is the failure the atmosphere work already hit on mintcoral and
 * ocean. A theme built on saturated hues wants more.
 */
@Immutable
internal data class SilkSpec(
    val bg: FloatArray,
    val cool: FloatArray,
    val mid: FloatArray,
    val warm: FloatArray,
    val hot: FloatArray,
    /** 1.0 is neutral. The travelling band scales saturation by this at its centre. */
    val vibrance: Float,
)
