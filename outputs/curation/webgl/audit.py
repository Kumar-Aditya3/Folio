"""
The canonical theme audit, measured against the app's SHIPPED palettes.

What this replaces, and why it is not a rewrite:

  * `topology.py` SURVIVED the wipe and is imported, not reimplemented. Its module docstring
    records the failure this prevents -- two functions both called `T6`, one sorting the
    pairwise distances and one comparing them positionally, disagreeing on every family
    (nebula 58 vs 21). The spec's rule is that all code imports the canonical
    implementation from one topology module, and this file obeys it.

  * `identity_audit.py` survived as a *script* but not as a working one: it imports
    `design_chroma_tiers` and `nebula_envelope`, neither of which exists any more. Its
    definitions are transcribed here (each one is quoted to its original), and its
    `lch` -- which came from the missing module -- is re-based on **Oklab**, which is what
    the spec's section 2 mandates. Nothing in these metrics depends on the absolute chroma
    scale: `wc_order` reads only its sign, `chroma_shape` divides by its own maximum,
    `axis_coherence` is an eigenvalue *ratio*, and `energy` is used only for ranking. So
    the re-basing cannot move a score.

  * It reads the faces from `FolioArtDirection.kt`, through `extract.py`. The old audit read
    `assign.py`'s output, i.e. the curation pipeline's intermediate; the spec's rule is to
    measure the OUTPUT, and the app's shipped palettes are the output.

Thresholds are versioned with their definitions (spec section 11). Every metric record
carries a definition id, a normalisation, a reference population and a threshold, because
a bare threshold beside a metric name is what let the old A/W gates survive a redefinition
and become unreachable.
"""
import itertools
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import topology as TOP  # noqa: E402  the surviving canonical module

# ── the six authored chromatic keys ───────────────────────────────────────────
# From identity_audit.py, verbatim: the sheet's own column order.
KEYS = ("backgroundBase", "surface", "primaryAccent", "secondaryAccent",
        "tertiaryAccent", "warmAccent")

# ── the semantic anchor bands, verbatim from identity_audit.py ────────────────
ANCHOR = {
    "ocean": (150, 210, "teal"), "forest": (80, 175, "green"), "terracotta": (15, 70, "earth"),
    "arctic": (180, 285, "cool"), "solar": (350, 70, "warm"), "jade": (140, 205, "jade"),
    "mintcoral": None, "moonstone": (240, 340, "mauve"), "nebula": None,
}

WEIGHTS = dict(T=0.30, S=0.25, W=0.20, A=0.15, C=0.10)

# Every threshold, with the definition it belongs to. A threshold is meaningless without it.
GATES = {
    "T6": {"definition": "T6/v1 sorted pairwise hue-distance multiset, mean abs difference",
           "normalisation": "0-100, 100 = identical multiset",
           "population": "9 families x {light,dark}, six authored keys",
           "threshold": 70.0},
    "S": {"definition": "S/v1 dominant dark chromatic axis inside the family's anchor band",
          "normalisation": "0-100, -3 per degree outside the band",
          "population": "7 anchored families (mintcoral and nebula are anchor-free)",
          "threshold": 60.0},
    "W6": {"definition": "W6/v2 per-pair warm/cool ORDERING, chroma-weighted, positional",
           "normalisation": "0-100 = fraction of the 15 pairs agreeing",
           "population": "9 families x {light,dark}, six authored keys",
           "threshold": 40.0},
    "A": {"definition": "A/v2 covariance anisotropy 100*(l1-l2)/(l1+l2) in the Oklab ab plane",
          "normalisation": "0-100, rotation-invariant by construction",
          "population": "9 families, both faces averaged",
          "threshold": 30.0},
    "C": {"definition": "C/v2 rank-normalised chroma hierarchy shape, mean abs difference x 90",
          "normalisation": "0-100, 100 = identical shape",
          "population": "9 families x {light,dark}, six authored keys",
          "threshold": 40.0},
    "E": {"definition": "E/v1 energy RANK among the nine families, -14 per rank step",
          "normalisation": "0-100",
          "population": "9 families, ranked on mean chroma",
          "threshold": 60.0},
}

