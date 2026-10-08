"""
Which nav-active fill should the app use?

`Theme.kt` says, of `navigationSelected`: *"deliberately NOT read by the nav capsule -- do not
'fix' this by wiring it into the selected pill."* The reason it gives is substantive: one fixed
colour for all four tabs flattens the per-room differentiation the capsule builds on purpose.

But the rendered-output measurement found a real problem the source-side audit could not: the
destination accents are **AA-fitted for ink**, and a role fitted as foreground text is the wrong
value for a fill. At 55% alpha the pill's visible chroma came out 0.020-0.036 against a 0.030 /
0.040 floor.

So the two positions are both right about different things, and neither proposed the obvious
third option: **keep the destination accent's hue** (so per-room differentiation survives, and
the single-table contract holds) but use its **display form** for the fill -- same hue, same
lightness, more chroma. `Theme.kt` itself names the distinction: `navigationSelected` is "the
palette's signature hue at *display* lightness ... while the four accents are AA-fitted for
*ink*."

This compares the three options analytically -- the pill is a flat fill over known layers, so the
composite is exact and 54 renders are not needed to answer it.

    python nav_options.py
"""
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from chrome import from_oklch, hue, max_chroma, oklab  # noqa: E402

# The pill, the capsule and the page, as the app composites them.
PILL_ALPHA = 0.55                 # the contract's 0.40-0.65 band
CAPSULE_ALPHA = 0.60              # navCapsuleFill(canBlur = true)
GAMUT_SAFETY = 0.96
DISPLAY_CHROMA = 0.16             # the fill form's chroma target


def rgb(hexstr):
    v = int(hexstr, 16)
    return (((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255)


def over(src, alpha, dst):
    return tuple(src[i] * alpha + dst[i] * (1 - alpha) for i in range(3))


def chroma(c):
    _l, a, b = oklab(c)
    return math.hypot(a, b)


def de(a, b):
    la, aa, ba = oklab(a)
    lb, ab, bb = oklab(b)
    return math.sqrt((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2)


def display_form(hexstr, target=DISPLAY_CHROMA):
    """The accent's own hue and lightness, carrying a fill's worth of chroma.

    Same hue means the destination table still decides *which* room the tab belongs to; only the
    role's fitting changes, from ink to fill. That is the one thing the source-side audit could
    not see, because it measured the token rather than the composite.
    """
    c = rgb(hexstr)
    l, a, b = oklab(c)
    h = math.degrees(math.atan2(b, a)) % 360.0
    return from_oklch(l, min(target, max_chroma(l, h) * GAMUT_SAFETY), h)


def main():
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by = {}
    for f in faces:
        by.setdefault(f["family"], {})["light" if f["light"] else "dark"] = f

    DEST = {"accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation"}

    print("=" * 108)
    print("NAV ACTIVE FILL -- three options, measured on the composite")
    print("=" * 108)
    print()
    print("  text     the destination accent as authored (AA-fitted for ink)")
    print("  display  the destination accent's hue and lightness at a fill's chroma")
    print("  navsel   navigationSelected -- one fixed colour, which Theme.kt forbids")
    print()
    print(f"  pill alpha {PILL_ALPHA}, capsule alpha {CAPSULE_ALPHA}, "
          f"display chroma target {DISPLAY_CHROMA}")
    print()
    print(f"{'family':<11} {'face':<6} {'option':<9} {'hue':>5} {'C':>6} {'VC':>7} {'VS':>7}  "
          f"vs floor")

    floors = {True: {"VC": 0.030, "VS": 0.07}, False: {"VC": 0.040, "VS": 0.085}}
    tally = {"text": 0, "display": 0, "navsel": 0}
    rows = []
    for fam in ("ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
                "mintcoral", "moonstone"):
        for light in (True, False):
            f = by[fam]["light" if light else "dark"]
            r = f["roles"]
            field = rgb(r["backgroundBase"])
            plane = rgb(r["surface"])
            # The page's own colour under the capsule, then the capsule's glass, then the pill.
            capsule = over(plane, CAPSULE_ALPHA, field)
            opts = {}
            # Home is the selected tab in the render, so the destination accent is progress.
            opts["text"] = rgb(r["accentProgress"])
            opts["display"] = display_form(r["accentProgress"])
            opts["navsel"] = rgb(r["navigationSelected"])
            for name, acc in opts.items():
                pill = over(acc, PILL_ALPHA, capsule)
                vc = chroma(pill) - chroma(field)
                vs = de(pill, field)
                ok = vc >= floors[light]["VC"] and vs >= floors[light]["VS"]
                tally[name] += ok
                rows.append((fam, light, name, hue(acc), chroma(pill), vc, vs, ok))
                print(f"{fam:<11} {'light' if light else 'dark':<6} {name:<9} "
                      f"{hue(acc):>5.0f} {chroma(pill):>6.3f} {vc:>7.3f} {vs:>7.3f}  "
                      f"{'ok' if ok else 'OUT'}")
        print()

    n = 18
    print("=" * 108)
    print(f"PASSES (of {n} faces):  "
          + "   ".join(f"{k} {v}" for k, v in tally.items()))
    print("=" * 108)
    print()
    print("  'display' keeps the destination hue -- so the tab still says *which room it is*,")
    print("  and the single-table contract with the masthead's segment switch is untouched --")
    print("  while giving the fill a chroma the ink-fitted role does not have.")
    print("  'navsel' scores well and is the option the source forbids: it collapses all four")
    print("  tabs onto one hue, which is the differentiation the capsule exists to build.")

    (HERE / "nav_options.json").write_text(json.dumps(
        {"pill_alpha": PILL_ALPHA, "capsule_alpha": CAPSULE_ALPHA,
         "display_chroma": DISPLAY_CHROMA, "passes": tally,
         "rows": [{"family": f, "light": l, "option": o, "hue": h, "C": c,
                   "VC": vc, "VS": vs, "ok": ok} for f, l, o, h, c, vc, vs, ok in rows]},
        indent=1), encoding="utf-8")
    print("\n-> nav_options.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
