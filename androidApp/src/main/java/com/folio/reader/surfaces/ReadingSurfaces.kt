package com.folio.reader.surfaces

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import com.folio.reader.AppGraph
import com.folio.reader.MainActivity
import com.folio.reader.ui.statistics.StatisticsViewModel
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.toFolioColors
import kotlinx.coroutines.flow.first

data class ResumeEntry(val kind: String, val id: String, val title: String, val subtitle: String,
    val cover: String?, val progress: Int, val openedAt: Long)

data class ReadingSurfaceSnapshot(val entry: ResumeEntry?, val minutes: Long, val goal: Int,
    val week: List<Long>, val accent: Int, val surface: Int, val ink: Int)

object ReadingSurfaceIntents {
    const val ACTION = "com.folio.reader.OPEN_READING_SURFACE"
    fun intent(context: Context, kind: String = "home", id: String = ""): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("folio").authority("surface").appendPath(kind).appendPath(id).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, kind: String = "home", id: String = ""): PendingIntent =
        PendingIntent.getActivity(context, 0, intent(context, kind, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

suspend fun AppGraph.readingSurfaceSnapshot(includeStats: Boolean = true): ReadingSurfaceSnapshot {
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
            ResumeEntry("manga", it.id, it.title, it.author ?: "Manga", it.coverPath, 0, it.lastReadAt!!.toEpochMilliseconds())
        }
    var entry: ResumeEntry? = null
    for (candidate in candidates.sortedByDescending { it.openedAt }) {
        if (candidate.kind != "manga") { entry = candidate; break }
        val chapters = mangaChapterRepository.getChapters(candidate.id)
        if (chapters.isEmpty() || chapters.any { !it.read }) {
            entry = candidate.copy(progress = if (chapters.isEmpty()) 0 else chapters.count { it.read } * 100 / chapters.size)
            break
        }
    }
    // Reuse the Stats pipeline so exclusions, local dates and active reading time agree.
    val stats = if (includeStats) StatisticsViewModel(bookRepository, sessionRepository,
        statsExclusionRepository = statsExclusionRepository, tagRepository = tagRepository,
        collectionRepository = collectionRepository, mangaBackend = mangaBackend,
        mangaRepository = mangaRepository, mangaCategoryRepository = mangaCategoryRepository).state.first() else null
    return ReadingSurfaceSnapshot(entry, stats?.todayMinutes ?: 0, settings.dailyGoalMinutes,
        stats?.week?.map { it.minutes }.orEmpty(), colors.accentProgress.toArgb(), colors.surface.toArgb(), colors.onSurface.toArgb())
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
