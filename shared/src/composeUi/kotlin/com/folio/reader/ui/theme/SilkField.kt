package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import com.folio.reader.ui.theme.FieldMotion

/**
 * The silk ribbon field on the hero — the structure frozen, only the mesh moving.
 *
 * ## Why this is drawn, not shaded
 *
 * The field is an **additive accumulation of strand polylines**, which is what the oracle
 * rasterises and what the reference is: a ribbon is not a shape with a fill, it is where many
 * translucent strands happen to overlap. An AGSL fragment shader would have to invert that —
 * find, per pixel, the distance to a twisted swept surface — and the distance to a Lissajous
 * ribbon has no closed form, so it would need an iterative search per pixel over ~2.6M
 * pixels. Drawing the polylines is both cheaper and *exactly* the model the desktop suite
 * pins, so the shader and the model cannot disagree: there is only one.
 *
 * ## The clock
 *
 * [SILK_MOTION_TICK_MS] is 16 ms, not the house [SLOW_MOTION_TICK_MS] of 100. The house tick
 * exists so the app can go idle, and it is right for the ambient light and the drifting mesh
 * — both are far below the eye's threshold at 10 Hz. This is not: the mesh travels a quarter
 * of the way around the ribbon per loop, and at 10 Hz that steps visibly. The guilloche plate
 * already made the same call for the same reason ([GUILLOCHE_MOTION_TICK_MS]).
 *
 * The cost is real and worth stating: at 16 ms the hero re-draws ~60x a second for as long as
 * it is on screen, which is the one thing the slow-clock discipline exists to avoid. It is
 * paid only while the hero is visible, and [animate] is false under reduce-motion.
 */
internal const val SILK_MOTION_TICK_MS = 16L

/**
 * One loop. The oracle's `PERIOD_S = 40` reads as a mechanism when the mesh rolls app-wide;
 * stretched to 48 s the wheels turn perceptibly slower while the 16 ms tick keeps it smooth.
 * The loop still closes exactly because only the integer `K_EPI` scroll depends on `p`.
 */
internal val SILK_PERIOD_MS = FieldMotion.silkStrandPeriodMs

/**
 * The glow halo around each thread. A thread is **not** drawn as a hard hairline with a halo
 * under it — that leaves a visible wire. Instead every segment is drawn as a feathered stack
 * of wide→narrow additive strokes ([SILK_FEATHER]) with no crisp 1 px core, so each filament
 * is soft to its centre and dissolves into its own glow. Because the passes are additive,
 * neighbouring threads and their halos merge into one luminous field rather than reading as
 * distinct lines, and that soft light is what tints the theme behind it.
 *
 * Each pair is `(widthMultiple, alphaShare)`, outermost first (widest/faintest), building a
 * roughly Gaussian cross-section. The widths scale off the hairline [silkStrokeWidth].
 */
private val SILK_FEATHER: List<Pair<Float, Float>> = listOf(
    6.0f to 0.16f,
    3.4f to 0.30f,
    1.8f to 0.58f,
)

/**
 * Samples along each strand, and so the segment count: each strand is drawn as
 * [SILK_SAMPLES] short stroked segments so the rotating thread gradient can ride it.
 *
 * This cannot be lowered for performance. The mesh phase completes `M_FREQ = 34` cycles
 * along a strand, so the thread gradient has 34 lit crests; resolving them without aliasing
 * (Nyquist, with headroom) needs ~4–5 samples per crest, i.e. ~140–160. Below that the
 * crests beat against the sampling and the gradient shimmers wrongly. The lever for cost is
 * [SILK_SEGMENT_BUDGET], which caps the strand *count*, not this.
 */
private const val SILK_SAMPLES = 160

/** Strand ceiling. The LOD may lower it; nothing raises it. */
private const val SILK_M_CEILING = 32

