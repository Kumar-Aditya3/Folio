# Semantic search

Folio's semantic search finds passages **by meaning** rather than by the words you typed. A
query like *"the ship sinks"* can reach a chapter that says *"the vessel foundered"*, which no
amount of keyword matching will do.

Everything runs on the device. No text, query, or vector ever leaves it, and there is no
generative model anywhere in the feature — it is one transformer **encoder** plus classic
algorithms.

---

## How it differs from exact search

The app already had FTS5 full-text search. The two are complementary, and the search screen
lets you switch between them, so you can compare them on your own library rather than take this
document's word for it.

| | Exact | Meaning | Best |
|---|---|---|---|
| what it does | SQLite FTS5, BM25-ranked | embedding cosine similarity | BM25 + cosine, fused |
| finds | the words you typed | paraphrases, related passages | both |
| good at | proper nouns, quotes, rare terms | concepts, descriptions of a scene | most queries |
| bad at | paraphrases ("vessel" ≠ "ship") | rare names it has never seen | — |

The three modes appear as chips above the results, but **only under the Content scope** — Titles,
Highlights, Notes and Bookmarks are not text corpora and offering a "meaning" mode there would
be a choice that cannot be honoured.

Measured on the test library, the difference is stark:

| query | Exact | Meaning |
|---|---|---|
| `teaching a child to listen in the dark` | zero results | the correct chapter of *Shadows Upon Time* |
| `grief over a dead friend` | "No matches" | grief passages across four different books |

Both of those queries are real English sentences with no rare words, and exact search returns
nothing for either — the words simply are not in the text.

---

## How it works

### 1. Chunking

A whole chapter is too coarse to embed: a 384-dimension vector cannot represent 5 000 words
without averaging away the detail that made a passage worth finding. So each chapter is split
into overlapping windows before it is embedded.

- **250-word windows, 50-word overlap** (`TextChunker.DEFAULT_MAX_WORDS` / `DEFAULT_OVERLAP_WORDS`).
  Overlap is what makes a passage retrievable regardless of where the author put the paragraph
  break — a sentence spanning a window boundary still appears whole in one of the two windows.
- **The window snaps back to a paragraph break** when one falls in the last 15% of it. A chunk
  that ends mid-sentence embeds slightly worse, so it is worth giving up a few words for a clean
  cut.
- **Windows, not sentences.** Sentence segmentation is deliberately avoided: EPUB plain text has
  no reliable sentence punctuation across a real corpus, and a wrong split costs recall silently.
- **Anything under 8 words is dropped** (`MIN_CHUNK_WORDS`). A three-word tail embeds to noise
  and pollutes results. Part titles, epigraphs and one-line interstitials fall here — in a real
  2 656-chapter library that is ~95 chapters, and they are excluded from the index total rather
  than being retried forever.
- **Character offsets are exact.** Every chunk records `charStart`/`charEnd` into the chapter's
  plain text, so a hit deep-links back into the reader at the right character.

Chunk ids are **content-derived**: `SHA-256(bookId \0 chapterId \0 charStart \0 contentHash)`.
This is the property that makes the index survivable — re-chunking identical text produces
identical ids, so a re-import cannot orphan vectors and a resumed backfill cannot duplicate
work.

### 2. Embedding

Each chunk is embedded by an ONNX transformer into a 384-float vector.

The session is created per use and closed afterwards rather than held open. A resident MiniLM
session is 40–60 MB and the multilingual one 150–200 MB, which is enough to get the app killed
in the background on a mid-range phone if it is never released.

`EmbedKind.QUERY` and `EmbedKind.PASSAGE` are distinct because some model families (e5) are
trained with different prefixes for the two; MiniLM ignores the distinction, but the type keeps
the call sites honest.

### 3. Storage

Vectors live in SQLite as BLOBs, so they sync and back up with everything else.

```
chapter_chunks   id, book_id, chapter_id, spine_index, chunk_index,
                 char_start, char_end, text, content_hash, model_id, dims
                 PRIMARY KEY (id, model_id)

chapter_vectors  chunk_id, model_id, dims, vector BLOB
                 PRIMARY KEY (chunk_id, model_id)
```

**`model_id` is in both primary keys and `content_hash` is on every chunk row.** This is not
optional bookkeeping: without them you cannot tell a stale vector from a fresh one, and a model
swap becomes an undebuggable quality regression rather than a well-defined amount of rework. A
schema repair pass enforces the composite keys on databases created before that decision.

