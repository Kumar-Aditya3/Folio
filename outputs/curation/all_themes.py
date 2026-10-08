"""All eighteen faces, each rendered on its own with the guilloche, in one page.

`sheet_proto.render` draws Home from the live Kotlin for one pack id, so this is 18 independent
renders rather than the four contact strips — one card per face, grouped by family, each labelled
with its light/dark polarity.
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

OUT = r"D:/projects/Folio/outputs/curation/all-themes.html"
WEB = r"D:/projects/Folio/outputs/curation/web"
NAMES = {
    "ocean": "Lagoon", "nebula": "Nebula", "forest": "Forest", "terracotta": "Terracotta",
    "arctic": "Arctic", "solar": "Solar", "jade": "Jade", "mintcoral": "Mint & Coral",
    "moonstone": "Moonstone",
}

CSS = """
:root{color-scheme:dark}
body{background:#0b0b0f;color:#e9e8ef;font:14px/1.55 -apple-system,Segoe UI,Roboto,sans-serif;
     margin:0;padding:40px 32px 72px;max-width:1500px;margin:0 auto}
h1{font-size:26px;margin:0 0 8px;letter-spacing:-.01em}
.sub{color:#8f8c9c;max-width:760px;margin:0 0 8px}
.fam{margin:52px 0 18px;font:600 12px/1 ui-monospace,Consolas,monospace;color:#8f8c9c;
     text-transform:uppercase;letter-spacing:.14em;border-top:1px solid #1d1e25;padding-top:14px}
.row{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:22px}
.card{background:#14151b;border:1px solid #21222a;border-radius:14px;padding:14px}
.card img{width:100%;display:block;border-radius:9px}
.cap{display:flex;justify-content:space-between;align-items:baseline;margin:12px 2px 0}
.name{font-weight:600;font-size:14px}
.pol{font:11px ui-monospace,Consolas,monospace;color:#8f8c9c;text-transform:uppercase;letter-spacing:.1em}
"""


def main():
    parts = ["<!doctype html><html><head><meta charset='utf-8'>",
             "<title>Folio — all 18 themes</title>",
             "<style>%s</style></head><body>" % CSS,
             "<h1>Folio — all eighteen themes</h1>",
             "<p class='sub'>Nine families, each with a separately painted light and dark face. "
             "Every card is an independent render from the live Kotlin — the palette, the field ramp, "
             "the crown, the nav capsule and the guilloche all come from the same parsed source, so "
             "no two of these share a palette.</p>"]
    for fam, (lp, dp) in S.FAMILY_PACK.items():
        parts.append("<div class='fam'>%s</div><div class='row'>" % NAMES.get(fam, fam))
        for pid, pol in ((lp, "light"), (dp, "dark")):
            im = SP.render(pid, 2)
            path = os.path.join(WEB, "_t_%s.png" % pid)
            im.save(path)
            small = im.resize((im.width // 2, im.height // 2), Image.LANCZOS)
            small.save(path, optimize=True)
            b64 = base64.b64encode(open(path, "rb").read()).decode("ascii")
            os.remove(path)
            parts.append(
                "<div class='card'><img alt='%s %s' src='data:image/png;base64,%s'>"
                "<div class='cap'><span class='name'>%s</span>"
                "<span class='pol'>%s</span></div></div>"
                % (NAMES.get(fam, fam), pol, b64, NAMES.get(fam, fam), pol))
            print("  rendered %-10s %s" % (pid, pol))
        parts.append("</div>")
    parts.append("</body></html>")
    html = "".join(parts)
    open(OUT, "w", encoding="utf-8").write(html)
    print("wrote %s (%.1f MB)" % (OUT, len(html) / 1e6))


if __name__ == "__main__":
    main()
