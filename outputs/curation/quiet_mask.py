"""How faithful is the renderer? One honest measurement, plus the biggest deviation drawn.

The renderer parses the live Kotlin and replays the derivation, so **colour** is faithful — that
is the whole reason `render_from_file.py` can assert 0 differing roles. **Geometry** is not: the
layout is redrawn by hand, the shader is approximated by a raster, and several app behaviours are
simply absent.

The largest of those absences is `GuillocheQuiet`. The app registers text regions and collapses
the plate to near zero where they sit — `guillocheLineColor` is fed a near-zero `amount` — so the
plate never competes with type. The renderer draws the plate *everywhere*. With a thread plate
that is not a small difference: it is the difference between "texture behind the content" and
"content sitting on a weave".

This renders one light and one dark face twice — as the renderer has been drawing it, and with a
quiet mask — and reports how much of the screen the app would actually suppress.

    python quiet_mask.py
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
    import flow_plate as FP
    import home_render as HR
    import sheet_proto as SP

OUT = r"D:\projects\Folio\outputs\curation\quiet-mask.png"
LOGW, LOGH = 390, 844
BG, PANEL = (13, 13, 17), (23, 24, 30)
INK, INK2 = (238, 238, 244), (154, 151, 166)
FONTS = {}

# The text-bearing rectangles of Home, in the renderer's own layout units. These are where the
# app would register a quiet region; the shelf *tiles* are excluded on purpose, because a tile is
# an opaque cover and the plate never shows there anyway.
QUIET_RECTS = [
    (20, 92, LOGW - 20, 372),        # the hero's type block
    (20, 402, LOGW - 20, 470),       # the ledger strip
    (20, 560, LOGW - 20, 640),       # shelf captions
    (20, 660, LOGW - 20, 772),       # the week well
    (79, LOGH - 66, LOGW - 79, LOGH - 12),   # the nav capsule
]


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C:\Windows\Fonts\%s" % name, int(size))
    return FONTS[key]


def quiet_mask(W, H, feather=26.0):
    """1 = plate allowed, 0 = collapsed. Soft-edged, like the app's registered regions.

    `QUIET_RECTS` are in the layout's own 390x844 units, so they are scaled to the canvas —
    using them unscaled put every region in the top-left quadrant and the mask suppressed
    nothing, which is exactly what the first run reported.
    """
    k = W / float(LOGW)
    ys, xs = np.mgrid[0:H, 0:W]
    m = np.ones((H, W), float)
    for (x0, y0, x1, y1) in [(a * k, b * k, c * k, d * k) for a, b, c, d in QUIET_RECTS]:
        # signed distance outside the rect, in pixels; smoothstep in from `feather`
        dx = np.maximum(np.maximum(x0 - xs, xs - x1), 0)
        dy = np.maximum(np.maximum(y0 - ys, ys - y1), 0)
        d = np.sqrt(dx * dx + dy * dy)
        inside = np.clip(d / feather, 0, 1)
        m = np.minimum(m, inside)
    return m


def render(pid, quiet, scale=2):
    """`home_render.render_home`, with the plate's coverage multiplied by the quiet mask."""
    real = FP.flow_plate
    box = {}

    def patched(swatches, W, H, **kw):
        rgb, cov = real(swatches, W, H, **kw)
        if quiet:
            m = quiet_mask(W, H)
            # the fraction of **plate** removed, not the fraction of screen: `cov` is near zero
            # almost everywhere because the threads are thin, so a screen-wide average is a
            # meaningless number that reads as 99% either way.
            ink = cov > 0.20
            box["masked"] = float((ink & (cov * m < 0.20)).sum()) / max(1, int(ink.sum()))
            cov = cov * m
        return rgb, cov

    FP.flow_plate = patched
    try:
        im = SP.render(pid, scale)
    finally:
        FP.flow_plate = real
    return im, box.get("masked", 0.0)


def main():
    faces = [("lagoon", "Lagoon — light"), ("abyss", "Abyss — dark")]
    frac = 0.0
    panels = []
    for pid, label in faces:
        a, _ = render(pid, False)
        b, frac = render(pid, True)
        panels.append((label, a, b))
    print("plate suppressed over %.0f%% of the strands the app would mask" % (frac * 100))

    sw, sh = LOGW * 2, LOGH * 2
    cols = len(panels) * 2
    W = 60 + cols * (LOGW + 14)
    H = 118 + LOGH + 20
    im = Image.new("RGB", (W * 2, H * 2), BG)
    d = ImageDraw.Draw(im)
    d.text((30 * 2, 20 * 2), "Renderer fidelity: the plate with and without the app's quiet mask",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((30 * 2, 44 * 2),
           "Colour is faithful (it replays the Kotlin). Geometry is not — and the app collapses "
           "the plate wherever text sits, which this renderer does not do.",
           font=font("segoeui.ttf", 11), fill=INK2)
    for ci, (label, a, b) in enumerate(panels):
        for k, img in enumerate((a, b)):
            x0 = 60 + (ci * 2 + k) * (LOGW + 14)
            y = 100
            d.rectangle([(x0 - 6) * 2, (y - 6) * 2, (x0 + LOGW + 6) * 2, (y + LOGH + 6) * 2],
                        fill=PANEL)
            im.paste(img.resize((LOGW * 2, LOGH * 2)), (x0 * 2, y * 2))
            cap = "%s · %s" % (label, "as I have been drawing it" if k == 0
                               else "with the quiet mask")
            d.text((x0 * 2, (y + LOGH + 12) * 2), cap,
                   font=font("segoeuib.ttf", 11), fill=INK if k else INK2)
    im = im.resize((W, H), Image.LANCZOS)
    im.save(OUT)
    print("saved", OUT, im.size)


if __name__ == "__main__":
    main()
