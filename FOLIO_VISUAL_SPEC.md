# Folio — Visual Design Spec (§12)

Companion to `FOLIO_IMPLEMENTATION_SPEC.md`. Separate file so it can be edited in parallel;
merge into the main spec as §12 when convenient.

Rules 1–12 of the main spec apply unchanged. This document adds Rules 13–18, which are
**additive** — nothing here relaxes Rule 2 (tokens only) or Rule 3 (one component per job).

Baseline: v1.0.30 / commit `70985ed`. Platform: **Android only** (Rule 1).

---

## 12.0 The two problems

**Problem A — monochrome flatness.** Every surface is drawn from one `FolioColors` role set,
so a card, a chip, a bar and a label in the "Peacock" palette are all the same teal at
different alphas. Verified in the code:
- `glassPanel` fills with `colors.surface` + a white sheen — identical for every card
- `FolioSectionCard` titles are always `colorScheme.primary`
- `ChartBar` fills with `colors.primary`, flat
- `ProgressRing` fills with `colors.primary` on `surfaceVariant`
- Body text is `onSurface`, secondary text `onSurfaceVariant`

Result: one hue, five alphas, no focal point. The palettes are not the problem — they carry
plenty of colour (`headingText`, `link`, `bookmark`, `progress`, `selection`, and an
8-entry `highlightColors` list per reader theme). **Those roles exist and are unused by the
app chrome.** This is a distribution problem, not a palette problem.

**Problem B — Home is bland and repeats Stats.** Home currently renders four
`FolioSectionCard`s of near-identical weight (goal ring, continue reading,
because-you-finished, this week). `ThisWeekCard` calls the same `WeekBars` the Stats tab uses,
so the two screens literally share a chart. Nothing on Home is bigger, warmer or more
image-led than anything else, and the one genuinely visual asset the app owns — cover art —
appears only at 48×72 dp thumbnails.

---

## 12.1 §12 RULES

### Rule 13 — Three tiers of surface, never one
Every screen must contain at most one **hero** surface, any number of **standard** surfaces,
and any number of **quiet** surfaces. They must be visually distinguishable without reading
the text.

| Tier | Container | Used for |
|---|---|---|
| Hero | `FolioHeroCard` (new) — accent-tinted gradient fill, `radiusCard`, cover art or large numeral | The one thing the screen is about |
| Standard | `FolioSectionCard` (existing, unchanged) | Grouped content |
| Quiet | `FolioQuietRow` (new) — no fill, no border, `space2` vertical padding only | Lists, secondary stats, metadata |

**Benchmark:** screenshot any screen, blur it to 8px, and the hero must still be identifiable
as the dominant element. A screen with three same-weight cards fails.

### Rule 14 — Accent roles are assigned, not inherited
`FolioColors` gains four **semantic accent** roles derived from the active palette, and
chrome must use the semantically correct one rather than defaulting to `primary`:

| Role | Meaning | Replaces today's |
|---|---|---|
| `accentProgress` | forward motion: rings, bars, progress fills | `primary` |
| `accentStreak` | streaks, goals met, celebration | `primary` |
| `accentDiscovery` | recommendations, new content, "because you finished" | `primary` |
| `accentAnnotation` | highlights, notes, quotes, bookmarks | `primary` |

Each palette assigns all four explicitly. Reader themes already carry `progress`, `bookmark`,
`link` and `headingText` — reuse those values so app chrome and reader ink agree.

**Forbidden:** `FolioTheme.colors.primary` as the fill of a chart, ring, or progress bar in
new code. `primary` is for interactive affordances (buttons, selected chips) only.

**Benchmark:** `Select-String -Pattern 'colors\.primary'` inside `StatsCharts.kt`,
`ProgressRing`, and any new chart returns zero hits.

### Rule 15 — Data visuals carry a gradient and a peak marker
Charts are the biggest wasted surface in the app. Every bar/ring/sparkline:
- fills with a **vertical gradient** from its accent role to that role at 55% alpha
- marks its **peak or current value** distinctly (brighter cap, dot, or label weight)
- animates from zero on first composition over `FolioTokens.motionEmphasis`

