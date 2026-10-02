package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

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
    /** Page-level gradient stops, top to bottom, already alpha-composited. */
    val fieldTop: Color,
    val fieldBottom: Color,
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
    val barGlass: Color,
    /** Hairline colour for structural rules and dividers. */
    val hairline: Color,
    /**
     * The body ink that sits directly on the field, carried so the environment can
     * bound itself: [FolioFieldModel] lights the room as brightly as this colour can
     * still clear its contrast floor against it, and no brighter.
     */
    val ink: Color,
    /**
     * The per-theme **skyscape**: the cosmic/ethereal background that makes two themes
     * read as different *places*, not the same room repainted. Dark faces get a deep
     * night sky (nebula + stars + a far planet); light faces get an ethereal daylight
     * sky (pale wash + sunset glow + mist). Every colour is tuned from the palette's
     * own roles in [atmosphereFor], so all 36 built-ins, System/Material-You and custom
     * themes get a distinct, alpha-bounded signature for free. See [FolioSignature].
     */
    val signature: FolioSignature,
)

/**
 * The ceiling the skyscape is ever composited at **within the content band** (the
 * middle of the screen, where cardless body text lives).
 *
 * A single constant, pinned by the `FolioFieldModelTest` contrast guard that
 * composites the field + the motif's strongest colour at this alpha and asserts body
 * ink still clears §12.3's 7:1 across every palette — the guard sets this value, not
 * taste. The renderer may run richer at the very top/bottom *edges* (where no cardless
 * body text sits), falling to this ceiling before the content band; see the vertical
 * falloff in `drawSkyscape`. Kept in the hero-mesh register (0.07): air, not a wall.
 */
const val FOLIO_SIGNATURE_ALPHA_MAX = 0.10f

/**
 * One theme's skyscape, solved from its colours only — deterministic per palette.
 *
 * Everything is derived from [seed] (a stable hash of the palette's `background`+
 * `primary`) and the palette's own accent roles, so a custom or System face inherits a
 * distinct sky with no per-theme table. All colours are opaque; the renderer composites
 * them at its own low alphas (bounded in the content band by [alphaCeiling]).
 *
 *  - [skyTop]/[skyBottom]: the vertical sky wash, strongest at the top edge.
 *  - [nebulaCool]/[nebulaWarm]: the two drifting cloud/accent pools.
 *  - [glow]: the celestial body + sparkle highlight (sunset-gold on paper, cool on dark).
 *  - [star]: the point/star ink.
 *  - [ink]: the single most contrast-reducing colour, the one the guard measures.
 *  - [mist]: a low horizon haze band for light faces (null on dark).
 */
@Immutable
data class FolioSignature(
    val seed: Int,
    val isDark: Boolean,
    val skyTop: Color,
    val skyBottom: Color,
    val nebulaCool: Color,
    val nebulaWarm: Color,
    val glow: Color,
    val star: Color,
    val ink: Color,
    val mist: Color?,
    val alphaCeiling: Float,
)

/**
 * A cheap, platform-stable integer hash (the well-known lowbias32 finaliser). Int
 * multiplication wraps deterministically, so a palette's sky reproduces across
 * processes and across Android/Desktop — the same reason [FolioShapeFamily] uses a
 * splitmix finaliser rather than `Random(seed)`. Public so the renderer can scatter
 * stars from the same stream without forking the arithmetic.
 */
internal fun signatureHash(x: Int): Int {
    var h = x
    h = h xor (h ushr 16); h *= 0x45d9f3b
    h = h xor (h ushr 16); h *= 0x45d9f3b
    h = h xor (h ushr 16)
    return h
}

/**
 * Solves a palette's [FolioSignature] from its colours only — the gradients are tuned
 * per theme from its own roles (accentDiscovery = the cool/sky hue, accentStreak = the
 * warm/sunset highlight, accentProgress = the secondary cloud), never fixed hex. Pure
 * and deterministic, so every theme — built-in, System or custom — gets its own sky.
 */
