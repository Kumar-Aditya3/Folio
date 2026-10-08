package com.folio.reader.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * The plate, drawn.
 *
 * The formation, in one paragraph, because it is the whole feature: **three long sine
 * waves crossed at different angles, superposed.** Each wave is a fine line family; each is
 * displaced by a slower perpendicular wave (its braid), which is what turns parallel stripes
 * into ornamental line work. Where two crests pass near each other the strokes bunch into a
 * bright ribbon; where they cancel, a calm gap. That bunch-and-spread rhythm is the visual
 * — it comes from the *interference*, which is why the earlier concentric-rings design
 * could never produce it (a radial family has even density by construction, so it reads as
 * a target and not as a ribbon).
 *
 * Then two accents, from the reference frame: a **radial fan** of fine lines struck where
 * the theme's own light comes from, and a few long **light streaks** raking across the
 * weave — so the plate reads as lit rather than as printed.
 *
 * **Motion** is a *phase walk*, not a translation. Each wave's own phase advances at its own
 * rate and sign ([GuillocheWave.drift]), so the interference pattern drifts through itself:
 * ribbons widen, slide and re-form. Translating the finished image instead would read as
 * scrolling wallpaper.
 *
 * **Colour** is derived, never authored. The line colour comes from [guillocheLineColor],
 * the same polarity-aware rule the model tests pin: a shadow cut into a dark room, a
 * chroma-matched line on paper. So the plate is the theme's own colour on both faces, and
 * the amplitude that satisfies "same vibrance in light and dark" is the model's job rather
 * than the shader's.
 *
 * **Guard rails, all in the model:** the plate's weight is multiplied by the quiet-region
 * mask (`guillocheWeightAt`) so text regions are *exactly* silent, and the whole term is
 * capped by `intensity` at or below `GUILLOCHE_ALPHA_MAX`.
 */
/**
 * The guilloche plate's own clock, faster than the house ambient [SLOW_MOTION_TICK_MS].
 * The plate drift is watched directly, so it samples once per vsync (~60Hz via the
 * `withFrameNanos` inside [rememberSlowPhases]); 10Hz makes the moiré visibly step. The
 * tick only runs while motion is enabled, so a reduce-motion/still room pays nothing.
 */
private const val GUILLOCHE_MOTION_TICK_MS = 16L

@Composable
actual fun Modifier.folioGuilloche(
    packId: String,
    fieldTop: Color,
    fieldBottom: Color,
    fieldSourceX: Float,
    fieldSourceY: Float,
    isDark: Boolean,
    intensity: Float,
    enabled: Boolean,
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    // Reduce-motion stills the plate but does not remove it: the engraving is a legitimate
    // static look, and a field with no plate on a reduce-motion device would be a different
    // design rather than the same design at rest. Parking the phase to its t=0 value is
    // exactly what the ambient film does, and it is what Rule 19 asks for — a complete
    // still image, never a frozen animation.
    val motion = rememberMotionEnabled()
    // The spec is pure and seed-derived; a theme change re-derives it once.
    val spec = remember(packId) { guillocheSpecFor(packId) }
    // The quiet regions, read in the draw phase. A registration rebuilds this brush and
    // never recomposes the tree under the root.
    val quiet = LocalFolioGuillocheQuiet.current
    // The plate's drift is the one slow-clock layer a reader *does* watch directly, so it
    // runs on a smooth per-vsync tick rather than the house 10Hz ambient tick: at 10Hz the
    // moiré rosettes visibly step. This stays in the draw phase (no recompose) and is gated
    // on motion, so a still room costs nothing. See GUILLOCHE_MOTION_TICK_MS.
    val phases = rememberSlowPhases(spec.periodsMs, tickMs = GUILLOCHE_MOTION_TICK_MS)
    // `FolioTheme.colors` is a @Composable read, so the accents have to be resolved here
    // rather than inside the draw cache — and resolving them here is also what makes the
    // plate follow a live theme change instead of the palette it was first drawn with.
    // Three of them: the theme authors three guilloche hues (§18), and a plate drawn in one
    // is a monochrome wash however good its geometry is.
    val tiers = guillocheTiersFor(FolioTheme.colors, isDark)
    val accent = tiers[0]
    val accentB = tiers[1]
    val accentC = tiers[2]
    // A device that rejects the AGSL draws nothing rather than crashing — the same visual
    // outcome as the pre-shader fallback.
    val shader = remember { runCatching { RuntimeShader(GUILLOCHE_AGSL) }.getOrNull() }
        ?: return this
    return this.drawWithCache {
        // One shared setter for the plate's whole uniform block, so the gel — which declares
        // the same block and refracts the same figure — cannot be wired differently. See
        // applyGuillocheUniforms.
        shader.applyGuillocheUniforms(spec = spec, isDark = isDark)
        // The three colours are `plateAt` parameters rather than part of the shared block, so
        // each shader spells them in its own namespace and sets them itself. Here they are the
        // bare names this source declares; the gel's are `plateAccent`/`plateFieldTop`/
        // `plateFieldBottom`, because the gel already owns `fieldTop`/`fieldBottom` for its
        // own film and has no `accent` at all.
        shader.setFloatUniform("accent", accent.red, accent.green, accent.blue)
        shader.setFloatUniform("accentB", accentB.red, accentB.green, accentB.blue)
        shader.setFloatUniform("accentC", accentC.red, accentC.green, accentC.blue)
        shader.setFloatUniform("fieldTop", fieldTop.red, fieldTop.green, fieldTop.blue)
        shader.setFloatUniform("fieldBottom", fieldBottom.red, fieldBottom.green, fieldBottom.blue)
        // The field's own gradient origin, so the plate is cut out of the ramp the page
        // actually drew — see `fieldT` in FolioFieldModel.kt.
        shader.setFloatUniform("fieldSource", fieldSourceX, fieldSourceY)
        shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
        val brush = ShaderBrush(shader)
        onDrawBehind {
            shader.setFloatUniform("size", size.width, size.height)
            // Aspect correction is mandatory: the reference's sweeps are long and
            // horizontal-dominant, and an uncorrected uv on a 1080x2392 phone would squash
            // them into vertical stripes.
            shader.setFloatUniform("aspect", size.width / size.height.coerceAtLeast(1f))
            val ps = phases.value
            shader.applyPlateClock(
                phase = if (motion) ps.getOrElse(0) { 0f } else 0f,
                breath = if (motion) ps.getOrElse(1) { 0f } else 0f,
            )
            // The quiet mask, shared with the gel for the same reason as the block above.
            shader.applyQuietUniforms(quiet)
            drawRect(brush)
        }
    }
}

