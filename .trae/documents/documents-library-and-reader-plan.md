# Documents Library and Reader Plan

## Summary
Add a first-class, local-only Documents domain to Folio for PDF, TXT, HTML/HTM, DOCX, and ODT without weakening the existing EPUB or manga paths. Documents will appear as a third Library mode, use dedicated metadata and reading-position persistence, and open in either a fixed-page PDF reader or a normalized reflowable HTML reader. EPUB remains on the current Book/EPUB reader path. Android intents, Android pickers, desktop command-line opening, and Windows associations will share one incoming-content dispatcher so supported EPUBs and documents import/deduplicate and open immediately. Folio can register as an eligible/default-capable handler, but Android and Windows must let the user confirm the default.

## Confirmed Scope and Decisions
- Internal formats: PDF, TXT, HTML/HTM, DOCX, and ODT; EPUB continues through its existing reader.
- Legacy `.doc`, RTF, spreadsheets, and presentations remain unsupported internally and may be handed to an external app.
- PDF v1: single-page and continuous reading, zoom/pan, thumbnails, page jump, rotation-aware rendering, last-page restoration, progress, and page bookmarks.
- PDF text selection, full-text search, highlights, forms, signatures, reflow, and password entry are deferred.
- DOCX/ODT provide semantic conversion (headings, paragraphs, lists, tables, links, bounded local images), not pixel-perfect office layout.
- Documents are local-only: no Firestore entities, synchronization queue entries, Firebase Storage objects, or backup payloads.
- Opening an external file imports an app-owned copy, deduplicates by SHA-256, and opens it; Folio does not depend on the source remaining accessible.
- Keep Android’s four bottom destinations (Home, Library, Stats, More); Documents is a Library mode, not a fifth destination.
- Do not generalize `Book`: EPUB hashes, spine/chapter positions, cloud DTOs, storage, backup, and reader behavior stay compatible.

## Current State Analysis
### Application boundaries
- `settings.gradle.kts` defines `:shared`, `:androidApp`, and `:desktopApp`.
- Shared models/contracts live in `shared/src/commonMain`; SQLite/import/JVM services in `commonJvm`; shared Compose UI in `composeUi`; renderer/storage actuals in `androidMain` and `desktopMain`.
- Dependencies are manually assembled in `androidApp/.../FolioApplication.kt` and `desktopApp/.../Main.kt`; there is no local backend server or UI/backend HTTP boundary.
- UI calls repositories directly. Firebase REST is optional remote synchronization and is intentionally outside document scope.

### Existing content and persistence
- `shared/src/commonMain/.../model/Book.kt`, `ReadingPosition.kt`, and `database/Repositories.kt` are EPUB-oriented.
- `shared/src/commonJvm/.../database/Database.kt` initializes SQLite idempotently; `JdbcRepositories.kt` implements Flow-based repositories and local sync-event behavior.
- `shared/src/commonJvm/.../importer/BookImporter.kt` parses/imports EPUB and derives stable identity; it should remain the EPUB implementation behind a new dispatcher.
- `shared/src/commonJvm/.../platform/Platform.kt` and platform actuals hard-code book storage and `original.epub`, requiring additive document-specific storage APIs.

### UI and navigation
- `shared/src/composeUi/.../library/LibraryScreen.kt` defines `LibraryMode { BOOKS, MANGA }`; `LibraryViewModels.kt`, `LibraryFilters.kt`, `LibraryGrid.kt`, and `LibraryList.kt` contain book/manga assumptions.
- Android navigation is split among `FolioNavHost.kt`, `FolioDestination.kt`, `FolioNavModel*.kt`, `FolioLibraryRoutes.kt`, and `FolioReaderRoutes.kt`.
- Desktop navigation is a manual `Screen` stack in `desktopApp/.../Main.kt`.
- EPUB rendering uses `ReaderViewModel.kt`, `ReaderContentLoader.kt`, `HtmlContentSurface.kt`, and Android/desktop actuals; its chapter/spine/DOM assumptions are unsuitable for PDF.

### Incoming files and associations
- `androidApp/.../MainActivity.kt` handles EPUB picker/intents, including `onNewIntent`; `AndroidManifest.xml` registers current associations.
- `desktopApp/.../Main.kt` filters startup arguments to EPUB.
- `desktopApp/build.gradle.kts` packages one EPUB association.
- Android/Windows registration can expose Folio in “Open with”; neither platform permits silently forcing Folio as default.

