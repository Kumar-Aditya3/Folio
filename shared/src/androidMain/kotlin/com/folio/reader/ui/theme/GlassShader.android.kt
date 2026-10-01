package com.folio.reader.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.folio.reader.ui.components.LocalGlassCapabilities
import com.folio.reader.ui.components.shapePath
import kotlin.math.sqrt

/**
 * §13.7 / M7 — the one AGSL shader in the app.
 *
 * The film is drawn as a [ShaderBrush] behind the caller's content via
 * [drawWithCache] (draw-phase only, one pass, no recomposition), so it inherits
 * whatever clip the caller applied above it — a hero's own [FolioShapes.hero]
 * silhouette — and never paints over its text. Below API 33, with the preference
 * off, at zero intensity, or if the shader fails to compile on a given device,
 * the modifier returns unchanged and the §13.4 mesh + sheen fallback stands in.
 */
@Composable
actual fun Modifier.folioLiquidGlass(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float,
    enabled: Boolean,
    refractBackdrop: Boolean,
    focal: FolioPressFocal?,
    shape: Shape,
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    val withFilm = liquidGlassLayer(accent, highlight, lightX, lightY, intensity, focal)
    // `blur` is the app's existing verdict for "platform-composited and not a low-RAM
    // device", which is the budget a second full-surface shader pass needs.
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalFolioBackdrop.current
    // No room published means nothing to refract. The film is the whole effect there,
    // exactly as it was before the gel existed — desktop, previews, tests, and any
    // surface placed outside the app root.
    //
    // The gel is also now opt-in per call (`refractBackdrop`), default off. On a smooth
    // low-frequency backdrop — the hero's field is a vertical ramp plus three soft pools
    // and one lamp bloom — a lens rim has almost nothing to bend: the dispersion that is
    // meant to separate a lens from a gradient lands three samples on the same colour, so
    // the band only ever reads as a painted rectangle inset a fifth of the short side, not
    // as glass. Tuning its brightness, its Fresnel floor, its baseline and its chroma in
    // turn each fixed one face of that rectangle and surfaced the next, which is the loop
    // this flag ends. It stays available for a surface that genuinely has detail behind it.
    if (!refractBackdrop || !caps.blur || backdrop == null) return withFilm
    val len = sqrt(lightX * lightX + lightY * lightY)
    return withFilm.backdropGel(
        backdrop = backdrop,
        shape = shape,
        highlight = highlight,
        lx = if (len < 1e-4f) 0f else lightX / len,
        ly = if (len < 1e-4f) -1f else lightY / len,
        intensity = intensity.coerceIn(0f, 1f),
        focal = focal,
    )
}