/**
 * The hard ceiling on segments drawn per frame — the one knob that keeps this from becoming
 * laggy as a full-screen, every-tab, 60 fps background.
 *
 * Each segment is one `drawPath` with `BlendMode.Plus`, so segments ≈ GPU draw ops per frame.
 * At [SILK_SAMPLES] = 160 this budget admits `2560 / 160 = 16` interior strands plus the two
 * edges — ~2720 ops — which is the agreed ceiling for an always-on background and well under
 * the ~5400 the full 32-strand field would cost. The strand count is capped to honour this
 * no matter the screen size or the LOD's ceiling, so nothing can unintentionally blow past it.
 *
 * The geometry is frozen (only the sub-pixel `EPS = 0.006` mesh term moves), so the per-frame
 * CPU is dominated by these draws, not by re-deriving the ribbon; capping draws caps the frame.
 *
 * NB device-unverified here (no on-device profiler in this environment): 2720 additive stroked
 * paths at 60 fps should hold on current hardware, but this budget is the single value to lower
 * first if a low-end device drops frames.
 */
private const val SILK_SEGMENT_BUDGET = 3520

/**
 * The strand count after both the LOD and the segment budget have had their say.
 *
 * The LOD ([Silk.strandCount]) lowers the count on a thin ribbon so it does not draw
 * sub-pixel strands; the budget then caps it so the per-frame segment count
 * (`m * SILK_SAMPLES` plus the two edges) can never exceed [SILK_SEGMENT_BUDGET]. The two
 * edges are counted against the budget so the cap is honest. [Silk.alphaScale] later brightens
 * each surviving strand to hold the field's total energy constant as the count drops, so
 * capping strands dims nothing — it only coarsens the mesh.
 */
private fun budgetedStrands(ribbonPx: Float): Int {
    val lod = Silk.strandCount(ribbonPx, SILK_M_CEILING)
    val byBudget = ((SILK_SEGMENT_BUDGET / SILK_SAMPLES) - 2).coerceAtLeast(1)
    return lod.coerceAtMost(byBudget)
}

/**
 * @param spec the theme's four colours plus its vibrance.
 * @param animate false parks the field at `p = 0` — the same structure, merely still.
 */
@Composable
internal fun Modifier.folioSilk(
    spec: SilkSpec,
    animate: Boolean,
): Modifier {
    val phases: State<List<Float>>? = if (animate) {
        rememberSlowPhases(listOf(SILK_PERIOD_MS), tickMs = SILK_MOTION_TICK_MS)
    } else {
        null
    }
    return drawWithCache {
        val aspect = size.width / size.height.coerceAtLeast(1f)
        // The ribbon's full rendered width in px: 2*W0 in normalised units, and one
        // normalised unit is height/2 px. Passing this to the LOD is what keeps a thin
        // ribbon from drawing sub-pixel strands that alias into a smear.
        val ribbonPx = Silk.W0 * size.height
        val m = budgetedStrands(ribbonPx)
        val ascale = Silk.alphaScale(m, SILK_M_CEILING)
        onDrawBehind {
            val p = (phases?.value?.getOrNull(0) ?: 0f) / (2f * PI.toFloat())
            drawSilk(spec, p, aspect, m, ascale)
        }
    }
}

/**
 * The ground plane's three incommensurate drift periods. Co-prime-ish on purpose: three
 * loops on related periods settle into a repeating lattice, which is the one thing a field
 * whose whole job is to never look like a pattern cannot do.
 */
private val FIELD_PERIODS_MS = FieldMotion.silkPeriodsMs

// §Vibrancy — how far the bloom alpha travels from its measured base (0.20 dark / 0.46 light) to
// the vivid / muted ends of the Muted ↔ Vivid lever. Both are 0 at VIBRANCY_NEUTRAL, so the base
// keeps its calibrated value there; the chroma itself rides the already-enriched atmosphere pools.
private const val BLOOM_ALPHA_SPAN = 0.45f
private const val BLOOM_ALPHA_MUTE = 0.45f

/**
 * How often the ground plane re-samples its drift: 20 Hz, not per vsync.
 *
 * This is the app's single most expensive per-frame invalidation, and it is worth stating
 * exactly why, because the cost is not where it looks. The field is the *source* of the
 * §16 room rung ([com.folio.reader.ui.components.folioRoomSource]), so every frame it
 * re-records also forces **every** glass surface in the app to re-record its own Haze
 * scratch layer and re-run its blur — Haze caches nothing across frames. An animating room
 * therefore costs `N_glass × full-area blur` per frame, at 60–120 Hz. Slowing the room does
 * not slow the animation (the phase is computed from elapsed time, so the drift is exactly
 * as fast and exactly as smooth at 20 Hz as at 120 — see [rememberSlowPhases]); it only
 * stops the room asking for a frame it does not need.
 *
 * 50 ms rather than the house [SLOW_MOTION_TICK_MS] of 100: the blooms travel a visible
 * fraction of their orbit per second, and at 10 Hz the three-lobe Lissajous orbit steps.
 * 20 Hz is below notice for motion this slow while still being 3–6× fewer invalidation
 * rounds on a 60/120 Hz display.
 *
 * The alternative — a scoped [androidx.compose.ui.platform.InfiniteAnimationPolicy] — was
 * rejected after checking: the effect coroutine context comes from the `Recomposer`
 * (`Composer.applyCoroutineContext` → `parentContext.effectCoroutineContext`), so a
 * `CompositionLocalProvider` cannot inject one into a subtree, and installing it globally
 * would throttle every shimmer skeleton and progress indicator in the app to 20 Hz too.
 */
