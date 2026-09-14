# Folio correctness tranche + reactive book data + settings typography restructure

Grounded in `review-findings.md` (2026-08-30 audit), re-verified against current code on 2026-09-13. Four workstreams, in order:

1. **B0** — settings whole-row overwrite (field-scoped merge; the careful one)
2. **B1/B2/B3/D5** — transactional multi-statement writes + manga cascade delete + restore timestamps
3. **D3/U2 (Option A)** — `bookDataRevision` reactive flows, delete `refreshTick`
4. **U-TYPE** — typography/formatting settings restructure: wording + merge (user-selected)

Out of scope: D1 indexes, D4 SQL-side filtering, manga zoom work (already implemented), desktop N-tranche items, B4 credential encryption, iOS/web.

Baseline that must not regress: **:shared:desktopTest ≥ 132 passing**, both apps compile. Spec rules that apply: Rule 1 (shared composeUi stays platform-neutral), Rule 2 (tokens), Rule 4 (chips are filters only — the "Applies to" scope chips in the reader panel are state selectors, allowed), Rule 9 (no file growth past its §6 count — see mitigations per task), Rule 10 (ship with tests), Rule 11 (device smoke on `XCR4XOVSJBZXRKQK`).

Note on stale audit claims: `showChapterTitle` was listed dead in the audit but is now consumed (`ReaderScreen.kt:373`) — it stays. Manga zoom/pan already shipped. Both verified today.

---

## Task 1 — B0: field-scoped settings merge

### The bug (verified today)
`SettingsRepository.saveGlobalSettings` (`shared/src/commonJvm/.../database/JdbcRepositories.kt:1371`) writes the entire `ReaderSettings` blob. Every caller passes a full object built from a **possibly stale snapshot**, so fields the caller didn't intend to change clobber newer values. Known repro: changing the **app theme** in Settings restores a stale reading `themeId`. Second half: the reader's "All books" write (`ReaderViewModel.updateGlobalSettings`, `ui/reader/ReaderViewModel.kt:406-417`) saves `newSettings` whole-row — if the panel passes effective (book-merged) values, book overrides leak into globals.

### Design decision: serializer-driven diff/apply (zero-drift)
`ReaderSettings` has ~45 fields and grows regularly. A hand-maintained field list *will* drift (the existing `changedFields` already covers only the 17 per-book fields). Instead, add generic helpers next to the data class in `shared/src/commonMain/kotlin/com/folio/reader/settings/ReaderSettings.kt`:

```kotlin
private val settingsMergeJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

/** Names of fields whose encoded value differs between two global rows. */
fun ReaderSettings.diffFields(other: ReaderSettings): Set<String>

/** This row with the named fields replaced by `from`'s values. */
fun ReaderSettings.withFieldsFrom(fields: Set<String>, from: ReaderSettings): ReaderSettings
```

Implementation via `Json.encodeToJsonElement(ReaderSettings.serializer(), …)`:
- **diff**: per-key comparison of the two `JsonObject`s. A key present in one and absent in the other (one side is the default value, `encodeDefaults = false`) counts as changed.
- **apply**: start from this row's `JsonObject`; for each changed name, set `from`'s element, or **remove** the key when `from` omits it (restores the default). Decode the merged object back.

Do **not** touch the existing `changedFields`/`clearing`/`overriddenFields` — different domain (per-book overrides), already correct.

### Repository API (commonMain `Repositories.kt` interface + JDBC impl)
```kotlin
suspend fun mergeGlobalSettings(
    transform: (ReaderSettings) -> ReaderSettings,
    emitSyncEvent: Boolean = true,
): ReaderSettings
```
JDBC impl (`JdbcRepositories.kt`): inside one `db.withConnection` (mutex-held, atomic vs other local writers): read current row → `transform(current)` → `setSettings(KEY_GLOBAL, …)` → emit the `"settings"/"global"/"UPSERT"` event with the **merged** row (only when `emitSyncEvent`) → return merged. Keep `saveGlobalSettings` for the two whole-row-intent callers (below).

### Call-site conversion (every writer, verified by grep `saveGlobalSettings\(`)