/** How many quiet regions the shader carries uniforms for. */
private const val GUILLOCHE_MAX_QUIET_REGIONS = 4

/** How many waves the uniforms carry — the register's own ceiling. */
private const val GUILLOCHE_MAX_UNIFORM_WAVES = 3

/**
 * The plate's **uniform block**, named exactly as [GUILLOCHE_AGSL] declares it.
 *
 * The gel refracts the plate, so the gel's own AGSL has to carry these same uniforms and
 * evaluate the same arithmetic. Declaring them once here and interpolating the block into
 * both sources is the cheapest possible version of "the two cannot drift": there is one
 * spelling of `fanSpin`, not two that a rename or a retune can leave disagreeing. The
 * setter side is shared too — see [applyGuillocheUniforms] — so a uniform cannot be added
 * to the shader and forgotten by the Kotlin that feeds it.
 *
 * Every one of these is read in `main` of the source that uses it, and all of them are
 * read in the gel's `plateAt` (whose result `main` uses), so SkSL's dead-uniform stripping
 * cannot remove any of them. That is a constraint on the *code*, not on this block: a name
 * added here and unused in both `main`s is exactly the trap the comments below record.
 */
internal const val GUILLOCHE_UNIFORMS = """
uniform float aspect;
uniform float2 fieldSource;
uniform float faceWeight;
uniform float isDark;
uniform float darkDepth;
uniform float darkLift;
uniform float darkGlow;
uniform float minVisibleDelta;
uniform float lightTint;
uniform float lightDepth;
// §29: the plate's shape family, as the ordinal of GuillocheGeometry. Numeric, so it
// belongs in the shared block — both shaders must bend the *same* figure, and a family
// is part of the figure.
uniform float family;
// How far the whole plate may dim on its slowest breath. See GUILLOCHE_SHIMMER.
uniform float shimmerDepth;
// The three waves: frequency, braid depth, braid frequency, angle, stroke weight,
// contribution, and how many whole stroke periods this wave's family creeps per lap.
uniform float freq0; uniform float cross0; uniform float crossFreq0;
uniform float angle0; uniform float weight0; uniform float share0; uniform float drift0;
uniform float freq1; uniform float cross1; uniform float crossFreq1;
uniform float angle1; uniform float weight1; uniform float share1; uniform float drift1;
uniform float freq2; uniform float cross2; uniform float crossFreq2;
uniform float angle2; uniform float weight2; uniform float share2; uniform float drift2;
// The corner fan.
uniform float2 fanCentre;
uniform float fanRays;
uniform float fanReach;
uniform float fanShare;
uniform float fanSpin;
// Quiet regions: up to four rectangles, each with its own cover, plus the ease distance.
// Carried by the gel as well, and for the same reason the plate carries them: the pane is
// a passenger *in* the field, so a quiet region has to silence the refraction exactly where
// it silences the engraving. One mask, or the pane pokes holes in the reader's clean page.
uniform float quietCount;
uniform float4 quiet0; uniform float quietCover0;
uniform float4 quiet1; uniform float quietCover1;
uniform float4 quiet2; uniform float quietCover2;
uniform float4 quiet3; uniform float quietCover3;
uniform float quietMargin;
"""

/**
 * The plate's **helper functions**: the weave, the fan, the streaks, the quiet mask, and the
 * three colour rules. Interpolated into both the plate's own source and the gel's.
 *
 * These are pure functions of their arguments — no globals, no uniforms — which is the
 * property that makes them shareable at all. Anything stateful belongs in a uniform block.
 * [plateAt] below is the one entry point both sources call.
 */
