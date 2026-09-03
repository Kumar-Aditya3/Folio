# Folio — Motion & Effects Spec (§13)

Companion to `FOLIO_IMPLEMENTATION_SPEC.md` (Rules 1–12) and `FOLIO_VISUAL_SPEC.md`
(Rules 13–18, §12). This file adds **Rule 19** and **§13**, and specifies five effects in
build order.

Baseline: v1.1.12 / commit `894b4c7`. Platform: **Android only** (Rule 1).
Compose resolved version: **1.7.6**. `minSdk` **24**, `targetSdk`/`compileSdk` **34**.

---

## 13.0 What this is not

Rejected 2026-09-02 after evaluation: **React-three-fiber, LiquidGlass.js, ShaderGradient**.
All three are React/JS libraries; Folio's UI is Kotlin Compose with no React, DOM, npm or
bundler anywhere in the tree. The only JS runtime is the reader's WebView/JCEF surface, and
`ChapterSanitizer` strips every `<script>` and `javascript:` URL before load as a security
boundary with tests pinning it. Widening that hole for decoration is not a trade worth making.
Every effect below is native Compose. Do not reintroduce a JS UI stack.

## 13.1 The Android blur constraint — read this before designing any glass

**Compose cannot blur a WebView.** This is already documented twice in the codebase and both
comments are correct:

- `Components.kt` → `glassPanel`: *"A panel cannot blur what is behind it on Android — the page
  is a separate native surface, so there is nothing to sample."*
- `OverlayUi.kt:41`: *"Near-opaque fill, no backdrop-filter blur: over an almost solid tint
  there is nothing left to blur."*

Consequence, and it is the opposite of intuition:

| Surface | Background | Real blur possible? |
|---|---|---|
| Home, Library, Stats, Book detail, More | Compose | **Yes** (API 31+) |
| Reader panels (Android) | WebView | **No** — nothing to sample |
| Reader overlays (desktop) | JCEF, HTML overlays | No — already near-opaque by choice |

So **do not** pursue Apple-style glass in the reader. Glass there is carried by rim, sheen,
shadow and motion — never by blur. Blur belongs on the five Compose-backed surfaces, which is
where §13.2 puts it.

## 13.2 Rule 19 — Every effect declares its API floor and its fallback

An effect that silently does nothing on a supported device is a bug. `minSdk` is 24, so any
effect above that floor ships with an explicit, *designed* degradation — not an absence.

| Capability | Floor | Fallback below floor |
|---|---|---|
| `Modifier.blur` | API 31 | Raise `glassPanel` fill alpha by 0.06 and keep the sheen |
| `RuntimeShader` / AGSL | API 33 | Static `Brush.linearGradient` from the same accent roles |
| `SharedTransitionLayout` | none (Compose 1.7.6, experimental API) | Standard fade — must still be opt-outable |
| Gradient / `graphicsLayer` / `drawWithCache` | none | n/a |

**Reduce-motion is mandatory, not optional.** Read
`Settings.Global.ANIMATOR_DURATION_SCALE`; when it is `0f`, every effect in §13 degrades to
its static form. Rule 6 requires animations be interruptible but says nothing about users who
asked for none. Put the check in one place:

```kotlin
// shared/src/androidMain/.../ui/theme/MotionCapabilities.kt
@Composable fun rememberMotionEnabled(): Boolean   // false when ANIMATOR_DURATION_SCALE == 0
@Composable fun rememberBlurSupported(): Boolean   // SDK_INT >= 31
@Composable fun rememberShaderSupported(): Boolean // SDK_INT >= 33
```

---

## 13.3 Effect 1 — Cover-derived hero accent

**Highest impact per line in this document.** Makes Home feel like *the book you are reading*
rather than like a theme. Promoted from §12.6's "optional, last" on the strength of that.

**What.** Sample one dominant colour from the current book's cover; use it to drive the Home
hero gradient (§12.4 item 1) in place of a flat `accentProgress` tint.

**Where.** New `shared/src/composeUi/.../ui/components/CoverAccent.kt`. `BookCover.kt` already
owns a decoded-cover cache (`coverCache`, cap 120) — reuse it; do not add a second decode path.

**How.**
1. Downsample the *already cached* `ImageBitmap` to ~32×32 before sampling. **Never re-decode
   the source file.** The `Canvas: trying to draw too large (216000000 bytes) bitmap` crash
   fixed in v1.0.24 came from exactly this class of mistake.