**Benchmark:** no flat single-colour `.background(color)` on a chart element in new code.

### Rule 16 — Home and Stats may not share a chart composable
`WeekBars` is currently called from both. Home shows *momentum* (short horizon, celebratory),
Stats shows *history* (long horizon, analytical). They must use different visual forms even
when the underlying data overlaps.

**Benchmark:** no composable is imported by both `ui/home/` and `ui/statistics/` except
primitives from `ui/components/`. `WeekBars` stays in `ui/statistics/` and Home stops
importing it.

### Rule 17 — Cover art is the app's primary visual asset
Any surface listing books or manga must show cover art at **≥64 dp on its shorter edge**, and
the hero surface of Home must show one cover at **≥120 dp**. Text-only book lists are allowed
only in the compact library view mode, which the user explicitly selected.

**Benchmark:** Home's hero renders a cover at ≥120 dp; `BookCover`'s generated fallback
(already implemented for missing art) is acceptable and must be exercised in the test.

### Rule 18 — An excluded title is excluded everywhere it is recommended
A title excluded via §11.2's `stats_exclusions` must not appear in any surface the app chose
to show the user — hero, carousels, suggestions, discovery. It must still appear everywhere
the user asked for it — library, search, direct navigation, its own detail screen. Full
treatment in §12.9.

**Benchmark:** the §12.9 acceptance criteria.

---

## 12.2 New tokens

Add to `FolioTokens` (Rule 2 — never inline these):
```kotlin
// §12 surface tiers
val radiusHero = 28.dp          // hero cards sit above radiusCard's 20
val heroCoverMin = 120.dp       // Rule 17 hero cover floor
val listCoverMin = 64.dp        // Rule 17 list cover floor

// §12 data visuals
val chartPeakCap = 3.dp         // brighter cap marking a peak bar
val ringStrokeHero = 8f         // hero ring stroke (compact strip uses 4f)
val sparkHeight = 32.dp         // inline sparkline height
val gradientMinAlpha = 0.55f    // floor for chart gradients (Rule 15)
```

## 12.3 Accent roles — implementation

`FolioColors` gains four fields with defaults that preserve today's appearance, so every
existing palette still compiles:
```kotlin
val accentProgress: Color = primary,
val accentStreak: Color = primary,
val accentDiscovery: Color = tertiary,
val accentAnnotation: Color = primary,
```

Then **every** `AppPalette` entry (28 of them) assigns all four explicitly. Guidance:
- `accentProgress` — the palette's coolest saturated hue
- `accentStreak` — its warmest (amber/orange/rose); this is the "you did it" colour
- `accentDiscovery` — a hue distinct from both above
- `accentAnnotation` — match the reader theme's `bookmark` where a `ThemePack` pairs them

The four must be **mutually distinguishable**: no two roles in one palette may be within a
ΔE of ~10. For monochrome-by-design palettes (`OLED`, `LIGHT`) vary lightness instead of hue,
but they must still differ.

**Test (`:shared:desktopTest`, extend `ThemeSchemeTest`):**
- all 28 palettes assign all four roles (none left at the default)
- within each palette, the four roles are pairwise distinct
- each role has ≥4.5:1 contrast against that palette's `surface`

## 12.4 Home redesign

Order and treatment. **Hero first, then never two adjacent surfaces of the same weight.**

**1. Hero — "Reading now"** (`FolioHeroCard`)
Replaces the goal ring as the focal point. Puts the user's actual content first.
- Cover of the most recently opened in-progress book **that passes `StatsScope`** at
  `heroCoverMin` (≥120 dp) — see §12.9
- Behind it: a vertical gradient from `accentProgress` at 30% to transparent
- Title (`titleLarge`), author (`bodyMedium`, `onSurfaceVariant`)
- Progress as a **thin bar under the cover** — not a ring (§2.6: one form per view)
- `finishEstimate` line when available (absent, not blank, when null — §5.1)
- Primary tap → reader at saved position. One filled "Continue" button (§2.2's single
  primary action for this screen).
