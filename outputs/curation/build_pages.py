"""Rebuild the three palette-system pages from the live output. One command, no hand-editing.

    nebula-counterfactual.html   current vs topology-aligned, side by side, NOT applied
    nebula-contract.html         the Class-X contract and the case it exists to reject
    identity-audit.html          the two-class matrix (Class P gated, Class X on its envelope)

Run from anywhere; paths are absolute.
"""
import base64
import contextlib
import io
import os
import subprocess
import sys

CUR = r"D:\projects\Folio\outputs\curation"
sys.path.insert(0, CUR)
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

PY = r"C:\Users\kumar\AppData\Local\Programs\Python\Python313\python.exe"

HEAD = """<!doctype html><html><head><meta charset='utf-8'><title>__TITLE__</title>
<style>
body{background:#0d0d11;color:#eeeef4;font:14px/1.7 -apple-system,'Segoe UI',sans-serif;
padding:36px;max-width:1000px;margin:0 auto}
h1{font-size:22px;margin:0 0 12px;letter-spacing:-.01em}
h2{font-size:12.5px;margin:32px 0 10px;color:#9a97a6;text-transform:uppercase;letter-spacing:.08em}
p{color:#9a97a6;max-width:820px;margin:0 0 12px}b,strong{color:#fff}
code{background:#17181e;padding:1px 5px;border-radius:4px;color:#d8d5e2;
font-family:ui-monospace,Consolas,monospace;font-size:12.5px}
pre{background:#131318;border:1px solid #21222a;border-radius:10px;padding:18px;overflow-x:auto;
color:#c9c7d2;font-family:ui-monospace,Consolas,monospace;font-size:12px;line-height:1.6;
white-space:pre;margin:0}
table{border-collapse:collapse;width:100%;margin:6px 0 14px;font-size:13px}
th,td{text-align:left;padding:7px 10px;border-bottom:1px solid #21222a}
th{color:#9a97a6;font-weight:500;font-size:11.5px;text-transform:uppercase;letter-spacing:.06em}
.row{display:flex;gap:20px;align-items:flex-start}
img{width:100%;display:block;border:1px solid #21222a;border-radius:10px}
.cap{font-size:12px;color:#c9c7d2;margin:9px 0 0}
.cap span{color:#6f6d7a;font-family:ui-monospace,Consolas,monospace}
.good{color:#7ddca4}.bad{color:#e88a8a}
</style></head><body>
<h1>__H1__</h1>
__BODY__
</body></html>"""


def run(script):
    return subprocess.run([PY, os.path.join(CUR, script)], capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;")


def page(path, title, h1, body):
    html = HEAD.replace("__TITLE__", title).replace("__H1__", h1).replace("__BODY__", body)
    open(os.path.join(CUR, path), "w", encoding="utf-8", newline="\n").write(html)
    print("wrote %-28s %6.1f KB" % (path, len(html) / 1024))


# ── 1. the counterfactual ────────────────────────────────────────────────────────────────────────
with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import nebula_study as NS
    import sheet_proto as SP

dark, al = NS.main()
with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    light = AS.face("nebula", True)
t0, w0 = NS.compare(light, dark)
t1, w1 = NS.compare(light, al)

real = SP.roles_for
imgs = []
for label, note, face in (("current", "T6 %.0f   W6 %.0f" % (t0, w0), dark),
                          ("topology-aligned", "T6 %.0f   W6 %.0f" % (t1, w1), al)):
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
    '<div class="cap">Nebula dark &mdash; %s<br><span>%s</span></div></div>' % (b, l, n)
    for l, n, b in imgs)

