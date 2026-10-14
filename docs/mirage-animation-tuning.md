# Mirage animation: configuration review and tuning proposal

Scope: the living-background field (`FolioThemeBackground` → `FolioThemeField` → Mirage), the  
slow ambient/silk/rim layers that share its role, and the Compose motion tokens. Everything below  
was read off the shipped code and the Mirage 0.1.0 artifact itself  
(`io.github.androidpoet:mirage:0.1.0`), not from documentation.

---

## 1. Verdict in one paragraph

The **choice** of animation is unusually tunable — 25 families, each already fed this theme's own  
palette, selectable at runtime. The **behaviour** of whichever family is chosen is almost entirely  
hardcoded: 20 per-family `speed` literals (0.3–0.9), per-family intensity/softness/size/count  
constants, per-family shape enums, and a binary `isDark` branch where a curve belongs. There is no  
user-facing lever for speed, intensity, scale or tempo anywhere in the app. Two families in the  
picker describe motion their shader cannot produce. Four families leave design-relevant library  
parameters at library defaults. The clock cannot be throttled at all — only frozen.

The good news: every gap is fixable **without forking Mirage**, because the parameters are already  
function arguments. The work is to move 100-odd scattered literals into one table, add two or  
three global multipliers, and set the knobs nobody ever set.

---

## 2. The stack, and who owns each layer

| Layer                  | Where                           | Owns                                                                                                           |
| ---------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `FolioThemeBackground` | `ui/theme/MeshField.kt`         | Composition: base ramp+blooms, then the field. Decides *whether* the shader runs (`Plain`, API 33, occlusion). |
| `FolioThemeField`      | `ui/theme/MeshField.android.kt` | Colour preparation (dark sink, palette cycling) + the 25-branch `when`.                                        |
| `MirageField`          | same file                       | **The tuning surface.** Every literal parameter lives here.                                                    |
| `FolioFieldCost`       | `ui/theme/FolioFieldCost.kt`    | Render scale per family (1.0 / 0.5 / 0.4), constrained-device ×0.75, floor 0.3.                                |
| `FolioFieldKind`       | `ui/theme/FolioFieldKind.kt`    | The 25-family enum, labels, descriptions, and the choice resolver.                                             |
| `folioFieldChoiceFor`  | same file                       | Reads `ReaderSettings.fieldOverrides["__global_field__"]`.                                                     |
| `rememberShaderTime`   | Mirage library                  | The clock. `withFrameNanos`, `time += dt * speed`.                                                             |

---

## 3. What is configurable today

| Knob                 | Exposed?                 | Where                                         | Notes                                                                                               |
| -------------------- | ------------------------ | --------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Which family         | **Yes**                  | Settings → General → Background animation     | 25 families + Default + Plain. Stored in `fieldOverrides` under a reserved **global** key.          |
| No animation (Plain) | **Yes**                  | same                                          | Falls back to the static ramp + drifting blooms.                                                    |
| Reduce motion        | **Only via the OS**      | `Settings.Global.ANIMATOR_DURATION_SCALE > 0` | Binary. Expressed as `speed = 0` — a freeze, not a calm.                                            |
| Vibrancy             | Yes, but **colour only** | Muted ↔ Vivid                                 | Changes the dark palette sink and pool alpha ceiling. Does **not** touch speed, scale or intensity. |
| Device budget        | Automatic                | `GlassCapabilities.budget`                    | Multiplies the render scale only.                                                                   |

Everything else in §4 is compiled in.

---

## 4. What is hardcoded

### 4.1 The `speed` literals

`speed` is the **only** time lever Mirage exposes, and every family's is authored in place:

| Family                                | speed | Family             | speed       |
| ------------------------------------- | ----- | ------------------ | ----------- |
| GrainGradient                         | 0.3   | MeshGradient       | 0.7         |
| Warp                                  | 0.35  | SmokeRing          | 0.4         |
| Dithering                             | 0.5   | ColorPanels        | 0.4         |
| Swirl                                 | 0.3   | NeuroNoise         | 0.5         |
| DotOrbit                              | 0.9   | PerlinNoise        | 0.3         |
| Metaballs                             | 0.6   | SimplexNoise       | 0.3         |
| Voronoi                               | 0.35  | Spiral             | 0.5         |
| GodRays                               | 0.5   | Water              | 0.8         |
| LiquidMetal                           | 0.6   | Heatmap            | 0.6         |
| GemSmoke                              | 0.5   | PulsingBorder      | 0.7         |
| DotGrid, Waves, HalftoneDots, Static* | —     | no `u_time` at all | cannot move |

There is no shared scale, no named tiers, and no way for a user to slow a field that reads as  
restless on their device.

### 4.2 Intensity / brightness — a two-point curve

Only three families vary by face, and each is a binary branch:

