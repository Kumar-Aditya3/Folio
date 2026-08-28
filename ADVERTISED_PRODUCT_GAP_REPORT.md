# Folio Advertised Product Gap Report

Date: 2026-08-25

This report compares the product described in `README.md`, `PHASES.md`, and
`PROGRESS_REPORT.md` against the code currently present in this repository.

The short version: the project is a real Kotlin Multiplatform EPUB reader with
Compose UI, local import, raw JDBC persistence, reader rendering, annotations,
statistics scaffolding, and Firestore REST sync for metadata/entities. The
advertised product, however, describes a more complete reader than the code
currently delivers. The largest gaps are cloud storage/file sync, product
navigation, export/backup, advanced typography, platform polish, and documentation
accuracy.

## Evidence Reviewed

- `README.md`
- `PHASES.md`
- `PROGRESS_REPORT.md`
- Gradle module setup and dependency catalog
- Android and Desktop app entry points
- Shared models, repositories, database schema, EPUB importer/parser, sync engine
- Compose UI screens for library, reader, settings, statistics, search, auth,
  book detail, tags, quotes, and revisit items
- Desktop test suite and build verification

Verification run:

```powershell
.\gradlew.bat :shared:desktopTest :shared:compileKotlinDesktop :shared:compileDebugKotlinAndroid :desktopApp:jar :androidApp:assembleDebug
```

Result: `BUILD SUCCESSFUL`. The desktop test XML reports show 23 tests passing.

## Product-Level Summary

| Area | Advertised | Current Reality | Gap |
|---|---|---|---|
| Core EPUB import/read | Local-first EPUB reader | Mostly present | Needs deeper reader polish and exact locators |
| Android + Desktop | Both targets | Both compile and have entry points | Several platform features missing |
| macOS | Planned / preparation claimed | No macOS target or packaging | Not implemented |
| SQLDelight | Advertised in README/PHASES | Removed; raw JDBC used | Docs stale |
| Koin DI | Advertised in README/PHASES | Removed; manual graph used | Docs stale |
| Library organization | Series, collections, tags | Repositories exist; UI partly exposes | Management flows incomplete |
| Reader | HTML rendering, selection, annotations | Present, but simplified | No precise selection ranges/pagination/footnote UX |
| Search | Full library and scoped search | Title/author + chapter content search | Missing annotation/quote/bookmark/series scopes |
| Statistics | Sessions, speed, heatmap, patterns | Models/repos/UI exist | Data completeness and lifecycle unclear |
| Firebase sync | Auth, Firestore, Cloud Storage | Firestore metadata/entity sync partly present | Storage/file sync inactive |
| Export/backup | Markdown/JSON/CSV, full backup/restore | Minimal settings-count backup only | Mostly missing |
| Advanced typography | Font import, embedded fonts, hyphenation, widow/orphan | Settings/models partly present | Much is stubbed or not enforced |
| Accessibility/platform polish | Screen readers, keyboard, wake lock, tray, file associations | Some semantics/settings exist | Most claimed polish absent |

## Documentation Accuracy Gaps

### SQLDelight Is Advertised But Not Used

Advertised:

- `README.md` says SQLDelight is the database layer.
- `README.md` Phase 1 marks SQLDelight schema and implementations complete.
- `PHASES.md` lists SQLDelight as the database technology and mentions Android/JVM
  SQLDelight drivers.

Current code:

- `shared/build.gradle.kts` depends on `sqlite-jdbc`, not SQLDelight.
- `Database.kt` creates schema manually with `CREATE TABLE IF NOT EXISTS`.
- Repository implementations are raw JDBC classes such as `JdbcBookRepository`,
  `JdbcHighlightRepository`, and `JdbcSearchRepository`.

Missing to match the advertised product:

- Either reintroduce SQLDelight schema files and generated drivers, or update all
  docs to say raw JDBC is the chosen persistence layer.

Recommended documentation fix:

- Treat `PROGRESS_REPORT.md` as the source of truth here: SQLDelight was removed.

### Koin Is Advertised But Not Used

Advertised:

- `README.md` and `PHASES.md` list Koin dependency injection.
- `README.md` marks Koin dependency injection complete.

Current code:

- Android uses `AppGraph` in `FolioApplication.kt`.
- Desktop uses `FolioDesktopAppDependencies` in `Main.kt`.
- No Koin module wiring exists in the active source.

Missing to match the advertised product:

- Either add real Koin modules and app startup integration, or remove Koin from
  the advertised architecture.

Recommended documentation fix:

- State that dependency wiring is manual constructor injection.

