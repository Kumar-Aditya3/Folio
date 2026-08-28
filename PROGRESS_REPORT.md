# Folio EPUB Reader - Progress Report

**Last Updated**: 2026-08-24
**Project**: Folio - Personal EPUB Reader (KMP + Compose Multiplatform)
**Target Platforms**: Android + Desktop (JVM)

---

## Executive Summary

| Area | Status |
|------|--------|
| Core business logic (`commonMain`) | Complete |
| Platform layer (`commonJvm` / `androidMain` / `desktopMain`) | Complete |
| Compose UI (`composeUi` shared source set) | Complete: library, reader (real HTML rendering + text selection), search, statistics, settings, **book detail, auth, tag manager, quote browser, revisit items** all wired on both Android + Desktop |
| Persistence (raw JDBC + repository layer) | Complete, tested; chapters/stats/sync-queue/devices/tags/quotes/revisits tables live |
| EPUB parsing | Complete, verified against full 22-book corpus |
| Reader engine | Real chapter HTML rendering, scroll-progress tracking, session/word tracking, long-press highlights, bookmarks/highlights/notes |
| Sync (`SyncEngine` + Firestore REST transport) | All entity types push+pull with merge semantics; activates via Firebase Web API key (free Spark plan); `.env` loading on Desktop, Android via string resources |
| Auth & Device Registry | Anonymous + Email/Password via REST Identity Toolkit; per-device sync filtering |
| App entry points (Android + Desktop) | Full navigation: Library → Reader / Settings / Statistics / Search / **BookDetail / Auth / TagManager / QuoteBrowser / RevisitItems** |
| Desktop distribution | `installDist` launcher scripts (`bin/desktopApp.bat`) + fat classpath in `build/install/desktopApp` |
| Android release | Signed `androidApp-release.apk` via `keystore.properties` signing config |
| Test suite (`desktopTest`) | **23/23 passing** |
| **Build status** | **GREEN - all modules compile, test and package** |

**Artifacts produced:**
- `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (18.9 MB)
- `androidApp/build/outputs/apk/release/androidApp-release.apk` (15.4 MB, signed)
- `desktopApp/build/libs/desktopApp-1.0.0.jar`
- `desktopApp/build/install/desktopApp/` - runnable distribution with launch scripts

**Run the desktop app:** `desktopApp\build\install\desktopApp\bin\desktopApp.bat`

**Enabling cloud sync (optional, free):** create a Firebase project with Anonymous
Auth + Firestore, then either:
- Set `.env` file in project root (Desktop): `projectId`, `apiKey`, `storageBucket`
- Set environment variables: `FOLIO_FB_PROJECT_ID`, `FOLIO_FB_API_KEY`, `FOLIO_FB_STORAGE_BUCKET`
- Android: set string resources `folio_fb_project_id`, `folio_fb_api_key`

The engine uses the Firestore REST v1 API + Identity Toolkit anonymous sign-in - no SDK,
works on both platforms under the free Spark plan. Books sync as metadata only (EPUB bodies
stay local). Per-device echo filtering prevents sync feedback loops.

---

## Architecture (as actually implemented)

```
shared/
  commonMain       Models, repository interfaces (13 repos), settings, sync engine,
                   statistics calculators, epub model types, content provider
  commonJvm        EpubParser (kxml2), BookImporter, FileHasher (SHA-256),
                   SearchIndexer, Database (raw JDBC), Jdbc*Repositories,
                   RestFirestoreSync + RestFirebaseStorageSync, NoopStorageSync
  androidMain      AndroidPlatform (app-dir storage, SAF-free file ops)
  desktopMain      DesktopPlatform (rootOverride for tests, JFileChooser-friendly)
  composeUi        All Compose Multiplatform UI:
                   theme (FolioTheme object + CompositionLocal palette),
                   library, reader, settings, statistics, importer screens,
                   auth (AuthScreen), book detail (BookDetailScreen),
                   tags (TagManagerScreen), quotes (QuoteBrowserScreen),
                   revisit items (RevisitItemsScreen), search,
                   shared components, HtmlRenderer (AnnotatedString)
