package com.folio.reader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Enqueue/cancel wrapper around WorkManager, callable from a settings toggle. */
object MangaUpdateScheduler {

    private const val PERIODIC_WORK_NAME = "manga_update_check"
    private const val MANUAL_WORK_NAME = "manga_update_check_manual"

    /**
     * Applies the stored interval: 0 (off) cancels any scheduled run; a positive hour
     * value schedules the periodic check (the settings toggle offers 6/12/24).
     */
    fun sync(context: Context, intervalHours: Int) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (intervalHours <= 0) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<MangaUpdateWorker>(
            intervalHours.coerceAtLeast(1).toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        // UPDATE rewrites the schedule when the interval changes without dropping a run in flight.
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** One-off run for a manual "check now" action. */
    fun runNow(context: Context) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val request = OneTimeWorkRequestBuilder<MangaUpdateWorker>()
            // Explicit, and load-bearing.
            //
            // This request previously omitted `setBackoffCriteria` entirely, so it inherited
            // WorkManager's default: `BackoffPolicy.EXPONENTIAL` with
            // `DEFAULT_BACKOFF_DELAY_MILLIS = 30_000`. Exponential delay is
            // `base * 2^(attempts-1)`, so a worker that keeps returning `Result.retry()` — which a
            // network fetch does whenever the phone is offline — reaches **hours** of delay after
            // only about ten attempts. Measured on the device:
            //
            //   JOB #u0a452/393  Minimum latency: +2h54m25s775ms
            //                    Backoff: policy=1 initial=+30s0ms
            //
            // That row was misread for a while as a poisoned *embedding-backfill* row, because the
            // two share a job service and the tag is WorkManager's generic one. It was this: a
            // "check now" the reader tapped, parked for three hours with no way to reset it short
            // of reinstalling, because the delay only grows.
            //
            // LINEAR keeps the growth bounded and legible — the same reasoning the backfill
            // scheduler records for its own backoff. One minute is long enough not to hammer a
            // network that just failed.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