### Source Set Names Are Stale In Docs

Advertised:

- Docs refer to `jvmMain` for desktop implementation.

Current code:

- The KMP target is `jvm("desktop")`.
- The desktop source set is `desktopMain`.
- There is a custom `commonJvm` source set shared by Android and Desktop.

Missing to match the advertised product:

- Update docs from `jvmMain` to `commonJvm` / `desktopMain` where appropriate.

## App Navigation And Product Surface Gaps

### Several Screens Exist But Are Not Actually Reachable From The Main Product

Advertised:

- `PROGRESS_REPORT.md` says app entry points provide full navigation to Library,
  Reader, Settings, Statistics, Search, BookDetail, Auth, TagManager,
  QuoteBrowser, and RevisitItems.

Current code:

- Android and Desktop define route states for those screens.
- The Library top bar only exposes Search, Import, Statistics, and Settings.
- Clicking a book opens Reader directly, not BookDetail.
- There is no visible navigation path to Auth, TagManager, QuoteBrowser, or
  RevisitItems from the primary Library surface.

Missing to match the advertised product:

- Add visible product navigation for:
  - Book detail from a book card/list item or context menu.
  - Auth/sync account screen from settings or sync badge.
  - Tag manager from library/settings/book detail.
  - Quote browser from library/reader/statistics.
  - Revisit items from library/reader/statistics.

Concrete code locations:

- `androidApp/src/main/java/com/folio/reader/MainActivity.kt`
- `desktopApp/src/main/java/com/folio/reader/Main.kt`
- `shared/src/composeUi/kotlin/com/folio/reader/ui/library/LibraryScreen.kt`

### Book Detail Actions Are Stubs

Advertised:

- Book detail is described as a full metadata view with tags, collections, series,
  progress, revisit flags, and editable metadata.

Current code:

- `BookDetailScreen` exists.
- App entry points pass empty callbacks for edit, series, and collection actions.
- Tag click only routes to the generic tag manager.

Missing to match the advertised product:

- Editable book metadata.
- Assign/remove tags from book detail.
- Assign/remove collections from book detail.
- Assign/change series and series number.
- Navigate from book detail chips to filtered views.
- Persist edits and refresh library/detail state.

### Tag Manager Is Only Partly Integrated

Advertised:

- Tags can be created/edited/deleted and bulk-assigned to books/highlights.
- Highlights can be filtered by tag and navigated.

Current code:

- `TagManagerScreen` and `TagManagerViewModel` exist.
- Main app wiring passes `deleteTag = { }`, so delete from the app route is not
  actually wired to persistence.
- `onHighlightClick` and `onBookClick` callbacks are empty in both Android and
  Desktop app entry points.

Missing to match the advertised product:

- Real tag deletion wiring.
- Book navigation from tag detail.
- Highlight navigation from tag detail to the reader location.
- Bulk-assign UI and persistence flow for books/highlights.
- Clear path to open tag manager from the primary app.

### Quote Browser Is Only Partly Integrated

Advertised:

- Quote browser can browse highlighted text, filter, sort, and navigate back into
  source context.

Current code:

- `QuoteBrowserScreen` exists.
- App route passes `onQuoteClick = { }`, so quote click does not navigate.
- Quote creation from highlights is not clearly wired from the reader annotation
  creation flow.

Missing to match the advertised product:

- Create quotes from selected highlights or note actions.
- Navigate from a quote to its book/chapter/locator in the reader.
- Expose Quote Browser in the main UI.
- Ensure quote filters include all advertised dimensions and are backed by data.

### Revisit Items Are Only Partly Integrated

Advertised:

- Revisit items can be flagged from highlights/notes/bookmarks, browsed globally,
  resolved, filtered, and navigated.

Current code:

- `RevisitItemsScreen` exists.
- App route passes `onItemClick = { }`, so item click does not navigate.
- Reader annotation actions do not clearly expose "revisit later" as a first-class
  action.

Missing to match the advertised product:

- Add revisit creation from highlight/note/bookmark UI.
- Navigate from revisit item to book/chapter/locator.
- Expose Revisit Items from the main app.
- Sync resolved/unresolved state robustly across devices.

## Library And Organization Gaps

### Library Filtering Is Narrower Than Advertised

Advertised:

- Series and collections organize the library.
- Library views are filtered by series/collection.
- Advanced library features are claimed in later phases.

Current code:

- Repositories support series and collection queries.
- `LibraryScreen` exposes status filter chips only.
- There is no visible collection/series/tag filter UI in the main library.

Missing to match the advertised product:

