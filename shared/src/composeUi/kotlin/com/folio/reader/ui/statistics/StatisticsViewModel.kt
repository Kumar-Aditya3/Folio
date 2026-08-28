package com.folio.reader.ui.statistics

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatisticsRepository
import com.folio.reader.model.Book
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

class StatisticsViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val statisticsRepository: StatisticsRepository
) {
    private fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
    fun getDashboardData(deviceId: String): Flow<DashboardData> {
        return combine(
            bookRepository.getCurrentlyReading(),
            bookRepository.getFinishedBooks(),
            statisticsRepository.getDailyStats(deviceId, today().minus(DatePeriod(days = 7)))
        ) { currentlyReading, finished, dailyStats ->
            val totalReadingTime = dailyStats.sumOf { it.readingTimeMs }
            val totalBooksFinished = finished.size
            val currentStreak = calculateStreak(dailyStats)

            DashboardData(
                continueReading = currentlyReading.firstOrNull(),
                currentlyReading = currentlyReading,
                recentlyFinished = finished.take(5),
                thisWeekStats = WeeklyStatistics(
                    weekStart = today().minus(DatePeriod(days = 7)),
                    readingTimeMs = totalReadingTime,
                    wordsRead = dailyStats.sumOf { it.wordsRead },
                    sessionsCount = dailyStats.sumOf { it.sessionCount },
                    booksFinished = dailyStats.sumOf { it.booksRead },
                    dailyStats = dailyStats
                ),
                totalReadingTimeMs = totalReadingTime,
                totalBooksFinished = totalBooksFinished,
                currentStreak = currentStreak
            )
        }
    }

    fun getBookStatistics(bookId: String): Flow<BookStatistics?> {
        // Custom flow from repository
        return kotlinx.coroutines.flow.flow { }
    }

    fun getHeatmapData(deviceId: String, year: Int): Flow<List<HeatmapDay>> {
        return statisticsRepository.getHeatmapData(deviceId, year)
    }

    fun getReadingPatterns(deviceId: String): Flow<ReadingPatterns> {
        return statisticsRepository.getDailyStats(deviceId, today().minus(DatePeriod(days = 365))).map { dailyStats ->
            val allSessions = dailyStats.flatMap { it.sessions }
            val sessionDurations = allSessions.map { it.durationMs }.sorted()
            val readingSpeeds = allSessions.mapNotNull { it.wordsPerMinute }

            ReadingPatterns(
                averageSessionMinutes = if (sessionDurations.isNotEmpty()) sessionDurations.average() / 60000.0 else 0.0,
                medianSessionMinutes = if (sessionDurations.isNotEmpty()) sessionDurations[sessionDurations.size / 2] / 60000.0 else 0.0,
                averageReadingSpeedWpm = if (readingSpeeds.isNotEmpty()) readingSpeeds.average() else 0.0,
                longestStreakDays = calculateLongestStreak(dailyStats),
                currentStreakDays = calculateStreak(dailyStats),
                mostReadDayOfWeek = calculateMostReadDay(dailyStats),
                mostReadHour = calculateMostReadHour(allSessions),
                averageCompletionDays = 0.0,
                averageTimePer100Pages = 0.0,
                totalBooksRead = dailyStats.sumOf { it.booksRead },
                totalReadingHours = dailyStats.sumOf { it.readingTimeMs } / 3_600_000.0
            )
        }
    }

    fun getReadingHistory(deviceId: String, days: Int = 30): Flow<List<ReadingHistoryEntry>> {
        return statisticsRepository.getDailyStats(deviceId, today().minus(DatePeriod(days = days))).map { dailyStats ->
            dailyStats.map { day ->
                ReadingHistoryEntry(
                    date = day.date,
                    sessions = day.sessions.map { session ->
                        session.toSessionSummary()
                    }
                )
            }
        }
    }

    fun getYearlyStats(deviceId: String, year: Int): Flow<YearlyStatistics> {
        val startDate = LocalDate(year, 1, 1)
        return statisticsRepository.getDailyStats(deviceId, startDate).map { dailyStats ->
            val monthly = mutableListOf<MonthlyStatistics>()
            for (month in 1..12) {
                val monthStart = LocalDate(year, month, 1)
                val monthEnd = monthStart.plus(DatePeriod(months = 1))
                val monthStats = dailyStats.filter { it.date >= monthStart && it.date < monthEnd }
                val weekly = mutableListOf<WeeklyStatistics>()
                var weekStart = monthStart
                while (weekStart < monthEnd) {
                    val weekEnd = weekStart.plus(DatePeriod(days = 7))
                    val weekStats = monthStats.filter { it.date >= weekStart && it.date < weekEnd }
                    weekly.add(WeeklyStatistics(
                        weekStart = weekStart,
                        readingTimeMs = weekStats.sumOf { it.readingTimeMs },
                        wordsRead = weekStats.sumOf { it.wordsRead },
                        sessionsCount = weekStats.sumOf { it.sessionCount },
                        booksFinished = weekStats.sumOf { it.booksRead },
                        dailyStats = weekStats
                    ))
                    weekStart = weekEnd
                }
                monthly.add(MonthlyStatistics(
                    year = year,
                    month = month,
                    readingTimeMs = monthStats.sumOf { it.readingTimeMs },
                    wordsRead = monthStats.sumOf { it.wordsRead },
                    sessionsCount = monthStats.sumOf { it.sessionCount },
                    booksFinished = monthStats.sumOf { it.booksRead },
                    weeklyStats = weekly
                ))
            }
            YearlyStatistics(
                year = year,
                readingTimeMs = dailyStats.sumOf { it.readingTimeMs },
                wordsRead = dailyStats.sumOf { it.wordsRead },
                sessionsCount = dailyStats.sumOf { it.sessionCount },
                booksFinished = dailyStats.sumOf { it.booksRead },
                monthlyStats = monthly
            )
        }
    }

    private fun calculateStreak(dailyStats: List<DailyStatistics>): Int {
        var streak = 0
        var currentDate = today()
        val statsMap = dailyStats.associateBy { it.date }

        while (true) {
            val stats = statsMap[currentDate]
            if (stats != null && stats.readingTimeMs > 0) {
                streak++
                currentDate = currentDate.minus(DatePeriod(days = 1))
            } else {
                break
            }
        }
        return streak
    }

    private fun calculateLongestStreak(dailyStats: List<DailyStatistics>): Int {
        var longest = 0
        var current = 0
        val statsMap = dailyStats.associateBy { it.date }
        var currentDate = dailyStats.minByOrNull { it.date }?.date ?: today()
        val endDate = today()

        while (currentDate <= endDate) {
            val stats = statsMap[currentDate]
            if (stats != null && stats.readingTimeMs > 0) {
                current++
                longest = maxOf(longest, current)
            } else {
                current = 0
            }
            currentDate = currentDate.plus(DatePeriod(days = 1))
        }
        return longest
    }

    private fun calculateMostReadDay(dailyStats: List<DailyStatistics>): Int {
        val dayTotals = mutableMapOf<Int, Long>()
        for (day in dailyStats) {
            val dow = day.date.dayOfWeek.ordinal + 1
            dayTotals[dow] = dayTotals.getOrDefault(dow, 0L) + day.readingTimeMs
        }
        return dayTotals.maxByOrNull { it.value }?.key ?: -1
    }

    private fun calculateMostReadHour(sessions: List<ReadingSession>): Int {
        val hourTotals = mutableMapOf<Int, Long>()
        for (session in sessions) {
            val hour = session.startedAt.toLocalDateTime(TimeZone.currentSystemDefault()).hour
            hourTotals[hour] = hourTotals.getOrDefault(hour, 0L) + session.durationMs
        }
        return hourTotals.maxByOrNull { it.value }?.key ?: -1
    }
}

fun ReadingSession.toSessionSummary(): com.folio.reader.statistics.SessionSummary {
    return com.folio.reader.statistics.SessionSummary(
        bookId = bookId,
        bookTitle = "",
        startTime = startedAt,
        endTime = endedAt ?: Clock.System.now(),
        durationMs = durationMs,
        startProgress = startProgress,
        endProgress = endProgress,
        wordsRead = wordsRead,
        position = startPosition
    )
}