internal const val GUILLOCHE_FUNCTIONS = """
float3 mix3(float3 a, float3 b, float t) { return a + (b - a) * t; }

float3 deepen3(float3 c, float amount) { return mix3(c, float3(0.0), clamp(amount, 0.0, 1.0)); }

// sRGB-ish relative luminance, matching FolioAtmosphere.luminanceOf closely enough for a
// colour decision (the exact ladder lives in the model; this only has to agree on *which
// side of the field* the line falls, which is what matchLuma needs).
float luma3(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// WCAG relative luminance — the *linearised* ladder, which is what
// `GUILLOCHE_MIN_VISIBLE_DELTA` is quoted in.
//
// [luma3] above is a gamma-space dot product and is NOT a substitute: measured on the
// dark fields that decide this branch, a 5% deepen produces a constant luma3 delta of
// 3.9e-3 for every grey from 10 to 29, while the true WCAG delta ranges from 3.0e-4 to
// 6.7e-4 across the same span — i.e. luma3 says "clearly visible" for cases the model
// calls invisible, and the two disagree *at the decision boundary* (grey 15→16 is
// identical in luma3 and crosses the threshold in WCAG). A shader deciding the plate's
// visibility has to use the same ruler as the model that specifies it, or the fix ships
// in the Kotlin and vanishes on the device.
float wcagChannel(float u) {
    return (u <= 0.04045) ? (u / 12.92) : pow((u + 0.055) / 1.055, 2.4);
}
float wcagLuma3(float3 c) {
    return 0.2126 * wcagChannel(c.r) + 0.7152 * wcagChannel(c.g) + 0.0722 * wcagChannel(c.b);
}

// matchLuma: move the colour to `target` lightness **without moving its hue**. Mirrors
// FolioAtmosphere.matchLuma, and the mirror has to be exact — the Kotlin model is what the
// design tests assert, so a shader that clamped per channel would ship a hue rotation the
// tests can never see (Rule 19).
//
// Darkening is a plain affine scale: k < 1 cannot leave the gamut, and a uniform scale
// multiplies all three channels by the same number, so every ratio — and therefore the hue,
// exactly — survives. No search needed. Lifting cannot be done by scaling, because the
// first channel to reach 1.0 would stop while the others keep climbing; that is precisely
// the hue rotation the old `clamp(c * k, 0, 1)` caused, and it is why the result is reached
// by bisecting a mix toward white instead — affine, and in-gamut at every step.
float3 matchLuma3(float3 c, float target) {
    float l = luma3(c);
    if (l <= 0.0001) return c;
    if (target <= l) return c * (target / l);
    float lo = 0.0;
    float hi = 1.0;
    for (int i = 0; i < 24; i++) {
        float mid = (lo + hi) * 0.5;
        bool under = luma3(mix(c, float3(1.0), mid)) < target;
        lo = under ? mid : lo;
        hi = under ? hi : mid;
    }
    return mix(c, float3(1.0), (lo + hi) * 0.5);
}

/**
 * The **family warp** — what makes the nine geometries nine *shapes* rather than nine
 * densities.
 *
 * A palette change cannot answer "every theme is the same guilloche in a different
 * colour"; the figure itself has to change. Scaling a frequency only changes how many
 * lines there are, which is why nine densities still read as one drawing. So each family
 * bends the running coordinate by a *different construction*:
 *
 *  - SWEEP folds once, very slowly — long sheets that barely bend.
 *  - ORBITAL warps by **radius**, so the lines curve about the field centre and spiral.
 *  - ORGANIC sums two incommensurate slow waves — uneven, never repeating.
 *  - EDITORIAL is nearly straight: the calmest family.
 *  - CRYSTALLINE is a **triangle** wave, not a sine, so the sheets meet at corners.
 *  - RAYS warps by radius at a much higher rate, so the sheets bow into radiating arcs.
 *  - ORNAMENTAL adds a second harmonic, so crests cross into loops.
 *  - PLAYFUL runs higher and faster — springy arcs.
 *  - MINIMAL is one very shallow fold, nearly a straight line.
 *
 * **Wrap-safety.** `braid` is a whole number of turns per lap (the caller passes
 * `sign(drift) * phase`, and `phase` advances 2π per lap), so every `sin`/`asin` here
 * swallows the clock's sawtooth wrap. The triangle is `(2/π)·asin(sin x)` rather than
 * `fract`-based precisely so it inherits that same guarantee from `sin` alone — and so
 * the function needs no GLSL builtin the rest of the block does not already use.
 */
float familyWarp(float2 p, float m, float family, float braid) {
    if (family < 0.5) {
        // SWEEP — long sheets, one very slow fold.
        return 0.30 * sin(m * 0.55 + braid);
    } else if (family < 1.5) {
        // ORBITAL — curvature about the field's centre, so the lines spiral.
        return 0.85 * sin(length(p) * 3.2 - braid) + 0.35 * sin(m * 1.05 + braid);
    } else if (family < 2.5) {
        // ORGANIC — two incommensurate slow waves: uneven, alive, never repeating.
        return 0.75 * sin(m * 0.70 + braid) + 0.45 * sin(m * 1.93 - braid * 2.0);
    } else if (family < 3.5) {
        // EDITORIAL — nearly straight. A printed plate, not a drawing.
        return 0.18 * sin(m * 0.42 + braid);
    } else if (family < 4.5) {
        // CRYSTALLINE — faceted. A triangle wave, so the sheets corner instead of curving.
        return 0.60 * 0.6366198 * asin(clamp(sin(m * 0.36 + braid * 0.5), -1.0, 1.0));
    } else if (family < 5.5) {
        // RAYS — the sheets bow outward from the centre, so the figure reads as arcs.
        return 1.10 * sin(length(p) * 2.4 + braid * 0.5);
    } else if (family < 6.5) {
        // ORNAMENTAL — the deepest braid plus a second harmonic: crests cross into loops.
        return 1.00 * sin(m * 0.85 + braid) + 0.62 * sin(m * 2.55 - braid);
    } else if (family < 7.5) {
        // PLAYFUL — springy: higher frequency and a second, faster fold.
        return 0.95 * sin(m * 1.55 + braid) + 0.42 * sin(m * 3.15 + braid * 2.0);
    }
    // MINIMAL — one very long, very shallow fold. Nearly a straight line.
    return 0.10 * sin(m * 0.30 + braid);
}

/**
 * One wave family, returned as a coverage in 0..1.
 *
 * `p` is the point in aspect-corrected field space. The wave runs along `dir`; the
 * family's warp displaces it, which is the braid. The stroke is one **fibre** per integer
 * crossing of `w`.
 *
 * **A shade, not a filament — and the history matters, because this file has now held both
 * positions.** The profile began as a broad bell (`pow(s, 1 + w*40)`, exponent ~2.6), was
 * redesigned into a **needle** (`pow(s, 100 + w*5600)`, K ~ 120-1500) on the argument that a
 * luminous fibre needs a narrow core, and is now a broad bell again. The needle was not wrong
 * about the shape it drew; it was wrong about the shape the design needs. At K = 380 only the
 * ~0.3% of each period around s = 1 exceeds 0.01, so each of the three tiers drew a 1-2 px
 * spike with a near-vertical shoulder - and 35 such spikes across a phone read as a **rake of
 * hard scratches**, not as engraving. Measured on a device screenshot they carried
 * dL = 0.146 against the field, and the field's own gradient map showed them as long straight
 * diagonals. The reference concepts show the opposite: a soft wash, flat to ~0.003 |dL| over
 * a 24 px window, with any structure spanning tens of pixels.
 *
 * So the profile is a **body plus a wider skirt** of the same wave:
 *
 *  - `core` - `pow(s, K)` at `K ~ 3-6`, a lobe covering a quarter to a third of a stroke
 *    period at half maximum;
 *  - `halo` - the same power at **0.42x the core's exponent**, i.e. a *wider* falloff, at
 *    just under two-thirds the weight: a convex skirt that leaves the peak slowly and dies
 *    gently, which is what reads as shading rather than as an edge.
 *
 * The halo's exponent is **derived from the core's**, so the two always scale together and
 * the sum stays shoulderless at any `w`. The earlier "60-75% quiet / 20-30% structure /
 * 5-10% highlight" budget is no longer the target - it is what *produced* the scratches -
 * and the amplitude that replaces it is set by `PLATE_GAIN` and the soft clip in [plateAt].
 *
 * `w` still orders the tiers' falloffs, and the family scales it (see
 * `GuillocheGeometry.character`), so CRYSTALLINE keeps a tighter lobe than MINIMAL - but the
 * difference is now one of density, not of gauge.
 *
 * **The two motion terms.** `walk` is the family's own creep, in stroke periods — a whole
 * number per lap, so `sin` swallows the sawtooth's wrap. `braid` is the warp's phase, in
 * radians, a whole number of turns per lap for the same reason. They do different things
 * and the second is the one that matters: a walk alone slides a fixed figure sideways,
 * while advancing the braid makes the wander itself travel, so the fibres undulate where
 * they stand.
 */
float waveLine(float2 p, float freq, float cross, float crossFreq, float angle, float w,
               float walk, float braid, float family) {
    float2 dir = float2(cos(angle), sin(angle));
    float2 perp = float2(-dir.y, dir.x);
    float v = dot(p, dir);
    float m = dot(p, perp);
    // The family's construction, at the seeded braid depth.
    //
    // Scaled up from the authored braid. With the broadened profile the eye can follow a whole
    // lobe, and a near-straight lobe ~40 px long is a *stripe* however soft its edges are —
    // the warp is the only thing that makes it read as a drift of light instead of a rule.
    // Scaling here rather than raising `cross` in the spec keeps every pack's authored braid
    // (and `everyPackDrawsADistinctFigure`, which compares them) untouched.
    float warp = familyWarp(p, m, family, braid) * cross * 2.4;
    // Per-fibre desynchronisation — the term that turns "a sheet folding in unison"
    // into independent interweaving threads.
    //
    // Every iso-line used to share one `walk`, so the whole family crested and
    // troughed together: a drawn sheet of threads folding and unfolding as one. Here
    // each fibre also carries a *secondary* phase that travels along the thread axis
    // (`v`) at a different whole-lap rate (`walk * 2`). Because its spatial coefficient
    // (0.73) is incommensurate with the stroke's own, successive fibres are handed a
    // progressively rotated phase, so neighbours fall out of step and the family reads
    // as separate curves sliding past one another rather than one rigid surface.
    //
    // Wrap-safety is preserved exactly as the main walk's is: `walk * 2` advances a
    // whole number of periods per lap (the authored drift is integral), so `sin`
    // returns to its start at the sawtooth wrap; the `v` term is constant in time and
    // never wraps. The amplitude (0.85 rad) bends the lobes into travelling curves
    // without dissolving the weave into noise.
    float lane = sin((v * freq * 0.73) * 6.2831853 - walk * 2.0 * 6.2831853);
    float s = clamp(0.5 + 0.5 * sin((v * freq + warp + walk) * 6.2831853 + 0.85 * lane), 0.0, 1.0);
    // ── the profile: a broad shade, not a drawn line ──
    //
    // **This was a needle and that was the defect.** The exponent used to be
    // `100 + w * 5600` — K ≈ 120–1500 — and `pow(s, K)` at K = 380 is above 0.01 only for the
    // ~0.3% of the period around s = 1. A 1–2 px spike with a near-vertical shoulder: a *line*,
    // and at 35 of them across the field, a rake of hard scratches. Measured on a device
    // screenshot the strokes carried ΔL = 0.146 against the field with a p99 gradient of
    // 0.039 — an edge, in the literal gradient sense.
    //
    // The reference concepts show the opposite: their fields are a **soft wash**, flat to
    // ~0.003 |ΔL| over a 24 px window, with any structure spanning tens of pixels rather than
    // two. So the exponent is now small enough that `pow(s, K)` is a wide bell — K ≈ 2.5–8,
    // i.e. a lobe covering roughly half a stroke period instead of a thousandth of one.
    //
    // The two terms are a **body plus a wider skirt**, not a core plus a bloom: at these
    // exponents both are broad, and their sum is a smooth hump with no shoulder anywhere. The
    // skirt is deliberately *wider* than the body (K * 0.42) so the falloff is convex — the
    // profile leaves its peak slowly and dies gently, which is what reads as shading rather
    // than as an edge.
    //
    // **The gauge argument no longer applies, and that is fine.** `w` still arrives
    // frequency-compensated, but a broad lobe has no meaningful gauge — every tier now draws
    // the same soft swell, which is the point. What is preserved is the *spacing* difference
    // between tiers, and spacing is what makes the plate a weave rather than a ruling.
    // `w` is the family's sharpness (0.72 CRYSTALLINE .. 1.45 MINIMAL), so this spans
    // K ~ 3.0-6.1: a lobe covering roughly a quarter to a third of a stroke period at half
    // maximum. The clamp is a guard rail, not a tuning knob — a non-positive exponent would
    // make `pow(s, K)` flat or divergent, and a very large one would silently restore the
    // needle this profile exists to replace.
    float coreK = clamp(4.2 * w, 2.0, 10.0);
    float core = pow(s, coreK);
    float halo = pow(s, coreK * 0.42);
    // Ragged runs: a real wash is uneven along its length, not evenly lit. Whole turns again,
    // for the same wrap-safety. Kept gentle — a strong streak term re-introduces structure.
    float streak = 0.5 + 0.5 * sin(m * crossFreq * 3.0 + braid * 2.0);
    return (core * 0.62 + halo * 0.38) * (0.72 + 0.28 * streak * streak);
}

/**
 * The corner fan: `rays` fine lines radiating from `centre`, fading with distance.
 *
 * `spin` is already in radians; the caller converts whole ray sectors into them, because
 * sectors are what make the rotation wrap-continuous. See `GuillocheFan.spin`.
 */
float fanBurst(float2 p, float2 centre, float rays, float reach, float spin) {

    float2 v = p - centre;
    float r = length(v);
    if (r > reach) return 0.0;
    float a = atan(v.y, v.x) + spin;
    // Soft feathered rays, not cut lines: a smooth bell per sector so the burst reads as a
    // diffuse corona of light rather than a drawn star. Whole `rays` keeps the sector bell
    // continuous across atan's branch cut.
    float sct = 0.5 + 0.5 * sin(a * rays);
    // Broadened from 3.0: at 3 the sectors were narrow spokes with visible edges, so the
    // corona read as a drawn star even before its amplitude was cut. At 1.7 the sectors
    // overlap into a soft diffuse glow, which is what the comment below always claimed.
    float ray = pow(sct, 1.7);
    // A soft glow core at the strike point, brightest at the centre, so the fan blooms
    // instead of radiating hard spokes.
    float core = 1.0 - smoothstep(0.0, reach * 0.5, r);
    // Fade out with distance so the burst has no hard rim.
    float fade = 1.0 - smoothstep(reach * 0.35, reach, r);
    return (ray * 0.7 + core * core * 0.6) * fade;
}

/**
 * A few long, near-straight specular streaks raking across the plate.
 *
 * `ph` is the plate's raw phase. The two multipliers on it are whole numbers so the rake
 * is continuous across the sawtooth's wrap — a fractional rate would slide the light
 * sideways once every lap, and a slow effect's one visible pop is more noticeable than its
 * whole motion.
 */
float streaks(float2 p, float angle, float ph) {
    // Deliberately misaligned with the weave so the streaks read as light *over* it.
    float2 dir = float2(cos(angle + 0.6), sin(angle + 0.6));
    float2 perp = float2(-dir.y, dir.x);
    float m = dot(p, perp);
    // Two very low frequencies: long, wide streaks, not a line family. Counter-travelling,
    // so the rake crosses itself instead of sweeping one way.
    float s = 0.5 + 0.5 * sin(m * 1.7 + ph);
    float s2 = 0.5 + 0.5 * sin(m * 0.9 - ph * 2.0 + 1.3);
    // Squared so the streaks are concentrated into soft bands with calm between them.
    return (s * s) * (0.6 + 0.4 * (s2 * s2));
}

/**
 * One quiet region's weight: 1 outside its influence, 0 inside its core.
 *
 * Defined **before** [quietWeight], which calls it. SkSL has no forward declarations, so
 * definition order in this block is a compile-time constraint and not a reading preference
 * — a callee below its caller is rejected, the `runCatching` swallows it, and the effect
 * simply never appears on a device while every Kotlin test stays green.
 */
float regionWeight(float2 uv, float4 r, float cover, float margin) {
    if (cover <= 0.0) return 1.0;
    // Distance outside the rectangle, max-metric, so the ease is a rounded box.
    float dx = max(max(r.x - uv.x, uv.x - r.z), 0.0);
    float dy = max(max(r.y - uv.y, uv.y - r.w), 0.0);
    float outside = max(dx, dy);
    float ease = (margin <= 0.0) ? 0.0 : clamp(outside / margin, 0.0, 1.0);
    // smoothstep, matching GuillocheModel.guillocheWeightAt exactly: no visible start.
    float kept = ease * ease * (3.0 - 2.0 * ease);
    return 1.0 - cover * (1.0 - kept);
}

/** The quiet mask: 0 inside a region's core, easing to 1 by `margin` outside it. */
float quietWeight(float2 uv, float count, float margin) {
    float w = 1.0;
    if (count < 0.5) return w;
    if (count > 0.5) { w *= regionWeight(uv, quiet0, quietCover0, margin); }
    if (count > 1.5) { w *= regionWeight(uv, quiet1, quietCover1, margin); }
    if (count > 2.5) { w *= regionWeight(uv, quiet2, quietCover2, margin); }
    if (count > 3.5) { w *= regionWeight(uv, quiet3, quietCover3, margin); }
    return w;
}

/**
 * **The plate at a point**, in the field's own normalized space — and the single definition
 * of it that both shaders call.
 *
 * This was [GUILLOCHE_AGSL]'s `main` body, inline. It is a function now because the gel
 * refracts the plate and therefore has to evaluate the *same* engraving the ground plane
 * paints: two copies of this arithmetic would be two plates, and the pane would bend an
 * engraving that is not the one behind it — a difference that only ever shows up on device,
 * at the rim, as detail that fails to line up. Splitting it out is what makes the parity
 * structural rather than a promise.
 *
 * `uv` is 0..1 across the field; `phase` and `breath` are the plate's two slow-clock values,
 * passed rather than read from globals so the caller controls the clock. Returns the line
 * colour and the coverage together, because the two callers composite them differently:
 * the ground plane paints the plate *over* the field, the gel multiplies the coverage into
 * its rim weight and lets the line colour arrive through the refraction.
 *
 * `accent` and `aspect` are parameters for the same reason: the gel declares an `accent` of
 * its own (the pane's material) and knows the *pane's* aspect, which is not the field's. A
 * shared body that read either from a global would silently use the wrong one at one of the
 * two call sites, and the engraving the pane bends would not be the engraving behind it.
 * `plateIntensity` is parameterised for exactly the same trap, and it is the sharpest of the
 * three: *both* shaders declare an `intensity`, and the two mean different things — the plate
 * is drawn at `GUILLOCHE_ALPHA_MAX`, the gel's is its own film strength. Reading the gel's
 * `intensity` here would scale the refracted engraving by the wrong material's opacity.
 *
 * **Three accents, not one.** A plate drawn in a single hue is a monochrome wash however
 * good its geometry is. The theme authors three hues for exactly this (§18's
 * `guillochePrimary`/`Secondary`/`Highlight`), so the wide structural sheets, the mid
 * filigree and the fine highlight each carry their own, and the crossings read as a *field*
 * rather than as one colour at several brightnesses. The blend happens **before** the
 * line-colour rule runs, so the plate still spends exactly one `matchLuma` and the
 * single-accent contrast guarantees in `GuillocheTest` still describe what is drawn.
 *
 * `faceWeight` is deliberately *not* a parameter: it is the plate's own per-face constant,
 * only the plate has a definition of it, and the gel has no candidate to confuse it with.
 *
 * `quiet` is applied by the caller along the same reasoning — one call site returns early on
 * it (a silent region costs no further work), the other folds it into a mask.
 */
float4 plateAt(float2 uv, float phase, float breath, float aspect, float plateIntensity,
               float3 fieldTop, float3 fieldBottom, float2 fieldSource,
               float3 accentA, float3 accentB, float3 accentC) {
    // Aspect-corrected, centred: the weave's angles are then true on any screen.
    float2 p = (uv - 0.5) * float2(aspect, 1.0);

    // ── the clock, in the two units the figure can actually consume ──
    //
    // `phase` and `breath` are 0..2PI *sawtooths* (slowPhaseAt ramps linearly and wraps),
    // so nothing may use them raw. A term consumed by `fract` must advance a whole number
    // of periods per lap; a term consumed by `sin`/`cos` must advance a whole number of
    // turns. `turns` is the phase expressed in laps, 0..1, which is the form the walk
    // needs: `drift * turns` steps by exactly `drift` at the wrap, and `drift` is a whole
    // number, so `sin` sees no discontinuity. Everything below is in one of those two
    // forms, which is what "continuous and periodic" costs.
    float turns = phase * 0.15915494;   // 1 / (2PI)

    // ── the weave ──
    // Each wave's family creeps at its own signed rate, and the braid's pulse rides the
    // same phase in the same direction (`sign(drift)`) so the wander and the walk stay
    // coherent — a family that wandered against its own travel reads as shear, not drift.
    //
    // The amplitude breath is on the *second* clock (79 s) at whole harmonics, so the three
    // layers swell against each other without ever lining up and without popping at that
    // clock's own wrap. The earlier 1.3x / 0.8x multipliers were the pop: at breath = 2PI
    // they land mid-cycle and snap back.
    float l0 = waveLine(p, freq0, cross0, crossFreq0, angle0, weight0,
                        drift0 * turns, sign(drift0) * phase, family)
             * share0 * (1.0 + 0.06 * sin(breath));
    float l1 = waveLine(p, freq1, cross1, crossFreq1, angle1, weight1,
                        drift1 * turns, sign(drift1) * phase, family)
             * share1 * (1.0 - 0.06 * sin(breath * 2.0 + 1.1));
    float l2 = waveLine(p, freq2, cross2, crossFreq2, angle2, weight2,
                        drift2 * turns, sign(drift2) * phase, family)
             * share2 * (1.0 + 0.06 * cos(breath * 3.0 + 2.3));
    // ── the gain, and why the sum is soft-clipped rather than clamped ──
    //
    // Broadening the profile (see `waveLine`) does not change a lobe's *peak*, only its
    // width — so the same three shares that used to draw three hairlines would now draw three
    // wide bands at full strength, i.e. a much louder plate. The gain takes the peak back down
    // to a shade, and the soft clip is the second half of the fix: `clamp(x, 0, 1)` saturates,
    // and a saturated sum is a flat-topped plateau with two hard shoulders — the mechanical
    // origin of "defined lines". `x / (1 + x)` compresses smoothly with no shoulder anywhere
    // and no ceiling to pile up against, so a crossing of two lobes is a slightly deeper
    // shade rather than a clipped bar.
    //
    // **0.30, calibrated against the shipped needle build's own measurement.** On that build
    // the plate's line core measured dL = 0.146 against the field on a device screenshot —
    // a needle core is `cover = 1`, so dL = cover * 0.146 is the mapping. The references'
    // field is a *faint* wash, so the target is ~1/3 of that, dL ≈ 0.045. At 0.62 the broad
    // profile lands near the old brightness (a wider lobe covers more of the period, so the
    // mean rises even at the same peak); at 0.30 the peak comes to dL ≈ 0.045 and the mean
    // coverage falls by ~2/3, which is the difference between a shade and a tint.
    float PLATE_GAIN = 0.30;
    float weaveRaw = (l0 + l1 + l2) * PLATE_GAIN;
    float weave = weaveRaw / (1.0 + weaveRaw);

    // ── the micro-detail ──
    // A fine cross-ruling at several times the structural frequency: the engraver's
    // hatching, which gives the fibres a woven surface at reading distance and dissolves
    // into tone at arm's length. This is the fourth of the brief's five depth layers
    // (atmospheric field / structural / highlight filaments / **micro-detail** / bloom).
    //
    // It rides a **high harmonic of the plate's own clock** (`turns * 7.0`). That is what
    // the slow-clock discipline requires — a whole number of turns per lap is
    // wrap-continuous, so the micro layer cannot pop at the wrap — and it is also what puts
    // the plate's fastest visible motion in the brief's 5–15 s band (53 s / 7 ≈ 7.6 s)
    // without introducing a fourth clock that could beat against the other three.
    //
    // The multiplier must ride `turns` (0..1), **not `phase`** (0..2PI): the outer ×2PI
    // makes one turn per unit, so `turns * 7` is exactly seven turns per lap while
    // `phase * 7` would be 7·2PI ≈ 44 turns — not a whole number, and a visible snap.
    float microPhase = (dot(p, float2(-sin(angle0), cos(angle0))) * freq0 * 6.0 + turns * 7.0) * 6.2831853;
    // Broadened alongside the fibres, and for the same reason: at exponent 30 this was a
    // ~1 px ruling, which at reading distance is a texture of hard scratches laid over the
    // wash. At 5 it is a gentle ripple that dissolves into tone, which is what the layer is
    // documented to be for.
    float micro = pow(clamp(0.5 + 0.5 * sin(microPhase), 0.0, 1.0), 5.0);

    // ── the accents ──
    // `fanSpin` is in whole ray sectors per lap; one sector is 2PI/rays, so the rotation
    // in radians is `spin * turns * 2PI / rays`, i.e. `spin * phase / rays`. Expressed that
    // way the fan's own `fract(a / 2PI * rays)` advances by exactly `fanSpin` at the wrap.
    float fan = fanBurst(p, fanCentre - 0.5, fanRays, fanReach,
                         fanSpin * phase / max(fanRays, 1.0))
              * fanShare;
    float rake = streaks(p, angle0, phase) * 0.12;

    // ── the breath of the whole plate ──
    // One very slow swell across the entire engraving, driven by both clocks at once (53 s
    // and 79 s share no small-integer ratio, so the swell never arrives twice at the same
    // place in the walk). This is the term that makes the plate read as weather passing
    // through the page rather than as a pattern printed on it.
    //
    // Written as a pure dimming — bounded to [1 - shimmerDepth, 1] — so the plate's
    // strongest moment is still exactly the alpha ceiling and every contrast claim proved
    // at that ceiling still holds. A symmetric swell would push past it and would have to
    // be re-proved against the ink floor.
    float shimmer = 1.0 - shimmerDepth * 0.5 * (1.0 - cos(phase + breath));

    // Total coverage, capped so the plate can never become a solid.
    // The accents are deliberately the *quietest* part of the plate now. They were the
    // loudest (`fan * 0.85`), and a fan is the one term that draws genuinely straight spokes
    // from a point — on a device screenshot they crossed the whole screen as hard bright
    // diagonals. A corona should be the faintest thing on the page, so it is scaled to a
    // fraction of the weave it used to dominate.
    float cover = clamp((weave + micro * 0.07 + fan * 0.20 + rake * 0.35) * shimmer, 0.0, 1.0);

    // ── the colour ──
    // The field's own ramp at this point, so the plate is cut out of the theme's gradient
    // rather than laid on top of it.
    // The field's own axis — see `fieldT` in FolioFieldModel.kt. Taken as a *parameter*, not
    // read as a global: the gel interpolates this function and declares its own `fieldSource`
    // for its own film, so a global read would silently use the gel's axis with the plate's
    // colours. See the parity skill's "sharing one implementation" section.
    // Centred and normalised to the page's own projection range, matching `fieldT` in
    // FolioFieldModel.kt. `uv` is normalised and `aspect` is w/h, so the pixel-space direction
    // (dx*w, dy*h) becomes (dx*aspect^2, dy) once the common h^2 factor is dropped.
    float2 fieldDir = float2((1.0 - 2.0 * fieldSource.x) * aspect * aspect,
                             1.0 - 2.0 * fieldSource.y);
    float fieldSpan = abs(fieldDir.x) + abs(fieldDir.y);
    float fieldAxis = clamp(0.5 + dot(uv - 0.5, fieldDir) / max(fieldSpan, 1e-6), 0.0, 1.0);
    float3 field = mix3(fieldTop, fieldBottom, fieldAxis);

    // ── which hue this fibre is ──
    // Blended by *tier weight* before the line rule runs: where the wide structural sheet
    // dominates the fibre wears `accentA`, where the fine filigree dominates it wears
    // `accentC`, and a crossing is a genuine mix of the two. Doing it here rather than
    // compositing three coloured layers is what keeps the plate at one `matchLuma` — and
    // therefore keeps every contrast guarantee the single-accent model proves.
    float tierA = l0 + fan * 0.20 * fanShare;
    float tierB = l1;
    float tierC = l2 + micro * 0.07 + rake * 0.35;
    float3 accent = (accentA * tierA + accentB * tierB + accentC * tierC)
                  / max(tierA + tierB + tierC, 1e-4);

    float3 line;
    if (isDark > 0.5) {
        // A **luminous engraving**: light cut into the dark field, not a shadow groove.
        // Mirrors `GuillocheModel.guillocheLineColor` exactly — the ribbon lifts toward a
        // brightened accent (the pack's `primary` pushed `darkGlow` of the way to white),
        // which is what reads as the reference's bright cyan-white web. The old deepen-
        // toward-black moved almost no luminance on a mid-dark navy and so read as a faded
        // smudge. Body text that sits on the field is kept legible by the quiet mask (it
        // takes `a` to exactly zero where type is registered), not by holding this line
        // dark, so the open field is free to bloom.
        float3 glow = mix3(accent, float3(1.0, 1.0, 1.0), darkGlow);
        line = mix3(field, glow, clamp(cover * darkLift, 0.0, 1.0));
        // Keep `darkDepth` and `minVisibleDelta` referenced so SkSL does not strip them
        // from the active uniform list (their setters would then throw). They no longer
        // shape the dark line; the shared block still declares them for the paper path.
        line += (darkDepth + minVisibleDelta) * 0.0;
    } else {
        // Paper: the line carries the theme's *hue*, matched back to the field's luminance
        // so the plate cannot move the contrast band, then deepened only slightly. This is
        // where "same vibrance on light" comes from — colour, not darkness.
        float3 tinted = mix3(field, accent, cover * lightTint);
        float3 matched = matchLuma3(tinted, luma3(field));
        line = deepen3(matched, cover * lightDepth);
    }

    // rgb is the line colour and a is the coverage, so the caller can either composite the
    // plate over the field (rgba * a) or weight its rim by `a` and take `line` outright.
    return float4(line, clamp(cover * plateIntensity * faceWeight, 0.0, 1.0));
}
"""