`(book_id, chapter_id)` — not `chapter_id` — is the chapter identity throughout. **The EPUB
manifest id is only unique within a book**: in the 22-book test corpus, 1 272 chapters carry just
847 distinct ids, `titlepage` alone appearing in 13 books. Keying on the id alone once meant that
indexing one book silently deleted another's chunks, and that a third of the library was never
embedded at all.

### 4. Search

Query embedding happens on `Dispatchers.Default` — never on the composition dispatcher. The
index is preloaded when the search screen opens, not when the query arrives, so the first
keystroke does not pay for it.

- **Meaning** — cosine similarity against every stored vector, brute force.
- **Best** — BM25 and cosine, fused with **Reciprocal Rank Fusion**:
  `score(d) = Σ 1/(60 + rank(d))`. RRF is used rather than score averaging because BM25 scores
  and cosine similarities are not on comparable scales, and normalising them is fiddly and
  corpus-dependent.

The fusion is done at **chapter** granularity, because that is the granularity the FTS5 index
and the existing search UI already work at: embeddings rank chunks, and each chunk is mapped to
its owning chapter so both rankers produce comparable lists.

### 5. Backfill for an existing library

New books are embedded as they are imported, right after the FTS5 write. Import is already a
background path, so this adds no UI cost. But an existing library needs a one-off pass, and that
is the real cost of the feature: roughly **100 books × 400 chunks × 15 ms ≈ 10 minutes of
sustained CPU**.

Done on first launch, that is a battery and thermal event which also makes the app feel sluggish
exactly when the reader is exploring it. So it is:

- **charging-gated** (`WorkManager`, `setRequiresCharging` + `setRequiresBatteryNotLow`);
- **chunked** — 40 chapters per slice, so a run yields to the system instead of holding a core
  for ten minutes;
- **resumable and idempotent** — the slice query only selects chapters with no vectors for this
  model, so an interrupted run picks up where it stopped and a repeat run is a no-op;
- **visible** — Settings → Semantic search shows progress, and the "Resume indexing" button
  starts it immediately without waiting for a charger.

Progress is written to the database as chunks land, so the UI observes it through
`ChunkRepository.observeProgress` rather than the worker having to publish anything. That flow
recomputes on `Database.chunkDataRevision`, which every chunk write bumps — a revision of its
own, deliberately not `bookDataRevision`, because the latter drives `getAllBooks()` and the
collection and series flows and bumping it once per slice would re-query the whole library UI.
(It originally collected `bookDataRevision`, which nothing in the chunk repository ever bumped,
so the readout sat still during a backfill and made a working index look like a stalled one.)

---

## Models

Models are **never bundled in the APK** — the smallest candidate alone is 22 MB. They are fetched
on demand into the models directory and verified by SHA-256 before first use, because a truncated
download otherwise fails as a confusing ONNX parse error at search time.

The **runtime**, however, is bundled, and it is large: ONNX Runtime and ML Kit each ship a native
`.so` per ABI. Android packages every ABI's copy into one APK unless told otherwise, which took the
debug APK from 47 MB to **229 MB** — four identical sets of nine native libs, ~101 MB of it
emulator-only x86. `androidApp/build.gradle.kts` now restricts `ndk.abiFilters` to
`arm64-v8a` + `armeabi-v7a` and splits per ABI with a universal APK: **arm64 ~85 MB**,
armeabi-v7a ~70 MB, universal ~117 MB. Models being absent from the APK is a separate concern from
the runtime being present in it.

| model | dims | size | notes |
|---|---|---|---|
| `all-MiniLM-L6-v2` int8 | 384 | 23 MB | **shipped default**; English, WordPiece |
| `multilingual-e5-small` int8 | 384 | 118 MB | 100+ languages; kept in the catalog for the comparison, not offered for download |

The multilingual model is XLM-R based — Unigram/SentencePiece rather than WordPiece — so it
needs a different tokenizer and is not runnable through `WordPieceTokenizer`.

### Measured performance

On the MT6897 test device, backfill throughput after the query and threading fixes:

| | rate | CPU |
|---|---|---|
| before | 0.08 chapters/s | 148% |
| after | **0.74 chapters/s** | 369% |

Two independent causes, both found by measuring rather than reasoning:

