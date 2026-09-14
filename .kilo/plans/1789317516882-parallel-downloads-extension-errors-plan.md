# Parallel chapter downloads + extension error surfacing

Two additions on top of the webtoon stability work.

## A. Parallel chapter downloads (3 concurrent)

**Is it dangerous?** No — with three properties the code already has or will get:
- File writes are isolated per chapter (`<mangaId>/<chapterId>/`), so workers never touch the same files.
- All DB writes go through `Database.withConnection` (single write mutex), so queue rows can't corrupt.
- Chapter pickup becomes an atomic *claim* (below), so two workers can never process the same row.

Real risks, both mitigated: source rate-limiting (the existing `withRetries` + backoff stays; 3 is the same ballpark Mihon itself offers), and one pre-existing race that parallelism would aggravate — **cancel-during-flight resurrection**: `cancel()` removes the row, but the worker's next `downloadsRepo.update()` is an `INSERT OR REPLACE` and resurrects it as a zombie DOWNLOADING/ERROR row. That gets fixed as part of this work.

### A1. Repository: atomic claim + no-resurrect update
`shared/src/commonMain/kotlin/com/folio/reader/manga/MangaRepositories.kt`
- Add to `MangaDownloadRepository`:
  ```kotlin
  suspend fun claimNextQueued(): MangaDownload?
  ```
  with a default implementation (observe queue → first QUEUED → conditional update) so the four test fakes and any other implementors keep compiling; the JDBC repo overrides it.

`shared/src/commonJvm/kotlin/com/folio/reader/database/JdbcMangaRepositories.kt`
- `claimNextQueued()`: one `db.withConnection { ... }` block (atomic under the write mutex) doing SELECT first QUEUED by `queued_at`, then `UPDATE ... SET status=DOWNLOADING, error=NULL WHERE id=? AND status=QUEUED`; return the row only when rowcount == 1, else null.
- `update()`: become a true `UPDATE ... WHERE id=?` (not INSERT OR REPLACE) so a cancelled row stays deleted. `enqueue()` keeps INSERT OR REPLACE (it is the insert/re-queue path). Audit other `update` callers — `process()` and `resumeInterrupted()` both operate on existing rows, so this is safe.

### A2. Manager: N workers
`shared/src/commonJvm/kotlin/com/folio/reader/manga/MangaDownloadManager.kt`
- Constructor gains `parallelChapters: Int = 3` (default keeps call sites unchanged: `FolioApplication`, desktop `Main.kt`).
- `start()`: idempotent as today; one `loop` Job that runs `resumeInterrupted()` once, then launches `parallelChapters` worker coroutines. Each worker: `claimNextQueued()` → process → repeat; `null` → `delay(1500)` (unchanged cadence).
- `stop()` unchanged (cancels the loop, hence all workers).
- Class doc: update "Sequential chapter download worker" to describe the claim-based pool.

### A3. Notification text
`androidApp/src/main/java/com/folio/reader/downloads/MangaDownloadService.kt`
- `observeQueue` currently passes `firstOrNull { DOWNLOADING }`; change to the count + first item so the notification reads e.g. "Downloading 2 chapters · 5 queued". `progressText` signature adjusts accordingly.

### A4. UI
`MangaDownloadsScreen` renders each row's own status — multiple DOWNLOADING rows display correctly with no change.

### A5. Tests (desktopTest, alongside `MangaDownloadResumeTest`)
- Claim exclusivity: 3 workers / 5 fake-backend chapters → each chapter processed exactly once, all end DOWNLOADED.
- Cancel-during-flight: worker mid-chapter, `cancel(id)` → subsequent `update` does not resurrect the row.
- JDBC claim atomicity: two sequential `claimNextQueued` calls return different rows (real DB, like `MangaDownloadResumeTest`'s setup).

## B. Extension error surfacing ("some extensions throw errors")

Today an extension that installs fine but fails to *load* is invisible: `ExtensionLoader` returns `LoadResult.Error` (a bare object, no reason) for missing versionName, unsupported lib version (only 1.4/1.6 are accepted), unsigned packages, classloader failures, source-class instantiation failures, and the 15s timeout — and those extensions simply vanish from the Installed list. Install failures equally render nothing (`ExtensionRow` has no `InstallStep.Error` branch; the button silently reverts to Install).

### B1. Reasons on load failures
`shared/src/androidMain/.../extension/model/LoadResult.kt`
- `data object Error` → `data class Error(val reason: String)`.

`shared/src/androidMain/.../extension/util/ExtensionLoader.kt`
- Every `LoadResult.Error` return carries a concrete reason: "missing versionName", "unsupported library version $lib (supported: 1.4, 1.6)", "package not signed", "class loader failed: …", "source class failed to load: …", "timed out after 15s", "trust store unavailable". Include pkgName where in scope.

### B2. Expose load errors
`shared/src/androidMain/.../extension/ExtensionManager.kt`
- New `loadErrorsFlow: StateFlow<List<String>>`, populated in `initExtensions` from the `LoadResult.Error` reasons ("name (pkg): reason").

`shared/src/commonMain/kotlin/com/folio/reader/manga/MangaBackend.kt`
- `observeExtensionLoadErrors(): Flow<List<String>> = flowOf(emptyList())` (default keeps DesktopMangaBackend and every test fake compiling).

`shared/src/androidMain/.../manga/AndroidMangaBackend.kt`
- Override mapping `extensionManager.loadErrors` to strings.

### B3. UI
`shared/src/composeUi/.../manga/MangaExtensionsScreen.kt`
- Render a load-errors section beside the existing repo-errors section (error color, bodySmall), e.g. "Extension failed to load — <reason>". These are the Android-only errors; the default empty flow keeps other platforms unaffected.
- `ExtensionRow`: add an `InstallStep.Error` branch — inline "Install failed" label in error color with the button becoming Retry (calls `onInstall` again).

`shared/src/composeUi/.../manga/MangaBrowseViewModel.kt`
- Expose `extensionLoadErrors` like `extensionRepoErrors` (stateIn). No other VM changes; `install()` already collects Error steps into `installStates`.

## Validation
- `:shared:desktopTest` — full suite plus the new A5 tests (the 2 pre-existing Windows-path failures in MangaReaderProgressTest remain acceptable).
- `:androidApp:assembleDebug`, `:desktopApp:compileKotlin`.
- Manual QA (device): queue 5+ chapters → three progress simultaneously in Downloads screen + notification; cancel one mid-flight → row stays gone, others unaffected; install an extension from a repo → failure now shows a reason on the row; an installed-but-unloadable extension shows a load-error line on the Extensions screen after restart.

## Files touched
A: MangaRepositories.kt, JdbcMangaRepositories.kt, MangaDownloadManager.kt, MangaDownloadService.kt, new test file.
B: LoadResult.kt, ExtensionLoader.kt, ExtensionManager.kt, MangaBackend.kt, AndroidMangaBackend.kt, MangaExtensionsScreen.kt, MangaBrowseViewModel.kt.

## Non-goals
- No user-facing parallelism setting (constant 3); can be promoted to a setting later if sources complain.
- No leniency on the lib-version gate (1.4/1.6) — surfacing the reason is the fix; accepting unknown lib versions is a compatibility risk.
- No per-source concurrency throttling; the retry/backoff path remains the mitigation for rate limits.
