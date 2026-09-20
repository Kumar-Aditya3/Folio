package com.folio.reader.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.TimeUnit

/**
 * Schedules the semantic-index backfill.
 *
 * Mirrors `MangaUpdateScheduler`: a unique work name so repeated calls coalesce, and
 * constraints applied at enqueue time.
 *
 * The charging requirement this used to carry has been removed — see [schedule] for why it
 * was both ineffective at protecting the reader and unreliable as a way to get the work done.
 * The backfill's real cost control is now the worker's **thread count** while the UI is on screen,
 * not a deferral: it used to wait for the app to be backgrounded, which on the shipping device is
 * precisely when the OEM freezer stops it executing at all.
 */
object EmbeddingBackfillScheduler {

    private const val WORK_NAME = "embedding_backfill"

    /**
     * Enqueues (or replaces) the backfill.
     *
     * @param unmeteredOnly when true, also requires an unmetered network. Only meaningful
     *   if the model still has to be downloaded; indexing itself is entirely local.
     */
    fun schedule(context: Context, unmeteredOnly: Boolean = false) {
        val appContext = context.applicationContext
        val workManager = workManager(appContext) ?: return

        // `setRequiresCharging(true)` used to be here, and removing it is the point of this
        // change. It read well — "minutes of sustained CPU must not run on battery" — but it was
        // wrong in both directions:
        //
        //  1. It did not protect the reader. Charging is satisfied exactly when someone is
        //     sitting there reading with the phone on a cable, which is the one situation where
        //     the indexer competing for CPU is visible as jank. On the test device the backfill
        //     ran flat out while the reader used the app and frames fell to ~9 fps.
        //  2. It did not reliably run either. Android reports `BATTERY_NOT_LOW` as *false* once
        //     the battery reaches 100% on many devices, so the very moment a night-long charge
        //     completed, the constraint that was supposed to enable the backfill silently
        //     disabled it — leaving the index permanently half-built with no error anywhere.
        //
        // The actual protection against battery cost is `setRequiresBatteryNotLow` below, plus the
        // worker's own thread cap while the UI is on screen. `setRequiresBatteryNotLow` stays: unlike
        // charging, it is true for a full battery too, and it still prevents a genuine drain-at-5% run.
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .apply { if (unmeteredOnly) setRequiredNetworkType(androidx.work.NetworkType.UNMETERED) }
            .build()

        val request = OneTimeWorkRequestBuilder<EmbeddingBackfillWorker>()
            .setConstraints(constraints)
            // Linear, not exponential, and measured in minutes rather than a quarter of an hour.
            // This delay now applies only to genuine faults: an ordinary run-end hands over via
            // `EmbeddingBackfillWorker.continueLater`, which enqueues a fresh row rather than
            // returning `retry()`. The distinction is the whole fix — `retry()` increments
            // `runAttemptCount` and the LINEAR delay multiplies by it, so using it for "there is
            // more to do" drove this value to `+5h31m54s903ms` (≈331 attempts) while the progress
            // readout sat frozen. Worse, an exponential 15-minute base had already been observed
            // pushing a working backfill to a ~19-hour `Minimum latency` after a handful of runs.
            // Linear 1 minute keeps a real fault's delay bounded and legible.
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                1,
                TimeUnit.MINUTES,
            )
            .addTag(WORK_NAME)
            .build()