- `GrainGradient.intensity` 0.26 dark / 0.38 light (plus `noise = 0.28`)
- `GodRays.intensity` 0.42 / 0.65, `bloom` 0.28 / 0.45
- `NeuroNoise.brightness` 0.05 / 0.04

Every other family has a single intensity-like value regardless of how bright or dark the room is,  
so "too bright in dark mode" can only be fixed by hand-editing that family's literal — which is  
exactly what the P9 comments record happening repeatedly.

### 4.3 Shape and structure — hardcoded enums

`GrainGradientShape.Corners`, `WarpPattern.Stripes`, `DitheringShape.Swirl` +  
`DitheringType.Bayer4x4`, `LiquidMetalShape.Diamond`, `GemSmokeShape.Diamond`,  
`HalftoneDotsGrid.Hex` + `HalftoneDotsType.Gooey`. All six are ordinary parameters with several  
enum values each; none is selectable.

### 4.4 Family-specific geometry

`bandCount 5`, `twist 0.14`, `center 0.25`, `proportion 0.5`, `softness 0.6`, `noiseFrequency 0.4`,  
`noise 0.22` (Swirl); `count 8`, `size 0.9` (Metaballs); `stepsPerColor`, `gap`, `glow`,  
`distortion` (Voronoi); `density`, `spotty`, `midSize`, `midIntensity` (GodRays); `noiseScale 3`,  
`noiseIterations 8`, `radius 0.28`, `thickness 0.7`, `innerShape 0.7` (SmokeRing); `sizeRange`,  
`spreading`, `stepsPerColor` (DotOrbit); `repetition`, `softness`, `distortion`, `contour`  
(LiquidMetal); `highlights`, `layering`, `waves`, `caustic` (Water); `thickness`, `softness`,  
`intensity`, `bloom`, `spots`, `smoke` (PulsingBorder). Roughly 60 numbers, all in one `when`.

### 4.5 Colour plumbing (not motion, but tuned in the same place)

`FIELD_DARK_BACK_FOOT 0.35`, `FIELD_DARK_BACK_DEEPEN 0.22`, `FIELD_DARK_PALETTE_SINK 0.52`,  
`FIELD_DARK_PALETTE_SINK_EXTRA 0.42`, `FIELD_DARK_SINK_RELAX 0.5`. The extra-sink family list  
(MeshGradient, Warp, Voronoi, Swirl, Heatmap, HalftoneDots, StaticMeshGradient,  
StaticRadialGradient) is an inline `when`-comment, not data — unlike `FolioFieldCost`, which  
already models exactly this kind of per-family table properly.

### 4.6 The clock — the hardest constraint

From the library, verified in `FolioFieldCost`'s header and the artifact:

- `rememberShaderTime(speed, startFrame)` runs `LaunchedEffect(speed) { while(true) withFrameNanos { time += dt * speed } }` — a frame request **every vsync, forever**.
- It is `withFrameNanos`, not `withInfiniteAnimationFrameNanos`, so an `InfiniteAnimationPolicy` cannot throttle it.
- `startFrame` is seeded by an **unkeyed `remember`** — passing it later is ignored. It is a dead parameter at every call site.
- `speed = 0` is the only exposed value that changes the clock: it stops it.

Consequence: **the redraw rate is not tunable; only the amount of work per frame is.** Folio  
already spends the available lever well — area, via `folioFieldDownsample`, and presence, via  
`FolioFieldOccluder`. A "30 Hz field" is impossible without library support, and no setting should  
pretend otherwise.

### 4.7 The per-theme granularity that was removed

`folioFieldChoiceFor(packId, overrides)` reads **only** the global key. Per-pack keys are explicitly  
ignored ("Any legacy per-pack entries written by older builds are simply ignored"). The picker still  
takes a `packId` and its scroll memory is still keyed by pack, so the UI implies per-theme scope  
that the resolver no longer honours. A user who wants Gallery still and Arctic swirling cannot have  
it.

### 4.8 Two families sold as motion that cannot move

- **Waves** — no `u_time` uniform in `WavesKt`. Picker text: "Wave bands rolling across".
- **DotGrid** — no `u_time` uniform in `DotGridKt`. Picker text: "A quiet breathing dot grid".

Neither is frozen by reduce-motion either (there is nothing to freeze), so they also sit outside the  
motion setting's contract. HalftoneDots and the two `Static*` families are honestly static.

### 4.9 Knobs left at library defaults

From the artifact's own uniform sets, parameters that exist and are never passed:

| Family        | Unset                                                                                       | What it would control                                                        |
| ------------- | ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| PulsingBorder | `pulse`, `roundness`, `spotSize`, `smokeSize`, `marginTop/Bottom/Left/Right`, `aspectRatio` | **`u_pulse` is the breathing rate** — the one thing the family is named for. |
| ColorPanels   | `density`, `edges`, `fadeIn`, `fadeOut`, `length`                                           | Panel count and the slide's own ease in/out.                                 |
| PerlinNoise   | `lacunarity`, `persistence`                                                                 | The noise's character and how fast it evolves.                               |
| Spiral        | `noise`, `noiseFrequency`, `strokeCap`, `strokeTaper`                                       | Stroke character; the spiral is currently perfectly clean.                   |
| GrainGradient | `shapeScale` (procedural families also never get `sizing`)                                  | Pool tightness.                                                              |
| every family  | `startFrame`                                                                                | Dead by construction — see §4.6.                                             |

### 4.10 Timing constants outside Mirage

These are the app's other slow animations, each with its own hardcoded period:

| Layer                  | Period         | Tick      | Source               |
| ---------------------- | -------------- | --------- | -------------------- |
| Ambient swing / breath | 47 s / 71 s    | 100 ms    | `FolioAmbient.kt`    |
| Field pools            | 37 / 53 / 43 s | 100 ms    | `FolioAmbient.kt`    |
| Silk drift             | 48 s           | 16 ms     | `SilkField.kt`       |
| Theme rim beam         | 24 s           | 33 ms     | `FolioThemeRim.kt`   |
| Hero mesh              | 21 / 27 / 18 s | 100 ms    | `HeroMesh.kt`        |
| Guilloche              | —              | slow tick | `GuillocheShader.kt` |

And the Compose motion tokens (`FolioTokens`): `motionFast 120`, `motionStandard 220`,  
`motionEmphasis 320`, `motionMorph 450`, `motionTabMorph 300`, `motionShimmer 1200`,  
`motionLiquidSweep 420` ms. None of the seven is user-exposed; all are `val`s on a token object, so  
scaling them globally needs an indirection.

---

## 5. Suggested adjustments

Ranked by polish gained per unit of risk. R1–R3 are the ones I would do first.

### R1 — One tuning table instead of 100 literals

Add `FolioFieldTuning.kt`, modelled on the table `FolioFieldCost` already gets right:

```kotlin
@Immutable
data class FieldTuning(
    val speed: Float,              // 1.0 == the authored rate below
    val intensity: Float = 1f,     // multiplies the family's own intensity/brightness
    val scale: Float = 1f,         // multiplies size/count/density-type parameters
    val shape: FieldShape = FieldShape.Default,
)
```

with `fun FolioFieldKind.tuning(): FieldTuning` as an **exhaustive `when`** (so a new family cannot  
inherit a budget silently — the same argument `fieldRenderScale` makes). The `when` in  
`MirageField` then reads `t.speed`, `t.intensity`, `t.scale` instead of literals. This is  
behaviour-preserving on day one (every value moves verbatim) and makes every later change a  
one-line edit with a testable home.


### R2 — A global "Field motion" preference (Calm / Standard / Lively)

Three values multiplying `speed` only: **0.5 / 1.0 / 1.6**. Because `speed` scales the clock and not  
the redraw rate, this costs **nothing** in frames — the single cheapest polish lever available.  
Store it in `fieldOverrides` under a new reserved key (`__field_motion__`), riding the existing  
serialization and sync path with no migration. `Plain` stays as the "none" end of the same scale, so  
the picker becomes: None → Calm → Standard → Lively, one control instead of two.

Pair it with the existing periods (§4.10): multiplying `AMBIENT_PERIODS_MS`, `SILK_PERIOD_MS`,  
`RIM_PERIOD_MS` and `MESH_PERIODS_MS` by the same factor makes the whole room breathe at one tempo  
instead of the field racing ahead of the light.

### R3 — A "Field presence" preference (Subtle / Full)

A single multiplier on each family's intensity-like parameters (0.75 / 1.0), clamped by the existing  
dark/light values. This is the knob the P9 comments were reaching for by hand: a user on an OLED  
phone who finds the dark field too bright gets a real control instead of a global retune that  
over-darkens everyone else's device.

### R4 — Restore per-theme scope, as a choice

Change the resolver to read the pack key first and fall back to the global key:

```kotlin
val token = overrides[packId] ?: overrides[FOLIO_FIELD_GLOBAL_KEY]
```

The picker already takes `packId`; add a two-state toggle above it — "All themes" / "This theme  
only" — which decides whether `onChoose` writes the pack key or the global one. Nothing else changes;  
legacy per-pack entries start working again instead of being ignored, which is also what the UI  
already implies.

### R5 — Make Waves and DotGrid honest, or make them move

They have no `u_time`, so they cannot animate inside the shader. Two options:

- **Cheap:** relabel them ("Wave bands" / "Dot grid", described as still), and group them with the  
  two `Static*` families so the picker stops promising motion.
