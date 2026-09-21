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

/** One slow drift of the caustics — long enough to read as water, not motion. */
private const val ATLAS_SHADER_PERIOD_MS = 38_000L

/**
 * The animated caustic sheen over the Atlas. A time-driven variant of [folioLiquidGlass]: two
 * interfering wave fields, sampled on the house slow clock and read in the draw phase, so there
 * is no per-frame recomposition. Below API 33, with the preference off, or under reduce-motion
 * the modifier returns unchanged and the Compose canvas stands alone.
 */
@Composable
actual fun Modifier.folioAtlasShader(
    sea: Color,
    light: Color,
    intensity: Float,
    enabled: Boolean,
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    if (!rememberMotionEnabled()) return this
    return atlasShaderLayer(sea, light, intensity)
}

private const val ATLAS_AGSL = """
uniform float2 size;
uniform float3 sea;
uniform float3 light;
uniform float phase;
uniform float intensity;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    // Two low-frequency interfering wave fields make a slow caustic net.
    float w1 = sin((uv.x * 6.0 + uv.y * 3.0) + phase);
    float w2 = sin((uv.x * 2.5 - uv.y * 5.0) - phase * 0.8);
    float caustic = (w1 * w2) * 0.5 + 0.5; // 0..1
    caustic = smoothstep(0.55, 1.0, caustic); // keep only the bright crests

    float3 rgb = (sea * 0.4 + light * caustic) * intensity;
    float a = (0.25 + 0.75 * caustic) * intensity;
    // Premultiplied (Skia convention): rgb already scaled by the weights, so rgb <= a.
    return half4(half3(rgb), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.atlasShaderLayer(
    sea: Color,
    light: Color,
    intensity: Float,
): Modifier {
    val phases = rememberSlowPhases(listOf(ATLAS_SHADER_PERIOD_MS))
    val shader = remember { runCatching { RuntimeShader(ATLAS_AGSL) }.getOrNull() } ?: return this
    return this.drawWithCache {
        shader.setFloatUniform("sea", sea.red, sea.green, sea.blue)
        shader.setFloatUniform("light", light.red, light.green, light.blue)
        shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
        val brush = ShaderBrush(shader)
        onDrawBehind {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("phase", phases.value.firstOrNull() ?: 0f)
            drawRect(brush)
        }
    }
}
