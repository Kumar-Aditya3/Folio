"""
Composite the rendered faces into a contact sheet: nine families, light and dark adjacent,
the same order every time. The face renders come from build.py, which runs the shipped AGSL.
"""
import json
import pathlib

from PIL import Image, ImageDraw

HERE = pathlib.Path(__file__).resolve().parent
SHOTS = HERE / "shots"

THUMB_W = 128
ORDER = ["ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
         "mintcoral", "moonstone", "system"]
DISPLAY = {
    "ocean": "Lagoon / Abyss", "nebula": "Dawn / Dusk", "forest": "Moss / Peacock",
    "terracotta": "Manuscript / Espresso", "arctic": "Arctic / Midnight",
    "solar": "Honey / Ember", "jade": "Rouge / Garnet",
    "mintcoral": "Sakura / Blossom", "moonstone": "Silver / Graphite",
    "system": "Gallery / Dark",
}
GEOM = {
    "ocean": "SWEEP", "nebula": "ORBITAL", "forest": "ORGANIC",
    "terracotta": "EDITORIAL", "arctic": "CRYSTALLINE", "solar": "RAYS",
    "jade": "ORNAMENTAL", "mintcoral": "PLAYFUL", "moonstone": "MINIMAL",
    "system": "EDITORIAL",
}

faces = json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]
by_key = {(f["family"], f["light"]): f for f in faces}

thumbs = []
for fam in ORDER:
    for light in (True, False):
        p = SHOTS / f"{fam}-{'light' if light else 'dark'}.png"
        if not p.exists():
            continue
        im = Image.open(p).convert("RGB")
        h = round(THUMB_W * im.height / im.width)
        thumbs.append((fam, light, by_key[(fam, light)], im.resize((THUMB_W, h), Image.LANCZOS)))

thumb_h = thumbs[0][3].height
GAP = 8
PAD = 18
CAP = 30
HEAD = 52
cols = len(thumbs) // 2
W = PAD * 2 + cols * THUMB_W + (cols - 1) * GAP
H = PAD * 2 + HEAD + 2 * (thumb_h + CAP) + GAP

sheet = Image.new("RGB", (W, H), (11, 11, 15))
d = ImageDraw.Draw(sheet)

for i, (fam, light, face, im) in enumerate(thumbs):
    col = i // 2
    row = i % 2
    x = PAD + col * (THUMB_W + GAP)
    y = PAD + HEAD + row * (thumb_h + CAP + GAP)
    sheet.paste(im, (x, y))
    d.rectangle([x, y, x + THUMB_W - 1, y + thumb_h - 1], outline=(58, 59, 68) if light else (24, 25, 30))
    if row == 0:
        d.text((x, y - 13), DISPLAY[fam], fill=(201, 199, 210))
    d.text((x, y + thumb_h + 5), "light" if light else "dark", fill=(120, 118, 130))
    d.text((x + 34, y + thumb_h + 5), GEOM[fam], fill=(96, 96, 108))

d.text((PAD, PAD - 4),
       "All twenty faces \u2014 the AGSL plate on the theme's own field",
       fill=(233, 232, 239))
d.text((PAD, PAD + 14),
       "Rendered by the shipped GUILLOCHE_AGSL and AMBIENT_AGSL fragment shaders over folioField, 390\u00d7844",
       fill=(143, 140, 156))

sheet.save(HERE / "sheet-all-faces.png")
print(f"sheet-all-faces.png  {sheet.width}x{sheet.height}  ({len(thumbs)} faces)")