- Empty library → the §5.4 empty state, unchanged.

**2. Goal strip** (quiet, not a card)
The goal ring shrinks from `ringLarge` (96 dp) to a **compact horizontal strip**:
`[ring 32dp] 34 / 60 min · 5-day streak`
- Ring uses `accentProgress`; the streak text uses `accentStreak`
- On a met goal the strip fills with `accentStreak` at 12% and the numeral goes semibold
- Tapping opens the Stats tab

**3. "Continue reading" — horizontal carousel** (standard card)
Currently a vertical list of 48 dp thumbnails; becomes a `LazyRow` of covers at
`listCoverMin` (≥64 dp) with progress rings, capped at 3 as today. Two exclusions apply:
the hero book (so it is never shown twice) and anything `StatsScope` rejects (§12.9).

**4. "Because you finished X"** (standard card, `accentDiscovery`)
Card title tinted `accentDiscovery` instead of `primary`, covers at `listCoverMin`. Unchanged
logic: hidden below 2 candidates. Both the anchor and the candidates respect `StatsScope`
(§12.9).

**5. "This week" — sparkline, not bars** (quiet)
Satisfies Rule 16. Home gets a single-line **sparkline** (`sparkHeight` 32 dp) with a dot on
today, plus `"1h 12m this week · 3 started · 1 finished"`. The 7-bar chart stays exclusive to
Stats. Home stops importing `WeekBars`.

**Acceptance criteria:**
- Home has exactly one hero and no two adjacent same-weight surfaces
- The hero book does not also appear in the carousel
- Blur test (Rule 13) identifies the hero
- `ui/home/` imports nothing from `ui/statistics/` except the `StatDay`/`ReadingInProgress`
  data types and shared `ui/components/` primitives
- Zero-progress library: hero shows the most recently *added* book with no progress bar
- Every §12.9 criterion passes

## 12.5 Stats redesign — differentiate from Home

Stats keeps the analytical role and gains the visual variety it lacks:
- **Hero:** the year heatmap, promoted to `FolioHeroCard`, cells tinted `accentProgress`
  scaled by intensity (currently flat `primary` alpha)
- 7-day bars: gradient fill per Rule 15, peak bar capped in `accentStreak`
- Streak card: `accentStreak` throughout, with best-streak alongside current
- Top-books leaderboard: covers at `listCoverMin` — currently text-only
- Genre breakdown: distinct hues from the palette's accent roles, not one hue at N alphas
- Quotes/highlights feed: `accentAnnotation`

**Acceptance criterion:** Home and Stats screenshotted side by side are not mistakable for
each other, and no chart composable is shared.

## 12.6 Making the existing palettes work harder

No new palettes. Distribute what the 28 already carry:
- **`glassPanel` accent tint.** Add an optional `accent: Color? = null`. When set, the sheen
  gradient's top stop mixes 8% of that accent into the white. Heroes pass their accent;
  standard cards pass null and are pixel-identical to today.
- **Section titles** use the card's semantic accent, not `primary`.
- **Reader `highlightColors`** (8 per theme, currently reader-only) become the genre-breakdown
  and multi-series chart palette.
- **Cover-derived tint (optional, last).** `BookCover` already decodes covers; sampling one
  dominant colour and using it for the hero gradient would make Home feel like the book being
  read. Gate it behind a setting, default off, and never let it override an accent role's
  contrast guarantee.

**Constraint:** all of §12.6 must be reversible per surface. If a palette looks wrong, the fix
is a role reassignment in `Theme.kt`, never a change in a feature file.

## 12.7 Phasing

| Phase | Contents | Version |
|---|---|---|
| **A — Foundations** | §12.2 tokens, §12.3 accent roles across all 28 palettes + `ThemeSchemeTest` extension, `FolioHeroCard`/`FolioQuietRow`, `glassPanel` accent param | 1.1.x *(shipped through v1.1.13: tokens, accent roles, ThemeSchemeTest contract incl. pairwise ΔE + WCAG gates, glassPanel accent; HeroCard/QuiRow components exist)* |
| **B — Home** | §12.4 in full, including Home's own sparkline, **and §12.9's `StatsScope` gating** | 1.2.0 |
| **C — Stats** | §12.5 in full | 1.2.x |
| **D — Spread** | §12.6 across library, book detail, reader chrome | 1.3.x |