private fun signatureFor(colors: FolioColors, dark: Boolean): FolioSignature {
    val seed = signatureHash(colors.background.toArgb() * 31 + colors.primary.toArgb())
    val cool = colors.accentDiscovery       // sky / nebula blue-violet
    val warm = colors.accentStreak          // sunset gold / warm highlight
    val second = colors.accentProgress      // the counter cloud (cyan/blue)
    return if (dark) {
        // Deep-space: a clearly indigo top lifting out of the palette's ground toward
        // its cool accent, cool+second nebula clouds, a cool far-planet glow, near-white
        // stars. Pulled well off the background so the wash actually reads as a sky.
        // Nebula/glow hues are pushed to higher chroma (saturateG) so the field reads as
        // vivid cloud, not a faded wash — star/ink are left exactly as calibrated.
        val skyTop = saturateG(mixG(deepenG(colors.background, 0.34f), cool, 0.46f), 0.22f)
        val star = liftG(colors.surface, 0.88f)
        FolioSignature(
            seed = seed,
            isDark = true,
            skyTop = skyTop,
            skyBottom = colors.background,
            nebulaCool = saturateG(second, 0.45f),
            nebulaWarm = saturateG(cool, 0.45f),
            glow = saturateG(cool, 0.40f),
            star = star,
            ink = star, // brightest element: worst case for light body ink on a dark field
            mist = null,
            alphaCeiling = FOLIO_SIGNATURE_ALPHA_MAX,
        )
    } else {
        // Ethereal daylight — "Cosmic Dawn": a clear pale sky layered pale-blue → a hint
        // of soft violet at the top, a warm (gold) sunset glow kept localized, faint cool
        // points, and a low mist haze along the horizon. The top wash leans toward the
        // cool hue and takes a whisper of the secondary accent so the sky reads as a
        // layered atmosphere rather than one flat tint — still dominated by the (near
        // white) background, so it stays luminous and the field never darkens under ink.
        //
        // `star`/`ink` are deliberately left exactly as calibrated: `ink = star` is the
        // single darkest drawn element, the one FolioFieldModelTest measures, so the
        // layering above (all lighter than the star) cannot move a contrast ratio.
        val skyTop = mixG(mixG(colors.background, cool, 0.30f), second, 0.12f)
        val star = mixG(colors.onSurfaceVariant, cool, 0.50f)
        FolioSignature(
            seed = seed,
            isDark = false,
            skyTop = skyTop,
            skyBottom = liftG(colors.background, 0.05f),
            nebulaCool = saturateG(cool, 0.32f),
            nebulaWarm = saturateG(warm, 0.32f),
            glow = saturateG(warm, 0.26f),
            star = star,
            ink = star, // darkest element: worst case for dark body ink on a light field
            mist = liftG(mixG(colors.background, cool, 0.14f), 0.22f),
            alphaCeiling = FOLIO_SIGNATURE_ALPHA_MAX,
        )
    }
}

/**
 * Push a colour's chroma up (or down, for a negative [amount]) around its own luminance,
 * so the cosmic nebula/arc/planet hues read as vivid rather than faded. Luma-preserving
 * and clamped, so it brightens the *colour*, not the lightness. Used only for decorative
 * signature hues — never for `ink`/`star`, which the contrast guards measure.
 */
private fun saturateG(c: Color, amount: Float): Color {
    val l = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return Color(
        red = (l + (c.red - l) * (1f + amount)).coerceIn(0f, 1f),
        green = (l + (c.green - l) * (1f + amount)).coerceIn(0f, 1f),
        blue = (l + (c.blue - l) * (1f + amount)).coerceIn(0f, 1f),
        alpha = c.alpha,
    )
}

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

