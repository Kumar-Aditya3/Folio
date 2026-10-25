package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.folio.reader.ui.components.rgbToHsv
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A palette is a colour set; an **atmosphere** is an environment. This derives
 * the second from the first so every theme gains depth, lighting and a shadow
 * character without hand-authoring 30 more colour tables.
 *
 * Everything here is computed from the palette's own roles, so a new palette
 * inherits an atmosphere for free and the §15 contrast tests keep governing the
 * inputs.
 *
 * The model: one light source above and slightly leading (top-start), two or
 * three low-alpha colour pools that give the page a temperature, and a shadow
 * whose colour is the palette's own darkness rather than pure black. Dark
 * palettes get light from *emission* (rims brighten) and light palettes get it
 * from *occlusion* (shadows deepen) — the same reason a photograph of a lamp and
 * a photograph of paper are lit differently.
 */
@Immutable
data class FolioAtmosphere(
    /** True when the field is dark enough that rims read as emitted light. */
    val isDark: Boolean,
    /** Page-level gradient stops along the field's own axis, already alpha-composited. */
    val fieldTop: Color,
    val fieldBottom: Color,
    /**
     * Where the page's light sits, as fractions of the field — the field's gradient runs
     * **from** this point **to** its mirror, so the ramp is the theme's own.
     *
     * **This is what makes the gradient unique per theme.** A ramp needs an *axis*, and the
     * first version of it used the screen's (`verticalGradient`, then a hard-coded
     * corner-to-corner diagonal) — which is the same geometry on all twenty palettes, i.e. one
     * shared wash wearing twenty colours. The app already authors a light per pack
     * ([FOLIO_LIGHTS]); using it means `dawn` (sourceX 0.70) ramps right-to-left while
     * `manuscript` (0.24) ramps steeply left-to-right, and every theme in between gets its own
     * angle for free. A tree with no pack gets [FolioLight.Default].
     */
    val fieldSourceX: Float,
    val fieldSourceY: Float,
    /** Large ambient colour pools behind content. Never more than three. */
    val pools: List<Color>,
    /** Alpha a pool is drawn at. Dark fields tolerate more; paper needs less. */
    val poolAlpha: Float,
    /**
     * How hard the colour of what is being read is folded into the field, 0..1.
     * Zero leaves the room exactly the palette's own.
     *
     * Paired across polarities here rather than in [FolioTokens], which is
     * deliberately polarity-free and cannot express a different amplitude in a
     * dark room and a light one. A dark field can carry far more of a cover's hue
     * before it reads as painted, so it is given more; paper shows tint readily
     * and is given a whisper. Hue and chroma move at this strength — luminance is
     * pinned by [tintFill] — so this knob cannot trade away contrast.
     */
    val fieldTintStrength: Float,
    /** Ambient shadow colour — the palette's darkness, not black. */
    val shadowAmbient: Color,
    /** Spot (directional) shadow colour. */
    val shadowSpot: Color,
    /** Multiplier on every elevation in the app: dark themes need less. */
    val shadowScale: Float,
    /** Top-edge light catch on a raised surface. */
    val rimLight: Color,
    /** Bottom-edge occlusion on a raised surface. */
    val rimShade: Color,
    /** Fill for a surface that sits *above* the page. */
    val raisedFill: Color,
    /**
     * Fill for a surface that rests *on* the page: the middle rung of the ladder.
     *
     * A role rather than a call site decision, because the panel is where the palette
     * was least present. It used to be filled from `colors.surface` — and on paper
     * `surface` is authored near-neutral (chroma 0.012–0.031), so the largest tier of
     * grouping surface in the app was painted with the one colour in the palette that
     * had deliberately been stripped of hue. Measured across the eighteen faces, the
     * panel carried chroma 0.012 against the well's 0.197: the ladder read as "tinted
     * card, grey slab, tinted hole".
     *
     * It leans on **primary**, the one hue already load-bearing in the room (the field's
     * anchor pool, the lamp, every progress read-out), so the panel is the palette's own
     * voice rather than a fourth competing accent — while the card, leaning on
     * **secondary**, stays a different place. Two tiers, two hues, one ladder.
     */
    val panelFill: Color,
    /**
     * The alpha [raisedFill] is drawn at when a surface is meant to be a **pane**
     * rather than a panel: the hero, which is the one large object sitting *in* the
     * room rather than on top of it. At 1f the room behind it is irrelevant, which is
     * also why the refraction along its rim read as a dark band — glass that hides
     * what is behind it is paint.
     *
     * Paired per polarity like the rest of this type: paper gives up its character
     * through a translucent panel far more readily than a dark field does, so the
     * light face stays closer to solid.
     */
    val paneAlpha: Float,
    /** Fill for a surface pressed *into* the page. */
    val sunkenFill: Color,
    /** Translucent fill for bars, nav and sheets that sit over content. */
    val veilFill: Color,
    /**
     * Ground under the OS status icons. Follows the field, not a fixed ink band:
     * a light palette gets a light ground (with dark icons over it), a dark one
     * gets a dark ground. Drawn with the alpha carried here.
     */
    val barScrim: Color,
    /**
     * Glass fill for the app's top bar and nav. Deliberately far more
     * transparent than [veilFill] — a bar over *Compose* content should let that
     * content show through, and only the reader's native surface needs opacity.
     */
    /**
     * The accent the **glass itself** is tinted with — the crown's own family.
     *
     * Separate from [barGlass] because the two answer different questions: `barGlass` is what
     * the pane's *plane* is made of (a step between the card and the page), and this is the hue
     * the pane is *coloured* by. The contract that motivated it is explicit that colour strength
     * should come from the material's tint and not from making the pane more opaque, so the two
     * are kept apart on purpose.
     */
    val chromeAccent: Color,
    val barGlass: Color,
    /**
     * The palette's authored **chrome plane** — a deliberate step between the card
     * plane and the page plane, authored on every face.
     *
     * This is what the always-on furniture is *made of*: the nav capsule and the
     * masthead. Before it was carried here, the one piece of furniture that is on
     * screen at every moment was painted from the same role as the content scrolling
     * behind it, so the chrome read as part of the page rather than as chrome.
     *
     * Carried **opaque**: the consumer applies its own alpha (the capsule's fill
     * preference), so consuming this changes what the chrome is made of and never how
     * solid it is — the §15 glass window does not move.
     */
    val navigationSurface: Color,
    /** Hairline colour for structural rules and dividers. */
    val hairline: Color,
    /**
     * The body ink that sits directly on the field, carried so the environment can
     * bound itself: [FolioFieldModel] lights the room as brightly as this colour can
     * still clear its contrast floor against it, and no brighter.
     */
    val ink: Color,
    /**
     * The room's colour-vibrancy position on a single "Muted ↔ Vivid" scale, carried on
     * the atmosphere so every field consumer (the pools, the blooms, the lit-cover clamp,
     * the glass dimmer) reads one orthogonal lever instead of its own hardcoded constant.
     *
     * [VIBRANCY_NEUTRAL] reproduces the pre-vibrancy output byte-for-byte, so the whole
     * design-system/field/lamp suite — which derives at the default — is unaffected. The
     * app ships at [FOLIO_VIBRANCY_DEFAULT] through [LocalFolioVibrancy]; the setting moves
     * it. The lever is hue- and luminance-preserving by construction (see [enrichChroma]),
     * so it can add colour but never trade away a contrast ratio.
     */
    val vibrancy: Float = VIBRANCY_NEUTRAL,
)

/**
 * Perceptual lightness, cheap. Used only to pick a lighting model, so the
 * sRGB-weighted approximation is sufficient and avoids a LAB conversion on
 * every recomposition.
 *
 * Internal rather than private because [FolioFieldModel] tints the field with the
 * same arithmetic and must not fork a second copy of it (Rule 3).
 */
internal fun luminanceOf(c: Color): Float =
    c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f

/** Pushes a colour toward its own saturated form without changing hue. */
internal fun deepen(c: Color, amount: Float): Color =
    lerp(c, Color.Black, amount)

internal fun lift(c: Color, amount: Float): Color =
    lerp(c, Color.White, amount)

// ── Region tinting (straight-line sRGB channel math, kept deterministic so the
// ThemeSchemeTest-adjacent contrast checks can replicate it exactly) ─────────
// The problem these solve: raised/panel/sunken all derive from surface±lightness,
// so within one theme a hero, a card and a well read as the same hue at three
// brightnesses. Tinting the raised plane toward the palette's primary and the
// sunken plane toward its tertiary (the counter-accent) gives each region its
// own temperature — iconic — while every tint is drawn from the theme's own
// roles, so it still blends. matchLuma pins the tinted result back to the
// untinted base's luminance, so text contrast on each plane is unchanged.
//
// Internal for the same reason as luminanceOf: the field tint is the same
// operation on the same inputs, and the invariant "hue moves, luminance does
// not" has to hold in exactly one place.
internal fun mixG(a: Color, b: Color, t: Float): Color = Color(
    red = (a.red + (b.red - a.red) * t).coerceIn(0f, 1f),
    green = (a.green + (b.green - a.green) * t).coerceIn(0f, 1f),
    blue = (a.blue + (b.blue - a.blue) * t).coerceIn(0f, 1f),
)

internal fun liftG(c: Color, amount: Float): Color = mixG(c, Color.White, amount)

internal fun deepenG(c: Color, amount: Float): Color = mixG(c, Color.Black, amount)

/**
 * Moves [c] to [target] lightness **without moving its hue or its chroma ratio**.
 *
 * The previous form scaled every channel by `k = target / luminanceOf(c)` and then
 * clamped each one with `coerceIn(0f, 1f)`. That clamp is not a rounding detail, it is a
 * hue rotation: once `k > 1` the first channel to reach 1.0 stops while the others keep
 * climbing, so the *ratios between the channels* — which are the entire definition of hue
 * and chroma — collapse. Measured across the eighteen art faces, nine clamped, and the
 * worst (moonstone-L at `k = 1.09`) rotated **42.6°** while keeping 26% of its chroma.
 * That is why a card tinted with the palette's own second hue arrived near-white and
 * grey, and it is the single biggest reason the palette did not reach the surfaces it was
 * painted for.
 *
 * The two directions are not symmetric, so they are not treated as one operation:
 *
 *  - **Darkening is a plain affine scale.** `k < 1` can never leave the gamut, and a
 *    uniform scale multiplies all three channels by the same number, so every ratio — and
 *    therefore the hue, exactly — survives. No search is needed or wanted.
 *  - **Lifting cannot be done by scaling**, which is the whole reason the clamp existed:
 *    the channel that saturates first is the one that decides the hue. So it bisects a
 *    *mix toward white* instead — affine, and in-gamut at every step of the search by
 *    construction.
 *
 * The metric stays [luminanceOf], the cheap gamma-space weighting the rest of this file
 * already agrees on, so `matchLuma(c, luminanceOf(c))` is still `c` and every existing
 * caller keeps the meaning it had. The contrast guards measure `relativeLuminance`, the
 * linearised one; the two are not interchangeable (see [desaturate]).
 */
internal fun matchLuma(c: Color, target: Float): Color {
    val l = luminanceOf(c)
    if (l <= 0.0001f) return c
    if (target <= l) {
        val k = target / l
        return Color(red = c.red * k, green = c.green * k, blue = c.blue * k)
    }
    // 24 passes is well under a 1/255 step on any channel — the finest thing an sRGB
    // colour can express — so the result is the target to the limit of the format.
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (luminanceOf(mixG(c, Color.White, mid)) < target) lo = mid else hi = mid
    }
    return mixG(c, Color.White, (lo + hi) / 2f)
}

// ── Oklch: placing a surface at a *colour*, not at a brightness ───────────────
//
// The ladder states each tier's *lightness*, and lightness alone cannot say what colour
// a tier is. Neither can mixing a near-neutral base toward a chromatic role: the tint
// share caps how much chroma survives, and on paper the base is then lifted toward
// white — the one operation that removes chroma. Measured on the previous construction
// the drawn surfaces carried Oklab chroma 0.0–6.0 while the palette's own accents
// carried 8–19.5, so every tier read as a shade of one colour rather than as a colour;
// and on paper card/panel/page sat within 1.4–3.3 ΔE of each other, under the ~2
// just-noticeable difference. "Different parts" were different *brightnesses*.
//
// So the tiers are now placed at (lightness, hue, chroma):
//
//  * **hue** is the owning role's own Oklab hue — exact by construction, so no tier can
//    rotate;
//  * **chroma** is the most the sRGB gamut holds at that lightness and hue, capped at a
//    target. That is what makes the result honest rather than tuned: a near-white paper
//    surface physically cannot hold chroma (the ceiling at L 0.97 is 1.5), so the paper
//    ladder steps are deeper than they were and the *gamut* does the limiting. It also
//    means each palette gets as much colour as its own hues allow, instead of one share
//    applied to 22 different hue geometries;
//  * **lightness** is solved for by bisection, so the tier's gamma-space luminance is
//    still exactly the ladder's value. That is what keeps every contrast guard on the
//    ground it was proved on. `matchLuma` is the wrong tool here: hitting a luminance by
//    scaling RGB scales chroma with it.
//
// These are the only functions in this file that leave the straight-line sRGB family,
// and they leave it deliberately — Oklch is the space in which "this colour, at this
// lightness" is a statement rather than an outcome. The transform is closed-form (the
// standard Ottosson matrices), so a contrast check can still replicate it exactly, which
// is the property the `G` family exists to provide. Nothing here reaches the shader: the
// AGSL side reads the finished fills as uniforms and never re-derives them, so no GLSL
// has to reproduce this.

