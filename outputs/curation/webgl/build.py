"""
Render the Folio ground plane with the shipped AGSL plate.

The page this builds contains the AGSL source verbatim (type-renamed, `main` moved one
level down) and the Kotlin model ported beside it, so the pixels come from the shader the
app actually runs rather than from a re-description of it.

    python build.py                 render every face
    python build.py ocean:1         render one face (family:light)
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
CHROME = pathlib.Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
W, H = 390, 844


def read(name: str) -> str:
    return (HERE / name).read_text(encoding="utf-8")


def build_page() -> pathlib.Path:
    faces = read("faces.json")
    agsl = read("agsl.json")
    page = f"""<!doctype html>
<meta charset="utf-8">
<title>folio-render</title>
<style>
  html,body{{margin:0;padding:0;background:#000;overflow:hidden}}
  #wrap{{position:relative;width:{W}px;height:{H}px}}
  canvas{{display:block;width:{W}px;height:{H}px}}
  #err{{position:absolute;left:0;top:0;right:0;bottom:0;background:#fff;color:#000;
        font:11px/1.4 monospace;padding:8px;white-space:pre-wrap;display:none;z-index:9;
        overflow:hidden}}
</style>
<div id="wrap"><canvas id="c"></canvas><pre id="err"></pre></div>
<script>window.FOLIO = {{faces: {faces}, agsl: {agsl}}};</script>
<script>{read("model.js")}</script>
<script>{read("sksl.js")}</script>
<script>
(function () {{
  const q = new URLSearchParams(location.search);
  const family = q.get("face") || "ocean";
  const light = q.get("light") === "1";
  const S = parseFloat(q.get("scale") || "2");
  const W = {W}, H = {H};

  const fail = (msg) => {{
    const el = document.getElementById("err");
    el.style.display = "block";
    el.textContent = msg;
    document.title = "ERR";
  }};

  let f;
  try {{ f = FOLIO_MODEL.buildFace(family, light); }}
  catch (e) {{ fail("model: " + e.message); return; }}

  const src = buildFolioFragmentShader(window.FOLIO.agsl.source);
  // The ambient bloom film: the second AGSL shader on the ground plane. It is a separate
  // full-screen pass, drawn over the plate and under the lamp, exactly as MainActivity
  // orders the two modifiers.
  const ambientSrc = skslFragmentShader(window.FOLIO.agsl.ambient, "ambientMain");

  const canvas = document.getElementById("c");
  canvas.width = W * S;
  canvas.height = H * S;
  const gl = canvas.getContext("webgl2", {{ antialias: false, preserveDrawingBuffer: true }});
  if (!gl) {{ fail("no webgl2 context"); return; }}

  const vsrc = "#version 300 es\\nin vec2 p; void main(){{ gl_Position = vec4(p, 0.0, 1.0); }}";
  const compile = (type, source) => {{
    const s = gl.createShader(type);
    gl.shaderSource(s, source);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {{
      const log = gl.getShaderInfoLog(s);
      const lines = source.split("\\n");
      const m = /:(\\d+):/.exec(log || "");
      const at = m ? Math.max(0, parseInt(m[1], 10) - 4) : 0;
      fail("shader compile failed:\\n" + log + "\\n\\n" +
           lines.slice(at, at + 8).map((l, i) => (at + i + 1) + ": " + l).join("\\n"));
      return null;
    }}
    return s;
  }};
  const vs = compile(gl.VERTEX_SHADER, vsrc);
  const fs = compile(gl.FRAGMENT_SHADER, src);
  if (!vs || !fs) return;

  const prog = gl.createProgram();
  gl.attachShader(prog, vs);
  gl.attachShader(prog, fs);
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {{
    fail("link failed:\\n" + gl.getProgramInfoLog(prog));
    return;
  }}
  gl.useProgram(prog);

  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, "p");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

  // ── the uniform block, mirroring GuillocheShader.android.kt's setters ──────
  const u = (name) => gl.getUniformLocation(prog, name);
  const set1 = (name, v) => {{ const l = u(name); if (l) gl.uniform1f(l, v); }};
  const set2 = (name, a, b) => {{ const l = u(name); if (l) gl.uniform2f(l, a, b); }};
  const set3 = (name, c) => {{ const l = u(name); if (l) gl.uniform3f(l, c[0], c[1], c[2]); }};
  const set4 = (name, a, b, c, d) => {{ const l = u(name); if (l) gl.uniform4f(l, a, b, c, d); }};

  const M = FOLIO_MODEL.MODEL;
  const a = f.atmos, sp = f.spec, dark = a.isDark;

  // `size` is the *drawing buffer*, because the shader divides `gl_FragCoord` by it — so
  // it has to be the same space `gl_FragCoord` lives in, or `uv` runs 0..S instead of 0..1
  // and the whole figure is scaled and shifted. `aspect` is scale-invariant.
  const BW = W * S, BH = H * S;
  set2("size", BW, BH);
  set1("aspect", W / H);
  set3("accent", f.tiers[0]);
  set3("accentB", f.tiers[1]);
  set3("accentC", f.tiers[2]);
  set3("fieldTop", a.fieldTop);
  set3("fieldBottom", a.fieldBottom);
  set2("fieldSource", a.fieldSourceX, a.fieldSourceY);
  set1("intensity", f.intensity);
  // The still frame. The app's plate walks two slow phases; t = 0 is the same engraving at
  // rest, which is what a rendered sheet should show.
  set1("phase", 0.0);
  set1("breath", 0.0);

  set1("faceWeight", f.weight);
  set1("isDark", dark ? 1 : 0);
  set1("family", f.face.geometryOrdinal);
  set1("darkDepth", M.GUILLOCHE_DARK_DEPTH);
  set1("darkLift", M.GUILLOCHE_DARK_LIFT);
  set1("darkGlow", M.GUILLOCHE_DARK_GLOW);
  set1("minVisibleDelta", M.GUILLOCHE_MIN_VISIBLE_DELTA);
  set1("lightTint", M.GUILLOCHE_LIGHT_TINT);
  set1("lightDepth", M.GUILLOCHE_LIGHT_DEPTH);
  set1("shimmerDepth", M.GUILLOCHE_SHIMMER);
  sp.waves.forEach((w, i) => {{
    set1("freq" + i, w.frequency);
    set1("cross" + i, w.cross);
    set1("crossFreq" + i, w.crossFreq);
    set1("angle" + i, w.angle);
    set1("weight" + i, w.weight);
    set1("share" + i, w.share);
    set1("drift" + i, w.drift);
  }});
  set2("fanCentre", sp.fan.x, sp.fan.y);
  set1("fanRays", sp.fan.rays);
  set1("fanReach", sp.fan.reach);
  set1("fanShare", sp.fan.share);
  set1("fanSpin", sp.fan.spin);
  set1("quietMargin", M.GUILLOCHE_QUIET_MARGIN);
  // No quiet regions: this is the open field. The app registers them from the screens
  // above, which a bare ground-plane render has none of.
  set1("quietCount", 0);
  for (let i = 0; i < 4; i++) {{
    set4("quiet" + i, 0, 0, 0, 0);
    set1("quietCover" + i, 0);
  }}

  // ── the field's own uniforms ───────────────────────────────────────────────
  FOLIO_MODEL.DAYLIGHT_POOLS.forEach((pool, i) => {{
    const colour = f.field.poolColors[pool.index];
    // shift = 0 (neutral daylight): no drift, and every pool at the field's own alpha.
    set3("poolColour" + i, colour);
    set2("poolCentre" + i, pool.fx, pool.fy);
    // A pool's radius is `fr * width` in the field's own pixels, which is the buffer here.
    set1("poolRadius" + i, pool.fr * BW);
    set1("poolAlpha" + i, f.field.poolAlpha);
  }});

  gl.viewport(0, 0, W * S, H * S);

  // ── pass 1: the field, with the plate composited over it ────────────────────
  gl.disable(gl.BLEND);
  gl.drawArrays(gl.TRIANGLES, 0, 3);

  // ── pass 2: the ambient film ───────────────────────────────────────────────
  const vsa = compile(gl.VERTEX_SHADER, vsrc);
  const fsa = compile(gl.FRAGMENT_SHADER, ambientSrc);
  if (!vsa || !fsa) return;
  const progA = gl.createProgram();
  gl.attachShader(progA, vsa);
  gl.attachShader(progA, fsa);
  gl.linkProgram(progA);
  if (!gl.getProgramParameter(progA, gl.LINK_STATUS)) {{
    fail("ambient link failed:\\n" + gl.getProgramInfoLog(progA));
    return;
  }}
  gl.useProgram(progA);
  const bufA = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, bufA);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const locA = gl.getAttribLocation(progA, "p");
  gl.enableVertexAttribArray(locA);
  gl.vertexAttribPointer(locA, 2, gl.FLOAT, false, 0, 0);

  const uA = (n) => gl.getUniformLocation(progA, n);
  gl.uniform2f(uA("size"), BW, BH);
  // colorA / colorB are the palette's `primary` and `tertiary`, per MainActivity.
  const ca = f.colors.primary, cb = f.colors.tertiary;
  gl.uniform3f(uA("colorA"), ca[0], ca[1], ca[2]);
  gl.uniform3f(uA("colorB"), cb[0], cb[1], cb[2]);
  gl.uniform1f(uA("intensity"), window.FOLIO.agsl.ambientIntensity);
  // The still frame, as with the plate: phase 0.
  gl.uniform1f(uA("phase"), 0.0);

  // The shader returns premultiplied rgb/a, so Skia's source-over is ONE / ONE_MINUS_SRC_ALPHA.
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
  gl.drawArrays(gl.TRIANGLES, 0, 3);

  document.title = "OK " + family + (light ? ":L" : ":D");
}})();
</script>
"""
    out = HERE / "render.html"
    out.write_text(page, encoding="utf-8")
    return out


def shoot(page: pathlib.Path, url_query: str, out: pathlib.Path, scale: int = 2) -> bool:
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        str(CHROME), "--headless=new", "--disable-gpu", "--enable-unsafe-swiftshader",
        "--no-sandbox", "--hide-scrollbars", "--disable-extensions",
        "--disable-features=Translate,BackForwardCache",
        f"--force-device-scale-factor={scale}",
        f"--window-size={W},{H}",
        "--virtual-time-budget=15000",
        f"--screenshot={out}",
        f"file:///{page.as_posix()}?{url_query}",
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    ok = out.exists() and out.stat().st_size > 0
    if not ok:
        print(f"  FAILED {url_query}: {r.stderr.strip()[:300]}")
    return ok


def main():
    page = build_page()
    faces = json.loads(read("faces.json"))["faces"]
    only = sys.argv[1] if len(sys.argv) > 1 else None

    shots = HERE / "shots"
    done = []
    for f in faces:
        key = f"{f['family']}:{'1' if f['light'] else '0'}"
        if only and key != only:
            continue
        name = f"{f['family']}-{'light' if f['light'] else 'dark'}"
        out = shots / f"{name}.png"
        q = f"face={f['family']}&light={'1' if f['light'] else '0'}"
        if shoot(page, q, out):
            done.append((name, f))
            print(f"  {name:<20} {f['geometry']:<12} "
                  f"{'dark ' if not f['light'] else 'light'}  {out.stat().st_size // 1024} KB")
    print(f"\n{len(done)} / {len(faces)} rendered into {shots}")
    return 0 if len(done) == len(faces) or only else 1


if __name__ == "__main__":
    raise SystemExit(main())
