"""The masthead — the surface the curation ignored.

`topBarFill` returns `scrim = (knob / BAR_GLASS)` clamped to 1, so with the default knob
(0.35 = BAR_GLASS) the status band is at **full strength at rest**, independent of collapse.
The glass veil, the sheen and the hairline are all scaled by `presence`, which is 0 at rest
— so on a screen that has not been scrolled, the masthead is exactly two things:

    the status band  = `scrimFor(atmos, roomTint, fieldTintStrength)`
    the wordmark     = `FolioTheme.colors.onSurface`

`home_render.py` draws only the wordmark ("no fill at rest, just the wordmark"), and
`check_all.py`'s `atmosphere3` does not model `barScrim` at all — so neither the renderer nor
the oracle has ever seen the band. This measures it on the sheet's eighteen faces and then
draws four treatments of the masthead so the design can be judged with it in the room.

    python masthead.py
"""
import os
import sys

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

import contextlib
import io

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS  # noqa: E402
    import home_render as HR  # noqa: E402
    import sheet as S  # noqa: E402

D, L, P = HR.D, HR.L, HR.P
S_SCALE = 2
OUT = r"D:\projects\Folio\outputs\curation\masthead.png"

BG = (13, 13, 17)
PANEL = (23, 24, 30)
INK = (238, 238, 244)
INK2 = (154, 151, 166)
INK3 = (104, 107, 120)

MAX_TIER_HUE_DEV = 12.0          # FolioAtmosphere.MAX_TIER_HUE_DEV
ROOM_HUE_MIN_CHROMA = 0.002
SCRIM_CHROMA = 0.14

FONTS = {}


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C:\Windows\Fonts\%s" % name, int(size * S_SCALE))
    return FONTS[key]


def rgb(c):
    return tuple(int(round(v)) for v in c)


def _01(c):
    return tuple(v / 255.0 for v in c)


def hue_of(c01):
    return D.lch(c01)[2]


def chroma_of(c01):
    return D.lch(c01)[1]


def signed(a, b):
    d = (a - b) % 360.0
    return d - 360.0 if d > 180.0 else d


def tier_hue(room_hue, role_hue, max_dev=MAX_TIER_HUE_DEV):
    """`tierHue`: bound a role's hue to the room. NaN room declines to bound."""
    if room_hue != room_hue:                      # NaN
        return role_hue
    return (room_hue + max(-max_dev, min(max_dev, signed(role_hue, room_hue)))) % 360.0


def bar_scrim(roles, dark):
    """The app's `barScrim`, on the sheet's own page."""
    bg = _01(roles["backgroundBase"])
    sf = _01(roles["surface"])
    room = hue_of(bg)
    room = room if chroma_of(bg) >= ROOM_HUE_MIN_CHROMA else float("nan")
    prim_h = hue_of(_01(roles["primaryAccent"]))
    if dark:
        base = L.deepen(bg, 0.30)
        h = tier_hue(room, prim_h)
        col, _ = D.tier_colour(L.gamma_lum(base), h, SCRIM_CHROMA)
        return col, 0.46
    base = P.deepenG(P.liftG(sf, 0.72), 0.13)
    h = tier_hue(room, prim_h)
    col, _ = D.tier_colour(L.gamma_lum(base), h, SCRIM_CHROMA)
    return col, 0.42


def over(fg, alpha, bg):
    return tuple(fg[i] * alpha + bg[i] * (1 - alpha) for i in range(3))


def to255(c):
    """The harness's colour helpers are 0-1; PIL is 0-255. Never mix them."""
    return tuple(v * 255.0 for v in c)


def rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([c * S_SCALE for c in box], radius=r * S_SCALE,
                        fill=rgb(fill) if fill else None,
                        outline=rgb(outline) if outline else None,
                        width=max(1, int(width * S_SCALE)))


# ── the four treatments ────────────────────────────────────────────────────────
def draw_masthead(d, x, y, w, h, roles, dark, treatment):
    """One masthead strip: the status band, the wordmark, and the top of the field.

    Everything inside is 0-1 (the harness's scale); only the final `fill=` converts.
    """
    page = _01(roles["backgroundBase"])
    warm = _01(roles["warmAccent"])
    scrim, alpha = bar_scrim(roles, dark)
    band = over(scrim, alpha, page)

    if treatment == "today":
        rr(d, [x, y, x + w, y + h], 0, fill=to255(band))
        ink = roles["textPrimary"]
    elif treatment == "signature":
        # `accentProgress`, NOT `primaryAccent`: the authored signature is a *display*
        # colour and fails the large-text floor on 4 of 18 faces (arctic light 1.55:1);
        # `accentProgress` is the same hue already pushed to AA on `surface`.
        rr(d, [x, y, x + w, y + h], 0, fill=to255(band))
        ink = roles["accentProgress"]
    elif treatment == "crown":
        # a crown wash: the §30 warm note laid over the top of the page, fading out
        for k in range(h):
            t = k / float(h)
            wash = over(warm, 0.085 * (1.0 - t) ** 1.6, page)
            rr(d, [x, y + k, x + w, y + k + 1], 0, fill=to255(over(scrim, alpha, wash)))
        ink = roles["accentProgress"]
    else:  # "crown_rule"
        for k in range(h):
            t = k / float(h)
            wash = over(warm, 0.085 * (1.0 - t) ** 1.6, page)
            rr(d, [x, y + k, x + w, y + k + 1], 0, fill=to255(over(scrim, alpha, wash)))
        ink = roles["accentProgress"]
        rr(d, [x, y + h - 1.2, x + w, y + h], 0,
           fill=to255(over(_01(roles["accentProgress"]), 0.5, band)))

    # the wordmark, at the app's own size and inset (headlineMedium, gutter 20)
    d.text(((x + 20) * S_SCALE, (y + h - 46) * S_SCALE), "Folio",
           font=font("georgiab.ttf", 27), fill=rgb(ink))


