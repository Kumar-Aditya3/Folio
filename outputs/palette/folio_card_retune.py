"""
folio_card_retune.py â€” the geometry-aware card accent mechanism.

The harmony audit (folio_card_harmony.py) judges authored hexes against authored
swatches. The eye never sees those: it sees the accent drawn on a plane that was
derived through the atmosphere pipeline and composited over a lit, animated field,
behind liquid glass. This script ports the pipeline's actual colour math and
derives/verifies accents against the EFFECTIVE rendered backdrop:

  authored roles
    -> liftPlane (Â§20: dark faces lift card/panel off the room; FolioAtmosphere.kt)
    -> paneFill = card plane at paneAlpha (0.60 dark / 0.76 light)
    -> composited over the field: mid-ramp + pools (glows + warm at POOL alpha)
       + a stress tint (the palette's own warm at ambient strength)
    -> accent drawn opaque on top (the Â§17 legibleOn guard is modeled as a no-op
       when the authored accent already clears, which this mechanism ensures)

The derivation mechanism is Material-You-shaped, in OKLCH, per face:

  accentProgress    the theme's anchor â€” authored HUE and CHROMA untouched; only the
                    tone moves, until the accent clears AA on the worst-case effective
                    plane. The theme's voice is not adjustable here.
  accentStreak      the authored warm hue and chroma, tone-adjusted for effective AA.
                    The pop role keeps the theme's own warm voice â€” that is the
                    "not bland" insurance.
  accentDiscovery   hue = anchor + 30deg, chroma = 0.70x anchor's â€” analogous, the
                    same light one window over. Widened per-face if separation needs it.
  accentAnnotation  hue = anchor - 30deg, chroma = 0.58x anchor's â€” the other side.

Same structure on every theme; every theme's absolute identity (anchor hue, chroma
scale, warm voice) survives â€” coordinated, not uniform. Achromatic faces (Gallery)
are skipped: their mechanism is the lightness ladder, already fixed in the audit.

Usage:
  python outputs\\palette\\folio_card_retune.py            # report only
  python outputs\\palette\\folio_card_retune.py --apply    # rewrite FolioArtDirection.kt
"""

import re
import sys

from coloraide import Color

KT = r"shared\src\composeUi\kotlin\com\folio\reader\ui\theme\FolioArtDirection.kt"

ACCENTS = ["accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation"]

# â”€â”€ ports of the pipeline's constants (file:line kept for drift checks) â”€â”€
DARK_PLANE_LIFT_CARD = 0.05        # FolioAtmosphere.kt Â§20
DARK_PLANE_LIFT_PANEL = 0.075      # FolioAtmosphere.kt Â§20
PANE_ALPHA_DARK = 0.72             # FolioAtmosphere.kt:1637 — raised 0.60 -> 0.72 so a
# dark card is more its own plane than a tint on the bloom-lit room behind it
# (folio_plane_retune measured every dark face's rendered card BELOW its room)
PANE_ALPHA_LIGHT = 0.76            # FolioAtmosphere.kt:1637
VEIL_ALPHA = 0.62                   # glass tier at rest, FolioSurfaceOpacity
FIELD_FOOT_ACCENT_DARK = 0.055     # FolioAtmosphere.kt:1114
FIELD_FOOT_ACCENT_LIGHT = 0.05     # FolioAtmosphere.kt:1115
BLOOM_MIX = 0.62                   # FolioAtmosphere.kt:1299
POOL_ALPHA = 0.09                  # FolioAtmosphere.kt pool pour
POOL_HARMONY_PULL = 0.18           # FolioAtmosphere.kt:858
TINT_STRESS = 0.10                 # stress: room lit by a warm cover, ambient on
AA = 4.5
MIN_DE = 10.0
ACHROMATIC_MIN_DE = 8.5
ACHROMATIC_MIN_DE = 8.5


