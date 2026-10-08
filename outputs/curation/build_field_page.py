"""Before/after for the multi-mode macro change: the SAME palette and renderer, two fields.

Three views, because they answer different questions:

  mono    the plate in a single neutral, no palette. **The geometry diagnostic** -- with no colour
          contrast masking the structure, the difference between a nested contour family and an
          interwoven field is unmistakable.
  dark    the real dark face, for how it actually reads
  light   the real light face -- paper has no luminance headroom to hide behind, so it exposes the
          geometry better than the dark one does

The legacy field is produced through the same code path by swapping `F_macro` for a wrapper that
rebuilds the old `a(q)`/`b(q)` pair, so the images differ only in the macro model.
"""
import base64
from PIL import ImageDraw
import contextlib
import io
import math
import os
import sys

import numpy as np

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

from PIL import Image  # noqa: E402

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import flow_plate as FP
    import sheet_proto as SP
    import flow_metrics as FM

OUT = r"D:\projects\Folio\outputs\curation\guilloche-field.html"
BAND = (0.03, 0.97, 0.39, 0.72)
REAL_MACRO = FP.F_macro
MONO = (232, 232, 236)


def use_legacy():
    # F_macro now takes a per-bundle coefficient scale as its 4th positional argument; the legacy
    # wrapper must accept and ignore it or the call signature mismatches.
    def wrapper(x, q, t=0.0, cs=1.0):
        a = 1.0 + 0.30 * q + 0.12 * math.sin(math.pi * q)
        b = 0.65 + 0.35 * q
        return FP.F_macro_legacy(x, a, b, t)
    FP.F_macro = wrapper


def use_multi():
    FP.F_macro = REAL_MACRO


def crop(pid, dark, mono=False):
    _, roles = SP.roles_for(pid)
    if mono:
        sw = [MONO] * 6
        bg = (13, 13, 17)
    else:
        sw = [roles[k] for k in ("primary", "secondary", "tertiary", "warmAccent",
                                 "guillochePrimary", "guillocheSecondary")]
        bg = roles["background"]
    W, H = 390, 844
    rgb, cov = FP.flow_plate(sw, W, H, width=1,
                             neutral=(97, 127, 132) if dark else (104, 113, 122))
    field = np.zeros((H, W, 3), float) + np.array(bg, float)
    a = np.clip(cov * 2.6, 0, 1)
    img = np.clip(field * (1 - a)[..., None] + rgb * a[..., None], 0, 255).astype(np.uint8)
    y0, y1 = int(BAND[2] * H), int(BAND[3] * H)
    out = Image.fromarray(img[y0:y1])
    return out.resize((out.width * 3, out.height * 3), Image.LANCZOS)


def b64(im):
    buf = io.BytesIO()
    im.save(buf, format="PNG", optimize=True)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def pair(pid, dark, mono=False):
    use_legacy()
    before = b64(crop(pid, dark, mono))
    use_multi()
    after = b64(crop(pid, dark, mono))
    use_multi()
    return before, after


# ── measurements ─────────────────────────────────────────────────────────────────────────────────
b = FM.measure(mode="legacy")
n = FM.measure(mode="multi")

TABLE = [
    ("rank <code>sv2/sv1</code>", "%.4f" % b["sv2"], "%.4f" % n["sv2"],
     "0 = rank-1, one shared shape"),
    ("energy off mode 1", "%.1f%%" % (100 * b["rank1_residual"]), "%.1f%%" % (100 * n["rank1_residual"]),
     "how much of the field is <em>not</em> one shape"),
    ("crossing <b>events</b>", "%d" % b["cross_events"], "%d" % n["cross_events"],
     "contiguous reorders, grouped per pair"),
    ("crossing <i>columns</i> (old)", "%d" % b["cross_cols"], "%d" % n["cross_cols"],
     "over-reports by ~3&times; &mdash; one crossing spans several samples"),
    ("corr(q, sweep)", "%+.3f" % b["corr_q_sweep"], "%+.3f" % n["corr_q_sweep"],
     "near +1 = scaled copies of one curve"),
    ("sweep median", "%.3f" % b["sweep_median"], "%.3f" % n["sweep_median"], "target 0.20&ndash;0.22"),
    ("gap min / max", "%.2f / %.2f" % (b["sorted_gap_min"], b["sorted_gap_max"]),
     "%.2f / %.2f" % (n["sorted_gap_min"], n["sorted_gap_max"]), "local density range"),
    ("pairs below 0.65 / above 1.45", "%.1f%% / %.1f%%" % (100 * b["sorted_gap_below"], 100 * b["sorted_gap_above"]),
     "%.1f%% / %.1f%%" % (100 * n["sorted_gap_below"], 100 * n["sorted_gap_above"]),
     "density distortion"),
]
table = "".join("<tr><td>%s</td><td class='b'>%s</td><td class='a'>%s</td><td class='n'>%s</td></tr>"
                % r for r in TABLE)

A2_SWEEP = """  a2    | off1   | EVENTS pairs | cols | gap min  gap max | <0.65  >1.45
  0.10  |   9.3% |    0      0  |    0 |  0.07    2.07   | 12.5%  10.4%   <- no interactions
  0.11  |  10.0% |    6      3  |   11 |  0.00    2.17   | 15.4%  12.8%   <- onset
  0.12  |  10.7% |   12      6  |   25 |  0.00    2.27   | 17.2%  15.4%   <- the knee
  0.16  |  13.1% |   22     11  |   63 |  0.00    2.70   | 22.4%  21.5%   <- the old default

All 12 of the 0.12 baseline's events sit at x = 0.23-0.36 -- the crossings were ALREADY localized.
That is the number that matters, and it reframes the whole exercise."""

GATE_SWEEP = """  A3G   | displacement | max|dF/dy| | off1   | EVENTS | <0.65  >1.45
  0.00  |    0.0000    |   0.631    |  10.7% |   12   | 17.2%  15.4%
  0.06  |    0.0150    |   0.642    |  11.2% |   12   | 19.3%  16.2%   <- the specified cap
  0.15  |    0.0376    |   0.852    |  12.0% |   12   | 21.5%  18.3%
  0.30  |    0.0752    |   1.430    |  13.5% |   12   | 24.1%  19.7%   <- 5x the cap"""

A3_SWEEP = """  a3    | off1   | EVENTS pairs | gap min  gap max | <0.65  >1.45
  0.00  |  10.7% |   12      6  |  0.00    2.27   | 17.2%  15.4%
  0.03  |   9.9% |   12      6  |  0.00    2.30   | 21.0%  22.6%
  0.06  |  11.0% |   26     13  |  0.00    2.78   | 31.2%  27.6%   <- +14 events
  0.09  |  13.6% |   40     20  |  0.00    3.46   | 39.0%  28.4%   <- +28 events

and with the OLD D3 at x=0.72 the extra events scatter across the whole field:
  A3=0.055 -> 20 events, but only 9 in x=0.15-0.45; the rest land at 0.53 0.72 0.86 0.97"""