- Filter controls for series.
- Filter controls for collections.
- Filter controls for tags.
- Collection and series management screens or dialogs.
- Book assignment flows from library selection/book detail.

### Library Selection Mode Is Stubbed

Advertised:

- Phase docs imply bulk organization workflows.

Current code:

- `selectedBooks` and `isSelectionMode` exist in `LibraryScreen`.
- Long-click handlers contain TODO comments and do not toggle selection.

Missing to match the advertised product:

- Long-press/secondary-click selection mode.
- Bulk actions for delete, assign tags, add to collection, set status, export,
  and sync/storage actions.

### Duplicate Detection Is Less Complete Than Advertised

Advertised:

- `PHASES.md` says duplicate detection uses ISBN > title+author > hash.

Current code:

- Import checks hash first.
- Then it checks ISBN.
- A comment mentions title+author, but no title+author duplicate check is present.

Missing to match the advertised product:

- Normalize title and author names.
- Detect same title+author when ISBN is absent.
- Decide whether title+author duplicate should block import or ask the user.
- Add tests for metadata-only duplicate detection.

## Reader Experience Gaps

### EPUB CFI Is Advertised But Not Implemented

Advertised:

- `README.md` says precise reading positions use EPUB CFI.

Current code:

- Positioning uses `spineIndex`, `paragraphIndex`, `contentLocator`, scroll
  fraction, and character offset estimates.
- Highlight locators are simple strings such as `/<spine>/<paragraph>:0`.

Missing to match the advertised product:

- Real EPUB CFI generation and parsing.
- CFI persistence for positions, bookmarks, highlights, and notes.
- CFI resolution back into rendered content.
- Migration from current locator strings if preserving existing user data.

Recommended documentation fix:

- If CFI is not planned soon, advertise the current locator model instead.

### Reader Pagination Is Not Implemented

Advertised:

- `README.md` lists a reader component with rendering and pagination as planned.
- `PHASES.md` mentions page navigation and layout modes.

Current code:

- Reader uses a scroll-based content area.
- Settings include layout-related options, but there is no true paginated layout
  engine.

Missing to match the advertised product:

- Page layout measurement.
- Page navigation controls.
- Stable page numbers or synthetic locations.
- Reflow on font/width/theme changes.
- Persist page location accurately.

### Text Selection Highlights Are Not Precise

Advertised:

- Highlights are described as text selection with locator persistence.

Current code:

- Native selection/copy is available via `SelectionContainer`.
- Long-press highlight captures the whole paragraph around the press location.
- The progress report already notes that character-exact ranges are a future gap.

Missing to match the advertised product:

- Capture actual selected character range.
- Store start/end offsets or CFI range.
- Render highlight spans inside the chapter text.
- Support overlapping highlights.
- Add desktop mouse selection and Android selection action integration.

### Annotation Rendering Is Incomplete

Advertised:

- Rich annotations: highlights, notes, bookmarks, quotes, revisit items.

Current code:

- Annotation repositories and sidebars exist.
- Highlight creation stores selected text and locators.
- The reader primarily displays HTML text; highlight overlays/spans are not a
  complete visible annotation layer.

Missing to match the advertised product:

- Draw highlight ranges in the text.
- Tap/click highlight to edit color/note/tags/revisit/quote.
- Attach notes to precise selected text.
- Bookmark labels and navigation UI.
- Deleted/recently-deleted management.

### Internal Links And Footnotes Are Partial

Advertised:

- Internal link handling, footnotes, cross references, hover popups on desktop,
  Android bottom sheets or inline expansion.

Current code:

- Links are parsed into annotations and clicks are routed.
- Desktop attempts to browse external URLs.
- There is no clear footnote popup, bottom sheet, inline expansion, or return
  stack UX.

Missing to match the advertised product:

- Resolve fragment IDs within current chapter.
- Resolve links across chapters.
- Footnote preview UI.
- Back-to-previous-location behavior.
- External link confirmation and platform-safe opening.

### Images, SVG, Audio, And Video Are Placeholders

Advertised:

- Later phase claims SVG/audio/video placeholders and image lazy loading.

Current code:

- `HtmlRenderer` appends spaces for `img`, `svg`, `audio`, and `video`.
- Covers are extracted and displayed separately.
- Inline EPUB media is not rendered.

Missing to match the advertised product:

- Inline image extraction and rendering.
- SVG display or explicit placeholder UI.
- Audio/video placeholder components.
- Resource path resolution from manifest/hrefs.
- Lazy loading/caching of chapter assets.

## Search Gaps

### Search Scopes Are Overclaimed