/**
 * The refractive layer: the **room**, bent.
 *
 * A render effect can only ever see the subtree it is applied to, which is why the
 * previous attempt at this — [android.graphics.RenderEffect]'s
 * `createRuntimeShaderEffect(shader, "content")` on a `graphicsLayer` — could bend a
 * hero's own mesh and type and nothing else. Refraction of what is *behind* a
 * surface has to reproduce that thing, so this pass is a [ShaderBrush] drawn behind
 * the content and evaluates [LIQUID_GEL_AGSL] against the field's own closed form,
 * handed over by [FolioBackdrop]. The same helpers
 * [com.folio.reader.ui.components.folioField] draws with —
 * [fieldColors], [poolAlphaAt], [poolCenterAt] — feed the uniforms, so the pane
 * cannot disagree with the page it sits on.
 *
 * The card's position in the room is measured, not passed: [onGloballyPositioned]
 * writes a state read in the draw phase, so scrolling a pane across its backdrop
 * costs a redraw of that pane alone. Two known approximations come from that: the
 * §13.9 hero collapse puts a `graphicsLayer` scale and a 12dp translation on the
 * node, which layout position does not follow, so the refraction rides up to that
 * far during the collapse and lands true at rest; and whatever the field paints
 * *over* itself — §17's additive bloom film, at well under 1% — is not in the model
 * and so is not bent.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.backdropGel(
    backdrop: FolioBackdrop,
    shape: Shape,
    highlight: Color,
    lx: Float,
    ly: Float,
    intensity: Float,
    focal: FolioPressFocal?,
): Modifier {
    val shader = runCatching { RuntimeShader(LIQUID_GEL_AGSL) }.getOrNull()
    if (shader == null) {
        // A rejected AGSL switches the gel off silently, and the rim band simply
        // vanishing looks *exactly* like a successful retune of it. Say so where a
        // device log can find it, so "better" can never be mistaken for "dead".
        androidx.compose.runtime.LaunchedEffect(Unit) {
            android.util.Log.w("folio-gel", "RuntimeShader rejected the gel AGSL — gel DISABLED")
        }
        return this
    }
    shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
    // Declared *and* used in the AGSL, or SkSL strips it from the active uniform list
    // and this setter throws at draw time rather than compile time.
    shader.setFloatUniform("highlightAlpha", highlight.alpha)
    shader.setFloatUniform("light", lx, ly)
    shader.setFloatUniform("intensity", intensity)
    // The pane's own chroma envelope. The refracted rim reproduces the room's accent
    // pools at the pool's *own* saturation, so a teal `accentProgress` pool behind the
    // edge painted a frame several times more chromatic than the pane it borders —
    // "the colour is outside the inner rectangle". The pane's fill was already
    // neutralised at palette time (FolioAtmosphere.raisedFill), and `tameCover` reins a
    // jacket to this exact ceiling; the rim was the one surface that borrowed the
    // room's colour without it. Same `chromaSpread` and envelope so there is one
    // definition of "too saturated", not a second guessed in the shader. Read
    // genuinely in `main`, so SkSL cannot strip it the way build 128's `highlightAlpha`
    // was stripped.
    shader.setFloatUniform(
        "rimChromaCeiling",
        chromaSpread(backdrop.atmos.raisedFill) * COVER_CHROMA_ENVELOPE,
    )
    val brush = ShaderBrush(shader)
    // Layout position in root pixels. Draw-phase state: a scroll invalidates this
    // pane's draw, never the tree under it.
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { coordinates -> origin = coordinates.positionInRoot() }
        .then(if (focal != null) Modifier.folioPressFocalOrigin(focal) else Modifier)
        .drawWithCache {
            val path = shapePath(shape)
            // The rim band has to follow *this* silhouette. Approximating it with one
            // radius guessed from the box gave a hero whose real corners are
            // 0/26/26/0 a rounded-rect band that ended well inside the card, and the
            // edge of that band is the dark rectangle this used to draw. The outline
            // already knows all four corners, and it allocates, so read it here in the
            // cache rather than per frame.
            val radii = when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> with(outline.roundRect) {
                    floatArrayOf(
                        topLeftCornerRadius.x, topRightCornerRadius.x,
                        bottomRightCornerRadius.x, bottomLeftCornerRadius.x,
                    )
                }
                else -> FloatArray(4) { minOf(size.width, size.height) * 0.12f }
            }
            onDrawBehind {
                val field = backdrop.fieldSize.value
                val fw = field.width.toFloat()
                val fh = field.height.toFloat()
                // Unmeasured room: there is no coordinate space to bend, so draw the
                // film alone rather than guess at a geometry.
                if (fw <= 0f || fh <= 0f) return@onDrawBehind
                val colors = backdrop.colors()
                val (driftX, driftY) = backdrop.drift()
                val drift = fieldDrift(backdrop.shift)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("fieldSize", fw, fh)
                shader.setFloatUniform("origin", origin.x, origin.y)
                shader.setFloatUniform("fieldTop", colors.top.red, colors.top.green, colors.top.blue)
                shader.setFloatUniform(
                    "fieldBottom",
                    colors.bottom.red, colors.bottom.green, colors.bottom.blue,
                )
                // Three pools, as three fixed uniform pairs rather than an array:
                // SkSL will not index a uniform array dynamically, and three is the
                // atmosphere's own ceiling on how many pools a field may hold.
                DAYLIGHT_POOLS.forEachIndexed { i, pool ->
                    val color = colors.poolColors.getOrNull(pool.index)
                    if (color == null) {
                        shader.setFloatUniform("poolColor$i", 0f, 0f, 0f)
                        shader.setFloatUniform("poolGeom$i", 0f, 0f, 0f, 0f)
                        return@forEachIndexed
                    }
                    val alpha = poolAlphaAt(colors.poolAlpha, pool.sideX, backdrop.shift)
                    val (cx, cy) = poolCenterAt(pool, drift, driftX, driftY)
                    shader.setFloatUniform("poolColor$i", color.red, color.green, color.blue)
                    // Centre and radius in the room's own pixels, exactly as the
                    // field places them.
                    shader.setFloatUniform("poolGeom$i", cx * fw, cy * fh, pool.fr * fw, alpha)
                }
                // The surface's own four corners, so the rim band lands on the
                // silhouette rather than inside it.
                shader.setFloatUniform(
                    "radii", radii[0], radii[1], radii[2], radii[3]
                )
                // The lamp, at rest. Bending a light is the whole reason the gel has
                // anything worth bending: a bare gradient looks the same displaced.
                val lamp = backdrop.lamp?.let { holder ->
                    lampOf(
                        atmos = backdrop.atmos,
                        authored = holder.authored,
                        themeColor = holder.themeColor,
                        cover = holder.light.value,
                        at = holder.at.value,
                        field = field,
                        phase = 0f,
                    )
                }
                if (lamp == null) {
                    shader.setFloatUniform("lampColor", 0f, 0f, 0f)
                    shader.setFloatUniform("lampGeom", 0f, 0f, 0f, 0f)
                } else {
                    shader.setFloatUniform(
                        "lampColor", lamp.color.red, lamp.color.green, lamp.color.blue
                    )
                    shader.setFloatUniform(
                        "lampGeom",
                        lamp.center.x, lamp.center.y, lamp.radius, lamp.alpha,
                    )
                }
                val at = focal?.pixelPoint()
                if (at == null) {
                    shader.setFloatUniform("focal", size.width * 0.5f, size.height * 0.5f)
                    shader.setFloatUniform("focalStrength", 0f)
                } else {
                    shader.setFloatUniform("focal", at.x, at.y)
                    shader.setFloatUniform("focalStrength", focal.strength.value.coerceIn(0f, 1f))
                }
                clipPath(path) { drawRect(brush) }
            }
        }
}

/**
 * AGSL source. A fixed virtual light (uniform `light`), no time term:
 *
 *  - **body**: two large, slow sinusoidal caustics in UV space give the fill a
 *    faint refractive shimmer — the "thick glass has structure" cue — at very low
 *    amplitude so it reads as material, never as a pattern;
 *  - **specular**: a soft radial hot-spot placed on the sun-facing side, so the
 *    highlight sits where every other material's catch does;
 *  - **edge**: a rim term that brightens the sun-facing border and falls to
 *    nothing on the anti-sun side — lensing without a normal map;
 *  - **focal**: the same specular hot-spot, moved under the finger while a press is
 *    held. It is deliberately *not* gated on `facing` the way the daylight spot is:
 *    `facing` is zero across the anti-sun half of the surface, so a thumb landing
 *    there would catch nothing, and the whole point of the term is that the light
 *    answers where the finger actually is.
 *
 * Output is premultiplied (Skia convention). At rest the film's ceiling is the body
 * term plus the light-catch terms scaled by the token's own alpha — about 0.20 of a
 * dark face's coverage and 0.27 of a paper one, which is the polarity the `rimLight`
 * token already encodes and that the uniform used to throw away. Text drawn over the
 * hero keeps its contrast either way; the legibility guard holds the veto.
 * Colours arrive as uniforms from the caller's accent roles.
 */
