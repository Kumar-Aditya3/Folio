"""Pick the plate's three hues by separation instead of by list order.

`assign.py` sets `guilloche = (A, S1, S2)` on light and `(A, B, S1)` on dark — the *next*
swatches in the sheet, with no requirement that they be far apart in hue. Measured, that
leaves five of eighteen faces with their closest plate pair under 25 degrees, and **four of
those five are dark faces**:

    moonstone light  343 vs 347   4 deg   (two mauves)
    solar     dark    48 vs  52   4 deg   (two oranges)
    ocean     dark   183 vs 195  13 deg   (two teals)
    forest    dark   166 vs 187  21 deg   (two greens)
    nebula    dark   294 vs 317  23 deg   (two violets)

A plate whose hues collapse is a plate in one colour, however good the geometry — which is
the page reading as one colour.

This picks the triple that maximises the *minimum* pairwise hue distance from the face's four
chromatic swatches (the page and the ink are near-neutrals and are already the field), and
renders the plate band both ways.

    python guilloche_pick.py
"""
import contextlib
import io
import itertools
import os
import sys

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

import numpy as np  # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import home_render as HR
    import sheet as S

D = HR.D
OUT = r"D:\projects\Folio\outputs\curation\guilloche-pick.png"
S_SCALE = 2
BG, PANEL = (13, 13, 17), (23, 24, 30)
INK, INK2 = (238, 238, 244), (154, 151, 166)
W, H = 390, 200
FONTS = {}


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C:\Windows\Fonts\%s" % name, int(size * S_SCALE))
    return FONTS[key]


def rgb(c):
    return tuple(int(round(v)) for v in c)


def hx(c):
    return "#%02X%02X%02X" % rgb(c)


def hue(c):
    return D.lch(tuple(v / 255.0 for v in c))[2]


def dh(a, b):
    x = (a - b) % 360.0
    return x - 360.0 if x > 180.0 else x


def chromatic(fam, light):
    """The four swatches that are not the page and not the ink."""
    sw = [AS.hx(h) for h in S.SHEET[fam]["light" if light else "dark"]]
    return sw[2:] if light else sw[1:5]      # light: sig/sup1/sup2/sup3; dark: A/B/sup1/sup2


def best_triple(fam, light):
    cands = chromatic(fam, light)
    best, score = None, -1.0
    for t in itertools.combinations(range(len(cands)), 3):
        hs = [hue(cands[i]) for i in t]
        w = min(abs(dh(hs[0], hs[1])), abs(dh(hs[1], hs[2])), abs(dh(hs[0], hs[2])))
        if w > score:
            best, score = t, w
    return [cands[i] for i in best], score


CURRENT_KEYS = ("guillochePrimary", "guillocheSecondary", "guillocheHighlight")


def plate_band(roles, dark, tiers, W=W, H=H):
    cov, shares = HR.plate_layers("x", W, H)
    a_max = 0.48 * (1.0 if dark else 1.35)
    lift = np.clip(cov * a_max * (0.95 if dark else 0.34), 0, 1)
    field = np.zeros((H, W, 3), float) + np.array(roles["backgroundBase"], float)
    if dark:
        tiers = [HR.mix(t, (255, 255, 255), 0.30) for t in tiers]
    acc = sum(np.array(tiers[i], float)[None, None, :] * shares[i][..., None] for i in range(3))
    acc = np.zeros((H, W, 3), float) + acc
    img = field * (1 - lift)[..., None] + acc * lift[..., None]
    return np.clip(img, 0, 255).astype(np.uint8)


def main():
    rows = []
    print("the plate's three hues: as assigned (list order) vs picked for separation")
    print("%-11s %-5s | %-6s %s | %-6s %s" % (
        "family", "face", "now", "hues", "picked", "hues"))
    for fam in S.SHEET:
        for light in (True, False):
            r = AS.face(fam, light)
            cur = [r[k] for k in CURRENT_KEYS]
            cur_h = [hue(c) for c in cur]
            cur_w = min(abs(dh(cur_h[0], cur_h[1])), abs(dh(cur_h[1], cur_h[2])),
                        abs(dh(cur_h[0], cur_h[2])))
            new, new_w = best_triple(fam, light)
            new_h = [hue(c) for c in new]
            print("%-11s %-5s | %-6.0f %s | %-6.0f %s" % (
                fam, "light" if light else "dark", cur_w,
                " ".join("%3.0f" % h for h in cur_h), new_w,
                " ".join("%3.0f" % h for h in new_h)))
            rows.append((fam, light, r, cur, new, cur_w, new_w))

    bad = [x for x in rows if x[5] < 30]
    bad.sort(key=lambda x: x[5])
    bad = bad[:5]
    cell_w = W + 16
    Wt = 250 + len(bad) * cell_w
    Ht = 92 + 2 * (H + 40)
    im = Image.new("RGB", (Wt * S_SCALE, Ht * S_SCALE), BG)
    d = ImageDraw.Draw(im)
    d.text((24 * S_SCALE, 18 * S_SCALE), "The plate's three hues, chosen for separation",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((24 * S_SCALE, 41 * S_SCALE),
           "Five faces had two plate hues within 25 deg — four of them dark, which is why dark "
           "reads as one colour.", font=font("segoeui.ttf", 11), fill=INK2)
    for ci, (fam, light, r, cur, new, cw, nw) in enumerate(bad):
        x0 = 250 + ci * cell_w
        d.text((x0 * S_SCALE, 66 * S_SCALE), "%s — %s" % (fam, "light" if light else "dark"),
               font=font("segoeuib.ttf", 11), fill=INK)
        for ri, (tiers, w, tag) in enumerate(((cur, cw, "as assigned"), (new, nw, "picked"))):
            y = 92 + ri * (H + 40)
            d.rectangle([(x0 - 6) * S_SCALE, (y - 6) * S_SCALE,
                         (x0 + W + 6) * S_SCALE, (y + H + 34) * S_SCALE], fill=PANEL)
            arr = plate_band(r, not light, tiers)
            im.paste(Image.fromarray(arr).resize((W * S_SCALE, H * S_SCALE), Image.NEAREST),
                     (x0 * S_SCALE, y * S_SCALE))
            sw = 0.0
            for i, t in enumerate(tiers):
                d.rectangle([(x0 + i * 34) * S_SCALE, (y + H + 6) * S_SCALE,
                             (x0 + i * 34 + 30) * S_SCALE, (y + H + 26) * S_SCALE],
                            fill=rgb(t))
            d.text(((x0 + 108) * S_SCALE, (y + H + 9) * S_SCALE), "%s · closest pair %.0f deg"
                   % (tag, w), font=font("segoeui.ttf", 10), fill=INK2)
    im = im.resize((Wt, Ht), Image.LANCZOS)
    im.save(OUT)
    print("\nsaved", OUT, im.size)


if __name__ == "__main__":
    main()
