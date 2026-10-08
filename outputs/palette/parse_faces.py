"""Parse generated_art_directions.kt into faces.json (list of 20 faces, 25 roles each)."""
import re, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "generated_art_directions.kt"), encoding="utf-8").read()

# display names per theme pack, light then dark
NAMES = {
    "gallery": ("Gallery", "Dark"),
    "manuscript": ("Manuscript", "Espresso"),
    "silver": ("Silver", "Graphite"),
    "arctic": ("Arctic", "Midnight"),
    "sakura": ("Sakura", "Blossom"),
    "honey": ("Honey", "Ember"),
    "moss": ("Moss", "Peacock"),
    "dawn": ("Dawn", "Dusk"),
    "lagoon": ("Lagoon", "Abyss"),
    "rouge": ("Rouge", "Garnet"),
}

blocks = re.findall(r"FolioArtDirection\((.*?)\)\s*,\s*(?=//|\n\s*FolioArtDirection|\n\)\s*$|\n\))", src, re.S)
# simpler: split on "FolioArtDirection("
chunks = src.split("FolioArtDirection(")[1:]
faces = []
for ch in chunks:
    body = ch.split("),")[0] if "),\n" in ch else ch[:ch.rfind(")")]
    # grab id and light
    mid = re.search(r'id\s*=\s*"([^"]+)"', ch)
    ml = re.search(r'light\s*=\s*(true|false)', ch)
    pid = mid.group(1)
    light = ml.group(1) == "true"
    roles = {}
    for k, v in re.findall(r'(\w+)\s*=\s*0x([0-9A-Fa-f]{8})', ch):
        if k in ("id",):
            continue
        roles[k] = "#" + v[2:].upper()  # strip FF alpha -> #RRGGBB
    name = NAMES[pid][0 if light else 1]
    faces.append({"pack": pid, "name": name, "light": light, "roles": roles})

# keep first 20 (safety)
faces = faces[:20]
with open(os.path.join(HERE, "faces.json"), "w", encoding="utf-8") as f:
    json.dump(faces, f, indent=2)
print(f"parsed {len(faces)} faces")
for fc in faces:
    print(fc["name"].ljust(12), fc["pack"], "light" if fc["light"] else "dark ", len(fc["roles"]), "roles")
