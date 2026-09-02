# Folio — Implementation Spec (v1.0.25 → v1.1)

Audience: an implementing agent. Every item has a file path, exact values, and a
pass/fail benchmark. Where a number is given it is normative, not a suggestion.

Baseline commit: `5ee5c66`. All UI work is **Android only** (Rule 1).
Test baseline that must never regress: **132 passing tests in `:shared:desktopTest`**.

---

## 0. Scope

| In scope | Out of scope |
|---|---|
| Android UI/UX overhaul | Desktop UI changes |
| Shared logic the Android UI needs | Desktop reader surface (JCEF) rework |
| Stats integration into existing surfaces | New sync backend |
| God-file decomposition | Manga backend / extension runtime rewrite |
| Android instrumented tests | iOS / web targets |

---

## 1. THE RULES (non-negotiable)

Violating any rule is a failed change, regardless of how well the feature works.

### Rule 1 — Android-only UI must not leak into shared code
`shared/src/composeUi/` compiles into **both** apps. Android-only UI goes in exactly one of:
- `androidApp/src/main/java/com/folio/reader/**` (screens wired to Android nav), or
- `shared/src/androidMain/kotlin/com/folio/reader/ui/**` (Android-only composables).

If a composable must live in `composeUi` because both platforms use it, it takes behaviour
via parameters — never a new `expect`/`actual` for this purpose, never a platform check
inside the composable body.

**Benchmark:** `:desktopApp:compileKotlin` succeeds with zero new warnings;
`git diff --stat shared/src/composeUi` shows no new files for any Android-only feature;
desktop Library + Reader screenshots show no layout change.

### Rule 2 — No raw values; tokens only
```
FolioTokens.radiusCard    = 20.dp    FolioTokens.space1 =  8.dp
FolioTokens.radiusControl = 14.dp    FolioTokens.space2 = 12.dp
FolioTokens.radiusChip    = 10.dp    FolioTokens.space3 = 16.dp
FolioTokens.barHeight     = 56.dp    FolioTokens.space4 = 24.dp
```
Colours: `FolioTheme.colors.*` only. Typography: `FolioTheme.typography.*` only.
Need a value that doesn't exist? **Add it to `FolioTokens`** with a one-line comment naming
its role, then use the token. Never inline the number.

**Forbidden in new or edited files:**
- `Color(0xFF...)` outside `.../ui/theme/Theme.kt`
- `MaterialTheme.colorScheme.*` in new code
- Any `.dp` literal other than `0.dp` and `1.dp` (hairline borders)

**Benchmark:** both commands print nothing for files you touched:
```powershell
Select-String -Path <files> -Pattern 'Color\(0xFF|MaterialTheme\.colorScheme'
Select-String -Path <files> -Pattern '(?<![\w.])([2-9]|[1-9]\d+)\.dp'
```

### Rule 3 — One component per job; extend, never fork
Primitives in `.../ui/components/Components.kt`: `FolioSectionCard`, `FolioChip`,
`FolioTopBar`, `FolioStatusBarBand`, `FolioProgressBar`, `FolioSearchBar`,
`FolioStatusBanner`.

If one is 80% right, add a parameter whose default preserves behaviour at every existing
call site.

**Hard ban in feature code:** `Card`, `Surface`-as-card, `AssistChip`, `FilterChip`,
`TopAppBar`, `LinearProgressIndicator`.

**Benchmark:** exactly 1 card-like composable app-wide (`FolioSectionCard`);
`Select-String -Pattern 'material3.Card|FilterChip\('` returns zero hits outside `Components.kt`.

### Rule 4 — Chips are filters. Never navigation.
`FolioChip` may only mean "narrow what this list shows". Top-level movement uses the nav
bar (§3). Currently violated in `LibraryScreen.kt` ~403–428 (`[Books] [Manga] [Stats]`)
and `SettingsScreen.kt` ~135–149 (category chips); §3 and §3.5 fix both.

**Benchmark:** no `FolioChip` `onClick` calls a navigation function or mutates a top-level
destination state.

### Rule 5 — Semantics on every interactive element
Every clickable gets `contentDescription` (icons) or a text label, and a ≥48.dp × 48.dp
touch target. State-carrying controls expose state via `Modifier.semantics { selected = … }`
or the component's own state parameter.