internal fun matchLuma(c: Color, target: Float): Color {
    val l = luminanceOf(c)
    if (l <= 0.0001f) return c
    val k = target / l
    return Color(
        red = (c.red * k).coerceIn(0f, 1f),
        green = (c.green * k).coerceIn(0f, 1f),
        blue = (c.blue * k).coerceIn(0f, 1f),
    )
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
 * Pulls [c] toward its own neutral and then puts back exactly the lightness the mix
 * cost, so only *hue* leaves the colour.
 *
 * [desaturate] holds [luminanceOf] — the cheap gamma-weighted sum — but every legibility
 * guard in the app reads `relativeLuminance`, the linearised WCAG one, and those two
 * disagree as soon as a colour has chroma in it: a violet's blue channel contributes far
 * more linear light than its gamma value suggests, so flattening it toward grey quietly
 * dims it. That is why desaturate could only ever take 0.30 out of the hero's fill before
 * the pane fell onto its own field and stopped reading as a surface at all.
 *
 * This decouples the two axes. The pull can then go as far as the design wants, because
 * the thing the guards measure — the pane's lightness against the room — is restored by
 * construction rather than traded away.
 *
 * The scaling is a local function rather than a top-level one: `ui.theme` already holds a
 * private `scaled` in `FolioFieldModel`, and a second copy of it here is the redeclaration
 * that has bitten this package before.
 */
internal fun neutralise(c: Color, amount: Float): Color {
    val target = relativeLuminance(c)
    val grey = luminanceOf(c)
    val flat = mixG(c, Color(grey, grey, grey), amount)

    fun at(k: Float): Color = Color(
        red = (flat.red * k).coerceIn(0f, 1f),
        green = (flat.green * k).coerceIn(0f, 1f),
        blue = (flat.blue * k).coerceIn(0f, 1f),
    )

    if (abs(relativeLuminance(flat) - target) < 1e-6) return flat
    // Linear luminance rises monotonically with a channel-wise scale, so the correction
    // is a bisection rather than a guess. 24 passes is well under a 1/255 step on any
    // channel, which is the finest thing an sRGB colour can express anyway.
    var lo = 0.5f
    var hi = 2.0f
    var mid = 1f
    repeat(24) {
        mid = (lo + hi) / 2f
        if (relativeLuminance(at(mid)) < target) lo = mid else hi = mid
    }
    return at((lo + hi) / 2f)
}

/**
 * How much chroma the base field gives up toward its own neutral, per polarity.
 * See the field block in [atmosphereFor]: this is what lets an injected cover hue
 * read as light instead of as a second brown, and it costs no luminance.
 */
private const val FIELD_NEUTRALITY_DARK = 0.35f
private const val FIELD_NEUTRALITY_LIGHT = 0.10f

/**
 * How much chroma the **hero's own fill** gives up, on a dark field.
 *
 * The field was neutralised at palette time to stop every room looking like the same
 * brown and to clear a place for the cover's hue. The pane never got the same treatment,
 * so it kept its palette's full chroma while the room behind it did not. On the dark
 * faces that is stark: `vaporwave`'s fill carries a channel spread of 57 where its own
 * field carries 33. The hero was roughly twice as loud as the room it stands in.
 *
 * The paper branch needs none of this, and the reason is worth keeping because it
 * explains why this went unnoticed for so long: `raisedFill` on light is
 * `liftG(surface, 0.55f)` — fifty-five percent white — which dilutes the chroma away
 * before any of this is needed. Dark has no such dilution, so dark is the only face
 * where the pane out-shouts its own theme.
 *
 * [neutralise] rather than [desaturate]: the lightness the pull costs is put straight
 * back, so this number is free to go as far as the design wants without the pane
 * collapsing onto the field and tripping `materialsAreVisuallyDistinguishable`.
 */
private const val PANE_NEUTRALITY_DARK = 0.80f

/**
 * Derives the atmosphere for [colors]. Pure and cheap — remembered per palette
 * at the theme root, so screens read it for free.
 */
fun atmosphereFor(colors: FolioColors): FolioAtmosphere {
    val bgLuma = luminanceOf(colors.background)
    val dark = bgLuma < 0.45f

    // The field is the page itself: a vertical wash from a slightly lifted top
    // (where the light is) to a deepened bottom.
    //
    // It is also neutralised first. A warm brown ground competes with the colour of
    // whatever is being read, and the winner was always the brown — which is most of
    // why every room looked the same. [desaturate] mixes toward a grey of the
    // colour's *own* lightness, and because the sRGB weights sum to one it is
    // luminance-preserving by construction, so pulling the base toward neutral moves
    // no contrast ratio at all: it only clears a place for the cover's hue to be read
    // as a light in the room rather than as more of the room's brown. Paper needs far
    // less of this than a dark field does, and gets a tenth of what the darks do.
    val fieldTop = if (dark) {
        desaturate(lerp(colors.background, colors.surface, 0.55f), FIELD_NEUTRALITY_DARK)
    } else {
        desaturate(lift(colors.background, 0.035f), FIELD_NEUTRALITY_LIGHT)
    }
    val fieldBottom = if (dark) {
        deepen(desaturate(colors.background, FIELD_NEUTRALITY_DARK), 0.30f)
    } else {
        desaturate(lerp(colors.background, colors.surfaceVariant, 0.45f), FIELD_NEUTRALITY_LIGHT)
    }

    // Colour pools come from the semantic accents, not primary, so a theme's
    // atmosphere carries the same hues its data visuals do (Rule 14).
    val pools = listOf(
        colors.accentProgress,
        colors.accentDiscovery,
        colors.accentStreak,
    )

    val signature = signatureFor(colors, dark)

    return FolioAtmosphere(
        isDark = dark,
        fieldTop = fieldTop,
        fieldBottom = fieldBottom,
        pools = pools,
        // Paper shows tint far more readily than a dark field absorbs it.
        poolAlpha = if (dark) 0.16f else 0.085f,
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
        fieldTintStrength = if (dark) 0.11f else 0.13f,
        // A shadow is absence of light, so it takes the palette's own deepest
        // neutral. Pure black on a warm cream page reads as a hole.
        shadowAmbient = if (dark) Color.Black else deepen(colors.onSurfaceVariant, 0.35f),
        shadowSpot = if (dark) Color.Black else deepen(colors.onSurface, 0.15f),
        shadowScale = if (dark) 0.55f else 1f,
        // Dark surfaces emit at the rim; light surfaces catch a white sheen.
        rimLight = if (dark) {
            lift(colors.surface, 0.30f).copy(alpha = 0.55f)
        } else {
            Color.White.copy(alpha = 0.85f)
        },
        rimShade = if (dark) {
            Color.Black.copy(alpha = 0.35f)
        } else {
            deepen(colors.outline, 0.10f).copy(alpha = 0.28f)
        },
        // Raised planes (the floating heroes) carry a primary-hue tint, so the
        // showcase surface reads as the theme's signature colour — not just a
        // brighter card. Luminance is pinned to the untinted lift, so ink keeps
        // its contrast.
        raisedFill = if (dark) {
            // Deeper and less chromatic than the paper branch: the hero sits over a
            // dark field it is also lighting, so a strong primary lean here stacked on
            // the lamp and the halo and read as a saturated violet slab. Sleeker wins
            // at this size.
            //
            // Then neutralised the way the field already was — and further than the
            // field, because this is the one surface large enough to be read as a
            // colour in its own right. `neutralise` takes the hue out and hands the
            // lightness straight back, so the pane keeps its separation from the room
            // instead of paying for quiet with flatness.
            neutralise(
                tintFill(mixG(colors.surface, colors.surfaceVariant, 0.18f), colors.primary, 0.02f),
                PANE_NEUTRALITY_DARK,
            )
        } else {
            tintFill(liftG(colors.surface, 0.55f), colors.primary, 0.12f)
        },
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
        paneAlpha = if (dark) 0.55f else 0.82f,
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
        sunkenFill = if (dark) {
            mixG(Color.Black, colors.tertiary, 0.10f).copy(alpha = 0.34f)
        } else {
            tintFill(mixG(colors.surfaceVariant, colors.background, 0.25f), colors.tertiary, 0.14f)
        },
        // Reader controls can sit directly above a page rendered by a native
        // surface. Keep the palette's surface character, but make the veil
        // effectively opaque so page text never competes with menu text.
        veilFill = if (dark) {
            deepen(colors.surface, 0.20f).copy(alpha = 0.97f)
        } else {
            lift(colors.surface, 0.35f).copy(alpha = 0.98f)
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
        barScrim = if (dark) {
            deepen(colors.background, 0.30f).copy(alpha = 0.46f)
        } else {
            lift(colors.surface, 0.72f).copy(alpha = 0.42f)
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
        barGlass = if (dark) {
            lerp(fieldTop, colors.surface, 0.35f).copy(alpha = 0.36f)
        } else {
            lift(fieldTop, 0.42f).copy(alpha = 0.40f)
        },
        hairline = if (dark) {
            lift(colors.outline, 0.05f).copy(alpha = 0.30f)
        } else {
            colors.outline.copy(alpha = 0.42f)
        },
        // The ink that sits straight on the page — most rows and lists in the app are
        // this colour on the field, with no surface between them.
        ink = colors.onBackground,
        signature = signature,
    )
}

/** The active atmosphere. Provided at the theme root; derived if absent. */
val FolioTheme.atmosphere: FolioAtmosphere
    @Composable
    get() {
        val colors = LocalFolioColors.current
        return remember(colors) { atmosphereFor(colors) }
    }

/**
 * [raisedFill] at [FolioAtmosphere.paneAlpha] — the hero's material. Everything else
 * about a raised surface stays: the shadow, the rim, the directional sheen all read
 * the same, so the card keeps its depth and only the fill starts letting the room
 * through. That is the difference between a panel pasted on a page and a pane sitting
 * in one.
 */
fun FolioAtmosphere.paneFill(): Color = raisedFill.copy(alpha = paneAlpha)