def parse_faces(text):
    faces = []
    for m in re.finditer(r'id = "(\w+)", light = (true|false),(.*?)\n    \),', text, re.S):
        roles = dict(re.findall(r"(\w+) = 0x([0-9A-Fa-f]{8})", m.group(3)))
        faces.append({"id": m.group(1), "light": m.group(2) == "true", "roles": roles})
    return faces


def col(face, role):
    hx = face["roles"].get(role)
    return None if hx is None else Color("#" + hx[2:])


# â”€â”€ the pipeline's arithmetic, ported â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

def _rgb(c):
    return (c.get("red"), c.get("green"), c.get("blue"))


def _srgb_color(rgb):
    return Color("srgb", [min(max(v, 0.0), 1.0) for v in rgb])


def mixg(a, b, t):
    """FolioAtmosphere.kt:192 — sRGB channel mix."""
    ar, ag, ab = _rgb(a)
    br, bg, bb = _rgb(b)
    return _srgb_color([
        ar + (br - ar) * t,
        ag + (bg - ag) * t,
        ab + (bb - ab) * t,
    ])


def liftg(c, amount):
    return mixg(c, Color("#ffffff"), amount)


def lift_plane(c, dark, amount):
    return c if not dark else liftg(c, amount)


def composite_over(fg, alpha, bg):
    """src-over in sRGB channels, the way a translucent pane paints the field."""
    fr, fgr, fb = _rgb(fg)
    br, bg_, bb = _rgb(bg)
    return _srgb_color([
        fr * alpha + br * (1 - alpha),
        fgr * alpha + bg_ * (1 - alpha),
        fb * alpha + bb * (1 - alpha),
    ])


def harmonize_pool_hues(pools, pull):
    """FolioAtmosphere harmonizePoolHues: rotate each partway to the circular mean hue."""
    oks = [p.convert("oklch") for p in pools]
    hues = [o.get("h") or 0.0 for o in oks]
    import math
    x = sum(math.sin(math.radians(h)) for h in hues)
    y = sum(math.cos(math.radians(h)) for h in hues)
    mean = math.degrees(math.atan2(x, y)) % 360
    out = []
    for o, h in zip(oks, hues):
        dh = ((mean - h + 180) % 360) - 180
        out.append(o.clone().set("h", (h + dh * pull) % 360).convert("srgb"))
    return out


def effective_backdrop(face):
    """The room a card's accent actually reads against: the field's mid ramp with
    the pools poured over it and a worst-case stress tint, composited under each
    plane class at its resting alpha."""
    dark = not face["light"]
    roles = face["roles"]

    def c(role):
        return Color("#" + roles[role][2:])

    bg, bg2 = c("backgroundBase"), c("backgroundSecondary")
    # fieldTop / fieldBottom â€” FolioAtmosphere.kt:1290-1308.
    if dark:
        bloom = mixg(bg, bg2, BLOOM_MIX)
        foot = c("secondaryAccent")  # chromaticRole: the more chromatic of the two
        if (c("primaryAccent").convert("oklch").get("c") or 0) > (foot.convert("oklch").get("c") or 0):
            foot = c("primaryAccent")
        field_bottom = mixg(bloom, foot, FIELD_FOOT_ACCENT_DARK)
        field_top = bg
    else:
        foot = c("secondaryAccent")
        if (c("primaryAccent").convert("oklch").get("c") or 0) > (foot.convert("oklch").get("c") or 0):
            foot = c("primaryAccent")
        field_top = bg
        field_bottom = mixg(mixg(bg2, Color("#000000"), 0.07), foot, FIELD_FOOT_ACCENT_LIGHT)
    field_mid = mixg(field_top, field_bottom, 0.5)

    # Pools: the authored light sources, harmonised, poured thin.
    pools = harmonize_pool_hues(
        [c("glowPrimary"), c("glowSecondary"), c("warmAccent")], POOL_HARMONY_PULL
    )
    # Worst case: two pools sweeping past plus a warm ambient stress tint.
    worst = composite_over(pools[0], POOL_ALPHA, field_mid)
    worst = composite_over(pools[1], POOL_ALPHA, worst)
    worst = composite_over(c("warmAccent"), TINT_STRESS, worst)
    # On a light face the field is pale; the worst case for dark accents is the
    # *darkest* reach â€” approximate by mixing a little of the field bottom back in.
    if not dark:
        worst = mixg(worst, field_bottom, 0.35)

    card = lift_plane(c("surface"), dark, DARK_PLANE_LIFT_CARD)
    panel = lift_plane(c("surfaceElevated"), dark, DARK_PLANE_LIFT_PANEL)
    veil_src = lift_plane(c("surfaceElevated"), dark, DARK_PLANE_LIFT_PANEL)
    pane_alpha = PANE_ALPHA_DARK if dark else PANE_ALPHA_LIGHT
    return {
        "raised": composite_over(card, pane_alpha, worst),
        "panel": composite_over(panel, 1.0, worst),
        "veil": composite_over(veil_src, VEIL_ALPHA, worst),
    }


