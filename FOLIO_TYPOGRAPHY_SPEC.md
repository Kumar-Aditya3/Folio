# Folio — Typography, Neutrals & New Themes Spec (§15)

Fourth companion spec. Rules 1–12 in `FOLIO_IMPLEMENTATION_SPEC.md`, Rules 13–18 + 20 in
`FOLIO_VISUAL_SPEC.md`, Rule 19 in `FOLIO_MOTION_SPEC.md`, Rule 21 in `FOLIO_DEPENDENCY_PLAN.md`.
This file adds **Rules 22–24** and **§15**.

Baseline: v1.1.21 / commit `927572e`. Platform: **Android only** (Rule 1), except §15.2 which is a
`Theme.kt` change and therefore lands on both.

**Why this file exists.** §12–§14 chased *effects* — accent roles, gradients, blur, shaders. The app
still reads monotonous because the two largest contributors to visual interest were never touched:
the **neutral ramp** (~90% of on-screen pixels) and the **type scale** (every glyph). Neither needs a
dependency, an API floor, or a version gate. This is the highest-leverage work remaining.

---

## 15.0 Measured findings

### Finding A — the neutral ramp is hue-locked

Peacock's complete neutral set, read from `Theme.kt`:

```
background               #001A24     surfaceVariant            #0A3340
surface                  #002530     surfaceContainerHighest   #144454
outline                  #2A6E80     outlineVariant            #144454
onBackground             #C8FAFF     onSurface                 #C8FAFF   ← identical
onSurfaceVariant         #78D2E4
```

Every value sits at ~195° hue. `onBackground` and `onSurface` are the *same colour*. All 26 custom
palettes override both `background` and `surface` in this pattern, so the overwhelming majority of
pixels are one hue at six lightnesses. The §12.3 accent roles are genuinely varied — Peacock carries
cyan `#26C6DA`, orange `#FF8A00`, green `#69F0AE`, pink `#F06292` — but they only ever paint a 28dp
ring, a chart bar and a section title. **Accents cannot fix a monochrome field they occupy 2% of.**

### Finding B — the display type tier is entirely unused

Usage counts of `FolioTheme.typography.*` across `shared/composeUi` and `androidApp`:

```
bodySmall     83      titleLarge        14      headlineLarge    0
bodyMedium    55      headlineSmall      6      displaySmall     0
labelSmall    46      headlineMedium     4      displayMedium    0
labelLarge    43                                displayLarge     0
titleSmall    32
labelMedium   29
titleMedium   22
bodyLarge     20
```

`displayLarge` (57sp), `displayMedium` (45sp), `displaySmall` (36sp) and `headlineLarge` (32sp) are
defined and never called. The largest text anywhere in Folio is **28sp, used four times**; everything
else lives between 11 and 22sp. Weight is equally flat: **12 of 16 styles are `W600`**, the other four
`Normal` — no light, no heavy.

A UI cannot feel dynamic when nothing is meaningfully bigger or bolder than anything else. `bodySmall`
is doing 83 different jobs.

---

## 15.1 RULES

### Rule 22 — Neutrals must vary in hue, not only lightness
Within one palette, `background` and `surface` must differ by **≥ 8° of hue** (or, for intentionally
achromatic palettes, by ≥ 0.04 relative luminance *and* a stated design note). `onSurface` and
`onSurfaceVariant` must never be the same colour, and must differ by **ΔE ≥ 12**.

**Benchmark:** new `ThemeSchemeTest` cases assert both, across all 30 palettes.

### Rule 23 — Every screen uses at least three type tiers, one of them ≥ 32sp
A screen composed only of `body*`/`label*` styles is rejected. The hero or primary figure on each
top-level destination uses `displaySmall` or larger.

**Benchmark:** `Select-String -Pattern 'typography\.(display|headlineLarge)'` returns ≥ 1 hit for
each of Home, Stats and Book detail.

### Rule 24 — Weight carries hierarchy alongside size
The type scale must span **at least three weights** (light/regular/semibold or heavier). Large display
text uses the lightest available weight; small labels use the heaviest. This inverts the instinct and
is what makes editorial layouts feel considered.

**Benchmark:** `FolioTypography` declares ≥ 3 distinct `fontWeight` values, asserted in test.

---

