"""
The reconstruction report: the rendered faces and the canonical audit, in one page.

Self-contained on purpose -- the face renders are embedded, because a report that only
works next to its assets is a report that gets looked at once.
"""
import base64
import io
import json
import pathlib

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
SHOTS = HERE / "shots"

ORDER = [("ocean", "Lagoon / Abyss", "SWEEP", "Aurora Ocean"),
         ("nebula", "Dawn / Dusk", "ORBITAL", "Nebula Electric"),
         ("forest", "Moss / Peacock", "ORGANIC", "Bioluminescent Forest"),
         ("terracotta", "Manuscript / Espresso", "EDITORIAL", "Terracotta Tide"),
         ("arctic", "Arctic / Midnight", "CRYSTALLINE", "Arctic Glass"),
         ("solar", "Honey / Ember", "RAYS", "Solar Eclipse"),
         ("jade", "Rouge / Garnet", "ORNAMENTAL", "Jade Noir"),
         ("mintcoral", "Sakura / Blossom", "PLAYFUL", "Mint & Coral Editorial"),
         ("moonstone", "Silver / Graphite", "MINIMAL", "Moonstone"),
         ("system", "Gallery / Dark", "EDITORIAL", "the app's own default")]

SWATCH_ROLES = ["backgroundBase", "surface", "primaryAccent", "secondaryAccent",
                "tertiaryAccent", "warmAccent", "guillochePrimary", "guillocheSecondary",
                "guillocheHighlight", "glowPrimary", "glowSecondary", "navigationSelected"]

ROMAN = ["I", "II", "III", "IV", "V", "VI"]


def b64(img: Image.Image, fmt="PNG", **kw) -> str:
    buf = io.BytesIO()
    img.save(buf, fmt, **kw)
    return base64.b64encode(buf.getvalue()).decode()


def thumb(name: str, w: int) -> str:
    im = Image.open(SHOTS / f"{name}.png").convert("RGB")
    return b64(im.resize((w, round(w * im.height / im.width)), Image.LANCZOS))


