import io, re, json

P = r"shared/src/composeUi/kotlin/com/folio/reader/ui/theme/FolioArtDirection.kt"
s = io.open(P, encoding="utf-8").read()
blocks = re.findall(r"    FolioArtDirection\(\n(.*?)\n    \),", s, re.S)


def get(b, k):
    m = re.search(k + r" = 0x([0-9A-Fa-f]{8})", b)
    return int(m.group(1), 16)


NAMES = {"gallery": "Gallery", "manuscript": "Manuscript", "silver": "Silver", "arctic": "Arctic",
         "sakura": "Sakura", "honey": "Honey", "moss": "Moss", "dawn": "Dawn", "lagoon": "Lagoon",
         "rouge": "Rouge", "cyberpunk": "Neon Day", "vice": "Vice Day", "aurum": "Champagne"}
DARKNAMES = {"gallery": "Dark", "manuscript": "Espresso", "silver": "Graphite", "arctic": "Midnight",
             "sakura": "Blossom", "honey": "Ember", "moss": "Peacock", "dawn": "Dusk",
             "lagoon": "Abyss", "rouge": "Garnet", "cyberpunk": "Cyberpunk", "vice": "Vice",
             "aurum": "Aurum"}

rows = []
for b in blocks:
    idm = re.search(r'id = "([^"]+)"', b).group(1)
    light = re.search(r"light = (true|false)", b).group(1) == "true"
    rows.append((idm, light, get(b, "warmAccent"), get(b, "tertiaryAccent"), get(b, "primaryAccent"),
                 get(b, "navAccent"), get(b, "mastheadAccent"), get(b, "readerOverlayAccent")))


def hx(v):
    return "#%06X" % (v & 0xFFFFFF)


top = 96
rh = 30
H = top + len(rows) * rh + 16
o = []
o.append('<svg viewBox="0 0 680 %d" width="100%%" role="img" xmlns="http://www.w3.org/2000/svg">' % H)
o.append('<title>Nav, masthead and reader-overlay hues per theme: derived vs newly authored</title>')
o.append('<desc>Each row is a theme face. The left group is the old derived colours (warm accent, tertiary accent, primary); the right group is the new authored trio (nav, masthead, reader overlay).</desc>')
o.append('<text class="th" x="40" y="30" dominant-baseline="central">Chrome hues per theme: derived vs newly authored</text>')
o.append('<text class="ts" x="40" y="52" dominant-baseline="central">Within each group: nav capsule / masthead / in-reader overlay</text>')
o.append('<text class="ts" x="180" y="80" dominant-baseline="central">was (derived)</text>')
o.append('<text class="ts" x="380" y="80" dominant-baseline="central">now (authored)</text>')
for i, (idm, light, w, t, p, n, m, ov) in enumerate(rows):
    y = top + i * rh
    label = (NAMES if light else DARKNAMES)[idm]
    o.append('<text class="t" x="40" y="%d" dominant-baseline="central">%s</text>' % (y + 8, label))
    for j, trip in enumerate(((w, t, p), (n, m, ov))):
        x0 = 180 if j == 0 else 380
        for k, v in enumerate(trip):
            o.append('<rect x="%d" y="%d" width="17" height="17" rx="4" fill="%s" stroke="#888780" stroke-width="0.5"/>'
                     % (x0 + k * 20, y - 1, hx(v)))
o.append('</svg>')
svg = "\n".join(o)
io.open("chrome_hues.svg", "w", encoding="utf-8").write(svg)
print("rows", len(rows), "H", H)
