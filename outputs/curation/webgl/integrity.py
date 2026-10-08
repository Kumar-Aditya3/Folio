"""
Renderer integrity: prove each material control actually changes rendered pixels.

The brief's clause 5, and it exists because the `glass=legacy` control was found *dead* — it
changed a model variable that the renderer never read, so a measurement was taken of a control
that did nothing. A control that changes state but not pixels is a failure regardless of what the
state says.

So every control is checked by rendering with it on and off and measuring the difference at the
surface it claims to govern. A near-zero difference means the control is disconnected.

    python integrity.py
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import screen as SC  # noqa: E402
from nav_options import de  # noqa: E402

# (name, control query, region, what it governs)
CONTROLS = [
    ("bar glass", "glass=legacy", (0.55, 0.075, 0.75, 0.095), "the crown's veil"),
    ("crown core", "core=0", (0.20, 0.102, 0.70, 0.106), "the 5dp chromatic band"),
    ("card material", "card=plain", (0.80, 0.16, 0.95, 0.20), "the hero pane's fill"),
    ("glass tint", "tint=0", (0.55, 0.075, 0.75, 0.095), "the barGlassFor tint pass"),
]
FLOOR = 0.002      # below this the control is presumed disconnected


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


def main():
    from PIL import Image
    agsl = json.loads((HERE / "agsl.json").read_text(encoding="utf-8"))
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by = {(f["family"], f["light"]): f for f in faces}
    out = HERE / "_ig"
    out.mkdir(exist_ok=True)
    page = HERE / "screen.html"

    print("=" * 96)
    print("RENDERER INTEGRITY -- does each control actually change the pixels?")
    print("=" * 96)
    print()
    print(f"{'face':<16} {'control':<15} {'governs':<26} {'dE on/off':>10}  verdict")
    failures = []
    for fam in ("ocean", "nebula", "solar", "jade"):
        for light in (True, False):
            page.write_text(SC.build_page(by[(fam, light)], "home", agsl), encoding="utf-8")
            name = f"{fam}-{'light' if light else 'dark'}"
            base = out / f"{name}-on.png"
            shoot(page, base, "")
            im_on = Image.open(base).convert("RGB")
            for label, query, region, governs in CONTROLS:
                alt = out / f"{name}-off.png"
                shoot(page, alt, query)
                im_off = Image.open(alt).convert("RGB")
                d = de(patch(im_on, region), patch(im_off, region))
                ok = d > FLOOR
                if not ok:
                    failures.append(f"{name}/{label}")
                print(f"{name:<16} {label:<15} {governs:<26} {d:>10.5f}  "
                      f"{'connected' if ok else 'DEAD'}")
    print()
    if failures:
        print(f"  {len(failures)} dead control(s): {', '.join(failures)}")
        print("  A control that changes state but not pixels is a failed control; any")
        print("  measurement taken through it describes a renderer that is not this one.")
    else:
        print("  every control moves the pixels it claims to govern.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