**Benchmark:** §8.3 test asserts zero `hasClickAction()` nodes lacking text/description,
and zero clickable nodes under 48.dp in either dimension.

### Rule 6 — Motion is consistent and interruptible
Add to `FolioTokens`:
- `motionFast = 120ms` — state flips (chip select, checkbox)
- `motionStandard = 220ms` — enter/exit, crossfade, panel slide
- `motionEmphasis = 320ms` — bottom sheet, full-screen transition

`FastOutSlowInEasing` enter, `LinearOutSlowInEasing` exit. No springs in navigation.

**Benchmark:** no `tween(` with a literal duration in new code — all reference
`FolioTokens.motion*`. Manual: reverse each transition at ~50%; no flicker, no stuck state.

### Rule 7 — All four states, always
Any data-loading surface designs **loading / empty / error / content**. Empty and error each
need (a) one plain-language sentence and (b) exactly one primary action. "Empty" is never a
blank screen.

**Benchmark:** per screen, an instrumented test asserts all four states render their
required elements. No `TODO` or blank branch ships.

### Rule 8 — Settings scope is always visible
Any control writing a reader setting states whose setting it is (§4). A user must never
change a setting and see nothing happen.

**Benchmark:** §4 acceptance criteria pass; `ReaderSettingsSnapshotTest` still passes.

### Rule 9 — No new god-files
No file in `shared/src/composeUi/` or `androidApp/` exceeds **600 lines**. Files over budget
(§6) must shrink as they are touched. A change pushing a file further over budget is rejected.

**Benchmark:**
```powershell
Get-ChildItem -Recurse shared/src/composeUi,androidApp -Filter *.kt |
  ForEach-Object { [pscustomobject]@{F=$_.Name;L=(Get-Content $_.FullName|Measure-Object -Line).Lines} } |
  Where-Object { $_.L -gt 600 }
```
Output must be a subset of the §6 table, every value ≤ its previous number.

### Rule 10 — Ship with tests, or don't ship
Logic → `:shared:desktopTest`. UI → instrumented test (§8). No change reduces test count.

**Benchmark:** `:shared:desktopTest` ≥ 132 passing; `:androidApp:connectedDebugAndroidTest` green.

### Rule 11 — Verify on the device that has extensions installed
The zstd SIGABRT (fixed in `5ee5c66`) reproduced *only* on a device with Tachiyomi/Mihon
extension APKs present, in a **release** build. Smoke-test every release on
`XCR4XOVSJBZXRKQK` (34 extension packages):

```powershell
adb -s XCR4XOVSJBZXRKQK install -r <apk>
adb -s XCR4XOVSJBZXRKQK logcat -c
adb -s XCR4XOVSJBZXRKQK shell monkey -p com.folio.reader -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 30
adb -s XCR4XOVSJBZXRKQK logcat -b crash -d            # must be empty
adb -s XCR4XOVSJBZXRKQK shell pidof com.folio.reader  # must print a pid
```
Then Manga → browse one source (forces a response through `CompressionInterceptor`) and
repeat the crash-buffer check.

**Benchmark:** crash buffer empty and process alive after both launch and a source browse.

### Rule 12 — R8 keep rules for anything reached by JNI or reflection
The zstd crash happened because R8 stripped classes native code resolves via `FindClass`.
Any new dependency shipping a `.so`, or any reflective lookup, gets an explicit `-keep` in
`androidApp/proguard-rules.pro` **with a comment naming the mechanism**, verified in a
**release** build (debug has R8 off and will not catch it).

**Benchmark:** feature exercised in a release build on-device; crash buffer clean.

---

## 2. Coherence contract (the "harmony" rules)

These make independently-built screens feel like one app.

**2.1 Layout skeleton.** Every full screen, top to bottom:
`FolioStatusBarBand` → `FolioTopBar` → scrollable content → nav bar (from the shell).
Horizontal content padding `space3`. Gap between sibling cards `space3`. Gap between rows
inside a card `space2`.

**2.2 One screen, one primary action.** At most one filled button per screen; everything
else outlined, text, or icon. Library's primary action is Import. The reader has none —
the content is the action.

