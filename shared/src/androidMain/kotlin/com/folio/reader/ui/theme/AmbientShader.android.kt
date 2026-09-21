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

/** One orbit of the ambient blooms. Long enough to read as drift, not motion. */
private const val AMBIENT_SHADER_PERIOD_MS = 42_000L

/**
 * §17 / M7 — the app's animated ambient light film.
 *
 * A time-driven variant of [folioLiquidGlass]: two soft accent blooms orbit on a
 * slow phase and are painted as a [ShaderBrush] over the field, behind content.
 * Below API 33, with the preference off, or under reduce-motion the modifier
 * returns unchanged and the static [folioField] stands alone. The phase comes
 * from [rememberSlowPhases] (the house ~10 Hz clock), read in the draw phase, so
 * there is no per-frame recomposition and the frame loop idles between ticks.
 */
@Composable
actual fun Modifier.folioAmbientShader(
    colorA: Color,
    colorB: Color,
    intensity: Float,
    enabled: Boolean,
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    // Reduce-motion: a breathing light is exactly the kind of motion Rule 19 asks
    // to still. The static field already reads complete, so stand the film down.
    if (!rememberMotionEnabled()) return this
    return ambientShaderLayer(colorA, colorB, intensity)
}

/**
 * The animated AGSL film. Two low-frequency blooms whose centres orbit on a
 * single slow phase (`phase` in 0..2π). Amplitudes are tiny and the output is
 * premultiplied (Skia convention); the alpha tops out at [intensity], well under
 * the fill it sits over, so text drawn above the field keeps its contrast.
 */
private const val AMBIENT_AGSL = """
uniform float2 size;
uniform float3 colorA;
uniform float3 colorB;
uniform float phase;
uniform float intensity;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;

    // Two bloom centres drifting on slow, out-of-phase orbits. The radii are
    // large fractions of the field so a centre never resolves into a disc.
    float2 cA = float2(0.30 + 0.10 * sin(phase),        0.30 + 0.08 * cos(phase * 0.9));
    float2 cB = float2(0.72 + 0.09 * cos(phase * 1.1),  0.68 + 0.10 * sin(phase * 0.8));

    float gA = smoothstep(0.78, 0.0, distance(uv, cA));
    float gB = smoothstep(0.82, 0.0, distance(uv, cB));

    // Premultiplied: rgb is already scaled by the bloom weights, so rgb <= a.
    float3 rgb = (colorA * gA + colorB * gB) * intensity;
    float a = (gA + gB) * intensity;
    return half4(half3(rgb), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.ambientShaderLayer(
    colorA: Color,
    colorB: Color,
    intensity: Float,
): Modifier {
    val phases = rememberSlowPhases(listOf(AMBIENT_SHADER_PERIOD_MS))
    // A device that rejects the AGSL draws nothing rather than crashing — the same
    // visual outcome as the pre-shader fallback.
    val shader = remember { runCatching { RuntimeShader(AMBIENT_AGSL) }.getOrNull() }
        ?: return this
    return this.drawWithCache {
        shader.setFloatUniform("colorA", colorA.red, colorA.green, colorA.blue)
        shader.setFloatUniform("colorB", colorB.red, colorB.green, colorB.blue)
        shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
        val brush = ShaderBrush(shader)
        onDrawBehind {
            shader.setFloatUniform("size", size.width, size.height)
            // Draw-phase read of the slow clock: redraws on each 100ms tick, never
            // recomposes.
            shader.setFloatUniform("phase", phases.value.firstOrNull() ?: 0f)
            drawRect(brush)
        }
    }
}
