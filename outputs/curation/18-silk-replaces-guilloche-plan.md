# Plan — remove the guilloche, ship the silk field

**Brief.** The guilloche plate is deleted from the app entirely. In its place: the
three-layer procedural silk of the attached spec — a Lissajous ribbon family, an
epicyclic strand/ruling weave, and a Fourier light envelope — accumulated additively in
linear space and tone-mapped.

**Reference.** `D:\projects\reference images\silk-hero-reference-2026-10-06.png`
(411×938, cropped from the clipboard screenshot; the raw screenshot is kept alongside as
`silk-hero-raw-screenshot-2026-10-06.png`). Aspect `a = 0.4382` against the spec's 0.45.

**Status.** Plan complete; the five open decisions are resolved (§10). **No source has been
touched.** Next action is Phase 1 — the Python oracle.

---

## 1. What "remove the guilloche" actually touches

The plate is not one file and not one concern. It is **three** things, and only the first
is the thing being replaced. The other two are contracts that other subsystems depend on,
and they must be re-homed, not deleted.

| concern | symbols | what it really is |
|---|---|---|
| **the figure** | `GuillocheModel.kt` (989 L), `GuillocheShader.kt` / `.android.kt` (853 L) / `.desktop.kt`, `guillocheSpecFor`, `guillocheGeometryFor`, `guillocheSeed`, `guillocheAccentFor`, `guillocheLineColor`, `GUILLOCHE_ALPHA_MAX`, `GUILLOCHE_SHIMMER`, `GUILLOCHE_PERIODS_MS` | the thing being replaced |
| **the text-legibility contract** | `GuillocheQuiet.kt` (180 L), `LocalFolioGuillocheQuiet`, `rememberFolioGuillocheQuiet`, `Modifier.folioGuillocheQuiet`, `GuillocheQuiet`, `guillocheWeightAt`, `GUILLOCHE_QUIET_MARGIN` | "a line pattern behind glyphs ragges" — the plate must be *exactly* absent under text. A **categorical** guarantee, not a contrast ratio. |
| **the refraction source** | `LocalFolioGuillocheActive`, `GuillocheShader.android.kt`'s `plateAt` / `GUILLOCHE_UNIFORMS` / `GUILLOCHE_FUNCTIONS`, `plateIntensityRef`, `plateTiers`, `platePackId`, `GLASS_TRAVEL_KAPPA(_MEAN)`, `GLASS_TRAVEL_PERIOD_MS` | `GlassShader.android.kt` documents at length why the gel degenerates on a smooth ramp: *"a lens rim has almost nothing to bend."* The plate is the detail it bends. Remove the plate and the gel loses its input. |

Consumers (every one must be re-pointed):

| file | uses |
|---|---|
| `androidApp/.../MainActivity.kt` (L501–556) | provides both locals, calls `.folioGuilloche(...)` on the app's ground plane |
| `androidApp/.../nav/FolioNavShell.kt` (L311) | `.folioGuillocheQuiet()` |
| `androidApp/.../settings/ThemeLabScreen.kt` (L169) | `.folioGuilloche(...)` |
| `shared/composeUi/.../components/FolioEditorial.kt` (L94, 135, 189) | `.folioGuillocheQuiet()` ×3 |
| `shared/composeUi/.../components/ComponentsStates.kt` (L112) | `.folioGuillocheQuiet()` |
| `shared/androidMain/.../GlassShader.android.kt` (L176–213, 316, 650, 690, 729, 929) | spec, phases, tiers, quiet uniforms, `plateIntensityRef` |
| `shared/desktopTest/.../GuillocheTest.kt` (522 L) | the model's whole test surface |
| `shared/desktopTest/.../GlassTravelTest.kt` | asserts `GLASS_TRAVEL_PERIOD_MS` is coprime-ish with `GUILLOCHE_PERIODS_MS` |

**Consequence for the plan:** Phase 5 is *seam surgery*, and the silk has to answer two
questions the guilloche answered implicitly:

**Resolved — the silk is quiet under text by construction, through an intensity scalar.**
The reference measures **mean relative luminance 0.090 over the whole frame** (§7 below)
and the plate ran at `GUILLOCHE_ALPHA_MAX = 0.48`, so a straight swap would fight body
copy everywhere. The silk therefore takes over the plate's *role* and plugs into the same
machinery, with one new scalar:

```kotlin
// One scalar, three regimes. Multiplied, never branched.
silkIntensity: Float
    hero surfaces   -> 1.00        // full: this is the reference
    ground plane    -> ~0.30       // "much lower" - the value is a Phase 5 measurement, not a guess
    under glyphs    -> 0.00        // exactly zero, via silkWeightAt (the existing quiet contract)
```

Composition stays multiplicative and in the same order the plate used it:

```kotlin
weight = silkIntensity * silkWeightAt(x, y, quietRegions) * breathe(p)
```

**`silkIntensity` must scale `I` (the radiance field) in linear space, before bloom and
before the tone map** — not alpha-composited over the finished image. §7's light budget is
a *ratio* test, and scaling `I` scales COOL, MID and WARM identically so the budget is
preserved at every intensity; a post-tone-map lerp toward BG would not preserve it. This is
also why the intensity is one number and not three.