private val FIELD_TICK_MS = FieldMotion.silkTickMs

/**
 * The silk as the **whole app's background**, behind every tab — not a hero-local field.
 *
 * This is the single ground plane for the app: placed once at the root (see MainActivity), it
 * shows through Home, Library, Stats, Settings, Search and every future tab, so no screen can
 * be left out by forgetting to paint a background and no per-screen background can drift out of
 * sync with it.
 *
 * It is **public and self-contained** on purpose. It is called from the app module, across the
 * module boundary, so it cannot take the `internal` [SilkSpec]; instead it reads the live theme
 * itself — the palette, the atmosphere's top/bottom field colours, the pack id's vibrance and
 * the motion preference — so a caller supplies nothing and cannot wire it to the wrong theme.
 *
 * It is two layers in one draw: a vertical top→bottom theme gradient that guarantees the screen
 * is covered edge to edge whatever the route, then the additive silk over it. The gradient is
 * the ground the strands glow over, so a half-painted screen can never flash through. Reduce
 * motion parks the mesh at `p = 0` — the same field, merely still.
 */
/**
 * The app's whole background, behind every tab — a calm aurora, not a wire mesh.
 *
 * ## Why this is a drifting-bloom field and not the strand mesh
 *
 * The strand mesh ([folioSilk]) renders the oracle's Lissajous ribbon as thousands of thin
 * additive line-strokes. On a dark room that reads as luminous silk only when the strokes
 * overlap into a glow; drawn straight to the surface with low per-stroke alpha (no HDR float
 * buffer + tone map — see [linearToColor]) the overlaps clip instead of blooming, so on device
 * it reads as grey scratches. As an always-on, every-tab ground plane that scratchy texture is
 * what the eye locks onto, and the dark gradient under it carried almost no colour, so the room
 * read bland.
 *
 * This replaces it with the thing the mesh was *trying* to be: the theme's three authored aurora
 * hues ([FolioAtmosphere.pools] — glowPrimary, glowSecondary, warmAccent) as large, soft radial
 * blooms drifting slowly over the field gradient. There are no hard edges to alias into
 * scratches, and the colour is real because it *is* the palette's own aurora, not a tint of near
 * black.
 *
 * ## Vibrance vs. the glass
 *
 * Almost everything on screen is glass (panes, cards, bars) that blurs and tints down whatever
 * sits behind it, so the ground plane has to be drawn *stronger* than it should look bare: the
 * glass is the dimmer. The blooms are therefore drawn well above [FolioAtmosphere.poolAlpha]
 * (which is tuned for aurora read *through* nothing), trusting the glass on top to pull them back
 * to a whisper everywhere a surface sits.
 *
 * ## The clock
 *
 * Motion is [rememberSlowPhases] at [FIELD_TICK_MS] — 20 Hz, not per vsync. This layer is the
 * source of the §16 room rung, so every frame it invalidates also forces every glass surface in
 * the app to re-record its Haze scratch layer and re-run its blur, and Haze caches nothing across
 * frames. The phase is computed from elapsed time, so the drift is exactly as fast and as smooth
 * at 20 Hz as it would be at 120; the tick only decides how often the picture is redrawn. Three
 * incommensurate periods keep the blooms from ever locking into a repeating pattern.
 * Reduce-motion parks them at their start positions.
 *
 * @param forceStatic parks the drift; see the clock note above.
 * @param blooms whether the three drifting pool blooms are drawn at all. They are three
 *   full-screen radial-gradient `drawRect`s — the expensive three quarters of this layer — and
 *   they are *invisible* whenever an opaque Mirage field is painting over this base
 *   ([FolioThemeBackground] passes false there). [forceStatic] already stopped them *moving*;
 *   it did not stop them being re-rasterised every time the room layer is re-recorded, which
 *   inside the §16 room rung is every frame the field invalidates. The ramp is always drawn, so
 *   turning the blooms off can never leave the window uncovered.
 */
