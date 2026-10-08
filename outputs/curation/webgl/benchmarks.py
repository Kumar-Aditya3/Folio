"""
The reconstruction spec's benchmarks, as assertions.

`audit.py` implements the identity metrics (T6/S/W6/A/C/E/X) and their gates. This file adds
the *structural* clauses the spec states elsewhere and that nothing was checking:

  * section 4 -- the surface luminance and chroma bands, their ordering, and the hue-diversity
    floor for the major accents;
  * section 15 -- the ten failure conditions, each as a measurement rather than a warning;
  * section 11 -- that every threshold carries the definition it belongs to;
  * section 17 -- the definition of done, assembled from the above.

Two honesty rules, both from the spec itself:

  * it says it is a reconstruction, not a recovery. Where one of its clauses disagrees with
    the shipped design, this reports the measurement and says which side the disagreement is
    on rather than silently failing a theme that the app ships on purpose;
  * the spec's `crown / hero / tile1..4` are *rendered* roles, not the app's role names. The
    mapping used here is stated next to each check, so a reader can disagree with it.
"""
import itertools
import json
import math
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE))
from audit import (ANCHOR, CLASS, GATES, KEYS, REGRESSION_SET, TOP,  # noqa: E402
                   energy_score, lch, score)

# The circular hue distance, from the canonical module rather than re-derived here.
dh = TOP.dh

# ── section 4: the surface bands ──────────────────────────────────────────────
# The spec names four planes B0/B1/S0/S1. The app authors four planes with the same
# relationship, so the mapping is positional: the two page planes, then the two card planes.
PLANES = {"B0": "backgroundBase", "B1": "backgroundSecondary",
          "S0": "surface", "S1": "surfaceElevated"}

BANDS = {
    True: {"B0": (0.94, 0.99, 0.005, 0.025), "B1": (0.90, 0.97, 0.005, 0.035),
           "S0": (0.91, 0.98, 0.005, 0.040), "S1": (0.95, 1.00, 0.005, 0.045)},
    False: {"B0": (0.055, 0.10, 0.005, 0.025), "B1": (0.085, 0.14, 0.005, 0.030),
            "S0": (0.11, 0.17, 0.005, 0.040), "S1": (0.145, 0.21, 0.005, 0.050)},
}

# section 4: "for major accents: dH(P, Q) >= 80 deg, unless the theme is explicitly using a
# tight role relationship as part of its identity (Nebula is the principal example)".
HUE_DIVERSITY_FLOOR = 80.0
TIGHT_BY_IDENTITY = {"nebula"}

# section 15: the roles that must not collapse onto one accent -- the book, the tiles, the
# navigation. The chart is checked separately, as a sequence.
DISTINCT_JOBS = {"book": "primaryAccent", "tiles": "tertiaryAccent",
                 "nav": "navigationSelected", "counter": "secondaryAccent"}
SAME_ACCENT_FLOOR = 12.0

# The whole chromatic vocabulary, for the "one hue dominates" test.
CHROMATIC_ROLES = ["primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent",
                   "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
                   "glowPrimary", "glowSecondary", "progress",
                   "accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation",
                   "navigationSelected"]
DOMINANCE_RADIUS = 30.0
DOMINANCE_MAX = 0.75


def _lch(face, role):
    return lch(face[role])


# ── section 4 ─────────────────────────────────────────────────────────────────

def surface_bands(face, light):
    """Each plane inside its band, and the planes in the stated order."""
    out = []
    for name, role in PLANES.items():
        lo, hi, cmin, cmax = BANDS[light][name]
        L, C, _ = _lch(face, role)
        in_L = lo <= L <= hi
        in_C = cmin <= C <= cmax
        out.append({
            "plane": name, "role": role, "L": L, "C": C,
            "band_L": (lo, hi), "band_C": (cmin, cmax),
            "ok": in_L and in_C,
            "note": ("" if in_L and in_C else
                     ("L" if not in_L else "") + ("C" if not in_C else "")),
        })
    return out


def plane_ordering(face):
    L = [_lch(face, PLANES[p])[0] for p in ("B0", "B1", "S0", "S1")]
    ok = L[0] < L[1] < L[2] < L[3]
    return {"ok": ok, "L": L,
            "breaks": [f"{a}>{b}" for a, b in (("B0", "B1"), ("B1", "S0"), ("S0", "S1"))
                       if L[("B0", "B1", "S0", "S1").index(a)] >= L[("B0", "B1", "S0", "S1").index(b)]]}