2. Bucket pixels into a coarse HSV histogram; take the modal bucket with saturation > 0.25 and
   value in 0.2..0.9 so paper-white and black-bar covers do not win.
3. Cache the result keyed by `coverPath` in a `Map<String, Color>` alongside `coverCache`.
4. **Contrast guard, non-negotiable:** if the sampled colour has < 4.5:1 against the palette's
   `surface`, blend it toward `accentProgress` until it passes. §12.3's role guarantees outrank
   the cover. A cover may tint the hero; it may never make the hero unreadable.
5. No cover, or sampling fails → `accentProgress`, silently.

**Setting.** `settings/appearance` toggle "Tint Home from book cover", **default on** (it is
contrast-guarded, so it is safe as a default). Off falls back to `accentProgress`.

**Cost.** One 32×32 sample per distinct cover, cached for the process. Effectively free.

**Acceptance criteria**
- Hero tint visibly tracks the book across three books with distinct cover palettes
- A pure-white cover and a pure-black cover both yield a legible hero (guard exercised)
- No decode of a source file happens for sampling — verified by adding no new
  `BitmapFactory`/`ImageIO` call site
- Toggle off → hero is pixel-identical to the `accentProgress` version
- Works in `LIGHT`, `midnightneon`, `rainbow` (§2.7)

**Shipped 1.1.19; sampling amended 1.1.20.** The "value in 0.2..0.9" window and flat modal
mean above did not survive contact with glow covers: a dark field with a bright bloom lost
every bloom pixel (v > 0.9), the modal bin's mean landed on the dark field, failed the 4.5:1
guard, and the hero washed into `accentProgress` — violating the first acceptance criterion.
Shipped sampling keeps the same filters minus the upper cap (`s > 0.25`, `v ≥ 0.2`) and
elects the modal bin by chroma salience — each qualifying pixel weighs `s·v²` — with the
bin's salience-weighted mean colour winning. Paper-white and black-bar covers still lose on
saturation/value alone; the bloom now speaks for the cover. The contrast guard (item 4) is
untouched. Regression: `glowingCoverSpeaksForTheCover` in `CoverAccentTest`.

---

## 13.4 Effect 2 — Drifting gradient mesh behind the hero

The honest Compose equivalent of what ShaderGradient sells, at **any** API level, scoped to
one surface instead of the whole screen.

**What.** Two or three large, low-alpha `Brush.radialGradient`s behind the Home hero only,
their centres drifting on slow independent cycles.

**Where.** `FolioHeroCard` (§12.4) via `Modifier.drawWithCache`, so it draws without
recomposing.

**How.**
- Colours: `accentProgress`, `accentDiscovery`, and the §13.3 cover accent when enabled
- Alpha per layer **≤ 0.18**; they must read as atmosphere, never as shapes
- Radii 0.8–1.4× the card's shorter edge, centres outside the bounds so no visible edge
- Motion: `rememberInfiniteTransition`, **18–30s per cycle**, different periods per layer so
  they never beat in sync. This is slow on purpose — anything faster looks like a loading state.
- `drawWithCache` + `graphicsLayer` so it is one drawing pass, not per-frame recomposition

**Reduce-motion / battery.** `rememberMotionEnabled() == false` → draw the same mesh with
static centres. Identical look, zero animation. Also freeze when the hero is not in the
viewport — do not animate offscreen.

**Cost budget.** Must not push Home below **60fps sustained** on the test device, and must not
measurably move idle battery draw. If it does, cut to two layers, then to static.

**Acceptance criteria**
- Home holds 60fps while scrolling with the mesh live (measure via
  `adb shell dumpsys gfxinfo com.folio.reader framestats`)
- With `ANIMATOR_DURATION_SCALE = 0` the mesh is static and still visible
- Mesh does not animate while Home is offscreen
- No banding on any of the three test themes
- Contained entirely to the hero — nothing bleeds into the goal strip

---

## 13.5 Effect 3 — Chart entry animation

Already implied by §12's Rule 15; specified here so it is testable. Cheapest polish in the
document and it makes Stats feel responsive rather than static.

**What.** Every bar, ring, sparkline and heatmap cell grows from zero on first composition.