## Proposed Changes
### 1. Introduce an independent Documents domain
Add under `shared/src/commonMain/kotlin/com/folio/reader/model/`:
- `Document`: id, title, original filename, format, MIME type, SHA-256, byte size, local path, optional author/description, optional page/section count, imported/updated/last-opened timestamps, and normalized progress.
- `DocumentFormat`: PDF, TXT, HTML, DOCX, ODT.
- `DocumentLocator`: versioned fixed-page locator (`pageIndex`, normalized x/y) and reflowable locator (`sectionId`, DOM/text locator, character offset).
- `DocumentPosition` and `DocumentBookmark`; annotations are deferred.
Add `DocumentRepository` to `shared/src/commonMain/.../database/Repositories.kt` with observe/list/get-by-id/get-by-hash, upsert, position update/observe, bookmark CRUD, and delete operations. Do not expose sync flags because this domain is local-only.

### 2. Add SQLite schema and JDBC persistence
Modify `shared/src/commonJvm/.../database/Database.kt` and `JdbcRepositories.kt`:
- `documents`: metadata above; nullable local path; unique `content_hash`; indexes on title, last-opened, imported-at, and format/imported-at.
- `document_positions`: one row per document with locator kind, versioned locator JSON, indexed page/section fields, progress, and update time.
- `document_bookmarks`: id, document id, locator kind/JSON, optional label, created/updated times.
- Add foreign-key/cascade behavior or explicit transactional cleanup consistent with current schema conventions.
- Extend idempotent schema initialization without rewriting existing book tables.
- Keep documents out of existing EPUB chapter FTS; add metadata search to the document repository. Reflowable body indexing and PDF text indexing are deferred.
Implement a document deletion coordinator that commits database deletion and then removes app-owned files; report cleanup failures without restoring deleted metadata.

### 3. Add format-correct private storage
Extend `shared/src/commonJvm/.../platform/Platform.kt` and its `AndroidPlatform.kt`/`DesktopPlatform.kt` actuals with document directory, original path, generated-assets path, cache path, staged copy, size, and recursive delete operations.
Use `library/documents/{documentId}/original.{canonicalExtension}`, `generated/index.html`, `generated/assets/`, and `cache/{pages,thumbnails}/`. Preserve `library/books/{bookId}/original.epub` unchanged. Stage imports and atomically rename when supported.

### 4. Centralize file detection and import
Add services under `shared/src/commonJvm/kotlin/com/folio/reader/importer/`:
- `IncomingContentCoordinator` accepts a path plus optional filename/MIME, detects format, dispatches EPUB to `BookImporter`, dispatches documents to `DocumentImporter`, and returns typed imported/duplicate/unsupported/unsafe/corrupt/encrypted/too-large/I/O results.
- `DocumentFormatDetector` combines magic bytes/container structure, declared MIME, and extension; content wins, conflicts fail safely, and broad octet-stream registration is avoided.
- Format processors for PDF metadata, TXT, sanitized HTML, DOCX, and ODT.
Stream copy and SHA-256 together; derive deterministic document IDs from the hash; attach duplicate imports to the existing row and open it. Limit concurrency to two imports.
Apply boundaries: 512 MiB general source, 100 MiB DOCX/ODT, 50 MiB generated HTML, 512 MiB extracted archive, 10,000 entries, 128 MiB per entry, 100:1 compression ratio, and 20,000 PDF pages. Reject traversal, absolute/drive paths, symlink-like entries, XML external entities, network access, active HTML, remote resources, and unsafe URLs. Clean staging/generated artifacts after failure.

### 5. Normalize reflowable documents
- TXT: conservative charset detection, escaped text, preserved paragraphs/line breaks.
- HTML/HTM: sanitize scripts, event handlers, embeds, active forms, remote URLs, and unsafe schemes; rewrite retained local assets into app storage.
- DOCX/ODT: select exact pinned JVM parser dependencies only after Android API 24, license, transitive size, malformed archive, and fidelity tests pass; otherwise block release of that individual format rather than bypass safety.
- Convert all supported reflowable inputs to the same sanitized app-owned HTML contract and reuse `HtmlContentSurface` actuals through document-specific loading and position adapters, not fake `Book` objects.

### 6. Build the PDF reader
Add a shared `FixedPageContentSurface` expect/actual contract near `shared/src/composeUi/.../render/` and platform implementations under `androidMain`/`desktopMain`.
- Android: framework `android.graphics.pdf.PdfRenderer` (compatible with current minSdk 24).
- Desktop: exact pinned Apache PDFBox dependency in `gradle/libs.versions.toml` and `shared/build.gradle.kts`.
- Render off the UI thread; cancel obsolete jobs; cache by document/page/scale; cap cache by rendered pixels/memory; generate bounded thumbnails.
Add document reader view models/screens under `composeUi` that dispatch PDF to fixed-page UI and TXT/HTML/DOCX/ODT to reflowable UI. Persist position on page/locator changes and lifecycle exit; expose loading, corrupt/encrypted, missing-file, and rendering errors.

