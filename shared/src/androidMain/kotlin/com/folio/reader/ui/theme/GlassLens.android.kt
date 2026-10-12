package com.folio.reader.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.components.FolioGlassPlate
import com.folio.reader.ui.components.shapePath
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The lens, Android side. See [folioGlassLens] for what it is and why it is the term that
 * was missing; this file is how it reaches the room's real pixels.
 *
 * **The mechanism the previous attempt ruled out.** `GlassShader.android.kt` records the
 * verdict that killed this approach: a render effect can only see the subtree it is applied
 * to, therefore refraction of a *backdrop* is impossible and the backdrop must instead be
 * reproduced in closed form. The premise is right; the conclusion skips a step. You do not
 * apply the effect to the glass surface's subtree — you apply it to a scratch layer into
 * which the already-captured backdrop has been replayed. `createRuntimeShaderEffect` then
 * binds that replay to `uniform shader content`, and the shader is holding the room.
 *
 * Three frames of reference meet here, so they are named once:
 *
 *  - **screen** — what `positionOnScreen` reports, and the only space the surface and the
 *    capture share.
 *  - **plate** — the captured room's own layer space; its origin sits at the plate's
 *    `originOnScreen`.
 *  - **lens** — this scratch layer. The surface's top-left is at `(pad, pad)` so the shader
 *    can sample *past* the silhouette: refraction pulls content in from outside the edge,
 *    and without that margin the outermost band would sample transparent black and the
 *    effect would fringe dark exactly where it is supposed to be brightest.
 *
 * A screen point `S` is therefore at `S - surfaceOrigin + pad` in lens space, so replaying
 * the plate means translating it by `plateOrigin - surfaceOrigin + pad`.
 *
 * **Why it stays live.** The record block contains one `drawLayer` of the plate, which puts
 * a live reference to the room's RenderNode into this layer's display list rather than a
 * copy of its pixels. The room animates on the render thread and this layer follows it with
 * no recomposition and no re-record. The one thing that *must* re-record is a change of
 * `surfaceOrigin` — scrolling — which is why the position is written from
 * [onGloballyPositioned] into draw-phase state: the surface's own draw invalidates, nothing
 * above it does.
 */