androidApp/        MainActivity (SAF multi-select import) + AppGraph DI root
desktopApp/        Main.kt (Compose Window, JFileChooser import, .env loading) + same DI shape
```

**Dependency injection**: Manual constructor graph (`AppGraph` / `FolioDesktopAppDependencies`).
Koin was removed from the codebase during the rewrite.

**Persistence**: Raw JDBC (`sqlite-jdbc`) behind a small `Database` facade
(`withConnection`, row mappers). SQLDelight was removed earlier; schema is created
by `CREATE TABLE IF NOT EXISTS` statements covering books, reading_positions,
reading_sessions, highlights, notes, bookmarks, series, collections,
book_collections, settings, search_index, tags, book_tags, highlight_tags,
quotes, revisit_items, reading_history, and sync_queue.

**Repository implementations** (`commonJvm/database/JdbcRepositories.kt`):
Book, ReadingPosition, ReadingSession (positions JSON-encoded in TEXT columns),
Highlight, Note, Bookmark, Collection, Series, Settings (JSON blobs under
`global_reader_settings` / `book_reader_settings:<id>` keys), Tag, Quote,
RevisitItem, and a LIKE-based SearchRepository with context snippets.

> Note: Flow-returning repository methods are cold one-shot snapshots of current
> DB state, not live queries. UI refreshes use a `key(refreshTick)` recomposition
> trigger after mutations.

---

## Verification Results

### Test suite (`shared/src/desktopTest`, JUnit5, runs against the real `testbooks/` corpus)

| Test | What it proves | Result |
|------|----------------|--------|
| corpus parses | All 22 EPUBs parse: title/spine/manifest/toc/chapters non-empty, word counts computed | PASS |
| import persists book | Import writes DB record, copies EPUB, hash lookup round-trips, **chapters persisted + spine-ordered** | PASS |
| duplicate rejected | Re-importing identical file fails with `DuplicateBookException` pointing at existing book | PASS |
| position roundtrip | ReadingPosition upsert/load per device, normalized progress, multi-device coexistence | PASS |
| settings persist | ReaderSettings JSON blob save/load through SettingsRepository | PASS |
| highlight CRUD (`RepositoryCrudTest`) | insert/update/soft-delete/restore; visible flows exclude deleted rows | PASS |
| note CRUD | insert/update/delete/restore with typed notes | PASS |
| bookmark CRUD | insert/delete/restore incl. deleted-bookmark queries | PASS |
| collection membership | create/add/remove books/cascade delete | PASS |
| series association | series CRUD + `getBooksInSeries` | PASS |
| position upsert semantics | one row per (book, device), latest wins | PASS |
| settings KV store | generic key-value roundtrip used by sync state | PASS |
| sync queue lifecycle | enqueue -> syncing -> synced/error/retry counting; clearSynced only drops exhausted retries | PASS |
| device registry | upsert/get/deactivate | PASS |
| HTML rendering (`HtmlRendererTest`) | headings/em/strong/lists/blockquote/links render; script+style skipped; malformed input safe | PASS |
| sync push (`SyncEngineTest`) | queued highlight reaches fake transport; queue drained | PASS |
| sync pull LWW (position) | newer remote overwrites stale local; fresh local survives stale remote | PASS |
| sync delete propagation | remote tombstone soft-deletes local annotation | PASS |
| sync own-device skip | entries authored by this device are not re-downloaded | PASS |
| sync secondary entities | collections push to transport; remote quotes merge in; queue drained | PASS |

Run: `.\gradlew.bat :shared:desktopTest` - exit 0, 23/23 passing.

### Full build verification (single invocation, exit 0)

```
.\gradlew.bat :shared:desktopTest :shared:compileKotlinDesktop `
    :shared:compileDebugKotlinAndroid :desktopApp:jar :androidApp:assembleDebug
```