**Resolved — the gel refracts an analytic proxy.** A 0.7 px strand mesh is a bad
refraction source: it aliases (the model/shader-parity skill's recorded failure), so the
gel would be bending noise. The silk publishes instead its three centre-lines `L_j(θ,p)`,
their widths `w_j(θ,p)` and the envelope `B_j(θ,p)` as uniforms; the gel evaluates them and
refracts a **smooth** field. Cheap, alias-free, and no texture round-trip. This is the same
shape as the existing `GUILLOCHE_FUNCTIONS` shared snippet — one source of truth for "where
is the ribbon", interpolated into both shaders.

---

## 2. What the spec fixes, and what it leaves open

The spec is unusually explicit about the split, and the plan must respect it:

> *"Treat every number as a default inside one parameter table, but do not change the
> structure."*

So **structure is frozen** (3 layers, the exact functional forms, the integer-frequency
rule, the accumulation order) and **every scalar is a default** — `R = 3`, `M = 56`,
`S = 720`, `N = 260`, `m = 34`, `c = 1.7`, `ε = 0.006`, `β = 2.2`, `T = 40 s`, `w0 = 0.16`,
`κ = 0.01`, and the three theme tables. That is the licence the LOD work in §4 needs.

Two things the spec states as *hard* and that a naive implementation gets wrong:

- **Every temporal term is `2π·k·p` with INTEGER `k`,** so `F(p=1) ≡ F(p=0)` **exactly**.
  This is the loop-closure test, and it is a property of the *form*, not of sampling. It
  also constrains the device clock: Folio's house clock is a **sawtooth** `phase ∈ [0,2π)`
  / `turns ∈ [0,1)` pair (`rememberSlowPhases`), and the model/shader-parity skill already
  records the shipped bug — `sin(phase * k)` is `k·2π` turns, not `k`. Here the spec
  writes the phase as `p = (t mod T)/T ∈ [0,1)`, i.e. **`turns`**, so every `2π·k·p` is
  safe *provided `p` is taken from the `turns`-flavoured clock*. Feeding `phase` into
  `2π·k·p` would advance `k·2π` laps per lap — a 6×-speed, once-per-lap snapping silk.
- **`k_n = (n mod 3) + 1` for `n ∈ {1,2,3,5}`** → `{2, 3, 1, 3}`. Note `n=3` and `n=5`
  share `k=3`. That is intended, not a typo, and the acceptance test for motion (§8.7)
  quotes `{2,3,1,3}`.

### The silk's clock — its own, and frozen when the plane is paused

Resolved: the silk does **not** read Folio's clock at all. It keeps its own, so the
"6×-speed silk that snaps once per lap" failure cannot be reached by construction rather
than by remembering to use `turns`:

```kotlin
/** p in [0,1). Own period. Frozen (not reset) when the ground plane is paused. */
internal fun silkPhase(elapsedMs: Long): Float =
    ((elapsedMs % SilkParams.PERIOD_MS).toFloat() / SilkParams.PERIOD_MS)
```

- **Independent of Folio.** `p = (elapsed / 40 s) mod 1` from the silk's own elapsed time.
  No dependency on `rememberSlowPhases`, `GUILLOCHE_PERIODS_MS`, or `GLASS_TRAVEL_PERIOD_MS`.
- **Frozen, not reset, when paused.** Reduce-motion / paused ground plane parks the phase
  at its *current* value (the model already has `GLASS_TRAVEL_PARKED_RAD` as precedent for
  a parked phase) — resetting to 0 would make the image jump on resume, and jumping is the
  one thing §8.7 forbids outright.
- **Driven at the house 10 Hz tick.** The silk is slow (`T = 40 s`, fastest harmonic
  `k=3` → 0.075 Hz), so there is nothing to gain from a per-frame clock; reuse
  `SLOW_MOTION_TICK_MS` so the frame loop still idles between ticks.
- **`T = 40 s` is the fixed period and the other clocks are the free variables.** The plate
  ran `53 s / 79 s`, the glass `142 s`. Three clocks that must not beat — check the coprime
  relationships before wiring (§9.7).

### The parameter table, as data (Kotlin)

```kotlin
internal object SilkParams {
    const val R = 3                       // ribbons
    val AX_FRAC = 0.90f                   // Ax = AX_FRAC * a
    const val AY = 1.10f                  // > 1 on purpose: ribbons leave top & bottom
    val DELTA = floatArrayOf(0f, 2.0943951f, 4.1887902f)          // 2*pi/3 steps
    val GAMMA = floatArrayOf(0f, 0.7f, 1.4f)
    val PHI   = floatArrayOf(0f, 1.1f, 2.3f)
    const val W0 = 0.16f
    const val KAPPA = 0.01f               // pinch floor -> min w ~ 0.1 * w0
    const val M = 56                      // strands per ribbon (LOD'd at render time)
    const val S = 720                     // samples per strand, closed
    const val N = 260                     // cross-rulings per ribbon
    const val M_FREQ = 34                 // epicyclic spatial frequency
    const val C_FREQ = 1.7f               // epicyclic phase shear per unit u
    const val EPS = 0.006f                // ~4% of w0
    val N_HARM = intArrayOf(1, 2, 3, 5)
    val K_N    = intArrayOf(2, 3, 1, 3)   // (n mod 3) + 1
    const val BETA = 2.2f
    const val HOT_CORE = 4f
    const val HOT_MIX = 0.5f
    // The silk's OWN clock - see "The silk's clock" below. Never Folio's.
    const val PERIOD_MS = 40_000L
    // LOD floor: strand spacing in device px. See §3.
    const val MIN_STRAND_SPACING_PX = 1.5f
    // RULING: dTheta = 0.10 + 0.04*sin(7*theta + 2*pi*2*p)
}
```

### The 12 envelope phases — generated once, then hardcoded

The spec calls for *"12 fixed phases in [0, 2π), generated once from a deterministic PRNG
(seed 1337) and then hardcoded."* The PRNG is unspecified; the plan pins **SplitMix64**
(one draw per phase, uniform in `[0,1)`, `× 2π`), so the values are reproducible from the
seed and the choice is recorded rather than implicit. Generated now, to be pasted into
`SilkModel.kt` as literals:

```
psi[j][n],  n in (1, 2, 3, 5)
  j=0   4.483122  4.994561  1.282503  2.920932
  j=1   1.439687  4.163606  2.302567  0.588848
  j=2   5.535523  1.395522  4.864267  0.639519
```

---

## 3. The architectural crux: 121k primitives per frame

Counted straight from the spec:

- strands: `R × M = 3 × 56 = 168`, each `S = 720` samples → **120,960** segments
- rulings: `R × N = 3 × 260 = 780` segments
- edges: `R × 2 = 6` strands
- ≈ **121,750 primitives per frame**, each needing its own α, colour and (for strands)
  the envelope `B(θ, p)`.

§5.1 also demands they be accumulated in **linear** space with `α·C` **added** (no
alpha-over, no depth) and kept in floating point *with headroom*, then blurred twice and
tone-mapped. That is a radiance-field pipeline, not a vector drawing.

### Path A — CPU primitive list, drawn as Skia lines. **Infeasible as written.**
121k `drawLine` calls per frame through Compose's `Canvas` is ~0.1–1 s/frame on a phone.
Even batched into a `Path` per ribbon the *stroke* rasterisation is fine but the per-
primitive α and colour differ, so it needs 121k separate draws. Dead on arrival at
`M=56, S=720`.

### Path B — analytic level-set shader. **Recommended.**
The structural observation that makes the silk cheap: **both meshes are level sets of the
ribbon's own `(θ, u)` parametrisation.**

- A strand `C_{j,k}(θ) = S_j(θ, u_k)` is the locus `u = k/(M−1)` — i.e. `u · (M−1)` integer.
- A ruling runs `θ_i → θ_i + Δ_i` across the full width, so it is the locus `θ` at a
  discrete set of values — i.e. `θ · N/(2π)` integer.

So the fine mesh is `fract()`-periodic in `(θ, u)` and costs **two periodic functions per
pixel**, not 121k draws. Per pixel: find `(θ, u)` (Newton, seeded from the 3 centre-lines —
the ribbon is a narrow band, so the seed is unambiguous), then evaluate
`B(θ, p)`, the two level-set weights, the width weight `w_j(θ,p)` and the colour ramp.
`w_j` already carries the pinch, so §3(d)'s convergence-at-the-waist falls out of the
geometry rather than being added.

This is exactly the shape `GuillocheShader.android.kt` already has (one fragment shader,
uniforms fed from Kotlin, the shared `GUILLOCHE_FUNCTIONS` snippet interpolated into
`GlassShader`), so it fits the existing architecture and the existing skill.

#### Correction: the level set is **not** `u = k/(M−1)`. The ε·g offset has to be inverted.

The shortcut above is only exact if the strand locus is a level set of the *un-offset*
width coordinate. It is not: `S_j(θ,u,p) = L_j + (2u−1)·w_j·n_j + ε·g(θ,u,p)`, and `g`
depends on `u` through the `2π·c·u` shear. Measured against the spec's own defaults:

| | value | vs strand spacing `2w/(M−1)` |
|---|---|---|
| ε | 0.006 | — |
| strand spacing at **max** width (`2·0.16/55`) | 0.005818 | **ε = 1.03× one spacing** |
| strand spacing at **min** width (`2·0.0159/55`) | 0.000579 | **ε = 10.4× one spacing** |

So at full width the offset displaces a strand by a *whole strand period* — the naive locus
is not perturbed, it is **decorrelated**, and the moiré (which is exactly what the offset
exists to produce, §3(a)) disappears. At the waist it is ten periods out.

The Jacobian of `S` splits cleanly, and the split is the useful part:

| column | ribbon term | offset term | ratio |
|---|---|---|---|
| `∂S/∂u` at max width | `2w = 0.3200` | `ε·2πc = 0.06409` | **20.0%** |
| `∂S/∂u` at min width | `2w = 0.0318` | `ε·2πc = 0.06409` | **201.3%** |
| `∂S/∂θ` | `|T| = 10.02` | `ε·m = 0.2040` | **2.0%** |

**The `θ` inversion may ignore the offset (2%); the `u` inversion may not (20–201%).** That
asymmetry is what keeps this cheap: seed `θ` from the un-offset inverse, then solve `u` with
the offset term included — one or two Newton steps, since `∂(εg)/∂u` is analytic and
bounded. At the waist the offset *dominates* `∂S/∂u`, which is the quantitative statement
of why the knots live at the pinches (§3(d)): the mesh there is almost entirely the
epicyclic term.

**Required parity test.** Rasterise the Kotlin oracle's primitive list and the shader at
**p = 0, 0.25, 0.5, 0.75** and fail on divergence. Proposed tolerance, to be calibrated in
Phase 3: mean absolute radiance difference ≤ **2% of the frame's peak `I`**, and the
**99th-percentile** difference ≤ **8%** — the mean catches a global decorrelation (a lost
moiré) and the percentile catches the knots, which is where a level-set renderer and a
stroke accumulation will disagree first. **The test must include the pinch regions
explicitly**, not just the frame average: a mesh that is right everywhere except at the
waists has lost the composition and would pass a mean-only check.

#### LOD: `M` is derived, and the alpha is rescaled so the light budget cannot shift

Resolved: `M` comes from the **rendered** ribbon width, not the spec's fixed 56.

```kotlin
// Strand spacing in device px, measured at the widest point of the ribbon on this frame.
val spacingPx = 2f * w0 * ribbonWidthPx
val m = floor(spacingPx / SilkParams.MIN_STRAND_SPACING_PX).toInt()
        .coerceIn(4, SilkParams.M)

// Radiance is conserved, so §7's 70/20/10 budget is invariant to the LOD.
val alphaScale = SilkParams.M.toFloat() / m

// Fade, do not pop: fwidth gives each strand a coverage ramp instead of a binary edge.
val coverage = smoothstep(0f, 1f, (spacingPx - MIN_STRAND_SPACING_PX) / fwidth(u_index))
```

- **Spacing floor ≈ 1.5 px.** For scale: a 54 px ribbon needs `M ≤ 36`, a 142 px ribbon
  `M ≤ 94` — so on a phone the spec's `M = 56` is *below* the alias floor on narrow
  ribbons and only just above it on wide ones. `MIN_STRAND_SPACING_PX` is the gate; the
  spec's `M = 56` becomes the ceiling, not the value.
- **`α ×= 56/m`** keeps total radiance constant, which is what makes the light budget a
  *property of the model* rather than of the device's screen size.
- **`fwidth`-based coverage, not popping.** `fwidth` (AGSL `fwidth`/`dfdx`) is what turns
  a strand that has fallen below the floor into a smooth fade instead of a strobing
  on/off as `M` steps. This is the difference between an LOD and a flicker.

**But it changes *how* the image is formed** — a density field instead of an accumulation
of finite-width strokes. §5.1's "keep I in floating point with headroom" is precisely the
kind of thing a shader gets subtly wrong (an `half4` clamps at 1.0 and the knots clip).
So B is only acceptable **with the parity harness above** (§6, Phases 1 and 3), not on
the strength of looking right.

### Path C — precomputed primitive list at the house clock rate. **Fallback.**
Bake geometry on the CPU at 10 Hz (`rememberSlowPhases`), with `M` derived from the
on-screen ribbon width — the model/shader-parity skill's hard-won rule: *"the count must be
DERIVED: `n = ribbon_width / 4 px`"*, because thread spacing below the pixel pitch aliases
into dots and **no alpha or blur fixes it**. Then upload and draw. Keeps the spec literally
and keeps §5.1 exactly, but is the slowest and needs the LOD to be aggressive.

**Recommendation:** model in Kotlin as Path A's primitive list (the oracle — the spec
explicitly asks for it: *"the geometry can be unit-tested without drawing"*), render on
device as Path B, and keep Path C as the fallback if B cannot be calibrated to within the
§7 tolerances.

