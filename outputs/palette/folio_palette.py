"""
folio_palette.py — palette auditor + bounded OKLCH optimiser for Folio's 20 themes.

Reads the authored tokens straight out of FolioArtDirection.kt (so it always scores
the REAL app colours), reconstructs the per-screen surfaces the app actually paints
(page / card / panel / nav / top-bar / the background animation's own colours) using a
faithful ColorAide port of the app's OKLab + chrome/card-glass + pool-harmony maths, and
scores every face across contrast, perceptual hierarchy, surface cohesion, accent
separation (the "no-monochrome" rule), animation-vs-background separation, CVD robustness
and gamut.  It then proposes bounded OKLCH nudges for the worst offender on each face.

Deps: coloraide, numpy, scipy.  No app build required.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from coloraide import Color
from scipy.optimize import minimize

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "shared/src/composeUi/kotlin/com/folio/reader/ui/theme/FolioArtDirection.kt"
OUT_MD = Path(__file__).resolve().parent / "folio_palette_audit.md"
OUT_JSON = Path(__file__).resolve().parent / "folio_palette_audit.json"

# ── Constants lifted verbatim from FolioAtmosphere.kt ────────────────────────────────
GAMUT_SAFETY = 0.90
BAR_GLASS_L_LIGHT = 0.58
BAR_GLASS_L_DARK = 0.65
BAR_GLASS_CHROMA = 0.16
BAR_GLASS_MATERIAL_SHARE = 0.90
BAR_GLASS_ALPHA = 0.40
CARD_GLASS_CHROMA = 0.195
CARD_MATERIAL_SHARE = 0.80
CARD_GLASS_L_DARK_FLOOR = 0.30
POOL_HARMONY_PULL = 0.42
MIN_POOL_SPREAD = 8.0

# ── Concept -> Mirage animation mapping + palette intent (the design decision) ───────
# Each entry: the named concept, the Mirage composable chosen to express it, the palette
# roles that feed the animation's colours (so the animation is coloured FROM the theme and
# optimised against the field it sits on), and the rationale.
CONCEPTS = {
    "system":    dict(name="System (achromatic)",        mirage="Metaballs",
                      intent="neutral steel/ink; the calm default", anim_roles=["glowPrimary", "glowSecondary"]),
    "ocean":     dict(name="Aurora Ocean",               mirage="MeshGradient",
                      intent="teal + azure + mint + whisper of gold", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "nebula":    dict(name="Nebula Electric",            mirage="NeuroNoise",
                      intent="indigo + electric cyan + violet, magenta surprise", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "forest":    dict(name="Bioluminescent Forest",      mirage="Voronoi",
                      intent="moss + teal + chartreuse + amber glow", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "terracotta":dict(name="Terracotta Tide",            mirage="GrainGradient(Wave)",
                      intent="terracotta + cream + blue-green + turquoise", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "arctic":    dict(name="Arctic Glass",               mirage="Swirl",
                      intent="glacier blue + violet + mint + frost rose", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "solar":     dict(name="Solar Eclipse",              mirage="GodRays",
                      intent="deep blue + gold corona + sky + crimson", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "jade":      dict(name="Jade Noir",                  mirage="SmokeRing",
                      intent="orchid + jade + periwinkle + amber", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "mintcoral": dict(name="Mint & Coral Editorial",     mirage="ColorPanels",
                      intent="mint + coral + periwinkle + ochre", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
    "moonstone": dict(name="Moonstone",                  mirage="PerlinNoise",
                      intent="slate blue + lilac + aqua + dusty rose", anim_roles=["glowPrimary", "glowSecondary", "warmAccent"]),
}

TOKEN_FIELDS = [
    "backgroundBase", "backgroundSecondary", "surface", "surfaceElevated",
    "primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent",
    "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
    "glowPrimary", "glowSecondary", "progress", "progressTrack",
    "textPrimary", "textSecondary", "textTertiary", "border", "borderHighlight",
    "navigationSelected", "accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation",
]


# ── Kotlin parse ─────────────────────────────────────────────────────────────────────
def parse_faces() -> list[dict]:
    src = ART.read_text(encoding="utf-8")
    faces = []
    for m in re.finditer(r"FolioArtDirection\((.*?)\)\s*,\s*(?=//|\n\s*//|FolioArtDirection|\))",
                          src, re.S):
        block = m.group(1)
        if "id =" not in block:
            continue
        fid = re.search(r'id\s*=\s*"([^"]+)"', block).group(1)
        light = re.search(r"light\s*=\s*(true|false)", block).group(1) == "true"
        toks = {}
        for fname in TOKEN_FIELDS:
            mm = re.search(rf"\b{fname}\s*=\s*0x([0-9A-Fa-f]{{8}})", block)
            if mm:
                toks[fname] = "#" + mm.group(1)[2:]  # drop AA, keep RRGGBB
        if len(toks) >= 20:
            faces.append(dict(id=fid, light=light, tokens=toks))
    return faces


# ── Colour helpers (faithful ports) ───────────────────────────────────────────────────
def C(hexstr: str) -> Color:
    return Color(hexstr)


def oklab_l(c: Color) -> float:
    return c.convert("oklab")["lightness"]


def oklab_hue(c: Color) -> float:
    h = c.convert("oklch")["hue"]
    return 0.0 if (h is None or math.isnan(h)) else h % 360.0


def oklab_chroma(c: Color) -> float:
    return c.convert("oklch")["chroma"]


def max_chroma_in_gamut(l: float, hue: float) -> float:
    lo, hi = 0.0, 0.5
    for _ in range(28):
        mid = (lo + hi) / 2
        if Color("oklch", [l, mid, hue]).in_gamut("srgb"):
            lo = mid
        else:
            hi = mid
    return lo


def oklch_to_color(l: float, chroma: float, hue: float) -> Color:
    c = Color("oklch", [l, chroma, hue]).convert("srgb")
    c.fit("srgb", method="clip")
    return c


def mix_srgb(a: Color, b: Color, t: float) -> Color:  # mixG / mixA
    ar, ag, ab = a[:3]
    br, bg, bb = b[:3]
    return Color("srgb", [ar + (br - ar) * t, ag + (bg - ag) * t, ab + (bb - ab) * t])


def lift_srgb(c: Color, amount: float) -> Color:  # lift(c, amt) == mix toward white
    return mix_srgb(c, Color("white"), amount)


def mix_oklab(a: Color, b: Color, share: float) -> Color:
    aa, ab = a.convert("oklab")["a"], a.convert("oklab")["b"]
    ba, bb = b.convert("oklab")["a"], b.convert("oklab")["b"]
    x = aa + (ba - aa) * share
    y = ab + (bb - ab) * share
    l = oklab_l(a) + (oklab_l(b) - oklab_l(a)) * share
    chroma = math.hypot(x, y)
    hue = (math.degrees(math.atan2(y, x)) + 360.0) % 360.0
    return oklch_to_color(l, chroma, hue)


def chrome_glass_material(accent: Color, dark: bool) -> Color:
    l = BAR_GLASS_L_DARK if dark else BAR_GLASS_L_LIGHT
    hue = oklab_hue(accent)
    chroma = min(BAR_GLASS_CHROMA, max_chroma_in_gamut(l, hue) * GAMUT_SAFETY)
    return oklch_to_color(l, chroma, hue)


def card_glass_material(accent: Color, base: Color, dark: bool) -> Color:
    l = max(oklab_l(base), CARD_GLASS_L_DARK_FLOOR) if dark else oklab_l(base)
    hue = oklab_hue(accent)
    chroma = min(CARD_GLASS_CHROMA, max_chroma_in_gamut(l, hue) * GAMUT_SAFETY)
    return oklch_to_color(l, chroma, hue)


def rgb_to_hsv(c: Color):
    import colorsys
    r, g, b = c[:3]
    h, s, v = colorsys.rgb_to_hsv(max(0, min(1, r)), max(0, min(1, g)), max(0, min(1, b)))
    return h * 360.0, s, v


def hsv_to_color(h, s, v):
    import colorsys
    r, g, b = colorsys.hsv_to_rgb((h % 360.0) / 360.0, s, v)
    return Color("srgb", [r, g, b])


def harmonize_pool_hues(pools: list[Color], pull: float) -> list[Color]:
    if len(pools) < 2 or pull <= 0:
        return pools
    hsv = [rgb_to_hsv(c) for c in pools]
    sx = sum(math.cos(math.radians(h)) for h, _, _ in hsv)
    sy = sum(math.sin(math.radians(h)) for h, _, _ in hsv)
    if sx == 0 and sy == 0:
        return pools
    mean = (math.degrees(math.atan2(sy, sx)) + 360.0) % 360.0
    widest = 0.0
    for a in range(len(hsv)):
        for b in range(a + 1, len(hsv)):
            raw = abs(hsv[a][0] - hsv[b][0]) % 360.0
            widest = max(widest, min(raw, 360.0 - raw))
    if widest <= MIN_POOL_SPREAD:
        return pools
    capped = min(max(0.0, min(pull, 1.0)), 1.0 - MIN_POOL_SPREAD / widest)
    if capped <= 0:
        return pools
    out = []
    for (h, s, v) in hsv:
        delta = ((mean - h + 540.0) % 360.0 - 180.0) * capped
        out.append(hsv_to_color((h + delta) % 360.0, s, v))
    return out


def composite_over(top: Color, bottom: Color, alpha: float) -> Color:
    tr, tg, tb = top[:3]
    br, bg, bb = bottom[:3]
    return Color("srgb", [tr * alpha + br * (1 - alpha),
                          tg * alpha + bg * (1 - alpha),
                          tb * alpha + bb * (1 - alpha)])


def wcag(a: Color, b: Color) -> float:
    return a.contrast(b)


def delta_e(a: Color, b: Color) -> float:
    return a.delta_e(b, method="2000")


def hue_gap(a: Color, b: Color) -> float:
    d = abs(oklab_hue(a) - oklab_hue(b)) % 360.0
    return min(d, 360.0 - d)


# ── Surface reconstruction (what each screen actually paints) ──────────────────────────
def derive_surfaces(face: dict) -> dict:
    t = {k: C(v) for k, v in face["tokens"].items()}
    dark = not face["light"]
    bg = t["backgroundBase"]
    bg2 = t["backgroundSecondary"]
    surface = t["surface"]
    panel = t["surfaceElevated"]
    pacc = t["primaryAccent"]

    if dark:
        top = mix_srgb(bg, bg2, 0.5)
        field_top = mix_srgb(top, pacc, 0.03)
        bloom = mix_srgb(panel, bg2, 0.35)
        field_bottom = mix_srgb(bloom, pacc, 0.26)
    else:
        field_top = bg
        field_bottom = mix_srgb(bg2, Color("black"), 0.07)

    card = mix_oklab(surface, card_glass_material(pacc, surface, dark), CARD_MATERIAL_SHARE)
    panel_fill = mix_oklab(panel, card_glass_material(pacc, panel, dark), CARD_MATERIAL_SHARE)
    nav = mix_srgb(surface, bg2, 0.45 if face["light"] else 0.35)

    bar_base = mix_srgb(field_top, nav, 0.35) if dark else mix_srgb(lift_srgb(field_top, 0.42), nav, 0.20)
    bar_plane = mix_oklab(bar_base, chrome_glass_material(pacc, dark), BAR_GLASS_MATERIAL_SHARE)
    bar_over_field = composite_over(bar_plane, field_top, BAR_GLASS_ALPHA)

    roles = CONCEPTS[face["id"]]["anim_roles"]
    pools = harmonize_pool_hues([t[r] for r in roles], POOL_HARMONY_PULL)

    return dict(
        field_top=field_top, field_bottom=field_bottom,
        card=card, panel=panel_fill, nav=nav, topbar=bar_over_field,
        pools=pools, text_primary=t["textPrimary"], text_secondary=t["textSecondary"],
        accents=[t["primaryAccent"], t["secondaryAccent"], t["tertiaryAccent"], t["warmAccent"]],
        guilloche=[t["guillochePrimary"], t["guillocheSecondary"], t["guillocheHighlight"]],
    )


# ── Scoring ────────────────────────────────────────────────────────────────────────────
WCAG_PRIMARY = 4.5
WCAG_SECONDARY = 3.0
ACCENT_HUE_FLOOR = 25.0   # no-monochrome: adjacent authored accents must be >= this apart
ACCENT_DE_FLOOR = 12.0
ANIM_DE_FLOOR = 12.0      # animation colour must read against the field it sits on
CVD_DE_FLOOR = 6.0
HIER_DL_FLOOR = 0.015


def score_face(face: dict) -> dict:
    s = derive_surfaces(face)
    issues = []
    rep = {}

    # A. text contrast on every surface
    surfaces = {"page": s["field_top"], "card": s["card"], "panel": s["panel"],
                "nav": s["nav"], "topbar": s["topbar"]}
    rep["contrast"] = {}
    for sname, col in surfaces.items():
        cp = round(wcag(s["text_primary"], col), 2)
        cs = round(wcag(s["text_secondary"], col), 2)
        rep["contrast"][sname] = dict(primary=cp, secondary=cs)
        if cp < WCAG_PRIMARY:
            issues.append(f"text-primary on {sname} = {cp} (< {WCAG_PRIMARY})")
        if cs < WCAG_SECONDARY:
            issues.append(f"text-secondary on {sname} = {cs} (< {WCAG_SECONDARY})")

    # B. perceptual hierarchy: page vs card vs panel must be separable in L
    lp, lc, lpa = oklab_l(s["field_top"]), oklab_l(s["card"]), oklab_l(s["panel"])
    rep["hierarchy_L"] = dict(page=round(lp, 3), card=round(lc, 3), panel=round(lpa, 3))
    for a, b, na, nb in [(lc, lp, "card", "page"), (lpa, lp, "panel", "page"), (lc, lpa, "card", "panel")]:
        if abs(a - b) < HIER_DL_FLOOR:
            issues.append(f"hierarchy flat: {na}/{nb} ΔL={round(abs(a-b),3)} (< {HIER_DL_FLOOR})")

    # C. surface cohesion (report OKLCH; informational)
    rep["cohesion"] = {k: [round(oklab_l(v), 3), round(oklab_chroma(v), 3), round(oklab_hue(v), 1)]
                       for k, v in surfaces.items()}

    # D. accent separation + no-monochrome
    acc = s["accents"]
    names = ["primary", "secondary", "tertiary", "warm"]
    rep["accents"] = {}
    min_hue, min_de = 999.0, 999.0
    for i in range(len(acc)):
        for j in range(i + 1, len(acc)):
            hg = round(hue_gap(acc[i], acc[j]), 1)
            de = round(delta_e(acc[i], acc[j]), 1)
            rep["accents"][f"{names[i]}~{names[j]}"] = dict(hue_gap=hg, dE=de)
            min_hue, min_de = min(min_hue, hg), min(min_de, de)
    rep["accent_min_hue_gap"] = round(min_hue, 1)
    rep["accent_min_dE"] = round(min_de, 1)
    if min_hue < ACCENT_HUE_FLOOR:
        issues.append(f"MONOCHROME risk: closest accents only {min_hue}° apart (< {ACCENT_HUE_FLOOR}°)")
    if min_de < ACCENT_DE_FLOOR:
        issues.append(f"accents too similar: min ΔE={min_de} (< {ACCENT_DE_FLOOR})")

    # E. animation colour vs its background (field_bottom) + pairwise pool spread
    rep["animation"] = dict(mirage=CONCEPTS[face["id"]]["mirage"], pools=[])
    for i, p in enumerate(s["pools"]):
        de_b = round(delta_e(p, s["field_bottom"]), 1)
        rep["animation"]["pools"].append(dict(
            hex=p.convert("srgb").to_string(hex=True), dE_vs_field=de_b,
            dL_vs_field=round(oklab_l(p) - oklab_l(s["field_bottom"]), 3)))
        if de_b < ANIM_DE_FLOOR:
            issues.append(f"animation pool {i} barely reads on field: ΔE={de_b} (< {ANIM_DE_FLOOR})")

    # F. CVD robustness: accent pair collapse under protan/deutan/tritan
    rep["cvd"] = {}
    worst = 999.0
    for cvd in ("protan", "deutan", "tritan"):
        sim = [a.filter(cvd, 1).convert("srgb") for a in acc]
        mn = min(delta_e(sim[i], sim[j]) for i in range(len(sim)) for j in range(i + 1, len(sim)))
        rep["cvd"][cvd] = round(mn, 1)
        worst = min(worst, mn)
    if worst < CVD_DE_FLOOR:
        issues.append(f"accents collapse under CVD: worst pair ΔE={round(worst,1)} (< {CVD_DE_FLOOR})")

    rep["issues"] = issues
    rep["ok"] = not issues
    return rep


# ── Bounded OKLCH suggestion optimiser ─────────────────────────────────────────────────
# Every nudge stays inside a tight OKLCH box (L±LBOX, C±CBOX, H±HBOX) so the authored look
# is preserved — these are corrections, not re-authorings. Each returns {token, was, suggested}.
LBOX, CBOX, HBOX = 0.06, 0.06, 18.0
HIER_DL_TARGET = 0.028   # desired card<->panel separation to clear the flat-hierarchy floor


def _hex(c: Color) -> str:
    return c.convert("srgb").to_string(hex=True)


def _oklch(c: Color):
    o = c.convert("oklch")
    return o["lightness"], o["chroma"], (o["hue"] or 0.0)


def _relabel(acc_names, idx):
    return acc_names[idx]


def suggest_accent_sep(face: dict, rep: dict) -> dict | None:
    """No-monochrome / ΔE floor: push the closest accent apart from the others."""
    if rep["accent_min_hue_gap"] >= ACCENT_HUE_FLOOR and rep["accent_min_dE"] >= ACCENT_DE_FLOOR:
        return None
    names = ["primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent"]
    acc = [C(face["tokens"][k]) for k in names]
    worst_pair, worst = (0, 1), 999.0
    for i in range(4):
        for j in range(i + 1, 4):
            d = hue_gap(acc[i], acc[j])
            if d < worst:
                worst, worst_pair = d, (i, j)
    idx = worst_pair[1]
    l0, c0, h0 = _oklch(acc[idx])
    others = [acc[k] for k in range(4) if k != idx]

    def neg(x):
        cand = oklch_to_color(*x)
        return -min(min(hue_gap(cand, o) / ACCENT_HUE_FLOOR,
                        delta_e(cand, o) / ACCENT_DE_FLOOR) for o in others)

    res = minimize(neg, [l0, c0, h0], method="Nelder-Mead",
                   options=dict(xatol=1e-3, fatol=1e-3, maxiter=400),
                   bounds=[(l0 - LBOX, l0 + LBOX), (max(0, c0 - CBOX), c0 + CBOX), (h0 - HBOX, h0 + HBOX)])
    return dict(kind="no-monochrome", token=names[idx], was=_hex(acc[idx]),
                suggested=_hex(oklch_to_color(*res.x)), metric=f"min-sep {round(-res.fun,2)}×floor")


def suggest_cvd(face: dict, rep: dict) -> dict | None:
    """Harden the accent pair that collapses most under colour-blindness by rotating/relighting
    the offending accent so the *simulated* pair stays separable, without breaking normal sep."""
    if min(rep["cvd"].values()) >= CVD_DE_FLOOR:
        return None
    names = ["primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent"]
    acc = [C(face["tokens"][k]) for k in names]
    # locate the accent in the worst-collapsing simulated pair (any cvd type)
    worst, pair, cvd_worst = 999.0, (0, 1), "deutan"
    for cvd in ("protan", "deutan", "tritan"):
        sim = [a.filter(cvd, 1).convert("srgb") for a in acc]
        for i in range(4):
            for j in range(i + 1, 4):
                d = delta_e(sim[i], sim[j])
                if d < worst:
                    worst, pair, cvd_worst = d, (i, j), cvd
    idx = pair[1]
    l0, c0, h0 = _oklch(acc[idx])
    others = [acc[k] for k in range(4) if k != idx]

    def neg(x):
        cand = oklch_to_color(*x)
        # keep normal separation acceptable (hard penalty), maximise min CVD separation
        norm = min(min(hue_gap(cand, o) / ACCENT_HUE_FLOOR, delta_e(cand, o) / ACCENT_DE_FLOOR)
                   for o in others)
        pen = 0.0 if norm >= 0.9 else (0.9 - norm) * 50.0
        cvdmin = 999.0
        for cvd in ("protan", "deutan", "tritan"):
            cs = cand.filter(cvd, 1).convert("srgb")
            for o in others:
                cvdmin = min(cvdmin, delta_e(cs, o.filter(cvd, 1).convert("srgb")))
        return -(cvdmin) + pen

    res = minimize(neg, [l0, c0, h0], method="Nelder-Mead",
                   options=dict(xatol=1e-3, fatol=1e-3, maxiter=500),
                   bounds=[(l0 - LBOX, l0 + LBOX), (max(0, c0 - CBOX), c0 + CBOX), (h0 - HBOX, h0 + HBOX)])
    cand = oklch_to_color(*res.x)
    newmin = min(delta_e(cand.filter(cvd, 1).convert("srgb"), o.filter(cvd, 1).convert("srgb"))
                 for cvd in ("protan", "deutan", "tritan") for o in others)
    if newmin <= worst + 0.3:
        return None  # bounded box couldn't meaningfully help
    return dict(kind=f"cvd({cvd_worst})", token=names[idx], was=_hex(acc[idx]),
                suggested=_hex(cand), metric=f"worst-CVD ΔE {round(worst,1)}→{round(newmin,1)}")


def suggest_hierarchy(face: dict, rep: dict) -> dict | None:
    """Card/panel too close in L: relight surfaceElevated (preserve hue/chroma) until the
    derived panel plane separates from the card plane by HIER_DL_TARGET, keeping existing order."""
    flat = [i for i in rep["issues"] if i.startswith("hierarchy flat: card/panel")]
    if not flat:
        return None
    dark = not face["light"]
    base = C(face["tokens"]["surfaceElevated"])
    l0, c0, h0 = _oklch(base)
    card_l = oklab_l(C(face["tokens"]["surface"]))  # proxy; card tracks surface L closely
    pacc = C(face["tokens"]["primaryAccent"])
    # dark: raised/panel should sit ABOVE page/surface; light: panel sits a touch below surface
    direction = 1.0 if dark else -1.0

    def panel_l(l_elev):
        elev = oklch_to_color(l_elev, c0, h0)
        panel = mix_oklab(elev, card_glass_material(pacc, elev, dark), CARD_MATERIAL_SHARE)
        return oklab_l(panel)

    card_plane_l = oklab_l(C(face["tokens"]["surface"]))
    card_plane_l = oklab_l(mix_oklab(C(face["tokens"]["surface"]),
                                     card_glass_material(pacc, C(face["tokens"]["surface"]), dark),
                                     CARD_MATERIAL_SHARE))
    target = card_plane_l + direction * HIER_DL_TARGET
    best_l, best_err = l0, 1e9
    for step in np.linspace(-LBOX, LBOX, 49):
        l = l0 + step
        err = abs(panel_l(l) - target)
        if err < best_err:
            best_err, best_l = err, l
    newsep = abs(panel_l(best_l) - card_plane_l)
    if newsep < HIER_DL_FLOOR or abs(best_l - l0) < 1e-3:
        return None
    return dict(kind="hierarchy", token="surfaceElevated", was=_hex(base),
                suggested=_hex(oklch_to_color(best_l, c0, h0)),
                metric=f"card/panel ΔL →{round(newsep,3)}")


def suggest_topbar_text(face: dict, rep: dict) -> dict | None:
    """Top-bar secondary text under AA: relight textSecondary (preserve hue/chroma) to reach 3.0."""
    if rep["contrast"]["topbar"]["secondary"] >= WCAG_SECONDARY:
        return None
    s = derive_surfaces(face)
    base = C(face["tokens"]["textSecondary"])
    l0, c0, h0 = _oklch(base)
    best = None
    for step in np.linspace(-LBOX, LBOX, 49):
        cand = oklch_to_color(l0 + step, c0, h0)
        cr = wcag(cand, s["topbar"])
        # also keep it legible on card/page
        if cr >= WCAG_SECONDARY and wcag(cand, s["card"]) >= WCAG_SECONDARY:
            if best is None or abs(step) < best[0]:
                best = (abs(step), cand, cr)
    if best is None:
        return None
    return dict(kind="topbar-text", token="textSecondary", was=_hex(base),
                suggested=_hex(best[1]), metric=f"topbar 2ry contrast →{round(best[2],2)}")


def suggest_all(face: dict, rep: dict) -> list[dict]:
    out = []
    for fn in (suggest_accent_sep, suggest_cvd, suggest_hierarchy, suggest_topbar_text):
        try:
            r = fn(face, rep)
        except Exception as e:  # keep the audit resilient
            r = dict(kind=fn.__name__, error=repr(e))
        if r:
            out.append(r)
    return out


# ── Main ────────────────────────────────────────────────────────────────────────────────
def main():
    faces = parse_faces()
    assert len(faces) == 20, f"expected 20 faces, parsed {len(faces)}"
    results = []
    for f in faces:
        rep = score_face(f)
        fixes = suggest_all(f, rep)
        results.append(dict(id=f["id"], light=f["light"],
                            concept=CONCEPTS[f["id"]]["name"], report=rep, suggestions=fixes))

    OUT_JSON.write_text(json.dumps(results, indent=2), encoding="utf-8")

    lines = ["# Folio palette audit", "",
             "Scored against the real authored tokens in `FolioArtDirection.kt`, using the app's",
             "own OKLab / chrome-glass / pool-harmony maths to reconstruct each screen surface.", ""]
    nfail = sum(1 for r in results if not r["report"]["ok"])
    lines.append(f"**{20 - nfail}/20 faces clean, {nfail} with issues.**\n")
    lines.append("| face | concept | animation | min accent hue° | min ΔE | CVD worst | issues |")
    lines.append("|---|---|---|---|---|---|---|")
    for r in results:
        rp = r["report"]
        face = f'{r["id"]} {"light" if r["light"] else "dark"}'
        lines.append(f'| {face} | {r["concept"]} | {rp["animation"]["mirage"]} | '
                     f'{rp["accent_min_hue_gap"]} | {rp["accent_min_dE"]} | '
                     f'{min(rp["cvd"].values())} | {len(rp["issues"])} |')
    lines.append("")
    for r in results:
        rp = r["report"]
        lines.append(f'## {r["id"]} — {"light" if r["light"] else "dark"} ({r["concept"]})')
        lines.append(f'- animation: **{rp["animation"]["mirage"]}** — '
                     f'intent: {CONCEPTS[r["id"]]["intent"]}')
        lines.append(f'- contrast: ' + ", ".join(
            f'{k} {v["primary"]}/{v["secondary"]}' for k, v in rp["contrast"].items()))
        lines.append(f'- hierarchy L: {rp["hierarchy_L"]}')
        lines.append(f'- accents: min hue gap {rp["accent_min_hue_gap"]}°, min ΔE {rp["accent_min_dE"]}')
        lines.append(f'- animation pools vs field: ' + ", ".join(
            f'{p["hex"]}(ΔE {p["dE_vs_field"]})' for p in rp["animation"]["pools"]))
        lines.append(f'- CVD worst accent ΔE: {rp["cvd"]}')
        if rp["issues"]:
            for iss in rp["issues"]:
                lines.append(f'  - ⚠ {iss}')
        else:
            lines.append("  - clean")
        if r["suggestions"]:
            for sg in r["suggestions"]:
                if "error" in sg:
                    lines.append(f'  - (suggester {sg["kind"]} skipped: {sg["error"]})')
                else:
                    lines.append(f'  - fix [{sg["kind"]}]: {sg["token"]} {sg["was"]} → '
                                 f'{sg["suggested"]}  ({sg["metric"]})')
        lines.append("")
    OUT_MD.write_text("\n".join(lines), encoding="utf-8")
    print(f"parsed {len(faces)} faces; {nfail} with issues")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")


if __name__ == "__main__":
    main()