@Composable
fun Modifier.folioSilkBackground(
    forceStatic: Boolean = false,
    blooms: Boolean = true,
): Modifier {
    val top = FolioTheme.atmosphere.fieldTop
    val bottom = FolioTheme.atmosphere.fieldBottom
    val dark = FolioTheme.atmosphere.isDark
    val pools = FolioTheme.atmosphere.pools
    // [forceStatic] parks the drift. Two callers want it: reduce-motion (via
    // [rememberMotionEnabled]) and — crucially for performance — the case where an opaque
    // per-theme Mirage shader is painting over this base edge to edge ([FolioThemeBackground]).
    // In that case this layer is never seen, so running its per-vsync infinite transition is a
    // wasted full-screen redraw every frame; freezing it removes that whole invalidation source
    // while still leaving a correct still picture for the brief moment before the shader paints.
    // With [blooms] off there is nothing left on this layer that moves, so the clock is dropped
    // too rather than ticking for a picture that cannot change.
    val animate = rememberMotionEnabled() && !forceStatic && blooms

    // This modifier is now the **base** ground-plane layer only: a vertical ramp plus soft
    // drifting blooms. The per-theme Mirage animation is a separate opaque composable stacked
    // over it ([FolioThemeField], placed by [FolioThemeBackground]); where that shader runs it
    // covers this layer edge to edge, and where it cannot (desktop / Skiko / Android < 33) this
    // ramp-plus-bloom field is the complete ground plane, byte-for-byte as before (Rule 19).
    // Sampled on [FIELD_TICK_MS], not per vsync: this layer is the §16 room rung's source, so
    // every frame it invalidates also forces every glass surface in the app to re-blur. Frozen
    // outright when motion is off, so reduce-motion costs nothing rather than a still frame
    // per tick.
    val phases: State<List<Float>> = if (animate) {
        rememberSlowPhases(FIELD_PERIODS_MS, tickMs = FIELD_TICK_MS)
    } else {
        remember { mutableStateOf(FIELD_PERIODS_MS.map { 0f }) }
    }

    // Each bloom sits in the room strongly: the glass over nearly everything will dim it back
    // down, so bare it has to be bold or the glass leaves nothing to tint. On dark fields the
    // blooms are composited *additively* so where two overlap they brighten into a luminous
    // aurora instead of flattening into one muddy wash — the thing that made the field read as
    // "no animation" on muted packs. Light fields keep plain over-compositing (additive would
    // just blow the paper to white) but still get the chroma lift so the colour actually reads.
    val additive = dark
    // P8: dark 0.52 → 0.38. This alpha is *the* single biggest contributor to a dark room
    // reading as "not dark enough", because it is composited **additively** — every unit of it
    // is light added to the page, and on a near-black field there is nothing for it to be lost
    // against. The blooms still overlap into a luminous aurora (the reason the additive blend
    // exists), they are just no longer bright enough to lift the whole floor. Light fields are
    // untouched: their 0.46 is plain over-compositing and does not add light the same way.
    //
    // P9: dark 0.38 → 0.20. The reasoning above was right and did not go far enough. At 0.38 a
    // `vivid`-boosted pool adds up to ~0.30 of its own channel value *on top of* the page under
    // `BlendMode.Plus`, which on `dawn`/Dusk puts the bloom core near #563677 over an authored
    // page of #05030F — three coloured spotlights, measured. The aurora reads at 0.20 because
    // additive light is perceptually cheap on a near-black bed; the thing that was expensive was
    // the floor it was lifting. Paper is still untouched.
    // §Vibrancy change B: the pools now arrive **already enriched** from `atmosphereFor` — the
    // one Muted ↔ Vivid lever is applied once in the derivation and consumed identically here and
    // by the Android Mirage shader, so the old path-specific `vivid()` boost is gone (chroma is a
    // derivation concern now, not a per-renderer one). What stays here is the bloom's *alpha*,
    // which still rides the lever: a more vivid room draws its aurora a touch more present. Both
    // terms are 0 at VIBRANCY_NEUTRAL, so the base keeps its measured 0.20 / 0.46 there.
    val vibrancy = FolioTheme.atmosphere.vibrancy
    val vibeUp = ((vibrancy - VIBRANCY_NEUTRAL) / (1f - VIBRANCY_NEUTRAL)).coerceIn(0f, 1f)
    val vibeDown = ((VIBRANCY_NEUTRAL - vibrancy) / VIBRANCY_NEUTRAL).coerceIn(0f, 1f)
    val bloomAlpha = (if (dark) 0.20f else 0.46f) *
        (1f + vibeUp * BLOOM_ALPHA_SPAN - vibeDown * BLOOM_ALPHA_MUTE)

    return drawWithCache {
        val w = size.width
        val h = size.height
        val maxDim = kotlin.math.max(w, h)
        val fieldBrush = Brush.verticalGradient(listOf(top, bottom))
        val blend = if (additive) BlendMode.Plus else BlendMode.SrcOver
        onDrawBehind {
            drawRect(fieldBrush)
            // Read in the draw phase, so a tick re-draws this layer without recomposing the
            // tree that contains it — the same discipline the ambient light and the lamp keep.
            if (!blooms) return@onDrawBehind
            val p = phases.value
            if (pools.isNotEmpty()) {
                drawBloom(pools[0], p.getOrElse(0) { 0f }, 0.26f, 0.30f, 1.20f, bloomAlpha, blend, w, h, maxDim)
            }
            if (pools.size > 1) {
                drawBloom(pools[1], p.getOrElse(1) { 0f }, 0.78f, 0.64f, 1.05f, bloomAlpha, blend, w, h, maxDim)
            }
            if (pools.size > 2) {
                drawBloom(pools[2], p.getOrElse(2) { 0f }, 0.52f, 0.90f, 0.92f, bloomAlpha * 0.9f, blend, w, h, maxDim)
            }
        }
    }
}

