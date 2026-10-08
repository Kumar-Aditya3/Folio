/*
 * SkSL (AGSL) -> GLSL ES 3.0, mechanically.
 *
 * The point of this file is that it contains no knowledge of the plate. It renames types
 * and moves `main` one level down so the body can keep its `return half4(...)` statements
 * verbatim; it does not rewrite a single expression. That matters, because the whole
 * exercise is to render *the shipped shader maths* and not a re-description of it — a
 * translator that understood the plate could quietly change it.
 *
 * The one semantic decision is the `main` rename. SkSL's `main(float2 fragCoord)` returns
 * the fragment colour; GLSL's `main()` writes to an out variable. Rather than rewrite the
 * returns, the AGSL main becomes `folioMain`, and a three-line `main` composites it over
 * the field exactly the way the app does — `drawRect(field)` then `drawRect(brush)` with a
 * premultiplied colour.
 */

const FOLIO_GLSL_HEADER = `#version 300 es
precision highp float;
precision highp int;
out vec4 fragColor;

// The field's three colour pools. Not part of the AGSL plate block — these belong to
// \`folioField\`, which draws underneath it.
uniform vec3 poolColour0;
uniform vec3 poolColour1;
uniform vec3 poolColour2;
uniform vec2 poolCentre0;
uniform vec2 poolCentre1;
uniform vec2 poolCentre2;
uniform float poolRadius0;
uniform float poolRadius1;
uniform float poolRadius2;
uniform float poolAlpha0;
uniform float poolAlpha1;
uniform float poolAlpha2;
`;

/** The field, drawn from the same uniforms the plate's colour rule already reads. */
const FOLIO_GLSL_FIELD = `
// \`fieldT\`, FolioFieldModel.kt, in normalised coordinates: the pixel-space form
// \`0.5 + ((x-w/2)*ax + (y-h/2)*ay)/span\` reduces to this once the common h^2 factor is
// dropped, and the AGSL plateAt derives the identical axis for its own line colour.
float fieldAxisAt(vec2 uv) {
    vec2 dir = vec2((1.0 - 2.0 * fieldSource.x) * aspect * aspect,
                    1.0 - 2.0 * fieldSource.y);
    float span = abs(dir.x) + abs(dir.y);
    return clamp(0.5 + dot(uv - 0.5, dir) / max(span, 1e-6), 0.0, 1.0);
}

// One pool: a radial falloff to transparent, composited. Mirrors
// \`fieldColorAtPoint\`'s pool loop, which is the pixel-exact form of the
// radial gradient \`folioField\` draws.
vec3 poolOver(vec3 base, vec2 uv, vec2 centre, float radiusPx, float alpha, vec3 colour) {
    if (alpha <= 0.0) return base;
    float d = length((uv - centre) * size) / max(radiusPx, 1e-6);
    if (d >= 1.0) return base;
    float a = alpha * (1.0 - d);
    return base * (1.0 - a) + colour * a;
}

// \`folioField\`'s draw order: the ramp first, then the three pools over it.
vec3 folioFieldAt(vec2 uv) {
    vec3 c = mix3(fieldTop, fieldBottom, fieldAxisAt(uv));
    c = poolOver(c, uv, poolCentre0, poolRadius0, poolAlpha0, poolColour0);
    c = poolOver(c, uv, poolCentre1, poolRadius1, poolAlpha1, poolColour1);
    c = poolOver(c, uv, poolCentre2, poolRadius2, poolAlpha2, poolColour2);
    return c;
}
`;

const FOLIO_GLSL_MAIN = `
void main() {
    // Skia's fragCoord is device space with its origin at the top-left; GL's gl_FragCoord
    // is bottom-left. Flipping y is what puts the corner fan in the corner the app puts it
    // in rather than the one diagonally opposite.
    vec2 fragCoord = vec2(gl_FragCoord.x, size.y - gl_FragCoord.y);
    // The plate, straight out of the shipped AGSL main.
    vec4 plate = folioMain(fragCoord);
    vec3 base = folioFieldAt(fragCoord / size);
    // Premultiplied source-over, which is what two successive drawRect calls do.
    fragColor = vec4(clamp(base * (1.0 - plate.a) + plate.rgb, 0.0, 1.0), 1.0);
}
`;

/** Rename SkSL types; do not touch a single expression. */
function skslToGlslTypes(src) {
  return src
    .replace(/\bhalf4\b/g, "vec4")
    .replace(/\bhalf3\b/g, "vec3")
    .replace(/\bhalf2\b/g, "vec2")
    .replace(/\bhalf\(/g, "float(")
    .replace(/\bfloat4\b/g, "vec4")
    .replace(/\bfloat3\b/g, "vec3")
    .replace(/\bfloat2\b/g, "vec2");
}

/**
 * A standalone AGSL shader as GLSL ES 3.0, with its `main` renamed so the body — and every
 * `return half4(...)` in it — stays verbatim.
 */
function skslFragmentShader(agslSource, entry) {
  const renamed = skslToGlslTypes(agslSource);
  const moved = renamed.replace(
    /vec4\s+main\s*\(\s*vec2\s+fragCoord\s*\)/,
    `vec4 ${entry}(vec2 fragCoord)`
  );
  if (moved === renamed) {
    throw new Error(`could not find \`half4 main(float2 fragCoord)\` to rename to ${entry}`);
  }
  return (
    "#version 300 es\nprecision highp float;\nprecision highp int;\nout vec4 fragColor;\n" +
    moved +
    `\nvoid main() { fragColor = ${entry}(vec2(gl_FragCoord.x, size.y - gl_FragCoord.y)); }\n`
  );
}

/**
 * The full fragment shader: the AGSL source, verbatim in structure, with the field under it.
 * `half4 main(float2 fragCoord)` becomes `vec4 folioMain(vec2 fragCoord)` so its body — and
 * every `return half4(...)` inside it — is untouched.
 */
function buildFolioFragmentShader(agslSource) {
  const renamed = skslToGlslTypes(agslSource);
  const moved = renamed.replace(
    /vec4\s+main\s*\(\s*vec2\s+fragCoord\s*\)/,
    "vec4 folioMain(vec2 fragCoord)"
  );
  if (moved === renamed) {
    throw new Error("could not find `half4 main(float2 fragCoord)` to rename — the AGSL entry point moved");
  }
  return FOLIO_GLSL_HEADER + moved + FOLIO_GLSL_FIELD + FOLIO_GLSL_MAIN;
}
