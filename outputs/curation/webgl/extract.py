"""
Extract the Folio theme system and the AGSL plate shader from the Kotlin sources.

Nothing here is transcribed by hand: every hex, every constant and every line of the
shader source is read out of the repository. That is the whole point of the script —
a render that disagrees with the app because someone retyped a colour is worse than
no render, and the AGSL source in particular must be byte-identical to what ships.

Outputs (next to this file):
    faces.json  - the 20 art-directed faces, their roles, their pack and their light
    agsl.json   - the plate shader, verbatim, plus the model constants
"""
import json
import pathlib
import re

REPO = pathlib.Path(__file__).resolve().parents[3]
THEME = REPO / "shared/src/composeUi/kotlin/com/folio/reader/ui/theme"
ANDROID = REPO / "shared/src/androidMain/kotlin/com/folio/reader/ui/theme"
HERE = pathlib.Path(__file__).resolve().parent

ART = (THEME / "FolioArtDirection.kt").read_text(encoding="utf-8")
# The AGSL lives in androidMain; the expect declaration and the model are in composeUi.
GUILLOCHE_SHADER = (ANDROID / "GuillocheShader.android.kt").read_text(encoding="utf-8")
GUILLOCHE_MODEL = (THEME / "GuillocheModel.kt").read_text(encoding="utf-8")
AMBIENT_SHADER = (ANDROID / "AmbientShader.android.kt").read_text(encoding="utf-8")
GLASS_SHADER = (ANDROID / "GlassShader.android.kt").read_text(encoding="utf-8")
SURFACE_OPACITY = (THEME / "FolioSurfaceOpacity.kt").read_text(encoding="utf-8")
ATMOSPHERE = (THEME / "FolioAtmosphere.kt").read_text(encoding="utf-8")
# The ambient film's own strength, taken from the expect declaration's default so a retune
# in Kotlin moves the render with it.
_amb = re.search(r"expect fun Modifier\.folioAmbientShader\((.*?)\)\s*: Modifier", (THEME / "AmbientShader.kt").read_text(encoding="utf-8"), re.S)
ambient_intensity = float(re.search(r"intensity\s*:\s*Float\s*=\s*([0-9.]+)", _amb.group(1)).group(1))
LIGHT = (THEME / "FolioLight.kt").read_text(encoding="utf-8")
THEME_KT = (THEME / "Theme.kt").read_text(encoding="utf-8")


def raw_string(src: str, name: str) -> str:
    """The body of `const val NAME = \"\"\"...\"\"\"`, verbatim."""
    m = re.search(rf'{name}\s*=\s*"""(.*?)"""', src, re.S)
    if not m:
        raise SystemExit(f"could not find the raw string {name}")
    return m.group(1)


# ── the AGSL plate ────────────────────────────────────────────────────────────
# GUILLOCHE_AGSL is assembled as `raw + GUILLOCHE_UNIFORMS + GUILLOCHE_FUNCTIONS + raw`,
# so the two interpolated blocks have to be spliced back in the order the source does it.
uniforms = raw_string(GUILLOCHE_SHADER, "GUILLOCHE_UNIFORMS")
functions = raw_string(GUILLOCHE_SHADER, "GUILLOCHE_FUNCTIONS")
agsl_parts = re.search(
    r'GUILLOCHE_AGSL\s*=\s*"""(.*?)"""\s*\+\s*GUILLOCHE_UNIFORMS\s*\+\s*GUILLOCHE_FUNCTIONS\s*\+\s*"""(.*?)"""',
    GUILLOCHE_SHADER,
    re.S,
)
if not agsl_parts:
    raise SystemExit("could not splice GUILLOCHE_AGSL")
agsl_source = agsl_parts.group(1) + uniforms + functions + agsl_parts.group(2)


def const(name: str, src=None, kind: str = "f") -> float:
    """A numeric `const val`, read out of whichever source declares it."""
    if src is None:
        text = GUILLOCHE_MODEL
    elif isinstance(src, str):
        text = src
    else:
        text = src.read_text(encoding="utf-8")
    m = re.search(rf'const val {name}\s*=\s*([0-9_.eE+-]+)', text)
    if not m:
        raise SystemExit(f"could not find the constant {name}")
    return float(m.group(1).replace("_", "").rstrip("f"))