Advertised:

- Search scopes include chapter, book, series, library, highlights, notes,
  bookmarks, and quotes.

Current code:

- Search UI filters titles/authors in memory.
- It searches indexed chapter content per book.
- There is no visible search mode for highlights, notes, bookmarks, quotes,
  tags, collections, or series.

Missing to match the advertised product:

- Unified search index or query fan-out across:
  - Books
  - Chapters
  - Highlights
  - Notes
  - Bookmarks
  - Quotes
  - Tags
  - Series
  - Collections
- Result type labels and icons.
- Navigation target per result type.
- Tests for all scopes.

### Search Navigation Ignores Exact Hit Location

Advertised:

- Results navigate to position.

Current code:

- Search result click opens the book/reader.
- It does not pass a chapter index, locator, or highlighted search position into
  the reader route.

Missing to match the advertised product:

- Reader route accepting search target.
- Jump to chapter/spine index.
- Scroll to result snippet or locator.
- Temporary result highlighting.

## Sync And Cloud Gaps

### Cloud Storage Is Implemented As A Class But Not Productized

Advertised:

- Firebase Cloud Storage.
- EPUB upload/download.
- Cover image sync.
- Storage states such as `LOCAL_ONLY`, `UPLOADING`, `SYNCED`, `REMOTE_ONLY`,
  `SYNC_ERROR`.
- Storage manager: local size, remove local copy, keep cloud.

Current code:

- `RestFirebaseStorageSync` exists and implements upload/download/delete methods.
- Android and Desktop inject `NoopStorageSync`.
- `SyncEngine` accepts `StorageSync` but does not call it during book sync.
- `PROGRESS_REPORT.md` says EPUB bodies stay local.

Missing to match the advertised product:

- Wire `RestFirebaseStorageSync` in app graphs when a storage bucket is configured.
- Add book upload on import or explicit "upload" action.
- Add cover upload.
- Add remote-only book state and download action.
- Update `Book.cloudState` during upload/download/error.
- Add storage progress UI.
- Add local file removal while keeping metadata.
- Add retry/resume behavior tested against fake storage.
- Add security rules / bucket rules documentation.

Recommended product decision:

- Either ship metadata-only sync and remove Storage claims, or implement storage
  end-to-end. The current docs say both things in different places.

### Firebase Auth UI Is Not Connected To Real Auth

Advertised:

- Anonymous and email/password auth.
- Auth screen.

Current code:

- Sync transport authenticates internally using REST Identity Toolkit when
  configured.
- `AuthScreen` exists, but app route callbacks just return to Library.
- There is no visible route to Auth in the main app.
- There is no user account/session state shown in the UI.

Missing to match the advertised product:

- Expose Auth screen from settings/sync UI.
- Wire sign-in/sign-up callbacks to a real auth service.
- Store/display current user state.
- Sign out.
- Error handling and loading states tied to real network calls.
- Trigger sync after successful auth.

### Sync Queue Is Not Automatically Fed By Most UI Mutations

Advertised:

- Queue-based push of books, positions, annotations, sessions, settings,
  collections, series, tags, quotes, and revisit items.

Current code:

- `SyncEngine` has enqueue helper methods for several core entities and settings.
- Import/book creation and many UI repository calls do not obviously enqueue sync
  items at the same time.
- Settings screen saves settings but does not call `enqueueSettingsChange` from
  the app route.
- Tag manager route calls repositories directly and does not enqueue tag changes.

Missing to match the advertised product:

- Central mutation service or repository decorators that always enqueue sync.
- Queue entries for:
  - Book imports/updates/deletes
  - Position updates
  - Session starts/ends
  - Highlight/note/bookmark CRUD
  - Settings changes
  - Tags, collections, series changes
  - Quotes and revisit items
- Tests that UI-level mutations create queue records.

### Settings Sync Is Push-Only And Incomplete

Advertised:

- Settings sync global + per-book.

Current code:

- `pushSettings` serializes global `ReaderSettings`.
- `bookSettings` is sent as an empty map.
- Remote settings fetch is defined in the interface but not included in the
  normal fetch/apply path.
- `PROGRESS_REPORT.md` correctly lists settings as push-only.

Missing to match the advertised product:

- Persist setting timestamps/watermarks.
- Sync per-book settings.
- Pull remote settings with conflict policy.
- Expose conflict/overwrite behavior to users.

### Secondary Entity Deletes Do Not Sync

Advertised:

- Tags, collections, series, quotes, revisit items sync.

Current code:

- Secondary entities merge as insert-if-missing union.
- Deletes for secondary entities are local-only according to the progress report.

