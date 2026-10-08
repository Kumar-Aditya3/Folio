"""The Nebula Class-X (Transformative) contract -- frozen numbers, not re-derived ones.

Nebula is the one Transformative theme. The counterfactual showed that aligning its dark topology to
its light face turns `312 -> 122` (violet -> green) and `52 -> 226` (gold -> blue): a metric
improvement and a design regression. So `T6`/`W6`/`C` are **diagnostics** for it, never gates.

What replaces them has to be derived from the authored swatches *before* looking at any failure score,
or it becomes a post-hoc rationalisation. Three attempts were needed to get there, and the two
failures are instructive:

  1. A circular mean band over the pooled light+dark hue sets. Nebula is the most divergent family
     (79 deg hue-set displacement), so the pooled distribution is nearly uniform: measured sigma
     134-148 deg, band ~288 deg wide. Wide enough to accept green as "violet" -- useless.
  2. A membership envelope taken from the LIGHT face. It accepted the counterfactual, necessarily:
     `topology_align` sets each dark hue *to* the light face's hue at its rank, so any vocabulary
     drawn from the light face contains the aligned result by construction. A contract that cannot
     reject the case it was written for is not a contract.

So the reference is the **dark** face -- the designed artefact -- and its vocabulary is frozen here as
literals. Deriving it at runtime would make the current face pass at `d = 0.0` by construction and
let the contract drift with the palette it is supposed to constrain. `DRIFT` is reported instead.

Two clauses, because "transformative" must not mean "anything goes":

  role-anchored   each role's hue stays near ITS OWN authored value        <- the gate
  vocabulary      each hue stays near SOME authored hue                    <- permits role permutation
  relational      the authored pairwise separations are preserved          <- the signature

The relational clause carries most of the meaning. The authored dark face has `primary` and
`warmAccent` only 4.55 deg apart -- a tight violet pair with a gold counterpoint. That single tight
relation is Nebula's chromatic signature, and it is the first thing an alignment destroys.
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

CHROM = ("primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent")

# ── FROZEN from the authored dark face. Do not re-derive at runtime. ─────────────────────────────
# If the palette changes, these stay put and the run reports DRIFT -- a deliberate edit to the
# contract is then required, which is the point.
DARK_VOCAB = (312.0862, 52.4291, 293.5412, 316.6366)      # primary, secondary, tertiary, warm
DARK_CHROMA = (25.41, 10.83, 15.92, 13.93)
REL = {"PQ": 100.343, "PR": 4.550, "QR": 95.793}          # authored pairwise separations

TOL_ROLE = 40.0        # deg: role-anchored envelope
TOL_VOCAB = 40.0       # deg: vocabulary membership (permits role migration)
TOL_REL = 25.0         # deg: pairwise separation tolerance
CHROMA_FLOOR = 8.0     # minimum chroma for primary and secondary -- "high chromatic energy"
AXIS_ARC = 60.0        # deg: a dominant axis means >= 3 of 4 hues inside one arc this wide
DRIFT_TOL = 0.5        # deg: beyond this, the frozen contract no longer matches the palette


def lch(c):
    return AS._lch255(c)


def dh(a, b):
    x = abs(a - b) % 360.0
    return min(x, 360.0 - x)


def axis_arc(hs, arc=AXIS_ARC):
    """The widest number of hues that fit inside one `arc`-wide window -- the dominant-axis test."""
    best = 0
    for h in hs:
        n = sum(1 for g in hs if (g - h) % 360.0 <= arc)
        best = max(best, n)
    return best


PAIRS = {("primaryAccent", "secondaryAccent"): "PQ",
         ("primaryAccent", "warmAccent"): "PR",
         ("secondaryAccent", "warmAccent"): "QR"}


def structural(face):
    """T6/W6/C for the Class-X diagnostic block -- the CANONICAL definitions from `topology.py`."""
    import topology as TP
    keys = ("backgroundBase", "surface") + CHROM
    light = AS.face("nebula", True)
    lh = [lch(light[k])[2] for k in keys]
    dhh = [lch(face[k])[2] for k in keys]
    lc = [lch(light[k])[1] for k in keys]
    dcc = [lch(face[k])[1] for k in keys]
    t = TP.T6(lh, dhh)
    w = TP.W6(lh, dhh, lc, dcc)
    lcs = sorted((lch(light[k])[1] for k in keys), reverse=True)
    dcs = sorted((lch(face[k])[1] for k in keys), reverse=True)
    m = lcs[0] or 1.0
    c = max(0.0, 100.0 - sum(abs(a - b) for a, b in
                             zip([v / m for v in lcs], [v / (dcs[0] or 1.0) for v in dcs])) * 90.0)
    return t, w, c


def clause_status(face):
    """The five clauses as `(name, ok, detail)` -- reusable, so the audit can consume them."""
    hs = [lch(face[k])[2] for k in CHROM]
    cs = [lch(face[k])[1] for k in CHROM]

    role_fail = [k for k, h, ref in zip(CHROM, hs, DARK_VOCAB) if dh(h, ref) > TOL_ROLE]
    vocab_fail = [k for k, h in zip(CHROM, hs)
                  if min(dh(h, v) for v in DARK_VOCAB) > TOL_VOCAB]
    rel_fail = [n for (a, b), n in PAIRS.items()
                if dh(dh(lch(face[a])[2], lch(face[b])[2]), REL[n]) > TOL_REL]
    energy_ok = cs[0] >= CHROMA_FLOOR and cs[1] >= CHROMA_FLOOR
    n_axis = axis_arc(hs)
    axis_ok = n_axis >= 3

    return [
        ("semantic envelope", not role_fail,
         "role %s" % ", ".join(role_fail) if role_fail else "every role inside its authored band"),
        ("vocabulary membership", not vocab_fail,
         "hue %s left the authored set" % ", ".join(vocab_fail) if vocab_fail
         else "every hue is a member of the authored set"),
        ("relational signature", not rel_fail,
         "dH %s broken" % ", ".join(rel_fail) if rel_fail
         else "authored separations held"),
        ("energy class", energy_ok,
         "primary C %.1f, secondary C %.1f (floor %.1f)" % (cs[0], cs[1], CHROMA_FLOOR)),
        ("axis coherence", axis_ok,
         "%d of 4 hues inside a %.0f deg arc" % (n_axis, AXIS_ARC)),
    ]


def evaluate(face, label, lines):
    hs = [lch(face[k])[2] for k in CHROM]
    for k, h, ref in zip(CHROM, hs, DARK_VOCAB):
        d = dh(h, ref)
        lines.append("  %-16s %7.1f   authored %7.1f   d %6.1f   %s"
                     % (k, h, ref, d, "PASS" if d <= TOL_ROLE else "REJECT"))
    for (a, b), name in PAIRS.items():
        got = dh(lch(face[a])[2], lch(face[b])[2])
        lines.append("  dH_%-3s  %7.1f   authored %7.1f   d %6.1f   %s"
                     % (name, got, REL[name], dh(got, REL[name]),
                        "PASS" if dh(got, REL[name]) <= TOL_REL else "REJECT"))

    clauses = clause_status(face)
    passed = all(ok for _, ok, _ in clauses)
    lines.append("")
    lines.append("  %s" % label)
    for name, ok, detail in clauses:
        lines.append("    %-22s %s   (%s)" % (name, "PASS" if ok else "REJECT", detail))
    lines.append("    status                 %s"
                 % ("PASS - TRANSFORMATIVE CONTRACT" if passed
                    else "REJECT - LEAVES THE SEMANTIC ENVELOPE"))
    return passed


def main():
    light = AS.face("nebula", True)
    dark = AS.face("nebula", False)
    live = [lch(dark[k])[2] for k in CHROM]

    print("NEBULA - CLASS X (TRANSFORMATIVE) CONTRACT")
    print("frozen from the authored dark swatches, before any failure score was consulted\n")

    print("frozen chromatic vocabulary")
    for k, h in zip(CHROM, DARK_VOCAB):
        print("  %-16s %7.1f deg" % (k, h))
    print("\nfrozen relational signature")
    for name, v in REL.items():
        print("  dH_%-3s  %7.1f deg" % (name, v))
    print("  (PR at %.2f deg is the tight violet pair -- Nebula's signature)" % REL["PR"])

    drift = max(dh(a, b) for a, b in zip(live, DARK_VOCAB))
    print("\ncontract drift check: max %.2f deg against the frozen values  ->  %s"
          % (drift, "OK" if drift <= DRIFT_TOL else "DRIFT"))
    if drift > DRIFT_TOL:
        print("  the palette has moved away from the frozen contract; update it deliberately")

    print("\nstructural correspondence (DIAGNOSTIC for Class X, not a gate): "
          "T6 %.0f  W6 %.0f  C %.0f" % structural(dark))

    lines = []
    cur_ok = evaluate(dark, "current dark face", lines)
    print("\nclause-by-clause, current dark face:")
    print("\n".join(lines))

    from nebula_study import aligned_dark
    al = aligned_dark(dark, light)
    lines = []
    cf_ok = evaluate(al, "topology-aligned counterfactual (the case this exists to reject)", lines)
    print("\nclause-by-clause, counterfactual:")
    print("\n".join(lines))

    print("\n=> current %s, counterfactual %s. The counterfactual is refused on the role-anchored"
          % ("PASSES" if cur_ok else "FAILS", "PASSES" if cf_ok else "IS REFUSED"))
    print("   envelope AND on the relational signature -- violet -> green leaves the authored")
    print("   vocabulary. Nebula may transform its topology; it may not replace its colours.")
    return cur_ok and not cf_ok


if __name__ == "__main__":
    ok = main()
    sys.exit(0 if ok else 1)
