"""
CHROME GLASS -- VISIBLE OUTPUT CONTRACT, measured from rendered pixels.

The problem this answers: the previous chrome gates evaluated the *source token*, while the
reader sees the *composite*. A saturated accent behind 0.25 alpha of glass has excellent source
chroma and almost no visible chroma. Those are different quantities and only one of them is on
screen.

So every gate here is sampled from the rendered PNG, after alpha compositing, the glass film,
the AGSL passes and the local background:

    VC  visible chromatic lift   C_final(chrome) - C(local background)
    VS  visible salience         dE_OK(final chrome, local background)
    AE  alpha efficiency         dE_visible / dE_opaque_reference      (diagnostic)

and the three derived tests the contract adds: scale robustness, the book/crown/nav hierarchy,
and cross-mode preservation on rendered output rather than on tokens.

The crown is measured in two places, because the contract asks for a chromatic core and the
core is what carries the signal:

    crown body   the glass field, y 7.5-9.5%   -- what the veil alone produces
    crown core   the narrow band,  y 10.2-10.6% -- the deliberate chromatic signal

    python visible_chrome.py
"""
import json
import math
import pathlib
import sys

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from audit import ANCHOR, TOP  # noqa: E402

# The contract's floors, verbatim. `VC` is an Oklab chroma difference; `VS` is an Oklab
# Euclidean distance. Both are in the contract's own units, not the x100 form.
FLOORS = {
    "crown": {True: {"VC": 0.035, "VS": 0.08}, False: {"VC": 0.045, "VS": 0.10}},
    "nav": {True: {"VC": 0.030, "VS": 0.07}, False: {"VC": 0.040, "VS": 0.085}},
}
CROSS_MODE = 0.90
DOWNSAMPLE = 1 / 3
HIERARCHY = (0.25, 0.65)          # R_CB = VS(crown) / VS(book)
TEXT_FLOOR = 4.5

# Regions as fractions of the 390x844 viewport.
REGIONS = {
    "crown_body": (0.55, 0.075, 0.75, 0.095),
    "crown_core": (0.20, 0.102, 0.70, 0.106),
    "bg_crown": (0.20, 0.122, 0.80, 0.131),
    "book": (0.05, 0.20, 0.35, 0.33),
    "bg_nav": (0.30, 0.975, 0.70, 0.995),
}
# The capsule band, where the active pill is found by its own chroma rather than by a guess at
# the flex layout's arithmetic.
CAPSULE_BAND = (0.0, 0.905, 1.0, 0.975)


# ── colour maths, in the contract's units ────────────────────────────────────

def _lin(u):
    return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4


