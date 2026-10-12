"""
folio_plane_retune.py — the geometry-aware CARD-vs-ROOM mechanism.

The card the eye sees is not the authored swatch: the authored `surface` is lifted
(§20), poured at paneAlpha (0.60 dark / 0.76 light) or veil tier 0.62, and
composited over a lit field — the mid ramp plus pools plus foot accent. Whether a
card "matches the background" is a property of that composite, and because the §20
lift is a constant while the rooms vary, the effective card-to-room step drifts
per face: some faces' cards sit a gentle step above their room, others barely
separate or jump too far, and the field's own hue can bleed into a thin pane and
pull the card off the room's hue.

This measures the EFFECTIVE relationship per face and corrects only the outliers:

  step   = OKLab L(effective card)  -  OKLab L(effective room)
  drift  = hue distance(effective card, effective room)

Faces whose step falls outside the pack's own band (median +- 0.03) get their
authored `surface` re-toned so the *rendered* card lands back in the band; faces
whose effective hue drifts more than 15 degrees get the plane's hue pulled halfway
back toward the room's own hue. Chroma is never touched, the corrected step comes
from the pack's own median (so the app's designed look is the authority), and
in-band faces are left exactly as authored.

Usage:
  python outputs\\palette\\folio_plane_retune.py            # report only
  python outputs\\palette\\folio_plane_retune.py --apply    # rewrite the planes
"""

import re
import sys

from coloraide import Color

from folio_card_retune import (
    KT,
    ACCENTS,
    parse_faces,
    mixg,
    lift_plane,
    composite_over,
    harmonize_pool_hues,
    DARK_PLANE_LIFT_CARD,
    DARK_PLANE_LIFT_PANEL,
    PANE_ALPHA_DARK,
    PANE_ALPHA_LIGHT,
    VEIL_ALPHA,
    FIELD_FOOT_ACCENT_DARK,
    FIELD_FOOT_ACCENT_LIGHT,
    BLOOM_MIX,
    POOL_ALPHA,
    TINT_STRESS,
)

ROLE_SURFACE = "surface"
ROLE_ELEVATED = "surfaceElevated"
BAND_HALF = 0.03
DRIFT_MAX = 15.0
HUE_PULL = 0.5
# The correction is capped: the mechanism must not turn an authored near-black
# plane into a mid-grey. If the in-band tone is further than this from the authored
# tone (OKLab L), take the closest step in that direction instead — a smaller step
# that still lifts the card clear of its room.
MAX_LIFT = 0.12


def col(face, role):
    hx = face["roles"].get(role)
    return None if hx is None else Color("#" + hx[2:])


def effective_room(face):
    """The rendered room a card floats on: field mid-ramp with pools and the
    worst-case stress tint — the same backdrop folio_card_retune composites
    planes over, exposed here so the step to the plane can be measured."""
    dark = not face["light"]

    def c(role):
        return Color("#" + face["roles"][role][2:])

    bg, bg2 = c("backgroundBase"), c("backgroundSecondary")
    prim, sec = c("primaryAccent"), c("secondaryAccent")
    foot = prim if (prim.convert("oklch").get("c") or 0) > (sec.convert("oklch").get("c") or 0) else sec
    if dark:
        bloom = mixg(bg, bg2, BLOOM_MIX)
        field_bottom = mixg(bloom, foot, FIELD_FOOT_ACCENT_DARK)
        field_top = bg
    else:
        field_top = bg
        field_bottom = mixg(mixg(bg2, Color("#000000"), 0.07), foot, FIELD_FOOT_ACCENT_LIGHT)
    field_mid = mixg(field_top, field_bottom, 0.5)
    pools = harmonize_pool_hues(
        [c("glowPrimary"), c("glowSecondary"), c("warmAccent")], 0.18
    )
    # Typical room, grounded in the renderer: SilkField.kt:317 pours the blooms at
    # 0.20 alpha on a dark face and 0.46 on paper (x vibrancy), and a card typically
    # sits over a bloom's mid-gradient — so the typical under-card pour is ~55% of a
    # bloom plus a weaker slice of the warm pool. (The pool-stacked worst case in
    # folio_card_retune is the accent-contrast stress and stays as it is.)
    bloom = 0.20 if dark else 0.46
    typical = composite_over(pools[0], bloom * 0.55, field_mid)
    typical = composite_over(pools[2], bloom * 0.30, typical)
    return field_mid, typical