private const val LIQUID_GLASS_AGSL = """
uniform float2 size;
uniform float3 accent;
uniform float3 highlight;
uniform float highlightAlpha;
uniform float2 light;
uniform float intensity;
uniform float2 focal;
uniform float focalStrength;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;

    // CAVEAT: highlightAlpha MUST be referenced before any dead-code elimination passes.
    // This line ensures the uniform isn't stripped by SkSL's static analysis.
    float _alphaCheck = highlightAlpha;

    // Caustic body: two low-frequency waves crossing, tiny amplitude. This is
    // the refractive "structure" of thick glass, not a visible ripple.
    float body = 0.5
        + 0.5 * sin(uv.x * 6.2831853 + uv.y * 3.5)
        * cos(uv.y * 4.7123889 - uv.x * 2.3);
    body = body * body; // bias dark so the sheen only blooms in patches

    // Direction from centre, so the specular can sit on the sun-facing side.
    float2 toward = uv - float2(0.5, 0.5);
    // light points *from* the sun across the surface (y up-negative); the catch
    // is where the surface faces into it, i.e. opposite the light vector.
    float facing = clamp(dot(normalize(toward + float2(0.0001, 0.0001)), -light), 0.0, 1.0);

    // Specular hot-spot: a soft radial on the sun-facing side, tightened by facing.
    float2 spot = float2(0.5, 0.5) - light * 0.32;
    float d = distance(uv, spot);
    float spec = smoothstep(0.42, 0.0, d) * facing;

    // No rectangular edge catch. The old `border`/`edge` pair lit the outer band of the
    // *bounding box* (a chebyshev metric on uv), not the hero's rounded, asymmetric
    // silhouette — so on every non-rectangular surface it painted a bright inset
    // rectangle, the "box within the card" the film was accused of. The real rim is
    // already drawn shape-aware by folioThemeRim and the folioRaised border, so the film
    // keeps only its shape-neutral terms: the caustic body, the radial specular and the
    // press focal. Reintroducing an edge means giving the film the silhouette (an SDF
    // like the gel's), not a box.

    // Press focal: the catch follows the finger. `focalStrength` is the press
    // spring, so at rest this term is exactly zero and the film is the one that
    // shipped before the finger existed.
    float focus = smoothstep(0.34, 0.0, distance(uv, focal)) * focalStrength;

    // Compose the contributions into a premultiplied colour.
    float bodyW = body * 0.06;
    float specW = spec * 0.11;
    float focusW = focus * 0.24;
    float hlW = (specW + focusW) * highlightAlpha;
    // Force use of _alphaCheck to prevent SkSL stripping - multiply result by it once.
    hlW = hlW * (_alphaCheck / max(0.001, _alphaCheck));

    float3 rgb = accent * bodyW + highlight * hlW;
    float a = (bodyW + hlW) * intensity;
    rgb = rgb * intensity;
    return half4(half3(rgb), half(a));
}
"""

