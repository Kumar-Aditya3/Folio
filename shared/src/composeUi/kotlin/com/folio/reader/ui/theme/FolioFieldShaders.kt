package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Independent field programs, also compiled and raster-tested by Skia on desktop. */
internal fun FolioFieldKind.designedFieldShader(): String? = when (this) {
    FolioFieldKind.Warp -> FIELD_HEADER + WARP_FIELD
    FolioFieldKind.Voronoi -> FIELD_HEADER + FIELD_HASH + VORONOI_FIELD
    FolioFieldKind.MeshGradient -> FIELD_HEADER + FIELD_HASH + MESH_FIELD
    FolioFieldKind.ColorPanels -> FIELD_HEADER + PANELS_FIELD
    FolioFieldKind.Heatmap -> FIELD_HEADER + HEATMAP_FIELD
    FolioFieldKind.LiquidMetal -> FIELD_HEADER + METAL_FIELD
    FolioFieldKind.Metaballs -> FIELD_HEADER + METABALLS_FIELD
    FolioFieldKind.Waves -> FIELD_HEADER + WAVES_FIELD
    FolioFieldKind.Water -> FIELD_HEADER + WATER_FIELD
    FolioFieldKind.Spiral -> FIELD_HEADER + SPIRAL_FIELD
    FolioFieldKind.PerlinNoise -> FIELD_HEADER + FIELD_HASH + FIELD_NOISE + CLOUD_FIELD
    FolioFieldKind.SmokeRing -> FIELD_HEADER + FIELD_HASH + FIELD_NOISE + SMOKE_FIELD
    else -> null
}

/** Sharp travelling edges need 30 Hz; a soft mesh can interpolate at 20 Hz. */
internal fun FolioFieldKind.designedFieldFrameMs(): Long = when (this) {
    FolioFieldKind.MeshGradient -> 50L
    else -> 33L
}

/**
 * Dot Orbit's five swatch identities and order, with more pigment on a dark face. Its sparse
 * dots can carry a stronger colour than a broad field after shading; the new fields therefore
 * lose less of each authored swatch to the backdrop. No white highlight or extra contrast clamp.
 */
internal fun folioDesignedFieldColors(
    back: Color,
    palette: List<Color>,
    isDark: Boolean,
    vibrancy: Float,
): List<Color> {
    val swatches = palette.ifEmpty { listOf(back) }
    val up = ((vibrancy - VIBRANCY_NEUTRAL) / (1f - VIBRANCY_NEUTRAL)).coerceIn(0f, 1f)
    val sink = 0.36f * (1f - up * 0.5f)
    return listOf(back.copy(alpha = 1f)) + List(5) {
        val color = swatches[it % swatches.size]
        (if (isDark) lerp(color, back, sink) else color).copy(alpha = 1f)
    }
}

private const val FIELD_HEADER = """
uniform float2 u_size;
uniform float u_time;
uniform float u_scale;
uniform float u_intensity;
uniform float3 u_back;
uniform float3 u_color1;
uniform float3 u_color2;
uniform float3 u_color3;
uniform float3 u_color4;
uniform float3 u_color5;

const float TAU = 6.2831853;

float2 fieldUv(float2 xy) {
    return (xy - 0.5 * u_size) / (min(u_size.x, u_size.y) * max(u_scale, 0.25));
}
float pixelWidth() {
    return 1.4 / (min(u_size.x, u_size.y) * max(u_scale, 0.25));
}
float2 turn(float2 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}
float3 fieldColor(float t) {
    float part = clamp(t, 0.0, 1.0) * 4.0;
    if (part < 1.0) return mix(u_color1, u_color2, part);
    if (part < 2.0) return mix(u_color2, u_color3, part - 1.0);
    if (part < 3.0) return mix(u_color3, u_color4, part - 2.0);
    return mix(u_color4, u_color5, part - 3.0);
}
float3 fieldSwatch(float index) {
    return fieldColor(mod(index, 5.0) * 0.25);
}
float3 fieldLight(float t) {
    return mix(u_color4, u_color5, clamp(t, 0.0, 1.0));
}
half4 finishField(float3 color) {
    return half4(half3(clamp(mix(u_back, color, clamp(u_intensity, 0.0, 1.0)), 0.0, 1.0)), 1.0);
}
"""

private const val FIELD_HASH = """
float hash21(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}
"""