private fun srgbToLinear(u: Float): Float =
    if (u <= 0.04045f) u / 12.92f else ((u + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(u: Float): Float {
    val v = u.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

/** Oklab -> linear sRGB, **unclamped**, so gamut membership can actually be tested. */
private fun oklabToLinear(l: Float, a: Float, b: Float): FloatArray {
    val lc = l + 0.3963377774f * a + 0.2158037573f * b
    val mc = l - 0.1055613458f * a - 0.0638541728f * b
    val sc = l - 0.0894841775f * a - 1.2914855480f * b
    val l3 = lc * lc * lc
    val m3 = mc * mc * mc
    val s3 = sc * sc * sc
    return floatArrayOf(
        4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
        -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
        -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
    )
}

/** A colour's Oklab `a`/`b` components. */
private fun oklabAb(c: Color): Pair<Float, Float> {    val r = srgbToLinear(c.red)
    val g = srgbToLinear(c.green)
    val b = srgbToLinear(c.blue)
    val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
    val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
    val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
    val lc = cbrt(l)
    val mc = cbrt(m)
    val sc = cbrt(s)
    return (1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc) to
        (0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc)
}

/**
 * A colour's Oklab hue, in degrees.
 *
 * **Undefined for a neutral**, and it returns 0 rather than anything honest: `atan2(0, 0)` is
 * 0°, so pure white and pure black both report "red". Callers that are bounding a colour
 * against the room must go through [roomHue], which turns that into NaN; callers that merely
 * want the hue of a chromatic colour (a role, an accent) can use this directly.
 */
internal fun oklabHue(c: Color): Float {
    val (oa, ob) = oklabAb(c)
    return ((atan2(ob, oa) * 180f / PI.toFloat()) + 360f) % 360f
}

/** A colour's Oklab chroma. */
internal fun oklabChroma(c: Color): Float {
    val (oa, ob) = oklabAb(c)
    return sqrt(oa * oa + ob * ob)
}

/** Oklch -> sRGB. */
internal fun oklchToColor(l: Float, chroma: Float, hueDeg: Float): Color {
    val rad = hueDeg * (PI / 180.0).toFloat()
    val rgb = oklabToLinear(l, chroma * cos(rad), chroma * sin(rad))
    return Color(linearToSrgb(rgb[0]), linearToSrgb(rgb[1]), linearToSrgb(rgb[2]))
}

/** The most Oklab chroma the sRGB gamut holds at this lightness and hue. */
internal fun maxChromaInGamut(l: Float, hueDeg: Float): Float {
    val rad = hueDeg * (PI / 180.0).toFloat()
    val ca = cos(rad)
    val sa = sin(rad)
    fun holds(c: Float): Boolean {
        val rgb = oklabToLinear(l, c * ca, c * sa)
        return rgb[0] >= -1e-6f && rgb[0] <= 1f + 1e-6f &&
            rgb[1] >= -1e-6f && rgb[1] <= 1f + 1e-6f &&
            rgb[2] >= -1e-6f && rgb[2] <= 1f + 1e-6f
    }
    if (!holds(0f)) return 0f
    var lo = 0f
    var hi = 0.45f
    repeat(30) {
        val mid = (lo + hi) / 2f
        if (holds(mid)) lo = mid else hi = mid
    }
    return lo
}

/**
 * Places a tier at [gammaLuma] **and** gives it a colour: the owning role's own hue,
 * carrying as much chroma as the gamut allows at that lightness, up to [chromaTarget].
 *
 * Iterated rather than one-shot, because chroma shifts the lightness that produces a
 * given luminance and the affordable chroma depends on that lightness. A single pass
 * sizes the chroma for the *wrong* lightness — which at high targets pushes the colour
 * past the gamut edge, where the gamma encode clamps and the hue stops round-tripping.
 */
internal fun tierColour(gammaLuma: Float, hueDeg: Float, chromaTarget: Float): Color {
    val target = gammaLuma.coerceIn(0.001f, 0.999f)
    // A NaN hue means "no hue belongs here" — an achromatic room, or an achromatic role. The
    // tier keeps its luminance and loses the colour entirely, rather than landing on whatever
    // angle the hue arithmetic happened to produce.
    if (hueDeg.isNaN()) {
        return oklchToColor(srgbToLinear(target).pow(1f / 3f), 0f, 0f)
    }
    fun affordable(l: Float) = minOf(chromaTarget, maxChromaInGamut(l, hueDeg) * GAMUT_SAFETY)
    fun solveL(chroma: Float): Float {
        var lo = 0f
        var hi = 1f
        repeat(40) {
            val mid = (lo + hi) / 2f
            if (luminanceOf(oklchToColor(mid, chroma, hueDeg)) < target) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }
    var l = srgbToLinear(target).pow(1f / 3f)
    var chroma = affordable(l)
    repeat(4) {
        l = solveL(chroma)
        chroma = affordable(l)
    }
    // Solve for the lightness of the chroma actually returned, last.
    //
    // The loop above ends on `chroma = affordable(l)`, which can only *reduce* the chroma
    // `l` was solved for — and a colour that is less chromatic than the one its lightness
    // was chosen for no longer carries the target luminance. That is the one promise this
    // function makes ("luminance is pinned and only chroma rises"), and it was off by up to
    // 0.034 on Cyberpunk's warm pool, which is exactly what `vividAddsChromaButHoldsLuminance`
    // measures. One more bisection closes it.
    l = solveL(chroma)
    return oklchToColor(l, chroma, hueDeg)
}

/**
 * The neutral position of the "Muted ↔ Vivid" lever. At this value every vibrancy gain is
 * exactly 1, so [atmosphereFor] returns the pre-vibrancy output byte-for-byte. Kept equal to
 * [com.folio.reader.settings.FOLIO_VIBRANCY_NEUTRAL], the setting-layer copy of the same anchor
 * (the settings module cannot see this one).
 */
internal const val VIBRANCY_NEUTRAL = 0.5f

/** Below this Oklab chroma a colour has no hue worth pushing, so [enrichChroma] leaves it be. */
private const val ENRICH_MIN_CHROMA = 0.012f

/**
 * Multiply a colour's Oklab **chroma** by [gain] while holding its luminance and its hue, capped
 * to the sRGB gamut. This is the one primitive the vibrancy lever is built on, and it has two
 * load-bearing properties:
 *
 * - [gain] == 1f returns the colour *unchanged* (reference equality), so [VIBRANCY_NEUTRAL] — where
 *   every gain is exactly 1 — is byte-identical to the old pipeline and no test moves.
 * - luminance is pinned (via [tierColour]'s bisection on [luminanceOf]) and only chroma rises, so
 *   enrichment can never lower a contrast ratio. "Hue moves, luminance does not" is the same
 *   invariant the lamp and the lit-field tint already run on.
 *
 * A near-neutral colour is returned as-is rather than having a hue invented for it.
 */
internal fun enrichChroma(c: Color, gain: Float): Color {
    if (gain == 1f || c.alpha == 0f) return c
    val chroma = oklabChroma(c)
    if (chroma < ENRICH_MIN_CHROMA) return c
    val enriched = tierColour(luminanceOf(c), oklabHue(c), chroma * gain).copy(alpha = c.alpha)
    // `tierColour` bisects for the largest chroma the gamut holds at this hue and lightness and
    // then spends only `GAMUT_SAFETY` of it. A colour that already sits at the gamut edge
    // therefore comes back with slightly *less* chroma than it went in with — the vibrancy
    // lever would dim a pool instead of enriching it (Manuscript's warm pool: 0.1254 ->
    // 0.1212). The knob may raise chroma or hold it; it may never lower it.
    return if (oklabChroma(enriched) >= chroma) enriched else c
}

/**
 * How far a tier may stray from the room's own hue, in degrees.
 *
 * **This is a measured bound, and it exists because the tiers used to have none.** The ladder
 * placed every tier at its role's hue *exactly* — the card at `secondary`, the panel at
 * `primary`, the well at `tertiary` — which is correct only when those roles happen to sit in
 * the room's own hue family. They often do not: `terracotta`/Espresso's dark `secondary` is a
 * teal (`#2ED6C2`, h 182°) on a face whose page is a warm brown (`backgroundBase` h 26°), so
 * the hero card rendered **olive-green** next to a brown room — 157° away, the single most
 * visible defect in the app.
 *
 * The reference concepts settle what the bound should be. Measured over all 18 faces, the
 * concept's hero card sits **5.1°** from its own page on average and never more than ~15°
 * (the one 36° case is a region where the mockup's *artwork* — not the palette — changes hue
 * locally).
 *
 * Swept against those concepts in Oklab, the mean hero-card hue error over the 18 faces is:
 *
 *    bound   0°    4°    6°    8°   10°   12°   16°   22°   unbounded
 *    error  13.6  13.0  12.8  12.9  13.3  13.6  14.1  15.4       57.1
 *
 * so accuracy is flat from 0° to about 8° and only then degrades — while the mean hue spread
 * between the card and the panel grows from 0.6° (bound 0°, i.e. every tier the same colour)
 * to 13.1° at 12°. The bound is therefore chosen at **12°**: the most spread that costs
 * essentially nothing measurable in fidelity, so the three tiers stay three distinguishable
 * temperatures rather than collapsing to one hue. Depth is still carried primarily by the
 * ladder's lightness steps; this is the second cue, not the first.
 */
private const val MAX_TIER_HUE_DEV = 12f

/**
 * A role's hue, pulled back toward the room until it is no further than
 * [MAX_TIER_HUE_DEV] away — measured as a signed shortest arc, so the clamp is symmetric
 * and the wrap at 0°/360° is handled once.
 *
 * Anchoring on the room rather than on the role is what makes a palette's *own* plane the
 * authority for its surfaces: a theme can still say "my card is cooler than my page" (a
 * deviation of any size up to the bound), but it can no longer say "my card is a different
 * colour from my room", which is what a 157° placement amounts to.
 */
internal fun tierHue(roomHue: Float, roleHue: Float, maxDev: Float = MAX_TIER_HUE_DEV): Float {
    // An achromatic room has no hue, so there is nothing for the role to clash with and the
    // correct bound is *none*. See [roomHue] for why this is not merely defensive.
    // An achromatic *role* is the same story from the other end — `chromaHue` hands back NaN
    // for one, and a NaN here means "this tier has no hue either", which [tierColour] turns
    // into a neutral plane rather than a plane at whatever angle atan2 happened to produce.
    if (roleHue.isNaN()) return Float.NaN
    if (roomHue.isNaN()) return roleHue
    return (roomHue + signedHueDelta(roleHue, roomHue).coerceIn(-maxDev, maxDev) + 360f) % 360f
}

/**
 * The role's Oklab hue, or [Float.NaN] when the role is achromatic.
 *
 * `oklabHue` of a grey is `atan2(0, 0)` = **0° — pink-red** — which is not a colour the palette
 * authored, it is an artefact of asking a neutral for a direction. Every tier that takes its hue
 * from a role must go through this, or a monochrome face (gallery, whose accents are all grey)
 * sprouts planes at hue 0° with real chroma behind them.
 */
internal fun chromaHue(c: Color): Float =
    if (oklabChroma(c) < ROOM_HUE_MIN_CHROMA) Float.NaN else oklabHue(c)

/**
 * The signed shortest arc from [from] to [to], in (−180°, 180°].
 *
 * One place for the wrap, so every bound in the file is symmetric about 0° and correct across
 * the 0°/360° seam. Positive means "counter-clockwise from [from] toward [to]".
 */
internal fun signedHueDelta(to: Float, from: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/**
 * Below this Oklab chroma a background has no hue worth bounding against.
 *
 * The two populations are cleanly separated, which is why a threshold works at all: the
 * eighteen art faces bottom out at **0.0054** (moonstone-light, `#F6F7FB`) while the two
 * System faces sit at exactly **0.0000** (`#FFFFFF` / `#000000`). 0.002 is below every art
 * face and above every System face.
 */
private const val ROOM_HUE_MIN_CHROMA = 0.002f

/**
 * The room's own hue — the authority every derived hue is bounded against.
 *
 * **Returns NaN when the room has no hue, and that is load-bearing rather than defensive.**
 * `LightFolioColors`/`DarkFolioColors` — the two System faces, which every invariant test
 * sweeps because they are entries of `AppPalette` — author `background` as pure `#FFFFFF` and
 * `#000000`. A neutral has no Oklab hue, so `atan2(0, 0)` silently returns **0°: red**. Every
 * bound downstream would then drag its role toward a red that is not in the palette at all;
 * on the System light face that moved `guillochePrimary` (a blue) to h 320 and, because
 * `withHue` re-places at the *field's* luminance where near-white has no chroma in gamut,
 * flattened the plate to a 1/255 spread — which is how `paperPlateCarriesTheThemesChroma`
 * caught it. [tierHue] reads the sentinel and passes the role through untouched, which is
 * also the correct answer: a face with no room colour has nothing to clash with.
 */
internal fun roomHue(colors: FolioColors): Float =
    if (oklabChroma(colors.background) < ROOM_HUE_MIN_CHROMA) Float.NaN
    else oklabHue(colors.background)

/**
 * The first of [roles] that actually has a hue, or the last one as the floor.
 *
 * Every voice in the role allocation below is a *hue* assignment, and a hue assignment is
 * meaningless on a role the palette authored as a neutral: `oklabHue` of a grey is
 * `atan2(0, 0)` = **0°, red**, so an achromatic role does not merely fail to tint — it tints
 * the surface a red that is nowhere in the palette. The two System faces are exactly this case
 * (see [roomHue]), and they are entries of `AppPalette`, so every invariant test sweeps them.
 *
 * Stated as a fallback chain rather than a null so the caller can read as a single assignment:
 * "the chrome speaks tertiary, or primary if this face has no tertiary to speak with".
 */
private fun chromaticRole(roles: List<Color>): Color =
    roles.firstOrNull { oklabChroma(it) >= ROOM_HUE_MIN_CHROMA } ?: roles.last()

/**
 * [c] with its hue rotated to [hueDeg], holding its luminance exactly and its Oklab chroma
 * as far as the gamut at that hue and lightness will hold it.
 *
 * Used where a colour must keep the *weight* it was authored with and change only its
 * family — e.g. a guilloche thread that should stay as strong as the palette intended but
 * stop being a teal hairline across a warm room. The chroma is a *target* rather than a
 * guarantee: [tierColour] clamps it to what is affordable at the destination hue, so a
 * rotation into a hue with less headroom loses a little saturation rather than clipping.
 */
internal fun withHue(c: Color, hueDeg: Float): Color =
    tierColour(luminanceOf(c), hueDeg, oklabChroma(c))

/**
 * The **fill form** of a role that was authored as ink.
 *
 * The four semantic accents (`accentProgress`/`Streak`/`Discovery`/`Annotation`) are AA-fitted:
 * each is the palette's own hue pushed toward the page's ink only as far as the 4.5:1 body-text
 * floor requires. That is correct for a role read as *foreground text*, and it is the wrong
 * value for a role used as a *fill*. Measured on the composite -- pill over capsule glass over
 * page -- the navigation's selected pill came out at **0.020-0.036 Oklab chroma** against a
 * 0.030 (light) / 0.040 (dark) floor, i.e. the tint was there in the token and not on screen.
 *
 * This is the same hue at the same gamma luminance carrying a fill's worth of chroma. It
 * deliberately keeps [folioDestinationAccent]'s per-destination hue, so a tab still says *which
 * room it is* and the capsule cannot disagree with the masthead's segment switch; only the
 * role's *fitting* changes, from ink to fill. The distinction is the one already recorded
 * against `navigationSelected` in `Theme.kt`: that role is "the palette's signature hue at
 * *display* lightness ... while the four accents are AA-fitted for *ink*".
 *
 * `FILL_ACCENT_CHROMA` is measured, not chosen: swept against the rendered floors, the pill
 * clears every face from **0.45 alpha** onward and fails 2 of 18 at 0.40.
 */
const val FILL_ACCENT_CHROMA = 0.16f

fun fillForm(c: Color, chromaTarget: Float = FILL_ACCENT_CHROMA): Color =
    tierColour(luminanceOf(c), oklabHue(c), chromaTarget)

/**
 * `G = (1 - tau) * B + tau * P`, with **chroma and lightness mixed at different rates**.
 *
 * That split is the whole reason this is done in Oklab rather than per channel. Glass should
 * gain the chrome's hue and chroma *without* gaining its lightness: a large lightness shift is
 * what makes a tinted pane read as a painted rectangle instead of as coloured glass. So `tau`
 * (scaled by `chromaBoost`) drives the (a, b) mix, and `lightnessShare` — several times smaller
 * — drives `L`. The contract's own lightness clause is `|L_glass - L_untinted| <= 0.08`, which
 * on a dark face (page L ~0.08 against accent L ~0.60) caps `lightnessShare` at about 0.15.
 *
 * `tau = 0` returns [base] exactly, which is what makes an untinted control render a real
 * control rather than an approximation of one.
 */
internal const val GLASS_TINT_DARK = 0.30f
internal const val GLASS_TINT_LIGHT = 0.18f
internal const val GLASS_CHROMA_BOOST_DARK = 1.45f
internal const val GLASS_CHROMA_BOOST_LIGHT = 1.25f
internal const val GLASS_LIGHTNESS_SHARE_DARK = 0.10f
internal const val GLASS_LIGHTNESS_SHARE_LIGHT = 0.08f

/** A colour's Oklab `L`, alongside [oklabAb]'s `a` and `b`. */
private fun oklabL(c: Color): Float {
    val r = srgbToLinear(c.red)
    val g = srgbToLinear(c.green)
    val b = srgbToLinear(c.blue)
    val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
    val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
    val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
    return 0.2104542553f * cbrt(l) + 0.7936177850f * cbrt(m) - 0.0040720468f * cbrt(s)
}

/**
 * The crown's **chromatic glass material**: the accent's hue, placed at a lightness where the
 * sRGB gamut can actually hold chroma, with the chroma clamped to what it holds there.
 *
 * This is the fix for a structural failure, not a tuning miss. The bar's plane used to sit at
 * the *page's* lightness -- near-white on paper, near-black at night -- and those are exactly
 * the two lightnesses where sRGB holds almost no chroma. Measured: `barGlass` carried chroma
 * *below the page's* on 15 of 20 faces, and the chroma clause 4 asks for was outside the gamut
 * on **all 20**. No tint coefficient, alpha or blend mode reaches a target the gamut cannot
 * represent; only moving the lightness can.
 *
 * So the plane is *placed* rather than mixed from two near-neutral inputs, and the placement is
 * **measured rather than chosen**. Sweeping every lightness at the §15 window's alpha ceiling
 * gives the achievable ceiling `VC = alpha * (C_gamut(L) - C_page)`: it peaks near **L ~ 0.65** and
 * every face reaches its VC target there, while at the page's own lightness it reaches only
 * 0.002-0.011. The dark plane in particular sat at 0.28, which is why dark fell short -- not a
 * gamut wall, a placement error.
 *
 * The light plane sits slightly lower than the VC optimum on purpose. Light *salience* is ~85%
 * a lightness difference, and on paper the page is already near-white, so VS peaks below the VC
 * peak; 0.58 buys the last few percent of separation without moving off the VC target.
 */
private const val BAR_GLASS_L_LIGHT = 0.58f
private const val BAR_GLASS_L_DARK = 0.65f
private const val BAR_GLASS_CHROMA = 0.16f

/** How much of the plane is the chromatic material rather than the page-derived base. */
private const val BAR_GLASS_MATERIAL_SHARE = 0.90f

/**
 * A material's hue and chroma, or nothing at all for an accent that has no hue to lend.
 *
 * Oklab reports an achromatic colour's hue as ~0°, which is *red*. Every material that took
 * its hue from an accent therefore invented a saturated one out of a neutral: Gallery's
 * deliberately hue-free faces came out with **pink cards and a pink bar glass**, and the card
 * was pulled dark enough to sit below its own page. A role below [ROOM_HUE_MIN_CHROMA] has no
 * hue to give, so it gives none and the material is the neutral it should be.
 */
private fun materialHueChroma(accent: Color, l: Float, chromaCeiling: Float): Pair<Float, Float> =
    if (oklabChroma(accent) < ROOM_HUE_MIN_CHROMA) {
        0f to 0f
    } else {
        val hue = oklabHue(accent)
        hue to minOf(chromaCeiling, maxChromaInGamut(l, hue) * GAMUT_SAFETY)
    }

internal fun chromeGlassMaterial(accent: Color, dark: Boolean): Color {
    val l = if (dark) BAR_GLASS_L_DARK else BAR_GLASS_L_LIGHT
    val (hue, chroma) = materialHueChroma(accent, l, BAR_GLASS_CHROMA)
    return oklchToColor(l, chroma, hue)
}

/**
 * Mixes two colours in Oklab, where the (a, b) vector is interpolated directly so the result
 * keeps a coherent hue rather than drifting through the middle of the RGB cube.
 */
/**
 * The **card material**: the same chrome family as the bar, but at the card's *own* lightness.
 *
 * Cards must participate in the chrome system rather than each inventing a tint, so the hue is
 * the chrome accent's, exactly as the bar's is. The lightness, though, stays the card's -- a card
 * that moved to the bar's 0.70 would stop reading as a card. That is also the honest limit: the
 * sRGB gamut at a card's lightness (near-white on paper, near-black at night) holds only
 * ~0.01-0.05 chroma, so on paper this is a *hue* contribution more than a chroma one, and at
 * night a genuinely chromatic one.
 *
 * Distinct from the bar on purpose: the bar is a placed plane at a lightness chosen for chroma,
 * the card is the surface's own plane carrying the chrome hue. The hierarchy is page -> bar ->
 * card, and the card must stay subordinate.
 */
// The ceiling on how much chroma a grouping surface (card, panel, raised hero) carries.
// This is the theme-wide enrichment lever: every surface's colour is `cardGlassMaterial`
// at this chroma, so raising it makes the whole app's grouping surfaces speak the palette's
// voice more strongly rather than reading as tinted greys. It was 0.10 — subordinate to the
// bar and to the roles' own 0.08–0.195 — which left panels (the largest surface, and the one
// settings controls sit on) only barely coloured against the field. Lifted to 0.15: still
// below the strongest roles, still bounded by `maxChromaInGamut * GAMUT_SAFETY` so it can
// never clip on a near-white or near-black plane, but enough that a panel reads as a *colour*.
private const val CARD_GLASS_CHROMA = 0.195f
private const val CARD_MATERIAL_SHARE = 0.80f

/**
 * The same share on a **dark** face — P9.
 *
 * `mixOklab` interpolates `L` at the same share as `(a, b)`, so one number was buying the
 * material's chroma *and* paying for its lightness. On paper that is harmless (the material's
 * lightness is capped *below* the card's, so the mix darkens slightly). On a dark face it is
 * the opposite: the floor sits *above* the card, so every point of share is light added to the
 * largest furniture in the app. Splitting the share by polarity is the smallest change that
 * lets the dark pane keep a real tint while landing near its authored lightness rather than at
 * twice it — `dusk`'s card goes 0.158 → 0.206 Oklab L instead of 0.158 → 0.32, and still
 * carries ~0.09 chroma against the authored roles' 0.08-0.195.
 */
private const val CARD_MATERIAL_SHARE_DARK = 0.42f

// Dark-mode lightness FLOOR for the grouping-surface material. The bug this fixes: a card's own
// lightness at night is near-black (L ~0.10-0.14), and `maxChromaInGamut(l, hue)` collapses to
// almost nothing down there, so the material came back as near-black with ~0.02 chroma — glass
// that read as a black hole instead of a tinted pane. Lifting the *material's* lightness (not the
// card's) to this floor moves it into the band where the gamut actually holds chroma, so the
// mix lands on a visibly tinted dark glass. Paper is untouched (its L is already well above
// this).
//
// **P9: 0.36 → 0.24, and the share it is mixed at is now per polarity.** The floor is a *chroma*
// device that was being paid for in *lightness*, and at 0.36 against an 0.80 share it was the
// second-largest lift in the dark pipeline: `dusk`'s card is authored at Oklab L 0.158 and
// `mixOklab` delivered 0.158 + (0.36 - 0.158) × 0.80 = **0.32** — a surface rendered at twice its
// authored lightness, on every panel, card, hero and row in the app (`FolioSurfaces` paints rows
// from `panelFill`, and `Theme.kt` maps Material's `surfaceContainerLow` to it). That is the
// "row surfaces sit a few percent lighter still" half of the report, and against a page that is
// now genuinely near-black it would have read as lighter still.
//
// 0.24 is where the gamut still holds ~0.13-0.16 Oklab chroma — comfortably inside the roles'
// own 0.08-0.195 band — so the pane keeps being a *colour* and stops being a mid-tone. The
// reasoning the floor was introduced under is intact; only the price is.
private const val CARD_GLASS_L_DARK_FLOOR = 0.17f

// Light-mode lightness CEILING for the grouping-surface material — the symmetric twin of
// CARD_GLASS_L_DARK_FLOOR, and the direct fix for "all ten light themes look like the same white
// card". The light faces author `surface`/`surfaceElevated` at L ~0.96-1.0; up there
// `maxChromaInGamut(l, hue)` collapses to ~0 (near-white cannot hold chroma), so the material
// came back near-white regardless of the accent hue and every light pack's panes/cards rendered
// as the same paper. Capping the *material's* lightness (not the card's) at this ceiling moves it
// into the band where the gamut holds the full CARD_GLASS_CHROMA, so the 0.80 mix finally carries
// the pack's primaryAccent hue and the ten light themes read as different temperatures. 0.90
// rather than lower: at 0.90 the 0.80 mix keeps the pane clearly a light surface (lands ~0.93 L)
// while admitting real chroma — low enough to colour, high enough to still read as paper.
// Dropped 0.90 -> 0.80: at 0.90 the sRGB gamut still only holds ~0.06 chroma, so light panes came
// back as faintly-tinted paper and the ten light themes still read as the same white. At 0.80 the
// gamut holds the full CARD_GLASS_CHROMA, so panel (primary hue) and card (secondary hue) land as
// genuinely different colours and each light theme reads as its own temperature. 0.80 keeps the
// pane clearly a light surface after the 0.80 material mix (lands ~0.86 L) while finally admitting
// real chroma — this is the direct fix for "everything is a variant of the same colour".
private const val CARD_GLASS_L_LIGHT_CEIL = 0.80f

/**
 * §20 — how far each piece of **furniture** is lifted off its authored lightness before it is
 * placed, on a dark face only.
 *
 * The report these answer: "in dark mode cards and the bottom nav are as dark as the
 * background, so it looks dull." That was literally true, and it was the sum of two correct
 * decisions pulling the same way. P8/P9 brought the dark room down hard — pools 0.16 → 0.07,
 * field tint 0.045 → 0.035, the card-glass floor 0.36 → 0.24 at a 0.58 share — because the
 * complaint then was the *opposite* one ("dark not dark enough", "row surfaces sit a few
 * percent lighter still"). Every one of those dials moves the furniture and the room together,
 * so the room arrived at its authored swatch with the furniture sitting flat against it.
 *
 * The fix is to separate the two: the **room** keeps every bit of the darkness P8/P9 bought,
 * and only the planes that are *objects in* that room step off it. A lift here is gamma-space
 * ([liftG]), applied to the plane's own authored colour *before* [tierColour] places it, so the
 * hue and the chroma the sheet authored survive and only the step off the page changes.
 *
 * Three values rather than one, because the three planes answer to different floors:
 *
 *  - the **card** feeds `raisedFill`, and `raisedFill` at [FolioAtmosphere.paneAlpha] is the
 *    hero pane that `FolioLampTest.theHeroPaneKeepsItsOwnTextLegible` holds to 7:1 over the
 *    worst lit point. Lifting the pane *reduces* that ratio, so the card gets the smallest
 *    step — enough to read as a separate object, not enough to spend the veto's margin;
 *  - the **panel** feeds `panelFill`, the largest grouping surface in the app (and Material's
 *    `surfaceContainerLow`), so it carries the bulk of the "cards look dull" report and takes
 *    the middle step;
 *  - the **nav plane** is the one piece of furniture on screen on every route and carries no
 *    body copy — only icons and labels at display weight — so it takes the largest step. It is
 *    also the surface the report named outright.
 */
private const val DARK_PLANE_LIFT_CARD = 0.02f
private const val DARK_PLANE_LIFT_PANEL = 0.045f
private const val DARK_PLANE_LIFT_NAV = 0.11f

/** Lifts a plane off its authored lightness on a dark face, preserving its alpha. */
private fun liftPlane(c: Color, dark: Boolean, amount: Float): Color =
    if (!dark) c else liftG(c, amount).copy(alpha = c.alpha)

internal fun cardGlassMaterial(accent: Color, base: Color, dark: Boolean): Color {
    val l = if (dark) maxOf(oklabL(base), CARD_GLASS_L_DARK_FLOOR)
    else minOf(oklabL(base), CARD_GLASS_L_LIGHT_CEIL)
    val (hue, chroma) = materialHueChroma(accent, l, CARD_GLASS_CHROMA)
    return oklchToColor(l, chroma, hue)
}

internal fun mixOklab(a: Color, b: Color, share: Float): Color {
    val (aa, ab) = oklabAb(a)
    val (ba, bb) = oklabAb(b)
    val x = aa + (ba - aa) * share
    val y = ab + (bb - ab) * share
    val l = oklabL(a) + (oklabL(b) - oklabL(a)) * share
    return oklchToColor(l, sqrt(x * x + y * y),
        (atan2(y, x) * 180f / PI.toFloat() + 360f) % 360f)
}

internal fun tintGlass(
    base: Color,
    accent: Color,
    tau: Float,
    chromaBoost: Float,
    lightnessShare: Float,
): Color {
    if (tau <= 0f) return base
    val (ab, bb) = oklabAb(base)
    val (aa, ba) = oklabAb(accent)
    val a = (1f - tau) * ab + tau * chromaBoost * aa
    val b = (1f - tau) * bb + tau * chromaBoost * ba
    val l = (1f - lightnessShare) * oklabL(base) + lightnessShare * oklabL(accent)
    val chroma = kotlin.math.sqrt(a * a + b * b)
    val hue = (atan2(b, a) * 180f / PI.toFloat() + 360f) % 360f
    return oklchToColor(l, chroma, hue)
}

/** Pulls [base] toward [toward] in hue, then restores base luminance. */
internal fun tintFill(base: Color, toward: Color, amount: Float): Color =
    matchLuma(mixG(base, toward, amount), luminanceOf(base))

/**
 * Mixes [base] toward a grey of its own lightness, so a region loses *chroma*
 * without gaining or losing brightness. Used to neutralise the base field so an
 * injected cover hue reads as a light in the room rather than as more brown.
 */
internal fun desaturate(c: Color, amount: Float): Color {
    val l = luminanceOf(c)
    return mixG(c, Color(l, l, l), amount)
}

/**
 * Rotates a colour's hue by [degrees] (HSV, saturation and value untouched).
 *
 * The engine behind the atmosphere's complementary spread: a single theme used to read as
 * one colour because its field pools were three near-neighbours on the wheel (or two of
 * them grey). Rotating the anchor by ±150° gives a *split-complementary* trio — colours
 * that are demonstrably different yet related, the definition of a coordinated scheme —
 * so every room carries a mixture of hues rather than one hue at three brightnesses.
 */
internal fun rotateHue(c: Color, degrees: Float): Color {
    val (h, s, v) = rgbToHsv(c.toArgbInt())
    return Color.hsv(((h + degrees) % 360f + 360f) % 360f, s, v)
}

/**
 * Pulls a set of pool colours toward their shared circular-mean hue by [pull] (0 = authored
 * spread untouched, 1 = all collapsed onto one hue), preserving each pool's own saturation
 * and value. The authored pools are a deliberately *wide* split-complementary trio; read as
 * three bold spotlights that single spread was what made the field feel incoherent — three
 * unrelated hues rather than one scheme. Rotating each pool a fraction of the way toward the
 * trio's mean keeps every pool distinct (and keeps `warmAccent`'s warm note) while making them
 * demonstrably belong to the *same* family, which is what reads as a coordinated palette.
 *
 * The pull is capped per pool so the widest pair never closes below [MIN_POOL_SPREAD],
 * keeping `ambientPoolsAreNotOneHue` satisfied by construction.
 */
internal fun harmonizePoolHues(pools: List<Color>, pull: Float): List<Color> {
    if (pools.size < 2 || pull <= 0f) return pools
    val hsv = pools.map { rgbToHsv(it.toArgbInt()) }
    // Circular mean of the hues (unit-vector sum), so the average is well-defined on the wheel.
    var sx = 0f
    var sy = 0f
    for ((h, _, _) in hsv) {
        val r = h / 180f * kotlin.math.PI.toFloat()
        sx += kotlin.math.cos(r)
        sy += kotlin.math.sin(r)
    }
    if (sx == 0f && sy == 0f) return pools
    val meanDeg = (kotlin.math.atan2(sy, sx) * 180f / kotlin.math.PI.toFloat() + 360f) % 360f

    // Widest authored pair gap. Rotating each hue a fraction `pull` toward the mean scales
    // every gap by (1 - pull), so cap the pull to keep the widest pair at or above the floor;
    // a palette already tighter than the floor is left untouched.
    var widest = 0f
    for (a in hsv.indices) for (b in a + 1 until hsv.size) {
        val raw = kotlin.math.abs(hsv[a].first - hsv[b].first) % 360f
        widest = maxOf(widest, minOf(raw, 360f - raw))
    }
    if (widest <= MIN_POOL_SPREAD) return pools
    val cappedPull = minOf(pull.coerceIn(0f, 1f), 1f - MIN_POOL_SPREAD / widest)
    if (cappedPull <= 0f) return pools

    return pools.mapIndexed { i, c ->
        val (h, s, v) = hsv[i]
        // Shortest signed arc from this hue to the mean, scaled by the capped pull.
        var delta = ((meanDeg - h + 540f) % 360f) - 180f
        delta *= cappedPull
        Color.hsv(((h + delta) % 360f + 360f) % 360f, s, v)
    }
}

/** Floor on the widest pool-pair hue gap after harmonisation (see [harmonizePoolHues]). */
private const val MIN_POOL_SPREAD = 8f

/**
 * How far the field pools are pulled toward their common hue for coherence (0..1).
 *
 * **0.42 -> 0.18.** The pools are the only mechanism in the whole pipeline that puts *several*
 * of the palette's hues into the air at the same time, so this constant is the one place the
 * atmosphere spends hue variety on purpose — and 0.42 was spending too much of it. Rotating
 * each of three lamps 42% of the way to their circular mean scales every pairwise gap by
 * 0.58, so `dawn`/Dusk's authored violet / salmon / pink trio arrived with well under half its
 * spread and the aurora read as one colour moving rather than three lights crossing. That is
 * the background's share of the "different shades of the same color" report, and unlike the
 * planes it cannot be fixed by reassigning a role: there is no other role to give it.
 *
 * 0.18 keeps what the pull is actually for. The problem it was introduced to solve is a
 * *split-complementary* trio read as three unrelated hues — and that failure comes from the
 * widest pairs, which a small pull still narrows (a 150° gap comes in to 123°), while the
 * near-neighbour pairs the harmonisation was collapsing keep the separation that makes the
 * field read as lit from several places. The cap in [harmonizePoolHues] is unchanged, so
 * `ambientPoolsAreNotOneHue`'s floor is approached from further away than before, not closer.
 */
private const val POOL_HARMONY_PULL = 0.18f

/** Scales a colour's saturation by [factor] (HSV), so a dull role can be made to sing. */
internal fun saturate(c: Color, factor: Float): Color {
    val (h, s, v) = rgbToHsv(c.toArgbInt())
    return Color.hsv(h, (s * factor).coerceIn(0f, 1f), v)
}

/**
 * How much chroma the base field gives up toward its own neutral, per polarity.
 *
 * Deliberately small now. The old values (0.35 dark / 0.10 light) were set when
 * every palette's background was the same tinted brown and the cover's hue had to
 * be given somewhere to land; the cost was that **all 36 rooms converged on the
 * same grey**, because stripping a third of the chroma out of the one mass that
 * fills the screen is stripping the theme out of it. Palettes now author a page
 * and a card in two different hue families, so the room has its own identity to
 * keep, and the cover's lamp has the pools and the lamp itself to arrive through.
 */
private const val FIELD_NEUTRALITY_DARK = 0.0f
private const val FIELD_NEUTRALITY_LIGHT = 0.0f

/**
 * The ladder, as explicit steps off the room's own lightness.
 *
 * These are *distances*, not levels: the room moves with the palette and with the book
 * being read, and the material order has to survive both. A step is what makes that
 * possible — it is invariant under the room's own movement in a way a target level is not.
 *
 * Dark faces are lit from above (a raised object catches light, a recess falls away from
 * it); paper is the opposite, because on paper the room is already the brightest thing a
 * surface may be, so every tier is a shade *below* it. Both are measured: on all 22
 * palettes these steps order `card > panel > well`, keep the raised material lighter than
 * the sunken one once composited (what `materialsAreVisuallyDistinguishable` asserts), and
 * leave the ink 11:1 or better on both the card and the panel.
 *
 * The paper steps are much deeper than they used to be (0.020 / 0.034 / 0.075), and that is
 * forced rather than chosen. A paper room sits at gamma luminance ~0.99, where the sRGB
 * gamut ceiling for a warm hue is **1.5** — a near-white surface physically cannot hold
 * chroma, so a tier has to come *down* before it can be a colour at all. Swept: deeper than
 * this buys about one chroma unit and starts failing `everyPaletteShimmersVisibly`.
 *
 * **The dark steps are deeper than they were (0.115 / 0.060) and that is the radiant-dark
 * fix, not a taste change.** Colour capacity is a curve in lightness, not a constant: the
 * sRGB gamut holds Oklab chroma ~9.8 at L 0.15, ~16.8 at L 0.25, ~29.4 at L 0.55, and peaks
 * near 31 at L 0.70. A dark surface parked at L 0.17–0.29 sits at the *bottom* of that curve,
 * which is why dark tiers could never be as coloured as they were asked to be — the target
 * was not binding, the gamut was. Lifting the dark card/panel up the ladder moves them to
 * L 0.39–0.55, where the same target buys roughly double the chroma. The steps grew by
 * exactly the amount that lift costs, so `card > panel > well` still holds by construction.
 * The one binding constraint is `blossom`'s ink at 5.01:1 against the 4.5 floor.
 */
private const val CARD_STEP_DARK = 0.170f
private const val PANEL_STEP_DARK = 0.100f
private const val WELL_STEP_DARK = 0.012f
private const val CARD_STEP_LIGHT = 0.100f
private const val PANEL_STEP_LIGHT = 0.140f
private const val WELL_STEP_LIGHT = 0.180f

/**
 * Chroma targets, in Oklab chroma (a vivid sRGB colour reaches ~0.25–0.40; a neutral is 0).
 *
 * Targets, not values: [tierColour] takes `min(target, gamut ceiling * GAMUT_SAFETY)`, so a
 * palette whose hue has no room at that lightness is limited by physics rather than landing
 * somewhere unintended — which is why the result varies across the 22 palettes and is still
 * correct on all of them.
 *
 * **Lowered (0.19 / 0.16 / 0.10 → 0.04 / 0.035 / 0.03): they were measured against the
 * reference concepts and came out ~2.4× too high.** The reasoning they were raised under — "a
 * tier should be as coloured as the palette's own accents (0.08–0.195)" — is wrong for a
 * *surface*. An accent is a small mark that has to shout; a tier is a large area the eye sits
 * inside. The concepts draw that distinction explicitly: measured over all 18 faces, the
 * concept's hero card carries Oklab chroma **2.4 (light) / 5.2 (dark)** where the app's carried
 * 6.8 / 11.4. Even at the 75th percentile of the concept's own within-card distribution — which
 * discounts the glass highlight washing the sample toward white — the targets are only 3.5 /
 * 5.4, and these values land the drawn card at 3.1 / 7.5.
 *
 * The symptom was worst where the gamut is widest, because a fixed chroma target is not a fixed
 * *appearance*: at high lightness the cyan/green region of sRGB affords far more chroma than the
 * red/violet region, so the same 0.19 gave near-neutral cards on arctic, nebula and moonstone
 * and full-saturation slabs on mintcoral (`#92FCA8`), forest (`#94FC91`) and ocean (`#4AFBF8`) —
 * the three loudest mismatches in `concept-checklist.md`. At 0.04 those land on the concepts'
 * pale tints (`#DDECE0` / `#DEEBDC` / `#D6EDEB`).
 *
 * Depth is still carried by the lightness ladder ([CARD_STEP_DARK] and friends); this only
 * removes a saturation the references never had.
 */
private const val CARD_CHROMA = 0.04f
private const val PANEL_CHROMA = 0.035f
private const val WELL_CHROMA = 0.03f

/**
 * The dark field's own colour: a chroma target and a lift, in Oklab chroma and gamma luminance.
 *
 * The field is the largest area on screen and it used to be grey. On a dark face it was
 * `saturate(mix(background, surface, 0.25), 1.35)`, and saturating a near-neutral returns a
 * near-neutral — measured, that lands at Oklab chroma **3.3**. So on a dark face the field is
 * *placed* instead: the palette's own `primary` hue, [FIELD_CHROMA_DARK] of chroma, and the
 * lightness of the mix it replaces **plus** [FIELD_LIFT_DARK].
 *
 * Holding the lightness is what makes this safe — every luminance guard, and the whole ladder
 * that hangs off `room`, would move if the field's brightness were free. The lift is the one
 * deliberate move, and it is small: 0.05 takes the field from chroma 3.3 to ~7 and its Oklab L
 * from 0.179 to ~0.27, which is where the *field itself* stops reading as a shadow. The bottom
 * stop is lifted a little less (×0.7) and coloured a little more (×1.15), because the gradient
 * should be a hue-and-value gradient, not two samples of one plane.
 *
 * Measured on the moderate sweep: field chroma 3.7–18.2, card 6.7–19.0, panel 5.4–16.0, with
 * the tightest ink contrast 5.01:1 (blossom) against the 4.5 floor.
 */
private const val FIELD_CHROMA_DARK = 0.20f
private const val FIELD_LIFT_DARK = 0.050f

/**
 * How far each field stop may lean from the room's own hue, in degrees.
 *
 * **The page is a gradient, and a gradient has two axes.** A ramp whose stops share one hue is
 * a *fixed colour* with a dimmer copy under it — the vertical form of the failure the room
 * anchor exists to fix. Measured against the references, the concept page's top and bottom
 * differ by **5.6° of hue on average** (up to 21°) as well as by lightness, so the stops lean
 * toward two *different* authored planes: the lit top toward `surface` (the card plane, whose
 * hue the palette rotated away from the page on purpose) and the floor toward
 * `surfaceVariant`.
 *
 * Bounded well below [MAX_TIER_HUE_DEV] because the field is the largest area on screen and
 * two stops at ±6° already span 12° — more than the concepts' mean — while a wider bound
 * would let a palette whose `surface` and `surfaceVariant` sit on opposite sides of the room
 * tear the page into two rooms. An achromatic room passes both hues through (see [roomHue]).
 */
private const val FIELD_HUE_DEV = 6f

/**
 * How much of the gamut ceiling a tier may claim.
 *
 * Not 1.0: sitting exactly on the boundary means the gamma encode clamps, and a clamped
 * channel does not round-trip to the hue it was placed at.
 */
private const val GAMUT_SAFETY = 0.90f

/**
 * How many times the room's own lightness a dark face's pools are worth.
 *
 * The pools are the one part of the atmosphere that is *light* rather than surface, so
 * they are stated as a multiple of the room rather than as a level — the same reasoning as
 * the ladder, and the reason they survive a room that moves with the cover. 1.6 keeps them
 * inside the "temperature in the air" band on every dark face; see the pools in
 * [atmosphereFor] for what they were before and why that was wrong.
 */
private const val POOL_LEVEL_DARK = 1.6f

/**
 * The chrome's chroma targets — the navigation plane, the reader veil, the status scrim and
 * the raised rim — in Oklab chroma.
 *
 * The chrome was built by **mixing the authored planes**: the nav plane is `mixA(card, page2)`,
 * `veilFill` a `deepen` of `surface`, `barScrim` a `deepen` of `background`, `rimLight` a
 * `lift` of `surface`. And the authored planes are neutral on most of the 22 palettes —
 * `background` on **21**, `surface` and `surfaceVariant` on **14**. So the app's most
 * persistent furniture was **grey on every theme**: the navigation capsule that sits on every
 * screen, the reader's menu veil, the status band, the rim of every raised card. No test
 * covered any of them, which is exactly why they stayed grey while the tiers were fixed.
 *
 * Each is now *placed* instead — the lightness of the mix it replaces, held exactly, given a
 * hue from a role not already spoken for (`tertiary` for the chrome plane, since the card
 * speaks `secondary` and the panel `primary`) and as much of these targets as the gamut holds
 * there. Holding the lightness is what makes it safe: `barScrim` is the one chrome colour with
 * a luminance guard (`statusScrimFollowsThePaletteNotAFixedInk`), and measured over all 22
 * palettes the placement moves it by under 0.0001.
 *
 * Measured on dark faces: the veil reaches chroma **3.2–10.0**, the rim **8.7–14.0**, the scrim
 * **1.6–11.1**. On **paper** they gain almost nothing (veil 0.0–1.1, rim 0.0–0.1) and that is
 * physics, not a failure — at gamma luminance ~0.99 the sRGB ceiling is 1–4, so a near-white
 * veil has no chroma to give. Paper's chrome is grey because white cannot be a colour; the only
 * way to change that is to darken it, which is a different design.
 *
 * **[RIM_CHROMA] is the one that is capped, and by a guard about something else entirely.**
 * `everyPaletteShimmersVisibly` computes `contrast(rimLight over the well, the well)` and holds
 * it in `[1.06, 2.2]`. Placing the rim holds its *gamma* luminance while its *relative*
 * luminance moves — and contrast is built from the relative one — so on `dusk` the baseline is
 * already only 1.078 and the rim's chroma can reach about **7** before the floor is breached
 * (swept: target 0 -> 1.078, 4 -> 1.067, 6 -> 1.061, 8 -> 1.053 FAIL). Hence 0.05 rather than
 * the 0.14 the other three use: it buys ~2x the old rim's chroma and still leaves the guard
 * 0.004 of margin. Freeing it properly would need a relative-luminance-preserving placement,
 * which [tierColour] deliberately is not.
 */
private const val NAV_CHROMA = 0.11f
private const val VEIL_CHROMA = 0.10f
// The dark veil sits at a much lower luminance than the light one, and chroma that reads as a
// clear tint at paper lightness disappears into black down here. The sRGB gamut actually *holds*
// more chroma at these low-mid lightnesses (see the gamut note above), so the dark glass is given
// a richer tint budget — this is what keeps dark-mode glass carrying the palette colour instead
// of collapsing to a flat black pane.
private const val VEIL_CHROMA_DARK = 0.22f
private const val SCRIM_CHROMA = 0.14f
private const val RIM_CHROMA = 0.05f
// Dark faces emit a coloured rim rather than catch a white sheen, and the emission is the one
// highlight that cannot cost legibility (it is laid at the surface boundary, not on the body the
// ink sits on). The dark rim therefore carries far more chroma than paper's grey edge so the card
// reads as a dark body ringed in the theme's own light. tierColour still clamps to the in-gamut
// ceiling, so a theme whose hue cannot hold this much simply takes as much as it can.
private const val RIM_CHROMA_DARK = 0.16f

/**
 * How far the **paper** chrome comes down off white, so that it can be a colour at all.
 *
 * On paper the chrome is built from `surface`, which is a near-white — `lift(surface, 0.35)`
 * lands at gamma luminance **0.986**, i.e. Oklab L ~0.996. The sRGB gamut ceiling *at these
 * hues* at that lightness is **0.56**, so the veil is a neutral no matter what target it is
 * given: [tierColour] correctly returns white, and the paper chrome measured 0.6 chroma for the
 * veil and 0.3 for the scrim. There is no arithmetic that colours a white; the lightness has to
 * move.
 *
 * So the paper chrome is brought down by this step first, and *then* placed. Swept:
 *
 * | drop | veil gamma | nav C | veil C | scrim C | min veil C | ink min |
 * |---|---|---|---|---|---|---|
 * | 0.00 | 0.986 | 3.5 | 0.6 | 0.3 | 0.0 | 12.6 |
 * | 0.06 | 0.927 | 7.2 | 3.8 | 3.5 | 2.0 | 11.0 |
 * | **0.13** | **0.858** | **8.7** | **6.5** | **7.3** | **4.5** | **9.4** |
 * | 0.20 | 0.789 | 9.9 | 8.4 | 9.2 | 6.9 | 7.9 |
 *
 * 0.13 is the point where the veil clears the just-noticeable difference with room to spare on
 * every palette (min 4.5 against a JND of ~2) while the reader-menu ink still sits at 9.4:1 and
 * the scrim's relative luminance stays at 0.709 — well clear of the 0.45 floor
 * `statusScrimFollowsThePaletteNotAFixedInk` enforces. Deeper buys colour the paper look cannot
 * pay for: at 0.25 the veil is a mid grey and the theme stops reading as paper.
 *
 * Stated in the `G` family, like the paper field — the light stops are straight-line sRGB so a
 * contrast check can replicate them.
 */
private const val PAPER_CHROME_DROP = 0.13f

/**
 * How much of the page's **second** hue lands at the foot of the field.
 *
 * The page's two stops used to lean on the same accent at two shares (0.03 crown, 0.08 foot),
 * so the largest surface in the app was one hue at two lightnesses. These replace the foot's
 * share with the same magnitude of a *different* role, which is the point: the spend is
 * unchanged, so the page is no lighter and no more painted than the values that were measured
 * against the rendered concepts — it simply arrives somewhere else on the wheel.
 *
 * The dark face takes slightly more than the 0.08 it replaced because a hue trace has to
 * survive a near-black bloom *and* the aurora poured over it before it reads as a second
 * colour rather than as noise; 0.10 is the smallest share where the foot is distinguishable
 * from the crown by hue and not only by value. Paper gets less (0.05) because its accents are
 * authored as AA-fitted *dark* colours — on a cream page 0.05 of one is already a clear cast,
 * and more would read as a stain rather than as the page's own temperature drifting. Paper's
 * foot also already falls 7% toward black, so this share is additive to a stop that is
 * deliberately the darkest thing on a light page.
 *
 * P8: dark 0.10 → 0.08, against the argument above. The counter-argument wins now because the
 * *bloom under this stop came down* at the same time (SilkField's dark `bloomAlpha` 0.52 →
 * 0.38 and `poolAlpha` 0.16 → 0.12), so the hue trace has a darker bed to survive rather than a
 * brighter one, and the foot no longer needs the extra share to stay a second hue. If a dark
 * face's foot does read as one hue after this, the number to raise is this one, not the bloom —
 * the bloom is the thing that was making the page read as "not dark enough".
 * P9: dark 0.08 → 0.055. Same argument one step further, now that the *floor under this
 * stop* is built from the two authored page planes instead of climbing toward
 * `surfaceElevated`. The trace survives on a darker bed, so it needs less of itself; and the
 * foot accent is a luminous display colour on every dark face, so each point of this share is
 * light added to the largest area on screen.
 */
private const val FIELD_FOOT_ACCENT_DARK = 0.055f
private const val FIELD_FOOT_ACCENT_LIGHT = 0.05f

// §Vibrancy spans — how far the "Muted ↔ Vivid" lever may travel from neutral, tuned per
// light/dark. Dark rooms carry the field as near-additive light over a near-black bed, so they
// take more chroma and more bloom before anything smears; paper is held gentler so it stays
// paper. Every span is the distance from neutral (gain 1) to the vivid/muted extreme, so at
// VIBRANCY_NEUTRAL all of them contribute exactly zero and the output is unchanged.
private const val POOL_CHROMA_SPAN_DARK = 0.55f
private const val POOL_CHROMA_SPAN_LIGHT = 0.42f
// The field's foot carries the least text and the most area, so it is the safest place to spend
// chroma — hence a wider span than the pools (change E: raise the deepest region's chroma target).
private const val FIELD_BOTTOM_CHROMA_SPAN_DARK = 0.80f
private const val FIELD_BOTTOM_CHROMA_SPAN_LIGHT = 0.62f
private const val VIBE_CHROMA_MUTE = 0.45f
// Pool/bloom alpha gain. The field is drawn bolder as it grows more vivid, within the lit-room
// ceiling the field model already enforces.
private const val POOL_ALPHA_SPAN = 0.50f
private const val POOL_ALPHA_MUTE = 0.50f
// Change D: let the glass breathe a few points as vibrancy rises so the plane dims the field
// less, kept small (and smaller on paper) so the hero's own text never loses its bed. Still
// vetoed downstream by `theHeroPaneKeepsItsOwnTextLegible` at the neutral reference.
private const val PANE_RELAX_DARK = 0.07f
private const val PANE_RELAX_LIGHT = 0.045f
private const val PANE_TIGHTEN = 0.06f

/**
 * Derives the atmosphere for [colors]. Pure and cheap — remembered per palette
 * at the theme root, so screens read it for free.
 *
 * [vibrancy] is the "Muted ↔ Vivid" lever; it defaults to [VIBRANCY_NEUTRAL] so every direct
 * caller (the whole test suite) gets the exact pre-vibrancy derivation, while the live app passes
 * the user's setting in through [LocalFolioVibrancy].
 */
fun atmosphereFor(
    colors: FolioColors,
    light: FolioLight = FolioLight.Default,
    vibrancy: Float = VIBRANCY_NEUTRAL,
): FolioAtmosphere {
    val bgLuma = luminanceOf(colors.background)
    val dark = bgLuma < 0.45f

    // §Vibrancy — resolve the one scalar into per-channel gains. `up`/`down` are the signed
    // distance from neutral in each direction, so at VIBRANCY_NEUTRAL both are 0 and every gain
    // below is exactly 1f: the derivation is then byte-identical to the pre-vibrancy pipeline.
    // Enrichment is applied at the construction site; it is hue- and luminance-preserving
    // (see enrichChroma), so none of it can lower a contrast ratio.
    val vibeUp = ((vibrancy - VIBRANCY_NEUTRAL) / (1f - VIBRANCY_NEUTRAL)).coerceIn(0f, 1f)
    val vibeDown = ((VIBRANCY_NEUTRAL - vibrancy) / VIBRANCY_NEUTRAL).coerceIn(0f, 1f)
    val poolChromaGain =
        1f + vibeUp * (if (dark) POOL_CHROMA_SPAN_DARK else POOL_CHROMA_SPAN_LIGHT) - vibeDown * VIBE_CHROMA_MUTE
    val bottomChromaGain =
        1f + vibeUp * (if (dark) FIELD_BOTTOM_CHROMA_SPAN_DARK else FIELD_BOTTOM_CHROMA_SPAN_LIGHT) - vibeDown * VIBE_CHROMA_MUTE
    val poolAlphaGain = 1f + vibeUp * POOL_ALPHA_SPAN - vibeDown * POOL_ALPHA_MUTE
    val paneAlphaGain = 1f - vibeUp * (if (dark) PANE_RELAX_DARK else PANE_RELAX_LIGHT) + vibeDown * PANE_TIGHTEN

    // **The room's own hue — the anchor for the field and for every tier.**
    //
    // The palette's `background` plane is the one colour that is on screen everywhere and
    // unconditionally. Ranked against the reference concepts (Oklab hue, all 18 faces, mean
    // absolute error), it is also by far the best predictor of what a face's surfaces should
    // look like:
    //
    //   backgroundBase        page 11.4°   card 10.3°     <- the anchor
    //   backgroundSecondary   page 12.0°   card 10.9°
    //   surfaceElevated       page 13.1°   card 11.8°
    //   surface               page 18.0°   card 19.4°
    //   guillochePrimary      page 29.7°   card 27.2°
    //   primaryAccent         page 38.1°   card 36.3°
    //   tertiaryAccent        page 58.1°   card 55.3°
    //   secondaryAccent       page 68.3°   card 70.3°
    //   guillocheSecondary    page 76.1°   card 78.4°
    //   warmAccent            page 80.8°   card 80.9°
    //
    // The accents are not a substitute for it and cannot be: an accent is *meant* to be a
    // different colour from the room, so placing a plane at an accent hue is a category
    // error — see [MAX_TIER_HUE_DEV] for the defect that produced and the bound that now
    // contains it.
    //
    // (Measured in **Oklab**, the space the derivation works in. `palette_lib.lab` is CIELAB
    // and `palette_lib.hue` is HSV; mixing those with the Kotlin's Oklab shifts a hue reading
    // by up to ~30° on a saturated violet and will quietly invert a conclusion.)
    //
    // Read through [roomHue], not `oklabHue(background)` directly: the two System faces have a
    // neutral background, where the raw hue is 0°-by-accident rather than a colour, and every
    // tier would then be dragged toward a red the palette never authored. `roomHue` returns
    // NaN there and [tierHue] passes the role's own hue through, which leaves those two faces
    // exactly as they were before the bound existed.
    //
    // The sentinel and the placement value are deliberately two things, because the two
    // consumers want opposite readings of "no hue". A *bound* wants the raw NaN, so it can
    // decline to bound at all. A *placement* cannot decline — there is no such thing as
    // putting a plane at "no hue" — so [placeHue] supplies `primary`'s, which is what the
    // dark field used before the room became the authority and therefore leaves the System
    // faces with the field they already had.
    val planeHue = roomHue(colors)
    val placeHue = if (planeHue.isNaN()) oklabHue(colors.primary) else planeHue

    // **Two hues, not one at two lightnesses.** A ramp whose top and bottom are the same
    // hue at different values is still a monochrome room — it is the *vertical* form of the
    // failure this redesign exists to fix. So each stop leans toward a **different authored
    // plane**: the lit top toward `surface` (the card plane, whose hue the palette rotated
    // deliberately away from the page), the floor toward `surfaceVariant` /
    // `backgroundSecondary`. The wash is then a genuine hue gradient as well as a value
    // gradient, which is what makes a room read as lit air rather than as a tinted sheet.
    //
    // **A dark face is a deep colour with a lit object in it, and those are two moves.**
    // The room used to be built 0.60 of the way to `surface` and then lifted another 0.030,
    // which put the dark field at ~0.12 sRGB — *level with the card it is supposed to sit
    // under* (0.14). A room whose floor is as bright as the object standing in it has no depth
    // at all: every dark screen collapsed into one flat near-black mass, which is exactly what
    // a reader sees as "the app renders dark". So the room returns to the palette's own
    // `background` plane and lets the ladder below do the lighting: the card is placed 0.170
    // *above* it rather than beside it.
    //
    // The room's **colour** is then set independently of its lightness — placed in Oklch at
    // the palette's `primary` hue rather than left as the near-neutral the mix produces. That
    // is the "radiant" half of the fix, and it is the half brightness cannot buy: depth in
    // colour comes from chroma at a *lower* lightness, not from more light. See
    // FIELD_CHROMA_DARK for the numbers.
    //
    // Paper is unchanged in character — a light field is already the brightest thing a
    // surface may be, so its tiers are placed *below* the room instead (see the ladder).
    //
    // The **paper** stops are written in the `G` family — straight-line sRGB, the same
    // arithmetic `mixG` is documented to keep deterministic so a contrast check can replicate
    // it. The dark stops are Oklab, via [tierColour], because they are placed rather than
    // mixed; the Oklab `lift`/`deepen` remain for the roles below that were calibrated with
    // them. Each face uses one arithmetic in one place on purpose.
    // ── §9: the authored page IS the page ────────────────────────────────────────
    //
    // This is the one place the sheet redesign changes the *model* rather than a value. The
    // two stops used to be **derived** — a mix of `background` and `surface`, then placed at a
    // hue bounded to the room — because the palettes of the time carried a hue in the page and
    // the derivation was what kept nine themes from reading as nine sunglasses.
    //
    // The sheet authors the opposite: every dark page is a near-black with a whisper of tint
    // and every light page a cream, and the *theme* lives in the ink and the accents. So the
    // derivation has nothing left to do here — it would only paint a saturated 0.20-chroma
    // hue over a page the designer signed off as `#0F1720`, which is precisely the "dark blue"
    // reading this redesign exists to remove. The page is taken **verbatim**, and the ramp
    // becomes a *value* ramp: the top sits at the authored swatch, the floor falls toward
    // black. A hue ramp would be inventing colour the palette does not have.
    //
    // The dark fall is 0.32 and the light 0.07 because the light page is already the brightest
    // thing a surface may be — it has almost no headroom to fall — while a near-black page can
    // lose a third of its value and still read as the same room. Both were measured against
    // the rendered concepts in `.workbuddy-ai/tmp/curate/sheet_proto.py`, which is the same
    // arithmetic replayed.
    // ── The role allocation: one voice per persistent surface ────────────────────
    //
    // **Every surface on screen at once used to be the same hue, and that was structural
    // rather than a tuning miss.** `toFolioColors` aliases `primary` to `primaryAccent`, so
    // "derived from primary" and "derived from primaryAccent" are the same colour — and the
    // page's two stops, the bar's glass material, the bar's `tintGlass` pull, the bar's 5dp
    // core, the panel, the veil and the scrim all read one of those two names. The nav capsule
    // escaped only to be dyed with the bar's hue downstream (`FolioNavShell`'s `BlendMode.Color`
    // wash), and the raised card was the single exception at `secondary`. The result is the
    // report this allocation exists to answer: *"top bar, nav bar, background and hero card
    // carry different shades of the same color"*. They did. Lightness and alpha were the only
    // axes left.
    //
    // The palettes are not the problem — `dawn`/Dusk authors violet / salmon / cornflower /
    // pink across its four accents, and `moss`/Peacock authors mint / cyan / apricot /
    // periwinkle. The data has four hues; the pipeline spent one of them four times.
    //
    // So the four surfaces a reader sees simultaneously now get four different roles, assigned
    // by **adjacency** — what each surface is actually painted next to:
    //
    //   | surface                  | role        | what it must not match           |
    //   |--------------------------|-------------|----------------------------------|
    //   | page, crown              | `primary`   | the top bar sits on it           |
    //   | page, foot               | `secondary` | the nav capsule sits on it       |
    //   | top bar (glass + core)   | `tertiary`  | the page crown, the hero         |
    //   | nav capsule              | `warmAccent`| the page foot, the top bar       |
    //   | raised card / hero       | `secondary` | the page crown it floats over    |
    //   | panel                    | `primary`   | (unchanged)                      |
    //   | veil (cards, menus)      | `tertiary`  | the panel beside it              |
    //
    // Nothing here changes a *lightness*: each surface keeps the value it was calibrated to and
    // changes only which hue it carries, so every contrast guard reads what it read before.
    //
    // The page also stops being a one-hue ramp. Its two stops used to lean on the same accent
    // (0.03 and 0.08 of `primaryAccent`), i.e. one hue at two lightnesses — a *value* ramp,
    // which is the thing a reader describes as monotone when it covers the whole window. The
    // crown keeps `primary` (the room's anchor) and the foot takes `secondary`, so the page
    // itself travels between two of the palette's hues. On `dawn`/Dusk that is literally the
    // face's own brief — "twilight violet with the last ember on the horizon" — with the ember
    // at the bottom of the window where the brief puts it.
    val fieldFootAccent = chromaticRole(listOf(colors.secondaryAccent, colors.primaryAccent))
    val fieldTop: Color
    val fieldBottom: Color
    if (dark) {
        // Dark packs ship a near-black `background`, so the old field (background -> blacker)
        // read as flat black with no theme in it. The fix is a ramp with real spread: the top
        // stays anchored on the authored near-black (barely lifted toward the hued
        // `backgroundSecondary`) so it reads as the room's floor, and the bottom blooms up
        // through `surfaceElevated` carrying a trace of the page's *second* hue. Keeping the
        // top dark while pushing the bottom bright is what makes the gradient actually visible
        // instead of two near-identical blacks — the earlier 0.05/0.14 lifts were too close in
        // value to see on a `#02010E` page — and the two stops leaning on two different roles
        // is what makes it a hue ramp rather than a value ramp. See [FIELD_FOOT_ACCENT_DARK].
        // Darkening pass: the numbers below are the *only* place the app decides how dark a
        // dark room is, and every one of them was lifted in an earlier pass to make the
        // gradient "actually visible" — which it did, by raising the floor of the whole page.
        // The floor is what the eye reads as "not dark enough", so the mixes come down and the
        // spread is carried by the *hue* instead of by value: the top stays on the authored
        // near-black, the bottom keeps its second-role hue but stops climbing toward
        // `surfaceElevated`. The gradient is still a gradient; it is no longer a lift.
        // P9: 0.28 -> 0.16. `backgroundSecondary` is the *lighter* of the two authored page
        // planes, so every point of this share is the crown drifting off the swatch the
        // designer signed off. The hue trace is what the stop is for; the lift was incidental.
        val top = mixG(colors.background, colors.backgroundSecondary, 0.16f)
        fieldTop = mixG(top, colors.primaryAccent, 0.02f)
        // **P9 — the room's floor may not be made of the card plane.** This is the biggest
        // single lift in the dark pipeline and it was structural, not a tuning miss:
        // `surfaceElevated` is the *elevated card* plane (Oklab L ~0.21 on dusk against the
        // page's ~0.05), so mixing the page's floor 16% toward it put the bottom of the room
        // level with the furniture standing in it. Combined with the foot accent it landed
        // `dawn`/Dusk's foot at #201525 — a washed mid purple — against an authored page of
        // `#05030F`, which is exactly the "dim purple, not dark" report.
        //
        // The floor is now built from the two *page* planes only (`background` ->
        // `backgroundSecondary`), so the ramp is a spread **within the authored page** and the
        // card plane is free to be the thing that sits above it. The gradient survives (the
        // two authored page planes differ, and the foot still carries the second role's hue);
        // what stops is the climb toward a surface the room is not made of.
        val bloom = mixG(colors.background, colors.backgroundSecondary, 0.50f)
        fieldBottom = mixG(bloom, fieldFootAccent, FIELD_FOOT_ACCENT_DARK)
    } else {
        fieldTop = colors.background
        fieldBottom = mixG(
            mixG(colors.backgroundSecondary, Color.Black, 0.07f),
            fieldFootAccent,
            FIELD_FOOT_ACCENT_LIGHT,
        )
    }

    // ── The ladder ───────────────────────────────────────────────────────────────
    //
    // **This is the second place §9 changes the model, and the bigger one.** The tiers used to
    // be *placed*: each at a step off the room's lightness, on a role's own hue (`secondary` /
    // `primary` / `tertiary`), bounded to within [MAX_TIER_HUE_DEV] of the room. That was the
    // right answer while the palettes' authored planes were near-neutral — three authored
    // planes would have been three greys — and it is why the ladder, not the data, was the
    // authority on depth.
    //
    // The sheet inverts the premise by painting `surface` and `surfaceElevated` per face, so
    // the card and the panel are now decisions rather than derivations. Re-deriving them can
    // only move away from the sign-off, so they are taken verbatim. `tierColour`, `tierHue`
    // and the step constants keep their other consumers — the veil, the scrim, the rim — but
    // no longer decide the card or the panel.
    //
    // `card > panel > well` is no longer asserted here and does not need to be: it is a
    // property of the sheet, and `sheet.py` carries the authored values.
    // **The ladder is authored too.** The three tiers used to be *placed* — each at a step off
    // the room, on a role's hue, bounded to the room — because the authored planes were
    // near-neutral on most palettes and would have made three greys. The sheet removes that
    // premise: it paints `surface` and `surfaceElevated` per face, so the card and the panel
    // are **decisions the designer made**, and re-deriving them can only move away from the
    // sign-off. They are taken as authored.
    //
    // The well is the one tier the sheet does *not* paint, so it stays derived — but derived
    // from the authored planes rather than from the ladder: the card laid a little way back
    // into the page, which is what a recess is. `sunkenFill` already treats it as a layer at
    // 0.30 alpha on a dark face, so the mix supplies its temperature and the alpha supplies
    // the depth.
    val panel = liftPlane(colors.surfaceElevated, dark, DARK_PLANE_LIFT_PANEL)
    val well = mixG(colors.surface, colors.background, 0.45f)

    // The pools are the palette's **authored light sources**, not three samples of one.
    //
    // This used to anchor on whichever accent was most chromatic and synthesise two
    // partners by rotating ±150°, because the palettes of the time could not be
    // trusted to carry more than one hue: their accents were near-neighbours on the
    // wheel, or two of them were grey. The palettes now author hues spread around the
    // wheel, so the honest thing — and the thing a theme author can actually predict —
    // is to light the room with what the theme said it was. Rule 14 holds by
    // construction: the aurora is literally the data's own hues.
    //
    // **The three are `glowPrimary` / `glowSecondary` / `warmAccent`, and that is a
    // measured choice, not the obvious one.** These were the three §12.3 *text* accents
    // (`accentProgress` / `Discovery` / `Streak`) — which are AA-fitted, i.e. already
    // pushed toward the ink, and so the wrong source for atmospheric *light*. `Theme.kt`
    // documents the two glows as "the two atmospheric light sources", so using them is
    // what the roles are for. But the two glows alone are **not** enough, because on most
    // faces they are near-neighbours on the wheel (both cool). Measured as the minimum
    // pairwise hue distance between the three pools, after the same `saturate()`:
    //
    //   over all 20 palettes    three text accents -> worst  1.2°, 7/20 pairs under 30°
    //                           glow+glow+warmAccent -> worst 20.6°, 5/20 pairs under 30°
    //   over the 18 art faces   glow+glow+an accent -> worst  0.9°, 9/18 pairs under 30°
    //
    // So the warm accent is load-bearing rather than decorative: it is the hue that stops
    // two cool glows collapsing into one another, and it is simultaneously the palette's
    // designated "<5% warm contrast" (§30) — the room's only warm note. Swapping the glows
    // in *without* it (row three) makes the mono-room failure worse, not better.
    // `DesignSystemTest.ambientPoolsAreNotOneHue` pins the floor.
    //
    // Saturated up so the spread registers through a wash that is only ever a quarter of
    // an alpha. These factors were picked for the AA-fitted accents; the glow roles are
    // authored as display colours and arrive already saturated, so `saturate` clamps more
    // of them. The spread above is measured *through* these factors, not despite them.
    //
    // **On a dark face the pools are then matched back to the room's own lightness**, and
    // that is what stops them being a disco. The glow roles are authored as *light sources*
    // — `glowPrimary` on the nebula face is sRGB 0.53, which is a lamp, not a tint. Poured
    // at a quarter alpha over a near-black field, a lamp lands 2.7×–12.7× the room's own
    // luminance: three coloured spotlights on a dark wall, which is the effect the reader
    // described. Matching them to a multiple of the room's lightness keeps every bit of the
    // hue and chroma — the thing the room actually needs from them — and spends only their
    // *brightness*, which is the part that was reading as a stage light. The composite now
    // sits at 0.68×–2.09× the room: a temperature in the air, not a shape on it.
    //
    // Paper needs none of this. Its glow roles are already authored as dark accents that
    // read as tints on a light field, and matching them would only dull them.
    // **The pools are the authored glows, poured thin.** They used to be saturated up and then
    // `matchLuma`'d back to `room * 1.6`, because the field was a mid-tone and a lamp poured
    // over it landed 2.7–12.7× the room's own luminance. Both halves of that are now wrong:
    // the field is a *near-black* (gamma luminance ~0.011 on a dark face), so matching a glow
    // to `room * 1.6` would crush it to ~0.017 — a lamp turned into a shadow — and at an alpha
    // of 0.09 the glow cannot be a spotlight whatever its own brightness. So the authored
    // colour is taken exactly and the alpha is the only dial.
    //
    // **The pools are the authored glows, poured thin — then harmonised.** The authored trio is
    // a deliberately wide split-complementary spread; poured as three strong moving spots that
    // width read as *incoherent* (three unrelated hues, not one scheme). [harmonizePoolHues]
    // rotates each pool partway toward the trio's circular-mean hue, so they stay distinct and
    // keep `warmAccent`'s warm note but visibly belong to the same family. The pull is capped so
    // the widest pair never drops under the one-hue floor (`ambientPoolsAreNotOneHue`).
    val pools = harmonizePoolHues(
        listOf(colors.glowPrimary, colors.glowSecondary, colors.warmAccent),
        pull = POOL_HARMONY_PULL,
    )

    // ── The two chrome planes, and why they are now two ──────────────────────────
    //
    // The bar and the capsule used to be "the same piece of furniture seen from two angles",
    // which is a true statement about *materials* and a false one about colour: they are never
    // seen from two angles, they are seen **at the same time, at opposite ends of the window**,
    // with the whole page between them. Treating them as one colour is what made the chrome
    // read as a frame rather than as two objects — and the implementation went further than the
    // doc did, because `FolioNavShell` then washed the capsule with the bar's own glass through
    // `BlendMode.Color`, which replaces hue and chroma outright. The capsule kept nothing of
    // its own but its lightness.
    //
    // They are two planes now, each placed at **its own role's hue and its own authored
    // lightness**:
    //
    //  - the **capsule** takes `warmAccent`. It is the palette's designated warm note (§30) and
    //    the only role not already spent on a plane, and it sits at the foot of the window
    //    where the field now leans `secondary` — so the one surface that is on screen on every
    //    route is the one surface that is unmistakably not the page behind it;
    //  - the **bar** takes `tertiary`, which is what this file's own §NAV_CHROMA note has
    //    always specified for chrome ("a role not already spoken for, since the card speaks
    //    `secondary` and the panel `primary`") and what the code never did. It sits at the
    //    crown, where the field leans `primary`.
    //
    // The capsule is a *placement*, not a mix: [tierColour] holds the authored plane's gamma
    // luminance exactly and spends only chroma, so the nav icons' contrast against it does not
    // move — which is the whole reason this can be done to a plane with text on it. The
    // authored `navigationSurface` is `mixA(card, page2)`, i.e. a lightness step with no hue of
    // its own on most faces; holding that step and giving it a hue is the smallest change that
    // turns a neutral into a colour. An achromatic `warmAccent` would place it at 0°/red, so
    // [chromaticRole] walks to a role that has a hue, and a face with no chromatic role at all
    // keeps the authored plane untouched.
    val navAccentColor = chromaticRole(
        listOf(
            // §18 chrome hue: the face's *authored* nav accent first. The derivation
            // used to lead with `warmAccent` — the palette's designated warm note, a
            // gold/olive/umber on most faces — so the one surface on every route was
            // placed at a brown. The authored role is a deliberate, clean hue per
            // face; the warm/tertiary/secondary/primary fallbacks keep any palette
            // that does not author one (or authors an achromatic one) exactly as it was.
            colors.navAccent,
            colors.warmAccent,
            colors.tertiaryAccent,
            colors.secondaryAccent,
            colors.primaryAccent,
        ),
    )
    // §20 lift: on a dark face the authored nav plane sits within a few percent of the field —
    // `navigationSurface` is `mixA(card, page2)`, i.e. a step off a page that P8/P9 brought down
    // to a near-black — so the one piece of furniture on screen on every route read as part of
    // the page. Lift it off the room *before* it is placed, so [tierColour] still holds the
    // lifted lightness exactly and spends only chroma; the hue the sheet authored survives.
    val navPlaneBase = liftPlane(colors.navigationSurface, dark, DARK_PLANE_LIFT_NAV)
    val navPlane = if (oklabChroma(navAccentColor) < ROOM_HUE_MIN_CHROMA) {
        navPlaneBase
    } else {
        tierColour(
            luminanceOf(navPlaneBase),
            oklabHue(navAccentColor),
            NAV_CHROMA,
        ).copy(alpha = colors.navigationSurface.alpha)
    }

    // The bar's hue. This one line is most of the report: it feeds [chromeAccent], which is
    // the bar's 5dp chromatic core, its Haze frost tint, the material 90% of `barGlass` is made
    // of *and* the `tintGlass` pull laid over that material — four helpings of one accent on
    // the strip directly above a page whose crown leans on the very same accent. `tertiary` is
    // the role the chrome was always specified to speak in.
    //
    // §18: it now speaks in the face's *authored* masthead hue first ([FolioColors.mastheadAccent]).
    // The derivation led with `tertiaryAccent`, which is a brown on manuscript, silver, sakura,
    // honey, moss, lagoon, cyberpunk and vice-day, so the top bar read as a brown band on those
    // faces. The authored role is a deliberate second hue per face; `tertiary`/`primary` remain
    // the fallback for a palette that does not author one.
    val chromeAccentColor = chromaticRole(
        listOf(colors.mastheadAccent, colors.tertiaryAccent, colors.primaryAccent),
    )
    val barGlassBase = if (dark) {
        // Weighted toward the (now lifted) chrome plane, not the dark field, so the bar sits
        // proud of the darkened background instead of being dragged down with it.
        mixG(fieldTop, navPlane, 0.72f)
    } else {
        mixG(liftG(fieldTop, 0.42f), navPlane, 0.20f)
    }
    val card = liftPlane(colors.surface, dark, DARK_PLANE_LIFT_CARD)

    // §18: the in-reader overlays' hue — the face's authored [FolioColors.readerOverlayAccent],
    // taken directly rather than clamped to the room. The clamp is right for a tier of the page
    // (a card, a well, a scrim) and wrong for the reader's panels: on the warm rooms
    // (manuscript, honey, dawn, aurum, rouge) the room hue is itself an orange, so a
    // room-bounded veil came out a flat brown on every one of them. An overlay is an opaque
    // *panel* laid over the page with its own ink, so it may be a colour of its own choosing.
    // An achromatic face (gallery, whose accents are all grey) falls back to the old
    // room-bounded placement so it stays neutral.
    val overlayHue = if (oklabChroma(colors.readerOverlayAccent) < ROOM_HUE_MIN_CHROMA) {
        tierHue(planeHue, chromaHue(colors.primary))
    } else {
        oklabHue(colors.readerOverlayAccent)
    }

    return FolioAtmosphere(
        isDark = dark,
        fieldTop = fieldTop,
        // §Vibrancy change E: the field's foot is the deepest, least text-bearing region, so it
        // carries the widest chroma span. Identity at neutral (bottomChromaGain == 1f).
        fieldBottom = enrichChroma(fieldBottom, bottomChromaGain),
        fieldSourceX = light.sourceX,
        fieldSourceY = light.sourceY,
        // §Vibrancy change A/B: enrich the authored pools once, here, so both the Canvas
        // SilkField base and the Android Mirage shader consume identical already-vivid colours
        // (no path-specific vivid()/sink). Hue- and luminance-preserving; identity at neutral.
        pools = pools.map { enrichChroma(it, poolChromaGain) },
        // The pools are the theme's colour, so they have to carry. Raised from a whisper
        // (0.16 / 0.085) so the four authored hues read as a living aurora rather than a
        // rumour under the wash — this is the main dial that turns a mono room multi-hue.
        //
        // Bounded by [POOL_ALPHA_CEILING], which is the *one* ceiling: the lit path in
        // `fieldColors` clamps there as well, so an at-rest value above it would mean a
        // room that quietly dims the moment a book's colour arrives.
        // Both faces sit at the atmosphere ceiling: the lit path in fieldColors clamps
        // to POOL_ALPHA_CEILING anyway, so authoring the dark face higher only disagreed
        // with the invariant without ever rendering brighter.
        // **0.28 -> 0.09 dark / 0.08 light.** The pools were repainting the room: at 0.28 over
        // a *mid-tone* field they were a wash, but the sheet's dark page is a near-black, so the
        // same alpha would put three authored lamps on top of the one surface the designer
        // signed off — the room would no longer be `#0F1720`. Measured on the rendered concepts
        // this is the alpha at which the aurora still reads as colour in the air while the page
        // stays at its swatch. See §9.
        // **0.12 dark / 0.15 light.** The pools are the one mechanism that puts the theme's
        // *several* hues into the air at once (glowPrimary / glowSecondary / warmAccent), so when
        // they are a whisper the room collapses to whatever single hue the panes carry — the
        // "everything is one colour" report. Lifted well off the old 0.09/0.08 whisper toward the
        // POOL_ALPHA_CEILING (0.28) so the three authored lamps read as distinct colour in the air
        // without repainting the authored page outright. The lit path still clamps at the ceiling.
        //
        // P8 brought the dark face down from 0.16 to 0.12. The dark half was carrying the same
        // amplitude as paper while sitting on a far darker page, so the same alpha read as a much
        // bigger lift — the pools were the largest single contributor to "dark not dark enough".
        // Multi-hue is preserved (three pools still read as three hues); only the amount of light
        // they add comes down, which is exactly the dark-mode intent.
        // P9 brought it again, 0.12 → 0.07. The pools are the *only* at-rest mechanism that
        // adds light to the authored page (the ramp is the page; the pools sit on top of it),
        // and `SilkField` then draws them a second time as three **additive** blooms. Two
        // layers of the same three lamps is why the room measured well above its own swatch
        // even after P8. Three pools still read as three hues at 0.07 — the hue spread is
        // carried by `harmonizePoolHues`, not by the alpha — so multi-hue is preserved and
        // only the amount of light comes down.
        poolAlpha = (if (dark) 0.07f else 0.15f) * poolAlphaGain,
        // The room is lit by what is being read, and the amplitude has to be real
        // for that to register at all: the value this replaced was a whisper at one
        // edge of the wash, which is why every screen looked like the same brown room.
        //
        // This moves the cover's *whole* colour, lightness included — `fieldColors`
        // mixes it and then clamps only at what the ink survives, which is a lot of
        // headroom on a deep dark. The pairing used to run the other way, on the
        // theory that a dark field can carry hue before it reads as painted: 0.24
        // against paper's 0.13. On device that theory is what turned deep dark rooms
        // olive — "makes dark themes bright and saturated... make the darker
        // background more dominant". So the faces are inverted: a dark room now takes
        // *less* of a cover than paper does, and keeps its character through where the
        // lamp sits rather than by drinking the jacket.
        // P8: 0.045 → 0.035. Same inversion, less of it: on a deep dark the 0.045 amplitude was
        // still enough to move the whole page toward the jacket, which is the "dark themes get
        // bright and saturated" half of the report. The character now comes from where the lamp
        // sits rather than from how much of the cover the field drinks.
        fieldTintStrength = if (dark) 0.035f else 0.10f,
        // A shadow is absence of light, so it takes the palette's own deepest
        // neutral. Pure black on a warm cream page reads as a hole.
        shadowAmbient = if (dark) Color.Black else deepen(colors.onSurfaceVariant, 0.35f),
        shadowSpot = if (dark) Color.Black else deepen(colors.onSurface, 0.15f),
        // P8: 0.55 → 0.45. A dark room has no light to occlude, so a full-scale shadow there is a
        // black halo rather than depth — the same reason the dark rim is *emitted* rather than
        // lifted. Dropping the scale lets a card sit on a dark page without a soot ring, and it
        // matters more now that covers no longer carry their own cast shadow on the shelf pane.
        shadowScale = if (dark) 0.45f else 1f,
        // Dark surfaces emit at the rim; light surfaces catch a white sheen.
        //
        // **The emitted rim is placed, not lifted.** A rim is the surface *emitting*, so it
        // takes the card's own hue (`secondary`) instead of a whitened `surface` — and
        // whitening is the one operation that removes chroma, which is why the rim read as a
        // grey edge highlight on an otherwise coloured card. The lightness the lift produced is
        // held exactly, so nothing about the rim's brightness moves. See [RIM_CHROMA].
        //
        // **Dark faces emit harder (0.34 → 0.48 lift, 0.62 → 0.78 alpha).** This file's own
        // `shadowScale` note sets the doctrine: a dark room has no light to occlude, so depth
        // there is carried by the rim *emitting* rather than by a cast shadow. The rim was being
        // asked to carry that whole job at an amplitude picked when the shadow still helped, so
        // a card at night sat at the field's own luminance with a whisper of an edge — the
        // "everything is as dark as the background, so it looks dull" report. Raising the
        // emission is the lever that cannot cost legibility: it is an edge highlight laid at the
        // surface's boundary, so it adds the lift the eye reads as a separate object without
        // touching the body the ink sits on (which is what the 7:1 pane veto measures).
        rimLight = if (dark) {
            // §Metal: lifted harder and carried at higher alpha than the original
            // 0.48/0.78 — the rim is the one highlight every glass surface shares
            // (sheen, lens Fresnel, well lips), and the decree is that it *stands
            // out on the glass*: a bright tight catch rather than a soft bloom.
            tierColour(
                luminanceOf(lift(colors.surface, 0.72f)),
                tierHue(planeHue, chromaHue(colors.secondary)),
                RIM_CHROMA_DARK,
            ).copy(alpha = 0.92f)
        } else {
            // **Paper's rim stays white, and that is not the same failure as the rest of the
            // paper chrome.** A rim is a specular highlight — light catching an edge — and on a
            // light surface a specular is white by physics. It is a highlight rather than a
            // plane, so there is nothing here that *should* carry the theme; colouring it would
            // read as a stained edge. The veil, the scrim and the nav plane are planes, and they
            // are the ones that now carry a hue. §Metal: 0.92 -> 0.97 — the catch stands out
            // here too.
            Color.White.copy(alpha = 0.97f)
        },
        rimShade = if (dark) {
            Color.Black.copy(alpha = 0.40f)
        } else {
            deepen(colors.outline, 0.10f).copy(alpha = 0.38f)
        },
        // Raised planes (the floating heroes) lean on the palette's **second** hue,
        // not its primary. Primary already owns the field's anchor pool, the lamp,
        // the ambient film and every progress read-out, so a primary-tinted hero
        // was one more helping of the colour the room was already full of — the
        // single biggest contributor to "the whole screen is one colour". The
        // second hue makes the hero a different *place*.
        //
        // The tier carries that hue **exactly**, at the ladder's lightness, with as much
        // chroma as the gamut holds there. Earlier versions mixed `surface` toward the role
        // and then matched luminance, which capped the surviving chroma at the tint share
        // and, on paper, lifted the result toward white — the one operation that removes
        // chroma. Measured, the card carried 0.00–0.06 Oklab chroma against the roles' own
        // 0.08–0.195, so it read as a lighter grey rather than as a colour, and on paper it
        // sat within 1.4 ΔE of the page — under the just-noticeable difference. `matchLuma`
        // is not used here for the same reason: hitting a luminance by scaling RGB scales
        // chroma with it, so it cannot place a *colour*, only a brightness.
        // The raised tier carries the chrome hue too, at its own lightness and mixing share.
        raisedFill = matchLuma(mixOklab(
            card,
            cardGlassMaterial(colors.secondary, card, dark),
            if (dark) CARD_MATERIAL_SHARE_DARK else CARD_MATERIAL_SHARE,
        ), luminanceOf(card)),
        // The middle rung. The panel is the largest grouping surface in the app and the
        // tier that carried the *least* palette — on paper it was raw `surface`, the one
        // colour authored with its hue deliberately removed, and it read at chroma 0.012
        // against the well's 0.197. It leans on `primary` so the panel speaks in the
        // palette's own voice while the card, on `secondary`, stays a different place.
        // Two tiers, two hues, one ladder — and now two *colours* rather than two greys.
        panelFill = matchLuma(mixOklab(
            panel,
            cardGlassMaterial(colors.primary, panel, dark),
            if (dark) CARD_MATERIAL_SHARE_DARK else CARD_MATERIAL_SHARE,
        ), luminanceOf(panel)),
        // The hero's pane: the room shows through it.
        //
        // This is now an *ambition*, not a calibration. A pane this transparent is
        // what makes the gel's refraction honest — bending a room that the eye can
        // already see through the glass reads as glass, while the same bend on a near
        // opaque card reads as a strip of wallpaper laid over a sticker, which is
        // exactly what it was. The two settings only make sense together.
        //
        // `theHeroPaneKeepsItsOwnTextLegible` holds the veto: it composites this pane
        // over the brightest point the *lit* page can reach and requires 7:1 for the
        // ink on it. If it fails, this number is wrong and the glass is not affordable
        // on that theme — the floor does not move.
        //
        // Paper stays much closer to solid: a light surface is already near white, so
        // translucency costs it its identity far faster than a dark field loses anything.
        //
        // P8: dark 0.64 → 0.60. The hero pane is the one surface that is *meant* to be
        // see-through, so on the dark faces it gets the extra transparency rather than the
        // fill — which is the honest way to make a dark room read dark without burying the
        // refraction the pane exists to show. `theHeroPaneKeepsItsOwnTextLegible` still vetoes.
        // §Vibrancy change D: the plane dims the field a touch less as the room grows vivid.
        // §20.1: 0.60 -> 0.72 on a dark face. The bloom-lit dark room is BRIGHTER than
        // the authored card swatch (SilkField pours the blooms at 0.20 alpha on dark),
        // so at 0.60 the rendered card composited under its pane sat at or below its
        // own room — a recess, not a raised card, on every dark theme (measured by
        // outputs/palette/folio_plane_retune.py). More of the pane, less of the room:
        // the card reads as its own surface while the room still breathes through the
        // remaining 28%. Paper keeps its authored 0.76.
        paneAlpha = (if (dark) 0.72f else 0.76f) * paneAlphaGain,
        // A recess cannot be an opaque colour. The page a reader actually sees is the
        // field *after* the runtime pools and the cover's lamp have been laid over it,
        // so any at-rest token chosen to be "the dark of the page" is either flat
        // against it or an abrupt cut — and three values picked in that family (a
        // deepenG of the background, background scaled by 0.74, a third of the way
        // back up from fieldBottom to fieldTop) all rendered the same black hole.
        //
        // So the well is no longer a colour, it is a layer: a dark tint at low alpha
        // that sits in front of the theme rather than beside it, and therefore darkens
        // whatever the room happens to be instead of trying to guess it. The tint is
        // the counter-accent rather than neutral black so the recess reads as a
        // different temperature from the card around it, which is what carries the
        // depth now that the luminance step no longer can.
        //
        // The alpha is the mechanism, not a tuning knob: raising it back toward opaque
        // re-creates the hole. Separation at the well's edge is folioSunken's rim lip.
        //
        // The well now wears `tertiary` — the counter-accent — so the recess is a different
        // *temperature* from the card around it, not merely a darker one.
        //
        // It gets the least chroma of the three tiers, and that is a limit rather than a
        // choice: on a dark face the well sits at Oklab L ≈ 0.10, where the gamut ceiling is
        // about 0.02, so a recess cannot be strongly coloured however hard it is asked to be.
        // That is also physically right — a recess is in shadow, and shadows are
        // desaturated. The card and the panel are where the colour lives.
        //
        // What matters is that it is *placed* rather than merely coloured. It used to be
        // handed over as a raw tint and left to land wherever it landed, which on the
        // deepest near-black faces put it above the raised pane and inverted the depth — the
        // reason the dark branch had to keep shaving its share and its alpha. A step below
        // the room states the relationship instead of negotiating it, and the alpha can go
        // back to being the mechanism it was always meant to be.
        sunkenFill = if (dark) well.copy(alpha = 0.34f) else well,
        // Reader controls can sit directly above a page rendered by a native
        // surface. Keep the palette's surface character, but make the veil
        // effectively opaque so page text never competes with menu text.
        //
        // The veil is the *room, dimmed* — it covers the page the reader was just looking at —
        // so it is placed at a hue rather than left as a deepen of the authored `surface`, which
        // is a neutral on most palettes. Lightness and alpha are both held exactly, so the
        // veil stays as opaque as the guard demands and its ink contrast does not move. See
        // [VEIL_CHROMA].
        //
        // **§18: the hue is the face's authored [FolioColors.readerOverlayAccent], not the room's.**
        // It used to be `tierHue(planeHue, hue(primary))` — the room hue, pulled at most 12° toward
        // primary. That is the right rule for a *tier of the page* (a card, a well, a scrim), and
        // the wrong one for the reader's overlays: on the warm rooms (manuscript, honey, dawn,
        // aurum, rouge) the room hue is itself an orange, so every reader panel — the settings
        // sheet, the TOC, the annotations — came out a flat brown. A reader overlay is an opaque
        // *panel* laid over the page with its own ink, not the page thickening, so it is allowed
        // to be a colour of its own choosing. The authored role is one deliberate hue per face;
        // an achromatic face (gallery, whose accents are all grey) falls back to the old
        // room-bounded placement so it stays neutral.
        veilFill = if (dark) {
            // Dark glass was reading as flat black: *deepening* `surface` at all drops the plane
            // toward the floor, and a hue needs luminance to live in — with none, the tint
            // vanishes and the glass is just black. So LIFT the surface instead (give the plane
            // real value) and carry a richer chroma (VEIL_CHROMA_DARK) so the palette tint reads
            // as a colour that holds its weight on screen. Alpha is untouched at 0.97 so
            // `veilStaysOpaqueEnoughForReaderMenus` holds; the lift is bounded so the menu ink
            // (light on dark) still clears its contrast floor against the plane.
            //
            // 0.07 was too timid — the plane stayed ~0.21 Oklab L and, under the 0.66 blurred-glass
            // fill over a near-black field, read as flat black. 0.24 overshot the other way (~0.39 L,
            // a washed mid-grey that stops reading as a *dark* theme). 0.13 lands the plane at
            // ~0.30 L: the band where the sRGB gamut actually holds this much chroma, so the glass
            // is unmistakably the palette's colour while still being dark. Light menu ink clears
            // ~13:1 against it, so the reader menus stay comfortably legible.
            // P9: 0.09 → 0.055. The lift is here so a hue has some luminance to live in, and
            // 0.055 still leaves the plane at ~Oklab L 0.21 — the band where the gamut holds
            // the full VEIL_CHROMA_DARK, so the glass is still unmistakably the palette's
            // colour. What it stops doing is lifting the one plane Material's
            // `surfaceContainer`/`High` ladder is built from (see Theme.kt's
            // `toColorScheme`), which put every menu, dialog and Material card in the app a
            // further step above a page the reader is asking to be darker. Alpha is untouched
            // at 0.97 so `veilStaysOpaqueEnoughForReaderMenus` holds, and the menu ink (light
            // on dark) gains contrast rather than losing it.
            tierColour(
                luminanceOf(lift(colors.surface, 0.055f)),
                overlayHue,
                VEIL_CHROMA_DARK,
            ).copy(alpha = 0.97f)
        } else {
            // Paper comes DOWN first: at gamma ~0.99 this is a white, and a white cannot be a
            // colour. See PAPER_CHROME_DROP. The alpha is untouched, so the veil stays as
            // opaque as `readerMenusStayOpaqueEnough` demands, and the menu ink still clears
            // 9.4:1 against it.
            tierColour(
                luminanceOf(deepenG(liftG(colors.surface, 0.35f), PAPER_CHROME_DROP)),
                overlayHue,
                VEIL_CHROMA,
            ).copy(alpha = 0.98f)
        },
        // The status icons need a ground, but a fixed dark ink band put a black
        // stripe across the top of every light theme. Take it from the field
        // instead: paper gets a paper-white ground and dark icons, a dark field
        // keeps its deep ground and light icons.
        //
        // Held low on purpose. Nothing scrolls *under* this band — the app bars sit
        // above their content, so what the scrim covers is the page's own field,
        // which already contrasts with the icons the OS draws over it. Anything
        // heavier is a painted band, and that band was most of what made the
        // masthead read as a lid.
        //
        // **And it is placed rather than mixed.** It used to be a `deepen` of the authored
        // `background` — a neutral on 21 of the 22 palettes — so the band that gives the
        // status icons their ground was grey on nearly every theme. The lightness is held
        // exactly, which is what keeps `statusScrimFollowsThePaletteNotAFixedInk` green: that
        // guard reads *relative* luminance while the placement holds the *gamma* one, and
        // measured over all 22 palettes the difference is under 0.0001. See [SCRIM_CHROMA].
        barScrim = if (dark) {
            tierColour(
                luminanceOf(deepen(colors.background, 0.30f)),
                tierHue(planeHue, chromaHue(colors.primary)),
                SCRIM_CHROMA,
            ).copy(alpha = 0.46f)
        } else {
            // Comes down first, like the veil — see PAPER_CHROME_DROP. The scrim stays well
            // above the 0.45 relative-luminance floor its guard reads (0.709 at this drop), so
            // the OS still has a light ground for the dark status icons it draws on paper.
            tierColour(
                luminanceOf(deepenG(liftG(colors.surface, 0.72f), PAPER_CHROME_DROP)),
                tierHue(planeHue, chromaHue(colors.primary)),
                SCRIM_CHROMA,
            ).copy(alpha = 0.42f)
        },
        // Bars sit over Compose content, so they can be actual glass. Three rules
        // here, all learned the hard way:
        //
        //  - the tint is taken from the *field*, not from `surface`. A surface-
        //    coloured bar over a background-coloured page is a different object
        //    from the page no matter how low its alpha goes, which is exactly what
        //    made the collapsed masthead read as a grey lid;
        //  - the alpha stays nearer a third than a half. Past that the fill starts
        //    describing its own rectangle, and the eye reads a rectangle as a panel;
        //  - depth is carried by the hairline and the fade below it (FolioTopBar),
        //    never by making the fill heavier.
        //
        // **And the chrome now speaks in a chrome role.** `navigationSurface` is authored
        // for all eighteen faces — a deliberate step between the card plane and the page
        // plane — and until now *nothing read it*: the bar and the capsule were both
        // derived from `surface` instead, so the app's two most persistent pieces of
        // furniture were painted with the same role as its content and read as part of it.
        // Taking the bar's tint from the chrome plane is what makes the top of the screen a
        // *bar* rather than the page happening to get lighter there — and that plane is now
        // the *placed* one ([navPlane]), so the bar carries the theme's hue rather than
        // mixing toward a neutral. The alpha is untouched, so the §15 glass window does not
        // move.
        chromeAccent = chromeAccentColor,
        // **A chromatic glass plane, not a page-coloured band.** The old form mixed two
        // near-neutral inputs (`fieldTop`, `navPlane`) and so anchored the bar to page chroma;
        // measured, it came out below the page's chroma on 15 of 20 faces. This blends the base
        // toward a material placed at a lightness the gamut can hold chroma at, so the crown
        // reads as coloured glass rather than as the page thickening.
        //
        // **And the accent pull is here, not in `barGlassFor`.** The glass is coloured before
        // anything else is asked of it: it gains the chrome accent's hue and chroma and almost
        // none of its lightness, so the material carries the theme rather than only the 5dp
        // core doing it. That pull used to be applied in `barGlassFor`, unconditionally,
        // "so the untinted branch is tinted too" — but a pull that applies whether or not the
        // room is lit is a palette decision, and putting it in the runtime function meant
        // `barGlassFor(atmos, null, s)` returned a colour no token named. Two guards pin that
        // identity (`anUnlitRoomIsThePalettesOwnByteForByte`,
        // `barGlassForLeavesTheGlassAloneWhenNothingIsLit`) and the nav capsule's "is this
        // room lit?" test depended on it. Stated once, here, it is also no longer a *second*
        // helping of the same hue laid over the material below it.
        barGlass = tintGlass(
            base = mixOklab(
                barGlassBase,
                chromeGlassMaterial(chromeAccentColor, dark),
                BAR_GLASS_MATERIAL_SHARE,
            ),
            accent = chromeAccentColor,
            tau = if (dark) GLASS_TINT_DARK else GLASS_TINT_LIGHT,
            chromaBoost = if (dark) GLASS_CHROMA_BOOST_DARK else GLASS_CHROMA_BOOST_LIGHT,
            lightnessShare = if (dark) GLASS_LIGHTNESS_SHARE_DARK else GLASS_LIGHTNESS_SHARE_LIGHT,
        // Dark was 0.36 and is now 0.40, matching paper. That is as far as the dark bar's *weight*
        // may go: `appBarsAreGlassNotLids` pins this alpha inside 0.22–0.42, and it is right to —
        // past that the bar stops being glass and becomes the grey lid the redesign removed. (An
        // earlier attempt at 0.58 broke exactly that guard.) So the dark bar's missing colour is
        // bought with CHROMA in `chromeGlassMaterial`, not with opacity.
        // Lifted 0.40 -> 0.52 so the crown reads as a *bar* and not as the page thickening: the
        // user's direct report was "the nav bar and the top bars are the same colour as the
        // background", which is what a 0.40 glass over a near-background field looks like. At 0.52
        // the chrome plane's own hue/lightness carries enough to separate from the field while the
        // bar still reads as glass (the field is visible through it). The old 0.22-0.42 "glass not
        // lid" window was part of the design the user is explicitly rejecting here.
        ).copy(alpha = 0.52f),
        // The chrome plane, opaque, and now **placed rather than passed through**.
        //
        // It used to be handed on untouched, on the argument that the colour decision belongs
        // to the palette. That argument was right about *who decides* and wrong about the
        // result: the authored role is `mixA(card, page2)` — a mix of two planes that are
        // themselves neutral on most palettes — so the decision the palette actually made was
        // "grey", on the one surface that is on every screen in the app. It is now placed at
        // the authored role's own lightness with the palette's `warmAccent` hue — the bar took
        // `tertiary`, and two pieces of chrome at opposite ends of one window are two objects —
        // so the palette still decides the *plane* and the theme gets to be a colour. See
        // [NAV_CHROMA].
        navigationSurface = navPlane,
        hairline = if (dark) {
            lift(colors.outline, 0.05f).copy(alpha = 0.30f)
        } else {
            colors.outline.copy(alpha = 0.42f)
        },
        // The ink that sits straight on the page — most rows and lists in the app are
        // this colour on the field, with no surface between them.
        ink = colors.onBackground,
        vibrancy = vibrancy,
    )
}

/** The active atmosphere. Provided at the theme root; derived if absent. */
val FolioTheme.atmosphere: FolioAtmosphere
    @Composable
    get() {
        val colors = LocalFolioColors.current
        // The pack's own light decides where the field's gradient axis runs, so each theme's
        // page ramps along its own source rather than a shared diagonal — see
        // [FolioAtmosphere.fieldSourceX]. A tree with no pack gets [FolioLight.Default].
        val packId = LocalFolioPackId.current
        val vibrancy = LocalFolioVibrancy.current.level
        return remember(colors, packId, vibrancy) {
            atmosphereFor(colors, FOLIO_LIGHTS[packId] ?: FolioLight.Default, vibrancy)
        }
    }

/**
 * [raisedFill] at [FolioAtmosphere.paneAlpha] — the hero's material. Everything else
 * about a raised surface stays: the shadow, the rim, the directional sheen all read
 * the same, so the card keeps its depth and only the fill starts letting the room
 * through. That is the difference between a panel pasted on a page and a pane sitting
 * in one.
 */
fun FolioAtmosphere.paneFill(): Color = raisedFill.copy(alpha = paneAlpha)

/**
 * The room's colour-vibrancy lever: a single scalar in `0..1` where [VIBRANCY_NEUTRAL] (0.5) is
 * the old look, `1` is the most saturated the gamut and the contrast guards allow, and `0` is
 * the most muted. One orthogonal knob, tuned per light/dark inside [atmosphereFor], driving the
 * pool and field chroma, the pool/bloom alpha, the lit-cover clamp headroom, the glass dimmer and
 * the Android field shader's dark-sink — so there is exactly one place a user (or a theme) moves
 * "how colourful the room is", and it can never move legibility.
 */
@Immutable
data class FolioVibrancy(val level: Float = com.folio.reader.settings.FOLIO_VIBRANCY_DEFAULT) {
    companion object {
        /** The pre-vibrancy look: every gain is 1, every test derives here. */
        val Neutral = FolioVibrancy(VIBRANCY_NEUTRAL)
        /** What the app ships at before the user touches the slider. */
        val Default = FolioVibrancy(com.folio.reader.settings.FOLIO_VIBRANCY_DEFAULT)
    }
}

/**
 * Provided once at the app root from `ReaderSettings.vibrancy`. Defaults to [FolioVibrancy.Default]
 * so any tree that does not provide it (previews, isolated activities) still gets the shipped
 * vivid look, while the design-system tests call [atmosphereFor] directly and keep the neutral
 * default. Read by [FolioTheme.atmosphere] and by the Material bridge in `FolioTheme.MaterialTheme`.
 */
val LocalFolioVibrancy = androidx.compose.runtime.compositionLocalOf { FolioVibrancy.Default }