| Call site | Conversion |
|---|---|
| `androidApp/.../nav/FolioNavModelImpl.kt:158` `updateSettings` | `changed = updated.diffFields(globalSettings)` (the navModel's state **is** the screen's snapshot); `merged = repo.mergeGlobalSettings { it.withFieldsFrom(changed, updated) }`; set `globalSettings = merged`; `credsChanged` only if one of the 4 cred fields ∈ `changed` (then `restartSync`). |
| `desktopApp/.../Main.kt:1168` (settings save) | Same patch pattern against desktop's `globalSettings` state. |
| `desktopApp/.../Main.kt:805` (font import) | `mergeGlobalSettings { it.copy(customFonts = it.customFonts + font) }` — also removes the read-modify-write race. |
| `androidApp/.../nav/FolioNavModelImporters.kt:38` (font import) | Same merge-with-transform (verify which field it writes). |
| `shared/.../ui/statistics/StatisticsScreen.kt:419` (daily goal) | `mergeGlobalSettings { it.copy(dailyGoalMinutes = value) }`. |
| `shared/.../ui/reader/ReaderViewModel.kt:406` `updateGlobalSettings` | Keep the existing `changed = _settings.value.changedFields(newSettings)` (per-book semantics for the override-clearing). Replace the whole-row save with `mergeGlobalSettings { it.withFieldsFrom(changed, newSettings) }`. **This structurally prevents the book-override leak** even if the panel still passes effective()-derived values. Keep the book-snapshot `clearing(changed)` logic unchanged. |
| `shared/.../sync/SyncEngine.kt` (remote apply) | **Keep whole-row** `saveGlobalSettings(…, emitSyncEvent = false)` — LWW is the intent. Verify the exact call. |
| `shared/.../export/ExportManager.kt:643` (backup restore) | **Keep whole-row** — full replacement is the intent (already `withoutCredentials()`). |

Also check `desktopApp Main.kt` sync-credentials path (~line 805 area, `firebaseCreds`/engine rebuild) — if it does its own read-modify-write of settings, convert to merge.

### Tests (`:shared:desktopTest`, JVM — reuse the existing repo test fixtures used by `SyncEngineTest`)
1. `SettingsMergeTest` — diff/apply round-trip: for **every** element name in `ReaderSettings.serializer().descriptor`, a single-field change is detected by `diffFields` and applied by `withFieldsFrom`, and equal rows produce an empty diff. (Serializer-driven, so future fields are covered automatically — this is the drift guard.)
2. `MergeGlobalSettingsStaleSnapshotTest` — seed global row; snapshot; second writer changes `themeId` through the repo; patch-write `appThemeId` via `diffFields(globalSnapshot)` + `withFieldsFrom`; assert `themeId` survived, `appThemeId` updated, and the sync-event payload equals the merged row.
3. `ReaderQuickSettingsNoLeakTest` — `updateGlobalSettings` with a `newSettings` carrying a book-overridden `fontSize` must not change the global `fontSize` unless it is in the changed set.
4. Manual repro on device (Task 4): open a book whose reading theme differs from global; change the app theme in Settings; return to the reader → reading theme intact.

### Rule 9 note
`ReaderSettings.kt` is already over budget; the helpers add ~40 lines. Mitigate by extracting the `Theme.PRESETS` map (~470 lines, pure data) into `ReaderThemes.kt` in the same package during this task, leaving both files under or at their previous totals.

---

## Task 2 — B1/B2/B3/D5: transactional writes, cascade delete, restore timestamps

### B1 — `deleteBook` (Database.kt:797-837)
Move the 14 DELETEs + highlight-tag orphan cleanup + per-book settings KV delete into **one `db.withTransaction { conn -> … }`**. Keep `runCatching` around **individual statements that may legitimately fail** (the FTS `search_index` delete uses virtual-table syntax) — a swallowed statement error inside a JDBC transaction does not abort it. Net effect: the relational deletes become atomic.

### B2 — manga delete cascade (JdbcMangaRepositories.kt:78-103)
Current `delete(mangaId)` removes only `manga_category_map`, `manga_history`, `manga_library`. Extend, inside one `withTransaction`:
- `DELETE FROM manga_chapters WHERE manga_id = ?`
- `DELETE FROM manga_notes WHERE manga_id = ?`
- `DELETE FROM manga_downloads WHERE manga_id = ?`
- On-disk: delete the manga's download directory via the same file-system abstraction `MangaDownloadManager` uses to write pages (locate the path helper — mangaId-scoped directory — and call it from the repository's delete path through an injected dependency; if the repo has no file-system access today, add a small `suspend (String) -> Unit` cleanup hook the app graph wires to the download manager, so the repo stays storage-agnostic).
- Keep the existing tombstone sync event and `bumpMangaData()`.
- Chapters/notes/downloads are not synced entities — no tombstones needed for them.

