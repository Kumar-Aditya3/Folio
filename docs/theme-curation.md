# Folio theme curation — the multi-hue pass

**Status:** in progress. This file is the durable contract; it must survive context
compression, so it restates the evidence rather than pointing at a conversation.

**Goal (user brief, verbatim intent):** every one of the 20 palettes must read as a
*colour scheme*, not one colour at several lightnesses. Dark must be **graphite**, not
dark blue. Gradients must be stronger. The proof of done is **regenerated Home images,
per theme, that look vibrant and mutually distinct**, with elements that are different
colours and still fit together.

**Authority granted:** tests, guards and comments may be bypassed to meet this goal.
Prefer *updating* an invariant to the new design intent over deleting it — see
"Guards" below.

---

## 1. The problem, measured

### 1.1 The rendered evidence

`.workbuddy-ai/tmp/curate/home_render.py` renders Home from the live Kotlin for every
theme. Baseline sheets: `sheet_dark_a/b.png`, `sheet_light_a/b.png`.

Looking at them: **each theme is a single-hue wash.** `dark` is blue, `graphite` is
blue-violet, `midnight` is blue, `dusk` is violet, `espresso` is brown; `light` is
blue-grey, `silver` is violet-blue, `arctic` is pale blue. The card is a grey-blue slab
on every one of them. The shelf covers, the week bars and the nav capsule all wear the
same hue as the page.

### 1.2 The measurement

`.workbuddy-ai/tmp/curate/mono_audit.py` + `role_hues.py` (output in `role_hues.txt`)
measure the Oklab hue of every hue-bearing role. The raw authored hexes actually carry
decent *spread* (124–180° between extremes) — so the palette data is not the whole
problem. The problem is **clustering at the point of use**:

| palette | roles crammed into one band |
|---|---|
| `graphite` | 7 roles in 266–285° (19° wide) |
| `silver` | 4 roles byte-identical at 269° |
| `dark` | 5 roles at 233° |
| `dusk` | nav/glow/guilloche all at 284–288° |

### 1.3 The root cause — the derivation overrides the authored planes

`atmosphereFor` (`FolioAtmosphere.kt`) does **not** use the palette's authored plane
colours. It *re-places* them:

```kotlin
val card = tierColour(room + CARD_STEP_DARK, tierHue(planeHue, oklabHue(colors.secondary)), CARD_CHROMA)
val panel = tierColour(...                    tierHue(planeHue, oklabHue(colors.primary)),   PANEL_CHROMA)
val well  = tierColour(...                    tierHue(planeHue, oklabHue(colors.tertiary)),  WELL_CHROMA)
```

Three constants do the flattening:

| constant | value | effect |
|---|---|---|
| `MAX_TIER_HUE_DEV` | **12°** | every tier is dragged to within 12° of the room hue |
| `CARD_CHROMA` / `PANEL_CHROMA` / `WELL_CHROMA` | **0.04 / 0.035 / 0.03** | the tiers are *nearly neutral* — the hue difference has almost no chroma to show itself in |
| `FIELD_HUE_DEV` | **6°** | the page's own two stops are one hue |

Plus `FIELD_CHROMA_DARK = 0.20` is applied **unconditionally**, so an achromatic palette
(e.g. the System `dark` face, whose `background` is `#000000` so `roomHue` is `NaN` and
`placeHue` falls back to `primary`) gets a fully saturated *blue* page. That is exactly
"the dark theme rn is dark blue".

The card is additionally drawn as `0.46·card + 0.54·field`, so the field's hue dominates
whatever the card tier contributed.

**Conclusion:** the §6.9 room-anchor fixed "the hero card is 157° off its room" by
bounding every plane to the room — which is correct as a *safety* mechanism and wrong as
a *design* mechanism. It bought coherence at the cost of every colour in the app.

---

## 2. The design system

### 2.1 Six hue stations per face

A curated face is a **composition of stations**, each authored for its own job:

| station | role(s) | job | target offset from the ground |
|---|---|---|---|
| **G** ground | `backgroundBase` | the page / the room | 0° (the reference) |
| **S** substrate | `surface` | the card — an object *in* the room | **+18…+40°** |
| **P** plate | `backgroundSecondary`, `surfaceElevated` | the second plane + wells | **+55…+110°** |
| **A1–A4** accents | the four `accent*` | progress / streak / discovery / annotation | spread ≥ **140°** total |
| **W** warm note | `warmAccent` | the one warm contrast (§30) | on the **far side** of G |
| **C** chrome | `navigationSelected` + `navigationSurface` | the nav capsule | its own station |