TREATMENTS = [
    ("today", "as it is today", "band = the derived scrim, wordmark = the ink"),
    ("signature", "wordmark in the fitted accent",
     "accentProgress — the signature, already pushed to AA"),
    ("crown", "crown wash", "warmAccent over the top of the page, fading down"),
    ("crown_rule", "crown + accent rule", "and a hairline of the accent at the foot"),
]

FACES = [("ocean", True), ("ocean", False), ("solar", False), ("jade", False)]
LABEL = {("ocean", True): "Lagoon — light", ("ocean", False): "Abyss — dark",
         ("solar", False): "Ember — dark", ("jade", False): "Garnet — dark"}

STRIP_W, STRIP_H = 300, 132


def main():
    print("%-11s %-5s  %-22s %-22s  %s" % ("family", "face", "page", "band over page", "delta"))
    for fam, light in FACES:
        r = AS.face(fam, light)
        scrim, alpha = bar_scrim(r, not light)
        band = over(scrim, alpha, _01(r["backgroundBase"]))
        page = _01(r["backgroundBase"])
        dv = max(abs(band[i] - page[i]) for i in range(3)) * 255
        print("%-11s %-5s  %-22s %-22s  %.0f" % (
            fam, "light" if light else "dark",
            "#%02X%02X%02X" % rgb(to255(page)), "#%02X%02X%02X" % rgb(to255(band)), dv))

    # ── the wordmark's three candidate inks, measured on all eighteen faces ──
    print("wordmark contrast against the page (large text floor 3.0, body 4.5)")
    print("%-11s %-5s  %-8s %-8s %-8s   %s" % (
        "family", "face", "ink", "signature", "accentPr", "verdict"))
    worst = []
    for fam in S.SHEET:
        for light in (True, False):
            r = AS.face(fam, light)
            page = _01(r["backgroundBase"])
            c_ink = L.contrast(_01(r["textPrimary"]), page)
            c_sig = L.contrast(_01(r["primaryAccent"]), page)
            c_acc = L.contrast(_01(r["accentProgress"]), page)
            bad = "FAIL" if c_sig < 3.0 else ("weak" if c_sig < 4.5 else "ok")
            if c_sig < 4.5:
                worst.append((c_sig, fam, light))
            print("%-11s %-5s  %-8.2f %-8.2f %-8.2f   signature %s" % (
                fam, "light" if light else "dark", c_ink, c_sig, c_acc, bad))
    worst.sort()
    print("\ntightest signature wordmarks: %s" % ", ".join(
        "%s/%s %.2f" % (f, "L" if l else "D", c) for c, f, l in worst[:6]))

    cols = len(FACES)
    cell_w = STRIP_W + 16
    W = 232 + cols * cell_w
    H = 96 + len(TREATMENTS) * (STRIP_H + 34)
    im = Image.new("RGB", (W * S_SCALE, H * S_SCALE), BG)
    d = ImageDraw.Draw(im)

    d.text((24 * S_SCALE, 18 * S_SCALE), "The masthead — what the curation ignored",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((24 * S_SCALE, 41 * S_SCALE),
           "scrim = 1.0 at rest, so the status band is at full strength on every screen.",
           font=font("segoeui.ttf", 11), fill=INK2)

    for ci, (fam, light) in enumerate(FACES):
        roles = AS.face(fam, light)
        x0 = 232 + ci * cell_w
        d.text((x0 * S_SCALE, 68 * S_SCALE), LABEL[(fam, light)],
               font=font("segoeuib.ttf", 11), fill=INK)
        for ri, (key, title, note) in enumerate(TREATMENTS):
            y = 92 + ri * (STRIP_H + 34)
            rr(d, [x0 - 6, y - 6, x0 + STRIP_W + 6, y + STRIP_H + 6], 8, fill=PANEL)
            draw_masthead(d, x0, y, STRIP_W, STRIP_H, roles, not light, key)
            if ci == 0:
                d.text((24 * S_SCALE, (y + 24) * S_SCALE), title,
                       font=font("segoeuib.ttf", 11.5), fill=INK)
                d.text((24 * S_SCALE, (y + 43) * S_SCALE), note,
                       font=font("segoeui.ttf", 9.5), fill=INK2)

    im = im.resize((W, H), Image.LANCZOS)
    im.save(OUT)
    print("\nsaved", OUT, im.size)


if __name__ == "__main__":
    main()
