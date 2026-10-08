"""The masthead as a *committed* colour, not a whisper.

The current crown is a derived scrim at 7-10/255 over the page — measured, it barely moves
the page — and the wordmark wears `accentProgress`, which is the signature pushed toward the
ink, i.e. deliberately muted. The user's read is right: it looks like the page.

So this treats the crown as an authored band instead, which is the classic editorial
masthead: a solid colour with the name reversed out. Four treatments, and for each the
wordmark's ink is *chosen* rather than assumed — the best of the face's own ink, the page,
white and black, measured against the band.

Large text (a 27px semibold wordmark) needs 3:1; body needs 4.5:1. Both are reported.

    python masthead_crown.py
"""
import contextlib
import io
import os
import sys

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import home_render as HR
    import sheet as S

D, L = HR.D, HR.L
S_SCALE = 2
OUT = r"D:\projects\Folio\outputs\curation\masthead-crown.png"

BG, PANEL = (13, 13, 17), (23, 24, 30)
INK, INK2, INK3 = (238, 238, 244), (154, 151, 166), (104, 107, 120)
BAND_H = 78                      # the crown's height in the strip
STRIP_W, STRIP_H = 300, 150

FONTS = {}


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C:\Windows\Fonts\%s" % name, int(size * S_SCALE))
    return FONTS[key]


def c01(c):
    return tuple(v / 255.0 for v in c)


def over(fg, alpha, bg):
    return tuple(fg[i] * alpha + bg[i] * (1 - alpha) for i in range(3))


def to255(c):
    return tuple(v * 255.0 for v in c)


def hx(c):
    return "#%02X%02X%02X" % tuple(int(round(v)) for v in c)


def rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([c * S_SCALE for c in box], radius=r * S_SCALE,
                        fill=tuple(int(round(v)) for v in fill) if fill else None,
                        outline=tuple(int(round(v)) for v in outline) if outline else None,
                        width=max(1, int(width * S_SCALE)))


def ink_for(band, roles):
    """The wordmark's ink on a band: the best of the face's own ink, the page, white, black."""
    cands = {
        "ink": roles["textPrimary"], "page": roles["backgroundBase"],
        "white": (255, 255, 255), "black": (0, 0, 0),
    }
    b = c01(band)
    scored = [(L.contrast(c01(c), b), name, c) for name, c in cands.items()]
    scored.sort(reverse=True)
    return scored[0]


TREATMENTS = [
    ("today", "as it is now", "derived scrim band + fitted accent name"),
    ("solid", "solid signature crown", "primaryAccent, name reversed out"),
    ("fade", "signature crown, fading", "primaryAccent into the page"),
    ("warm", "warm crown", "warmAccent — the §30 warm note"),
]

FACES = [("ocean", True), ("ocean", False), ("solar", False)]
LABEL = {("ocean", True): "Lagoon — light", ("ocean", False): "Abyss — dark",
         ("solar", False): "Ember — dark"}


def draw(d, x, y, roles, dark, key):
    """All maths in 0-1 (the harness's scale); every `fill=` converts once, at the end."""
    page = roles["backgroundBase"]
    if key == "today":
        scrim, alpha = SP_bar_scrim(roles, dark)
        for k in range(BAND_H):
            t = k / float(BAND_H)
            rr(d, [x, y + k, x + STRIP_W, y + k + 1], 0,
               fill=to255(over(scrim, alpha * (1.0 - t) ** 1.6, c01(page))))
        band_used = to255(over(scrim, alpha * 0.5, c01(page)))
        name_ink = roles["accentProgress"]
    else:
        band = roles["primaryAccent"] if key != "warm" else roles["warmAccent"]
        if key == "fade":
            for k in range(BAND_H):
                t = k / float(BAND_H)
                rr(d, [x, y + k, x + STRIP_W, y + k + 1], 0,
                   fill=to255(over(c01(band), (1.0 - t) ** 1.3, c01(page))))
        else:
            rr(d, [x, y, x + STRIP_W, y + BAND_H], 0, fill=band)
        band_used = to255(c01(band))
        _, _, name_ink = ink_for(band, roles)
    rr(d, [x, y + BAND_H, x + STRIP_W, y + STRIP_H], 0, fill=page)
    d.text(((x + 20) * S_SCALE, (y + BAND_H - 44) * S_SCALE), "Folio",
           font=font("georgiab.ttf", 27), fill=tuple(int(round(v)) for v in name_ink))
    return band_used, name_ink


