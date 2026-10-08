"""
Render a whole Folio screen, not just the ground plane.

Everything a reader sees is drawn by the maths the app uses:

  * the ground plane -- `folioField` + `GUILLOCHE_AGSL` + `AMBIENT_AGSL`, on a WebGL canvas;
  * every pane of glass -- the crown, the hero, the nav capsule, the cards -- by
    `LIQUID_GLASS_AGSL`, run per surface and composited by the page. That shader never
    samples the backdrop, which is what makes this affordable: one small offscreen render
    per surface shape, and the page composites it source-over, exactly as Skia does with
    the premultiplied `half4` it returns;
  * every fill and every alpha -- `topBarFill`, `navCapsuleFill`, `glassTierAlpha`, and
    `FolioAtmosphere`'s material ladder -- read from the Kotlin;
  * the layout -- FolioTokens' spacing, radii, cover ladder and type scale.

    python screen.py                  every configured screen and face
    python screen.py home:ocean:1     one screen, one face
"""
import json
import math
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from nav_options import display_form  # noqa: E402  the fill-form derivation
CHROME = pathlib.Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
W, H = 390, 844

# FolioTokens. At 390dp wide, dp == px.
GUTTER = 20
SPACE2 = 12
BAR_H, STATUS_H = 56, 44
COVER_ANCHOR, COVER_SHELF = 148, 84
COVER_ASPECT = 1.5
BLUR_RADIUS = 20
NAV_BOTTOM = 24

# FolioShapes, as the film's (topStart, topEnd, bottomEnd, bottomStart) radii.
R_HERO = (34, 20, 34, 8)
R_CARD = (18, 18, 18, 18)
R_PILL = (999, 999, 999, 999)


# ── colour plumbing ───────────────────────────────────────────────────────────

def _rgb(hexstr):
    v = int(hexstr, 16)
    return (v >> 16) & 255, (v >> 8) & 255, v & 255


def css(hexstr):
    r, g, b = _rgb(hexstr)
    return f"#{r:02x}{g:02x}{b:02x}"


def rgba(hexstr, alpha):
    r, g, b = _rgb(hexstr)
    return f"rgba({r},{g},{b},{alpha:.4f})"


