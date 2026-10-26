package com.folio.reader.surfaces

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.folio.reader.FolioApplication
import com.folio.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

open class FolioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { WidgetRefreshWorker.enqueue(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { WidgetRefreshWorker.enqueue(context) }
    override fun onEnabled(context: Context) { WidgetRefreshWorker.enqueue(context) }
}
class ContinueReadingWidget : FolioWidgetProvider()
class ReadingGoalWidget : FolioWidgetProvider()

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val reading = manager.getAppWidgetIds(ComponentName(applicationContext, ContinueReadingWidget::class.java))
        val goals = manager.getAppWidgetIds(ComponentName(applicationContext, ReadingGoalWidget::class.java))
        if (reading.isEmpty() && goals.isEmpty()) Result.success() else {
            val snapshot = (applicationContext as FolioApplication).graph.readingSurfaceSnapshot(includeStats = goals.isNotEmpty())
            reading.forEach { update(manager, it, snapshot, false) }
            goals.forEach { update(manager, it, snapshot, true) }
            Result.success()
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { android.util.Log.w("FolioWidgets", "Widget refresh failed", e); Result.retry() }

    private fun update(manager: AppWidgetManager, id: Int, snapshot: ReadingSurfaceSnapshot, goal: Boolean) {
        manager.updateAppWidget(id, views(applicationContext, snapshot, goal,
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280),
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 184)))
    }

    companion object {
    fun views(context: Context, snapshot: ReadingSurfaceSnapshot, goal: Boolean, width: Int = 280, height: Int = 184): RemoteViews {
        val views = RemoteViews(context.packageName, if (goal) R.layout.widget_reading_goal else R.layout.widget_continue_reading)
        views.setImageViewBitmap(R.id.widget_glass, WidgetArtwork.glass(snapshot.accent, snapshot.surface))
        views.setInt(R.id.widget_glass, "setBackgroundResource", 0)
        views.setTextColor(R.id.widget_eyebrow, snapshot.accent)
        views.setTextColor(R.id.widget_title, snapshot.ink)
        views.setTextColor(R.id.widget_subtitle, (snapshot.ink and 0x00FFFFFF) or (190 shl 24))
        views.setTextColor(R.id.widget_action, snapshot.accent)
        views.setTextViewText(R.id.widget_eyebrow, if (goal) {
            if (width < 240) "FOLIO  ·  TODAY" else "FOLIO  ·  DAILY RHYTHM"
        } else if (width < 240) "FOLIO  ·  READ" else "FOLIO  ·  CONTINUE READING")
        if (goal) {
            views.setTextViewText(R.id.widget_title, snapshot.minutes.toString())
            views.setTextViewText(R.id.widget_subtitle, if (snapshot.goal > 0) "of ${snapshot.goal} minutes today" else "minutes read today")
            views.setTextViewText(R.id.widget_action, when {
                snapshot.goal > 0 && snapshot.minutes >= snapshot.goal -> "Daily goal complete  ✓"
                snapshot.goal > 0 && snapshot.minutes > 0 -> "${goalPercent(snapshot.minutes, snapshot.goal)}% of your goal  ↗"
                else -> "Make a little room for reading  ↗"
            })
            views.setImageViewBitmap(R.id.widget_week, WidgetArtwork.week(snapshot.week, snapshot.goal, snapshot.accent, snapshot.ink,
                width - 44, height - 125))
            views.setContentDescription(R.id.widget_week, "Last seven days: ${snapshot.week.joinToString()} minutes")
            views.setOnClickPendingIntent(R.id.widget_root, ReadingSurfaceIntents.pending(context, "stats"))
        } else {
            val entry = snapshot.entry
            views.setTextViewText(R.id.widget_title, entry?.title ?: "Your next page awaits")
            views.setTextViewText(R.id.widget_subtitle, entry?.let { "${it.progress}%  ·  ${it.subtitle}" } ?: "Find a place to begin.")
            views.setTextViewText(R.id.widget_action, if (entry == null) "Open your library  ↗" else "Resume reading  ↗")
            views.setInt(R.id.widget_title, "setMaxLines", if (height < 176) 1 else 2)
            views.setProgressBar(R.id.widget_progress, 100, entry?.progress ?: 0, false)
            val cover = WidgetArtwork.cover(entry?.cover)
            views.setViewVisibility(R.id.widget_cover, if (cover != null && width >= 240 && height >= 176) View.VISIBLE else View.GONE)
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover)
            views.setOnClickPendingIntent(R.id.widget_root, ReadingSurfaceIntents.pending(context, entry?.kind ?: "library", entry?.id.orEmpty()))
        }
        // Full updates release previous bitmap allocations on Android 17 widget hosts.
        return views
    }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("folio_widget_refresh", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
        }
        fun installed(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return listOf(ContinueReadingWidget::class.java, ReadingGoalWidget::class.java).any {
                manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
            }
        }
    }
}

object ReadingSurfaceRuntime {
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start(context: Context, graph: com.folio.reader.AppGraph) {
        ReadingReminderWorker.sync(context)
        if (WidgetRefreshWorker.installed(context)) WidgetRefreshWorker.enqueue(context)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).let { scope ->
            merge(graph.database.progressRevision, graph.database.bookDataRevision, graph.database.mangaDataRevision,
                graph.database.documentDataRevision, graph.database.sessionDataRevision)
                .sample(30_000).onEach {
                    if (WidgetRefreshWorker.installed(context)) WidgetRefreshWorker.enqueue(context)
                }.launchIn(scope)
            graph.database.sessionDataRevision.drop(1).onEach { ReadingNotifications.cancelReminder(context) }.launchIn(scope)
        }
    }
}
