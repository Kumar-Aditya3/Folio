"""
Measure the crown as the APP draws it, from device screenshots.

Everything measured before this was the crown of *this reconstruction* -- an HTML/CSS rebuild
of `FolioTopBar` -- which is not the app's crown. A screenshot has no such problem, so the
device captures in the repository are sampled directly.

One correction the measurement forced, and it cuts against the obvious story: `_topbar` did
hardcode `knob = BAR_GLASS` (0.35), the **unblurred** path, where a real device takes
`glassTierAlpha(0.35, 0.35, 0.25)` = **0.25** -- but because the crown's fill colour *is* the
page, the two tiers composite to within **1.34 dE** worst case across all twenty faces. So the
wrong tier was a real bug with a negligible visual effect, and it is NOT what explains the
2.9 dE gap between the reconstruction's crown and the device's. Saying otherwise would have
been a tidy story and a wrong one.

    python crown.py
"""
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE))
from audit import TOP  # noqa: E402

FLOOR = 4.2

# Device captures in the repository whose masthead is actually on screen. Each was checked to
# be a real screen rather than a splash or a crash frame.
CANDIDATES = [
    "shot116_home.png", ".dbg/shots/after_install.png", ".dbg/phone_home.png",
    ".dbg/now2.png", "shot_home.png", "shot_lib.png", ".dbg/shot_first.png",
]


