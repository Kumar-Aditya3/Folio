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
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.components.LocalGlassCapabilities
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
    focal: FolioPressFocal?,
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    val withFilm = liquidGlassLayer(accent, highlight, lightX, lightY, intensity, focal)
    // `blur` is the app's existing verdict for "platform-composited and not a low-RAM
    // device", which is exactly the budget a per-frame render effect needs: the layer
    // re-runs the shader whenever anything in it redraws, and the hero's rim ticks at
    // 30 Hz. Anything weaker keeps the additive film it has always had.
    val caps = LocalGlassCapabilities.current
    return if (caps.blur) {
        val len = sqrt(lightX * lightX + lightY * lightY)
        withFilm.liquidGel(
            highlight = highlight,
            lx = if (len < 1e-4f) 0f else lightX / len,
            ly = if (len < 1e-4f) -1f else lightY / len,
            intensity = intensity.coerceIn(0f, 1f),
            focal = focal,
        )
    } else {
        withFilm
    }
}

/**
 * The refractive layer. Binds [LIQUID_GEL_AGSL] to this node's own composited layer
 * through `RenderEffect.createRuntimeShaderEffect`, so the surface bends what it
 * contains at its rim — the one term that separates glass from a tinted panel.
 *
 * `size` and the focal arrive in the layer block, which is a render-phase read: a
 * press invalidates this layer and nothing above it. Any device or driver that
 * refuses the effect leaves the receiver untouched, which is today's look.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.liquidGel(
    highlight: Color,
    lx: Float,
    ly: Float,
    intensity: Float,
    focal: FolioPressFocal?,
): Modifier {
    val shader = runCatching { RuntimeShader(LIQUID_GEL_AGSL) }.getOrNull() ?: return this
    shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
    shader.setFloatUniform("light", lx, ly)
    shader.setFloatUniform("intensity", intensity)
    val effect: RenderEffect? = runCatching {
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            ?.asComposeRenderEffect()
    }.getOrNull() ?: return this
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    return this
        .onSizeChanged { layerSize = it }
        .then(
            if (focal != null) Modifier.folioPressFocalOrigin(focal) else Modifier
        )
        .graphicsLayer {
            val w = layerSize.width
            val h = layerSize.height
            if (w <= 0 || h <= 0) {
                renderEffect = null
                return@graphicsLayer
            }
            shader.setFloatUniform("size", w.toFloat(), h.toFloat())
            // An approximation of an asymmetric shape: the hero plate is 34/20/34/8 dp.
            // The SDF only needs a plausible radius for the rim weight and normal.
            shader.setFloatUniform("radius", minOf(w, h) * 0.12f)
            val at = focal?.pixelPoint()
            if (at == null) {
                shader.setFloatUniform("focal", w * 0.5f, h * 0.5f)
                shader.setFloatUniform("focalStrength", 0f)
            } else {
                shader.setFloatUniform("focal", at.x, at.y)
                shader.setFloatUniform("focalStrength", focal.strength.value.coerceIn(0f, 1f))
            }
            renderEffect = effect
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
 * Output is premultiplied (Skia convention). All amplitudes are small: the film
 * tops out well under the fill it sits behind, so text drawn over the hero keeps
 * its contrast. Colours arrive as uniforms from the caller's accent roles.
 */
private const val LIQUID_GLASS_AGSL = """
uniform float2 size;
uniform float3 accent;
uniform float3 highlight;
uniform float2 light;
uniform float intensity;
uniform float2 focal;
uniform float focalStrength;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;

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

    // Edge catch: brightest at the sun-facing border, gone at the far one.
    // `border` is 0.5 at the centre and 1.0 at any edge of the box.
    float border = max(max(uv.x, 1.0 - uv.x), max(uv.y, 1.0 - uv.y));
    float edge = smoothstep(0.86, 1.0, border) * facing;

    // Press focal: the catch follows the finger. `focalStrength` is the press
    // spring, so at rest this term is exactly zero and the film is the one that
    // shipped before the finger existed.
    float focus = smoothstep(0.34, 0.0, distance(uv, focal)) * focalStrength;

    // Compose the contributions into a premultiplied colour.
    float bodyW = body * 0.06;
    float specW = spec * 0.22;
    float edgeW = edge * 0.30;
    float focusW = focus * 0.24;

    float3 rgb = accent * bodyW + highlight * (specW + edgeW + focusW);
    float a = (bodyW + specW + edgeW + focusW) * intensity;
    rgb = rgb * intensity;
    return half4(half3(rgb), half(a));
}
"""

/**
 * The gel: the surface's own layer, bent. Where [LIQUID_GLASS_AGSL] paints a film
 * *behind* content, this is a [android.graphics.RenderEffect] child shader bound to
 * the composited layer itself, so it can displace what it draws rather than add to it.
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
 * Every term is gated on `step(d, 0.0)`, so nothing outside the shape is displaced or
 * brightened and the layer cannot grow a fringe. Alpha is taken from the centre
 * sample, so the effect preserves the layer's own silhouette.
 */
private const val LIQUID_GEL_AGSL = """
uniform shader content;
uniform float2 size;
uniform float3 highlight;
uniform float2 light;
uniform float radius;
uniform float intensity;
uniform float2 focal;
uniform float focalStrength;

half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 v = xy - c;
    float2 n = normalize(v + float2(0.0001, 0.0001));
    float r = min(radius, min(c.x, c.y));
    float2 q = abs(v) - (c - r);
    float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;

    // Rim weight: 1 at the edge, 0 `band` inside, 0 outside the shape.
    float band = min(size.x, size.y) * 0.22;
    float rim = clamp(1.0 + d / band, 0.0, 1.0) * step(d, 0.0);
    float lens = 15.0 * rim;

    half4 rr = content.eval(xy - n * (lens * 1.12));
    half4 gg = content.eval(xy - n * lens);
    half4 bb = content.eval(xy - n * (lens * 0.88));

    float facing = clamp(dot(n, -light), 0.0, 1.0);
    float fres = pow(rim, 1.6) * (0.35 + 0.65 * facing) * 0.34;
    float foc = smoothstep(band * 1.8, 0.0, distance(xy, focal)) * focalStrength * 0.30;

    half3 rgb = half3(rr.r, gg.g, bb.b) +
        half3(highlight * ((fres + foc) * intensity)) * max(gg.a, 0.0);
    return half4(rgb, gg.a);
}
"""

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
    val shader = runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull() ?: return this
    // Normalise the light vector defensively; a zero vector would divide by zero
    // in the shader's normalize(). Neutral daylight is straight-down (0, -1).
    val len = sqrt(lightX * lightX + lightY * lightY)
    val lx = if (len < 1e-4f) 0f else lightX / len
    val ly = if (len < 1e-4f) -1f else lightY / len
    shader.setFloatUniform("accent", accent.red, accent.green, accent.blue)
    shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
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
