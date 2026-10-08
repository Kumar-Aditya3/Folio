"""Inject the guilloche bundle measurements into `guilloche-zoom.html`.

Computed from the live `flow_plate.py` through `flow_metrics.measure_bundles()` -- the ONE canonical
definition -- so the page cannot drift from the field it describes. Idempotent.

**Bundle-level, not global.** Global adjacent-gap ordering across all bundles is meaningless now: the
bundles are meant to overlap and cross, so an inter-bundle crossing is an intended property rather
than a failure. What must hold is ordering WITHIN each bundle.
"""
import os
import sys

CUR = r"D:/projects/Folio/outputs/curation"
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp/curate")
sys.path.insert(0, r"D:/projects/Folio/.workbuddy-ai/tmp")
os.chdir(r"D:/projects/Folio/.workbuddy-ai/tmp")

import flow_metrics as FM  # noqa: E402
import flow_plate as FP  # noqa: E402

r = FM.measure_bundles()

rows = "".join(
    "<tr><td><b>%s</b></td><td>%d</td><td>%.3f</td><td>%.3f</td>"
    "<td class='%s'>%+.4f %s</td><td>%.4f / %.4f / %.4f</td><td>%s</td></tr>"
    % (nm, r[nm]["n"], (FP.BUNDLE_BY_NAME[nm]["hi"] - FP.BUNDLE_BY_NAME[nm]["lo"]) / 2.0,
       FP.BUNDLE_BY_NAME[nm]["cs"],
       "g" if r[nm]["margin"] > 0 else "bad", r[nm]["margin"],
       "HELD" if r[nm]["margin"] > 0 else "BROKEN",
       r[nm]["gap_min"], r[nm]["gap_median"], r[nm]["gap_max"],
       str(r[nm]["kappa_lobes"]))
    for nm in ("A", "B", "C"))

body = """<h2>The bundle construction, measured</h2>
<p>The field is now <b>three independently generated bundles</b>, not one trajectory population.
Within a bundle <code>dy/dq &gt; 0</code> holds; <b>between</b> bundles there is deliberately no
ordering constraint, because they are meant to overlap and cross. Global adjacent-gap ordering is
therefore meaningless and is <b>no longer reported</b>.</p>
<table>
<tr><th>bundle</th><th>n</th><th>seed grad</th><th>cs</th><th>ordering margin</th>
<th>gap min / med / max</th><th>curvature lobes</th></tr>
__ROWS__
</table>
<p><b>Bundle crossings:</b> %(crossings)s &mdash; total <b>%(ctotal)d</b>, at x = %(clocs)s. Target was
2&ndash;4. They are <em>concentrated</em> at x&nbsp;&asymp;&nbsp;0.25&ndash;0.27 rather than distributed
across the composition, and B&ndash;C does not cross at all &mdash; two shortfalls against the brief,
reported rather than tuned away.</p>

<h3>Why per-bundle coefficient scaling was needed</h3>
<p>Ordering within a bundle needs <code>dy/dq = y0'(q) + &Sigma; a_i'(q)&middot;M_i(x) &gt; 0</code>,
and <code>y0'(q)</code> <em>is the bundle's own seed gradient</em>, <code>(hi&minus;lo)/2</code>. A
narrow bundle has a small gradient, so the <b>same field breaks its ordering</b>: bundle C (gradient
0.07) measured <code>&minus;0.079</code> where bundle A (gradient 0.24) held at <code>+0.019</code>.</p>
<p><b>And the span ratio alone was not enough &mdash; two things had to be measured, not assumed:</b></p>
<p><b>1. The splitter is the dominant q-dependent term.</b> Its <code>S'(q)</code> reaches
<code>&minus;0.98</code> at the population edges, contributing
<code>A_SPLIT&middot;G'(x)&middot;S'(q)</code> to <code>dy/dq</code> &mdash; comparable to a bundle's
entire seed gradient. Scaling only <code>a1</code>&hellip;<code>a4</code> left it unscaled, which is
what kept bundle B broken at <code>&minus;0.045</code>.</p>
<p><b>2. Bundle B is offset +0.10, so it samples a different part of the field</b> and its worst-case
negative <code>dy/dq</code> is <em>not</em> A's:</p>
<pre>  multiplier | A margin | B margin | C margin
     1.00    |  +0.0189 |  -0.0168 |  +0.0305    &lt;- span ratio alone: B broken
     0.90    |  +0.0189 |  +0.0057 |  +0.0345
     0.80    |  +0.0189 |  +0.0282 |  +0.0386    &lt;- chosen</pre>

<h3>Hero luminosity follows local curvature</h3>
<p>Unchanged: <code>alpha_i(x) = a0&middot;(1 + 0.9&middot;|kappa_i(x)|/kappa_max)</code>, now with
heroes assigned <em>per bundle</em> &mdash; 2 for A, 2 for B, 1 for C &mdash; so each bundle announces
its own bending rather than one bundle carrying all the bright filaments.</p>
""".replace("__ROWS__", rows) % dict(
    crossings=r["crossings"], ctotal=r["crossing_total"],
    clocs=", ".join("%.2f" % x for x in r["crossing_locations"]))

path = os.path.join(CUR, "guilloche-zoom.html")
h = open(path, encoding="utf-8").read()
MARK = "<h2>The bundle construction, measured</h2>"
if MARK in h:
    h = h[:h.index(MARK)]
h = h.replace("</body>", body + "</body>")
h = h.replace("<style>", "<style>\n.g{color:#7ddca4}.bad{color:#e88a8a}.b{color:#8f8c9c}\n"
              "td.b,td.g,td.bad{font-family:ui-monospace,Consolas,monospace}", 1)
open(path, "w", encoding="utf-8", newline="\n").write(h)
print("injected into guilloche-zoom.html")
print("  bundles: " + "  ".join("%s n=%d margin %+.4f" % (nm, r[nm]["n"], r[nm]["margin"])
                                for nm in ("A", "B", "C")))
print("  bundle crossings total %d at %s" % (r["crossing_total"],
      ", ".join("%.2f" % x for x in r["crossing_locations"])))