**2.3 Elevation is flat.** Cards use a 1.dp `outlineVariant` border, never a shadow.
`FolioSectionCard` already does this; never add `elevation`.

**2.4 Copy style.** Sentence case everywhere, including buttons ("Import a book", not
"IMPORT"). No exclamation marks, no "Oops". Errors say what happened and what to do:
"Couldn't open this book — the file may have moved. Locate file". Humanised numbers:
"18 min", "3 days", "1,240 words" — never "1080000ms".

**2.5 Iconography.** Material Symbols outlined, 24.dp, using `Icons.AutoMirrored.Filled.*`
wherever a mirrored variant exists. The build currently emits ~12 deprecation warnings for
non-mirrored `Notes`, `Sort`, `MenuBook`, `Toc`, `List` — fix each one you touch. One icon
per concept app-wide: pick it once, reuse it.

**2.6 Progress is one visual language.** Reading progress is a ring when it decorates a
cover, `FolioProgressBar` when it is a row. Never both in one view. Percentages are whole
numbers, no decimals.

**2.7 Theming survives.** Every new surface must be legible in all 34 reader themes and both
app themes. Three-point check: `paper` (light), `midnightneon` (dark, saturated),
`rainbow` (light, high-chroma).

**Benchmark for §2:** a reviewer opens Home → Library → Book detail → Reader → Stats →
Settings and cannot tell which screen was built last. Concretely: identical top-bar height,
card radius, border, horizontal padding and gap rhythm; one filled button per screen; all
three test themes legible.

---

## 3. Navigation overhaul (Android)

### 3.1 Problem being fixed
`androidApp/.../MainActivity.kt` holds a private `sealed interface Screen` (line ~60) plus a
hand-rolled `pushScreen`/`popScreen` stack, duplicated near-1:1 in `desktopApp/.../Main.kt`
(line ~344). Consequences: every new screen edits two files identically; no state
restoration across process death; no deep links; top-level movement is done with chips.

### 3.2 Target structure
Create `androidApp/src/main/java/com/folio/reader/nav/`:

| File | Contents | Max lines |
|---|---|---|
| `FolioDestination.kt` | sealed interface, one entry per destination, each with `route: String` | 120 |
| `FolioNavHost.kt` | `NavHost` wiring routes → screen composables | 250 |
| `FolioNavShell.kt` | `Scaffold` + `NavigationBar`, hosts `FolioNavHost` | 200 |
| `FolioNavBarItem.kt` | the 4 bar items (icon, label, route, badge) | 80 |

Use `androidx.navigation:navigation-compose:2.8.0` (matches the lifecycle 2.8.x already in
`libs.versions.toml`; add as `androidx-navigation-compose`).

### 3.3 Destination map (exact)
Bottom bar, left to right — exactly 4 items, never more:

| Item | Icon | Route | Start destination content |
|---|---|---|---|
| Home | `Icons.Filled.Home` | `home` | §5.4 home surface |
| Library | `Icons.AutoMirrored.Filled.MenuBook` | `library` | books + manga, `FolioChip` filters only |
| Stats | `Icons.Filled.BarChart` | `stats` | §5.5 deep-dive stats |
| More | `Icons.Filled.MoreHoriz` | `more` | settings, tags, quotes, revisit, downloads, extensions |

Non-bar destinations pushed on top (bar hidden): `reader/{bookId}?spine={i}`,
`book/{bookId}`, `manga/{mangaId}`, `mangaReader/{mangaId}/{chapterId}`, `search`,
`settings/{category}`, `extensions`, `mangaDownloads`, `mangaHistory`, `tags`, `quotes`.

**Rule:** the nav bar is visible on exactly the 4 top-level routes and hidden on every
pushed route. No exceptions; no animation of the bar beyond a `motionStandard` fade.

### 3.4 Migration steps (in order, each independently shippable)
1. Add the dependency and the 4 nav files; `FolioNavHost` initially wraps the *existing*
   screen composables unchanged. `MainActivity` renders `FolioNavShell` instead of its
   `when (current)` block. Delete `Screen` and `pushScreen`/`popScreen` from `MainActivity`.
   **Do not touch `desktopApp/Main.kt`** — it keeps its own stack (Rule 1).
