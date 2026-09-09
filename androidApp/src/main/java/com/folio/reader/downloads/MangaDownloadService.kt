package com.folio.reader.downloads

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.folio.reader.FolioApplication
import com.folio.reader.R
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDownload
import com.folio.reader.manga.MangaDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the manga download queue has pending work.
 *
 * The download loop itself lives in [com.folio.reader.manga.MangaDownloadManager] on a
 * process-lifetime scope. Android freezes (Doze / app standby) or outright kills the
 * process the moment the user leaves the app, so an in-flight chapter dies: OkHttp's read
 * timeout fires while the process is frozen and the row is written off as ERROR
 * ("Failed · timed out"), or it is left stuck DOWNLOADING. This foreground service holds a
 * dataSync wake so the manager keeps running after the UI is gone, surfaces honest progress
 * in an ongoing notification, and retires itself the instant the queue drains.
 *
 * It MUST run in the same process as the app graph: queue reactivity comes from an
 * in-process MutableStateFlow revision counter inside JdbcMangaDownloadRepository, so a
 * separate process would never observe updates (the manifest therefore sets no
 * android:process). The service never runs its own download loop and never moves the
 * manager's scope — it only keeps the process alive and reports progress.
 */
class MangaDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var queueJob: Job? = null

    // The queue flow re-emits on every page write, so a chapter name is resolved at most
    // once per chapter and cached; building the notification stays cheap mid-download.
    private var cachedChapterId: String? = null
    private var cachedChapterName: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive: the manager is normally started at DI time, but if the process was
        // restarted just to run this service, kick the (now idempotent) loop so downloads
        // resume instead of waiting for the next full app launch.
        runCatching {
            (applicationContext as? FolioApplication)?.graph?.mangaDownloadManager?.start()
        }
        // Must go foreground promptly — the system kills a startForegroundService that does
        // not within ~5s. Never gated on POST_NOTIFICATIONS: with the permission denied the
        // notification is simply invisible, but the service still runs and downloads continue.
        goForeground("Downloading manga…")
        if (queueJob?.isActive != true) {
            queueJob = scope.launch { observeQueue() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must NOT stop downloads — surviving that is the whole point
        // of the service. Stay foreground; the dataSync FGS keeps the process alive. Do not
        // stopSelf here.
        Log.d(TAG, "Task removed; keeping the download service alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Single collector on the durable queue. Starts nothing (the manager owns the loop);
     * it only updates the notification while work is pending and retires the foreground
     * service once nothing is QUEUED or DOWNLOADING.
     */
    private suspend fun observeQueue() {
        val graph = (applicationContext as? FolioApplication)?.graph
        if (graph == null) {
            Log.w(TAG, "No app graph reachable; stopping the download service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        graph.mangaDownloadRepository.observeQueue().collect { queue ->
            val downloading = queue.firstOrNull { it.status == MangaDownloadStatus.DOWNLOADING }
            val queued = queue.count { it.status == MangaDownloadStatus.QUEUED }
            if (downloading == null && queued == 0) {
                // Queue drained (everything DOWNLOADED/ERROR, or empty): retire the service.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@collect
            }
            updateNotification(progressText(downloading, queued, graph.mangaChapterRepository))
        }
    }

    private suspend fun progressText(
        downloading: MangaDownload?,
        queued: Int,
        chapters: MangaChapterRepository,
    ): String {
        val active = downloading ?: return pluralChapters(queued)
        val name = chapterName(active.chapterId, chapters)
        val pages = if (active.totalPages > 0) {
            "${active.downloadedPages}/${active.totalPages} pages"
        } else {
            "starting…"
        }
        val extra = if (queued > 0) " · +$queued queued" else ""
        return if (name != null) "$name · $pages$extra" else "$pages$extra"
    }

    private suspend fun chapterName(chapterId: String, chapters: MangaChapterRepository): String? {
        if (cachedChapterId == chapterId) return cachedChapterName
        val name = runCatching { chapters.getChapter(chapterId)?.name?.takeIf { it.isNotBlank() } }
            .getOrNull()
        cachedChapterId = chapterId
        cachedChapterName = name
        return name
    }

    private fun pluralChapters(count: Int): String =
        "$count ${if (count == 1) "chapter" else "chapters"} queued"

    private fun goForeground(text: String) {
        // ServiceCompat does the version branching for us: on API 29+ it passes the dataSync
        // type (required for targetSdk 34), on older platforms it falls back to the 2-arg
        // call and ignores the type. FOREGROUND_SERVICE_TYPE_DATA_SYNC is a compile-time
        // constant, so referencing it is safe down to minSdk 24.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @SuppressLint("MissingPermission") // gated on areNotificationsEnabled() below
    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // A denied POST_NOTIFICATIONS silently drops the notify; the foreground service stays
        // foreground regardless, so the download continues either way.
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
            .onFailure { Log.w(TAG, "Could not update the download notification", it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Manga downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing progress while manga chapters download"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)                                  // non-dismissible while pending
            .setOnlyAlertOnce(true)                            // progress updates must not re-alert
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val TAG = "FolioDownload"
        private const val CHANNEL_ID = "manga_downloads"
        private const val NOTIFICATION_ID = 4712

        /**
         * Starts or stops the service to match [shouldRun]. Every start is wrapped: on
         * Android 12+ a background start throws ForegroundServiceStartNotAllowedException.
         * That refusal is recoverable — the queue is durable and MangaDownloadManager
         * reconciles DOWNLOADING/ERROR rows back to QUEUED on the next launch — so it is
         * logged, never rethrown, and never crashes the app.
         */
        fun sync(context: Context, shouldRun: Boolean) {
            if (shouldRun) start(context) else stop(context)
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MangaDownloadService::class.java),
                )
            } catch (e: Exception) {
                // Catches ForegroundServiceStartNotAllowedException (an IllegalStateException)
                // and any other start refusal without needing the API-31-only class at runtime.
                Log.w(TAG, "Foreground start refused; downloads resume on next launch", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MangaDownloadService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the download service", e)
            }
        }
    }
}