private const val FIELD_NOISE = """
// Gradient noise with a quintic shoulder, not a blurred lattice of random greys.
float perlin(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = dot(hash22(i) * 2.0 - 1.0, f);
    float b = dot(hash22(i + float2(1.0, 0.0)) * 2.0 - 1.0, f - float2(1.0, 0.0));
    float c = dot(hash22(i + float2(0.0, 1.0)) * 2.0 - 1.0, f - float2(0.0, 1.0));
    float d = dot(hash22(i + float2(1.0, 1.0)) * 2.0 - 1.0, f - 1.0);
    return 0.5 + mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float cloudNoise(float2 p) {
    float sum = 0.0;
    float weight = 0.533333;
    for (int i = 0; i < 4; i++) {
        sum += weight * perlin(p);
        p = float2(1.6 * p.x - 1.2 * p.y, 1.2 * p.x + 1.6 * p.y) + 9.4;
        weight *= 0.5;
    }
    return sum;
}
"""

private const val WARP_FIELD = """
half4 main(float2 xy) {
    float2 p = turn(fieldUv(xy), -0.38);
    float t = u_time;
    p.x += 0.26 * sin(p.y * 2.4 + t * 0.90) + 0.12 * sin(p.y * 4.1 - t * 0.70);
    float pleat = p.x * 12.0 - t * 1.40 + 1.20 * sin(p.y * 1.9 - t * 0.80)
                  + 0.45 * sin(p.y * 4.2 + t * 0.65);
    float fold = 0.5 + 0.5 * sin(pleat);
    float band = floor((pleat + 1.5707963) / TAU);
    float3 silk = fieldSwatch(mod(band, 5.0));
    silk = mix(u_back, silk, 0.12 + 0.88 * smoothstep(0.12, 0.80, fold));
    float catchLight = pow(0.5 + 0.5 * sin(pleat + 0.6), 12.0);
    return finishField(mix(silk, fieldLight(fold), 0.24 * catchLight));
}
"""

private const val VORONOI_FIELD = """
half4 main(float2 xy) {
    float t = u_time;
    float2 p = fieldUv(xy) * 3.1 + float2(t * 0.32, -t * 0.20);
    p += 0.24 * float2(sin(p.y * 1.4 + t * 0.55), cos(p.x * 1.2 - t * 0.45));
    float2 cell = floor(p);
    float2 local = fract(p);
    float first = 100.0;
    float second = 100.0;
    float2 nearest = float2(0.0);
    float2 next = float2(1.0);
    float2 tint = float2(0.5);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float2 offset = float2(float(x), float(y));
            float2 seed = hash22(cell + offset);
            float2 site = 0.5 + 0.34 * sin(TAU * seed + t * float2(1.10, 0.92));
            float2 delta = offset + site - local;
            float d = dot(delta, delta);
            if (d < first) {
                second = first;
                next = nearest;
                first = d;
                nearest = delta;
                tint = seed;
            } else if (d < second) {
                second = d;
                next = delta;
            }
        }
    }
    float seam = 0.5 * (second - first) / max(length(next - nearest), 0.001);
    float aa = pixelWidth() * 3.1;
    float glass = smoothstep(0.014, 0.014 + aa, seam);
    float face = clamp(0.70 + dot(nearest, float2(-0.22, -0.30)), 0.36, 0.95);
    float3 color = mix(u_back, fieldSwatch(floor(tint.x * 5.0)), face);
    float bevel = (1.0 - smoothstep(0.018 + aa, 0.055 + aa, seam)) * glass;
    color = mix(color, fieldLight(tint.y), bevel * 0.28);
    return finishField(mix(u_back, color, glass));
}
"""

private const val MESH_FIELD = """
half4 main(float2 xy) {
    float2 p = fieldUv(xy);
    float t = u_time;
    p += 0.22 * float2(sin(p.y * 2.2 + t * 0.85), cos(p.x * 2.8 - t * 0.75));
    float3 pigment = float3(0.0);
    float total = 0.0;
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float phase = fi * 1.2566371;
        float2 center = float2(0.46 * cos(phase + t * (0.52 + fi * 0.04)),
                               0.90 * sin(phase + t * (0.62 - fi * 0.035)));
        float2 delta = p - center;
        float weight = exp(-dot(delta, delta) * 7.0);
        pigment += fieldSwatch(fi) * weight;
        total += weight;
    }
    float3 color = pigment / max(total, 0.001);
    color = mix(u_back, color, 0.24 + 0.76 * smoothstep(0.02, 0.65, total));
    return finishField(mix(color, u_back, 0.008 * hash21(xy)));
}
"""

