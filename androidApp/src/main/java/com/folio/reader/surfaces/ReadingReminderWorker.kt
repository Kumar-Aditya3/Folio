package com.folio.reader.surfaces

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.folio.reader.FolioApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

class ReadingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = ReadingSurfacePreferences(applicationContext)
        if (!preferences.reminders) return Result.success()
        val zone = TimeZone.currentSystemDefault()
        val local = Clock.System.now().toLocalDateTime(zone)
        if (!reminderDue(local.hour * 60 + local.minute, preferences.minute, local.date.toString(), preferences.lastReminder, false)) return Result.success()
        return try {
            val graph = (applicationContext as FolioApplication).graph
            val sessions = graph.sessionRepository.getSessionsForDateRange(local.date.atStartOfDayIn(zone),
                (local.date + DatePeriod(days = 1)).atStartOfDayIn(zone))
            if (sessions.any { it.durationMs > 0 || it.isActive }) return Result.success()
            val snapshot = graph.readingSurfaceSnapshot(includeStats = false)
            // Re-read after the suspend queries: disabling a reminder takes effect immediately.
            val check = Clock.System.now().toLocalDateTime(zone)
            if (check.date != local.date) return Result.success()
            val latestSessions = graph.sessionRepository.getSessionsForDateRange(local.date.atStartOfDayIn(zone),
                (local.date + DatePeriod(days = 1)).atStartOfDayIn(zone))
            val due = reminderDue(check.hour * 60 + check.minute, preferences.minute, check.date.toString(),
                preferences.lastReminder, latestSessions.any { it.durationMs > 0 || it.isActive })
            if (preferences.reminders && due && ReadingNotifications.remind(applicationContext, snapshot)) {
                preferences.lastReminder = local.date.toString()
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("ReadingReminder", "Reminder could not be prepared", e); Result.retry() }
    }

    companion object {
        fun sync(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!ReadingSurfacePreferences(context).reminders) {
                manager.cancelUniqueWork("folio_reading_reminder")
                ReadingNotifications.cancelReminder(context)
                return
            }
            manager.enqueueUniquePeriodicWork("folio_reading_reminder", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReadingReminderWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
