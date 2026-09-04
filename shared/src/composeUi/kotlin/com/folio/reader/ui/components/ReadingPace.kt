package com.folio.reader.ui.components

import com.folio.reader.model.ReadingSession
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

/**
 * Words per day averaged over the last seven days of session history, or null when
 * the pace is too thin to project a finish date from.
 */
fun readingPaceWordsPerDay(sessions: List<ReadingSession>): Double? {
    val weekAgo = Clock.System.now() - 7.days
    val wordsThisWeek = sessions.filter { it.startedAt >= weekAgo }.sumOf { it.wordsRead }
    val dailyPace = wordsThisWeek / 7.0
    return if (dailyPace >= 100.0) dailyPace else null
}

/**
 * Days remaining before the book is finished at the current pace, or null when a
 * projection would be meaningless (not started, finished, or pace too thin).
 *
 * Shared by [finishEstimate] and [finishHorizon] so the long and short forms can
 * never disagree.
 */
private fun remainingDays(
    totalWords: Long,
    progress: Double,
    bookSessions: List<ReadingSession>,
    paceSessions: List<ReadingSession>
): Long? {
    if (progress <= 0.0 || progress >= 1.0) return null
    val days = if (totalWords > 0) {
        val pace = readingPaceWordsPerDay(paceSessions) ?: return null
        totalWords * (1.0 - progress) / pace
    } else {
        val readMs = bookSessions.sumOf { it.durationMs }
        if (readMs < 2 * 60_000L) return null
        val weekAgo = Clock.System.now() - 7.days
        val dailyMinutes = paceSessions
            .filter { it.startedAt >= weekAgo }
            .sumOf { it.durationMs } / 7.0 / 60_000.0
        if (dailyMinutes < 3.0) return null
        val remainingMs = readMs * (1.0 - progress) / progress
        remainingMs / (dailyMinutes * 60_000.0)
    }
    if (!days.isFinite() || days <= 0.0) return null
    return days.toLong().coerceAtLeast(1)
}

/** "~6 days" / "~3 months" / "~2 years" — the horizon phrase on its own. */
private fun horizonPhrase(wholeDays: Long): String = when {
    wholeDays < 60 -> "~$wholeDays day${if (wholeDays == 1L) "" else "s"}"
    wholeDays < 730 -> {
        val m = (wholeDays / 30.44).toInt().coerceAtLeast(2)
        "~$m month${if (m == 1) "" else "s"}"
    }
    else -> {
        val y = (wholeDays / 365.25).toInt().coerceAtLeast(1)
        "~$y year${if (y == 1) "" else "s"}"
    }
}

/**
 * Compact form for library rows: "~6 days left", or null when no projection
 * exists. Callers render nothing for null rather than a placeholder (§5.1).
 */
fun finishHorizon(
    totalWords: Long,
    progress: Double,
    bookSessions: List<ReadingSession>,
    paceSessions: List<ReadingSession> = bookSessions
): String? = remainingDays(totalWords, progress, bookSessions, paceSessions)
    ?.let { "${horizonPhrase(it)} left" }

/**
 * "On pace to finish in ~N days · around Mon D", or null when a projection would be
 * meaningless (not started, finished, or pace too thin to extrapolate).
 *
 * Long horizons are rendered, not hidden: a huge book at a modest pace projects years
 * out, and blanking the estimate there reads as broken. Beyond a year the horizon is
 * phrased in months/years and the target becomes "Mon YYYY".
 *
 * Prefers a word-count projection; books without a word count fall back to one built
 * from the time already spent on the book versus its progress.
 */
fun finishEstimate(
    totalWords: Long,
    progress: Double,
    bookSessions: List<ReadingSession>,
    paceSessions: List<ReadingSession> = bookSessions
): String? {
    val wholeDays = remainingDays(totalWords, progress, bookSessions, paceSessions) ?: return null
    val date = (Clock.System.now() + wholeDays.toInt().days)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val target = if (wholeDays <= 365) "${months[date.monthNumber - 1]} ${date.dayOfMonth}"
        else "${months[date.monthNumber - 1]} ${date.year}"
    return "On pace to finish in ${horizonPhrase(wholeDays)} · around $target"
}

/** Consecutive reading days ending today — or yesterday, if today hasn't started yet. */
internal fun currentStreak(readDays: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in readDays) today else today.minus(DatePeriod(days = 1))
    var streak = 0
    while (day in readDays) {
        streak++
        day = day.minus(DatePeriod(days = 1))
    }
    return streak
}

/**
 * Chapters per day, averaged over the same seven-day window the word-count pace
 * uses, or null when the week is too thin to extrapolate from.
 *
 * Manga has no word count, so the unit of progress is the chapter. Two chapters
 * across a week is noise, not a pace — below that the caller shows nothing rather
 * than a projection it would have to apologise for.
 */
fun mangaPaceChaptersPerDay(weekReadChapters: List<Int>): Double? {
    if (weekReadChapters.isEmpty()) return null
    val perDay = weekReadChapters.sum() / weekReadChapters.size.toDouble()
    return if (perDay >= 0.3) perDay else null
}

/**
 * Days before the unread backlog is cleared at the current chapter pace, or null
 * when there is nothing to project.
 */
private fun mangaRemainingDays(unreadChapters: Int, chaptersPerDay: Double?): Long? {
    if (unreadChapters <= 0) return null
    val pace = chaptersPerDay ?: return null
    if (pace <= 0.0) return null
    val days = unreadChapters / pace
    if (!days.isFinite() || days <= 0.0) return null
    return days.toLong().coerceAtLeast(1)
}

/**
 * Compact manga form for shelves: "12 unread · ~4 days", falling back to the
 * unread count alone when the pace cannot carry a projection, and to null when
 * there is nothing left to read.
 *
 * Deliberately the same horizon vocabulary as [finishHorizon]: a reader should not
 * have to learn two dialects of "how long is left" because one shelf holds EPUBs
 * and the other holds chapters.
 */
fun mangaFinishHorizon(unreadChapters: Int, chaptersPerDay: Double?): String? {
    if (unreadChapters <= 0) return null
    val unread = "$unreadChapters unread"
    val days = mangaRemainingDays(unreadChapters, chaptersPerDay) ?: return unread
    return "$unread · ${horizonPhrase(days)}"
}

/**
 * The long manga form for the Home anchor, matching [finishEstimate]'s phrasing so
 * the hero reads identically whether it is a book or a manga.
 */
fun mangaFinishEstimate(unreadChapters: Int, chaptersPerDay: Double?): String? {
    val wholeDays = mangaRemainingDays(unreadChapters, chaptersPerDay)
        ?: return if (unreadChapters > 0) "$unreadChapters unread" else null
    val date = (Clock.System.now() + wholeDays.toInt().days)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val target = if (wholeDays <= 365) "${months[date.monthNumber - 1]} ${date.dayOfMonth}"
        else "${months[date.monthNumber - 1]} ${date.year}"
    return "On pace to clear $unreadChapters unread in ${horizonPhrase(wholeDays)} · around $target"
}