Plus the guilloche trio (`guillochePrimary/Secondary/Highlight`) and the two glows.

> **Both numeric claims below were written for the `curated.py` era and the sheet superseded them.
> Measured across the 20 faces:**
>
> - **The authored guilloche trio spans 75.9°–175.9°**, not 25–90°, so 19 of 20 exceed that ceiling.
>   What actually bounds the plate is the renderer: `guillocheTiersFor` compresses any authored span
>   wider than `MAX_GUILLOCHE_HUE_DEV` (**55°**, §3) down to it, so the *drawn* plate is three hues
>   within 55° of the room whatever the sheet authors. The floor still holds — the narrowest is `dark`
>   at 75.9°.
> - **The two glows sit 3.9°–75.0° apart**, not ≥40°; six of the 20 faces are below 40 and `silver`
>   is at 3.9. The glows are authored (`glowPrimary`/`glowSecondary` come straight off the sheet's
>   swatches), and on several faces they are the same swatch family — the same authored collision the
>   `pools` rule now measures as a *spread* rather than pair-by-pair (§8.1).
>
> **Nothing checks either of these today.** The trio and the glows were missing from
> `check_all.py`'s `MAP` until §7's harness fix, and no Kotlin test asserts a tier or glow
> separation. If a rule is wanted, the floors are 60° for the trio and 3° for the glows — just under
> the authored minimums, the same convention the rest of §8.1 uses.

### 2.2 What "fits together" means, numerically

The failure mode to avoid is 157° (a different colour pasted on). The failure mode to
fix is 0° (no colour at all). The window is **analogous-to-triadic**: neighbours stay
within ~40° (same family, different temperature), and the *second* plane and the accents
carry the real distance. That is the definition of a coordinated scheme and it is what
the reference mockups' *own* accents do (their pages are flat, their accents are not).

### 2.3 Chroma is a property of the palette, not a constant

The single most important change: **stop placing every plane at one chroma.** A graphite
room must be able to say "my planes are neutral"; an ocean room must be able to say "my
planes are teal". So the tier chroma target becomes the **authored plane's own chroma**,
clamped into a legibility window:

```
target_chroma = clamp(oklabChroma(authored_plane), floor, ceiling)
```

with `floor` ≈ 0.035 (below this a plane reads as grey) and `ceiling` ≈ 0.11 (above this
the card stops being a surface). Same for the field: `FIELD_CHROMA_DARK` becomes
`clamp(authored_background_chroma × K, 0.02, 0.16)` so a neutral palette yields a neutral
page and a coloured palette a coloured one.

---

## 3. The changes to `atmosphereFor`

Final values as prototyped in `curated.DERIVATION` (see §9):

| constant | from | to | why |
|---|---|---|---|
| `MAX_TIER_HUE_DEV` | 12° | **45°** | safety bound, not a flattening bound |
| `CARD_CHROMA` etc. | fixed 4.0/3.5/3.0 | **derived from the authored plane** (windows 3.5–12 / 3–11 / 2.5–10) | §2.3 |
| `FIELD_HUE_DEV` | 6° | **40°** | the page becomes a real 2-hue gradient |
| `FIELD_CHROMA_DARK` | 0.20 fixed | **derived, clamped 3–28** | graphite must stay grey |
| `FIELD_LIFT_DARK` / span | 0.050 / none | **0.095 / 0.100** | "tune up the intensity of the gradient" |
| `POOL_ALPHA` / `POOL_SAT` | 0.28 / 1.92 | **0.20 / 1.45** | the pools were repainting the room (§9) |
| `MAX_GUILLOCHE_HUE_DEV` | 10° | **55°** | the plate is three hues by design |
| light-face field | mixed | **placed at the authored plane hues**, chroma 1.5–12 | the light ramp already reads; give it hue |

The **lightness ladder is unchanged** (`card > panel > well`, `CARD_STEP_*` etc.) — depth
must still be carried by value first and hue second.


---

## 4. The graphite default

`DarkFolioColors` (`Theme.kt`) is hand-written, not art-directed. It must become a
**graphite** face:

- planes: neutral graphite, chroma ≤ 0.015, a barely-warm or barely-cool cast — **no blue**
- the page must not inherit `primary`'s hue: give `background` a defined (tiny) chroma so
  `roomHue` returns the graphite hue instead of `NaN`, **or** make `primary` itself a
  graphite-family steel so the `NaN` fallback lands on graphite anyway. (Prefer the
  former: it is explicit.)
