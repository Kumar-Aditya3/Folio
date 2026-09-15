# Known-issues batch: webtoon zoom, PDF continuous, reader themes, bottom nav, stats

Six confirmed issues. Design decisions already made with the user:
- "The library core" → replaced by **Option B "Where your time went"**, absorbing the existing "Most read" leaderboard (no duplicate data).
- Reader themes **always own paper & ink**, including Original formatting mode (Original keeps publisher typography/layout/fonts only).
- Stats hero becomes **this week's reading time** (year/words/finished demoted to tertiary).

Tasks are independent; suggested order: 5 → 1 → 4 → 3 → 2 → 6 (smallest to largest).

---

## Task 1 — Webtoon zoom drifts the scroll position

**Root cause** (`shared/src/composeUi/kotlin/com/folio/reader/ui/manga/MangaPager.kt`):
- Zoom is a *layout* change: `WebtoonReader` (lines 43–129) sizes items `Modifier.width(imageWidth)` where `imageWidth = viewport * zoom`; every item height scales linearly with zoom (FillWidth). `LazyColumn` pins `firstVisibleItemScrollOffset` in px, so the content under the pinch centroid slides by `(k−1)·(offset + centroidY)` on each zoom step. Nothing compensates (verified: no `scrollBy`/`dispatchRawDelta` anywhere in the manga reader).
- Secondary bleed: `Modifier.pinchZoom` (lines 259–295) consumes events only while `pressed.size >= 2`. When one finger lifts, the remaining finger's movement scrolls the list (post-pinch drift).

**Fix:**
1. In `pinchZoom`, capture the event centroid (average of pressed pointer positions, or `calculateCentroid()`).
2. Compensate scroll exactly when zoom is applied. Because all webtoon content heights scale linearly with the zoom ratio `k`, the exact correction (new-layout px) is:
   - vertical: `Δ = (k − 1) · (listState.firstVisibleItemScrollOffset + centroid.y)` → `listState.dispatchRawDelta(Δ)`
   - horizontal: `Δ = (k − 1) · (hState.value + centroid.x)` → `hState.dispatchRawDelta(Δ)` (auto-clamped at edges)
   Apply immediately after `setZoom` in the same pointer event (order setZoom → delta is exact; the subsequent layout pass preserves the corrected offset).
   Implementation shape: give `pinchZoom` an `onZoom: (ratio: Float, centroid: Offset) -> Unit` parameter (default = current `viewModel.setZoom(viewModel.zoom.value * ratio)` behavior for paged modes). `WebtoonReader` passes a lambda that calls `setZoom` plus the two `dispatchRawDelta` compensations. Check the page-level attach at `MangaPageCache.kt:174` — webtoon pages are created with `zoomable = false`; if the page-level handler still runs in webtoon, route it through the same compensating lambda.
3. Pinch tail: once `pinching` is true, keep consuming all events until every pointer is up (`if (pressed.size >= 2 || pinching) event.changes.forEach { it.consume() }`), but only update zoom while `pressed.size >= 2`.
4. Double-tap reset (`onDoubleTap = { viewModel.resetZoom() }` in webtoon): apply the same compensation with `k = 1f / oldZoom` and the tap position as centroid so the reset doesn't jump.
5. Extract the pure math `fun zoomScrollDelta(ratio: Float, anchorPx: Float): Float = (ratio - 1f) * anchorPx` and unit-test it on desktop (precedent: `DocumentReaderCoreTest` tests pure helpers).

**Validation:** new desktop test for `zoomScrollDelta`; manual pinch on a long webtoon chapter — the content under the fingers stays put through zoom in/out and no scroll happens on finger-lift.

---

## Task 2 — PDF continuous mode: cropped, gapped, blurry

