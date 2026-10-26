package com.folio.reader.surfaces

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.folio.reader.R

object ReadingNotifications {
    private const val REMINDER = "reading_ritual"
    private const val ID = 4714

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission")
    fun remind(context: Context, snapshot: ReadingSurfaceSnapshot): Boolean {
        if (!allowed(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(REMINDER, "Reading time", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Your optional daily invitation to read"; setShowBadge(false)
        })
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(REMINDER)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val entry = snapshot.entry
        val open = ReadingSurfaceIntents.pending(context, entry?.kind ?: "home", entry?.id.orEmpty())
        val text = entry?.let { "Return to ${it.title}. A few quiet pages are enough." }
            ?: "A little room for reading. Your library is waiting."
        val notification = NotificationCompat.Builder(context, REMINDER).setSmallIcon(R.drawable.ic_notification_folio)
            .setContentTitle("A moment for your next page").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setColor(snapshot.accent)
            .setContentIntent(open).addAction(0, "Read now", open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true).setTimeoutAfter(90 * 60 * 1000L)
        WidgetArtwork.cover(entry?.cover)?.let { notification.setLargeIcon(it) }
        manager.notify(ID, notification.build())
        return true
    }

    fun cancelReminder(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ID) }

    @android.annotation.SuppressLint("MissingPermission")
    fun downloadsFinished(context: Context, complete: Int, failed: Int) {
        if (!allowed(context) || complete + failed == 0) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "download_results"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Download results", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A quiet summary when your manga download queue finishes"; setShowBadge(false)
        })
        val open = ReadingSurfaceIntents.pending(context, "downloads")
        val text = if (failed == 0) "$complete ${if (complete == 1) "chapter is" else "chapters are"} ready for offline reading."
            else "$complete ready to read · $failed ${if (failed == 1) "chapter needs" else "chapters need"} another try."
        manager.notify(4715, NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_folio)
            .setContentTitle(if (failed == 0) "Your offline shelf is ready" else "A few downloads need attention")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFFB4C8D8.toInt()).setContentIntent(open).addAction(0, "View downloads", open)
            .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}
