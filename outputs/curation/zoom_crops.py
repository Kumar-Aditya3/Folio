"""Guilloche-only crops, one dark and one light, at 3x — the plate is not legible at phone scale.

Crops the band the plate actually occupies (y 0.39-0.72) so the filaments can be seen on their own,
without the UI around them.
"""
import contextlib
import base64
import io
import os
import sys

sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp/curate")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp")
os.chdir(r"D:/projects/Folio/.workbuddy-ai/tmp/curate")

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import flow_plate as FP
    import sheet_proto as SP

OUT = r"D:/projects/Folio/outputs/curation/guilloche-zoom.html"
BAND = (0.03, 0.97, 0.39, 0.72)
FACES = [("abyss", "Lagoon &mdash; dark", True), ("lagoon", "Lagoon &mdash; light", False)]


def crop(pid, dark):
    darkr, roles = SP.roles_for(pid)
    sw = [roles[k] for k in ("primary", "secondary", "tertiary", "warmAccent",
                             "guillochePrimary", "guillocheSecondary")]
    W, H = 390, 844
    rgb, cov = FP.flow_plate(sw, W, H, width=1, neutral=(97, 127, 132) if dark else (104, 113, 122))
    field = np.zeros((H, W, 3), float) + np.array(roles["background"], float)
    a = np.clip(cov * 2.6, 0, 1)
    img = np.clip(field * (1 - a)[..., None] + rgb * a[..., None], 0, 255).astype(np.uint8)
    y0, y1 = int(BAND[2] * H), int(BAND[3] * H)
    out = Image.fromarray(img[y0:y1])
    return out.resize((out.width * 3, out.height * 3), Image.LANCZOS)


def main():
    parts = ["""<!doctype html><html><head><meta charset='utf-8'><title>Folio - guilloche zoom</title>
<style>body{background:#0d0d11;color:#eeeef4;font:14px/1.6 -apple-system,Segoe UI,sans-serif;
padding:34px;max-width:1180px;margin:0 auto}h1{font-size:22px;margin:0 0 8px}
p{color:#9a97a6;max-width:820px}h2{font-size:12px;color:#8f8c9c;text-transform:uppercase;
letter-spacing:.09em;margin:34px 0 10px}
img{width:100%;display:block;border:1px solid #21222a;border-radius:10px}
code{background:#17181e;padding:1px 5px;border-radius:4px;color:#d8d5e2}b{color:#fff}</style></head><body>
<h1>The guilloche on its own, 3&times;</h1>
<p>The band the plate occupies (y 0.39&ndash;0.72), cropped and magnified, with the UI removed. At
phone scale the filaments are too fine to read; this is what is actually being drawn.</p>"""]
    for pid, label, dark in FACES:
        im = crop(pid, dark)
        buf = io.BytesIO()
        im.save(buf, format="PNG", optimize=True)
        b64 = base64.b64encode(buf.getvalue()).decode("ascii")
        parts.append("<h2>%s</h2><img alt='%s' src='data:image/png;base64,%s'>" % (label, label, b64))
    parts.append("</body></html>")
    html = "".join(parts)
    open(OUT, "w", encoding="utf-8").write(html)
    print("wrote %s (%.2f MB)" % (OUT, len(html) / 1e6))


if __name__ == "__main__":
    main()