def wcag_rgb(rgb):
    def lin(u):
        u /= 255.0
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(a, b):
    la, lb = wcag_rgb(a), wcag_rgb(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hex_to_rgb(h):
    v = int(h, 16)
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def rgb_to_hex(t):
    return "0x%02X%02X%02X" % tuple(t)


def oklab(rgb):
    def lin(u):
        u /= 255.0
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(v) for v in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    lc, mc, sc = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    return (0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
            0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc)


def de(a, b):
    la, aa, ba = oklab(a)
    lb, ab, bb = oklab(b)
    return 100 * math.sqrt((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2)


def _hue_chroma(rgb):
    """(Oklab hue degrees, chroma). NaN hue below the chroma sentinel, where it is noise."""
    _l, oa, ob = oklab(rgb)
    c = math.hypot(oa, ob)
    if c < 0.02:
        return float("nan"), c
    return math.degrees(math.atan2(ob, oa)) % 360.0, c


def main():
    from PIL import Image

    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    dark = [f for f in faces if not f["light"]]

    print("=" * 108)
    print("THE IN-APP CROWN, measured from device screenshots")
    print("=" * 108)
    print()
    print(f"{'capture':<30} {'face (matched)':<18} {'crown ground':<14} {'glyph':<14} "
          f"{'ink':>6} {'accentProgress':>15} {'measured':>9}")

    rows = []
    for rel in CANDIDATES:
        p = REPO / rel
        if not p.exists():
            continue
        im = Image.open(p).convert("RGB")
        W, H = im.size

        # The bar row, clear of the mark and the title: a clean ground sample.
        ground_box = im.crop((int(0.55 * W), int(0.058 * H), int(0.78 * W), int(0.088 * H)))
        gpx = list(ground_box.getdata())
        ground = tuple(sum(q[i] for q in gpx) // len(gpx) for i in range(3))

        # The title region: the extreme pixels are the glyph. Which extreme depends on the
        # ground's polarity, and comparing the two ends against 0.5 gets it backwards on a
        # dark crown -- the glyph there is the *bright* end, which is exactly the case the
        # naive test rejected.
        title_box = im.crop((int(0.045 * W), int(0.045 * H), int(0.42 * W), int(0.105 * H)))
        tpx = list(title_box.getdata())
        lum = [wcag_rgb(q) for q in tpx]
        ground_lum = wcag_rgb(ground)
        lo, hi = min(lum), max(lum)
        pick = hi if (hi - ground_lum) >= (ground_lum - lo) else lo
        glyph = tpx[lum.index(pick)]

        # Which face is this? NOT by Oklab distance to a page colour: the crown ground is
        # brighter than any page (the sheen, the scrim and the film all lift it), so the
        # nearest-page match collapses onto whichever face has the lightest near-black.
        # Hue is the stable signal -- a violet crown is a violet room.
        gh, gc = _hue_chroma(ground)
        ranked = []
        for f in dark:
            ph, pc = _hue_chroma(hex_to_rgb(f["roles"]["backgroundBase"]))
            if math.isnan(gh) or math.isnan(ph):
                continue
            ranked.append((TOP.dh(gh, ph) + 60 * abs(gc - pc), f))
        ranked.sort(key=lambda t: t[0])
        best = ranked[0][1] if ranked else dark[0]
        fam = f"{best['family']}/dark"

        c_ink = contrast(ground, hex_to_rgb(best["roles"]["textPrimary"]))
        c_acc = contrast(ground, hex_to_rgb(best["roles"]["accentProgress"]))
        c_meas = contrast(ground, glyph)

        rows.append({"capture": rel, "face": fam, "ground": ground, "glyph": glyph,
                     "ink": c_ink, "accent": c_acc, "measured": c_meas,
                     "textPrimary": best["roles"]["textPrimary"],
                     "accentProgress": best["roles"]["accentProgress"]})
        print(f"{rel:<30} {fam:<18} {rgb_to_hex(ground):<14} {rgb_to_hex(glyph):<14} "
              f"{c_ink:>6.2f} {c_acc:>15.2f} {c_meas:>9.2f}")

    print()
    n = len(rows)
    ink_ok = sum(1 for r in rows if r["ink"] >= FLOOR)
    acc_ok = sum(1 for r in rows if r["accent"] >= FLOOR)
    print(f"  wordmark contrast >= {FLOOR} against the REAL crown ground:")
    print(f"    textPrimary      {ink_ok} / {n}")
    print(f"    accentProgress   {acc_ok} / {n}")
    print()
    print("  Does the rendered glyph match the palette's `textPrimary`?")
    for r in rows:
        # The matched face is a *dark* one, so its roles must be read from that entry.
        # Keying on the string "family/dark" matches the light entry too, and `next` then
        # returns whichever comes first -- which is the light face, and a 70 dE "mismatch"
        # that is entirely an artefact of the lookup.
        d = de(r["glyph"], hex_to_rgb(r["textPrimary"]))
        print(f"    {r['capture']:<30} glyph {rgb_to_hex(r['glyph'])} vs textPrimary "
              f"{r['textPrimary'][2:]}   dE {d:>5.1f}"
              f"{'   <- not the ink' if d > 12 else ''}")

    # The reconstruction's crown, for the comparison that motivated this.
    print()
    print("  Reconstruction vs app, same face (Dusk):")
    recon = HERE / "screens" / "home-nebula-dark.png"
    if recon.exists():
        im = Image.open(recon).convert("RGB")
        W, H = im.size
        box = im.crop((int(0.60 * W), int(0.075 * H), int(0.70 * W), int(0.095 * H)))
        px = list(box.getdata())
        rg = tuple(sum(q[i] for q in px) // len(px) for i in range(3))
        app = next((r for r in rows if r["face"] == "nebula/dark"), None)
        print(f"    reconstruction crown ground  {rgb_to_hex(rg)}  (fill alpha 0.35, unblurred)")
        if app:
            print(f"    device crown ground          {rgb_to_hex(app['ground'])}  "
                  f"(fill alpha 0.25, blurred -- the path a real device takes)")
            print(f"    dE between them              {de(rg, app['ground']):.1f}")

    (HERE / "crown.json").write_text(json.dumps(
        {"floor": FLOOR, "rows": [{**r, "ground": list(r["ground"]),
                                   "glyph": list(r["glyph"])} for r in rows]},
        indent=1), encoding="utf-8")
    print("\n-> crown.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
