package com.folio.reader.surfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.reader.R
import com.folio.reader.narration.NarrationPhase
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudiobookWidgetStateTest {
    @Test fun stalePauseFromAnotherWidgetDoesNotChangeTheCurrentBook() = kotlinx.coroutines.runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = (context.applicationContext as com.folio.reader.FolioApplication).graph.narration
        org.junit.Assume.assumeTrue(controller.service == null)
        val original = controller.state.value
        try {
            instrumentation.runOnMainSync {
                controller.mutable.value = com.folio.reader.narration.NarrationState(
                    bookId = "current-widget-book", phase = NarrationPhase.Interrupted)
            }
            WidgetRefreshWorker.audioPending(context, "pause", "old-widget-book").send()
            kotlinx.coroutines.withTimeout(5_000) {
                while (controller.service == null) kotlinx.coroutines.delay(20)
            }
            instrumentation.waitForIdleSync()
            assertEquals("current-widget-book", controller.state.value.bookId)
            assertEquals(NarrationPhase.Interrupted, controller.state.value.phase)
        } finally {
            context.stopService(android.content.Intent(context, com.folio.reader.narration.NarrationPlaybackService::class.java))
            instrumentation.waitForIdleSync()
            controller.cleanup?.join()
            instrumentation.runOnMainSync { controller.mutable.value = original }
        }
    }

    @Test fun emptyMissingModelAndPlaybackStatesHaveHonestControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "widget-modern-review").apply { mkdirs() }
        val saved = ListeningEntry("fixture", "The Shape of Quiet Things", "A place to return to", null,
            "Chapter 7", 42, 1f, NarrationPhase.Paused, true)
        val states = listOf("empty" to null, "setup" to saved.copy(ready = false), "paused" to saved,
            "playing" to saved.copy(phase = NarrationPhase.Playing), "preparing" to saved.copy(phase = NarrationPhase.Preparing),
            "error" to saved.copy(phase = NarrationPhase.Error), "finished" to saved.copy(phase = NarrationPhase.Ended, progress = 100))
        instrumentation.runOnMainSync {
            states.forEach { (name, entry) ->
                val data = ReadingSurfaceSnapshot(null, 0, 60, emptyList(), 0xFFAFC9C1.toInt(),
                    0xFF152725.toInt(), 0xFFF0F2E9.toInt(), entry)
                val host = FrameLayout(context)
                val view = WidgetRefreshWorker.views(context, data, FolioWidgetKind.Audiobook, 340).apply(context, host)
                host.addView(view)
                val density = context.resources.displayMetrics.density
                val w = (340 * density).toInt(); val h = (WidgetRefreshWorker.defaultHeight(FolioWidgetKind.Audiobook) * density).toInt()
                host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, w, h)
                assertEquals(entry?.ready == true, view.findViewById<View>(R.id.widget_audio_previous).isEnabled)
                assertEquals(entry?.ready == true, view.findViewById<View>(R.id.widget_audio_next).isEnabled)
                assertTrue(view.findViewById<View>(R.id.widget_audio_toggle).isEnabled)
                assertEquals(if (entry == null) "Choose a book" else if (!entry.ready) "Open narration setup" else if (entry.phase == NarrationPhase.Ended) "Restart narration" else if (entry.playing) "Pause narration" else "Resume narration",
                    view.findViewById<View>(R.id.widget_audio_toggle).contentDescription.toString())
                if (name == "preparing") assertEquals("Preparing narration", view.findViewById<TextView>(R.id.widget_subtitle).text.toString())
                if (name == "setup") assertEquals("Set up narration to listen", view.findViewById<TextView>(R.id.widget_subtitle).text.toString())
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                host.draw(Canvas(bitmap))
                File(folder, "audiobook-state-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
