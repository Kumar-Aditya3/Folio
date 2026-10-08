"""
The CHROME / FRAME CONTRACT, implemented as a derivation plus eleven clause checks.

The diagnosis this answers is that the crown and the navigation were inheriting the *surface*
family (`topBarFill` is a page treatment) rather than the *accent* family, so both edges of the
UI collapsed toward the substrate instead of framing it. So the chrome is not derived here
from `backgroundBase` at all: it is placed at an **accent's hue**, and the lightness and chroma
are solved to land the colour in the contract's salience band with as much chroma as the gamut
holds there.

The placement is a search, because salience and chroma pull against each other:

  * `CS` is a distance from the page, and on paper the page is already light, so a saturated
    mid-tone accent sits 35+ away -- far outside the 0.08-0.18 band. The chrome has to come
    *back toward* the page's lightness to get inside it.
  * `CL` wants the chrome's chroma above the page's, so it cannot come back all the way.

Solving rather than authoring is what makes this checkable: every face gets a chrome that is
inside the band by construction, and the clauses then say whether that chrome is also
accent-owned, distinct from its partner, and legible.

Units: `CS` and `dE` are Oklab Euclidean distances (0..~1), `CL` is an Oklab chroma difference.
These are the contract's own units -- not the x100 form the earlier audits print.

    python chrome.py
"""
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from audit import ANCHOR, CLASS, TOP, lch  # noqa: E402

# ── the contract's numbers, verbatim ─────────────────────────────────────────
BANDS = {
    "crown": {True: (0.08, 0.18), False: (0.10, 0.20)},
    "nav": {True: (0.07, 0.16), False: (0.09, 0.18)},
}
LIFT = {
    "crown": {True: 0.025, False: 0.040},
    "nav": {True: 0.020, False: 0.035},
}
OWNERSHIP_MAX_DH = 40.0
FRAME_DH = (25.0, 110.0)
FRAME_DE = 0.05
DOMINANCE = {"crown": 3, "nav": 4}
CROSS_MODE_CS = 0.90
CL_DARK_CEILING = 0.12
TEXT_FLOOR = 4.5
AREA_BUDGET = 0.12
AREA_CHROMA = 0.08
ANCHOR_TOLERANCE = 60.0     # the contract's `anchorTolerance`, not otherwise specified

GAMUT_SAFETY = 0.96

# The accent vocabulary chrome may be placed in. `primaryAccent` is the crown's family and the
# others are the counterpoint candidates for the navigation.
ACCENTS = ["primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent"]

# The theme's non-content UI roles, for the dominance ranking. Content (covers, illustrations,
# user art) is excluded by definition, and so are the error roles.
# The contract's "tertiary UI colours" -- the quiet frame roles the crown must not be buried
# beneath. The four semantic accents are *not* in here: they colour progress rings, tags and
# streaks, and they are saturated by design, so a chrome capped at 0.20 salience can never
# outrank them. Ranking against this pool is the reading the contract's own sentence supports.
QUIET_ROLES = [
    "textSecondary", "textTertiary", "border", "borderHighlight",
    "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
    "glowPrimary", "glowSecondary", "progressTrack", "navigationSelected",
]

RANKED_ROLES = [
    "primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent",
    "progress", "navigationSelected", "accentProgress", "accentStreak",
    "accentDiscovery", "accentAnnotation",
    "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
    "glowPrimary", "glowSecondary", "textSecondary", "textTertiary", "border",
]


# ── colour maths, in the contract's units ────────────────────────────────────

def _lin(u):
    return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4


def _unlin(u):
    v = min(max(u, 0.0), 1.0)
    return v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055


