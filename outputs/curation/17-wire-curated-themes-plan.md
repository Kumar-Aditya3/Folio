# Plan — wire the curated themes into the app

## What exists

**`curated.py`** (the proposal) — 20 entries, 10 dark + 10 light, keyed by the **AppPalette id**.
Each carries `(hue, chroma)` per station:

    ground  backgroundBase      the page / the room
    substrate  surface          the card
    plate   backgroundSecondary the second plane / the well
    card_up surfaceElevated
    chrome  navigationSurface   the nav capsule
    accents the four accent*    progress / streak / discovery / annotation
    guilloche  x3               the plate's three threads
    glows   x2
    warm    (dark `dark` and `graphite` only)

Its own docstring says it plainly: *"This is the proposal, not the shipping code. It is the table
that will become `FolioArtDirection.kt` (18 faces) + `Theme.kt` (2 System faces) once approved."*

**The app already has the 20 palettes, but with the OLD hexes.** `AppPalette` has 20 entries
resolving through `folioArtDirection(family, light)` into 18 authored faces in
`FolioArtDirection.kt`, plus `LightFolioColors` / `DarkFolioColors` as the 2 System faces in
`Theme.kt`. Every authored station is a literal hex today. The curated hues are NOT in there —
`abyss` ships `backgroundBase = 0xFF000206` (near-neutral) where the curated table asks for
hue 212 at chroma 11.5.

**`tier_colour(gamma_target, hue, c_target)`** in `design_chroma_tiers.py` is the primitive:
"place a tier at (gamma luminance = gamma_target, Oklab hue, max affordable C)". It is what
`atmosphereFor` already uses, so the generated values are gamut-safe by construction.

## The mapping

    curated id   →  Kotlin face
    lagoon, warm, silver, arctic, sakura, honey, moss, dawn, rouge   → the 9 light faces
    abyss, espresso, graphite, midnight, dusk, ember, peacock,
    blossom, garnet                                                  → the 9 dark faces
    light / dark                                                     → Light/DarkFolioColors

    family ids: ocean lagoon/abyss · terracotta warm/espresso · moonstone silver/graphite ·
                arctic arctic/midnight · mintcoral sakura/blossom · solar honey/ember ·
                forest moss/peacock · nebula dawn/dusk · jade rouge/garnet

## The rule

**Hue and chroma change; luminance does NOT.** The shipping hexes carry the authored lightness, and
the depth ladder (`card > panel > well`, the hero pane, the well's recess) is built on it. Each new
value is `tier_colour(luminance_of_the_shipping_hex, curated_hue, curated_chroma)` — so a plane keeps
its exact place in the ladder and only changes colour. Nothing derived moves: the containers, the
`on*` inks and the inverse family keep deriving as they do now.

The four `accent*` roles are the exception. They are read as **foreground text on `surface`** and
have a hard WCAG AA floor, so their hue comes from the curated table but the lightness is re-fitted
against the new `surface`, and they are held pairwise ≥ 10 ΔE apart. `ThemeSchemeTest` pins both.

## Phases

**1 — Extract.** Parse `FolioArtDirection.kt` and `Theme.kt` into `(face, station, hex)`, and take
each hex's gamma luminance. This is the input the generator must not guess: the luminances live in
the Kotlin, not in `curated.py`.

**2 — Generate.** For each station: `tier_colour(shipping_luminance, curated_hue, curated_chroma)`
→ new hex. Re-fit the four accents. Emit a full new `FolioArtDirection.kt` body plus the two System
face blocks for `Theme.kt`.

**3 — Verify in Python BEFORE writing.** Re-run the invariants on the generated values:
- every plane's luminance is unchanged from the shipping value (bit-for-bit ladder)
- `backgroundBase` / `surface` / `backgroundSecondary` still order `card > panel > well`
- each `accent*` clears 4.5:1 on its `surface`
- the four accents are pairwise ≥ 10 ΔE
- the guilloche trio spans 25–90° in hue
- the 10 dark grounds are spread around the wheel (no two inside 20°)
Report any that fail and fix the generator rather than writing a broken palette.

**4 — Write.** Replace the hexes in `FolioArtDirection.kt` and the two System faces in `Theme.kt`.
Nothing else in either file changes — no structural edits, no renamed roles, no new fields.

**5 — Build and test.** `gradlew :shared:desktopTest` (the real suite, `ThemeSchemeTest` included),
then the Android compile. Confirm the palette count is still 20 and every `AppPalette` entry still
resolves.

## Risks, named up front

- **A curated accent hue may be unfittable at the authored luminance** — the AA floor can be
  unreachable for some hue/lightness pairs. The generator clamps to the nearest passing lightness
  and reports which faces needed it, rather than silently emitting a failing accent.
- **The dark faces' `warm` station exists only on `dark` and `graphite`.** Every other dark face
  keeps its shipping `warm` value; the generator must not invent one.
- **The luminances must come from the Kotlin.** Deriving them from the curated chroma would move
  the ladder, which is the one thing this pass must not do.

## Out of scope

The guilloche **geometry**. This pass changes the palette table only.
