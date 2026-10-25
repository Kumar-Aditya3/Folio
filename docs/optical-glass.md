# Folio optical glass

Implemented 20 October 2026 with Haze 2.0.1.

## What changes on screen

Rounded floating surfaces now use a single material that refracts and diffuses
the actual captured Compose backdrop. The navigation capsule and menus keep a
shallow frost with an 8dp optical displacement; larger panels keep Haze Regular's
size-responsive diffusion so background detail does not compete with their text.
Press and hover change the material lighting at the interaction position.

The material is dyed with the surface's existing theme colour. The old flat
surface paint, painted bevel, decorative film and room-only lens are omitted
when this optical pass runs. This matters visually: an opaque coat on top of a
new renderer would hide the upgrade, and two refracted/painted contours would
produce a second border inside the panel.

Full-width mastheads retain progressive diffusion. In-flow surfaces retain their
lighter treatment and existing minimal-glass preference. Embedded surfaces do
not add an independent optical layer. Existing transparency settings still
control the material, including fully solid surfaces at maximum opacity.
For captured optical glass this opacity is carried by the material tint. The
reproduced backdrop is drawn at full strength: fading the whole reproduction
would blend the original sharp background back in and hide the frost.

## How quality adapts

The published 2.0.1 source uses fixed performance profiles. Its `Adaptive` symbol
is a deprecated alias, despite the announcement's earlier description. Folio
therefore supplies the policy explicitly:

| Budget | Resting quality | Scrolling quality |
| --- | ---: | ---: |
| Small controls / bars | 0.75 | 0.4875 |
| Larger panes | 0.45 | 0.2925 |
| Minimum device budget | 0.20 | 0.13 |

These are Haze quality fractions, not pixel-resolution scales or measured FPS
gains. Lower quality is requested while scrolling consumes movement. Detail
returns after 180ms without consumed scroll. Wheel ticks and fling tails share
that dwell, preventing repeated quality changes between events. The observer
returns zero consumed offset and does not intercept scrolling. Only effect
nodes read the moving flag; scroll coordinates do not recompose whole screens.

Source selection retains Folio's room/content hierarchy, and stale retained
pixels are cleared when input becomes unavailable during navigation. The
experimental Android native-backdrop path remains disabled: source selection
must continue to respect Folio's layer and outgoing-screen rules.

## Integration and replacement

All Haze imports remain in `Glass.kt`. `FolioGlassWorkload.kt` owns the independent
scroll-quality policy. Both hosts now provide the shared material environment;
the desktop host also registers its theme field as the room source.

The custom `GlassLens` expect/actual implementation and Android shader were
removed. Haze now owns capture alignment, renderer lifecycle and refraction
instead of the app reading captured graphics layers from Haze internals.

The required compatible toolchain is Kotlin 2.4.20, Compose Multiplatform 1.12.1,
Material 3 1.9.0, Skiko 0.150.1, AGP 9.2.1 and Gradle 9.4.1. Android compiles
against API 37; its minimum and target SDK settings are retained. The existing
shared Android source layout temporarily uses AGP's legacy DSL compatibility
flags; conversion to the new Android KMP plugin is a separate build-layout task.

## Limits and verification

Android keeps its existing API-32 blur floor and low-RAM fallback. Unprovided
preview trees and the glass-disabled preference retain painted surfaces. Haze
interaction motion follows the app's reduced-motion decision. Native EPUB
WebView/JCEF page pixels are not captured by this Compose source pipeline.

`FolioOpticalGlassTest` renders the same scene with painted and optical materials,
saves both images, requires substantial changed pixels inside the panel, and
checks an outside pixel stays unchanged. It also checks finite quality bounds,
lower scrolling budgets and restoration of the resting policy. The existing
glass capability, travel and transparency tests cover the surrounding contracts.
The render test additionally requires a book-spine edge to spread across pixels
through the glass; changing only its tint cannot pass.
Setting `FOLIO_GLASS_WINDOW_CAPTURE=1` runs the same comparison in a real desktop
window and captures its rendered output; ordinary CI uses offscreen rendering.

The native Direct3D comparison and all 30 focused checks passed. Inspected captures
are saved in `outputs/optical-glass-dark.png`, `outputs/optical-glass-light.png` and
`outputs/painted-glass-before.png`.

Physical-device frame time and interaction feel still need measurement. The
quality values are deliberately explicit so that tuning follows those results.

## Release artifact

Android release 1.2.94 (version code 148) built successfully with R8 optimization.
The ARM64, ARMv7 and universal APKs in `releases/` all pass `apksigner verify` and
match the signing certificate of the previous release. Versions, file sizes and
SHA-256 hashes are recorded in `outputs/haze2-release-verification.json`.
The release build log is `outputs/haze2-release.log`.

### Android startup regression in 1.2.94

Device logcat showed `InitializationProvider` failing while WorkManager created
its Room database, before the activity started. WorkManager 2.9.1 brings Room
2.5.0, whose class-only consumer keep rule does not explicitly retain the
no-argument constructor under the new optimizer. The release R8 usage report
listed `WorkDatabase_Impl.<init>()` as removed.

The app now explicitly keeps no-argument constructors of `RoomDatabase`
subclasses. The release minification task also checks that the generated
`WorkDatabase_Impl` keeps its name and constructor, failing the build if either
disappears. This preserves the existing database and WorkManager configuration.
See Android's [constructor retention guidance](https://developer.android.com/topic/performance/app-optimization/full-mode#retain-the-default-constructor).

Release 1.2.95 (version code 149) passes that guard and builds successfully with
R8. Its generated DEX also contains the public `WorkDatabase_Impl()` constructor.
All three signed APKs are in `releases/`; hashes and certificates are recorded in
`outputs/launch-fix-release-verification.json`. The build log is
`outputs/launch-fix-release.log`. The device subsequently confirmed that this
release starts, but logcat exposed the same constructor omission in ML Kit's
Firebase component registrars. WorkManager input-merger constructors were also
listed as stripped and use reflection when background work runs.

Release 1.2.96 (version code 150) adds explicit constructor retention for Firebase
component registrars and WorkManager input mergers. Its release guard covers
all seven reflected types. All three APK signatures pass verification; details
are in `outputs/startup-stability-release-verification.json`. The ARM64 APK was
installed with `adb install -r`, preserving data, and passed three cold launches
with a stable process for ten seconds each. The fresh app log contains no fatal
errors or invalid component registrar warnings. Results are recorded in
`outputs/startup-stability-device-verification.json`. OCR and translation
operations themselves were not exercised in this startup check.

### Visual acceptance

The isolated render test verifies diffusion and refraction, not an improvement
to the real app's design. The user reports little visible change on Android.
Most in-flow cards still use diffusion over the room background; optical
refraction is limited to actual rounded overlays. Their layout, hierarchy and
existing opacity settings were retained. A visible improvement needs comparison
of actual app screens on the device. Android frame-time improvements have not
been measured.

## Sources

- [Haze 2 announcement](https://chrisbanes.me/posts/haze-2.0.0/)
- [Published 2.0.1 quality policy](https://github.com/chrisbanes/haze/blob/2.0.1/haze/src/commonMain/kotlin/dev/chrisbanes/haze/HazePerformanceMode.kt)
- [Haze migration guide](https://github.com/chrisbanes/haze/blob/2.0.1/docs/migrating-2.0.md)
- [Haze glass guide](https://github.com/chrisbanes/haze/blob/2.0.1/docs/effects/glass.md)