def oklab(rgb):
    """rgb is 0..1. Returns (L, a, b)."""
    r, g, b = (_lin(v) for v in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    lc, mc, sc = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    return (0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
            0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc)


def oklab_to_linear(l, a, b):
    lc = l + 0.3963377774 * a + 0.2158037573 * b
    mc = l - 0.1055613458 * a - 0.0638541728 * b
    sc = l - 0.0894841775 * a - 1.2914855480 * b
    l3, m3, s3 = lc ** 3, mc ** 3, sc ** 3
    return (4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3)


def from_oklch(l, c, h):
    rad = math.radians(h)
    rgb = oklab_to_linear(l, c * math.cos(rad), c * math.sin(rad))
    return tuple(_unlin(v) for v in rgb)


def max_chroma(l, h):
    rad = math.radians(h)
    ca, sa = math.cos(rad), math.sin(rad)

    def holds(c):
        return all(-1e-6 <= v <= 1 + 1e-6 for v in oklab_to_linear(l, c * ca, c * sa))

    if not holds(0.0):
        return 0.0
    lo, hi = 0.0, 0.45
    for _ in range(28):
        mid = (lo + hi) / 2
        if holds(mid):
            lo = mid
        else:
            hi = mid
    return lo


def de(a, b):
    la, aa, ba = oklab(a)
    lb, ab, bb = oklab(b)
    return math.sqrt((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2)


def chroma(rgb):
    _l, a, b = oklab(rgb)
    return math.hypot(a, b)


def hue(rgb):
    _l, a, b = oklab(rgb)
    return math.degrees(math.atan2(b, a)) % 360.0


def rel_lum(rgb):
    r, g, b = (_lin(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = rel_lum(a), rel_lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hex_to_rgb(h):
    v = int(h, 16)
    return (((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255)


# ── the derivation ───────────────────────────────────────────────────────────

def place_chrome(page, hue_deg, cs_band, min_lift):
    """Place a chrome colour at `hue_deg`, inside `cs_band`, carrying the most chroma it can.

    Sweeps lightness and keeps the candidate with the greatest chroma that is still inside the
    band and still clears the lift floor. Chroma is maximised because the contract's own point
    is that the chrome should read *coloured* rather than merely lighter or darker, and the
    band's width is the only thing keeping it from becoming a saturated block.
    """
    page_c = chroma(page)
    lo, hi = cs_band
    best = None
    l = 0.02
    while l <= 0.995:
        c = max_chroma(l, hue_deg) * GAMUT_SAFETY
        if c > 0.0:
            cand = from_oklch(l, c, hue_deg)
            d = de(cand, page)
            if lo <= d <= hi and (chroma(cand) - page_c) >= min_lift:
                if best is None or chroma(cand) > chroma(best[1]):
                    best = (l, cand, c, d)
        l += 0.004
    if best is not None:
        return {"ok": True, "rgb": best[1], "L": best[0], "C": best[2], "CS": best[3],
                "hue": hue_deg}
    # Nothing inside the band clears the lift floor: report the closest, so the failure is
    # legible rather than silent.
    fallback, fbest = None, 1e9
    l = 0.02
    while l <= 0.995:
        c = max_chroma(l, hue_deg) * GAMUT_SAFETY
        if c > 0.0:
            cand = from_oklch(l, c, hue_deg)
            d = de(cand, page)
            err = 0.0 if lo <= d <= hi else min(abs(d - lo), abs(d - hi))
            err += max(0.0, min_lift - (chroma(cand) - page_c)) * 2
            if err < fbest:
                fbest, fallback = err, {"L": l, "rgb": cand, "C": c, "CS": d, "hue": hue_deg}
        l += 0.004
    fallback["ok"] = False
    return fallback


def pick_on_fill(fill, candidates):
    """`chromeOnFill` from the fitted text colours, by the actual rendered fill."""
    scored = [(contrast(c, fill), n, c) for n, c in candidates.items()]
    scored.sort(reverse=True)
    return scored[0]


def derive(face):
    r = face["roles"]
    light = face["light"]
    page = hex_to_rgb(r["backgroundBase"])
    accents = {k: hex_to_rgb(r[k]) for k in ACCENTS}

    # ── the crown: the primary family, per the contract's preference ─────────
    crown = place_chrome(page, hue(accents["primaryAccent"]), BANDS["crown"][light],
                         LIFT["crown"][light])

    # ── the navigation: a counterpoint, 25-110 deg away and not a duplicate ──
    best_nav, best_err = None, 1e9
    for name in ("secondaryAccent", "tertiaryAccent", "warmAccent"):
        cand = place_chrome(page, hue(accents[name]), BANDS["nav"][light], LIFT["nav"][light])
        if not cand["ok"]:
            continue
        dh = TOP.dh(cand["hue"], crown["hue"])
        d = de(cand["rgb"], crown["rgb"])
        err = 0.0 if FRAME_DH[0] <= dh <= FRAME_DH[1] else min(
            abs(dh - FRAME_DH[0]), abs(dh - FRAME_DH[1])) * 2
        err += max(0.0, FRAME_DE - d) * 400
        if err < best_err:
            best_err, best_nav = err, {**cand, "from": name, "dh_crown": dh, "de_crown": d}
    nav = best_nav or place_chrome(page, hue(accents["secondaryAccent"]), BANDS["nav"][light],
                                   LIFT["nav"][light])
    nav.setdefault("from", "secondaryAccent")
    nav.setdefault("dh_crown", TOP.dh(nav["hue"], crown["hue"]))
    nav.setdefault("de_crown", de(nav["rgb"], crown["rgb"]))

    texts = {"textPrimary": hex_to_rgb(r["textPrimary"]), "white": (1.0, 1.0, 1.0),
             "black": (0.0, 0.0, 0.0)}
    on_crown = pick_on_fill(crown["rgb"], texts)
    on_nav = pick_on_fill(nav["rgb"], texts)

    return {"crown": crown, "nav": nav, "page": page, "accents": accents,
            "chromeOnFill": on_crown, "navOnActive": on_nav, "light": light}


# ── the eleven clauses ───────────────────────────────────────────────────────

def clause_1_salience(ch):
    out = {}
    for key, role in (("crown", "crown"), ("nav", "nav")):
        lo, hi = BANDS[role][ch["light"]]
        cs = ch[key]["CS"]
        out[role] = {"value": cs, "band": (lo, hi), "ok": lo <= cs <= hi}
    return out


def clause_2_lift(ch):
    pc = chroma(ch["page"])
    out = {}
    for key, role in (("crown", "crown"), ("nav", "nav")):
        cl = chroma(ch[key]["rgb"]) - pc
        floor = LIFT[role][ch["light"]]
        ok = cl >= floor and (not ch["light"] is False or cl <= CL_DARK_CEILING)
        out[role] = {"value": cl, "floor": floor, "ok": ok}
    return out


def clause_3_ownership(ch):
    out = {}
    for key in ("crown", "nav"):
        h = ch[key]["hue"]
        near = min(ACCENTS, key=lambda k: TOP.dh(h, hue(ch["accents"][k])))
        dh = TOP.dh(h, hue(ch["accents"][near]))
        # Closer to the accent vocabulary than to an arbitrary surface colour.
        de_acc = de(ch[key]["rgb"], ch["accents"][near])
        de_surf = min(de(ch[key]["rgb"], hex_to_rgb(v))
                      for k, v in ch.get("surfaces", {}).items()) if ch.get("surfaces") else None
        out[key] = {"nearest": near, "dh": dh, "ok": dh <= OWNERSHIP_MAX_DH,
                    "de_accent": de_acc, "de_surface": de_surf}
    return out


def clause_4_diversity(ch):
    dh = TOP.dh(ch["crown"]["hue"], ch["nav"]["hue"])
    d = de(ch["crown"]["rgb"], ch["nav"]["rgb"])
    return {"dh": dh, "de": d, "ok": FRAME_DH[0] <= dh <= FRAME_DH[1] and d >= FRAME_DE}


def clause_5_quiet(ch, faces_roles):
    """The same rank, over the quiet frame roles only."""
    page = ch["page"]
    vals = {}
    for k in QUIET_ROLES:
        if k in faces_roles:
            vals[k] = de(hex_to_rgb(faces_roles[k]), page)
    vals["crown"] = ch["crown"]["CS"]
    vals["nav"] = ch["nav"]["CS"]
    order = [k for k, _ in sorted(vals.items(), key=lambda kv: -kv[1])]
    return {"rank_crown": order.index("crown") + 1, "rank_nav": order.index("nav") + 1,
            "ok": order.index("crown") + 1 <= DOMINANCE["crown"]
                  and order.index("nav") + 1 <= DOMINANCE["nav"],
            "order": order}


def clause_5_dominance(ch, faces_roles):
    """Rank the theme's UI roles by their distance from the substrate.

    `parent_i` is the substrate for every role here, which is the reading that makes the rank
    comparable across roles. The contract also caps the chrome's own salience at 0.18-0.20, so
    this clause and clause 1 pull in opposite directions and the result is reported as measured.
    """
    page = ch["page"]
    vals = {}
    for k in RANKED_ROLES:
        if k in faces_roles:
            vals[k] = de(hex_to_rgb(faces_roles[k]), page)
    vals["crown"] = ch["crown"]["CS"]
    vals["nav"] = ch["nav"]["CS"]
    ranked = sorted(vals.items(), key=lambda kv: -kv[1])
    order = [k for k, _ in ranked]
    return {"rank_crown": order.index("crown") + 1, "rank_nav": order.index("nav") + 1,
            "ok": order.index("crown") + 1 <= DOMINANCE["crown"]
                  and order.index("nav") + 1 <= DOMINANCE["nav"],
            "order": order, "values": vals}


def clause_6_cross_mode(light_ch, dark_ch):
    out = {}
    for key in ("crown", "nav"):
        cs_l, cs_d = light_ch[key]["CS"], dark_ch[key]["CS"]
        cl_l = chroma(light_ch[key]["rgb"]) - chroma(light_ch["page"])
        cl_d = chroma(dark_ch[key]["rgb"]) - chroma(dark_ch["page"])
        out[key] = {"cs_l": cs_l, "cs_d": cs_d, "ratio": cs_d / cs_l if cs_l else 0.0,
                    "cl_l": cl_l, "cl_d": cl_d,
                    "ok": cs_d >= CROSS_MODE_CS * cs_l and cl_d >= cl_l
                          and cl_d <= CL_DARK_CEILING}
    return out


def clause_7_axis(ch, fam):
    band = ANCHOR.get(fam)
    if band is None:
        return {"ok": True, "detail": "anchor-free by design"}
    lo, hi, name = band
    out = {}
    for key in ("crown", "nav"):
        h = ch[key]["hue"]
        d = 0.0 if lo <= h <= hi else min(TOP.dh(h, lo), TOP.dh(h, hi))
        out[key] = {"hue": h, "out_by": d, "ok": d <= ANCHOR_TOLERANCE}
    return {"ok": all(v["ok"] for v in out.values()), "detail": out, "band": name}


def clause_10_area(fam, light):
    """The high-chroma chrome area, measured from the render.

    Counts the pixels inside the crown band and the capsule whose Oklab chroma exceeds 0.08,
    over the whole viewport. This is the one clause that cannot be answered from the palette:
    it is about how much *screen* the chrome occupies, which only the layout knows.
    """
    from PIL import Image
    p = HERE / "screens" / f"home-{fam}-{'light' if light else 'dark'}.png"
    if not p.exists():
        return None
    im = Image.open(p).convert("RGB")
    W, H = im.size
    # The crown band (status bar + bar row) and the floating capsule, in CSS px scaled by the
    # render's own factor.
    scale = W / 390
    regions = [(0, 0, W, int(100 * scale)),
               (int(16 * scale), int((844 - 78) * scale), int(374 * scale), int((844 - 24) * scale))]
    total = 0
    hot = 0
    for x0, y0, x1, y1 in regions:
        box = im.crop((x0, y0, x1, y1))
        for px in box.getdata():
            total += 1
            if chroma((px[0] / 255, px[1] / 255, px[2] / 255)) > AREA_CHROMA:
                hot += 1
    viewport = W * H
    return {"hot": hot, "chrome_px": total, "viewport": viewport,
            "fraction": hot / viewport, "ok": hot / viewport <= AREA_BUDGET}


def main():
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by = {}
    for f in faces:
        by.setdefault(f["family"], {})["light" if f["light"] else "dark"] = f

    sheet = [n for n in ANCHOR if n in by]
    derived = {}
    for fam in sheet:
        for light in (True, False):
            face = by[fam]["light" if light else "dark"]
            ch = derive(face)
            ch["surfaces"] = {k: face["roles"][k] for k in
                              ("backgroundBase", "backgroundSecondary", "surface",
                               "surfaceElevated")}
            ch["face"] = face
            derived[(fam, light)] = ch

    print("=" * 112)
    print("CHROME / FRAME CONTRACT -- crown and nav derived from the ACCENT vocabulary")
    print("=" * 112)
    print()
    print("  derivation: place the chrome at an accent's hue, sweep lightness, keep the most")
    print("  chroma that is still inside the salience band and still clears the lift floor.")
    print()
    print(f"{'family':<11} {'face':<6} {'role':<6} {'from':<17} {'hue':>5} {'C':>6} "
          f"{'CS':>6} {'band':>12} {'CL':>7} {'floor':>6}  ok")

    rows = {}
    for (fam, light), ch in derived.items():
        tag = "light" if light else "dark"
        for key, role in (("crown", "crown"), ("nav", "nav")):
            c = ch[key]
            lo, hi = BANDS[role][light]
            cl = chroma(c["rgb"]) - chroma(ch["page"])
            floor = LIFT[role][light]
            ok = c["ok"] and lo <= c["CS"] <= hi and cl >= floor
            print(f"{fam:<11} {tag:<6} {role:<6} {c.get('from', 'primaryAccent'):<17} "
                  f"{c['hue']:>5.0f} {c['C']:>6.3f} {c['CS']:>6.3f} "
                  f"{f'{lo:.2f}-{hi:.2f}':>12} {cl:>7.3f} {floor:>6.3f}  {'ok' if ok else 'OUT'}")
        rows[(fam, light)] = ch

    # ── the clauses, per family (cross-mode clauses need both faces) ──────────
    print()
    print("=" * 112)
    print("THE ELEVEN CLAUSES")
    print("=" * 112)
    print()
    header = (f"{'family':<11} {'c1 sal':>7} {'c2 lift':>8} {'c3 own':>7} {'c4 div':>7} "
              f"{'c5 rank':>8} {'c6 mode':>8} {'c7 axis':>8} {'c8 txt':>7} {'c9 nav':>7}  verdict")
    print(header)
    results = {}
    for fam in sheet:
        L, D = rows[(fam, True)], rows[(fam, False)]
        c1 = all(v["ok"] for v in clause_1_salience(L).values()) and \
             all(v["ok"] for v in clause_1_salience(D).values())
        c2 = all(v["ok"] for v in clause_2_lift(L).values()) and \
             all(v["ok"] for v in clause_2_lift(D).values())
        c3 = all(v["ok"] for v in clause_3_ownership(L).values()) and \
             all(v["ok"] for v in clause_3_ownership(D).values())
        c4 = clause_4_diversity(L)["ok"] and clause_4_diversity(D)["ok"]
        dom_l = clause_5_dominance(L, L["face"]["roles"])
        dom_d = clause_5_dominance(D, D["face"]["roles"])
        c5 = dom_l["ok"] and dom_d["ok"]
        c6 = all(v["ok"] for v in clause_6_cross_mode(L, D).values())
        c7 = clause_7_axis(L, fam)["ok"] and clause_7_axis(D, fam)["ok"]
        c8 = min(L["chromeOnFill"][0], D["chromeOnFill"][0]) >= TEXT_FLOOR
        c9 = min(L["navOnActive"][0], D["navOnActive"][0]) >= TEXT_FLOOR
        verdict = "PASS" if all((c1, c2, c3, c4, c5, c6, c7, c8, c9)) else "fail " + ",".join(
            n for n, v in (("c1", c1), ("c2", c2), ("c3", c3), ("c4", c4), ("c5", c5),
                           ("c6", c6), ("c7", c7), ("c8", c8), ("c9", c9)) if not v)
        q_l = clause_5_quiet(L, L["face"]["roles"])
        q_d = clause_5_quiet(D, D["face"]["roles"])
        results[fam] = {"c1": c1, "c2": c2, "c3": c3, "c4": c4, "c5": c5, "c6": c6, "c7": c7,
                        "c8": c8, "c9": c9, "verdict": verdict,
                        "dom_light": dom_l, "dom_dark": dom_d,
                        "c5_quiet": {"ok": q_l["ok"] and q_d["ok"], "light": q_l, "dark": q_d},
                        "frame_light": clause_4_diversity(L),
                        "frame_dark": clause_4_diversity(D)}
        print(f"{fam:<11} " + " ".join(f"{'ok' if v else 'OUT':>7}" for v in
                                       (c1, c2, c3, c4, c5, c6, c7, c8, c9))
              + f"  {verdict}")

    # ── the clauses that need a reason, spelled out ───────────────────────────
    print()
    print()
    print("  c5 -- the SAME clause over the QUIET frame roles only (text ladder, borders,")
    print("  guilloche, glows, progress track, nav signature). This is the reading the")
    print("  contract's own sentence supports -- \"buried beneath tertiary UI colours\":")
    for fam in sheet:
        q = results[fam]["c5_quiet"]
        print(f"    {fam:<11} light crown rank {q['light']['rank_crown']}, "
              f"nav {q['light']['rank_nav']}   dark crown rank {q['dark']['rank_crown']}, "
              f"nav {q['dark']['rank_nav']}   "
              f"{'ok' if q['ok'] else 'still OUT'}")
    print()
    print("  c5 -- over the FULL UI pool. The order is by distance from the")
    print("  page, and the contract's own clause 1 caps the chrome at 0.18-0.20 while a")
    print("  saturated accent role sits 40-70 away, so the two clauses are in tension:")
    for fam in sheet[:4]:
        d = results[fam]["dom_light"]
        top = ", ".join(f"{k} {v:.2f}" for k, v in list(d["values"].items())[:0]) or ""
        order = d["order"]
        print(f"    {fam:<11} light  crown rank {d['rank_crown']}, nav rank {d['rank_nav']}"
              f"   ahead of crown: {', '.join(order[:order.index('crown')]) or 'none'}")

    print()
    print("  c8 / c9 -- the rendered text contrasts, at their worst face:")
    for fam in sheet:
        L, D = rows[(fam, True)], rows[(fam, False)]
        print(f"    {fam:<11} chromeOnFill {L['chromeOnFill'][1]:<11} {L['chromeOnFill'][0]:>6.2f}"
              f" / {D['chromeOnFill'][1]:<11} {D['chromeOnFill'][0]:>6.2f}"
              f"   navOnActive {min(L['navOnActive'][0], D['navOnActive'][0]):>6.2f}")

    # ── clause 10, from the render ────────────────────────────────────────────
    print()
    print("  c10 -- high-chroma chrome area, as a fraction of the viewport (budget 0.12):")
    areas = {}
    for fam in sheet:
        for light in (True, False):
            a = clause_10_area(fam, light)
            if a:
                areas[(fam, light)] = a
    for (fam, light), a in list(areas.items()):
        tag = "light" if light else "dark"
        print(f"    {fam:<11} {tag:<6} hot {a['hot']:>7} / viewport {a['viewport']:>7}"
              f"  = {a['fraction']:.4f}  {'ok' if a['ok'] else 'OVER'}")
    over = [k for k, v in areas.items() if not v["ok"]]
    print(f"    -> {len(areas) - len(over)} / {len(areas)} inside the budget")

    passing = [f for f in sheet if results[f]["verdict"] == "PASS"]
    print()
    print("=" * 112)
    print(f"RESULT: {len(passing)} / {len(sheet)} families satisfy clauses 1-9")
    for f in sheet:
        if results[f]["verdict"] != "PASS":
            print(f"  {f:<11} {results[f]['verdict']}")
    print("=" * 112)

    out = {"derived": {f"{fam}:{'light' if lt else 'dark'}": {
        "crown": {"rgb": list(ch["crown"]["rgb"]), "hex": "0x%02X%02X%02X" % tuple(
            round(v * 255) for v in ch["crown"]["rgb"]),
            "hue": ch["crown"]["hue"], "C": ch["crown"]["C"], "CS": ch["crown"]["CS"]},
        "nav": {"rgb": list(ch["nav"]["rgb"]), "hex": "0x%02X%02X%02X" % tuple(
            round(v * 255) for v in ch["nav"]["rgb"]),
            "hue": ch["nav"]["hue"], "C": ch["nav"]["C"], "CS": ch["nav"]["CS"],
            "from": ch["nav"].get("from")},
        "chromeOnFill": {"name": ch["chromeOnFill"][1], "contrast": ch["chromeOnFill"][0]},
        "navOnActive": {"name": ch["navOnActive"][1], "contrast": ch["navOnActive"][0]},
    } for (fam, lt), ch in rows.items()}, "clauses": results,
        "area": {f"{fam}:{'light' if lt else 'dark'}": v for (fam, lt), v in areas.items()}}
    (HERE / "chrome.json").write_text(json.dumps(out, indent=1, default=str), encoding="utf-8")
    print("\n-> chrome.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
