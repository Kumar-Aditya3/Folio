package com.folio.reader.ui.components

import com.folio.reader.model.ReadingSession
import kotlinx.datetime.Clock
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
    val wholeDays = days.toLong().coerceAtLeast(1)
    val date = (Clock.System.now() + wholeDays.toInt().days)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val horizon = when {
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
    val target = if (wholeDays <= 365) "${months[date.monthNumber - 1]} ${date.dayOfMonth}"
        else "${months[date.monthNumber - 1]} ${date.year}"
    return "On pace to finish in $horizon · around $target"
}
