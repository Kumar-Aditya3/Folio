"""
Which crown layer removes the body's chroma?

The contract's clause 9: "the acceptance measurement must identify *which layer* is responsible
for the chromatic contribution. Otherwise we risk solving the measurement by damaging the wrong
material layer."

The crown is a stack -- field, veil, status scrim, sheen, foot fade, glass film, core -- and the
body's visible chroma is what survives all of them. So each overlay is switched off in turn and
the body re-measured, which turns "the composite wash attenuates it" into a named layer.

    base        the veil alone (scrim and sheen off, core off)
    +scrim      the status scrim restored
    +sheen      the mirror finish restored
    full        both, i.e. what ships

    python layer_attribution.py
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import screen as SC  # noqa: E402
from nav_options import chroma, de  # noqa: E402

BODY = (0.55, 0.075, 0.75, 0.095)
PAGE = (0.20, 0.122, 0.80, 0.131)
STATES = [("veil only", "core=0&scrim=0&sheen=0"),
          ("+ scrim", "core=0&sheen=0"),
          ("+ sheen", "core=0"),
          ("full", "core=0")]
FAMILIES = ["ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
            "mintcoral", "moonstone"]


def patch(im, box):
    W, H = im.size
    x0, y0, x1, y1 = box
    crop = im.crop((int(x0 * W), int(y0 * H), int(x1 * W), int(y1 * H)))
    px = list(crop.getdata())
    return tuple(sum(q[i] for q in px) / len(px) / 255 for i in range(3))


def main():
    from PIL import Image
    agsl = json.loads((HERE / "agsl.json").read_text(encoding="utf-8"))
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by = {(f["family"], f["light"]): f for f in faces}
    out = HERE / "_la"
    out.mkdir(exist_ok=True)
    page = HERE / "screen.html"

    print("=" * 108)
    print("LAYER ATTRIBUTION -- the crown body's visible chroma after each layer")
    print("=" * 108)
    print()
    print(f"{'family':<11} {'face':<6} " + " ".join(f"{s[0]:>10}" for s in STATES)
          + f" {'lost to overlays':>17}")

    losses = {True: [], False: []}
    for fam in FAMILIES:
        for light in (True, False):
            page.write_text(SC.build_page(by[(fam, light)], "home", agsl), encoding="utf-8")
            tag = "light" if light else "dark"
            vcs = []
            for i, (label, q) in enumerate(STATES):
                png = out / f"{fam}-{tag}-{i}.png"
                subprocess.run([str(SC.CHROME), "--headless=new", "--disable-gpu",
                                "--enable-unsafe-swiftshader", "--no-sandbox",
                                "--hide-scrollbars", "--force-device-scale-factor=2",
                                "--window-size=390,844", "--virtual-time-budget=25000",
                                f"--screenshot={png}",
                                f"file:///{page.as_posix()}?{q}"],
                               capture_output=True, text=True, timeout=300)
                im = Image.open(png).convert("RGB")
                vcs.append(chroma(patch(im, BODY)) - chroma(patch(im, PAGE)))
            loss = vcs[0] - vcs[-1]
            losses[light].append(loss / vcs[0] if vcs[0] else 0.0)
            print(f"{fam:<11} {tag:<6} " + " ".join(f"{v:>10.4f}" for v in vcs)
                  + f" {loss / vcs[0] * 100 if vcs[0] else 0:>16.0f}%")
        print()

    for light in (True, False):
        v = losses[light]
        print(f"  {'light' if light else 'dark ':>5}: overlays remove a mean "
              f"{sum(v) / len(v) * 100:.0f}% of the veil's chroma "
              f"(min {min(v) * 100:.0f}%, max {max(v) * 100:.0f}%)")
    print()
    print("  Read the columns left to right: whatever the scrim column drops is the scrim's doing,")
    print("  and likewise for the sheen. That names the layer instead of blaming 'the composite'.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