2. Move `[Books] [Manga]` from chips into `library` as a two-tab segmented control at the top
   of the list (still filters, so chips remain legal), and delete the `Stats` chip entirely —
   Stats is a bar destination now.
3. Add `rememberSaveable` state hoisting for scroll position and selected filters per destination.
4. Add deep links: `folio://book/{bookId}` and `folio://reader/{bookId}` in the manifest.

### 3.5 Settings restructure
`SettingsScreen.kt` (1270 lines) is split. The `SettingsCategory` chip row (lines ~135–149)
becomes a **list of rows** on `more`, each navigating to `settings/{category}`:

```
More
 ├ Reading            → settings/typography, settings/layout, settings/formatting
 ├ Appearance         → settings/themes
 ├ Sync & backup      → settings/cloud_sync, settings/advanced
 ├ Library tools      → tags, quotes, revisit
 └ Manga              → extensions, downloads, history
```

Each category screen is its own file under
`androidApp/src/main/java/com/folio/reader/settings/` (or `shared/.../ui/settings/` if the
panel is genuinely shared), ≤ 250 lines each.

**Acceptance criteria for §3:**
- `MainActivity.kt` drops below 600 lines and contains no `Screen` sealed interface.
- `desktopApp/Main.kt` unchanged except for imports it no longer needs.
- Back from any pushed route returns to the correct top-level route with scroll position
  intact after process death (`adb shell am kill com.folio.reader`, relaunch).
- `adb shell am start -a android.intent.action.VIEW -d "folio://book/<id>"` opens book detail.
- Nav bar visible on exactly 4 routes, hidden on all others (instrumented test asserts both).
- Zero `FolioChip` used for navigation anywhere.

---

## 4. Settings scope, made visible

### 4.1 What is currently wrong
Fixed in `937069c`: alignment / formattingMode / hyphenation were snapshotted per book at
first open, so Main Settings changes silently did nothing for existing books. The *data* bug
is fixed; the **UX** bug remains — nothing in the UI tells a user that a book can carry its
own copy of a setting.

### 4.2 Required UI
**In the reader settings panel** (`ReaderScreen.kt` → `ReaderSettingsPanel`, line ~1167), add
a segmented control pinned at the top:

```
Applies to:  [ This book ]  [ All books ]
```
- Default selection: `This book`.
- `This book` → writes through `ReaderViewModel.updateSettings` (per-book snapshot).
- `All books` → writes global defaults **and** clears this book's override for the fields
  being changed.
- Built from `FolioChip` — legal, because it scopes a write rather than navigating. Wrap in
  `Modifier.semantics { selected = … }`.

**Per-book override indicator.** Any row whose value differs from the global default shows a
trailing dot (add `FolioTokens.dotIndicator = 6.dp`, `FolioTheme.colors.primary`) with
`contentDescription = "Overridden for this book"`. One "Reset this book to defaults" text
button sits at the bottom of the panel, enabled only when ≥1 override exists.

**In Main Settings → Formatting**, keep the note added in `937069c` and append a count when
applicable: "Applies to all books. 3 books have their own overrides — review". Tapping opens
a list of those books with per-book reset.

### 4.3 Shared logic to add
In `shared/src/commonMain/kotlin/com/folio/reader/settings/ReaderSettings.kt`:
```kotlin
/** Fields this book overrides, for the reader panel's override dots. */
fun BookReaderSettings.overriddenFields(global: ReaderSettings): Set<String>
/** Drops the named fields so the book follows the global defaults again. */
fun BookReaderSettings.clearing(fields: Set<String>): BookReaderSettings
```

**Acceptance criteria for §4:**
- Change alignment in Main Settings → open a book that was opened *before* the change → the
  new alignment is applied. (Regression guard for the original bug.)
- Change font size with `This book` selected → a second book is unaffected.
- Change font size with `All books` selected → the second book *is* affected, and the first
  book's override dot for that row disappears.
- "Reset this book to defaults" disabled with zero overrides, enabled with one.
- `ReaderSettingsSnapshotTest` still passes; ≥4 new tests cover `overriddenFields` and `clearing`.

---

## 5. Stats: integrated, not segregated