---

## 4. Module plan

### New

| file | contents |
|---|---|
| `shared/src/composeUi/.../theme/SilkModel.kt` | the pure model. `SilkParams`, `SilkPhaseTable` (the 12 hardcoded ψ), `silkField(theta,u,j,p): SilkSample(position, B, color)`, `silkStrands(j,p): List<Strand>`, `silkRulings(j,p): List<Ruling>`, `silkEdges(j,p)`, `silkEnvelope(theta,j,p)`, `silkWidth(theta,j,p)`, `silkColour(u,B,theme)`, `silkPhase(elapsedMs)`, `silkLod(ribbonWidthPx): SilkLod(m, alphaScale)`, `SilkIntensity(hero/ground/quiet)`. **No drawing, no Android.** |
| `SilkClock.kt` (or a section of `SilkModel.kt`) | `silkPhase(elapsedMs) = (elapsedMs % 40_000) / 40_000f`, plus the parked/frozen variant. Own period, no dependency on `rememberSlowPhases`. |
| `SilkTheme.kt` | `SilkTheme(bg, cool, mid, warm, hot)`; `validateSilkTheme` implementing §6's four invariants; `correctSilkTheme` (auto-correct); `lerpSilkTheme(a,b,t)` in **linear** space with ease-in-out; `SilkThemes.aurora/ember/orchid`. |
| `SilkShader.kt` (expect) + `SilkShader.android.kt` + `SilkShader.desktop.kt` | the device renderer. Desktop returns the receiver unchanged (**Rule 19** — no second implementation to keep in agreement). |
| `SilkQuiet.kt` | `GuillocheQuiet.kt` renamed, mechanism intact: `SilkQuiet`, `LocalFolioSilkQuiet`, `rememberFolioSilkQuiet`, `Modifier.folioSilkQuiet`, `silkWeightAt`, `SILK_QUIET_MARGIN`. |
| `desktopTest/.../SilkTest.kt` | §7's acceptance tests 1, 2, 3, 6, 7, 8 — plus 4 as a texture metric and 5 as a colour-relationship metric. |
| `.dbg/silk/silk_ref.py` | the Python reference renderer + the four numeric checks. The oracle for Phase 1, and the thing that renders the comparison sheets. |

