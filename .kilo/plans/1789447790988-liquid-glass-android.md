# Liquid Glass — Android-only, phased implementation plan

Second plan in this repo. **Precondition: run after `1789447790988-known-issues-fixes.md` completes** — its Task 4 reshapes the nav capsule (hit targets, `imePadding`) and its Task 2 turns the PDF reader into a pure-Compose strip, which this plan builds on.

## Scope & interpretation (agreed)

- **Android only**: glass effects ship on the Android app. The shared `composeUi` module must keep compiling for desktop and desktop keeps today's visuals exactly (gate via capability flag, not forks).
- **Material upgrade, not surface sprawl**: glass is upgraded *once* in the shared material functions so every eligible surface inherits it. The spec's law holds: glass = surfaces that sit **over** content (bars, capsule, sheets, banner). In-flow content (cards, stats wells, cover plates, chips, reader page block) stays paper/sunken.
- **Out of scope**: Material3 `AlertDialog`/`DropdownMenu` containers (20+ call sites; they carry their own scrim and forking M3 is a refactor without payoff — revisit later if wanted). True refraction via **hand-rolled** means — it stays a Phase 6 stretch gated on AGSL, not a Phase 1 requirement.
- **Evaluated and deferred** (don't re-litigate during implementation): API 34 non-linear font scaling for the reader's WebView CSS px (real accessibility gap — worth a small separate task); splash screen API (`core-splashscreen`); API 35 PdfRenderer v2 with mipmap rendering (needs compileSdk 35 + AGP ≥ 8.6; the repo is deliberately pinned at 34/8.5.0). API 32 offers nothing applicable.

## Gradle disclaimer (environment quirk — read before running anything)

**Every Gradle invocation must carry the stop flag inside the command itself: `.\gradlew --no-daemon <tasks>`.** On this machine a bare `.\gradlew <tasks>` leaves the daemon holding the invocation and the command runs indefinitely — it never returns to the agent. Never run Gradle without `--no-daemon`. If a daemon is already stuck from an earlier run, kill it with `.\gradlew --stop` first, then continue with `--no-daemon` commands. All commands in this plan are written accordingly.

## Platform facts driving the design

| Fact | Consequence |
|---|---|
| minSdk 24, compileSdk 34 (`androidApp/build.gradle.kts:19,23`) | Real blur (`RenderEffect`) only on API 31+; API 24–30 must fall back to today's rim/sheen look |
| EPUB reader page is an Android `WebView` — native surface | No Compose blur (Haze or otherwise) can sample through it. Reader chrome over EPUBs = specular/noise only, at any API level |
| Manga reader + (post-Task-2) PDF continuous are pure Compose | **Real** backdrop glass possible there |
| Existing system: `folioVeil` (`FolioSurfaces.kt:305`), `FolioSurfaceOpacity` 4-knob preferences, `FolioAtmosphere` (`rimLight/rimShade/veilFill/barGlass/hairline`), `FolioDaylight` virtual sun, `folioPressable` (:447), §15 "glass not lid" tests in `DesignSystemTest` | Build on all of it; do not replace it |

## Degradation ladder (the core contract)

One pure resolver decides what a surface gets:

| Condition | Result |
|---|---|
| API ≥ 31, pref on, Compose backdrop available | Blur + tint + noise + specular (full liquid glass) |
| API < 31, or pref off, or low-RAM device | Today's `folioVeil` look, byte-for-byte (rim + sheen + hairline + fill) |
| Any API, surface floats over the WebView (EPUB reader chrome) | Specular + noise upgrade only; fill alpha governed by the existing `readerChrome` knob |
| Desktop | Capability flag off → today's look, no code fork |

(Phase 6 adds one more rung — API ≥ 33 + blur on + perf headroom → + edge refraction — attempted last, optional.)

## Phase 0 — Capability scaffolding + Haze spike (decision gate)

1. Add **Haze** (`dev.chrisbanes.haze:haze`) pinned to the newest version that resolves against Compose Multiplatform 1.7.3 / Kotlin 2.1.20 (expect 1.2.x; check compatibility — 1.3+ targets Compose 1.8). Add to `shared/build.gradle.kts` (composeUi source set; Haze is multiplatform so desktop still compiles).
2. **Spike**: one throwaway screen — `hazeSource` on a LazyColumn, `hazeEffect` on an overlay capsule — verified on an API 33 emulator and an API 29 emulator (expect tint-only fallback). If version resolution fails or the fallback is visually broken, **stop and fall back to plan B**: specular/noise-only material (no new dependency) — note it in the plan file and continue; Phases 1–4 are written so blur is always an additive layer.
3. Capability resolver, expect/actual:
   - `shared/composeUi`: `@Immutable data class GlassCapabilities(val blur: Boolean, val noise: Boolean, val specular: Boolean)` + `LocalGlassCapabilities`.
   - Android actual (`androidApp` provides it at the activity root): `blur = SDK_INT >= 31 && !ActivityManager.isLowRamDevice && settings.liquidGlassEffects`, `specular/noise = true`.
   - Desktop actual: all the same except `blur = false` (desktop keeps current visuals per scope).
4. Backdrop plumbing: `LocalGlassBackdrop` (`HazeState?`, default null). Screens set it by wrapping their scrolling content; `folioVeil` reads it. Pattern precedent: `LocalFolioBarInset`. Desktop never provides it → null → no-op.
5. New user preference `liquidGlassEffects: Boolean = true` in global `ReaderSettings` (+ migration-safe default), wired into `SettingsPanelAppearance.kt` next to the existing glass sliders. Keep `FolioSurfaceOpacity` untouched (its knobs are fill alphas; the doc comment forbids overloading it).
6. Spec: add the glass rule to `FOLIO_IMPLEMENTATION_SPEC.md` — "glass is the material of the overlay layer only" + the degradation table above. Extend `DesignSystemTest` §15: blur must never change fill alphas (the "glass not lid" window assertions stay valid with blur on and off).

## Phase 1 — `folioVeil` v2: the material

All in `shared/src/composeUi/kotlin/com/folio/reader/ui/components/FolioSurfaces.kt` unless noted. Every existing call site inherits; signature stays backward-compatible via a new optional `glass: GlassSpec = GlassSpec.Default` param.

1. **Blur layer** (when `capabilities.blur && backdrop != null`): `hazeEffect(backdrop, style)` with `HazeStyle(blurRadius = 20.dp, tint = atmos.veilFill-based, backgroundNoise = …)`. Blur is *additive under* the existing fill: the user's opacity knob still governs the fill alpha on top — at 100% the surface is a lid and blur is invisible; at the designed glass points the blur reads. Cap blur radius at 28dp anywhere in the app.
2. **Noise**: Haze's `backgroundNoise` for blurred surfaces; for non-blurred glass (API < 31, reader chrome) a deterministic grain drawn in `drawWithCache` (seeded LCG precedent: `CoreRandom` in `StatsCoreSample.kt`) at ~2–3% alpha to kill gradient banding.
3. **Specular 2.0**: replace `folioVeil`'s fixed vertical sheen (:324–329) with a directional gradient driven by `FolioDaylight.azimuth` — the highlight tracks the virtual sun the atmosphere already renders (see `daylightGradient` usage in `folioSunken`, :280). Draw-phase only (`drawWithCache`/`graphicsLayer`), honoring the §13.3 no-recomposition rule. Same treatment for the masthead's crown sheen (`FolioChrome.kt:215–220`).
4. **Edge bevel (lensing approximation)**: stacked 1dp treatments — outer `atmos.hairline` border (existing) + a light-facing inner rim arc (brighter on the sun side via the daylight gradient, `rimLight` on light palettes / stronger emission on dark). Pills (`FolioShapes.pill`) get a slightly brighter arc along the top-light edge.
5. **Regression guard**: with `blur=false` / `backdrop=null` the modifier chain must render today's look — the capability resolver is pure and unit-testable (see Phase 4 tests).

## Phase 2 — Surface rollout (Android)

Ordered by visibility; each is a small PR-able slice.

1. **Nav capsule** (`androidApp/.../nav/FolioNavShell.kt`): `LocalGlassBackdrop` provided from the shell's content Box so the page scrolling beneath the capsule is the blur source; capsule's `folioVeil` picks up Phase 1 automatically. Runs *after* known-issues Task 4 lands (it edits the same file). The gradient fade Box (:121–132) stays pass-through.
2. **Masthead** (`FolioChrome.kt`, `FolioTopBar`): today's masthead paints its own glass in `drawBehind` (:178–249). Add blur only while `fill.presence > 0.01f` (content actually passing underneath — same condition as the fill). Progressive blur if the pinned Haze version supports it; otherwise fixed radius gated on presence. Keep the crown sheen + hairline rule; add daylight-directional specular. Each screen with a masthead must provide the backdrop source on its scrolling child (HomeScreen, LibraryScreen tabs, Statistics tab, quote browser, revisit, tags, manga screens — grep `rememberFolioHeaderState`).
3. **Sheets**: `TagEditSheet`, `ReaderSettingsPanel`, settings panels (`SettingsPanel*`), quick-settings — wherever they call `folioVeil`; ensure the host screen provides `LocalGlassBackdrop`.
4. **Status banner** (`Components.kt:494` `FolioStatusBanner`): it floats over the capsule and content — give it the veil treatment.
5. Explicitly **not** glassed: content cards, `FolioSectionCard`, stats sunken wells, cover plates, chips, heatmap hero, M3 dialogs/menus (see Out of scope).

## Phase 3 — Liquid motion

All gated by `rememberMotionEnabled()` (§13.3/§13.9 precedent). Springs with slight overshoot (`dampingRatio ≈ 0.7–0.8`) — liquid, not sloppy.

1. **Press response** — upgrade `folioPressable` (`FolioSurfaces.kt:447`): scale (existing) + corner-radius softening while pressed + a specular flash (the rim brightens ~1.5× during press, decaying on release). Only for glass surfaces; plain cards keep today's scale-only press.
2. **Shape morphs**: capsule → sheet expansion for quick settings (animated corner-radius interpolation between `FolioShapes.pill` and sheet shapes — animate a float and construct `RoundedCornerShape(corner)` per frame in the draw phase).
3. **Elastic drag-to-dismiss** on sheets (swipe-down with rubber-band resistance and one small overshoot on settle).
4. **Predictive back drives the glass (API 33+)**: set `android:enableOnBackInvokedCallback` in the manifest and adopt the androidx.activity back-progress APIs (`OnBackAnimationCallback`) — back-swipe progress animates the chrome (masthead rail folding away, capsule receding) through the same graphicsLayer phases; nav-compose 2.8.2 (bumped by the known-issues plan) supplies the in-app predicted transitions. Below API 33 the opt-in is inert. Verify the reader's WebView back history still behaves.
5. **Haptic primitives (API 31+)**: a low-amplitude `VibrationEffect.Composition` primitive (`PRIMITIVE_TICK`) on press-settle of glass surfaces — nav capsule and sheet handles only, nowhere else (app-wide press haptics would be noise). `LocalHapticFeedback` fallback below 31.
6. Specular sweep stays static (daylight-driven); no pointer-chasing highlight in this pass — motion sickness + perf risk, and the sun already moves it across the day.

## Phase 4 — Readers + performance + validation

1. **EPUB reader chrome** (`ReaderScreen.kt` bars/rail/TOC/quick settings over the WebView): specular + noise only (ladder row 3). The existing `readerVeilAlpha` knob keeps governing fill. Do **not** attempt WebView snapshot fake blur (stale frames, jank — rejected in review).
2. **Manga reader chrome** (`MangaReaderChrome.kt` — pure Compose pages): full glass via a `LocalGlassBackdrop` on the pager.
3. **PDF reader chrome** (post-Task-2 Compose strip): same as manga.
4. **Performance hardening**:
   - Blur only on surfaces with real overlap (masthead gated on `presence`, capsule always, sheets while visible).
   - Validate scroll frame cost on a low-end device/emulator with image-heavy Library grid; if janky, restrict blur to nav capsule + masthead (bounded strips) and leave sheets at specular-only — record the decision.
   - Low-RAM auto-off already in the resolver (Phase 0).
5. **Tests**:
   - New `GlassCapabilityTest` (desktop, pure): the degradation matrix — capability × backdrop × pref → expected effect set; `folioVeil` with `blur=false` renders the legacy modifier chain.
   - `DesignSystemTest` §15 additions from Phase 0 pass with blur on and off.
   - Settings round-trip test for `liquidGlassEffects` (pattern: `SettingsMergeTest`).
   - Manual matrix: API 24 & 29 emulators (fallback identical to today), API 31+ (blur), API 33+ (predictive back progress; AGSL if Phase 6 is attempted), low-RAM emulator, pref off, reduce-motion on, all 26 fixed app palettes + the System palette light+dark, EPUB vs manga vs PDF readers, keyboard open (capsule `imePadding` from the previous plan still working).

## Phase 5 — Material You "System" palette (API 31+)

Independent of the glass work; folded here so the session gets one document.

1. New **"System"** app palette: on API 31+, built from `dynamicLightColorScheme`/`dynamicDarkColorScheme`; shown as the first card in the theme picker (`SettingsPanelThemeCards.kt`), hidden below 31. Persisted as a normal `appThemeId` value; selecting it on a device that later drops below 31 falls back to the default pack.
2. **No raw pass-through**: derive `FolioColors` + `FolioAtmosphere` from the dynamic scheme through the same contrast-enforcing derivation `CustomAppPalette.toFolioColors()` uses, so the `ThemeSchemeTest` invariants (accent WCAG ≥ 4.5, onSurface ≥ 7:1, pairwise-distinct accents, distinct chartSeries) hold for wallpaper-derived colors. The derivation is a pure function `ColorScheme → FolioColors` — that purity is what makes it testable and what protects the design law.
3. Live updates: recompute on configuration change (wallpaper changes broadcast a config change); both faces (light/dark) flow through the existing pack mechanism so `flipThemeMode` keeps working. App palette only — reader themes are a separate system and stay untouched.
4. Tests: `ThemeSchemeTest` gains a case for the derivation fed a synthetic `ColorScheme` (pure, desktop-testable); the dynamic-scheme fetch itself is the only platform bit and stays thin.

## Phase 6 — AGSL true refraction (API 33+, stretch — attempt only if Phases 0–2 landed with perf headroom)

1. The lensing Phase 1 approximates becomes real: an AGSL shader (edge UV distortion + subtle chromatic split at the rim) applied via `RenderEffect.createRuntimeShaderEffect` → `asComposeRenderEffect()` in the draw phase, with the backdrop snapshot supplied by the same `GraphicsLayer` record mechanism Haze uses.
2. Capability resolver gains `refraction = blur && SDK_INT >= 33 && !lowRam`; desktop false; honors the same `liquidGlassEffects` preference.
3. **Capsule first, capsule only**: bounded surface, bounded cost. Evaluate on a mid-tier device before considering sheets/masthead. If it janks or shows artifacts — ship nothing; the Phase 1 bevel approximation stands.

## Risks

- **Haze ↔ Compose 1.7.3 version friction** — Phase 0 spike is the gate; plan B (no new dep, specular/noise only) is viable and explicitly allowed.
- **Haze cannot blur through `AndroidView`** (WebView) — by design; the ladder handles it.
- **Perf on minSdk-era hardware** — degradation ladder + Phase 4 measurement; blur is always removable per-surface without touching callers.
- **Desktop regression** — capability gate + `LocalGlassBackdrop` stays null on desktop; `:shared` desktop compile is part of every phase's validation.
- **Dynamic color vs design invariants** — raw Material You schemes violate the `ThemeSchemeTest` contrast/hue floors; the mitigation is structural (derive through the contrast-enforcing path, never pass through), and the test enforces it.
- **AGSL refraction is advanced graphics work** with real jank potential — bounded to the capsule, attempted last, with ship-nothing as the success criterion for failure.
- **Spec drift** — Phase 0 writes the rule down first so later phases have a law to obey, not just a look.

## Validation (per phase)

- `.\gradlew --no-daemon :shared:desktopTest` (capability resolver, §15 tests, settings round-trip).
- `.\gradlew --no-daemon :androidApp:assembleDebug` every phase; `.\gradlew --no-daemon :shared:compileKotlinDesktop` every phase.
- Manual: the Phase 4 matrix, repeated at Phase 2 and 3 checkpoints with the surfaces added so far.

## Implementation record (2026-09-15)

Phases 0–5 are implemented; Phase 6 (AGSL refraction) was **not attempted** — its
own success criterion is measured perf headroom on a mid-tier device, which cannot
be established without one, and ship-nothing was the designed outcome of that check.

Deviations from the plan, each with its reason:

- **Blur floor is API 32, not 31.** Haze 1.2.2 itself disables API 31 by default
  for observed RenderNode invalidation issues; a stale blur frame is worse than
  the clean fallback, so 31 renders the ladder's specular+grain row. The plan's
  "check compatibility" precondition is what surfaced this.
- **Version spike resolved by metadata, not emulator.** Haze 1.2.2's Gradle module
  metadata declares org.jetbrains.compose.ui:ui → 1.7.3 exactly (the newest
  release that does); 1.3+ declare Compose 1.8. Plan B was not needed.
- **One seam, tightened.** §14.3.1 asked for two files importing Haze; the
  implementation needs exactly one (ui/components/Glass.kt) because the masthead
  and veil route through olioGlassEffect, and the activity root through
  FolioGlassRoot, so no Haze type crosses a module boundary at all.
- **Press corner-softening omitted.** GraphicsLayer.shape (the draw-phase
  mechanism it needed) is a Compose 1.8 API; the pinned 1.7.3 exposes it as a val.
  It was a no-op for the only current caller (the pill) anyway; the rim flash,
  settle tick and scale shipped.
- **Capsule→sheet morph ships as the sheet-enter morph** the reader drawers
  already had a shape for (26dp sweep entering from a capsule read), plus elastic
  drag-to-dismiss; there is no capsule-that-expands-into-a-sheet surface in the
  app to morph, and inventing one was out of scope.
- **GestureCancellationException does not exist in activity 1.9.2** — predictive
  back cancellation arrives as a plain CancellationException, which is what the
  chrome recede resets on.
- **Sheets got olioSheetDragToDismiss**; there is no TagEditSheet in the
  codebase (the plan named it speculatively). The three reader drawers are the
  end-edge sheets that exist.
- **On-device matrix (Phase 4 item 5) not run** — no emulator/device in this
  session. Compile + unit tests + the §14.1 metadata check are the validation
  that ran; the device pass remains a precondition for release.

The spec rule (glass is the material of the overlay layer only) + the ladder
live in docs/FOLIO_IMPLEMENTATION_SPEC.md §16.