private const val PANELS_FIELD = """
half4 main(float2 xy) {
    float2 p = fieldUv(xy);
    // Rigid matte planes: fixed widths and lean, constant face colour, no glossy tube shading.
    float column = (p.x - 0.12 * p.y - u_time * 0.20) / 0.34;
    float local = fract(column);
    float aa = pixelWidth() / 0.34;
    float mask = smoothstep(0.006, 0.006 + aa, local)
                 * (1.0 - smoothstep(0.994 - aa, 0.994, local));
    return finishField(mix(u_back, fieldSwatch(floor(column)), mask));
}
"""

private const val HEATMAP_FIELD = """
half4 main(float2 xy) {
    float2 p = fieldUv(xy);
    float t = u_time;
    p += 0.10 * float2(sin(p.y * 3.1 + t * 0.7), cos(p.x * 3.5 - t * 0.8));
    float2 a = float2(-0.22 + 0.34 * sin(t * 0.83), -0.48 + 0.48 * cos(t * 0.72));
    float2 b = float2(0.20 + 0.38 * cos(t * 0.67), 0.05 + 0.64 * sin(t * 0.91));
    float2 c = float2(-0.12 + 0.40 * sin(t * 0.71 + 2.0), 0.65 + 0.46 * cos(t * 0.69));
    float heat = 0.90 * exp(-dot(p - a, p - a) * 5.0)
                 + 0.85 * exp(-dot(p - b, p - b) * 4.0)
                 + 0.75 * exp(-dot(p - c, p - c) * 3.8);
    heat = clamp(1.0 - exp(-heat * 1.3)
                 + 0.040 * sin(p.x * 6.0 + p.y * 4.0 + t * 1.1), 0.0, 1.0);
    float terrace = mix(heat, floor(heat * 7.0) / 7.0, 0.18);
    float3 color = mix(u_back, fieldColor(terrace), smoothstep(0.03, 0.45, heat));
    float contour = pow(0.5 + 0.5 * cos(TAU * heat * 7.0), 18.0);
    contour *= smoothstep(0.08, 0.20, heat);
    color = mix(color, fieldLight(heat), contour * 0.48);
    color = mix(color, u_color2, smoothstep(0.70, 0.88, heat) * 0.28);
    return finishField(color);
}
"""

private const val METAL_FIELD = """
half4 main(float2 xy) {
    float2 p = fieldUv(xy);
    float t = u_time;
    float2 q = p + float2(0.30 * sin(p.y * 1.7 + t * 0.65),
                         0.25 * sin(p.x * 2.1 - t * 0.75));
    float a = q.x * 2.6 + q.y * 1.4 + t * 1.15;
    float b = q.x * -3.8 + q.y * 2.7 - t * 0.95;
    float c = q.x * 6.4 + q.y * 4.1 + t * 0.80;
    // An analytic height-field normal: deforming mercury, with no object mask or ray march.
    float dx = 1.43 * cos(a) - 0.988 * cos(b) + 1.024 * cos(c);
    float dy = 0.77 * cos(a) + 0.702 * cos(b) + 0.656 * cos(c);
    float2 slope = float2(dx + dy * 0.525 * cos(p.x * 2.1 - t * 0.75),
                          dy + dx * 0.510 * cos(p.y * 1.7 + t * 0.65));
    float3 normal = normalize(float3(-slope * 0.46, 1.0));
    float3 reflected = reflect(float3(0.0, 0.0, -1.0), normal);
    float softbox = 1.0 - smoothstep(0.045, 0.22, abs(reflected.y + 0.25 + reflected.x * 0.14));
    float stripLight = 1.0 - smoothstep(0.012, 0.09, abs(reflected.x * 0.4 - reflected.y + 0.72));
    float darkBand = 1.0 - smoothstep(0.08, 0.26, abs(reflected.y - 0.30));
    float grazing = pow(1.0 - normal.z, 2.0);
    float3 tint = fieldColor(clamp(0.5 + reflected.x * 0.50, 0.0, 1.0));
    float3 metal = mix(u_back, tint, 0.42 + 0.42 * smoothstep(-0.6, 0.6, reflected.y));
    metal = mix(metal, u_back, darkBand * 0.70);
    metal = mix(metal, u_color3, grazing * 0.24);
    metal = mix(metal, fieldLight(0.5 + reflected.x * 0.5),
                clamp(softbox * 0.90 + stripLight * 0.60 + grazing * 0.22, 0.0, 0.96));
    return finishField(metal);
}
"""

