"""The plate's colour: one hue (what the renderer drew) versus three (what the app draws).

`GuillocheShader.android.kt` blends the plate's colour per pixel from the palette's three
authored guilloche hues, weighted by each layer's own intensity. `home_render.py` drew it in
one colour, which is why the dark faces read as a single colour here when the app does not.

This renders the plate band both ways over each face's own field, at 1:1, so the difference
is visible rather than argued about.

    python plate_hues.py
"""
import contextlib
import io
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

OUT = r"D:\projects\Folio\outputs\curation\plate-hues.png"
S_SCALE = 2
BG, PANEL = (13, 13, 17), (23, 24, 30)
INK, INK2, INK3 = (238, 238, 244), (154, 151, 166), (104, 107, 120)
W, H = 390, 250
FONTS = {}
KEYS = ("guillochePrimary", "guillocheSecondary", "guillocheHighlight")

FACES = [("ocean", False), ("solar", False), ("jade", False),
         ("ocean", True), ("mintcoral", True)]
LABEL = {("ocean", False): "Abyss — dark", ("solar", False): "Ember — dark",
         ("jade", False): "Garnet — dark", ("ocean", True): "Lagoon — light",
         ("mintcoral", True): "Sakura — light"}


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C:\Windows\Fonts\%s" % name, int(size * S_SCALE))
    return FONTS[key]


def rgb(c):
    return tuple(int(round(v)) for v in c)


def band(roles, dark):
    """Four renderings of the plate over this face's own field.

    0 one hue          — what the renderer drew before
    1 three tiers      — what the app draws (alpha unchanged)
    2 three tiers, x1.5— the plate strengthened
    3 three tiers, x1.5, unpinned — and on light, no luma pinning either
    """
    cov, shares = HR.plate_layers("x", W, H)          # pack geometry; colour is what varies
    # forced to (H, W, 3): letting numpy broadcast a (1,1,3) against a (H,W,1) produced a
    # 5-D array that PIL refuses.
    field = np.zeros((H, W, 3), float) + np.array(roles["backgroundBase"], float)
    if dark:
        tiers = [HR.mix(roles[k], (255, 255, 255), 0.30) for k in KEYS]
        single = HR.mix(roles["guillochePrimary"], (255, 255, 255), 0.30)
    else:
        tiers = [roles[k] for k in KEYS]
        single = roles["guillocheSecondary"]
    acc = sum(np.array(tiers[i], float)[None, None, :] * shares[i][..., None] for i in range(3))
    out = []
    for i, (boost, unpin) in enumerate(((1.0, False), (1.0, False), (1.5, False), (1.5, True))):
        a_max = 0.48 * (1.0 if dark else 1.35) * boost
        lift = np.clip(cov * a_max * (0.95 if dark else 0.34), 0, 1)
        if not dark and unpin:
            # the light branch pins the line's *luminance* to the field, so only chroma can
            # vary — which is why the light plate measures near-achromatic. Unpinned, the
            # line is allowed to move lightness too, and it reads as drawn rather than grey.
            lift = np.clip(cov * a_max * 0.55, 0, 1)
        base = single if i == 0 else acc
        base_arr = np.zeros((H, W, 3), float) + np.array(base, float)
        img = field * (1 - lift)[..., None] + base_arr * lift[..., None]
        out.append(np.clip(img, 0, 255).astype(np.uint8))
    return out


ROWS = ["one hue — what I drew", "three tiers — what the app draws",
        "three tiers, plate x1.5", "x1.5 and unpinned (light)"]


def spread(arr):
    """How many distinct hues the band actually carries, and how far they reach."""
    px = arr.reshape(-1, 3).astype(float) / 255.0
    mx, mn = px.max(axis=1), px.min(axis=1)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)
    keep = sat > 0.06
    if keep.sum() < 50:
        return 0, 0.0
    import colorsys
    hues = np.array([colorsys.rgb_to_hsv(*p)[0] * 360.0 for p in px[keep][::37]])
    hues.sort()
    if len(hues) < 2:
        return 0, 0.0
    d = np.diff(np.concatenate([hues, hues[:1] + 360.0]))
    return len(hues), 360.0 - d.max()


def main():
    print("%-18s %-30s %s" % ("face", "plate", "hue span of the coloured pixels"))
    for fam, light in FACES:
        r = AS.face(fam, light)
        for name, arr in zip(ROWS, band(r, not light)):
            n, span = spread(arr)
            print("%-18s %-30s %5.1f deg  (%d samples)" % (LABEL[(fam, light)], name, span, n))

    cell_w = W + 16
    Wt = 250 + len(FACES) * cell_w
    Ht = 92 + len(ROWS) * (H + 34)
    im = Image.new("RGB", (Wt * S_SCALE, Ht * S_SCALE), BG)
    d = ImageDraw.Draw(im)
    d.text((24 * S_SCALE, 18 * S_SCALE), "The plate's colour: one hue vs the three the app draws",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((24 * S_SCALE, 41 * S_SCALE),
           "The shader blends the palette's three authored guilloche hues per pixel; the "
           "renderer drew one, and the light branch pins the line's luminance.",
           font=font("segoeui.ttf", 11), fill=INK2)
    for ci, (fam, light) in enumerate(FACES):
        r = AS.face(fam, light)
        bands = band(r, not light)
        x0 = 250 + ci * cell_w
        d.text((x0 * S_SCALE, 68 * S_SCALE), LABEL[(fam, light)],
               font=font("segoeuib.ttf", 11), fill=INK)
        for ri, arr in enumerate(bands):
            y = 92 + ri * (H + 34)
            d.rectangle([(x0 - 6) * S_SCALE, (y - 6) * S_SCALE,
                         (x0 + W + 6) * S_SCALE, (y + H + 6) * S_SCALE],
                        fill=PANEL)
            tile = Image.fromarray(arr).resize((W * S_SCALE, H * S_SCALE), Image.NEAREST)
            im.paste(tile, (x0 * S_SCALE, y * S_SCALE))
            if ci == 0:
                d.text((24 * S_SCALE, (y + 100) * S_SCALE), ROWS[ri],
                       font=font("segoeuib.ttf", 11.5), fill=INK)
    im = im.resize((Wt, Ht), Image.LANCZOS)
    im.save(OUT)
    print("\nsaved", OUT, im.size)


if __name__ == "__main__":
    main()