---

## Bugs Found & Fixed by the Corpus Tests

These were all silent or latent parser defects exposed by running the real corpus:

1. **Metadata NPE** - `parseOpf` initialized `metadata = null` but only assigned it
   through `updateMetadata(metadata!!, ...)`; every book crashed on first text node.
   Fixed with a blank-initialized value + blank-title fallback ("Unknown").
2. **Double href resolution** - manifest hrefs were already resolved against the OPF
   base dir, then `parseToc`/`extractCover` prepended the base again producing
   `OPS/OPS/toc.ncx`. Added idempotent `findZipEntry()` used by TOC and cover paths.
3. **Root-level OPF corruption** - `resolveHref("content.opf", href)` produced
   `content.opf/toc.ncx` because `substringBeforeLast('/')` returned the whole
   filename when no slash existed. Rewrote resolver to detect file-vs-dir-vs-empty bases.
4. **UTF-8 BOM crash** - kxml2 throws `PI must not start with xml` on BOM-prefixed
   documents (present in some Calibre-style testbooks). All XML entry points now
   strip the BOM via `bomFree()`.
5. **`linear="yes"` misparsed** - Kotlin's `String.toBoolean()` returns false for
   `"yes"`, silently marking *every* spine item non-linear and yielding zero
   chapters for Random House-style EPUBs. Now accepts yes/no/false per spec.

---

## Build Fixes From This Session

- **Orphaned source set**: `DesktopPlatform.kt` lived in `src/jvmMain/` while the
  target is `jvm("desktop")` whose source set is `desktopMain`; the file was never
  compiled (jar inspection proved it). Moved to `src/desktopMain/kotlin/...`.
- **JVM signature clash** (both platforms): private `val fontsDir` getter collided
  with interface method `fun getFontsDir()`; renamed backing property to
  `fontsDirectory`.
- **Lifecycle artifact**: `lifecycle-viewmodel-ktx` is Android-only and broke the
  desktop variant; catalog alias switched to KMP-capable
  `androidx.lifecycle:lifecycle-viewmodel` (2.8.x).
- **`local.properties`**: backslash paths are escape-mangled by Java Properties;
  wrote `sdk.dir=C:/Users/Administrator/AppData/Local/Android/Sdk`.
- **Android theme resources**: Material3 XML theme not on classpath; replaced with
  framework `android:Theme.Material.NoActionBar` (Compose draws all UI).
- **Launcher icon**: manifest referenced missing mipmaps; added vector
  `drawable/ic_launcher.xml` and repointed `android:icon`.
- **Compose API corrections across `composeUi/`**: CompositionLocal-based theme
  palette, experimental API opt-ins (M3 TopAppBar, foundation combinedClickable),
  icons-core-only icon usage, kotlinx-datetime arithmetic via `DatePeriod`,
  `Clock.System.now()` instead of `Instant.now()`, viewModelScope wrapping for all
  suspend repository calls, `collectAsState(initial = ...)`.
- **Theme invocation**: `FolioTheme` is an object; call sites use
  `FolioTheme.MaterialTheme(darkTheme = false) { ... }`.

---

## What Works End-to-End Today

- Import any of the 22 testbooks (and well-formed EPUB 2/3 generally) on desktop
  via JFileChooser or on Android via the document picker; dedupe by SHA-256 hash
  and ISBN; cover extraction; chapter normalization with word counts; chapters
  persisted to their own table for reader navigation.
- Browse the library grid (real `LibraryScreen`), see progress/status badges,
  jump into Statistics or Search from the top bar.
- **Read a book**: real chapter HTML rendered through `HtmlRenderer` into styled
  text (font family/size/line-height from reader settings), tap to toggle
  chrome, TOC sidebar navigation, bookmarks, highlights and notes with live
  annotation sidebars.