@Composable
actual fun Modifier.folioGlassLens(
    plate: FolioGlassPlate?,
    shape: Shape,
    tint: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    thickness: Dp,
    refraction: Dp,
    dispersion: Float,
    specular: Float,
    depth: Float,
    intensity: Float,
    enabled: Boolean,
): Modifier {
    if (!enabled || plate == null || intensity <= 0.01f) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return folioGlassLensLayer(
        plate = plate,
        shape = shape,
        tint = tint,
        highlight = highlight,
        lightX = lightX,
        lightY = lightY,
        thickness = thickness,
        refraction = refraction,
        dispersion = dispersion,
        specular = specular,
        depth = depth,
        intensity = intensity,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.folioGlassLensLayer(
    plate: FolioGlassPlate,
    shape: Shape,
    tint: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    thickness: Dp,
    refraction: Dp,
    dispersion: Float,
    specular: Float,
    depth: Float,
    intensity: Float,
): Modifier {
    // A rejected AGSL has to be loud for the same reason the gel's is: the lens simply not
    // painting looks exactly like a successful retune of it, and "better" would quietly
    // become "dead". One warning, at the one place the decision is made.
    val shader = remember { runCatching { RuntimeShader(FOLIO_GLASS_LENS_AGSL) }.getOrNull() }
    if (shader == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            android.util.Log.w("folio-lens", "RuntimeShader rejected the lens AGSL — lens DISABLED")
        }
        return this
    }

    // The scratch layer, held for the life of the surface rather than created per frame.
    // Compose pools these, but a card that scrolls is re-drawing every frame and the
    // allocation would be the one cost this effect cannot justify.
    val graphicsContext = LocalGraphicsContext.current
    val lens = remember(graphicsContext) { graphicsContext.createGraphicsLayer() }
    DisposableEffect(lens) { onDispose { graphicsContext.releaseGraphicsLayer(lens) } }

    val len = sqrt(lightX * lightX + lightY * lightY)
    val lx = if (len < 1e-4f) 0f else lightX / len
    val ly = if (len < 1e-4f) -1f else lightY / len
    val body = if (tint.isSpecified) tint else Color.Transparent

    // Screen position, written in layout and read in draw. Not a composition read: a scroll
    // must cost this surface a redraw, never a recomposition of the tree under it.
    var surfaceOrigin by remember { mutableStateOf(Offset.Unspecified) }

    return this
        .onGloballyPositioned { surfaceOrigin = it.positionOnScreen() }
        .drawWithCache {
            val path = shapePath(shape)
            // The caller's real four corners. Guessing one radius from the box is what gave
            // the old gel a band that ended inside a 0/26/26/0 hero instead of on its edge.
            val radii = when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> with(outline.roundRect) {
                    floatArrayOf(
                        topLeftCornerRadius.x,
                        topRightCornerRadius.x,
                        bottomRightCornerRadius.x,
                        bottomLeftCornerRadius.x,
                    )
                }
                else -> FloatArray(4)
            }
            val bandPx = thickness.toPx()
            val reachPx = refraction.toPx()
            // Enough margin to cover the furthest-displaced channel, plus a pixel for the
            // bilinear tap. Below this the rim samples outside the replay and fringes dark.
            val pad = ceil(reachPx * (1f + dispersion)) + 1f
            val padPx = pad.toInt()
            // The surface's own pixel size, and the padded scratch size derived from it.
            // Kept as the guard rather than the padded size: the padding is always positive,
            // so a zero-size surface still produces a positive `lensW`/`lensH` and would
            // otherwise sail past the check and record a layer of pure margin.
            val surfaceW = size.width.toInt()
            val surfaceH = size.height.toInt()
            val lensW = surfaceW + padPx * 2
            val lensH = surfaceH + padPx * 2

            // ── the uniforms and the render effect, built here rather than per frame ──
            //
            // This block used to live inside `onDrawBehind`, with a note saying a cached
            // RenderEffect "would keep drawing the geometry this surface had before it was
            // scrolled or resized". Half of that is right and the half that matters is not.
            // A RenderEffect does capture the shader's uniform state at creation — but look
            // at what the uniforms actually are: `size`, `pad`, `radii`, the band and reach,
            // the light vector, the colours and the intensity. **Not one of them is the
            // surface's position.** Scrolling moves the *replay* (the `translate` in the
            // record block below), and the record is still re-taken every frame; it changes
            // no uniform at all. So the only input that can invalidate this is a resize or a
            // recomposition — which is exactly when `drawWithCache` rebuilds.
            //
            // Hoisting it saves, per lensed surface per frame: one `RenderEffect`
            // allocation, one `asComposeRenderEffect` wrapper, one `renderEffect` write to
            // the layer (which re-uploads it) and fourteen `setFloatUniform` JNI calls.
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("pad", pad, pad)
            shader.setFloatUniform("radii", radii[0], radii[1], radii[2], radii[3])
            // A bevel can never be more than a fraction of the thing it is the edge of.
            // Past roughly a third of the shorter side the two opposite bands meet and
            // the "edge" has become the whole object — which is how the 54dp nav capsule
            // and a 180dp card share one tuning instead of two. The throw is scaled by
            // the same factor so the fillet keeps its shape rather than getting steeper
            // on small surfaces.
            val limit = minOf(size.width, size.height) * 0.30f
            val band = bandPx.coerceAtMost(limit).coerceAtLeast(1f)
            val scale = if (bandPx <= 0f) 1f else (band / bandPx).coerceIn(0.35f, 1f)
            shader.setFloatUniform("bandPx", band)
            shader.setFloatUniform("reachPx", reachPx * scale)
            shader.setFloatUniform("chroma", dispersion)
            shader.setFloatUniform("light", lx, ly)
            shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
            shader.setFloatUniform("tint", body.red, body.green, body.blue)
            shader.setFloatUniform("tintAlpha", body.alpha)
            shader.setFloatUniform("gleam", specular)
            shader.setFloatUniform("wall", depth)
            shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
            // ── the room, frosted before it is bent ──
            //
            // The third part of the nested-shape fix, and the one the on-device
            // measurement pointed at. The body of a lensed pane is the Haze pass: the room
            // *blurred*. The band was the room **raw** — the plate replayed at full
            // sharpness and then split three ways by the dispersion. The ground plane
            // carries a fine halftone figure, so what the band actually showed was that
            // figure un-frosted and chromatically smeared: measured on the nav capsule as
            // a 6px-cell, 12px-pitch pattern swinging +-30 luma, inside a body that was
            // smooth to +-2. Two different *textures* meeting on a closed curve read as a
            // shape inside a shape no matter how gently the alpha between them ramps,
            // which is why every previous attempt to retune the ramp failed.
            //
            // The frost first shipped as a **chained** blur RenderEffect — blur the replay,
            // then lens it — and that is the one change that made the whole pass expensive:
            // a RenderEffect runs over the whole layer, so every lensed pane (the capsule,
            // every sheet, the hero) paid a second full-surface Gaussian per frame that the
            // lens's own body early-out could do nothing about, on top of Haze's. The
            // frost now runs **inside the shader**: a five-tap poisson disc at
            // [FolioGlassLens.frost] around each displaced sample, which costs only band
            // fragments that already survived the early-out — the body pays nothing, and a
            // pane's lens costs a fraction of the blur pass it replaces. Same intent:
            // dissolving the halftone texture so the band agrees with the frosted body,
            // nowhere near far enough to flatten the macro structure the refraction needs
            // something to bend. `content.eval` clamps at the replay's bounds, which is the
            // CLAMP behaviour the chain was chosen for.
            shader.setFloatUniform("frost", FolioGlassLens.frost.toPx())
            lens.renderEffect =
                RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()

            onDrawBehind {
                val origin = surfaceOrigin
                val plateLayer = plate.layer
                // Nothing measured, nothing captured, or a surface too small to hold a
                // bevel: draw nothing and let the fill and bevel underneath stand. This is
                // the first-frame state, and a half-formed lens is worse than none.
                if (!origin.isSpecified || plateLayer == null || !plate.ready) return@onDrawBehind
                if (surfaceW <= 0 || surfaceH <= 0) return@onDrawBehind

                val plateOrigin = plate.originOnScreen

                // Replay the room into lens space. One drawLayer: a live reference, not a
                // copy — see the file note. This *is* position-dependent, so it stays per
                // frame; the render effect above is not, so it does not.
                lens.record(IntSize(lensW, lensH)) {
                    translate(
                        left = plateOrigin.x - origin.x + pad,
                        top = plateOrigin.y - origin.y + pad,
                    ) {
                        drawLayer(plateLayer)
                    }
                }

                // Clipped to the caller's silhouette, then pulled back by the margin so lens
                // space lines up with the surface again. The shader's own alpha ramp keeps the
                // body clear, so the frost underneath is untouched.
                clipPath(path) {
                    translate(left = -pad, top = -pad) { drawLayer(lens) }
                }
            }
        }
}