/**
 * The gel: the **room**, bent.
 *
 * [fieldAt] is the page's own closed form — the vertical ramp and the three drifting
 * pools, in the same order and by the same arithmetic `folioField` draws with, so a
 * pane shows the room rather than a likeness of it. `mix` per channel is what
 * [fieldColorAt] does, and a radial gradient that fades a colour to transparent
 * composites identically to a linear alpha falloff of that colour, which is why the
 * pool term is one `mix` and not a two-stop ramp.
 *
 * - **lens** — a rounded-rect signed distance field gives the outward normal and the
 *   rim weight; sample points move toward the centre near the edge, so the outline
 *   rolls over like the lip of a thick pane instead of stopping.
 * - **dispersion** — R, G and B are pulled at three different depths, which is the
 *   single cue that separates a lens from a gradient.
 * - **Fresnel** — the rim brightens where the edge faces away from the viewer and
 *   toward the room's light, on the same `light` vector the film uses.
 * - **gel highlight** — the press focal, now riding a refractive surface.
 *
 * Alpha is the rim weight, so the gel paints only its own border: at the centre of a
 * pane the lens term is 0, `fieldAt` returns the backdrop exactly as the page already
 * drew it there, and repainting it would be wasted work hiding the surface's own
 * fill. Fading to nothing a fifth of the short side inside is what keeps a hero a
 * hero; widening the band is one constant away if the pane ever deserves more of it.
 */