Principle: **stats appear where the thing they describe lives.** `ReadingPace.kt` already
provides `readingPaceWordsPerDay(sessions)` and `finishEstimate(totalWords, progress,
bookSessions, paceSessions)` — reuse them; do not reimplement pace maths.

### 5.1 Library cards
In the books grid/list (`LibraryScreen.kt` → `LibraryContent`):
- **Grid:** progress ring overlaid bottom-right of the cover. Diameter
  `FolioTokens.ringSmall = 28.dp`, stroke 3.dp, `FolioTheme.colors.primary` on
  `outlineVariant` track. Hidden when progress is 0 or ≥ 0.99.
- **List/compact:** one `FolioProgressBar` row plus a caption line
  `"42% · ~6 days left"` from `finishEstimate`. Caption omitted when `finishEstimate`
  returns null — never render "null" or an em dash placeholder.

### 5.2 Book detail
Add a `FolioSectionCard` titled "Your reading" above the chapter list, containing:
- Sessions sparkline, last 30 days (bar per day, `primary`, height ∝ minutes).
- Rows: total time read · average session · pace (words/day) · `finishEstimate` string.
- Highlight density: count per chapter as a thin heat strip.
All values from `sessionRepository` + `highlightRepository`; no new tables.

### 5.3 Reader — end-of-chapter chip
When a chapter is completed, show a dismissible chip above the bottom bar for 4s:
`"Chapter 7 · 18 min · a little faster than usual"`. Compares this chapter's minutes/1000
words against the trailing 7-day pace: ±10% → "about your usual pace", faster → "a little
faster than usual", slower → "a little slower than usual". Never blocks a tap; auto-dismiss
`motionStandard` fade.

### 5.4 Home surface (new `home` route)
Vertical list of `FolioSectionCard`s, in this exact order:
1. **Daily goal ring** — `dailyGoalMinutes` (already persisted, currently only decorative)
   vs today's minutes; centre label `"34 / 60 min"`. Below it: streak `"5-day streak"`.
2. **Continue reading** — the ≤3 most recently opened in-progress books, cover + title +
   progress ring + `finishEstimate`. Tapping opens the reader at the saved position.
3. **Because you finished X** — up to 6 covers from the same series/author/tags as the most
   recently completed book. Hidden entirely if fewer than 2 candidates exist.
4. **This week** — minutes read per day as 7 bars, plus books started/finished counts.

Empty state (no books at all): one sentence "Your library is empty — import an EPUB to
start." + one filled "Import a book" button. Nothing else on screen.

### 5.5 Stats deep-dive (`stats` route)
Keep the existing `StatisticsScreen` content (year heatmap, totals, genre breakdown,
per-book leaderboard) as the bar destination. Remove the `statsContent` slot and
`viewModel.statsVisible` from `LibraryScreen` entirely.

**Acceptance criteria for §5:**
- `LibraryScreen.kt` has no reference to `statsVisible` or `statsContent`.
- A book at 42% shows the same percentage on the library card, book detail, and reader —
  whole numbers, no decimals, no drift.
- With `finishEstimate` returning null (fresh book, no sessions), no caption/estimate row
  renders anywhere — no placeholder text, no blank row with padding.
- Daily goal ring reflects a `dailyGoalMinutes` change without an app restart.
- Home's "Because you finished X" is absent when the library has < 2 candidates.

---

## 6. God-file decomposition

Rule 9 sets the ceiling at 600 lines. Current offenders and their required splits — the
"After" column is the maximum for **every** resulting file:

