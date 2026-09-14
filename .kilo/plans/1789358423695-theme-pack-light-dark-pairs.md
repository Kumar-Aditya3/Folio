# Theme packs with light/dark pairs, a mode toggle, and curation

## Goal

Every theme pack becomes a **matched light/dark pair**. A toggle at the top of the
Themes settings section flips the active pack to its other side; theme card names
crossfade into their counterpart name (e.g. "Honey" → "Ember") as it flips.
Near-duplicate palettes are removed with their persisted ids migrated to the
nearest survivor.

User-approved decisions:
- 12 curated pairs + a 13th Silver↔Graphite pair (Graphite kept, new "Silver" light palette).
- OLED/"Black" palette removed (Dark already has a #000000 base).
- Toggle lives at the top of the "Theme packs" section in Settings → Themes
  (works identically on Android `SettingsThemesScreen` and the shared `SettingsScreen`).

## The 13 packs

`lightName ↔ darkName (lightPalette/lightReader ↔ darkPalette/darkReader)`:

| # | Pack id | Names | Light side | Dark side |
|---|---------|-------|-----------|-----------|
| 1 | gallery | Gallery ↔ Dark | light / white | dark / dark |
| 2 | manuscript | Manuscript ↔ Espresso | warm / sepia | espresso / espresso |
| 3 | silver | Silver ↔ Graphite | **silver (NEW)** / gray | graphite / graphite |
| 4 | matcha | Matcha ↔ Toxic Lime | matcha / matcha | toxiclime / toxiclime |
| 5 | arctic | Arctic ↔ Midnight | arctic / arctic | midnight / dark |
| 6 | sakura | Sakura ↔ Blossom | sakura / paper | blossom / blossom |
| 7 | honey | Honey ↔ Ember | honey / sepia | ember / ember |
| 8 | moss | Moss ↔ Peacock | moss / moss | peacock / peacock |
| 9 | sherbet | Sherbet ↔ Retro Sunset | sherbet / sherbet | retrosunset / retrosunset |
| 10 | bubblegum | Bubblegum ↔ Vaporwave | bubblegum / bubblegum | vaporwave / vaporwave |
| 11 | dawn | Dawn ↔ Dusk | **dawn (NEW)** / **dawn (NEW reader)** | dusk / dusk |
| 12 | hyperpop | Hyperpop ↔ Synthwave | hyperpop / candypop | synthwave / synthwave |
| 13 | rainbow | Rainbow Candy ↔ Neon Tokyo | rainbow / rainbow | neontokyo / neontokyo |

Removed `AppPalette` entries (and their `FolioColors` objects): `grape`, `lava`,
`acid`, `ocean`, `midnightneon`, `oled`. Kept: everything else, including
`graphite`, `moss`, `midnight`, `dusk`. All reader presets survive (acid/lava/
midnightneon/graphite/oled_black etc. remain selectable as reader-only page
themes, like dracula/nord already are).

## Changes

### 1. `shared/src/composeUi/kotlin/com/folio/reader/ui/theme/Theme.kt`

- Author two new light palettes (model them on `WarmFolioColors` /
  `LightFolioColors.copy(...)`; they must pass every ThemeSchemeTest gate):
  - **DawnFolioColors** — soft violet light theme (bg/surface in the violet
    family with ≥8° hue separation to satisfy the non-achromatic clause; four
    accent roles pairwise ΔE ≥ 10 and ≥4.5:1 on surface; onSurface ≥7:1;
    onSurfaceVariant ≥4.5:1 and below onSurface; outline ≥1.5:1; chartSeries 8
    hues with first six pairwise ΔE ≥ 10).
  - **SilverFolioColors** — Graphite's mirror: a cool light-grey, deliberately
    achromatic. Add `"silver"` to `achromaticByDesign` in ThemeSchemeTest
    (mirroring graphite); background/surface must differ by ΔL* ≥ 4.0.
- Add `DAWN` and `SILVER` enum entries (`isDark = false`).
- Delete `GrapeFolioColors`, `LavaFolioColors`, `AcidFolioColors`,
  `OceanFolioColors`, `MidnightNeonFolioColors`, `OledFolioColors` and their
  enum entries.
- **Migration** — `AppPalette.byId` consults a `legacyIds` map before falling
  back to LIGHT:
  `grape→dusk`, `lava→ember`, `acid→toxiclime`, `ocean→peacock`,
  `midnightneon→neontokyo`, `oled→dark`. This is the single migration point;
  MainActivity, Main.kt, ChallengeWebViewActivity, and the custom-theme editor
  all resolve through `byId`.
- Restructure `ThemePack` (~line 1109):
  ```kotlin
  data class ThemePack(
      val id: String,
      val lightName: String, val darkName: String,
      val lightAppPaletteId: String, val darkAppPaletteId: String,
      val lightReaderThemeId: String, val darkReaderThemeId: String,
  ) {
      fun name(dark: Boolean) = if (dark) darkName else lightName
      fun appPaletteId(dark: Boolean) = if (dark) darkAppPaletteId else lightAppPaletteId
      fun readerThemeId(dark: Boolean) = if (dark) darkReaderThemeId else lightReaderThemeId
      companion object { val ALL = listOf(/* 13 packs above */) }
  }
  ```
  Suggested row order: gallery, manuscript, silver, matcha, arctic, sakura,
  honey, moss, sherbet, bubblegum, dawn, hyperpop, rainbow (neutrals → hue walk
  → loud).
- Add a pure, testable flip helper next to `ThemePack`:
  `fun flipThemeMode(settings: ReaderSettings): ReaderSettings` —
  - Resolve `dark = settings.customAppTheme?.isDark ?: AppPalette.byId(settings.appThemeId).isDark`.
  - Find the pack where `AppPalette.byId(settings.appThemeId).id` equals either
    side's palette id. (Compare **resolved** ids, never raw strings — persisted
    settings may still hold legacy ids like "grape".)
  - Set `appThemeId` to the other side's palette id.
  - Set `themeId` to the other side's reader id **only if** the current
    `themeId` equals one of the pack's two reader ids and `customTheme == null`;
    otherwise leave the reader theme alone (independent choices like dracula are
    never stomped). Never touch `customAppTheme` (the toggle is disabled then —
    see below) or `customTheme`.

