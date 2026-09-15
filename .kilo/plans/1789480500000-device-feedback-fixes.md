# Device-feedback fixes: reader theme jump, typeface options, ridgeline smoothing

Three bugs reported from on-device testing of the §16 liquid-glass build
(`androidApp-release.apk`, v1.1.5+, Android 16 device). None of them are caused
by the glass work — all three predate it — but they surfaced during the same test
pass. Precondition: the liquid-glass plan's code is in the working tree,
uncommitted, and builds (`:shared:desktopTest` green except two pre-existing
Windows-path manga failures; `:androidApp:assembleDebug`/`assembleRelease` clean).

## Bug 1 — switching in-reader themes mid-read jumps the page up (continuous mode)

**Repro:** open a book in continuous (scroll) mode, scroll a few screens down,
open the quick settings drawer, tap a different theme in the theme slider. The
page flashes and lands noticeably *above* where you were.

**Root cause (diagnosed, verified in source):** the Android reader surface bakes
the theme CSS into the document and keys the whole document on the whole settings
object — so a pure color change rewrites the HTML and forces a full reload, which
restores scroll from the last *reported* progress fraction, which lags the
finger. The jump-up is the gap between the last throttled progress report and the
actual scroll position.

- `shared/src/androidMain/kotlin/com/folio/reader/ui/render/HtmlContentSurface.android.kt:114`
  — `val content = remember(sections, windowed, settings) { ... injectReaderCss(...) }`
  keys on ALL of `settings`, and `injectReaderCss` (line 616) bakes
  `ReaderCss.styleSheet(settings, ...)` into `<style id="folio-reader-style">`.
- `:409` — `contentKey = "$loadKey:${content.hashCode()}"`; a theme-only change
  alters `content`, so the key changes.
- `:416` + `:446` — the `update` block reads `fraction = position?.scrollOffset`
  and issues `loadDataWithBaseURL`; the engine then restores from that stale
  fraction.

**Fix — hot-swap the style element, don't reload:**

1. Change the `content` remember key to settings *minus the theme fields*:
   `remember(sections, windowed, settings.copy(themeId = "", customTheme = null))`.
   Theme-only changes then leave `content` (and `contentKey`) untouched → no
   reload. Layout-affecting changes (font, size, margins, alignment, layoutMode,
   formattingMode…) keep the existing reload path, which is correct for them.
2. Add a live CSS-swap effect, patterned on the existing highlight-repaint
   effect at `:103` (`LaunchedEffect(highlights, settings.themeId,
   settings.customTheme, webViewRef)`), e.g.:

   ```kotlin
   LaunchedEffect(settings.themeId, settings.customTheme, content, webViewRef) {
       val wv = webViewRef ?: return@LaunchedEffect
       val safeLayout = if (settings.layoutMode == LayoutMode.SPREAD) PAGINATED else settings.layoutMode
       val css = ReaderCss.styleSheet(
           settings, PageEngine.colsFor(safeLayout),
           fontStack = { "'$it',serif" },
           continuousCss = <same string as injectReaderCss>,
       )
       wv.evaluateJavascript(
           "(function(){var s=document.getElementById('folio-reader-style');" +
           "if(s)s.textContent=" + jsLiteral(css) + ";})();", null)
       )
   }
   ```

   `jsLiteral()` already exists at `:479`. Factor the duplicated styleSheet call
   + continuousCss string out of `injectReaderCss` into one shared private fun so
   the baked and live CSS can't drift.
3. Note for the implementer: the effect also fires once after each real load
   (setting identical CSS) — harmless no-op. The `HighlightPaint` effect already
   re-paints highlights in the new theme; nothing else needed there.
4. Consider (cheap, same file): `highlightColorIndex` is also color-only — but it
   does not participate in the document CSS (it lives in `HighlightPaint` JS), so
   no key change needed for it. Verify by changing highlight color mid-read and
   confirming no reload (it should already be live).

**Desktop parity:** desktop's HtmlContentSurface has its own implementation
(`HtmlContentSurface.desktop.kt`); check whether it has the same settings-keyed
reload for theme changes. If it reloads on theme too, apply the same hot-swap
(`document.getElementById` via CEF evaluateJS) — but do not fork logic: extract
the shared "CSS for settings" builder in common code if the desktop needs it.
Desktop is lower priority; the bug was reported on Android.

**Validation:**
- Manual (device or emulator, continuous mode): scroll deep into a chapter,
  switch theme 3–4 times rapidly → zero scroll movement, no white flash; switch
  font size → reload + position restore still works (that path must not regress).
- Manual: paginated mode theme switch → colors change in place, page number kept.
- `:shared:desktopTest` green; `:androidApp:assembleDebug` builds.

## Bug 2 — typeface options render at inconsistent sizes in the in-reader panel

