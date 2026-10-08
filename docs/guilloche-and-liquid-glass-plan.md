# Plan — Theme Guilloche + True Liquid Glass

Two features, one careful pass. Both touch the same render chain, both must obey the
same invariants, so they are planned together.

Requested:

- **A. Animated guilloche.** A faint, flowing, per-theme background pattern that takes the
  theme's own gradient and lays it over the existing app, so the glass on top has something
  to show off.
- **B. Real Liquid Glass.** The current glass is a re-fake (a caustic body + a radial
  specular + an opt-in rim gel). It does not read like Apple's Liquid Glass. Make it real
  where the platform allows.

---

## 0. The ground truth (what already exists)

Verified from source, not assumed.

### The render chain (app root, `MainActivity.kt` ~501)

```
Box  .fillMaxSize()
     .onSizeChanged { folioFieldPx.value = it }
     .folioField(ambient = ambientShown)        // static: vertical ramp + 3 accent pools + daylight
     .folioAmbientShader(primary, tertiary)     // AGSL API33+: 2 blooms orbiting on a 42 s phase
     .folioLamp(folioLamp)                      // Canvas radial: per-book visible source
     { FolioNavShell { FolioNavHost } }
```

Everything is drawn **behind** all content. Screens (Home, Library, …) are drawn on top by
the nav shell and never recolour the ground — a screen that wants to light the room writes
into `LocalFolioAmbientTint` and the field reads it in its draw pass.

### What `folioField` actually draws (`FolioSurfaces.kt:564`)

1. `Brush.verticalGradient(colors.top → colors.bottom)` over the whole surface.
2. Three `Brush.radialGradient` pools from `DAYLIGHT_POOLS`, each `color.copy(alpha = poolAlphaAt(...))`,
   centre `poolCenterAt(...)` (drifts a few % on the slow clock), radius `pool.fr * w`.
3. A daylight wash `drawRect(dayTemp.copy(alpha = dayAlpha))` — skipped at night.

**The whole background is low-frequency.** That is the single most important fact for
feature B, and it is already documented in `GlassShader.android.kt` as the reason the gel
was switched off by default.

### The theme inputs available to a per-theme pattern

| Input | Where | Grain |
|---|---|---|
| `folColors.primary`, `tertiary` | `FolioColors` | per palette (36 faces) |
| `atmos.fieldTop`, `fieldBottom` | `FolioAtmosphere` | derived, contrast-pinned |
| `atmos.pools` (accentProgress / Discovery / Streak) | `atmos.pools` | 3 accents |
| `colors.poolColors` | `FolioFieldColors` | the field's own mixed pool colours |
| `FOLIO_LIGHTS[id]` | `FolioLight.kt` | **18 authored packs** — `sourceX`, `sourceY`, `lean`, `hueRange` |
| `daylight.azimuth`, `.elevation` | `FolioDaylight` | time of day, continuous |
| `palette.id` | `AppPalette` | stable per theme — the identity key |

So "unique per theme" has a real, authored identity to key on: **`FOLIO_LIGHTS` (18 entries)
+ `palette.id`**, and the geometry seed should be derived from the pack id, not from
`hashCode()` (unstable across runs).

### The constraints (non-negotiable, from the repo's own rules)

- **Rule 19** — every effect declares its floor and its fallback. Below the floor the
  modifier returns `this` **byte-for-byte**, and desktop/previews/tests render the
  pre-effect look exactly.
- **§12.3 / ink floor** — `INK_FLOOR_WITH_MARGIN = 11.0`; `LAMP_CONTRAST_FLOOR = 7.4f`. Any
  new paint over the field must be proven not to move a WCAG ratio off its floor. The
  field's own invariant is *"hue moves, luminance does not"* and *"the bottom only ever
  deepens."*
- **Slow clock** — `rememberSlowPhases(periodsMs)` ticks at `SLOW_MOTION_TICK_MS = 100L`
  (~10 Hz), read **in the draw phase only** (`withFrameNanos` once per tick). Per-frame
  composition is forbidden; the frame loop must idle between ticks.
- **Reduce motion** — `rememberMotionEnabled()`; a static, complete-looking value, never a
  frozen stale one.
- **Capability** — `GlassCapabilities(blur, noise, specular)` from
  `glassCapabilitiesFor(platformBlurSupported, lowRamDevice, liquidGlassEffects)`. AGSL is
  `Build.VERSION.SDK_INT >= TIRAMISU` (33). Blur (Haze) floor is API 32. Precedent: the
  lamp is deliberately **Canvas**, not AGSL, so it works to minSdk 24.
- **One shader seam** — Haze is imported only in `Glass.kt`. AGSL `RuntimeShader` is
  currently created in exactly two places (film + gel in `GlassShader.android.kt`, and the
  ambient in `AmbientShader.android.kt`).
- **Naming / colours** — every colour arrives from the palette; a shader must mirror the
  field's closed form (`fieldColors`, `poolAlphaAt`, `poolCenterAt`) so shader and Canvas
  cannot disagree. `chromaSpread` + `COVER_CHROMA_ENVELOPE = 1.15f` is the one definition of
  "too saturated"; a new layer reuses it, never re-derives it.

---

## Part A — Animated theme guilloche

### A1. What a guilloche is, chosen deliberately

A guilloche is a family of **parametric ornamental curves** — counter-rotating circles /
Lissajous figures / rose curves — historically engraved on banknotes and watch dials. The
key property that makes it right here: it is **pure line geometry with no fill**. A guilloche
adds *structure* at low frequency without adding *luminance mass*.

#### A1-R. Revised from the author's reference (authoritative)

The author supplied a reference frame. It overrides the earlier "concentric family" sketch
below, and the differences are specific and worth stating:

The reference shows a **sweeping filigree ribbon**, not a concentric field:

1. **Crossing sine waves, not radial rings.** Several long sinusoids, each with its own
   frequency, amplitude, phase and *orientation*, superposed so they cross and weave. Where
   two crests pass near each other the strokes bunch into a **bright ribbon**; where they
   cancel, a calm gap. That bunched-and-spread rhythm is the signature of the reference, and
   it is what `fract(r * LINES)` **cannot** produce — a radial family has even density by
   construction.
2. **A star-fan** — a radial burst of fine lines in one corner (top-right in the reference),
   like light through a fan or a starburst engraving.
3. **Long specular streaks** — a few nearly-straight diagonal lines crossing the curves,
   reading as light raking across the plate, not as more guilloche.
4. **Scale layering** — a wide, slow wave plus finer detail riding on it, so the pattern has
   depth rather than one uniform frequency.

Formulation (per fragment, normalised `uv`, aspect-corrected):

```
p = (uv - 0.5) * vec2(aspect, 1.0)

// Wave i: a line family running at angle a_i, modulated by a slow cross-wave.
// v_i = dot(p, dir(a_i)) ; m_i = dot(p, dir(a_i + PI/2))
// w_i = sin(v_i * F_i + m_i * C_i + phase_i)          // the weave
// The family is a thin stroke at each integer crossing of w_i, anisomorphic so a line
// is a line at any DPI.

float f = 0.0;
f += ribbon(w0, W0);   // wide slow wave  — the structure
f += ribbon(w1, W1);   // mid
f += ribbon(w2, W2);   // fine detail    — the filigree texture
f += streak(p, phase); // 2-3 long near-straight speculars
f += fan(p, centre, phase);  // the corner starburst
```

- **`F_i` / `C_i` per theme** — the wave frequencies and cross-modulation depths come from
  `FOLIO_LIGHTS` + a deterministic hash of `pack.id`, so every theme's *weave* is distinct
  (not merely denser or sparser: a different interference figure).
- **Angles `a_i`** — seeded from the pack's `sourceX/sourceY`, so the sweep runs *with* the
  theme's light. This is why the reference's curves feel lit rather than pasted.
- **Aspect correction is mandatory** — the reference's sweeps are long and horizontal-dominant;
  an uncorrected `uv` on a 1080×2392 phone would squash them into vertical stripes.
- **Anti-aliasing** — each `ribbon()` uses a `smoothstep` band whose width is scaled by a
  deterministic frequency guard (see A5). A raw threshold on a moving multi-wave sum is a
  strobe-and-moiré generator.

#### A1-kept: what survives from the first sketch

- **Pure line, no fill.** Unchanged and non-negotiable.
- **No focal centre.** The reference has a *fan in a corner*, which is a local accent — not a
  rosette medallion in the middle. The centre of the page stays free for content.
- **Colour sampled from the field's own ramp** (A3, below), so the lines are made of the
  theme's gradient. This is now more important, not less: the reference's lines are *cool and
  pale* because its theme is dark blue, and ours must be pale-in-*our*-theme, not a fixed tint.

> **Explicitly dropped from the first sketch:** the concentric `fract(r * LINES)` family and
> the "plate turns as a sheet" rotation. A radial family cannot make a ribbon, and rotating a
> radial family reads as a spinning disc, which the reference is not.



### A2. Where it goes in the chain

#### A2-requirement. **Evenly across the app, without extravagance** (author's constraint)

Stated by the author and binding on both parts:

> *"we need the agsl thing throughout the app evenly and not something too extravagant."*

Two requirements in one sentence, and they constrain the design in different ways:

