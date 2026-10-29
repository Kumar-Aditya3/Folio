package com.folio.reader.surfaces

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import com.folio.reader.AppGraph
import com.folio.reader.MainActivity
import com.folio.reader.settings.FOLIO_WIDGET_GLASS
import com.folio.reader.ui.statistics.StatisticsViewModel
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.toFolioColors
import kotlinx.coroutines.flow.first

data class ResumeEntry(val kind: String, val id: String, val title: String, val subtitle: String,
    val cover: String?, val progress: Int, val openedAt: Long)

data class ReadingSurfaceSnapshot(val entry: ResumeEntry?, val minutes: Long, val goal: Int,
    val week: List<Long>, val accent: Int, val surface: Int, val ink: Int, val listening: ListeningEntry? = null,
    val glassOpacity: Float = FOLIO_WIDGET_GLASS, val sheen: Float = 1f, val glassEffects: Boolean = true)

data class ListeningEntry(val id: String, val title: String, val author: String, val cover: String?,
    val chapter: String, val progress: Int, val speed: Float,
    val phase: com.folio.reader.narration.NarrationPhase, val ready: Boolean) {
    val playing: Boolean get() = phase in setOf(com.folio.reader.narration.NarrationPhase.Playing,
        com.folio.reader.narration.NarrationPhase.Preparing, com.folio.reader.narration.NarrationPhase.Buffering)
}

object ReadingSurfaceIntents {
    const val ACTION = "com.folio.reader.OPEN_READING_SURFACE"
    fun intent(context: Context, kind: String = "home", id: String = ""): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("folio").authority("surface").appendPath(kind).appendPath(id).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, kind: String = "home", id: String = ""): PendingIntent =
        PendingIntent.getActivity(context, 0, intent(context, kind, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

suspend fun AppGraph.readingSurfaceSnapshot(includeStats: Boolean = true, includeAudio: Boolean = true): ReadingSurfaceSnapshot {
    val settings = settingsRepository.getGlobalSettings()
    val colors = settings.customAppTheme?.toFolioColors() ?: AppPalette.byId(settings.appThemeId).colors
    val books = bookRepository.getAllBooks().first()
    val documents = documentRepository.observeDocuments().first()
    val manga = mangaRepository.observeLibrary().first()
    val candidates = books.filter { it.lastOpenedAt != null && it.normalizedProgress < .995 &&
        it.status != com.folio.reader.model.BookStatus.FINISHED && it.status != com.folio.reader.model.BookStatus.ABANDONED }
        .map { ResumeEntry("book", it.id, it.displayTitle, it.displayAuthor.ifBlank { "Book" }, it.coverPath,
            it.progressPercent.coerceIn(0, 100), it.lastOpenedAt!!.toEpochMilliseconds()) } +
        documents.filter { it.lastOpenedAt != null && it.normalizedProgress < .995 }.map {
            ResumeEntry("document", it.id, it.title, it.format.name, it.thumbnailPath,
                (it.normalizedProgress * 100).toInt().coerceIn(0, 100), it.lastOpenedAt!!.toEpochMilliseconds())
        } + manga.filter { it.lastReadAt != null }.map {
            ResumeEntry("manga", it.id, it.title, it.author ?: "Manga",
                it.coverPath?.takeIf { path -> java.io.File(path).isFile }
                    ?: com.folio.reader.ui.manga.MangaCoverDiskCache.cachedPath(it.sourceId, it.thumbnailUrl),
                0, it.lastReadAt!!.toEpochMilliseconds())
        }
    var entry: ResumeEntry? = null
    for (candidate in candidates.sortedByDescending { it.openedAt }) {
        if (candidate.kind != "manga") { entry = candidate; break }
        val chapters = mangaChapterRepository.getChapters(candidate.id)
        if (chapters.isEmpty() || chapters.any { !it.read }) {
            entry = candidate.copy(progress = if (chapters.isEmpty()) 0 else chapters.count { it.read } * 100 / chapters.size)
            if (entry.cover == null) {
                val series = manga.first { it.id == candidate.id }
                series.thumbnailUrl?.let { url ->
                    val key = "${series.sourceId}:$url"
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(8_000) {
                            mangaBackend.fetchCover(series.sourceId, url)?.let { bytes ->
                                com.folio.reader.ui.manga.MangaCoverDiskCache.write(key, bytes)
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                    } catch (_: Exception) { /* Offline: the widget can still resume the series. */ }
                    entry = entry?.copy(cover = com.folio.reader.ui.manga.MangaCoverDiskCache.cachedPath(series.sourceId, url))
                }
            }
            break
        }
    }
    // Reuse the Stats pipeline so exclusions, local dates and active reading time agree.
    val stats = if (includeStats) StatisticsViewModel(bookRepository, sessionRepository,
        statsExclusionRepository = statsExclusionRepository, tagRepository = tagRepository,
        collectionRepository = collectionRepository, mangaBackend = mangaBackend,
        mangaRepository = mangaRepository, mangaCategoryRepository = mangaCategoryRepository).state.first() else null
    val listening = if (includeAudio) {
        val state = narration.state.value
        val checkpoint = com.folio.reader.narration.NarrationPersistence(database).latest()
        val bookId = state.bookId.takeIf { state.hasBook } ?: checkpoint?.position?.bookId
        books.firstOrNull { it.id == bookId }?.let { book ->
            val live = state.takeIf { it.hasBook && it.bookId == book.id }
            val position = live?.position ?: checkpoint?.position?.takeIf { it.bookId == book.id }
            val chapter = bookRepository.getChaptersForBook(book.id).firstOrNull { it.spineIndex == position?.spineIndex }
            ListeningEntry(book.id, book.displayTitle, book.displayAuthor, book.coverPath,
                live?.chapterTitle?.takeIf { it.isNotBlank() } ?: chapter?.title.orEmpty(),
                ((position?.normalizedProgress ?: book.normalizedProgress) * 100).toInt().coerceIn(0, 100),
                live?.speed ?: checkpoint?.speed ?: 1f,
                live?.phase ?: if (checkpoint?.bookCompleted == true) com.folio.reader.narration.NarrationPhase.Ended else com.folio.reader.narration.NarrationPhase.Paused,
                narration.pack.value.phase == com.folio.reader.narration.VoicePackPhase.Installed &&
                    narration.configuration.value.narratorReady && !narration.configuration.value.changing)
        }
    } else null
    return ReadingSurfaceSnapshot(entry, stats?.todayMinutes ?: 0, settings.dailyGoalMinutes,
        stats?.week?.map { it.minutes }.orEmpty(), colors.accentProgress.toArgb(), colors.surface.toArgb(), colors.onSurface.toArgb(), listening,
        settings.widgetOpacity.coerceIn(FolioSurfaceOpacity.MIN, 1f),
        settings.metallicSheen, settings.liquidGlassEffects)
}

class ReadingSurfacePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("reading_surfaces", Context.MODE_PRIVATE)
    var reminders: Boolean
        get() = prefs.getBoolean("reminders", false)
        set(value) { prefs.edit().putBoolean("reminders", value).apply() }
    var minute: Int
        get() = prefs.getInt("minute", 20 * 60).coerceIn(0, 1439)
        set(value) { prefs.edit().putInt("minute", value.coerceIn(0, 1439)).apply() }
    var lastReminder: String?
        get() = prefs.getString("last_reminder", null)
        set(value) { prefs.edit().putString("last_reminder", value).apply() }
}