        // Off the calling thread.
        //
        // `recoverFromRunawayBackoff` reads and cancels work rows, which are *blocking* future
        // gets against WorkManager's own database. `schedule()` is called from
        // `Application.onCreate`, so doing that inline stalls app startup on disk I/O and — worse —
        // races WorkManager's own initialisation, which is when it reconciles its persisted rows.
        // Reading before that finishes sees a partial view, which is why an already-poisoned row
        // survived the repair on the device (one Folio job still sat at
        // `Minimum latency: +5h6m56s756ms` after the fixed APK was installed).
        //
        // `WorkManager` is safe to drive from a background thread, and the enqueue itself does not
        // need to complete before `onCreate` returns.
        kotlin.concurrent.thread(name = "backfill-schedule") {
            val hadRunaway = recoverFromRunawayBackoff(workManager)
            // KEEP, not REPLACE: repeated calls to `schedule()` happen on every app start, and
            // REPLACE would cancel a run that is mid-slice each time the reader opens the app —
            // throwing away real work to enqueue an identical request.
            //
            // KEEP is only safe because ordinary run-ends no longer accumulate
            // `runAttemptCount`: `EmbeddingBackfillWorker.continueLater` enqueues a fresh row
            // instead of returning `retry()`, so the row this KEEPs is never the poisoned one.
            // Before that change, KEEP was load-bearing in a defect — it preserved a row whose
            // LINEAR delay had grown to `+5h31m54s903ms` (≈331 attempts) and could never be
            // reset by any later `schedule()`.
            //
            // `recoverFromRunawayBackoff` handles rows poisoned by that older build. Without
            // it, an install of this fix would still inherit the five-hour delay, because KEEP
            // would keep serving the very row the fix exists to escape — a fix that cannot take
            // effect on the device that needs it.
            runCatching {
                workManager.enqueueUniqueWork(
                    WORK_NAME,
                    if (hadRunaway) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { android.util.Log.w("EmbeddingBackfill", "could not schedule", it) }
        }
    }

    /**
     * Runs the backfill now, preempting any scheduled run.
     *
     * Used by the "index now" button. The backoff is deliberately short: this path exists so the
     * reader can push the index along, and being told to wait 15 minutes after a tap is the
     * same defect as a dead button.
     *
     * Carries no constraints at all — not even `BATTERY_NOT_LOW`, which [schedule] keeps. An
     * explicit tap is the reader overriding the automatic policy, and a button that silently
     * does nothing because the battery is at 14% is exactly the class of bug that made this
     * feature hard to trust. The worker still caps its own thread count while the UI is on screen,
     * so a "build index" tap under the reader's own nose does not stutter what they are looking at.
     */
    fun runNow(context: Context) {
        val workManager = workManager(context) ?: return
        val request = OneTimeWorkRequestBuilder<EmbeddingBackfillWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                15,
                TimeUnit.SECONDS,
            )
            .addTag(WORK_NAME)
            .build()
        // `WORK_NAME`, not a separate `_manual` name.
        //
        // A distinct unique name looks like isolation and is the opposite: WorkManager serialises
        // within a *chain*, so two names mean two chains that can run at the same time. On device
        // that is exactly what happened — `dumpsys jobscheduler` showed two live backfill rows
        // (#454, #455) and the instrumented log caught two ONNX sessions and two 4 GB forward
        // passes in one process at the same millisecond. Sharing the name is what makes the tap
        // preempt the automatic run instead of racing it, and `REPLACE` is the right policy here
        // because an explicit tap *is* the reader overriding the current plan.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Continuation for a run that ended early with work left, *without* burning a backoff cycle.
     *
     * `EmbeddingBackfillWorker.continueLater` calls this instead of returning `Result.retry()`. The
     * distinction matters more than it looks: `retry()` marks the row as failed and increments
     * `runAttemptCount`, so a backfill that legitimately needs hundreds of runs drives its own
     * LINEAR delay to hours. Measured on device: `Minimum latency: +5h31m54s903ms` — ≈331 attempts
     * at a 1-minute base — with the progress readout frozen while the work waited.
     *
     * A fresh request starts at `runAttemptCount = 0`, so the continuation runs promptly. `APPEND`
     * (not `REPLACE`) chains it after the current run; `REPLACE` would cancel the run that just made
     * the progress this is meant to continue from. `APPEND_OR_REPLACE` is required rather than
     * `APPEND` because `APPEND` throws if the prerequisite work is already `CANCELLED` or
     * `FAILED` — which happens whenever the reader taps "stop indexing".
     *
     * No constraints: [schedule]'s `BATTERY_NOT_LOW` already applied to the run that is handing
     * over, and re-checking it here would let a continuation be dropped at 14% battery even though
     * the work is mid-flight and resumable. The worker's own foreground check is the real cost gate.
     */
    fun continueBackfill(context: Context) {
        val workManager = workManager(context) ?: return
        val request = OneTimeWorkRequestBuilder<EmbeddingBackfillWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                1,
                TimeUnit.MINUTES,
            )
            .addTag(WORK_NAME)
            .build()
        runCatching {
            // `WORK_NAME`, not a separate `_cont` name.
            //
            // This is the second half of the same defect as [runNow]. `APPEND` chains *within one
            // unique name*: a continuation appended to `${WORK_NAME}_cont` is a different chain from
            // the scheduled `${WORK_NAME}` row, so nothing stopped both from executing. Sharing the
            // name is what makes `APPEND` mean "run this after the current run" rather than "run this
            // whenever". `EmbeddingBackfillWorker` also refuses to start a second sweep in the same
            // process as a belt-and-braces guard, but the structural fix belongs here — a duplicate
            // that never starts costs nothing, whereas one that runs costs a second 4 GB pass.
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }.onFailure {
            // A continuation that cannot be enqueued means the backfill stops until the next
            // scheduler tick. That is recoverable, but it must be visible: a silent failure here
            // is indistinguishable from "indexing is just slow", which is the exact ambiguity that
            // made this feature hard to diagnose.
            android.util.Log.w("EmbeddingBackfill", "could not enqueue continuation", it)
        }
    }

