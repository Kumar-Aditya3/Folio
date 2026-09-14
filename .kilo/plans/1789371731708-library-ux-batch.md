# Folio library UX batch: rail parity, unified search, continuous scroll, cover cache, import picker, scan option

Answers captured from user: friend's glitch **deferred** (not described); scan = **device-wide AND selected-folder options**; search = **unified rail search**; scroll = **seamless forward AND backward**.

Repo conventions to follow throughout: rationale comments referencing `FOLIO_IMPLEMENTATION_SPEC.md` sections (§), one concern per file under the existing package layout, `FolioTheme`/`FolioTokens`/`FolioChip`/`FolioSegmented` for all new UI.

Validation commands (PowerShell, repo root):
- `.\gradlew :shared:desktopTest` (unit tests in `shared/src/desktopTest`)
- `.\gradlew :desktopApp:build`
- `.\gradlew :androidApp:assembleDebug`

---

## Task 1 — Documents rail parity (categories beside the Books/Manga/Documents switch)

**Problem:** books/manga put the mode switch and category/filter chips on one rail; documents stack them (switch above, categories below, plus an always-visible search field) on phones (`shared/src/composeUi/kotlin/com/folio/reader/ui/library/LibraryScreen.kt`, `railContent` for `LibraryMode.DOCUMENTS`, lines ~307–363).

**Change:**
1. Replace the `BoxWithConstraints`/`maxWidth < 600.dp` Column/Row structure with a single horizontally scrollable `LazyRow` rail identical in structure to the books rail:
   - item: `LibraryModeSwitch(libraryMode, onLibraryModeChange)`
   - items: `DocumentCategoryRail` chips (reuse existing composable, `LibraryScreen.kt:879`)
   - when Task 2's search is active: the search slot replaces the category chips (see Task 2 pattern).