# `sheet_proto.bar_scrim` is the derived band; imported lazily so this module stays standalone.
# It reads the *renderer's* role names, while `AS.face` returns the face's field names, so the
# two that differ are mapped across.
def SP_bar_scrim(roles, dark):
    import sheet_proto
    mapped = dict(roles)
    mapped["background"] = roles["backgroundBase"]
    mapped["primary"] = roles["primaryAccent"]
    return sheet_proto.bar_scrim(mapped, dark)


def main():
    print("the crown: band colour, chosen name ink, and its contrast")
    print("%-11s %-5s  %-10s %-22s %-8s %-8s" % (
        "family", "face", "treatment", "band", "name ink", "K"))
    for fam, light in FACES:
        r = AS.face(fam, light)
        for key, title, note in TREATMENTS:
            band, name_ink = None, None
            # recompute for the report (draw() also does this, for the pixels)
            page = r["backgroundBase"]
            if key == "today":
                scrim, alpha = SP_bar_scrim(r, not light)
                band = over(scrim, alpha * 0.5, c01(page))
                name_ink = r["accentProgress"]
            else:
                b = r["primaryAccent"] if key != "warm" else r["warmAccent"]
                band = c01(b)
                _, _, name_ink = ink_for(b, r)
            k = L.contrast(c01(name_ink), band)
            flag = "FAIL" if k < 3.0 else ("body-ok" if k >= 4.5 else "large-ok")
            print("%-11s %-5s  %-10s %-22s %-8s %-8.2f %s" % (
                fam, "light" if light else "dark", key, hx(to255(band)), hx(name_ink), k, flag))

    cols = len(FACES)
    cell_w = STRIP_W + 16
    W = 236 + cols * cell_w
    H = 96 + len(TREATMENTS) * (STRIP_H + 34)
    im = Image.new("RGB", (W * S_SCALE, H * S_SCALE), BG)
    d = ImageDraw.Draw(im)
    d.text((24 * S_SCALE, 18 * S_SCALE), "The masthead as a committed colour",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((24 * S_SCALE, 41 * S_SCALE),
           "The crown is the largest surface on every screen; it can carry the theme.",
           font=font("segoeui.ttf", 11), fill=INK2)
    for ci, (fam, light) in enumerate(FACES):
        roles = AS.face(fam, light)
        x0 = 236 + ci * cell_w
        d.text((x0 * S_SCALE, 68 * S_SCALE), LABEL[(fam, light)],
               font=font("segoeuib.ttf", 11), fill=INK)
        for ri, (key, title, note) in enumerate(TREATMENTS):
            y = 92 + ri * (STRIP_H + 34)
            rr(d, [x0 - 6, y - 6, x0 + STRIP_W + 6, y + STRIP_H + 6], 8, fill=PANEL)
            draw(d, x0, y, roles, not light, key)
            if ci == 0:
                d.text((24 * S_SCALE, (y + 30) * S_SCALE), title,
                       font=font("segoeuib.ttf", 11.5), fill=INK)
                d.text((24 * S_SCALE, (y + 49) * S_SCALE), note,
                       font=font("segoeui.ttf", 9.5), fill=INK2)
    im = im.resize((W, H), Image.LANCZOS)
    im.save(OUT)
    print("\nsaved", OUT, im.size)


if __name__ == "__main__":
    main()