- Progress tracking: scroll position maps to chapter + normalized book progress
  via cumulative word counts; positions saved per device on every change;
  reading sessions accumulate words read and end cleanly when leaving a book.
- Statistics screen: weekly time, streak, finished count, reading patterns
  (avg session length, WPM, favourite day/hour), currently-reading shelf -
  computed from sessions + daily stats tables.
- Full-text search: query titles/authors instantly plus per-book content hits
  with snippets (import-time index).
- **Book detail screen**: tags, collections, series, reading progress,
  revisit flags, last opened timestamp - full book metadata view.
- **Tag manager**: create/edit/delete tags, bulk-assign to books,
  filter highlights by tag, bulk-tag highlight selections.
- **Quote browser**: browse all highlighted text across books, filter by
  book/tag/keyword, sort by date/text, tap to navigate.
- **Revisit items**: flagged paragraphs for later review, mark as resolved,
  filter by type (highlight/note/bookmark), contextual text preview.
- **Auth & cloud sync**: anonymous sign-in with optional email/password,
  per-device sync filtering to prevent echo, push/pull with LWW merge
  for all entity types.
- Cloud sync (optional): Firestore REST transport with anonymous auth; queue-
  based push of books/positions/annotations/sessions/settings plus collections,
  series, tags, quotes and revisit items; pull-side merge with LWW for positions
  and annotations, insert-if-missing union for secondary types, remote-delete
  propagation, and per-device filtering that prevents echo.
- **In-book annotations from the UI**: long-press any paragraph to create a
  highlight (stored with chapter/spine locators, synced across devices);
  native text selection + copy via `SelectionContainer`; manage everything
  from the annotations sidebar.

## Known Gaps / Next Steps

By design (documented decisions, not missing work):
- **Books sync as metadata only** - EPUB bodies stay on the importing device;
  other devices see the book entry but must import the file locally.
- **Settings are push-only** - global reader settings upload to the cloud but a
  device never overwrites its own preferences from remote (no local updatedAt
  watermark exists for them).
- **Secondary types merge as union** - collections/tags/series/quotes have no
  updatedAt watermark, so they converge as id-union; deletes stay local.
- **No undo/soft-delete** - recently deleted items feature not yet implemented.

Known gaps (advertised/expected features not complete today):
- **Pagination** - the reader is scroll-based; there is no paginated layout engine,
  page navigation, or stable page numbers.
- **Precise selection highlights** - long-press highlights capture whole
  paragraphs; character-exact ranges are neither captured nor rendered.
- **Annotation overlay rendering** - partial; annotations persist and sidebars
  work, but highlight spans are not drawn inside the rendered chapter text.
- **Search scopes** - limited to titles/authors plus per-book chapter content;
  annotation/quote/bookmark/tag/series scopes are not implemented.
- **Cloud EPUB file sync** - inactive unless `storageBucket` is configured;
  `RestFirebaseStorageSync` exists but is not wired into the app graphs, so books
  sync as Firestore metadata only (works with projectId + apiKey alone).
- **Custom theme editor** - basic; built-in theme presets only, no editor UI yet
  (settings shows "coming soon").
- **Font import** - wired via the Settings screen (Import TTF/OTF), but the
  embedded-font toggle and live preview remain stubs.

Possible future work:
1. **Live flows** - repositories return one-shot snapshots; replace with
   invalidation-triggered flows if live multi-window refresh is wanted.
2. **Precise selection highlights** - long-press highlights capture whole
   paragraphs; character-exact ranges would need deeper TextLayoutResult work.
3. **jpackage native installers** - `installDist` scripts ship today; add
   `compose.desktop.nativeDistributions` if .msi/.dmg bundles wanted.
4. **Play publishing** - release APK is signed with a local keystore; generate a
   production keystore + AAB when publishing.
5. **Phase 4** - Font management, advanced typography, footnotes, rereads.
6. **Phase 5** - Polish, accessibility, advanced library features.