/**
 * The lens shader.
 *
 * Two things it does that the app's earlier glass could not. It samples `content` — the
 * *actual* room, replayed by the Kotlin above — rather than a closed-form reproduction of
 * the field, so it bends whatever is genuinely behind the surface including the animated
 * shader field and the lamp. And its displacement follows a **quarter-round fillet**, not a
 * linear ramp: the slope `t / sqrt(1 - t²)` is near zero through the body and runs away in
 * the last pixels before the rim. That runaway is the entire read. A linear bend slides the
 * background sideways and still looks like a decal; a fillet *crowds* it against the edge,
 * which is the thing eyes recognise as thickness.
 *
 * Alpha is zero through the body on purpose. The lens owns the edge; the Haze frost owns the
 * middle. Clear-at-the-rim over frosted-through-the-centre is how a bevelled slab actually
 * behaves — and `main` now *returns* on that zero rather than computing its way to it, so the
 * pass genuinely costs the band it paints instead of merely looking as though it does.
 */
private const val FOLIO_GLASS_LENS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 pad;
uniform float4 radii;
uniform float bandPx;
uniform float reachPx;
uniform float chroma;
uniform float2 light;
uniform float3 highlight;
uniform float3 tint;
uniform float tintAlpha;
uniform float gleam;
uniform float wall;
uniform float intensity;
uniform float frost;