1. **`ORDER BY` over a standalone FTS5 table.** FTS5 indexes tokens, not columns, so there is no
   B-tree on `book_id`/`spine_index`. Ordering by them made SQLite materialise every row —
   each chapter's full text included — into a temp B-tree before `LIMIT` applied:
   **2 452 ms vs 62 ms** for the same slice. Ordering by `rowid` (FTS5's storage order) is free
   and still deterministic.
2. **A missing composite index on the `NOT EXISTS` probe.** With only single-column indexes the
   planner walks every chunk of the model, so the cost *grew as the backfill proceeded*: 405 ms
   per slice with 2 000 chapters left, 890 ms with 40 left. With
   `idx_chunks_book_chapter_model(book_id, chapter_id, model_id)` it is a covering lookup and
   flat (~55 ms). On device this appeared as
   `SQLiteConnection: operation elapsed time: 38100 ms`.

Embedding threads default to `availableProcessors().coerceIn(1, 4)`. The cap is deliberate: the
encoder is small, so past four threads the synchronisation costs more than the parallelism
returns, and the remaining cores stay free for the reader the backfill is supposed to stay out
of the way of.

### Native memory: the session owns an arena

`openOnnxModel` returns a session that also holds its `OrtSession.SessionOptions`, and `close()`
releases the options **after** the session. This is not tidiness — `SessionOptions` is a native
handle and the session's CPU memory arena hangs off it, so releasing the options early would be a
use-after-free, and never releasing them leaks an arena per opened model.

That leak existed until 2026-09-18: options were closed on the failure path only, so every
successfully opened model stranded its arena. It was invisible in the app (one model per process)
and unmissable in the benchmark, which opens three — the test JVM reached a **36 GB working set
with a 648 MB live heap**, i.e. all of it where `-Xmx` and the GC cannot reach it. Measured, not
inferred:

```
jcmd <pid> GC.heap_info   ->  used 648085K of a 3145728K max
jcmd <pid> Thread.print   ->  DefaultDispatcher-worker-11  cpu=853843.75ms  elapsed=1582.76s
```

`enableCpuMemArena` (default **true**) lets a caller turn the arena off. The app keeps it on:
embedding runs in same-shaped batches, where one reused arena is pure win. A **sweep** turns it
off, because the arena only ever grows when every input is a different shape — which is exactly
what a 34 856-chunk benchmark pass is. `RetrievalQualityBenchmark` sets it false on every
embedder it builds.

---

## Where the code lives

```
commonMain/  ml/Embedding.kt            Embedder, Chunk, EmbeddingModel, VectorIndex
             ml/OnnxModel.kt            expect fun openOnnxModel
             ml/OnDeviceTextTools.kt     Phase 6 seam (OCR + translation) + desktop decision
commonJvm/   ml/TextChunker.kt          chunking
             ml/CosineIndex.kt          brute-force cosine
             ml/RrfFusion.kt            RRF
             ml/OnnxEmbedder.kt         ONNX session + inference
             ml/WordPieceTokenizer.kt   tokenizer
             ml/EmbeddingIndexer.kt     import path + backfill slices
             ml/SemanticSearchRepository.kt   search, modes, hybrid fusion, moreLikeThis
             ml/ZeroShotTagger.kt       Phase 5 #2 — pure ranking
             ml/AutoTaggerService.kt    Phase 5 #2 — ranking + tagRepository
             ml/ModelDownloader.kt      download + SHA-256 verify
             database/JdbcChunkRepository.kt  chunk/vector storage
             database/JdbcRepositories.kt     SearchRepository.getChapterTexts
             ui/quotes/RelatedWire.kt   Phase 5 #3 — resolves a hit to a readable passage
androidMain/ ml/OnnxModel.android.kt    onnxruntime-android
             ml/OnDeviceTextTools.android.kt  ML Kit recognition + translation
desktopMain/ ml/OnnxModel.desktop.kt    onnxruntime
             ml/OnDeviceTextTools.desktop.kt  documented no-op (see Phase 6 below)
androidApp/  work/EmbeddingBackfillWorker.kt
             settings/SettingsSemanticSearchScreen.kt
composeUi/   ui/search/SearchScreen.kt  mode chips
             ui/quotes/QuoteBrowserScreen.kt  "More like this" panel
             ui/book/TagSuggestionPanel.kt    suggested-tags panel
             ui/settings/SettingsPanelSemanticSearch.kt
```

The `expect`/`actual` split on the ONNX session is forced: `onnxruntime-android` and the desktop
`onnxruntime` are different artifacts, so the engine cannot live in `commonJvm`. Everything else
stays shared.

Brute-force cosine is deliberate — no `sqlite-vec`, no ANN index. 40 000 chunks × 384 dims × 4 B
is ~61 MB, and a full scan is ~15 M multiply-adds, i.e. 10–20 ms single-threaded. An ANN index
only becomes worth its complexity past roughly 200 000 chunks.

---

## Phase 5 #3 — "More like this"

`SemanticSearchRepository.moreLikeThis(text, excludeChunkId, limit)` embeds the passage the reader
already has and returns its nearest neighbours. The quote browser renders them behind a
**More like this** chip on each quote.

Two details are load-bearing:

- **The source passage is excluded** (`excludeChunkId`). Without it the top hit is always the quote
  itself, which reads as a broken feature.
- **"No similar passages" and "the index was never built" are different sentences.**
  `QuoteRelatedFinder.related()` returns a `RelatedLookup` — `Ready` / `NotIndexed` / `Failed` —
  not a bare list. An unindexed library reporting "nothing similar" is a claim the app cannot
  support, and the same distinction is why `SemanticSearchRepository.hasIndex()` exists.

Fusion keying is unchanged and still applies: hits resolve through `(bookId, chapterId)`, never
`chapterId` alone, because `chapter_id` is the EPUB manifest id and is book-local.

## Phase 5 #2 — Auto-tagging (zero-shot)

Embed the candidate tag names once, embed the book, cosine, threshold. No training, no LLM, no new
model — the same embedder and the same index lifecycle the search screen uses.

`ZeroShotTagger` is the pure ranking half and `AutoTaggerService` is the half that knows where
candidates come from. The split is what keeps the ranking exhaustively testable with
`FakeEmbedder`.

**The book vector is the mean of its chapter vectors**, not a vector of the concatenated text —
a 150-chapter book does not fit in 256 tokens, so embedding the whole thing would silently score
only the first chapter.

Three properties worth knowing:

| property | value | why |
|---|---|---|
| `DEFAULT_THRESHOLD` | `0.35` | Unrelated English prose scores ~0.1–0.2 against an unrelated label (MiniLM puts all English in a cone). A threshold inside that band assigns every tag to every book. |
| `DEFAULT_MARGIN` | `0.05` | How far the leader must clear the runner-up before it is marked `confident`. Only `confident` suggestions can be bulk-applied. |
| `MAX_CHAPTERS` | `60` | The mean is capped so a 300-chapter omnibus does not make one tagging pass cost more than a search. |

**Applying is additive and never destructive.** Nothing removes a tag. The reader's tags are the
only signal this feature has, and a tagger that can silently undo the reader's own work is not a
feature — hence the asymmetry, and hence the panel proposing (with the score shown) rather than
assigning. Only the `confident` subset can be applied in bulk, because a bulk write that looks like
the app's own idea has no undo.

`SearchRepository.getChapterTexts(bookId)` is the read path: one bulk query for the whole book out
of the FTS5 table, in spine order. It exists because the alternative — `searchInBook(title)` per
chapter, taking `snippet` — returns a 12-token fragment rather than the chapter, at O(chapters)
round trips.

## Phase 6 — OCR + translation (Android only, by decision)

`OnDeviceTextTools` is the seam; `expect fun onDeviceTextTools()` has a real ML Kit implementation
on `androidMain` and a documented no-op on `desktopMain`. Text recognition is one model per script
(`OcrScript`: Latin, Chinese, Devanagari, Japanese, Korean) because the models are not small.

**Decision: losing this on desktop is acceptable.** `ML_PLAN.md` asked for this explicitly. The
reasoning, in short: `com.google.mlkit:*` is an Android AAR with no desktop artifact and its
translation models arrive through Play Services, so a desktop equivalent would mean *two* new
engines, not one; a desktop already has better OCR in the OS (Preview text selection, `pdftotext`,
Tesseract) than anything this app would ship; the desktop corpus is EPUBs, which are text by
construction; and the cases that actually need OCR — a photographed page, a downloaded manga
chapter — are phone-shaped.

The seam is honest about the gap rather than quiet: the desktop actual reports
`OcrAvailability.UnsupportedOnThisPlatform` and the UI hides the affordance. Nothing throws, so a
caller that skipped the check degrades to "no result". `OnDeviceTextToolsDesktopTest` pins all of
that, because a decision that only lives in a comment is enforced by nothing.

Reversible: if desktop OCR is ever wanted, only the `desktopMain` actual changes.

---

## Tests

The `ml` package holds 79 tests, none of which need ONNX — `FakeEmbedder` returns deterministic
vectors, which is why the interfaces live in `commonMain`. That is what keeps the retrieval
layer testable without a model.

**Full suite: `:shared:desktopTest` is 625 tests across 93 classes, all green (2026-09-18).**
`RetrievalQualityBenchmark` is excluded from a normal run — it embeds the whole corpus twice and
takes ~36 minutes. Pass `-PfolioBench` to include it. It self-gates on model fixtures being
present, and those *are* present on a dev machine, so that gate alone does not protect a routine
test run.

The storage, loop and progress tests are the ones that matter most, because the bugs they guard
do not show up as wrong answers, only as an index that is quietly incomplete:

- `chapters that share an id across two books are tracked separately` — the regression that
  guarded against books evicting each other
- `backfill slice query is indexed and does not sort` — asserts on `EXPLAIN QUERY PLAN`, so a
  future edit that reintroduces the temp B-tree fails the build
- `chapters too short to chunk are not offered as work or counted in the total`
- `BackfillLoopTest` — runs the worker's `while` loop verbatim over a multi-book corpus whose
  books share chapter ids, and asserts it reaches `complete` having indexed every chapter, that a
  resumed run does no duplicate work, and that a slice is not masked by another book's ids
- `BackfillProgressTest` — holds a live collector and asserts the readout emits on every chunk
  write, and that chunk writes do *not* invalidate the book library flows
- `OnnxSessionLifecycleTest` — opens and closes 12 sessions twice and bounds growth in committed
  non-heap memory, so the `SessionOptions` arena leak cannot return unnoticed
- vector round-tripping, little-endian encoding, corrupt-blob rejection, model isolation

Two test-writing traps worth knowing, both hit here: a fixture of fewer than 8 words indexes
**zero chunks** (see `MIN_CHUNK_WORDS` above), and Gradle discards a *passing* test's stdout, so
instrumenting a fixture requires throwing rather than printing.

### Retrieval quality, measured

`RetrievalQualityBenchmark` (Phase 0b) measures recall@5 / recall@10 / MRR@10 for BM25-AND,
BM25-OR, MiniLM and the RRF hybrids against a 22-book corpus (1 270 chapters → 34 856 chunks)
with hand-checked gold passages. **Run it with the fast knobs on** — the full corpus takes ~27
minutes and the e5 row several times that:

```
./gradlew.bat :shared:desktopTest --tests "com.folio.reader.ml.RetrievalQualityBenchmark" \
  -Dfolio.bench.e5=false
```

Progress is appended live to `.dbg/phase0/phase0b-progress.log` (Gradle buffers test stdout until
the test exits, so without this file a killed run leaves nothing), and the report is written to
`.dbg/phase0/phase0b-retrieval.md`.

**Measured 2026-09-18** — the reason the *Exact / Meaning / Best* choice exists at all:

| ranker | recall@5 | recall@10 | MRR@10 | paraphrase r@10 | lexical r@10 |
|---|---|---|---|---|---|
| BM25 (AND, shipped) | 25% | 25% | 0.192 | **0%** | 83% |
| BM25 (OR, fair) | 35% | 35% | 0.263 | 7% | **100%** |
| MiniLM-L6-v2 | 30% | 40% | 0.175 | **43%** | 33% |
| RRF (BM25-OR + MiniLM) | 30% | 45% | 0.167 | 36% | 67% |
| RRF (BM25-AND + MiniLM) | **40%** | **50%** | 0.208 | 29% | **100%** |

Two conclusions that are baked into the product:

1. **Semantic retrieval does the job it was built for.** On paraphrase queries — the ones with no
   shared vocabulary — lexical search goes 0–7% and MiniLM reaches **43%**. That is the "find the
   passage, not the word" promise, quantified.
2. **The hybrid is not the better default.** RRF gains recall but **loses MRR (0.167 vs 0.263)**
   against a fair BM25 baseline: it ranks the right answer *lower*. So exact search stays the
   default and "Meaning" is offered for paraphrase, rather than a single fused ranking quietly
   getting worse.

Cost: MiniLM embeds at **23.1 chunks/s** on desktop (4 threads) — 25 minutes for a 22-book
library. This is why the model is downloaded on demand and the backfill is charging-gated.

---

## Known limitations

- **English only, by default.** The shipped model is MiniLM. The multilingual comparison exists
  in the catalog but is not offered as a download, and its Phase 0b row is still unmeasured.
- **Brute force.** A full cosine scan over the whole library on every query — measured at
  **~259 ms to decode a 34 856-vector library**. Fine at 40 000 chunks; the number to watch is
  that one, and an ANN index is the fix past it.
- **No OCR.** Scanned PDFs and manga pages are not readable text and therefore not indexed.
- **"More like this" has no UI yet.** `SemanticSearchRepository.moreLikeThis()` is implemented
  and tested but nothing calls it.
- **Auto-tagging is not implemented.**
- **Storage throughput is measured on desktop only.** Vectors are written as BLOBs at
  **0.062 ms/chunk** through `sqlite-jdbc`. On Android the same JDBC calls go through
  `org.sqldroid:sqldroid:1.1.0-rc1`, a thin and unmaintained shim, and that specific path is
  unmeasured — the residual risk is the shim, not the schema.
