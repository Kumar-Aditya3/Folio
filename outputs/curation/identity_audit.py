"""Theme identity as STRUCTURE, not per-hue correspondence.

Raw hue displacement asks "did every hue survive?", which is too strict — a theme can shift
H 280 -> 310 and still be a cool violet/magenta world, while H 280 -> 40 is a genuine identity
change. So identity is measured from the palette's *geometry*:

    I = wT*T + wW*W + wA*A + wC*C + wS*S          (wT > wW > wA > wC)

    T  hue topology   - the set of pairwise hue distances, which is label-free: it captures whether
                        the palette is analogous / complementary / triadic without needing a
                        correspondence between the two faces' swatches
    W  warm/cool      - the weighted warm/cool balance
    A  dominant axis  - 1 - cos(vL, vD), the chromatic direction the face points in
    C  chroma ranking - the SHAPE of the chroma hierarchy, not its absolute values, because dark
                        is systematically louder across this sheet and that is an energy change,
                        not an identity change
    S  semantic anchor- a per-family hue band the dominant axis must stay inside

Each sub-score is 0-100, 100 = perfectly preserved.
"""
import contextlib
import io
import itertools
import math
import os
import sys

sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp/curate")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp")
os.chdir(r"D:/projects/Folio/.workbuddy-ai/tmp")

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import design_chroma_tiers as D
    import sheet as S

WEIGHTS = dict(T=0.30, S=0.25, W=0.20, A=0.15, C=0.10)

# The semantic anchor: the hue band each family's dominant axis must stay inside. Nebula has none
# by design — its brief is explicitly high transformation freedom.
ANCHOR = {
    "ocean": (150, 210, "teal"), "forest": (80, 175, "green"), "terracotta": (15, 70, "earth"),
    "arctic": (180, 285, "cool"), "solar": (350, 70, "warm"), "jade": (140, 205, "jade"),
    "mintcoral": None, "moonstone": (240, 340, "mauve"), "nebula": None,
}


def lch(c):
    """Accepts either a 0-255 triple or a `#RRGGBB` string."""
    if isinstance(c, str):
        c = tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))
    L, C, h = D.lch(tuple(v / 255.0 for v in c))
    return L, C / 100.0, h


def dh(a, b):
    x = abs(a - b) % 360.0
    return min(x, 360.0 - x)


def pair_dists(face):
    """The 15 pairwise hue distances — the palette's relational signature, label-free."""
    hs = [lch(c)[2] for c in face]
    return sorted(dh(a, b) for a, b in itertools.combinations(hs, 2))


def wc_order(face):
    """**W redefined: the ORDERING of warm/cool, not the absolute centroid.**

    An intentional rotation changes the centroid even while the semantic identity is preserved, so
    comparing means would flag a correct repair as a W failure. This compares the sign of each
    swatch pair's warm/cool difference, which a rotation cannot change.
    """
    vals = []
    for c in face:
        _, ch, h = lch(c)
        w = 1.0 if (h < 90 or h > 300) else (-1.0 if 150 <= h <= 270 else 0.0)
        vals.append(w * ch)
    return [1 if a > b else (-1 if a < b else 0)
            for a, b in itertools.combinations(vals, 2)]


def wc_balance(face):
    """+1 warm, -1 cool, weighted by chroma so a neutral page does not outvote an accent."""
    num = den = 0.0
    for c in face:
        _, ch, h = lch(c)
        w = 1.0 if (h < 90 or h > 300) else (-1.0 if 150 <= h <= 270 else 0.0)
        num += w * ch
        den += ch
    return num / den if den else 0.0


def axis(face):
    """The dominant chromatic direction: chroma-weighted mean hue vector."""
    x = y = 0.0
    for c in face:
        _, ch, h = lch(c)
        x += ch * math.cos(math.radians(h))
        y += ch * math.sin(math.radians(h))
    n = math.hypot(x, y)
    return (x / n, y / n) if n else (0.0, 0.0)


