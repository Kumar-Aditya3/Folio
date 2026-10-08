"""
Applies the verified palettes from folio_theme_rebuild.py into the Kotlin sources.

File surgery rather than hand-editing, because the payload is 500 + 360 colour
constants and a transcription error in a hex is invisible in review.

Run after folio_theme_rebuild.py:
    python outputs/palette/folio_theme_apply_rebuild.py
"""

from __future__ import annotations

import os
import re
from typing import Dict, List, Tuple

from folio_theme_rebuild import (
    THEMES,
    argb,
    derive,
    emit_art_directions,
    reader_preset,
)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

ART = os.path.join(
    ROOT, "shared", "src", "composeUi", "kotlin", "com", "folio", "reader",
    "ui", "theme", "FolioArtDirection.kt",
)
READER = os.path.join(
    ROOT, "shared", "src", "commonMain", "kotlin", "com", "folio", "reader",
    "settings", "ReaderThemes.kt",
)

ART_TAIL_DOC = '''/**
 * The ten themes, each with its two separately painted faces.
 *
 * **The table is keyed by the theme pack's own id.** It used to be keyed by a parallel set of
 * "art-direction family" ids (`ocean`, `nebula`, `forest`, ...) which no other registry used,
 * and that second id space was not merely redundant — it silently broke the per-theme
 * background animation, because `LocalFolioPackId` carries a *pack* id and the field lookup
 * keyed on a *family* id, so nine of the ten packs fell through to the same default field.
 * One id space, one lookup, no drift.
 *
 * | theme | light / dark | identity | field |
 * |---|---|---|---|
 * | `gallery` | Gallery / Dark | white-cube plaster, cobalt placard, the red sold-dot | GrainGradient |
 * | `manuscript` | Manuscript / Espresso | vellum, rubric sienna, iron-gall indigo, gold leaf | Warp |
 * | `silver` | Silver / Graphite | gelatin-silver print; cool platinum, a magenta flash | Dithering |
 * | `arctic` | Arctic / Midnight | glacier blue, snow-shadow, aurora green, low-sun gold | Swirl |
 * | `sakura` | Sakura / Blossom | petal rose, young leaf, branch umber, spring sky | DotOrbit |
 * | `honey` | Honey / Ember | amber gold, hive-shade teal, propolis, clover | Metaballs |
 * | `moss` | Moss / Peacock | forest green, lichen, bark; at night teal + sapphire + bronze | Voronoi |
 * | `dawn` | Dawn / Dusk | lilac sky, apricot horizon, morning blue | GodRays |
 * | `lagoon` | Lagoon / Abyss | turquoise shallows, coral, sand; at depth bioluminescence | MeshGradient |
 * | `rouge` | Rouge / Garnet | theatrical crimson, gilt, velvet plum | SmokeRing |
 *
 * Every face was authored in OKLCH and verified with `coloraide`
 * (`outputs/palette/folio_theme_rebuild.py`, report in `outputs/palette/folio_theme_audit.md`):
 * the three ink tiers clear 7:1 / 4.5:1 / 3:1 on the *darkest* plane they ever land on, the
 * progress fill clears 3:1 against its own track, the four semantic accents clear AA on
 * `surface` and are held pairwise >= 10 dE2000 apart, and the ten themes' primaries are held
 * >= 10 dE2000 apart from each other on both faces so no two themes read as the same app in
 * different sunglasses.
 *
 * The ten primaries are spread deliberately around the hue circle rather than clustered:
 * light faces sit at 18 42 80 145 188 218 248 276 310 350 degrees, and the dark faces are
 * re-pitched per their *own* name (Ember is an orange coal, not Honey darkened; Peacock is
 * teal + sapphire + bronze, not Moss at night).
 */
'''