| File | Now | Split into | After |
|---|---|---|---|
| `MangaScreens.kt` | 2853 | `MangaLibraryScreen`, `MangaBrowseScreen`, `MangaDetailScreen`, `MangaDownloadsScreen`, `MangaExtensionsScreen`, `MangaCovers` | ≤500 each |
| `ReaderScreen.kt` | 1840 | `ReaderScreen` (shell), `ReaderChrome` (bars), `ReaderPanels`, `ReaderGestures`, `ReaderDialogs`, `ChapterContent` | ≤450 each |
| `MangaViewModels.kt` | 1820 | `MangaLibraryViewModel`, `MangaBrowseViewModel`, `MangaDetailViewModel`, `MangaDownloadViewModel` | ≤500 each |
| `SettingsScreen.kt` | 1270 | one file per §3.5 category + `SettingsLivePreview` | ≤250 each |
| `LibraryScreen.kt` | 1043 | `LibraryScreen`, `LibraryToolbar`, `LibraryGrid`, `LibraryList`, `LibraryFilters` | ≤350 each |
| `MangaReaderScreen.kt` | 926 | `MangaReaderScreen`, `MangaPager`, `MangaPageCache` | ≤400 each |
| `StatisticsScreen.kt` | 797 | `StatisticsScreen`, `StatsCharts`, `StatsRows` | ≤350 each |
| `TagManagerScreen.kt` | 754 | `TagManagerScreen`, `TagEditSheet` | ≤450 each |
| `BookDetailScreen.kt` | 739 | `BookDetailScreen`, `BookDetailHeader`, `BookReadingCard` (§5.2) | ≤350 each |
| `ReaderViewModel.kt` | 705 | `ReaderViewModel`, `ReadingSessionTracker`, `ReaderPositionStore` | ≤400 each |

**Method:** pure extraction — move code, change nothing else, one file per commit. Behaviour
must be provably unchanged.

**Benchmark:** after each extraction commit, `:shared:desktopTest` count unchanged and all
green; the Rule 9 command's output shrinks monotonically. `Theme.kt` (1113) is **exempt** — it
is a data registry, not logic, and `ThemeSchemeTest` guards it.

---

## 7. Features (Android)

Each entry: what, where, and how it is judged done. Build in the order given within a tier.

### Tier 1 — highest value
**7.1 TTS / read-aloud — REMOVED FROM SCOPE** (user decision, 2026-09-02): no read-aloud
feature in this cycle. Numbering kept so downstream references (§9) stay valid; the
paragraph anchors (`folio-sel:<paragraphIndex>:…` in `PageEngine`) remain available if it is
ever revisited.

**7.2 Dictionary + Wikipedia on selection.** Extend the existing selection action bar
(currently Highlight only). Offline first: a `DictionaryProvider` interface with a bundled
WordNet-style lookup; Wikipedia via the existing Ktor client.
*Done when:* selecting a word offers Define / Wikipedia / Highlight / Copy; Define works in
aeroplane mode; results appear in a bottom sheet that does not lose the selection.

**7.3 Goals, streaks, notifications.** `dailyGoalMinutes` already persists. Add streak
computation to `StatisticsViewModel` and a daily reminder via `WorkManager`.
*Done when:* the Home ring (§5.4) fills live while reading; a streak survives a day where the
goal was met and breaks on one where it was not; the reminder is opt-in, fires once per day,
and never fires while the reader is open.

**7.4 Library-wide full-text search.** A `search` route over the existing search index.
*Done when:* a query returns hits grouped by book with a snippet; tapping a hit opens the
reader at that paragraph; a 3-character query over a 50-book library returns in < 400ms.

**7.5 Export highlights.** Extend `ExportManager` with Markdown and Readwise CSV.
*Done when:* export produces one Markdown file per book with `> quote` blocks, chapter
headings, and notes as nested bullets; the file opens correctly in Obsidian.

### Tier 2 — reader quality
**7.6 Footnote popovers.** Intercept `a[href]` targets resolving inside the same chapter or to
an `<aside epub:type="footnote">`; show in a bottom sheet instead of navigating.
*Done when:* tapping a footnote marker never loses reading position; dismiss returns to the
exact scroll offset.

**7.7 Better justification.** Add `text-wrap: pretty` and `hanging-punctuation: first` to
`ReaderCss.styleSheet`, plus per-language `hyphens` from the chapter's `lang` attribute.
*Done when:* a justified page shows no rivers wider than ~3 spaces; `HtmlRendererTest` gains a
case asserting the new declarations appear in HYBRID and NORMALIZED.

**7.8 Vertical writing mode (CJK).** `writing-mode: vertical-rl` driven by the EPUB's
`page-progression-direction`.
*Done when:* a vertical-RL EPUB paginates right-to-left with correct swipe direction.