def _rgb01(hexstr):
    v = int(hexstr, 16)
    return (((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255)


def _mix_oklab(a, b, share):
    """Mirrors `mixOklab` in FolioAtmosphere.kt."""
    from chrome import oklab as _ok, from_oklch as _fk
    La, aa, ba = _ok(a)
    Lb, ab, bb = _ok(b)
    x = aa + (ab - aa) * share
    y = ba + (bb - ba) * share
    L = La + (Lb - La) * share
    return _fk(L, math.hypot(x, y), math.degrees(math.atan2(y, x)) % 360)


def _card_material(accent, base):
    """Mirrors `cardGlassMaterial` in FolioAtmosphere.kt."""
    from chrome import oklab as _ok, from_oklch as _fk, max_chroma, hue as _hue
    l = _ok(base)[0]
    h = _hue(accent)
    c = min(0.06, max_chroma(l, h) * 0.96)
    return _fk(l, c, h)


def material_rgb(face, role):
    """The material ladder's `role` for this face, as a 0..1 triple.

    `raisedFill`/`panelFill` are no longer the raw `surface`/`surfaceElevated` -- they carry the
    chrome hue. Read through the same derivation the app uses so the renderer cannot diverge.
    """
    r = face["roles"]
    if role == "raisedFill":
        card = _rgb01(r["surface"])
        return _mix_oklab(card, _card_material(_rgb01(r["primaryAccent"]), card), 0.55)
    if role == "panelFill":
        panel = _rgb01(r["surfaceElevated"])
        return _mix_oklab(panel, _card_material(_rgb01(r["primaryAccent"]), panel), 0.55)
    return _rgb01(r[role])


def rgba01(t, alpha):
    """A 0..1 triple as a CSS colour at the given alpha."""
    return (f"rgba({round(t[0] * 255)},{round(t[1] * 255)},{round(t[2] * 255)},{alpha:.4f})")


def rgb_css(t):
    """A 0..255 triple as a CSS colour."""
    return f"rgb({t[0]},{t[1]},{t[2]})"


def mix_hex(a, b, t):
    va, vb = int(a, 16), int(b, 16)
    out = 0xFF000000
    for sh in (16, 8, 0):
        ca, cb = (va >> sh) & 255, (vb >> sh) & 255
        out |= int(ca + (cb - ca) * t) << sh
    return f"0x{out:08X}"


def deepen_hex(h, amount):
    return mix_hex(h, "0xFF000000", amount)


def relative_luminance(hexstr):
    def lin(u):
        u /= 255.0
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = _rgb(hexstr)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


# ── the pieces ────────────────────────────────────────────────────────────────

def glass(kind, film_id, fill_css, extra="", body=""):
    """One pane of glass: a blurred backdrop, the fill, the shader film, then the content."""
    return (f'<div class="{kind}" data-film="{film_id}" {extra}>'
            f'<div class="blur"></div>'
            f'<div class="fill" style="background:{fill_css}"></div>'
            f'{body}</div>')


def cover(hexstr, w, h, radius=R_CARD, mark=""):
    rr = " ".join(f"{v}px" for v in radius)
    return (f'<div class="cover" style="width:{w}px;height:{h}px;border-radius:{rr};'
            f'background:linear-gradient(155deg,{css(hexstr)} 0%,'
            f'{css(deepen_hex(hexstr, 0.42))} 100%)">'
            f'<span>{mark}</span></div>')


CHROME_ROLES = (json.loads((HERE / "chrome.json").read_text(encoding="utf-8"))["derived"]
          if (HERE / "chrome.json").exists() else {})


def _chrome(face):
    """The derived chrome roles for this face, from `chrome.py`.

    The crown is NOT `topBarFill` any more: it is placed at an accent's hue and solved into the
    contract's salience band, so the two edges of the UI read as a frame rather than as a
    slightly different page.
    """
    key = f"{face['family']}:{'light' if face['light'] else 'dark'}"
    c = CHROME_ROLES.get(key)
    if not c:
        return None
    def rgb(d):
        return tuple(round(v * 255) for v in d["rgb"])
    return {
        "crown": rgb(c["crown"]), "nav": rgb(c["nav"]),
        "onCrown": c["chromeOnFill"]["name"], "onNav": c["navOnActive"]["name"],
    }


def _ctx(face):
    r = face["roles"]
    light = face["lightParams"]
    lx, ly = light["sourceX"] - 0.5, light["sourceY"] - 0.5
    n = (lx * lx + ly * ly) ** 0.5 or 1.0
    return {
        "roles": r,
        "page": r["backgroundBase"],
        "page2": r["backgroundSecondary"],
        "surface": r["surface"],
        "surfaceElevated": r["surfaceElevated"],
        "ink": r["textPrimary"],
        "ink2": r["textSecondary"],
        "ink3": r["textTertiary"],
        "primary": r["primaryAccent"],
        "secondary": r["secondaryAccent"],
        "tertiary": r["tertiaryAccent"],
        "warm": r["warmAccent"],
        # `navigationSurface` is derived: mixA(card, page2, 0.45 light / 0.35 dark).
        "nav": mix_hex(r["surface"], r["backgroundSecondary"], 0.45 if face["light"] else 0.35),
        "navSel": r["navigationSelected"],
        "hairline": r["border"],
        "progress": r["progress"],
        "progressTrack": r["progressTrack"],
        "aProgress": r["accentProgress"],
        "aStreak": r["accentStreak"],
        "aDiscovery": r["accentDiscovery"],
        "aAnnotation": r["accentAnnotation"],
        "well": mix_hex(r["surface"], r["backgroundBase"], 0.45),
        "roles": r,
        "chrome": _chrome(face),
        "light": [lx / n, ly / n],
        "dark": relative_luminance(r["backgroundBase"]) < 0.45 ** 2.2,
    }


def _topbar(c, collapse=1.0, blurred=True):
    """`topBarFill` at the given collapse, plus the two layers under it that are not fills.

    `blurred` defaults to **true**, because that is the path a real device takes:
    `FolioTopBar` computes `blurred = glassCaps.blur && glassBackdrop != null`, which is true
    on any API 33+ build with a registered backdrop, and `topBarFill` then steps the knob
    through `glassTierAlpha(0.35, 0.35, 0.25)` -> **0.25**, not 0.35. Rendering the unblurred
    branch made this crown 40% too opaque and every contrast number taken against it was taken
    against the wrong surface.
    """
    BAR_GLASS, BAR_GLASS_BLURRED = 0.35, 0.25
    knob = (BAR_GLASS * (BAR_GLASS_BLURRED / BAR_GLASS)) if blurred else BAR_GLASS
    solid = min(max((knob - BAR_GLASS) / (1 - BAR_GLASS), 0), 1)
    presence = max(collapse, solid)
    crown = knob * presence
    waist = crown * (0.52 + 0.48 * solid)
    foot = crown * (0.19 + 0.81 * solid)
    scrim = min(knob / BAR_GLASS, 1)
    page = c["page"]
    return (f"linear-gradient(180deg,{rgba(page, crown)} 0%,"
            f"{rgba(page, waist)} 60%,{rgba(page, foot)} 100%)",
            rgba(page, 0.46 * scrim))


def _topbar_sheen(c, collapse=1.0):
    """`mastheadSheenStop(collapse)` = 0.62 - 0.44*collapse, along the daylight axis.

    The bar's mirror finish: a thin specular band that ramps in from `mastheadSheenStop` to
    the far edge. It is one of the reasons the device crown measures lighter than a plain
    `topBarFill` composite, so leaving it out is a visible omission rather than a nicety.
    """
    stop = 0.62 - 0.44 * min(max(collapse, 0.0), 1.0)
    alpha = (0.62 if c["dark"] else 0.92) * 0.30
    tint = "255,255,255" if not c["dark"] else "255,255,255"
    lx, ly = c["light"]
    deg = (math.degrees(math.atan2(ly, lx)) + 90) % 360
    return (f"linear-gradient({deg:.1f}deg,rgba({tint},0) 0%,"
            f"rgba({tint},0) {stop * 100:.1f}%,"
            f"rgba({tint},{alpha:.3f}) 100%)")


def _film_specs(c):
    """The surfaces that get a liquid-glass film, keyed by the `data-film` id.

    `accent` is the surface's own hue; `highlight` is the light's near-white. That split is
    the shader's own: the edge leans toward the surface, the point catches stay the light's.
    """
    return {
        "crown": {"accent": c["nav"], "highlight": "0xFFFFFFFF", "alpha": 0.50,
                  "intensity": 0.80, "r": [0, 0, 0, 0], "light": c["light"]},
        "hero": {"accent": c["primary"], "highlight": "0xFFFFFFFF", "alpha": 0.62,
                 "intensity": 1.0, "r": list(R_HERO), "light": c["light"]},
        "nav": {"accent": c["nav"], "highlight": "0xFFFFFFFF", "alpha": 0.50,
                "intensity": 0.72, "r": list(R_PILL), "light": c["light"]},
        "card": {"accent": c["secondary"], "highlight": "0xFFFFFFFF", "alpha": 0.42,
                 "intensity": 0.45, "r": list(R_CARD), "light": c["light"]},
    }


# ── the screens ───────────────────────────────────────────────────────────────

def home(c):
    ink, ink2, ink3 = css(c["ink"]), css(c["ink2"]), css(c["ink3"])
    cover_grad, scrim = _topbar(c, 1.0)
    sheen = _topbar_sheen(c, 1.0)
    ch = c["chrome"]
    crown_fill = rgb_css(ch["crown"]) if ch else cover_grad
    on_crown = css(c["ink"]) if not ch else {"black": "#000", "white": "#fff"}.get(
        ch["onCrown"], css(c["ink"]))
    c = dict(c, paneAlpha=(0.46 if c["dark"] else 0.66))

    crown = (
        '<div class="crown" data-film="crown">'
        f'<div class="blur"></div>'
        f'<div class="scrim" style="background:linear-gradient(180deg,{scrim} 0%,transparent 100%)"></div>'
        # Painted by the runner from `f.materials` and `topBarFill` -- the app's own values,
        # not a role this pipeline invented. See `_RUNNER`'s `paintChrome`.
        '<div class="fill"></div>'
        '<div class="core"></div>'
        '<div class="sheen"></div>'
        '<div class="footfade"></div>'
        '<div class="hairline"></div>'
        '<div class="statusbar"></div>'
        '<div class="barrow">'
        '<svg class="mark" viewBox="0 0 28 28" width="28" height="28" aria-hidden="true">'
        f'<path d="M3 8c4-3 8-3 11 0v13c-3-3-7-3-11 0V8z" fill="{css(c["primary"])}"/>'
        f'<path d="M25 8c-4-3-8-3-11 0v13c3-3 7-3 11 0V8z" fill="{css(c["tertiary"])}" opacity=".7"/>'
        '</svg>'
        # The wordmark is `colors.onSurface`, which the device measurement confirmed at
        # dE 1.7-3.8 against the rendered glyph on all seven captures.
        f'<h1 class="bartitle" style="color:{ink}">Folio</h1>'
        f'<div class="baractions"><span class="ic" style="border-color:{ink3}"></span>'
        f'<span class="ic" style="border-color:{ink3}"></span></div>'
        '</div></div>')

    hero_body = (
        f'<div class="herocover">{cover(c["primary"], COVER_ANCHOR, round(COVER_ANCHOR * COVER_ASPECT), mark="&#9679;")}</div>'
        '<div class="herotext">'
        f'<p class="eyebrow" style="color:{css(c["aProgress"])}">Reading now</p>'
        f'<h2 class="herotitle" style="color:{ink}">The Shadow of the Wind</h2>'
        f'<p class="heroauthor" style="color:{ink2}">Carlos Ruiz Zaf&oacute;n</p>'
        f'<p class="herofigure" style="color:{ink}"><b>17%</b> read today</p>'
        f'<p class="heropace" style="color:{ink2}">On pace to finish in ~2 months &middot; around Dec 8</p>'
        f'<div class="herobtn" style="background:{css(c["primary"])}">Continue</div>'
        f'<div class="progressbar" style="background:{css(c["progressTrack"])}">'
        f'<i style="width:17%;background:{css(c["progress"])}"></i></div>'
        '</div>')
    hero = (f'<div class="hero" data-film="hero">'
            f'<div class="blur"></div>'
            f'<div class="fill" data-mat="raisedFill" data-alpha="{c["paneAlpha"]}"></div>'
            f'{hero_body}</div>')

    shelf_h = round(COVER_SHELF * COVER_ASPECT)
    shelf = "".join(
        f'<figure class="shelfitem">'
        f'{cover([c["tertiary"], c["secondary"], c["warm"]][i % 3], COVER_SHELF, shelf_h)}'
        f'<figcaption style="color:{ink2}">{40 + i * 11}%</figcaption></figure>'
        for i in range(5))

    bars = "".join(
        f'<div class="bar" style="height:{v}%;'
        f'background:{css(c["aProgress"] if i < 4 else c["aStreak"])}"></div>'
        for i, v in enumerate([38, 62, 24, 71, 55, 83, 44]))

    nav = _capsule(c, selected=0)

    return crown + f"""
<main>
  {hero}
  <section class="ledger">
    <div class="fig"><span style="color:{ink2}">Today</span>
      <b style="color:{ink}">0<span style="color:{ink2}"> of 60 min</span></b></div>
    <div class="fig"><span style="color:{ink2}">Streak</span>
      <b style="color:{ink}">18<span style="color:{ink2}"> days</span></b></div>
  </section>
  <div class="rule" style="background:{css(c["hairline"])}"></div>
  <p class="note" style="color:{css(c["aStreak"])}">Warm spell &middot; 18-day streak</p>
  <p class="notice" style="color:{ink2}">Some titles are excluded &mdash; review &rsaquo;</p>
  <h3 class="sectionhead" style="color:{ink}">Continue reading</h3>
  <div class="shelf">{shelf}</div>
  <h3 class="sectionhead" style="color:{ink}">The week</h3>
  <div class="well" style="background:{rgba(c["well"], 0.30 if c["dark"] else 1.0)}">
    <div class="bars">{bars}</div>
  </div>
</main>
{nav}"""


def stats(c):
    ink, ink2, ink3 = css(c["ink"]), css(c["ink2"]), css(c["ink3"])
    cover_grad, scrim = _topbar(c, 1.0)
    sheen = _topbar_sheen(c, 1.0)
    ch = c["chrome"]
    crown_fill = rgb_css(ch["crown"]) if ch else cover_grad
    on_crown = css(c["ink"]) if not ch else {"black": "#000", "white": "#fff"}.get(
        ch["onCrown"], css(c["ink"]))
    pane = rgba(c["surfaceElevated"], 0.82 if c["dark"] else 0.97)

    crown = (
        '<div class="crown" data-film="crown">'
        f'<div class="blur"></div>'
        f'<div class="scrim" style="background:linear-gradient(180deg,{scrim} 0%,transparent 100%)"></div>'
        '<div class="fill"></div>'
        '<div class="core"></div>'
        '<div class="sheen"></div>'
        '<div class="footfade"></div>'
        '<div class="hairline"></div>'
        '<div class="statusbar"></div>'
        '<div class="barrow">'
        f'<h1 class="bartitle" style="color:{ink}">Stats</h1>'
        f'<div class="baractions"><span class="ic" style="border-color:{ink3}"></span></div>'
        '</div></div>')

    circ = 2 * 3.14159265 * 42
    dial = (f'<svg viewBox="0 0 96 96" width="96" height="96" aria-hidden="true">'
            f'<circle cx="48" cy="48" r="42" fill="none" stroke="{css(c["progressTrack"])}" stroke-width="8"/>'
            f'<circle cx="48" cy="48" r="42" fill="none" stroke="{css(c["progress"])}" stroke-width="8"'
            f' stroke-linecap="round" stroke-dasharray="{circ:.1f}"'
            f' stroke-dashoffset="{circ * 0.38:.1f}" transform="rotate(-90 48 48)"/></svg>')

    card = (f'<div class="dialcard" data-film="card">'
            f'<div class="blur"></div>'
            f'<div class="fill" style="background:{pane}"></div>'
            f'{dial}'
            '<div class="dialtext">'
            f'<p class="figlabel" style="color:{ink2}">Today&rsquo;s goal</p>'
            f'<b style="color:{ink}">37<span style="color:{ink2}"> of 60 min</span></b>'
            '</div></div>')

    pts = [(18, 92), (72, 66), (126, 84), (180, 40), (234, 58), (288, 22), (342, 50)]
    d = f"M{pts[0][0]},{pts[0][1]}"
    for i in range(len(pts) - 1):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        mx = (x0 + x1) / 2
        d += f" C{mx},{y0} {mx},{y1} {x1},{y1}"

    genres = [("Literary fiction", 34, c["aProgress"]), ("History", 22, c["aDiscovery"]),
              ("Science fiction", 18, c["tertiary"]), ("Poetry", 12, c["aStreak"]),
              ("Other", 14, c["secondary"])]
    rows = "".join(
        f'<div class="genre"><span style="color:{ink2}">{n}</span>'
        f'<div class="gtrack"><i style="width:{p}%;background:{css(col)}"></i></div>'
        f'<b style="color:{ink}">{p}%</b></div>'
        for n, p, col in genres)

    return crown + f"""
<main>
  <p class="eyebrow" style="color:{css(c["aProgress"])}">This week</p>
  <h2 class="overture" style="color:{ink}">4h 12m</h2>
  <p class="heropace" style="color:{ink2}">across 5 sessions &middot; 18-day streak</p>
  {card}
  <h3 class="sectionhead" style="color:{ink}">The week</h3>
  <div class="well" style="background:{rgba(c["well"], 0.30 if c["dark"] else 1.0)}">
    <svg viewBox="0 0 360 110" width="100%" height="110" aria-hidden="true">
      <path d="{d}" fill="none" stroke="{css(c["aProgress"])}" stroke-width="2.5" stroke-linecap="round"/>
    </svg>
  </div>
  <h3 class="sectionhead" style="color:{ink}">By genre</h3>
  <div class="genres">{rows}</div>
</main>
{_capsule(c, selected=2)}"""


def label_of(selected):
    return ("Home", "Library", "Stats", "More")[selected]


def _capsule(c, selected):
    """The floating nav capsule. The active item wears `navActiveAccent`, not the signature.

    The contract's point is that the two edges of the UI form a *frame*: the crown takes the
    primary family, the navigation a counterpoint, and they must be related but not duplicates.
    """
    ink3 = css(c["ink3"])
    # The capsule's fill is `atmos.navigationSurface` at `navCapsuleFill(true)` = 0.60, and the
    # selected pill is `folioDestinationAccent(route)` -- the *destination's* accent, not the
    # palette's single signature. Home is progress, Stats is streak.
    # The pill takes the *fill form* of the destination accent -- the same hue the app's
    # `folioDestinationAccent` table picks, re-placed at a fill's chroma rather than an ink's.
    # Mirrors `fillForm(accent)` at alpha 0.45 in `FolioNavShell.kt`.
    dest = {"Home": "accentProgress", "Library": "accentDiscovery",
            "Stats": "accentStreak", "More": "accentAnnotation"}[label_of(selected)]
    acc = display_form(c["roles"][dest])
    items = []
    for i, label in enumerate(("Home", "Library", "Stats", "More")):
        if i == selected:
            items.append(
                f'<div class="navitem sel" style="background:{rgba01(acc, 0.60)}">'
                f'<span class="dot" style="background:{rgb_css(tuple(round(v * 255) for v in acc))}"></span>'
                f'<span style="color:{rgb_css(tuple(round(v * 255) for v in acc))}">{label}</span></div>')
        else:
            items.append(f'<div class="navitem">'
                         f'<span class="dot" style="background:{ink3};opacity:.78"></span></div>')
    # `navCapsuleFill(canBlur = true)` = glassTierAlpha(0.9, 0.9, 0.60) = 0.60.
    return (f'<nav class="capsule" data-film="nav">'
            f'<div class="blur"></div>'
            '<div class="fill"></div>'
            + "".join(items) + '</nav>')


# Every face, Home only. Keyed by the art-direction *family* id, which is what `faces.json`
# carries; the pack names are the pairs (ocean = Lagoon/Abyss, solar = Honey/Ember, ...).
FAMILIES = ["ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
            "mintcoral", "moonstone", "system"]
SCREENS = [("home", fam, light) for fam in FAMILIES for light in (True, False)]


# ── the page ──────────────────────────────────────────────────────────────────

def build_page(face, screen, agsl):
    """A plain template with tokens rather than an f-string.

    The CSS is almost entirely braces; doubling every one of them inside a 200-line f-string
    is how a brace-counting error gets introduced, and it did.
    """
    c = _ctx(face)
    content = home(c) if screen == "home" else stats(c)

    head = _HEAD
    for token, value in (
        ("__W__", str(W)), ("__H__", str(H)), ("__STATUS__", str(STATUS_H)),
        ("__BAR__", str(BAR_H)), ("__SPACE2__", str(SPACE2)), ("__GUTTER__", str(GUTTER)),
        ("__BLUR__", str(BLUR_RADIUS)), ("__NAV_BOTTOM__", str(NAV_BOTTOM)),
        ("__PAGE__", css(c["page"])), ("__CONTENT__", content),
    ):
        head = head.replace(token, value)

    script = _SCRIPT
    for token, value in (
        # `model.js` reads `window.FOLIO`, and it needs the whole set: the geometry
        # characters and the order table are per-family, not per-face.
        ("__FOLIO__", json.dumps({"faces": FACES_JSON, "agsl": agsl})),
        ("__MODEL__", (HERE / "model.js").read_text(encoding="utf-8")),
        ("__SKSL__", (HERE / "sksl.js").read_text(encoding="utf-8")),
        ("__FACE__", json.dumps(face)),
        ("__FILMS__", json.dumps(_film_specs(c))),
        ("__RUNNER__", _RUNNER),
    ):
        script = script.replace(token, value)
    return head + script


# The whole art-direction set, so `model.js` finds the geometry tables it indexes by family.
FACES_JSON = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))