## 15.2 Neutral ramp rework (fixes Finding A)

**Method, applied to every palette.** Four moves, in this order:

1. **Deepen and desaturate `background`.** Push it toward true dark/true light and drop chroma. The
   base plane should be the *least* coloured thing on screen, not a saturated wash.
2. **Rotate `surface` 8–15° off `background`'s hue** and give it slightly more chroma. Cards then read
   as objects *on* a plane rather than lighter patches *of* it.
3. **Rotate `surfaceVariant` a further 8–15°** in the same direction. Three planes, three hues, one
   family.
4. **Split the two text colours.** `onSurface` stays near-neutral and high-contrast;
   `onSurfaceVariant` becomes cooler (dark themes) or warmer (light themes) with visibly lower chroma.

### Worked example — Peacock

| Role | Now | Target | Change |
|---|---|---|---|
| `background` | `#001A24` | `#00131A` | deeper, less chroma |
| `surface` | `#002530` | `#062A3A` | +10° toward blue |
| `surfaceVariant` | `#0A3340` | `#123344` | +8° further |
| `surfaceContainerHighest` | `#144454` | `#1A4257` | follows surface |
| `outline` | `#2A6E80` | `#2F6B84` | follows |
| `onSurface` | `#C8FAFF` | `#E8F6FA` | near-neutral, brighter |
| `onSurfaceVariant` | `#78D2E4` | `#8FB4C4` | cooler, ~40% less chroma |

Contrast after: `onSurface` on `surface` rises from ~11.9:1 to ~13.4:1; `onSurfaceVariant` stays above
4.5:1 while no longer competing with primary text.

**Apply the same four moves to all 28 existing palettes.** Achromatic-by-design palettes (`LIGHT`,
`OLED`, and the new `GRAPHITE`) satisfy Rule 22 via the luminance clause plus a code comment.

### Contrast targets per palette (Rule 22 + the existing WCAG gate)

| Pair | Minimum | Target |
|---|---|---|
| `onSurface` / `surface` | 7.0:1 | ≥ 10:1 |
| `onSurfaceVariant` / `surface` | 4.5:1 | 5.5–7:1 (deliberately *below* primary text) |
| `onBackground` / `background` | 7.0:1 | ≥ 10:1 |
| `outline` / `surface` | 1.5:1 | 2–3:1 (visible rim, not a line of text) |
| each accent / `surface` | 4.5:1 | unchanged from §12.3 |

## 15.3 Typography rework (fixes Finding B, Rules 23–24)

### 15.3.1 Weight ladder

`UiFonts` already loads **variable** Fraunces and Manrope, so extra weights cost no new assets.
Extend `FolioTypography` to three weights:

| Tier | Weight | Rationale |
|---|---|---|
| `display*` | **W300** | large text needs less weight; light at 45sp reads editorial, W600 reads shouty |
| `headline*`, `title*` | W600 | unchanged |
| `body*` | W400 | unchanged |
| `label*` | **W700** | small text needs more weight to hold colour and stay legible |

### 15.3.2 Reassignment table — where the display tier goes

| Surface | Element | Now | Becomes |
|---|---|---|---|
| Home hero | book title | `titleLarge` 22sp | **`displaySmall` 36sp W300** |
| Home goal strip | minutes figure | `labelMedium` | **`headlineMedium` 28sp**; unit stays `labelSmall` |
| Stats | primary total | `headlineMedium` 28sp | **`displayMedium` 45sp W300** |
| Stats | streak count | `titleLarge` | **`displaySmall` 36sp** |
| Book detail | book title | `titleLarge` | **`headlineLarge` 32sp** |
| Book detail | "Your reading" figures | `bodyMedium` | **`headlineSmall` 24sp** for the number only |
| Library | section headers | `titleSmall` | unchanged |
| Reader chrome | everything | unchanged | **unchanged** — the reader is text; chrome must not compete |

**Numerals get the display tier; their labels stay small.** A 45sp figure beside an 11sp label *is*
hierarchy, and it is the cheapest visual upgrade available in this codebase.

### 15.3.3 Constraints

- **Do not touch reader body text.** `ReaderCss`/`HtmlRenderer` typography is user-controlled (§4's
  settings scope). §15.3 is chrome only.