**Root cause** (`shared/src/composeUi/kotlin/com/folio/reader/ui/document/FixedPageSurfaceImpl.kt`, lines 120–199, 291–316):
- 36dp of chrome between consecutive pages: `Arrangement.spacedBy(12.dp)` (176) + `contentPadding(vertical = 12.dp)` (177) + per-page `.background(surfaceVariant).padding(12.dp).clip(FolioShapes.inset)` (293–295) — a stack of framed cards, not an appended document.
- Cropping/distortion: hardcoded `.aspectRatio(0.74f)` (192) — the book-cover ratio reused for PDF pages; real pages (A4 = 0.707, Letter = 0.773, landscape ≈ 1.4) letterbox inside it. `FixedPageDocument` (`DocumentReaderState.kt:72–75`) exposes no page dimensions, so the UI cannot size items truthfully.
- Whole-list `graphicsLayer` zoom (171–174) scales visually only: scroll extents don't grow, zoomed content is clipped and unreachable; zoom never re-renders because the raster cache key excludes it (`FixedPageCache.kt`).
- Blurriness: rasters render at exactly 1× fit pixels (desktop `FixedPageContentSurface.desktop.kt:53–68`, android `:64–85` use `min()` scale with zero oversampling headroom — contrast manga's 3× budget at `MangaReaderScreen.kt:97–101`), and the `Image` (line 304) uses the default `FilterQuality.Low`.

**Fix** (model on the webtoon reader, `MangaPager.kt:43–129`):
1. Extend `FixedPageDocument` with `suspend fun pageAspectRatio(pageIndex: Int): Float` (width/height, normalized for the document's own rotation):
   - Desktop (PDFBox): `PDPage.cropBox` + `/Rotate`, already computed internally in the renderer (lines 53–58).
   - Android: `PdfRenderer.openPage(i)` width/height (close the page without rendering — cheap).
2. Rewrite the CONTINUOUS branch as a webtoon-style strip:
   - `BoxWithConstraints`; `columnWidth = viewport * max(zoom, 1f)`; `pageWidth = viewport * zoom`; `horizontalScroll(hState)` on the wrapper when zoom > 1.
   - `LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp))`, no vertical contentPadding, no `.clip(FolioShapes.inset)` on the list.
   - Items: `Modifier.width(pageWidth).aspectRatio(realAspect)` using the new API (remember per page; while unresolved use a placeholder aspect — first page's aspect or 0.707). Parameterize `RenderedPdfPage` so continuous mode drops the card chrome (`background(surfaceVariant).padding(12.dp).clip(...)`) — background comes from the reader surface behind the list.
   - Replace the `graphicsLayer` zoom with layout-width zoom. Reuse Task 1's focal-point compensation (heights scale linearly since aspect is fixed).
3. Quality:
   - Oversample: render target = item size × `PDF_RENDER_OVERSAMPLE = 2f` (new constant), still clamped by `MAX_RENDER_DIMENSION`/`MAX_RENDER_PIXELS`. Layout-width zoom then re-renders automatically (item grows → `onSizeChanged` → new `rasterSize` → new cache key).
   - Pass `filterQuality = FilterQuality.High` to the `Image` (line 304) in both modes.
   - Single-page mode: feed a zoom bucket into the raster size (`oversample × ceil(zoom)`) so zooming re-renders sharp instead of GPU-upscaling (zoom there is graphicsLayer-based, layout doesn't change).
4. Tests: extend `shared/src/desktopTest/.../DesktopFixedPageDocumentTest.kt` — generate a page with known dimensions and assert `pageAspectRatio` matches (e.g. A4 ≈ 0.707, landscape variant). Delete/refresh the stale note in `debug-pdf-reader-layout-navigation.md` (lines 45, 60) once the layout zoom flaw is gone.

**Note:** `MAX_RENDER_PIXELS = 16.7MP` still downscales on very large desktop windows; acceptable — do not raise (memory). Rotation in continuous mode: apply rotation to the aspect ratio so rotated pages size correctly instead of rendering sideways in a portrait box.

---

## Task 3 — Reader themes don't affect the page

**Root cause** (`shared/src/commonMain/kotlin/com/folio/reader/ui/render/ReaderCss.kt`):
- In `FormattingMode.ORIGINAL` every theme rule is gated off (lines 40–41, 62, 66–67, 73) and the single surviving `html,body{background;color}` rule (102) lacks `!important` → publisher CSS wins → **zero theme effect**.
- Even in HYBRID/NORMALIZED the forcing is leaky: text color forced only on `p,div,span,li,blockquote`; backgrounds washed transparent only on `body,div,section,article,figure` — publisher colors survive on `td,th,figcaption,pre,code…` and backgrounds on `table,aside,blockquote,pre,header,nav…`; class-based publisher `!important` rules out-rank `body p`.
- Only 6 of 14 palette fields ever reach the page; `secondaryText`, `surface`, `divider` never paint the page (`ReaderThemes.kt` presets), so near-duplicate presets (white vs gray: `#FFFFFF/#1A1A1A` vs `#F0F0F0/#222222`; dark vs graphite) look identical on the page.

**Fix:**
1. **Paper & ink always apply** (all modes including ORIGINAL): emit with `!important` — `html,body{background…;color…!important}`, the `themeBgCss` transparent wash, `colorCss`, heading rule (114), link rule (118), `::selection`. ORIGINAL keeps gating only *typography/layout*: `typography`, `alignCss`, `paragraphCss`, `elementForceCss`'s font-family parts, `normalizedExtra` stay suppressed there exactly as today.
2. **Widen the force** (all modes):
   - Text color: extend the element list (73–76) to `td,th,dd,dt,figcaption,pre,code,em,strong,i,b,u,small,aside,details,summary` alongside `p,div,span,li,blockquote`.
   - Background wash: extend (66–67) to `table,td,th,aside,blockquote,pre,header,footer,nav,ul,ol,main,dl` — but exclude `mark`, `img`, `svg`, `a` so highlight washes and link tints survive. Check `HighlightPaint.css` emits `!important` and/or sufficient specificity so the wash cannot stomp it; if not, add `:not()` guards.
   - `secondaryText` → `figcaption,small` ink (best-effort secondary ink on the page).
   - `divider` → `hr{background…;border-color…}`.
   - `surface` → subtle block tint for `pre,blockquote` backgrounds (HYBRID/NORMALIZED only).
3. **Differentiate near-duplicate presets** in `ReaderThemes.kt`: adjust background/ink of visually identical same-polarity presets (at minimum `gray` vs `white`, `graphite` vs `dark`; audit all 34) so every preset produces a visibly distinct page while keeping primaryText-on-background contrast ≥ 4.5:1.
4. **Picker swatches** show text+background: render "Aa" in `primaryText` over the `background` swatch — desktop overlay (`OverlayUi.kt:158–164`, fed by `ReaderPanels.kt:93–95` which currently passes only the background hex) and the Android quick-settings preview if it shows the same flat swatch.
5. Tests (new `ReaderCssThemeTest`, desktop): for every preset and every formatting mode, `styleSheet()` contains the background hex and primaryText hex with `!important`, the heading/link/selection rules, and (non-original) the extended element lists; a preset-distinctness + contrast test in the vein of `ThemeSchemeTest` (pairwise ΔL/ΔE floor within a polarity; contrast ≥ 4.5). Update `ThemeSchemeTest` if palette values shift.

---

## Task 4 — Bottom nav Home taps randomly fail

**Root cause** (`androidApp/src/main/java/com/folio/reader/nav/FolioNavShell.kt`), ranked:
- **H1 hit target**: item Box is `.height(42.dp)` (247–253) — below the 48dp minimum — inside a 54dp capsule, with 2dp inter-item gaps (163), 5dp capsule end-padding (161), and a pass-through gradient band below. Low thumb taps fall through to content.
- **H5 IME**: `enableEdgeToEdge()` + `windowSoftInputMode="adjustResize"` and **no `imePadding()` anywhere** (grep-verified) — the keyboard covers the capsule whenever a text field is focused.
- **H2 moving targets**: widths spring 50↔86dp (~200–400ms) after each switch; `launchSingleTop` makes a tap on the already-selected tab a silent no-op.
- **H4**: navigation-compose 2.8.0 (`gradle/libs.versions.toml:80`) shipped transition/`popUpTo`/`restoreState` regressions fixed in 2.8.1/2.8.2.

**Fix:**
1. Hit targets: keep the 42dp visual pill but make the clickable Box ≥ 48dp tall and bleed horizontally into the gaps (e.g. `.heightIn(min = 48.dp).width(targetWidth + 2.dp)` with the pill drawn on an inner 42dp child, centered).
2. Add `.imePadding()` to the capsule's outer `Box` (FolioNavShell.kt:133) so the keyboard pushes it up.
3. Re-tap on the selected tab scrolls that tab's content to top (standard behavior; also fixes the "silent no-op" perception): when `currentRoute == item.route` on click, emit on a small shared bus (e.g. `MutableStateFlow<Long>` keyed by route); HomeScreen / Library tabs / StatisticsTabContent / More each collect it and `animateScrollTo(0)` (or `scrollToItem(0)`) their primary scrollable. Scope: the four top-level tabs only.
4. Bump `androidx-navigation-compose` 2.8.0 → 2.8.2 in `gradle/libs.versions.toml:80`.

**Validation:** manual matrix on device — taps at icon top/low edge, taps during the width spring, taps with the keyboard open, rapid double taps, taps while an import status banner is showing.

---

## Task 5 — "Last 7 days" ridgeline clips outside the box

**Root cause** (`shared/src/composeUi/kotlin/com/folio/reader/ui/statistics/StatsRidgeline.kt:112–115, 127`): `stepDown = h/(n+1)` puts the back line's baseline at `h/8`, but the amplitude cap is `0.62·h` — the back lines' crests overshoot the canvas top by up to ~`0.5·h` whenever an early-window day holds the max (matches "clips at times").

**Fix:**
1. Restack so the amplitude fits: e.g. `stepDown = h / (2·(n+1))` (back line baseline ≈ `h/2` for n=7) and `amplitudeCap = backBaseline − smallMargin`; `amplitude = amplitudeCap · (minutes/peak)` — the same cap for every line keeps the per-day encoding proportional across the week while no crest can leave the canvas (cap ≤ the smallest baseline). Tune once for visual density; the constraint is *crest y ≥ 0 for every line at every value*.
2. While there: sanity-check `MangaWeekChart` (`StatsCharts.kt:117–167`) — the count `Text` sits above a bar that can grow to the full `chartHeight` row; verify `chartHeight ≥ chartBarBase + chartBarSpan + label/text heights` and pad if it can overflow.
3. Extract the geometry (stack offset + amplitude cap) as a pure function and add a desktop test sweeping n = 2..14 and minute vectors, asserting min crest y ≥ 0 (precedent: `CoreSampleTest` for chart math).

---

## Task 6 — Stats redesign (library core → "Where your time went", hierarchy, metrics, patterns)

Files: `StatisticsScreen.kt`, `StatsRows.kt`, `StatsCoreSample.kt`, `StatsCoreData.kt`, `StatisticsViewModel.kt`, `StatsRidgeline.kt`; tests `CoreSampleTest.kt`, `StatisticsPhaseCTest.kt`.

### 6a. Replace "The library core" with "Where your time went" (absorbs "Most read")
1. Delete `CoreSampleChart`/`CoreColumn`/`CoreLabels` and the core math (`coreRockSpec`, `coreStratumFractions`, `coreHueIndex`, `coreRockColor`, `coreCaption`, `CoreRandom`) from `StatsCoreSample.kt`; remove `coreStrata` from `StatisticsUiState` and `buildCoreStrata` from `StatsCoreData.kt`/`StatisticsViewModel.kt:431`; drop the now-unused `FolioTokens.coreSample*`/`coreStratum*` tokens (`Theme.kt:1259–1262`).
2. Build the new section where TopBooksCard sits (`StatsRows.kt:119–183`), reusing its ranked cover rows plus the `GenresCard` bar idiom:
   - Head: "Where your time went", eyebrow "This year". Rows: rank figure + `FolioCoverPlate` + title/author + trailing `shortMinutes` + a proportional bar under each row (width = minutes/peak, hue from `chartSeries` like the genre bars, peak row in `accentStreak`). Tap → `onBookClick`.
   - After the ranked rows: an aggregate row "Everything else · Xh" (sum of non-top books' minutes) and a caption "N of M books opened" (N = books with window minutes, M = library size — both already computable from `minutesByBook`/`books`; expose `booksOpened`/`librarySize` on `StatisticsUiState`).
   - Cap rows at 6.
3. Update section order in `StatisticsTabContent` (`StatisticsScreen.kt:171–205`): overture → ledger → week chart → heatmap → finish predictions → **where your time went** → genres → patterns → quotes → manga.
4. Tests: delete core-specific assertions in `CoreSampleTest.kt` (including its exclusion-coverage cases at lines 304–327 — port those exclusion guarantees to the new section's data: excluded books never appear in ranked rows or the aggregate). Add pure tests for the everything-else/books-opened aggregation.

### 6b. Hierarchy (primary/secondary/tertiary)
1. `StatsOverture` (`StatisticsScreen.kt:244–317`): hero figure switches to `formatDuration(stats.timeThisWeekMs)` with label "Reading this week"; keep the chronotype sentence, peak-window line and the `GoalDial` companion. Remove the year-hours hero.
2. `HeadlineRow` (`StatsRows.kt:366–409`) becomes: row 1 (secondary) — Streak (`FigureScale.Standard`) + Active days this week; row 2 (tertiary, Quiet) — This year · Words read · Books finished. **Remove the `sessionsThisWeek` caption** (feedback #4: sessions out of the headline entirely).

### 6c. Metrics that say something
1. `StatisticsViewModel`/`StatisticsUiState` additions: `longestSessionMinutes` (max `durationMs`), `daysActiveThisYear` (`readDays.size`), `booksOpened`, `librarySize`, `hourTotals: List<Long>` (the existing 24-slot array, line 387).
2. `MangaStatistics` (`MangaModels.kt`) + both repository implementations (`JdbcMangaStatisticsRepository`, `commonJvm/.../JdbcMangaRepositories.kt:1301+`; find the Android counterpart by grepping `MangaStatisticsRepository`): add `biggestDayChapters` = MAX(chapters read in a single local day). Default value keeps existing fakes/tests compiling.
3. `PatternsCard` (`StatsRows.kt:418–469`):
   - Keep chronotype headline + "Your reading happens mostly between {peakWindow}."
   - Add a compact 24-hour band: thin bars per hour (heights from `hourTotals`, peak hour in `accentStreak`, others `accentProgress` low alpha) inside the existing sunken-well idiom, with an edge-labeled scale (12a … 11p) and a "▲ your peak · {mostReadHour}" caption — the review's peak-hour visual.
   - Rows: replace "Average binge" with "Biggest day" (`biggestDayChapters` chapters); add "Longest session" (`longestSessionMinutes`); keep Average session, Current/Longest streak, Most active hour, Favourite day, Average speed, Active days, Synced from. Do not reintroduce a raw session count anywhere in the headline.

### 6d. Tests
Update `StatisticsPhaseCTest`, add coverage for the new VM fields (longest session, days active, hourTotals, books opened) with synthetic sessions; adjust `CoreSampleTest`/`StatsLibraryOnlyTest` only if signatures moved (manga fake gets the new defaulted field).

---

## Validation (whole batch)

> **Gradle disclaimer (environment quirk):** every Gradle invocation must carry the stop flag inside the command itself — `.\gradlew --no-daemon <tasks>`. A bare `.\gradlew` leaves the daemon holding the invocation and the command runs indefinitely, never returning. If a daemon is already stuck, kill it with `.\gradlew --stop` first, then continue with `--no-daemon` commands.

- Desktop unit tests: `.\gradlew --no-daemon :shared:desktopTest` (new: zoom delta, ridgeline geometry, ReaderCss theme emission per mode, preset distinctness/contrast, where-your-time-went aggregation, PDF aspect API).
- Android compile + smoke: `.\gradlew --no-daemon :androidApp:assembleDebug`; connected tests if a device is available (`ReaderFlowTest`, `ThemeLegibilityTest`).
- Manual checklist: pinch zoom on webtoon (no drift, no post-pinch scroll); PDF continuous (seamless strip, true aspect, sharp at 2× zoom, rotated page); switch through all 34 reader themes in HYBRID and ORIGINAL (page always repaints, near-duplicates now distinct); nav taps (low taps, keyboard open, re-tap-to-top, during transitions); stats (no clipping at any data shape, hierarchy reads primary→tertiary, no session count in headline, peak-hour band renders for all chronotypes).
- Watch for regressions: `ReaderSettingsOverrideTest`, `ReaderQuickSettingsNoLeakTest`, `ReaderSettingsSnapshotTest`, `SettingsMergeTest` (settings plumbing untouched but adjacent); `DocumentReaderViewModelTest` (mode-switch page preservation); `ShimmerTest`/`DesignSystemTest` (token removals).

## Risks / notes

- `body *`-style CSS forcing was deliberately avoided in favor of explicit element lists to protect highlights/links — verify against `HighlightPaint.css` before widening the background wash.
- Webtoon scroll compensation assumes item heights scale linearly with zoom (FillWidth, fixed aspect) — true today; if per-page `placeholderHeight` learning (`MangaPageCache.kt`) ever breaks that linearity, the compensation becomes approximate.
- PDF `pageAspectRatio` on Android opens each page once for dimensions — do it lazily per visible page, cached, not eagerly for 500-page documents.
- Re-tap-to-top touches four screens; keep the bus trivial (route-keyed timestamp) and no-op where a screen has no scrollable.
