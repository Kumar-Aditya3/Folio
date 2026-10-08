"""All eighteen faces in ONE row, zoomed out — for taking in at a glance in an analyser.

`all-themes.html` is a card grid (300px minimum, four or five per row), which is good for
inspecting a face but spreads the set over several screens. This is the opposite: every face on a
single horizontal strip, each family's light and dark adjacent, small enough that the whole set fits
one screenshot.
"""
import base64
import contextlib
import io
import os
import sys

sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp/curate")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp")
os.chdir(r"D:/projects/Folio/.workbuddy-ai/tmp/curate")

from PIL import Image  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import sheet as S
    import sheet_proto as SP

OUT = r"D:/projects/Folio/outputs/curation/theme-strip.html"
THUMB_W = 92                      # px per phone; 18 x ~100 = ~1.8k, one screenshot
NAMES = {
    "ocean": "Lagoon", "nebula": "Nebula", "forest": "Forest", "terracotta": "Terracotta",
    "arctic": "Arctic", "solar": "Solar", "jade": "Jade", "mintcoral": "Mint&Coral",
    "moonstone": "Moonstone",
}


def main():
    cells = []
    for fam, (lp, dp) in S.FAMILY_PACK.items():
        for pid, pol in ((lp, "L"), (dp, "D")):
            im = SP.render(pid, 2)
            h = int(im.height * THUMB_W / im.width)
            im = im.resize((THUMB_W, h), Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, format="PNG", optimize=True)
            b64 = base64.b64encode(buf.getvalue()).decode("ascii")
            cells.append((NAMES.get(fam, fam), pol, b64, h))
            print("  %-10s %s" % (pid, pol))

    thumb_h = cells[0][3]
    cards = []
    for name, pol, b64, _h in cells:
        cls = "d" if pol == "D" else "l"
        cards.append(
            "<figure class='c %s'><img alt='%s %s' src='data:image/png;base64,%s'>"
            "<figcaption><span class='n'>%s</span><span class='p'>%s</span></figcaption></figure>"
            % (cls, name, pol, b64, name, pol))
    html = """<!doctype html><html><head><meta charset='utf-8'><title>Folio - all 18, one strip</title>
<style>
html,body{background:#0b0b0f;margin:0}
body{font:11px/1.35 -apple-system,Segoe UI,Roboto,sans-serif;color:#e9e8ef;padding:18px 16px 22px}
h1{font-size:14px;margin:0 0 3px;font-weight:600}
.sub{color:#8f8c9c;font-size:11px;margin:0 0 14px}
.strip{display:flex;gap:7px;align-items:flex-start}
.c{margin:0;flex:0 0 auto}
.c img{display:block;width:__W__px;border-radius:5px;border:1px solid #21222a}
.c.l img{border-color:#3a3b44}
.c.d img{border-color:#15161b}
figcaption{display:flex;justify-content:space-between;margin-top:5px;padding:0 1px}
.n{font-size:9.5px;color:#c9c7d2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.p{font-size:9px;color:#6f6d7a;font-family:ui-monospace,Consolas,monospace}
.fam{display:flex;gap:2px}
</style></head><body>
<h1>All eighteen faces &mdash; one strip</h1>
<p class='sub'>Nine families, light and dark adjacent, in the same order every time. Scaled to
__W__px per phone so the whole set fits a single screenshot.</p>
<div class='strip'>__CARDS__</div>
</body></html>""".replace("__W__", str(THUMB_W)).replace("__CARDS__", "".join(cards))
    open(OUT, "w", encoding="utf-8").write(html)
    print("wrote %s (%.2f MB, %d faces, thumb %dx%d)" % (
        OUT, len(html) / 1e6, len(cells), THUMB_W, thumb_h))


if __name__ == "__main__":
    main()