Phase A ships with **zero visible change** except the new components existing — that is the
point: roles land and are proven contrast-safe before any screen depends on them.

**Verification for every §12 phase** (in addition to main-spec §10):
- Three-theme check per §2.7 — `LIGHT`/`paper`, `midnightneon`, `rainbow`
- Rule 13 blur test on every screen touched
- `ThemeSchemeTest` green across all 28 palettes
- Rule 11 device smoke test

## 12.8 Sequencing against §11

§12 Phase A is independent of everything and can start immediately. §12 Phase B/C touch
`HomeScreen`/`HomeViewModel` and `StatisticsScreen`, which §11 Phase 7 (stats exclusion) also
touches — do **not** run those in parallel. §12 B/C are additive to §11's manga Home cards
(§11.4), so whichever lands second inherits the other's surface tiers.

**§12.9 depends on §11 Phase 7.** `StatsScope` must exist before Home can gate on it. If §12
Phase B lands first, ship the layout work and land §12.9's gating in the same commit as §11
Phase 7's Home consumption — they are the same change viewed from two specs, and splitting
them leaves a window where Home recommends books the user excluded.

Neither §12 nor §11 may begin before the §6 manga splits (§11.1), since Rule 9 is enforced
repo-wide.

## 12.9 Exclusion applies to content surfaces, not just numbers

§11.2 scoped `StatsScope` to statistics. That is not enough. `HomeViewModel.buildState`
builds `continueReading` from `getCurrentlyReading()` and the `candidates` list from
`getAllBooks()`, and **neither consults `StatsScope` today**. So a book excluded from stats
would still be the first thing on Home, as its hero — which is worse than it appearing in a
total, because it is the largest element on the screen.

**Rule 18 — an excluded title is excluded everywhere it is recommended to you.**

`StatsScope` gates all four of Home's content selections:

| Surface | Selection | Effect of exclusion |
|---|---|---|
| Hero "Reading now" | most recent in-progress | skipped; the next passing book becomes hero |
| Continue reading | ≤3 in-progress | filtered out |
| Because you finished | anchor **and** candidates | excluded book is neither anchor nor suggestion |
| Manga continue reading / New chapters / Discover (§11.4) | all three | filtered out |

**What exclusion does *not* touch** — the distinction that keeps this coherent:
- The **Library** shelf shows everything. It is inventory, not a recommendation; hiding books
  from your own library would be a bug, and the library already has its own filter system.
- **Search** returns excluded titles.
- The **reader** opens them normally and keeps recording sessions and progress (§11.2's
  non-destructive guarantee).
- Book/manga **detail** screens show their own per-title stats — you asked for that title
  specifically, so it is not a recommendation.

The rule in one line: **exclusion removes a title from anything the app chose to show you; it
never removes it from anything you asked for.**

**Empty-state consequence.** Excluding every in-progress book leaves Home with no hero. That
is a legitimate state and needs a designed empty (Rule 7), not a blank: the hero slot shows
"Nothing in progress — pick something from your library" with one outlined "Open library"
button. Do **not** fall back to showing an excluded book.

**Acceptance criteria:**
- Exclude the book currently in Home's hero → the hero becomes the next in-progress book that
  passes, and the excluded one is absent from the carousel too
- The same book still appears in Library, in Search, and opens normally in the reader
- Re-include it → it returns to the hero, with all progress and sessions accrued while
  excluded intact (§11.2)
- Exclude every in-progress book → hero shows the designed empty state, never an excluded book
- Excluding a manga category removes those titles from all three manga Home cards
- Tests: `HomeViewModel` under a scope excluding the hero book; under a scope excluding all
  in-progress books; `becauseFinished` anchor selection skipping an excluded finished book