**"Throughout the app evenly."** The plate is **not** a Home-screen feature or a flagship
flourish — it is the app's *ground*, so every screen stands on it (Home, Library, Stats,
Settings, Search, Quotes, Tags, documents, manga). That is satisfied **structurally** rather
than by per-screen opt-in: the plate is a modifier on the app-root ground plane
(`MainActivity.kt`'s single `Box`), exactly as `folioField` and `folioAmbientShader` already
are. A screen cannot be left out by forgetting to call it, and a new screen added tomorrow
gets it for free. The **only** surfaces that stand it down are the ones that publish a quiet
region (A5b) — the reader's paper and bare-field text — which is a legibility requirement, not
a scoping one.

**"Not too extravagant."** This is the restraint knob, and it is *already* the whole
discipline of the plan; the author's constraint makes it a hard requirement rather than a
preference:

- the plate's ceiling is **`GUILLOCHE_ALPHA_MAX` (0.12)** — a visible texture, still a full
  octave below the field's own pool ceiling (0.24);
- the **glass** is the restrained half: a small glint, a thin rim, **no** milky wash — "less
  area, more definition" (B1-R);
- **nothing** animates per frame — both layers walk the same ~10 Hz slow clock, so the app
  idles between ticks;
- **no** effect is ever allowed to be the thing you look at. If the plate or the glass becomes
  the most interesting object on the screen, the number to lower is `GUILLOCHE_ALPHA_MAX`, and
  the on-device check in A9 is what catches it.

**The test of "not extravagant" is therefore negative:** with the plate and the glass both
present, a reader should be able to describe the *book*, the *shelf*, the *statistic* — and
only afterwards, if asked, notice that the page had a texture. If the texture comes first, the
work is wrong.



**Between `folioField` and `folioAmbientShader`:**

```
.folioField(...)
.folioGuilloche(...)        // ← NEW: the engraving, in the theme's own gradient
.folioAmbientShader(...)    // the ambient blooms stay on top of the lines
.folioLamp(...)
```

Rationale, in order of importance:

1. **Under the ambient film and under the lamp.** The guilloche is the *plate*; ambient light
   and the lamp are light falling *on* the plate. Drawing lines over the lamp would put a
   crisp geometric edge on a light source that is deliberately limbless.
2. **Over `folioField`.** The field is the paint the lines are cut into; the guilloche tints
   itself from the field's own ramp (`fieldColorAtPoint`) so the lines are literally made of
   the theme's gradient rather than an imported colour.
3. **Under `folioLiquidGlass`.** This is the point of the whole feature: the glass surfaces
   already sit above the field, and now they have **high-frequency detail behind them to
   refract.** See Part B — this is also what un-breaks the gel.

### A3. How it is "unique for every theme"

Per theme, five independent knobs, from authored data plus a deterministic seed:

| Knob | Source | Effect |
|---|---|---|
| Wave frequencies `F_i` | `FOLIO_LIGHTS[pack.id].lean` + seed | the scale of the weave |
| Cross-modulation `C_i` | seed from `pack.id` | **the interference figure itself** — the main identity |
| Sweep angles `a_i` | `FOLIO_LIGHTS[pack.id].sourceX/sourceY` → atan2 | which way the ribbon runs (with the theme's light) |
| Fan position + tightness | `FOLIO_LIGHTS[pack.id].hueRange` + seed | where the corner burst sits |
| Colour | `flowColorAt(...)` / `fieldColorAtPoint(...)` at each fragment | the line *is* the theme's gradient |

Because the identity lives in the **frequencies and cross-modulations**, two themes do not
merely differ in density — they draw a *different interference figure*. That is what the
reference's "unique for every theme" actually requires, and it is why the seed is taken from
a deterministic hash of `pack.id` rather than a shared constant.

Two phases per theme so no two themes share a rhythm:

```
GUILLOCHE_PERIOD_MS = listOf(53_000L, 79_000L)   // both outside the ambient's 42s and the lamp's 61s
```

Palette identity → geometry is derived through a **deterministic string hash** of
`pack.id` (not `hashCode()`), so theme X's plate is identical on every device and every run —
and `ThemeSchemeTest` can pin it.

> Hue is not authored here. Following `FolioLight.kt`'s own precedent: *"hue is the theme's
> `primary`, not authored"* — the line colour is sampled from the field, so it inherits the
> correct hue for free and cannot drift from the page.

### A3-colour. Making the plate read on **both** faces — and the bug that hid in it

The author's constraint was *"the same vibrance for each theme light and dark"*. A single
`deepenG(field, amount)` — the obvious implementation — fails it: darkening a dark room is
generous, darkening paper is nearly invisible and spends the dark ink's headroom. So the
operation is chosen by **polarity**, and the two branches spend *different currencies*:

| Branch | Operation | What it spends |
|---|---|---|
| dark | `deepenG(field, a * 0.42)` | luminance — the ink is light, so deeper is further from it, free |
| light | `matchLuma(mixG(field, accent, a * 0.55), lum(field))` then `deepenG(…, a * 0.10)` | **chroma** first, luminance only a whisper |

The light branch's ordering is load-bearing and cannot be permuted: `mixG` brings the theme's
hue in, `matchLuma` **pins the luminance back** so the plate cannot move the contrast band, and
only then may a small `deepenG` make the line read as *drawn* rather than as a hue shimmer.
Skipping the pin is not a style choice — a bright `tertiary` over a mid-light field would
*lift* the line and erase the effect.

**The bug.** `GuillocheTest.plateIsVisibleOnBothFaces` kept failing with `worst = 0.0`, and it
was a real defect, not a bad test. On the darkest pool corners of `DARK` and `BLOSSOM` the field
reaches relative luminance ≈0.0066, where a 5% blend toward black moves the colour by **less
than one 8-bit step**. The plate was drawn and perfectly invisible at once. No depth fixes it —
swept to 0.70, still under a visible step — because the headroom is not in that direction.

The fix is a **headroom check** in the dark branch: cut downward when the cut registers, and
when it does not, cut *upward* — mix toward the accent (on dark, `primary`, a light colour),
which is the mirror of what the paper branch already does.

Two traps were hit on the way, and both are worth keeping:

1. **The headroom test has to be a *visibility* test, not an inequality.** The first attempt
   used `luminanceOf(deepened) != luminanceOf(field)`. In float arithmetic the deepen *does*
   move the value — by ~1e-5 — so the branch took the deepen path and still shipped an invisible
   line. The threshold is now `GUILLOCHE_MIN_VISIBLE_DELTA = 4e-4`, one shared constant so the
   model cannot decide a cut is worth drawing on a standard the test does not hold it to.
2. **`luminanceOf` is `Float`, `relativeLuminance` is the WCAG `Double`** — and they live in
   different files (`FolioAtmosphere.kt`, `FolioFieldModel.kt`). Quoting the constant in WCAG
   units while comparing the palette helper's `Float` would be a silent unit mismatch;
   `luminanceDelta` uses `relativeLuminance`, which is also what the test measures in.

Result across all 18 dark faces (measured, full 5×5 grid): worst dark delta **0.0 → 4.03e-4**,
and **ink contrast unchanged at 11.36** against a floor of 7.0 — because the ink is light and
the gap is huge, a line that moves *toward* it still cannot approach it. The paper face was
never the problem: it moves luminance ≈0.026, about 65× more, so the balance guard is simply
that paper is never the weaker face.

#### The shader is a second implementation, and it drifted

**A green test suite does not mean the plate is fixed.** The model has a Kotlin implementation
(what the tests exercise) and an **AGSL implementation** (what the device actually draws), and
the test only ever proves the first. After the headroom fix landed and all 23 tests passed, the
shader *still* had the old single-branch `deepen3(field, cover * darkDepth)` — so on-device the
plate would have remained invisible on `DARK` and `BLOSSOM` exactly as before. The fix has to be
written twice, and nothing in the suite notices a drift between the two.

Mirroring it surfaced two further traps, both caught by simulating the AGSL arithmetic in plain
Java rather than by reasoning about it:

1. **The two luminance metrics are not interchangeable.** The shader had a `luma3` gamma-space
   dot product; the model's `relativeLuminance` is WCAG-linearised. Measured on the deciding
   fields, a 5% deepen yields a **constant** `luma3` delta of 3.9e-3 for every grey from 10 to
   29, while the true WCAG delta moves from 3.0e-4 to 6.7e-4 across the same span — i.e.
   `luma3` calls "clearly visible" cases the model calls invisible, and the two **disagree at
   the decision boundary** (grey 15→16 is identical in `luma3` and crosses the threshold in
   WCAG). The shader now carries a `wcagLuma3` with the real sRGB linearisation.
2. **The headroom question must be asked at the same *depth* the model asks it.** The first
   attempt checked the cut at `darkDepth` (0.42). At that depth a near-black field *does*
   produce a visible step (5.1e-4 at grey 4, above the 4e-4 threshold), so the lift branch was
   **dead code** and the shader disagreed with the model on precisely the fields the fix
   exists for. The model quotes its constant at the plate's *working strength*
   (`GUILLOCHE_ALPHA_MAX × weight`), so the shader must evaluate
   `intensity * faceWeight * darkDepth` — the same product.

Verified on the real fields, through the shader's own arithmetic: `DARK` (luminance 0.0056)
cut = 3.71e-4 → **lift** → delta **5.83e-3** where it was 0.0; `BLOSSOM` → deepen → 5.75e-4;
`NEON_TOKYO` → deepen → 8.0e-4. Note `DARK`'s lift overshoots because `darkLift = 0.42` is a
large mix — a tuning question, not a correctness one, and it errs toward visible.

> **Lesson worth keeping:** when a visual effect has both a pure model and a shader, the model's
> tests prove the model. Any change to a colour or geometry rule must be mirrored into the AGSL
> *in the same commit*, and the mirroring should be checked by reproducing the shader's
> arithmetic numerically — every bug in this section was invisible to a green test run and
> found only by executing the maths.

---

### A4. The animation — the "flowing" and the "animated edge"

- Two slow phases, `53 s` and `79 s`, sampled through `rememberSlowPhases` at 10 Hz and read
  in the draw phase — **zero recompositions**, frame loop idles between ticks. This is the
  identical mechanism the ambient film already uses.
- `phase[0]` **advances each wave's own phase** at a slightly different rate per wave, so the
  superposition *drifts through its interference pattern*: ribbons widen, slide, and re-form.
  This is the reference's flowing quality and it is why the motion must be a *phase walk of the
  wave sum*, not a translation of the whole image. Translating the image would look like a
  scrolling wallpaper; walking the phases looks like the pattern is **alive**.
- `phase[1]` **breathes the amplitudes** of the wave layers fractionally against each other, so
  a ribbon swells and thins. Bounded to a few percent.
- The **streaks drift** on the same clock, at the lowest rate, so the "light raking across the
  plate" reads as slow-moving rather than static.
- All **continuous and periodic**, so the pattern never snaps or pops at the wrap.
- **Reduce motion** → both phases park at their `t=0` value; the plate renders as a static
  engraving that reads as complete (not frozen), exactly like the ambient film's rule.

> **The author's "animated edge"** applies to both layers: for the guilloche it is the drifting
> streaks and the fan's slow rotation; for the glass it is the moving specular rim (Part B,
> stage R3). The two share the clock, so the page has **one** motion tempo rather than two
> competing ones.

#### A4-R. What "continuous and periodic" actually costs (implemented, and binding)

The bullet above asks for motion that "never snaps or pops at the wrap". That is a real
constraint with a mechanical consequence, and the first implementation did not meet it —
every time term was written as if the clock were continuous. It is not: `slowPhaseAt` is a
**0..2π sawtooth** that ramps linearly and resets. A term built from it is only continuous
across that reset in one of two forms:

| Consumer | Condition | Where |
|---|---|---|
| `fract(...)` | advance a **whole number of periods** per lap — `fract(x + n) == fract(x)` | the weave's walk, the fan's turn |
| `sin`/`cos` | advance a **whole number of turns** per lap | the braid's pulse, the breath, the rake |

So the following are now *integers by contract*, pinned by `GuillocheTest`, and must stay so:

- **`GuillocheWave.drift`** — whole stroke periods per lap, signed. Magnitude is `i + 1` so
  the three layers travel at comparable *spatial* speeds despite frequencies spanning ~4×.
  Wave 1's sign is forced against wave 0's: three same-signed walks share a velocity, and a
  shared velocity **is** the translation this section forbids.
- **`GuillocheFan.rays`** — whole, because `atan2`'s branch cut jumps a full turn into the
  burst's `fract` argument. A fractional count draws a permanent seam out of the fan's centre
  even at rest.
- **`GuillocheFan.spin`** — whole *ray sectors* per lap, not radians, so a lap advances that
  same `fract` argument by an integer.
- **The breath harmonics** — the original `1.3×` / `0.8×` multipliers on `breath` land
  mid-cycle at 2π and snap back; they are now whole harmonics with constant offsets.

Two things were added to get the requested quality rather than mere correctness:

1. **The braid's phase travels** (`sign(drift) * phase`), not just the family's position. A
   walk alone slides a fixed figure sideways; advancing the braid makes the *wander itself*
   travel, so lines undulate where they stand. This is the difference between a pattern
   moving and a pattern being moved through, and it is where the drifting-veil quality lives.
2. **`GUILLOCHE_SHIMMER` (0.14)** — one very slow swell over the whole plate, driven by both
   clocks at once. Written as a pure *dimming*, bounded to `[1 − depth, 1]`, so the plate's
   strongest moment is still exactly `GUILLOCHE_ALPHA_MAX` and every contrast proof made at
   that ceiling still holds. A symmetric swell would exceed the ceiling and would have to be
   re-proved against the ink floor.

> **Why this is written down rather than left in the code.** Every one of these is a number
> that looks arbitrary and tunable, and three of them were originally written as plausible
> fractions. A later retune that "rounds 2 to 1.7 because it felt fast" reintroduces a visible
> jolt once a lap in an effect whose entire premise is not being noticed — and no screenshot,
> contrast test or build catches it.

### A5. Amplitude, contrast, banding

- **Alpha ceiling.** New constant `GUILLOCHE_ALPHA_MAX` — the reference's pattern is *legible*,
  so this is **higher than the 0.05f first sketched**: start at **0.10–0.14f** and tune against
  the reference (see open question 2). For calibration, the field's pool ceiling is
  `POOL_ALPHA_CEILING = 0.24f` and the daylight wash is `DAYLIGHT_WASH_ALPHA_MAX = 0.06f`, which
  `DaylightTest` already proves cannot move a WCAG ratio off its floor; at 0.12 the pattern is
  roughly twice the daylight wash, which is exactly the "visible texture, not a filter" register
  the reference occupies. **A5b's quiet regions are what make this number safe near text** — the
  higher alpha is affordable *because* text regions go to zero, not in spite of it.
- **Bandwidth, not just alpha.** The reference's pattern is legible partly because its lines are
  *thin and many* rather than few and heavy. Prefer raising the count and keeping the stroke
  narrow: a legible filigree at 0.12, not a coarse grid at 0.12.
- **Hue-only.** The line colour is `fieldColorAtPoint(p)` **deepened toward the field's own
  bottom colour** (not brightened) — i.e. the engraving is a *shadow* cut into the paint.
  This preserves the field's invariant *"the bottom only ever deepens"* and cannot lift a
  local luminance above the ink floor.
- **No fill, no glow.** Line only; no bloom, no wide soft edge. A wide soft edge is what turns
  a pattern into a haze and what dragged the old gel into "painted rectangle" territory.
- **Banding.** Primed line counts + the deterministic width guard + `smoothstep` AAA. Verified
  by `probe-screen.ps1 -ScanX/-ScanY` (banding) and `-Grid` (flatness) on the 1080×2392
  probe device, plus `VisualAuditTest` in model space across all 36 faces.

### A5b. Text that is **not** on a card — the ragging problem

This is the question that decides whether the feature is a success, and it is the one that
changes the design. Verified from source before answering.

#### What is actually protected, and what is exposed

| Surface | What is behind the text | Guilloche visible? |
|---|---|---|
| `FolioSectionCard` (all of Settings) | `.folioPanel()` → **opaque `colors.surface` fill** (+ border, clip) | **No** — fully protected |
| `FolioPanel` / `folioRaised` / `folioSunken` | levelled fills, opaque-ish | **No** |
| `FolioHeroCard`, Home hero | `.folioRaised(paneFill())` ≈ translucent pane | Partly — intentional, this is the point |
| `folioGlassBar` (chrome, nav capsule) | glass + Haze blur | Blurred, then refracted — desirable |
| **Sheet/masthead text, `FolioEyebrow` labels, empty states, `FolioFigure`** | **the raw `folioField`** | **YES — exposed** |

So Settings is safe. What is *not* protected is a real and specific list:

- section eyebrows and column labels (`FolioEyebrow` — 25 call sites, many on bare field);
- `FolioFigure` labels/captions on the Stats screen (the "THIS YEAR / Where your time went"
  headings sit directly on the field, per the screenshots);
- empty states and any prose between cards;
- the masthead's own text *before* the scroll collapses it into the glass bar.

#### Why a line pattern is worse behind text than the existing pools are

The field's pools are safe behind text because they are **low-frequency**. A glyph is a
high-frequency edge; a smooth luminance ramp under it does not touch the glyph's contrast.
A **line** is also high-frequency. Two high-frequency structures at different scales is
exactly the definition of **ragging**: a thin glyph stroke happens to overlay a thin pattern
stroke and loses its edge in one spot and not another, so the text looks smudged at the
baseline and crisp a word later. The eye reads that as "dirty", not as "patterned".

That is why the guilloche cannot simply be painted at the same alpha everywhere and the ink
floor check be trusted: **the ink floor asserts a *contrast ratio*, not *peacefulness*.**
Contrast can pass while the text still looks rough. The 0.05 alpha keeps the *ratio* honest;
it does nothing about the *ragging*.

#### The answer: a text-aware mask, not a flat alpha

The guilloche must **know where text is** and reduce itself there. Three candidate
mechanisms, in order of preference:

1. **Derived from the design system's own measure — chosen.** The design system already knows
   a screen's *content* is text-shaped: `folioBackdropSource()` is applied to the scroller of
   every content screen (`SettingsScreen.kt:226` proves it — the settings LazyColumn registers
   itself as a backdrop source). We introduce the mirror image,
   **`folioGuillocheQuiet()`**, which a screen applies to a region of bare-field text. It writes
   the region's bounds into a shared holder — exactly the pattern `LocalFolioAmbientTint` already
   uses for "a screen handing a colour *up* to the ground it stands on" — and the guilloche
   reads those bounds **in its draw pass** and eases to zero inside them with a soft margin.
   No new concept; it is the ambient-tint idiom applied to a rectangle.
   - Cost: draw-phase rectangle test per fragment region. Cheap and bounded.
   - Under reduce-motion / desktop it is a no-op like everything else.
2. **A density term in the shader.** Even without bounds, make the pattern's own amplitude
   depend on local contrast: where the field's neighbouring ramp is steeper, thin the lines.
   This is a *guard*, not a fix: it helps against the pools without knowing about glyphs.
   Keep it as a second-order damping, not the primary answer.
3. **Global reduction on text-heavy screens** (Settings, Stats, Reader). Blunt. It fixes the
   symptom by removing the feature exactly where the user is looking at a settings list — which
   is most of the app. Rejected unless 1 proves insufficient.

#### And the corollary: `folioGuillocheQuiet()` also fixes something I got wrong

The plan's original insertion point (A2) put the guilloche at the app root, over the *whole*
field. That is wrong for one specific reason: **the reader's paper is not the room.** The
reading surface must stay paper — a plate of engraved lines behind a paragraph is a
legibility defect no alpha floor makes acceptable. The root-level layer must therefore be
**suspended for the reader**, and the honest way to express "this screen is paper" is the same
mechanism: the reader applies a full-surface quiet region (`folioGuillocheQuiet(cover = 1f)`),
and the pattern stands down there entirely, not merely faintly.

That makes the mechanism carry three jobs with one definition: **text regions, the reader,
and any future surface that must be plain.**

#### Consequence for the tests

Two new assertions, both in model space (no Compose needed):

- `quietRegionProducesIdentity` — inside an authored quiet region the guilloche term is exactly
  `0f`, not merely small (so "quiet" cannot mean "still faintly ragged").
- `readerIsAlwaysQuiet` — the reader route is in the quiet set by construction, so a future
  refactor that drops the call fails a test rather than shipping lines under a paragraph.

Plus a device pass: `probe-screen.ps1 -Point` on a Stats eyebrow and a Settings header at the
same coordinates, with and without the pattern, comparing local contrast **and** looking at the
`-Record` frames for ragging the ratio test cannot see.

#### What this costs the plan

- **One new `expect/actual` modifier pair** (`folioGuillocheQuiet`, android = holder write,
  desktop = no-op) — small, and it reuses an existing idiom.
- **One new shared holder** alongside `LocalFolioAmbientTint`.
- **A2's insertion point is unchanged**; what changes is that the root layer is no longer
  unconditional — it is *masked* by whatever quiet regions are published. The default (nothing
  published) is still the full plate, because a screen that never registers text is the
  exception, not the rule.

### A6. Rule 19 contract table

| | Android API 33+ | Android < 33 | Desktop / tests / previews | Reduce motion | Preference off |
|---|---|---|---|---|---|
| Static engraved plate | – | – | – | – | – |
| Animated plate | ✔ (AGSL) | ✖ → byte-for-byte unchanged | ✖ → byte-for-byte unchanged | ✖ (parks static) | ✖ |
| Quiet regions (text / reader) | ✔ (mask) | n/a | n/a | ✔ (already zero) | n/a |
| Fallback | — | `folioField` + `folioAmbientShader` alone | `folioField` alone | static plate off | field alone |

**Explicitly not doing a Canvas fallback for the animated version.** The repo's own precedent
(the lamp) is Canvas for things the *majority* must see; the guilloche is a *bonus* layer on
top of a complete background, so the correct Rule 19 answer is *"the pre-existing look is the
fallback"*, not a second, cheaper implementation that could disagree with the first. This
avoids two sources of truth for the same pattern.

### A7. Files touched (Part A)

| File | Change |
|---|---|
| `theme/GuillocheShader.kt` | **new** — `expect fun Modifier.folioGuilloche(packId, fieldTop, fieldBottom, poolColors, intensity, enabled)` + the Rule 19 doc block |
| `androidMain/theme/GuillocheShader.android.kt` | **new** — `GUILLOCHE_AGSL`, `rememberSlowPhases(58/79 s)`, `RuntimeShader` guarded by `runCatching`, `drawWithCache { onDrawBehind { drawRect(brush) } }` |
| `desktopMain/theme/GuillocheShader.desktop.kt` | **new** — `= this` (byte-for-byte) |
| `theme/GuillocheModel.kt` | **new** — pure data: `GuillocheSpec(lines, ecc, baseAngle, period0, period1)`, `guillocheSpecFor(packId)` (deterministic hash), `GUILLOCHE_ALPHA_MAX`, `GUILLOCHE_QUIET_MARGIN`; unit-testable with no Compose |
| `theme/GuillocheQuiet.kt` | **new** — `expect fun Modifier.folioGuillocheQuiet(cover: Float = 1f)`, the shared bounds holder (the `LocalFolioAmbientTint` idiom), and the draw-phase region test. Writes on Android, no-op on desktop. |
| `settings/SettingsScreen.kt`, `statistics/*`, empty-state call sites | apply `folioGuillocheQuiet()` to bare-field text regions |
| reader screens | apply `folioGuillocheQuiet(cover = 1f)` — paper must stay paper |
| `MainActivity.kt` | one modifier inserted between `.folioField` and `.folioAmbientShader` |
| `desktopTest/…/GuillocheTest.kt` | **new** — see A8 |
| `docs/` + `FolioThemeRimTest`-style guards | rule 19 doc |

### A8. Tests (Part A)

Mirroring `AmbientLightTest` exactly:

- `identityUnderReduceMotion` — spec against a still clock ⇒ the plate is the static value,
  not stale, not animated.
- `desktopIsByteForByte` — the `actual` returns the receiver unchanged (the desktop file is
  the assertion of record; a `desktopTest` calls the real `actual`).
- `weaveIsDistinctPerPack` — every one of the 18 packs produces a distinct `(F_i, C_i, a_i)`
  tuple, so no two themes share an interference figure (the reference's real requirement: not
  merely denser or sparser, but a *different* figure).
- `specIsDeterministic` — same `packId` ⇒ identical spec on two separate calls and (by
  construction) on two "devices".
- `alphaCeilingCannotMoveTheInkFloor` — the `GUILLOCHE_ALPHA_MAX` marginal over
  `fieldColorAtPoint` across all 36 faces stays above `INK_FLOOR_WITH_MARGIN`. This is the
  §12.3 gate, expressed the way `DaylightTest` expresses it.
- `phasesDoNotCollide` — the guilloche periods are not equal to the ambient's or the lamp's,
  so the pattern cannot beat against them into a visible pulse.
- `quietRegionProducesIdentity` — inside an authored quiet region the guilloche term is exactly
  `0f` (see A5b), not merely small.
- `readerIsAlwaysQuiet` — the reader route is quiet by construction, so a refactor that drops
  the call fails a test instead of shipping lines under a paragraph.

### A9. Verification on device

1. `probe-screen.ps1 -Point -Grid -ScanX` on Home and Library, mid-grey and a saturated pack
   (e.g. hyperpop), to prove: no banding, no shifted ink contrast.
2. `probe-screen.ps1 -Record 5` to eyeball the flow rate — the pass is *"visible drift, not
   motion"*.
3. Repeat at night (`daylight.isNight`) and at noon, since the field's own ramp changes and
   the lines tint from it.

---

## Part B — True Liquid Glass

### B1. Why the current glass is not Liquid Glass

Apple's Liquid Glass (WWDC 2025) is defined by four things. The current implementation has
one of them, weakly:

| Apple's cue | What Apple does | What Folio does now | Verdict |
|---|---|---|---|
| **1. Backdrop refraction with *dispersion*** | The element bends the scene behind it; colours separate slightly at the rim | `LIQUID_GEL_AGSL` does three displaced taps — **but it is opt-in, default off** | Implemented, **disabled** |
| **2. Real blur of a *detailed* backdrop** | Strong `backdrop-filter` over content with structure | Haze is wired (`caps.blur`, API 32+) but the field is a **smooth ramp**; nothing to blur | Blur available, **nothing to blur** |
| **3. Specular highlight that tracks light + motion** | Bright rim catch aligned to a *real* light; responds to tilt/scroll | `LIQUID_GLASS_AGSL` has a caustic body + radial specular on a virtual sun; **no time term, no motion** | Static, approximate |
| **4. Rim bevel / thickness + a content-aware adaptive tint** | A visible lens edge, tint pulled from the content beneath | Rim drawn by `folioThemeRim` + `folioRaised` (shape-aware); tint from `tameCover` (approximation) | Partially present |

The code itself already knows the root cause, verbatim:

> *"On a smooth low-frequency backdrop — the hero's field is a vertical ramp plus three soft
> pools and one lamp bloom — a lens rim has almost nothing to bend: the dispersion that is
> meant to separate a lens from a gradient lands three samples on the same colour, so the
> band only ever reads as a painted rectangle…"*

**This is the crucial finding: Part A is the enabling work for Part B.** The guilloche gives
the backdrop the high-frequency detail that refraction needs to be visible. Everything below
is gated on Part A landing first.

#### B1-R. The target, read off the author's reference

The reference frame defines the destination better than any description, and it is *quieter*
than a literal Liquid Glass reading. What it actually shows on the hero card:

| Observed in the reference | Reading | What we implement |
|---|---|---|
| A soft luminous line tracing the card's top and both sides, brightest at the corners | A **rim catch on a thick pane** — the edge of glass catching light | SDF edge term on the **film** (stage R2) — the film currently has *no* edge term at all |
| Interior barely tinted; the pattern clearly readable through it | Glass is **transparent first, coloured second** | Lower the film's body weight; let the guilloche read through |
| A small four-point **star glint** near the top-right corner | A **specular sparkle**, small and hard | A tight, high-frequency specular — not the current wide smooth radial |
| Faint **diagonal light rays** sweeping across the card face | The sheen has **direction and grain**, not a soft blob | Directional rays in the film's caustic term, aligned to `light` |
| The "Continue" capsule carries its own thin edge light | Glassness is **per-surface and consistent**, not just the flagship | The edge term lives in `folioLiquidGlass`, so every glass surface inherits it |

**The author's three constraints, translated:**

1. **"A bit less flashy"** — the reference's glass is quieter than Apple's marketing renders.
   Concretely: *smaller* specular (a glint, not a bloom), *lower* body weight, and **no** wide
   milky wash. The film's current `body * 0.06` / `spec * 0.11` weights are roughly the right
   register; the change is **shape** (tight glint + thin rim) rather than **strength**. Do not
   read "less flashy" as "lower alpha everywhere" — read it as "less area, more definition".
2. **"More with its own colour"** — the reference reads pale-blue because *its theme is* dark
   blue. Ours must read in **each theme's own colour**: the rim, glint and rays take their hue
   from the palette's `primary`/`tertiary` (which is what `accent` already is at the call sites)
   rather than a fixed white. This is the same discipline as the guilloche's A3 rule, and it is
   the single biggest difference between "Apple's glass pasted on" and "our glass".
   - Guard: the **existing `rimChromaCeiling`** still applies — more of our own colour must not
     mean a frame louder than the pane it edges. That ceiling stays untouched.
3. **"The animated edge"** — the rim and glint **move**. The rim's brightest point should
   travel slowly around the silhouette, and the glint should drift a little, on the same
   10 Hz slow clock the guilloche uses (stage R3). This is what makes it read as glass in a
   room rather than a rendered picture of glass.

> **Where this differs from my earlier stage list:** I had ranked "SDF edge on the film" (B3.1)
> as the *third* stage. The reference makes it the **first** — the rim catch is the most
> prominent signal in the image, and it is precisely the piece the film lacks. Stage order is
> revised below.

### B2. The plan, in four stages

#### Stage R1 — Turn refraction on where it can actually be seen — **partly implemented**

- Keep `refractBackdrop` opt-in at the API level (it must stay correct for a smooth backdrop),
  but **raise the default at the two *detail* call sites** — the Home hero and
  `FolioHeroCard` — where a guilloche is now beneath. `Glass.kt` stays the only Haze seam;
  no new dependency.
- Fix the gel's known geometry approximations that the code already documents:
  - ~~the **§13.9 hero collapse** puts a `graphicsLayer` scale + 12 dp translation on the node
    which `positionInRoot` does not follow, so the refraction rides up during collapse — take
    the `graphicsLayer` transform into account (read the layer's transform in the draw pass)
    so the band lands true throughout the collapse, not only at rest;~~
    **Not a defect — the premise was false, and no code was needed.** `positionInRoot()` is
    defined as `localToRoot(Offset.Zero)`, and `localToRoot` walks the coordinator chain calling
    `toParentPosition`, which is `layer?.mapOffset(position, inverse = false)` *plus* the layout
    position. It is the composed transform — every `graphicsLayer` between the node and the
    root, scale and translation included. So the gel tracked the collapse from the day it was
    written. **The lesson is worth more than the fix:** this was a plausible-sounding
    approximation recorded in a comment and then inherited by the plan, and it survived several
    sessions because nobody re-derived it. When a comment states a *mechanism* ("position does
    not follow the layer"), it is cheap to check that mechanism in the framework source and
    expensive to keep designing around it. It was corrected in place rather than silently
    dropped, so the next reader does not re-invent it.
  - the **ambient bloom film and the new guilloche are now in the model** — the gel's
    `fieldAt()` must sample them too, or it bends a room that is missing its own plate.
    **Done for the plate** — see the "plate is now in the gel's field model" note below. The
    ambient film is deliberately left out; it is a sub-1% low-frequency wash with no figure,
    so there is nothing at the rim for a lens to bend.

**As built (so far).** The film is now on **four** surfaces, not two: the Home hero, the
generic `FolioHeroCard`, the **masthead** (`FolioTopBar`), and **`folioVeil`** — which is the
large win, because `folioVeil` is the app's card/panel/sheet material and carries **15 call
sites** across settings, quotes, documents, the library shell and the reader's own panels.

`refractBackdrop = true` is set at the hero, at `FolioHeroCard`, and on `folioVeil` — but
**conditionally**, and the condition is the interesting part. `folioVeil` is used heavily
*inside the reader* (`ReaderChrome`, `ReaderEchoesPanel`, `ReaderPanels`,
`ReaderSettingsPanel`), and the plate deliberately stands down on a reading route ("paper
stays paper", gated structurally at the app root). A gel over the reader's smooth page is
exactly the "painted rectangle" failure the default-off flag existed to avoid — so telling
`folioVeil` to always refract would have re-introduced the bug on 7 of its 15 call sites.

Rather than duplicate the reading-route test (which a screen cannot evaluate locally), the
root now publishes **`LocalFolioGuillocheActive`**, computed from the *same expression* as the
plate's own `enabled` (currently `glassCapabilities.specular && !isReadingScreen`, line-for-line
in `MainActivity`). Surfaces ask whether the engraving is behind them; the gel follows the
plate exactly, wherever it is and whatever the reader does with routing. `false` by default, so
previews, tests and any surface outside the app root answer honestly and get no gel.

Not yet done in this stage: the **geometry approximation** below (the hero-collapse transform),
and the masthead's gel is deliberately left off — the bar's backdrop is the page scrolling
beneath it, which the Haze pass already reproduces, and the film's job at the crown is the
specular, not a second lens.

**The plate is now in the gel's field model (done).** `fieldAt()` used to stop at the lamp — a
vertical ramp, three drifting pools and one bloom: everything the *field* paints and nothing the
*plate* engraves over it. That was defensible while the plate was absent or near-invisible. With
a visible engraving it was not, and the failure is the kind that only ever shows on a device: a
rim that crosses a bright ribbon and returns the un-engraved field has silently replaced the
detail with a likeness of it.

The fix is structural rather than a second copy of the arithmetic. `GuillocheShader.android.kt`
now factors the plate into three shared pieces, and `GlassShader.android.kt` interpolates them
into the gel's own AGSL source:

| Shared piece | What it is | Why it is shared |
| --- | --- | --- |
| `GUILLOCHE_UNIFORMS` | the plate's uniform **block** (waves, fan, the five colour-rule constants, the quiet mask) | a second spelling of `crossFreq1` is a second thing a retune can leave disagreeing |
| `GUILLOCHE_FUNCTIONS` | every pure helper — `waveLine`, `fanBurst`, `streaks`, `quietWeight`, `regionWeight`, `matchLuma3`, `wcagLuma3`, and **`plateAt`**, the extracted body of the plate's old `main` | `plateAt` *is* the engraving; two copies would be two plates |
| `applyGuillocheUniforms` / `applyQuietUniforms` | the **setter** side, shared by both shaders | a uniform cannot be added to the shader and forgotten by the Kotlin that feeds it |

`plateAt` had to become a function with explicit parameters rather than reading globals, and the
three that mattered are worth naming because each is a real trap:

- **`accent`** — the gel already declares an `accent` of its own, the *pane's* material. Reading
  the global would have engraved the refraction in the pane's colour instead of the plate's.
- **`aspect`** — the plate is specified in the *field's* fractions; the pane is a different box.
  A pane-ratio would shear the engraving the rim bends away from the one the page painted.
- **`plateIntensity`** — the sharpest of the three. *Both* shaders declare an `intensity`, and
  they mean different things: the plate is drawn at `GUILLOCHE_ALPHA_MAX`, the gel's is its own
  film strength. Reading the gel's would have scaled the refracted engraving by the wrong
  material's opacity.

The gel's `plateStrength` is the gel's copy of the plate's own `enabled`, computed from the same
`LocalFolioGuillocheActive`, and at `0` the plate branch in `fieldAt` is never entered — so a
surface with no engraving behind it is the pre-plate shader, byte-for-byte. The quiet mask
travels with it, from the same uniforms: a region that silences the engraving has to silence the
refraction too, or the pane pokes a ribbon back into the reader's clean page.

Two design calls went the other way, deliberately:

- **The chroma gate stays over the plate.** The plate rides `fieldAt`, so by the time the bent
  room reaches the gate the engraving is part of it and cannot be separated again without a
  fourth, unengraved tap. Gating it costs only chroma the plate barely has (its colour is a
  deepen of the field on a dark face, a luminance-pinned tint on paper) while the term the gate
  exists for — a saturated room pool arriving at its own chroma, "the colour is outside the inner
  rectangle" — is suppressed exactly as before. Adding a bypass would be re-opening the
  A3-colour bug to fix a difference no eye can find.
- **The film is still not in the model.** The ambient bloom is documented at well under 1%, and
  unlike the plate it is a low-frequency wash with no figure: nothing a lens rim can visibly
  bend. It stays out, and the code says so.

Worth recording as a process note: this change produced a **duplicate-helper bug in the plate's
own file** — an `Edit` inserted `plateAt`'s closing delimiter in the wrong place, so
`GUILLOCHE_FUNCTIONS` swallowed the rest of the file and the old inline helpers + `main` lived on
as orphaned AGSL. The Kotlin compiler caught it immediately (a string that never closes is a
syntax error at the next top-level declaration), which is the one mercy of the duplication: it is
loud in `compileKotlinAndroid` and silent nowhere else. The check that would have caught it
earlier is one line — `grep -cE "^(float|float3|float4|half4) <name>\(" ` for each shared symbol
should print `1`.

**Second parameterisation leak, caught by the last grep before the build.** The same trap the three
named above describe struck `plateAt`'s **body**, not just its signature: two live lines still read
the bare global `intensity` after the parameter was renamed. One was cosmetic
(`workingDepth`, line 385, which only asks a headroom question), but the other was the **return
line** — `float4(line, clamp(cover * intensity * faceWeight, 0, 1))`. In the plate itself that
global *is* `GUILLOCHE_ALPHA_MAX` and the line was correct; interpolated into the gel it becomes the
gel's own film strength, so the refracted engraving would have been scaled by the wrong material's
opacity — exactly the failure the parameter documents. **Neither reference is an error in either
compilation unit**, because `intensity` is declared in both. This is the whole hazard of string
interpolation wearing a friendly face: the shared snippet is compiled in two worlds where the same
identifier means two things, and only a human reading the body catches it. The build was green
before the fix and would have stayed green after; the pixels would simply have been wrong. Fixed by
renaming both reads to `plateIntensity`.

**Does the refracted plate actually show? (measured, and the answer changes Stage R4.)** Two probes
(`.dbg/probe/Probe16.java`, `Probe17.java`) modelled the gel's rim composite to ask whether the
engraving survives the trip. Findings:

- **The chroma gate is innocent.** `d_before == d_after` *exactly* on every sample — a neutral plate
  contribution has little spread, so the gate's `k` is ≈1 and it neither helps nor hurts. The
  deliberate "keep the gate over the plate" call above costs nothing measurable.
- **The plate's refracted contribution is below the 8-bit floor almost everywhere.** On dark faces it
  adds **0.00–0.08 of an 8-bit step** at 11:1 ink contrast — it rounds to nothing. On paper it only
  reaches ~4.6 steps at a full `cover = 0.75` ribbon. Worst post-gate WCAG delta: **3.704e-06**
  (0.00 steps).
- **The gel's own rim terms dominate, and that is the real reason.** A *full-coverage* ribbon tap
  carries **181.8%** of `bandBase` — but `bandBase` is the gel's *smallest* own rim term
  (`rim * 0.12`, alpha-spent), and a *typical* tap carries only a few percent of it. So the rim
  catch the gel already had is what the eye sees; the refracted engraving rides underneath it.

**Conclusion, and it is a decision the user should see.** The field-model change is *correct* — the
gel should not bend a room missing its own plate, and now it does not — but it is **visually near-
moot**. Faithfully refracting the plate's *absolute alpha* was the wrong lever: an engraving authored
at `GUILLOCHE_ALPHA_MAX = 0.12` as a background texture has no amplitude left to bend once it is
behind a pane. If the intent is that the refraction should be *seen* (the standing "flaunted evenly
but not extravagant" instruction), the lever is **the plate's contrast inside the gel**, not its
absolute level — e.g. refracting `plate.a` with a gain, or bending the plate's *line* rather than
its composite. That is a Stage R4 question, and it is cheaper to answer on-device than in a probe
because the probe can only measure mean luminance, not the transient the eye actually tracks across
an animated ribbon. Recording it here so R4 does not re-tune dispersion and the tint while the term
it is tuning is invisible.

**The lever, taken (R4, first cut).** The user chose "do the refraction-gain lever" over "build an
APK first", so the term is now amplified rather than merely present. `GLASS_PLATE_REFRACT_GAIN`
(=`1.5f`) multiplies the plate's alpha **inside the gel only**:

```glsl
float a = clamp(plate.a * q * plateStrength * plateGain, 0.0, 1.0);
```

Three properties make this the right shape of fix, and each was a choice:

- **Refraction-only.** The gain is applied at the gel's composite, never to the plate the page
  draws. The engraving on screen is *unchanged*; only the pane's bend of it is lifted. A gain that
  reached the plate would fight the whole "less flashy, its own colour" brief.
- **Fed as a uniform, not written in AGSL.** One number, `1.5f`, in Kotlin beside the measurement
  that justified it. R4 can retune it — or set it to `1.0` to restore the faithful composite — with
  no shader edit. This is the same discipline as the shared setters: the constant lives where the
  reasoning lives.
- **Sub-linear in effect.** A sub-1% rim tap stays sub-1%; a dense ribbon is what crosses the 8-bit
  floor. So the visible result is *engraving present where the plate is dense, absent where it is
  faint* — which is the "flaunted evenly but not extravagant" brief expressed as arithmetic rather
  than as a taste call.

Honest status: `1.5f` is a **reasoned starting value, not a measured optimum.** At 0.08 steps the
dark-face full-ribbon tap needs ~12× to cross the floor *uniformly*, but a uniform 12× would also
lift faint taps into visible noise, which is the extravagant failure. The gain is therefore chosen
to bend the *distribution* rather than the floor, and only a device can say whether 1.5 is the knee.
The value is isolated so that answer costs one integer, not a build.

**The measurement came back, and it overturns the guess.** `Probe18` swept the gain 1→12 across
three themes, both sides of the two-sided question (sparse corners must stay invisible, dense
ribbons must read), after the chroma gate. `.dbg/probe/Probe18.java`, reproduced in the table below
in 8-bit steps:

| gain | DARK 45% | DARK 75% | PAPER 45% | PAPER 75% | sparse corners |
|---|---|---|---|---|---|
| 1.0 (faithful) | 0.03 | 0.08 | 1.66 | 4.60 | 0.00 |
| **1.5 (shipped guess)** | **0.04** | **0.12** | **2.49** | **6.87** | **0.00** |
| 4.0 | 0.12 | 0.32 | 6.60 | 18.70 | 0.00 |
| 12.0 | 0.34 | 0.80 | 20.05 | 39.26 | 0.00–0.04 |

Two findings, and the second is the important one:

- **`1.5` is not the knee — it is barely off the floor.** It moves a dark-face ribbon from 0.08 to
  0.12 steps. The gain is *linear in alpha but sub-linear in effect*, because the plate's line
  colour is a **deepen of the field**, so at low coverage the plate approaches the field's own
  colour and the composite moves almost nothing regardless of gain. Even **12×** only reaches 0.80
  steps on a dark 75% ribbon.
- **On a dark face no gain in the sweep makes the plate visible at all.** The plate's *line* is
  dark-on-dark — it is a shadow cut into a dark room. Amplifying the alpha of a near-black line over
  a near-black field cannot produce a visible step, however large the multiplier. `PAPER` clears the
  floor at **gain 1.0 already** (1.66 steps at 45%, 4.60 at 75%) and is clearly visible at 1.5 —
  so the refraction *already worked on light*, and the gain is only being spent on the dark themes
  that structurally cannot respond to it.

**The design consequence, which is the real R4 finding.** The lever is not the alpha gain at all —
it is **the plate's contrast**, exactly as the earlier note predicted, and the failure is specific:
on dark faces the plate is authored as a *deepen* (`GUILLOCHE_DARK_DEPTH`), and a deepen of an
almost-black field has nowhere to go. The model *already knows this* — `plateAt`'s lift branch
exists for precisely these fields (`if (fullCut < minVisibleDelta) line = mix3(field, accent, …)`)
— but it fires on the **cut can't register** test at the plate's working strength, which is a
*local* headroom question, not "the rim will be invisible". So on dark faces the plate should be
**lifted toward the accent inside the gel** rather than deepened: a lens bends the engraving into
its own bright line, which is also what the reference's rim does.

**The dark-face lift — implemented, and it works.** The user chose to take the recommendation, so
the dark branch's refraction now goes through `mix3(line, accent, cover * plateLift)`. `Probe19`
(`.dbg/probe/Probe19.java`) swept the lift with the gain fixed at 1.5:

| lift | DARK 45% | DARK 75% | DARK 90% | corners | BLOSSOM 75% |
|---|---|---|---|---|---|
| 0.00 (gain only) | 0.04 | 0.12 | 0.17 | 0.00 | 0.12 |
| 0.55 (first guess) | 0.57 | 1.84 | 2.91 | ≤0.02 | 1.54 |
| **0.70 (shipped)** | **0.76** | **2.52** | **4.06** | **≤0.02** | **2.10** |
| 0.90 | 1.02 | 3.54 | 5.83 | ≤0.03 | 2.92 |

The lift moves a dark 75% ribbon from **0.12 → 2.52 steps**, a ~21× gain in visible signal, and it is
a clean valley: sparse corners stay at 0.00–0.03 all the way to `0.90`, so the lift cannot produce
speckle. That is the "flaunted evenly but not extravagant" brief as a number — visible where the
engraving is dense, silent where it is sparse.

**`0.55` was the first guess and it was wrong, in an instructive way.** `0.55` is the paper branch's
`lightTint`, so matching it looked like "the same vibrance for each theme light and dark" achieved by
construction. `Probe19`+analysis show it is not: at `0.55` dark reaches 1.84 steps against paper's
6.87 (27%), and **even at 0.90 dark reaches only ~52% of paper's signal**. The gap is *structural*,
not a tuning miss:

- **paper** draws a *colour* line on a light field, luminance-pinned (`matchLuma3`), so a small
  shift is a large *relative* change and the chroma gate passes it;
- **dark** draws a lift toward a light accent on a dark field — a small *absolute* rise, which the
  gate partly re-neutralises.

So "same vibrance" cannot mean "same number" here. Copying `0.55` would have shipped a dark theme at
27% of the intended signal *with a comment claiming symmetry* — the most dangerous kind of wrong,
because the justification is plausible and the number is consistent. The honest target is **each
polarity visible in its own right**, which is what `0.70` delivers.

*Three* implementation details worth keeping, because each was a trap:

- **`plate.rgb` is not premultiplied.** `plateAt` returns `float4(line, coverage)`; the plate's own
  `main` premultiplies it as `min(plate.rgb, 1.0) * a`. The gel's plate composite previously read
  `plate.rgb / max(a, 1e-5)` — **dividing an un-premultiplied colour by its own coverage**, which
  over-brightens the line by the inverse of its alpha. That was a real pre-existing bug, masked by
  the term being sub-quantisation; fixing the term's visibility is what made it matter. Now
  `mix(c, min(plate.rgb, 1.0), a)`, which is the same arithmetic the plate's `main` performs.
- **The lift tracks coverage, not a floor.** `cover` is *recovered* from `plate.a` by dividing out
  `plateIntensityRef * plateFaceWeightRef` — the two factors `plateAt` bakes into its alpha — rather
  than growing `plateAt` a second signature. Two extra uniforms buy the recovery and keep the shared
  plate function untouched.
- **`plateDark` is fed from the same `backdrop.atmos.isDark` the plate itself is built with**, so the
  gel cannot take a different polarity branch than the engraving it is bending. Paper is bit-for-bit
  unchanged (`plateLift` is set to `0` on a light face, so the branch is skipped entirely).

Note that `PAPER` visible at gain 1.0 is still a constraint on the tint work: on light the refraction
is already legible, so R4's dispersion step has real chroma to work with there and less on dark.

#### Stage R2 — **The rim catch and the glint** (first, per the reference) — **implemented**

The reference's most prominent signal is the luminous rim tracing the card's silhouette. The
film has **no edge term at all** — the code says the old box-metric edge was deliberately
removed because it painted an inset rectangle. So this is the first thing to build, not the
third.

- Give the **film** the same rounded-rect **SDF** the gel already computes, with per-corner
  radii (`radii`) — the `Outline` already knows all four corners, so an asymmetric hero
  silhouette gets an asymmetric rim. This is the chebyshev → SDF switch, and it is the fix the
  code's own comment already prescribes (*"Reintroducing an edge means giving the film the
  silhouette (an SDF like the gel's), not a box"*).
- The rim is **thin and bright, not wide and soft**: a tight `smoothstep` on the SDF near zero,
  so it is a *line of light on an edge*, which is what the reference shows.
- **The glint**: replace the current wide smooth radial specular with a **small, tight
  four-point star** — the reference's sparkle is small and hard, at a corner-ish position
  (`spot = 0.5 - light * 0.32` stays, but sharpened and shrunk).
- **The rays**: give the caustic body **direction** — two or three faint diagonal streaks
  aligned to the `light` vector, instead of the current isotropic crossing waves. This is the
  "grain" the reference's card face has.
- **All of it takes the theme's own colour** (`accent` / `highlight` as passed), reined by the
  existing `rimChromaCeiling`.

**As built.** The rim is a rounded-rect SDF with per-corner radii pushed from
`shape.createOutline(size, layoutDirection, this)` inside `drawWithCache` — so a hero that bleeds
square on one edge and rounds on the other gets the asymmetry for free, and the four-radius
uniforms are allocated once per size rather than per frame. `RIM_BAND = 0.16` with
`pow(rim, 2.6)` gives a *line of light on an edge* rather than a soft inset. The glint is a
four-point star (`GLINT_RADIUS = 0.11`: a tight core plus two crossed needles) and the sheen is
two streaks aligned to `light`, giving the face grain. Weights keep the split the doc below
records: definition is small-area/high-value (`rimW` 0.20, `glintW` 0.30), material is
large-area/low-value (`bodyW` 0.045, `rayW` 0.030). Nothing here enlarges any term relative to
the pre-R2 film; the change is *where* the light lands.

#### Stage R3 — Make it *time-aware* (the animated edge) — **implemented**

What landed, and the two decisions the stage forced:

- **One new period, not a reuse.** `GLASS_TRAVEL_PERIOD_MS = listOf(110_000L)` lives beside
  `GUILLOCHE_PERIODS_MS`, so the four independent periods on a page are 42 s (ambient orbit),
  53 s / 79 s (the plate) and 110 s (the edge). Reusing a guilloche period — as this plan first
  suggested — would have made the two layers *phase-lock*, and a beat between the plate and the
  rim is exactly the "extravagant" failure the brief rules out. Four periods sharing no small
  integer ratio cannot beat.
- **The redistribution normalisation is the whole trick.** `rimTravel = exp(3.2*(cos(θ−p)−1))`
  is normalised so its mean over a lap is exactly **1** — the von Mises kernel — so the rim term
  can only ever *move* its budget around the silhouette, never add to it. The glint's life
  `0.35 + 0.65*travel01(...)` is bounded in `[0, 1]`, so it can dim and return but never brighten
  past its own maximum. The ray slide is a **phase offset inside the sine's argument**, which
  slides the fringes without raising their amplitude. Every one of these was chosen so that
  "amplitude unchanged" holds *at every instant*, not merely on average — an average-preserving
  term that spikes at one moment would still break A5b's bound behind text.
- `exp()` of an *angle difference* rather than a compare against `atan` is deliberate: it has no
  seam at the top of the lap, which a piecewise window would leave as a visible flick.
- The glint drifts **perpendicular to the light**, not along it: a spot sliding along the light
  would read as the sun moving, contradicting every other material in the app that shares that
  vector.
- The phase is read in `onDrawBehind`, **never** folded into the `drawWithCache` identity — the
  same rule and the same reason as `ambientShaderLayer`. Reduce-motion parks at
  `GLASS_TRAVEL_PARKED_RAD = 2π`, one complete lap, not at 0.
- The AGSL reads `phase` through an `_alphaCheck`-style barrier, so SkSL's dead-code elimination
  cannot strip a uniform that only ever *modulates* other terms and silently freeze the edge
  (the `highlightAlpha` trap this file already records once).

#### Stage R4 — Close the remaining fidelity gaps

Ranked by value-per-risk; each is independently shippable and independently revertible:

1. **Chromatic dispersion that is *visible but restrained*.** Already reined by
   `rimChromaCeiling` (from `chromaSpread(raisedFill) * COVER_CHROMA_ENVELOPE`). With a
   guilloche behind, the three taps finally land on different colours, so the existing term
   starts working. Possibly widen the lens depth slightly (`lens = 15.0 * rim`) once it is
   legible; do not touch the ceiling.
   - **Do not start here.** `GLASS_PLATE_REFRACT_GAIN` (above) is the first cut at making the
     engraving reach the rim at all; tuning dispersion on top of a term that was measuring at
     0.00 steps is tuning an invisible variable. Settle the gain first — ideally on a device,
     where the animated ribbon can actually be watched — then dispersion, then the tint.
     Also worth remembering that the plate is **mostly neutral** (a deepen on dark, a
     luminance-pinned tint on paper), so dispersion has little chroma to separate until the
     tint work lands. That ordering is not a preference; it is what the measurement implies.
2. **Adaptive tint from content beneath, but *in the theme's own colour*.** Replace the
   `tameCover` approximation at the hero with a **sampled** tint: with a guilloche + field
   closed form available in the model, sample `fieldColorAtPoint` under the card and pull
   `tint` toward it by a small, ceilinged share — the same discipline `tameCover` uses (hue
   direction kept, only intensity moves, `min(1, …)`).
   - This is the concrete mechanism for the author's **"more with its own colour"**: the glass
     takes its hue from the theme's `primary`/`tertiary` and the room beneath, never a fixed
     white. The ceiling keeps it honest.
3. **Explicitly out of scope (state it, don't silently skip):** real device tilt/gyro parallax
   (`SensorManager`) and Apple's exact multi-pass gaussian stack are **not** planned. Tilt is a
   new permission-adjacent behaviour and a data source the app has no seam for; the blur ladder
   is already capped at API 32 and is the one seam (`Haze`) the repo allows. Say this in the
   doc rather than implying parity.

### B6. Rule 19 contract table (Part B)

| | API 33+, pref on, backdrop published | API 33+ but smooth/absent backdrop | API 32 blur available | < API 33 | Desktop/tests | Reduce motion |
|---|---|---|---|---|---|---|
| Film (static + time-aware specular) | ✔ | ✔ | ✔ (film is AGSL: needs 33) | ✖ → mesh+sheen | ✖ → mesh+sheen | ✔ static, phase parked |
| Gel (refraction) | ✔ where `refractBackdrop` **and** backdrop **and** blur | ✖ → film only (unchanged behaviour) | ✖ | ✖ | ✖ | ✔ static |
| Blur (Haze) | ✔ | ✔ | ✔ (API 32) | ✖ | ✖ | ✔ |
| Fallback | — | film alone | film alone | `§13.4 mesh + raised sheen` | same | same |

The **fallback stays exactly what it is today**. Nothing in Part B removes a fallback; it only
turns on and sharpens what already degrades correctly.

### B5. Files touched (Part B)

| File | Change |
|---|---|
| `androidMain/theme/GlassShader.android.kt` | time term in `LIQUID_GLASS_AGSL`; an SDF edge term in the film; extend the gel's `fieldAt()` to include the guilloche + ambient film; **`plateGain` uniform + `GLASS_PLATE_REFRACT_GAIN`** (the refraction-only amplification) |
| `composeUi/theme/GlassShader.kt` | doc: Rule 19 table update; per-call default for `refractBackdrop` at detail call sites |
| `ui/home/HomeScreen.kt` | hero: pass `refractBackdrop = true`; time-aware film |
| `ui/components/Components.kt` | `FolioHeroCard`: same |
| `components/Glass.kt` | only if the capability tuple needs a new axis — **prefer not to** |
| `desktopTest/…/GlassCapabilityTest.kt` / new `LiquidGlassModelTest.kt` | pin the new terms' envelope |
| `docs/guilloche-and-liquid-glass-plan.md` | the Rule 19 tables above |

### B7. Tests (Part B)

- `refractionOnlyWhereThereIsDetail` — the gel is a no-op when `refractBackdrop` is false or
  the backdrop is absent (the *existing* guarantee must not regress).
- `specularDriftIsBounded` — the new time term moves the hot-spot by ≤ the existing envelope;
  reduce-motion parks it.
- `filmIsByteForByteOffTheFloor` — API < 33 / pref off ⇒ receiver unchanged.
- `gelStillReinsChroma` — `rimChromaCeiling` unchanged and still applied (the "colour outside
  the inner rectangle" bug stays fixed).
- ~~On device: `probe -Record` over a hero during the §13.9 collapse, to confirm the rim no
  longer rides up.~~ **No longer needed — the premise was wrong.** See the collapse note in
  Stage R1: `positionInRoot` *is* `localToRoot(Offset.Zero)` and already composes every
  `graphicsLayer` transform up to the root, so the gel has always tracked the collapse. The
  "rides up" behaviour was a plausible comment, not an observed defect.

---

## Ordering, and why

```
Step 1  Guilloche model + tests (pure, no Compose)          — safest, unblocks everything
Step 2  Guilloche AGSL layer + wiring at app root           — the visible feature (A)
Step 3  View against the reference; tune the weave + alpha  — the reference is the target
Step 4  Verify A on device (banding / contrast / flow)      — must pass before Part B
Step 5  R2: the rim catch + glint + rays on the film        — the reference's biggest signal
Step 6  R1: refraction on where there is now detail         — the payoff of A
Step 7  R3: the animated edge (time-aware rim/glint)        — "the animated edge"
Step 8  R4: dispersion tuning, then adaptive theme-coloured tint
```

Steps 1–2 are one shippable increment. Step 4 is a **gate**: Part B must not be tuned until
the backdrop actually has detail, or we repeat the exact loop the code documents
(*"tuning its brightness, its Fresnel floor, its baseline and its chroma in turn each fixed
one face of that rectangle and surfaced the next"*).

### Build status

| Step | State | Evidence |
|---|---|---|
| 1–2 | **compiles** | `:shared:compileKotlinDesktop` BUILD SUCCESSFUL, 0 errors — `GuillocheModel`, `GuillocheQuiet`, the expect/actual pair, `FolioEditorial`, `MainActivity` |
| 5 (R2) | **compiles** | `:shared:compileDebugKotlinAndroid` BUILD SUCCESSFUL, 0 errors — `LIQUID_GLASS_AGSL` rim/glint/rays + `Shape`/`radii` threading |
| 7 (R3) | **compiles** | same run — `GLASS_TRAVEL_PERIOD_MS`, the von Mises travel, `GLASS_TRAVEL_PARKED_RAD` |
| 3–4, 6, 8 | **not started** | needs a device; the reference comparison is step 3 and the gate is step 4 |

Steps 1–2, 5 and 7 landed in one pass rather than the planned order, because R2 and R3 are the
same file and the same three weights — splitting them would have meant two cold Android compiles
(~5 min each) to change constants that only make sense together. The *ordering rationale* above
still holds for the remaining work: step 4 is a real gate on step 6, because turning refraction
on before the backdrop has detail reproduces the documented dead end.

Two compile failures were found and fixed on the way, both worth recording because both are
easy to reintroduce:

- **`liquidGlassLayer` had to become `@Composable`** once it read the slow clock, which then
  forced `folioLiquidGlass` to stay composable as it already was.
- **`FolioTheme.colors` is a composable read**, so `guillocheAccentFor(FolioTheme.colors, …)`
  cannot live inside a `drawWithCache` lambda. Hoisting it to the composable body is also what
  makes the plate follow a live theme change rather than freezing on the palette it first drew
  with — the bug was a genuine one hiding behind the compile error.

Steps 5 and 6 are ordered rim-first because the reference makes the rim catch the most
prominent signal on the card, and the film currently has no edge term at all — so the rim is
both the biggest visible win and the prerequisite for refraction reading as a *lens* rather
than a gradient.

Every step keeps the working tree shippable and is individually revertible. You are on
detached HEAD at `aecbe6c` (confirmed clean, nothing staged, nothing modified); work should
move to a branch (`agents/guilloche-liquid-glass`) so each step can be reviewed and dropped
independently — and so nothing is lost to the reflog while detached.

---

## Where this plan came from

Read from the working tree at `aecbe6c`, not from memory: `MainActivity.kt` (the app-root
chain), `FolioSurfaces.kt` (`folioField`, `folioPanel`), `FolioFieldModel.kt` (the closed
form), `FolioAtmosphere.kt`, `FolioLight.kt` / `FOLIO_LIGHTS`, `FolioDaylight.kt`,
`FolioLamp.kt`, `AmbientShader.{kt,android.kt,desktop.kt}`,
`GlassShader.{kt,android.kt,desktop.kt}`, `Glass.kt`, `Theme.kt`, `AmbientLightTest.kt`,
`SettingsScreen.kt`, `Components.kt` (`FolioSectionCard`, `FolioHeroCard`), `HomeScreen.kt`.

`08fb817` ("refractive glass (fake)") sits *above* the checked-out commit and has **not** been
read. If that commit already attempted the rim/refraction direction, it is worth diffing before
writing the film's SDF edge — it may contain a rejected route not worth re-walking.


## Explicit non-goals

- **No Canvas reimplementation of the guilloche.** One source of truth; the field is the
  fallback (see A6).
- **No new dependency.** Haze stays the only blur seam; AGSL stays `RuntimeShader`.
- **No per-frame composition.** Both features read the 10 Hz slow clock in the draw phase.
- **No gyro/tilt parallax, no multi-pass gaussian.** Stated, not silently skipped (B3.4).
- **No change to any existing contrast-pinned token.** `atmosphereFor` and its outputs are
  untouched; both features are *additive layers over* the field, exactly as `FolioDaylight`
  is — never a rewrite of it.
- **No change to the reader, the bar, or the nav shell.** This is background + hero glass only;
  the reader's paper stays paper — enforced by construction (A5b), not by convention.

---

## Open questions for the author

**Answered by the reference frame (2026-10-03):**

1. ~~Register?~~ **Sweeping filigree ribbon** — crossing waves with a corner fan and drifting
   light streaks, not a concentric plate and not a rosette. See A1-R.
2. ~~How visible?~~ The reference's pattern is **clearly legible**, not subliminal — so
   `GUILLOCHE_ALPHA_MAX` needs to be **higher than the original 0.05f proposal**. Starting
   suggestion: **0.10–0.14f**, tune against the reference. The ink-floor test and `probe -Point`
   must be re-run at whatever value is chosen, and A5b's quiet regions are what make a higher
   number safe near text. *(This is the one number I would like confirmed before I start, since
   it is the difference between "texture" and "pattern".)*
3. ~~Per-theme variation?~~ **Yes — the interference figure itself varies** (frequencies +
   cross-modulations from a deterministic `pack.id` seed). See A3.
4. **Still open — how aggressive should the text mask be?** A5b proposes text regions ease to
   *exactly* zero (pattern absent), reader goes to zero, everything else gets the full ribbon.
   The softer alternative is that text regions merely *thin* (say 40% of alpha) so the pattern
   stays continuous across the page. With a *more visible* pattern (per the reference) this
   question matters more, not less. I recommend zeroing.

**New, raised by the reference:**

5. **The corner fan — one corner, or mirrored?** The reference has a single fan in the top-right.
   A single fan is asymmetrical and confident; corner-balanced fans are calmer on a card-heavy
   page like Home. Which?
6. **The hero card's glass.** The reference's card interior is almost untinted (the pattern
   reads cleanly through). That means lowering the hero's wash/`paneFill` opacity. It looks
   beautiful over a pattern but risks the hero's text contrast — which the `FrameScale.Hero` /
   ink floor would have to be re-verified against. Confirm you want the card this transparent,
   or keep a touch more fill on the hero than the reference shows.