model = {
    "GUILLOCHE_ALPHA_MAX": const("GUILLOCHE_ALPHA_MAX"),
    "GUILLOCHE_STRUCTURE_SCALE": const("GUILLOCHE_STRUCTURE_SCALE"),
    "GUILLOCHE_QUIET_MARGIN": const("GUILLOCHE_QUIET_MARGIN"),
    "GUILLOCHE_SHIMMER": const("GUILLOCHE_SHIMMER"),
    "GUILLOCHE_WAVES": int(const("GUILLOCHE_WAVES")),
    "GUILLOCHE_WEIGHT_DARK": const("GUILLOCHE_WEIGHT_DARK"),
    "GUILLOCHE_WEIGHT_LIGHT": const("GUILLOCHE_WEIGHT_LIGHT"),
    "GUILLOCHE_DARK_DEPTH": const("GUILLOCHE_DARK_DEPTH"),
    "GUILLOCHE_DARK_LIFT": const("GUILLOCHE_DARK_LIFT"),
    "GUILLOCHE_DARK_GLOW": const("GUILLOCHE_DARK_GLOW"),
    "GUILLOCHE_MIN_VISIBLE_DELTA": const("GUILLOCHE_MIN_VISIBLE_DELTA"),
    "GUILLOCHE_LIGHT_TINT": const("GUILLOCHE_LIGHT_TINT"),
    "GUILLOCHE_LIGHT_DEPTH": const("GUILLOCHE_LIGHT_DEPTH"),
    "MAX_GUILLOCHE_HUE_DEV": const("MAX_GUILLOCHE_HUE_DEV"),
    "ROOM_HUE_MIN_CHROMA": const("ROOM_HUE_MIN_CHROMA", THEME / "FolioAtmosphere.kt"),
    "MAX_TIER_HUE_DEV": const("MAX_TIER_HUE_DEV", THEME / "FolioAtmosphere.kt"),
    "GAMUT_SAFETY": const("GAMUT_SAFETY", THEME / "FolioAtmosphere.kt"),
    "POOL_ALPHA_CEILING": const("POOL_ALPHA_CEILING", THEME / "FolioFieldModel.kt"),
    "POOL_DRIFT": const("POOL_DRIFT", THEME / "FolioFieldModel.kt"),
    "POOL_FACING": const("POOL_FACING", THEME / "FolioFieldModel.kt"),
    "GUILLOCHE_PERIODS_MS": [
        float(x.strip().replace("_", "").rstrip("Lf"))
        for x in re.search(r"GUILLOCHE_PERIODS_MS\s*=\s*listOf\(([^)]*)\)", GUILLOCHE_MODEL).group(1).split(",")
    ],
}

# ── the lights ────────────────────────────────────────────────────────────────
lights = {}
for pid, args in re.findall(r'"(\w+)"\s*to\s*FolioLight\(([^)]*)\)', LIGHT):
    nums = [float(x.strip().rstrip("f")) for x in args.split(",")]
    lights[pid] = dict(zip(
        ("sourceX", "sourceY", "lean", "strengthDark", "strengthLight", "hueRange"), nums
    ))

# ── the geometry families ─────────────────────────────────────────────────────
GEOMETRY_ORDER = ["SWEEP", "ORBITAL", "ORGANIC", "EDITORIAL", "CRYSTALLINE",
                  "RAYS", "ORNAMENTAL", "PLAYFUL", "MINIMAL"]
character = {}
for fam, args in re.findall(
    r"GuillocheGeometry\.(\w+)\s*->\s*GuillocheCharacter\(([^)]*)\)", GUILLOCHE_MODEL
):
    character[fam] = [float(x.strip().rstrip("f")) for x in args.split(",")]

geometry = dict(re.findall(r'"(\w+)"\s*to\s*GuillocheGeometry\.(\w+)', GUILLOCHE_MODEL))

# ── the packs: which palettes each pack owns ──────────────────────────────────
packs = {}
for pid, light_name, dark_name, lp, dp, *_ in re.findall(
    r'ThemePack\("(\w+)",\s*"([^"]*)",\s*"([^"]*)",\s*"(\w+)",\s*"(\w+)"', THEME_KT
):
    packs[pid] = {"light": lp, "dark": dp, "lightName": light_name, "darkName": dark_name}

# ── the faces ─────────────────────────────────────────────────────────────────
HEX_ROLES = [
    "backgroundBase", "backgroundSecondary", "surface", "surfaceElevated",
    "primaryAccent", "secondaryAccent", "tertiaryAccent", "warmAccent",
    "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
    "glowPrimary", "glowSecondary",
    "progress", "progressTrack",
    "textPrimary", "textSecondary", "textTertiary",
    "border", "borderHighlight", "navigationSelected",
    "accentProgress", "accentStreak", "accentDiscovery", "accentAnnotation",
]

faces = []
for block in re.findall(r"FolioArtDirection\(\s*(.*?)\n\s*\),", ART, re.S):
    fields = dict(re.findall(r'(\w+)\s*=\s*("(?:[^"]*)"|true|false|0x[0-9A-Fa-f]+)', block))
    if "id" not in fields or "light" not in fields:
        continue
    fam = fields["id"].strip('"')
    is_light = fields["light"] == "true"
    roles = {r: fields[r] for r in HEX_ROLES if r in fields}
    missing = [r for r in HEX_ROLES if r not in fields]
    if missing:
        raise SystemExit(f"{fam}/{is_light} is missing {missing}")
    faces.append({
        "family": fam,
        "light": is_light,
        "roles": roles,
        "pack": None,
        "lightParams": None,
        "geometry": None,
    })