**How.**
- `animateFloatAsState`, target `1f`, duration `FolioTokens.motionEmphasis` (320ms),
  `FastOutSlowInEasing`
- **Stagger bars by 40ms** left-to-right in `WeekBars` and the Stats 7-day chart. Simultaneous
  growth reads as a glitch; a sweep reads as intent.
- Rings animate sweep angle, bars animate height, sparklines animate path trim
- Animate **once per data identity**, not on every recomposition — key the animation on the
  data, or a progress update will re-trigger it mid-scroll
- Peak marker (Rule 15) appears **after** its bar finishes, +80ms

**Reduce-motion.** Render final state immediately. No fade substitute.

**Acceptance criteria**
- Entering Stats animates every chart once; scrolling away and back does not re-trigger
- A live progress update does not restart the week chart
- `ANIMATOR_DURATION_SCALE = 0` → charts render complete on first frame
- Peak cap never appears before its bar has finished growing

---

## 13.6 Effect 4 — Shared-element transition, library → book detail

The single biggest "feels premium" win available, and Compose 1.7.6 already ships the API.

**What.** The cover flies from its library grid cell into the book-detail header, growing as it
travels. Title and author cross-fade in behind it.

**Where.** `SharedTransitionLayout` wrapping the nav host, `sharedElement` on `BookCover` in
`LibraryGrid`/`LibraryList` and in `BookDetailHeader`.

**Scope — deliberately narrow.**
- library → book detail: **yes**
- Home hero → reader: **no**. The reader lands on a WebView that paints its own surface; a
  cover animating into it will look wrong (this is the `htmlSurfaceOccludesOverlays()` problem
  from the other direction).
- library → reader: **no**, same reason.
- manga library → manga detail: yes, **after** the books path is proven.

**How.**
- Shared key: `"cover-${book.id}"` — stable across both screens
- Duration `FolioTokens.motionEmphasis` (320ms), `FastOutSlowInEasing` in,
  `LinearOutSlowInEasing` out
- Must survive the transition being reversed mid-flight (Rule 6)
- `SharedTransitionLayout` is `@ExperimentalSharedTransitionApi` in 1.7.6 — opt in at one call
  site, not repo-wide

**Risks, stated up front.** This is the one effect here that touches navigation structure
(`FolioNavShell`/`FolioNavHost`), which §3 rebuilt at some cost. Land it **after** §12 Phase B,
never alongside a nav change. If back-navigation state or scroll restoration regresses (the §3
acceptance criteria), revert this effect rather than patching around it.

**Reduce-motion.** Standard fade, no shared element.

**Acceptance criteria**
- Cover animates both directions; reversing mid-flight leaves no stuck or duplicated cover
- `NavigationTest` still green — back returns to the right route with scroll intact after
  `am kill`
- A book with no cover art (generated fallback) transitions without flicker
- `ANIMATOR_DURATION_SCALE = 0` → plain fade
- No shared-element attempt on any reader route

---

## 13.7 Effect 5 — AGSL liquid glass (API 33+, last)

The only real path to refraction and specular response. Deliberately **last**: it is the least
portable thing in this document.

**What.** A `RuntimeShader` producing a refractive, light-responsive glass fill for hero cards
and panel rims.

**Reality check on reach.** API 33+ only. The test device runs Android 16 so it will look
excellent to *you* and to nobody on an older handset. That asymmetry is why this is fifth and
not first. `minSdk` is 24 — the fallback is not an edge case, it is the majority path for some
users.