private const val LIQUID_GEL_AGSL = """
uniform float2 size;
uniform float2 fieldSize;
uniform float2 origin;
uniform float3 fieldTop;
uniform float3 fieldBottom;
uniform float3 poolColor0;
uniform float4 poolGeom0;
uniform float3 poolColor1;
uniform float4 poolGeom1;
uniform float3 poolColor2;
uniform float4 poolGeom2;
// The lamp: colour, then centre.xy / radius / alpha.
uniform float3 lampColor;
uniform float4 lampGeom;
uniform float3 highlight;
uniform float highlightAlpha;
uniform float2 light;
// The most chroma the refracted rim may carry, as a max-minus-min channel spread.
// Set from the pane's own neutralised fill, so the band cannot out-saturate the
// surface it edges. Used in main(); a bare declaration would be stripped by SkSL.
uniform float rimChromaCeiling;
// The surface's own four corners: x = topStart, y = topEnd, z = bottomEnd,
// w = bottomStart. A hero bleeds square on one edge and rounds on the other, so
// one radius cannot describe it and the band ended up inside the card.
uniform float4 radii;
uniform float intensity;
uniform float2 focal;
uniform float focalStrength;

// 0.26: the rim is what makes the hero read as an outlined box, so the gradient has
// to be long and shallow rather than short and strong. A narrow band at high
// coverage draws an edge; a wide one at low coverage reads as thickness.
const float GEL_COVERAGE = 0.26;

float3 addPool(float3 col, float4 geom, float2 p, float3 over) {
    // geom is centre.xy, radius, alpha — all in the room's own pixels.
    if (geom.w <= 0.0 || geom.z <= 0.0) return over;
    float d = distance(p, geom.xy) / geom.z;
    if (d >= 1.0) return over;
    return mix(over, col, geom.w * (1.0 - d));
}

float lampFalloff(float d) {
    // Mirrors FolioLamp.lampFalloff: flat leaving the source, flat arriving, so the
    // glow has no limb to bend and no edge to read as an object.
    if (d >= 1.0) return 0.0;
    if (d <= 0.0) return 1.0;
    float s = 1.0 - d;
    return s * s * (3.0 - 2.0 * s);
}

// The room, in the room's own pixels.
//
// This used to bend the pane's own material, and that was right while the hero was
// 86% opaque: an opaque card has no business showing the wallpaper at its edge, so
// the rim band was pure import and read as a frame. The pane is now genuinely
// translucent, which is the only condition under which refracting the room is a
// true statement about the surface — the two settings are a package deal, and
// changing one without the other reproduces the brown band this replaced.
float3 fieldAt(float2 p) {
    float3 c = mix(fieldTop, fieldBottom, clamp(p.y / fieldSize.y, 0.0, 1.0));
    c = addPool(poolColor0, poolGeom0, p, c);
    c = addPool(poolColor1, poolGeom1, p, c);
    c = addPool(poolColor2, poolGeom2, p, c);
    // The lamp, as the Canvas layer draws it: one bloom and nothing else, so the pane
    // bends what the page actually shows.
    if (lampGeom.w > 0.0 && lampGeom.z > 0.0) {
        float d = distance(p, lampGeom.xy) / lampGeom.z;
        c = mix(c, lampColor, lampGeom.w * lampFalloff(d));
    }
    return c;
}

half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 v = xy - c;
    float2 n = normalize(v + float2(0.0001, 0.0001));
    // The corner this fragment belongs to, so an asymmetric silhouette gets an
    // asymmetric band. Screen y grows downward, so y < 0 is the top pair.
    float r = (v.x < 0.0)
        ? ((v.y < 0.0) ? radii.x : radii.w)
        : ((v.y < 0.0) ? radii.y : radii.z);
    r = min(r, min(c.x, c.y));
    float2 q = abs(v) - (c - r);
    float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;

    // Rim weight: 1 at the edge, 0 `band` inside, 0 outside the shape. It is both the
    // lens depth and the opacity, so the pane only paints where it actually bends.
    float band = min(size.x, size.y) * 0.20;
    float rim = clamp(1.0 + d / band, 0.0, 1.0) * step(d, 0.0);
    float lens = 15.0 * rim;

    // The card's own pixels are not the room's: the taps have to land where the page
    // is, which is the fragment plus however far the pane sits into the field.
    float2 p = xy + origin;
    float3 rr = fieldAt(p - n * (lens * 1.12));
    float3 gg = fieldAt(p - n * lens);
    float3 bb = fieldAt(p - n * (lens * 0.88));

    // The dispersed refraction of the room. R, G and B are pulled at three depths, so
    // a saturated room pool sitting behind the rim arrives here at the pool's *own*
    // chroma — a frame louder than the pane it edges, the colour that read as "outside
    // the inner rectangle". Rein it to the pane's envelope exactly as tameCover reins a
    // jacket: pull toward the colour's own grey until its spread is within the ceiling,
    // hue direction and (mean) lightness untouched, only intensity moving. min(1.0, …)
    // so a quiet refraction is only ever pulled under the ceiling, never lifted toward
    // it, and the >0 guard keeps a neutral sample off the divide. The dispersion that
    // separates a lens from a gradient survives — the three depths still disagree, they
    // are merely no longer allowed to disagree in a saturated colour.
    float3 room = float3(rr.r, gg.g, bb.b);
    float spread = max(room.r, max(room.g, room.b)) - min(room.r, min(room.g, room.b));
    float grey = (room.r + room.g + room.b) / 3.0;
    float k = (spread > 1e-5) ? min(1.0, rimChromaCeiling / spread) : 1.0;
    room = mix(float3(grey, grey, grey), room, k);

    float facing = clamp(dot(n, -light), 0.0, 1.0);
    // Raised floor (0.55 vs 0.35) so the gel never dims below a baseline, preventing
    // dark field taps from producing edge dimming when the sun-side faces away.
    float fres = pow(rim, 1.6) * (0.55 + 0.45 * facing) * 0.34;
    float foc = smoothstep(band * 1.8, 0.0, distance(xy, focal)) * focalStrength * 0.30;
    // Band baseline: a rim-weighted highlight that does not go through `facing`, so
    // the rim cannot darken when the field taps land below the card face. Tuned
    // alongside the Fresnel-floor raise (0.35 -> 0.55): the floor lifts the
    // anti-sun side, the baseline lifts everywhere along the band so a chromatic
    // cover on a dark pane can no longer paint a darker rim than its centre.
    float bandBase = rim * 0.12;

    // The same dropped-alpha repair the film got: the Fresnel and focal catches are
    // laid over the bent room, and on a dark face `highlight` is a 55% token being
    // spent as if it were solid. The band baseline shares the highlightAlpha chain
    // so a refactor of the token's alpha carries through.
    float3 rgb = room +
        highlight * ((fres + foc + bandBase) * highlightAlpha * intensity);
    return half4(half3(rgb), half(rim * GEL_COVERAGE));
}
"""

