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
    fun showUpdateSummary(context: Context, newChapters: Int, seriesCount: Int, titles: List<String> = emptyList(), mangaId: String? = null) {
        runCatching {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
            if (!com.folio.reader.surfaces.ReadingNotifications.allowed(context)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Manga updates",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val contentIntent = com.folio.reader.surfaces.ReadingSurfaceIntents.pending(context, "manga", mangaId.orEmpty())
            val text = "$newChapters new ${if (newChapters == 1) "chapter" else "chapters"} " +
                "across $seriesCount ${if (seriesCount == 1) "series" else "series"}"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_folio)
                .setContentTitle("Your next chapters are here")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (titles.isEmpty()) "" else "\n\n" + titles.take(4).joinToString("\n")))
                .setColor(0xFFB4C8D8.toInt())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .addAction(0, "Read chapters", contentIntent)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
        }
    }
}