- Long titles ellipsize at `maxLines = 2` in the hero; a 36sp four-line title would eat the screen.
- `displayLarge` (57sp) stays defined but unused — no surface in a reader justifies it. Keep it for a
  future onboarding moment rather than deleting it.

## 15.4 Two new themes

Both from the supplied reference images. Added to `AppPalette`, `Theme.PRESETS` and `ThemePack.ALL`
**together** — `ThemeSchemeTest` already fails a palette missing from any of the three registries.

### 15.4.1 `GRAPHITE` — "Graphite" (white on dark grey)

Reference: white wordmark on near-black neutral grey. The deliberate opposite of the saturated
palettes — **zero hue, maximum legibility**. The theme for people who find the others loud.

```
background               #17181A   near-black, faint cool cast
surface                  #1F2124   +0.02 luminance
surfaceVariant           #2A2D31
surfaceContainerHighest  #35383D
outline                  #3E4247
outlineVariant           #2A2D31
onBackground             #F5F6F7   near-white, not pure #FFF (pure white on dark bloom-glares)
onSurface                #F5F6F7
onSurfaceVariant         #A8AEB5   grey, clearly secondary
primary                  #E8EAED   near-white — buttons read as light on dark
onPrimary                #17181A
accentProgress           #7FB0D9   cool blue
accentStreak             #E0C070   muted gold
accentDiscovery          #8FBF9F   muted sage
accentAnnotation         #C89BB5   muted mauve
```

Rule 22 satisfied via the luminance clause (achromatic by design — note it in code).
`onSurface`/`surface` ≈ 13.8:1. The accents are desaturated on purpose: on a neutral field a little
colour goes far, and they still clear ΔE ≥ 10 and 4.5:1.

Reader preset `graphite` — `#1A1B1D` page, `#EDEEEF` ink.
Pack: `ThemePack("graphite", "Graphite", "graphite", "graphite")`.

### 15.4.2 `BLOSSOM` — "Blossom" (true black + rose)

Reference: pure-black field, rose-pink accents, warm mauve card, muted grey secondary. A **true black**
OLED theme with one warm accent family — distinct from `OLED` (grey-blue accents) and `BUBBLEGUM`
(light).

```
background               #000000   true black, as in the reference
surface                  #141013   warm-shifted, not neutral (Rule 22)
surfaceVariant           #241C21   the mauve card plane
surfaceContainerHighest  #33272E
outline                  #45353D
outlineVariant           #241C21
onBackground             #F7EFF2   warm white
onSurface                #F7EFF2
onSurfaceVariant         #B9A6AE   warm grey
primary                  #F8A0B4   rose — the reference's dominant accent
onPrimary                #2B0A14
primaryContainer         #6E2438
onPrimaryContainer       #FFD9E1
accentProgress           #F8A0B4   rose
accentStreak             #F0C088   peach (the reference's tan swatch)
accentDiscovery          #E8748C   deeper rose (its red swatch)
accentAnnotation         #C9A0D8   violet, for separation from the rose family
```

`accentProgress` and `accentDiscovery` are both rose-family, so **verify ΔE ≥ 10 between them before
committing** — if it fails, push `accentDiscovery` toward `#E85C7A`. The existing test will catch it;
do not weaken the test.

Reader preset `blossom` — `#0A0508` page, `#F2E6EA` ink, rose highlight palette.
Pack: `ThemePack("blossom", "Blossom", "blossom", "blossom")`.

### 15.4.3 New-theme checklist (both)

1. `FolioColors` value in `Theme.kt` with all four accent roles and `chartSeries` (8 hues, first six
   pairwise ΔE ≥ 10)
2. `AppPalette` entry with correct `isDark`
3. Reader preset in `Theme.PRESETS` with exactly 8 `highlightColors`
4. `ThemePack.ALL` entry pairing them — darkness must agree (existing test)
5. `ThemeSchemeTest` green, including the new Rule 22 cases
6. Visual check on Home, Library, Stats, Book detail, reader

---

## 15.5 Test additions

Extend `ThemeSchemeTest` — it already has `deltaE`, `wcagRatio` and `lab` helpers, so all four cases
reuse existing maths:

