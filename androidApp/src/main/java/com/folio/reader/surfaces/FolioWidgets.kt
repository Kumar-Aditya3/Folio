package com.folio.reader.surfaces

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.folio.reader.FolioApplication
import com.folio.reader.R
import com.folio.reader.narration.NarrationPhase
import com.folio.reader.narration.NarrationPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

enum class FolioWidgetKind { Reading, Goal, Audiobook }

open class FolioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { WidgetRefreshWorker.enqueue(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { WidgetRefreshWorker.enqueue(context) }
    override fun onEnabled(context: Context) { WidgetRefreshWorker.enqueue(context) }
}
class ContinueReadingWidget : FolioWidgetProvider()
class ReadingGoalWidget : FolioWidgetProvider()
class AudiobookWidget : FolioWidgetProvider()

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val groups = providers.map { (kind, provider) ->
            kind to manager.getAppWidgetIds(ComponentName(applicationContext, provider))
        }
        if (groups.all { it.second.isEmpty() }) Result.success() else {
            val snapshot = (applicationContext as FolioApplication).graph.readingSurfaceSnapshot(
                includeStats = groups.any { it.first == FolioWidgetKind.Goal && it.second.isNotEmpty() },
                includeAudio = groups.any { it.first == FolioWidgetKind.Audiobook && it.second.isNotEmpty() })
            groups.forEach { (kind, ids) -> ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                manager.updateAppWidget(id, views(applicationContext, snapshot, kind,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultHeight(kind))))
            } }
            Result.success()
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { android.util.Log.w("FolioWidgets", "Widget refresh failed", e); Result.retry() }

    companion object {
        fun defaultHeight(kind: FolioWidgetKind): Int = if (kind == FolioWidgetKind.Audiobook) 132 else 184

        private val providers = listOf(FolioWidgetKind.Reading to ContinueReadingWidget::class.java,
            FolioWidgetKind.Goal to ReadingGoalWidget::class.java, FolioWidgetKind.Audiobook to AudiobookWidget::class.java)

        // Kept for existing preview callers; all three launcher widgets use the same renderer.
        fun views(context: Context, snapshot: ReadingSurfaceSnapshot, goal: Boolean, width: Int = 280, height: Int = 184): RemoteViews =
            views(context, snapshot, if (goal) FolioWidgetKind.Goal else FolioWidgetKind.Reading, width, height)

        fun views(context: Context, snapshot: ReadingSurfaceSnapshot, kind: FolioWidgetKind, width: Int = 280, height: Int = defaultHeight(kind)): RemoteViews {
            val layout = when(kind) {
                FolioWidgetKind.Reading -> R.layout.widget_continue_reading
                FolioWidgetKind.Goal -> R.layout.widget_reading_goal
                FolioWidgetKind.Audiobook -> R.layout.widget_audiobook
            }
            val views = RemoteViews(context.packageName, layout)
            views.setImageViewBitmap(R.id.widget_glass, WidgetArtwork.glass(snapshot.accent, snapshot.surface, width - 8, height - 8,
                snapshot.glassOpacity, snapshot.sheen, snapshot.glassEffects))
            views.setInt(R.id.widget_glass, "setBackgroundResource", 0)
            views.setTextColor(R.id.widget_eyebrow, snapshot.accent)
            views.setTextColor(R.id.widget_title, snapshot.ink)
            views.setTextColor(R.id.widget_subtitle, (snapshot.ink and 0x00FFFFFF) or (200 shl 24))
            val compact = width < 240 || height < 176
            val largeText = context.resources.configuration.fontScale > 1.15f
            views.setTextViewText(R.id.widget_eyebrow, when(kind) {
                FolioWidgetKind.Reading -> "FOLIO"
                FolioWidgetKind.Goal -> "DAILY RHYTHM"
                FolioWidgetKind.Audiobook -> "AUDIOBOOK"
            })
            when(kind) {
                FolioWidgetKind.Goal -> {
                    views.setTextViewText(R.id.widget_title, snapshot.minutes.toString())
                    views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact && largeText) 26f else if (compact || largeText) 32f else 40f)
                    views.setTextViewText(R.id.widget_subtitle, if (snapshot.goal > 0) "of ${snapshot.goal} min today" else "minutes today")
                    views.setTextColor(R.id.widget_action, snapshot.accent)
                    views.setTextViewText(R.id.widget_action, when {
                        snapshot.goal > 0 && snapshot.minutes >= snapshot.goal -> "Daily goal complete  ✓"
                        snapshot.goal > 0 -> "${(snapshot.goal - snapshot.minutes).coerceAtLeast(0)} min to your goal  ↗"
                        else -> "Your week in reading  ↗"
                    })
                    views.setImageViewBitmap(R.id.widget_week, WidgetArtwork.week(snapshot.week, snapshot.goal, snapshot.accent, snapshot.ink,
                        width - 40, height - if (largeText) 140 else 120))
                    views.setContentDescription(R.id.widget_week, "Last seven days: ${snapshot.week.joinToString()} minutes")
                    views.setOnClickPendingIntent(R.id.widget_root, ReadingSurfaceIntents.pending(context, "stats"))
                }
                FolioWidgetKind.Reading -> {
                    val entry = snapshot.entry
                    views.setTextViewText(R.id.widget_title, entry?.title ?: "Your next page awaits")
                    views.setTextViewText(R.id.widget_subtitle, entry?.subtitle ?: "A little time, a good story.")
                    views.setTextViewText(R.id.widget_format, when (entry?.kind) {
                        "manga" -> "MANGA"
                        "document" -> "DOCUMENT"
                        "book" -> "ON YOUR NIGHTSTAND"
                        else -> "YOUR READING ROOM"
                    })
                    views.setTextColor(R.id.widget_format, (snapshot.ink and 0x00FFFFFF) or (150 shl 24))
                    views.setViewVisibility(R.id.widget_format, if (width < 280 || largeText) View.GONE else View.VISIBLE)
                    views.setTextColor(R.id.widget_action, snapshot.accent)
                    views.setTextViewText(R.id.widget_action, if (entry == null) "Open library" else if (width < 180) "Read on" else "Continue reading")
                    views.setTextViewTextSize(R.id.widget_action, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
                    views.setImageViewBitmap(R.id.widget_read_arrow, WidgetArtwork.readingArrow(snapshot.accent))
                    views.setTextViewText(R.id.widget_progress_label, "${entry?.progress ?: 0}%")
                    views.setTextColor(R.id.widget_progress_label, (snapshot.ink and 0x00FFFFFF) or (185 shl 24))
                    views.setViewVisibility(R.id.widget_progress_label, if (entry == null || width < 220 || (compact && largeText)) View.GONE else View.VISIBLE)
                    views.setInt(R.id.widget_title, "setMaxLines", if (height < 176 || (largeText && (compact || height < 200))) 1 else 2)
                    views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP,
                        if (compact) 16f else if (largeText && height < 200) 19f else 22f)
                    views.setImageViewBitmap(R.id.widget_progress, WidgetArtwork.progress(entry?.progress ?: 0, snapshot.accent, snapshot.ink, width - 40))
                    views.setViewVisibility(R.id.widget_progress, if (entry != null && !(height < 176 && largeText)) View.VISIBLE else View.GONE)
                    val showCover = entry != null && width >= 180 && height >= 156
                    views.setViewVisibility(R.id.widget_cover, if (showCover && !compact) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.widget_cover_compact, if (showCover && compact) View.VISIBLE else View.GONE)
                    if (showCover) views.setImageViewBitmap(if (compact) R.id.widget_cover_compact else R.id.widget_cover,
                        WidgetArtwork.readingJacket(entry?.cover, snapshot.accent, snapshot.surface))
                    views.setContentDescription(R.id.widget_root, entry?.let {
                        "Continue reading ${it.title}, ${it.progress}% complete, ${it.subtitle}"
                    } ?: "Open your Folio library")
                    views.setOnClickPendingIntent(R.id.widget_root, ReadingSurfaceIntents.pending(context, entry?.kind ?: "library", entry?.id.orEmpty()))
                }
                FolioWidgetKind.Audiobook -> {
                    val entry = snapshot.listening
                    val ready = entry?.ready == true
                    val short = height < 176
                    val small = width < 280 || short
                    val showDisc = height >= 124 && !(width < 220 && largeText)
                    views.setViewVisibility(R.id.widget_eyebrow, if (height < 156) View.GONE else View.VISIBLE)
                    views.setViewVisibility(R.id.widget_disc, if (!small && showDisc) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.widget_disc_compact, if (small && showDisc) View.VISIBLE else View.GONE)
                    if (showDisc) views.setImageViewBitmap(if(small) R.id.widget_disc_compact else R.id.widget_disc,
                        WidgetArtwork.listeningDisc(entry?.cover, entry?.progress ?: 0, snapshot.accent, snapshot.ink, snapshot.surface))
                    views.setTextViewText(R.id.widget_title, entry?.title ?: "A story, out loud")
                    views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, if (height < 124) 15f else if (small || height < 200) 17f else 21f)
                    views.setInt(R.id.widget_title, "setMaxLines", if (height < 200) 1 else 2)
                    views.setTextViewText(R.id.widget_audio_chapter, entry?.chapter?.takeIf { it.isNotBlank() }
                        ?: entry?.author?.takeIf { it.isNotBlank() } ?: "Your listening shelf")
                    views.setViewVisibility(R.id.widget_audio_chapter, if (short || (largeText && height < 240)) View.GONE else View.VISIBLE)
                    views.setTextColor(R.id.widget_audio_chapter, (snapshot.ink and 0x00FFFFFF) or (200 shl 24))
                    val speed = entry?.speed?.let { if (it % 1f == 0f) "${it.toInt()}×" else "${it}×" }.orEmpty()
                    views.setTextViewText(R.id.widget_subtitle, when {
                        entry == null -> "Choose a book in Folio"
                        !ready -> "Set up narration to listen"
                        entry.phase == NarrationPhase.Ended -> "Finished · tap play to restart"
                        entry.phase == NarrationPhase.Preparing -> "Preparing narration"
                        entry.phase == NarrationPhase.Buffering -> "Buffering · $speed"
                        entry.phase == NarrationPhase.Seeking -> "Moving to passage"
                        entry.phase == NarrationPhase.Error -> "Open Folio to resume"
                        entry.phase == NarrationPhase.Interrupted -> "Interrupted · ${entry.progress}%"
                        entry.playing -> "Listening · $speed · ${entry.progress}%"
                        else -> "Paused · $speed · ${entry.progress}%"
                    })
                    val open = ReadingSurfaceIntents.pending(context, if (entry == null) "library" else if (ready) "book" else "narration", entry?.id.orEmpty())
                    views.setOnClickPendingIntent(R.id.widget_root, open)
                    val toggle = if (entry?.playing == true) "pause" else "play"
                    listOf(R.id.widget_audio_previous to "previous", R.id.widget_audio_toggle to toggle, R.id.widget_audio_next to "next").forEach { (id, action) ->
                        val primary = id == R.id.widget_audio_toggle
                        views.setImageViewBitmap(id, WidgetArtwork.control(action, snapshot.accent, snapshot.ink, ready || primary))
                        views.setBoolean(id, "setEnabled", ready || primary)
                        views.setOnClickPendingIntent(id, if (ready) audioPending(context, action, entry!!.id) else open)
                    }
                    views.setContentDescription(R.id.widget_audio_toggle, when {
                        entry == null -> "Choose a book"
                        !ready -> "Open narration setup"
                        entry.phase == NarrationPhase.Ended -> "Restart narration"
                        toggle == "pause" -> "Pause narration"
                        else -> "Resume narration"
                    })
                }
            }
            // Full updates replace old bitmap allocations on launcher hosts.
            return views
        }

        internal fun audioPending(context: Context, action: String, bookId: String): PendingIntent {
            val intent = Intent(context, NarrationPlaybackService::class.java).setAction("widget-$action")
                .setData(Uri.Builder().scheme("folio").authority("widget-audio").appendPath(bookId).appendPath(action).build())
                .putExtra("widgetBookId", bookId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (action != "pause" && Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 0, intent, flags)
                else PendingIntent.getService(context, 0, intent, flags)
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("folio_widget_refresh", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
        }
        fun installed(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return providers.any { manager.getAppWidgetIds(ComponentName(context, it.second)).isNotEmpty() }
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
            combine(graph.narration.state.map { listOf(it.bookId, it.phase, it.chapterTitle, it.speed) }.distinctUntilChanged(),
                graph.narration.pack.map { it.phase }.distinctUntilChanged(),
                graph.narration.configuration.map { it.narratorReady to it.changing }.distinctUntilChanged()) { _, _, _ -> Unit }
                .debounce(350).onEach { if (WidgetRefreshWorker.installed(context)) WidgetRefreshWorker.enqueue(context) }.launchIn(scope)
            graph.database.sessionDataRevision.drop(1).onEach { ReadingNotifications.cancelReminder(context) }.launchIn(scope)
            graph.database.settingsDataRevision.drop(1).debounce(350).onEach {
                if (WidgetRefreshWorker.installed(context)) WidgetRefreshWorker.enqueue(context)
            }.launchIn(scope)
        }
    }
}