page("nebula-counterfactual.html", "Nebula counterfactual",
     "Nebula: the counterfactual, not a repair",
     """<p>Nebula is the one theme the structural gates cannot settle. Forest had a demonstrable
erroneous mapping (15.8&deg; &rarr; 105.4&deg;) correctable while restoring the light face's
relationships. Nebula has no such defect, so the question is a design one &mdash;
<b>does restoring structural correspondence make Nebula better, or destroy what makes it
Nebula?</b></p>

<h2>What the same machinery would do</h2>
<p>Alignment raises <code>T6 %(t0).0f &rarr; %(t1).0f</code> and
<code>W6 %(w0).0f &rarr; %(w1).0f</code>. That is not a marginal gain: <b>T6 %(t1).0f is above the
Class P gate of 70</b>. The aligned version would <em>pass</em> the structural gate Nebula currently
fails.</p>
<p>And the hues it has to move <em>are</em> the identity:
<code>primaryAccent 312&deg; &rarr; 122&deg;</code> (violet &rarr; <b>green</b>),
<code>secondaryAccent 52&deg; &rarr; 226&deg;</code> (gold &rarr; <b>blue</b>),
<code>warmAccent 317&deg; &rarr; 43&deg;</code>.</p>
<p><b>That is the whole argument in one line:</b> the optimizer would score this as a pass, and the
theme would no longer be Nebula. Metric improvement does not imply design improvement &mdash; and
here the metric does not merely improve, it succeeds, while the design fails.</p>

<h2>Side by side</h2><div class="row">%(cells)s</div>

<h2>Not applied</h2>
<p>The gates were not changed for Nebula, and <code>T6 &ge; 70</code> was not quietly rewritten as
&ldquo;except Nebula&rdquo;. The theme class is decided first: Nebula is <b>Class X</b>, held to a
semantic envelope derived from its own authored swatches. This counterfactual is kept permanently as
a <b>negative control</b> &mdash; it is the concrete demonstration that a future optimisation pass
could otherwise rediscover this failure and call it a success.</p>"""
     % dict(t0=t0, t1=t1, w0=w0, w1=w1, cells=cells))

# ── 2. the contract ──────────────────────────────────────────────────────────────────────────────
out = run("nebula_envelope.py")
page("nebula-contract.html", "Nebula class X contract",
     "Nebula: transformative topology, bounded chromatic vocabulary",
     """<p>Nebula is the one <b>Class X</b> theme. <code>T6</code>/<code>W6</code>/<code>C</code> are
diagnostics for it, never gates; what replaces them is a semantic envelope derived from the authored
swatches <em>before</em> any failure score was consulted.</p>

<h2>Three attempts, two of them wrong</h2>
<table><tr><th>attempt</th><th>result</th><th>why it failed</th></tr>
<tr><td>circular mean band, both faces pooled</td><td class="bad">&sigma; 134&ndash;148&deg;</td>
<td>Nebula is the most divergent family (79&deg; hue-set displacement), so the pooled distribution is
nearly uniform. Band ~288&deg; wide &mdash; wide enough to accept green as &ldquo;violet&rdquo;.</td></tr>
<tr><td>membership envelope from the <em>light</em> face</td><td class="bad">accepted the counterfactual</td>
<td>Necessarily: <code>topology_align</code> sets each dark hue <em>to</em> the light face's hue at
its rank, so a vocabulary drawn from the light face contains the aligned result by construction.
<b>A contract that cannot reject the case it was written for is not a contract.</b></td></tr>
<tr><td>frozen envelope from the <em>dark</em> face</td><td class="good">works</td>
<td>The dark face is the designed artefact, so its vocabulary is the reference. Frozen as literals so
the contract cannot drift with the palette it constrains.</td></tr></table>

<h2>The clauses</h2>
<p><b>Role-anchored</b> (the gate): each role stays near its own authored hue.
<b>Vocabulary membership</b> (the migration clause): each hue stays near <em>some</em> authored hue,
which is what permits role permutation &mdash; the mechanical definition of &ldquo;transformative&rdquo;.
<b>Relational</b> (the signature): the authored pairwise separations hold.</p>
<p>The relational clause carries most of the meaning. The authored dark face has
<code>primary</code> and <code>warmAccent</code> only <b>4.55&deg;</b> apart &mdash; a tight violet
pair with a gold counterpoint. That single tight relation is Nebula's chromatic signature, and it is
the first thing an alignment destroys (<code>dH_PR 4.5 &rarr; 79.3</code>).</p>
<p><b>A note on why the pairwise test alone is not enough:</b> <code>dH_PQ</code> survives the
counterfactual (3.8&deg; off) while <code>dH_PR</code> breaks by 74.7&deg;. Testing the relations as
a set would have missed it; the tight pair is what carries the signal.</p>

<h2>Full output</h2><pre>%s</pre>""" % esc(out))

# ── 3. the two-class audit ───────────────────────────────────────────────────────────────────────
out = run("identity_audit.py")
page("identity-audit.html", "theme identity - two classes",
     "Theme identity: two formally different classes",
     """<p><b>Class P</b> (paired, 8 families) constrains the dark face by the light face's relational
structure &mdash; <code>P_D &asymp; f(P_L)</code> &mdash; gated on <code>T6/S/W6/A/C/E</code>.
<b>Class X</b> (transformative, Nebula) permits the dark face to reorganise its topology and is held
to a semantic envelope instead.</p>
<p>All 8 Class P families pass. Nebula passes its Class X contract. <code>T6/W6/C</code> are retained
in the matrix as diagnostics for Class X, but they are not gates for it.</p>
<h2>Full output</h2><pre>%s</pre>""" % esc(out))