Missing to match the advertised product:

- Tombstones or `isDeleted`/`deletedAt` fields for secondary entities.
- Delete operations in Firestore transport.
- Pull-side delete propagation.
- UI recovery/recently-deleted behavior if desired.

### Device Registry Is Not Productized In UI

Advertised:

- Device registry, per-device status, sync status UI.

Current code:

- Device repository exists.
- Sync status models/components exist.
- The main app does not clearly expose device management or per-device sync
  status as a complete settings screen.

Missing to match the advertised product:

- Device list UI.
- Current device naming.
- Deactivate/remove device flow.
- Last sync/last seen display.
- Per-device conflict and sync diagnostics.

## Export, Import, Backup, And Migration Gaps

### Annotation Export Is Missing

Advertised:

- Export annotations as Markdown, JSON, and CSV.

Current code:

- `ExportManager` does not export highlights, notes, bookmarks, quotes, tags, or
  selected book data.
- There are no Markdown or CSV export functions.

Missing to match the advertised product:

- Export format models.
- Markdown export for selected/all books.
- JSON export containing annotations and locators.
- CSV export for spreadsheet use.
- UI to choose scope and destination.
- Tests for escaping, ordering, and round-trip JSON.

### Backup/Restore Is Minimal

Advertised:

- Backup/restore library metadata, progress, annotations, and optionally EPUBs.

Current code:

- `FolioBackup` stores version, exportedAt, deviceId, globalSettings, and counts.
- Counts for highlights, notes, and bookmarks are hardcoded to zero.
- Import restores only global settings.
- Settings UI says export/backup and restore will be available in a future update.

Missing to match the advertised product:

- Full backup schema with:
  - Books
  - Chapters
  - Positions
  - Reading sessions/history/statistics
  - Highlights
  - Notes
  - Bookmarks
  - Tags and tag mappings
  - Collections and memberships
  - Series associations
  - Quotes
  - Revisit items
  - Reader settings and per-book settings
  - Optional EPUB and cover files
- Restore merge/replace modes.
- Versioned migrations.
- Data validation and conflict handling.
- UI flow and tests.

### EPUB Replacement And Migration Are Not Evident

Advertised:

- Detect same book/different hash.
- Map old positions by chapter ID, title, nearby text, or percentage.

Current code:

- Import rejects same hash and ISBN duplicates.
- There is no clear replacement workflow or position remapping service.

Missing to match the advertised product:

- Replacement detection flow.
- User confirmation UI.
- Chapter alignment algorithm.
- Annotation/position remap logic.
- Tests with modified EPUBs.

## Advanced Reader And Typography Gaps

### Font Import Is Not Wired Into The UI

Advertised:

- Font import TTF/OTF into app fonts directory.
- Font list in settings with preview.
- FontManager + FontFamily API.

Current code:

- `FontManager` exists in `commonJvm`.
- `SettingsScreen` has an "Import TTF/OTF Font" button.
- The button callback is a stub in the settings panels.
- App dependency graphs do not expose or use `FontManager`.

Missing to match the advertised product:

- Platform file picker for font import.
- App graph `FontManager` dependency.
- Save imported fonts into settings.
- Load custom fonts into Compose `FontFamily`.
- Preview imported font before selecting.
- Remove font from disk and settings.

### EPUB Embedded Font Toggle Is A Stub

Advertised:

- EPUB embedded font toggle.

Current code:

- Settings UI shows "Use EPUB embedded fonts".
- It is hardcoded checked and has TODO callback.

Missing to match the advertised product:

- Setting field for embedded font preference.
- Parse embedded font declarations/resources.
- Register embedded fonts per book.
- Apply them in renderer based on formatting mode/user preference.

### Hyphenation, Justification, Widow/Orphan Handling Are Not Fully Implemented

Advertised:

- Language-aware hyphenation.
- Justification controls.
- Widow/orphan handling.
- Chapter heading typography separate from body.
- Variable font weight support.

Current code:

- Settings include fields such as `hyphenation`, `alignment`, `fontWeight`,
  `wordSpacing`, and themes.
- There is no dedicated language-aware hyphenation engine.
- No widow/orphan layout engine is visible.
- Variable font detection is a file-size heuristic in `FontManager`.

Missing to match the advertised product:

- Actual hyphenation integration in renderer/layout.
- Text justification behavior tested in Compose.
- Widow/orphan layout rules, likely requiring pagination/layout control.
- Separate configurable heading style model and UI.
- Real variable font axis support rather than file-size inference.