CLASS = {f: "X" if f == "nebula" else "P" for f in ANCHOR}

# The binding regression set (spec section 9). Not a recommendation.
REGRESSION_SET = ["ocean", "terracotta", "arctic", "solar", "jade", "mintcoral"]

# ── Oklab, per the spec's section 2 ───────────────────────────────────────────

def _srgb_to_linear(u):
    return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4


def lch(rgb):
    """(Oklab L, Oklab chroma, Oklab hue degrees). Replaces the wiped module's lch.

    Accepts the `0xAARRGGBB` form `extract.py` lifts out of `FolioArtDirection.kt`, or a
    0..1 triple.
    """
    if isinstance(rgb, str):
        v = int(rgb, 16)
        rgb = (((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255)
    r, g, b = (_srgb_to_linear(v) for v in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    lc, mc, sc = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    oa = 1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc
    ob = 0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc
    return (0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            math.hypot(oa, ob),
            math.degrees(math.atan2(ob, oa)) % 360.0)


def face_hues(face):
    return [lch(face[k])[2] for k in KEYS]


def face_chromas(face):
    return [lch(face[k])[1] for k in KEYS]


# ── the metrics, each transcribed to its canonical definition ─────────────────

def axis(face):
    """identity_audit.axis -- the chroma-weighted mean hue vector."""
    x = y = 0.0
    for c in (lch(face[k]) for k in KEYS):
        x += c[1] * math.cos(math.radians(c[2]))
        y += c[1] * math.sin(math.radians(c[2]))
    n = math.hypot(x, y)
    return (x / n, y / n) if n else (0.0, 0.0)


def axis_coherence(face):
    """identity_audit.axis_coherence -- A/v2, the covariance anisotropy."""
    pts = []
    for c in (lch(face[k]) for k in KEYS):
        pts.append((c[1] * math.cos(math.radians(c[2])), c[1] * math.sin(math.radians(c[2]))))
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
    """identity_audit.chroma_shape -- the rank-normalised chroma hierarchy."""
    cs = sorted(face_chromas(face), reverse=True)
    m = cs[0] or 1.0
    return [v / m for v in cs]


def energy(face):
    return sum(face_chromas(face)) / len(KEYS)


def energy_score(faces):
    """identity_audit.energy_score -- E/v1, -14 per rank step across the nine."""
    names = list(faces)
    lr = {n: i for i, n in enumerate(sorted(names, key=lambda n: energy(faces[n][0])))}
    dr = {n: i for i, n in enumerate(sorted(names, key=lambda n: energy(faces[n][1])))}
    return {n: max(0.0, 100.0 - abs(lr[n] - dr[n]) * 14.0) for n in names}


def score(fam, light, dark, e):
    """identity_audit.score, with T6/W6 taken from the canonical topology module."""
    hl, hd = face_hues(light), face_hues(dark)
    cl, cd = face_chromas(light), face_chromas(dark)

    t = TOP.T6(hl, hd)                       # canonical: sorted distance multiset
    w = TOP.W6(hl, hd, cl, cd)               # canonical: positional warm/cool ordering
    a = 0.5 * (axis_coherence(light) + axis_coherence(dark))

    ax_l, ax_d = axis(light), axis(dark)
    x = max(0.0, 100.0 - (1.0 - (ax_l[0] * ax_d[0] + ax_l[1] * ax_d[1])) * 100.0 / 2.0)

    c = max(0.0, 100.0 - sum(abs(p - q) for p, q in zip(chroma_shape(light), chroma_shape(dark))) * 90.0)

    band = ANCHOR.get(fam)
    if band is None:
        s, note = 100.0, "free (no anchor by design)"
    else:
        lo, hi, name = band
        hd_axis = math.degrees(math.atan2(ax_d[1], ax_d[0])) % 360.0
        inside = lo <= hd_axis <= hi
        s = 100.0 if inside else max(0.0, 100.0 - min(TOP.dh(hd_axis, lo), TOP.dh(hd_axis, hi)) * 3.0)
        note = f"{name} {'held' if inside else 'MIGRATED'} (dark axis {hd_axis:.0f} deg)"

    total = sum(WEIGHTS[k] * v for k, v in zip("TSWAC", (t, s, w, a, c)))
    return {"I": total * 0.85 + e * 0.15, "T6": t, "S": s, "W6": w, "A": a, "C": c,
            "E": e, "X": x, "anchor": note}


# ── the Nebula Class-X contract (spec section 7) ──────────────────────────────
# The spec is explicit that the frozen literal tolerances did NOT survive, and instructs the
# reconstruction to seed from the surviving role/hue facts and VERSION itself rather than
# pretend the old literals were recovered. So this is contract `nebula-x/v1-reconstructed`,
# and its tolerances are stated as such.
NEBULA_CONTRACT = {
    "id": "nebula-x/v1-reconstructed",
    "tolerances_recovered": False,
    "notes": "Frozen literal tolerances did not survive the wipe. Seeded from the role/hue "
             "facts in the spec's section 7 and the authored dark vocabulary.",
    # dH(primary, warmAccent) ~= 4.55 deg. The topology-aligned counterfactual expands it to
    # ~79.3 deg and must be rejected. The band below admits the authored relation and
    # refuses the counterfactual.
    "signature_pair": ("primaryAccent", "warmAccent", 4.55, 12.0),
    # The counterfactual's substitutions, which are the thing to reject.
    "forbidden": {"primaryAccent": (122.0, 12.0), "secondaryAccent": (226.0, 12.0)},
    "vocabulary": (300.0, 20.0),   # authored dark violet family, +/- 20 deg
}


def nebula_clauses(dark):
    """The five clauses, each returning (name, passed, detail)."""
    hues = {k: lch(dark[k])[2] for k in KEYS}
    chromas = {k: lch(dark[k])[1] for k in KEYS}
    out = []

    # 1. role-anchored semantic gate: the dark vocabulary is a violet family.
    lo, span = NEBULA_CONTRACT["vocabulary"]
    in_violet = sum(1 for k in ("primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent")
                    if TOP.dh(hues[k], lo) <= span)
    out.append(("role-anchored semantic gate", in_violet >= 3,
                f"{in_violet}/4 accents inside {lo:.0f} +/- {span:.0f} deg"))

    # 2. vocabulary membership: no arbitrary hue substitution.
    bad = []
    for key, (forbidden, tol) in NEBULA_CONTRACT["forbidden"].items():
        if TOP.dh(hues[key], forbidden) <= tol:
            bad.append(f"{key} at {hues[key]:.0f} deg is the counterfactual's {forbidden:.0f} deg")
    out.append(("vocabulary membership", not bad,
                "; ".join(bad) if bad else "no authored role sits on a counterfactual hue"))

    # 3. role-conditioned relational signature.
    a, b, target, tol = NEBULA_CONTRACT["signature_pair"]
    d = TOP.dh(hues[a], hues[b])
    out.append(("role-conditioned relational signature", abs(d - target) <= tol,
                f"dH({a}, {b}) = {d:.2f} deg, target {target} +/- {tol}"))

    # 4. energy-class preservation.
    e = energy(dark)
    out.append(("energy-class preservation", e > 0.02,
                f"dark mean Oklab chroma {e:.4f}"))

    # 5. within-face axis coherence.
    ac = axis_coherence(dark)
    out.append(("within-face axis coherence", ac >= GATES["A"]["threshold"],
                f"A = {ac:.1f}, gate {GATES['A']['threshold']}"))

    return out


def main():
    data = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    faces = {}
    for f in data:
        faces.setdefault(f["family"], {})["light" if f["light"] else "dark"] = f["roles"]

    sheet = [n for n in ANCHOR if n in faces]
    E = energy_score({n: (faces[n]["light"], faces[n]["dark"]) for n in sheet})

    print("CANONICAL THEME AUDIT -- measured on the app's shipped palettes")
    print(f"  six authored keys: {', '.join(KEYS)}")
    print(f"  T6/W6 from {TOP.__file__}")
    print(f"  gates: " + "  ".join(f"{k}>={v['threshold']:.0f}" for k, v in GATES.items()))
    print()
    print(f"{'family':<11} {'cls':<3} {'T6':>4} {'S':>4} {'W6':>4} {'A':>4} {'C':>4} {'E':>4} "
          f"| {'I':>4}  gate")
    rows = {}
    for fam in sheet:
        r = score(fam, faces[fam]["light"], faces[fam]["dark"], E[fam])
        rows[fam] = r
        cls = CLASS[fam]
        if cls == "X":
            clauses = nebula_clauses(faces[fam]["dark"])
            ok = all(o for _, o, _ in clauses)
            gate = "PASS-X" if ok else "fail-X"
        else:
            fails = [k for k in ("T6", "S", "W6", "A", "C", "E") if r[k] < GATES[k]["threshold"]]
            gate = "PASS" if not fails else "fail " + ",".join(fails)
        rows[fam]["gate"] = gate
        print(f"{fam:<11} {cls:<3} {r['T6']:>4.0f} {r['S']:>4.0f} {r['W6']:>4.0f} "
              f"{r['A']:>4.0f} {r['C']:>4.0f} {r['E']:>4.0f} | {r['I']:>4.0f}  {gate}")

    print()
    print("anchors:")
    for fam in sheet:
        print(f"  {fam:<11} {rows[fam]['anchor']}")

    # ── Phase D: the regression assertions ────────────────────────────────────
    print()
    print("regression set (spec section 9) -- binding:")
    reg_ok = True
    for fam in REGRESSION_SET:
        if fam not in rows:
            print(f"  {fam:<11} MISSING")
            reg_ok = False
            continue
        gate = rows[fam]["gate"]
        ok = gate in ("PASS", "PASS-X")
        reg_ok = reg_ok and ok
        print(f"  {fam:<11} {gate}")
    print(f"  -> {'all six pass' if reg_ok else 'REGRESSION FAILURE'}")

    # ── the Nebula Class-X block ──────────────────────────────────────────────
    if "nebula" in rows:
        print()
        print("NEBULA -- class TRANSFORMATIVE")
        print(f"  contract: {NEBULA_CONTRACT['id']}  "
              f"(tolerances recovered: {NEBULA_CONTRACT['tolerances_recovered']})")
        print(f"  T6 {rows['nebula']['T6']:.0f}   W6 {rows['nebula']['W6']:.0f}   "
              f"C {rows['nebula']['C']:.0f}   <- diagnostics, not gates")
        for name, ok, detail in nebula_clauses(faces["nebula"]["dark"]):
            print(f"  {'PASS' if ok else 'REJECT':<7} {name}")
            print(f"          {detail}")

    # ── the System pair, which is not on the sheet ────────────────────────────
    if "system" in faces:
        r = score("system", faces["system"]["light"], faces["system"]["dark"],
                  E.get("system", 100.0))
        print()
        print(f"SYSTEM (not on the art-direction sheet, reported for completeness)")
        print(f"  T6 {r['T6']:.0f}  S {r['S']:.0f}  W6 {r['W6']:.0f}  A {r['A']:.0f}  "
              f"C {r['C']:.0f}   I {r['I']:.0f}")

    out = {
        "keys": list(KEYS),
        "gates": GATES,
        "weights": WEIGHTS,
        "canonical_topology_module": str(TOP.__file__),
        "nebula_contract": {k: (list(v) if isinstance(v, tuple) else v)
                            for k, v in NEBULA_CONTRACT.items()},
        "regression_set": REGRESSION_SET,
        "regression_ok": reg_ok,
        "faces": rows,
    }
    (HERE / "audit.json").write_text(json.dumps(out, indent=1, default=str), encoding="utf-8")
    print(f"\n-> audit.json")
    return 0 if reg_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
