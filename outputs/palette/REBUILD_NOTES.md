# Folio theme rebuild — working notes

Autonomous task brief. User left mid-task; finish without questions.

## User's instructions (verbatim intent)

1. "literally remove all hex values and remake them urself based off of the names" — current palettes are ugly and must not survive.
2. "one dark and one light for all" — 10 themes x {light, dark} = 20 faces.
3. "each should be a palette, so like different colors per theme not different versions of a color per theme" — each theme is a genuine multi-hue palette, and the 10 themes occupy DIFFERENT hue territories. No 10 shades of blue.
4. "give each an animation from mirage ... unique animation for unique theme ... I WANT UNIQUE ANIMATION FROM MIRAGE PER PALETTE" — 10 themes => 10 DIFFERENT Mirage shaders, each chosen to fit that theme's aesthetic, uniforms tuned, fed that theme's palette.
5. "in themes section in settings asw the current palettes do not reflect what the themes actually look like" — Settings > Themes cards must preview what the theme really renders, incl. its animation character.
6. "use coloraide to verify the palettes and harmony" — Python `coloraide`: contrast, dE separation, hue harmony.
7. "THE CURRENT THEME TESTS ARE USELESS, IGNORE THEM" — delete/rewrite the theme tests; do not let their invariants dictate the design.
8. "PICK THE ANIMATION ITSELF ACCORDING TO THE AESTHETIC PER THEME UR GOING FOR AND MAKE IT GOOD"

## Project facts