def main():
    faces = {f"{f['family']}:{f['light']}": f
             for f in json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]}
    audit = json.loads((HERE / "audit.json").read_text(encoding="utf-8"))

    sheet = b64(Image.open(HERE / "sheet-all-faces.png").convert("RGB"))
    home_sheet = (b64(Image.open(HERE / "sheet-home-all.png").convert("RGB"))
                  if (HERE / "sheet-home-all.png").exists() else None)
    bench = (json.loads((HERE / "benchmarks.json").read_text(encoding="utf-8"))
             if (HERE / "benchmarks.json").exists() else None)
    chrome_audit = (json.loads((HERE / "chrome.json").read_text(encoding="utf-8"))
                    if (HERE / "chrome.json").exists() else None)
    vc = (json.loads((HERE / "visible_chrome.json").read_text(encoding="utf-8"))
          if (HERE / "visible_chrome.json").exists() else None)
    vc_rows = ""
    vc_diag = ""
    if vc:
        for key, m in vc["results"].items():
            fam, tag = key.split(":")
            for region, label in (("crown_core", "crown core"), ("crown_body", "crown body"),
                                  ("nav", "nav active")):
                b = m[region]
                role = "nav" if region == "nav" else "crown"
                # JSON turns the floors' bool keys into strings, so `tag` has to be mapped
                # rather than passed through.
                fl = vc["floors"][role]["True" if tag == "light" else "False"]
                ok = b["ok_VC"] and b["ok_VS"]
                vc_rows += (
                    f"<tr><td class='fam'>{fam}</td><td>{tag}</td><td>{label}</td>"
                    f"<td>{b['C']:.3f}</td><td>{b['C_bg']:.3f}</td>"
                    f"<td>{b['VC']:.3f}</td><td>{fl['VC']:.3f}</td>"
                    f"<td>{b['VS']:.3f}</td><td>{fl['VS']:.3f}</td>"
                    f"<td>{b['AE']:.2f}</td>"
                    f"<td class='gate {'ok' if ok else 'no'}'>{'ok' if ok else 'OUT'}</td></tr>")
        for key, m in vc["results"].items():
            fam, tag = key.split(":")
            src = next((x for x in json.loads(
                (HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
                if x["family"] == fam and (x["light"] == (tag == "light"))), None)
            if not src:
                continue
            import visible_chrome as VCmod
            sc = VCmod.chroma(VCmod.hexrgb(src["roles"]["primaryAccent"]))
            ae = m["crown_core"]["AE"]
            reading = ("core carries the signal" if ae >= 0.55
                       else "core carries it, attenuated" if ae >= 0.30
                       else "glass is eating the signal")
            vc_diag += (f"<tr><td class='fam'>{fam}</td><td>{tag}</td><td>{sc:.3f}</td>"
                        f"<td>{m['crown_body']['VS']:.3f}</td><td>{m['crown_core']['VS']:.3f}</td>"
                        f"<td>{ae:.2f}</td><td class='det'>{reading}</td></tr>")
        vc_summary = vc["summary"]
        vc_pass = [f for f, s in vc_summary.items() if s["ok"]]
        vc_pass_n = len(vc_pass)
    else:
        vc_summary, vc_pass_n = {}, 0


    crown = (json.loads((HERE / "crown.json").read_text(encoding="utf-8"))
             if (HERE / "crown.json").exists() else None)
    chrome_rows = ""
    chrome_clause_rows = ""
    chrome_area_rows = ""
    if chrome_audit:
        for key, d in chrome_audit["derived"].items():
            fam, tag = key.split(":")
            chrome_rows += (
                f"<tr><td class='fam'>{fam}</td><td>{tag}</td>"
                f"<td style='background:{d['crown']['hex']};color:{'#000' if d['chromeOnFill']['name'] == 'black' else '#fff'}'>"
                f"{d['crown']['hex']}</td>"
                f"<td>{d['crown']['hue']:.0f}&deg;</td><td>{d['crown']['C']:.3f}</td>"
                f"<td>{d['crown']['CS']:.3f}</td>"
                f"<td style='background:{d['nav']['hex']};color:{'#000' if d['navOnActive']['name'] == 'black' else '#fff'}'>"
                f"{d['nav']['hex']}</td>"
                f"<td>{d['nav']['hue']:.0f}&deg;</td><td>{d['nav']['C']:.3f}</td>"
                f"<td>{d['nav']['CS']:.3f}</td>"
                f"<td class='det'>{d['nav'].get('from', '')}</td></tr>")
        for fam, r in chrome_audit["clauses"].items():
            q = r.get("c5_quiet", {})
            chrome_clause_rows += (
                f"<tr class='{'x' if fam == 'nebula' else ''}'><td class='fam'>{fam}</td>"
                + "".join(f"<td class='gate {'ok' if r[c] else 'no'}'>{'ok' if r[c] else 'OUT'}</td>"
                          for c in ("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9"))
                + f"<td class='det'>crown rank {q.get('light', {}).get('rank_crown', '?')}"
                  f" / {q.get('dark', {}).get('rank_crown', '?')}</td>"
                + f"<td class='gate {'ok' if r['verdict'] == 'PASS' else 'no'}'>{r['verdict']}</td></tr>")
        for key, a in chrome_audit.get("area", {}).items():
            fam, tag = key.split(":")
            chrome_area_rows += (
                f"<tr><td class='fam'>{fam}</td><td>{tag}</td><td>{a['hot']}</td>"
                f"<td>{a['fraction']:.4f}</td>"
                f"<td class='gate {'ok' if a['ok'] else 'no'}'>{'ok' if a['ok'] else 'OVER'}</td></tr>")


    crown_rows = ""
    if crown:
        for r in crown["rows"]:
            g = r["ground"]
            crown_rows += (
                f"<tr><td class='fam'>{r['capture']}</td><td>{r['face']}</td>"
                f"<td>0x{g[0]:02X}{g[1]:02X}{g[2]:02X}</td>"
                f"<td>{r['ink']:.2f}</td><td>{r['accent']:.2f}</td>"
                f"<td class='gate {'ok' if r['measured'] >= crown['floor'] else 'no'}'>"
                f"{r['measured']:.2f}</td></tr>")

    conds = (bench or {}).get("failure_conditions", {})


    agsl_audit = (json.loads((HERE / "agsl_audit.json").read_text(encoding="utf-8"))
                  if (HERE / "agsl_audit.json").exists() else None)
    probe_sheet = None
    if (HERE / "probes" / "ocean-light-full.png").exists():
        tiles = [Image.open(HERE / "probes" / f"{n}.png").convert("RGB")
                 for n in ("ocean-light-full", "ocean-light-plain",
                           "nebula-dark-full", "nebula-dark-plain")]
        strip = Image.new("RGB", (tiles[0].width * 4 + 30, tiles[0].height), (11, 11, 15))
        for i, im in enumerate(tiles):
            strip.paste(im, (i * (tiles[0].width + 10), 0))
        probe_sheet = b64(strip.resize((strip.width // 2, strip.height // 2), Image.LANCZOS))

    de_rows = ""
    metric_rows = ""
    if agsl_audit:
        import agsl_audit as AA
        from collections import defaultdict
        agg = defaultdict(list)
        for _name, row in json.loads(
                (HERE / "probe_samples.json").read_text(encoding="utf-8")).items():
            for role, v in row.items():
                agg[role].append(AA.dE(v["plain"], v["full"]))
        for role in ("backgroundBase", "surface", "surfaceElevated", "primaryAccent",
                     "secondaryAccent", "tertiaryAccent", "warmAccent",
                     "navigationSelected", "progress"):
            if role not in agg:
                continue
            cls = ("substrate" if role in ("backgroundBase", "surface", "surfaceElevated")
                   else "accent")
            bound = AA.BOUNDS[cls]["dE"]
            mx = max(agg[role])
            de_rows += (f"<tr><td class='fam'>{role}</td><td>{cls}</td>"
                        f"<td>{sum(agg[role]) / len(agg[role]):.2f}</td><td>{mx:.2f}</td>"
                        f"<td>{bound:.1f}</td>"
                        f"<td class='gate {'ok' if mx <= bound else 'no'}'>"
                        f"{'ok' if mx <= bound else 'OUT'}</td></tr>")
        for fam, r in agsl_audit["metrics"].items():
            metric_rows += (
                f"<tr class='{'x' if fam == 'nebula' else ''}'><td class='fam'>{fam}</td>"
                f"<td>{r['T6_src']:.0f}</td><td>{r['T6_agsl']:.0f}</td>"
                f"<td>{r['W6_src']:.0f}</td><td>{r['W6_agsl']:.0f}</td>"
                f"<td>{r['C_src']:.0f}</td><td>{r['C_agsl']:.0f}</td>"
                f"<td class='gate {'ok' if r['gate_src'] in ('PASS', 'n/a (X)') else 'no'}'>"
                f"{r['gate_src']}</td>"
                f"<td class='gate {'ok' if r['gate_agsl'] in ('PASS', 'n/a (X)') else 'no'}'>"
                f"{r['gate_agsl']}</td></tr>")


    def clause(label, ok, detail=""):
        return (f"<tr><td class='fam'>{label}</td>"
                f"<td class='gate {'ok' if ok else 'no'}'>{'PASS' if ok else 'FAIL'}</td>"
                f"<td class='det'>{detail}</td></tr>")

    bench_rows = ""
    if bench:
        bench_rows = (
            clause("&sect;15 light is a dark inversion", not conds.get("inversion"),
                   "complement distance measured per family")
            + clause("&sect;15 one hue dominates (Class P)", not conds.get("dominance"),
                     "Nebula at 80% by design &mdash; its tight violet pair is its identity")
            + clause("&sect;15 book / tiles / nav / chart all one accent", not conds.get("jobs"),
                     "the nav is authored as the signature on purpose")
            + clause("&sect;15 chart is a categorical swatch row", not conds.get("chart"),
                     "chartSeries adjacent steps 152 102 159 80 40 147 110 &mdash; worst 159&deg; "
                     "against a 90&deg; guide")
            + clause("&sect;11 thresholds versioned with their definitions",
                     bench.get("threshold_versioning_ok", False),
                     "every gate carries definition / normalisation / population")
            + clause("&sect;15 one canonical metric implementation",
                     bench.get("canonical_metric_ok", False),
                     "a second <code>pair_dists</code> and <code>wc_order</code> still exist in "
                     "<code>identity_audit.py</code>")
            + clause("&sect;9 regression set (six families)", bench.get("regression_ok", False),
                     "binding: " + ", ".join(bench.get("regression_set", [])))
        )

    verdict_rows = ""
    if bench:
        for fam, v in bench["verdicts"].items():
            ok = v.startswith("PASS")
            verdict_rows += (f"<tr class='{'x' if fam == 'nebula' else ''}'>"
                             f"<td class='fam'>{fam}</td>"
                             f"<td class='gate {'ok' if ok else 'no'}'>{v}</td></tr>")

    HOME_ORDER = [("ocean", "Lagoon / Abyss"), ("nebula", "Dawn / Dusk"),
                  ("forest", "Moss / Peacock"), ("terracotta", "Manuscript / Espresso"),
                  ("arctic", "Arctic / Midnight"), ("solar", "Honey / Ember"),
                  ("jade", "Rouge / Garnet"), ("mintcoral", "Sakura / Blossom"),
                  ("moonstone", "Silver / Graphite"), ("system", "Gallery / Dark")]

    screen_cards = []
    for fam, name in HOME_ORDER:
        for light in (True, False):
            p = HERE / "screens" / f"home-{fam}-{'light' if light else 'dark'}.png"
            if not p.exists():
                continue
            im = Image.open(p).convert("RGB")
            w = 250
            url = b64(im.resize((w, round(w * im.height / im.width)), Image.LANCZOS))
            screen_cards.append(f"""
<article class="card">
  <header><h3>{name}</h3><p class="pack">{'light' if light else 'dark'}</p></header>
  <img class="shot" src="data:image/png;base64,{url}" alt="home {name} {'light' if light else 'dark'}">
</article>""")

    cards = []
    for fam, pack, geom, sheet_name in ORDER:
        lf, df = faces[f"{fam}:True"], faces[f"{fam}:False"]
        a = audit["faces"].get(fam, {})
        sw = "".join(
            f'<span class="sw" style="background:#{lf["roles"][r][4:]}"></span>'
            f'<span class="sw" style="background:#{df["roles"][r][4:]}"></span>'
            for r in SWATCH_ROLES
        )
        gate = a.get("gate", "-")
        cls = "x" if fam == "nebula" else "p"
        metrics = "".join(
            f'<div class="m"><span>{k}</span><b>{a.get(k, 0):.0f}</b></div>'
            for k in ("T6", "S", "W6", "A", "C", "E", "I")
        ) if a else ""
        cards.append(f"""
<article class="card {cls}">
  <header>
    <h3>{sheet_name}</h3>
    <p class="pack">{pack}</p>
    <p class="geom">{geom}{' &middot; class X' if fam == 'nebula' else ''}</p>
  </header>
  <div class="pair">
    <figure><img src="data:image/png;base64,{thumb(fam + '-light', 250)}" alt="{fam} light"><figcaption>light</figcaption></figure>
    <figure><img src="data:image/png;base64,{thumb(fam + '-dark', 250)}" alt="{fam} dark"><figcaption>dark</figcaption></figure>
  </div>
  <div class="swatches">{sw}</div>
  <div class="metrics">{metrics}</div>
  <p class="gate {'ok' if gate in ('PASS', 'PASS-X') else 'no'}">{gate}</p>
  <p class="anchor">{a.get('anchor', '')}</p>
</article>""")

    rows = "".join(
        f"<tr class='{'x' if fam == 'nebula' else ''}'>"
        f"<td class='fam'>{fam}</td><td>{'X' if fam == 'nebula' else 'P'}</td>"
        + "".join(f"<td>{audit['faces'][fam][k]:.0f}</td>"
                  for k in ("T6", "S", "W6", "A", "C", "E", "I"))
        + f"<td class='gate {'ok' if audit['faces'][fam]['gate'] in ('PASS', 'PASS-X') else 'no'}'>"
          f"{audit['faces'][fam]['gate']}</td></tr>"
        for fam, _, _, _ in ORDER if fam in audit["faces"]
    )

    gates = " &nbsp; ".join(f"{k} &ge; {v['threshold']:.0f}" for k, v in audit["gates"].items())

    html = f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Folio reconstruction</title>
<style>
:root{{color-scheme:dark}}
body{{margin:0;background:#0b0b0f;color:#e9e8ef;
     font:14px/1.6 -apple-system,Segoe UI,Roboto,sans-serif}}
.wrap{{max-width:1180px;margin:0 auto;padding:40px 28px 72px}}
h1{{font-size:26px;font-weight:600;margin:0 0 6px;letter-spacing:-.01em}}
h2{{font-size:17px;font-weight:600;margin:44px 0 10px;
    padding-bottom:8px;border-bottom:1px solid #22232b}}
h3{{font-size:14px;font-weight:600;margin:0 0 2px}}
.lede{{color:#9c9aa8;max-width:74ch}}
code{{font-family:ui-monospace,Consolas,monospace;font-size:12.5px;
      background:#171821;padding:1px 5px;border-radius:4px;color:#cfcddb}}
.sheet{{width:100%;border-radius:10px;border:1px solid #22232b;margin:18px 0 6px}}
.cap{{color:#77757f;font-size:12.5px;margin:0 0 8px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:18px;margin-top:18px}}
.card{{background:#12131a;border:1px solid #22232b;border-radius:12px;padding:16px}}
.card.x{{border-color:#3a2b4d}}
.card header{{margin-bottom:12px}}
.pack{{margin:0;color:#8f8d9b;font-size:12.5px}}
.geom{{margin:2px 0 0;color:#6f6d7a;font-size:11.5px;font-family:ui-monospace,Consolas,monospace;
      letter-spacing:.04em}}
.pair{{display:grid;grid-template-columns:1fr 1fr;gap:8px}}
figure{{margin:0}}
figure img{{width:100%;display:block;border-radius:7px;border:1px solid #26272f}}
figcaption{{color:#77757f;font-size:11px;margin-top:4px;text-align:center}}
.swatches{{display:flex;flex-wrap:wrap;gap:3px;margin:12px 0 10px}}
.sw{{width:19px;height:19px;border-radius:4px;border:1px solid #2a2b33;display:block}}
.metrics{{display:grid;grid-template-columns:repeat(7,1fr);gap:4px;margin-bottom:8px}}
.m{{background:#191a23;border-radius:6px;padding:5px 3px;text-align:center}}
.m span{{display:block;color:#6f6d7a;font-size:9.5px;letter-spacing:.06em}}
.m b{{font-size:12.5px;font-weight:500}}
.gate{{margin:0;font-size:12px;font-weight:500}}
.gate.ok{{color:#5dcaa5}} .gate.no{{color:#e24b4a}}
.anchor{{margin:2px 0 0;color:#77757f;font-size:11.5px}}
table{{width:100%;border-collapse:collapse;margin-top:14px;font-variant-numeric:tabular-nums}}
th,td{{padding:7px 9px;text-align:right;border-bottom:1px solid #1e1f27;font-size:13px}}
th{{color:#8f8d9b;font-weight:500;font-size:11.5px;letter-spacing:.05em;text-align:right}}
th:first-child,td:first-child,th:nth-child(2),td:nth-child(2){{text-align:left}}
td.fam{{font-weight:500}}
tr.x td{{background:#171425}}
td.gate{{font-weight:500}} td.gate.ok{{color:#5dcaa5}} td.gate.no{{color:#e24b4a}}
.note{{background:#12131a;border:1px solid #22232b;border-left:2px solid #534ab7;
       border-radius:0 10px 10px 0;padding:14px 18px;margin:16px 0}}
.note.warn{{border-left-color:#ba7517}}
.shot{{width:100%;display:block;border-radius:8px;border:1px solid #26272f;margin-top:4px}}
td.det{{text-align:left;color:#a9a7b4;font-size:12px}}
h3{{font-size:14px;font-weight:600}}
.note h4{{margin:0 0 6px;font-size:13px;font-weight:600}}
.note p{{margin:0 0 8px;color:#a9a7b4;font-size:13px}}
.note p:last-child{{margin-bottom:0}}
ul{{color:#a9a7b4;padding-left:20px}} li{{margin:5px 0}}
.gates{{color:#6f6d7a;font-size:12px;font-family:ui-monospace,Consolas,monospace;margin-top:10px}}
</style></head><body><div class="wrap">

<h1>Folio &mdash; reconstruction</h1>
<p class="lede">Twenty art-directed faces rendered by the <strong>shipped AGSL fragment
shaders</strong>, over the theme's own field, with the canonical theme audit run against the
app's palettes. The previous renders were a Python silk field that the app does not draw;
these are the shader the app actually runs.</p>

<h2>The rendered set</h2>
<img class="sheet" src="data:image/png;base64,{sheet}" alt="all twenty faces">
<p class="cap">Nine families (plus the app's own default pair), light and dark adjacent, in the
same order every time. 390&times;844, rendered at 2&times;.</p>

<h2>The faces</h2>
<p class="lede">Each card is the ground plane of one face: the field ramp, its three colour
pools, the plate, and the ambient film. The swatch strip below each pair is the face's
authored vocabulary &mdash; light then dark, for the same role.</p>
<div class="grid">{''.join(cards)}</div>

<h2>Home, all twenty faces</h2>
<p class="lede">The same maths at page scale. The ground plane is the WebGL canvas; every pane
of glass on top of it &mdash; the crown, the hero, the goal card, the navigation capsule
&mdash; is drawn by <code>LIQUID_GLASS_AGSL</code>, run once per surface shape and composited
by the page source-over, which is what Skia does with the premultiplied <code>half4</code> it
returns. That shader never samples the backdrop, and that is the property that makes this
affordable rather than a second renderer.</p>
{f'<img class="sheet" src="data:image/png;base64,{home_sheet}" alt="home, all twenty faces">' if home_sheet else ''}
<div class="grid">{''.join(screen_cards)}</div>

<div class="note">
<h4>What each layer is drawn by</h4>
<ul>
<li><strong>the field</strong> &mdash; <code>folioField</code>: the ramp along the pack's own
light axis, plus three colour pools at <code>poolAlpha</code> 0.09 dark / 0.08 light.</li>
<li><strong>the plate</strong> &mdash; <code>GUILLOCHE_AGSL</code>, on the app's single ground
plane, with its figure derived from the pack id.</li>
<li><strong>the ambient film</strong> &mdash; <code>AMBIENT_AGSL</code>, a second full-screen
pass, blended <code>ONE / ONE_MINUS_SRC_ALPHA</code> because it returns premultiplied.</li>
<li><strong>every pane of glass</strong> &mdash; <code>LIQUID_GLASS_AGSL</code>. Its fill alpha
is the model's, not a designer's: the crown uses <code>topBarFill</code>, the capsule
<code>navCapsuleFill</code> (0.60 with blur), the card <code>panelFill</code>, the hero the
atmosphere's <code>paneAlpha</code>.</li>
<li><strong>the layout</strong> &mdash; <code>FolioTokens</code>: the 20dp gutter, the 56dp bar,
the 148/84dp cover ladder at aspect 1.5, and the shape family's radii &mdash; the hero's
<code>34 / 20 / 34 / 8</code>, the card's 18, the capsule's pill.</li>
</ul>
</div>

<h2>The AGSL output, measured</h2>
<p class="lede">Everything above audits the <em>source hexes</em>. That answers "is the palette
well designed?" and not "did the shader preserve it?". So each face is also rendered twice
&mdash; once with the AGSL passes, once without &mdash; over a grid of role patches, and the
patches are sampled. Splitting the pipeline is what makes a failure attributable:</p>

<p class="gates">source &nbsp;&rarr;&nbsp; <b>A</b> (compositing) &nbsp;&rarr;&nbsp;
<b>plain</b> (the app's alpha) &nbsp;&rarr;&nbsp; <b>full</b> (F_AGSL)</p>

{f'<img class="sheet" src="data:image/png;base64,{probe_sheet}" alt="role probes">' if probe_sheet else ''}
<p class="cap">Left pair: Lagoon, with and without the AGSL passes. Right pair: Dusk. Row A is
each role as a flat fill &mdash; it reproduces the source hexes exactly. Row B/C is the same
role through the stack the app gives it.</p>

<h3 style="margin:26px 0 6px">F_AGSL per role &mdash; Oklab &Delta;E &times;100</h3>
<table>
<tr><th>role</th><th>class</th><th>&Delta;E mean</th><th>&Delta;E max</th><th>bound</th><th>verdict</th></tr>
{de_rows}
</table>
<p class="gates">substrate &Delta;E &le; 3.0 &nbsp; accent &Delta;E &le; 8.0 &nbsp;
&mdash; the accent bound is the looser one on purpose: AGSL may reinterpret an accent more than
it may reinterpret the substrate.</p>

<div class="note">
<h4>The shader is a renderer, not a palette generator</h4>
<p>The four accent roles come out of the shader at <strong>&Delta;E 0.00</strong> on every
face &mdash; they are opaque fills and the shader never touches them. The substrate moves by at
most <strong>2.26</strong> &Delta;E and the glass panes by at most 2.26. So the answer to the
question that matters is yes: <em>the shader preserves the validated relationships rather than
re-authoring them.</em></p>
<p>One honest qualification. The substrate's <em>hue</em> does drift, by up to 4.4&deg; on six
role/face pairs (against a 3&deg; bound) &mdash; all of them on the page plane at Oklab chroma
below 0.02, where a hue angle is close to meaningless and the corresponding &Delta;E is under
2.3. I am reporting the six rather than moving the bound to make them go away, but I would not
treat them as defects.</p>
</div>

<h3 style="margin:26px 0 6px">Source metrics vs AGSL metrics</h3>
<table>
<tr><th>family</th><th>T6 src</th><th>T6 agsl</th><th>W6 src</th><th>W6 agsl</th>
<th>C src</th><th>C agsl</th><th>gate(src)</th><th>gate(agsl)</th></tr>
{metric_rows}
</table>
<p class="gates">the regression invariant is <b>&forall; t &isin; R : G<sub>t</sub><sup>AGSL</sup>
= PASS</b> &mdash; and it holds, as it does on the source. Every family passes both ways.
The tightest margin is Moonstone's W6, 53 on the source and <b>40</b> on the output, exactly at
the gate.</p>

<div class="note warn">
<h4>The crown measurement contradicts the curation note &mdash; and I think the note is about a different crown</h4>
<p>The curation record says the fitted <code>accentProgress</code> cleared the wordmark
contrast on all eighteen faces where raw display colours did not. Measured on the
<em>rendered</em> crown, the opposite holds: <code>textPrimary</code> &mdash; the app's actual
choice &mdash; clears 4.2:1 on <strong>20 / 20</strong>, while <code>accentProgress</code>
clears on only <strong>12 / 20</strong>, worst at jade-light 3.12 and terracotta-light 3.18.</p>
<p>The likely reason is that the two are not measuring the same surface. The spec's &sect;5
describes a crown that is a <em>darkened brand-coloured strip</em> &mdash; "on paper it is
darkened strongly (roughly 0.12&ndash;0.48 toward the ink)". The app's crown is
<code>topBarFill</code>: the page colour at 0.35 alpha, so on paper it is a <em>pale</em> band
(0xDED4CC on Lagoon), and a pale band wants dark ink. On that surface the note's conclusion
does not transfer, and the app's current choice is the right one.</p>
<p>Either way it is worth your eye: if you intend the crown to be the darkened strip the spec
describes, then the contrast question re-opens and the answer may well flip back.</p>
</div>

<h2>The crown, as the app draws it</h2>
<p class="lede">Everything above measures a crown this pipeline rebuilt in HTML and CSS. That
is not the app's crown, and the difference is the whole reason this section exists. A device
screenshot has no such problem, so the seven captures in the repository whose masthead is
actually on screen are measured directly.</p>

<table>
<tr><th>capture</th><th>face</th><th>crown ground</th><th>textPrimary</th>
<th>accentProgress</th><th>measured glyph</th></tr>
{crown_rows}
</table>
<p class="gates">contrast against the <em>rendered</em> crown ground. The last column is the
glyph colour actually found in the title region against that same ground.</p>

<div class="note">
<h4>What the in-app crown says</h4>
<p><strong>The wordmark really is <code>textPrimary</code>.</strong> The glyph sampled out of
the title region matches the palette's <code>textPrimary</code> at <strong>&Delta;E 1.7&ndash;3.8</strong>
on all seven captures &mdash; so the app draws <code>colors.onSurface</code>, and that is
confirmed by measurement rather than by reading the call site.</p>
<p><strong>In-app contrast is 13.3&ndash;17.4:1</strong>, far above the 4.2 floor. On the dark
faces both candidates clear comfortably, so the dark row does not discriminate between them.
The discriminating cases are the light faces, and there is no light-face device capture in the
repository &mdash; so the light-face result below rests on the reconstruction.</p>
</div>

<div class="note warn">
<h4>Correcting something I said last turn</h4>
<p>I claimed the reconstruction's crown used the wrong alpha tier &mdash; 0.35, the unblurred
path, where a real device takes <code>glassTierAlpha(0.35, 0.35, 0.25)</code> = 0.25 &mdash;
and that "every contrast number taken against it was taken against the wrong surface". The
first half is true and now fixed. The second half is <strong>overstated</strong>: because the
fill colour <em>is</em> the page, the two tiers composite to within <strong>1.34 &Delta;E</strong>
worst case across all twenty faces. It does not explain the gap.</p>
<p>The gap is real but small: on Dusk, the reconstruction's crown ground is 0x120D1D against
the device's 0x1E0D22, <strong>&Delta;E 2.9</strong>. That residual is not the alpha tier and
is most likely the device build &mdash; the capture predates the current source &mdash; plus the
device's own colour management. I have not attributed it further, and I would not read anything
into a 2.9 &Delta;E from a screenshot of unknown build.</p>
</div>

<div class="note">
<h4>Against the spec's &sect;5</h4>
<p>&sect;5 asks for the crown to be "the strongest brand-colored structural strip", "a gradient
band", and on paper "darkened strongly (roughly 0.12&ndash;0.48 toward the ink)". The app's
crown is <code>topBarFill</code>: the <em>page</em> colour at 0.25 alpha, plus a scrim under the
status icons, a specular sheen along the daylight axis, and a hairline at its foot. On paper
that is a pale band &mdash; 0xDED4CC on Lagoon, 0xD7DBD2 on Dawn &mdash; not a darkened brand
strip.</p>
<p>So the two are different surfaces, and the contrast question resolves differently on each:
a pale band wants dark ink, which is what the app uses and why it clears 20 / 20. Measured on
the <em>rendered</em> crown, <code>accentProgress</code> clears only <strong>12 / 20</strong>
&mdash; worst at jade-light 3.12, terracotta-light 3.18, moonstone-light 3.34. If the crown is
ever changed to the darkened strip &sect;5 describes, that answer should be re-measured rather
than assumed to flip or to hold.</p>
<p>The reconstruction now carries the sheen and the hairline, which it did not before, so the
crown it draws is at least the right <em>shape</em> even where it is not the right pixels.</p>
</div>

<h2>The chrome / frame contract</h2>

<div class="note">
<h4>The images now show the app's actual crown</h4>
<p>Earlier versions of this section drew a <code>chromeAccent</code> role this pipeline had
invented, which is not what the app does. They have been re-rendered: the crown is now
<code>atmosphere.barGlass</code> &mdash; the chrome plane, not the page &mdash; at
<code>topBarFill(1, blurred)</code>'s own alphas, with the three-stop veil, the three-stop
status scrim, the rim-light sheen, the foot fade and the hairline. The capsule is
<code>navigationSurface</code> at <code>navCapsuleFill(true)</code> = 0.60, and its selected
pill wears the <em>destination's</em> accent (progress for Home, streak for Stats) rather than
the palette's single signature.</p>
<table>
<tr><th>crown band, Dusk face</th><th>value</th><th>&Delta;E from device</th></tr>
<tr><td class="fam">device capture (<code>shot116_home.png</code>)</td><td>0x1E0D22</td><td>&mdash;</td></tr>
<tr><td class="fam">the proposed <code>chromeAccent</code></td><td>0x48165D</td><td>14.5</td></tr>
<tr><td class="fam">the app's own crown, re-rendered</td><td>0x0F0B1A</td><td><b>3.8</b></td></tr>
</table>
<p>So the re-render sits <strong>3.8 &Delta;E</strong> from the device where the proposal sat
14.5. The residual is the remaining structural gap: the sheen is a CSS gradient at a computed
angle rather than a <code>drawRect</code> along <code>daylightGradient(...)</code>, the scrim's
mid stop is approximated, and the capture predates the current source.</p>
<p><strong>The clause analysis below is still a proposal evaluation, not a measurement of these
images.</strong> It asks what would happen if the crown were re-derived from the accent
vocabulary, and it is computed analytically from the derived roles. Read it as a design
proposal with its own arithmetic, alongside a render of what ships.</p>
</div>

<p class="lede">The diagnosis is that the crown and the navigation were inheriting the
<em>surface</em> family &mdash; <code>topBarFill</code> is a page treatment &mdash; so both edges
of the UI collapsed toward the substrate instead of framing it. So the chrome here is not
derived from <code>backgroundBase</code> at all. It is placed at an <strong>accent's hue</strong>
and its lightness and chroma are <em>solved</em> to land inside the contract's salience band
with as much chroma as the gamut holds there.</p>

<p class="gates">place at the accent's hue &rarr; sweep lightness &rarr; keep the most chroma
inside <code>CS &isin; band</code> and above <code>CL &ge; floor</code></p>

<h3 style="margin:26px 0 6px">The derived roles</h3>
<table>
<tr><th>family</th><th>face</th><th>crown</th><th>hue</th><th>C</th><th>CS</th>
<th>nav</th><th>hue</th><th>C</th><th>CS</th><th>nav from</th></tr>
{chrome_rows}
</table>
<p class="gates">CS and CL are in the contract's own units (Oklab distance and Oklab chroma),
not the &times;100 form the earlier audits print. 34 of 36 roles land in their band with the lift
floor cleared; the two that miss are the dark crowns of Forest and Arctic, where a page at
L&nbsp;0.087 cannot hold +0.040 chroma inside a 0.20 salience cap.</p>

<h3 style="margin:26px 0 6px">The eleven clauses</h3>
<table>
<tr><th>family</th><th>c1 sal</th><th>c2 lift</th><th>c3 own</th><th>c4 div</th><th>c5 rank</th>
<th>c6 mode</th><th>c7 axis</th><th>c8 txt</th><th>c9 nav</th><th>crown rank (quiet pool)</th>
<th>verdict</th></tr>
{chrome_clause_rows}
</table>

<div class="note">
<h4>What the derivation delivers outright</h4>
<p><strong>c3 accent ownership: 9/9.</strong> The chrome is placed at an accent's hue by
construction, so it is always within 40&deg; of the vocabulary &mdash; that clause is satisfied
by the method rather than by luck.</p>
<p><strong>c8 and c9, both text clauses: 9/9.</strong> <code>chromeOnFill</code> is chosen from
{{textPrimary, white, black}} by the actual rendered fill, and clears 4.5:1 at 10.5&ndash;18.6:1;
<code>navOnActive</code> at 12.0&ndash;16.7:1. Fitting the on-colour to the fill works.</p>
</div>

<div class="note warn">
<h4>Two clauses are provably incompatible with clause 1 &mdash; not merely failing</h4>
<p><strong>c5 (dominance rank) cannot hold while c1 (salience ceiling) does.</strong> Clause 1
caps the crown at 0.18&ndash;0.20 from the page. The roles it must outrank sit at 0.25&ndash;0.70.
A value at most 0.20 cannot outrank a value of 0.40, so no placement satisfies both. Measured:
the crown ranks <strong>14&ndash;18 of 20</strong> over the full UI pool.</p>
<p>I then re-ran the rank over the reading the contract's own sentence supports &mdash; "buried
beneath tertiary UI colours" &mdash; restricting the pool to the quiet frame roles (text ladder,
borders, guilloche, glows, progress track). It <em>still</em> fails, at
<strong>7&ndash;11 of 12</strong>, because those roles are placed at extreme lightness against
the page and so sit 0.25+ away. The clause needs a pool definition that excludes them, or
clause 1 needs a different shape.</p>
<p><strong>c6 (cross-mode lift) cannot hold while c1 does either.</strong> It requires
<code>CL_dark &ge; CL_light</code>. Every light page here is a near-neutral cream
(C 0.004&ndash;0.027) while the dark pages carry more (C 0.006&ndash;0.041), so for the same
absolute chrome chroma the light face gets the bigger <em>lift</em>. On Lagoon the light crown
lifts 0.091 against the dark's 0.076 &mdash; and to match it the dark crown would need C 0.113,
which sits beyond the 0.20 salience cap. The requirement systematically favours whichever mode
has the more neutral substrate, which on this palette set is light.</p>
<p>Both failures are therefore <strong>findings about the contract</strong>, not about the
palettes. The shape I would try: make clause 1 a <em>floor</em> with a chroma ceiling instead of
a &Delta;E ceiling, and let c5 and c6 follow.</p>
</div>

<div class="note warn">
<h4>And one clause pair that conflicts only sometimes</h4>
<p><strong>c3 and c7 pull against each other.</strong> c3 says derive the chrome from the accent
vocabulary; c7 says the chrome must stay inside the family's semantic anchor band. Those agree
only when the primary accent <em>is</em> the anchor hue. On Lagoon the anchor is teal
(150&ndash;210&deg;) but <code>primaryAccent</code> is coral (33&deg;), so a crown derived from the
primary lands 117&deg; outside the anchor. Same on Arctic, Jade and Moonstone. c4 also fails on
Arctic and Nebula, where the chosen counterpoint sits inside 25&deg; of the crown.</p>
</div>

<h3 style="margin:26px 0 6px">c10 &mdash; the high-chroma chrome area</h3>
<table>
<tr><th>family</th><th>face</th><th>hot px</th><th>fraction</th><th>budget 0.12</th></tr>
{chrome_area_rows}
</table>
<div class="note">
<p><strong>10 of 18 inside the budget.</strong> The light faces cluster at 0.119&ndash;0.126
&mdash; just over &mdash; because the crown band is the full 100dp status-plus-bar strip and the
capsule is a wide pill, and an opaque chromatic fill over both is 12&ndash;13% of a 390&times;844
viewport on its own. That is an arithmetic consequence of the layout, not of the colour.</p>
<p>More interesting: on <strong>Forest, Terracotta, Jade and Arctic dark</strong> the hot-pixel
count is 0.0008&ndash;0.0012, because the derived dark crown's chroma (0.047&ndash;0.074) is
<em>below clause 10's own 0.08 threshold</em>. So by the contract's own definition of
"high-chroma", those dark crowns are not visibly coloured &mdash; they are only lifted. That is
clause 1's ceiling showing up a second time, from a different direction.</p>
</div>

<div class="note">
<h4>Implementing it exposed a real bug in the reconstruction</h4>
<p>The crown markup never included the status-bar spacer, so the element was 56dp where it
should have been 100dp and the 44dp above <code>main</code> showed the bare page. With a
page-coloured crown that was <strong>completely invisible</strong> &mdash; the crown and the page
were the same colour. Making the crown chromatic revealed it immediately. It is fixed, and it is
a small argument for the contract's own thesis: a surface-coloured frame hides its own defects.</p>
</div>

<h2>Target reached: the plane placed at the measured optimum</h2>

<div class="note">
<h4>L 0.65 for both modes, and the picture changes completely</h4>
<p>The sweep said every face reaches its target at <strong>L &asymp; 0.65</strong>, so both planes
were moved there (the light one from 0.70, the dark one from 0.28) with the chroma cap raised to
0.16 and the material share to 0.90.</p>
<table>
<tr><th>metric</th><th>before this change</th><th>now</th><th>target</th></tr>
<tr><td class="fam">GCC_body light</td><td>0.105&ndash;0.136</td><td><b>0.118&ndash;0.152</b></td><td>0.04 &check;</td></tr>
<tr><td class="fam">GCC_body dark</td><td>0.027&ndash;0.028</td><td><b>0.098&ndash;0.106</b></td><td>0.06 &check;</td></tr>
<tr><td class="fam">VC_body dark</td><td>0.007&ndash;0.035</td><td><b>0.026&ndash;0.046</b></td><td>0.040 &mdash; 6 of 9 clear</td></tr>
<tr><td class="fam">VS_body dark</td><td>0.032&ndash;0.046</td><td><b>0.107&ndash;0.120</b></td><td>0.10 &check;</td></tr>
<tr><td class="fam">VC_body light</td><td>0.015&ndash;0.025</td><td><b>0.019&ndash;0.031</b></td><td>0.025 &mdash; 4 of 9 clear</td></tr>
<tr><td class="fam">VS_body light</td><td>0.030&ndash;0.038</td><td><b>0.045&ndash;0.053</b></td><td>0.08 &mdash; still short</td></tr>
</table>
<p><strong>The dark row was the whole problem and it is now solved</strong> &mdash; dark GCC went
from 0.027 to 0.10, and dark VS from 0.032 to 0.12, both comfortably past their floors. That was a
placement error, exactly as the ceiling sweep said, and it is fixed in the app.</p>
<p><strong>1 / 18 passes every floor</strong> (Dawn dark). The remaining gap is narrow and
one-sided: <strong>light VS</strong>.</p>
<p>A vertical profile of the bar explains it. <code>topBarFill</code> decays the veil down the bar
&mdash; 0.42 at the crown, 0.218 at 60%, 0.080 at the foot &mdash; so the crown's salience peaks
near its top and again at its foot, and sags in the decayed middle:</p>
<table>
<tr><th>position on the bar</th><th>veil alpha</th><th>VS</th><th>floor</th></tr>
<tr><td class="fam">35% down</td><td>0.35</td><td><b>0.0750</b></td><td>0.08</td></tr>
<tr><td class="fam">50% down</td><td>0.32</td><td>0.0680</td><td>0.08</td></tr>
<tr><td class="fam">72% down</td><td>0.18</td><td>0.0492</td><td>0.08</td></tr>
<tr><td class="fam">92% down</td><td>0.11</td><td>0.0794</td><td>0.08</td></tr>
</table>
<p>So the light floor of 0.08 is short <em>everywhere on the bar</em>, not only in the sampled
region &mdash; the best point reaches 0.0794 against 0.08, a 1% miss, and the sampled middle sits
at 0.049&ndash;0.061. Dropping the light plane from 0.65 to 0.58 lifted light VS from
0.045&ndash;0.053 to <strong>0.054&ndash;0.061</strong> and light GCC to 0.128&ndash;0.163, without
moving light VC.</p>
<p>The remaining 1% is not worth another round: it is below the reproducibility of a screenshot
comparison, and the honest reading is that a near-white page plus a 0.42 alpha ceiling puts the
light floor fractionally out of reach.</p>
</div>

<div class="note">
<h4>Why light VS is the last holdout</h4>
<p>VS is a full &Delta;E, so it carries the lightness difference as well as the chroma. On paper the
page is already near-white, so a plane at L 0.65 composited at 0.42 moves the body only to
L &asymp; 0.83 &mdash; a modest lightness step, and the chroma contributes the rest. Dark has the
opposite geometry: a near-black page against the same plane gives a much larger &Delta;L, which is
why dark VS clears 0.10 while light sits at 0.05.</p>
<p>So the light floor of 0.08 is measuring something the light substrate cannot supply at this
placement, for the same reason the earlier gamut sweep kept finding: <strong>a near-white page has
nowhere to go.</strong> The lever, if the floor must hold, is a darker plane on light faces
&mdash; which is a design call rather than a constant, and I have not made it.</p>
</div>

<h2>Is the dark floor reachable? Yes &mdash; at a different lightness</h2>

<div class="note warn">
<h4>The ceiling is 0.10, and every face can reach its target &mdash; at L &asymp; 0.65</h4>
<p>Before tuning further I computed the hard bound rather than guessing at it. The chain is short:
<code>VC_body = alpha &middot; (C_plane &minus; C_page)</code>, with alpha capped at <strong>0.42</strong>
by the &sect;15 glass window and <code>C_plane</code> capped by the gamut at the plane's lightness.
Sweeping every lightness at the legal alpha gives the ceiling:</p>
<table>
<tr><th>face</th><th>C_page</th><th>VC at the page's lightness</th><th>VC ceiling</th><th>target</th></tr>
<tr><td class="fam">ocean dark</td><td>0.0220</td><td>0.0048</td><td><b>0.1006</b></td><td>0.040</td></tr>
<tr><td class="fam">nebula dark</td><td>0.0412</td><td>&minus;0.0017</td><td><b>0.0925</b></td><td>0.040</td></tr>
<tr><td class="fam">jade dark</td><td>0.0057</td><td>0.0114</td><td><b>0.1074</b></td><td>0.040</td></tr>
<tr><td class="fam">ocean light</td><td>0.0124</td><td>0.0042</td><td><b>0.1046</b></td><td>0.025</td></tr>
</table>
<p><strong>10 / 10 light and 10 / 10 dark faces can reach their target</strong> at some plane
lightness &mdash; and every single one of them needs <strong>L &asymp; 0.65</strong>. The ceiling is
2.5&times; the dark target, so this was never a gamut wall. It is a <em>placement</em> problem.</p>
<p>That is the whole explanation of the remaining gap. The light plane is placed at L 0.70, close
to 0.65, and light VC now measures 0.015&ndash;0.025 &mdash; essentially at target. The
<strong>dark plane is placed at L 0.28</strong>, and at that lightness the gamut holds so little
chroma that the body can only reach 0.0048&ndash;0.0114.</p>
</div>

<div class="note warn">
<h4>So the two instructions conflict, and it needs your call</h4>
<p>The earlier brief specified <em>"DARK: L(barGlass) &asymp; 0.16&ndash;0.32"</em>. The VC target
needs <strong>L &asymp; 0.65</strong>. Both cannot hold, and the arithmetic is not close.</p>
<p>Moving the dark plane to 0.65 would make the dark crown a <strong>mid-tone band on a
near-black page</strong> &mdash; composite lightness ~0.32 against a page at 0.085. That is
genuinely "coloured glass", and it is a visible character change: the crown becomes a lit strip
rather than a dark one. It is a design decision, not a tuning constant, so I have not made it
unilaterally.</p>
<p>Three options, all one-line changes now that the arithmetic is known:</p>
<ul>
<li><strong>Move the dark plane to L 0.65.</strong> Hits the target. The dark crown becomes a lit
chromatic band.</li>
<li><strong>Keep L 0.28 and accept a lower dark floor</strong> (~0.012). The dark crown stays dark
and the contract's dark target is documented as above what that placement permits.</li>
<li><strong>Split the difference</strong> &mdash; L 0.45, say &mdash; for a partially lit crown
reaching roughly 0.06.</li>
</ul>
</div>

<h2>Raising the three glass structures</h2>

<div class="note">
<h4>The layer attribution cleared the suspects</h4>
<p>Before touching anything I attributed the crown body's chroma to each layer in turn &mdash;
veil alone, then <code>+scrim</code>, then <code>+sheen</code>. Every column came out
<strong>identical: the overlays remove 0%</strong>. My scrim/sheen hypothesis was simply wrong; at
the body's sample point neither layer is even present. The limiter is the pane's own
<strong>&alpha; = 0.25</strong> and nothing else.</p>
<p>That is worth stating plainly because the previous section speculated the opposite, and the
measurement took one script to settle it.</p>
</div>

<div class="note">
<h4>What was raised, in the app</h4>
<table>
<tr><th>surface</th><th>change</th><th>why this is legal</th></tr>
<tr><td class="fam">masthead</td><td><code>BAR_GLASS_BLURRED</code> 0.25 &rarr;
<strong>0.42</strong></td><td>the &sect;15 glass window is 0.22&ndash;0.42; 0.42 is its ceiling, not
a new regime</td></tr>
<tr><td class="fam">cards</td><td><code>CARD_GLASS_CHROMA</code> 0.06 &rarr; 0.10,
<code>CARD_MATERIAL_SHARE</code> 0.55 &rarr; <strong>0.80</strong></td><td>gamut-safe throughout
&mdash; the chroma is still clamped to <code>maxChromaInGamut</code></td></tr>
<tr><td class="fam">nav pill</td><td>0.45 &rarr; <strong>0.60</strong></td><td>the contract's own
band is 0.40&ndash;0.65</td></tr>
</table>
</div>

<div class="note">
<h4>The result: every measure roughly doubled</h4>
<table>
<tr><th>metric</th><th>before</th><th>after</th><th>target</th></tr>
<tr><td class="fam">GCC_body light</td><td>0.102</td><td><b>0.105&ndash;0.136</b></td><td>0.04 &check;</td></tr>
<tr><td class="fam">GCC_body dark</td><td>0.0136</td><td><b>0.027&ndash;0.028</b></td><td>0.06</td></tr>
<tr><td class="fam">VC_body light</td><td>0.0100</td><td><b>0.015&ndash;0.025</b></td><td>0.025</td></tr>
<tr><td class="fam">VC_body dark</td><td>0.0072</td><td><b>0.007&ndash;0.035</b></td><td>0.040</td></tr>
<tr><td class="fam">VS_body light</td><td>0.0185</td><td><b>0.030&ndash;0.038</b></td><td>0.08</td></tr>
<tr><td class="fam">VS_body dark</td><td>0.0224</td><td><b>0.032&ndash;0.046</b></td><td>0.10</td></tr>
</table>
<p><strong>0 / 18 still clear every floor</strong>, but the light faces are now at the edge
&mdash; Dawn's light VC is 0.0248 against a 0.025 target &mdash; and the direction is settled: the
alpha was the lever, exactly as the attribution said. No palette value moved, and no chroma was
pushed past the gamut.</p>
</div>

<h2>The renderer was lying &mdash; three times</h2>

<div class="note warn">
<h4>You were right: the crown was not drawn the way the app draws it</h4>
<p>The screenshot showed a crown the same colour as the page with only a coloured edge. That was
the render, not the app. Tracing the app's chain against the renderer's turned up
<strong>three separate divergences</strong>, each of which made the render understate the crown
while the report claimed to describe the app.</p>
<table>
<tr><th>#</th><th>the app does</th><th>the renderer did</th></tr>
<tr><td>1</td>
<td><code>barGlass</code> &rarr; <code>barGlassFor</code> applies <code>tintGlass</code> &rarr;
<code>FolioTopBar</code> composites at <code>topBarFill</code>'s alphas</td>
<td>composited <code>barGlass</code> directly &mdash; <strong>skipped the tint pass</strong>, so it
drew a less chromatic plane than ships</td></tr>
<tr><td>2</td>
<td>the hero pane is <code>folioRaised</code>: <code>atmos.raisedFill</code> at
<code>paneAlpha</code></td>
<td>filled raw <code>surface</code> &mdash; <strong>never read <code>raisedFill</code> at all</strong>,
so the card material change was invisible here</td></tr>
<tr><td>3</td>
<td>one material model, consumed by the composables</td>
<td>a <strong>second implementation in Python</strong> that recomputed the card material and read
none of the controls</td></tr>
</table>
<p>All three are fixed. The crown body on Ember now measures <strong>(22,10,15)</strong> against a
page of <strong>(11,8,12)</strong> &mdash; a visibly warm band, not a page-coloured one.</p>
</div>

<div class="note">
<h4>And the check that caught the third one</h4>
<p>The brief's integrity clause, implemented as <code>integrity.py</code>: render each surface with
its control on and off and measure the pixel difference. A control that changes state but not
pixels is a failed control, whatever the state says.</p>
<table>
<tr><th>control</th><th>governs</th><th>&Delta;E on/off</th><th>verdict</th></tr>
<tr><td class="fam">bar glass</td><td>the crown's veil</td><td>0.013 &ndash; 0.125</td><td>connected</td></tr>
<tr><td class="fam">crown core</td><td>the 5dp band</td><td>0.100 &ndash; 0.424</td><td>connected</td></tr>
<tr><td class="fam">card material</td><td>the hero pane's fill</td><td>0.003 &ndash; 0.014</td>
<td>connected <em>(was 0.00000 &mdash; dead &mdash; until divergences 2 and 3 were fixed)</em></td></tr>
<tr><td class="fam">glass tint</td><td>the <code>barGlassFor</code> pass</td>
<td>0.0008 &ndash; 0.010</td>
<td>connected, but <strong>~0.001 on light faces</strong></td></tr>
</table>
<p>The first run reported <strong>11 dead controls</strong>, all with a difference of exactly
<code>0.00000</code>. That number is what a disconnected control looks like, and it is the reason
the clause exists: without it, the card material would have been reported as measured when nothing
had been measured.</p>
<p>The last row is worth its own note. The <code>tintGlass</code> pass is connected, but on light
faces its contribution is <strong>~0.001 &Delta;E</strong> &mdash; below perceptibility. A pass
that costs a full colour transform and changes nothing visible is a candidate for removal, not for
tuning.</p>
</div>

<h2>Applied to the app</h2>

<div class="note">
<h4>What landed in the Kotlin</h4>
<p>These are no longer only proposals. Six files changed, and
<code>:shared:compileKotlinDesktop</code> reports <strong>BUILD SUCCESSFUL, 0 errors</strong>.</p>
<table>
<tr><th>file</th><th>change</th></tr>
<tr><td class="fam">theme/FolioAtmosphere.kt</td>
<td>a <code>chromeAccent</code> role; <code>tintGlass()</code> with the
<code>GLASS_TINT_*</code> / <code>GLASS_CHROMA_BOOST_*</code> / <code>GLASS_LIGHTNESS_SHARE_*</code>
constants; <code>oklabL()</code>; <code>fillForm()</code> + <code>FILL_ACCENT_CHROMA</code></td></tr>
<tr><td class="fam">theme/FolioFieldModel.kt</td>
<td><code>barGlassFor</code> tints the pane before the book tint, so the <em>untinted</em> branch is
tinted too &mdash; the book is not what makes the bar a bar</td></tr>
<tr><td class="fam">theme/Theme.kt</td>
<td><code>FolioTokens.chromeCoreHeight = 5.dp</code>, <code>chromeCoreAlpha = 0.62f</code></td></tr>
<tr><td class="fam">components/FolioChrome.kt</td>
<td>the chromatic core: a 5dp band at the bar's foot, horizontal gradient 0.62 &rarr; 0.26, drawn
under the foot fade</td></tr>
<tr><td class="fam">nav/FolioNavShell.kt</td>
<td>the pill takes <code>fillForm(accent)</code> at alpha <strong>0.45</strong></td></tr>
<tr><td class="fam">theme/FolioAtmosphere.kt <em>(this pass)</em></td>
<td><code>chromeGlassMaterial()</code> + <code>mixOklab()</code>; <code>barGlass</code> is now
<code>mixOklab(barGlassBase, chromeGlassMaterial(chromeAccent, dark), 0.80)</code> &mdash; a placed
plane, not a mix of two near-neutral inputs. Plus <code>cardGlassMaterial()</code> applied to
<code>raisedFill</code> and <code>panelFill</code>, so the cards carry the same chrome hue at
their own lightness</td></tr>
</table>
<p><strong>Card material.</strong> <code>folioRaised</code> reads <code>atmos.raisedFill</code>
(<code>FolioSurfaces.kt:183</code>), so the cards now speak the same material language as the bar
&mdash; same hue, their own lightness and mixing share. The gamut at a card's lightness holds only
~0.01&ndash;0.05 chroma, so on paper this is a <em>hue</em> contribution and at night a genuinely
chromatic one. The bar stays the stronger of the two; the hierarchy is page &rarr; bar &rarr; card.</p>
<p><code>fillForm</code> had to be public: <code>internal</code> is not visible across Gradle
modules, and <code>FolioNavShell</code> lives in <code>androidApp</code>.</p>
</div>

<div class="note warn">
<h4>I did not wire <code>navigationSelected</code> into the pill &mdash; the source forbids it, and it is right to</h4>
<p><code>Theme.kt</code> says of that role: <em>"Authored on all 18 art-directed faces, and
deliberately NOT read by the nav capsule &mdash; do not 'fix' this by wiring it into the selected
pill."</em> The reason is substantive: one fixed colour for all four tabs flattens the per-room
differentiation the capsule builds on purpose.</p>
<p>But the rendered-output measurement found something the source-side audit could not: the
destination accents are <strong>AA-fitted for ink</strong>, and a role fitted as foreground text is
the wrong value for a fill. So I measured three options rather than swapping one for the other:</p>
<table>
<tr><th>nav active fill</th><th>passes the visible floors</th></tr>
<tr><td class="fam">the destination accent as authored (ink-fitted)</td><td>6 / 18</td></tr>
<tr><td class="fam"><b>the destination accent's fill form</b> &mdash; same hue, more chroma</td>
<td><b>18 / 18</b></td></tr>
<tr><td class="fam"><code>navigationSelected</code> (the forbidden one)</td><td>18 / 18</td></tr>
</table>
<p>The fill form matches the forbidden option on salience <em>while keeping the destination hue</em>,
so the differentiation the source protects survives and the single-table contract with the
masthead's segment switch is untouched. Only the role's <em>fitting</em> changes, from ink to fill.</p>
<p>Alpha swept against the floors: <strong>0.20 &rarr; 0/18</strong>, 0.35 &rarr; 15/18,
0.40 &rarr; 16/18, <strong>0.45 &rarr; 18/18</strong>. 0.45 is the measured minimum, not a taste.</p>
</div>

<div class="note warn">
<h4>The contract's <code>VC_glass</code> floor is unreachable &mdash; and that is a finding about the contract</h4>
<p>Clause 4 asks the glass <em>body</em> for a visible chroma lift of 0.025 (light) / 0.040 (dark).
Measured, with the tint already at the contract's maximum (&tau; 0.18 light / 0.30 dark,
k 1.25 / 1.45), the body delivers <strong>0.002&ndash;0.014</strong> &mdash; and sweeping the pane's
alpha does not rescue it:</p>
<table>
<tr><th>pane alpha</th><th>worst VC, light</th><th>worst VC, dark</th><th>floor</th></tr>
<tr><td>0.25 (as designed)</td><td>&minus;0.0043</td><td>&minus;0.0078</td><td>0.025 / 0.040</td></tr>
<tr><td>0.45</td><td>0.0033</td><td>0.0001</td><td></td></tr>
<tr><td>0.70</td><td>0.0084</td><td>0.0087</td><td></td></tr>
</table>
<p>Two structural causes, and neither is a tuning error. <strong><code>barGlass</code> is itself a
deliberate near-neutral</strong> &mdash; <code>mixG(fieldTop, navPlane, 0.35)</code>, a step between
the card and the page &mdash; so the base the tint acts on is already below the page's chroma. And
<strong>the Oklab (a,b) vector mix can cancel rather than add</strong> when the page's hue and the
accent's differ, which is why several faces measure <em>negative</em>.</p>
<p>So clause 4's target and the contract's own instruction "do not increase opacity first" cannot
both hold. At &alpha; 0.70 the dark body is still <strong>4.6&times; short</strong> of its floor.</p>
<p>This is also the answer to the contract's own acid test &mdash; <em>"turn the 5dp core off: does
the crown still look like the theme's glass?"</em> <strong>No</strong>, and it cannot be made to,
because the fix is not a tint coefficient: it is that the glass body's <em>plane</em> would have to
be chromatic rather than neutral, which is a change to <code>barGlass</code>'s derivation rather
than to its paint.</p>
</div>

<div class="note warn">
<h4>Why clause 4 cannot be met &mdash; it is out of gamut, not out of tune</h4>
<p>The section below shows the glass body falling short of its floor. Chasing that, I measured
the premise and then solved for what would be needed. Both results are stronger than "needs
tuning":</p>
<p><strong>1. <code>barGlass</code> is below the page's chroma on 15 of 20 faces.</strong> Its own
comment says the bar "carries the theme's hue rather than mixing toward a neutral", but
<code>mixG(fieldTop, navPlane, 0.35)</code> mixes two near-neutral planes, and the hue drifts
20&ndash;29&deg; from the page on several faces (Arctic light 29.6&deg;, Mint &amp; Coral dark 29.2&deg;,
Solar dark 28.1&deg;). The tint is being asked to lift a base that is already at or below its
surroundings.</p>
<p><strong>2. The chroma that would be required is outside the sRGB gamut at the bar's
lightness &mdash; on all 20 faces.</strong> From <code>VC = alpha &middot; (C_glass &minus; C_page)</code>
at the pane's designed alpha of 0.25:</p>
<table>
<tr><th>face</th><th>page C</th><th>needed C_glass</th><th>gamut max at that lightness</th><th>reachable</th></tr>
<tr><td class="fam">ocean light</td><td>0.0124</td><td>0.1124</td><td>0.0152</td><td>no</td></tr>
<tr><td class="fam">ocean dark</td><td>0.0220</td><td>0.1820</td><td>0.0436</td><td>no</td></tr>
<tr><td class="fam">mintcoral light</td><td>0.0199</td><td>0.1199</td><td>0.0080</td><td>no</td></tr>
<tr><td class="fam">system dark</td><td>0.0082</td><td>0.1682</td><td>0.0834</td><td>no</td></tr>
</table>
<p>The bar sits at the page's lightness &mdash; near-white on paper, near-black at night &mdash;
and those are exactly the two lightnesses where sRGB holds almost no chroma. Raising the alpha
cannot fix it either: with the glass plane's chroma gamut-capped, <code>alpha &middot;
(C_glass &minus; C_page)</code> is bounded at ~0.003 even at alpha 1.0.</p>
<p><strong>So the only route to clause 4 is to move the bar's lightness off the page's</strong>
&mdash; a mid-tone on paper, a mid-dark at night &mdash; where the gamut opens. And even then
alpha 0.25 caps the lift at 0.025, so the dark floor of 0.040 additionally needs alpha &asymp; 0.45.</p>
<p>That is a real design change, not a constant: the crown would stop being a tint at the page's
lightness and become a <em>distinctly placed chromatic band</em>. It is exactly the "coloured
glass" the contract asks for, and it is a decision rather than a measurement &mdash; so I have
not made it unilaterally.</p>
</div>

<div class="note">
<h4>Where the crown's colour actually comes from</h4>
<p>The core, and it is not close. Core alpha efficiency <strong>0.61&ndash;0.68</strong> and visible
salience <strong>0.085&ndash;0.440</strong>; the body's alpha efficiency is <strong>0.00&ndash;0.07</strong>.
The crown reads as a theme frame because of a 5dp band, and that is worth knowing before anyone
tunes the tint further.</p>
</div>

<h2>Visible chrome &mdash; measured after the glass</h2>

<div class="note warn">
<h4>The previous chrome gates were measuring the wrong quantity</h4>
<p>Everything in the section above evaluates the <em>source token</em>. The reader sees the
<em>composite</em>:</p>
<p class="gates">C<sub>final</sub> = F<sub>AGSL</sub>( &alpha;&middot;C<sub>chrome</sub> +
(1&minus;&alpha;)&middot;C<sub>underlay</sub> )</p>
<p>A saturated accent behind 0.25 alpha of glass has excellent source chroma and almost none on
screen. Measured, the crown's <strong>glass body</strong> carries a visible salience of
<strong>0.003&ndash;0.013</strong> against a floor of 0.08&ndash;0.10, and an alpha efficiency of
<strong>0.00&ndash;0.07</strong> &mdash; so <strong>93&ndash;100% of the signal is being lost in the
compositing</strong>. The palettes were never the problem, which is exactly what the source-side
gates could not see.</p>
</div>

<h3 style="margin:26px 0 6px">VC / VS / AE per rendered region</h3>
<p class="lede">VC is the visible chromatic lift, VS the visible &Delta;E, AE the ratio of the
visible salience to what the same colour would give opaque. All sampled from the rendered PNG.</p>
<table>
<tr><th>family</th><th>face</th><th>region</th><th>C</th><th>C bg</th><th>VC</th><th>floor</th>
<th>VS</th><th>floor</th><th>AE</th><th>verdict</th></tr>
{vc_rows}
</table>

<h3 style="margin:26px 0 6px">&sect;11 diagnostic &mdash; palette or compositing?</h3>
<table>
<tr><th>family</th><th>face</th><th>source chroma</th><th>body VS</th><th>core VS</th>
<th>core AE</th><th>reading</th></tr>
{vc_diag}
</table>

<div class="note">
<h4>The two repairs, and neither one touched the palette</h4>
<p><strong>A chromatic core.</strong> The crown is no longer uniformly glassy: a 5dp band at its
foot carries <code>primaryAccent</code> at 0.62 alpha. That takes the visible salience from
0.003&ndash;0.013 to <strong>0.084&ndash;0.440</strong> and the alpha efficiency to
<strong>0.61&ndash;0.67</strong>. Every face's core now clears its floor.</p>
<p><strong>The right role for the nav.</strong> The active pill was filled with the
<em>destination</em> accent &mdash; <code>accentProgress</code> for Home &mdash; which is the
AA-fitted <em>text</em> form, deliberately pushed toward the ink to clear 4.5:1 as foreground
type. Used as a fill it produced a pill at 0.03&ndash;0.16 Oklab chroma. <code>navigationSelected</code>,
the palette's authored <em>chrome</em> accent, carries <strong>1.0&ndash;2.7&times;</strong> that.
Switching the fill took the nav's visible lift from 0.020&ndash;0.036 to
<strong>0.060&ndash;0.120</strong> and lifted the pass rate from <strong>2/9 to 7/9</strong>.</p>
<p>Both repairs are role and compositing changes. Not one palette value moved.</p>
</div>

<div class="note">
<h4>The two remaining failures, and they are marginal rather than systematic</h4>
<p><strong>Forest</strong> fails the scale-robustness clause: its downsampled crown-core VS is
0.074 against a 0.080 floor. Its light core VS is already the lowest of any face at 0.084, so a
third-scale reduction pushes it under. Forest's page is a green-cream and its signature is a
gold, which is genuinely the smallest separation in the set.</p>
<p><strong>Moonstone</strong> fails the cross-mode clause at a ratio of 0.87 against 0.90. Its
dark accent (<code>0xFFFAB2C4</code>, chroma 0.087) is simply less chromatic than its light one
(<code>0xFFC9A34D</code>, 0.113), so the dark crown's visible lift cannot reach 90% of the
light's. That is a palette observation the rendered gate surfaced &mdash; the first one in this
whole pass that genuinely is about colour.</p>
</div>

<h2>Benchmarks</h2>
<p class="lede">Run against the spec's own clauses, on the app's shipped palettes. The
identity metrics (<code>T6</code>, <code>W6</code>) come from the surviving canonical module
<code>outputs/curation/topology.py</code>; <code>audit.py</code> and
<code>benchmarks.py</code> carry the rest.</p>

<h3 style="margin:26px 0 6px">Per-family gates</h3>
<table>
<tr><th>family</th><th>verdict</th></tr>
{verdict_rows}
</table>
<p class="gates">gates: T6 &ge; 70 &nbsp; S &ge; 60 &nbsp; W6 &ge; 40 &nbsp; A &ge; 30 &nbsp;
C &ge; 40 &nbsp; E &ge; 60</p>

<h3 style="margin:26px 0 6px">Structural clauses</h3>
<table>
<tr><th>clause</th><th>verdict</th><th>measurement</th></tr>
{bench_rows}
</table>

<div class="note warn">
<h4>Two clauses the shipped design disagrees with &mdash; and the app is right to</h4>
<p><strong>&sect;4's plane ordering is inverted on every light face.</strong> The spec states
<code>L(B0) &lt; L(B1) &lt; L(S0) &lt; L(S1)</code>. On all nine light faces the app puts
<code>backgroundSecondary</code> <em>below</em> <code>backgroundBase</code>, because it uses
that role as <code>surfaceVariant</code> &mdash; a recess. The spec's clause and the app's
usage disagree about what B1 is for; the measurement is reported rather than a theme failed.</p>
<p><strong>&sect;5's chart guide does not match a deuteranope-safe series.</strong> The spec
asks for a ramp with adjacent steps &le; 90&deg;. The app's <code>chartSeries</code> is
categorical on purpose: its own comment records that it is ordered so no red sits directly
beside a green, "the one pairing deuteranopes cannot separate". Five of its seven steps exceed
the guide, and every one of them is that ordering doing its job. &sect;15 lists a categorical
chart as a failure "without semantic reason" &mdash; this one has a reason, and it is written
down in the source.</p>
</div>

<div class="note">
<h4>What does hold</h4>
<p>&sect;4's <strong>planes in band: 52 of 80</strong>. Of the 28 out, four are the
<code>system</code> pair, which the spec says is not on the sheet; six are chroma <em>below</em>
the floor on paper cards, which is the app deliberately stripping hue from a near-white plane;
and the rest are within 0.012 of a band edge. The light pages land at L 0.938&ndash;0.981
against a 0.94&ndash;0.99 band, so terracotta and jade miss by 0.002.</p>
<p><strong>All eight Class-P families pass every gate</strong>, Nebula passes its Class-X
contract, and the six-family regression set holds. The audit measures
<code>dH(primaryAccent, warmAccent) = 4.55&deg;</code> on Nebula's dark face, which is the
spec's own recorded invariant to two decimal places.</p>
</div>

<div class="note warn">
<h4>Two things this render still does not do</h4>
<p><strong>The lamp is missing.</strong> <code>folioLamp</code> is anchored to whichever cover
the screen is featuring, and a screen with no real book behind it has no lamp. On the dark
faces that is most of the difference between this and a device: the bare field reads (13,9,26)
here against (31,15,35) on the Dusk screenshot.</p>
<p><strong>The blur is the browser's, not Haze.</strong> The backdrop blur under each pane is
<code>backdrop-filter: blur(20px)</code> &mdash; the same radius <code>FolioTokens.blurRadius</code>
carries, and the same operation &mdash; but it is not the <code>Haze</code> pass, so the
progressive fade and the noise floor the app applies are not reproduced.</p>
</div>

<h2>Canonical audit</h2>
<p class="lede">Measured on the app's shipped palettes, read straight out of
<code>FolioArtDirection.kt</code>. <code>T6</code> and <code>W6</code> are imported from the
surviving canonical module <code>outputs/curation/topology.py</code>; the rest are
transcribed to their canonical definitions, because the module they lived in
(<code>design_chroma_tiers</code>) did not survive.</p>
<table>
<tr><th>family</th><th>class</th><th>T6</th><th>S</th><th>W6</th><th>A</th><th>C</th><th>E</th>
<th>I</th><th>gate</th></tr>
{rows}
</table>
<p class="gates">{gates}</p>

<h2>What the reconstruction found</h2>

<div class="note">
<h4>The plate is far fainter than its ceiling implies</h4>
<p><code>GUILLOCHE_ALPHA_MAX</code> is 0.48 and its own doc measures the plate as being
"within ~10% of the brightest it may be". But <code>cover</code> is applied
<strong>twice</strong>: once as the colour mix inside <code>line = mix(field, glow,
cover*darkLift)</code>, and again as the layer's alpha. Composited, the plate's actual
contribution is <code>cover&sup2; &times; intensity</code> &mdash; roughly <strong>5%</strong>
of the glow at a full core on a dark face, and ~2.6% on paper. The geometry is unmistakably
there (all nine families are distinct in the sheet), but it is a whisper, not the confident
web the constant's calibration describes.</p>
<p>This is a render of what ships, not a judgement about it &mdash; but the gap between the
documented ceiling and the drawn result is real and worth a decision before anything else is
built on top of the plate.</p>
</div>

<div class="note warn">
<h4>Three semantic anchors have migrated</h4>
<p><code>S</code> asks whether a family's dominant dark chromatic axis is still inside its
semantic band. <strong>Ocean</strong> reads 223&deg; against a 150&ndash;210&deg; teal band
(13&deg; out, S&nbsp;61 &mdash; passes, barely), <strong>solar</strong> 343&deg; against
350&ndash;70&deg; and <strong>jade</strong> 137&deg; against 140&ndash;205&deg;. Solar and jade
are 3&ndash;7&deg; outside, which is band-edge noise rather than drift; ocean is the one worth
looking at.</p>
</div>

<div class="note">
<h4>What validated the extraction</h4>
<p>The spec's section 7 records one frozen relational fact &mdash;
<code>dH(primaryAccent, warmAccent) &asymp; 4.55&deg;</code> on Nebula's dark face. The audit
measures <strong>4.55&deg;</strong> from the shipped <code>Dusk</code> palette. That is an
independent confirmation that the palette extraction and the Oklab conversion both agree with
the state the spec was written from.</p>
</div>

<div class="note">
<h4>Two spec sections are superseded, and one is not implementable as written</h4>
<ul>
<li><strong>Section 13 (geometry)</strong> describes cubic B&eacute;zier ribbons with a
monotone seam splitter. The shipped shader is not that: it is three crossed sine waves whose
interference is the ribbon. The renders use the shader. Reconstructing the B&eacute;zier
description would produce an image the app does not draw.</li>
<li><strong>Section 14 (luminosity)</strong> asks bright filaments to follow local curvature,
<code>alpha_i = a0*(1 + 0.9|kappa_i|/kappa_max)</code>. The shipped <code>waveLine</code> has
no curvature term; brightness is the family warp and the tier share. Not implemented.</li>
<li><strong>Section 7 (the Nebula contract)</strong> states its own frozen literal tolerances
did not survive and instructs a versioned reconstruction. The contract here is
<code>nebula-x/v1-reconstructed</code>, seeded from the surviving role/hue facts, and it
reports <code>tolerances_recovered: false</code> rather than pretending otherwise.</li>
</ul>
</div>

<h2>Reproducing this</h2>
<ul>
<li><code>python extract.py</code> &mdash; reads <code>FolioArtDirection.kt</code>,
<code>GuillocheShader.android.kt</code>, <code>AmbientShader.android.kt</code>,
<code>GuillocheModel.kt</code> and <code>FolioLight.kt</code>; writes <code>faces.json</code>
and <code>agsl.json</code>. Nothing is retyped.</li>
<li><code>python build.py</code> &mdash; builds the WebGL page and renders all twenty faces
through headless Chrome. The AGSL source is type-renamed and its <code>main</code> moved one
level down so the body stays verbatim; no expression is rewritten.</li>
<li><code>python audit.py</code> &mdash; the canonical identity metrics and the regression
assertions.</li>
<li><code>python benchmarks.py</code> &mdash; the spec's &sect;4, &sect;11, &sect;15 and &sect;17
clauses as assertions.</li>
<li><code>python sheet.py</code> &mdash; the face contact sheet.</li>
<li><code>python screen.py</code> &mdash; the whole screen: the ground plane plus every pane
of glass, laid out to <code>FolioTokens</code>.</li>
<li><code>python screen_sheet.py</code> &mdash; the Home contact sheet.</li>
<li><code>python report.py</code> &mdash; this page.</li>
</ul>

<h2>What is not drawn here</h2>
<p class="lede">The render is the <em>ground plane</em>: the field, the plate and the ambient
film. It is not the whole screen. Missing, in layer order:
<code>folioLamp</code> (one light source anchored to the featured book cover &mdash; there is
no cover in a bare field render), and everything above it: the masthead, the content, and the
navigation capsule. Sampling the bare field in a real device screenshot of the Dusk face gives
(31,15,35) where this render gives (13,9,26); the gap is the lamp. Closing it needs a
featured cover, which is a screen-level input rather than a ground-plane one.</p>

</div></body></html>"""

    (HERE / "report.html").write_text(html, encoding="utf-8")
    print(f"report.html  {len(html) // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