def oklab(rgb):
    r, g, b = (_lin(v) for v in rgb)
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
    return math.sqrt((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2)


def chroma(rgb):
    _l, a, b = oklab(rgb)
    return math.hypot(a, b)


def rel_lum(rgb):
    r, g, b = (_lin(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = rel_lum(a), rel_lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexrgb(h):
    v = int(h, 16)
    return (((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255)


def _mean(im, box):
    W, H = im.size
    x0, y0, x1, y1 = box
    crop = im.crop((int(x0 * W), int(y0 * H), int(x1 * W), int(y1 * H)))
    px = list(crop.getdata())
    return tuple(sum(q[i] for q in px) / len(px) / 255 for i in range(3))


def _brightest_chroma(im, box):
    """The active pill, found by its own chroma rather than by predicting the flex layout."""
    W, H = im.size
    x0, y0, x1, y1 = box
    crop = im.crop((int(x0 * W), int(y0 * H), int(x1 * W), int(y1 * H)))
    px = list(crop.getdata())
    best, bi = -1.0, 0
    for i, q in enumerate(px):
        c = chroma((q[0] / 255, q[1] / 255, q[2] / 255))
        if c > best:
            best, bi = c, i
    w = crop.width
    cx, cy = bi % w, bi // w
    n = crop.crop((max(0, cx - 12), max(0, cy - 4), min(crop.width, cx + 12),
                   min(crop.height, cy + 4)))
    pp = list(n.getdata())
    return tuple(sum(q[i] for q in pp) / len(pp) / 255 for i in range(3))


def measure(im, face):
    """VC, VS and AE for the crown core, the crown body and the active nav pill."""
    out = {}
    bg_crown = _mean(im, REGIONS["bg_crown"])
    bg_nav = _mean(im, REGIONS["bg_nav"])
    light = face["light"]

    def block(name, colour, bg, role):
        vc = chroma(colour) - chroma(bg)
        vs = de(colour, bg)
        opaque = de(hexrgb(face["roles"]["primaryAccent"]), bg)
        return {"VC": vc, "VS": vs, "AE": (vs / opaque) if opaque > 1e-6 else 0.0,
                "C": chroma(colour), "C_bg": chroma(bg),
                "ok_VC": vc >= FLOORS[role][light]["VC"],
                "ok_VS": vs >= FLOORS[role][light]["VS"]}

    out["crown_body"] = block("crown_body", _mean(im, REGIONS["crown_body"]), bg_crown, "crown")
    out["crown_core"] = block("crown_core", _mean(im, REGIONS["crown_core"]), bg_crown, "crown")
    out["nav"] = block("nav", _brightest_chroma(im, CAPSULE_BAND), bg_nav, "nav")
    # The book is the content hero: the hierarchy test needs its salience too.
    out["book"] = {"VS": de(_mean(im, REGIONS["book"]), bg_crown),
                   "C": chroma(_mean(im, REGIONS["book"]))}
    out["text_contrast"] = contrast(_mean(im, REGIONS["crown_core"]),
                                    hexrgb(face["roles"]["textPrimary"]))
    return out


def main():
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by = {}
    for f in faces:
        by.setdefault(f["family"], {})["light" if f["light"] else "dark"] = f
    sheet = [n for n in ANCHOR if n in by]

    print("=" * 118)
    print("CHROME GLASS -- VISIBLE OUTPUT CONTRACT   (measured from rendered pixels)")
    print("=" * 118)
    print()
    print("  VC = C_final - C(local bg)   VS = dE_OK(final, local bg)   "
          "AE = VS / dE(same colour, opaque)")
    print()
    print(f"{'family':<11} {'face':<6} {'region':<11} {'C':>6} {'C_bg':>6} {'VC':>7} {'floor':>6} "
          f"{'VS':>6} {'floor':>6} {'AE':>5}  verdict")

    results = {}
    for fam in sheet:
        for light in (True, False):
            name = f"{fam}-{'light' if light else 'dark'}"
            p = HERE / "screens" / f"home-{name}.png"
            if not p.exists():
                continue
            im = Image.open(p).convert("RGB")
            m = measure(im, by[fam]["light" if light else "dark"])
            results[(fam, light)] = m
            tag = "light" if light else "dark"
            for region in ("crown_core", "crown_body", "nav"):
                b = m[region]
                role = "nav" if region == "nav" else "crown"
                fl = FLOORS[role][light]
                ok = b["ok_VC"] and b["ok_VS"]
                print(f"{fam:<11} {tag:<6} {region:<11} {b['C']:>6.3f} {b['C_bg']:>6.3f} "
                      f"{b['VC']:>7.3f} {fl['VC']:>6.3f} {b['VS']:>6.3f} {fl['VS']:>6.3f} "
                      f"{b['AE']:>5.2f}  {'ok' if ok else 'OUT'}")
        print()

    # ── the derived tests ─────────────────────────────────────────────────────
    print("=" * 118)
    print("THE DERIVED TESTS")
    print("=" * 118)
    print()
    print(f"{'family':<11} {'core VC':>8} {'core VS':>8} {'nav VC':>7} {'nav VS':>7} "
          f"{'AE core':>8} {'ds VS':>6} {'R_CB':>6} {'cross':>6}  verdict")
    summary = {}
    for fam in sheet:
        L, D = results.get((fam, True)), results.get((fam, False))
        if not L or not D:
            continue
        ds = downsampled(fam)
        r_cb = L["crown_core"]["VS"] / L["book"]["VS"] if L["book"]["VS"] else 0.0
        cross_vs = D["crown_core"]["VS"] / L["crown_core"]["VS"] if L["crown_core"]["VS"] else 0.0
        cross_vc = (D["crown_core"]["VC"] / L["crown_core"]["VC"]
                    if L["crown_core"]["VC"] else 0.0)
        ok = (L["crown_core"]["ok_VC"] and L["crown_core"]["ok_VS"]
              and D["crown_core"]["ok_VC"] and D["crown_core"]["ok_VS"]
              and L["nav"]["ok_VC"] and L["nav"]["ok_VS"]
              and D["nav"]["ok_VC"] and D["nav"]["ok_VS"]
              and cross_vs >= CROSS_MODE and cross_vc >= CROSS_MODE
              and HIERARCHY[0] <= r_cb <= HIERARCHY[1]
              and ds["ok"])
        summary[fam] = {"ds": ds, "r_cb": r_cb, "cross_vs": cross_vs, "cross_vc": cross_vc,
                        "ok": ok}
        print(f"{fam:<11} {L['crown_core']['VC']:>8.3f} {L['crown_core']['VS']:>8.3f} "
              f"{L['nav']['VC']:>7.3f} {L['nav']['VS']:>7.3f} {L['crown_core']['AE']:>8.2f} "
              f"{ds['VS']:>6.3f} {r_cb:>6.2f} {cross_vs:>6.2f}  "
              f"{'PASS' if ok else 'fail'}")

    # ── the diagnostic the contract asks for ──────────────────────────────────
    print()
    print("=" * 118)
    print("§11 FINAL DIAGNOSTIC -- separating palette failure from compositing failure")
    print("=" * 118)
    print()
    print("  AE is the ratio of the visible salience to what the same colour would give if it")
    print("  were opaque. A low AE with a healthy source chroma means the GLASS is eating the")
    print("  signal, and the repair belongs in the compositing, not in the palette.")
    print()
    print(f"{'family':<11} {'face':<6} {'src chroma':>10} {'body VS':>8} {'core VS':>8} "
          f"{'core AE':>8}  reading")
    for fam in sheet:
        for light in (True, False):
            m = results.get((fam, light))
            if not m:
                continue
            face = by[fam]["light" if light else "dark"]
            src_c = chroma(hexrgb(face["roles"]["primaryAccent"]))
            b, k = m["crown_body"], m["crown_core"]
            if k["AE"] >= 0.55:
                reading = "core carries the signal"
            elif k["AE"] >= 0.30:
                reading = "core carries it, attenuated"
            else:
                reading = "glass is eating the signal"
            print(f"{fam:<11} {'light' if light else 'dark':<6} {src_c:>10.3f} "
                  f"{b['VS']:>8.3f} {k['VS']:>8.3f} {k['AE']:>8.2f}  {reading}")

    passing = [f for f in summary if summary[f]["ok"]]
    print()
    print("=" * 118)
    print(f"RESULT: {len(passing)} / {len(sheet)} families pass the visible-output contract")
    for f in sheet:
        if f in summary and not summary[f]["ok"]:
            s = summary[f]
            why = []
            if not (0.0 < s["cross_vs"]):
                why.append("cross-mode")
            if s["cross_vs"] < CROSS_MODE:
                why.append(f"VS cross {s['cross_vs']:.2f}")
            if s["cross_vc"] < CROSS_MODE:
                why.append(f"VC cross {s['cross_vc']:.2f}")
            if not (HIERARCHY[0] <= s["r_cb"] <= HIERARCHY[1]):
                why.append(f"R_CB {s['r_cb']:.2f}")
            if not s["ds"]["ok"]:
                why.append(f"downsampled VS {s['ds']['VS']:.3f}")
            print(f"  {f:<11} {', '.join(why) or 'a floor'}")
    print("=" * 118)

    out = {"floors": {k: {str(a): b for a, b in v.items()} for k, v in FLOORS.items()},
           "results": {f"{fam}:{'light' if lt else 'dark'}": m for (fam, lt), m in results.items()},
           "summary": {f: {**s, "ds": s["ds"]} for f, s in summary.items()}}
    (HERE / "visible_chrome.json").write_text(json.dumps(out, indent=1, default=str),
                                              encoding="utf-8")
    print("\n-> visible_chrome.json")
    return 0


def downsampled(fam):
    """Clause 9: the same VS after reducing the render to a third of its linear size."""
    im = Image.open(HERE / "screens" / f"home-{fam}-light.png").convert("RGB")
    small = im.resize((max(2, int(im.width * DOWNSAMPLE)), max(2, int(im.height * DOWNSAMPLE))),
                      Image.LANCZOS)
    core = _mean(small, REGIONS["crown_core"])
    bg = _mean(small, REGIONS["bg_crown"])
    vs = de(core, bg)
    return {"VS": vs, "ok": vs >= FLOORS["crown"][True]["VS"]}


if __name__ == "__main__":
    raise SystemExit(main())
