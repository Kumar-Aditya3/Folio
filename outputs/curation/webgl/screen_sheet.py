"""The Home sheet: all twenty faces, light and dark adjacent, one strip."""
import pathlib

from PIL import Image, ImageDraw

HERE = pathlib.Path(__file__).resolve().parent
SHOTS = HERE / "screens"

FAMILIES = ["ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
            "mintcoral", "moonstone", "system"]
LABEL = {
    "ocean": "Lagoon / Abyss", "nebula": "Dawn / Dusk", "forest": "Moss / Peacock",
    "terracotta": "Manuscript / Espresso", "arctic": "Arctic / Midnight",
    "solar": "Honey / Ember", "jade": "Rouge / Garnet",
    "mintcoral": "Sakura / Blossom", "moonstone": "Silver / Graphite",
    "system": "Gallery / Dark",
}

THUMB_W = 132
GAP = 8
PAD = 18
CAP = 32
HEAD = 56


def main():
    thumbs = []
    for fam in FAMILIES:
        for light in (True, False):
            p = SHOTS / f"home-{fam}-{'light' if light else 'dark'}.png"
            if not p.exists():
                continue
            im = Image.open(p).convert("RGB")
            thumbs.append((fam, light,
                           im.resize((THUMB_W, round(THUMB_W * im.height / im.width)),
                                     Image.LANCZOS)))

    thumb_h = thumbs[0][2].height
    cols = len(thumbs) // 2
    W = PAD * 2 + cols * THUMB_W + (cols - 1) * GAP
    H = PAD * 2 + HEAD + 2 * (thumb_h + CAP) + GAP

    sheet = Image.new("RGB", (W, H), (11, 11, 15))
    d = ImageDraw.Draw(sheet)
    d.text((PAD, PAD - 4), "Folio \u2014 Home, all twenty faces",
           fill=(233, 232, 239))
    d.text((PAD, PAD + 14),
           "ground plane by GUILLOCHE_AGSL + AMBIENT_AGSL \u00b7 crown, hero, cards and capsule "
           "by LIQUID_GLASS_AGSL \u00b7 fills from the Kotlin models",
           fill=(143, 140, 156))

    for i, (fam, light, im) in enumerate(thumbs):
        col, row = i // 2, i % 2
        x = PAD + col * (THUMB_W + GAP)
        y = PAD + HEAD + row * (thumb_h + CAP + GAP)
        sheet.paste(im, (x, y))
        d.rectangle([x, y, x + THUMB_W - 1, y + thumb_h - 1],
                    outline=(58, 59, 68) if light else (24, 25, 30))
        if row == 0:
            d.text((x, y - 13), LABEL[fam], fill=(201, 199, 210))
        d.text((x, y + thumb_h + 5), "light" if light else "dark", fill=(110, 108, 120))

    sheet.save(HERE / "sheet-home-all.png")
    print(f"sheet-home-all.png  {sheet.width}x{sheet.height}  ({len(thumbs)} faces)")


if __name__ == "__main__":
    main()