/**
 * A single soft radial bloom whose centre drifts on a slow Lissajous orbit around an anchor.
 *
 * The orbit radius is a small fraction of the screen, so the bloom wanders without ever leaving
 * — the field stays a field. The colour fades fully to transparent at the bloom's edge, so there
 * is no ring and nothing to alias; many of these over the gradient read as one living aurora.
 */
private fun DrawScope.drawBloom(
    color: Color,
    phase: Float,
    anchorXFrac: Float,
    anchorYFrac: Float,
    radiusFrac: Float,
    alpha: Float,
    blendMode: BlendMode,
    w: Float,
    h: Float,
    maxDim: Float,
) {
    val orbit = maxDim * 0.14f
    val cx = anchorXFrac * w + cos(phase) * orbit
    val cy = anchorYFrac * h + sin(phase * 0.8f) * orbit
    val radius = maxDim * radiusFrac
    val brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = Offset(cx, cy),
        radius = radius,
    )
    drawRect(brush = brush, blendMode = blendMode)
}

/**
 * Additive, like the oracle: every strand adds, nothing occludes, there is no depth.
 *
 * `BlendMode.Plus` rather than alpha-over is not a stylistic choice — an alpha-over ribbon
 * would show each strand hiding the one behind it, which is the one thing silk does not do.
 *
 * Each strand is drawn as a run of short segments rather than one polyline so the **rotating
 * thread gradient** can ride along it: every segment is lit toward HOT on the crest of the
 * mesh phase and falls to shadow in its trough, which is what makes a filament read as a
 * turning strand of silk instead of a flat line.
 */
private fun DrawScope.drawSilk(
    spec: SilkSpec,
    p: Float,
    aspect: Float,
    m: Int,
    ascale: Float,
) {
    val w = size.width
    val h = size.height
    val step = (2f * PI.toFloat()) / SILK_SAMPLES
    val pt = FloatArray(2)
    val col = FloatArray(3)
    val seg = Path()

    // ── the body: m strands, each a run of thread-gradient segments ──
    for (si in 0 until m) {
        val u = if (m == 1) 0.5f else si / (m - 1f)
        drawStrand(spec, u, p, aspect, ascale, w, h, step, seg, pt, col, Edge.NONE)
    }

    // ── the two edges. These are the bright lines, so they carry the HOT mix ──
    drawStrand(spec, 0f, p, aspect, ascale, w, h, step, seg, pt, col, Edge.WARM)
    drawStrand(spec, 1f, p, aspect, ascale, w, h, step, seg, pt, col, Edge.COOL)
}