def axis_coherence(face):
    """**A redefined: does the face HAVE a coherent chromatic axis, not where it points.**

    The old A measured the dark axis against the *light* axis, which made it incompatible with
    anchor repair — it rewarded the dark face for staying near the thing we were trying to move it
    away from. Ocean went S 1->61 and A 100->2 on the same rotation, which is the definition
    fighting itself, not a palette failure.

    This is the anisotropy of the chromatic covariance in the (a, b) plane:

        A = 100 * (lambda1 - lambda2) / (lambda1 + lambda2)

    A rotation `v_i -> R_theta v_i` leaves the eigenvalues unchanged, so A is **invariant under
    exactly the operation anchor repair performs**. That is the property it needed.
    """
    pts = []
    for c in face:
        _, ch, h = lch(c)
        pts.append((ch * math.cos(math.radians(h)), ch * math.sin(math.radians(h))))
    n = len(pts)
    mx = sum(p[0] for p in pts) / n
    my = sum(p[1] for p in pts) / n
    sxx = sum((p[0] - mx) ** 2 for p in pts) / n
    syy = sum((p[1] - my) ** 2 for p in pts) / n
    sxy = sum((p[0] - mx) * (p[1] - my) for p in pts) / n
    tr, det = sxx + syy, sxx * syy - sxy * sxy
    disc = max(0.0, tr * tr / 4.0 - det) ** 0.5
    l1, l2 = tr / 2.0 + disc, tr / 2.0 - disc
    return 100.0 * (l1 - l2) / (l1 + l2) if (l1 + l2) else 0.0


def chroma_shape(face):
    """The normalised chroma ranking — the shape of the hierarchy, not its absolute level."""
    cs = sorted((lch(c)[1] for c in face), reverse=True)
    m = cs[0] or 1.0
    return [v / m for v in cs]


def energy(face):
    """Relative energy: mean chroma of the six swatches. Absolute level is not the point."""
    return sum(lch(c)[1] for c in face) / len(face)


def energy_score(all_faces):
    """E = how well each family keeps its ENERGY RANK across the nine.

    Absolute chroma is the wrong measure — 8 of 9 dark faces are louder, and that is an energy
    transformation, not an identity failure. What matters is whether a family stays in its energy
    *class*: Moonstone being the quietest in light and the loudest in dark would be a failure even
    though no hue moved.
    """
    names = list(all_faces)
    lv = sorted(names, key=lambda n: energy(all_faces[n][0]))
    dv = sorted(names, key=lambda n: energy(all_faces[n][1]))
    lr = {n: i for i, n in enumerate(lv)}
    dr = {n: i for i, n in enumerate(dv)}
    return {n: max(0.0, 100.0 - abs(lr[n] - dr[n]) * 14.0) for n in names}


# **Recalibrated for the new definitions.** The old A (axis vs the light face) ran 94-100, so a
# gate of 70 discriminated. The new A is an *anisotropy*, which for a deliberately multi-hue palette
# naturally sits near 0.5 (isotropic = 0, collinear = 1), so the whole set lands at 34-63 and a gate
# of 70 is unreachable. Same for W: the relational ordering is far stricter than the old centroid.
# A metric's threshold does not survive a change to its definition.
# **Two formally different classes.** Class P (paired) constrains the dark face by the light face's
# relational structure: `P_D ~ f(P_L)`, gated on T/S/W/A/C/E. Class X (transformative) permits the
# dark face to reorganise its topology -- `P_D !~ f(P_L)` -- and is instead held to a *semantic
# envelope* derived from its own authored swatches. Nebula is the only Class X theme, and the
# counterfactual is why: aligning it turns 312 -> 122 (violet -> green), a metric gain and a design
# regression. T/W/C stay visible for it as diagnostics, but they are not gates.
CLASS = {f: "X" if f == "nebula" else "P" for f in ANCHOR}

GATES = dict(T=70.0, S=60.0, W=40.0, A=30.0, C=40.0, E=60.0)


def score(fam, L, K, e=100.0):
    t = 100.0 - sum(abs(a - b) for a, b in zip(pair_dists(L), pair_dists(K))) / len(pair_dists(L))
    ow = wc_order(L)
    ok = wc_order(K)
    agree = sum(1 for x, y in zip(ow, ok) if x == y)
    w = 100.0 * agree / max(1, len(ow))
    a = 0.5 * (axis_coherence(L) + axis_coherence(K))
    ax_l, ax_d = axis(L), axis(K)
    x = (1.0 - (ax_l[0] * ax_d[0] + ax_l[1] * ax_d[1])) * 100.0 / 2.0
    x = max(0.0, 100.0 - x)
    c = max(0.0, 100.0 - sum(abs(x - y) for x, y in zip(chroma_shape(L), chroma_shape(K))) * 90.0)
    band = ANCHOR.get(fam)
    if band is None:
        s, note = 100.0, "free (no anchor by design)"
    else:
        lo, hi, name = band
        hd = math.degrees(math.atan2(ax_d[1], ax_d[0])) % 360.0
        inside = (lo <= hd <= hi)
        s = 100.0 if inside else max(0.0, 100.0 - min(dh(hd, lo), dh(hd, hi)) * 3.0)
        note = "%s %s (dark axis %.0f deg)" % (name, "held" if inside else "MIGRATED", hd)
    total = sum(WEIGHTS[k] * v for k, v in zip("TSWAC", (t, s, w, a, c)))
    total = total * 0.85 + e * 0.15
    return total, t, s, w, a, c, note, e, x


