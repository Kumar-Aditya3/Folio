package com.folio.reader.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
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
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    return liquidGlassLayer(accent, highlight, lightX, lightY, intensity)
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
 *    nothing on the anti-sun side — lensing without a normal map.
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

    // Compose the three contributions into a premultiplied colour.
    float bodyW = body * 0.06;
    float specW = spec * 0.22;
    float edgeW = edge * 0.30;

    float3 rgb = accent * bodyW + highlight * (specW + edgeW);
    float a = (bodyW + specW + edgeW) * intensity;
    rgb = rgb * intensity;
    return half4(half3(rgb), half(a));
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
    return this.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        onDrawBehind { drawRect(brush) }
    }
}