```kotlin
@Test fun neutralsVaryInHueNotOnlyLightness()      // Rule 22, part 1
@Test fun primaryAndSecondaryTextAreDistinct()     // Rule 22, part 2 (ΔE ≥ 12)
@Test fun contrastTargetsPerPalette()              // §15.2 table, all 30 palettes
@Test fun typographySpansAtLeastThreeWeights()     // Rule 24
```

`neutralsVaryInHueNotOnlyLightness` needs an achromatic allowlist —
`setOf("light", "oled", "graphite")` — checked against the luminance clause instead of hue. Keep that
list in the test, not in `Theme.kt`, so adding a palette to it is a visible decision.

*(Shipped v1.2.1 — Rule 22/§15.2 applied to all 28 palettes. Two recorded readings: **(1)** the
achromatic luminance clause is enforced as ΔL* (CIELAB) ≥ 4.0, not ΔY ≥ 0.04 — the spec's own
Graphite pair `#17181A`/`#1F2124` measures ΔY ≈ 0.006 yet ΔL* ≈ 4.4, so the literal 0.04 reading
would reject this spec's own reference theme; **(2)** `dark` joins the allowlist in
`ThemeSchemeTest` (`setOf("light", "dark", "oled", "graphite")`): a true-black base cannot carry
hue, so `DARK` and `OLED` separate planes by lightness instead — both surfaces lifted to `#101010`
(ΔL* ≈ 4.7 against true black) and `OLED`'s outline lightened `#262626` → `#494949` (2.11:1;
darkening can never reach 1.5:1 against a near-black surface). Dark palettes rotate surface =
background hue +11° and the variant planes +20°; light palettes hold colour value at 1.0 so the
§12.3 4.5:1 accent gate survives with at most one darkened accent per palette. The §15.2 Peacock
worked example was not already Rule 22-compliant — it needed the same rotation and now measures
Δhue ≈ 10.8.)*

## 15.6 Phasing

| Phase | Contents | Version |
|---|---|---|
| **T1 — Test contract first** | §15.5's four cases, written to **fail** against today's palettes. This is the whole method: the tests define the target, then the palettes move to satisfy them. | 1.2.x |
| **T2 — Neutral ramp** | §15.2 across all 28 existing palettes. `Theme.kt` only, no feature files. | 1.2.x |
| **T3 — Typography** | §15.3 weight ladder + reassignment table | 1.2.x |
| **T4 — New themes** | §15.4 `GRAPHITE` and `BLOSSOM`, full checklist | 1.2.x |

T1 first is deliberate and mirrors §12 Phase A: land the contract, watch it fail, then satisfy it.
Writing the palettes first means hand-checking 30 × 5 contrast pairs by eye.

**T2 warning.** Touching 28 palettes in one commit is the largest single edit in this project's
history. Do it as **one palette per commit, or in batches of five at most**, each batch with
`ThemeSchemeTest` green. A 28-palette diff that fails the test is unbisectable.

## 15.7 Verification for every §15 phase

In addition to main-spec §10:
- `ThemeSchemeTest` green — all 30 palettes, all cases including the four new ones
- `:shared:desktopTest` ≥ 148 green (T1 raises the count; it must not fall afterwards)
- `:shared:compileKotlinDesktop` **and** `:desktopApp:compileKotlin` clean — `Theme.kt` is shared, so
  §15.2/§15.4 legitimately reach desktop; that is the one exception to Rule 1 in this file, and it is
  colour data, not UI
- On-device visual check per palette group: one light, one dark, one saturated, plus both new themes
- Rule 11 device smoke test
- Rule 13 blur test on Home after T3 — a 36sp hero title changes what dominates

## 15.8 What this spec refuses

- **Generated colour schemes** (Material Kolor and similar). 28 hand-tuned palettes with an enforced
  ΔE/WCAG contract cannot be replaced by seed-derived schemes without deleting the guarantee that
  every theme is legible. §14's Rule 21 clause 4 also applies: this is colour data, not a library
  problem.
- **More palettes beyond these two.** 30 is already more than any reader needs; the remaining work is
  making the existing ones *better*, not adding a 31st.
- **Touching reader body typography.** User-controlled (§4).
- **`displayLarge` in normal UI.** 57sp has no home in a reading app's chrome.
- **Weakening `ThemeSchemeTest` to make a colour fit.** If `BLOSSOM`'s two roses fail ΔE, the colour
  moves — not the threshold.
