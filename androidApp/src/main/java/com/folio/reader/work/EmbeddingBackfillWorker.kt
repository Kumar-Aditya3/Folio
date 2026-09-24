package com.folio.reader.work

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.folio.reader.AppGraph
import com.folio.reader.FolioApplication
import com.folio.reader.ml.BackfillSlice
import com.folio.reader.ml.EmbeddingIndexer
import com.folio.reader.ml.MlDispatchers
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds the semantic index for an existing library.
 *
 * This is the real cost of the feature, not the inference: 100 books x ~400 chunks x
 * ~15 ms is roughly ten minutes of sustained CPU. Done on first launch that is a battery
 * and thermal event which also makes the app feel sluggish exactly when the reader is
 * exploring it, so it is:
 *
 * - **core-capped** — a slice reads whether the UI is on screen and takes fewer intra-op threads
 *   when it is ([MlDispatchers.backfillThreads], a quarter of the cores), and runs on
 *   `MlDispatchers` rather than the generic pool so it cannot take the cores the UI needs. This
 *   replaces a gate that *waited* for the app to be backgrounded before working; on ColorOS that
 *   was self-defeating, because the OS freezes a backgrounded process within seconds — see the
 *   call site in [doWork] for the log evidence. It also replaces the `setRequiresCharging` gate
 *   that came before that: charging is satisfied precisely when someone is reading on a cable, so
 *   it let the backfill run flat out during use, and on many devices a battery at 100% reports
 *   `BATTERY_NOT_LOW` false, so it also silently blocked the work it was meant to allow;
 * - **chunked** — one [EmbeddingIndexer.backfillSlice] per loop, so a run yields to the
 *   system instead of holding a core for ten minutes;
 * - **reduced-footprint** — the slice's ONNX session gets fewer intra-op threads than an
 *   interactive one, and runs on `MlDispatchers` rather than the generic pool, so it cannot
 *   take the cores the UI itself needs;
 * - **resumable and idempotent** — the slice query only ever selects chapters with no
 *   vectors for this model, so an interrupted run picks up where it stopped and a repeat
 *   run is a no-op rather than duplicated work.
 *
 * Progress is written to the database as chunks land, so the UI can observe it through
 * `ChunkRepository.observeProgress` without this worker having to publish anything.
 */
class EmbeddingBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * Chapters indexed by this run, kept only so the foreground notification can report progress.
     * Resets every run by construction, which is correct: the notification describes *this* pass,
     * not the library's lifetime total.
     */
    private var indexedThisRun = 0

    override suspend fun doWork(): Result {
        // One sweep at a time, process-wide.
        //
        // WorkManager serialises work within a *chain*, but this feature enqueues under three
        // different unique names (`embedding_backfill`, `_manual`, `_cont`) and a chain is only
        // serialised against itself. So a scheduled run and a continuation could both be live, and
        // on device they were: `dumpsys jobscheduler` showed two healthy backfill rows (#454, #455,
        // both `policy=0 initial=+1m0s0ms`) and the instrumented log caught the consequence —
        //
        //   OnnxMem: session created: pss 287MB -> 400MB ...   (pid 18161)
        //   OnnxMem: session created: pss 328MB -> 430MB ...   (pid 18161)
        //   OnnxMem: run #1: pss=4380MB ...
        //   OnnxMem: run #1: pss=4321MB ...
        //
        // — two sessions and two forward passes in one process, at the same millisecond. That is
        // double the peak of an already-fatal number, and it also halves throughput by making the
        // two runs contend for the same cores.
        //
        // `Result.success()` rather than `retry()`: the other run is making progress and will hand
        // over via `continueLater` when it ends, so this duplicate's work is not lost — it is
        // already being done. Retrying would only add a backoff cycle and burn an attempt.
        if (!ACTIVE.compareAndSet(false, true)) {
            Log.i(TAG, "another backfill run is active in this process; exiting this duplicate")
            return Result.success()
        }
        return try {
            runBackfill()
        } finally {
            ACTIVE.set(false)
        }
    }

    private suspend fun runBackfill(): Result {
        // Promote to a foreground service *before* any real work.
        //
        // ### Why this is the load-bearing fix for "it only works while I'm in the app"
        //
        // As a plain `CoroutineWorker` this ran as an ordinary background job, and the reader
        // reported the consequence exactly: *"it only works when im on the app"*. Measured on the
        // device while backgrounded, the process was reaped and restarted repeatedly — pids cycled
        // `27685 -> (dead) -> 28874 -> 29868` over 80 seconds — with an **empty crash buffer**,
        // i.e. the system killing a cached process rather than any exception in our code. Each
        // reaping lost the in-flight slice and burned a backoff attempt, so indexing could only
        // advance while the app happened to be visible and the process happened to be TOP.
        //
        // `setForeground` is what changes that: it hands WorkManager a notification and asks it to
        // run this work in a `dataSync` foreground service, which the platform will not reap for
        // being cached. The permissions and the service type already exist for manga downloads
        // (`MangaDownloadService`), so this reuses that capability rather than adding a new one.
        //
        // ### Refusal is recoverable, never fatal — and it is also the common case
        //
        // On Android 12+ a foreground start from the background is refused with
        // `BackgroundServiceStartNotAllowedException`. That must not crash the app — the backfill
        // is resumable and idempotent, so the correct response is to log it and carry on as an
        // ordinary background worker, which is exactly how `MangaDownloadService.start` treats the
        // same refusal. Without this guard, the fix for a crash would introduce a worse one.
        //
        // Observed on the test device, and it is what the whole `readerAway` change below turns on:
        //
        // ```
        // W/EmbeddingBackfill: foreground start refused; continuing as a background worker
        //     android.app.BackgroundServiceStartNotAllowedException: Not allowed to start service
        //     Intent { act=ACTION_NOTIFY ... SystemForegroundService }:
        //     app is in background uid UidRecord{... LAST bg:+3m22s746ms ...}
        // ```
        //
        // So promotion succeeds **only when the app is on screen at the moment the worker starts**,
        // which is the opposite of what the old "wait for the app to be backgrounded" gate assumed.
        // That gate made the refusal the normal case by construction: it deferred until the app was
        // backgrounded, and by then the one thing that could have kept the process alive was no
        // longer permitted to start.
        //
        // Holding an FGS is what makes the difference on this device, because ColorOS does not
        // freeze a process that has one — see the `OplusHansManager` freeze/unfreeze pair quoted at
        // the `readerAway` call site. The promotion is attempted once, up front, while the app is
        // still visible; everything after that inherits it.
        val foregrounded = goForeground(notificationText())
        if (!foregrounded) {
            Log.i(TAG, "no foreground service; this run is an ordinary cached job")
        }

        val graph = (applicationContext as? FolioApplication)?.graph ?: run {
            Log.e(TAG, "no FolioApplication graph; cannot index")
            return Result.failure()
        }
        val indexer = graph.embeddingIndexer

        // Drop a stale generation of vectors before indexing.
        //
        // Vectors are keyed by `model_id`, so *switching models* is already lossless — the other
        // model's index is untouched. A change to the **chunk window** is not covered by that,
        // because the window is not part of the model id: when it moved from the flat 250 words to
        // `EmbeddingModel.maxChunkWords` (173 for MiniLM, 350 for Arctic-S) the boundaries moved,
        // but `chaptersMissingVectors` skips any chapter that already has chunks for the model, so
        // the backfill would never revisit them. The result would be two generations of chunks for
        // the same passage, both retrievable.
        //
        // So the recipe the vectors were built with is compared against the one in force, and a
        // mismatch deletes the model's rows. That is deliberately destructive and deliberately
        // narrow: only this model, and only when the recipe genuinely differs. Re-running with an
        // unchanged recipe is a no-op, which keeps the backfill idempotent and resumable as before.
        val staleReason = try {
            reconcileChunkRecipe(graph)
        } catch (error: Throwable) {
            // Not fatal: a bookkeeping table that cannot be read is not a reason to refuse to
            // index. The worst case is that a stale generation survives one more run.
            Log.e(TAG, "could not reconcile chunk recipe; indexing anyway", error)
            null
        }
        if (staleReason != null) Log.i(TAG, "cleared stale index: $staleReason")

        var totalChapters = 0
        var totalChunks = 0
        var totalFailed = 0
        var rounds = 0
        val startedAt = System.currentTimeMillis()

        while (rounds < MAX_ROUNDS) {
            if (isStopped) {
                Log.i(TAG, "stopped after $rounds slice(s), $totalChapters chapter(s) indexed")
                return continueLater(totalChapters, totalChunks)
            }

            // Is anyone looking at the UI right now? This decides how many cores the slice may
            // take — and nothing else.
            //
            // ### This used to *wait* for the app to be backgrounded, and that was backwards
            //
            // The wait was added for a real reason: on the 8-core MT6897 test device a backfill
            // held ~310% CPU while the reader used the app, and frames fell to ~9 fps with 59%
            // janky frames and the GPU idle. So each slice stepped aside until the reader left.
            //
            // On this device that is a trap, because "backgrounded" and "runnable" are not the
            // same state. ColorOS freezes background apps:
            //
            // ```
            // 00:57:29  OplusHansManager: unfreeze uid: 10452 com.folio.reader  reason: TransBinder
            // 00:57:30  OnnxMem: session created
            // 00:57:34  OplusHansManager: freeze uid: 10452 com.folio.reader  pids: [29524]
            // ```
            //
            // Five seconds after leaving the foreground the process is frozen, and a frozen
            // process executes nothing. So the gate was not "step aside while the reader is busy";
            // it was "wait for the one state in which this device will not run us", and the
            // observable symptom was exactly the report that prompted this change — *"it only
            // works when I'm on the app"*. Throughput measured across the two states differs by
            // more than an order of magnitude (≈5 chunks/s unfrozen, ≈0.4 chunks/s frozen) while
            // the process stays alive the whole time, which is why it looked like slowness rather
            // than a stall.
            //
            // So the work now proceeds in whatever state it finds, and the *thread count* is what
            // protects an open reader ([MlDispatchers.backfillThreads] — a quarter of the cores,
            // chosen to leave the UI and interactive search their own). That is a better lever
            // than deferral anyway: it is the one that was actually validated. The 9 fps
            // regression was diagnosed as `Dispatchers.Default` starvation, and the fix for it
            // was `MlDispatchers`' own smaller pool, not this gate.
            //
            // When the app *is* backgrounded we still take more threads — but only because that
            // is then safe, not because we waited for it.
            val readerAway = !isForeground()
            // `awaitHeadroom` reports whether it had to wait, i.e. whether the device is under
            // real pressure. Holding a smaller batch is then the one change that lowers *peak*
            // footprint — the slice length cannot, because the peak is reached inside a single
            // forward pass, which `EmbeddingIndexer.flush` now bounds.
            val pressured = awaitHeadroom()
            rounds++

            val slice: BackfillSlice = try {
                indexer.backfillSlice(
                    limit = sliceSizeForMemory(pressured),
                    // `null` means "use the batch this model derives for itself" — see
                    // [EmbeddingIndexer.batch]. Under pressure that is quartered, which is the only
                    // lever that lowers *peak* footprint: the slice length cannot, because the peak
                    // is reached inside a single forward pass.
                    batchSize = if (pressured) constrainedBatchFor(indexer.batch) else null,
                    // More cores only when the reader is provably elsewhere, so an open reader
                    // always keeps the smaller share. `null` means "the indexer's own
                    // [MlDispatchers.backfillThreads] figure". See [MlDispatchers.backgroundThreads].
                    threads = if (readerAway) MlDispatchers.backgroundThreads else null,
                    // A slice must not outlive the platform's job limit, and only the loop doing the
                    // work knows how long it has been going. Shorter still under pressure, so a
                    // struggling device gets to re-evaluate sooner.
                    budgetMs = if (pressured) SLICE_BUDGET_MS / 3 else SLICE_BUDGET_MS,
                )
            } catch (error: Throwable) {
                // Logged, not merely printed: the release build has no visible stderr, and a
                // silent retry is indistinguishable from a stalled worker. This is what made
                // the earlier "Resume indexing does nothing" investigation take so long.
                //
                // `retry()` is correct *here* — this is a genuine fault, not "more work to do" —
                // and `runAttemptCount` clamping it is the behaviour we want. It is still bounded
                // so a persistent fault cannot drive the delay to hours: past
                // MAX_ERROR_ATTEMPTS the work is failed rather than retried forever.
                Log.e(TAG, "backfill slice threw", error)
                return if (runAttemptCount >= MAX_ERROR_ATTEMPTS) {
                    Log.e(TAG, "giving up after $runAttemptCount failed attempt(s)")
                    Result.failure()
                } else {
                    Result.retry()
                }
            }

            // No model on disk: there is nothing to do, and retrying will not change that.
            if (slice.modelMissing) {
                Log.i(TAG, "no model on disk; nothing to index")
                return Result.success()
            }

            totalChapters += slice.indexedChapters
            totalChunks += slice.indexedChunks
            totalFailed += slice.failedChapters
            setProgress(
                workDataOf(
                    PROGRESS_CHAPTERS to totalChapters,
                    PROGRESS_CHUNKS to totalChunks,
                )
            )

            // The only on-device view of what the system actually prices.
            //
            // Java cannot read its own *native* PSS, and the native heap is where this feature's
            // memory goes — but `Debug.getPss()` reports whole-process PSS, which is the quantity
            // the lowmemorykiller and ColorOS's watcher compare against every other app. Logging it
            // per slice turns "indexing is heavy" into a time series instead of a single reading
            // taken by hand while racing the sampler.
            //
            // `batch` is logged because it is the term under measurement: PSS against batch is what
            // re-calibrates `EmbeddingIndexer.BYTES_PER_ROW_PER_TOKEN_SQUARED`, whose current value
            // came from runs taken with the arena still on. See `EmbeddingIndexer.batch`.
            Log.i(
                TAG,
                "slice: +${slice.indexedChapters}ch/${slice.indexedChunks}ck " +
                    "batch=${if (pressured) constrainedBatchFor(indexer.batch) else indexer.batch} " +
                    "pss=${Debug.getPss() / 1024}MB " +
                    "running=${totalChapters}ch/${totalChunks}ck",
            )

            // Keep the foreground notification in step. This is what makes the pass visibly alive
            // from the notification shade while the app is closed — the only progress signal that
            // exists when the reader is not looking at the settings screen.
            indexedThisRun = totalChapters
            goForeground(notificationText())

            if (slice.failedChapters > 0) {
                Log.w(
                    TAG,
                    "slice skipped ${slice.failedChapters} chapter(s); indexed ${slice.indexedChapters}",
                    slice.firstError,
                )
            }

            if (slice.complete) break

            if (slice.indexedChapters == 0) {
                // Nothing moved. Chapters that failed will be offered again, so coming back is
                // right — but the library being exhausted is a success, not a retry.
                return if (slice.failedChapters > 0) {
                    Log.w(TAG, "no progress: $totalFailed chapter(s) failing to index")
                    continueLater(totalChapters, totalChunks)
                } else {
                    Log.i(TAG, "library exhausted after $rounds slice(s)")
                    classifyGenresBestEffort(graph)
                    Result.success()
                }
            }

            // One WorkManager run should do real work, not a few seconds of it. How long it may
            // last depends on whether we hold a foreground service — see [RUN_BUDGET_FGS_MS].
            if (System.currentTimeMillis() - startedAt > runBudgetMs(foregrounded)) {
                Log.i(
                    TAG,
                    "run budget reached (${if (foregrounded) "fgs" else "job"}); " +
                        "$totalChapters chapter(s) this run",
                )
                return continueLater(totalChapters, totalChunks)
            }
        }

        Log.i(
            TAG,
            "run finished: $totalChapters chapter(s), $totalChunks chunk(s), " +
                "$totalFailed failure(s) over $rounds slice(s)",
        )
        classifyGenresBestEffort(graph)
        return Result.success(
            workDataOf(
                PROGRESS_CHAPTERS to totalChapters,
                PROGRESS_CHUNKS to totalChunks,
            )
        )
    }

    /**
     * Best-effort broad-genre classification of embedded books that still lack a genre row for the
     * current model (Atlas galaxy communities). Runs after the embedding pass, so the books just
     * embedded this run are covered; a model swap re-derives genres because they are keyed by model
     * id. Null-safe and never fatal — the Atlas degrades to "Mixed" communities without it.
     *
     * It reuses the *stored* chunk vectors (a cheap mean per book) rather than re-embedding whole
     * books, and only embeds the ≈20 fixed taxonomy labels once, so it is far lighter than the
     * embedding pass it follows and does not need the same memory gating.
     */
    private suspend fun classifyGenresBestEffort(graph: AppGraph) {
        val service = graph.genreClassification ?: return
        // Re-derive genres once when the classifier algorithm changes. Rows are keyed by model, not
        // by algorithm, so a library already classified by the previous (flat-threshold) classifier
        // would otherwise keep its stale genres forever. A stored per-model version makes the clear
        // happen exactly once per bump; failures here are swallowed so classification still runs.
        runCatching {
            val key = "genre_classifier_version:${service.model.id}"
            val seen = graph.settingsRepository.getRaw(key)?.toIntOrNull()
            if (seen != com.folio.reader.ml.GenreClassificationService.CLASSIFIER_VERSION) {
                val cleared = service.clearForModel()
                if (cleared > 0) Log.i(TAG, "genre: cleared $cleared stale row(s) for reclassification")
                graph.settingsRepository.setRaw(
                    key,
                    com.folio.reader.ml.GenreClassificationService.CLASSIFIER_VERSION.toString(),
                )
            }
        }.onFailure { Log.w(TAG, "genre reclassification gate skipped", it) }
        runCatching { service.backfillMissing() }
            .onSuccess { classified -> if (classified > 0) Log.i(TAG, "genre: classified $classified book(s)") }
            .onFailure { Log.w(TAG, "genre classification skipped", it) }
    }

    /**
     * Asks WorkManager to run this work inside a `dataSync` foreground service.
     *
     * Also used to refresh the notification's text as progress advances, which is why it takes the
     * message rather than reading state: the reader can watch the index build from the notification
     * shade, and that is the only visible signal that the pass is still alive when the app is not
     * open.
     *
     * Refusal is logged, never thrown — see the call site in [doWork] for why.
     *
     * Returns whether the promotion actually succeeded, because the answer changes how long this
     * run may last — see [RUN_BUDGET_FGS_MS]. A `false` here is not an error, but it is a fact
     * worth carrying: it means the platform will treat this run as an ordinary cached job, and on
     * ColorOS it means the process will be frozen the moment the app leaves the screen.
     */
    private suspend fun goForeground(text: String): Boolean =
        runCatching { setForeground(foregroundInfo(text)) }
            .onFailure {
                Log.w(TAG, "foreground start refused; continuing as a background worker", it)
            }
            .isSuccess

    /** WorkManager's own hook for an expedited/foreground run; delegates to the same builder. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(notificationText())

    private fun foregroundInfo(text: String): ForegroundInfo {
        createChannel()
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(text),
            // A compile-time constant, and `ServiceCompat` ignores it below API 29 — the same
            // arrangement `MangaDownloadService.goForeground` relies on.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Notification channel for the index pass, created on every call because creating an existing
     * channel is a no-op and the worker has no guaranteed single entry point.
     *
     * `IMPORTANCE_LOW` and no badge, matching the downloads channel: this is ongoing background
     * progress, and it must not make a sound or light up the status bar each time it updates.
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Indexing",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress while the semantic search index is built"
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * Returns a built `Notification`, not the builder: `ForegroundInfo`'s constructor takes a
     * `Notification`, and passing the builder is a compile error rather than a runtime one — which
     * is the good outcome, since a silently-wrong notification type here would fail at the moment
     * the worker tries to promote itself.
     */
    private fun buildNotification(text: String): Notification {
        val contentIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(applicationContext.applicationInfo.labelRes))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)                 // non-dismissible while the pass runs
            .setOnlyAlertOnce(true)           // progress updates must not re-alert
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun notificationText(): String =
        if (indexedThisRun > 0) "Indexed $indexedThisRun chapters" else "Building search index"

    /**
     * Whether this process currently hosts a visible UI.
     *
     * Read through `getMyMemoryState` because that is the only public way to read *our own*
     * importance — there is no `ActivityManager.importance` property, and
     * `getRunningAppProcesses` is both deprecated for third-party callers and needlessly
     * expensive to poll. The out-param is filled in on success; on failure the default
     * `IMPORTANCE_CACHED` keeps this false, so an unreadable state is treated as "nobody is
     * looking" and never blocks the backfill.
     *
     * This is a *state reading*, not a gate. It is consulted once per slice to choose a thread
     * count, and the slice proceeds either way — see the call site for why waiting on it was
     * removed. A background or cached process has no frames to drop, and neither does one whose
     * screen is off, which is why the backfill correctly carries on with the display asleep.
     */
    private fun isForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * Suspends while the device is short of memory, so indexing cannot evict other apps.
     *
     * ### Why this exists
     *
     * The reader's report on 2026-09-18 was that indexing **crashed YouTube Music** and **reset this
     * app** when switching back to it. Both are the lowmemorykiller acting on this process's native
     * footprint: the ONNX session and the tokenizer's buffers are native, so the Java heap stays
     * around 19 MB while the process holds 819 MB. Android's LMKD prices that against every other
     * app on the device, and the cheapest one to evict loses.
     *
     * Shrinking the slice does not address this — peak footprint is `batch x seq^2` inside a single
     * forward pass, which the slice length does not change. The only correct response is to not hold
     * the memory when the device cannot spare it.
     *
     * ### Yield, don't abort
     *
     * Bounded by [MAX_HEADROOM_WAIT_MS] so a permanently-tight device still makes slow progress
     * rather than never indexing. Reaching the bound proceeds anyway rather than returning: at that
     * point the reader has had their other app foregrounded for minutes, so the pressure is
     * chronic rather than a transient spike, and refusing forever would mean the index never
     * completes on such a device.
     *
     * `availMem` is used rather than `totalMem` because it is the headroom the kernel can hand out
     * *without* reclaiming — which is exactly the quantity that decides whether allocating another
     * batch forces an eviction elsewhere.
     *
     * @return true when it had to wait, i.e. the device is under real pressure. The caller uses
     *   this to shrink the batch, which is the only lever that actually lowers peak footprint.
     */
    private suspend fun awaitHeadroom(): Boolean {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        // Resolved once per call rather than per iteration: `totalMem` cannot change, and the
        // floor is what the loop compares against.
        val floor = runCatching {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            headroomFloorFor(info.totalMem)
        }.getOrNull() ?: return false
        var waited = 0L
        while (!isStopped && waited < MAX_HEADROOM_WAIT_MS) {
            val avail = runCatching {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.availMem
            }.getOrNull() ?: return waited > 0
            if (avail >= floor) {
                if (waited > 0) Log.i(TAG, "resumed after waiting ${waited}ms for headroom")
                return waited > 0
            }
            Log.i(
                TAG,
                "low memory: ${avail / (1024 * 1024)}MB free of " +
                    "${floor / (1024 * 1024)}MB needed; yielding",
            )
            delay(HEADROOM_POLL_MS)
            waited += HEADROOM_POLL_MS
        }
        if (waited >= MAX_HEADROOM_WAIT_MS) {
            // Proceeding anyway, and saying so: on a device that stays tight this is the normal
            // path, and without this line the only evidence would be an index that moves slowly
            // for no visible reason — the ambiguity that made this feature hard to diagnose.
            Log.w(TAG, "still short of headroom after ${waited}ms; proceeding at reduced batch")
        }
        return true
    }

    /**
     * Chapters for the next slice, reduced when the device is already short of memory.
     *
     * ### Why this exists
     *
     * The user report that prompted it was **not** "the app janks" but "**indexing hangs the entire
     * phone**" — a system-wide symptom. On-device measurement fits that: the process held 819 MB of
     * *native* heap against a 19 MB Java heap, and system CPU was `sys`-dominated
     * (`800%cpu 109%user 215%nice 194%sys 245%idle`). Kernel time above user time plus a whole-device
     * stall is memory pressure and reclaim, not compute.
     *
     * A desktop probe (`NativeFootprintProbeTest`) then ruled out the obvious cause: `close()` works,
     * a live session costs ~41 MB exactly linearly, and six sequential open/embed/close rounds left
     * `delta_alive` *negative* every time. There is no per-search leak. So the pressure is **peak
     * footprint**.
     *
     * Peak footprint is set by how many chunks are resident inside a *forward pass*, which is a
     * property of the indexer rather than of the slice — so this method no longer sizes anything for
     * memory, and the real levers are the indexer's batch budget and [awaitHeadroom].
     *
     * ### What it does
     *
     * Returns the number of chapters to fetch for one slice: [CHAPTERS_PER_SLICE], or
     * [CONSTRAINED_CHAPTERS_PER_SLICE] when [awaitHeadroom] timed out and the device is genuinely
     * short. That is a *batching* decision — how much work to do before re-checking the reader and
     * the device, and how often to pay for an ONNX session (measured at 142 MB plus a graph load).
     *
     * ### Why it stopped being memory-derived
     *
     * It used to divide a quarter of `availMem` by a per-batch peak, which on the test device always
     * collapsed to the 4-chapter floor: a 2561-chapter library then needed ~640 slices, each
     * rebuilding a session, and the reader's report of indexing being "extremely slow" is exactly
     * that shape. The arithmetic was only sound while peak footprint scaled with the slice length.
     * It no longer does — `EmbeddingIndexer.flush` bounds the pass at
     * [EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES] regardless of how many chapters the slice covers,
     * so charging every chapter a full batch was modelling a constraint that had stopped existing.
     *
     * The remaining guard on slice length is wall-clock, not memory: [SLICE_BUDGET_MS] caps how long
     * one slice may run so a slow device cannot overrun the platform's job limit mid-slice. That is
     * enforced inside `backfillSlice`, because only the loop that is doing the work knows how long
     * it has been going.
     */
    private fun sliceSizeForMemory(pressured: Boolean): Int =
        if (pressured) CONSTRAINED_CHAPTERS_PER_SLICE else CHAPTERS_PER_SLICE

    /**
     * Drops [graph]'s vectors for the active model when they were built by a different chunking
     * recipe. Returns a human-readable reason when it deleted something, or null when the index is
     * already current.
     *
     * Extracted from [doWork] so the decision is testable without a Worker or a device: what is
     * being checked is a comparison of two strings, and getting it wrong is expensive in both
     * directions — too eager and every launch re-indexes the library, too lax and search silently
     * serves two generations of chunks.
     *
     * The recipe compared against is the one the indexer will actually use. Reading it from
     * [EmbeddingIndexer]'s own window rather than recomputing it here is the point: a re-derived
     * value could describe a window the indexer is not using, and the check would pass while the
     * index was rebuilt at a different size.
     */
    private suspend fun reconcileChunkRecipe(graph: AppGraph): String? =
        graph.embeddingIndexer.reconcileRecipe()

    /**
     * Hands the backfill back to the scheduler for another run, *without* burning a backoff cycle.
     *
     * ### Why this is not `Result.retry()`
     *
     * `retry()` reads as "come back later", which is exactly what a resumable backfill wants — and
     * that is why it was used here for every ordinary reason a run ends early: the run budget was
     * reached, the worker was stopped, a slice made no progress. But WorkManager does not model
     * "there is more to do". It models **failure**, and every `retry()` increments the work row's
     * `runAttemptCount`, which the backoff policy then multiplies:
     *
     * ```
     * delay = runAttemptCount * initialDelay      // LINEAR, initialDelay = 1 minute
     * ```
     *
     * For a backfill there is nothing exceptional about needing many runs — it is the *designed*
     * behaviour — so the counter climbs on the happy path and the delay grows without bound. That
     * is not a theory: measured on the test device, a scheduled backfill carried
     * `Minimum latency: +5h31m54s903ms`, which at a 1-minute base means **≈331 attempts**. The
     * progress readout sat frozen at 166 chapters because the next run was five and a half hours
     * away, and each run it did get burned another attempt.
     *
     * `ExistingWorkPolicy.KEEP` made it permanent: `schedule()` refuses to create a fresh row while
     * the old one is `ENQUEUED`, so the inflated counter was carried across every launch and could
     * never reset. The backfill was, asymptotically, dead.
     *
     * ### What this does instead
     *
     * Enqueues a brand-new request. A fresh row starts at `runAttemptCount = 0`, so the next run
     * happens immediately rather than in hours. `ExistingWorkPolicy.APPEND_OR_REPLACE` is used
     * rather than `REPLACE` so that the continuation is chained after the current run finishes
     * rather than cancelling it mid-flight — cancelling here would kill the very slice that just
     * made progress.
     *
     * The work is still resumable and idempotent (see the class doc), so a continuation is safe by
     * construction: the next run's slice query only selects chapters with no vectors for this model.
     *
     * @param chapters chapters indexed so far, published as progress on the completed run so a
     *   reader watching the readout sees the total rather than a reset to zero.
     */
    private fun continueLater(chapters: Int, chunks: Int): Result {
        EmbeddingBackfillScheduler.continueBackfill(applicationContext)
        return Result.success(
            workDataOf(
                PROGRESS_CHAPTERS to chapters,
                PROGRESS_CHUNKS to chunks,
            )
        )
    }

    companion object {
        private const val TAG = "EmbeddingBackfill"

        /**
         * Whether a backfill run is currently executing in this process.
         *
         * Process-wide and deliberately not persisted: this is not a lock on the *work*, it is a
         * statement about this process's memory and cores, both of which reset when it dies. See
         * [doWork] for the two-concurrent-runs measurement that made it necessary.
         */
        private val ACTIVE = AtomicBoolean(false)

        /**
         * Chapters fetched per slice.
         *
         * A *batching* figure, not a memory budget — see [sliceSizeForMemory]. One slice embeds this
         * many chapters' chunks in a single ONNX session, so it is the unit that amortises session
         * construction (142 MB and a graph load, measured on device). Larger is more efficient;
         * smaller re-checks the reader and the device more often.
         *
         * It was a flat 40 when a chunk window was 250 words and a chapter yielded ~62 chunks. The
         * window is now derived from the model ([EmbeddingModel.maxChunkWords]: 173 words for
         * MiniLM, 350 for Arctic-S) so a typical chapter is ~44 chunks. 40 chapters is therefore
         * ~1 760 chunks per session — comfortably more than the session cost needs to amortise over,
         * and [SLICE_BUDGET_MS] bounds the wall-clock regardless of how slow the device is.
         */
        const val CHAPTERS_PER_SLICE = 40

        /**
         * Slice length when [awaitHeadroom] had to wait, i.e. the device is genuinely short.
         *
         * The batch is quartered in the same case, so this is the second half of one response: hold
         * less at once *and* come back sooner to re-check. Neither alone is enough — the batch
         * controls peak footprint, and the slice controls how long we stay in a state the device has
         * told us it does not like.
         */
        private const val CONSTRAINED_CHAPTERS_PER_SLICE = 8

        /**
         * Longest one slice may run, checked inside `backfillSlice`.
         *
         * The platform stops a job at ~10 minutes and [MAX_RUN_WALL_CLOCK_MS] hands over at 8, so a
         * slice has to finish well inside that. Before this existed the slice length was the only
         * bound, which meant slice duration was a function of device speed: on a slow phone a
         * 40-chapter slice could overrun the job limit and be killed *mid-slice*, losing the tail.
         * A time budget makes the invariant explicit and device-independent.
         *
         * 90 s gives ~5 slices per run, so the reader and the memory gate are consulted often enough
         * that neither can be ignored for long.
         */
        private const val SLICE_BUDGET_MS = 90_000L

        // A per-chunk byte estimate used to live here (`BYTES_PER_INFLIGHT_CHUNK = 1_800_000`), and
        // deleting it is the point rather than a tidy-up. It counted the `[batch, seq, hidden]`
        // output buffer, so it under-counted the real peak by ~70x for Arctic-S — whose dominant
        // allocation is the quadratic attention term (`batch x seq^2`), not anything linear in the
        // row count. Two on-device measurements now calibrate that term, and they live where the
        // batch is actually chosen: [EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES] and
        // [EmbeddingIndexer.BYTES_PER_ROW_PER_TOKEN_SQUARED]. Nothing in this class should
        // re-derive peak footprint — it is bounded by the indexer's budget by construction, and a
        // second opinion here is exactly how the two drifted apart before.

        /**
         * Rows resident at once, at the indexer's *ceiling*.
         *
         * Read off [EmbeddingIndexer.MAX_BATCH] rather than re-stated so the two cannot drift.
         * This is the ceiling, not the batch any given model uses: the real batch is
         * [EmbeddingIndexer.batch], derived from the model's token ceiling, and *that* is what
         * bounds peak native memory. See [EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES].
         */
        private val MAX_BATCH_CHUNKS = EmbeddingIndexer.MAX_BATCH

        /**
         * The reduced batch used when the device is short of memory — a quarter of the model's own.
         *
         * This is the response to a *chronic* lack of headroom (the gate timed out rather than
         * clearing). Peak native footprint is `batch x seq^2`, so quartering the batch quarters the
         * dominant allocation; the cost is four times the forward passes for the same text, which is
         * the right trade when the alternative is being killed for holding memory the device needs.
         *
         * **A fraction, never a constant.** The normal batch is now derived per model — 2 for
         * Arctic-S, 8 for MiniLM — so a fixed number here would be *larger* than normal on one model
         * and a no-op on the other. That is the same defect shape as the byte estimate above: a
         * plausible constant that silently stops meaning what it says once something it depends on
         * moves.
         */
        fun constrainedBatchFor(normalBatch: Int): Int = (normalBatch / 4).coerceAtLeast(1)

        /** Safety valve so a pathological library cannot pin a worker forever. */
        private const val MAX_ROUNDS = 2_000

        /**
         * `runAttemptCount` at which a slice that keeps throwing is treated as a permanent fault.
         *
         * Only the exception path reaches this; ordinary "more work to do" ends a run through
         * [continueLater] and does not accumulate attempts. Chosen to survive a transient fault
         * (a locked database, a transiently unreadable file) while bounding the LINEAR delay at
         * [MAX_ERROR_ATTEMPTS] minutes rather than letting it reach the five-plus hours that a
         * runaway counter produced before.
         */
        private const val MAX_ERROR_ATTEMPTS = 5

        /**
         * Wall-clock budget for one run that holds **no** foreground service.
         *
         * The platform stops an ordinary job after ~10 minutes, and a worker killed mid-slice
         * loses that slice's work, so this stops itself first and hands over deliberately.
         */
        private const val RUN_BUDGET_MS = 8 * 60 * 1000L

        /**
         * Wall-clock budget for one run that **does** hold a foreground service.
         *
         * Much longer, for a reason that is specific to this device rather than to the platform's
         * job limit. Two things change once `setForeground` has succeeded:
         *
         *  1. WorkManager backs the work with a `dataSync` foreground service, which is not subject
         *     to the ~10 minute `JobScheduler` limit that [RUN_BUDGET_MS] exists to respect.
         *  2. ColorOS stops freezing the process. Its freezer keys on whether the app is in the
         *     background, and an app with a running FGS is not:
         *
         *     ```
         *     00:57:29  OplusHansManager: unfreeze uid: 10452 com.folio.reader
         *     00:57:34  OplusHansManager: freeze   uid: 10452 com.folio.reader  pids: [29524]
         *     ```
         *
         *     Those two lines are five seconds apart, and between them the process got exactly one
         *     session's worth of work done. An FGS is the only thing that keeps the second line from
         *     happening — which makes it the difference between an overnight index and one that
         *     only advances while someone is looking at the screen.
         *
         * So a run that has an FGS keeps going rather than handing over every eight minutes. Each
         * handover is a fresh worker start, and a fresh start is a fresh `setForeground` — which
         * will be **refused** if the app is by then off screen. Handing over is therefore not free
         * here: it is the moment the run loses the protection it just acquired. Staying in one long
         * run holds it.
         *
         * The ceiling is still well under any platform limit, and [SLICE_BUDGET_MS] bounds the
         * worst-case loss if the system stops us anyway — the loop checks [isStopped] every slice,
         * so a stop costs at most one slice rather than the run.
         */
        private const val RUN_BUDGET_FGS_MS = 60 * 60 * 1000L

        /** Which budget applies. Split out so the choice is testable without a Worker. */
        fun runBudgetFor(hasForegroundService: Boolean): Long =
            if (hasForegroundService) RUN_BUDGET_FGS_MS else RUN_BUDGET_MS

        private fun runBudgetMs(hasForegroundService: Boolean): Long =
            runBudgetFor(hasForegroundService)

        /** How often the memory-headroom check repeats while yielding under pressure. */
        private const val HEADROOM_POLL_MS = 5_000L

        /**
         * Longest single stretch the backfill will wait for memory to free up before proceeding
         * anyway. Five minutes is long enough to outlast a burst of other-app use and short enough
         * that a chronically tight device still finishes its index within a session or two.
         */
        private const val MAX_HEADROOM_WAIT_MS = 5 * 60 * 1000L

        const val PROGRESS_CHAPTERS = "chapters"
        const val PROGRESS_CHUNKS = "chunks"

        /**
         * Notification channel for the index pass. Distinct from the downloads channel so the
         * reader can mute one without muting the other.
         */
        private const val CHANNEL_ID = "semantic_index"

        /**
         * Notification id. Deliberately not the downloads id (4712) — two ongoing notifications
         * sharing an id would overwrite each other, and both can be live at once.
         */
        private const val NOTIFICATION_ID = 4713

        /**
         * The slice-length rule, exposed so a test can pin it without a device.
         *
         * It is a two-value choice now — see [sliceSizeForMemory] for why the memory-derived
         * arithmetic that used to live here was retired rather than adjusted. Kept as a seam because
         * the *relationship* it encodes is worth pinning: a pressured slice must be strictly shorter
         * than a normal one, or the response to pressure would be a no-op.
         */
        fun sliceSizeForTest(pressured: Boolean): Int =
            if (pressured) CONSTRAINED_CHAPTERS_PER_SLICE else CHAPTERS_PER_SLICE

        /**
         * Native bytes resident during one forward pass.
         *
         * This is a *budget*, not a product. The indexer derives its own batch so that one pass
         * fits inside [EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES], which means peak footprint is
         * bounded by construction and no longer scales with a batch size this class has to model.
         *
         * The arithmetic that used to be here — `batch x BYTES_PER_INFLIGHT_CHUNK` — was ~70x low
         * for Arctic-S, because it counted the `[batch, seq, hidden]` output buffer and ignored the
         * quadratic attention term that actually dominates. A 1.5 GB peak passing a 55 MB budget is
         * precisely how that error presented: the gate could not see the memory it was gating.
         */
        fun batchPeakBytes(): Long = EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES

        /**
         * The indexer's batch *ceiling*, exposed so a test can assert the two cannot drift. The
         * batch actually used is per model — see [EmbeddingIndexer.batchFor].
         */
        fun maxBatchChunksForTest(): Int = MAX_BATCH_CHUNKS

        /** One batch's peak cost, in bytes — the term the headroom floor is compared against. */
        fun batchPeakBytesForTest(): Long = batchPeakBytes()

        /**
         * The reduced batch for a model whose batch is [normalBatch], exposed so a test can pin the
         * fraction and that it never reaches zero. See [constrainedBatchFor].
         */
        fun constrainedBatchForTest(normalBatch: Int): Int = constrainedBatchFor(normalBatch)

        /**
         * The most attempts the current worker can accumulate before it gives up.
         *
         * `EmbeddingBackfillScheduler.RUNAWAY_ATTEMPT_THRESHOLD` must sit just above this, so the
         * two are pinned together by a test rather than by a comment.
         */
        fun maxErrorAttemptsForTest(): Int = MAX_ERROR_ATTEMPTS

        /**
         * Device memory headroom, in bytes, below which the backfill refuses to start a batch.
         *
         * ### Why a gate and not a smaller slice
         *
         * Peak footprint is set by how many chunks are resident *at once* inside a batch, and that
         * is a property of the indexer, not of the slice — shrinking the slice reduces how long a
         * run works, not how much memory it holds. So the only way to stop the backfill from
         * evicting other apps is to stop it from working when the device has no room, which is what
         * this does.
         *
         * ### The evidence
         *
         * On 2026-09-18 the backfill was running while the reader used other apps, and the reader
         * reported: *"yt music chrashed"* — the lowmemorykiller took YouTube Music, then restarted
         * this app mid-run ("app tabbed out ... came back and it reset"). Measured native heap was
         * 819 MB against a 19 MB Java heap, with system CPU dominated by reclaim rather than
         * compute (`194%sys` vs `109%user`).
         *
         * ### A flat floor was inert — this is a proportion
         *
         * The first version of this gate was a flat `256 MB` compared against `MemoryInfo.availMem`.
         * On the 7.65 GB test device that is ~3% of RAM, and the phone sits at 2.4 GB *available*
         * while idle with Spotify and Chrome resident — so the comparison passed on every
         * iteration and the gate never once engaged. That is the same failure mode as the 64 KB
         * `BYTES_PER_INFLIGHT_CHUNK`: a plausible-looking constant that is never actually reached,
         * leaving the protection decorative. Three separate reports of the same crash arrived after
         * it was added, which is what a gate that cannot fire looks like from the outside.
         *
         * So the floor is `max(MIN_HEADROOM_BYTES, HEADROOM_FRACTION * totalMem)`: enough for one
         * batch on a small device, and a meaningful slice of RAM on a large one, which is what
         * "the device has room" actually means at both ends. `availMem` is used rather than
         * `totalMem` for the *test* because it is the headroom the kernel can hand out without
         * reclaiming — exactly the quantity that decides whether allocating another batch forces
         * an eviction elsewhere.
         */
        const val MIN_HEADROOM_BYTES = 512L * 1024L * 1024L

        /**
         * Headroom the backfill insists on, as a fraction of total RAM.
         *
         * ### This is a starvation guard, not a pressure gauge
         *
         * It must be set against the backfill's *actual* footprint, which is now known rather than
         * estimated: one forward pass is bounded by [EmbeddingIndexer.NATIVE_BATCH_BUDGET_BYTES]
         * (320 MB) and a live session was measured at 142 MB on device, so the worker holds roughly
         * 300–500 MB at peak. [MIN_HEADROOM_BYTES] is sized for exactly that, which is why it is
         * 512 MB rather than the 256 MB it was when the batch was thought to be a handful of rows.
         *
         * ### Why not a large fraction
         *
         * The first version of this was a flat 256 MB, which never fired. The correction was to a
         * proportion, but 0.35 was an overcorrection in the other direction: on the 7.30 GB test
         * device the floor became 2.55 GB while the device idles at **2.1–2.4 GB available**, so
         * the gate fired on *every* slice, waited the full [MAX_HEADROOM_WAIT_MS] five minutes, and
         * proceeded at a quarter batch. Observed on device:
         *
         * ```
         * I EmbeddingBackfill: low memory: 2090MB free of 2616MB needed; yielding
         * ```
         *
         * That is "extremely slow indexing" reproduced by the fix for "indexing crashes other
         * apps" — a gate that fires on a merely-busy device is as wrong as one that never fires,
         * and both are the same mistake: a threshold not checked against what a real device
         * reports. 0.125 (one eighth) is ~913 MB here, which is above the point where Android's
         * LMKD becomes aggressive on a device this size and well clear of the ~2.1 GB a busy
         * device offers, so indexing runs at full speed while a genuinely starved phone is still
         * held back. The device was measured at 306 MB available at its worst, where this fires.
         */
        private const val HEADROOM_FRACTION = 0.125

        /**
         * The headroom floor for a device with [totalBytes] of RAM.
         *
         * Pure so [EmbeddingBackfillSliceSizeTest] can pin both ends: a small device must be held
         * to the absolute floor, and a large one must be held to a proportion rather than a value
         * that a healthy large device always clears.
         */
        fun headroomFloorFor(totalBytes: Long): Long =
            maxOf(MIN_HEADROOM_BYTES, (totalBytes * HEADROOM_FRACTION).toLong())
    }
}