_HEAD = """<!doctype html>
<meta charset="utf-8"><title>folio-screen</title>
<style>
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:#000;overflow:hidden}
#stage{position:relative;width:__W__px;height:__H__px;overflow:hidden;background:__PAGE__}
#ground{position:absolute;inset:0;width:__W__px;height:__H__px;display:block;z-index:0}
.chrome{position:absolute;inset:0;z-index:1;
  font:400 14px/1.45 -apple-system,"Segoe UI",Roboto,sans-serif;-webkit-font-smoothing:antialiased}
#err{position:absolute;inset:0;background:#fff;color:#000;font:11px/1.4 monospace;padding:8px;
  white-space:pre-wrap;display:none;z-index:99;overflow:hidden}

/* every pane of glass: a blurred backdrop, the fill, the shader film */
.blur{position:absolute;inset:0;border-radius:inherit;z-index:-3;
  backdrop-filter:blur(__BLUR__px);-webkit-backdrop-filter:blur(__BLUR__px)}
.fill{position:absolute;inset:0;border-radius:inherit;z-index:-2}
.film{position:absolute;inset:0;border-radius:inherit;z-index:-1;background-size:100% 100%}

.crown{position:absolute;left:0;right:0;top:0;z-index:3;isolation:isolate}
.crown .scrim{position:absolute;left:0;right:0;top:0;height:__STATUS__px;z-index:-2;border-radius:0}
.crown .fill{border-radius:0}
.crown .sheen{position:absolute;inset:0;z-index:-2;border-radius:0;pointer-events:none}
.crown .footfade{position:absolute;left:0;right:0;bottom:0;height:10px;z-index:-2;pointer-events:none}
.crown .core{position:absolute;left:0;right:0;bottom:9px;height:5px;z-index:-1;pointer-events:none}
.crown .hairline{position:absolute;left:0;right:0;bottom:0;height:1px;z-index:-1}
.statusbar{height:__STATUS__px}
.barrow{height:__BAR__px;display:flex;align-items:center;gap:8px;padding:0 10px}
.bartitle{margin:0;font-size:24px;font-weight:600;flex:1 1 auto;letter-spacing:-.005em}
.baractions{display:flex;gap:14px}
.ic{width:20px;height:20px;border-radius:6px;border:1.5px solid;opacity:.7}

main{position:absolute;left:0;right:0;top:100px;bottom:0;
  padding:__SPACE2__px __GUTTER__px 0;overflow:hidden}
.eyebrow{margin:0 0 6px;font-size:11px;font-weight:700;letter-spacing:.5px;text-transform:uppercase}
h2,h3{margin:0}

.hero{position:relative;border-radius:34px 20px 34px 8px;padding:16px;display:flex;gap:14px;
  margin-bottom:20px;isolation:isolate}
.herocover{flex:0 0 auto}
.herotext{flex:1 1 auto;min-width:0}
.herotitle{font-size:22px;font-weight:600;line-height:1.22;margin:0 0 3px}
.heroauthor{margin:0 0 10px;font-size:12.5px}
.herofigure{margin:0 0 3px;font-size:13px}
.herofigure b{font-size:19px;font-weight:600;margin-right:3px}
.heropace{margin:0 0 12px;font-size:11.5px;line-height:1.4}
.herobtn{display:inline-block;padding:7px 20px;border-radius:999px;color:#fff;font-size:13px;
  font-weight:600;margin-bottom:10px}
.progressbar{height:5px;border-radius:3px;overflow:hidden}
.progressbar i{display:block;height:100%;border-radius:3px}

.cover{position:relative;overflow:hidden;display:flex;align-items:flex-end;
  justify-content:center;padding-bottom:8px;flex:0 0 auto}
.cover span{font-size:16px;color:rgba(255,255,255,.5)}

.ledger{display:flex;gap:40px;margin-bottom:14px}
.fig{display:flex;flex-direction:column;gap:1px}
.fig>span{font-size:11px;font-weight:700;letter-spacing:.5px;text-transform:uppercase}
.fig b{font-size:26px;font-weight:600;letter-spacing:-.01em}
.fig b span{font-size:12.5px;font-weight:400}
.rule{height:1px;margin-bottom:12px}
.note{margin:0 0 5px;font-size:12.5px}
.notice{margin:0 0 22px;font-size:12.5px}
.sectionhead{font-size:19px;font-weight:600;margin:0 0 12px}

.shelf{display:flex;gap:12px;margin-bottom:24px}
.shelfitem{margin:0;flex:0 0 auto}
.shelfitem figcaption{font-size:10.5px;margin-top:5px}

.well{border-radius:12px;padding:14px;margin-bottom:20px}
.bars{display:flex;align-items:flex-end;gap:8px;height:110px}
.bar{flex:1 1 0;border-radius:6px 6px 2px 2px;min-height:6px;opacity:.92}

.overture{font-size:44px;font-weight:300;letter-spacing:-.02em;line-height:1.05;margin:0 0 4px}
.dialcard{position:relative;border-radius:18px;padding:18px;display:flex;align-items:center;
  gap:18px;margin:22px 0 24px;isolation:isolate}
.dialtext{display:flex;flex-direction:column;gap:2px}
.figlabel{margin:0;font-size:11px;font-weight:700;letter-spacing:.5px;text-transform:uppercase}
.dialtext b{font-size:28px;font-weight:600}
.dialtext b span{font-size:12.5px;font-weight:400}
.genres{display:flex;flex-direction:column;gap:10px}
.genre{display:grid;grid-template-columns:104px 1fr 40px;align-items:center;gap:10px;font-size:12.5px}
.gtrack{height:6px;border-radius:3px;background:rgba(128,128,128,.22);overflow:hidden}
.gtrack i{display:block;height:100%;border-radius:3px}
.genre b{text-align:right;font-weight:500}

.capsule{position:absolute;left:16px;right:16px;bottom:__NAV_BOTTOM__px;
  height:54px;z-index:4;border-radius:999px;display:flex;align-items:center;justify-content:center;
  gap:2px;isolation:isolate}
.navitem{height:42px;border-radius:999px;display:flex;align-items:center;justify-content:center;
  gap:5px;padding:0 14px;font-size:11px;font-weight:700;letter-spacing:.4px}
.navitem .dot{width:19px;height:19px;border-radius:6px;display:block}
</style></head>
<body><div id="stage">
<canvas id="ground"></canvas>
<div class="chrome">__CONTENT__</div>
<pre id="err"></pre>
</div>
"""