### 2. `shared/src/commonMain/kotlin/com/folio/reader/settings/ReaderThemes.kt`

- Add a `"dawn"` light reader preset (violet paper: tinted bg/surface, dark
  violet inks, 8 highlight colours) so the Dawn pack's light side has a page.
  Silver's light side reuses the existing `"gray"` preset. Nothing removed.

### 3. `shared/src/composeUi/kotlin/com/folio/reader/ui/settings/SettingsPanelGeneral.kt`

- At the top of the "Theme packs" section: header row with title + updated copy
  ("Each pack is a matched pair — a light side and a dark side. The switch flips
  the pair; either side can still be adjusted on its own.") on the left, and a
  new `ThemeModeToggle` on the right.
- `ThemeModeToggle` (new composable, `SettingsPanelThemeCards.kt`): compact
  two-segment capsule using `Icons.Filled.LightMode` / `Icons.Filled.DarkMode`
  (material-icons-extended is already an `api` dep of shared), selected segment
  highlighted with `primaryContainer`/`primary`, `FolioTokens.radiusControl`
  shape, `motionFast`/`motionStandard` timing. Disabled (greyed) when
  `settings.customAppTheme != null` — a custom theme defines its own polarity;
  the Appearance panel remains the way to leave it.
- Compute once: `val dark = settings.customAppTheme?.isDark ?: AppPalette.byId(settings.appThemeId).isDark`.
- Selection logic (both the scroll-target index and the card `selected` flag):
  a pack is selected when the resolved `(appPaletteId, readerThemeId)` pair
  equals **either side** of the pack exactly, and `customTheme == null` and
  `customAppTheme == null`.
- Card `onClick` applies the **displayed** side (mode-aware, see below) and
  clears both custom themes — same as today's pack apply.
- Toggle `onToggle = { onSettingsChange(flipThemeMode(settings)) }`, disabled
  when `customAppTheme != null` or no pack resolves.

### 4. `shared/src/composeUi/kotlin/com/folio/reader/ui/settings/SettingsPanelThemeCards.kt`

- `ThemePackCard` gains a `dark: Boolean` parameter; the mini chrome/page
  preview resolves `AppPalette.byId(pack.appPaletteId(dark))` and
  `Theme.getPreset(pack.readerThemeId(dark))`.
- **Name fade** (the requested detail): render the name with
  `AnimatedContent(targetState = pack.name(dark))` using
  `fadeIn(tween(FolioTokens.motionStandard)) togetherWith fadeOut(...)` — pure
  crossfade, no slide.
- Polish: run the preview's key colours (`chrome.background`,
  `chrome.primary`, page colours) through `animateColorAsState(
  tween(FolioTokens.motionStandard))` so the mini-app crossfades along with the
  name. LazyRow item keys stay `it.id` so cards aren't recreated on flip.

