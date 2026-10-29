package com.folio.reader.narration

import android.content.Context
import androidx.work.*
import com.folio.reader.FolioApplication
import com.folio.reader.model.ReadingPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Charging-only, installed assets only, no progress/stats writes and no audio focus. */
class NarrationPreparationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val power = PreparationPower(applicationContext)
        try {
        val controller = (applicationContext as FolioApplication).graph.narration
        val extras = controller.extras
        if (!controller.options.value.prepareWhileCharging || !controller.options.value.audioCache) return@withContext Result.success()
        if (!power.ready || extras.foregroundRequested || controller.state.value.wantsPlayback) return@withContext Result.retry()
        val position = runCatching { Json.decodeFromString(ReadingPosition.serializer(),inputData.getString("position") ?: "") }.getOrNull()
            ?: return@withContext Result.failure()
        val config = controller.configuration.value
        if (!controller.installer.isInstalled() || !extras.voiceReady(config)) return@withContext Result.failure(workDataOf("message" to "Download the selected model and narrator first"))
        extras.preparationCancelled = false
        try {
            withTimeout(8 * 60_000L) { extras.inference.withLock {
                if (extras.foregroundRequested || isStopped) return@withLock Result.retry()
                val chapter = controller.graph.bookRepository.getChaptersForBook(position.bookId).firstOrNull { it.spineIndex == position.spineIndex }
                    ?: return@withLock Result.failure(workDataOf("message" to "This chapter is no longer available"))
                val html = controller.graph.contentProvider.getHtml(position.bookId,chapter.href)
                if (com.folio.reader.ui.render.FixedLayoutDetector.detect(html).isFixedLayout) return@withLock Result.failure(workDataOf("message" to "Preparation supports reflowable text chapters"))
                val document = NarrationDelivery.plan(NarrationText.prepare(html),controller.options.value)
                val rules = extras.readRules(position.bookId)
                var yielded = false
                var samples = 0L
                val job = currentCoroutineContext()[Job]!!
                android.system.Os.setenv("RAYON_NUM_THREADS","2",true)
                extras.Session(config,controller.installer.directory).use { session ->
                    for (passage in document.passages) {
                        ensureActive()
                        if (!power.ready || extras.foregroundRequested || isStopped || extras.preparationCancelled) { yielded = true; break }
                        val complete = session.render(position.bookId,NarrationDelivery.spoken(passage.text,rules)) { pcm ->
                            samples += pcm.size
                            job.isActive && power.ready && !isStopped && !extras.foregroundRequested && !extras.preparationCancelled
                        }
                        if (!complete) { yielded = true; break }
                        setProgress(workDataOf("passages" to (document.passages.indexOf(passage)+1)))
                        // Each work unit is bounded even for an unusually large chapter.
                        if (samples >= 24_000L * 20 * 60) return@withLock Result.failure(workDataOf("message" to "Prepared the first 20 minutes; play onward to cache the rest"))
                    }
                }
                extras.refreshStorage()
                if (yielded && !extras.preparationCancelled) Result.retry() else Result.success()
            } }
        } catch (error: TimeoutCancellationException) {
            Result.failure(workDataOf("message" to "Preparation paused at its time limit; completed passages are saved"))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Result.failure(workDataOf("message" to "Could not prepare this chapter. Try again.")) }
        } finally { power.close() }
    }
}