/**
 * AGSL source. Premultiplied output (Skia convention).
 *
 * Uniforms are deliberately flat scalars rather than arrays: SkSL does not allow dynamic
 * indexing of a uniform array, and the earlier glass shader learned that a uniform which is
 * *declared* but not *referenced* in `main` is stripped from the active list, so every
 * `setFloatUniform` below names a uniform that `main` genuinely reads.
 *
 * The uniform block and the whole body of the engraving are interpolated from
 * [GUILLOCHE_UNIFORMS] and [GUILLOCHE_FUNCTIONS] rather than written here: the gel refracts
 * this same plate and must compute the identical figure, and one spelling is the only
 * version of that guarantee a future edit cannot quietly break.
 */
private const val GUILLOCHE_AGSL = """
uniform float2 size;
// The plate's three authored hues, in tier order: structural, secondary, highlight.
// Three uniforms rather than one because a single-hue plate is a monochrome wash no
// matter how good the geometry is — see plateAt's doc.
uniform float3 accent;
uniform float3 accentB;
uniform float3 accentC;
uniform float intensity;
uniform float3 fieldTop;
uniform float3 fieldBottom;
// The slow clock: the weave's phase walk, and the amplitude breath.
uniform float phase;
uniform float breath;
""" + GUILLOCHE_UNIFORMS + GUILLOCHE_FUNCTIONS + """
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;

    // The quiet mask first: a silent region costs no further work and, crucially, is
    // *exactly* zero rather than merely small.
    float q = quietWeight(uv, quietCount, quietMargin);
    if (q <= 0.0) return half4(0.0);

    // `intensity` is this shader's own plate strength (GUILLOCHE_ALPHA_MAX). It is passed
    // rather than read inside `plateAt` because the gel declares an `intensity` of its own
    // meaning a different thing — see plateAt's doc.
    float4 plate = plateAt(uv, phase, breath, aspect, intensity, fieldTop, fieldBottom,
                           fieldSource, accent, accentB, accentC);

    // The plate is drawn *over* the field, so it is composited as a line at low alpha
    // rather than as a replacement: rgb is the line colour, a is the coverage.
    // Premultiplied. The quiet mask is what can take this to exactly zero.
    float a = clamp(plate.a * q, 0.0, 1.0);
    // Guard: premultiplied rgb must never exceed the alpha, or Skia clamps it and the line
    // shifts hue on a dense plate.
    float3 rgb = min(plate.rgb, float3(1.0)) * a;
    return half4(half3(rgb), half(a));
}
"""