    /**
     * Clears a work row left poisoned by the older `retry()`-based backfill, and reports whether it
     * did. `true` means the caller must enqueue with `REPLACE` rather than `KEEP`.
     *
     * ### Why a repair path is needed at all
     *
     * Fixing the cause is not enough on the device that already suffered it. `ExistingWorkPolicy.KEEP`
     * will not replace an `ENQUEUED` row, so a phone carrying a 331-attempt row keeps its
     * five-and-a-half-hour delay **no matter how correct the new code is** — verified on this device:
     * after installing the fixed APK the job still reported `Delay=+5h18m34s211ms`. A fix that cannot
     * take effect where it is needed is not a fix.
     *
     * ### The threshold
     *
     * `runAttemptCount` above [RUNAWAY_ATTEMPT_THRESHOLD] is not reachable by the fixed worker: the
     * only path that increments it now is a slice that throws, capped at 5 attempts by
     * `MAX_ERROR_ATTEMPTS`. So a higher count is documentary evidence of the old behaviour rather
     * than of a current fault, and replacing the row discards the counter without discarding work —
     * the index itself lives in SQLite, and the backfill is resumable and idempotent.
     *
     * Cancellation is the mechanism: it is the only public way to make WorkManager drop a row's
     * state, and `REPLACE` at the call site then enqueues the same work under a clean row. Reading
     * state is non-blocking-tolerant — a failure to read means we cannot prove poisoning, and the
     * conservative answer is to leave the row alone rather than cancel work on a hunch.
     */
    private fun recoverFromRunawayBackoff(workManager: WorkManager): Boolean = runCatching {
        // Selected by **tag**, not by unique-work name.
        //
        // The first two versions of this repair both failed on the device for the same underlying
        // reason: they enumerated names and states, and the set of each is not knowable from the
        // current source. Version 1 looked at `WORK_NAME` only; version 2 looked at all three
        // `*_cont`/`*_manual` names and accepted `BLOCKED` as well as `ENQUEUED`. A row still
        // survived both — `JOB #u0a452/378` with `Backoff: policy=1 initial=+30s0ms`, i.e.
        // WorkManager's *default* (`DEFAULT_BACKOFF_DELAY_MILLIS = 30000`, EXPONENTIAL), which is
        // what a request gets when a build omits `setBackoffCriteria`. No current call site omits
        // it, so that row was written by an older build — and whatever unique name *that* build
        // used is not recoverable from today's code.
        //
        // The tag is the right selector because it is the one thing every enqueue path in every
        // build has set (`.addTag(WORK_NAME)` appears on all of them). Selecting by tag is
        // therefore exhaustive by construction: it cannot miss a row because a name changed.
        //
        // Cancellation is by **id**, not by unique work, so only the poisoned rows are dropped and
        // the healthy in-flight run is left alone. That distinction matters — `cancelAllWorkByTag`
        // would also kill the run that is currently making progress, and the backfill re-enqueues
        // on every app start, so that would throw away a slice each launch.
        val stuck = runCatching { workManager.getWorkInfosByTag(WORK_NAME).get() }
            .getOrDefault(emptyList())
            // Any state that has not finished. WorkManager reports queued work in an `APPEND`
            // chain as `BLOCKED`, not `ENQUEUED`, so filtering on `ENQUEUED` alone excludes exactly
            // the continuation rows this exists to clear.
            .filter {
                it.state != WorkInfo.State.SUCCEEDED &&
                    it.state != WorkInfo.State.FAILED &&
                    it.state != WorkInfo.State.CANCELLED
            }
            .filter { it.runAttemptCount > RUNAWAY_ATTEMPT_THRESHOLD }
        if (stuck.isEmpty()) return@runCatching false
        android.util.Log.w(
            "EmbeddingBackfill",
            "clearing ${stuck.size} backfill row(s) with runaway backoff " +
                "(attempts=${stuck.map { it.runAttemptCount }}, states=${stuck.map { it.state }}); " +
                "the index itself is in SQLite and is not affected",
        )
        stuck.forEach { runCatching { workManager.cancelWorkById(it.id) } }
        true
    }.getOrDefault(false)

