package com.folio.reader.work

import android.content.Context
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
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MangaUpdateWorker>().build(),
        )
    }
}