- accents stay colourful — an accent *should* be a different colour; that is what stops
  the screen being a grey slab.

`LightFolioColors` gets the same treatment in reverse (a true white/grey paper).

---

## 5. File map

| file | what changes |
|---|---|
| `shared/src/composeUi/.../ui/theme/FolioArtDirection.kt` | all 18 authored sheet faces (plus the 2 System faces — see §8): planes get real hues + chroma; guilloche trios spread |
| `shared/src/composeUi/.../ui/theme/Theme.kt` | `LightFolioColors` / `DarkFolioColors` → graphite / true paper |
| `shared/src/composeUi/.../ui/theme/FolioAtmosphere.kt` | the derivation table in §3 |
| `shared/src/composeUi/.../ui/theme/FolioLight.kt` | light sources may need re-seating once the field axis changes |
| `shared/src/composeUi/.../ui/theme/GuillocheModel.kt` | `MAX_GUILLOCHE_HUE_DEV` 10° → wider, so the three authored thread hues survive |
| `.workbuddy-ai/tmp/curate/home_render.py` | the renderer (the proof) |
| `.workbuddy-ai/tmp/curate/mono_audit.py`, `role_hues.py` | the measurement |

---

## 6. The renderer contract

```bash
cd D:/projects/Folio/.workbuddy-ai/tmp/curate
PY=C:/Users/kumar/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe
$PY home_render.py            # -> sheet_dark_a/b.png, sheet_light_a/b.png
$PY home_render.py <palette>  # -> home_<palette>.png at 2x
$PY mono_audit.py             # hue spread / clustering per palette
$PY role_hues.py              # per-role hue + chroma table
```

It parses `FolioArtDirection.kt`, `Theme.kt`, `FolioLight.kt` and `GuillocheModel.kt`,
replays the shipping `atmosphere3` with `check_all.py`'s `SHIP` config, draws the
guilloche plate at the shipping profile, and lays out Home as `HomeScreen.kt` describes
(`ReadingNowAnchor → LedgerStrip → ContinueShelf → ThisWeekWell` + masthead + capsule).

**Prototype in Python first, port to Kotlin second.** The renderer takes a `params` dict
that overrides the `SHIP` constants, so a derivation change can be swept and *seen*
before it costs a Gradle cycle.

---

## 7. Guards

The repo's invariant tests encode the *old* design intent (e.g. `check_all.py`'s
`neutrals` rule asserts `bg/surface hue distance ≥ 8°` — a floor that is now far too
low, and a `contrastTargets` rule that must survive). The plan:

1. Keep every **legibility** floor (contrast, progress-vs-track 3:1, AA accents).
2. **Update** the *aesthetic* invariants to the new intent — a hue-spread **floor** per
   face, and a chroma window on the planes — rather than deleting them.
3. `check_all.py`'s role `MAP` omits `guillochePrimary/Secondary/Highlight`, so the oracle
   silently reports them as achromatic. Fix the harness.

---

## 8. Progress log

- [x] Baseline renderer built; baseline sheets rendered (the problem reproduced).
- [x] Measurement: mono audit + per-role hue/chroma table.
- [x] Derivation changes prototyped in the renderer (`curated.DERIVATION`).
- [x] All 20 prototype palettes authored (`curated.py`) and rendered.
- [x] Renderer fidelity fix: the hero is a *pane* (`paneAlpha`), not a slab.
- [x] All 20 palettes re-checked against the AA / contrast / ΔE floors — clear.
- [x] **Superseded — the sheet replaced `curated.py`.** The user supplied a nine-family
  art-direction sheet; §10 records the reading and the assignment.
- [x] The 108 sheet swatches written into `FolioArtDirection.kt` **verbatim**, verified by
  `.workbuddy-ai/tmp/curate/verify_assign.py` (18 faces at the time; the file carries 20 now — see
  the System-face entry below. Header and tail byte-identical.)
