"""
Is the dark floor reachable at all?

The measured gap is dark GCC_body 0.027 against a 0.06 floor, and VC_body ~0.02 against 0.040.
Before tuning anything further, this asks the only question that matters: **can sRGB represent
it?**

The chain that bounds it is short:

    VC_body = alpha * (C_plane - C_page)

`alpha` is the pane's, and the §15 glass window caps it at 0.42. `C_plane` cannot exceed the gamut
at the plane's lightness. So the achievable VC has a hard ceiling that can be computed without
rendering anything -- and if the target is above it, the target is the thing that is wrong, not
the implementation.

    python dark_ceiling.py
"""
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from chrome import max_chroma, oklab  # noqa: E402
from nav_options import chroma, rgb  # noqa: E402

# The §15 glass window's ceiling: the pane's alpha cannot legally exceed this.
ALPHA_MAX = 0.42
TARGET = {True: {"VC": 0.025, "GCC": 0.04}, False: {"VC": 0.040, "GCC": 0.06}}


def main():
    faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]

    print("=" * 104)
    print("THE DARK CEILING -- the most visible chroma the glass body can carry")
    print("=" * 104)
    print()
    print(f"  alpha is capped at {ALPHA_MAX} by the §15 glass window; C_plane by the gamut at the")
    print("  plane's lightness. VC_body = alpha * (C_plane - C_page), so both bound it.")
    print()
    print(f"{'face':<16} {'C_page':>7} {'L_page':>7} {'gamut@pageL':>12} {'VC if plane at page L':>22}"
          f" {'VC ceiling':>11} {'target':>7}  reachable")

    for f in faces:
        r = f["roles"]
        light = f["light"]
        page = rgb(r["backgroundBase"])
        cp = chroma(page)
        lp = oklab(page)[0]
        gamut_page = max_chroma(lp, 0)
        # The plane's own lightness is now placed (0.70 / 0.28); the gamut there is what bounds
        # C_plane. Sweep the whole lightness range to find the true ceiling.
        best = 0.0
        best_l = 0.0
        l = 0.02
        while l <= 0.98:
            c = max_chroma(l, 0)
            vc = ALPHA_MAX * (c - cp)
            if vc > best:
                best, best_l = vc, l
            l += 0.005
        t = TARGET[light]["VC"]
        ok = best >= t
        name = f"{f['family']}/{'light' if light else 'dark'}"
        print(f"{name:<16} {cp:>7.4f} {lp:>7.3f} {gamut_page:>12.4f} "
              f"{ALPHA_MAX * (gamut_page - cp):>22.4f} {best:>11.4f} {t:>7.3f}  "
              f"{'yes' if ok else 'NO'} (needs L~{best_l:.2f})")

    print()
    print("  'VC if plane at page L' is what the current design can reach: the plane sits near the")
    print("  page's lightness, where the gamut is smallest. 'VC ceiling' is the best any lightness")
    print("  could do at the legal alpha.")
    print()
    dark = [f for f in faces if not f["light"]]
    light = [f for f in faces if f["light"]]
    for group, label in ((light, "light"), (dark, "dark")):
        need = TARGET[group[0]["light"]]["VC"]
        reach = 0
        for f in group:
            cp = chroma(rgb(f["roles"]["backgroundBase"]))
            best = 0.0
            l = 0.02
            while l <= 0.98:
                best = max(best, ALPHA_MAX * (max_chroma(l, 0) - cp))
                l += 0.005
            reach += best >= need
        print(f"  {label:>5}: {reach}/{len(group)} faces can reach VC {need} at ANY plane lightness "
              f"and the legal alpha")


if __name__ == "__main__":
    main()