### Deleted

`GuillocheModel.kt`, `GuillocheQuiet.kt`, `GuillocheShader.kt`, `GuillocheShader.android.kt`,
`GuillocheShader.desktop.kt`, `GuillocheTest.kt`, and `GlassShader.android.kt`'s plate
half (`plateAt`, `GUILLOCHE_UNIFORMS`, `GUILLOCHE_FUNCTIONS`, `applyQuietUniforms`,
`plateIntensityRef`, `plateTiers`, `platePhases`) — subject to the §1.2 decision.

`GlassTravelTest.kt` survives but its `GUILLOCHE_PERIODS_MS` reference must be re-pointed
(the silk's clock is a single `T = 40 s`, so the "coprime periods" assertion changes shape
entirely — it becomes "the silk's single period is coprime with the glass's 142 s").

---

## 5. The theme bridge: 20 palettes → 5 silk colours

The spec's theme is 5 colours with four **hard invariants and auto-correction**. Folio has
**20 palettes**, each a full role set. These are different shapes, and this is the largest
integration unknown in the whole plan.

```kotlin
data class SilkTheme(val bg: Color, val cool: Color, val mid: Color, val warm: Color, val hot: Color)

// §6 invariants, validated then auto-corrected (never silently violated):
//  1. relL(BG) < relL(COOL) < relL(MID) < relL(WARM) < relL(HOT)   [linear]
//  2. relL(BG) < 0.01
//  3. hueSep(COOL, WARM) >= 120deg, and MID's hue on the SHORTER arc between them
//  4. geometry identical for every theme; only the five colours change
```

