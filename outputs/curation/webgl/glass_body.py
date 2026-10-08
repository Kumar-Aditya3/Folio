"""
The glass body's acceptance test: A / B / C / D, measured from rendered pixels.

The contract's clause 10 is explicit that the 5dp core must not be allowed to make the body
pass, so the four states are rendered separately rather than inferred:

    A  the page alone                      (the local background every distance is measured to)
    B  the untinted glass plane            (`glass=legacy` -- the old page-derived mix)
    C  the chromatic glass body, core OFF  (`core=0`)
    D  the final glass + core              (the shipping state)

    GCC_body = dE_OK(B, C)        what the *material* contributes
    VC_body  = C(C) - C(A)        the body's visible chromatic lift
    VS_body  = dE_OK(C, A)        the body's visible salience
    VC_total = C(D) - C(A)        with the core
    VS_total = dE_OK(D, A)

Targets: light GCC >= 0.04, VC >= 0.025, VS >= 0.08;  dark GCC >= 0.06, VC >= 0.040, VS >= 0.10.

    python glass_body.py
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import screen as SC  # noqa: E402
from nav_options import chroma, de  # noqa: E402

TARGET = {True: {"GCC": 0.04, "VC": 0.025, "VS": 0.08},
          False: {"GCC": 0.06, "VC": 0.040, "VS": 0.10}}
BODY = (0.55, 0.075, 0.75, 0.095)      # a clean patch of the bar row
PAGE = (0.20, 0.122, 0.80, 0.131)      # the bare page just below the crown
FAMILIES = ["ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
            "mintcoral", "moonstone"]


def patch(im, box):
    W, H = im.size
    x0, y0, x1, y1 = box
    crop = im.crop((int(x0 * W), int(y0 * H), int(x1 * W), int(y1 * H)))
    px = list(crop.getdata())
    return tuple(sum(q[i] for q in px) / len(px) / 255 for i in range(3))


def shoot(page, out, query):
    subprocess.run([str(SC.CHROME), "--headless=new", "--disable-gpu",
                    "--enable-unsafe-swiftshader", "--no-sandbox", "--hide-scrollbars",
                    "--force-device-scale-factor=2", "--window-size=390,844",
                    "--virtual-time-budget=25000", f"--screenshot={out}",
                    f"file:///{page.as_posix()}?{query}"],
                   capture_output=True, text=True, timeout=300)
    return out


def main():
    agsl = json.loads((HERE / "agsl.json").read_text(encoding="utf-8"))
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by = {}
    for f in faces:
        by.setdefault(f["family"], {})["light" if f["light"] else "dark"] = f

    from PIL import Image
    out = HERE / "_gb"
    out.mkdir(exist_ok=True)
    page = HERE / "screen.html"
    rows = {}
    print("=" * 118)
    print("GLASS BODY -- A/B/C/D acceptance test, measured from rendered pixels")
    print("=" * 118)
    print()
    print(f"{'family':<11} {'face':<6} {'GCC_body':>9} {'tgt':>5} {'VC_body':>8} {'tgt':>6} "
          f"{'VS_body':>8} {'tgt':>5} {'VC_tot':>7} {'VS_tot':>7}  verdict")
    for fam in FAMILIES:
        for light in (True, False):
            page.write_text(SC.build_page(by[fam]["light" if light else "dark"], "home", agsl),
                            encoding="utf-8")
            tag = "light" if light else "dark"
            shots = {}
            for name, q in (("B", "glass=legacy&core=0"), ("C", "core=0"), ("D", "")):
                png = out / f"{fam}-{tag}-{name}.png"
                shoot(page, png, q)
                shots[name] = Image.open(png).convert("RGB")
            A = patch(shots["D"], PAGE)
            B = patch(shots["B"], BODY)
            C = patch(shots["C"], BODY)
            D = patch(shots["D"], BODY)
            gcc, vcb, vsb = de(B, C), chroma(C) - chroma(A), de(C, A)
            vct, vst = chroma(D) - chroma(A), de(D, A)
            t = TARGET[light]
            ok = gcc >= t["GCC"] and vcb >= t["VC"] and vsb >= t["VS"]
            rows[f"{fam}:{tag}"] = {"GCC_body": gcc, "VC_body": vcb, "VS_body": vsb,
                                    "VC_total": vct, "VS_total": vst, "ok": ok,
                                    "target": t}
            print(f"{fam:<11} {tag:<6} {gcc:>9.4f} {t['GCC']:>5.2f} {vcb:>8.4f} {t['VC']:>6.3f} "
                  f"{vsb:>8.4f} {t['VS']:>5.2f} {vct:>7.4f} {vst:>7.4f}  "
                  f"{'PASS' if ok else 'short'}")
        print()

    n = len(rows)
    for key in ("GCC_body", "VC_body", "VS_body"):
        light_v = [r[key] for k, r in rows.items() if k.endswith("light")]
        dark_v = [r[key] for k, r in rows.items() if k.endswith("dark")]
        print(f"  {key:<9} light mean {sum(light_v)/len(light_v):.4f}   "
              f"dark mean {sum(dark_v)/len(dark_v):.4f}")

    passing = [k for k, r in rows.items() if r["ok"]]
    print()
    print("=" * 118)
    print(f"BODY PASSES: {len(passing)} / {n}")
    for k, r in rows.items():
        if not r["ok"]:
            t = r["target"]
            why = [nm for nm, v, f in (("GCC", r["GCC_body"], t["GCC"]),
                                       ("VC", r["VC_body"], t["VC"]),
                                       ("VS", r["VS_body"], t["VS"])) if v < f]
            print(f"  {k:<18} short on {', '.join(why)}")
    print("=" * 118)
    (HERE / "glass_body.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print("\n-> glass_body.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