_SCRIPT = """<script>window.FOLIO = __FOLIO__;</script>
<script>__MODEL__</script>
<script>__SKSL__</script>
<script>
const FACE = __FACE__;
const FILM_SPECS = __FILMS__;
// Set before model.js evaluates, so the card control is read at build time.
window.FOLIO_CARD_OFF = new URLSearchParams(location.search).get("card") === "plain";
__RUNNER__
</script></body></html>"""


_RUNNER = r"""
(function () {
  const S = 2, W = 390, H = 844, BW = W * S, BH = H * S;
  const AGSL = window.FOLIO.agsl;
  const fail = (m) => { const e = document.getElementById("err");
    e.style.display = "block"; e.textContent = m; document.title = "ERR"; };
  const VS = "#version 300 es\nin vec2 p; void main(){ gl_Position = vec4(p,0,1); }";
  const mkShader = (g, t, s) => { const o = g.createShader(t); g.shaderSource(o, s);
    g.compileShader(o);
    if (!g.getShaderParameter(o, g.COMPILE_STATUS)) {
      fail("shader compile:\n" + g.getShaderInfoLog(o)); return null; }
    return o; };
  const mkProgram = (g, frag) => {
    const p = g.createProgram();
    const v = mkShader(g, g.VERTEX_SHADER, VS); const f = mkShader(g, g.FRAGMENT_SHADER, frag);
    if (!v || !f) return null;
    g.attachShader(p, v); g.attachShader(p, f); g.linkProgram(p);
    if (!g.getProgramParameter(p, g.LINK_STATUS)) {
      fail("link:\n" + g.getProgramInfoLog(p)); return null; }
    g.useProgram(p);
    const b = g.createBuffer(); g.bindBuffer(g.ARRAY_BUFFER, b);
    g.bufferData(g.ARRAY_BUFFER, new Float32Array([-1,-1,3,-1,-1,3]), g.STATIC_DRAW);
    const l = g.getAttribLocation(p, "p"); g.enableVertexAttribArray(l);
    g.vertexAttribPointer(l, 2, g.FLOAT, false, 0, 0);
    return p;
  };

  let f;
  try { f = FOLIO_MODEL.buildFace(FACE.family, FACE.light); }
  catch (e) { fail("model: " + e.message); return; }
  const M = FOLIO_MODEL.MODEL, a = f.atmos, sp = f.spec, dark = a.isDark;
  // `tint=0` renders the same geometry, alpha and lighting with GLASS_TINT forced to zero.
  // That is the untinted control the GCC metric compares against -- a real second render, not
  // an approximation of one, so the difference isolates what the *material* contributes.
  const TINT_ON = new URLSearchParams(location.search).get("tint") !== "0";
  const Q = new URLSearchParams(location.search);
  const LEGACY_GLASS = Q.get("glass") === "legacy";
  const CORE_ON = Q.get("core") !== "0";
  // Layer attribution: each of the crown's overlays can be switched off independently, so the
  // acceptance measurement can say *which layer* removes the body's chroma rather than guessing.
  const SCRIM_ON = Q.get("scrim") !== "0";
  const SHEEN_ON = Q.get("sheen") !== "0";

  // ── the crown and the capsule ──────────────────────────────────────────────
  // `FolioTopBar`'s own layers, with the glass body tinted. Alpha is `topBarFill`'s and is not
  // touched: colour strength comes from GLASS_TINT and GLASS_CHROMA_BOOST, because raising the
  // alpha to gain colour also destroys the translucency that makes it glass.
  (function paintChrome() {
    const m = f.materials;
    if (LEGACY_GLASS) {
      // B: the plane as it was before the chromatic change -- mixed from two near-neutral
      // inputs, so it anchors to page chroma. The control the body's GCC is measured against.
      m.barGlass = [].concat(
        dark ? FOLIO_MODEL.mixG(a.fieldTop, m.navPlane, 0.35)
             : FOLIO_MODEL.mixG(FOLIO_MODEL.liftG(a.fieldTop, 0.42), m.navPlane, 0.20),
        [dark ? 0.36 : 0.40]);
    }
    const fill = FOLIO_MODEL.topBarFill(1.0, true);
    const g = FOLIO_MODEL.GLASS[dark ? "dark" : "light"];
    const base = a.fieldTop;                 // the field the glass sits over
    const accent = f.colors.primaryAccent;   // the crown's family
    const rgba = function (c, alpha) {
      return "rgba(" + Math.round(c[0] * 255) + "," + Math.round(c[1] * 255) + "," +
             Math.round(c[2] * 255) + "," + alpha.toFixed(4) + ")";
    };

    const crown = document.querySelector(".crown");
    if (crown) {
      // Spatial variation: tau rises toward the lower edge, and the sheen adds a whisper at the
      // top, so the pane reads as a coloured optical material rather than a flat rectangle.
      // The veil is `barGlass` at `topBarFill`'s three alphas -- exactly what `FolioTopBar`
      // draws (`veil = folioBarGlass(atmos)`, composited at `fill.{crown,waist,foot}`).
      //
      // It must NOT be re-derived here through `tintGlass`: that was the previous task's
      // approach, and leaving it in meant the renderer drew a *different algorithm* from the
      // app while the report claimed to describe the app. The `glass=legacy` control silently
      // did nothing for the same reason -- it overrode `barGlass`, which nothing read.
      // The app's chain is TWO steps, and the renderer must run both:
      //   `barGlassFor` = tintGlass(barGlass, chromeAccent, tau, k, lambda)
      //   `FolioTopBar` = that, composited at topBarFill's {crown, waist, foot}
      // Omitting the first step drew a less chromatic plane than the app ships, so the render
      // understated the crown. Mirror it exactly.
      const veil = FOLIO_MODEL.tintGlass(
        m.barGlass, f.colors.primaryAccent,
        TINT_ON ? g.tau : 0, g.k, g.lambda);
      const stops = [
        [fill.crown, "0%"], [fill.waist, "60%"], [fill.foot, "100%"]];
      crown.querySelector(".fill").style.background = "linear-gradient(180deg," +
        stops.map(function (s) {
          return rgba(veil, s[0]) + " " + s[1];
        }).join(",") + ")";

      // The status scrim, the sheen, the foot fade and the hairline are unchanged.
      const sa = m.barScrim[3] * fill.scrim;
      const grad = function (list) {
        return "linear-gradient(180deg," + list.map(function (s) {
          return rgba(s[0], s[1]) + " " + s[2];
        }).join(",") + ")";
      };
      if (SCRIM_ON) {
        crown.querySelector(".scrim").style.background = grad([
          [m.barScrim, sa, "0%"], [m.barScrim, sa * 0.55, "45%"], [m.barScrim, sa * 0.12, "100%"]]);
      } else {
        crown.querySelector(".scrim").style.background = "none";
      }
      const sheenA = m.rimLight[3] * 0.30 * fill.presence;
      if (SHEEN_ON) {
        crown.querySelector(".sheen").style.background = grad([
          [m.rimLight, sheenA, "0%"], [m.rimLight, 0, "38%"]]);
      } else {
        crown.querySelector(".sheen").style.background = "none";
      }
      crown.querySelector(".footfade").style.background = grad([
        [m.barGlass, 0, "0%"], [m.barGlass, fill.crown * 0.45, "100%"]]);
      crown.querySelector(".hairline").style.background =
        "linear-gradient(90deg," + rgba(m.hairline, 0.55 * fill.presence) + " 0%," +
        rgba(m.hairline, 0.10 * fill.presence) + " 100%)";
      // The chromatic core stays. The glass gives the crown its material colour; the core gives
      // it the brand accent. Removing the core must not be how the body passes.
      const core = crown.querySelector(".core");
      if (core && !CORE_ON) core.style.display = "none";
      if (core && CORE_ON) {
        core.style.background =
          "linear-gradient(90deg," + rgba(accent, 0.62) + " 0%," + rgba(accent, 0.62) + " 72%," +
          rgba(accent, 0.16) + " 100%)";
      }
    }
    // Every surface that carries a material is painted from the model, so there is one
    // implementation and one control rather than a Python copy that can silently disagree.
    document.querySelectorAll("[data-mat]").forEach(function (el) {
      const c = m[el.dataset.mat];
      if (!c) return;
      el.style.background = rgba(c, parseFloat(el.dataset.alpha || "1"));
    });
    // The capsule's glass is tinted on the same principle.
    const cap = document.querySelector(".capsule");
    if (cap) {
      const navTau = TINT_ON ? g.tau * 0.6 : 0;
      cap.querySelector(".fill").style.background = rgba(
        FOLIO_MODEL.tintGlass(a.fieldBottom, f.colors.navigationSelected, navTau, g.k, g.lambda),
        FOLIO_MODEL.navCapsuleFill(true));
    }
  })();

  // ── the ground plane: field + plate, then the ambient film ────────────────
  const gl = document.getElementById("ground").getContext("webgl2",
    { antialias: false, preserveDrawingBuffer: true });
  if (!gl) { fail("no webgl2"); return; }
  const p1 = mkProgram(gl, buildFolioFragmentShader(AGSL.source));
  if (!p1) return;
  const u = (n) => gl.getUniformLocation(p1, n);
  const s1 = (n, v) => { const l = u(n); if (l) gl.uniform1f(l, v); };
  const s2 = (n, x, y) => { const l = u(n); if (l) gl.uniform2f(l, x, y); };
  const s3 = (n, c) => { const l = u(n); if (l) gl.uniform3f(l, c[0], c[1], c[2]); };
  const s4 = (n, x, y, z, w) => { const l = u(n); if (l) gl.uniform4f(l, x, y, z, w); };
  s2("size", BW, BH); s1("aspect", W / H);
  s3("accent", f.tiers[0]); s3("accentB", f.tiers[1]); s3("accentC", f.tiers[2]);
  s3("fieldTop", a.fieldTop); s3("fieldBottom", a.fieldBottom);
  s2("fieldSource", a.fieldSourceX, a.fieldSourceY);
  s1("intensity", f.intensity); s1("phase", 0.0); s1("breath", 0.0);
  s1("faceWeight", f.weight); s1("isDark", dark ? 1 : 0);
  s1("family", FACE.geometryOrdinal);
  s1("darkDepth", M.GUILLOCHE_DARK_DEPTH); s1("darkLift", M.GUILLOCHE_DARK_LIFT);
  s1("darkGlow", M.GUILLOCHE_DARK_GLOW); s1("minVisibleDelta", M.GUILLOCHE_MIN_VISIBLE_DELTA);
  s1("lightTint", M.GUILLOCHE_LIGHT_TINT); s1("lightDepth", M.GUILLOCHE_LIGHT_DEPTH);
  s1("shimmerDepth", M.GUILLOCHE_SHIMMER);
  sp.waves.forEach((w, i) => { s1("freq" + i, w.frequency); s1("cross" + i, w.cross);
    s1("crossFreq" + i, w.crossFreq); s1("angle" + i, w.angle); s1("weight" + i, w.weight);
    s1("share" + i, w.share); s1("drift" + i, w.drift); });
  s2("fanCentre", sp.fan.x, sp.fan.y); s1("fanRays", sp.fan.rays);
  s1("fanReach", sp.fan.reach); s1("fanShare", sp.fan.share); s1("fanSpin", sp.fan.spin);
  s1("quietMargin", M.GUILLOCHE_QUIET_MARGIN); s1("quietCount", 0);
  for (let i = 0; i < 4; i++) { s4("quiet" + i, 0, 0, 0, 0); s1("quietCover" + i, 0); }
  FOLIO_MODEL.DAYLIGHT_POOLS.forEach((pool, i) => {
    s3("poolColour" + i, f.field.poolColors[pool.index]);
    s2("poolCentre" + i, pool.fx, pool.fy);
    s1("poolRadius" + i, pool.fr * BW); s1("poolAlpha" + i, f.field.poolAlpha); });
  gl.viewport(0, 0, BW, BH); gl.disable(gl.BLEND);
  gl.drawArrays(gl.TRIANGLES, 0, 3);

  const p2 = mkProgram(gl, skslFragmentShader(AGSL.ambient, "ambientMain"));
  if (!p2) return;
  gl.uniform2f(gl.getUniformLocation(p2, "size"), BW, BH);
  const ca = f.colors.primary, cb = f.colors.tertiary;
  gl.uniform3f(gl.getUniformLocation(p2, "colorA"), ca[0], ca[1], ca[2]);
  gl.uniform3f(gl.getUniformLocation(p2, "colorB"), cb[0], cb[1], cb[2]);
  gl.uniform1f(gl.getUniformLocation(p2, "intensity"), AGSL.ambientIntensity);
  gl.uniform1f(gl.getUniformLocation(p2, "phase"), 0.0);
  gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
  gl.drawArrays(gl.TRIANGLES, 0, 3);

  // ── the liquid-glass film, one offscreen render per surface shape ─────────
  // LIQUID_GLASS_AGSL never samples the backdrop, so this is exact rather than an
  // approximation: the shader draws the specular, and the page composites the premultiplied
  // result source-over -- the same operation Skia performs.
  const filmSrc = skslFragmentShader(AGSL.glass, "glassMain");
  const hexToRgb = (v) => { const n = typeof v === "string" ? parseInt(v, 16) : v;
    return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255]; };
  const cache = {};
  const filmFor = (id, w, h) => {
    const key = id + "@" + Math.round(w) + "x" + Math.round(h);
    if (cache[key] !== undefined) return cache[key];
    const spec = FILM_SPECS[id];
    const cw = Math.max(2, Math.round(w * S)), ch = Math.max(2, Math.round(h * S));
    const c = document.createElement("canvas"); c.width = cw; c.height = ch;
    const g = c.getContext("webgl2", { antialias: false, preserveDrawingBuffer: true });
    if (!g) { cache[key] = null; return null; }
    const prog = mkProgram(g, filmSrc);
    if (!prog) { cache[key] = null; return null; }
    const U = (n) => g.getUniformLocation(prog, n);
    const acc = hexToRgb(spec.accent), hi = hexToRgb(spec.highlight);
    // A pill's radius is half the short side; a square corner is 0.
    const r = spec.r.map((v) => (v >= 999 ? Math.min(cw, ch) / 2 : v * S));
    g.uniform2f(U("size"), cw, ch);
    g.uniform3f(U("accent"), acc[0], acc[1], acc[2]);
    g.uniform3f(U("highlight"), hi[0], hi[1], hi[2]);
    g.uniform1f(U("highlightAlpha"), spec.alpha);
    g.uniform2f(U("light"), spec.light[0], spec.light[1]);
    g.uniform1f(U("intensity"), spec.intensity);
    g.uniform2f(U("focal"), 0.5, 0.5);
    g.uniform1f(U("focalStrength"), 0.0);
    g.uniform4f(U("radii"), r[0], r[1], r[2], r[3]);
    g.uniform1f(U("phase"), 0.0);
    g.viewport(0, 0, cw, ch);
    g.clearColor(0, 0, 0, 0); g.clear(g.COLOR_BUFFER_BIT);
    g.drawArrays(g.TRIANGLES, 0, 3);
    const url = c.toDataURL("image/png");
    cache[key] = url;
    return url;
  };

  document.querySelectorAll("[data-film]").forEach((el) => {
    const rect = el.getBoundingClientRect();
    const url = filmFor(el.dataset.film, rect.width, rect.height);
    if (!url) return;
    const film = document.createElement("div");
    film.className = "film";
    film.style.backgroundImage = "url(" + url + ")";
    el.insertBefore(film, el.firstChild);
  });

  document.title = "OK " + FACE.family + (FACE.light ? ":L" : ":D");
})();
"""


