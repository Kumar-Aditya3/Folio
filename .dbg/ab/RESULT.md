# Home→Library morph: controlled A/B result

**Question.** Do the five cold-start fixes (hoisted `libraryBooks`, `knownBooks` seed,
remembered shelf flows, accent sampling off the UI thread, draw-phase cover reveal)
actually improve the Home→Library morph?

**Answer: no measurable improvement.** The composition count and timing in the
transition window are unchanged, and the cold/warm ratio does not move.

## Method

Two APKs, measured under identical conditions in one session, interleaved:

- `prefix.apk` — the tree with the five changes reverted (built from a backup-restored
  source tree, `BUILD SUCCESSFUL in 11m 54s`).
- `postfix.apk` — the fixed tree.

Each batch is 3 cold first-visits (force-stop, launch, 1.5 s, tap Library) and 2 warm
second-visits. Batch order was **post → pre → post**, so a monotonic device drift shows
up as pre sitting between the two post batches.

### Why the cold/warm *ratio* and not absolute ms

The first post-fix runs came back uniformly worse than a baseline recorded hours
earlier — but warm moved by +5.6 ms while cold moved by +6.1 ms, i.e. by the same
amount. A cold-path code change cannot slow the warm path, so that shift was device
state (the phone is now on USB power; this morning it was on battery). A uniform
slowdown multiplies both terms, so the **ratio** is what survives. The three batches
below confirm the drift was real and roughly monotonic.

## Results

| batch | time | cold avg | warm avg | **ratio** | cold worst | warm worst | worst ratio |
|---|---|---|---|---|---|---|---|
| post-fix #1 | 20:35 | 37.01 | 30.69 | **1.206** | 188.2 | 124.4 | 1.513 |
| **pre-fix** | 20:52 | 35.96 | 29.85 | **1.205** | 141.9 | 93.9 | 1.512 |
| post-fix #2 | 21:05 | 40.01 | 32.07 | **1.248** | 217.1 | 127.6 | 1.702 |

Raw runs (avg ms):

```
pre_cold1  38.67   pre_cold2  36.00   pre_cold3  33.21   pre_warm1  29.09   pre_warm2  30.61
post_cold1 37.09   post_cold2 35.65   post_cold3 38.28   post_warm1 30.57   post_warm2 30.80
post2_cold1 36.15  post2_cold2 43.76  post2_cold3 40.12  post2_warm1 31.01  post2_warm2 33.13
```

Pre-fix sits exactly where the drift predicts (between post #1 and post #2 in both cold
and warm), so the two builds are **indistinguishable**. The ratio — the drift-free
metric — is 1.205 pre vs 1.206/1.248 post: no benefit, possibly a slight harm.

## The composition pattern is unchanged

Frame-level, `recompose` in the transition window (t = 200–700 ms). If the seed had
removed the skeleton→grid swap, the grid would compose once, on the destination's first
frame, and the second event would disappear.

```
pre_cold2    t=299.8 rc= 1.27 | t=310.9 rc= 8.07 | t=322.0 rc=30.33 | t=344.2 rc= 7.99
post2_cold2  t=311.0 rc=12.40 | t=322.1 rc=36.41 | t=366.5 rc=16.91 | t=544.2 rc=32.34
pre_cold1    t=333.1 rc= 1.14 | t=344.2 rc= 7.19 | t=355.3 rc=30.71 | t=377.5 rc= 9.83
post2_cold1  t=266.5 rc= 6.63 | t=277.6 rc=22.56 | t=288.7 rc=15.70 | t=422.0 rc=24.88
```

Both builds still show **two** composition events: a small one, then a 22–36 ms one
11–22 ms later. Total recompose in the window: pre 94.1 / 101.1 / 87.2 ms, post
89.3 / 108.5 / 98.7 ms — the same work, not less.

## Why the seed cannot help

`LibraryViewModel.filteredBooks` (`LibraryViewModels.kt:290`) ends with:

```kotlin
return combine(baseFlow, shelfBookIds) { books, shelfIds -> ... }
```

`combine` emits **nothing** until *both* upstreams have emitted. And `libraryVM` is
`by lazy`, constructed by the destination's own first composition — so `allBooks()` and
`shelfBookIds` both begin their reads at that instant.

So the seed does hold the destination's opening frame (no skeleton), but `combine`'s
first real emission arrives one DB round-trip later (~25–30 ms) and recomposes the grid
anyway. One composition is traded for another: the count is unchanged, and the grid —
the expensive part — composes either way. The saving was only ever the *skeleton's*
composition, which is cheap.

## The actual ceiling

`render` is 8.0–9.6 ms per frame in **every** run, cold and warm, on **both** builds.
At 90 Hz the budget is 11.1 ms, so render alone puts the app at ~30 fps for the whole
transition. No composition-side change can make this morph smooth.

That is the Haze `hazeSource` backdrop capture paid twice, because a transition composes
two pages. The one lever for it — standing the backdrop down during the transition — was
already tried and reverted, because the bars visibly lose their glass.

## Recommendation

- **Revert** the `libraryBooks` hoist + `knownBooks` seed. It adds an app-lifetime eager
  DB subscription (a second permanent subscriber on `allBooks()`) for no measured gain.
- The other three are independently defensible and worth keeping on their own merits,
  though none of them moved the morph:
  - `remember`ed shelf flows — fixes a genuine bug: `allBooks()`/`allSeries()`/
    `filteredBooks()` each return a *new* Flow per call, so calling them inline made
    `collectAsState` resubscribe on every recomposition (a fresh DB read each time).
  - accent sampling on `Dispatchers.Default` — moves a real ~10 ms UI-thread read off
    the main thread.
  - draw-phase cover reveal — removes per-frame recomposition of every cover.
- **Next lever is render, not composition.** Anything that reduces the two `hazeSource`
  captures per transition, without the material flicker that got the last attempt
  reverted.

## Reproduce

```
bash .dbg/ab/measure_build.sh <label> 3 2      # 3 cold + 2 warm for the installed build
```