// Per-corner rounded-rect SDF, in coordinates centred on the surface. radii is
// (topLeft, topRight, bottomRight, bottomLeft) to match Compose's RoundRect.
float lensSdf(float2 c) {
    float r = (c.x < 0.0) ? ((c.y < 0.0) ? radii.x : radii.w)
                          : ((c.y < 0.0) ? radii.y : radii.z);
    float2 h = size * 0.5;
    r = clamp(r, 0.0, min(h.x, h.y));
    float2 q = abs(c) - h + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

// The room, frosted before it is bent — the in-shaper form of the frost that used to be
// a chained blur RenderEffect. That chain blurred the *whole layer* a second time every
// frame, which the band-only lens cannot help but pay in full; five taps per sample cost
// only the fragments that already survived the early-out in main. The poisson disc at
// the frost radius dissolves the halftone figure the frost was bought for while leaving
// the macro structure the refraction bends. Samples are premultiplied, so averaging
// them directly is the correct blur in that space.
half4 frostAt(float2 p) {
    if (frost < 0.5) { return content.eval(p); }
    float f = frost * 0.71;
    half4 c = content.eval(p);
    c += content.eval(p + float2( f,  f));
    c += content.eval(p + float2(-f,  f));
    c += content.eval(p + float2( f, -f));
    c += content.eval(p + float2(-f, -f));
    return c * 0.2;
}

half4 main(float2 fragCoord) {
    float2 c = fragCoord - pad - size * 0.5;
    float d = lensSdf(c);
    // Outside the silhouette the caller's clip has already taken over.
    if (d > 0.5) { return half4(0.0); }

    // ── the band's weight ──
    //
    // **This is the fix for "the nav capsule and the hero have a shape inside them".**
    // The weight used to be `smoothstep(0.02, 0.70, t)` over `t = 1 + d/bandPx`, and that
    // single expression drew two closed contours inside every surface it ran on:
    //
    //  1. **A plateau.** `smoothstep` saturates at `t >= 0.70`, so the outer 30% of the
    //     band was a flat alpha of 1 and the lens *replaced* the material there. The inner
    //     boundary of that plateau is an iso-contour of the rounded-rect SDF at
    //     `d = -0.30·band` — a closed rounded rect a few dp inside the silhouette, and on
    //     a shape whose corner radius is smaller than the inset (the 0/26/26/0 hero) a
    //     **square-cornered** one. Measured on device: a hard ring ~13dp in from the
    //     capsule's edge.
    //  2. **An end.** The ramp is exactly 0 at `d = -band`, which is a second contour at
    //     a second radius. `GLASS_RIM_FUNCTIONS` in GlassShader.android.kt already records
    //     why that is fatal and already fixed it for the film and the gel — "a ramp has an
    //     end, and an end is an edge" — but the lens, written later, reintroduced it.
    //
    // An exponential has neither: no saturation, so no plateau to bound, and no finite
    // support, so no radius at which the term stops. It is the same kernel the film uses,
    // which also means the two passes' falloffs coincide instead of crossing — two
    // contours at the same place are one edge, which is what a surface is allowed.
    //
    // The early-out survives, and it is now honest rather than structural: it fires where
    // the weight is already below one 8-bit level, so it removes work and not a visible
    // value. `0.30` puts that depth at ~1.7 bands, which still skips the majority of a
    // hero's interior.
    float a = exp(min(d, 0.0) / max(bandPx * 0.30, 1.0)) * intensity;
    if (a <= 0.0039) { return half4(0.0); }

    // 0 deep in the body, 1 on the rim. Shapes the *geometry* — the fillet's slope and
    // the Fresnel — but no longer the alpha, so its end at `d = -band` is no longer a
    // visible boundary: the terms it drives are already multiplied by a weight that is
    // still falling smoothly through it.
    float t = clamp(1.0 + d / max(bandPx, 1.0), 0.0, 1.0);

    // Outward normal, from the SDF itself rather than from the bounding box, so a
    // hero with two square corners and two round ones gets the right direction on
    // all four edges.
    float2 g = float2(
        lensSdf(c + float2(1.0, 0.0)) - lensSdf(c - float2(1.0, 0.0)),
        lensSdf(c + float2(0.0, 1.0)) - lensSdf(c - float2(0.0, 1.0))
    );
    float gl = length(g);
    float2 n = (gl < 1e-5) ? float2(0.0, 0.0) : g / gl;

    // The fillet. Held just short of 1 so the slope stays finite at the very rim.
    float ts = t * 0.985;
    float slope = ts / sqrt(max(1.0 - ts * ts, 1e-4));
    float bend = reachPx * clamp(slope / 6.0, 0.0, 1.0);

    float2 o = n * bend;

    // Dispersion costs two extra taps, and through most of the band `bend` is small
    // enough that the red and blue offsets land inside the same texel as the green one —
    // three samples of one colour. Paying for it only where the split is resolvable is
    // free visually and gives the wider weight above its cost back.
    float3 col;
    float cover;
    if (bend * chroma < 0.75) {
        half4 s = frostAt(fragCoord + o);
        cover = float(s.a);
        col = float3(s.rgb) / max(cover, 1e-3);
    } else {
        half4 sr = frostAt(fragCoord + o * (1.0 + chroma));
        half4 sg = frostAt(fragCoord + o);
        half4 sb = frostAt(fragCoord + o * (1.0 - chroma));
        // The replay is premultiplied; recover straight colour before lighting it, or
        // the rim darkens wherever the room is not fully opaque.
        cover = float(sg.a);
        col = float3(
            float(sr.r) / max(float(sr.a), 1e-3),
            float(sg.g) / max(float(sg.a), 1e-3),
            float(sb.b) / max(float(sb.a), 1e-3)
        );
    }

    float facing = clamp(dot(n, light) * 0.5 + 0.5, 0.0, 1.0);
    // §Metal: the fifth power (was a cube) tightens the catch into a specular band
    // rather than a broad bloom — metal reflects a light source as a narrow streak,
    // glass as a wash — and the deeper anti-sun floor below (0.10, was 0.16) is the
    // other half of the same read: a chamfered edge, dark where it turns away.
    float fres = t * t * t * t * t;

    // The far wall falls into the slab's own thickness on the anti-light edge...
    col = col * (1.0 - fres * (1.0 - facing) * wall);
    // ...and the lit edge catches. A tenth survives on the dark side so the rim is
    // continuous rather than a lit arc with two ends.
    col = col + highlight * (fres * mix(0.10, 1.0, facing) * gleam);
    // The glass's own body colour. **The caller now passes its whole plane, not a third
    // of it**, and that is the other half of the nested-shape fix. The old `* 0.35f` at
    // the call sites meant the lens composited a materially *different* surface from the
    // one painted underneath it — roughly a sixth of the plane over a sharp room, against
    // the full plane over a frosted one — so wherever the weight was high the band was a
    // visibly thinner, sharper material than the body, and the transition between them
    // was the ring. Carrying the full plane makes the band the *same* material as the
    // body, displaced: the lens then adds refraction and a Fresnel catch and subtracts
    // nothing, so there is no material step left for an edge to form on.
    col = mix(col, tint, tintAlpha * (1.0 - fres * 0.22));

    float af = a * cover;
    return half4(half3(clamp(col, 0.0, 1.0) * af), half(af));
}
"""
