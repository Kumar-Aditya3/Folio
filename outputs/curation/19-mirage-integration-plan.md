# Folio — Color + Mirage Integration Plan

> Status: color audit complete (20/20 clean). Remaining work = wire 10 Mirage animation
> families per theme, each fed the already-validated atmosphere pools. This file is the
> durable record of the plan agreed in session, so it survives context compression.

## Part 0 — What's already done (color), and what it means for Mirage

The palette auditor (`outputs/palette/folio_palette.py` + `folio_palette_apply.py`) has
converged: **20/20 faces clean, 0 issues** (`folio_palette_audit.md/json`). Crucially:

- **Animation pool colors are NOT separately authored.** They are *derived at runtime* in
  `FolioFieldModel.kt` from each face's authored roles `glowPrimary / glowSecondary /
  warmAccent` via `harmonizePoolHues(...)`, surfaced as `FolioAtmosphere.pools` (→
  `FolioFieldColors.poolColors`).
- The audit's `animation.pools[].dE_vs_field` scores those exact runtime pools against each
  theme's field, and they pass. So the "mirage colors" are the atmosphere pools — already
  validated, nothing new to author. Mirage shaders just consume `atmosphere.pools` the same
  way `folioMeshField` already does.

**Conclusion:** color work = complete. Remaining work is purely wiring 10 Mirage animation
families per theme, each fed the already-validated pools.

## Part 1 — The mapping (source of truth to port into Kotlin)

From `folio_palette.py` `CONCEPTS`, keyed by the pack `id` in `FolioArtDirection.kt`:

| pack id | concept | Mirage family | pool roles |
|---|---|---|---|
| `system` | System (achromatic) | **Metaballs** | glowPrimary, glowSecondary |
| `ocean` | Aurora Ocean | **MeshGradient** *(exists today)* | +warmAccent |
| `nebula` | Nebula Electric | **NeuroNoise** | +warmAccent |
| `forest` | Bioluminescent Forest | **Voronoi** | +warmAccent |
| `terracotta` | Terracotta Tide | **GrainGradient (Wave)** | +warmAccent |
| `arctic` | Arctic Glass | **Swirl** | +warmAccent |
| `solar` | Solar Eclipse | **GodRays** | +warmAccent |
| `jade` | Jade Noir | **SmokeRing** | +warmAccent |
| `mintcoral` | Mint & Coral Editorial | **ColorPanels** | +warmAccent |
| `moonstone` | Moonstone | **PerlinNoise** | +warmAccent |

Default for an unknown id → `Metaballs`.

## Part 2 — Current architecture (integration points)

- **Single entry point:** `Modifier.folioSilkBackground()` in
  `shared/src/composeUi/.../ui/theme/SilkField.kt`. Called at the app ground plane
  (`MainActivity.kt` ~L528) and `ThemeLabScreen.kt`. Reads `FolioTheme.atmosphere` and either
  calls `folioMeshField(...)` (if `folioMeshFieldSupported()`) or paints the ramp+bloom
  Compose fallback.
- **The shader seam** is an `expect/actual` modifier:
  - `MeshField.kt` (composeUi `expect fun Modifier.folioMeshField(top, bottom, pools,
    poolStrength, isDark, animate)` + `folioMeshFieldSupported()`),
  - `MeshField.android.kt` (AGSL, API 33+ — currently the only family, a woven mesh),
  - `MeshField.desktop.kt` (no-op; supported=false).
- Mirage math is already **license-cleared** in `NOTICE` (Apache-2.0 port of Paper Shaders),
  and `MeshField.android.kt` already adapts Mirage's mesh gradient — established pattern.

## Part 3 — Proposed design

### 3a. New pure-data mapping (shared, unit-testable)
`shared/src/composeUi/.../ui/theme/FolioFieldKind.kt`:
```kotlin
enum class FolioFieldKind { Metaballs, MeshGradient, NeuroNoise, Voronoi,
    GrainWave, Swirl, GodRays, SmokeRing, ColorPanels, PerlinNoise }
fun folioFieldKindFor(packId: String): FolioFieldKind // table above; default Metaballs
```
No Compose dependency → covered by a desktopTest.

### 3b. Generalize the shader seam
Add a kind-dispatched expect (keep existing signature + add `kind`):
```kotlin
expect fun Modifier.folioField(
    kind: FolioFieldKind,
    top: Color, bottom: Color, pools: List<Color>,
    poolStrength: Float, isDark: Boolean, animate: Boolean,
): Modifier
expect fun folioFieldSupported(): Boolean
```
`folioMeshField` becomes the `MeshGradient` branch (zero behavior change for `ocean`).

### 3c. Android actual — 10 AGSL families
One AGSL `RuntimeShader` per family, each adapting the corresponding Mirage/Paper-Shaders
routine, all sharing:
- the same uniform contract (`iResolution`, `iTime`, `top`, `bottom`, `pool0..2`,
  `poolStrength`, `isDark`),
- the same opaque, edge-to-edge output and `mix`-not-`Plus` compositing discipline,
- the 10 Hz slow clock (`rememberSlowPhases`) and `animate=false` parking for reduce-motion.
Dispatch via `when (kind)`; keep support logic as `folioFieldSupported()` (API 33+).

### 3d. Desktop actual
`FolioField.desktop.kt`: no-op modifier, `folioFieldSupported() = false` → existing ramp+bloom
fallback stays byte-for-byte (Rule 19).

### 3e. Wire the entry point
In `SilkField.kt` `folioSilkBackground()`: resolve `kind = folioFieldKindFor(FolioTheme.packId)`
and call `folioField(kind, …)` with the existing atmosphere values. One call site.

## Part 4 — Verification
- Unit: `FolioFieldKindTest` (all 10 ids map; unknown → Metaballs).
- Existing tests stay green (pools/atmosphere untouched).
- Re-run `folio_palette.py` read-only to reconfirm 20/20 (no `_apply`).
- Build `:androidApp:assembleDebug`; manual visual pass per theme; reduce-motion parks;
  desktop falls back.
- Extend `NOTICE` to list all adapted families.

## Part 5 — Risk / sequencing
1. Land 3a + 3b + 3c-as-`MeshGradient-only` refactor (behavior-preserving rename) → build green.
2. Add families one at a time (Metaballs → PerlinNoise), building/eyeballing each.
3. Wire 3e last.

Highest-risk: AGSL correctness/perf of `GodRays`, `SmokeRing`, `Voronoi` (loop-heavy).
Mitigation: cap iteration counts, keep the 10 Hz clock, capability gate degrades to fallback.
