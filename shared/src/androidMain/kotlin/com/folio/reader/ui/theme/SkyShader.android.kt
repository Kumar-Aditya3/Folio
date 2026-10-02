package com.folio.reader.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush

/** One slow orbit of the sky's drift + twinkle. Slower than the ambient film. */
private const val SKY_SHADER_PERIOD_MS = 48_000L

/** Parked phases for reduce-motion: drift and twinkle both frozen at 0. */
private val STATIC_PHASES = listOf(0f, 0f)

/**
 * Android side of [folioSkyShader]. One AGSL fragment shader renders the whole
 * skyscape — a domain-warped FBM nebula, a hashed three-scale star field with soft
 * bloom, and an SDF planet with a ring — tinted entirely by the theme's
 * [FolioSignature] colours. Static (phase 0) under reduce-motion, a no-op below API 33.
 */
@Composable
actual fun Modifier.folioSkyShader(enabled: Boolean): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return skyShaderLayer()
}

/**
 * The AGSL. Premultiplied output (Skia convention): rgb is already scaled by the
 * accumulated alpha, so rgb ≤ a. The whole figure fades to a content-band floor
 * through the vertical middle (`fall`), where cardless body text lives, so the sky
 * carries the margins and gets out of the reader's way in the column.
 *
 * Every declared uniform is read in `main` — SkSL strips an unreferenced uniform and
 * the setter then throws at draw time (build-128 lesson), so none is left dangling.
 */
