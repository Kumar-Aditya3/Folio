"""Nebula: a counterfactual, not a repair.

Nebula is the last unresolved theme (`T6 58, W6 7, C 4`), but its problem is qualitatively different
from Forest's. Forest had a demonstrable erroneous mapping (15.8 -> 105.4 deg) that could be
corrected while restoring the light face's relationships. Nebula has no such obvious defect.

So this does **not** apply the repair. It builds the topology-aligned counterfactual, measures both,
and renders both side by side, so the question can be asked concretely:

    does restoring structural correspondence make Nebula better,
    or does it destroy what makes Nebula look like Nebula?

The gates are deliberately NOT changed for Nebula. Deciding the theme class comes first.
"""
import contextlib
import io
import itertools
import os
import sys

sys.path.insert(0, r"D:/projects/Folio/outputs/curation")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp/curate")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp")
os.chdir(r"D:/projects/Folio/.workbuddy-ai/tmp")

import topology as TP  # noqa: E402  -- the canonical T6/W6; one definition, imported

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import design_chroma_tiers as D
    import sheet as S
    import sheet_proto as SP

KEYS = ("primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent")
AUDIT_KEYS = ("backgroundBase", "surface", "primaryAccent", "secondaryAccent",
              "tertiaryAccent", "warmAccent")


def lch(c):
    return AS._lch255(c)


def dh(a, b):
    x = abs(a - b) % 360.0
    return min(x, 360.0 - x)


def metrics(face):
    """Hues and chromas on the six authored keys."""
    hs = [lch(face[k])[2] for k in AUDIT_KEYS]
    cs = [lch(face[k])[1] for k in AUDIT_KEYS]
    return hs, cs


def compare(light, dark):
    """**The canonical T6/W6.** Imported from `topology.py`, not reimplemented.

    This function used to carry its own copies, and they disagreed with `identity_audit`'s on every
    family (nebula 21 vs 58, arctic 64 vs 90). The cause was that this version compared the pairwise
    distances POSITIONALLY while the audit SORTS them -- and sorting is the correct topology measure,
    because the relational signature is the distance *multiset* and the labels are exactly what a
    topology measure should be blind to. Two definitions of one name is a bug that reports plausible
    numbers, so the definitions now live in one module.
    """
    lh, lc = metrics(light)
    dhh, dcc = metrics(dark)
    return TP.T6(lh, dhh), TP.W6(lh, dhh, lc, dcc)


def aligned_dark(face, light):
    """The same machinery that repaired Forest, applied to Nebula's dark chromatic swatches."""
    out = dict(face)
    chrom = [face[k] for k in KEYS]
    lc = sorted([light[k] for k in KEYS], key=lambda c: -lch(c)[1])
    lh = [lch(c)[2] for c in lc]
    order = sorted(range(len(chrom)), key=lambda i: -lch(chrom[i])[1])
    for rank, idx in enumerate(order):
        cur = lch(chrom[idx])[2]
        delta = (lh[rank] - cur + 180.0) % 360.0 - 180.0
        if abs(delta) > 1.0:
            out[KEYS[idx]] = AS.rotate(chrom[idx], delta)
    return out


def render(face, pid):
    """Home for a hypothetical face: patch the roles the renderer reads."""
    dark, roles = SP.roles_for(pid)
    m = dict(roles)
    m["primary"] = face["primaryAccent"]
    m["secondary"] = face["secondaryAccent"]
    m["tertiary"] = face["tertiaryAccent"]
    m["warmAccent"] = face["warmAccent"]
    m["navigationSurface"] = AS.mix(face["surface"], face["backgroundSecondary"], 0.35)
    return SP.render(pid, 2, roles=m)


def main():
    light = AS.face("nebula", True)
    dark = AS.face("nebula", False)
    al = aligned_dark(dark, light)

    t0, w0 = compare(light, dark)
    t1, w1 = compare(light, al)
    print("Nebula, counterfactual (T6/W6 on the six authored keys)")
    print("  current      T6 %.0f   W6 %.0f" % (t0, w0))
    print("  aligned      T6 %.0f   W6 %.0f" % (t1, w1))
    print()
    print("  dark chromatic hues, current -> aligned")
    for k in KEYS:
        print("    %-16s %6.1f -> %6.1f" % (k, lch(dark[k])[2], lch(al[k])[2]))
    print()
    print("  NOT APPLIED. This is a design question, not a repair.")
    return dark, al


if __name__ == "__main__":
    main()