### 7. Add Documents to Library
Modify `LibraryScreen.kt` to `LibraryMode { BOOKS, DOCUMENTS, MANGA }` and replace binary manga branches with exhaustive mode handling. Existing persisted BOOKS/MANGA enum names remain valid.
Extend `LibraryViewModels.kt`, `LibraryFilters.kt`, `LibraryGrid.kt`, and `LibraryList.kt` with a document-specific state/model, not book-field overloading. Show title, format badge, original filename, size, import/last-opened date, page count when known, progress, and local-file state. Support grid/list, metadata search, sort by title/last opened/import date/size/format/progress, import, open, and delete.
Use existing visual tokens, components, spacing, motion, empty/loading/error patterns, and adaptive layouts so Documents looks native to Folio.

### 8. Wire Android navigation, picker, and default-capable opening
Modify `FolioNavHost.kt`, `FolioNavModel.kt`, `FolioNavModelImpl.kt`, `FolioNavModelImporters.kt`, `FolioLibraryRoutes.kt`, and `FolioReaderRoutes.kt` to construct document view models, open document detail/reader routes, and return to Documents mode correctly.
Replace the EPUB-only picker in `MainActivity.kt` with `OpenMultipleDocuments` for explicit supported MIME types. Copy `content://` data immediately to a temp file while enforcing limits, then pass it to the coordinator. Preserve cold-start and `onNewIntent` handling.
Behavior: ACTION_VIEW imports/deduplicates and opens; ACTION_SEND does the same; multi-select imports all with bounded concurrency and opens the first success while reporting per-file failures.
Add manifest filters for EPUB, PDF, plain text, HTML/XHTML, DOCX, and ODT. Do not claim default ownership; optionally link users to system default-app settings after explaining they must choose Folio/“Always.”

### 9. Wire desktop navigation and Windows associations
Modify `desktopApp/.../Main.kt` to register document dependencies/screens and process all existing startup file arguments through the coordinator; import all supported files, open the first success, retain the rest in Documents, and report failures non-fatally.
Modify `desktopApp/build.gradle.kts` to register PDF, TXT, HTML/HTM, DOCX, and ODT in addition to EPUB using Compose Desktop’s supported repeated association syntax. Build and inspect MSI/EXE association metadata on Windows; registration still requires user choice in Default Apps.

### 10. Keep cloud and backup boundaries explicit
Do not modify `SyncEngine.kt`, Firestore models/transports/rules, Firebase Storage, or `ExportManager.kt` for document entities. Document originals, metadata, positions, and bookmarks remain local and outside Folio backup/restore. Existing EPUB sync/backup behavior must remain unchanged.

## Verification
- Schema tests: fresh creation, repeated initialization, migration from book-only DB, indexes/uniqueness, CRUD flows, positions/bookmarks, and transactional cascades.
- Import tests: renamed duplicate hash, MIME/extension/magic mismatch, correct extension/path, rollback/cleanup, empty/truncated/corrupt/encrypted/oversized PDF, and bounded multi-import.
- Security tests: DOCX/ODT traversal, absolute paths, excessive entries, zip bombs, oversized output, malformed XML/XXE; HTML scripts/events/forms/remote resources/unsafe URLs.
- Reader tests: Android/desktop PDF page render, continuous/single-page navigation, zoom/pan, thumbnails, page jump, rotation, cache cancellation/limits, position restoration, bookmarks, and reflowable locator restoration.
- UI/navigation tests: Documents mode persistence, grid/list/sort/search/empty/error states, import/open/delete, Android cold ACTION_VIEW, `onNewIntent`, ACTION_SEND, picker multi-import, and desktop one/multiple startup files.
- Association checks: Android chooser eligibility for every supported MIME and Windows installer metadata for every extension; manually verify user-selected default opening routes to import then reader.
- Regressions: existing EPUB import/open/share/reader/sync/backup, manga library/reader, four-item bottom navigation, and existing databases remain unchanged.
- Run `./gradlew :shared:desktopTest --console=plain`, `./gradlew :desktopApp:compileKotlin --console=plain`, and `./gradlew :androidApp:assembleDebug --console=plain`; additionally build Windows MSI/EXE on Windows.

## Implementation Order
1. Models/contracts and schema/repository tests.
2. Platform document storage and deletion coordinator.
3. Detection/coordinator plus safe PDF/TXT/HTML imports.
4. Fixed-page PDF renderer and document reader shell.
5. DOCX/ODT dependency gate and converters.
6. Documents Library UI and shared reader integration.
7. Android routes, picker, intents, and manifest associations.
8. Desktop routes, startup handling, and installer associations.
9. Full security, UI, platform, packaging, and EPUB/manga regression validation.
