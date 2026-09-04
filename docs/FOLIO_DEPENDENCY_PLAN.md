# Folio — Dependency & Integration Plan (§14)

Third companion to `FOLIO_IMPLEMENTATION_SPEC.md` (Rules 1–12),
`FOLIO_VISUAL_SPEC.md` (Rules 13–18, 20, §12) and `FOLIO_MOTION_SPEC.md` (Rule 19, §13).

Baseline: v1.1.19 / commit `9e7ac25`. Platform: **Android only** (Rule 1).
Current toolchain, read from the build files — every decision below is constrained by these:

| | Folio | Notes |
|---|---|---|
| Kotlin | 2.1.20 | |
| Compose Multiplatform | 1.7.3 | resolves to Jetpack Compose **1.7.6** |
| AGP / Gradle | 8.5.0 / 9.6 | |
| compileSdk / targetSdk | 34 / 34 | |
| **minSdk** | **24** | the binding constraint for every visual effect |
| JDK in use | 25 | already breaks `packageReleaseMsi` (ProGuard 7.2.2 cannot parse class v69) |

---

## 14.0 Verdicts

Three things were proposed. One is adoptable, two are not.

| Proposal | Verdict | Reason |
|---|---|---|
| **Haze** (`dev.chrisbanes.haze`) | **Adopt, narrowly** | Real published library, Apache-2.0, Compose Multiplatform-native, does the one thing Folio cannot do itself. §14.2 |
| **UItrends** (`faizhussain7/UItrends`) | **Reject as dependency; mine for patterns** | Showcase app, no Maven coordinate. Requires AGP 9.2 / Gradle 9.4 / compileSdk 37 / **minSdk 28**. Third-party stack includes AGPL components. Its headline feature is a CameraX + C++/NCNN vision engine — irrelevant to an EPUB reader. §14.4 |
| **Why-Not-Compose** (`ImaginativeShohag/Why-Not-Compose`) | **Reject as dependency; excellent reference** | Apache-2.0 cookbook, but a multi-module playground (`app`, `base`, `common-ui-compose`, `cms`, `exoplayer`, `tictactoe`) built on Hilt and its own theme system. Adding it imports an architecture, not a component. §14.4 |