def main():
    agsl = json.loads((HERE / "agsl.json").read_text(encoding="utf-8"))
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    by_key = {(f["family"], f["light"]): f for f in faces}

    only = sys.argv[1] if len(sys.argv) > 1 else None
    targets = SCREENS
    if only:
        screen, fam, lt = only.split(":")
        targets = [(screen, fam, lt == "1")]

    out = HERE / "screens"
    out.mkdir(exist_ok=True)
    html = HERE / "screen.html"
    done = 0
    for screen, fam, light in targets:
        face = by_key[(fam, light)]
        html.write_text(build_page(face, screen, agsl), encoding="utf-8")
        name = f"{screen}-{fam}-{'light' if light else 'dark'}"
        png = out / f"{name}.png"
        cmd = [str(CHROME), "--headless=new", "--disable-gpu", "--enable-unsafe-swiftshader",
               "--no-sandbox", "--hide-scrollbars", "--disable-extensions",
               "--force-device-scale-factor=2", f"--window-size={W},{H}",
               "--virtual-time-budget=25000", f"--screenshot={png}",
               f"file:///{html.as_posix()}"]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        ok = png.exists() and png.stat().st_size > 0
        print(f"  {name:<26} {'ok' if ok else 'FAILED'}  "
              f"{png.stat().st_size // 1024 if ok else 0} KB")
        if not ok:
            print("   ", r.stderr.strip()[:300])
        done += ok
    print(f"\n{done} / {len(targets)} screens")
    return 0 if done == len(targets) else 1


if __name__ == "__main__":
    raise SystemExit(main())
