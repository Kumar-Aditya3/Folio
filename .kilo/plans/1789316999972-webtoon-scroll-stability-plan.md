# Webtoon reader: scroll/position jumps while images load

Symptom: opening a manga in webtoon mode, the viewport and the page/chapter
counter jump pages and chapters forwards and backwards as images load.

## Diagnosis (root causes, in causal order)

### 1. Loading pages have no reserved height (primary)
`ReaderPage` (`shared/src/composeUi/kotlin/com/folio/reader/ui/manga/MangaPageCache.kt:67`)
gives the webtoon item no height while `bitmap == null` — the item is just a
~32dp spinner until decode finishes. In a `LazyColumn` whose geometry *is* the
page heights, this means:

- **Fling teleporting**: unloaded regions have near-zero total height, so one
  fling crosses dozens of pages.
- **Bogus position reports**: the spinner stack of many collapsed pages spans
  multiple chapters, so `visibleItemsInfo`'s topmost item
  (`MangaPager.kt:61-69`) is semantically wrong. `onPageKeyVisible` →
  `onPageChanged` then jumps `chapter.value`, records history for chapters
  never actually viewed, `finalizePassedSlots` marks chapters read that were
  only "passed" as collapsed spinners (data corruption), and
  `checkExtensions` fires extend forward/backward at wrong moments.
- **Re-collapse on revisit**: the bitmap cache is capped (96MB); scrolling back
  to an evicted page re-collapses it, shifting geometry mid-scroll.

### 2. `remember(index)` resets every page on backward extension
`ReaderPage` keys `bitmap`/`failed` with `remember(index)` and its resolve
effect with `LaunchedEffect(index)` (`MangaPageCache.kt:80-101`). Backward
extension (`extendBackward` → `prependSlot`, `MangaReaderViewModel.kt:276-288`)
shifts **every** combined index. LazyColumn keeps item identity by page key,
but ReaderPage keys its *state* by index — so every composed page's bitmap
resets to null at the same moment: the whole viewport collapses to spinners,
the scroll anchor is recomputed against collapsed sizes, and the content jumps
as images reload. This is the exact "jump backwards when images load".

Forward jumps come from cause 1: scroll far ahead through unloaded pages, land
on a collapsed item, and it expands under the anchor as its image loads.

### 3. Open-path flashes (minor)
- `loading` starts `false` and is only set inside the `open()` coroutine, so a
  "No pages" frame renders before the spinner.
- `mode` defaults to `WEBTOON` and is resolved asynchronously in `open()`, so a
  webtoon frame flashes when the manga's saved mode is a paged one.

## Fixes

### Task 1 — Key ReaderPage state by page key, not index
In `MangaPageCache.kt` `ReaderPage`:
- Use the already-computed `cacheKey` (`viewModel.pageKey(index)`, stable
  across prepends) as the remember key for `bitmap` and `failed`, and as the
  `LaunchedEffect` key for the resolve/decode block.
- `offsetX`/`offsetY` too (only used by paged modes, but same reasoning).

### Task 2 — Reserve placeholder height while loading (webtoon)
- `ReaderPage` gains `placeholderHeight: Dp?` (default null; paged modes keep
  `fillMaxSize` and pass nothing). When `bitmap == null` — loading **or**
  failed — the item reserves `placeholderHeight` instead of collapsing.
- `WebtoonReader` passes the viewport height as the placeholder.
- Learned aspect ratios: add a bounded top-level map `pageKey -> aspectRatio
  (w/h)` in `MangaPageCache.kt` (same pattern/locking as `pageBitmapCache`),
  recorded on successful decode. Placeholder height = `imageWidth / aspect`
  when known, else viewport height. Revisits after eviction then keep exact
  geometry; first loads get a sane default (webtoon strips are tall, so
  viewport height is the right default).

### Task 3 — Open-path polish
- `MangaReaderViewModel.open()`: set `loading.value = true` synchronously
  before `scope.launch` (error paths already clear it).
- Add `modeResolved` state (false until the saved/global mode is resolved in
  `open()`); `MangaReaderScreen` shows the spinner until `modeResolved` so a
  webtoon frame never flashes for paged-mode manga. Error display must not be
  gated behind it.

### Task 4 — Validation
- `:shared:desktopTest` still green (only the 2 pre-existing Windows-path
  failures in MangaReaderProgressTest may remain).
- `:desktopApp:compileKotlin`, `:androidApp:assembleDebug` succeed.
- If the aspect-ratio cache ends up a pure-logic seam, add a small desktopTest
  for it (bounded eviction + ratio math). Compose scroll behavior itself is
  manual QA:
  - Open a webtoon mid-chapter: lands on the resume page, no jump while the
    first images load.
  - Fling down through unloaded pages: no teleporting; page counter advances
    smoothly; chapters NOT marked read for spinner-passed regions.
  - Scroll to the very top: backward extension prepends without a viewport
    jump or a spinner cascade.
  - Scroll far away and back: revisited (evicted) pages keep their height.
  - Slider seek in both directions.
  - Paged-mode manga opens with no webtoon flash.

## Files touched
- `shared/src/composeUi/kotlin/com/folio/reader/ui/manga/MangaPageCache.kt`
- `shared/src/composeUi/kotlin/com/folio/reader/ui/manga/MangaPager.kt`
- `shared/src/composeUi/kotlin/com/folio/reader/ui/manga/MangaReaderViewModel.kt`
- `shared/src/composeUi/kotlin/com/folio/reader/ui/manga/MangaReaderScreen.kt`

## Risks / notes
- Placeholder heights change fling feel (unloaded regions now have real
  extent) — that is the point, but worth a manual pass.
- The aspect map must be bounded and synchronized like the existing caches.
- No new locking (Rule 2 unaffected); all changes are UI-layer state keying
  and layout reservation.
- Chapter finalization correctness relies on Tasks 1+2 making the topmost
  visible report truthful again; no extra guard is planned — if QA still shows
  spurious read-marks, a follow-up can require a chapter crossing to persist
  across two settled layout passes before finalizing.