### Rule 21 — A dependency must be consumable, licence-clear, and reach minSdk 24
Before any third-party UI code enters Folio it must satisfy all four:
1. **Published artifact** with a Maven coordinate and a pinned version (Rule 12's pinning rule).
2. **Licence compatible and recorded** — Apache-2.0 / MIT / BSD. Anything AGPL, or any dependency
   whose own transitive licences are unaudited, is rejected.
3. **Functions at minSdk 24**, either natively or with a designed fallback (Rule 19).
4. **Cannot be replaced by ~100 lines of Compose** using tokens already in `FolioTokens`.

Copying *patterns* from a readable repo is always allowed with attribution; adding a **project**
as a dependency is not the same act and does not get the same answer.

---

## 14.1 The version problem — read before touching `libs.versions.toml`

Haze tracks Compose Multiplatform closely, and **its latest releases are far ahead of Folio**:

| Haze | Built against | Usable on Folio's CMP 1.7.3? |
|---|---|---|
| 2.0.0-beta02 | Kotlin 2.3.20, CMP 1.12.0 | **No** |
| 1.7.3 | Kotlin 2.3.20, CMP 1.12.0 | **No** |
| 1.7.0 | Kotlin 2.2.20, CMP 1.9.3 / Jetpack 1.9.4 | **No** |
| 1.6.x | CMP 1.8.x era | **Unverified — must be checked** |
| ~1.3.x–1.5.x | CMP 1.7.x era | **Most likely the compatible band** |

**Do not simply add the newest version.** Compose runtime/compiler metadata mismatches surface as
`NoSuchMethodError` or `AbstractMethodError` at runtime, not at compile time — the same class of
failure as the zstd SIGABRT: invisible in a debug smoke test, fatal in the wild.

**Required first step (§14.5 Phase D0).** Resolve the newest Haze whose declared Compose dependency
is **1.7.x**, add it, and verify:
1. `:androidApp:compileReleaseKotlin` clean
2. `:shared:desktopTest` still 148+ green
3. Rule 11 device smoke test on a **release** build, including a source browse
4. No Compose version bump appears in `./gradlew :androidApp:dependencies` output

If no Haze release targets CMP 1.7.x, **stop**. Do not upgrade Compose to accommodate a blur
library. Folio's Compose version is load-bearing for 28 palettes, `ThemeSchemeTest`, and a reader
built on `AnnotatedString` paragraph styles; and AGP 8.5 + JDK 25 already constrains the desktop
release path. In that case implement §14.3's own-content fallback instead — it needs no dependency.

## 14.2 What Haze actually buys, and where it is useless

**Buys:** `Modifier.blur` cannot sample *other* composables' pixels — it blurs only the content it
is applied to. Haze introduces a source/effect split (`hazeSource` on the scrolling content,
`hazeEffect`/`hazeBlur` on the overlay) which is genuinely not reproducible in ~100 lines, so it
passes Rule 21 clause 4.

**Android reach, from Haze's own platform docs — this matters at minSdk 24:**

| API | Behaviour |
|---|---|
| 33+ | Optimal, `RenderEffect` |
| 31–32 | Works, with progressive-blur workarounds that can cost performance |
| ≤30 | **Blur disabled by default → translucent scrim.** RenderScript blurring exists from Haze 1.6 but is *opt-in and explicitly experimental*, runs on a background thread, is always ≥1 frame behind, and drops frames under load |

So on Folio's floor (API 24) Haze degrades to a scrim — which is essentially what `glassPanel`
already draws. **Do not enable the experimental RenderScript path.** Rule 19's fallback table stands:
below 31, raise the panel fill alpha and keep the sheen.

**Useless for:** the reader. Haze's docs confirm the constraint the codebase already documents
twice — it can only blur pixels drawn in the same Compose graphics layer as `hazeSource`. Their
CameraX guidance (`PreviewView` must be `ImplementationMode.COMPATIBLE`; a `SurfaceView` cannot be
captured) is the same limitation Folio hits with WebView/JCEF. **§13.1 is unchanged: no blur in
the reader, on any API, with or without Haze.**

### Where Haze may be used
Only these five surfaces, all Compose-backed:
`home`, `library`, `stats`, `book detail`, `more` — plus modal sheets and dialogs above them.

### Where it must never appear
- Any reader route (`reader/*`, `mangaReader/*`) — nothing to sample
- Desktop (`shared/src/desktopMain`, `desktopApp`) — Rule 1; desktop overlays are HTML and already
  near-opaque by choice
- Behind body text — Rule 19's legibility ban

## 14.3 Integration architecture

The danger with a blur library is that `hazeSource`/`hazeEffect` calls scatter across every screen
and Folio ends up unable to remove it. **One seam, one owner.**

### 14.3.1 The seam

```
shared/src/androidMain/kotlin/com/folio/reader/ui/components/
  FolioBlurScope.kt        // expect/actual-free: Android-only, provides a blur source handle
  FolioBlurredSurface.kt   // the ONLY composable that calls Haze
```

- `FolioBlurScope` wraps a screen's scrollable content and marks it as the blur source.
- `FolioBlurredSurface` is the single Haze call site. Everything else — `FolioSectionCard`,
  `FolioHeroCard`, sheets, dialogs — takes an optional blur handle and forwards it.
- **No feature file imports `dev.chrisbanes.haze`.** One import, in one file.

**Benchmark:** `Select-String -Pattern 'dev\.chrisbanes\.haze'` returns hits in exactly two files
plus `libs.versions.toml` and `androidApp/build.gradle.kts`.

### 14.3.2 Capability routing

`FolioBlurredSurface` picks its path from §13.2's existing helpers — no new capability logic:

| Condition | Path |
|---|---|
| API ≥ 31 **and** `rememberMotionEnabled()` | Haze blur, `HazeBlurStyle` tinted from the surface's semantic accent role (Rule 14) |
| API ≥ 31, reduce-motion on | Haze blur with a **static** style — blur is not motion, so it stays |
| API ≤ 30 | Existing `glassPanel`, fill alpha +0.06 (Rule 19's table) |
| Haze absent (§14.1 stop) | Existing `glassPanel`, unchanged |

The last row matters: the architecture must build and look correct **with the dependency removed**.
That is the escape hatch, and it is a hard requirement, not a nicety.

### 14.3.3 Token and role integration

Rule 2 and Rule 14 still bind. Haze must not introduce raw colour:
- Blur radius comes from a new `FolioTokens.blurRadius = 20.dp` — never a literal
- Tint comes from `FolioTheme.colors.*`, chosen semantically per surface (`accentProgress` for the
  hero, `accentAnnotation` for a quote sheet, and so on)
- Any palette-level correction happens in `Theme.kt` only (§12.6's reversibility constraint)
- **Contrast survives blur:** text over a blurred surface must still clear 4.5:1. Blurred content is
  lower-contrast than a flat fill, so `ThemeSchemeTest`'s guarantees are necessary but not
  sufficient — this needs a visual check per §2.7's three themes.

### 14.3.4 Where it actually improves Folio

Ranked, and deliberately short:
1. **Modal sheets and dialogs** over Home/Library/Stats — the classic case, and the one Haze is
   unambiguously best at. Delete confirmations, the settings-scope sheet, exclusion pickers.
2. **The nav bar** — blurring content behind the four-item bar as it scrolls under.
3. **Collapsing hero top bar** (§13.9) — as the hero collapses, the top bar blurs what passes
   beneath it, which is exactly the effect the collapse is reaching for.
4. **Nothing else.** Cards do not need blur; `glassPanel`'s rim and sheen already carry them.

## 14.4 Mining the two showcase repos (no dependency)

Both licences permit copying with attribution. Record attribution in a new
`THIRD_PARTY_NOTICES.md` at the repo root when any of this lands.

**From UItrends (MIT):**
- `ModalBackdrop.kt` — the blur-scrim pattern for sheets/dialogs; the shape of §14.3.4 item 1
- `DismissFocusOnScrollAndImeEffect.kt` — a real bug fix Folio does not have: search fields keep
  focus when the list scrolls
- `CatalogColorMath.kt` — adaptive hero/chip colour maths, comparable to §13.3's cover accent;
  worth reading for its contrast handling
- **Ignore entirely:** `pretext_geometry`, the CameraX/NCNN/LiteRT vision path, alternate app icons.
  The AGPL entries in its notices file sit in that stack.

**From Why-Not-Compose (Apache-2.0):**
- Animation recipes for §13.5's chart entry work
- Bottom-sheet and tooltip compositions
- Its `common-ui-compose` module as a *reference* for how to structure a shared UI module — Folio's
  `composeUi` already does this, so read for contrast, not for code

## 14.5 Phasing

| Phase | Contents | Gate to proceed | Version |
|---|---|---|---|
| **D0 — Compatibility spike** | Resolve the newest Haze targeting CMP 1.7.x. Add to `libs.versions.toml` + `androidApp` only. No UI change. | All four §14.1 checks pass. **If none targets 1.7.x, abandon §14 and keep `glassPanel`.** | 1.1.x |
| **D1 — The seam** | `FolioBlurScope` + `FolioBlurredSurface`, capability routing, `FolioTokens.blurRadius`. Zero screens changed. | Builds and passes with the dependency both present and removed | 1.2.x |
| **D2 — Sheets and dialogs** | §14.3.4 item 1 — every modal over a Compose-backed screen | Contrast check on three themes; 60fps on the test device | 1.2.x |
| **D3 — Nav bar + collapsing top bar** | §14.3.4 items 2–3, tied to §13.9 | `NavigationTest` green | 1.3.x |
| **D4 — Pattern mining** | §14.4 items, each rewritten onto tokens; `THIRD_PARTY_NOTICES.md` added | Rule 2 scan clean on every copied file | 1.3.x |

D0 is a spike whose acceptable outcome is **"no"**. That is the point of running it first and
separately — the cost of discovering incompatibility is one throwaway commit, not a half-migrated
blur system.

## 14.6 Verification for every §14 phase

In addition to main-spec §10, §12.7 and §13.10:
- `:androidApp:compileReleaseKotlin` clean — **release**, because that is where R8 and metadata
  mismatches bite (Rule 12)
- `./gradlew :androidApp:dependencies | Select-String 'compose'` shows **no** version change
- `:shared:desktopTest` ≥ 148 green
- `:desktopApp:compileKotlin` clean and desktop UI unchanged (Rule 1 — Haze must not have reached it)
- APK size delta recorded; Haze is small, but the number goes in the commit message
- Rule 11 device smoke test on a release build, launch **and** source browse
- `adb shell dumpsys gfxinfo com.folio.reader framestats` — no janky-frame regression on any screen
  that gained blur
- Manual check on API 24 behaviour (emulator is acceptable here): scrim fallback renders, no blank
  surface, no log spam
- Three-theme check per §2.7, specifically confirming text contrast **over blurred content**

## 14.7 What this plan deliberately refuses

- **Upgrading Compose to fit a blur library.** The dependency serves the app.
- **The experimental RenderScript path** for API ≤ 30. Background-thread blur that is always a frame
  behind is worse than a clean scrim.
- **Adding UItrends or Why-Not-Compose as project dependencies.** Neither publishes an artifact;
  both would import an architecture.
- **Any JS UI stack** — §13.0's rejection of React-three-fiber / LiquidGlass.js / ShaderGradient
  stands and is not reopened by this document.
- **Blur in the reader.** Not a policy choice — technically impossible, on any API, with or without
  Haze (§13.1).
- **A second card component.** Blur is a parameter on the existing surfaces (Rule 3), never a new
  `FolioBlurCard`.