# Attach the pack (and through it the light and the geometry family) to each face.
family_to_pack = {
    "ocean": "lagoon", "nebula": "dawn", "forest": "moss", "terracotta": "manuscript",
    "arctic": "arctic", "solar": "honey", "jade": "rouge", "mintcoral": "sakura",
    "moonstone": "silver", "system": "gallery",
}
# The dark face of each pack is keyed by the *dark palette's* name in PACK_GEOMETRY, so a
# face's geometry key is the pack id on light and the dark palette's id on dark.
dark_key = {v["dark"]: k for k, v in packs.items()}
for f in faces:
    pack = family_to_pack[f["family"]]
    f["pack"] = pack
    f["lightParams"] = lights[pack]
    key = pack if f["light"] else dark_key.get(f["family"], pack)
    # `system` has no dark palette of its own; it borrows the calm printed plate.
    if f["family"] == "system" and not f["light"]:
        key = "gallery"
    f["geometryKey"] = key
    f["geometry"] = geometry.get(key, "SWEEP")
    f["geometryOrdinal"] = GEOMETRY_ORDER.index(f["geometry"])
    f["character"] = character[f["geometry"]]

(HERE / "faces.json").write_text(json.dumps({
    "faces": faces,
    "packs": packs,
    "lights": lights,
    "character": character,
    "geometryOrder": GEOMETRY_ORDER,
}, indent=1), encoding="utf-8")

(HERE / "agsl.json").write_text(json.dumps({
    "uniforms": uniforms,
    "functions": functions,
    "source": agsl_source,
    # The second AGSL shader on the same ground plane: the ambient bloom film, which the
    # app draws over the plate and under the lamp.
    "ambient": raw_string(AMBIENT_SHADER, "AMBIENT_AGSL"),
    "ambientIntensity": ambient_intensity,
    # The third: the liquid-glass specular film. Unlike the other two it never samples the
    # backdrop -- it is a pure overlay -- so it can be rendered per surface, offscreen, and
    # composited by the page. That is what makes "every pane is drawn by the shader"
    # affordable rather than a second renderer.
    "glass": raw_string(GLASS_SHADER, "LIQUID_GLASS_AGSL"),
    "model": model,
    "opacity": {
        # FolioSurfaceOpacity.kt, read rather than retyped.
        "MIN": const("MIN", SURFACE_OPACITY),
        "BAR_GLASS": const("BAR_GLASS", SURFACE_OPACITY),
        "NAV_GLASS": const("NAV_GLASS", SURFACE_OPACITY),
        "PANEL_GLASS": const("PANEL_GLASS", SURFACE_OPACITY),
        "BAR_GLASS_BLURRED": const("BAR_GLASS_BLURRED", SURFACE_OPACITY),
        "NAV_GLASS_BLURRED": const("NAV_GLASS_BLURRED", SURFACE_OPACITY),
        "PANEL_GLASS_BLURRED": const("PANEL_GLASS_BLURRED", SURFACE_OPACITY),
    },
    "atmosphere": {
        # FolioAtmosphere.kt: the material ladder and the chrome plane.
        "VEIL_CHROMA": const("VEIL_CHROMA", ATMOSPHERE),
        "PAPER_CHROME_DROP": const("PAPER_CHROME_DROP", ATMOSPHERE),
        "RIM_CHROMA": const("RIM_CHROMA", ATMOSPHERE),
        "NAV_CHROMA": const("NAV_CHROMA", ATMOSPHERE),
        "SCRIM_CHROMA": const("SCRIM_CHROMA", ATMOSPHERE),
        "MAX_TIER_HUE_DEV": const("MAX_TIER_HUE_DEV", ATMOSPHERE),
    },
    # The chart hues. One authored list for the whole app, not a per-face derivation --
    # which is why the chart coherence check is a property of this list and not of a face.
    "chartSeries": [
        f"0x{h.upper()}"
        for h in re.findall(r"0x(F[0-9A-Fa-f]{7}|[0-9A-Fa-f]{8})",
                            re.search(r"chartSeriesDefault[^=]*=\s*listOf\((.*?)\)",
                                      THEME_KT, re.S).group(1))
    ],
}, indent=1), encoding="utf-8")

print(f"faces: {len(faces)}")
for f in faces:
    print(f"  {f['family']:<11} {'light' if f['light'] else 'dark ':<5} pack={f['pack']:<11}"
          f" geom={f['geometry']:<12} page={f['roles']['backgroundBase']}")
print(f"\nAGSL source: {len(agsl_source)} chars, {agsl_source.count(chr(10))} lines")
print(f"uniforms block: {uniforms.count('uniform')} declarations")
