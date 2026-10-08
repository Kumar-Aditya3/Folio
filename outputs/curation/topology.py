"""The canonical topology measures -- ONE definition, imported everywhere.

There were two implementations both called `T6`, and they disagreed on **every family**:

    family     identity_audit (sorted)     nebula_study (positional)
    nebula            58.0                       21.0
    forest            90.4                       77.6
    ocean             81.0                       62.9
    arctic            90.4                       64.4

The cause is one line: `identity_audit.pair_dists` **sorts** the 15 pairwise distances before
comparing, while `nebula_study.pair_d` compares them **positionally**, tied to the order of the key
list. Sorting is the correct topology measure, because a palette's relational signature is the
*multiset* of pairwise distances -- the labels (which key is which) are exactly what a topology
measure should be blind to. The positional form silently changes if you reorder the keys, which makes
it a different quantity wearing the same name.

This is the second time a name collision has produced two different numbers for one metric (the first
was T4 vs T6). So the definitions now live in one place and everything imports them.

`W6` is deliberately NOT sorted: the warm/cool comparison is per-pair, and pair k of the light face
corresponds to pair k of the dark face because both are indexed by the same key order. Sorting it
would destroy the correspondence the measure depends on.
"""
import itertools


def dh(a, b):
    x = abs(a - b) % 360.0
    return min(x, 360.0 - x)


def pair_dists(hues):
    """The 15 pairwise hue distances, sorted -- label-free."""
    return sorted(dh(a, b) for a, b in itertools.combinations(hues, 2))


def wc_order(hues, chromas):
    """Per-pair warm/cool ordering, chroma-weighted. Positional on purpose (see module docstring)."""
    vals = []
    for h, ch in zip(hues, chromas):
        w = 1.0 if (h < 90 or h > 300) else (-1.0 if 150 <= h <= 270 else 0.0)
        vals.append(w * ch)
    return [1 if a > b else (-1 if a < b else 0)
            for a, b in itertools.combinations(vals, 2)]


def T6(hues_L, hues_D):
    """Hue topology, 0-100, 100 = identical distance multiset."""
    dl, dd = pair_dists(hues_L), pair_dists(hues_D)
    return 100.0 - sum(abs(a - b) for a, b in zip(dl, dd)) / len(dl)


def W6(hues_L, hues_D, chromas_L, chromas_D):
    """Warm/cool ordering agreement, 0-100."""
    ol, od = wc_order(hues_L, chromas_L), wc_order(hues_D, chromas_D)
    return 100.0 * sum(1 for a, b in zip(ol, od) if a == b) / max(1, len(ol))


def T4(hues_L, hues_D):
    """The four-chromatic-swatch diagnostic. Same definition, smaller input -- NOT interchangeable
    with T6, because `M4(P) != M6(P)`. Both independently located the Forest defect; their absolute
    scores never were."""
    return T6(hues_L, hues_D)


def W4(hues_L, hues_D, chromas_L, chromas_D):
    return W6(hues_L, hues_D, chromas_L, chromas_D)
