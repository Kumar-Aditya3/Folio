package com.folio.reader.statistics

import com.folio.reader.model.Book
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

@Serializable
data class BookStatistics(
    val bookId: String,
    val totalReadingTimeMs: Long = 0,
    val totalSessions: Int = 0,
    val totalWordsRead: Long = 0,
    val averageSessionTimeMs: Long = 0,
    val longestSessionMs: Long = 0,
    val readingDays: Int = 0,
    val firstOpenedAt: Instant? = null,
    val lastOpenedAt: Instant? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val completionTimeMs: Long? = null,
    val averageReadingSpeedWpm: Double = 0.0,
    val currentCycle: Int = 1,
    val cycleHistory: List<CycleStatistics> = emptyList()
) {
    val totalReadingHours: Double
        get() = totalReadingTimeMs / 3_600_000.0

    val averageSessionMinutes: Double
        get() = if (totalSessions > 0) averageSessionTimeMs / 60_000.0 else 0.0

    val completionTimeDays: Double?
        get() = completionTimeMs?.let { it / 86_400_000.0 }

    fun addSession(session: ReadingSession): BookStatistics {
        val newTotalTime = totalReadingTimeMs + session.durationMs
        val newTotalSessions = totalSessions + 1
        val newTotalWords = totalWordsRead + session.wordsRead
        val newAvgSession = if (newTotalSessions > 0) newTotalTime / newTotalSessions else 0L
        val newLongest = maxOf(longestSessionMs, session.durationMs)
        val newAvgSpeed = if (newTotalTime > 0) (newTotalWords.toDouble() / (newTotalTime / 60_000.0)) else 0.0

        return copy(
            totalReadingTimeMs = newTotalTime,
            totalSessions = newTotalSessions,
            totalWordsRead = newTotalWords,
            averageSessionTimeMs = newAvgSession,
            longestSessionMs = newLongest,
            lastOpenedAt = session.endedAt ?: Clock.System.now(),
            averageReadingSpeedWpm = newAvgSpeed,
            readingDays = calculateReadingDays(session)
        )
    }

    private fun calculateReadingDays(session: ReadingSession): Int {
        // Simplified - in reality would track unique dates
        return readingDays
    }

    fun startReading(now: Instant = Clock.System.now()): BookStatistics {
        return copy(
            firstOpenedAt = firstOpenedAt ?: now,
            lastOpenedAt = now,
            startedAt = startedAt ?: now
        )
    }

    fun finishReading(now: Instant = Clock.System.now()): BookStatistics {
        val completionTime = startedAt?.let { now.toEpochMilliseconds() - it.toEpochMilliseconds() }
        return copy(
            finishedAt = now,
            lastOpenedAt = now,
            completionTimeMs = completionTime
        )
    }

    fun addCycle(cycle: CycleStatistics): BookStatistics {
        return copy(
            currentCycle = currentCycle + 1,
            cycleHistory = cycleHistory + cycle
        )
    }
}

@Serializable
data class CycleStatistics(
    val cycleNumber: Int,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val totalDurationMs: Long = 0,
    val sessionCount: Int = 0,
    val finalProgress: Double = 0.0,
    val averageSpeedWpm: Double = 0.0,
    val readingDays: Int = 0
) {
    val durationHours: Double
        get() = totalDurationMs / 3_600_000.0

    val isCompleted: Boolean
        get() = finishedAt != null
}

@Serializable
data class DailyStatistics(
    val date: LocalDate,
    val readingTimeMs: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0,
    val booksRead: Int = 0,
    val sessions: List<ReadingSession> = emptyList()
) {
    val readingHours: Double
        get() = readingTimeMs / 3_600_000.0

    val readingMinutes: Int
        get() = (readingTimeMs / 60_000).toInt()

    val formattedTime: String
        get() {
            val hours = readingMinutes / 60
            val minutes = readingMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
}

@Serializable
data class WeeklyStatistics(
    val weekStart: LocalDate,
    val readingTimeMs: Long = 0,
    val wordsRead: Long = 0,
    val sessionsCount: Int = 0,
    val booksFinished: Int = 0,
    val dailyStats: List<DailyStatistics> = emptyList()
) {
    val readingHours: Double
        get() = readingTimeMs / 3_600_000.0

    val averageDailyMinutes: Double
        get() = if (dailyStats.isNotEmpty()) dailyStats.map { it.readingTimeMs / 60_000.0 }.average() else 0.0
}

@Serializable
data class MonthlyStatistics(
    val year: Int,
    val month: Int,
    val readingTimeMs: Long = 0,
    val wordsRead: Long = 0,
    val sessionsCount: Int = 0,
    val booksFinished: Int = 0,
    val weeklyStats: List<WeeklyStatistics> = emptyList()
) {
    val readingHours: Double
        get() = readingTimeMs / 3_600_000.0
}

@Serializable
data class YearlyStatistics(
    val year: Int,
    val readingTimeMs: Long = 0,
    val wordsRead: Long = 0,
    val sessionsCount: Int = 0,
    val booksFinished: Int = 0,
    val monthlyStats: List<MonthlyStatistics> = emptyList()
) {
    val readingHours: Double
        get() = readingTimeMs / 3_600_000.0
}

@Serializable
data class ReadingPatterns(
    val averageSessionMinutes: Double = 0.0,
    val medianSessionMinutes: Double = 0.0,
    val averageReadingSpeedWpm: Double = 0.0,
    val longestStreakDays: Int = 0,
    val currentStreakDays: Int = 0,
    val mostReadDayOfWeek: Int = -1, // 1=Mon, 7=Sun
    val mostReadHour: Int = -1, // 0-23
    val averageCompletionDays: Double = 0.0,
    val averageTimePer100Pages: Double = 0.0,
    val totalBooksRead: Int = 0,
    val totalReadingHours: Double = 0.0
)

@Serializable
data class HeatmapDay(
    val date: LocalDate,
    val intensity: Int, // 0-4
    val readingTimeMs: Long = 0,
    val wordsRead: Long = 0,
    val sessionCount: Int = 0,
    val booksRead: Int = 0
) {
    val hasReading: Boolean
        get() = readingTimeMs > 0
}

@Serializable
data class HeatmapMonth(
    val year: Int,
    val month: Int,
    val days: List<HeatmapDay>
) {
    val totalReadingTimeMs: Long
        get() = days.sumOf { it.readingTimeMs }

    val totalWordsRead: Long
        get() = days.sumOf { it.wordsRead }
}

@Serializable
data class DashboardData(
    val continueReading: Book? = null,
    val currentlyReading: List<Book> = emptyList(),
    val recentlyFinished: List<Book> = emptyList(),
    val thisWeekStats: WeeklyStatistics? = null,
    val totalReadingTimeMs: Long = 0,
    val totalBooksFinished: Int = 0,
    val currentStreak: Int = 0
)