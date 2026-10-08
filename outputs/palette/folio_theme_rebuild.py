"""
Folio theme rebuild — palette author, verifier and Kotlin emitter.

Every theme is authored in OKLCH (perceptually uniform) rather than as sRGB hex, so
"the same lightness" and "a different hue" mean what they say. The 10 themes are
designed from their NAMES, each with its own hue territory; the light and dark faces
of a theme are separately art-directed (the dark face is not the light face darkened).

Run:  python outputs/palette/folio_theme_rebuild.py
Emits: outputs/palette/folio_theme_audit.md
       outputs/palette/generated_art_directions.kt
       outputs/palette/generated_reader_themes.kt
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from coloraide import Color

HERE = os.path.dirname(os.path.abspath(__file__))

# ─────────────────────────────────────────────────────────────────────────────
# colour helpers
# ─────────────────────────────────────────────────────────────────────────────


def oklch(l: float, c: float, h: float) -> Color:
    """An OKLCH colour mapped into sRGB, reducing chroma rather than clipping."""
    col = Color("oklch", [l, c, h]).convert("srgb")
    col.fit("srgb", method="oklch-chroma")
    return col


def hx(col: Color) -> str:
    return col.convert("srgb").to_string(hex=True, upper=True)


def argb(col: Color) -> str:
    return "0xFF" + hx(col)[1:]


def contrast(a: Color, b: Color) -> float:
    return Color(a).contrast(b)


def de(a: Color, b: Color) -> float:
    return Color(a).delta_e(b, method="2000")


def fit_contrast(
    l: float, c: float, h: float, against: Color, target: float, darken: bool
) -> Color:
    """Walk lightness until the colour clears `target` contrast on `against`.

    Light faces can only add darkness, dark faces can only add light, so the
    direction is the face's polarity rather than a search over both.
    """
    best = oklch(l, c, h)
    if contrast(best, against) >= target:
        return best
    lo, hi = (0.0, l) if darken else (l, 1.0)
    for _ in range(40):
        mid = (lo + hi) / 2
        cand = oklch(mid, c, h)
        if contrast(cand, against) >= target:
            best = cand
            if darken:
                lo = mid
            else:
                hi = mid
        else:
            if darken:
                hi = mid
            else:
                lo = mid
    # keep as much chroma/lightness as the floor allows
    return best


def fit_against_each_other(
    l: float, c: float, h: float, track: Color, target: float, darken: bool
) -> Color:
    return fit_contrast(l, c, h, track, target, darken)


# ─────────────────────────────────────────────────────────────────────────────
# the authored design
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class Face:
    """One separately art-directed face of a theme."""

    name: str              # display name, e.g. "Gallery" / "Dark"
    reader_id: str         # reader page preset id
    light: bool
    # the four authored hues (degrees, OKLCH)
    h_primary: float
    h_secondary: float
    h_tertiary: float
    h_warm: float
    # chroma of the primary accent — a theme's "loudness"
    c_primary: float
    # the page's own cast
    h_page: float
    c_page: float
    l_page: float          # backgroundBase lightness
    note: str = ""


@dataclass
class Theme:
    pack_id: str
    concept: str
    shader: str
    shader_why: str
    light: Face
    dark: Face


# Light faces sit on an even hue spread so the ten themes never read as one app in
# different sunglasses:  18  42  80  145  192  215  250  272  310  350
# Dark faces are re-pitched per their own name (Ember is not Honey darkened):
#                         8  33  62  168  195  220  250  272  295  345

THEMES: List[Theme] = [
    Theme(
        pack_id="gallery",
        concept="White-cube gallery — plaster walls, a cobalt placard, the red sold-dot, a brass rail",
        shader="GrainGradient",
        shader_why=(
            "A gallery is still. GrainGradient with Corners shape and high softness is a vast "
            "quiet wall whose light drifts imperceptibly, with canvas grain over it — motion you "
            "feel rather than watch."
        ),
        light=Face(
            name="Gallery", reader_id="white", light=True,
            h_primary=248, h_secondary=28, h_tertiary=85, h_warm=150,
            c_primary=0.17, h_page=75, c_page=0.006, l_page=0.970,
            note="warm plaster white; the art supplies the colour",
        ),
        dark=Face(
            name="Dark", reader_id="dark", light=False,
            h_primary=250, h_secondary=28, h_tertiary=85, h_warm=150,
            c_primary=0.145, h_page=255, c_page=0.013, l_page=0.150,
            note="the gallery after hours — cool charcoal, picture-lights off",
        ),
    ),
    Theme(
        pack_id="manuscript",
        concept="Vellum codex — rubric sienna initials, iron-gall indigo, verdigris, gold leaf",
        shader="Warp",
        shader_why=(
            "Warp on Stripes with moderate distortion and swirl reads as ink bleeding through "
            "laid paper and the ruled lines of a scribe's page pulling out of true."
        ),
        light=Face(
            name="Manuscript", reader_id="sepia", light=True,
            h_primary=42, h_secondary=265, h_tertiary=165, h_warm=92,
            c_primary=0.15, h_page=80, c_page=0.034, l_page=0.955,
            note="vellum cream",
        ),
        dark=Face(
            name="Espresso", reader_id="espresso", light=False,
            h_primary=62, h_secondary=258, h_tertiary=168, h_warm=35,
            c_primary=0.13, h_page=52, c_page=0.028, l_page=0.140,
            note="dark roast with crema gold — a yellow-brown, deliberately not Ember's orange",
        ),
    ),
    Theme(
        pack_id="silver",
        concept="Gelatin-silver print — cool platinum, steel-violet, an iridescent magenta flash",
        shader="Dithering",
        shader_why=(
            "Silver is a photographic process before it is a colour. Dithering with a Bayer 4x4 "
            "matrix over a Swirl is the silver-halide grain of a darkroom print, which is the one "
            "shader that names the theme instead of decorating it."
        ),
        light=Face(
            name="Silver", reader_id="gray", light=True,
            h_primary=276, h_secondary=215, h_tertiary=330, h_warm=88,
            c_primary=0.095, h_page=255, c_page=0.007, l_page=0.968,
            note="cool platinum; restrained chroma is the point — a metal, not a pigment",
        ),
        dark=Face(
            name="Graphite", reader_id="graphite", light=False,
            h_primary=278, h_secondary=215, h_tertiary=330, h_warm=88,
            c_primary=0.098, h_page=258, c_page=0.013, l_page=0.160,
            note="pencil graphite — blue-black with the silver flash",
        ),
    ),
    Theme(
        pack_id="arctic",
        concept="Ice and aurora — glacier blue, snow-shadow, aurora green, low-sun gold",
        shader="Swirl",
        shader_why=(
            "Swirl's concentric twisted bands at low twist and high softness are aurora curtains "
            "over ice strata — the only shader whose geometry is literally the sky this theme is named for."
        ),
        light=Face(
            name="Arctic", reader_id="arctic", light=True,
            h_primary=218, h_secondary=158, h_tertiary=288, h_warm=72,
            c_primary=0.145, h_page=212, c_page=0.024, l_page=0.962,
            note="pale ice; snow's shadow is blue",
        ),
        dark=Face(
            name="Midnight", reader_id="midnight", light=False,
            h_primary=218, h_secondary=158, h_tertiary=288, h_warm=72,
            c_primary=0.155, h_page=232, c_page=0.030, l_page=0.130,
            note="polar night — navy, not black",
        ),
    ),
    Theme(
        pack_id="sakura",
        concept="Cherry blossom — petal rose, young leaf, branch umber, spring sky",
        shader="DotOrbit",
        shader_why=(
            "DotOrbit's dots orbiting a repeating field are petals on the wind. Nothing else in "
            "the set drifts as discrete soft bodies, which is exactly what sakura is."
        ),
        light=Face(
            name="Sakura", reader_id="paper", light=True,
            h_primary=350, h_secondary=140, h_tertiary=55, h_warm=240,
            c_primary=0.145, h_page=355, c_page=0.024, l_page=0.968,
            note="blush paper",
        ),
        dark=Face(
            name="Blossom", reader_id="blossom", light=False,
            h_primary=344, h_secondary=140, h_tertiary=55, h_warm=240,
            c_primary=0.150, h_page=340, c_page=0.030, l_page=0.145,
            note="night hanami — plum sky, lantern-lit petals",
        ),
    ),
    Theme(
        pack_id="honey",
        concept="Honey — amber gold, hive-shade teal, propolis, clover",
        shader="Metaballs",
        shader_why=(
            "Metaballs are merging gooey bodies: honey drops coalescing. Viscosity is the whole "
            "character of the name and this is the viscous shader."
        ),
        light=Face(
            name="Honey", reader_id="honey", light=True,
            h_primary=80, h_secondary=196, h_tertiary=32, h_warm=128,
            c_primary=0.150, h_page=88, c_page=0.046, l_page=0.960,
            note="honeycomb cream — the warmest page in the set",
        ),
        dark=Face(
            name="Ember", reader_id="ember", light=False,
            h_primary=36, h_secondary=196, h_tertiary=62, h_warm=128,
            c_primary=0.160, h_page=48, c_page=0.032, l_page=0.132,
            note="a dying coal — orange-red, deliberately away from Espresso's yellow-brown",
        ),
    ),
    Theme(
        pack_id="moss",
        concept="Moss — damp forest green, lichen chartreuse, bark, fungus rose",
        shader="Voronoi",
        shader_why=(
            "Voronoi cells with a gap and a slow glow are a colony: lichen and moss spreading over "
            "stone. Cellular growth is the shader's literal subject."
        ),
        light=Face(
            name="Moss", reader_id="moss", light=True,
            h_primary=145, h_secondary=110, h_tertiary=50, h_warm=8,
            c_primary=0.135, h_page=132, c_page=0.030, l_page=0.962,
            note="pale moss",
        ),
        dark=Face(
            name="Peacock", reader_id="peacock", light=False,
            h_primary=164, h_secondary=220, h_tertiary=48, h_warm=268,
            c_primary=0.150, h_page=178, c_page=0.035, l_page=0.135,
            note="a peacock eye is teal + sapphire + bronze + a violet sheen — its own hue set, not Moss at night",
        ),
    ),
    Theme(
        pack_id="dawn",
        concept="Dawn — lilac sky, apricot horizon, morning blue, pale gold",
        shader="GodRays",
        shader_why=(
            "GodRays are volumetric shafts from an off-screen source with bloom — sunrise, "
            "unambiguously. Dawn's source sits low and below; Dusk's is lower still and warmer."
        ),
        light=Face(
            name="Dawn", reader_id="dawn", light=True,
            h_primary=310, h_secondary=48, h_tertiary=245, h_warm=95,
            c_primary=0.140, h_page=330, c_page=0.030, l_page=0.966,
            note="first light — lilac over apricot",
        ),
        dark=Face(
            name="Dusk", reader_id="dusk", light=False,
            h_primary=308, h_secondary=28, h_tertiary=258, h_warm=350,
            c_primary=0.145, h_page=290, c_page=0.032, l_page=0.132,
            note="twilight violet with the last ember on the horizon",
        ),
    ),
    Theme(
        pack_id="lagoon",
        concept="Lagoon — turquoise shallows, coral, sand, the deep beyond the reef",
        shader="MeshGradient",
        shader_why=(
            "MeshGradient's spots travel on organic trajectories and blend without ever climbing "
            "to white — moving water. Turquoise, sand and coral drifting through each other is a "
            "shallow reef seen from above."
        ),
        light=Face(
            name="Lagoon", reader_id="lagoon", light=True,
            h_primary=188, h_secondary=30, h_tertiary=88, h_warm=250,
            c_primary=0.140, h_page=196, c_page=0.034, l_page=0.964,
            note="sunlit shallows",
        ),
        dark=Face(
            name="Abyss", reader_id="abyss", light=False,
            h_primary=193, h_secondary=282, h_tertiary=150, h_warm=20,
            c_primary=0.160, h_page=210, c_page=0.030, l_page=0.098,
            note="the deepest page in the set — bioluminescence is the only light",
        ),
    ),
    Theme(
        pack_id="rouge",
        concept="Rouge — theatrical crimson, gilt, velvet plum, a cold counterpoint",
        shader="SmokeRing",
        shader_why=(
            "A turbulent ring of smoke in a red spotlight: the house before curtain. SmokeRing is "
            "the only shader with a stage's single luminous centre and soft falloff to the wings."
        ),
        light=Face(
            name="Rouge", reader_id="rouge", light=True,
            h_primary=18, h_secondary=85, h_tertiary=335, h_warm=198,
            c_primary=0.155, h_page=20, c_page=0.022, l_page=0.966,
            note="playbill cream with a crimson press",
        ),
        dark=Face(
            name="Garnet", reader_id="garnet", light=False,
            h_primary=14, h_secondary=78, h_tertiary=325, h_warm=190,
            c_primary=0.150, h_page=8, c_page=0.030, l_page=0.125,
            note="wine-black with facet fire",
        ),
    ),
]


# ─────────────────────────────────────────────────────────────────────────────
# role derivation
# ─────────────────────────────────────────────────────────────────────────────

# WCAG floors we actually hold to. Body text is AA (4.5); the three text tiers are
# authored well above it. The 3dp progress bar is a graphical object, so its floor is
# 1.4.11's 3:1 measured against its own track, not against the page.
AA_TEXT = 4.5
AA_LARGE = 3.0
GRAPHIC = 3.0


@dataclass
class Roles:
    """The 25 authored art-direction roles, as Colors."""

    values: Dict[str, Color] = field(default_factory=dict)

    def __getitem__(self, k: str) -> Color:
        return self.values[k]

    def __setitem__(self, k: str, v: Color) -> None:
        self.values[k] = v


def derive(face: Face) -> Roles:
    r = Roles()
    L = face.light
    hp, hs, ht, hw = face.h_primary, face.h_secondary, face.h_tertiary, face.h_warm
    hpg, cpg = face.h_page, face.c_page

    if L:
        # ── planes: a tinted paper, never pure white ──
        r["backgroundBase"] = oklch(face.l_page, cpg, hpg)
        r["backgroundSecondary"] = oklch(face.l_page - 0.045, cpg * 1.9, (hpg + hs) / 2 if abs(hpg - hs) < 180 else hpg)
        r["surface"] = oklch(min(0.995, face.l_page + 0.018), cpg * 0.55, hpg)
        r["surfaceElevated"] = oklch(min(1.0, face.l_page + 0.030), cpg * 0.30, hpg)
        # ── ink: tinted with the page's own cast so the page reads as one material ──
        # Fitted against `backgroundSecondary`, the *darkest* plane the ink ever lands on,
        # so a tier that clears its floor there clears it on every plane. Authoring the
        # tiers at a fixed lightness instead put every light face's third tier at 2.5–2.9:1.
        floor_plane = r["backgroundSecondary"]
        r["textPrimary"] = fit_contrast(0.255, 0.030, hpg, floor_plane, 7.0, True)
        r["textSecondary"] = fit_contrast(0.495, 0.026, hpg, floor_plane, 4.5, True)
        r["textTertiary"] = fit_contrast(0.645, 0.022, hpg, floor_plane, 3.0, True)
        # ── edges ──
        r["border"] = oklch(0.845, 0.018, hpg)
        r["borderHighlight"] = oklch(0.915, 0.012, hpg)
        # ── the four authored accents ──
        r["primaryAccent"] = oklch(0.560, face.c_primary, hp)
        r["secondaryAccent"] = oklch(0.575, face.c_primary * 0.82, hs)
        r["tertiaryAccent"] = oklch(0.595, face.c_primary * 0.76, ht)
        r["warmAccent"] = oklch(0.620, face.c_primary * 0.70, hw)
        # ── glows: light cannot glow, so these are the pale luminous tints ──
        r["glowPrimary"] = oklch(0.780, face.c_primary * 0.85, hp)
        r["glowSecondary"] = oklch(0.810, face.c_primary * 0.72, hs)
        # ── guilloche: the plate's own three, pitched under the accents ──
        r["guillochePrimary"] = oklch(0.520, face.c_primary * 0.88, hs)
        r["guillocheSecondary"] = oklch(0.520, face.c_primary * 0.80, ht)
        r["guillocheHighlight"] = oklch(0.555, face.c_primary * 1.00, hp)
        # ── progress: a committed mid-tone over a near-invisible track ──
        r["progressTrack"] = oklch(0.900, face.c_primary * 0.33, hp)
        r["progress"] = fit_against_each_other(
            0.500, face.c_primary * 0.95, hp, r["progressTrack"], GRAPHIC + 0.3, darken=True
        )
        r["navigationSelected"] = oklch(0.520, face.c_primary * 1.00, hp)
    else:
        # ── planes: a coloured near-black, never #000 ──
        r["backgroundBase"] = oklch(face.l_page, cpg, hpg)
        r["backgroundSecondary"] = oklch(face.l_page + 0.040, cpg * 1.35, hpg)
        r["surface"] = oklch(face.l_page + 0.055, cpg * 1.15, hpg)
        r["surfaceElevated"] = oklch(face.l_page + 0.115, cpg * 1.00, hpg)
        # Fitted against `surfaceElevated`, the lightest plane the ink lands on.
        floor_plane = r["surfaceElevated"]
        r["textPrimary"] = fit_contrast(0.955, 0.012, hpg, floor_plane, 7.0, False)
        r["textSecondary"] = fit_contrast(0.740, 0.016, hpg, floor_plane, 4.5, False)
        r["textTertiary"] = fit_contrast(0.575, 0.018, hpg, floor_plane, 3.0, False)
        r["border"] = oklch(face.l_page + 0.185, cpg * 0.85, hpg)
        r["borderHighlight"] = oklch(face.l_page + 0.085, cpg * 0.70, hpg)
        # the dark face's accents are the luminous versions of the light face's
        r["primaryAccent"] = oklch(0.790, face.c_primary * 0.90, hp)
        r["secondaryAccent"] = oklch(0.800, face.c_primary * 0.78, hs)
        r["tertiaryAccent"] = oklch(0.815, face.c_primary * 0.72, ht)
        r["warmAccent"] = oklch(0.835, face.c_primary * 0.64, hw)
        r["glowPrimary"] = oklch(0.715, face.c_primary * 1.10, hp)
        r["glowSecondary"] = oklch(0.740, face.c_primary * 0.95, hs)
        r["guillochePrimary"] = oklch(0.700, face.c_primary * 1.00, hs)
        r["guillocheSecondary"] = oklch(0.700, face.c_primary * 0.92, ht)
        r["guillocheHighlight"] = oklch(0.745, face.c_primary * 1.10, hp)
        r["progressTrack"] = oklch(face.l_page + 0.105, face.c_primary * 0.40, hp)
        r["progress"] = fit_against_each_other(
            0.745, face.c_primary * 1.00, hp, r["progressTrack"], GRAPHIC + 0.3, darken=False
        )
        r["navigationSelected"] = oklch(0.790, face.c_primary * 1.00, hp)

    # ── the four §12.3 semantic accents: read as foreground text on `surface` ──
    # Each is one of the palette's own hues pushed only as far toward the page's ink as
    # AA requires, then held pairwise apart so the ring, the streak, the discovery tint
    # and the annotation mark stay tellable apart.
    surface = r["surface"]
    darken = L
    base_c = face.c_primary
    sem = [
        ("accentProgress", hp, base_c * 1.00),
        ("accentStreak", hw, base_c * 0.92),
        ("accentDiscovery", hs, base_c * 0.95),
        ("accentAnnotation", ht, base_c * 0.90),
    ]
    start_l = 0.520 if L else 0.800
    placed: List[Color] = []
    for name, h, c in sem:
        col = fit_contrast(start_l, c, h, surface, AA_TEXT, darken)
        # nudge lightness until it is >= 10 dE from the ones already placed
        tries = 0
        while placed and min(de(col, p) for p in placed) < 10.0 and tries < 24:
            tries += 1
            step = 0.018 * tries * (-1 if L else 1)
            cand = fit_contrast(
                max(0.12, min(0.92, start_l + step)), c, h, surface, AA_TEXT, darken
            )
            if not placed or min(de(cand, p) for p in placed) > min(
                de(col, p) for p in placed
            ):
                col = cand
        placed.append(col)
        r[name] = col
    return r


ROLE_ORDER = [
    "backgroundBase", "backgroundSecondary", "surface", "surfaceElevated",
    "primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent",
    "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
    "glowPrimary", "glowSecondary",
    "progress", "progressTrack",
    "textPrimary", "textSecondary", "textTertiary",
    "border", "borderHighlight",
    "navigationSelected",
    "accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation",
]


# ─────────────────────────────────────────────────────────────────────────────
# reader page presets
# ─────────────────────────────────────────────────────────────────────────────


def reader_preset(face: Face, r: Roles) -> Dict[str, object]:
    """The reader page is the same world as the chrome, one step calmer.

    The page is a *reading* surface, so its ink runs to 7:1 rather than 4.5:1 and its
    accents are pulled back: a page that competes with the chrome is a page you cannot
    read on.
    """
    L = face.light
    hpg = face.h_page
    hp, hs = face.h_primary, face.h_secondary

    if L:
        background = oklch(face.l_page + 0.012, face.c_page * 0.80, hpg)
        surface = oklch(face.l_page - 0.030, face.c_page * 1.30, hpg)
        primary_text = oklch(0.225, 0.028, hpg)
        secondary_text = oklch(0.455, 0.026, hpg)
        heading_text = oklch(0.185, 0.038, hpg)
        link = fit_contrast(0.500, face.c_primary * 0.90, hp, background, AA_TEXT, True)
        selection = oklch(0.845, face.c_primary * 0.45, hp)
        bookmark = fit_contrast(0.560, face.c_primary * 0.95, hs, background, AA_LARGE, True)
        progress = fit_contrast(0.520, face.c_primary * 0.95, hp, background, AA_LARGE, True)
        divider = oklch(0.865, face.c_page * 1.6, hpg)
        hl_l, hl_c = 0.845, 0.085
    else:
        background = oklch(max(0.075, face.l_page - 0.020), face.c_page * 0.90, hpg)
        surface = oklch(face.l_page + 0.040, face.c_page * 1.10, hpg)
        primary_text = oklch(0.925, 0.014, hpg)
        secondary_text = oklch(0.715, 0.018, hpg)
        heading_text = oklch(0.970, 0.010, hpg)
        link = fit_contrast(0.790, face.c_primary * 0.90, hp, background, AA_TEXT, False)
        selection = oklch(0.370, face.c_primary * 0.55, hp)
        bookmark = fit_contrast(0.790, face.c_primary * 0.95, hs, background, AA_LARGE, False)
        progress = fit_contrast(0.775, face.c_primary * 0.95, hp, background, AA_LARGE, False)
        divider = oklch(face.l_page + 0.130, face.c_page * 1.0, hpg)
        hl_l, hl_c = 0.420, 0.095

    # Eight highlight pigments, anchored on the theme's own primary so a highlight
    # belongs to its theme, then spread evenly so eight marks stay eight marks.
    highlights = [oklch(hl_l, hl_c, (hp + i * 45.0) % 360.0) for i in range(8)]

    return {
        "id": face.reader_id,
        "name": face.name,
        "background": background,
        "surface": surface,
        "primaryText": primary_text,
        "secondaryText": secondary_text,
        "headingText": heading_text,
        "link": link,
        "selection": selection,
        "bookmark": bookmark,
        "highlightColors": highlights,
        "progress": progress,
        "divider": divider,
        "isDark": not L,
    }


# ─────────────────────────────────────────────────────────────────────────────
# verification
# ─────────────────────────────────────────────────────────────────────────────


def verify(theme: Theme, face: Face, r: Roles, rp: Dict[str, object]) -> Tuple[List[str], List[str]]:
    fails: List[str] = []
    warns: List[str] = []
    tag = f"{theme.pack_id}/{face.name}"

    planes = ["backgroundBase", "backgroundSecondary", "surface", "surfaceElevated"]
    tiers = [("textPrimary", 7.0), ("textSecondary", 4.5), ("textTertiary", 3.0)]
    for tier, floor in tiers:
        for plane in planes:
            ratio = contrast(r[tier], r[plane])
            if ratio < floor:
                fails.append(f"{tag}: {tier} on {plane} = {ratio:.2f} < {floor}")

    ratio = contrast(r["progress"], r["progressTrack"])
    if ratio < GRAPHIC:
        fails.append(f"{tag}: progress vs progressTrack = {ratio:.2f} < {GRAPHIC}")

    for role in ("accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation"):
        ratio = contrast(r[role], r["surface"])
        if ratio < AA_TEXT:
            fails.append(f"{tag}: {role} on surface = {ratio:.2f} < {AA_TEXT}")

    sem = ["accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation"]
    for i in range(len(sem)):
        for j in range(i + 1, len(sem)):
            d = de(r[sem[i]], r[sem[j]])
            if d < 10.0:
                fails.append(f"{tag}: {sem[i]} vs {sem[j]} dE2000 = {d:.1f} < 10")

    accents = ["primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent"]
    for i in range(len(accents)):
        for j in range(i + 1, len(accents)):
            d = de(r[accents[i]], r[accents[j]])
            if d < 10.0:
                warns.append(f"{tag}: {accents[i]} vs {accents[j]} dE2000 = {d:.1f} < 10")

    # the page must carry its theme's chroma, or the theme is a grey with a coloured button
    page_c = r["backgroundBase"].convert("oklch")["chroma"]
    if page_c < 0.004:
        warns.append(f"{tag}: backgroundBase chroma {page_c:.4f} is effectively achromatic")

    # reader page floors
    for tier, floor in (("primaryText", 7.0), ("secondaryText", 4.5), ("headingText", 7.0)):
        ratio = contrast(rp[tier], rp["background"])
        if ratio < floor:
            fails.append(f"{tag} reader: {tier} on background = {ratio:.2f} < {floor}")
    ratio = contrast(rp["link"], rp["background"])
    if ratio < AA_TEXT:
        fails.append(f"{tag} reader: link on background = {ratio:.2f} < {AA_TEXT}")
    hls = rp["highlightColors"]
    for i in range(len(hls)):
        for j in range(i + 1, len(hls)):
            d = de(hls[i], hls[j])
            if d < 8.0:
                warns.append(f"{tag} reader: highlight {i} vs {j} dE2000 = {d:.1f} < 8")
    # highlighted text must still read
    for i, h in enumerate(hls):
        ratio = contrast(rp["primaryText"], h)
        if ratio < AA_LARGE:
            fails.append(f"{tag} reader: primaryText on highlight {i} = {ratio:.2f} < {AA_LARGE}")

    return fails, warns


def cross_theme(all_roles: Dict[str, Dict[str, Roles]]) -> Tuple[List[str], List[str]]:
    """The ten themes must be tellable apart, which is the whole complaint."""
    fails: List[str] = []
    warns: List[str] = []
    for polarity in ("light", "dark"):
        keys = list(all_roles.keys())
        for i in range(len(keys)):
            for j in range(i + 1, len(keys)):
                a = all_roles[keys[i]][polarity]["primaryAccent"]
                b = all_roles[keys[j]][polarity]["primaryAccent"]
                d = de(a, b)
                if d < 10.0:
                    fails.append(
                        f"cross-theme {polarity}: {keys[i]} vs {keys[j]} primaryAccent dE2000 = {d:.1f} < 10"
                    )
                elif d < 14.0:
                    warns.append(
                        f"cross-theme {polarity}: {keys[i]} vs {keys[j]} primaryAccent dE2000 = {d:.1f} (tight)"
                    )
    return fails, warns


# ─────────────────────────────────────────────────────────────────────────────
# Kotlin emitters
# ─────────────────────────────────────────────────────────────────────────────


def emit_art_directions(data: List[Tuple[Theme, Face, Roles]]) -> str:
    out: List[str] = []
    out.append("internal val FOLIO_ART_DIRECTIONS: List<FolioArtDirection> = listOf(")
    for theme, face, r in data:
        out.append(f"    // {theme.pack_id.upper()} — \"{face.name}\" — {face.note}")
        out.append("    FolioArtDirection(")
        out.append(f'        id = "{theme.pack_id}", light = {"true" if face.light else "false"},')
        groups = [
            ("backgroundBase", "backgroundSecondary", "surface", "surfaceElevated"),
            ("primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent"),
            ("guillochePrimary", "guillocheSecondary", "guillocheHighlight"),
            ("glowPrimary", "glowSecondary"),
            ("progress", "progressTrack"),
            ("textPrimary", "textSecondary", "textTertiary"),
            ("border", "borderHighlight"),
            ("navigationSelected",),
            ("accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation"),
        ]
        for g in groups:
            line = "        " + ", ".join(f"{k} = {argb(r[k])}" for k in g) + ","
            out.append(line)
        out.append("    ),")
    out.append(")")
    return "\n".join(out)


def emit_reader(presets: List[Dict[str, object]]) -> str:
    out: List[str] = []
    for p in presets:
        out.append(f'    "{p["id"]}" to Theme(')
        out.append(f'        id = "{p["id"]}", name = "{p["name"]}",')
        out.append(
            f'        background = {argb(p["background"])}.toInt(), surface = {argb(p["surface"])}.toInt(),'
        )
        out.append(
            f'        primaryText = {argb(p["primaryText"])}.toInt(), secondaryText = {argb(p["secondaryText"])}.toInt(),'
        )
        out.append(
            f'        headingText = {argb(p["headingText"])}.toInt(), link = {argb(p["link"])}.toInt(),'
        )
        out.append(
            f'        selection = {argb(p["selection"])}.toInt(), bookmark = {argb(p["bookmark"])}.toInt(),'
        )
        out.append("        highlightColors = listOf(")
        hls = p["highlightColors"]
        out.append(
            "            " + ", ".join(f"{argb(h)}.toInt()" for h in hls[:4]) + ","
        )
        out.append(
            "            " + ", ".join(f"{argb(h)}.toInt()" for h in hls[4:]) + ","
        )
        out.append("        ),")
        out.append(
            f'        progress = {argb(p["progress"])}.toInt(), divider = {argb(p["divider"])}.toInt(),'
        )
        out.append(f'        isDark = {"true" if p["isDark"] else "false"}')
        out.append("    ),")
    return "\n".join(out)


def emit_chart_series(data: List[Tuple[Theme, Face, Roles]]) -> str:
    """Eight chart series, one per theme's primary, spread so a stacked chart reads."""
    hues = [18, 80, 145, 192, 250, 272, 310, 350]
    cols = [oklch(0.620, 0.135, h) for h in hues]
    return ", ".join(f"Color({argb(c)})" for c in cols)