def oklch(c):
    return c.convert("oklch")


def tone_for_aa(hue, chroma, planes, dark, floor=AA):
    """Find the tone whose sRGB colour clears `floor` on every effective plane.
    Dark faces walk up (light accents), light faces walk down. The per-role floor
    is the contrast LADDER: solving every role to the same floor put all four
    accents on one lightness (muted faces collapsed under the pairwise dE check),
    so each role targets its own step — progress the strongest, discovery the
    lightest that still clears AA."""
    start = 0.80 if dark else 0.45
    step = 0.005
    for i in range(200):
        l = start + step * i if dark else start - step * i
        if not 0.05 <= l <= 0.97:
            break
        cand = oklch(Color("#ffffff")).clone()
        cand.set("h", hue % 360).set("c", max(chroma, 0.0)).set("l", l)
        srgb = cand.convert("srgb")
        if not srgb.in_gamut("srgb"):
            srgb.clip("srgb")
        srgb = Color(srgb.to_string(hex=True))
        if all(srgb.contrast(p) >= floor for p in planes.values()):
            return srgb
    return None


# The per-role effective-contrast ladder: the primary reading the strongest,
# the celebratory streak just under it, annotation and discovery lighter —
# every rung still clears AA. Solving to a ladder instead of one register is
# what keeps four accents separated on muted faces.
ROLE_FLOORS = {
    "accentProgress": 5.6,
    "accentStreak": 5.1,
    "accentAnnotation": 4.8,
    "accentDiscovery": 4.6,
}


