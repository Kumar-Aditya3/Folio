"""Build one self-contained HTML contact sheet of the curated prototypes.

The six PNGs live in `outputs/curation/web/` as JPEGs; this embeds them as base64 data
URIs so the page needs no file serving and renders anywhere. Written because
`present_files` returned no previews for the loose image files.

    python build_contact_sheet.py
"""
import base64
import hashlib
import os

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")
OUT = os.path.join(HERE, "curation.html")

ITEMS = [
    ("01-home-light-lagoon-dawn-moss-warm-arctic.jpg", "Home — light faces, 1 of 2",
     "Lagoon · Dawn · Moss · Warm · Arctic. Each page is its own authored cream; the hero card, "
     "the four shelf tiles and the week bars each take a different swatch from that face."),
    ("02-home-light-honey-rouge-sakura-silver.jpg", "Home — light faces, 2 of 2",
     "Honey · Rouge · Sakura · Silver. Same screen, four different worlds — note the hero card "
     "swings from rose (honey) to cream (silver) while the tiles stay gold."),
    ("03-home-dark-abyss-dusk-peacock-espresso-midnight.jpg", "Home — dark faces, 1 of 2",
     "Abyss · Dusk · Peacock · Espresso · Midnight. Every page is a near-black with a whisper of "
     "its own tint — graphite, not dark blue."),
    ("04-home-dark-ember-garnet-blossom-graphite.jpg", "Home — dark faces, 2 of 2",
     "Ember · Garnet · Blossom · Graphite. Ember's week bars turn yellow-green rather than "
     "collide with its orange tiles — the sheet's own two oranges are only 4&deg; apart."),
    ("05-board-light-swatches-to-roles.jpg", "The light assignment",
     "Left: the six swatches the sheet authors for each face, in sheet order. Right: the roles "
     "they became. This is the art decision, written down."),
    ("06-board-dark-swatches-to-roles.jpg", "The dark assignment",
     "Same, for the dark faces. The last swatch is read rather than assumed: on seven families "
     "it is a plane one step up from the page, on ocean and moonstone it is the ink."),
    ("07-shelf-tiles-where-the-colour-can-live.jpg",
     "Shelf tiles — where the theme colour can actually live",
     "A tile is a book thumbnail, so a themed <em>fill</em> is invisible. Row 1 is what I "
     "prototyped (the renderer fabricates a cover out of the theme, which is why the shelves "
     "looked so harmonious). Row 2 is the plate's own spine at its current treatment — barely "
     "there. Row 3 is that same spine at full strength: always drawn, same geometry on every "
     "plate, reads as a series without fighting the art. Row 4 is the coverless state, whose "
     "fallback cover is a hardcoded indigo today and should be the face's own swatches."),
    ("08-masthead-treatments.jpg",
     "The masthead — the surface the curation never looked at",
     "<code>topBarFill</code> returns <code>scrim = 1.0</code> at rest, so the status band is at "
     "full strength on every screen even when nothing has been scrolled — and "
     "<code>home_render.py</code> drew only the wordmark, while <code>check_all.py</code> does not "
     "model <code>barScrim</code> at all. Measured, the band is a <em>subtle</em> tint: on Lagoon "
     "light the page goes #F4F1E8 &rarr; #EAE8E3 (10/255), on Abyss dark #0F1720 &rarr; #0F1419 "
     "(7/255). So the band is fine and the personality is elsewhere: in the wordmark. "
     "<strong>Row 2 is the recommendation</strong> — the name in <code>accentProgress</code>, not "
     "<code>primaryAccent</code>, because the authored signature is a display colour and fails the "
     "large-text floor on four faces (arctic light 1.55:1, mintcoral light 1.78:1, moonstone light "
     "2.25:1, solar dark 2.76:1), while the fitted accent clears 4.2:1 on all eighteen."),
    ("09-masthead-crown-treatments.jpg",
     "The masthead as a committed colour, not a whisper",
     "Row 1 is the current crown and it confirms the objection: the band is <code>#EFEDE5</code> "
     "against a Lagoon page of <code>#F4F1E8</code>, and <code>#0F151C</code> against an Abyss "
     "page of <code>#0F1720</code> &mdash; it <em>is</em> the page. Rows 2&ndash;4 make the crown "
     "an authored band instead, with the name reversed out: <strong>row 2</strong> a solid "
     "<code>primaryAccent</code>, <strong>row 3</strong> the same colour fading into the page, "
     "<strong>row 4</strong> the <code>warmAccent</code> warm note. The name's ink is chosen per "
     "band (the best of the face's ink, the page, white, black) and every combination clears "
     "4.9:1 or better. <strong>Row 3 is the recommendation</strong> &mdash; unmistakable colour "
     "at the top that dissolves rather than a bar cutting the page."),
    ("10-plate-one-hue-vs-three.jpg",
     "The guilloche plate: one hue (what I drew) vs three (what the app draws)",
     "The shader blends the plate's colour per pixel from the palette's <strong>three</strong> "
     "authored guilloche hues, weighted by each layer's intensity "
     "(<code>GuillocheShader.android.kt:87</code>). <code>home_render.py</code> drew it in "
     "<em>one</em> colour &mdash; so part of the sameness you saw was the renderer lying. Fixed. "
     "Measured hue span: Ember dark <strong>12.5&deg; &rarr; 80&deg;</strong>, Garnet "
     "15&deg; &rarr; 48&deg;, Abyss 15.4&deg; &rarr; 17.6&deg;. Note also that the light plate "
     "is near-achromatic by construction &mdash; Lagoon light produced <strong>zero</strong> "
     "saturated pixels at any strength, because the light branch pins the line's luminance to "
     "the field and its <code>guillocheSecondary</code> is a near-white mint."),
    ("11-plate-hues-picked-for-separation.jpg",
     "The real cause: the plate's three hues were chosen by list order",
     "<code>assign.py</code> set <code>guilloche = (A, S1, S2)</code> on light and "
     "<code>(A, B, S1)</code> on dark &mdash; the next swatches in the sheet, with no "
     "requirement that they be far apart. Measured, <strong>five of eighteen faces had their "
     "closest plate pair under 25&deg;, and four of those five are dark</strong>: moonstone "
     "light 4&deg;, solar dark 4&deg;, ocean dark 13&deg;, forest dark 21&deg;, nebula dark "
     "23&deg;. That is exactly the &ldquo;especially in dark mode&rdquo; reading. Choosing the "
     "triple for maximum separation instead: ocean dark 13&deg; &rarr; <strong>104&deg;</strong>, "
     "solar dark 4&deg; &rarr; 67&deg;, forest dark 21&deg; &rarr; 75&deg;, terracotta 41&deg; "
     "&rarr; 104&deg;, arctic 29&deg; &rarr; 92&deg;. Two faces cannot improve &mdash; nebula "
     "dark stays at 23&deg; because its four swatches are genuinely clustered."),
    ("12-flow-field-guilloche.jpg",
     "The ribbon-bundle guilloche — one geometry, every palette",
     "The brief asks for the reference's construction: <em>parametric guilloche &rarr; parallel "
     "thread family &rarr; nonlinear warp &rarr; multiple vortices &rarr; correlated phase motion "
     "&rarr; variable density &rarr; gradient colour &rarr; restrained bloom</em>. That is a "
     "<strong>deforming 2-D flow field carrying bundles of curves</strong>, not a closed form, so "
     "this integrates streamlines of <code>v(p) = drift + shear + &Sigma; &gamma;<sub>k</sub> "
     "e<sup>-|p-c<sub>k</sub>|<sup>2</sup>/&sigma;<sub>k</sub><sup>2</sup></sup> J(p-c<sub>k</sub>)"
     "</code> with <code>J(x,y) = (-y, x)</code>, rather than evaluating harmonics. Two properties "
     "are deliberate: <strong>the field and its seeds are fixed constants</strong>, so all "
     "eighteen faces draw one pattern (that is what makes a theme set read as a set), and the "
     "colour is a ramp built by sorting the face's <em>own</em> swatches by hue and interpolating "
     "around the wheel &mdash; a re-ordering of the sheet, nothing invented. Two things it forced: "
     "a uniform <em>drift</em> (rotation fields alone converge on one attractor, which rendered as "
     "a single knot in a corner), and a coverage mask drawn from the <em>geometry</em> &mdash; "
     "deriving coverage from the colour layer's brightness meant a darker thread produced a "
     "smaller alpha, so deepening the ramp on paper faded the threads instead of strengthening "
     "them. On paper the ramp is deepened 0.62 toward the ink, because the sheet's light swatches "
     "are mid-tones and a mid-tone thread on cream is pastel."),
    ("13-renderer-fidelity-quiet-mask.jpg",
     "How faithful is the renderer? The quiet mask, drawn",
     "<strong>Colour is faithful; geometry is not.</strong> The renderer parses the live Kotlin "
     "and replays the derivation, which is why <code>render_from_file.py</code> can assert 0 "
     "differing roles and pixel-identical panels. The layout, however, is redrawn by hand, the "
     "shader is approximated by a raster, and several app behaviours are simply absent. The "
     "largest is <strong><code>GuillocheQuiet</code></strong>: the app registers text regions and "
     "collapses the plate to near zero where they sit, so the plate never competes with type. The "
     "renderer has been drawing it everywhere. Measured, the mask covers <strong>82% of the "
     "strands</strong> geometrically &mdash; though most of those already sit behind opaque "
     "content, so the visible difference is smaller than that number suggests, and it shows up in "
     "the field strips between the cards. Also absent: the hero's gel and refraction, the app's "
     "real fonts (Fraunces / Manrope / EB Garamond, not Georgia / Segoe), the shader's own "
     "wave-and-fan geometry &mdash; the renderer now draws the <em>new</em> ribbon design, which "
     "the app does not implement yet &mdash; and the plate's alpha, which is a made-up multiplier "
     "here rather than `GUILLOCHE_ALPHA_MAX`."),
    ("14-ribbon-travelling-deformation.jpg",
     "Motion: a travelling deformation, not a translation",
     "The spec's distinction is the whole thing: <code>phase = 2&pi;u &minus; &omega;t</code> is a wave "
     "that <em>propagates along</em> the ribbon, where <code>2&pi;u + sin(t)</code> would be a stationary "
     "wave that merely breathes, and translating the pattern would move the ribbon through the screen. "
     "Implemented as a normal displacement <code>A&#8321;sin(2&pi;u&minus;&omega;&#8321;t) + "
     "A&#8322;sin(4&pi;u&minus;&omega;&#8322;t)</code> with periods 7s and 11s, plus thread shear, a "
     "&plusmn;3.5% width breath on a 12.7s cycle, and the same phase law in the threads so neighbours "
     "stay coherent. Verified by sampling the wave: its peak travels <strong>u = 0.18 &rarr; 0.63 "
     "&rarr; 0.81 &rarr; 1.00</strong> over six seconds &mdash; a propagating wave, not a standing one."),
]

