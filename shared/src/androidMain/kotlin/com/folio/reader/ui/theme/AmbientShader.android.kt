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

// ── value-noise fbm, for gas ──
// A couple of cheap octaves of smooth value noise. Used to tear the blooms into wisps and
// to lay a faint cloud over the whole field, so the ambient film reads as drifting nebula
// gas rather than two clean lamps.
float hash(float2 p) {
    p = fract(p * float2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}
float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 p) {
    float s = 0.0;
    float amp = 0.5;
    for (int k = 0; k < 4; k++) {
        s += amp * vnoise(p);
        p = p * 2.02 + 7.0;
        amp *= 0.5;
    }
    return s;
}

// A **curtain**: a long soft ribbon, not a lamp.
//
// This is the fix for the one thing the old film got wrong. It used
// `smoothstep(0.80, 0.0, distance(uv, c))` — an isotropic falloff, which is a disc by
// construction, so two of them read as two blurred blobs behind the UI. An aurora is
// *anisotropic*: it is many times longer than it is wide. So the sample is rotated into
// the ribbon's own frame and the two axes are scaled apart before the distance is taken,
// which is the whole difference between a ribbon and a disc. The falloff is steeper than
// the old smoothstep as well, so the ribbon has a defined body with a soft shoulder rather
// than a wash that fills half the screen.
float curtain(float2 uv, float2 centre, float angle, float stretch, float width) {
    float2 v = uv - centre;
    float2 dir = float2(cos(angle), sin(angle));
    float along = dot(v, dir);
    float across = dot(v, float2(-dir.y, dir.x));
    float d = length(float2(along / max(stretch, 0.05), across / max(width, 0.05)));
    return pow(clamp(1.0 - d, 0.0, 1.0), 2.6);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;

    // Slow, continuous drift of the gas. Built from sin/cos of the single phase so it never
    // jumps at the 42s sawtooth wrap — the same wrap-safety the plate and burst observe.
    float2 flow = 0.16 * float2(sin(phase), cos(phase * 0.9));

    // Two curtain centres drifting on slow, out-of-phase orbits.
    float2 cA = float2(0.30 + 0.10 * sin(phase),       0.30 + 0.08 * cos(phase * 0.9));
    float2 cB = float2(0.72 + 0.09 * cos(phase * 1.1), 0.68 + 0.10 * sin(phase * 0.8));

    // Turbulent domain warp: shove the sample point around with low-frequency noise so the
    // curtains tear into filaments and billows instead of staying smooth bands.
    float2 warp = float2(fbm(uv * 3.0 + flow), fbm(uv * 3.0 - flow + 11.0));
    float2 wuv = uv + (warp - 0.5) * 0.30;

    // The two curtains, laid across each other at different angles so the film has a
    // composition rather than one direction.
    float gA = curtain(wuv, cA, 0.35, 2.8, 0.34);
    float gB = curtain(wuv, cB, -0.55, 3.2, 0.30);

    // ── striation ──
    // The fine banding along a real aurora's length. This is the term that finally stops
    // the film reading as a blurred blob: a ribbon with *grain* reads as structure, where a
    // smooth ribbon reads as a smudge. High frequency, low amplitude, and it rides the same
    // warp so the bands bend with the curtain instead of ruling straight through it.
    float bandA = 0.5 + 0.5 * sin(wuv.x * 44.0 + warp.y * 7.0 + phase * 3.0);
    float bandB = 0.5 + 0.5 * sin(wuv.y * 38.0 + warp.x * 7.0 - phase * 2.0);
    gA *= 0.52 + 0.48 * bandA * bandA;
    gB *= 0.52 + 0.48 * bandB * bandB;

    // A faint gas between the curtains, so the field is not dead fill — but much weaker
    // than the old version, which let the cloud term itself become a third blob.
    float cloud = fbm(wuv * 2.5 + flow * 0.5);
    gA *= 0.58 + 0.52 * cloud;
    gB *= 0.58 + 0.52 * cloud;

    // Premultiplied: rgb is already scaled by the curtain weights, so rgb <= a.
    float3 rgb = (colorA * gA + colorB * gB) * intensity;
    float a = (gA + gB) * intensity;
    return half4(half3(clamp(rgb, 0.0, 1.0)), half(clamp(a, 0.0, 1.0)));
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