### Live Preview Is Not Actually Present

Advertised:

- Live preview in settings.

Current code:

- Settings panel carries `showPreview`, but no preview surface is rendered in the
  reviewed settings UI.

Missing to match the advertised product:

- Preview panel using current settings.
- Sample text with headings, paragraphs, quote, links, and highlight colors.
- Responsive behavior on Android/Desktop.

### Custom Theme Editor Is Missing

Advertised:

- Customizable themes/layouts and per-book overrides.

Current code:

- Built-in theme presets exist.
- Settings screen says "Custom theme editor coming soon".

Missing to match the advertised product:

- Theme editor UI.
- Color pickers for background/surface/text/links/highlights.
- Save/delete custom themes.
- Per-book theme override UI.

## Statistics Gaps

### Statistics Exist But May Not Be Complete End-To-End

Advertised:

- Sessions, speed, heatmap, patterns, history timeline, per-book/week/month/year
  aggregates.

Current code:

- Statistics models, repositories, and screen/viewmodel exist.
- Reader creates/updates sessions and tracks progress/words.
- Broader daily/yearly aggregation behavior is not clearly wired for every
  lifecycle edge.

Missing to fully match the advertised product:

- Confirm daily stats are updated whenever sessions end.
- Confirm reading history timeline is written from reader activity.
- Finish/start status transitions from progress.
- Per-cycle statistics integration.
- Tests for statistics generated from real reading sessions, not only repository
  CRUD.

### Completion And Reading Cycle UX Is Thin

Advertised:

- Reread support with cycles, per-cycle progress history, permanent annotations
  shared across cycles.

Current code:

- `ReadingCycleRepository` and `reading_cycles` table exist.
- Main reader/book detail flows do not expose reread cycle management as a
  product workflow.

Missing to match the advertised product:

- Start new reread action.
- Mark cycle finished.
- Show cycle history.
- Tie sessions/positions to cycles.
- Tests for cycle-specific stats.

## Platform Gaps

### Desktop Native Distribution Is Less Complete Than Advertised

Advertised:

- Desktop distribution and later jpackage/native installers.
- Desktop multi-window, system tray, file associations.

Current code:

- Desktop app uses Gradle `application` plugin and Compose Desktop.
- `desktopApp:jar` builds.
- No `compose.desktop.nativeDistributions` configuration is present.
- No system tray, file association, or multi-window management code is visible.

Missing to match the advertised product:

- `installDist` or native distribution tasks documented accurately.
- jpackage config for MSI/DMG/DEB/RPM if desired.
- Desktop file open handling for `.epub`.
- System tray integration.
- Multi-window state and repository invalidation strategy.

### Android Platform Polish Is Overclaimed

Advertised:

- Share sheet import, wake lock, backup API.

Current code:

- Android import uses `OpenMultipleDocuments`.
- Manifest/resources exist.
- No clear share intent import flow, wake lock handling, or Android backup API
  integration was found in active source.

Missing to match the advertised product:

- Intent filters for shared/opened EPUB files.
- Persist URI permissions or robust temp-copy flow for shared files.
- Wake lock or keep-screen-on during reading.
- Android Auto Backup configuration for metadata where appropriate.
- User-facing backup/restore if relying on app-level backup instead.

### macOS Preparation Is Not Implemented

Advertised:

- macOS planned in README.
- `PHASES.md` claims macOS preparation / KMP iOS target.

Current code:

- Desktop JVM target can theoretically run on macOS with Compose Desktop, but no
  macOS packaging target is configured.
- No iOS/macOS native KMP target is configured.

Missing to match the advertised product:

- Decide whether macOS means JVM desktop on macOS or native Apple target.
- Add macOS packaging if JVM desktop.
- Add Kotlin/Native targets only if actually building iOS/macOS native.
- Update docs accordingly.

## Authentication And Firebase Configuration Gaps

### README Mentions Google Auth But Code Uses REST Email/Anonymous

Advertised:

- Firebase setup says enable Google and Email/Password.

Current code:

- `RestFirestoreSync` / storage auth use Identity Toolkit anonymous sign-up or
  email/password.
- No Google sign-in UI or OAuth flow is wired.

Missing to match the advertised product:

- Either remove Google Auth from setup docs or implement platform Google sign-in.

### Android Firebase SDK Dependencies Are Present But Not The Main Sync Path

Advertised:

- Firebase SDKs per platform.

Current code:

- Android app includes Firebase Auth/Firestore/Storage dependencies.
- Actual sync graph uses `RestFirestoreSync`.
- Storage SDK is not used by app sync.

