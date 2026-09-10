# Debug Session: PDF reader, layout, and navigation

- Session ID: `pdf-reader-layout-navigation`
- Status: `[OPEN]`
- Reported environment: Android release build

## Symptoms
- PDF page fails with `The coroutine scope left the composition`.
- Horizontal page swiping does not navigate.
- Pinch zoom/pan is unavailable or not bounded to the page viewport.
- Reader actions are crowded into the top bar; bottom-right overflow has no useful actions.
- Library search/menu text and boxes are misaligned on a narrow screen.
- Documents appears between Books and Manga instead of after Anime/Manga.

## Evidence
- User screenshot: PDF error explicitly states `The coroutine scope left the composition`.
- User screenshots: narrow search field wraps one character per line; the filter menu exceeds the useful viewport height; Documents appears before Manga.
- Static inspection found cancellation could reach generic reader error mapping during composition disposal.
- Static inspection found PDF rendering and document disposal needed a shared lifecycle boundary to prevent close/render overlap.
- Static inspection found the single-page surface had no complete fit-geometry-based swipe, zoom, pan, and clipping implementation.
- Static inspection found compact Documents controls shared one row and allowed search to collapse beside the three-option selector.
- Static inspection found visual library ordering was coupled to enum entry order (`BOOKS`, `DOCUMENTS`, `MANGA`), while persistence already used enum names.
- Live zoom was part of the former rendering path; it could alter raster dimensions/cache identity and trigger repeated rendering/loading flashes.
- PDF imports had no reliable target-specific first-page renderer wiring, so genuine thumbnails were not consistently generated or persisted.
- Existing-file edits to renderer/JDBC declarations appeared in editor tooling but remained absent from direct filesystem reads and compiler input. Dedicated compiler-visible files were therefore required.
- PDFBox 3.0.3 test PDFs saved with default object-stream compression hid `/Type /Page` and `/Count` tokens from the existing raw-byte PDF validation.
- Per user instruction, additional device reproduction was waived. No installation, `adb` interaction, or runtime network instrumentation was performed.

## Hypothesis results
1. Confirmed: ordinary composition-owned coroutine cancellation was being surfaced as a reader error instead of being rethrown/ignored as cancellation.
2. Confirmed: the single-page gesture path lacked the required arbitration between fit-level paging and zoomed pan/zoom.
3. Confirmed: the fitted page needed an explicitly clipped viewport and letterboxing-aware pan bounds.
4. Confirmed: the Documents controls and menu lacked compact-width layout constraints.
5. Partially confirmed: enum declaration order is Books, Documents, Manga and UI mapping depended on that order. Persistence was already name-based, not ordinal-based.
6. Confirmed: transient pinch zoom must be separated from stable raster inputs; stable keys now depend only on document, page, viewport/raster size, rotation, and thumbnail status.
7. Confirmed: Single and Continuous modes needed distinct surfaces and gesture state. Current-page state can be retained while incompatible gesture state resets on mode changes.
8. Confirmed: thumbnail generation required explicit Desktop/Android renderer injection, first-page PNG storage, and SQLite hydration.
9. Confirmed: the initial PDF thumbnail test failure was a fixture compatibility issue caused by PDFBox object-stream compression, not a renderer failure.

## Fixes
- Cancellation is rethrown during PDF open/render and thumbnail generation, and excluded from `DocumentReaderError` mapping.
- PDF ownership transfer checks coroutine activity; a newly opened document is closed if cancellation occurs before Compose owns it.
- Android `PdfRenderer`/descriptor and desktop PDFBox rendering/close operations are serialized with the same mutex.
- Single mode now presents one bounded page. Fit-scale horizontal swipes navigate pages; zoomed gestures use transient `graphicsLayer` scale/translation with bounded pan.
- Continuous mode uses a separate vertical `LazyColumn`, tracks visible pages, renders/caches pages independently, and has no horizontal paging. Mode switches preserve the current page and reset incompatible gesture state.
- Stable raster/cache keys exclude live zoom. Rerendering is limited to stable document, page, viewport, rotation, or quality changes.
- PDF import now invokes an explicitly injected target renderer, writes page one to `<document directory>/cache/thumbnails/first-page.png`, and stores the path on `Document`. Ordinary renderer failures fall back nonfatally; cancellation is rethrown.
- Dedicated Desktop and Android renderer files use PDFBox and `PdfRenderer`, respectively, with bounded output size and safe open/render/close lifecycles.
- Library list and grid items decode thumbnail files on `Dispatchers.IO`, cache successful decodes, and retain format-badge fallback for blank, missing, empty, unreadable, or failed thumbnails and for non-PDF documents.
- Recursive document deletion removes the thumbnail with the document directory.
- The schema and probe migration include nullable `thumbnail_path`. Because edits to the compiler-visible JDBC implementation remained unreliable, `ThumbnailDocumentRepository` wraps it to update and hydrate that column for upsert, lookup, observation, hash lookup, and search.
- The PDFBox test fixture now saves with `CompressParameters.NO_COMPRESSION`, preserving compatibility with the existing raw-byte page-tree validation.
- The page viewport is explicitly clipped. Fit and clamp calculations use actual fitted page and viewport geometry, including letterboxing. Page changes and Reset zoom restore zoom/pan state.
- The top bar now contains only back, title, and bookmark. Mode, rotation, page navigation/slider, zoom guidance, and reset are in bottom controls; dead overflow is suppressed.
- Compact Documents layout places the selector and full-width search on separate rows. The display/filter menu is height constrained.
- Library visual order is explicitly Books, Manga, Documents. Mapping uses explicit values/indexes and persisted enum names remain unchanged; enum ordinals are not used.

## Caveats
- `ThumbnailDocumentRepository` is a containment workaround for the source visibility discrepancy. Its thumbnail update is a second, non-atomic SQL statement and list/search hydration currently performs one thumbnail query per document. Direct support should move into a reliable JDBC implementation when that discrepancy is resolved.
- Continuous zoom currently scales the whole lazy list visually; lazy-list scroll extents do not scale with it and should be behaviorally reviewed on a target device.
- Existing PDF raw-byte validation remains sensitive to PDFs whose page-tree tokens exist only in compressed object streams; the focused generated fixture avoids that unrelated limitation.

## Verification
- Focused raster-key, stable-input, mode/current-page, PDF thumbnail render/store/persist/delete, non-PDF fallback, repository round-trip, and schema migration tests: passed.
- `:shared:desktopTest`: `BUILD SUCCESSFUL in 1m 9s` (5 actionable tasks: 4 executed, 1 up-to-date).
- `:desktopApp:compileKotlin`: `BUILD SUCCESSFUL in 15s` (4 actionable tasks: 1 executed, 3 up-to-date).
- `:shared:compileDebugKotlinAndroid`: `BUILD SUCCESSFUL in 1m 48s` (6 actionable tasks: 1 executed, 5 from cache).
- `:androidApp:assembleRelease`: `BUILD SUCCESSFUL in 5m 8s` (63 actionable tasks: 15 executed, 9 from cache, 39 up-to-date).
- The release build emitted repeated nonfatal R8 Kotlin metadata-version warnings. Existing serialization/Compose deprecation, SQLite restricted native-access, Gradle deprecation, and test API warnings remain; no requested validation failed.

Status remains `[OPEN]` pending user confirmation on the target device.