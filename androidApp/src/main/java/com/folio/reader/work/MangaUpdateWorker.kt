package com.folio.reader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.folio.reader.FolioApplication
import com.folio.reader.database.JdbcMangaUpdateRepository

/**
 * Background manga chapter update check (§11.3). The heavy lifting (rate limiting,
 * trust gating, chapter diff) lives in [JdbcMangaUpdateRepository]; this class only
 * wires the app graph, runs one pass and posts the summary notification.
 */
class MangaUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? FolioApplication)?.graph ?: return Result.failure()
        val repository = JdbcMangaUpdateRepository(
            db = graph.database,
            mangaRepository = graph.mangaRepository,
            chapterRepository = graph.mangaChapterRepository,
            backend = graph.mangaBackend,
        )
        val result = runCatching { repository.runUpdateCheck() }.getOrElse { e ->
            e.printStackTrace()
            return Result.failure()
        }
        if (result.newChaptersTotal > 0) {
            MangaUpdateNotifier.showUpdateSummary(
                applicationContext,
                result.newChaptersTotal,
                result.seriesWithNewChapters,
            )
        }
        return Result.success()
    }
}
