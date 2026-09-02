package com.folio.reader.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import com.folio.reader.R

/** One summary notification per update run — never per manga (§11.3). */
object MangaUpdateNotifier {

    private const val CHANNEL_ID = "manga_updates"
    private const val SUMMARY_NOTIFICATION_ID = 4711

    /** Silently does nothing when notifications are unavailable or permission is withheld. */
    @SuppressLint("MissingPermission") // gated on areNotificationsEnabled() below
    fun showUpdateSummary(context: Context, newChapters: Int, seriesCount: Int) {
        runCatching {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
            if (!notificationManager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Manga updates",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = "$newChapters new ${if (newChapters == 1) "chapter" else "chapters"} " +
                "across $seriesCount ${if (seriesCount == 1) "series" else "series"}"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
        }
    }
}