**Where.** `shared/src/androidMain/.../ui/theme/GlassShader.kt`, consumed by `FolioHeroCard`
through an optional modifier. **`glassPanel` itself must not change** — every existing card
keeps its current appearance (§12.6's reversibility constraint).

**How.**
- AGSL shader: subtle normal-map distortion of the layer beneath + a specular highlight tracked
  to a fixed virtual light
- Driven by `graphicsLayer { renderEffect = ... }`
- Uniforms from the accent roles, never hardcoded colour (Rule 2 still applies inside shaders)
- **Only where the backdrop is Compose** (§13.1). Never in the reader.

**Fallback (API < 33).** The §13.4 static gradient mesh plus the existing sheen and rim. Must
look intentional, not degraded — a user on API 30 should not be able to tell something is
missing.

**Acceptance criteria**
- Renders on API 33+; API 24–32 shows the fallback with no blank area and no log spam
- `glassPanel`'s output for standard cards is unchanged (pixel diff against pre-change)
- Never instantiated on a reader route
- Costs no more than 2ms/frame on the test device
- Contrast of text over the shader still ≥ 4.5:1 in all three test themes

---

## 13.8 Explicitly rejected

- **Full-screen animated shader backgrounds.** Battery cost, and they fight the 28 palettes
  instead of expressing them.
- **Parallax on every card.** Noise, not interest. §13.4 is scoped to the hero for this reason.
- **Any motion during reading.** The reader is the one place motion is a pure liability. The
  §5.3 end-of-chapter chip is the sole permitted exception and it auto-dismisses.
- **Blur behind body text.** Legibility loss for decorative gain.
- **Scroll-linked parallax on Home — for now.** Home is ~1.2 screens; scroll effects need travel
  to read as intentional. Revisit after §11.4's manga cards and Discover make Home 2–3 screens.
  The near-term substitute is §13.9.

## 13.9 Hero collapse on scroll (the answer to "animated Home scrolling")

Wanted: an animated scrolling experience on Home. Blocked by: Home being too short for parallax.
Resolution: animate the **hero collapse** instead of the page.

As Home scrolls, the hero cover shrinks toward the top bar and its gradient bleeds upward into
it — a collapsing-toolbar pattern. Needs no additional page height, and makes a short page feel
deliberate rather than truncated.

- Driven by `LazyListState.firstVisibleItemScrollOffset`, not a nested-scroll connection
- Cover scales `1.0` → `0.55`, gradient alpha `0.30` → `0.12`, over the first 160dp of scroll
- Title migrates into the top bar as `titleMedium` at full collapse
- Never fully hides the cover — it collapses to a thumbnail, so the anchor is never lost
- Reduce-motion: no collapse; the hero stays full size and simply scrolls away

**Acceptance criteria**
- Collapse tracks the finger 1:1, with no spring or settle
- Reversing scroll direction mid-collapse tracks back with no jump
- With one card on screen (no scroll possible) the hero renders full size and nothing twitches
- `ANIMATOR_DURATION_SCALE = 0` → no collapse

---

## 13.10 Phasing

| Phase | Effect | Depends on | Version |
|---|---|---|---|
| **M1** | Rule 19 capability helpers (`MotionCapabilities.kt`), reduce-motion plumbing | nothing | shipped 1.1.19 |
| **M2** | §13.5 chart entry animation | M1 | shipped 1.1.19 |
| **M3** | §13.3 cover-derived hero accent | M1, §12 Phase B (hero exists) | shipped 1.1.19 |
| **M4** | §13.4 drifting gradient mesh | M3 | shipped 1.1.19 |
| **M5** | §13.9 hero collapse on scroll | §12 Phase B | shipped 1.1.19 |
| **M6** | §13.6 shared-element transition | §12 Phase B complete, no in-flight nav work | 1.3.x |
| **M7** | §13.7 AGSL liquid glass | M4 (fallback must exist first) | 1.4.x |

M1–M5 shipped together in v1.1.19 (versionCode 50, commit 0903a64): entry sweeps keyed
on data-window dates with `rememberSaveable` played-flags, peak caps starting strictly
after their own bar completes; cover accent sampled from the already-cached bitmap with
a contrast guard whose floor extends past the fallback to the better pure extreme
(always ≥5.6:1); mesh drawn in `onDrawBehind` so no per-frame recomposition; collapse
tracked 1:1 in the first 160dp with the title migrating to the top bar at full collapse
and the hero tint bleeding upward via a 220ms alpha animation. Desktop gates green
(201/0/0, incl. 7 new §13 tests); the device verification items above (gfxinfo,
three-theme, Rule 11 smoke, reduce-motion) run on reconnect.

M1 first, always: every later effect reads its capability flags, and doing it last means
retrofitting reduce-motion into five call sites.

**Verification for every §13 phase** (in addition to main-spec §10 and §12.7):
- Both motion states: normal, and `ANIMATOR_DURATION_SCALE = 0`
- `adb shell dumpsys gfxinfo com.folio.reader framestats` — no regression in janky-frame
  percentage on Home or Stats
- Three-theme check per §2.7
- Rule 11 device smoke test
- For M6 only: `NavigationTest` green, including process-death scroll restoration
