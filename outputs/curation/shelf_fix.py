"""What a shelf tile actually looks like, and where the theme colour can live on it.

The problem this answers: `assign.py` gives a face's `sup1` swatch to `tertiaryAccent`,
which the prototype draws as the **tile fill**. But a tile is a `FolioCoverPlate` ->
`BookCover`, i.e. a real cover thumbnail, so a themed fill is only ever seen in the
fallback state. `home_render.py` fakes the tile as `mix(tertiary, black, ...)` — it paints
a cover *out of the theme*, which is why the shelves looked so harmonious. Arbitrary cover
art will not.

Four treatments of the same four tiles, on one light and one dark face:

  1 as prototyped    tile fill = the theme swatch
  2 spine, as drawn  artwork + the swatch on the spine at `FolioCoverPlate`'s 5.5%, darkened
  3 spine, committed artwork + the same spine at full strength
  4 coverless        the fallback cover, drawn from the face's own swatches

    python shelf_fix.py
"""
import colorsys
import os
import sys

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

import assign as AS  # noqa: E402

S = 3                                    # supersample, then downscale: crisp text at 1x
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shelf-fix.png")

BG = (13, 13, 17)
PANEL = (23, 24, 30)
INK = (238, 238, 244)
INK2 = (154, 151, 166)
INK3 = (104, 107, 120)

FACES = [("ocean", True), ("ocean", False), ("solar", False)]
LABEL = {("ocean", True): "Lagoon — light", ("ocean", False): "Abyss — dark",
         ("solar", False): "Ember — dark"}

TILE_W, TILE_H, GAP = 132, 198, 14
GUTTER, MARGIN, ROW_H = 232, 24, TILE_H + 54
SPINE_FRAC = 0.055                       # what FolioCoverPlate actually draws

FONTS = {}


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C:\Windows\Fonts\%s" % name, int(size * S))
    return FONTS[key]


def rgb(c):
    return tuple(int(round(v)) for v in c)


def mix(a, b, t):
    return AS.mix(a, b, t)


def art(i, fi, dark):
    """A muted stand-in for arbitrary cover artwork — deliberately NOT from the theme."""
    h = (i * 97 + fi * 53) % 360
    lo, hi = (0.30, 0.56) if dark else (0.52, 0.78)
    a = colorsys.hsv_to_rgb(h / 360.0, 0.20, hi)
    b = colorsys.hsv_to_rgb(((h + 26) % 360) / 360.0, 0.30, lo)
    return [c * 255 for c in a], [c * 255 for c in b]


def rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([c * S for c in box], radius=r * S,
                        fill=rgb(fill) if fill else None,
                        outline=rgb(outline) if outline else None,
                        width=max(1, int(width * S)))


def plate(d, x, y, roles, i, fi, dark, art_face=True, spine=None):
    if art_face:
        a, b = art(i, fi, dark)
        for k in range(TILE_H):
            rr(d, [x, y + k, x + TILE_W, y + k + 1], 0, fill=mix(a, b, k / float(TILE_H)))
    if spine is not None:
        sw = max(4.0, TILE_W * SPINE_FRAC)
        for k in range(int(sw)):
            rr(d, [x + k, y, x + k + 1, y + TILE_H], 0, fill=spine(k))
    rr(d, [x, y, x + TILE_W, y + TILE_H], 4, outline=mix(roles["surface"], (0, 0, 0), 0.30))
    # the progress seam — always drawn, already themed
    rr(d, [x, y + TILE_H - 4, x + TILE_W * 0.45, y + TILE_H], 0, fill=roles["progress"])


def t_proto(d, x, y, r, i, fi, dark):
    plate(d, x, y, r, i, fi, dark, art_face=False)
    rr(d, [x, y, x + TILE_W, y + TILE_H], 4,
       fill=mix(r["tertiaryAccent"], (0, 0, 0), 0.25 + 0.13 * i))


def t_spine_drawn(d, x, y, r, i, fi, dark):
    plate(d, x, y, r, i, fi, dark,
          spine=lambda k: mix(r["tertiaryAccent"], (0, 0, 0), 0.05 + 0.16 * k))


def t_spine_full(d, x, y, r, i, fi, dark):
    plate(d, x, y, r, i, fi, dark, spine=lambda k: r["tertiaryAccent"])


def t_fallback(d, x, y, r, i, fi, dark):
    plate(d, x, y, r, i, fi, dark, art_face=False)
    a, b = r["primaryAccent"], r["secondaryAccent"]
    for k in range(TILE_H):
        rr(d, [x, y + k, x + TILE_W, y + k + 1], 0, fill=mix(a, b, k / float(TILE_H)))


ROWS = [
    ("1  as prototyped", "tile fill = the theme swatch",
     "invisible: a real thumbnail sits on top", t_proto),
    ("2  spine, as drawn", "artwork + swatch on the spine",
     "5.5% wide, darkened — the current treatment", t_spine_drawn),
    ("3  spine, committed", "same spine, full strength",
     "reads as a series without fighting the art", t_spine_full),
    ("4  coverless", "themed fallback cover",
     "hardcoded indigo today, not the theme", t_fallback),
]


def main():
    cell_w = 4 * TILE_W + 3 * GAP
    W = MARGIN * 2 + GUTTER + len(FACES) * cell_w + (len(FACES) - 1) * 22
    H = MARGIN * 2 + 74 + len(ROWS) * ROW_H
    im = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(im)

    d.text((MARGIN * S, 15 * S), "Shelf tiles — where the theme colour can live",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((MARGIN * S, 38 * S),
           "A tile is a book thumbnail, so a themed fill is invisible. The spine is not.",
           font=font("segoeui.ttf", 11), fill=INK2)

    for fi, (fam, light) in enumerate(FACES):
        roles = AS.face(fam, light)
        dark = not light
        x0 = MARGIN + GUTTER + fi * (cell_w + 22)
        d.text((x0 * S, 60 * S), LABEL[(fam, light)], font=font("segoeuib.ttf", 12), fill=INK)
        for ri, (title, sub, note, fn) in enumerate(ROWS):
            y = 82 + ri * ROW_H
            rr(d, [x0 - 10, y - 10, x0 + cell_w + 8, y + TILE_H + 12], 9, fill=PANEL)
            if fi == 0:
                d.text((MARGIN * S, (y + 2) * S), title, font=font("segoeuib.ttf", 12), fill=INK)
                d.text((MARGIN * S, (y + 22) * S), sub, font=font("segoeui.ttf", 10.5), fill=INK2)
                d.text((MARGIN * S, (y + 40) * S), note, font=font("segoeui.ttf", 9.5), fill=INK3)
            for i in range(4):
                fn(d, x0 + i * (TILE_W + GAP), y, roles, i, fi, dark)

    im = im.resize((W, H), Image.LANCZOS)
    im.save(OUT)
    print("saved %s  %sx%s" % (OUT, W, H))


if __name__ == "__main__":
    main()