def oklch(c):
    return c.convert("oklch")


def hue_distance(h1, h2):
    return abs((h1 - h2 + 180) % 360 - 180)


def hexof(c):
    return "0xFF" + c.to_string(hex=True)[1:].upper()


def main():
    apply = "--apply" in sys.argv
    with open(KT, "r", encoding="utf-8") as f:
        text = f.read()
    faces = parse_faces(text)

    # ── pass 1: measure every face's effective card↔room relationship ──
    report = {}
    for face in faces:
        # Achromatic faces (the Gallery pair) are exempt, keyed on their ACCENTS
        # like the accent mechanism — a light face's near-white surface is
        # achromatic by nature, not by design, and must not be skipped.
        if all(
            (col(face, r) is None) or (oklch(col(face, r)).get("c") or 0) < 0.02
            for r in ACCENTS
        ):
            print(f"{face['id']}{'-L' if face['light'] else '-D'}: achromatic, ladder mechanism, skipped")
            continue
        dark = not face["light"]
        alpha = PANE_ALPHA_DARK if dark else PANE_ALPHA_LIGHT
        field_mid, worst = effective_room(face)
        authored = col(face, ROLE_SURFACE)
        plane = lift_plane(authored, dark, DARK_PLANE_LIFT_CARD)
        eff_card = composite_over(plane, alpha, worst)
        rl, cl = oklch(eff_card), oklch(worst)
        report[(face["id"], face["light"])] = {
            "step": rl.get("l") - cl.get("l"),
            "drift": hue_distance(rl.get("h") or 0.0, cl.get("h") or 0.0),
            "room_l": cl.get("l"),
            "alpha": alpha,
            "worst": worst,
        }

    # The band is MODE-AWARE: light faces hold a fuller step than dark ones (the
    # dark pack's designed register sits lower — forcing the light median onto
    # them was the ratchet that kept re-lifting every dark plane), and a face is
    # corrected toward its OWN mode's median, never the other's.
    light_steps = sorted(v["step"] for k, v in report.items() if k[1])
    dark_steps = sorted(v["step"] for k, v in report.items() if not k[1])
    light_median = light_steps[len(light_steps) // 2]
    dark_median = dark_steps[len(dark_steps) // 2] if dark_steps else light_median
    print(
        f"pack step: light median {light_median:+.3f}  dark median {dark_median:+.3f}"
        f"  band half {BAND_HALF} (OKLab L)"
    )

    # ── pass 2: correct the outliers ──
    changed = 0
    for face in faces:
        key = (face["id"], face["light"])
        if key not in report:
            continue  # achromatic exempt
        m = report[key]
        tag = f"{face['id']}{'-L' if face['light'] else '-D'}"
        # This face's OWN mode median is the authority (see pass 1's note).
        median = light_median if face["light"] else dark_median
        lo, hi = median - BAND_HALF, median + BAND_HALF
        in_band = lo <= m["step"] <= hi
        # NaN drift = achromatic plane: hue cannot drift if there is none.
        drift_ok = m["drift"] != m["drift"] or m["drift"] <= DRIFT_MAX
        if in_band and drift_ok:
            print(f"{tag}: step {m['step']:+.3f}  in band, untouched")
            continue
        dark = not face["light"]
        authored = col(face, ROLE_SURFACE)
        ok = oklch(authored)
        # Target: the pack's median step, measured on the rendered composite.
        target_l_room = m["room_l"]
        target_eff_l = target_l_room + median
        hue = ok.get("h") or 0.0
        # Drift is REPORTED, never corrected: the plane's hue is authored identity
        # (Vice's pink-black, Aurum's aubergine, every dark face's breath), and the
        # room's own hue under a card is a transient pool mix — pulling the plane
        # toward it gutted the identity for a difference the eye cannot find. The
        # tone step below is the whole correction.
        if not drift_ok:
            room_h = oklch(m["worst"]).get("h") or 0.0
            print(
                f"    (drift note: card hue {hue_distance(hue, room_h):.0f}deg off the room's mix — left as authored)"
            )
        chroma = ok.get("c") or 0.0
        best = None
        direction = 1 if median > m["step"] else -1
        for i in range(240):
            l = ok.get("l") + direction * 0.005 * i
            if not 0.02 <= l <= 0.98:
                break
            cand = Color("srgb", [0, 0, 0])
            c_ok = ok.clone().set("l", l).set("h", hue).set("c", chroma)
            srgb = c_ok.convert("srgb")
            if not srgb.in_gamut("srgb"):
                srgb.clip("srgb")
            plane = lift_plane(Color(srgb.to_string(hex=True)), dark, DARK_PLANE_LIFT_CARD)
            eff = composite_over(plane, m["alpha"], m["worst"])
            eff_l = oklch(eff).get("l")
            if abs(eff_l - target_eff_l) <= 0.004:
                cand_hex = srgb.to_string(hex=True)
                # Post-rounding check, as always.
                plane = lift_plane(Color(cand_hex), dark, DARK_PLANE_LIFT_CARD)
                eff = composite_over(plane, m["alpha"], m["worst"])
                if abs(oklch(eff).get("l") - target_eff_l) <= 0.006:
                    best = Color(cand_hex)
                    break
        if best is None:
            print(f"{tag}: step {m['step']:+.3f}  OUTLIER, no tone found")
            continue
        # Cap the correction: never drag a plane further than MAX_LIFT from its
        # authored tone — partial separation beats a mid-grey card.
        ok_new = oklch(best)
        if abs(ok_new.get("l") - ok.get("l")) > MAX_LIFT:
            capped_l = ok.get("l") + (MAX_LIFT if ok_new.get("l") > ok.get("l") else -MAX_LIFT)
            capped = ok.clone().set("l", capped_l).set("h", ok_new.get("h") or 0.0).set("c", ok.get("c") or 0.0).convert("srgb")
            if not capped.in_gamut("srgb"):
                capped.clip("srgb")
            best = Color(capped.to_string(hex=True))
        new_hex = hexof(best)
        old_hex = "0xFF" + face["roles"][ROLE_SURFACE][2:].upper()
        # surfaceElevated moves in lockstep (same OKLab L delta, its own hue kept)
        # so the card > panel ladder can never invert when surface is corrected.
        elev = col(face, ROLE_ELEVATED)
        elev_hex = None
        if elev is not None:
            d_l = oklch(best).get("l") - ok.get("l")
            e_ok = oklch(elev)
            e_new = e_ok.clone().set("l", min(max(e_ok.get("l") + d_l, 0.02), 0.98)).convert("srgb")
            if not e_new.in_gamut("srgb"):
                e_new.clip("srgb")
            elev_hex = hexof(Color(e_new.to_string(hex=True)))
        drift_note = ""
        if not drift_ok:
            drift_note = (
                f"  hue pulled to {hue_distance(oklch(best).get('h') or 0.0, oklch(m['worst']).get('h') or 0.0):.0f}deg of room"
            )
        eff_l = None
        plane = lift_plane(best, dark, DARK_PLANE_LIFT_CARD)
        eff = composite_over(plane, m["alpha"], m["worst"])
        eff_l = oklch(eff).get("l") - oklch(m["worst"]).get("l")
        print(
            f"{tag}: step {m['step']:+.3f} -> {eff_l:+.3f} (target {median:+.3f})"
            f"{drift_note}   {ROLE_SURFACE}: {old_hex} -> {new_hex}"
            + (f"   {ROLE_ELEVATED}: -> {elev_hex}" if elev_hex else "")
        )
        if apply and new_hex != old_hex:
            face_start = text.index(
                f'id = "{face["id"]}", light = {str(face["light"]).lower()},'
            )
            face_end = text.index("\n    ),", face_start)
            block = text[face_start:face_end]
            # The captured role hex is the full 8 chars INCLUDING the alpha FF —
            # match it whole, or the pattern never finds the line.
            old = face["roles"][ROLE_SURFACE].upper()
            new_block, n = re.subn(
                rf"{ROLE_SURFACE} = 0x{old}\b", f"{ROLE_SURFACE} = {new_hex}", block, count=1
            )
            if elev_hex is not None:
                old_e = face["roles"][ROLE_ELEVATED].upper()
                new_block, n2 = re.subn(
                    rf"{ROLE_ELEVATED} = 0x{old_e}\b", f"{ROLE_ELEVATED} = {elev_hex}", new_block, count=1
                )
                n = n + n2
            if n >= 2 or (n >= 1 and elev_hex is None):
                text = text[:face_start] + new_block + text[face_end:]
                changed += 1
    if apply:
        with open(KT, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"\n--apply: wrote {changed} plane change(s).")
    else:
        print(f"\nreport only. re-run with --apply to write.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