/**
 * Sets every uniform [GUILLOCHE_UNIFORMS] declares, for whichever shader is passed.
 *
 * **One setter for two shaders, on purpose.** The gel declares the plate's block too, and a
 * second copy of this sequence would be a second place for a wave's `crossFreq` to be wired
 * to `cross`, or for a new rule constant to be set on one shader and not the other. Nothing
 * here reads `main`; it exists so the block above is the only thing that needs to know the
 * names.
 *
 * **It writes the shared block and nothing else, and that boundary is load-bearing.** The
 * three colours (`accent`, `fieldTop`, `fieldBottom`), the aspect, the intensity and the
 * clock are *parameters* of [plateAt], not globals it reads — precisely so each shader can
 * spell them in its own namespace. The gel genuinely owns `fieldTop`/`fieldBottom` for its
 * own film, with different values, and has no `accent` at all; its plate copies are
 * `plateAccent`/`plateFieldTop`/`plateFieldBottom`. A setter that reached past the shared
 * block to write bare colour names would therefore overwrite the pane's own material on one
 * shader and throw `IllegalArgumentException` for an undeclared uniform on the other. Each
 * caller sets its own three, next to the `plateAt` call that consumes them.
 */
internal fun RuntimeShader.applyGuillocheUniforms(
    spec: GuillocheSpec,
    isDark: Boolean,
) {
    // Flatten the spec into uniforms once per (size, theme) identity. SkSL will not index a
    // uniform array dynamically, so the three waves are three fixed uniform groups — three
    // is the register's own ceiling on how many waves a plate may hold.
    //
    // The face's weight multiplier — paper needs more coverage to read at the same apparent
    // strength, because its line is luminance-pinned rather than dark.
    setFloatUniform("faceWeight", guillocheWeightFor(isDark))
    setFloatUniform("isDark", if (isDark) 1f else 0f)
    // §29: the shape family. Numeric, so it belongs to the shared block — the gel bends the
    // same figure, and a family is part of the figure, not of the material.
    setFloatUniform("family", spec.geometry.ordinal.toFloat())
    // The plate's colour rules, as uniforms, so the shader reproduces the model rather than
    // re-deriving it. See guillocheLineColor for what each means.
    setFloatUniform("darkDepth", GUILLOCHE_DARK_DEPTH)
    setFloatUniform("darkLift", GUILLOCHE_DARK_LIFT)
    setFloatUniform("darkGlow", GUILLOCHE_DARK_GLOW)
    setFloatUniform("minVisibleDelta", GUILLOCHE_MIN_VISIBLE_DELTA_F)
    setFloatUniform("lightTint", GUILLOCHE_LIGHT_TINT)
    setFloatUniform("lightDepth", GUILLOCHE_LIGHT_DEPTH)
    // The plate's slowest breath. Shared because the gel refracts the engraving at whatever
    // strength the page is drawing it: a pane that bent an unshimmered plate would hold a
    // ribbon still at its rim while the same ribbon faded behind it.
    setFloatUniform("shimmerDepth", GUILLOCHE_SHIMMER)
    val waves = spec.waves.take(GUILLOCHE_MAX_UNIFORM_WAVES)
    waves.forEachIndexed { i, w ->
        setFloatUniform("freq$i", w.frequency)
        setFloatUniform("cross$i", w.cross)
        setFloatUniform("crossFreq$i", w.crossFreq)
        setFloatUniform("angle$i", w.angle)
        setFloatUniform("weight$i", w.weight)
        setFloatUniform("share$i", w.share)
        setFloatUniform("drift$i", w.drift)
    }
    setFloatUniform("fanCentre", spec.fan.x, spec.fan.y)
    setFloatUniform("fanRays", spec.fan.rays)
    setFloatUniform("fanReach", spec.fan.reach)
    setFloatUniform("fanShare", spec.fan.share)
    setFloatUniform("fanSpin", spec.fan.spin)
    // A quiet region's ease distance, in field fractions.
    setFloatUniform("quietMargin", GUILLOCHE_QUIET_MARGIN)
}