### B3 — restore timestamps (JdbcRepositories.kt:752, 863)
`restoreNote` and `restoreBookmark`: add `updated_at = ?` (now) to both UPDATE statements. Without it, sync LWW can re-delete a restored item — violates the project's own tombstone rule.

### D5 — `replaceChapters` (JdbcMangaRepositories.kt:~255)
Wrap the SELECT→DELETE→INSERT sequence in `withTransaction` (it already has `withTransaction` call sites nearby at lines 634/669/706 — follow their shape).

### Audit the remaining multi-statement deletes flagged in the audit
Tag delete, collection delete, series delete (`JdbcRepositories.kt` / Database.kt — locate via `DELETE FROM tags|collections|series`): wrap each in `withTransaction`. **Series delete must also `UPDATE books SET series_id = NULL WHERE series_id = ?`** (audit D6: dangling references today).

### Tests
- `TransactionalDeleteTest`: with the JDBC test DB, drop one of the child tables to force a mid-sequence failure → assert the parent row **still exists** (rollback) for deleteBook and deleteManga. Also assert full success path leaves no child rows.
- `MangaCascadeDeleteTest`: seed manga + chapters + notes + downloads rows + a temp download dir (via the injected hook) → delete → all rows gone, directory gone, `mangaDataRevision` bumped, tombstone event emitted.
- `RestoreTimestampTest`: soft-delete a note, `restoreNote`, assert `updated_at` advanced past the delete time.

---

## Task 3 — D3/U2 Option A: reactive book data, delete `refreshTick`

### Repo layer
1. `Database.kt` (next to `mangaDataRevision` at line 40): add `bookDataRevision: MutableStateFlow<Long>` + `bumpBookData()`.
2. `JdbcRepositories.kt` book repo — call `db.bumpBookData()` after every write to the **books, book_tags, book_collections, series, collections** tables (upsertBook, deleteBook, markOpened, setBookStatus, setCloudState, updateNormalizedProgress, tag/collection/series membership writes). Do **not** bump from `reading_positions`/`reading_sessions` writes (they fire per 1.2s debounce while reading; the books-row progress fields are bumped via markOpened/updateNormalizedProgress instead, which is what lists display).
3. Convert the one-shot flows to revision-driven, mirroring the in-repo template `JdbcDocumentCategoryRepository.observeCategories` (`documentDataRevision.map { … }`): `getAllBooks`, `getBooksByStatus`, `getBooksBySeries`, `getBooksByCollection`, `getCurrentlyReading`, `getFinishedBooks`, `getUnreadBooks`, `searchBooks`, plus the series/collections list flows the library rail consumes.
4. `HomeViewModel` (`ui/home/HomeViewModel.kt`): verify how it loads; convert its data sources to collect the now-reactive flows (combine + `stateIn`) so Home updates after import without a remount.

### Delete the `refreshTick` hack
Remove the field and every increment/wrapper — verified sites:
- `androidApp/.../MainActivity.kt:96` (field), `nav/FolioReaderRoutes.kt:274`, `nav/FolioNavModelImporters.kt:84,155`, `nav/FolioLibraryRoutes.kt:270,287`
- `desktopApp/.../Main.kt:507` (field), `605, 629, 675, 750, 855, 1010, 1035, 1254` (increments), **`973`** (`key(refreshTick) { … }` wrapper — remove the wrapper, keep the content)

For **each** removed increment: name the data it was refreshing and confirm a reactive flow now covers that screen. If a screen loads one-shot on entry only (e.g. BookDetail), removing the tick changes nothing for it (it reloads on navigation anyway).

### Test
`BookDataRevisionTest`: collect `getAllBooks()` (first emission), insert a book through the repo, assert a second emission contains it — without any `refreshTick`.

### Rule 9 note
`JdbcRepositories.kt` is a §6 god-file (1402 lines). Adding bumps + reactive flows grows it; mitigate by extracting the settings-repository implementation (around lines 1360-1400, now including `mergeGlobalSettings`) into `JdbcSettingsRepository.kt` in the same package, and/or the book observe-flows into `JdbcBookFlows.kt`.

---

## Task 4 — U-TYPE: typography/formatting settings restructure (wording + merge)