- `D:\projects\Folio` — Kotlin Multiplatform Compose (Android + Desktop/Skiko).
- Compose pinned **1.7.3** (DO NOT BUMP — Haze 1.2.2 alignment). Kotlin 2.1.20, AGP 8.5.0, minSdk 24, compileSdk 34, JVM 17.
- Gradle output dir is `D:\gbuild`. Release APKs land in `D:\gbuild\androidApp\outputs\apk\release`.
- Source sets under `shared\src\`:
  - `commonMain\kotlin\com\folio\reader\` — no Compose
  - `commonJvm\kotlin\...` (custom, dependsOn commonMain)
  - `composeUi\kotlin\...` (custom, dependsOn commonJvm) — ALL shared Compose UI incl. `ui\theme\` (33 files)
  - `androidMain\`, `desktopMain\` — both dependsOn(commonJvm)+dependsOn(composeUi); expect/actual shader pairs
  - `desktopTest\` — the whole test suite (`commonTest` is declared in gradle but the dir does not exist)
- App modules: `androidApp\src\main\java\com\folio\reader\`, `desktopApp\src\main\java\com\folio\reader\`.
- Shared module MUST compile for both `androidTarget` and `jvm("desktop")`. Android-only libs go in `androidMain` with a `desktopMain` no-op actual.

## Theme registries

### AppPalette — 20 entries
`shared\src\composeUi\kotlin\com\folio\reader\ui\theme\Theme.kt:246-307`
ids: `light dark warm midnight silver dusk arctic espresso sakura ember honey peacock moss graphite dawn blossom lagoon abyss rouge garnet`
Legacy id remap table `Theme.kt:280-302`. `byId()` `:304`.

### ThemePack — the user-facing theme (10 packs, each = light face + dark face)
`Theme.kt:315-342`, `ThemePack.ALL` at `:329-340`.

| pack id | light name | dark name | light appPalette | dark appPalette | light reader | dark reader |
|---|---|---|---|---|---|---|
| gallery | Gallery | Dark | light | dark | white | dark |
| manuscript | Manuscript | Espresso | warm | espresso | sepia | espresso |
| silver | Silver | Graphite | silver | graphite | gray | graphite |
| arctic | Arctic | Midnight | arctic | midnight | arctic | dark |
| sakura | Sakura | Blossom | sakura | blossom | paper | blossom |
| honey | Honey | Ember | honey | ember | sepia | ember |
| moss | Moss | Peacock | moss | peacock | moss | peacock |
| dawn | Dawn | Dusk | dawn | dusk | dawn | dusk |
| lagoon | Lagoon | Abyss | lagoon | abyss | lagoon | abyss |
| rouge | Rouge | Garnet | rouge | garnet | rouge | garnet |

### FolioArtDirection — THE PRIMARY HEX TABLE TO REPLACE
`shared\src\composeUi\kotlin\com\folio\reader\ui\theme\FolioArtDirection.kt`
- type / 25 role fields `:34-88`
- `FOLIO_ART_DIRECTIONS` `:201-462` = 20 faces x 25 `Long` hexes = **500 constants**
- faces keyed by art-direction family ids: `ocean nebula forest terracotta arctic solar jade mintcoral moonstone system`, each `light=true`/`light=false`
- per-face line ranges: ocean-L 203-214, ocean-D 216-227, nebula-L 229-240, nebula-D 242-253, forest-L 255-266, forest-D 268-279, terracotta-L 281-292, terracotta-D 294-305, arctic-L 307-318, arctic-D 320-331, solar-L 333-344, solar-D 346-357, jade-L 359-370, jade-D 372-383, mintcoral-L 385-396, mintcoral-D 398-409, moonstone-L 411-422, moonstone-D 424-435, system-L 437-448, system-D 450-461
- 25 roles: `backgroundBase backgroundSecondary surface surfaceElevated primaryAccent secondaryAccent tertiaryAccent warmAccent guillochePrimary guillocheSecondary guillocheHighlight glowPrimary glowSecondary progress progressTrack textPrimary textSecondary textTertiary border borderHighlight navigationSelected accentProgress accentStreak accentDiscovery accentAnnotation`
- helpers: `mixA` `:91-96`, `onFillA` `:99-100` (dark ink `0xFF14110C`), error constants `:132-135`, `toFolioColors()` `:110-176`, `folioArtDirection(id, light)` `:465-466`

### Theme.kt other hexes
- `:25-28` `chartSeriesDefault` (8 hexes)
- `:30-146` `FolioColors` constructor defaults (24 hexes) — vestigial per the file's own comment at `:62-63`
- `:174-243` builds all 20 `AppPalette` colors as top-level vals

### Reader page themes (separate axis)
`shared\src\commonMain\kotlin\com\folio\reader\settings\ReaderThemes.kt:7-610` — 45 presets x 18 `Int` hexes = **810 constants**. Type at `ReaderSettings.kt:436-461`. Drives WebView CSS (`ui\render\ReaderCss.kt`) and the theme card's page block.
The 20 presets referenced by ThemePack faces must be re-authored: `white dark sepia espresso gray graphite arctic paper blossom ember moss peacock dawn dusk lagoon abyss rouge garnet`.

## Light/dark + ColorScheme pipeline

No `lightColorScheme`/`darkColorScheme` anywhere; the `ColorScheme` is hand-built once.

- Android: `androidApp\...\MainActivity.kt:312` `AppPalette.byId(appThemeId)`; `:319-322` colors; `:323` `appDark = customAppTheme?.isDark ?: appPalette.isDark`; `:330-334` packId resolved via `ThemePack.ALL` (fallback `"gallery"`); `:386-395` status-bar icon inversion; `:404-410` `FolioTheme.AppTheme(...)`.
- Desktop: `desktopApp\...\Main.kt:1158-1172` (identical; desktop paints NO animated field).
- No `isSystemInDarkTheme()` follow. `flipThemeMode(settings)` `Theme.kt:355-368`.
- `Theme.kt:703-750` `FolioTheme.MaterialTheme`; `:725` `deepenInkRoles(colors).withLegibleAccents()` (`FolioInk.kt:51`, `Theme.kt:800-805`); `:740` `ink.toColorScheme()`; `:807-846` the single hand-built `ColorScheme`; CompositionLocals `:726-736` (`LocalFolioColors`, `LocalFolioTypography`, `LocalFolioSurfaceOpacity`, `LocalFolioDaylight`) declared `:484-485`; `AppTheme` `:758-780`.
- Derived atmosphere (real source for backgrounds/glass): `ui\theme\FolioAtmosphere.kt` — type `:34-159`, pools built `:1185-1188` from `glowPrimary`/`glowSecondary`/`warmAccent` then harmonised, returned `:1212-1218`, `FolioTheme.atmosphere` accessor `:1495-1500`.

## Background animation system (current state)

- Live root: `MainActivity.kt:533-543` — `Box.fillMaxSize().onSizeChanged{...}.folioSilkBackground().folioLamp(folioLamp)`. `LocalFolioPackId` provided `MainActivity.kt:505-506` / `:516`.
- `Modifier.folioSilkBackground()`: `shared\src\composeUi\...\ui\theme\SilkField.kt:208-278`. Reads `FolioTheme.atmosphere.fieldTop/fieldBottom/isDark/pools`, `rememberMotionEnabled()` `:213`; if `folioFieldSupported()` (`:221`, Android 33+) -> `folioFieldKindFor(LocalFolioPackId.current)` `:222`, `pools.map{it.vivid(1.5f)}` `:226`, `folioField(kind, top, bottom, meshPools, poolStrength = if(dark) 0.52f else 0.46f, isDark, animate)` `:227-235`. Fallback `:241-277` = vertical ramp + 3 drifting radial blooms (periods 37s/53s/43s). Helpers `vivid()` `:285`, `driftPhase()` `:299`, `drawBloom()` `:317`.
- `FolioFieldKind` enum (10 families): `ui\theme\FolioFieldKind.kt:11-32`. `folioFieldKindFor(packId)` `:39-51` maps `ocean->MeshGradient nebula->NeuroNoise forest->Voronoi terracotta->GrainWave arctic->Swirl solar->GodRays jade->SmokeRing mintcoral->ColorPanels moonstone->PerlinNoise system->Metaballs else->Metaballs`.

### KNOWN BUG TO FIX
`LocalFolioPackId` carries **ThemePack** ids (`gallery manuscript silver arctic sakura honey moss dawn lagoon rouge`) but `folioFieldKindFor` keys on **art-direction family** ids (`ocean nebula forest ...`). Only `arctic` intersects, so **9 of 10 themes currently fall through to `else -> Metaballs`**. `desktopTest\...\FolioFieldKindTest.kt:14-41` passes only because it calls the function with family ids directly. This is exactly why "unique animation per theme" does not happen today. The new design must key on the id `MainActivity` actually provides, and be verified end-to-end.

By contrast `FOLIO_LIGHTS` (`FolioLight.kt:68-83`) and `PACK_GEOMETRY` (`GuillocheModel.kt:232-255`) ARE correctly keyed on pack ids. `silkVibranceFor` (`SilkSpec.kt:30-41`) is keyed on AppPalette ids but is unreachable.

### Android AGSL actuals
`shared\src\androidMain\kotlin\com\folio\reader\ui\theme\FolioField.android.kt` (352 lines): `actual folioField` `:31-44`, `folioFieldSupported` `:46-47` (SDK>=33), `folioFieldShader` `:49-98` (`RuntimeShader(FIELD_COMMON + mainFor(kind))`, per-vsync clock `:64-71`, uniforms `:83-90`, `groundScale = if(isDark) 0.55f else 0.96f` `:81`), `mainFor` `:100-112`, `FIELD_COMMON` `:116-157` (uniforms `size u_time top bottom pool0..2 poolCount poolStrength isDark` + `hash11 hash21 hash22 rot vnoise fbm poolCycle groundRamp`), 9 family mains `:159-352` (`METABALLS_MAIN NEURO_MAIN VORONOI_MAIN GRAINWAVE_MAIN SWIRL_MAIN GODRAYS_MAIN SMOKERING_MAIN COLORPANELS_MAIN PERLIN_MAIN`). `MeshGradient` delegates to `MeshField.android.kt:34` / `MESH_FIELD_AGSL :107`. Desktop no-ops `MeshField.desktop.kt:14,31`.

### Other shader plumbing
- DEAD (no call sites): `folioGuilloche` (`GuillocheShader.kt:53` + `GUILLOCHE_AGSL`), `folioAmbientShader` (`AmbientShader.kt:40` + `AMBIENT_AGSL`), `folioSilk` + `SilkSpec.kt` + `SilkModel.kt`, `deriveSystemPalette`/`rememberDynamicSchemes`.
- LIVE: `folioLiquidGlass` (`HomeScreen.kt:571`, `Components.kt:184`, `FolioChrome.kt:371`, `FolioSurfaces.kt:532`; `LIQUID_GLASS_AGSL`/`LIQUID_GEL_AGSL` in `GlassShader.android.kt`).
- `guillocheSpecFor` MUST STAY — the glass gel consumes it at `GlassShader.android.kt:177`, `:204`.
- NAME COLLISION: `FolioSurfaces.kt:644-699` has an unrelated static `folioField(ambient, daylight, strength)` used by `SettingsPanelAppearance.kt:117` and `ChallengeWebViewActivity.kt:85`.
- Per-theme params: `FolioLight.kt:33-58` type, `FOLIO_LIGHTS :68-83`, `folioLightFor :92-97`, `LocalFolioLight :105`. `GuillocheModel.kt`: `PACK_GEOMETRY :232-255`, `GuillocheGeometry.character :201-221`, `GUILLOCHE_ALPHA_MAX :296`, `guillocheLineColor :849-885`, `guillocheSpecFor :463`.
- Motion gate: `MotionCapabilities.kt:12` `rememberMotionEnabled` (android actual reads system animator scale; desktop -> true); `:18` shader-support gate. `LocalFolioPackId` declared `GuillocheQuiet.kt:91`.
- There are NO `.agsl`/`.glsl`/`.sksl` asset files — all shader source is Kotlin `private const val` raw strings.
- Mirage is NOT yet a dependency (zero `mirage`/`androidpoet` in any gradle file). The Mirage MATH was hand-ported into local AGSL with Apache-2.0 attribution in `NOTICE:33-34`; see also `MeshField.android.kt:23-24`, `FolioField.android.kt:19`, `FolioFieldKind.kt:6-7`, `MeshField.kt:67`, `SilkField.kt:215`.

## Settings > Themes section

Route: `androidApp\...\settings\SettingsHubScreen.kt:149-155` -> `SettingsThemesScreen.kt:14-26` -> `GeneralSettingsPanel`.

Body: `shared\src\composeUi\...\ui\settings\SettingsPanelGeneral.kt:40-141`. `:50-51` modeDark; `:68` "Theme packs" header; `:80-84` `ThemeModeToggle(flipThemeMode)`; `:95` `packs = ThemePack.ALL`; `:100-109` selection + auto-scroll; `:110-140` LazyRow -> `:120-139` `ThemePackCard(pack, dark = modeDark, selected, onClick, previewColors = null)`; `:125-136` tap sets `appThemeId = pack.appPaletteId(modeDark)`, `themeId = pack.readerThemeId(modeDark)`, clears custom themes.

`ThemePackCard`: `shared\src\composeUi\...\ui\settings\SettingsPanelThemeCards.kt:50-159`. 148dp wide `:76`, `FolioShapes.inset` `:65`, `folioRaised(accent = colors.primary)` when selected else `folioPanel` `:78-88`. Sources `:61-73` — `spec = previewColors ?: AppPalette.byId(pack.appPaletteId(dark)).colors` `:62-63`, `page = Theme.getPreset(pack.readerThemeId(dark))` `:64`. Draws only 6 colors: chrome band `spec.background` `:95`, 9dp dot `spec.primary` `:100-104`, title line `spec.onBackground@0.35` `:105-110`, page fill `page.background` `:116`, heading bar `page.headingText` `:120-125`, body lines `page.secondaryText@0.75/@0.55` `:126-137`; name `colors.primary`/`onSurface` `:141-157`; selected rim `colors.primary` `:83`.

Three defects to fix (this is the "palettes do not reflect what the themes actually look like" complaint):
1. only ONE accent shown, so all palettes look identical
2. no animation character at all
3. reads the RAW `AppPalette.colors` BEFORE `deepenInkRoles().withLegibleAccents()`, so it previews the authored palette, not the rendered one

Siblings: `ThemeModeToggle :171-187`; `FontThemeCard :194-235`; `AppearanceMockup SettingsPanelAppearance.kt:88-157` (+`MockShelfRow :165 MockTopBar :196 MockContentCard :259 MockReaderSheet :306 MockNavCapsule :384`); `CustomThemeSettingsPanel :656-752`, `ThemeRole :622-629`, `RoleRow :755-804`, `ColorEditor :811-860`, `SeedChip :863-896`.

## Other color constants (leave unless they fight the new palettes)
`ReaderSettings.kt:413-423` CustomAppTheme defaults (6); `SystemPalette.kt:64` (2); `CustomAppPalette.kt:34,39` (3); `FolioDaylight.kt:125,129,133` (3); `Annotations.kt:44-51` HighlightColor (8); `TagEditSheet.kt:53-64` (12); `BookCover.kt:268-273` (12); `FolioLogo.kt:37,40,43` (3); `SyncIndicator.kt:42`; `PageBlock.kt:285`; `ReaderScreen.kt:752`; `MangaReaderChrome.kt:221`; `FolioNavShell.kt:516`; `ReaderCss.kt:210,354` (4).

## Tests declared USELESS — delete or rewrite, do not let them dictate palettes
`shared\src\desktopTest\...`: `ThemeSchemeTest.kt` (registry cross-checks, dE>=10, AA/7:1 floors, achromaticByDesign, synthetic Material-You schemes `:403-425`), `DesignSystemTest.kt` (`progressFillSeparatesFromItsTrack`, `everyPaletteShimmersVisibly`, `materialsAreVisuallyDistinguishable`, `paperPlateCarriesTheThemesChroma`, `ambientPoolsAreNotOneHue`; hexes `:331-334,:389,:445`), `GuillocheTest.kt`, `FolioLampTest.kt`, `InkDeepeningTest.kt`, `ui\theme\FolioFieldKindTest.kt`, `CoverAccentTest.kt`, `FolioThemeRimTest.kt`, `FolioCoverBorrowTest.kt`, `PageBlockTest.kt`, `ui\render\ReaderCssThemeTest.kt`; plus `androidApp\src\androidTest\java\com\folio\reader\ThemeLegibilityTest.kt`.
Keep every NON-theme test green.

## Gradle
- Catalog `gradle\libs.versions.toml` (versions `:1-53`, libraries `:55-139`, plugins `:141-149`; kotlin `:2`, agp `:3`, composeMultiplatform 1.7.3 `:4` DO NOT BUMP, haze 1.2.2 `:33`).
- `settings.gradle.kts` repos `:9-17` (google, mavenCentral, jitpack), modules `:shared :androidApp :desktopApp` `:21-23`.
- `shared\build.gradle.kts`: plugins `:3-8`, targets `:18-28`, deps commonMain `:31-38` / commonJvm `:41-50` / composeUi `:53-67` (Compose MP as `api(...)`, haze `implementation` not `api` `:62-65`) / androidMain `:69-111` / desktopMain `:113-129` / commonTest `:131-136` / desktopTest `:138-149`; android block `:153-172` (lint disabled `:166-171`); test config `:180-222`.
- `androidApp\build.gradle.kts`: android `:17-109` (versionCode 140, versionName 1.2.86, ABI filters arm64-v8a/armeabi-v7a `:36-38`, splits `:45-52`, signing `:54-63`, release minify `:74`), deps `:121-165`.

## Mirage library reference
`Androidpoet/mirage`, Apache-2.0, 29 AGSL shaders, requires **API 33** at render time, library minSdk 21, Maven Central `io.github.androidpoet:mirage:0.1.0` (verify latest resolves before committing to it).

Procedural composables (fill their own bounds; all take `modifier`):
- `ColorPanels(colors, colorBack, angle1, angle2, length, edges, blur, fadeIn, fadeOut, gradient, density, speed, startFrame, sizing)`
- `Dithering(colorBack, colorFront, shape: DitheringShape{Simplex,Warp,Dots,Wave,Ripple,Swirl,Sphere}, type: DitheringType{Random,Bayer2x2,Bayer4x4,Bayer8x8}, size, speed, startFrame, sizing)`
- `DotGrid(colorBack, colorFill, colorStroke, size, gapX, gapY, strokeWidth, sizeRange, opacityRange, shape: DotGridShape{Circle,Diamond,Square,Triangle}, sizing)`
- `DotOrbit(colorBack, colors, size, sizeRange, spreading, stepsPerColor, speed, startFrame, sizing)`
- `GodRays(colorBack, colorBloom, colors, density, spotty, midSize, midIntensity, intensity, bloom, speed, startFrame, sizing)`
- `GrainGradient(colorBack, colors, softness, intensity, noise, shape: GrainGradientShape{Wave,Dots,Truchet,Corners,Ripple,Blob,Sphere}, speed, startFrame, sizing)`
- `MeshGradient(colors /*up to 10*/, distortion, swirl, grainMixer, grainOverlay, speed, startFrame, sizing)`
- `Metaballs(colorBack, colors, count, size, speed, startFrame, sizing)`
- `NeuroNoise(colorFront, colorMid, colorBack, brightness, contrast, speed, startFrame, sizing)`
- `PerlinNoise(colorFront, colorBack, proportion, softness, octaveCount, persistence, lacunarity, speed, startFrame, sizing)`
- `PulsingBorder(colorBack, colors, roundness, thickness, margin*, aspectRatio, softness, intensity, bloom, spots, spotSize, pulse, smoke, smokeSize, speed, startFrame, sizing)`
- `SimplexNoise(colors, stepsPerColor, softness, speed, startFrame, sizing)`
- `SmokeRing(colorBack, colors, noiseScale, noiseIterations, radius, thickness, innerShape, speed, startFrame, sizing)`
- `Spiral(colorBack, colorFront, density, distortion, strokeWidth, strokeTaper, strokeCap, noise, noiseFrequency, softness, speed, startFrame, sizing)`
- `StaticMeshGradient(colors, positions, waveX, waveXShift, waveY, waveYShift, mixing, grainMixer, grainOverlay, sizing)`
- `StaticRadialGradient(colorBack, colors, radius, focalDistance, focalAngle, falloff, mixing, distortion, distortionShift, distortionFreq, grainMixer, grainOverlay, sizing)`
- `Swirl(colorBack, colors, bandCount, twist, center, proportion, softness, noiseFrequency, noise, speed, startFrame, sizing)`
- `Voronoi(colors, stepsPerColor, colorGlow, colorGap, distortion, gap, glow, speed, startFrame, sizing)`
- `Warp(colors, proportion, softness, shape: WarpPattern{Checks,Stripes,Edge}, shapeScale, distortion, swirl, swirlIterations, speed, startFrame, sizing)`
- `Waves(colorFront, colorBack, shape, frequency, amplitude, spacing, proportion, softness, sizing)`

Modifier image filters: `flutedGlass gemSmoke halftoneCmyk halftoneDots heatmap imageDithering liquidMetal paperTexture water`.

Shared controls on every shader: `speed: Float` (`0f` freezes = zero per-frame cost), `startFrame: Float` (ms offset), `sizing: SizingParams(fit: ShaderFit{None,Contain,Cover}, scale, rotation, originX, originY, offsetX, offsetY, worldWidth, worldHeight)`; presets `SizingParams.Object` (Contain) and `SizingParams.Pattern` (None). Many expose `grainMixer`/`grainOverlay` 0f..1f. Each shader has a `<Name>Defaults` object with its default colors.

## Plan

A. **Author 10 theme identities from their names** — each a real multi-hue palette, light + dark face, mutually distinct hue territories. Keep the 10 ThemePack name-identities (Gallery/Dark, Manuscript/Espresso, Silver/Graphite, Arctic/Midnight, Sakura/Blossom, Honey/Ember, Moss/Peacock, Dawn/Dusk, Lagoon/Abyss, Rouge/Garnet). Rename a face only where the old name cannot carry a chromatic identity; if renamed, update `ThemePack`, `AppPalette` ids + the legacy remap table, reader preset ids, `FOLIO_LIGHTS`, `PACK_GEOMETRY`, and any persisted-id fallbacks consistently.

B. **Verify with coloraide** — `pip install coloraide` if missing; throwaway script under `outputs\palette\`. Check WCAG/APCA contrast for `textPrimary/textSecondary/textTertiary` on `backgroundBase`+`surface`+`surfaceElevated`; dE2000 separation between accents within a theme AND between the 10 themes' primaries; hue-harmony intent per theme. Iterate hexes until clean. Record the final report.

C. **Replace `FOLIO_ART_DIRECTIONS` wholesale** (all 500 hexes) with the new 20 faces, all 25 roles authored. Replace `Theme.kt:25-28` `chartSeriesDefault` and the vestigial `FolioColors` defaults `:30-146`.

D. **Re-author the 20 reader presets** in `ReaderThemes.kt` that the packs reference so the reader page matches its theme.

E. **One DIFFERENT Mirage animation per theme**, chosen for that theme's aesthetic, with light/dark parameterisation, fed the theme's own palette colors. Prefer adding the real Mirage dependency (androidMain only + desktop no-op actual, matching the existing expect/actual pattern) over hand-porting more AGSL; if `io.github.androidpoet:mirage` cannot resolve or cannot compile against Compose 1.7.3 / Kotlin 2.1.20 / compileSdk 34, fall back to extending the local AGSL family set so there are still 10 visually distinct fields. Either way: key the animation on the ThemePack id that `MainActivity` actually provides via `LocalFolioPackId`, fixing the mismatch bug, and verify the live chain `MainActivity.kt:516/533-543 -> SilkField.kt:208-278 -> kind mapping`. Respect `rememberMotionEnabled()`; keep a graceful fallback for API<33 and Desktop/Skiko. Update `NOTICE` attribution.

F. **Rebuild the Settings > Themes cards** (`ThemePackCard`) so each card reflects its theme: real multi-accent palette (not one dot), the theme's animation character rendered or evoked, and post-`deepenInkRoles`/`withLegibleAccents` colors so the preview matches what renders.

G. **Delete or rewrite the theme tests** listed above; keep the rest of the suite green.

H. **Validate** — run the desktop test task for surviving tests, then build the Android debug (and release if quick) APK. Use `background_process` `monitor` for long Gradle runs.

## Constraints
- Compose stays 1.7.3.
- `shared` must compile for BOTH `androidTarget` and `jvm("desktop")`.
- Do not commit or push unless asked.
- Do not ask the user questions; make the calls and document them in the final report.