CSS = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; padding: 28px 32px 60px; background: #0d0d11; color: #eeecf2;
       font: 15px/1.55 -apple-system, "Segoe UI", Roboto, sans-serif; }
h1 { font-size: 21px; font-weight: 650; margin: 0 0 6px; letter-spacing: -0.01em; }
.sub { color: #9a97a6; font-size: 13.5px; margin: 0 0 26px; max-width: 78ch; }
figure { margin: 0 0 34px; }
figcaption { margin: 0 0 9px; }
.t { font-size: 15px; font-weight: 620; color: #f2f0f6; }
.c { font-size: 13px; color: #9a97a6; max-width: 92ch; margin-top: 2px; }
img { width: 100%; height: auto; display: block; border-radius: 9px;
      border: 1px solid #262631; background: #16161c; }
"""


def main():
    parts = [f"<!doctype html><html><head><meta charset='utf-8'>",
             f"<title>Folio — curated theme prototypes</title>",
             f"<style>{CSS}</style></head><body>",
             "<h1>Folio — the curated theme set</h1>",
             "<p class='sub'>Home regenerated per theme from the six swatches each face authors. "
             "Nine families &times; two separately painted faces = eighteen faces, no two of which "
             "share a palette.</p>"
             "<p class='sub'><strong>The audit pass.</strong> The colour brief was run as "
             "assertions over the 18 faces and the failures were fixed: <b>31 &rarr; 25</b>, "
             "and both headline categories are gone. <b>(1)</b> The hue separation was "
             "under 90&deg; on 8 faces and the bridge outranked the primary on 12 &mdash; both "
             "are <em>assignment</em> problems, so both were fixed by re-picking which swatch "
             "fills which role: P is now the most chromatic, Q the furthest from it in hue, R "
             "the most chromatic of the rest, and the leftover lands on the warm note so all "
             "six still appear. Every hex is still the sheet's own. <b>(2)</b> The dark pages "
             "measured <code>L(B0) 0.185-0.226</code> against the brief's <code>0.055-0.10</code>, "
             "so B0/B1/S0/S1 are now <em>placed</em> at the brief's lightness with their hue "
             "kept. The 25 that remain are one contradiction in the brief "
             "(<code>C(X) &ge; C(P)</code> cannot hold while P is also the most chromatic), "
             "five bridge-separation cases, and two nebula surface values. "
             "<code>verify_assign.py</code> passes: the re-lit hexes are exported by the "
             "generator and checked, not quietly excused.</p>"]
    for name, title, cap in ITEMS:
        q = os.path.join(WEB, name)
        b64 = base64.b64encode(open(q, "rb").read()).decode("ascii")
        parts.append(
            f"<figure><figcaption><div class='t'>{title}</div>"
            f"<div class='c'>{cap}</div></figcaption>"
            f"<img alt='{title}' src='data:image/jpeg;base64,{b64}'></figure>")
    parts.append("</body></html>")
    html = "".join(parts)
    open(OUT, "w", encoding="utf-8").write(html)
    digest = hashlib.sha1(html.encode("utf-8")).hexdigest()[:8]
    versioned = OUT.replace(".html", "-%s.html" % digest)
    open(versioned, "w", encoding="utf-8").write(html)
    print("wrote %s  (%.1f MB)" % (OUT, len(html) / 1e6))
    print("wrote %s" % versioned)


if __name__ == "__main__":
    main()