- [x] `atmosphereFor` ported to the authored planes — field, ladder, pools, nav (§10.2).
- [x] `MAX_GUILLOCHE_HUE_DEV` 10 -> 55.
- [x] **The two System faces are now the tenth family.** They were the only palettes no table
  generated, no renderer drew and no guard checked — which is exactly how they drifted. `sheet.py`
  gains a `system` family, `assign.system_face()` builds it, `Theme.kt` reads
  `folioArtDirection("system", light = …)`, and `theme-strip.html` now renders 20 faces. Their four
  planes come from `curated.SYSTEM_PLANES` **verbatim** (they cannot go through `face()`, which
  re-places every dark page at a fixed Oklab L and would turn `#131418` into `#020202`); everything
  else is the curated hue and chroma at the station's *shipping* lightness, frozen in
  `system_ship.json`. Verified: `assign.py --check` → 20 faces / **0 changed lines**, 500 station
  values across 20 faces Python-vs-Kotlin → **0 differences**, `verify_assign.py` → PASS, and the 18
  sheet faces provably untouched (the diff is pure addition).
- [x] **The Kotlin suite was run before and after, and this pass broke nothing — it fixed one.**
  With the pre-change theme files swapped back in and the test file held constant:
  `DesignSystemTest.materialsAreVisuallyDistinguishable` **failed** before (`light: raised fill
  0.8785 is not lighter than sunken fill 0.9302` — the hand-written `light` System face had depth
  reading inverted) and **passes** now. Failures went **6 → 5**.
- [ ] Compile `:shared:compileKotlinDesktop`, then re-render from the compiled Kotlin.
- [ ] **Open design calls** — the shelf spine, the masthead wordmark, the fallback cover (§10.4).
- [ ] Update the aesthetic invariants.

### 8.1 The five remaining theme failures, classified

Full desktop suite: **837 tests, 14 failed** — 5 theme, 9 unrelated (HTML text-align, the ONNX
tokenizer/model set, a probe that throws by design). The 5 theme failures are pre-existing and every
one names a **sheet** face, never `light`/`dark`. They are the Kotlin twins of `check_all.py`'s
pre-existing violations and match one-for-one.

| failure | id | classified as |
|---|---|---|
| `contrastTargetsPerPalette` | `warm` onSurfaceVariant/surface **3.92:1** (floor 4.5) | **derivation defect** — `ink2 = mix(ink, page, 0.42)` has no `fit()`, unlike the accents which get `fit(…, 4.5)` |
| `everyPaletteAssignsAccentRolesExplicitly` | `midnight` accentStreak == primary | **derivation defect** — the accent pool and `pick_roles` draw from the same swatches, so the repairs can collide them |
| `accentRolesArePairwiseDistinctPerPalette` | `arctic` ΔE **9.27** (floor 10) | **derivation defect** — a near-miss; the AA fit pushed two accents together |
| `ambientPoolsAreNotOneHue` | `silver` pools 1/2 **3.9°** (floor 15) | **sheet design** — the sheet's own swatches `#B29BA8` / `#B18D9F` are 3.9° apart |
| `neutralsVaryInHueNotOnlyLightness` | `warm` bg/surface hue **Δ 0.3°** (floor 8) | **sheet design** — `surface = mix(page, WHITE, .55)` preserves hue, and §10.1 states the page/card sharing a hue is intended |

**The first three are defects and want fixing; the last two are the invariant fighting a stated design
intent and want a decision.** All three defects are in *derived* roles (`textSecondary` and the four
`accent*`), so fixing them leaves the 108 painted swatches verbatim and `verify_assign.py` still
passes. But they do change the sheet faces' values, which is why they are not folded into the
System-face pass.

Two floors were updated rather than relaxed-away, per §7: `ThemeSchemeTest`'s `achromaticByDesign`
ΔL* floor 4.0 → **2.0** (the curated System planes give 2.37 / 3.44; the floor sits below the authored
minimum so a collapse to zero is still caught), and `check_all.py`'s twin rule moved with it — the
oracle went **43 → 41** violations.


---

## 10. The sheet (what actually shipped)

### 10.1 The source

`docs/theme-art-direction-sheet.md` transcribes the nine families — name, tagline, pack, and all
six hexes per polarity — verified against the image's own pixels. `docs/theme-role-assignment.md`
records the authored decision: what each of the six swatches *is*. The machine-readable form is
`.workbuddy-ai/tmp/curate/sheet.py`; `assign.py` is the transcription into Kotlin.

Light faces read `page · ink · signature · sup1 · sup2 · sup3`; dark faces read
`page · accent A · accent B · sup1 · sup2 · sup6`. The mapping to roles, and the four semantic
accents that are then AA-fitted to `surface`, are in §10.1 of `theme-role-assignment.md`.

**The structural finding:** every light page is a cream near-white and every dark page a
near-black with a whisper of tint. The theme lives in the *ink and the accents*, never in the
page. That is what makes the dark faces read graphite rather than dark blue.

