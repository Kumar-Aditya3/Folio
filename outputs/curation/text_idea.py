"""Is "take a palette colour and brighten/darken it for text" different from what we do?

`accentProgress = fit(primaryAccent, surface, 4.5)` — and `assign.fit` pushes the colour
toward the page's ink (black on a light face, white on a dark one) in 5% steps until it
clears AA. So the idea is already implemented. The question this answers is whether the
*mix-toward-ink* form is the best version of it, or whether re-*placing* the colour at a
usable lightness is better.

The difference is chroma. Mixing toward black or white is the one operation that removes
chroma — the same reason the notes give for the rim reading as a grey edge highlight. A
placement (`tier_colour`) holds hue and chroma and moves lightness only.

So, per face: the fitted accent's contrast and chroma against the page, versus the most
chromatic placement of the *same hue* that clears the same floor.

    python text_idea.py
"""
import contextlib
import io
import os
import sys

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import home_render as HR
    import sheet as S

D, L, O = HR.D, HR.L, HR.O
FLOOR = 4.5


def c01(c):
    return tuple(v / 255.0 for v in c)


def c255(c):
    return tuple(v * 255.0 for v in c)


def chroma(c):
    """Oklab chroma, 0-1, via the harness's own accessor.

    `O.ochroma` takes 0-255 (it is what `home_render.params_for` calls). `D.lch` is *not*
    this accessor — it returns a mixed scale, which is what made the first run of this
    script report a fully saturated black.
    """
    return O.ochroma(c if max(c) > 1 else c255(c))


def hue(c):
    return D.lch(c if max(c) <= 1 else c01(c))[2]


def best_placement(page, sig, floor=FLOOR):
    """The most chromatic placement of `sig`'s hue that clears `floor` against the page.

    Scans gamma luminance and Oklab chroma, and scores the **achieved** chroma of the
    returned colour — not the requested one, because at L 0.0 the gamut returns black for
    any request and a requested-chroma metric would report a fully saturated black.
    """
    pg = c01(page)
    h = hue(sig)
    best = None
    for i in range(101):
        lum = i / 100.0
        for j in range(0, 41):
            ch = j / 100.0
            col, _ = D.tier_colour(lum, h, ch)
            if not all(0.0 <= v <= 1.0 for v in col):
                break                      # out of gamut at this chroma; stop climbing
            achieved = D.lch(col)[1]
            k = L.contrast(col, pg)
            if k >= floor and (best is None or achieved > best[2]):
                best = (tuple(v * 255 for v in col), k, achieved, lum)
    return best


def main():
    print("wordmark ink: the mix-toward-ink fit vs the best same-hue placement (floor %.1f:1)" % FLOOR)
    print("%-11s %-5s  %-7s %-7s | %-7s %-7s %-7s | %s" % (
        "family", "face", "fit K", "fit C", "plc K", "plc C", "plc L", "gain"))
    gains = []
    for fam in S.SHEET:
        for light in (True, False):
            r = AS.face(fam, light)
            page = r["backgroundBase"]
            sig = r["primaryAccent"]
            fitted = AS.fit(sig, r["surface"], FLOOR)
            k_fit = L.contrast(c01(fitted), c01(page))
            c_fit = chroma(fitted)
            b = best_placement(page, sig)
            if b is None:
                print("%-11s %-5s  %-7.2f %-7.3f | %-7s %-7s %-7s | %s" % (
                    fam, "light" if light else "dark", k_fit, c_fit,
                    "-", "-", "-", "hue cannot clear the floor"))
                gains.append((0.0, fam, light))
                continue
            col, k_p, c_p, lum = b
            gain = (c_p / c_fit) if c_fit > 1e-6 else float("inf")
            gains.append((gain, fam, light))
            print("%-11s %-5s  %-7.2f %-7.3f | %-7.2f %-7.3f %-7.2f | %.1fx" % (
                fam, "light" if light else "dark", k_fit, c_fit, k_p, c_p, lum, gain))
    gains.sort()
    real = [g for g in gains if g[0] > 0]
    print("\nchroma gain of placing over mixing: min %.1fx  median %.1fx  max %.1fx"
          % (real[0][0], real[len(real) // 2][0], real[-1][0]))
    print("worst three: %s" % ", ".join("%s/%s %.1fx" % (f, "L" if l else "D", g)
                                        for g, f, l in real[:3]))


if __name__ == "__main__":
    main()