private const val SKY_AGSL = """
uniform float2 size;
uniform float phase;
uniform float twinkle;
uniform float isDark;
uniform float contentScale;
uniform float edgeStrength;
uniform float starDensity;
uniform float planetVis;
uniform float hazeVis;
uniform float3 skyTop;
uniform float3 nebCool;
uniform float3 nebWarm;
uniform float3 glow;
uniform float3 star;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float s = 0.0;
    float amp = 0.5;
    float2 q = p;
    for (int i = 0; i < 5; i++) {
        s += amp * vnoise(q);
        q *= 2.02;
        amp *= 0.5;
    }
    return s;
}

float starLayer(float2 uv, float scale, float thresh, float weight) {
    float2 g = uv * scale;
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash21(id);
    float present = step(thresh, h);
    float2 off = (float2(hash21(id + 1.3), hash21(id + 2.7)) - 0.5) * 0.6;
    float d = length(f - off);
    float core = smoothstep(0.07, 0.0, d);
    float halo = smoothstep(0.34, 0.0, d) * 0.22;
    // Staggered twinkle: a per-star phase (h*31) AND a per-star speed (0.7..1.5 from h),
    // swung at the raised amplitude so stars shimmer out of sync rather than as one pulse.
    float spd = 0.7 + h * 0.8;
    float tw = 0.60 + 0.42 * sin(twinkle * spd + h * 31.0);
    return present * (core + halo) * tw * weight;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float asp = size.x / max(size.y, 1.0);
    float2 p = float2(uv.x * asp, uv.y);

    // Content-band falloff: vivid at the top/bottom edges, calm through the middle where
    // body text lives. contentScale (<=1) caps the centre so no level outshines the
    // shipped field; edgeStrength lifts the margins (Expressive reads bolder).
    float top = clamp((0.5 - uv.y) / 0.5, 0.0, 1.0);
    float bot = clamp((uv.y - 0.5) / 0.5, 0.0, 1.0);
    float fall = 0.5 * contentScale + 0.5 * edgeStrength * max(top, bot);

    // ── Nebula ── domain-warped FBM with wide coverage and high chroma so the field
    // reads as real, saturated cloud (the concept) rather than a faded wash.
    float2 drift = float2(phase * 0.010, phase * 0.005);
    float2 q = p * 1.05 + drift;
    float2 warp = float2(fbm(q), fbm(q + float2(5.2, 1.3)));
    // Low domain-warp + a soft, wide threshold + an extra smoothing pass → billowing
    // cloud, not the hard streaky "concrete lines" a strong warp produced.
    float n = fbm(q + 0.85 * warp);
    n = smoothstep(0.26, 0.95, n);
    n = n * n * (3.0 - 2.0 * n);
    float mixv = smoothstep(0.30, 0.80, fbm(q * 0.6 + warp));
    float3 nebCol = mix(nebCool, nebWarm, mixv);
    // The cool accent — the nebula/discovery hue on dark, the warm glow on light — used
    // for the orbital arcs and the planet so the celestial geometry is NOT forced to the
    // warm/gold `glow` (which read as a fixed yellow on dark themes).
    float3 coolAccent = mix(glow, nebCool, isDark);
    // A broad bias toward the top-right pools the brightest cloud in the margin, but the
    // whole field now carries soft colour.
    float corner = smoothstep(1.3, 0.0, length(p - float2(0.80 * asp, 0.20)));
    float nebBias = mix(0.6, 1.0, corner);
    float nebA = n * nebBias * mix(0.80, 0.92, isDark) * fall * mix(hazeVis, 1.0, isDark);

    // ── Sky wash ── a tinted lift from the top and bottom edges. Held back on dark so
    // the deep base stays dark and the saturated nebula/stars pop against it.
    float washTop = smoothstep(0.52, 0.0, uv.y) * 0.55;
    float washBot = smoothstep(0.66, 1.0, uv.y) * 0.40;
    float washA = (washTop + washBot) * contentScale * mix(1.0, 0.6, isDark);

    // ── Orbital arcs ── static thin ellipses with a bright shimmer that TRAVELS along
    // each curve (a border-edge beam), rather than the whole orbit rotating — the
    // rotation read as choppy. Each ring's beam starts at a different angle but all drift
    // at the same angular rate, so the sweep is seamless when the phase wraps. Frozen
    // under reduce-motion (phase 0). Hairlines, so they never touch legibility.
    float ang = -0.46;
    float ca = cos(ang);
    float sa = sin(ang);
    float2 oc = p - float2(0.74 * asp, 0.14);
    float2 orot = float2(oc.x * ca - oc.y * sa, oc.x * sa + oc.y * ca);
    float el = length(float2(orot.x / 1.35, orot.y / 0.82));
    float theta = atan(orot.y, orot.x);
    float m1 = smoothstep(0.009, 0.0, abs(el - 0.34));
    float m2 = smoothstep(0.009, 0.0, abs(el - 0.62));
    float m3 = smoothstep(0.009, 0.0, abs(el - 0.92));
    float d1 = abs(mod(theta - phase + 3.14159265, 6.2831853) - 3.14159265);
    float d2 = abs(mod(theta - phase - 2.1 + 3.14159265, 6.2831853) - 3.14159265);
    float d3 = abs(mod(theta - phase - 4.2 + 3.14159265, 6.2831853) - 3.14159265);
    float beam1 = 0.16 + 0.84 * smoothstep(1.1, 0.0, d1);
    float beam2 = 0.16 + 0.84 * smoothstep(1.1, 0.0, d2);
    float beam3 = 0.16 + 0.84 * smoothstep(1.1, 0.0, d3);
    float arcA = (m1 * beam1 + m2 * beam2 + m3 * beam3) * 0.34 * (0.55 + 0.45 * fall);

    // ── Stars ── dense, bright, soft bloom; brighter toward the edges.
    float s = starLayer(p, 9.0, 0.80, 1.0)
        + starLayer(p, 17.0, 0.86, 0.7)
        + starLayer(p, 33.0, 0.90, 0.45);
    s *= (0.72 + 0.42 * fall) * mix(0.75, 1.2, isDark) * starDensity;

    // ── Planet ── a soft-shaded sphere with a tilted ring and a glow, parked in the
    // top-right corner. Lit from the upper-left so it reads as a body, not a sticker.
    float2 pc = float2(0.82 * asp, 0.15);
    float pr = 0.085;
    float dp = length(p - pc);
    float2 nd = (p - pc) / max(dp, 0.0001);
    float pglow = smoothstep(pr * 2.7, pr * 0.7, dp) * 0.34;
    float sphere = smoothstep(pr, pr * 0.88, dp);
    float lambert = clamp(dot(nd, normalize(float2(-0.6, -0.5))), 0.0, 1.0);
    float discA = sphere * mix(0.28, 1.0, lambert) * 0.95;
    float2 rp = p - pc;
    float2 rr = float2(rp.x * ca - rp.y * sa, rp.x * sa + rp.y * ca);
    float k = length(float2(rr.x / (pr * 1.95), rr.y / (pr * 0.62)));
    float ring = smoothstep(0.16, 0.0, abs(k - 1.0)) * 0.5;
    float planetGlowA = (pglow + ring) * fall * planetVis;
    float planetDiscA = discA * fall * planetVis;
    float3 planetCol = coolAccent;

    // ── Compose over, premultiplied ──
    float3 col = skyTop;
    float a = washA;
    col = mix(col, nebCol, clamp(nebA / max(a + nebA, 0.001), 0.0, 1.0));
    a = a + nebA;
    col = mix(col, coolAccent, clamp(arcA / max(a + arcA, 0.001), 0.0, 1.0));
    a = a + arcA;
    col = mix(col, planetCol, clamp(planetGlowA / max(a + planetGlowA, 0.001), 0.0, 1.0));
    a = a + planetGlowA;
    col = mix(col, planetCol, clamp(planetDiscA / max(a + planetDiscA, 0.001), 0.0, 1.0));
    a = a + planetDiscA;
    col = mix(col, star, clamp(s / max(a + s, 0.001), 0.0, 1.0));
    a = a + s * 0.8;

    a = clamp(a, 0.0, 1.0);
    return half4(half3(col * a), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.skyShaderLayer(): Modifier {
    val sig = FolioTheme.atmosphere.signature
    val motion = rememberMotionEnabled()
    // The per-screen cosmic intensity, solved into renderer scalars. Read in composition
    // so a screen that raises/lowers intensity rebuilds this layer once; the scalars are
    // constant per level, so they are set in the cache block, not per draw.
    val sky = rememberCosmicIntensity().sky()
    // Two phases on the shader clock: slow drift (phase) and a few-second twinkle. The
    // twinkle period is the house CosmicMotion value so the AGSL and Canvas skies shimmer
    // at the same rate.
    val phases = rememberSlowPhases(listOf(SKY_SHADER_PERIOD_MS, CosmicMotion.starTwinkleMs))
    val shader = remember { runCatching { RuntimeShader(SKY_AGSL) }.getOrNull() } ?: return this
    return this.drawWithCache {
        shader.setFloatUniform("skyTop", sig.skyTop.red, sig.skyTop.green, sig.skyTop.blue)
        shader.setFloatUniform("nebCool", sig.nebulaCool.red, sig.nebulaCool.green, sig.nebulaCool.blue)
        shader.setFloatUniform("nebWarm", sig.nebulaWarm.red, sig.nebulaWarm.green, sig.nebulaWarm.blue)
        shader.setFloatUniform("glow", sig.glow.red, sig.glow.green, sig.glow.blue)
        shader.setFloatUniform("star", sig.star.red, sig.star.green, sig.star.blue)
        shader.setFloatUniform("isDark", if (sig.isDark) 1f else 0f)
        shader.setFloatUniform("contentScale", sky.contentScale)
        shader.setFloatUniform("edgeStrength", sky.edgeStrength)
        shader.setFloatUniform("starDensity", sky.starDensity)
        shader.setFloatUniform("planetVis", if (sky.drawsPlanet) 1f else 0f)
        shader.setFloatUniform("hazeVis", if (sky.drawsHaze) 1f else 0f)
        val brush = ShaderBrush(shader)
        onDrawBehind {
            shader.setFloatUniform("size", size.width, size.height)
            // Static under reduce-motion (phase 0), else the slow clock drives drift and
            // the faster clock drives the staggered twinkle.
            val p = if (motion) phases.value else STATIC_PHASES
            shader.setFloatUniform("phase", p.getOrNull(0) ?: 0f)
            shader.setFloatUniform("twinkle", p.getOrNull(1) ?: 0f)
            drawRect(brush)
        }
    }
}