### 10.2 The port

`atmosphereFor` stops deriving the two things the sheet authors:

| | before | after |
|---|---|---|
| field stops | `mixG(bg, surface, .25)` placed at the room hue, chroma 0.20 | `fieldTop = background`; dark floor `mixG(bg, black, .32)`, light `mixG(bg2, black, .07)` |
| card / panel | `tierColour(room ± step, tierHue(role))` | `surface` / `surfaceElevated`, verbatim |
| well | `tierColour(room - step, tertiary)` | `mixG(surface, background, .45)` |
| pools | `saturate(…, 1.9)` + `matchLuma(room * 1.6)` | the authored glows, verbatim |
| `poolAlpha` | 0.28 | 0.09 dark / 0.08 light |
| `navPlane` | `tierColour(lum, tertiary, NAV_CHROMA)` | `navigationSurface` |

`matchLuma(room * 1.6)` had to go with the field: the sheet's dark page is a near-black (gamma
luminance ~0.011), so that target is ~0.017 and would crush a glow into a shadow.

### 10.3 Roles must land on STRUCTURE, never on ARTWORK

The first assignment gave `sup1` to `tertiaryAccent`, which the prototype painted as the
**shelf-tile fill** — but a tile is a `FolioCoverPlate` -> `BookCover`, i.e. a real thumbnail, so
a themed fill is visible only in the fallback state. **A role assigned to a surface that user
content covers is a dead role.** Two corollaries:

- `home_render.py:761` drew the tile as `mix(tertiary, black, …)` — it fabricated a cover *out of
  the theme*, which is why the shelves looked more harmonious than they can be.
- The always-drawn structure on a plate is the **spine** (`FolioCoverPlate.kt:217`, 5.5% of the
  width, a literal `Black 30% -> Transparent` — theme-blind), the **rim** (already themed via
  `atmos.rimLight`), the **progress seam**, and the caption ink.

### 10.4 Open design calls

1. **The spine** takes the swatch that has nowhere else to live. Same geometry on all ten
   `FolioCoverPlate` call sites, no new layout, and at full strength it reads as a series of
   plates without fighting the cover art. Study: `outputs/curation/shelf_fix.py`.
2. **The wordmark** wears `accentProgress`, *not* `primaryAccent`. Measured against the page over
   all 18 faces, the authored signature fails the 3:1 large-text floor on four — arctic light
   **1.55:1**, mintcoral light **1.78**, moonstone light **2.25**, solar dark **2.76** — and sits
   under 4.5 on four more; the fitted accent clears **4.21-10.39** everywhere. Study:
   `outputs/curation/masthead.py`.
3. **`FallbackCover`** (`BookCover.kt:267`) is a hardcoded six-entry palette chosen by
   `title.hashCode() % 6`, so a coverless book shows indigo on a terracotta theme. It is the one
   surface on a plate that is 100% theme colour with nothing over it.

### 10.5 Two surfaces the harness never modelled

Neither `home_render.py` nor `check_all.py`'s `atmosphere3` renders `barScrim`, so the masthead's
status band had never been drawn *or* checked. `topBarFill` returns `scrim = 1.0` at rest, so the
band is at full strength on every screen. Measured, it is a subtle tint — Lagoon light page
`#F4F1E8` -> `#EAE8E3` (10/255), Abyss dark `#0F1720` -> `#0F1419` (7/255) — so it needs no fix,
but it should be in the renderer.

---

## 9. The prototype (awaiting approval)

**Files.** `.workbuddy-ai/tmp/curate/curated.py` is the proposal — one plane set per theme
(hue + chroma per station) plus `DERIVATION` and `MAX_GUILLOCHE_HUE_DEV`. `propose.py`
turns it into `proposal.md` (every hex, with its Oklab hue and chroma, and the floors).
`iter_sheet.py` renders `iter_dark.png` / `iter_light.png`; `home_render.py` renders the
2x sheets and any single theme.

**The rule that keeps it honest.** Only *hue* and *chroma* are proposed. Every plane's
lightness is the authored one, re-placed through `tier_colour`, so the depth ladder cannot
move and no proposed value can fall outside sRGB.

**The one exception.** The two System faces' `background` are `#000000` / `#FFFFFF` — the
two values that cannot be re-placed by hue (black has no lightness to carry a colour).
Those four roles per face are authored outright in `curated.SYSTEM_PLANES`: `dark` becomes
`#131418` (graphite, chroma 0.8) and `light` becomes `#FAF8F4` (warm paper).