def derive_face(face):
    """Tone discipline only. The hue relationships are AUTHORED — deliberately wide,
    per REBUILD_NOTES rule 3 ("different colors per theme, not versions of one
    color") — so this mechanism never touches hue or chroma. What it disciplines is
    the one axis the authored sheet could not see: each accent's TONE against the
    *effective rendered plane* (the §20-lifted plane composited over the lit field,
    behind the glass). Every role is solved to the same effective-AA register on
    that geometry, so the four accents read as one coordinated set ON THE CARD while
    staying exactly as colourful as the theme authored them."""
    dark = not face["light"]
    planes = effective_backdrop(face)
    out = {}
    notes = []
    for role in ACCENTS:
        authored = col(face, role)
        if authored is None:
            notes.append(f"{role}: missing, skipped")
            continue
        ok = oklch(authored)
        cand = tone_for_aa(
            ok.get("h") or 0.0, ok.get("c") or 0.05, planes, dark, ROLE_FLOORS.get(role, AA)
        )
        if cand is None:
            out[role] = authored
            notes.append(f"{role}: no tone clears effective AA, kept authored")
        else:
            out[role] = cand

    # ── pairwise repair ──
    # The ladder gives separation a chance but cannot guarantee it: two roles that
    # share a hue family at low chroma (silver's steel blues) converge on the same
    # lightness rung. Walk the worst colliding pair apart — the lower-floor member
    # toward the plane (never below AA), the higher-floor member away from it —
    # until the floor clears. Deterministic on the solved set, so re-runs converge
    # instead of churning.
    import itertools
    for _ in range(80):
        worst = None
        solved = [r for r in ACCENTS if r in out]
        achro = all((oklch(out[r]).get("c") or 0) < 0.02 for r in solved)
        floor = ACHROMATIC_MIN_DE if achro else MIN_DE
        for a, b in itertools.combinations(solved, 2):
            d = out[a].delta_e(out[b], method="2000")
            if d < floor and (worst is None or d < worst[0]):
                worst = (d, a, b)
        if worst is None:
            break
        _, a, b = worst
        lower, higher = (a, b) if ROLE_FLOORS[a] <= ROLE_FLOORS[b] else (b, a)
        moved = False
        for role, away in ((lower, -1), (higher, 1)):
            c_ok = oklch(out[role])
            direction = 1 if dark else -1  # away from the plane raises contrast
            step = direction * away * 0.01
            nl = min(max(c_ok.get("l") + step, 0.05), 0.97)
            cand = c_ok.clone().set("l", nl).convert("srgb")
            if not cand.in_gamut("srgb"):
                cand.clip("srgb")
            cand = Color(cand.to_string(hex=True))
            if all(cand.contrast(p) >= AA for p in planes.values()):
                out[role] = cand
                moved = True
                break
        if not moved:
            notes.append(f"pairwise {a}/{b} below floor and immovable, left solved")
            break
    return out, notes, planes


def hexof(c):
    return "0xFF" + c.to_string(hex=True)[1:].upper()


def main():
    apply = "--apply" in sys.argv
    with open(KT, "r", encoding="utf-8") as f:
        text = f.read()
    faces = parse_faces(text)
    changed = 0
    for face in faces:
        tag = f"{face['id']}{'-L' if face['light'] else '-D'}"
        # Achromatic faces: the ladder is the mechanism; leave authored.
        if all(
            (col(face, r) is None) or (oklch(col(face, r)).get("c") or 0) < 0.02
            for r in ACCENTS
        ):
            print(f"{tag}: achromatic, ladder mechanism, skipped")
            continue
        derived, notes, planes = derive_face(face)
        lines = [f"{tag}:"]
        for role in ACCENTS:
            new = derived.get(role)
            old = col(face, role)
            if new is None or old is None:
                continue
            nh, oh = hexof(new), "0xFF" + face["roles"][role][2:].upper()
            eff = min(new.contrast(p) for p in planes.values())
            mark = " (unchanged)" if new.to_string(hex=True) == old.to_string(hex=True) else ""
            lines.append(f"  {role}: {oh} -> {nh}  min effective AA {eff:.2f}:1{mark}")
        for n in notes:
            lines.append(f"  note: {n}")
        print("\n".join(lines))
        if apply:
            for role in ACCENTS:
                new = derived.get(role)
                if new is None:
                    continue
                nh = hexof(new)
                # The captured hex is the full 8 chars including the alpha FF —
                # match it whole or the pattern never finds the line (this exact
                # bug shipped two "successful" applies that wrote nothing).
                old = face["roles"][role].upper()
                if nh == "0xFF" + old:
                    continue
                face_start = text.index(f'id = "{face["id"]}", light = {str(face["light"]).lower()},')
                face_end = text.index("\n    ),", face_start)
                block = text[face_start:face_end]
                new_block, n = re.subn(
                    rf"{role} = 0x{old}\b", f"{role} = {nh}", block, count=1
                )
                if n == 1:
                    text = text[:face_start] + new_block + text[face_end:]
                    changed += 1
    if apply:
        with open(KT, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"\n--apply: wrote {changed} role hex change(s).")
    else:
        print(f"\nreport only ({changed} would change). re-run with --apply to write.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