Missing to match the advertised product:

- Remove unused SDK dependencies if REST-only is the product direction.
- Or implement SDK-backed Android sync/storage and document the split.

## Data Model And Persistence Gaps

### Flow APIs Are One-Shot, Not Live

Advertised:

- UI behaves like a modern synced reading app.

Current code:

- Repository flow methods typically emit one database snapshot.
- The progress report already notes this.
- UI refresh relies on manual `refreshTick` keys after some mutations.

Missing to match the advertised product:

- Database invalidation mechanism.
- Shared observable state for repository changes.
- Live updates after sync pulls, annotation edits, and multi-window changes.

### Recently Deleted / Undo Is Missing

Advertised:

- `PHASES.md` leaves Undo/Recently deleted unchecked, while later sections imply
  mature delete/error handling.

Current code:

- Highlights, notes, and bookmarks support soft delete and restore.
- No product UI for recently deleted or undo was found.

Missing to match the advertised product:

- Recently deleted screen.
- Undo snackbar/action after delete.
- Restore/delete forever policy.
- Sync tombstone handling for all relevant entity types.

## EPUB Compatibility And Performance Gaps

### Malformed EPUB Handling Is Partly Tested But Product Claims Are Broader

Advertised:

- Malformed EPUB graceful degradation.
- Unsupported feature warnings.
- Large book performance and parse caching.

Current code:

- Parser has tests over the included corpus and handles several parser edge cases.
- No user-facing unsupported feature warning system was evident.
- No explicit parse cache layer beyond persisted chapters/metadata was evident.

Missing to match the advertised product:

- User-facing partial import warnings.
- Unsupported media/layout feature warnings.
- Cache invalidation strategy.
- Performance tests for very large EPUBs.

### Startup Time Target Is Not Proven

Advertised:

- Startup time less than 500ms.

Current code:

- No benchmark or startup measurement harness was found.

Missing to match the advertised product:

- Define startup metric.
- Add benchmark/manual measurement.
- Track cold/warm startup on Android and Desktop separately.

## Accessibility Gaps

Advertised:

- Screen reader support: TalkBack, JAWS, NVDA.
- Keyboard navigation for all actions.
- Minimum touch targets.
- System font scaling.
- Reduced motion.

Current code:

- Some accessibility helpers and semantics exist.
- Compose components inherit some platform behavior.
- No complete accessibility test matrix or dedicated screen-reader support layer
  was evident.
- Keyboard shortcuts are modeled in settings, but full shortcut handling was not
  evident across the app.

Missing to match the advertised product:

- Systematic semantics labels for all custom controls.
- Keyboard shortcuts wired on Desktop.
- Focus traversal testing.
- Touch target audit.
- TalkBack/NVDA/JAWS manual test notes.
- Reduced motion setting and behavior.

## Build, Packaging, And Release Gaps

### README Build Commands Are Partly Stale

Advertised:

- `./gradlew :shared:compileKotlinJvm`
- `./gradlew :desktopApp:packageDistribution`

Current code:

- The shared JVM target is named `desktop`, so `compileKotlinDesktop` is the
  relevant task.
- Desktop app currently uses the `application` plugin; no `packageDistribution`
  task was verified in the active Gradle script.

Missing to match the advertised product:

- Update README build commands to actual task names.
- Document Windows commands using `gradlew.bat`.
- Document `desktopApp:run`, `desktopApp:jar`, and any real distribution task.

### Release Claims Need Qualification

Advertised:

- Signed Android release APK.
- Desktop distribution artifacts.

Current code:

- Release signing config exists and depends on local `keystore.properties`.
- The report did not verify release APK creation in this run.
- Debug APK assembly was verified.

Missing to match the advertised product:

- Verify `:androidApp:assembleRelease`.
- Decide whether release artifacts should be documented as reproducible or local
  machine outputs.
- Add AAB support if Play publishing is planned.

## Security And Configuration Gaps

### Sensitive Local Files Exist

Observed:

- `.env`
- `keystore.properties`
- `local.properties`

These may be ignored locally, but because this directory did not appear as a Git
repository during the shell check, the report cannot confirm repository tracking
state.

Recommended actions:

- Confirm `.env`, `keystore.properties`, and `local.properties` are never
  committed.
- Remove hardcoded default Firebase credentials from storage sync if they are not
  intended public test credentials.
- Add `.env.example` with safe placeholder keys.

### Firebase Rules Cover Firestore But Not Storage

Advertised:

- Cloud Storage setup.

Current code/docs:

- `firestore.rules` exists.
- No storage rules file was reviewed.