/** One-shot so a dead film logs once per process instead of once per recomposition. */
private var filmDisabledWarned = false

/**
 * Builds the shader once per (size, colour, light) identity inside the draw
 * cache, wraps it in a [ShaderBrush] and paints it behind the content. Guarded:
 * a device that rejects the AGSL (a vendor SkSL quirk) draws nothing rather than
 * crashing, which is the same visual outcome as the pre-shader fallback.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.liquidGlassLayer(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float,
    focal: FolioPressFocal?,
): Modifier {
    val shader = runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull()
    if (shader == null) {
        // Same trap the gel logs against: a rejected AGSL switches the film off
        // silently, and a sheen that has stopped drawing is indistinguishable by eye
        // from one that has been successfully quietened. Not a `LaunchedEffect` —
        // this is not a composable, so the one-shot lives in a process flag.
        if (!filmDisabledWarned) {
            filmDisabledWarned = true
            android.util.Log.w("folio-gel", "RuntimeShader rejected the film AGSL — film DISABLED")
        }
        return this
    }
    // Normalise the light vector defensively; a zero vector would divide by zero
    // in the shader's normalize(). Neutral daylight is straight-down (0, -1).
    val len = sqrt(lightX * lightX + lightY * lightY)
    val lx = if (len < 1e-4f) 0f else lightX / len
    val ly = if (len < 1e-4f) -1f else lightY / len
    shader.setFloatUniform("accent", accent.red, accent.green, accent.blue)
    shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
    // The token's alpha is part of the token. Handing over only the channels is what
    // let the film spend a 55% light-catch at full strength on every dark palette.
    shader.setFloatUniform("highlightAlpha", highlight.alpha)
    shader.setFloatUniform("light", lx, ly)
    shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
    val brush = ShaderBrush(shader)
    val base = if (focal != null) this.folioPressFocalOrigin(focal) else this
    return base.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        onDrawBehind {
            // Draw-phase only. Reading the press here rather than capturing it in the
            // cache block keeps it out of the cache's identity, which would otherwise
            // rebuild the brush and re-set all four colour uniforms on every frame of
            // the settle spring. At rest the strength is 0, so this is exactly the
            // film that shipped before the finger existed.
            val uv = focal?.normalized(Offset(size.width, size.height))
            if (uv == null) {
                shader.setFloatUniform("focal", 0.5f, 0.5f)
                shader.setFloatUniform("focalStrength", 0f)
            } else {
                shader.setFloatUniform("focal", uv.x, uv.y)
                shader.setFloatUniform("focalStrength", focal.strength.value.coerceIn(0f, 1f))
            }
            drawRect(brush)
        }
    }
}