/** Which colour law a strand uses: the interior law, or one of the two bright edges. */
private enum class Edge { NONE, WARM, COOL }

/**
 * A strand's stroke width in px, at the oracle's relative weight.
 *
 * The Python rasteriser drew 1 px at 411 px wide, so the same visual weight at 1080 px is
 * 2.6 px. This has to be **explicit**: `drawPath` defaults to `Fill`, and Compose closes an
 * open path implicitly, so a strand drawn without a style is not a line at all — it is the
 * filled region enclosed by the strand's own wiggle. That both misdraws the mesh and makes
 * every strand a complex polygon for the rasteriser instead of a hairline.
 */
private val DrawScope.silkStrokeWidth: Float
    get() = (size.width / 411f).coerceAtLeast(1f)

/**
 * Draw one strand as short segments, shading each by the rotating thread gradient.
 *
 * The colour and alpha are sampled at each segment's **midpoint** so a segment's tint and its
 * mesh phase describe the same place on the strand. Interior strands take [Silk.colourAt];
 * the two edges take their own warm/cool edge law. Either way the thread gradient
 * ([Silk.shadedColour]/[Silk.shadedAlpha]) is applied last, over whichever base colour.
 */
private fun DrawScope.drawStrand(
    spec: SilkSpec,
    u: Float,
    p: Float,
    aspect: Float,
    ascale: Float,
    w: Float,
    h: Float,
    step: Float,
    seg: Path,
    pt: FloatArray,
    col: FloatArray,
    edge: Edge,
) {
    val sw = silkStrokeWidth
    var px = 0f
    var py = 0f
    for (k in 0..SILK_SAMPLES) {
        val theta = k * step
        Silk.surface(theta, u, 0, p, aspect, pt, 0)
        val x = (pt[0] / (2f * aspect) + 0.5f) * w
        val y = (0.5f - pt[1] / 2f) * h
        if (k > 0) {
            val thetaMid = theta - step * 0.5f
            val b = Silk.envelope(thetaMid, 0)
            val shade = Silk.threadShade(Silk.meshPhase(thetaMid, u, p))
            val alphaBase = when (edge) {
                Edge.NONE -> {
                    Silk.colourAt(u, b, thetaMid, p, spec, col, 0)
                    Silk.strandAlpha(b)
                }
                Edge.WARM -> {
                    Silk.warmEdgeColour(spec, b, col, 0)
                    Silk.edgeAlpha(b)
                }
                Edge.COOL -> {
                    Silk.coolEdgeColour(spec, b, col, 0)
                    Silk.edgeAlpha(b)
                }
            }
            Silk.shadedColour(col, 0, shade, spec, col, 0)
            val alpha = Silk.shadedAlpha((alphaBase * ascale).coerceIn(0f, 1f), shade)
                .coerceIn(0f, 1f)
            seg.reset()
            seg.moveTo(px, py)
            seg.lineTo(x, y)
            // Feathered filament: a wide->narrow stack of additive strokes with no crisp 1 px
            // core, so each thread is soft to its centre and dissolves into its own glow.
            // Additive overlap melts neighbouring threads into one luminous field instead of
            // distinct lines, and that soft light is what tints the theme behind it.
            for (layer in SILK_FEATHER) {
                drawPath(
                    seg,
                    linearToColor(col, (alpha * layer.second).coerceIn(0f, 1f)),
                    style = Stroke(width = sw * layer.first),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        px = x
        py = y
    }
}

/**
 * Linear-light floats to a Compose colour.
 *
 * The oracle tone-maps (`1 - exp(-expose * c)`) before the OETF, which needs a per-pixel
 * pass; here the exposure is folded into the alphas and only the OETF is applied. The
 * difference is that very bright overlaps clip instead of rolling off, which is why the
 * strand alphas stay low.
 */
private fun linearToColor(lin: FloatArray, alpha: Float): Color {
    val r = oetf(lin[0])
    val g = oetf(lin[1])
    val b = oetf(lin[2])
    return Color(r, g, b, alpha)
}

/** The sRGB opto-electronic transfer function, linear 0..1 -> 0..1. */
private fun oetf(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
}