User decision: **merge the screens AND fix the wording.** The problem, verified in code: text shaping is scattered across four screens (Typography / Layout / Formatting / Themes-holds-text-colour), the mode dropdown speaks in jargon (`Original / Hybrid / Normalized` via `Enum.settingsLabel()`, `SettingsSupport.kt:143`), and the two "let the book be itself" controls live on different screens with interacting semantics ("Use publisher fonts" in General/Themes silently loses to `FormattingMode.ORIGINAL` in `ReaderCss.kt:52`).

### 4a. New shared panel: `TextAndPageSettingsPanel`
Create in `shared/src/composeUi/.../ui/settings/SettingsPanelTextPage.kt` (new file; Rule 9-safe), absorbing the contents of `SettingsPanelTypography.kt`, `SettingsPanelLayout.kt`, `SettingsPanelFormatting.kt`. One live preview, one inheritance escape hatch (the widest blast radius — Typography's wording: typeface, size, weight, spacing, width, margins dropped). Section eyebrows inside the panel:

1. **Typeface** — font family dropdown + "Use publisher fonts" toggle **moved here** from `SettingsPanelGeneral.kt:139` (delete it there), with new copy: "Use the fonts embedded in the book, where it has them. Turning this off is also implied by 'The book's own formatting' below." + "Import a font…" button (from Advanced/General — keep exactly one entry point, remove the other).
2. **Size and spacing** — the six existing sliders (size, weight, line height, letter, word, paragraph).
3. **Page shape** — text width dropdown + margins slider (from Layout).
4. **Alignment** — default alignment dropdown + hyphenation toggle.
5. **Who controls formatting** — replaces the "Formatting mode" dropdown. Plain labels + one-line captions, driven by the same `FormattingMode` enum (no persisted-value change):
   - `ORIGINAL` → "The book's own formatting" — "The publisher's fonts, colours and alignment are kept exactly as made."
   - `HYBRID` → "Mine, except special pages" — "Your type and colours, but the book's own titles and special layouts stay centred." (default)
   - `NORMALIZED` → "Mine everywhere" — "Your settings apply to every paragraph, including title pages."
   
   Implement as label/caption helper functions beside the panel (not a fork of `settingsLabel` — other enums still use it).

**Escaped-scope note:** `FormattingMode` is deliberately *not* per-book (it is excluded from `copyWith`/`toBookSettings` by design — see ReaderSettings.kt:99-105 comment about the justify asymmetry). Keep that; the merged panel's escape hatch lists only the per-book fields it drops, and the "Who controls formatting" section keeps its existing "applies to every book, including already-opened ones" caption.

**Text colour pointer:** one non-interactive row at the foot of the Typeface section: "Text and page colour come from your reading theme — change them in Themes." (No navigation chip; Rule 4.) On Android the row can carry a `TextButton("Open Themes")` wired to the host's category navigation; on desktop `SettingsScreen` selects the THEMES category — pass an optional `onOpenThemes: (() -> Unit)?` parameter, null hides the button (Rule 1 compliant).

### 4b. Screen/category wiring (both platforms)

**Android** (`androidApp/.../settings/` + `nav/FolioLibraryRoutes.kt`):
- New `FolioSettingsCategory.TEXT_PAGE = "text_page"`; delete `TYPOGRAPHY`, `LAYOUT`, `FORMATTING` constants and their `SettingsRoute` branches (FolioLibraryRoutes.kt:486-493). New `SettingsTextPageScreen.kt` (same shape as the deleted three: `SettingsCategoryScaffold` + live preview + the merged panel + one `InheritanceEscapeHatch`).
- Hub (`SettingsHubScreen.kt:111-131`): three rows → one row: title "Text & page", subtitle "Typeface, size, spacing, width, margins and alignment", `last = true` moves to Reader behavior.
- Deep links / `pendingOpenRoute` strings for the deleted categories: keep navigating — map old values `typography|layout|formatting` → `text_page` in the route parser (one `when` line) so bookmarks/intents don't break.

**Desktop** (`SettingsScreen.kt`): delete `SettingsCategory` entries `TYPOGRAPHY`, `LAYOUT`, `FORMATTING`; add `TEXT_PAGE("Text & page")`; the `when` at lines 126-128 collapses to one branch rendering the merged panel; the preview-inclusion set at lines 144-148 becomes `{TEXT_PAGE}`. **Rule 1 check:** `SettingsScreen` is shared composeUi — this change is a rename/merge of existing shared categories, not an Android-only feature, so it stays legal.

### 4c. Reader panel (minimal touch)
`ReaderSettingsPanel` already has the right scope story ("This book / All books") — leave it. One wording alignment only: its Layout `QuickChoiceRow` labels ("Scroll"/"Page"/"Double Page") already match the new plain-language direction; no change.

### 4d. What deliberately does NOT change
- No persisted settings fields are renamed, moved, or re-defaulted (`formattingMode`, `useEmbeddedFonts`, `textWidth`, `margins`, spacing fields all keep their storage) — this is a presentation-layer restructure only, so no migration, no sync impact.
- `ReaderSettingsSnapshotTest`, `ReaderSettingsOverrideTest`, `ReaderCssJustifyPrintTest` must pass unchanged (they pin the semantics we are preserving).
- The reader-side theme slider, override dots and escape hatches stay as they are.

### Tests
- Existing `ReaderSettingsSnapshotTest` / `ReaderSettingsOverrideTest` / `ReaderCssJustifyPrintTest` unchanged and green (semantics preserved).
- New desktopTest `TextAndPageLabelsTest`: the "Who controls formatting" helper maps each `FormattingMode` to its plain label and back (no `settingsLabel()` jargon strings in the new panel — assert via the helper directly).
- Manual (device): More hub → "Text & page" opens the merged screen with live preview; every control moves the preview; publisher-fonts toggle present exactly once app-wide (search for "publisher" across screens); Themes still opens independently; old category routes (`settings/typography` etc., via `pendingOpenRoute` if exercised) land on Text & page.

### Rule 9 note
Three panel files (~330 lines total) collapse into one ~260-line file plus the deletion of two Android screens; net reduction. `SettingsScreen.kt` shrinks by two `when` branches.

---

## Validation (all tasks)

1. `./gradlew :shared:desktopTest` — ≥ 132 existing passing **plus** the new tests above; no test removed.
2. `./gradlew :androidApp:assembleDebug` and `:desktopApp:compileKotlin` — zero new warnings (Rule 1 benchmark).
3. On-device (install release, per Rule 11, device `XCR4XOVSJBZXRKQK`):
   - Import a book → library updates **in place**: no flash, scroll position preserved (was: full remount).
   - Add/rename a tag, change a book status → chips and lists update live.
   - B0 repro: book with per-book reading theme open → Settings → change app theme → back to reader → reading theme unchanged.
   - Delete a manga that has downloads → `adb shell ls` the download dir: gone; chapters/notes rows gone (verify via a fresh add not resurrecting read state).
   - Manga → browse one source (CompressionInterceptor path) → crash buffer empty, process alive.
   - Task 4: "Text & page" flow above; also spot-check the reader panel still writes per-book vs all-books correctly after the settings-merge change (Task 1) — the two interact.
4. Rule 2 spot-check on touched files: no `Color(0xFF…)` outside Theme.kt, no new `.dp` literals (the existing panels' literals move as-is; do not add new ones).

## Risks / notes for the implementing agent
- The B0 merge is deliberately serializer-based so it **cannot drift** when fields are added; do not replace it with a hand-listed diff.
- `withTransaction` already takes the write mutex — never nest it inside `withConnection`/another `withTransaction` on the same connection.
- FTS statement failures inside `deleteBook`'s transaction are expected and swallowed individually — do not "fix" them by removing `runCatching`, and do not let them abort the transaction.
- If `HomeViewModel` turns out to load via one-shot repository calls with no flow, wiring it to the reactive flows is in scope (it is the primary `refreshTick` consumer on both platforms); keep its public `HomeUiState` shape unchanged.
- Sync-applied remote settings do not currently refresh the Android navModel's `globalSettings` snapshot (pre-existing gap, unchanged by this plan — note only).
- Task ordering matters for Task 1 × Task 4: do the B0 merge **before** the screen merge, because the merged panel's writes all flow through `navModel.updateSettings`/`onSettingsChange` — after Task 1 those become field-scoped patches, so the merged screen cannot regress the fix. If executed in the same branch, land Task 1's repo changes first.
- Desktop `Main.kt:973` `key(refreshTick)` removal: verify the desktop Settings screens re-read `globalSettings` state (they take it as a parameter from the same state var the merge updates — after Task 1, `globalSettings` is set from the **merged** row the repo returns, so no remount is needed for settings changes either).