private const val METABALLS_FIELD = """
half4 main(float2 xy) {
    float2 p = fieldUv(xy);
    float t = u_time;
    float potential = 0.0;
    float2 gradient = float2(0.0);
    float3 pigment = float3(0.0);
    float pigmentWeight = 0.0;
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float phase = fi * 0.897598;
        float2 center = float2(0.36 * sin(t * 0.85 + phase) + 0.10 * sin(t * 1.39 + phase * 2.0),
                               0.80 * cos(t * 0.73 + phase) + 0.18 * sin(t * 1.11 + phase));
        float radius = 0.15 + 0.045 * (0.5 + 0.5 * sin(t * 0.77 + phase));
        float2 delta = p - center;
        float denominator = dot(delta, delta) + 0.009;
        float weight = radius * radius / denominator;
        potential += weight;
        gradient -= 2.0 * radius * radius * delta / (denominator * denominator);
        // A dominant nearby droplet carries its own hue; distant balls must not grey every body.
        pigment += fieldSwatch(fi) * weight * weight * weight;
        pigmentWeight += weight * weight * weight;
    }
    float aa = pixelWidth() * max(length(gradient), 1.0);
    float mask = smoothstep(1.22 - aa, 1.22 + aa, potential);
    float halo = smoothstep(0.62, 1.24, potential) * 0.09;
    float3 ground = mix(u_back, u_color2, halo);
    float3 material = pigment / max(pigmentWeight, 0.001);
    float3 normal = normalize(float3(-gradient * 0.07, 1.0));
    float diffuse = max(dot(normal, normalize(float3(-0.45, -0.65, 1.0))), 0.0);
    float specular = pow(max(dot(normal, normalize(float3(-0.45, -0.65, 1.0))), 0.0), 18.0);
    float membrane = 1.0 - smoothstep(0.0, 0.28, abs(potential - 1.22));
    material = mix(u_back, material, 0.62 + diffuse * 0.38);
    material = mix(material, fieldLight(diffuse), clamp(specular * 0.32 + membrane * diffuse * 0.16, 0.0, 0.48));
    return finishField(mix(ground, material, mask));
}
"""

private const val WAVES_FIELD = """
half4 main(float2 xy) {
    float2 p = turn(fieldUv(xy), -0.24);
    float3 color = mix(u_back, u_color3, 0.10);
    float aa = pixelWidth();
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float phase = fi * 1.37;
        float curve = 0.21 * sin(p.x * 2.8 - u_time * 1.30 + phase)
                      + 0.070 * sin(p.x * 6.2 + u_time * 1.05 + phase);
        float lift = 0.10 * sin(u_time * 0.70 + phase);
        float d = p.y - (-1.02 + fi * 0.34) - curve - lift;
        float width = 0.105 + 0.022 * sin(phase + u_time * 0.74);
        float mask = 1.0 - smoothstep(width, width + 0.018 + aa, abs(d));
        float3 ribbon = mix(u_back, fieldSwatch(fi),
                            0.60 + 0.40 * (1.0 - smoothstep(-width, width, d)));
        float crest = 1.0 - smoothstep(0.0, 0.014 + aa, abs(d + width * 0.70));
        ribbon = mix(ribbon, fieldLight(fi * 0.15), crest * 0.20);
        color = mix(color, ribbon, mask * 0.96);
    }
    return finishField(color);
}
"""

private const val WATER_FIELD = """
half4 main(float2 xy) {
    float2 p = fieldUv(xy);
    float t = u_time;
    float2 q = p * 7.5 + float2(t * 0.55, -t * 0.43);
    q += 0.54 * float2(sin(q.y * 0.8 + t * 0.74), sin(q.x * 0.7 - t * 0.61));
    float a = sin(q.x + 0.65 * sin(q.y));
    float b = sin(q.y - 0.55 * sin(q.x));
    float interference = a + 0.72 * b + 0.28 * sin(q.x * 0.8 - q.y * 1.2 + t * 0.85);
    float caustic = pow(clamp(1.0 - abs(interference) * 0.72, 0.0, 1.0), 14.0);
    float echo = sin(q.x * 0.76 + q.y * 0.43 + t * 0.52)
                 + 0.70 * sin(q.y * 0.82 - q.x * 0.31 - t * 0.68);
    float reflectedLight = pow(clamp(1.0 - abs(echo) * 0.82, 0.0, 1.0), 18.0);
    float undertow = 0.5 + 0.5 * sin(p.y * 2.1 + p.x * 1.5 + t * 0.63 + a * 0.38);
    float3 color = mix(u_back, fieldColor(undertow), 0.34 + undertow * 0.46);
    color = mix(color, fieldLight(0.5 + b * 0.5), clamp(caustic * 0.86 + reflectedLight * 0.34, 0.0, 0.94));
    return finishField(color);
}
"""