Missing to match the advertised product:

- `storage.rules` for EPUB/cover objects.
- Documentation for bucket path shape and auth requirements.
- Tests/manual checklist for denied cross-user access.

## Test Coverage Gaps

Verified:

- 23 desktop tests pass.
- Covered areas include corpus parsing, import persistence, repository CRUD,
  renderer basics, and sync-engine behavior with fake transport.

Missing coverage relative to advertised product:

- Android instrumentation tests.
- Compose UI tests.
- Real navigation tests for every advertised screen.
- Search scope tests for highlights/notes/bookmarks/quotes.
- Backup/export/import round-trip tests.
- Storage sync tests.
- Auth UI/service tests.
- Font import/rendering tests.
- EPUB replacement/remapping tests.
- Accessibility/focus tests.
- Performance benchmarks.

## Recommended Product Truth Table

Use these statuses in docs until the implementation changes.

| Claim | Recommended Status |
|---|---|
| Local EPUB import | Implemented |
| Duplicate detection by hash/ISBN | Implemented |
| Duplicate detection by title+author | Missing |
| EPUB parsing with cover/chapter extraction | Implemented, corpus-tested |
| Library grid/list/compact | Implemented |
| Library filters by status | Implemented |
| Library filters by series/collection/tag | Missing/partial repository support |
| Reader HTML rendering | Implemented |
| Native text selection/copy | Implemented |
| Precise selection highlights | Missing |
| EPUB CFI positions | Missing |
| Scroll progress tracking | Implemented |
| Pagination | Missing |
| Bookmarks/highlights/notes persistence | Implemented |
| Annotation visual overlays | Partial/missing |
| Quote browser screen | Implemented but not integrated |
| Revisit screen | Implemented but not integrated |
| Tag manager screen | Implemented but not fully integrated |
| Book detail screen | Implemented but not fully integrated |
| Full-text chapter search | Implemented |
| Search across annotations/quotes/bookmarks | Missing |
| Search result jump to exact location | Missing |
| Statistics screen | Implemented |
| Complete statistics lifecycle | Needs verification |
| Firestore metadata/entity sync | Partly implemented |
| Cloud EPUB file sync | Missing/inactive |
| Auth transport | Implemented internally |
| Auth product UI | Missing/inactive |
| Export annotations Markdown/JSON/CSV | Missing |
| Full backup/restore | Missing |
| FontManager core | Partial |
| Font import UI | Missing/stub |
| Embedded EPUB fonts | Missing/stub |
| Custom theme editor | Missing |
| Desktop jar/run | Implemented |
| Native desktop installers | Missing |
| Android debug APK | Verified |
| Android release APK | Config exists, not verified here |
| macOS/iOS target preparation | Missing |

## Highest-Impact Fix Order

1. Fix the documentation truth first.
   - Update `README.md` and `PHASES.md` to remove SQLDelight/Koin claims.
   - Change Firebase Storage claims to metadata-only sync unless storage is being
     prioritized immediately.
   - Mark advanced phases as planned/partial rather than complete.

2. Wire the product navigation.
   - Make Book Detail reachable.
   - Add routes from Settings/Library to Auth, Tags, Quotes, and Revisit Items.
   - Replace empty callbacks with real navigation.

3. Decide the cloud storage direction.
   - If metadata-only is intentional, delete or quarantine storage claims.
   - If file sync is required, wire `RestFirebaseStorageSync` and add UI/state.

4. Replace stubbed settings actions.
   - Font import.
   - Embedded font toggle.
   - Custom theme editor or remove the advertised claim.
   - Backup/restore UI.

5. Build the export/backup product.
   - This is one of the biggest advertised-vs-real gaps and needs its own feature
     design and tests.

6. Improve search and navigation precision.
   - Add scoped search.
   - Pass exact chapter/locator targets into Reader.

7. Make annotations precise and visible.
   - Character-range highlights.
   - Highlight rendering in text.
   - Notes/tags/quotes/revisit actions from selected text.

8. Add missing tests around advertised workflows.
   - Navigation smoke tests.
   - Export/import round-trip.
   - Sync queue creation from UI mutations.
   - Storage fake tests if storage is kept.

## Bottom Line

The implementation is a solid prototype-to-alpha reader, not the complete
advertised product described by all Markdown files combined. `PROGRESS_REPORT.md`
is the closest document to the code, but it still overstates app navigation and
some end-to-end product workflows. `README.md` and `PHASES.md` need substantial
correction because they describe removed architecture and many completed features
that are currently only models, repositories, stubs, or future intentions.
