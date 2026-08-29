package com.folio.reader.ui.statistics

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.model.Book
import com.folio.reader.model.ReadingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** A single day of reading, used by both the week chart and the activity heatmap. */
data class StatDay(val date: LocalDate, val minutes: Long)

/** A book still being read, reduced to what the list needs to show. */
data class ReadingInProgress(
    val id: String,
    val title: String,
    val author: String,
    val progress: Float
)

/**
 * Every number the statistics screen renders, computed in one pass.
 *
 * The window is deliberately bounded by [StatisticsViewModel.historyDays]: streaks,
 * heatmaps and averages all need a fixed span to be comparable, and an unbounded
 * "all time" query grows with the library for no visible benefit.
 */
data class StatisticsUiState(
    val hasData: Boolean = false,
    val timeThisWeekMs: Long = 0,
    val timeThisYearMs: Long = 0,
    val streakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val activeDaysThisWeek: Int = 0,
    val sessionsThisWeek: Int = 0,
    val wordsReadThisYear: Long = 0,
    val averageSessionMinutes: Double = 0.0,
    val averageSpeedWpm: Double = 0.0,
    val mostReadDay: String = "",
    val mostReadHour: String = "",
    val booksFinished: Int = 0,
    val booksInProgress: Int = 0,
    val sourceDevices: Int = 0,
    val week: List<StatDay> = emptyList(),
    val heatmap: List<StatDay> = emptyList(),
    val currentlyReading: List<ReadingInProgress> = emptyList()
)

class StatisticsViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository
) {
    companion object {
        /** Days of history pulled into every calculation. */
        const val historyDays = 365
        /** Weeks of activity drawn in the heatmap. */
        const val heatmapWeeks = 18
        private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    private val timeZone: TimeZone get() = TimeZone.currentSystemDefault()
    private fun today(): LocalDate = Clock.System.todayIn(timeZone)

    /**
     * Reading sessions are synced across devices, so the history here is the whole
     * account's, not this handset's — which is the point of showing it.
     */
    val state: Flow<StatisticsUiState> = combine(
        sessionRepository.observeSessionsSince(
            today().minus(DatePeriod(days = historyDays)).atStartOfDayIn(timeZone)
        ),
        bookRepository.getCurrentlyReading(),
        bookRepository.getFinishedBooks()
    ) { sessions, inProgress, finished -> buildState(sessions, inProgress, finished) }

    private fun buildState(
        sessions: List<ReadingSession>,
        inProgress: List<Book>,
        finished: List<Book>
    ): StatisticsUiState {
        val today = today()
        val weekStart = today.minus(DatePeriod(days = 6))
        val yearStart = today.minus(DatePeriod(days = historyDays - 1))

        val minutesByDay = sessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }

        // A day counts as read if it holds a session at all. Measured durations can
        // legitimately round to zero on a short sitting, and treating that as "did not
        // read" breaks a streak the reader actually earned.
        val readDays = sessions.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }

        val thisWeek = sessions.filter { it.startedAt.toLocalDateTime(timeZone).date >= weekStart }
        val thisYear = sessions.filter { it.startedAt.toLocalDateTime(timeZone).date >= yearStart }
        val timed = sessions.filter { it.durationMs > 0 }

        return StatisticsUiState(
            hasData = sessions.isNotEmpty(),
            timeThisWeekMs = thisWeek.sumOf { it.durationMs },
            timeThisYearMs = thisYear.sumOf { it.durationMs },
            streakDays = currentStreak(readDays, today),
            longestStreakDays = longestStreak(readDays),
            activeDaysThisWeek = thisWeek.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }.size,
            sessionsThisWeek = thisWeek.size,
            wordsReadThisYear = thisYear.sumOf { it.wordsRead },
            averageSessionMinutes = if (timed.isEmpty()) 0.0 else timed.sumOf { it.durationMs } / 60_000.0 / timed.size,
            averageSpeedWpm = timed.mapNotNull { it.wordsPerMinute }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            mostReadDay = mostReadDay(minutesByDay),
            mostReadHour = mostReadHour(sessions),
            booksFinished = finished.size,
            booksInProgress = inProgress.size,
            sourceDevices = sessions.map { it.deviceId }.distinct().size,
            week = (0..6).map { offset ->
                val day = weekStart.plus(DatePeriod(days = offset))
                StatDay(day, minutesByDay[day] ?: 0L)
            },
            heatmap = heatmapDays(minutesByDay, today),
            currentlyReading = inProgress.map {
                ReadingInProgress(it.id, it.displayTitle, it.displayAuthor, it.normalizedProgress.toFloat())
            }
        )
    }

    /** Consecutive reading days ending today — or yesterday, if today hasn't started yet. */
    private fun currentStreak(readDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in readDays) today else today.minus(DatePeriod(days = 1))
        var streak = 0
        while (day in readDays) {
            streak++
            day = day.minus(DatePeriod(days = 1))
        }
        return streak
    }

    private fun longestStreak(readDays: Set<LocalDate>): Int {
        val days = readDays.sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && day == previous.plus(DatePeriod(days = 1))) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        return longest
    }

    private fun mostReadDay(minutesByDay: Map<LocalDate, Long>): String {
        if (minutesByDay.isEmpty()) return ""
        val totals = LongArray(7)
        minutesByDay.forEach { (day, minutes) -> totals[day.dayOfWeek.ordinal % 7] += minutes }
        val best = totals.indices.maxByOrNull { totals[it] } ?: return ""
        return if (totals[best] == 0L) "" else dayNames[best]
    }

    private fun mostReadHour(sessions: List<ReadingSession>): String {
        val totals = LongArray(24)
        sessions.forEach { totals[it.startedAt.toLocalDateTime(timeZone).hour] += it.durationMs }
        val best = totals.indices.maxByOrNull { totals[it] } ?: return ""
        return if (totals[best] == 0L) "" else "%02d:00".format(best)
    }

    /** Monday-aligned weeks so the heatmap's columns read as calendar weeks. */
    private fun heatmapDays(minutesByDay: Map<LocalDate, Long>, today: LocalDate): List<StatDay> {
        val thisMonday = today.minus(DatePeriod(days = today.dayOfWeek.ordinal % 7))
        var day = thisMonday.minus(DatePeriod(days = 7 * (heatmapWeeks - 1)))
        val out = ArrayList<StatDay>(heatmapWeeks * 7)
        while (day <= today) {
            out.add(StatDay(day, minutesByDay[day] ?: 0L))
            day = day.plus(DatePeriod(days = 1))
        }
        return out
    }
}