The derivation from a Folio palette (proposed, to be validated by the sweep):

| silk role | source | note |
|---|---|---|
| `BG` | `backgroundBase` | already `< 0.01` on every dark face. **Light faces do not get a silk theme at all** — see below. |
| `COOL` | `primary` | the body's dominant hue |
| `MID` | `tertiary` (fallback `secondary`) | must land on the short arc |
| `WARM` | `warmAccent` | the u=0 edge |
| `HOT` | the lightest `on*` ink | the u=1 cool-white edge and the hot core |

Then `validateSilkTheme` → `correctSilkTheme` in the same place `tier_colour` already lives,
so the correction is gamut-safe by construction, and the sweep runs over `AppPalette.entries`
so all 20 are proven before anything ships. **Per-face overrides** must be allowed: the
spec's own presets (Aurora / Ember / Orchid) do not map 1:1 onto Folio's 20, and a
correction that fights a deliberately near-achromatic face (the System faces, the
`chroma 0.009–0.026` backgrounds the theme skill already flags) will need one.

### Dark faces only — resolved, and it is a *categorical* gate

**The 9 dark faces + the dark System face get a silk theme. The 10 light faces do not**, and
keep their quiet ground (or their pre-silk behaviour) untouched.

The reason is not effort, it is that the spec's model is **additive light**: §5.1 sums
`α·C` with no occlusion and no alpha-over, and §5.4 adds the background *before* tone
mapping. Additive light cannot exist on paper — `relL(BG) < 0.01` (§6 invariant 2) is not a
stylistic preference, it is what makes `1 − exp(−1.15c)` a *glow* rather than a wash, and
relaxing it collapses the whole §6 tonal ladder (`BG < COOL < MID < WARM < HOT`) that the
warm edge's "brightest chromatic element" claim rests on.

So the gate is **categorical, at the same layer as `isDark`**, not a per-face tunable:

```kotlin
// Same shape as the plate's existing reader gate: a capability, not a preference.
enabled = isDark && glassCapabilities.specular && !isReadingScreen
```

**If silk on light faces is wanted later, it is a separate spec with its own model** — a
multiply-ink variant (subtractive, `B` modulating ink density rather than emitting light),
its own invariants, and its own acceptance tests. It is explicitly **not** a tweak to this
one, and this plan does not carry a hook for it.

Consequence for the sweep: the palette sweep in Phase 4 runs over the **10 dark palettes**,
not all 20. The light 10 are asserted to be *unaffected* — i.e. the sweep must prove they
have no silk theme and that their ground plane is byte-identical to pre-silk behaviour.

**Measured, against the reference** (see §7): the reference's own light budget is
COOL 75.3 / MID 21.6 / WARM 3.1, and the Aurora preset satisfies all four invariants
(hue separation 130.9°). So the default theme is already validated — the bridge is about
the other 19.

---

## 6. Phases, each with a gate

**Phase 0 — reference in hand.** ✅ The image is saved (crop + raw) and measured (§7).

**Phase 1 — Python oracle.** `.dbg/silk/silk_ref.py`: the full model, the primitive list
generators, the linear-space accumulation, the two-Gaussian bloom, the tone map, the
background and vignette. Render the reference size and produce a comparison sheet.
**Gate:** acceptance tests **1** (loop closure, `max|F(p=1)−F(p=0)| ≤ 1e-9` *and* every `k`
integer), **2** (`count(local minima of w_j over θ) == 4` for all three `j`), **3**
(`min w / max w ≈ 0.1`), **6** (light budget 70/20/10 ± 8) all pass numerically, **and** the
render reads as the reference at a glance.

**Phase 1 also carries two added requirements**, both of which are prerequisites for the
shader and cannot be retro-fitted after it exists:

**1.1 — The ε·g offset must be modelled, and proved.** The oracle is the *reference* for the
offset: it builds `S_j(θ,u,p)` with `ε·g` included, and it publishes the strand locus
computed **both** ways — the exact one, and the naive `u = k/(M−1)` level set. The gate is a
measurement of how far apart they are, per `p`, in the pinch regions specifically. If the
naive locus were adequate the numbers in §3 would say so; the plan asserts it is not and
the oracle must show the size of the error rather than assume it. **This is the number that
sizes the shader's Newton correction.**

**1.2 — The LOD must be modelled, and the budget proved invariant.** The oracle renders the
same `p` at **three `M` values** — the spec's 56, and two reduced ones — and the light
budget test (6) must pass at all three. That is the proof that `α ×= 56/m` conserves
radiance, and it has to happen in Python where the accumulation is literal, before any
shader exists that could hide the error in a tone map.

The oracle additionally emits the **parity fixtures** for Phase 3: the radiance field `I`
at `p = 0, 0.25, 0.5, 0.75`, full frame and the three pinch crops, as float arrays. Phase 3
is then a comparison against a file, not a judgement call.

**Phase 2 — port to Kotlin.** `SilkModel.kt` + `SilkTheme.kt`, and `SilkTest.kt` mirroring
the four tests. **Gate:** Kotlin ≡ Python on a shared sample grid (max abs difference
≤ 1e-6 over `θ ∈ [0,2π)`, `u ∈ [0,1]`, `j ∈ {0,1,2}`, `p ∈ {0, 1/8, …, 7/8}`), plus
`desktopTest` green.

