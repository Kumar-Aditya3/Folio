# Folio visual modernization

Researched 20 October 2026. Recommendations below distinguish existing capabilities,
changes implemented in this pass, and future migrations.

Follow-up: the Haze 2 migration is now implemented; see
[Folio optical glass](optical-glass.md) for the visible material changes, explicit
scroll-quality policy, dependency requirements and verification. Published 2.0.1
uses fixed profiles, so the announcement's automatic adaptation description is
superseded by the published API and Folio's own scroll-aware policy.

## Direction

Treat Folio as a contemporary private library: expressive book jackets, editorial
type, restrained moving light, and controls with physical continuity. Let the book
be the most visually prominent object. Reserve refractive glass for floating
controls; use quieter materials behind reading and dense lists.

This is a design recommendation based on Folio's existing implementation, not a
claim that a particular visual style is universally better.

## Techniques and technologies evaluated

| Area | Research | Fit and decision for Folio |
| --- | --- | --- |
| Expressive motion | [Material motion](https://m3.material.io/styles/motion/overview/how-it-works) and [Compose spring interruption](https://developer.android.com/develop/ui/compose/animation/customize) | Use springs for interaction feedback and interruption continuity. Folio already has centralized spring/timing profiles; retain that system and its reduced-motion controls. Do not add a competing motion engine. |
| Modern glass | [Haze 2 announcement](https://chrisbanes.me/posts/haze-2.0.0/) and [performance guidance](https://chrisbanes.github.io/haze/latest/performance/) | Strong migration candidate: explicit blur/glass effects, adaptive sampling, and improved capture lifecycle could replace portions of custom glass plumbing. Current project pins Haze 1.2.2 to Compose 1.7.3. Treat replacement as a coordinated dependency/API migration, with Android and desktop validation. The author's sample benchmarks are not Folio measurements. |
| Progressive blur | [Haze progressive blur and masking](https://chrisbanes.me/posts/haze-1.0/) | Keep at chrome boundaries, not every shelf card. Folio already requests the performance-oriented progressive path. Measure total affected pixels and moving background cost before adding more. |
| Procedural materials | [Compose cached drawing](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | Implemented bookcloth fallback covers: six dark dyes, ivory lettering, inset foil, woven lines and spine depth. Cached geometry avoids bitmap assets and permanent animation. Full title/author now reach layout instead of being cut to 24 characters. |
| Shader lighting | [Android RuntimeShader/AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl) | Existing platform shader infrastructure is already the right foundation. Keep Android capability checks and desktop equivalents. Adding another animated shader library would duplicate infrastructure. |
| Typographic identity | [Compose fonts and variable axes](https://developer.android.com/develop/ui/compose/text/fonts) | Existing Fraunces/Manrope and optical-size display styles are valuable. Retain optical sizing and tabular statistics. Avoid continuously animating font metrics, which can move line breaks. Improved fallback-cover lettering in this pass. |
| Spatial continuity | [Shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements) | Existing cover-to-reader transitions should remain the signature motion. Text and images require different treatment; avoid scaling whole text-heavy screens. Existing shelf dissolve is appropriate. |
| Image arrival | [Deferred state reads](https://developer.android.com/develop/ui/compose/performance/bestpractices) | Implemented synchronous cache seeding for mounted book covers: cached covers appear immediately instead of showing a skeleton and another reveal. Newly decoded covers retain their draw-phase fade. |
| Rendering cost | [drawWithCache and transforms](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | Implemented cached shimmer and hero gradient brushes; translate their coordinate system instead of allocating new brushes at each animation tick. Motion preferences continue to govern their clocks. |
| Toolkit refresh | [Compose Multiplatform 1.10 changes](https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html) and [compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) | Evaluate newer Compose and Material together with Haze. Availability in AndroidX alone does not establish compatibility with this shared codebase. Do not replace version pins without testing navigation, text, graphics and JCEF interop. |
| Cold-start smoothness | [Baseline Profiles](https://developer.android.com/develop/ui/compose/performance/baseline-profiles) | Add an app-specific profile generated from real home → library → reader journeys in a dedicated performance pass. Compose's library profile is useful but does not cover all Folio code. No unmeasured hand-written profile introduced. |

## What to pursue next, in order

1. Measure library scroll, returning to home, opening a book, showing reader
   controls and dragging a sheet in a release-like build. Compare frame-time
   percentiles with identical content, refresh rate and device conditions.
2. Prototype Haze 2 alongside a compatible Compose/Kotlin update. Compare one
   navigation capsule and one reader panel before replacing the shared material
   pipeline. Check older Android fallback, desktop clipping, nested overlays,
   transparency preferences and reduced motion.
3. Generate Baseline Profiles from those journeys; compare cold and warm starts.
4. Tune motion only where recordings show a problem: interruption, delayed
   response, excessive overshoot or blurred text. Avoid global duration changes
   without observing their effect.

## Acceptance checks

- Generated covers remain readable at compact and large sizes, with long titles,
  non-Latin text, missing authors, and suppressed text during shared transitions.
- Loaded images returning from cache do not flash skeletons.
- Shimmer movement has continuous wrap and retains a static reduced-motion state.
- Hero lighting retains its original paths and opacity after brush caching.
- Verify Android and desktop compilation. Device frame-rate improvement remains
  unproven until the above measurements are recorded.

The implemented pass does not upgrade Compose/Haze or claim an FPS gain. It
improves cover identity and removes specific avoidable rendering/loading work.

## Verification recorded

Android and desktop shared UI compilation passed using Android Studio's JDK 21.
The focused desktop run passed 16 tests: cover contrast (1), Compose preview
rendering (1), shimmer geometry/material checks (7), and entry motion (7).
The generated cover contact sheet was visually inspected, including long-title
wrapping and Japanese text, and saved to `outputs/visual-refresh-covers.png`.
Build logs are in `outputs/visual-refresh-build.log` and
`outputs/visual-refresh-tests.log`. No physical-device frame measurements were run.