private const val SPIRAL_FIELD = """
half4 main(float2 xy) {
    float2 center = float2(0.10 + 0.045 * sin(u_time * 0.65), -0.16 + 0.09 * cos(u_time * 0.55));
    float2 p = fieldUv(xy) - center;
    float radius = length(p);
    // Integer arms close the atan seam; the quiet core hides its undefined direction.
    float angle = atan(p.y, p.x);
    float curl = angle * 3.0 + 12.0 * log(1.0 + radius * 2.0) - u_time * 1.55
                 + 0.32 * sin(radius * 5.0 - u_time * 0.85);
    float ribbon = pow(0.5 + 0.5 * cos(curl), 3.0);
    float envelope = smoothstep(0.04, 0.18, radius) * (1.0 - smoothstep(0.65, 1.48, radius));
    float3 nacre = fieldColor(clamp(radius * 0.90, 0.0, 1.0));
    float3 color = mix(u_back, nacre, ribbon * envelope * 0.94);
    float catchLight = pow(0.5 + 0.5 * cos(curl + 0.32), 28.0);
    float fineTrace = pow(0.5 + 0.5 * cos(curl - 0.55), 48.0);
    return finishField(mix(color, fieldLight(radius), (catchLight * 0.25 + fineTrace * 0.10) * envelope));
}
"""

private const val CLOUD_FIELD = """
half4 main(float2 xy) {
    float2 p = turn(fieldUv(xy), -0.18) * 2.1;
    float2 wind = float2(u_time * 0.36, -u_time * 0.21);
    float2 warp = float2(perlin(p * 0.7 + wind), perlin(p * 0.7 + wind + 9.2)) - 0.5;
    float cloud = cloudNoise(p + warp * 0.80 + wind);
    float density = smoothstep(0.37, 0.65, cloud);
    float tint = 0.5 + 0.5 * sin(p.y * 1.5 + p.x * 1.1 - u_time * 0.55 + warp.x * 1.8);
    float3 mist = fieldColor(tint);
    float3 color = mix(u_back, mist, pow(density, 1.1) * 0.96);
    color = mix(color, fieldLight(tint), pow(density, 3.0) * 0.16);
    return finishField(color);
}
"""

private const val SMOKE_FIELD = """
half4 main(float2 xy) {
    float t = u_time;
    float2 center = float2(0.07 * sin(t * 0.65), -0.10 + 0.10 * cos(t * 0.75));
    float2 p = turn(fieldUv(xy) - center, 0.10 * sin(t * 0.65));
    p *= float2(0.93, 1.12 + 0.13 * sin(t * 0.90));
    float radius = length(p);
    float angle = atan(p.y, p.x);
    float ringRadius = 0.34 + 0.045 * sin(t * 0.95)
                       + 0.026 * sin(angle * 3.0 + t * 0.85)
                       + 0.018 * sin(angle * 5.0 - t * 1.15);
    float turbulence = cloudNoise(turn(p, t * 0.55) * 8.0 + float2(t * 0.23, -t * 0.18));
    float d = radius - ringRadius - (turbulence - 0.5) * 0.04;
    float width = 0.060 + 0.025 * turbulence;
    float ring = exp(-d * d / (width * width));
    float haze = exp(-d * d / 0.018) * 0.12;
    float filaments = 0.5 + 0.5 * sin(d * 82.0 + turbulence * 10.0 - t * 2.1);
    float body = ring * (0.42 + 0.58 * smoothstep(0.28, 0.64, turbulence)) * (0.68 + 0.32 * filaments);
    float light = 0.5 + 0.5 * dot(p / max(radius, 0.001), float2(-0.60, -0.80));
    // Integer angular frequency keeps the cool and warm plumes continuous across atan's seam.
    float tint = 0.5 + 0.5 * sin(angle * 2.0 - t * 0.90 + d * 8.0);
    float3 smoke = fieldColor(tint);
    float3 color = mix(u_back, u_color3, haze);
    color = mix(color, smoke, clamp(body * 1.25, 0.0, 1.0));
    color = mix(color, fieldLight(tint), body * light * 0.24);
    return finishField(color);
}
"""