**Phase 3 — the device renderer.** Path B in AGSL, with the shared-snippet discipline the
skill records (one `SILK_FUNCTIONS` interpolated into the silk shader *and* the gel — the
gel now reads the centre-line proxy, so the two **must** agree on where a ribbon is; the
declared↔set set-difference run in **both** directions; the `"""` delimiter and "declared
exactly once" greps before every build).
**Gate — the required parity test.** Rasterise the shader and the Kotlin oracle's primitive
list at **p = 0, 0.25, 0.5 and 0.75** and fail on divergence beyond tolerance:
**mean abs radiance difference ≤ 2% of the frame's peak `I`**, **99th-percentile ≤ 8%**, and
the **pinch crops tested separately** — a mesh that is right everywhere except at the waists
has lost the composition and would pass a mean-only check. Plus
`:shared:compileDebugKotlinAndroid` (not just `…KotlinDesktop`).

The parity test is not a formality here. It is the only thing that catches the failure mode
this architecture actually has: the level-set shader silently dropping the ε·g term and
producing a *smooth* ribbon that looks plausible and has no moiré. That render would pass
every test in `SilkTest.kt` and be wrong.

**Phase 4 — theme table + bridge.** The 20-palette sweep, the invariant validator, the
auto-correction, and the per-face overrides. **Gate:** all **10 dark** palettes pass the
four invariants after correction, the correction count is reported per face, and the 10
light palettes are proved **byte-identical to their pre-silk ground** (§5).

**Phase 5 — seam surgery.** Delete the guilloche, wire the silk at the two call sites that
draw it (`MainActivity` ground plane, `ThemeLabScreen`), re-home the quiet contract, and
point the gel at the centre-line proxy. **Calibrate `silkIntensity` here** — the ground-plane
value is read off the rendered silk against body text, not chosen. **Gate:** every symbol in
§1's table is either gone or renamed-and-consumed; `grep -rn "uilloche" shared/src
androidApp/src` returns **nothing**; the app compiles on both source sets; and the
ground-plane intensity is a measured number with the contrast reading that produced it.

**Phase 6 — build and device.** The real suite, the Android compile, then device
verification of the pixels — which is the only thing that proves a shader, and the only
step no test can stand in for.

---

## 7. The reference, measured (Phase 0 output)

At 411×938, `a = 0.4382`:

| quantity | reference | spec target | verdict |
|---|---|---|---|
| BG (5th pct) | `(0, 6, 18)`, `relL 0.00184` | `relL(BG) < 0.01` | ✅ |
| frame mean `relL` | 0.090 | — | the legibility constraint (§1.1) |
| median pixel | `(7, 23, 62)` | — | mid-dark blue, i.e. body glow fills a lot of frame |
| p95 / p99.5 / max | `(104,100,182)` / `(226,197,247)` / `(255,249,255)` | non-HOT peak ≲ 245 | ✅ (the 255 is HOT) |
| near-black (<8/255) | 11.9% | — | much less quiet than a "faint engraving" |
| lit chromatic pixels | 51.1% of frame | — | |
| **light budget** | **COOL 75.3 / MID 21.6 / WARM 3.1** | **70 / 20 / 10 ± 8** | ✅ all three inside |

Aurora preset, checked against §6: hues `COOL 266.2°`, `MID 290.0°`, `WARM 37.1°`;
separation `130.9°` (≥ 120 ✅); MID lies on the short arc `266.2 → 397.1` ✅;
`relL` ordering `0.000999 < 0.1531 < 0.2155 < 0.5819 < 0.9070` ✅; `relL(BG) < 0.01` ✅.

Two numbers here are the plan's real warnings: **mean `relL` 0.090** and **51% of the frame
lit** are *not* a background texture — they are a hero image. That is why the silk needs an
intensity scalar and not just a quiet mask (§1.1).

### The ε·g offset, measured against the spec's own defaults

Derived, not estimated — the same arithmetic the oracle will assert in Phase 1.1:

| quantity | value |
|---|---|
| `ε` | 0.006 |
| `w_max` / `w_min` (`κ = 0.01`) | 0.160000 / 0.015921 — ratio **0.0995**, the §7.3 target |
| strand spacing at max width, `2w/(M−1)`, `M = 56` | 0.005818 → **ε = 1.03 spacings** |
| strand spacing at min width | 0.000579 → **ε = 10.36 spacings** |
| `|∂S/∂u|` offset term, `ε·2πc` | 0.06409 |
| `|∂S/∂u|` ribbon term at max / min width | 0.3200 / 0.0318 |
| → offset share of `∂S/∂u` | **20.0% / 201.3%** |
| `|∂S/∂θ|` offset term, `ε·m` | 0.2040 |
| `|∂S/∂θ|` ribbon term `|T|` (`a = 0.438`) | 10.02 → offset share **2.0%** |

The asymmetry is the whole design: **the `θ` inversion can ignore the offset, the `u`
inversion cannot.** `u` is what selects the strand, so `u` is the mesh, so `u` is the moiré.

---

## 8. Symptom → parameter (spec deliverable 4)

| symptom | parameter | direction |
|---|---|---|
| ribbons too few / too many in frame | `R`, `Ax`, `Ay` | `Ay > 1` is what makes them leave the frame top and bottom; lowering it pulls them inside and they read as loops |
| the whole thing reads as a *rotation* | the `k` values | **forbidden.** Every spatial/temporal term must be an integer multiple; a non-integer `k` is the usual cause |
| pinches not 4 per cycle, or not travelling | `φ_j` (offset only) vs the `2π·2·p` term in `w_j` | the count is `π/2`-periodic in θ → always 4. If the count is wrong, `w_j` has been edited, not `φ_j` |
| pinch too deep / too shallow | `κ` | `min w ≈ w0·√(κ/(1+κ))`; `κ = 0.01` → ratio 0.0995 ≈ the 0.1 target |
| ribbon too fat / too thin | `w0` | linear; check it against the *pixel* pitch at render time, not in normalised units |
| mesh too coarse / too fine / aliasing into dots | `m` (`34`), and `M` at render time | `m` sets the dominant strand-spacing frequency (§7.4). `M` must be derived from on-screen ribbon width — `n = ribbon_width / 4 px` — or the strands alias |
| moiré absent / mushy | `c` (`1.7`) and `ε` (`0.006`) | `c` is the phase shear per unit `u` (the interference); `ε` is only ~4% of `w0`, so it modulates texture and must never carry the shape |
| diagonal net missing or wrong slope | `Δ_i = 0.10 + 0.04·sin(7θ_i + 2π·2·p)` | the `0.10` is the obliquity; the `0.04` is its travelling wobble |
| bands too hard / too soft | `β` (`2.2`) | `B = E^β`; higher = narrower bright bands, larger dark gaps |
| bands drift as one | `k_n = {2,3,1,3}` | each harmonic must move at its own speed; equal `k` = the whole envelope pulses |
| interior too colourful | the `W_warm/W_mid/W_cool` smoothstep edges (`0.35`, `0.70`) | the 70/20/10 budget is enforced **here**, by the gradient weights — *"not by changing alphas"* |
| edge highlights invisible or two-toned wrong | `α` for `u=0` (`0.55+0.40B`, warm) vs `u=1` (cool-white, `mix(COOL,HOT,0.6)`) | the asymmetry is *defining*; `u=0` warm, `u=1` cool-white |
| knots not bright, or clipping | the `Σ α·C` accumulation | §3(d): knots come from strand convergence at `w → min` **plus additive accumulation**. Never add glow sprites — that is explicitly forbidden |
| glow too tight / too wide | `σ1 = 0.006H`, `σ2 = 0.012H`, weights `0.60 / 0.35` | separable Gaussians |
| too dark / clipped neon | `1.15` in `1 − exp(−1.15·c)` | the exposure; §5.5 wants non-HOT peaks ≲ 245/255 |
| corners too dark / too flat | the vignette `1.0 → 0.82` | |
| visible seam at the loop point | any `k` | `F(p=1) ≡ F(p=0)` is exact iff every `k` is an integer — check the *integer* property, not a 3-sample comparison |

---

## 9. Risks, named up front

1. **Path B's parity — the top risk, and now a named test.** A level-set shader is a
   *different image-forming model* from an accumulation of finite-width strokes. Its
   characteristic failure is silent: drop the ε·g term and you get a **smooth ribbon that
   looks plausible and has no moiré** — which passes every test in `SilkTest.kt` and is
   wrong. The Phase 3 parity test at four `p` values, with the pinch crops tested
   separately, is the mitigation. If it cannot be brought inside tolerance, Path C is the
   answer.
2. **The `M`/`S` LOD is load-bearing, not a nicety.** At the spec's `M = 56` on a narrow
   ribbon the strand spacing is below the pixel pitch — the skill records this exact
   failure (*"threads 0.64–0.75 px apart … they alias into a dot pattern … the reference
   measures 4.0 px"*). Resolved by deriving `M` from the rendered width with a 1.5 px floor
   and rescaling `α ×= 56/m`; the residual risk is that **the rescale must be proved in
   Phase 1.2**, because a tone map can hide a radiance error that the budget test would
   otherwise catch.
3. **The light faces.** ✅ Resolved: dark faces only, categorically gated (§5). Residual
   risk is only that the gate must be *proved* — the Phase 4 sweep has to assert the 10
   light palettes are byte-identical to their pre-silk ground, not merely that they were
   skipped.
4. **The gel's refraction input.** ✅ Resolved: the analytic centre-line proxy (§1.2). The
   residual work is the `SILK_FUNCTIONS` discipline — the gel and the silk shader must not
   be able to disagree about where a ribbon is, which is exactly the bug class the
   model/shader-parity skill exists for. `LocalFolioGuillocheActive` is renamed, not
   dropped.
5. **Text legibility.** Partly resolved by `silkIntensity` (§1.1): full on hero surfaces,
   low on the ground plane, exactly zero under glyphs. The residual risk is that **the
   ground-plane value is a measurement, not a choice** — `~0.30` in the plan is a starting
   point, and Phase 5 must pick it by reading the actual contrast of body text over the
   rendered silk, the way the plate's quiet contract was calibrated. The quiet contract
   suppresses *where*, not *how bright*.
6. **`GlassTravelTest`** asserts a coprime relationship against `GUILLOCHE_PERIODS_MS`.
   Deleting that constant breaks the test's premise, and the assertion has to be
   re-expressed rather than deleted — it is protecting a real property (the glass edge must
   not beat against the plate's clock). With the silk's single `T = 40 s` it becomes "the
   glass's 142 s is coprime with the silk's 40 s".
7. **The 40 s loop and the existing clocks.** `T = 40 s` against the plate's `53 s / 79 s`
   and the glass's `142 s`. Three clocks that must not beat. The spec's loop is exact, so
   `40 s` is the fixed one and the *other* periods are the degrees of freedom — check the
   coprime relationships before wiring.
8. **NEW — the silk's §5.4 background versus the existing `folioField`.** The spec's post
   model *includes* its own background: a vertical `BG → BG + 2% COOL` gradient, a centre
   haze, and a vignette, **added before the tone map** (§5.3–5.4). Folio already has all
   three, as `FolioAtmosphere`'s field ramp, pools and vignette, drawn by `folioField`. Two
   backgrounds would double-count, and compositing the silk *after* the tone map would make
   §5.3's `1 − exp(−1.15c)` wrong because the background has to be inside `c`.

   **Recommended resolution:** the silk owns the field's uniforms rather than adding a
   second ground. `FolioAtmosphere` already exposes `fieldTop`, `fieldBottom`,
   `fieldSourceX/Y` and the pools; the silk pass reproduces the field from them *inside*
   `c = I + Bl + B_field`, tone-maps once, and replaces both `folioField` and the plate on
   that plane. That keeps one ground, keeps §5.3 exact, and keeps every palette's authored
   atmosphere intact — which the standing directive (§1) cares about more than anything
   else here. **This needs confirming before Phase 5**, because the alternative (silk
   draws its own `BG`, the field is dropped) would discard the atmosphere work for every
   theme that gets silk.

---

## 10. Decisions — resolved 2026-10-06

| # | decision | resolution |
|---|---|---|
| 1 | **Where the silk lives** | **App-wide ground plane**, taking over the plate's role and its machinery, with a new `silkIntensity` scalar: **1.0 on hero surfaces, ~0.30 on the ground plane, exactly 0.0 under glyphs** via the existing `silkWeightAt`. The scalar multiplies `I` in **linear space before bloom and tone map**, so §7's light budget is invariant to it. |
| 2 | **What the gel refracts** | The **analytic proxy** — the three centre-lines `L_j`, their widths `w_j` and the envelope `B_j`, published as uniforms. A 0.7 px strand mesh aliases, so it is a bad refraction source; a smooth centre-line field bends cleanly and costs almost nothing. Shared `SILK_FUNCTIONS` keeps gel and silk agreeing. |
| 3 | **Light faces** | **Dark-face-only**, gated categorically at the `isDark` layer, not per-face. Additive light cannot exist on paper and relaxing `relL(BG) < 0.01` collapses the §6 tonal ladder. Light faces keep their quiet ground. **A multiply-ink variant for light faces is a separate spec with its own model, not a tweak to this one** — this plan carries no hook for it. |
| 4 | **LOD policy** | **Derived from the rendered ribbon width.** `M = floor(spacingPx / 1.5)`, clamped to `[4, 56]` — the spec's 56 is the *ceiling*, not the value. `α ×= 56/m` so total radiance is constant and the budget cannot shift; `fwidth`-based coverage so strands fade rather than pop. |
| 5 | **The silk's clock** | **Its own**: `p = (elapsed / 40 s) mod 1`, no dependency on Folio's `rememberSlowPhases`. **Frozen at its current value when the ground plane is paused** — never reset, because a reset is a jump and §8.7 forbids any rigid motion. Driven at the house 10 Hz tick; the silk is slow (0.075 Hz at its fastest) so there is nothing to gain from a per-frame clock. |

### Still open — one item

**The silk's §5.4 background versus the existing `folioField`** (§9.8). The spec's post
model includes its own background, added before the tone map; Folio already has a field
ramp, pools and vignette. Two grounds would double-count, and compositing the silk after
the tone map breaks §5.3. Recommended: **the silk owns the field's uniforms** and
reproduces the atmosphere inside `c = I + Bl + B_field`, replacing both `folioField` and the
plate on that plane — one ground, §5.3 exact, and every palette's authored atmosphere
preserved. Confirm before Phase 5, because the alternative discards the atmosphere work for
every silk theme.
