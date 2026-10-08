"""Assemble the Nebula counterfactual page: render both faces, embed, write."""
import base64
import contextlib
import io
import os
import sys

sys.path.insert(0, r"D:/projects/Folio/outputs/curation")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp/curate")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp")
os.chdir(r"D:/projects/Folio/.workbuddy-ai/tmp")

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import nebula_study as NS
    import sheet_proto as SP

dark, al = NS.main()
real = SP.roles_for
imgs = []
for label, note, face in (("Nebula dark &mdash; current", "T6 21 &nbsp; W6 27", dark),
                          ("Nebula dark &mdash; topology-aligned", "T6 51 &nbsp; W6 40", al)):
    def patched(pid, _f=face, _r=real):
        d, roles = _r(pid)
        m = dict(roles)
        m["primary"] = _f["primaryAccent"]
        m["secondary"] = _f["secondaryAccent"]
        m["tertiary"] = _f["tertiaryAccent"]
        m["warmAccent"] = _f["warmAccent"]
        return d, m
    SP.roles_for = patched
    im = SP.render("dusk", 2)
    buf = io.BytesIO()
    im.save(buf, format="PNG", optimize=True)
    imgs.append((label, note, base64.b64encode(buf.getvalue()).decode("ascii")))
SP.roles_for = real

cells = "".join(
    '<div style="flex:1"><img alt="x" src="data:image/png;base64,%s">'
    '<div class="cap">%s<br><span>%s</span></div></div>' % (b, l, n)
    for l, n, b in imgs)
html = """<!doctype html><html><head><meta charset='utf-8'><title>Nebula counterfactual</title>
<style>body{background:#0d0d11;color:#eeeef4;font:14px/1.65 -apple-system,Segoe UI,sans-serif;
padding:34px;max-width:980px;margin:0 auto}h1{font-size:21px;margin:0 0 10px}
h2{font-size:13px;margin:30px 0 8px;color:#9a97a6;text-transform:uppercase;letter-spacing:.07em}
p{color:#9a97a6;max-width:800px}b{color:#fff}code{background:#17181e;padding:1px 5px;border-radius:4px;color:#d8d5e2}
.row{display:flex;gap:20px;align-items:flex-start}img{width:100%;display:block;border:1px solid #21222a;border-radius:10px}
.cap{font-size:12px;color:#c9c7d2;margin:9px 0 0}.cap span{color:#6f6d7a;font-family:ui-monospace,Consolas,monospace}</style>
</head><body>
<h1>Nebula: the counterfactual, not a repair</h1>
<p>Nebula is the last unresolved theme. Its problem is qualitatively different from Forest's: Forest had
a demonstrable erroneous mapping (15.8&deg; &rarr; 105.4&deg;) correctable while restoring the light
face's relationships. Nebula has no such obvious defect, so this is a design question &mdash;
<b>does restoring structural correspondence make Nebula better, or destroy what makes it Nebula?</b></p>
<h2>What the same machinery would do</h2>
<p>Alignment raises <code>T6 21 &rarr; 51</code> and <code>W6 27 &rarr; 40</code> &mdash; a real
improvement, though still under the gates. But the hues it has to move <em>are</em> the identity:
<code>primaryAccent 312&deg; &rarr; 122&deg;</code> (violet &rarr; <b>green</b>),
<code>secondaryAccent 52&deg; &rarr; 226&deg;</code> (gold &rarr; <b>blue</b>),
<code>warmAccent 317&deg; &rarr; 43&deg;</code>.</p>
<h2>Side by side</h2><div class="row">__CELLS__</div>
<p style="margin-top:26px"><b>NOT APPLIED.</b> The gates were not changed for Nebula, and
<code>T6 &ge; 70</code> was not quietly rewritten as &ldquo;except Nebula&rdquo;. The theme class is
decided first: either Nebula is an ordinary paired theme and meets the ordinary contract, or it is a
transformative theme whose contract is defined <i>before</i> it is evaluated.</p>
</body></html>""".replace("__CELLS__", cells)
open(r"D:/projects/Folio/outputs/curation/nebula-counterfactual.html", "w",
     encoding="utf-8").write(html)
print("wrote nebula-counterfactual.html (%.2f MB)" % (len(html) / 1e6))