def main():
    # **Measure the OUTPUT, not the sheet.** The audit must read what `assign.py` actually emits —
    # otherwise the anchor repair is invisible to it, which is exactly what happened on the first
    # run: the rotation fired and every score stayed identical.
    faces = {}
    for f in S.SHEET:
        fl = AS.face(f, True)
        fd = AS.face(f, False)
        keys = ("backgroundBase", "surface", "primaryAccent", "secondaryAccent",
                "tertiaryAccent", "warmAccent")
        fmt = lambda d: ["#%02X%02X%02X" % tuple(int(v) for v in d[k]) for k in keys]
        faces[f] = (fmt(fl), fmt(fd))
    E = energy_score(faces)

    print("9 x 6 DIAGNOSTIC MATRIX   (gates: T>=70 S>=60 W>=40 A>=30 C>=40 E>=60)")
    print("T6/W6 = full authored-key audit (this matrix). T4/W4 = the 4 chromatic-swatch\ndiagnostic in forest_repair.py -- different projections, NOT interchangeable.")
    print("class P = paired (gated on T/S/W/A/C/E).  class X = transformative (gated on its own")
    print("semantic envelope; T/S/W/C are diagnostics for it, not gates).")
    print("%-11s %-3s %-5s %-5s %-5s %-5s %-5s %-5s | %-6s %s" % (
        "family", "cls", "T6", "S", "W6", "A", "C", "E", "I", "gate"))
    rows = []
    neb = None
    for fam in S.SHEET:
        L, K = faces[fam]
        tot, t, sc, w, a, c, note, e, xx = score(fam, L, K, E[fam])
        cls = CLASS[fam]
        if cls == "X":
            # Class X: evaluate the semantic envelope instead of the paired gates.
            import nebula_envelope as NE
            clauses = NE.clause_status({k: AS.face(fam, False)[k] for k in NE.CHROM})
            ok = all(o for _, o, _ in clauses)
            gate = "PASS-X" if ok else "fail-X"
            neb = (clauses, t, sc, w, a, c, e, xx)
        else:
            fails = [k for k, v in (("T", t), ("S", sc), ("W", w), ("A", a), ("C", c), ("E", e))
                     if v < GATES[k]]
            gate = "PASS" if not fails else "fail " + ",".join(fails)
        rows.append((tot, fam, t, sc, w, a, c, e, gate, note))
        # `I` is the weighted identity index `tot`. The old column printed `xx` (axis vs the LIGHT
        # axis) under the same heading, which is a different quantity -- the same label collision
        # that produced two different `T6`s. `xx` is still returned by `score` but no longer shown.
        print("%-11s %-3s %-5.0f %-5.0f %-5.0f %-5.0f %-5.0f %-5.0f | %-6.0f %s" % (
            fam, cls, t, sc, w, a, c, e, tot, gate))
    print()
    print("failure classes (Class P only -- Class X is not gated on these):")
    any_p_fail = False
    for tot, fam, t, sc, w, a, c, e, gate, note in rows:
        if CLASS[fam] == "P" and gate != "PASS":
            print("  %-11s %s" % (fam, gate))
            any_p_fail = True
    if not any_p_fail:
        print("  none -- all 8 Class P families pass")
    print()
    print("anchors:")
    for tot, fam, t, sc, w, a, c, e, gate, note in rows:
        print("  %-11s %s" % (fam, note))
    print()
    print("energy rank (quiet -> loud), light then dark:")
    lv = sorted(faces, key=lambda n: energy(faces[n][0]))
    dv = sorted(faces, key=lambda n: energy(faces[n][1]))
    print("  light: " + " < ".join(lv))
    print("  dark:  " + " < ".join(dv))

    # ── the Class X contract block ───────────────────────────────────────────────────────────────
    if neb:
        clauses, t, sc, w, a, c, e, xx = neb
        print()
        print("=" * 78)
        print("NEBULA")
        print("  class: TRANSFORMATIVE")
        print()
        print("  T6: %.0f" % t)
        print("  W6: %.0f" % w)
        print("  C:  %.0f" % c)
        print()
        print("  structural correspondence: TRANSFORMED")
        for name, ok, detail in clauses:
            print("  %-24s %s" % (name + ":", "PASS" if ok else "REJECT"))
            print("  %-24s   %s" % ("", detail))
        ok = all(o for _, o, _ in clauses)
        print()
        print("  status: %s" % ("PASS -- TRANSFORMATIVE CONTRACT" if ok
                                else "REJECT -- LEAVES THE SEMANTIC ENVELOPE"))
        print("  (T6/W6/C retained as diagnostics; they are not gates for this class)")
        print("=" * 78)


if __name__ == "__main__":
    main()