**Repro:** open the reader quick-settings drawer, tap the Typeface row, open the
dropdown. The options ("Calluna", "Comfortaa", "Literata", "Georgia", …) each
render in their own typeface with no explicit size, so the same sp yields wildly
different optical sizes (Calluna small-bodied, Comfortaa large and round) — the
list looks broken/ragged.

**Location:** `shared/src/composeUi/kotlin/com/folio/reader/ui/reader/ReaderSettingsPanel.kt:247-272`
— the `DropdownMenu`'s items:

```kotlin
DropdownMenuItem(text = {
    Text(actualFontName(font),
        fontFamily = systemFontFamily(actualFontName(font)),
        fontWeight = ..., color = ...)
}, onClick = ...)
```

No `style`/`fontSize`/`lineHeight`; the item text rides the ambient dropdown
style while switching family per row.

**Fix — keep the preview, normalize the size:** each item becomes a Row:
- the font NAME in the app UI font (FolioTheme.typography.bodyMedium, onSurface
  color, selected→accentText + SemiBold as today) — this is the tappable label
  and it must be visually uniform across all options;
- a trailing "Ag" SPECIMEN in the option's own typeface at a fixed size
  (~18sp), fixed-width (say 34dp), onSurfaceVariant color — this preserves the
  "see the font before choosing" function without letting each font's optical
  size dictate the row.
- Pin the item row height implicitly via consistent text styles; do not use
  per-font fontSize on the label.

Check the desktop appearance settings for the same pattern
(`SettingsPanelAppearance.kt` has its own font list; desktop-only) — apply the
same name+specimen structure there if it previews fonts in-place.

**Validation:** manual — dropdown rows align optically, one text style for all
labels; the specimen still shows each font's character. No test needed (pure
layout), but a screenshot comparison before/after helps review.

## Bug 3 — stats ridgeline graph is jagged; make it smoother

**Repro:** open the Statistics tab (Library → Stats or the Stats route); the
per-day reading "mountains" of the week chart are drawn as polyline zigzags.

**Location:** `shared/src/composeUi/kotlin/com/folio/reader/ui/statistics/StatsRidgeline.kt`
— `WeekChart` (:95) → `RidgelinePlot` (:136). Each day's crest is built at
:179-197 with `path.moveTo(x, y)` / `path.lineTo(x, y)` per sample step; the
fill path (:202-208) closes down to the baseline.

**Fix — monotone cubic smoothing in the draw phase:**
1. Add a pure, desktop-testable helper (new small file
   `ui/statistics/MonotoneCurve.kt`, or private in StatsRidgeline.kt if it stays
   single-use — prefer the new file + tests):

   ```kotlin
   fun Path.appendMonotoneCubic(points: List<Offset>)
   ```

   Fritsch–Carlson tangents (the standard monotone cubic interpolation): compute
   slopes per segment, clamp tangents where the sign of the slope changes so the
   curve is smooth but **never overshoots** — a reading chart must not display
   minutes the reader did not read. Flat runs (zero samples) must stay perfectly
   flat (zero tangents). Fewer than 3 points → straight lines.
2. In `RidgelinePlot`, replace the `lineTo` loop with building the sample list
   then `path.appendMonotoneCubic(samples)`. The fill path keeps closing from
   the smoothed path exactly as today (`addPath(path)` + baseline close).
3. Keep it draw-phase only (§13.3): the smoothing is computed inside the
   `drawWithCache`/onDraw block where the polyline is built today; no
   recomposition, no state.

**Tests (extend `StatsRidgelineGeometryTest` or new `MonotoneCurveTest`, desktop):**
- The curve passes exactly through every sample point.
- Between two samples the curve never exceeds the local min/max envelope of the
  two (no overshoot) — e.g. samples [0, 10, 0] must not dip below 0 or exceed 10.
- A flat run of samples yields an exactly flat curve segment.
- 0/1/2-point inputs degrade to polyline without crashing.
- Existing ridgeline geometry tests (`ridgeCrestMinY`, baseline math) untouched.

## Order and validation

Do Bug 1 first (worst UX), then Bug 3 (self-contained + tests), then Bug 2
(pure layout). After each:

```
.\gradlew --no-daemon :shared:desktopTest
.\gradlew --no-daemon :androidApp:assembleDebug
```

At the end: `.\gradlew --no-daemon :androidApp:assembleRelease` and
`adb install -r androidApp\build\outputs\apk\release\androidApp-release.apk`
(device `XCR4XOVSJBZXRKQK` is the test device, API 36). Manual pass on device:
theme-switch scroll steadiness (continuous + paginated), typeface dropdown
alignment, stats week chart smoothness — and re-check the §16 surfaces still
look right (glass unaffected by all three fixes).

## Out of scope / notes

- The two pre-existing desktopTest failures (MangaReaderProgressTest, Windows
  path-illegal chapter ids) are unrelated; leave them.
- If the theme hot-swap reveals that font-family changes ALSO want live swap
  (they reflow but shouldn't lose position), that's a separate, riskier change —
  do not fold it in here.