### Tier 3 — platform
**7.9 Home-screen widget** (Glance): current book, cover, progress, tap to resume.
**7.10 Quick Settings tile:** resume last book.
**7.11 OPDS catalogs:** browse/download from an OPDS feed URL, reusing the manga
source-browse UI patterns.
**7.12 Sync conflict UI:** `RestFirestoreSync` already carries preconditions; surface
"This device: p.142 · Other device: p.180 — keep which?" instead of silently picking.

---

## 8. Testing

### 8.1 Setup (currently missing entirely)
Add to `androidApp/build.gradle.kts`:
```kotlin
androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
```
Pin exact versions (no ranges), matching the Compose 1.7.6 the project already resolves.

### 8.2 Required instrumented tests
| Test | Asserts |
|---|---|
| `NavigationTest` | 4 bar routes reachable; bar hidden on pushed routes; back returns correctly; scroll survives `am kill` |
| `SettingsScopeTest` | the four §4 acceptance criteria |
| `StateCoverageTest` | loading/empty/error/content for Home, Library, Stats, Book detail |
| `ThemeLegibilityTest` | `paper`, `midnightneon`, `rainbow` — no text with contrast < 4.5:1 |
| `ReaderFlowTest` | open book → change font size → rotate → position and setting retained |

### 8.3 Accessibility test
`AccessibilityAuditTest` walks every screen and asserts: zero `hasClickAction()` nodes without
text or `contentDescription`; zero clickable nodes < 48.dp in either dimension; every image
has a description or is explicitly decorative.

### 8.4 Crash reporting (do this before any feature work)
Both bugs found this cycle were diagnosed only because a device happened to be on a cable.
Add ACRA or Sentry, opt-in, with a visible privacy note.
*Done when:* a forced native crash and a forced Kotlin exception both produce a report
containing the stack trace and build version.

**Benchmark for §8:** `:shared:desktopTest` ≥ 132 green; `connectedDebugAndroidTest` ≥ 20
tests green; accessibility audit reports zero violations.

---

## 9. Sequencing

Each phase ends in a shippable, on-device-verified release. Do not start a phase before the
previous one's acceptance criteria all pass.

| Phase | Contents | Version |
|---|---|---|
| **0 — Safety net** | §8.1 test setup, §8.4 crash reporting, `FolioTokens` motion + ring + dot additions (Rules 2, 6) | 1.0.26 |
| **1 — Navigation** | §3 all steps, §3.5 settings split, §6 splits for `MainActivity`/`SettingsScreen`/`LibraryScreen`, §8.2 `NavigationTest` | 1.1.0 |
| **2 — Scope & trust** | §4 in full, §8.2 `SettingsScopeTest`, §6 split of `ReaderScreen`/`ReaderViewModel` | 1.1.1 |
| **3 — Stats integration** | §5 in full, §6 split of `StatisticsScreen`/`BookDetailScreen`, §8.2 `StateCoverageTest` | 1.2.0 |
| **4 — Reading features** | §7.1–7.5, then §7.6–7.8 | 1.3.0 |
| **5 — Platform** | §7.9–7.12, §6 manga splits | 1.4.0 |

**Every phase, without exception:** run Rule 11's device smoke test on `XCR4XOVSJBZXRKQK`;
confirm the Rule 9 line-count command shrank; confirm `:desktopApp:compileKotlin` is clean and
the desktop UI is visually unchanged (Rule 1); then bump both `androidApp/build.gradle.kts`
(`versionCode` + `versionName`) and `desktopApp/build.gradle.kts` (`packageVersion`), copy
artifacts to `releases/Folio-<version>.apk`, and commit.

Known build constraint: `:desktopApp:packageReleaseMsi` fails under JDK 25 (ProGuard 7.2.2
cannot parse class file v69). Use `:desktopApp:packageMsi`, as every shipped release has.

---

## 10. Definition of done (per change)

1. Rules 1–12 all satisfied.
2. The relevant section's acceptance criteria demonstrably pass.
3. `:shared:desktopTest` ≥ 132 green; instrumented tests green.
4. Rule 9 line-count command output did not grow.
5. Rule 11 device smoke test clean (launch **and** source browse).
6. `:desktopApp:compileKotlin` clean; desktop UI unchanged.
7. Copy reviewed against §2.4.
8. Verified legible in `paper`, `midnightneon`, `rainbow`.