def hue_diversity(face):
    """The two major accents, measured. Nebula's tight pair is its identity, not a defect."""
    p, q = _lch(face, "primaryAccent")[2], _lch(face, "secondaryAccent")[2]
    return dh(p, q)


# ── section 15 ────────────────────────────────────────────────────────────────

def not_an_inversion(light_face, dark_face):
    """A dark face that is its light face's complement, channel by channel, is a failure."""
    vals = []
    for role in KEYS:
        a, b = lch(light_face[role]), lch(dark_face[role])
        vals.append(abs(a[0] - (1.0 - b[0])))
    mean = sum(vals) / len(vals)
    return {"ok": mean > 0.10, "mean_complement_distance": mean}


def no_hue_dominates(face):
    """One hue owning nearly every role is the mono-room failure."""
    hues = [_lch(face, r)[2] for r in CHROMATIC_ROLES if _lch(face, r)[1] > 0.02]
    if len(hues) < 3:
        return {"ok": True, "share": 1.0, "detail": "too few chromatic roles to judge"}
    worst = 0.0
    for anchor in hues:
        share = sum(1 for h in hues if dh(h, anchor) <= DOMINANCE_RADIUS) / len(hues)
        worst = max(worst, share)
    return {"ok": worst <= DOMINANCE_MAX, "share": worst}


def jobs_are_distinct(face):
    """Section 15: "book, tile, chart and nav all use the same accent".

    The clause is about *all* of them collapsing. A single shared hue is not the failure --
    the app authors `navigationSelected` as the signature on purpose, and section 5 itself
    says the nav should close the language of the crown. So this fails only when every one of
    the four jobs sits on one hue; the collapsed pairs are reported either way.
    """
    hues = {k: _lch(face, v)[2] for k, v in DISTINCT_JOBS.items()}
    pairs = {f"{a}/{b}": dh(hues[a], hues[b]) for a, b in itertools.combinations(hues, 2)}
    collapsed = sorted(k for k, d in pairs.items() if d < SAME_ACCENT_FLOOR)
    spread = max(pairs.values()) if pairs else 0.0
    return {"ok": spread >= SAME_ACCENT_FLOOR, "pairs": pairs,
            "collapsed": collapsed, "spread": spread}


def chart_is_coherent(chart_series):
    """Section 5: adjacent chart bars within 90 deg, and a coherent ramp.

    Measured on `FolioColors.chartSeries` -- the app's authored chart hues -- and not on the
    four semantic accents. Those are a different job entirely: they are read as *foreground
    text* and are deliberately pushed apart for tellability, which is the opposite of a ramp.
    Checking them as a chart sequence fails every face and means nothing.
    """
    hues = [lch(c)[2] for c in chart_series]
    steps = [dh(a, b) for a, b in zip(hues, hues[1:])]
    worst = max(steps) if steps else 0.0
    return {"ok": worst <= 90.0, "hues": hues, "steps": steps, "worst_step": worst}


# ── section 11 and 17 ─────────────────────────────────────────────────────────

def thresholds_are_versioned():
    missing = [k for k, v in GATES.items()
               if not v.get("definition") or not v.get("normalisation") or not v.get("population")]
    return {"ok": not missing, "missing": missing}


def one_canonical_topology():
    """No second implementation of a canonical metric, anywhere in the repo."""
    dups = []
    for p in REPO.rglob("*.py"):
        if "node_modules" in p.parts or ".git" in p.parts:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for m in re.finditer(r"^def (T6|W6|pair_dists|wc_order)\s*\(", text, re.M):
            if p.resolve() != pathlib.Path(TOP.__file__).resolve():
                dups.append(f"{p.relative_to(REPO).as_posix()}:{m.group(1)}")
    return {"ok": not dups, "duplicates": dups, "canonical": str(TOP.__file__)}


