package com.folio.reader.surfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.NotificationManager
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.reader.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingWidgetRenderTest {
    @Test fun widgetDataLoadsFromTheActualLibraryAndStatisticsPipeline() = kotlinx.coroutines.runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as com.folio.reader.FolioApplication).graph
        val snapshot = kotlinx.coroutines.withTimeout(30_000) { graph.readingSurfaceSnapshot() }
        assertEquals(graph.settingsRepository.getGlobalSettings().dailyGoalMinutes, snapshot.goal)
        assertEquals(7, snapshot.week.size)
        assertTrue(snapshot.minutes >= 0)
        snapshot.entry?.let { entry ->
            when (entry.kind) {
                "book" -> assertNotNull(graph.bookRepository.getBook(entry.id))
                "document" -> assertNotNull(graph.documentRepository.getDocument(entry.id))
                "manga" -> assertNotNull(graph.mangaRepository.get(entry.id))
                else -> fail("Unknown reading destination")
            }
        }
    }

    @Test fun notificationsUseDistinctChannelsPrivateContentAndDirectActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue("Enable notifications in the test profile to exercise delivery", ReadingNotifications.allowed(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val data = ReadingSurfaceSnapshot(null, 24, 60, emptyList(), 0xFFB3C6D5.toInt(), 0xFF1B2029.toInt(), 0xFFF1EFE8.toInt())
            assertTrue(ReadingNotifications.remind(context, data))
            com.folio.reader.work.MangaUpdateNotifier.showUpdateSummary(context, 3, 1, listOf("A story · 3 waiting"), "fixture")
            com.folio.reader.work.MangaUpdateNotifier.showUpdateSummary(context, 4, 1, listOf("A story · 4 waiting"), "fixture")
            ReadingNotifications.downloadsFinished(context, 2, 1)
            var active = manager.activeNotifications.filter { it.id in setOf(4711, 4714, 4715) }
            repeat(20) {
                if (active.size < 3) {
                    Thread.sleep(50)
                    active = manager.activeNotifications.filter { it.id in setOf(4711, 4714, 4715) }
                }
            }
            assertEquals(3, active.size)
            active.forEach {
                assertEquals(android.app.Notification.VISIBILITY_PRIVATE, it.notification.visibility)
                assertNotNull(it.notification.contentIntent)
                assertTrue(it.notification.actions.isNotEmpty())
            }
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(3, active.map { it.notification.channelId }.toSet().size)
                assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("reading_ritual").importance)
                assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("download_results").importance)
            }
        } finally {
            listOf(4711, 4714, 4715).forEach { manager.cancel(it) }
        }
    }

    @Test fun realLauncherLayoutsInflateAndRenderInLightDarkAndCompactSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "widget-review").apply { mkdirs() }
        val coverFile = File(folder, "specimen-cover.png")
        val cover = Bitmap.createBitmap(144, 208, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        Canvas(cover).apply {
            drawColor(0xFF202F39.toInt())
            paint.color = 0xFFB9C7B9.toInt(); drawCircle(112f, 56f, 54f, paint)
            paint.color = 0xFF304752.toInt(); drawCircle(131f, 40f, 52f, paint)
            paint.color = 0xFFE5D5B7.toInt(); paint.textSize = 16f; paint.typeface = android.graphics.Typeface.create("serif", 0)
            drawText("THE SHAPE", 12f, 135f, paint); drawText("OF QUIET", 12f, 157f, paint); drawText("THINGS", 12f, 179f, paint)
        }
        coverFile.outputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }; cover.recycle()
        instrumentation.runOnMainSync {
            for (light in listOf(false, true)) for (goal in listOf(false, true)) for (width in listOf(180, 340)) for (height in listOf(156, 184)) {
                val data = ReadingSurfaceSnapshot(ResumeEntry("book", "fixture", "The Shape of Quiet Things", "A place to return to", coverFile.absolutePath, 42, 1),
                    24, 60, listOf(12, 25, 0, 18, 40, 9, 24), if (light) 0xFF48645D.toInt() else 0xFFB3C6D5.toInt(),
                    if (light) 0xFFF3EFE7.toInt() else 0xFF1B2029.toInt(), if (light) 0xFF202B28.toInt() else 0xFFF1EFE8.toInt())
                val parent = FrameLayout(context)
                val view = WidgetRefreshWorker.views(context, data, goal, width, height).apply(context, parent)
                parent.addView(view)
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt(); val h = (height * density).toInt()
                parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, w, h)
                assertEquals(if (goal) "24" else data.entry!!.title, view.findViewById<TextView>(R.id.widget_title).text.toString())
                val title = view.findViewById<TextView>(R.id.widget_title)
                val subtitle = view.findViewById<TextView>(R.id.widget_subtitle)
                assertTrue(title.height >= title.lineHeight)
                assertTrue(subtitle.height >= subtitle.lineHeight)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                parent.draw(Canvas(bitmap))
                File(folder, "${if (goal) "goal" else "continue"}-${if (light) "light" else "dark"}-$width-$height.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        assertNotEquals(ReadingSurfaceIntents.pending(context, "book", "same-id"), ReadingSurfaceIntents.pending(context, "document", "same-id"))
    }
}
