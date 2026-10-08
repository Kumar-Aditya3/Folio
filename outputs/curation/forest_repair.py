"""Forest topology repair: extract the matrices, find the offending relationships, correct minimally.

Forest now has `C = 88` and `S = 100`, so the remaining `T = 63, W = 27` cannot be a chroma or
anchor problem. It is specifically that the dark face's hue *relationships* do not reproduce the
light face's.

The chroma repair already aligned the swatches **by rank**, which gives the correspondence needed to
compare the two faces at all -- without it, six unordered swatches on each side have no pairing.

    D^L_ij, D^D_ij    pairwise hue distances, 15 each
    R^L_ij, R^D_ij    pairwise warm/cool orderings
    E_T = mean |D^L - D^D|

Then the correction is the minimum one: rotate the fewest dark swatches by the smallest angles that
restore T and W, rather than regenerating Forest.
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
    import sheet as S

KEYS = ("primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent")


def lch(c):
    return AS._lch255(c)


def dh(a, b):
    x = abs(a - b) % 360.0
    return min(x, 360.0 - x)


def sorted_hues(face):
    """The four chromatic swatches sorted by chroma -- the rank order the chroma repair aligned."""
    cs = [face[k] for k in KEYS]
    return [lch(c)[2] for c in sorted(cs, key=lambda c: -lch(c)[1])]


def dists(hs):
    return [dh(a, b) for a, b in itertools.combinations(hs, 2)]


def wc(h):
    return 1 if (h < 90 or h > 300) else (-1 if 150 <= h <= 270 else 0)


def wc_rel(hs):
    return [1 if wc(a) > wc(b) else (-1 if wc(a) < wc(b) else 0)
            for a, b in itertools.combinations(hs, 2)]


def T_of(lh, dh_):
    dl, dd = dists(lh), dists(dh_)
    return 100.0 - sum(abs(a - b) for a, b in zip(dl, dd)) / len(dl)


def W_of(lh, dh_):
    rl, rd = wc_rel(lh), wc_rel(dh_)
    return 100.0 * sum(1 for a, b in zip(rl, rd) if a == b) / len(rl)


def main():
    fl = AS.face("forest", True)
    fd = AS.face("forest", False)
    lh = sorted_hues(fl)
    dh_ = sorted_hues(fd)

    print("Forest, chromatic swatches by chroma rank")
    print("  light hues: " + "  ".join("%6.1f" % h for h in lh))
    print("  dark  hues: " + "  ".join("%6.1f" % h for h in dh_))
    print("  per-rank delta: " + "  ".join("%6.1f" % (a - b) for a, b in zip(lh, dh_)))
    print()
    print("pairwise hue distances (light vs dark)")
    dl, dd = dists(lh), dists(dh_)
    for k, (a, b) in enumerate(zip(dl, dd)):
        flag = "   <-- offending" if abs(a - b) > 20 else ""
        print("  pair %2d   light %5.1f   dark %5.1f   diff %5.1f%s" % (k, a, b, abs(a - b), flag))
    print("  E_T = %.1f deg mean" % (sum(abs(a - b) for a, b in zip(dl, dd)) / len(dl)))
    print()
    print("warm/cool relation (light vs dark)")
    rl, rd = wc_rel(lh), wc_rel(dh_)
    print("  light: " + " ".join("%+d" % r for r in rl))
    print("  dark:  " + " ".join("%+d" % r for r in rd))
    print("  disagreements: %d of %d" % (sum(1 for a, b in zip(rl, rd) if a != b), len(rl)))
    print()
    print("current  T = %.0f   W = %.0f" % (T_of(lh, dh_), W_of(lh, dh_)))

    # ── the minimal correction: rotate the fewest swatches by the smallest angles ──
    deltas = [a - b for a, b in zip(lh, dh_)]
    order = sorted(range(4), key=lambda i: abs(deltas[i]))
    cur = list(dh_)
    applied = []
    print("\ngreedy minimal correction (smallest angle first, stop when T and W clear):")
    for i in order:
        cur[i] = lh[i]                      # rotate this swatch by its own delta
        applied.append((i, deltas[i]))
        t, w = T_of(lh, cur), W_of(lh, cur)
        print("  rotate rank %d by %+6.1f deg  ->  T %.0f  W %.0f%s"
              % (i, deltas[i], t, w, "   OK" if (t >= 70 and w >= 40) else ""))
        if t >= 70 and w >= 40:
            break
    print("\napplied %d of 4 swatches: %s" % (
        len(applied), ", ".join("rank %d %+.0f deg" % a for a in applied)))


if __name__ == "__main__":
    main()
