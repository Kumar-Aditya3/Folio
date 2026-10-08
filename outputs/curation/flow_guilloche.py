"""The ribbon-bundle guilloche, rendered across four faces.

The construction lives in `.workbuddy-ai/tmp/curate/flow_plate.py`; this only renders it. The
field is a set of fixed constants, so all four panels below are **the same geometry** - only the
colour ramp differs, and that is built from each face's own swatches.

    python flow_guilloche.py
"""
import contextlib
import io
import os
import sys

sys.path.insert(0, r"D://projects//Folio//.workbuddy-ai//tmp//curate")
sys.path.insert(0, r"D://projects//Folio//.workbuddy-ai//tmp")
os.chdir(r"D://projects//Folio//.workbuddy-ai//tmp")

import numpy as np  # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import flow_plate as FP

OUT = r"D://projects//Folio//outputs//curation//flow-guilloche.png"
S = 2
BG, PANEL = (13, 13, 17), (23, 24, 30)
INK, INK2 = (238, 238, 244), (154, 151, 166)
W, H = 390, 260

FACES = [("ocean", True), ("terracotta", True), ("ocean", False), ("solar", False)]
LABEL = {("ocean", True): "Lagoon - light", ("terracotta", True): "Warm - light",
         ("ocean", False): "Abyss - dark", ("solar", False): "Ember - dark"}
FONTS = {}


def font(name, size):
    key = (name, size)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(r"C://Windows//Fonts//%s" % name, int(size * S))
    return FONTS[key]


def strip(roles, dark):
    rgb, cov = FP.flow_plate(
        [roles[k] for k in ("primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent",
                            "guillochePrimary", "guillocheSecondary", "guillocheHighlight")],
        W * S, H * S, width=S,
        deepen=0.0 if dark else 0.62,
        ink=(0, 0, 0) if dark else roles["textPrimary"])
    field = np.zeros((H * S, W * S, 3), float) + np.array(roles["backgroundBase"], float)
    alpha = np.clip(cov * (1.6 if dark else 1.5), 0, 1)
    img = field * (1 - alpha)[..., None] + rgb * alpha[..., None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def main():
    cols = len(FACES)
    cell_w = W + 16
    Wt = 236 + cols * cell_w
    Ht = 100 + H + 34
    im = Image.new("RGB", (Wt * S, Ht * S), BG)
    d = ImageDraw.Draw(im)
    d.text((24 * S, 18 * S), "The ribbon-bundle guilloche - one geometry, every palette",
           font=font("segoeuib.ttf", 15), fill=INK)
    d.text((24 * S, 41 * S),
           "Five bundles, each with its own centreline, orientation and width envelope; "
           "30 threads offset along each normal, spaced non-uniformly.",
           font=font("segoeui.ttf", 11), fill=INK2)
    for ci, (fam, light) in enumerate(FACES):
        roles = AS.face(fam, light)
        x0 = 236 + ci * cell_w
        y = 92
        d.rectangle([(x0 - 6) * S, (y - 6) * S, (x0 + W + 6) * S, (y + H + 6) * S], fill=PANEL)
        im.paste(strip(roles, not light), (x0 * S, y * S))
        d.text((x0 * S, (y + H + 12) * S), LABEL[(fam, light)],
               font=font("segoeuib.ttf", 11), fill=INK)
    im = im.resize((Wt, Ht), Image.LANCZOS)
    im.save(OUT)
    print("saved", OUT, im.size)


if __name__ == "__main__":
    main()