/**
 * The quiet mask, as uniforms — up to [GUILLOCHE_MAX_QUIET_REGIONS] regions, read in the
 * draw phase.
 *
 * A plain list read in the draw phase; four regions is far more than any real page
 * publishes, and the cap keeps the loop bounded on a device that somehow accumulates stale
 * registrations. Shared for the same reason as [applyGuillocheUniforms]: the gel refracts
 * the plate, so a region that silences the engraving has to silence the refraction too, and
 * a second copy of this loop is a second chance to forget one.
 */
internal fun RuntimeShader.applyQuietUniforms(
    quiet: FolioGuillocheQuiet?,
) {
    val regions = quiet?.regions?.value.orEmpty().take(GUILLOCHE_MAX_QUIET_REGIONS)
    setFloatUniform("quietCount", regions.size.toFloat())
    regions.forEachIndexed { i, r ->
        setFloatUniform("quiet$i", r.left, r.top, r.right, r.bottom)
        setFloatUniform("quietCover$i", r.cover)
    }
}

/**
 * The plate's `phase`/`breath`, for whichever shader is passed.
 *
 * Separate from the block above because the gel uses these same two names for a *different*
 * pair of values — its rim travel rides `phase` on the glass clock while the plate rides it
 * on the guilloche clock, and the two must not be conflated or the plate would appear to
 * slow down and the rim to race.
 */
private fun RuntimeShader.applyPlateClock(phase: Float, breath: Float) {
    // Two slow phases: p0 walks the waves (the flowing weave), p1 breathes the amplitudes so
    // ribbons swell and thin. Under reduce-motion both park at their t=0 value, so the plate
    // is the same engraving, still.
    setFloatUniform("phase", phase)
    setFloatUniform("breath", breath)
}

