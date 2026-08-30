package com.folio.reader.database

import com.folio.reader.statistics.BookStatistics
import com.folio.reader.statistics.CycleStatistics
import com.folio.reader.statistics.DailyStatistics
import com.folio.reader.statistics.HeatmapDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val statsJson = Json { ignoreUnknownKeys = true }

class JdbcStatisticsRepository(private val db: Database) : StatisticsRepository {

    override suspend fun upsertBookStats(stats: BookStatistics) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO book_statistics (
                    book_id, total_reading_time_ms, total_sessions, total_words_read,
                    average_session_time_ms, longest_session_ms, reading_days,
                    first_opened_at, last_opened_at, started_at, finished_at, completion_time_ms,
                    average_reading_speed_wpm, current_cycle, cycle_history
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, stats.bookId)
                stmt.setLong(2, stats.totalReadingTimeMs)
                stmt.setInt(3, stats.totalSessions)
                stmt.setLong(4, stats.totalWordsRead)
                stmt.setLong(5, stats.averageSessionTimeMs)
                stmt.setLong(6, stats.longestSessionMs)
                stmt.setInt(7, stats.readingDays)
                setNullableLong(stmt, 8, stats.firstOpenedAt)
                setNullableLong(stmt, 9, stats.lastOpenedAt)
                setNullableLong(stmt, 10, stats.startedAt)
                setNullableLong(stmt, 11, stats.finishedAt)
                setNullableLong(stmt, 12, stats.completionTimeMs)
                stmt.setDouble(13, stats.averageReadingSpeedWpm)
                stmt.setInt(14, stats.currentCycle)
                stmt.setString(
                    15,
                    statsJson.encodeToString(ListSerializer(CycleStatistics.serializer()), stats.cycleHistory)
                )
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun getBookStats(bookId: String): BookStatistics? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM book_statistics WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        BookStatistics(
                            bookId = rs.getString("book_id"),
                            totalReadingTimeMs = rs.getLong("total_reading_time_ms"),
                            totalSessions = rs.getInt("total_sessions"),
                            totalWordsRead = rs.getLong("total_words_read"),
                            averageSessionTimeMs = rs.getLong("average_session_time_ms"),
                            longestSessionMs = rs.getLong("longest_session_ms"),
                            readingDays = rs.getInt("reading_days"),
                            firstOpenedAt = getNullableInstant(rs, "first_opened_at"),
                            lastOpenedAt = getNullableInstant(rs, "last_opened_at"),
                            startedAt = getNullableInstant(rs, "started_at"),
                            finishedAt = getNullableInstant(rs, "finished_at"),
                            completionTimeMs = getNullableLong(rs, "completion_time_ms"),
                            averageReadingSpeedWpm = rs.getDouble("average_reading_speed_wpm"),
                            currentCycle = rs.getInt("current_cycle"),
                            cycleHistory = runCatching {
                                statsJson.decodeFromString(
                                    ListSerializer(CycleStatistics.serializer()),
                                    rs.getString("cycle_history") ?: "[]"
                                )
                            }.getOrDefault(emptyList())
                        )
                    } else null
                }
            }
        }
    }

    override suspend fun upsertDailyStats(stats: DailyStatistics) = upsertDailyStatsFor(DEFAULT_DEVICE, stats)

    suspend fun upsertDailyStatsFor(deviceId: String, stats: DailyStatistics) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO daily_statistics (
                    device_id, date, reading_time_ms, words_read, session_count, books_read, sessions_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.setString(2, stats.date.toString())
                stmt.setLong(3, stats.readingTimeMs)
                stmt.setLong(4, stats.wordsRead)
                stmt.setInt(5, stats.sessionCount)
                stmt.setInt(6, stats.booksRead)
                stmt.setString(
                    7,
                    statsJson.encodeToString(
                        ListSerializer(com.folio.reader.model.ReadingSession.serializer()),
                        stats.sessions
                    )
                )
                stmt.executeUpdate()
            }
        }
    }

    override fun getDailyStats(deviceId: String, fromDate: LocalDate): Flow<List<DailyStatistics>> =
        flow { emit(loadDailyStats(deviceId, fromDate)) }

    private suspend fun loadDailyStats(deviceId: String, fromDate: LocalDate): List<DailyStatistics> {
        return db.withConnection { conn ->
            val out = mutableListOf<DailyStatistics>()
            conn.prepareStatement(
                "SELECT * FROM daily_statistics WHERE device_id = ? AND date >= ? ORDER BY date"
            ).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.setString(2, fromDate.toString())
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(
                            DailyStatistics(
                                date = LocalDate.parse(rs.getString("date")),
                                readingTimeMs = rs.getLong("reading_time_ms"),
                                wordsRead = rs.getLong("words_read"),
                                sessionCount = rs.getInt("session_count"),
                                booksRead = rs.getInt("books_read"),
                                sessions = runCatching {
                                    statsJson.decodeFromString(
                                        ListSerializer(com.folio.reader.model.ReadingSession.serializer()),
                                        rs.getString("sessions_json") ?: "[]"
                                    )
                                }.getOrDefault(emptyList())
                            )
                        )
                    }
                }
            }
            out
        }
    }

    override fun getHeatmapData(deviceId: String, year: Int): Flow<List<HeatmapDay>> = flow {
        val stats = loadDailyStats(deviceId, LocalDate(year, 1, 1))
        emit(
            stats.map { day ->
                HeatmapDay(
                    date = day.date,
                    intensity = when {
                        day.readingTimeMs <= 0 -> 0
                        day.readingTimeMs < 15 * 60_000L -> 1
                        day.readingTimeMs < 30 * 60_000L -> 2
                        day.readingTimeMs < 60 * 60_000L -> 3
                        else -> 4
                    },
                    readingTimeMs = day.readingTimeMs,
                    wordsRead = day.wordsRead,
                    sessionCount = day.sessionCount,
                    booksRead = day.booksRead
                )
            }
        )
    }

    private fun setNullableLong(stmt: java.sql.PreparedStatement, index: Int, value: Instant?) {
        if (value != null) stmt.setLong(index, value.toEpochMilliseconds()) else stmt.setNull(index, java.sql.Types.BIGINT)
    }

    private fun setNullableLong(stmt: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value != null) stmt.setLong(index, value) else stmt.setNull(index, java.sql.Types.BIGINT)
    }

    private fun getNullableInstant(rs: java.sql.ResultSet, column: String): Instant? =
        getNullableLong(rs, column)?.let { Instant.fromEpochMilliseconds(it) }

    private fun getNullableLong(rs: java.sql.ResultSet, column: String): Long? {
        val value = rs.getLong(column)
        return if (rs.wasNull()) null else value
    }

    companion object {
        /** Aggregation bucket used when a single-device view is enough. */
        const val DEFAULT_DEVICE = "all"
    }
}