    /**
     * `runAttemptCount` above which a row is presumed poisoned by an older backfill build.
     *
     * ### Why 6 and not 20
     *
     * This was 20, on the reasoning that "the fixed worker's only incrementing path is a slice that
     * throws, bounded at 5 attempts, so 20 sits well clear of legitimate behaviour". The reasoning
     * was sound but the number was never checked against a real row, and it **never matched one**:
     *
     * The stale row on the device carried `Minimum latency: +3h7m15s837ms` with
     * `Backoff: policy=1 initial=+30s0ms` — WorkManager's default (EXPONENTIAL, 30 s), which a
     * request gets when a build omits `setBackoffCriteria`. Exponential delay is
     * `base * 2^(attempts-1)`, so three hours from a 30-second base is
     *
     * ```
     * 30000ms * 2^(n-1) ≈ 11235000ms  ->  2^(n-1) ≈ 374  ->  n ≈ 10
     * ```
     *
     * **about ten attempts, not twenty.** The threshold was calibrated against the *linear*
     * 1-minute policy that the current code declares (where the original 5h31m delay really was
     * ~331 attempts), so it silently excluded every row written by an exponential build — which is
     * exactly the population it exists to clear. A selector that cannot match its target is the
     * same failure this repair was written to fix, one level up.
     *
     * 6 is the honest bound rather than a round number: `MAX_ERROR_ATTEMPTS = 5` is the most the
     * current worker can accumulate, and a row that reaches it returns `Result.failure()` — so it
     * leaves the unfinished set entirely rather than sitting at 6. Any *unfinished* row above 5
     * attempts is therefore, by construction, not something the current worker produced.
     *
     * This also means the threshold no longer depends on the backoff policy: it is stated in terms
     * of what the worker can produce, which is a property of our code, not of a row's history.
     */
    private const val RUNAWAY_ATTEMPT_THRESHOLD = 6

    /**
     * Test seam for [RUNAWAY_ATTEMPT_THRESHOLD].
     *
     * Exposed because the value encodes an arithmetic relationship with
     * `EmbeddingBackfillWorker.MAX_ERROR_ATTEMPTS` and with the delay curve of a *different*
     * backoff policy — three things that live in two files and drifted apart once already, which
     * is what let a 3-hour-delayed row survive the repair.
     */
    fun runawayAttemptThresholdForTest(): Int = RUNAWAY_ATTEMPT_THRESHOLD

    fun cancel(context: Context) {
        val workManager = workManager(context) ?: return
        workManager.cancelUniqueWork(WORK_NAME)
        // The two suffixed names are no longer enqueued by anything — every path shares [WORK_NAME]
        // now, so that WorkManager serialises them into one chain instead of letting them run
        // concurrently. Cancelling them here is cleanup for a device that still carries rows from
        // the build that used them; without it, "stop indexing" would leave those rows alive.
        workManager.cancelUniqueWork("${WORK_NAME}_manual")
        workManager.cancelUniqueWork("${WORK_NAME}_cont")
    }

    /**
     * Live state of the backfill, so the settings screen can tell "running" from "waiting for a
     * charger". Both enqueue paths carry [WORK_NAME] as a tag, so one tag query covers both.
     */
    fun observe(context: Context): Flow<List<WorkInfo>> {
        val workManager = workManager(context) ?: return flowOf(emptyList())
        return workManager.getWorkInfosByTagFlow(WORK_NAME)
    }

    /**
     * WorkManager, or null if it is unavailable.
     *
     * This used to be a silent `runCatching { ... }.getOrNull() ?: return` inlined at each
     * call site, which is how a button that does nothing leaves no evidence at all: if
     * `getInstance` throws — an initialisation failure is the usual cause — the tap is
     * discarded with no log line, no crash, and no change in the UI. Log it instead.
     */
    private fun workManager(context: Context): WorkManager? =
        runCatching { WorkManager.getInstance(context) }
            .onFailure { android.util.Log.w("EmbeddingBackfill", "WorkManager unavailable", it) }
            .getOrNull()
}