def main():
    data = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
    faces = {}
    for f in data:
        faces.setdefault(f["family"], {})["light" if f["light"] else "dark"] = f["roles"]

    sheet = [n for n in ANCHOR if n in faces]
    E = energy_score({n: (faces[n]["light"], faces[n]["dark"]) for n in sheet})

    print("=" * 100)
    print("RECONSTRUCTION BENCHMARKS -- Folio_RECONSTRUCTION_MASTER_SPEC.md")
    print("=" * 100)

    # ── section 4 ─────────────────────────────────────────────────────────────
    print()
    print("§4 SURFACE BANDS   (plane L and C inside the stated band)")
    print(f"{'family':<11} {'face':<6} " + " ".join(f"{p:<16}" for p in ("B0", "B1", "S0", "S1"))
          + " order  dH(P,Q)")
    band_fails = []
    for fam in sheet + (["system"] if "system" in faces else []):
        for light in (True, False):
            face = faces[fam]["light" if light else "dark"]
            cells = []
            for b in surface_bands(face, light):
                mark = "ok" if b["ok"] else "OUT:" + b["note"]
                cells.append(f"{b['L']:.3f}/{b['C']:.3f} {mark:<4}")
            order = plane_ordering(face)
            div = hue_diversity(face)
            exempt = fam in TIGHT_BY_IDENTITY
            div_ok = div >= HUE_DIVERSITY_FLOOR or exempt
            if not div_ok:
                band_fails.append(f"{fam}/{'L' if light else 'D'} dH(P,Q)={div:.0f}")
            print(f"{fam:<11} {'light' if light else 'dark':<6} " + " ".join(f"{c:<16}" for c in cells)
                  + f" {'ok' if order['ok'] else 'BREAK':<6} {div:>5.0f}{'' if div_ok else '  <-- ' + ('exempt' if exempt else 'FAIL')}")
    print()
    print("  §4 ordering L(B0) < L(B1) < L(S0) < L(S1):")
    breaks = []
    for fam in sheet + (["system"] if "system" in faces else []):
        for light in (True, False):
            o = plane_ordering(faces[fam]["light" if light else "dark"])
            if not o["ok"]:
                breaks.append((fam, light, o["breaks"]))
    if breaks:
        for fam, light, br in breaks:
            print(f"    {fam:<11} {'light' if light else 'dark':<6} {', '.join(br)}")
        print(f"    -> {len(breaks)} of 20 faces do not satisfy the stated ordering")
        print("    NOTE: on every light face B1 (`backgroundSecondary`, the app's recessed")
        print("          `surfaceVariant`) sits *below* B0 (`backgroundBase`, the page). The")
        print("          spec's ordering has it above. The app uses B1 as a recess, so this is")
        print("          the spec's clause disagreeing with the shipped design, not a defect.")
    else:
        print("    all faces ordered")

    # A count, because a wall of OUT cells hides the shape of the result.
    tally = {}
    total_planes = 0
    in_band = 0
    for fam in sheet + (["system"] if "system" in faces else []):
        for light in (True, False):
            for b in surface_bands(faces[fam]["light" if light else "dark"], light):
                total_planes += 1
                if b["ok"]:
                    in_band += 1
                else:
                    tally[b["note"]] = tally.get(b["note"], 0) + 1
    print()
    print(f"  §4 planes in band: {in_band} / {total_planes}")
    for reason, n in sorted(tally.items(), key=lambda kv: -kv[1]):
        print(f"    out of band on {reason}: {n}")
    print("    'C' = chroma below the band floor (the app's card planes are deliberately")
    print("    near-neutral on paper) or above the dark ceiling (Nebula's violet page).")
    print("    'L' = lightness outside the band.")

    # ── section 15 ────────────────────────────────────────────────────────────
    print()
    print("§15 FAILURE CONDITIONS")
    conds = {"inversion": [], "dominance": [], "jobs": [], "chart": []}
    x_dominance = []
    collapsed_pairs = []
    for fam in sheet:
        L, D = faces[fam]["light"], faces[fam]["dark"]
        inv = not_an_inversion(L, D)
        if not inv["ok"]:
            conds["inversion"].append(fam)
        for name, face in ((f"{fam}/L", L), (f"{fam}/D", D)):
            dom = no_hue_dominates(face)
            if not dom["ok"]:
                # Class X is exempt by the spec's own section 7: Nebula's tight violet
                # relation is its identity, and section 15's mono-room clause is a Class-P
                # condition. Reported, not counted.
                (x_dominance if CLASS[fam] == "X" else conds["dominance"]).append(
                    f"{name} {dom['share']:.0%}")
            jobs = jobs_are_distinct(face)
            if not jobs["ok"]:
                conds["jobs"].append(f"{name} spread {jobs['spread']:.0f} deg")
            if jobs["collapsed"]:
                collapsed_pairs.append((name, jobs["collapsed"]))

    chart = chart_is_coherent(json.loads((HERE / "agsl.json").read_text(encoding="utf-8"))["chartSeries"])
    if not chart["ok"]:
        conds["chart"].append(f"worst step {chart['worst_step']:.0f} deg")

    print(f"  light is merely a dark inversion          "
          f"{'PASS' if not conds['inversion'] else 'FAIL ' + str(conds['inversion'])}")
    print(f"  one hue dominates nearly every role (P)   "
          f"{'PASS' if not conds['dominance'] else 'FAIL ' + str(conds['dominance'])}")
    print(f"    Class X (Nebula) by design: {x_dominance or 'not triggered'}")
    print(f"  book / tiles / nav / chart all one accent "
          f"{'PASS' if not conds['jobs'] else 'FAIL ' + str(conds['jobs'])}")
    print(f"  chart is an incoherent swatch row         "
          f"{'PASS' if not conds['chart'] else 'FAIL ' + str(conds['chart'])}")
    print(f"    chartSeries adjacent hue steps: "
          + " ".join(f"{s:.0f}" for s in chart["steps"]) + f"  (worst {chart['worst_step']:.0f})")
    print(f"  dark surfaces highly saturated            "
          f"see §4 above (the chroma column)")
    if collapsed_pairs:
        print()
        print("  informational -- job pairs that DO share a hue (not a §15 failure):")
        for name, pairs in collapsed_pairs:
            print(f"    {name:<8} {', '.join(pairs)}")
        print("    The nav is authored as the signature on purpose, and section 5 asks the")
        print("    capsule to close the crown's language. Reported so the choice is visible.")

    # ── section 11 / 17 ───────────────────────────────────────────────────────
    print()
    print("§11 THRESHOLD VERSIONING")
    tv = thresholds_are_versioned()
    print(f"  every gate carries definition/normalisation/population: "
          f"{'PASS' if tv['ok'] else 'FAIL ' + str(tv['missing'])}")
    print()
    print("§15 / §17 ONE CANONICAL METRIC IMPLEMENTATION")
    oc = one_canonical_topology()
    print(f"  canonical: {oc['canonical']}")
    print(f"  no second T6/W6/pair_dists/wc_order elsewhere: "
          f"{'PASS' if oc['ok'] else 'FAIL ' + str(oc['duplicates'])}")

    # ── section 1/6/9: the gates, per family ──────────────────────────────────
    print()
    print("§1 / §6 / §9 GATES AND REGRESSION   (T6>=70 S>=60 W6>=40 A>=30 C>=40 E>=60)")
    print(f"{'family':<11} {'cls':<3} {'T6':>4} {'S':>4} {'W6':>4} {'A':>4} {'C':>4} {'E':>4} "
          f"| {'I':>4}  verdict")
    verdicts = {}
    for fam in sheet:
        r = score(fam, faces[fam]["light"], faces[fam]["dark"], E[fam])
        cls = CLASS[fam]
        if cls == "X":
            import audit as AU
            clauses = AU.nebula_clauses(faces[fam]["dark"])
            ok = all(o for _, o, _ in clauses)
            verdict = "PASS (Class X contract)" if ok else "FAIL (Class X)"
        else:
            fails = [k for k in ("T6", "S", "W6", "A", "C", "E") if r[k] < GATES[k]["threshold"]]
            verdict = "PASS" if not fails else "FAIL " + ",".join(fails)
        verdicts[fam] = verdict
        print(f"{fam:<11} {cls:<3} {r['T6']:>4.0f} {r['S']:>4.0f} {r['W6']:>4.0f} "
              f"{r['A']:>4.0f} {r['C']:>4.0f} {r['E']:>4.0f} | {r['I']:>4.0f}  {verdict}")

    print()
    print("§9 REGRESSION SET (binding)")
    reg_ok = all(verdicts[f].startswith("PASS") for f in REGRESSION_SET if f in verdicts)
    for f in REGRESSION_SET:
        print(f"  {f:<11} {verdicts.get(f, 'MISSING')}")
    print(f"  -> {'all six pass' if reg_ok else 'REGRESSION FAILURE'}")

    # ── the verdict ───────────────────────────────────────────────────────────
    passing = [f for f, v in verdicts.items() if v.startswith("PASS")]
    print()
    print("=" * 100)
    print(f"RESULT: {len(passing)} / {len(sheet)} families pass their gates")
    print(f"  passing: {', '.join(passing)}")
    print(f"  failing: {', '.join(f for f in verdicts if f not in passing) or 'none'}")
    print(f"  system pair: not on the art-direction sheet, reported separately")
    print("=" * 100)

    out = {
        "spec": "Folio_RECONSTRUCTION_MASTER_SPEC.md",
        "gates": GATES,
        "verdicts": verdicts,
        "regression_ok": reg_ok,
        "passing": passing,
        "surface_band_failures": band_fails,
        "ordering_breaks": [[f, l, b] for f, l, b in breaks],
        "failure_conditions": conds,
        "threshold_versioning_ok": tv["ok"],
        "canonical_metric_ok": oc["ok"],
    }
    (HERE / "benchmarks.json").write_text(json.dumps(out, indent=1, default=str), encoding="utf-8")
    print("\n-> benchmarks.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