2. Delete the always-visible documents `OutlinedTextField` from the rail (its function moves to the unified search in Task 2; the `documentState.query` flow stays — it becomes the unified field's backing store).
3. Keep the `railPx` measurement (`onSizeChanged`) and `topInset` computation unchanged.
4. Desktop uses the same shared `LibraryScreen` composable — no separate change needed (verify `desktopApp/Main.kt` passes the same params, ~line 1030).

**Acceptance:** In all three modes, one rail row: switch first, chips after; no mode shows categories stacked below the switch.

---

## Task 2 — Unified rail search (books, manga, docs) that never destroys the selector

**Problem:** books = full-screen `SearchScreen` (5 scopes) via top-bar icon; manga = `MangaSearchHeader` that **replaces the rail including the switch** (`MangaLibraryScreen.kt:182–192`); docs = always-visible field. Incoherent + selector disappears in manga mode.

**Design (user-selected): all three modes use an inline rail search. The Books/Manga/Documents switch is never removed while searching.**

### 2.1 Shared rail-search pattern
Create a shared composable (suggest `LibrarySearchSlot.kt` in `ui/library`):
- When search is **inactive**: rail = `[LibraryModeSwitch] [mode chips]` (current behaviour, Task 1 structure).
- When search is **active**: rail = one `LazyRow`: first item is a `Row(Modifier.fillMaxWidth())` containing `LibraryModeSwitch` + `OutlinedTextField(Modifier.weight(1f))` (query field, search icon, clear button), followed by scope-chip items. Mode chips are hidden while searching (consistent across modes); the switch is not.
- Scope chips use `FolioChip` as today.

### 2.2 Per-mode wiring
- **Manga** (`MangaLibraryScreen.kt`): replace `MangaSearchHeader`'s monopoly on the furniture band. When `searchActive`, the band's row renders `[railLeading() (the switch)] [field] [In library / All sources chips]`; the existing `SourceSearchResults` results behaviour, debounce (350 ms), `viewModel.query`, `searchScope`, back-handler (`model.mangaSearchActive = false`) all stay.
- **Documents** (`LibraryScreen.kt`): enable the top-bar search icon (`enabled = !documentMode` → always enabled; `LibraryMode.DOCUMENTS -> onDocSearchClick` instead of `Unit`). Field binds `documentLibraryViewModel.query` / `setQuery` (existing filter logic in `DocumentLibraryViewModel.filterDocuments` untouched). No scope chips (single implicit scope). Remove the old always-visible field (done in Task 1).
- **Books** (`LibraryScreen.kt` + `ui/search/`):
  - Add search-active state + hoisted query + scope to the nav model (`FolioNavModelImpl`) or `LibraryViewModel` (follow `mangaSearchActive` precedent in `MainActivity.kt:237`).
  - Scope chips: `Titles / Content / Highlights / Notes / Bookmarks` (existing `SearchScope` enum, `SearchScreen.kt:63`).
  - `Titles` scope: filter the books grid in place — thread the query into `LibraryViewModel.filteredBooks(...)` chain (extend `LibraryState` with `query`, filter `title`/`displayAuthor` contains, same as `SearchScreen` TITLES branch).
  - Non-Titles scopes: render a result list **in the shelf area** (inside the books `SaveableStateProvider`, replacing `LibraryContent` while the scope is active). Extract from `SearchScreen.kt`:
    - the `runSearch` execution (debounce 250 ms, FTS via `searchRepository.searchInBook`, annotation repos) into a small controller class (e.g. `BookSearchController`) with its own state, usable both from the rail search and from the existing full-screen `SearchScreen`;
    - the result row rendering (`SnippetText`, hit `ListItem`s, "No matches" row) into a shared composable (e.g. `BookSearchResults.kt`).
  - Keep `SearchScreen.kt` itself: it remains the **in-reader** search opened from `ReaderTopBar` (`FolioReaderRoutes.kt:224` `SearchRoute`, with `contentBookId` scoping). Only its guts move to the shared pieces.

### 2.3 Consistency details
- Same field visual on all three (one shared composable, `FolioTokens.radiusControl` shape, `FolioTokens.gutter` content padding — match books' current rail padding).
- Query/scope/active state survives navigation (hoisted, like manga's VM query today).
- Search icon in the top bar toggles active state per mode; closing clears or preserves query per current manga behaviour (preserve).

**Acceptance:** Identical search interaction in all three modes; the switch is always visible; books' content/highlight/note/bookmark search still works (in shelf area); manga's source search still works; docs filter still works; reader's in-book search screen still works.

---

## Task 3 — Book reader: continuous scroll across chapters (forward AND backward)

**Problem:** one chapter per WebView document; at the bottom edge `onChapterEnd` → `nextChapter()` does a full document swap (`ReaderViewModel.kt:261`, `ReaderContentLoader.kt`); image-only/blank chapters seize (nothing to scroll; blank pages show a "Next page →" button, `ChapterContent.kt:284–298`).

**Design: in `LayoutMode.CONTINUOUS` (normalized) only, render a *window* of chapters in one document and extend it in both directions as the reader approaches either end. Paged/spread modes unchanged. Document reader (reflowable PDFs/DOCX) is single-section already — no change.**

### 3.1 Window model (shared)
- `ReaderViewModel`: add window state — `chapterWindow` (`fromIndex..toIndex` around a center). `ReaderContentLoader` gains `loadWindowHtml(centerIndex)`: builds ONE html document from sections `[center-1, center, center+1]` (clamped), each chapter's sanitized html wrapped as:
  `<section class="folio-chapter" data-folio-spine="{spineIndex}" data-folio-chapter="{chapterId}" data-folio-href="{href}">…</section>`
  plus a small inter-chapter separator (hairline + spacing via reader CSS).
- Cover chapter (spineIndex 0 with `coverPath`) stays a special case: excluded from the window; the existing cover screen + "Start reading →" remains the entry point (windows start at spine 1). If the saved position is spine 0, behaviour unchanged.
- Blank/short/image chapters become ordinary (possibly tiny) sections — no more dead-ends or in-flow buttons in continuous mode.

### 3.2 Shared JS engine
New `ContinuousEngine` in `shared/src/commonMain/kotlin/com/folio/reader/ui/render/` (beside `PageEngine.kt`), used by **both** platform surfaces instead of the current inline scroll bridge:
- Progress protocol: report per **visible section** — `folio-progress:{fractionWithinSection}:{pageInSection}:{pagesInSection}:{atEnd}` (extend, don't replace, the existing title protocol) plus a new `folio-visible:{spineIndex}` event when the topmost visible section changes. Host uses it to update `currentChapterIndex` (TOC highlight, bookmarking, annotations, position store) live.
- Extension triggers (debounced, one in flight per direction): when `scrollTop + 2×viewportH ≥ lastSection.bottom` → title `folio-extend:fwd:{nonce}`; when `scrollTop ≤ 2×viewportH` from first section top → `folio-extend:bwd:{nonce}`.
- Injection API: `window.__folioAppendSection(spine, html)` and `window.__folioPrependSection(spine, html)` — insert the section node; **prepend must capture `scrollHeight` before insert and add the delta to `scrollTop` after** (re-apply once ~300 ms later to absorb late image layout). Then re-report.
- Trimming: `window.__folioTrimSections(keepFromSpine, keepToSpine)` when the window exceeds `MAX_SECTIONS = 7`; head-trims adjust `scrollTop` by the removed height.
- Namespaced paragraphs: selection/highlight/`p:` locators become section-scoped — paragraphs numbered within their `<section>`, and callbacks carry `(spineIndex, paragraphIndexInChapter)`. Extend the existing `folio-sel:`/`folio-selclear:`/highlight protocols with the spine index.
- Keep the existing tap, link, clear-selection, font-ready and resize/report hooks from the current bridge (`HtmlContentSurface.android.kt:312–360`).

### 3.3 Platform surfaces
- **Android** (`shared/src/androidMain/.../HtmlContentSurface.android.kt`): accept window html (chapterHref key becomes the window range); resource pre-resolution loop must walk **all** sections' `src/href` + CSS `url()` chains (the canonical-path cache already supports mixed chapters); new title-protocol branches (`folio-visible`, `folio-extend`) posted to the host; section injection via `evaluateJavascript` with the html JSON-string-encoded.
- **Desktop** (`shared/src/desktopMain/.../HtmlContentSurface.desktop.kt`): same — `SurfaceCallbacks` gains `onVisibleSection`, `onExtendForward/Backward`; `resolveResources` walks all sections; injection via `executeJS`. Keep the load-token bookkeeping for full reloads.
- Settings/geometry change (`structuralSig`) → rebuild the whole window document centered on the current section, restoring its fraction (same UX as today's full reload).

### 3.4 Host wiring
- `ChapterContent.kt` / `ReaderScreen.kt`: in continuous mode pass window html + `onExtendForward`/`onExtendBackward` (→ `ReaderViewModel.extendWindow(direction)` → loader fetches adjacent chapter html (LRU cache already exists) → surfaces inject). `onScrollProgress` keeps meaning "fraction within current chapter"; `onPageChange` stays chapter-local; `ReadingPosition` persistence unchanged (chapterId/spineIndex/scrollOffset per chapter, updated as the visible section changes).
- Chapter chip (`onChapterEnd`): fires when the viewport crosses fully past a section's bottom (into the next section), keeping the existing once-per-chapter guards. End-of-book keeps today's terminal behaviour.
- Selection/highlight callbacks change signature to include the section's chapter id/spine; `ReaderAnnotations.addHighlight/addBookmark` must target that chapter (not necessarily `currentChapterIndex`). Update `ReaderScreen`'s `pageSelection` handling accordingly.
- `onChapterStart` edge-hop is superseded inside the window (prepend handles it); keep it only for the window's leading edge (first chapter of the book).

**Risks:** scroll anchoring on prepend when images load late (mitigate with the deferred re-fix); very long books (mitigated by `MAX_SECTIONS` trim); locator compatibility (namespaced locators must remain readable by the annotation/locator helpers — verify `model/locators` parsing and update both writer and reader sides together).

**Acceptance:** In continuous mode, scrolling never stops at a chapter boundary in either direction; image-only/blank chapters flow past; position restore, TOC, bookmarks, highlights, chapter chip, progress bar all keep working; paged modes byte-identical behaviour.

---

## Task 4 — Persistent manga cover cache (library thumbnails load instantly on cold start)

**Problem:** `MangaCovers.kt` caches only in memory (`ConcurrentHashMap`, 150 entries, key `"$sourceId:$thumbnailUrl"`); every app start refetches from the network. `mangaCoversDir` exists on both platforms but is unused (`Platform.kt:51`).

**Change:**
1. New `MangaCoverDiskCache` (commonJvm, e.g. `manga/MangaCoverDiskCache.kt`): directory supplied at app start via a configure-at-boot holder (same pattern as `MangaChallenges.solver`, `MainActivity.kt:265`) — set from `graph.platform.fileSystem.mangaCoversDir` in `FolioApplication` (Android) and `Main.kt` (desktop). Null directory → current behaviour (memory only).
2. Load order in `loadMangaCover` (`MangaCovers.kt:36`): memory → disk → `backend.fetchCover`; on network success write-through to disk (and memory, as today).
3. Disk filename: hex sha256 of the cache key + raw bytes stored (decoder sniffs content — no extension needed). Key includes `thumbnailUrl`, so a source changing its cover naturally invalidates.
4. Eviction: none in v1 (covers are small; directory is app-private). Note in code comment.
5. `coverPath`-based covers (local source, downloaded) already read from files — unchanged.

**Acceptance:** With network unavailable after a previous session, all previously seen manga covers (library grid/list, detail, Home plates) render instantly from disk; no functional change for `coverPath` entries.

---

## Task 5 — Android import picker: fix the broken/slow system search

**Problem:** books/docs and manga imports launch `ActivityResultContracts.OpenMultipleDocuments` with a primary MIME of `application/epub+zip` / `application/x-cbz` (`MainActivity.kt:71–87, 138–146`). In OPEN_DOCUMENT mode the DocumentsUI search recursively walks storage providers AND filters by that MIME (EPUBs are frequently indexed as `application/octet-stream`), producing "no results" or long hangs. Apps using ACTION_GET_CONTENT (what the user calls "normal android file system") search through the indexed MediaProvider surfaces instead.

**Change:**
1. Swap `pickContent` and `pickMangaArchives` to `ActivityResultContracts.GetMultipleContents`, launched with `"*/*"`. Keep the manga "Archive files / Folder" choice dialog; keep `OpenDocumentTree` for the folder option.
2. Safety is already handled downstream: `IncomingContentCoordinator` detects format from content and returns `Unsupported` with a status message (`IncomingContentCoordinator.kt:26`); `copyIncomingUri` enforces size caps; duplicate detection exists. No new validation needed — but verify `GetMultipleContents` results (possibly non-openable/cloud URIs) flow through `contentResolver.openInputStream` correctly (they do — copy path is provider-agnostic).
3. Update `INCOMING_CONTENT_MIMES`/`MANGA_ARCHIVE_MIMES` usage accordingly (arrays become a single `"*/*"` input).

**Acceptance:** On a device with EPUBs/PDFs in storage, tapping the search icon in the import picker returns them quickly, matching the behaviour of other apps' pickers; unsupported picks still fail gracefully with the existing status message.

---

## Task 6 — Scan option for ebooks and docs (settings; device-wide and selected folder)

**Design (user-selected): a scanning feature with two scopes — selected folder(s) and device-wide — exposed as settings, plus a manual trigger.**

### 6.1 Settings storage (raw keys via `SettingsRepository`, `KEY_MANGA_DOWNLOADS_LOCATION` precedent)
- `library.scan.scope` = `off | folder | device`
- `library.scan.folder` = SAF tree URI (Android) / absolute path (desktop)
- `library.scan.on_start` = boolean (default off)

### 6.2 Scanner (shared core + platform file sources)
- Common `LibraryScanCoordinator` (commonJvm, beside `importer/`): takes a list of staged candidate files, dedupes (the `IncomingContentCoordinator` already dedupes by hash), imports, and reports `ScanSummary(imported, duplicates, skipped, failed)`.
- **Android sources:**
  - Device-wide: `MediaStore.Files` query for supported MIMEs + extension fallback (`.epub/.pdf/.docx/.odt/.txt/.html` — match `DocumentFormatDetector`'s supported set). Fast and indexed; runtime permission `READ_EXTERNAL_STORAGE` requested from the settings screen when enabling device scope (permission-launcher pattern exists in `SettingsMangaScreen.kt:54`).
  - Folder: persisted SAF tree (take persistable permission, `changeMangaDownloadsLocation` pattern, `FolioNavModelImporters.kt:198`), walked via `DocumentFile`.
  - Candidates copied to cache temp files (reuse the `importContentUris` copy helper) before import.
- **Desktop sources:**
  - Folder: Swing directory chooser (`Main.kt` dialog patterns), `Files.walk` filtered by extension.
  - Device-wide maps to the user profile's common folders (Desktop, Documents, Downloads) — a full fixed-drive walk is too slow; document this definition in the settings subtitle.
- Scan on app start: check in `FolioApplication`/graph init (mirrors `graph.startSync(appScope)`); manual "Scan now" button in settings; results surfaced through the existing `importStatus` mechanism.

### 6.3 Settings UI
- **Android:** new row in `SettingsHubScreen` "Library tools" section — "Library scanning", subtitle "Import new ebooks and documents automatically" → new `SettingsLibraryScanScreen` (androidApp settings package, `FolioSettingsCategory` entry + `SettingsScaffold` registration): scope selector (Off / Folder / Device), folder picker button, "Scan on app start" switch, "Scan now" button with last-scan summary.
- **Desktop:** equivalent panel wired into the shared `SettingsScreen` host (`ui/settings/`, Advanced or a new `SettingsCategory` entry — follow existing category plumbing).

**Acceptance:** With scope=folder and a folder containing new EPUBs/PDFs (plus already-imported ones), "Scan now" imports only the new files and reports counts; scope=device (Android) finds books in Downloads/Documents without hanging; disabling stops all scanning; no duplicates created on re-scan.

---

## Out of scope (explicit)
- The friend's glitch — never described; revisit when the user provides details.
- Manga reader scroll behaviour (webtoon mode already extends forward seamlessly; paged modes are pagers by design).
- Cloud sync interactions with scan-imported books beyond what the existing import path already does.

## Suggested implementation order
1. Task 1 (small, standalone UI) → 2. Task 5 (small, Android-only) → 3. Task 4 (small, isolated) → 4. Task 2 (medium, touches three rails + search refactor) → 5. Task 6 (medium, new subsystem) → 6. Task 3 (largest; shared JS + both surfaces + position model).

Each task lands independently; run the three validation commands after each.