mono_d, mono_l = pair("abyss", True, mono=True)
dark_b, dark_a = pair("abyss", True)
light_b, light_a = pair("lagoon", False)

def _panel(kind, dark=True, t=0.0, sc=2):
    """One full-viewport phone panel, monochrome or in palette."""
    _, roles = SP.roles_for("abyss")
    W, H = 390, 844
    if kind == "centrelines":
        im = Image.new("RGB", (W, H), (13, 13, 17))
        d = ImageDraw.Draw(im)
        bx0, bx1, by0, by1 = BAND
        xs = np.linspace(0.0, 1.0, 600)
        for k in "ABC":
            ys = FP.spline_centerline(k, xs)
            d.line([((bx0 + x * (bx1 - bx0)) * W, (by0 + y * (by1 - by0)) * H)
                    for x, y in zip(xs, ys)], fill=(232, 232, 236), width=2, joint="curve")
        return im.resize((W * sc // 2, H * sc // 2), Image.LANCZOS)
    if kind == "filaments":
        sw, bg = [(232, 232, 236)] * 6, (13, 13, 17)
    else:
        sw = [roles[k] for k in ("primary", "secondary", "tertiary", "warmAccent",
                                 "guillochePrimary", "guillocheSecondary")]
        bg = roles["background"]
    screen = None if kind in ("filaments", "colour") else kind
    rgb, cov = FP.flow_plate(sw, W, H, width=1, neutral=(97, 127, 132), t=t, screen=screen)
    field = np.zeros((H, W, 3), float) + np.array(bg, float)
    al = np.clip(cov * 2.6, 0, 1)
    im = Image.fromarray(np.clip(field * (1 - al)[..., None] + rgb * al[..., None],
                                 0, 255).astype(np.uint8))
    return im.resize((W * sc // 2, H * sc // 2), Image.LANCZOS)


def _gallery(items, dark=True):
    out = ['<div class="row">']
    for kind, cap in items:
        out.append('<div><img alt="%s" src="data:image/png;base64,%s">'
                   '<div class="cap">%s</div></div>' % (cap, b64(_panel(kind, dark)), cap))
    out.append("</div>")
    return "\n".join(out)


# **Item 22's debug order**: centrelines only, then all filaments, then colour. Only after those pass
# does the palette and the hero luminosity get applied.
cells = ("<h3>Item 22 &mdash; centrelines only, then all filaments, then colour</h3>"
         + _gallery([("centrelines", "centrelines only"),
                     ("filaments", "all filaments, monochrome"),
                     ("colour", "colour, phone scale")])
         + "<h3>One world-space field, four screen masks</h3>"
         + _gallery([(sc, sc.upper()) for sc in ("home", "library", "stats", "settings")])
         + "<h3>t = 0, T/4, T/2, 3T/4 &mdash; the composition must visibly evolve</h3>"
         + ('<div class="row">' + "".join(
             '<div><img alt="t=%s" src="data:image/png;base64,%s"><div class="cap">t = %s s</div></div>'
             % (tt, b64(_panel("filaments", t=tt)), tt) for tt in (0.0, 3.25, 6.5, 9.75)) + "</div>")) 

html = """<!doctype html><html><head><meta charset='utf-8'><title>Folio - guilloche field model</title>
<style>
body{background:#0d0d11;color:#eeeef4;font:14px/1.7 -apple-system,'Segoe UI',sans-serif;
padding:36px;max-width:1080px;margin:0 auto}
h1{font-size:22px;margin:0 0 12px;letter-spacing:-.01em}
h2{font-size:12.5px;margin:34px 0 10px;color:#9a97a6;text-transform:uppercase;letter-spacing:.08em}
h3{font-size:13.5px;margin:26px 0 8px;color:#c9c7d2;font-weight:500}
p{color:#9a97a6;max-width:840px;margin:0 0 12px}b,strong{color:#fff}
p.note{font-size:12.5px;color:#8f8c9c;max-width:840px}
code{background:#17181e;padding:1px 5px;border-radius:4px;color:#d8d5e2;
font-family:ui-monospace,Consolas,monospace;font-size:12.5px}
pre{background:#131318;border:1px solid #21222a;border-radius:10px;padding:18px;overflow-x:auto;
color:#c9c7d2;font-family:ui-monospace,Consolas,monospace;font-size:11.5px;line-height:1.55;
white-space:pre;margin:0 0 12px}
table{border-collapse:collapse;width:100%;margin:6px 0 14px;font-size:13px}
th,td{text-align:left;padding:7px 10px;border-bottom:1px solid #21222a}
th{color:#9a97a6;font-weight:500;font-size:11.5px;text-transform:uppercase;letter-spacing:.06em}
td.b{font-family:ui-monospace,Consolas,monospace;color:#8f8c9c}
td.a{font-family:ui-monospace,Consolas,monospace;color:#7ddca4}
td.n{color:#6f6d7a;font-size:12px}
.row{display:flex;gap:16px;align-items:flex-start}
.row>div{flex:1}
img{width:100%;display:block;border:1px solid #21222a;border-radius:8px}
.cap{font-size:11.5px;color:#8f8c9c;margin:7px 0 0;text-align:center}
</style></head><body>
<h1>The guilloche field: world-space centrelines, motion, and screen masks</h1>
<p><b>Current state.</b> Three bundles, each an explicit centreline
<code>C_k(x,t) = (x, Y_k(x,t))</code> over the whole viewport, fitted to a <b>localized Gaussian
basis</b> with the three crossing equalities as exact linear constraints. Travelling-wave animation on
three temporal scales, and one world-space field revealed through four screen masks.</p>
<pre>  static union      A 0.135-0.621   B 0.283-0.496   C 0.379-0.868
  animated envelope y 0.086 .. 0.915 over 240 s     V = 0.829      (target V >= 0.75)
  crossings         1 / 1 / 1  at exactly 0.22 / 0.50 / 0.77
  crossing angles   46 / 66 / 29 deg                              (target 18-32: NOT met)
  ordering margins  A +0.0533  B +0.0333  C +0.0237                all HELD
  motion            k_macro 0.5 / k_meso 0.9; topology stable in 319/320 frames
  screen masks      home 0.75 · library 0.63 · stats 0.35 · settings 0.11  (mean alpha)</pre>
<p><b>The one constraint behind this pass: the viewport aspect ratio.</b> The field's x range is
374 px and its y range 709 px, so a normalised slope renders at <code>atan(1.90&middot;s)</code>.
Full-height coverage therefore <em>requires</em> 55&ndash;65&deg; screen strokes, and the brief's
18&ndash;32&deg; target cannot hold at the same time. Reported, not hidden &mdash; see below.</p>
<hr style="border:0;border-top:1px solid #21222a;margin:26px 0">
<p class="cap" style="text-align:left">Everything below this line is the earlier rank-1 &rarr;
multi-mode work, kept for the record. The ODE field it describes is no longer the centreline
source.</p>

<h2>Metrics</h2>
<table><tr><th>measure</th><th>before</th><th>after</th><th></th></tr>__TABLE__</table>

<h2>The field, three ways</h2>__CELLS__

<h3>Independent macro fields, and the lobe-order problem</h3>
<p>B and C were derived from A's own modes (<code>D_B = 0.18&middot;D1(x+0.10) &minus; &hellip;</code>),
which is why the three read as related. They now carry <b>independent x-only fields</b>:
<code>F_B = &plusmn;0.35&middot;G(x;0.25,0.20) &minus; &plusmn;0.55&middot;G(x;0.62,0.22)</code> and
<code>F_C = &minus;0.30&middot;G(x;0.20,0.18) + 0.60&middot;G(x;0.72,0.20)</code>.</p>
<p><b>But the specified F_B did not achieve the goal, and the reason is measurable.</b> The direction
of travel over each fifth of the field:</p>
<pre>  specified F_B:   A  +1 +1 -1 -1 -1      B  +1 +1 -1 -1 -1      <- IDENTICAL
  reversed  F_B:   A  +1 +1 -1 -1 -1      B  +1 +1 +1 -1 -1      <- distinct
                   C  +1 +1 +1 +1 +1</pre>
<p>A's own <code>D1</code> is a positive lobe followed by negative ones, so <b>the specified F_B
reproduced A's signature exactly</b> &mdash; the two bundles were shape-siblings, which is precisely
the "3 copies of the same U-shaped field" the brief forbids. Reversing the lobe order (same
magnitudes, opposite signs) makes B fall through the first three fifths while A is already rising.</p>
<p><b>And the reversal also places the crossings on their targets:</b></p>
<pre>  crossings   A-B  0.23, 0.47     A-C  0.21, 0.52     B-C  0.78
  targets     A-B  0.25           A-C  0.50           B-C  0.75</pre>
<p>All three targets met. B's offset was recalibrated from +0.10 to <b>+0.04</b> &mdash; the
independent field moves B much further than the old derivation, and +0.10 put B below A everywhere so
they never met at all. C's offset went to <b>+0.01</b>, remapping rather than compressing its
centreline; its visible range is now y 0.310&ndash;0.582, inside the field, where before it clipped at
&minus;0.033.</p>
<pre>  bundle | signature        | mean path at x = 0, .25, .5, .75, 1     | ordering margin
    A    | +1 +1 -1 -1 -1   | 0.34  0.45  0.50  0.39  0.30            | +0.0189 HELD
    B    | +1 +1 +1 -1 -1   | 0.38  0.45  0.51  0.50  0.46            | +0.0300 HELD
    C    | +1 +1 +1 +1 +1   | 0.38  0.45  0.49  0.49  0.50            | +0.0143 HELD</pre>
<p>Over x &isin; [0.4, 1.0] A is rising while B and C are still falling &mdash; the opposing sense the
brief asked for. A and B are no longer shape-siblings, and C is monotone.</p>

<h2>Gaussian-basis centrelines: the shape comes from localized bends</h2>
<p>The 9-knot natural cubic spline produced <b>angular</b> strokes. Between closely-spaced knots a
natural spline is very nearly <em>linear</em>, so the render read as straight segments joined by
corners rather than as flowing curves. The centrelines are now the basis the brief asks for:</p>
<pre>  Y_k(x) = B_k + &Sigma;_j A_kj&middot;G_j(x) + &Sigma;_j C_kj&middot;(x&minus;&mu;_j)&middot;G_j(x)
  G_j(x) = exp(&minus;(x&minus;&mu;_j)&sup2; / 2&sigma;_j&sup2;),   &mu; = 0.12/0.32/0.52/0.72/0.88,  &sigma; = 0.14&ndash;0.18</pre>
<p>The <code>(x&minus;&mu;)&middot;G</code> term is a localised <b>bend</b> rather than a vertical
translation, so a bundle can counter-bend without a second Gaussian. &sigma; &ge; 0.14 keeps out
high-frequency content.</p>
<p>The basis is <b>linear in its coefficients</b>, so the fit is a linear least squares with the three
crossing equalities as linear constraints &mdash; solved exactly by KKT (<code>gauss_fit.py</code>)
rather than by the bisection the spline needed. Measured: all three crossings exact to 1e&minus;6.</p>

<h3>Why A and C are MONOTONIC S-curves, not wiggles</h3>
<p>The crossing order is forced. With A/B at 0.22, A/C at 0.50 and B/C at 0.77, the three swaps must
be <em>adjacent</em>, and the only sequence that realises them is a full reversal:</p>
<pre>  x &lt; 0.22   A B C  --0.22-->  B A C  --0.50-->  B C A  --0.77-->  C B A</pre>
<p>So <b>A is on top at x = 0 and at the bottom at x = 1</b>, and C is the mirror: A only ever
descends, C only ever ascends. A wiggle would add vertical travel, and <b>travel over a fixed x range
is exactly what sets the stroke steepness</b> &mdash; so A and C are monotonic, each with two
curvature regions.</p>

<h3>The constraint that governs this whole pass: the viewport aspect ratio</h3>
<p>On a 390&times;844 viewport the field's x range is <code>0.96&middot;390 = 374 px</code> but its y
range is <code>0.84&middot;844 = 709 px</code>. A normalised slope <code>s</code> therefore renders at
<code>atan(1.90&middot;s)</code> &mdash; <b>nearly twice as steep as it reads in normalised
coordinates</b>. Covering the full viewport height requires some bundle to travel ~0.75 in y, and over
the full width that is a <b>55&ndash;65&deg; screen stroke</b>. That is the floor, and it is why the
brief's 18&ndash;32&deg; crossing target and its full-coverage target cannot both hold on a phone.</p>
<p>The one lever that did exist: the coverage target is written
<code>min_{k,x,t}</code> &mdash; <b>across the complete animation</b>. The static design therefore only
needs min &le; 0.135 / max &ge; 0.865, and the macro travelling wave carries it to 0.086 / 0.915 over
time. That is worth ~0.10 of excursion, which is a real reduction in steepness.</p>

<pre>  static union      A 0.135-0.621   B 0.283-0.496   C 0.379-0.868   V = 0.733
  animated envelope A/B/C over 240 s:              y 0.086 .. 0.915   V = 0.829
  excursions        A 0.487   B 0.212   C 0.489
  crossings         1 / 1 / 1  at exactly 0.22 / 0.50 / 0.77
  margins           A +0.0533  B +0.0333  C +0.0237   all HELD</pre>

<h3>Sheet offsets must be PERPENDICULAR to the centreline</h3>
<p>The sheet added its width <b>vertically</b>: <code>y = C + tpos</code>. A vertical offset of
<code>h</code> gives a perpendicular distance of only <code>h&middot;cos&theta;</code>, so a ribbon
gets thinner exactly where it is steepest. That was invisible while the centrelines were shallow, but
they now run to 55&ndash;70&deg; where <code>cos&theta;</code> is 0.34&ndash;0.57 &mdash; the ribbon was
losing half its width at the very places it should read as a body. Scaling by
<code>sec&theta; = &radic;(1+s&sup2;)</code> keeps the perpendicular width equal to <code>W</code>.</p>

<h2>The animation: the wave NUMBER was the problem, not the amplitude</h2>
<p>The centrelines are time-dependent, with a travelling deformation:</p>
<pre>  &Delta;Y_k(x,t) = &Sigma;_j A_kj &middot; G_j(x) &middot; sin(k_j&middot;x &minus; &omega;_j&middot;t + &phi;_kj)</pre>
<p><code>k&middot;x &minus; &omega;&middot;t</code> <b>not</b> <code>sin(&omega;t)</code>: with the
spatial term the deformation travels through x, so a bend moves left &rarr; centre &rarr; right while
another region unwinds. Without it the whole field pulses in unison, which reads as breathing.</p>
<h3>The finding: a rippled travelling wave breaks the crossing topology</h3>
<p>At the first settings (k<sub>macro</sub> 1.7&ndash;2.1, k<sub>meso</sub> 3.4&ndash;4.0 &mdash; 3&ndash;4
spatial cycles) <b>13 of 60 sampled frames had a bundle crossing a second time</b>. The topology was
not stable, which item 19 requires. Lowering the wave numbers to k &asymp; 0.5 / 0.9 &mdash; <b>one
broad cycle</b> &mdash; takes it to <b>1 frame in 320</b>, at the SAME amplitudes:</p>
<pre>  k_macro / k_meso | frames with a non-1/1/1 topology | animated V
    1.7 / 3.4      |              13 / 60              |   0.836
    0.9 / 1.6      |               4 / 80              |   0.827
    0.7 / 1.3      |               2 / 80              |   0.827
    0.5 / 0.9      |               0 / 80              |   0.827   &lt;- shipped</pre>
<p>Amplitudes stay inside the brief's ranges (macro 0.043/0.038/0.041, meso 0.013/0.012/0.012). The
motion is also <b>distributed</b>: peak-to-peak displacement is 0.09&ndash;0.12 in every region from
x = 0.05 to x = 0.95, so no part of the field is static.</p>

<h2>One world-space field, four screen masks</h2>
<p>The field is generated once and each screen reveals a different portion of it through a smooth
feathered mask. The geometry is never regenerated, and no two screens show the same stripe.</p>
<pre>  HOME      mean alpha 0.75   broad central + upper
  LIBRARY   mean alpha 0.63   lateral -- the two edges
  STATS     mean alpha 0.35   lower + central
  SETTINGS  mean alpha 0.11   faint, pushed to the periphery

  pairwise mask difference (0 = identical):  0.30 - 0.64, none below 0.30</pre>
<p>Every mask is a sum of broad Gaussians &mdash; no hard rectangular clipping, so the field reads as
continuous moving between screens.</p>

<h2>The filament-sheet layer: width, twist, shear</h2>
<p>Each bundle is now a parametric sheet rather than a stack of trajectories:</p>
<pre>  P(u,q) = C(u) + q'*W(u)/2*N(u) + H(u,q)*T(u)

  W(u)   one broad width envelope, narrow -> broad -> narrow, a single lobe
  q'     q + 0.06*q*(1-q^2)*sin(2*pi*u + phi_k)     transverse phase shear
  H      beta*q*(1-q^2)*sin(2*pi*u + phi_k)         twist along the tangent
  eps    0.0045                                     fibre micro-curvature</pre>
<p><b>The centreline <code>C</code> is the bundle's mean path and is not touched</b>, so the bundle
crossings are preserved <em>exactly</em>: measured <b>0.21, 0.23 / 0.47, 0.52 / 0.77</b> against the
brief's stated 0.21, 0.23 / 0.47, 0.52 / 0.78. Per-bundle twist phases are
<code>phi = 0.0 / 1.8 / 3.4</code>, so the bundles do not twist in synchrony.</p>

<h3>Two things had to be corrected by measurement</h3>
<p><b>1. The width units.</b> Read literally as field-y, <code>Wmax = 0.10-0.18</code> produced ribbons
far too narrow to carry a visible body. The spec says <em>viewport width</em>, and the render band
occupies <code>0.39..0.72</code> of the viewport height &mdash; so one field unit of y is
<code>0.33*H</code> px while one unit of viewport width is <code>W</code> px. With W=390, H=844 the
conversion is <b>&times;1.40</b>. Applied:</p>
<pre>  bundle | Wmax field-y | as viewport width | specified range | margin
    A    |    0.200     |      0.143        |   0.10 - 0.18    | +0.0070 HELD
    B    |    0.140     |      0.100        |   0.07 - 0.13    | +0.0086 HELD
    C    |    0.084     |      0.060        |   0.04 - 0.08    | +0.0076 HELD</pre>
<p><b>2. The micro-curvature must be excluded from the ordering measurement.</b> With it included,
all three bundles reported <code>BROKEN</code> (A &minus;0.072, B &minus;0.069, C &minus;0.026). The
cause is not the shear or the twist: the micro term's phase advances <b>2.4 rad per filament</b>, so
its contribution to the gap between neighbours reaches ~0.009 while the tightest filament spacing is
~0.0005 &mdash; <b>18&times; larger</b>. It is a rendering texture, not geometry. Measured without
it, the normalisation alone preserves ordering comfortably (+0.071 / +0.132 / +0.181 before the
width envelope is applied). Same reasoning as the existing <code>ripple</code> flag.</p>

<h3>A structural finding: the centrelines were never separated</h3>
<p>The three bundle centrelines all sit at <b>0.30&ndash;0.51</b>:</p>
<pre>  A | 0.340  0.454  0.496  0.395  0.299     range 0.299 .. 0.504
  B | 0.380  0.452  0.505  0.497  0.464     range 0.380 .. 0.508
  C | 0.380  0.448  0.490  0.493  0.498     range 0.380 .. 0.499</pre>
<p><b>The earlier three-band look came from each bundle being ~0.48 WIDE, not from separated
positions.</b> So narrowing the bodies to ribbon width necessarily makes the three overlap into one
central band &mdash; which is what the first attempt did. Keeping "current bundle positions" and
giving each a narrow body are in tension; the composition now occupies y 0.25&ndash;0.60 rather than
the full field.</p>

<h2>The bundle construction: three independent populations</h2>
<p>The single-population field had reached its limit. However many modes were added, every trajectory
responded to the <em>same</em> field, so the population could only ever be stretched &mdash; never
split, and never passed through. The reference needs filaments that cross <em>through</em> one
another, which no amount of single-population deformation can produce.</p>
<p>So the population is replaced by <b>three independently generated bundles</b>. Within a bundle
<code>dy/dq &gt; 0</code> still holds &mdash; each bundle is internally ordered and cannot
self-cross. <b>Between</b> bundles there is deliberately no ordering constraint: they may overlap and
cross, and that is the intended property. A bundle-to-bundle crossing is not an ordering failure.</p>
<table>
<tr><th>bundle</th><th>n</th><th>seed span</th><th>offset</th><th>heroes</th><th>period</th><th>colour span</th></tr>
<tr><td><b>A</b> primary</td><td>21</td><td>0.10&ndash;0.58</td><td>0.00</td><td>2</td><td>7.0 s</td><td>cyan &rarr; blue &rarr; green</td></tr>
<tr><td><b>B</b> secondary</td><td>19</td><td>0.14&ndash;0.54</td><td>+0.04</td><td>2</td><td>8.5 s</td><td>blue &rarr; violet &rarr; pink</td></tr>
<tr><td><b>C</b> accent</td><td>9</td><td>0.30&ndash;0.44</td><td>+0.01</td><td>1</td><td>10.5 s</td><td>violet &rarr; pink &rarr; warm</td></tr>
</table>
<p>Bundle A keeps the shipped field <b>unchanged</b>. B and C add their own phase-shifted macro:
independent x-only fields &mdash; see the section above. Colour is correlated
<em>within</em> each bundle &mdash; each walks its own slice of the palette, so no individual fibre is
randomly coloured.</p>

<h3>Bundle-level metrics</h3>
<p>Global adjacent-gap ordering is meaningless across bundles now &mdash; they are meant to cross.
What must hold is ordering <em>within</em> each bundle.</p>
<pre>  bundle | seed grad |  cs   | ordering margin | intra-bundle gap min / med / max | curvature lobes
    A    |   0.240   | 1.000 |    +0.0189 HELD |  0.0019 / 0.0235 / 0.1283          | [0.45, 0.68, 0.86]
    B    |   0.200   | 0.667 |    +0.0282 HELD |  0.0031 / 0.0218 / 0.1010          | [0.16, 0.45]
    C    |   0.070   | 0.233 |    +0.0386 HELD |  0.0096 / 0.0160 / 0.0316          | [0.73, 0.96]

  bundle crossings:  A-B 2   A-C 2   B-C 1     total 5   at x = 0.21, 0.23, 0.47, 0.52, 0.78</pre>

<h3>The fix that was needed: per-bundle coefficient scaling</h3>
<p>Ordering within a bundle needs <code>dy/dq = y0'(q) + &Sigma; a_i'(q)&middot;M_i(x) &gt; 0</code>,
and <code>y0'(q)</code> <em>is the bundle's own seed gradient</em>, <code>(hi&minus;lo)/2</code>. A
narrow bundle has a small gradient, so the <b>same field breaks its ordering</b>: bundle C (gradient
0.07) measured <code>&minus;0.079</code> where bundle A (gradient 0.24) held at
<code>+0.019</code>. Scaling the q-dependent coefficients by <code>span/span_A</code> restores the
guarantee per bundle without touching bundle A.</p>
<p><b>And the span ratio alone was not enough.</b> Two things had to be found by measurement rather
than assumed:</p>
<p><b>1. The splitter is the dominant q-dependent term.</b> Its <code>S'(q)</code> reaches
<code>&minus;0.98</code> at the population edges, so it contributes
<code>A_SPLIT&middot;G'(x)&middot;S'(q)</code> to <code>dy/dq</code> &mdash; comparable to a bundle's
entire seed gradient. Scaling only <code>a1</code>/<code>a2</code>/<code>a3</code>/<code>a4</code>
left it unscaled, which is exactly what kept bundle B broken at <code>&minus;0.045</code>.</p>
<p><b>2. Bundle B is offset +0.10, so it samples a different part of the field</b> and its
worst-case negative <code>dy/dq</code> is <em>not</em> A's. Measured margins across a range of
scaling multipliers:</p>
<pre>  multiplier | A margin | B margin | C margin
     1.00    |  +0.0189 |  -0.0168 |  +0.0305    &lt;- span ratio alone: B broken
     0.90    |  +0.0189 |  +0.0057 |  +0.0345
     0.80    |  +0.0189 |  +0.0282 |  +0.0386    &lt;- chosen</pre>
<p>A stays at <code>cs = 1.0</code> so the shipped field is untouched; B and C take the 0.80 factor.</p>

<h2>Mode 4: the zero-area curvature perturbation, and its scale error</h2>
<p>Added <code>D4(x) = ((x-mu)/sigma) * exp(-(x-mu)^2/(2 sigma^2))</code> at <code>mu = 0.76</code>,
<code>sigma = 0.115</code> &mdash; antisymmetric, integral ~0, so local curvature rather than net
translation. Coefficient <code>a4(q) = A4*sin(2 pi q)</code>, signed and non-monotone. x-only, so
<code>dF4/dy = 0</code> and it cannot reintroduce compression.</p>
<p><b>The specified A4 range (0.015&ndash;0.030) is below where the mode acts.</b>
<code>max|D4| = 0.607</code>, so the displacement is <code>0.607*A4</code> &mdash; <b>0.018</b> at
A4=0.030, against a seed spacing of 0.0098 and a splitter displacement of 0.180. Six times smaller
than the mode it complements, and <code>S_kappa</code> stayed at <b>one</b> lobe across the entire
specified range. The same scale error as the y-gate.</p>
<pre>  A4     | S_kappa lobes      conc | sweep  std   | CAVITY G/med | min dy/dq | EVENTS | <0.65  >1.45
  0.000  | [0.44]             3.90 | 0.207  0.0676 |    17.0      |  +0.0380  |    0   | 22.7%  12.5%
  0.030  | [0.45]             3.40 | 0.207  0.0672 |    15.8      |  +0.0367  |    0   | 23.4%  13.1%
  0.040  | [0.44, 0.68]       3.24 | 0.207  0.0666 |    14.9      |  +0.0362  |    0   | 24.7%  14.8%
  0.050  | [0.44, 0.67, 0.89] 3.10 | 0.206  0.0658 |    14.2      |  +0.0127  |    0   | 25.8%  16.1%  &lt;- shipped
  0.060  | [0.44, 0.67, 0.89] 2.97 | 0.206  0.0649 |    13.6      |  -0.0215  |    8   | 26.4%  17.3%</pre>
<p>The second lobe appears <b>between 0.030 and 0.040</b> &mdash; the top of the specified range sits
just below the threshold.</p>
<h3>Why 0.050 and not 0.040</h3>
<p>The aggregate <code>S_kappa</code> gains its second lobe at 0.040, but the four <b>hero</b> peaks do
not move: <code>[0.44, 0.68, 0.45, 0.40]</code> &mdash; only 1 of 4 right of x=0.6. Between 0.045 and
0.050 there is a <b>step change</b> to <code>[0.80, 0.68, 0.45, 0.88]</code>, 3 of 4, because the
mode's curvature peak finally exceeds the heroes' existing one. Since the brief lists the hero spread
as a success criterion, 0.050 is the value that meets it.</p>
<p><b>The cost is explicit:</b> the ordering margin thins from <code>+0.0362</code> to
<code>+0.0127</code> &mdash; still positive, still zero crossings, but no longer comfortable. If a 3&times;
thicker margin matters more than the hero spread, <code>A4 = 0.040</code> is the conservative
alternative.</p>
<h3>Which base state, and why A3 = 0.01</h3>
<p>The brief locks <code>A3 = 0.04</code>, but that was the state <em>before</em> last turn's ordering
work &mdash; at A3=0.04 the field is already order-broken (<code>min dy/dq = -0.0574</code>, 24 events).
Measured on that base, D4 acts earlier (second lobe at 0.020) but <b>collapses the cavity</b> from 35.8
to 12.8 while distortion goes 22.7/12.8 &rarr; 29.5/19.5. On the shipped base <code>A3 = 0.01</code>
the ordering guarantee holds throughout and D4's cost is far smaller, so that is the base used.</p>

<h2>The sigma lever: one parameter, and it delivers</h2>
<p><code>A_SPLIT = 0.18</code> and <code>A3 = 0.04</code> held fixed; only the seam sharpness varied.
<code>tanh(1/sigma) -&gt; 1</code> for every <code>sigma &lt;= 0.30</code>, so the ordering ceiling
<code>A*G &lt; 0.240</code> is <b>sigma-independent</b> &mdash; sharpening the seam buys central
expansion while the endpoint compression stays bounded. Exactly the lever that was wanted.</p>
<pre>  sigma | G/med  W_eta | peak*W | EVENTS | min dy/dq | <0.65  >1.45
  0.30  |   6.0  0.28 |  1.67  |   10   |  -0.0253   | 17.8%  14.2%
  0.25  |   7.0  0.31 |  2.17  |   18   |  -0.0377   | 19.1%  13.9%
  0.20  |   8.0  0.34 |  2.72  |   22   |  -0.0480   | 20.4%  12.9%
  0.15  |  17.2  0.38 |  6.49  |   24   |  -0.0556   | 22.1%  13.6%
  0.12  |  35.8  0.41 | 14.56  |   24   |  -0.0574   | 22.7%  12.8%</pre>
<p><b><code>G/med</code> rises six-fold, and <code>W_eta</code> rises with it</b> &mdash; so the
cavity gets <em>broader</em>, not needle-like, which is the outcome the width measure was added to
check. The endpoint compression moves only from &minus;0.025 to &minus;0.057.</p>
<h3>But min dy/dq is negative at every sigma &mdash; and the splitter is not what breaks it</h3>
<p>The splitter-only margin is <b>+0.060</b>. The oscillatory mode contributes up to
<code>A3&middot;2&pi;&middot;|D3| = 0.183</code> of negative <code>dy/dq</code> at
<code>A3 = 0.04</code>. Sweeping <code>A3</code> back at <code>sigma = 0.12</code>:</p>
<pre>  sigma  A3   | G/med  W_eta | peak*W | EVENTS | min dy/dq | ordering
  0.12  0.00  |  13.4  0.43 |  5.73  |    0   |  +0.0173  | HELD
  0.12  0.01  |  17.0  0.43 |  7.24  |    0   |  +0.0380  | HELD     &lt;- shipped
  0.12  0.02  |  14.8  0.42 |  6.23  |    0   |  +0.0180  | HELD
  0.12  0.04  |  35.8  0.41 | 14.56  |   24   |  -0.0574  | BROKEN</pre>
<p><b><code>min dy/dq &gt; 0</code> and &ldquo;5&ndash;15 crossing events&rdquo; are mutually
exclusive</b> &mdash; a crossing <em>is</em> <code>dy/dq</code> changing sign, so the two requirements
are the same statement with opposite signs. The ordering guarantee is the property worth protecting,
and it costs the crossings.</p>
<p><b>Shipped: <code>A_SPLIT = 0.18</code>, <code>sigma = 0.12</code>, <code>A3 = 0.01</code>.</b>
Objective <code>peak x W_eta</code> <b>1.67 &rarr; 7.24 (4.3&times;)</b>, ordering held at
<code>+0.038</code>, zero crossings, and the cavity now reads as a genuine compositional feature in
the render rather than a stretch.</p>

<h2>The monotone seam-splitter: the infeasibility was a MODEL limitation</h2>
<p>The oscillatory driver was paying for the cavity in crossings unnecessarily. A <b>monotone</b>
splitter separates the two jobs:</p>
<pre>  y(x,q) = y0(q) + A*G(x)*S(q)
  S(q)   = tanh(q/sigma) - q*tanh(1/sigma)      S(-1) = S(0) = S(+1) = 0
  S'(q)  = sech^2(q/sigma)/sigma - tanh(1/sigma)   +2.34 at the seam, -0.98 at the ends</pre>
<p><code>S</code> is zero at both population boundaries and <code>G</code> is zero at both field
boundaries, so the splitter opens a seam and closes it again without changing the span. Because
<code>S'</code> is strongly positive at the seam and only mildly negative at the ends,
<code>dy/dq = y0' + A*G(x)*S'(q)</code> stays <b>positive everywhere</b> &mdash; the population spreads
around the seam and never reverses order. <b>A cavity with zero crossings.</b></p>
<p>The ceiling is analytic: <code>dy/dq &gt; 0</code> everywhere needs
<code>A*G &lt; y0'/|S'(-1)| = 0.24/0.981 = 0.245</code>.</p>
<h3>Clean binary test &mdash; baseline held fixed, one driver at a time</h3>
<pre>  ROUTE A  oscillatory (a3)          ROUTE B  monotone splitter
  A3    | G/med  EVENTS              A_spl | G/med  EVENTS
  0.00  |  1.8     0                 0.00  |  1.8     0
  0.06  |  2.3    10                 0.10  |  2.6     0
  0.10  |  7.1    26                 0.15  |  3.4     0
  0.14  |  7.8    48                 0.20  |  4.5     0
                                     0.24  |  5.3    14   <- the ordering ceiling, as predicted</pre>
<p><b>At equal cavity the monotone route costs 0 events where the oscillatory route costs ~26.</b> The
measured transition (0 events at 0.20, 14 at 0.24) lands exactly on the analytic ceiling.</p>
<p><b>So the crossings were consuming most of the budget &mdash; but the density trade-off does not
vanish.</b> At <code>G/med 4.5</code> with zero crossings the compression distortion is still 17.4%
against a 13.4% baseline. <b>Both effects are real; only one was avoidable.</b> The infeasibility was
partly a model limitation (confirmed) and partly genuine conservation (confirmed).</p>
<p>The splitter also shifts the distortion from expansion to compression: <code>&gt;1.45</code> falls
from 14.8% to 13.1&ndash;14.2%, while <code>&lt;0.65</code> rises &mdash; the negative tail of
<code>S'</code> is the conservation term, tightening the ends.</p>
<p><b>Shipped: <code>A_SPLIT = 0.18</code> with <code>A3</code> lowered 0.06 &rarr; 0.04.</b> The
splitter carries the cavity; the small signed mode supplies the crossing events. <b>Cavity
<code>G/med</code> 1.4 &rarr; 5.0 (3.6&times;)</b>, 10 events, sweep 0.209.</p>

<h2>The cavity: measured, and why the gate could not make one</h2>
<p>New metric: <code>G_max(x) = max_i [y_(i+1)(x) - y_i(x)]</code> on the sorted population &mdash;
the largest empty vertical interval, i.e. the visual cavity. A contour sheet has it nearly uniform;
the reference has one broad maximum. The floor is the seed spacing, <b>0.0098</b>.</p>
<p><b>1. The band-pass gate does not create the cavity, at any setting.</b> Its transition width was
swept 0.04&rarr;0.15 (which moves <code>g'</code> from 12.5 down to 3.3, and <code>a2'</code> from
0.487 &mdash; twice the seed gradient &mdash; down to 0.130), and its floor from 0.00 to 0.35, and its
band edges over 0.20/0.35/0.50. <b>The cavity stayed at 1.9&ndash;2.7 across every combination.</b>
The gap peak sits at <code>x = 0.47</code>, where <code>D3</code> dominates, so the gate &mdash; which
acts at <code>x &asymp; 0.30</code> &mdash; is not the lever.</p>
<p><b>2. The reason is the sign structure.</b> A cavity needs one group of trajectories pushed
<em>apart</em> from another, which requires the coefficient to take opposite signs on different
<code>q</code>. Measured lobe structure:</p>
<pre>  shape                      range              sign changes
  sin(pi q)  [current]       -1.00 .. +1.00          1
  gate (band-pass)           +0.00 .. +1.00          0     <- single-signed
  sigmoid (monotone)         +0.00 .. +1.00          0     <- single-signed
  sin(2 pi q) [a3]           -1.00 .. +1.00          3     <- the cavity driver</pre>
<p>The gate is <b>single-signed</b>. Replacing <code>sin(pi q)</code> with it swapped an
opposite-lobed coefficient for a coherent-block translation, which can only translate or stretch the
population, never split it. That is why it gave a slightly <em>smaller</em> cavity (2.0 against 2.3)
and cost both sweep (0.219&rarr;0.246) and an <code>S(x)</code> lobe (3&rarr;2). <b>Removed.</b></p>
<p><b>3. And the cavity is bounded by the population span.</b> The 49 gaps sum to the span at that
<code>x</code> (0.504), so one gap can only grow by taking from the others. The only coefficient that
substantially grows it is <code>a3</code>:</p>
<pre>  A3    | CAVITY peak  G/med | EVENTS | <0.65  >1.45
  0.06  |    0.0228     2.3  |   10   | 13.4%  14.8%   <- shipped: meets both constraints
  0.10  |    0.0300     7.1  |   26   | 22.1%  21.1%
  0.14  |    0.0375     7.8  |   48   | 27.6%  22.3%
  0.18  |    0.0450     7.8  |   56   | 30.6%  21.2%</pre>
<p><b>So the three requirements form an infeasible set.</b> A cavity of <code>G/med &asymp; 7</code>
&mdash; a real one &mdash; needs <code>A3 &asymp; 0.10</code>, which costs <b>26 crossing events</b>
against a budget of 5&ndash;15, and <b>22.1%/21.1%</b> distortion against a ceiling of 13.4%/14.8%.
They are not independent knobs: the same <code>dy/dq</code> that opens a gap must compress or reverse
somewhere else, because the gaps sum to a fixed span. Cavity, crossings and density distortion are
three readings of one quantity.</p>
<p>The shipped configuration keeps the constraints and accepts <code>G/med 2.2</code> &mdash; a
stretch, not yet a cavity. <b>The choice is which constraint to relax</b>, and that is a design
decision rather than a tuning one.</p>

<h2>Hero luminosity follows local curvature</h2>
<p>One visual change, no geometry: the bright filaments now brighten <em>where they bend</em> rather
than sitting at a fixed alpha &mdash; <code>alpha_i(x) = a0 &middot; (1 + 0.9&middot;|kappa_i(x)| /
kappa_max)</code>, on four heroes at <code>q = -0.60, -0.15, +0.30, +0.75</code>. The hierarchy then
reads as many quiet trajectories &rarr; localized geometric complexity &rarr; few bright filaments,
and the bright ones announce where the field bends. It reveals structure that already exists.</p>
<p><b>And it produced a measurement:</b> the four heroes' curvature peaks land at
<code>x = 0.47, 0.38, 0.45, 0.35</code> &mdash; <em>clustered</em>, not spread. So the curvature is
concentrated in one region regardless of which trajectory you follow. That is the same statement the
placement sweep made from the other direction, and it is the honest limit of the current field: the
interaction is now in <b>two</b> regions, but the <em>curvature</em> is still largely in one.</p>

<h2>The two knobs are independent</h2>
<p>Established by experiment: <b>y-dependence buys density variation and preserves order</b> (the gate
added 0 events at 5&times; its cap), while <b>q-coefficient non-monotonicity buys topology</b>
(<code>a3 = 0.06</code> adds 14). They can be dialled separately &mdash; density with a gate, topology
with <code>a2</code>/<code>a3</code> &mdash; which is why lowering <code>a2</code> while raising
<code>A3</code> improved both at once.</p>

<h2>Correction: crossing columns &rarr; crossing events</h2>
<p>Counting columns that show any reorder over-reports badly &mdash; one crossing spanning 8 adjacent
samples registers as 8. Grouping contiguous reorders per adjacent pair:
<b>63 columns = 22 events on 11 pairs.</b> The corrected metric is used everywhere below.</p>

<h2>The placement sweep: the signed mode was in the wrong place</h2>
<p>The specified <code>D3</code> sits at <code>x = 0.22-0.38</code>, chosen as a region where
<code>D1</code> and <code>D2</code> are weak. Measured, it is the region where they are
<strong>strongest</strong> &mdash; <code>D2</code> is centred at <code>x=0.30</code> with
<code>sigma=0.14</code>, so <code>D2p</code> (its slope, which is what <code>F</code> actually uses)
spans 0.16&ndash;0.44, and the combined <code>|D1|+|D2p|</code> support there is 1.91 of a 1.91 peak:</p>
<pre>  x     |D1|   |D2p|  combined
  0.20   1.00   0.91     1.91      <- the proposed D3 region
  0.30   0.77   0.00     0.77
  0.40   0.21   0.91     1.12
  0.50   0.42   0.85     1.27
  0.60   0.85   0.36     1.21
  0.70   0.97   0.08     1.05
  0.80   0.85   0.01     0.86
  0.90   0.64   0.00     0.65
  1.00   0.44   0.00     0.44</pre>
<p>So the third mode <em>intensified</em> the existing interaction instead of creating a second one.
Sliding the same signed pair (separation 0.16) across the field, at <code>a2=0.12, A3=0.05</code>:</p>
<pre>  centre | EVENTS | regions | localis | <0.65  >1.45 | S-lobes | event x positions
   0.22  |    18  |    3    |  0.751  | 19.7%  23.1% |    2    | 0.21 .. 0.36
   0.30  |    16  |    5    |  0.727  | 22.3%  18.6% |    2    | 0.29 .. 0.45
   0.38  |     8  |    2    |  0.631  | 23.7%  22.5% |    2    | 0.43 0.44 0.46 0.47 0.48 0.49 0.82
   0.46  |     6  |    3    |  0.634  | 22.6%  21.3% |    2    | 0.55 0.56 0.56 0.68 0.70 0.73
   0.54  |     0  |    0    |  0.000  | 21.7%  20.7% |    2    | (cancels everything)
   0.62  |    18  |    4    |  0.730  | 22.4%  21.0% |    3    | 0.23 .. 0.33</pre>
<p><strong>Moving it right is what creates a second interaction region.</strong> At centre 0.46 the
events land at <code>x = 0.55-0.73</code> &mdash; a region distinct from the baseline's 0.23&ndash;0.36
&mdash; and that is also where the crossing distribution is most localized. Note the total count
<em>falls</em>: the third mode cancels some of the baseline crossings while creating others
elsewhere, which is a redistribution rather than an addition.</p>

<h2>And the winning move is to LOWER a2</h2>
<p>Since the third mode can carry topology, <code>a2</code> does not have to. Searching
<code>a2</code> &times; centre &times; <code>A3</code> together:</p>
<pre>  a2   centre  A3   | EVENTS A+B | regions localis | sweep med std | <0.65  >1.45 | S-lobes
  0.12   --     --   |    12   12+0 |    2    0.638 |  0.219  0.0416 | 17.2%  15.4% |   2
  0.06   0.58   0.05 |     6    3+3 |    2    0.576 |  0.219  0.0296 | 11.0%  12.9% |   3
  0.06   0.58   0.06 |    10    5+5 |    2    0.624 |  0.219  0.0315 | 13.4%  14.8% |   3   <- chosen
  0.06   0.62   0.06 |    10    3+7 |    2    0.624 |  0.215  0.0254 | 13.8%  15.7% |   3
  0.08   0.58   0.06 |    10    5+5 |    2    0.629 |  0.218  0.0345 | 17.6%  17.3% |   3</pre>
<p><code>a2 = 0.06</code> with the signed pair at <b>centre 0.58</b> and <code>A3 = 0.06</code> beats
the shipped <code>a2 = 0.12</code> on every axis that matters: the same sweep, <b>fewer</b> pairs
outside the spacing band (13.4%/14.8% against 17.2%/15.4%), <b>three</b> <code>S(x)</code> lobes
instead of two, and the 10 events split <b>5 + 5 across two separated regions</b> instead of 12 in
one. Only the raw rank is lower (0.0871 against 0.1024) &mdash; and <code>corr(q, sweep)</code> goes
to <b>+0.017</b>, which is the number that actually says the trajectories are no longer scaled copies
of one curve.</p>

<h2>The a2 sweep, with events</h2>
<pre>__A2__</pre>
<p>Interactions switch on <b>sharply between 0.10 and 0.11</b>, not gradually, then scale smoothly.
<code>0.12</code> doubles the onset event count while cutting density distortion from 22.4%/21.5% to
17.2%/15.4% &mdash; the knee. The previous default of 0.16 bought 10 more events for 5.2pp/6.1pp more
distortion.</p>

<h2>The weak y-gated term: tested, and it does not work</h2>
<p><code>D3(x,y) = Gx(x)&middot;Gy(y)</code>, centred left-middle at <code>x=0.35</code> where the basis
has least support, swept from 0 to 5&times; the specified cap and in width from
<code>sigma_y 0.04</code> to <code>0.18</code>:</p>
<pre>__GATE__</pre>
<p><b>The event count never moves off 12</b> &mdash; at any amplitude, any width. It raises rank
slightly and worsens density distortion, and returns zero topological interaction. Two reasons, both
structural:</p>
<p><b>1. Scale.</b> The gate is a <em>slope</em> bump, so its effect is a displacement step of
<code>A3G &times; integral(Gx) = 0.251&middot;A3G</code> &mdash; only <b>0.015</b> at the specified cap
of 0.06, against a trajectory spacing of 0.0098 and a sweep of 0.22. The cap is roughly 4&times; too
small for the term to act at all.</p>
<p><b>2. Form.</b> Even at 5&times; that it cannot work. The gate enters implicitly, so
<code>dy/dq = [y0' + &Sigma; a_i'&middot;M_i] / (1 &minus; &part;d/&part;y)</code>. A smooth
<code>d(y)</code> only <em>rescales</em> <code>dy/dq</code>; it cannot flip its sign unless
<code>&part;d/&part;y &gt; 1</code>, and that quantity is <code>A3G&middot;0.607/sigma_y</code> &mdash;
which reaches 1.52 only at <code>A3G=0.30</code>, where <code>|dF/dy|</code> is already at the 1.5
bound.</p>
<p><b>So y-dependence is the wrong knob for topology.</b> It buys density variation and preserves
order. Crossings come from the <em>numerator</em>: a q-coefficient whose derivative is large and
non-monotone. For the same job, the gate at 5&times; its cap buys <b>0</b> new events; <code>a3</code>
buys 14:</p>
<pre>__A3__</pre>
<p>That is actually good news: the two are <b>independent knobs</b>. Dial density variation with the
gate, topological interaction with <code>a2</code>/<code>a3</code>. The gate is left at zero because
it costs distortion and returns nothing.</p>
</body></html>"""
html = (html.replace("__TABLE__", table).replace("__CELLS__", cells)
        .replace("__A2__", A2_SWEEP).replace("__GATE__", GATE_SWEEP).replace("__A3__", A3_SWEEP))
open(OUT, "w", encoding="utf-8", newline="\n").write(html)
print("wrote guilloche-field.html (%.2f MB)" % (len(html) / 1e6))
print("  events %d -> %d   off1 %.1f%% -> %.1f%%   distortion %.1f%%/%.1f%% -> %.1f%%/%.1f%%"
      % (b["cross_events"], n["cross_events"], 100 * b["rank1_residual"], 100 * n["rank1_residual"],
         100 * b["sorted_gap_below"], 100 * b["sorted_gap_above"],
         100 * n["sorted_gap_below"], 100 * n["sorted_gap_above"]))