- **Better:** drive their *parameters* from Folio's own slow tick — `Waves.frequency`/`spacing`  
  nudged by `rememberSlowPhases(listOf(60_000L))`, `DotGrid.opacityRange`/`sizeRange` breathing on  
  its own period. They are uniform-only shaders, so this is a draw-phase state read and one  
  parameter write per tick, not a per-frame Kotlin loop. This also brings them inside the reduce-motion  
  contract, which today they are outside of.

### R6 — Set the knobs nobody set

Each of these is a design decision currently being made by the library's default:

- `PulsingBorder.pulse` — the breathing rate of a family whose entire identity is breathing. Pick  
  something far slower than the library default (a reading-app pulse, not a notification), e.g.  
  `0.15`, and set `roundness`/`spotSize` deliberately while there.
- `ColorPanels.density` + `edges` + `fadeIn`/`fadeOut` — currently a slide with library-default  
  count and library-default ease; `fadeIn`/`fadeOut` are exactly the "easing" the request asks  
  about, and they are per-family uniforms, not library constants.
- `PerlinNoise.lacunarity` / `persistence` — two numbers decide whether the cloud reads as weather  
  or as television static. The description promises "slow cloud drift"; the defaults are tuned for a  
  generic effect.
- `Spiral.noise` / `noiseFrequency` — the description says "fine strokes"; the strokes are currently  
  mathematically perfect, which is the one thing a hand-drawn motif should not be.

### R7 — Replace the `isDark` binary with a gain

`intensity = if (isDark) 0.26f else 0.38f` is two points on a curve that should be continuous. Fold  
`isDark` and the vibrancy lever into one `fieldGain: Float` and lerp:

```kotlin
val gain = fieldGain(isDark, vibrancy)   // 0 == the dark end, 1 == the light end
intensity = lerp(0.26f, 0.38f, gain)
```

The same shape then covers `GodRays` and `NeuroNoise`, and a future third face (a dimmed-light  
palette) needs no new branch.

### R8 — Shapes as an advanced option

Six families take a shape enum that is already a parameter. Exposing it per family is a small picker  
(3–4 values) and gives real range cheaply — e.g. `WarpPattern` Stripes/Checks/Cross changes Warp's  
character more than any number does.

### R9 — Retire the dead parameters honestly

`startFrame` is unusable at every call site (§4.6) and `filterSource(animate = ...)` is passed  
`false` unconditionally — both read as levers that do not exist. Either delete the plumbing or  
document it in one line where it is passed. Also worth a comment at `speed = s(...)`: *"speed scales  
the clock, not the frame rate; the field redraws at vsync regardless."* That is the single most  
misread fact in this subsystem.

### R10 — Make the cost story explicit in the UI

`FolioFieldCost`'s tiers are excellent and invisible. Two small things: show the resolved field  
scale in the existing debug overlay (it shows the field frame budget already), and consider a  
"Battery saver" that steps the tier down one notch (`scaleSoft` → `scaleGrain`) rather than  
switching the field off — a graceful middle that does not exist today.

---

## 6. Test and verification notes

- `FolioFieldCostTest` already pins the render-scale tiers; `FolioFieldTuning` should get the same  
  treatment (exhaustive `when` + a test that every family has an entry).
- Any new preference key must go through `SettingsMergeTest`'s field-count guard — the fixture  
  enumerates `ReaderSettings` properties, and this repo has already lost a test run to a fixture  
  gap (`editorialReading`).
- The reduce-motion contract should be re-checked for Waves/DotGrid if R5's parameter-driven  
  animation lands: today those two are outside `animate`.
- Visual checks: the field is a full-screen opaque layer, so a tuning change is best verified by  
  screenshot at a fixed time (the field is deterministic given `u_time`), not by eye across frames.

---

## 7. Summary table

| Area                               | Tunable now          | Hardcoded                 | Highest-value fix                |
| ---------------------------------- | -------------------- | ------------------------- | -------------------------------- |
| Family choice                      | 25 + Plain + Default | —                         | R4 (per-theme scope)             |
| Speed                              | —                    | 20 literals               | R1 + R2                          |
| Intensity / brightness             | —                    | 2-point `isDark` branches | R3 + R7                          |
| Geometry (size, count, density)    | —                    | ~60 literals              | R1 (`scale` field)               |
| Shape                              | —                    | 6 hardcoded enums         | R8                               |
| Easing / curves                    | —                    | inside library AGSL       | R6 (`fadeIn`/`fadeOut`, `pulse`) |
| Redraw rate                        | —                    | fixed at vsync            | R9 (document; no lever exists)   |
| Cost                               | automatic            | tier table                | R10                              |
| Room tempo (silk/rim/ambient/mesh) | —                    | 6 periods                 | R2 (shared multiplier)           |
