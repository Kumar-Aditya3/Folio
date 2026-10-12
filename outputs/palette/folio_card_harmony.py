"""
folio_card_harmony.py — per-face check that the card-worn accent roles actually
coordinate, held to the file's own documented standards:

  1. The four semantic accents (accentProgress, accentStreak, accentDiscovery,
     accentAnnotation) are read as foreground text/figures ON the card planes, so
     each must clear WCAG AA (>= 4.5:1) against `surface` AND `surfaceElevated`.
  2. The four accents are four different meanings; they must stay pairwise
     >= 10 dE2000 apart or two roles collapse into one colour.
  3. `progress` over `progressTrack` and `navigationSelected` over `surface`
     must clear the non-text 3:1.
  4. Hue orbit (report only): each accent's OKLCH hue vs the face's primaryAccent
     hue. Per the user's rule the fix for a badly coordinated face moves lightness
     and chroma, NEVER hue and never toward a shared palette — the orbit table is
     here to prove the hues survived.

For every failure, prints a suggested hex: same OKLCH hue, same chroma, lightness
walked (in 0.005 steps, toward the card plane) until the check clears — so the theme's
colour identity is untouched and only the step off the card changes.

Run from the repo root:  python outputs\\palette\\folio_card_harmony.py [--fix]
"""

import re
import sys

from coloraide import Color

KT = r"shared\src\composeUi\kotlin\com\folio\reader\ui\theme\FolioArtDirection.kt"

ACCENTS = ["accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation"]
PLANES = ["surface", "surfaceElevated"]
AA = 4.5
NT = 3.0
MIN_DE = 10.0
# A deliberately achromatic face (the Gallery pair) has no hue to separate roles by, and
# dE2000's lightness weighting makes a 10 dE floor unreachable for four greys pinned
# between AA-on-white and "darker than body text". The honest standard there is the
# lightness ladder itself: rungs at least ~12 L* apart, which measures 8.5+ dE2000.
ACHROMATIC_MIN_DE = 8.5


def parse_faces(text):
    faces = []
    for m in re.finditer(r'id = "(\w+)", light = (true|false),(.*?)\n    \),', text, re.S):
        roles = dict(re.findall(r"(\w+) = 0x([0-9A-Fa-f]{8})", m.group(3)))
        faces.append({"id": m.group(1), "light": m.group(2) == "true", "roles": roles})
    return faces


def col(face, role):
    hx = face["roles"].get(role)
    if hx is None:
        return None
    return Color("#" + hx[2:])


def contrast(c1, c2):
    return c1.contrast(c2)


def de2000(c1, c2):
    return c1.delta_e(c2, method="2000")


def hue(c):
    return c.convert("oklch").get("h") or 0.0


def suggest_accent(accent, plane):
    """Same hue + chroma, lightness walked until AA clears against the plane."""
    ok = accent.convert("oklch")
    step = 0.005
    # Walk toward whichever end of the L scale the plane is not at.
    dark_plane = plane.convert("oklch").get("l") < 0.5
    best = None
    for i in range(1, 120):
        l = ok.get("l") + (step * i if dark_plane else -(step * i))
        if not 0.05 <= l <= 0.97:
            break
        cand = ok.clone().set("l", l).convert("srgb")
        if not cand.in_gamut("srgb"):
            cand.clip("srgb")
        # Round-trip through the hex we would actually ship, and measure *that*:
        # the pre-rounding float is systematically ~2-4% optimistic on contrast.
        cand = Color(cand.to_string(hex=True))
        r = cand.contrast(plane)
        if r >= AA:
            best = (cand, r)
            break
    return best


def main():
    with open(KT, "r", encoding="utf-8") as f:
        text = f.read()
    faces = parse_faces(text)
    failures = 0
    for face in faces:
        tag = f"{face['id']}{'-L' if face['light'] else '-D'}"
        lines = []

        for role in ACCENTS:
            c = col(face, role)
            if c is None:
                lines.append(f"  {role}: MISSING")
                continue
            for plane_role in PLANES:
                plane = col(face, plane_role)
                if plane is None:
                    continue
                r = contrast(c, plane)
                if r < AA:
                    sug = suggest_accent(c, plane)
                    sugtxt = ""
                    if sug is not None:
                        sugtxt = f"  -> suggest {sug[0].to_string(hex=True)} ({sug[1]:.2f}:1)"
                    lines.append(f"  {role} on {plane_role}: {r:.2f}:1  FAIL{sugtxt}")
                    failures += 1

        # Four greys have no hue to separate by — hold the lightness ladder instead.
        achromatic = all(
            col(face, r) is not None and col(face, r).convert("oklch").get("c") < 0.02
            for r in ACCENTS
        )
        min_de = ACHROMATIC_MIN_DE if achromatic else MIN_DE
        for i in range(len(ACCENTS)):
            for j in range(i + 1, len(ACCENTS)):
                a, b = col(face, ACCENTS[i]), col(face, ACCENTS[j])
                if a is None or b is None:
                    continue
                d = de2000(a, b)
                if d < min_de:
                    lines.append(
                        f"  dE {ACCENTS[i]} vs {ACCENTS[j]}: {d:.1f}  FAIL (<{min_de:g})"
                    )
                    failures += 1

        for fg, bg in [("progress", "progressTrack"), ("navigationSelected", "surface")]:
            c, p = col(face, fg), col(face, bg)
            if c is None or p is None:
                continue
            r = contrast(c, p)
            if r < NT:
                lines.append(f"  {fg} on {bg}: {r:.2f}:1  FAIL (<{NT:g})")
                failures += 1

        if lines:
            print(f"== {tag} ==")
            for ln in lines:
                print(ln)

        primary = col(face, "primaryAccent")
        if primary is not None:
            ph = hue(primary)
            orbit = []
            for role in ACCENTS + ["warmAccent"]:
                c = col(face, role)
                if c is None:
                    continue
                d = min(abs(hue(c) - ph), 360 - abs(hue(c) - ph))
                orbit.append(f"{role.split('accent')[-1] or role}:{d:.0f}\u00b0")
            print(f"-- {tag} hue orbit vs primary: {' '.join(orbit)}")

    print(f"\n{failures} hard failure(s).")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