**Three findings the prototype forced:**

1. **The hero pane.** `paneAlpha` is 0.46 dark / 0.66 light, so the card the reader sees is
   *54% the field*. The renderer had been drawing it opaque, which flattered every card and
   hid the single biggest reason the app read as one colour. Fixed; the prototype now
   composites it the way the app does.
2. **The ambient pools were repainting the room.** `POOL_ALPHA 0.28` at `POOL_SAT 1.92` over
   three pools of radius ~1.15x the screen meant every theme with a cyan glow read teal
   whatever its ground was authored to be. Held at 0.20 / 1.45 the pools *light* the room
   instead of becoming it — this is the change that made the ten dark themes separable.
3. **Accents are inks, so re-placing the surface re-opens their floor.** Holding gamma
   luminance does not hold WCAG luminance. Nine light faces dropped below 4.5:1 on at least
   one accent; `aa_fit` now pushes each accent toward the page's ink until it clears AA,
   which is the same rule the authored faces already follow. All 20 clear every floor.



---

## 11. The crown and the guilloche (final rules)

### 11.1 The crown is *placed*, never mixed

`FolioTopBar`'s status band is at full strength at rest (`topBarFill` returns `scrim = 1.0`), so
the masthead is the band plus the wordmark. The band is now an **authored crown** — and the one
thing that took four attempts to get right is that it must be *placed*, not mixed:

    crown, _ = tier_colour(0.155, hue_of(signature), min(ochroma * 100, 24))

A fixed fraction toward black cannot put two faces at the same darkness. The sheet's *light*
signatures are mid-tones, so 0.28 of darkening lands dark; its *dark* signatures are luminous
(`abyss` is `#7CD4C6`), so the same 0.28 lands mid-light. Placing at a common gamma luminance is
what makes "the dark themes' crown is the reference and the light themes' match it" true rather
than hoped for — and it keeps each family's own hue. The name's ink is chosen against the band by
the renderer, so it flips automatically.

The **nav capsule wears the same colour** (`mix(crownTop, crownBot, 0.45)`): they are the same
furniture at two ends of the screen.

**Two traps, both named:** the crown must start at the bar and **not behind the status strip**
(the OS picks status icons from the theme's *polarity*, so a luminous band erases them), and
`tier_colour` wants chroma **x100** while `O.ochroma` returns 0-1.

### 11.2 The guilloche: composition first, threads second

Two rewrites, and the second reason is the important one.

1. A **flow field** is the wrong construction. A field has one global structure, so every thread
   inherits it and the plate renders as long, low-curvature, nearly parallel curves.
2. The **first ribbon version** was right in kind, wrong in degree: it kept an oscillating
   harmonic centreline, so each ribbon carried many small bends, every bundle competed at equal
   weight, and the canvas had uniform complexity. Technically ribbons, still noise.

`flow_plate.py` now generates the **composition first**:

* 4 asymmetric **cubic Bézier** centrelines, widely-separated control points — 1-2 major bends
  each, not a wave train.
* **Hierarchy**: width `{1.0, 0.72, 0.48, 0.30}`, opacity `{1.0, 0.65, 0.38, 0.18}`, threads
  `{42, 26, 16, 12}` — primary / secondary / supporting / ghost.
* **Nearly uniform spacing** `q_j = -1 + 2j/(N-1)` + perturbation < 0.03. A power law
  (`|r|**1.8`) pushes threads to the centre and makes them read as independently-generated
  curves rather than parallel engraving lines of one ribbon.
* **Gentle width** `w0(0.82 + 0.18 cos(2*pi*u + phi))` — a ribbon folds, it does not inflate.
* Colour **interpolated along `u`** between two positions on a ramp built by sorting the face's
  own swatches by hue. A re-ordering of the sheet, nothing invented.
* The **mask carries the per-ribbon alpha**, so a ghost ribbon is faint without the colour layer
  being darkened — darkening shrinks coverage, which is what faded the previous version on paper.

### 11.3 Still open

* **The AGSL shader.** The plate is a shader in the app, not a raster; 84 threads per frame is not
  what `GuillocheShader.android.kt` does today.
* **The animation** from the brief — `phi_k(t)`, `w_k(t)` — and the local rotational flow fields
  `V_k`. The plate is at rest.
* **`GuillocheQuiet` is not modelled in the renderer**, so the Home images are pessimistic: the
  app collapses the plate where text sits, and with threads that mask is load-bearing.