# ─────────────────────────────────────────────────────────────────────────────
# main
# ─────────────────────────────────────────────────────────────────────────────


def main() -> None:
    data: List[Tuple[Theme, Face, Roles]] = []
    presets: List[Dict[str, object]] = []
    by_pack: Dict[str, Dict[str, Roles]] = {}
    all_fails: List[str] = []
    all_warns: List[str] = []

    for theme in THEMES:
        by_pack[theme.pack_id] = {}
        for polarity, face in (("light", theme.light), ("dark", theme.dark)):
            r = derive(face)
            rp = reader_preset(face, r)
            f, w = verify(theme, face, r, rp)
            all_fails += f
            all_warns += w
            data.append((theme, face, r))
            presets.append(rp)
            by_pack[theme.pack_id][polarity] = r

    f, w = cross_theme(by_pack)
    all_fails += f
    all_warns += w

    # ── report ──
    lines: List[str] = []
    lines.append("# Folio theme rebuild — coloraide audit\n")
    lines.append(f"coloraide verification of 10 themes x 2 faces = 20 faces, 25 roles each.\n")
    lines.append("## Verdict\n")
    lines.append(f"- **failures: {len(all_fails)}**")
    lines.append(f"- warnings: {len(all_warns)}\n")
    if all_fails:
        lines.append("### Failures\n")
        for x in all_fails:
            lines.append(f"- {x}")
        lines.append("")
    if all_warns:
        lines.append("### Warnings\n")
        for x in all_warns:
            lines.append(f"- {x}")
        lines.append("")

    lines.append("## Theme identities and shader assignment\n")
    lines.append("| theme | light / dark | hue territory (OKLCH h) | Mirage shader |")
    lines.append("|---|---|---|---|")
    for theme in THEMES:
        lines.append(
            f"| `{theme.pack_id}` | {theme.light.name} / {theme.dark.name} "
            f"| {theme.light.h_primary:.0f}° / {theme.dark.h_primary:.0f}° | `{theme.shader}` |"
        )
    lines.append("")
    for theme in THEMES:
        lines.append(f"### {theme.light.name} / {theme.dark.name} (`{theme.pack_id}`)\n")
        lines.append(f"{theme.concept}\n")
        lines.append(f"**Shader — `{theme.shader}`.** {theme.shader_why}\n")

    lines.append("## Contrast detail\n")
    lines.append("| face | ink on page | ink2 on page | ink3 on page | progress:track | page chroma |")
    lines.append("|---|---|---|---|---|---|")
    for theme, face, r in data:
        lines.append(
            f"| {face.name} "
            f"| {contrast(r['textPrimary'], r['backgroundBase']):.2f} "
            f"| {contrast(r['textSecondary'], r['backgroundBase']):.2f} "
            f"| {contrast(r['textTertiary'], r['backgroundBase']):.2f} "
            f"| {contrast(r['progress'], r['progressTrack']):.2f} "
            f"| {r['backgroundBase'].convert('oklch')['chroma']:.4f} |"
        )
    lines.append("")

    lines.append("## Cross-theme primary separation (dE2000)\n")
    for polarity in ("light", "dark"):
        lines.append(f"### {polarity} faces\n")
        keys = list(by_pack.keys())
        lines.append("| | " + " | ".join(keys) + " |")
        lines.append("|---|" + "---|" * len(keys))
        for a in keys:
            row = [a]
            for b in keys:
                if a == b:
                    row.append("—")
                else:
                    row.append(
                        f"{de(by_pack[a][polarity]['primaryAccent'], by_pack[b][polarity]['primaryAccent']):.0f}"
                    )
            lines.append("| " + " | ".join(row) + " |")
        lines.append("")

    lines.append("## Palettes\n")
    for theme, face, r in data:
        lines.append(f"### {face.name} (`{theme.pack_id}`, {'light' if face.light else 'dark'})\n")
        lines.append("| role | hex |")
        lines.append("|---|---|")
        for k in ROLE_ORDER:
            lines.append(f"| {k} | `{hx(r[k])}` |")
        lines.append("")

    with open(os.path.join(HERE, "folio_theme_audit.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))

    with open(os.path.join(HERE, "generated_art_directions.kt"), "w", encoding="utf-8") as fh:
        fh.write(emit_art_directions(data))

    with open(os.path.join(HERE, "generated_reader_themes.kt"), "w", encoding="utf-8") as fh:
        fh.write(emit_reader(presets))

    with open(os.path.join(HERE, "generated_chart_series.kt"), "w", encoding="utf-8") as fh:
        fh.write(emit_chart_series(data))

    summary = {
        "faces": len(data),
        "failures": all_fails,
        "warnings": all_warns,
        "shaders": {t.pack_id: t.shader for t in THEMES},
    }
    with open(os.path.join(HERE, "folio_theme_audit.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    print(f"faces={len(data)} failures={len(all_fails)} warnings={len(all_warns)}")
    for x in all_fails[:40]:
        print("FAIL", x)
    for x in all_warns[:20]:
        print("WARN", x)


if __name__ == "__main__":
    main()