def replace_art_directions() -> None:
    src = open(ART, encoding="utf-8").read()
    lines = src.split("\n")
    cut = next(i for i, l in enumerate(lines) if "The nine rooms" in l)
    # back up over the opening /** of that KDoc
    while not lines[cut].lstrip().startswith("/**"):
        cut -= 1

    data = [(t, f, derive(f)) for t in THEMES for f in (t.light, t.dark)]
    tail = ART_TAIL_DOC + emit_art_directions(data) + "\n\n" + (
        "/** One theme's face, by the theme pack's own id. */\n"
        "internal fun folioArtDirection(id: String, light: Boolean): FolioColors =\n"
        "    FOLIO_ART_DIRECTIONS.first { it.id == id && it.light == light }.toFolioColors()\n"
    )
    out = "\n".join(lines[:cut]) + tail
    open(ART, "w", encoding="utf-8").write(out)
    print(f"FolioArtDirection.kt: replaced from line {cut + 1}, {len(data)} faces")


def emit_one_reader(p: Dict[str, object], indent: str = "    ") -> str:
    hls = p["highlightColors"]
    i = indent
    return (
        f'{i}"{p["id"]}" to Theme(\n'
        f'{i}    id = "{p["id"]}", name = "{p["name"]}",\n'
        f'{i}    background = {argb(p["background"])}.toInt(), surface = {argb(p["surface"])}.toInt(),\n'
        f'{i}    primaryText = {argb(p["primaryText"])}.toInt(), secondaryText = {argb(p["secondaryText"])}.toInt(),\n'
        f'{i}    headingText = {argb(p["headingText"])}.toInt(), link = {argb(p["link"])}.toInt(),\n'
        f'{i}    selection = {argb(p["selection"])}.toInt(), bookmark = {argb(p["bookmark"])}.toInt(),\n'
        f'{i}    highlightColors = listOf(\n'
        f'{i}        ' + ", ".join(f"{argb(h)}.toInt()" for h in hls[:4]) + ",\n"
        f'{i}        ' + ", ".join(f"{argb(h)}.toInt()" for h in hls[4:]) + "\n"
        f'{i}    ),\n'
        f'{i}    progress = {argb(p["progress"])}.toInt(), divider = {argb(p["divider"])}.toInt(),\n'
        f'{i}    isDark = {"true" if p["isDark"] else "false"}\n'
        f'{i}),\n'
    )


def find_block(lines: List[str], preset_id: str) -> Tuple[int, int]:
    """Line span [start, end) of a `"<id>" to Theme(...)` entry, paren-balanced."""
    pat = re.compile(r'^\s*"' + re.escape(preset_id) + r'" to Theme\(')
    start = next(i for i, l in enumerate(lines) if pat.match(l))
    depth = 0
    i = start
    while i < len(lines):
        depth += lines[i].count("(") - lines[i].count(")")
        if depth == 0:
            return start, i + 1
        i += 1
    raise RuntimeError(f"unbalanced block for {preset_id}")


def replace_reader_themes() -> None:
    src = open(READER, encoding="utf-8").read()
    lines = src.split("\n")

    presets = {}
    for t in THEMES:
        for f in (t.light, t.dark):
            r = derive(f)
            presets[f.reader_id] = reader_preset(f, r)

    # Replace the 18 pack-linked presets that already exist, in place.
    existing = [
        "white", "paper", "sepia", "gray", "arctic", "moss", "dawn",
        "dark", "graphite", "blossom", "dusk", "espresso", "ember",
        "peacock", "lagoon", "abyss", "rouge", "garnet",
    ]
    for pid in existing:
        assert pid in presets, pid
        s, e = find_block(lines, pid)
        lines[s:e] = emit_one_reader(presets[pid]).rstrip("\n").split("\n")

    # Two faces had no page of their own and were borrowing another theme's:
    # Midnight read on Gallery's "dark" and Honey read on Manuscript's "sepia",
    # so two themes could never match their own reader page. Give them their own.
    for new_id, after_id in (("midnight", "dark"), ("honey", "sepia")):
        s, e = find_block(lines, after_id)
        lines[e:e] = emit_one_reader(presets[new_id]).rstrip("\n").split("\n")

    open(READER, "w", encoding="utf-8").write("\n".join(lines))
    print(f"ReaderThemes.kt: replaced {len(existing)} presets, inserted 2 new")


if __name__ == "__main__":
    replace_art_directions()
    replace_reader_themes()
