# Home balance and color direction

## Home: retain the composition, reduce the offset

The 1.2.115 candidate keeps the 1.2.114 structure: left-aligned title, an offset
front cover, both upcoming covers on the upper trailing side, and progress/turn
controls beneath that stack. The centered, bilateral proposal was discarded
before building or installing it.

- Front cover offset changes from -20% to -13% of the stage width. At a typical
  353dp stage that moves the front cover about 25dp inward.
- The upcoming stack has less horizontal spread and slightly larger covers.
- Front-cover resting lean changes from -3 to -1.5 degrees.
- Cover width changes from 52% to 50%, capped at 176dp. The progress column uses
  30% of the stage (minimum 96dp), with a 36sp rather than 42sp percentage.
- Large text retains additional stage height. Turn buttons keep 48dp targets.
- Resume resolves the currently selected item when tapped, including mid-turn.

Existing gesture physics, artwork blending, glass rendering, theme colors and
reader behavior are retained. No new color palette is included in this change.

## Verification

- 12 focused checks across RevolvingShelfTest, ReadingDeskTest and
  ReadingDeskPreviewTest passed with no failures or errors.
- Offscreen Compose previews reviewed at 320/393px in light/dark, including
  1.4x font scale and a two-cover narrow-screen case. These are not phone captures
  or hardware performance measurements.
- Test evidence: outputs/home-balance-test-verification.json.
- Signed Android release 1.2.115, code 169: BUILD SUCCESSFUL in 13m 17s.
- All three APK signatures verified against the existing release certificate.
  ARM64 saved as releases/Folio-1.2.115-arm64.apk. Other variants remain in the
  Gradle output directory.
- ARM64 installed through ADB and installed package version verified. The app was
  not launched or navigated, preserving the install-only preference.
- Evidence: outputs/home-balance-release.log, home-balance-release-verification.json
  and home-balance-device-install.json.

## Color discussion: proposals only

The study keeps one composition to compare color meaning. It does not depict a
new app layout or commit any palette to the native implementation. Sample cover
art, titles, passage and source-update counts are illustrative.

The user clarified that the collection should retain nine theme families, each
with paired light/dark variants. These are sample families within that structure,
not proposals to replace the collection with one global palette. The checked
source currently registers seven built-in pairs in ThemePack.ALL; preserve stable
IDs and resolve the difference before any palette migration.

Three example family directions:

- Petrol + mineral: graphite/petrol foundations; celadon for interaction, amber
  for time/progress, lilac for saved meaning, cool blue for incoming content.
- Copper + ink: warm ink foundations; copper interaction, ice-blue progress,
  plum saved meaning and mineral-green incoming content.
- Ash + afterimage: graphite/ash foundations; iris interaction, celadon progress,
  mauve saved meaning and pale-blue incoming content.

Common rules worth deciding before implementation:

- Functional roles, hierarchy and placement are stable across Home, Library,
  statistics and reader chrome. Each theme authors its own role hues. An action
  can be celadon in Forest, copper in Paper and neutral ink in Classic; the active
  destination uses that same interaction role. Routes do not each get unrelated
  signature colors. Classic retains a genuinely achromatic material treatment.
- Covers keep their own colors and a limited atmospheric contribution. Their
  art does not redefine action or status meaning.
- Glass body remains mostly neutral; a silver edge describes the material and
  local semantic color indicates interaction. Saved highlights have their own
  role without flooding entire sheets with purple.
- Destructive/error states keep a distinct coral/red role. Labels, icons and
  selected shapes continue to carry meaning alongside hue.
- Vividness can change atmosphere/art contribution while functional foregrounds
  retain readable contrast. User-selected reading page colors remain independent.
- Dark and light appearances need separately tuned foreground colors, not a
  single hex code reused on both backgrounds.
- Material tint and temperature distinguish families: neutral smoked glass,
  warm copper/vellum, mineral green, deep-sea blue, ivory/garnet, opal/rose, and
  graphite/chartreuse. Geometry, glass behavior and motion remain one coherent
  Folio system.

The interactive color study was checked at 320/736px in light/dark. All three
choices update locally, and each has independently selectable light/dark variants.
Cover art retains its colors, there is no horizontal
overflow, and no script errors were reported. Evidence:
outputs/home-color-study-verification.json.

## Build storage

The generated merged-native-library cache was moved intact to
C:/Users/kumar/.cache/folio-build-storage/androidApp-merged-native-libs. A directory
junction preserves D:/gbuild/androidApp/intermediates/merged_native_libs for
Gradle. No source, existing release or user data was deleted.