No changes needed in `MainActivity.kt` / `Main.kt` — they already resolve via
`AppPalette.byId(appThemeId)`; the flip just writes a different id.

### 5. Tests

`shared/src/desktopTest/kotlin/com/folio/reader/ThemeSchemeTest.kt`:
- Make the three pack tests side-aware: `everyPackResolvesOnBothSides` checks
  all four ids; `everyAppPaletteIsReachableThroughAPack` unions both sides;
  `readerAndAppDarknessAgreePerPack` checks each side.
- New: `packSidesHaveOppositePolarity` — light side has `isDark == false`
  (palette and reader), dark side `isDark == true`.
- New: `legacyPaletteIdsResolve` — `byId("grape") == DUSK`, etc. for all six.
- `achromaticByDesign`: remove `"oled"`, add `"silver"`.
- New: `flipThemeMode` test — honey/sepia → ember/ember;
  honey/dracula → ember/dracula (reader untouched);
  custom reader theme preserved; `customAppTheme != null` → input returned
  unchanged; legacy id ("ocean") resolves through the remapped side.

`androidApp/src/androidTest/.../ThemeLegibilityTest.kt`: replace
`AppPalette.byId("midnightneon")` with a surviving palette (e.g. `"dusk"`).
`SettingsMergeTest` / `MergeGlobalSettingsStaleSnapshotTest` use surviving ids
("light"/"midnight") — verify, no change expected.

## Validation

1. `.\gradlew :shared:desktopTest` — all theme gates pass (iterate Dawn/Silver
   hexes against ThemeSchemeTest until they do).
2. `.\gradlew :androidApp:assembleDebug :desktopApp:assembleDist` (or the
   project's compile tasks) — both platforms build.
3. `.\gradlew :shared:compileKotlinDesktop` after each model change for fast
   feedback.
4. Manual, both platforms: Settings → Themes — toggle flips the whole app
   between pack sides; names crossfade; card previews flip; selected card stays
   selected (same pack, other side); reader page flips only when it was the
   pack's own page; custom app theme disables the toggle; an install previously
   set to a removed theme (e.g. "Lava") lands on its survivor ("Ember").
5. Android `connectedDebugAndroidTest` if a device is available
   (ThemeLegibilityTest).

## Risks / notes

- Authoring Dawn and Silver to pass every contrast gate is the main iteration
  loop — the tests are the spec, tune hexes against them.
- Raw-string comparisons against `appThemeId` anywhere new would silently break
  legacy ids; always compare `AppPalette.byId(...).id`.
- App-wide theme swap stays instant (as pack-apply is today); only the card
  names/previews animate. A full app crossfade is explicitly out of scope.
