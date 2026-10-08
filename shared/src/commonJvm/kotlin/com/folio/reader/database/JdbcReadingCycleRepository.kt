package com.folio.reader.database

import com.folio.reader.model.ReadingCycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

class JdbcReadingCycleRepository(private val db: Database) : ReadingCycleRepository {

    override suspend fun insertCycle(cycle: ReadingCycle): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reading_cycles
                (id, book_id, cycle_number, started_at, finished_at, total_duration_ms, session_count, final_progress)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, cycle.id)
                stmt.setString(2, cycle.bookId)
                stmt.setInt(3, cycle.cycleNumber)
                stmt.setLong(4, cycle.startedAt.toEpochMilliseconds())
                if (cycle.finishedAt == null) stmt.setNull(5, java.sql.Types.INTEGER) else stmt.setLong(5, cycle.finishedAt.toEpochMilliseconds())
                stmt.setLong(6, cycle.totalDurationMs)
                stmt.setInt(7, cycle.sessionCount)
                stmt.setDouble(8, cycle.finalProgress)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun updateCycle(cycle: ReadingCycle): Unit = insertCycle(cycle)

    override suspend fun getCurrentCycle(bookId: String): ReadingCycle? = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM reading_cycles WHERE book_id = ? AND finished_at IS NULL ORDER BY cycle_number DESC LIMIT 1"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getCyclesForBook(bookId: String): List<ReadingCycle> = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            val out = mutableListOf<ReadingCycle>()
            conn.prepareStatement("SELECT * FROM reading_cycles WHERE book_id = ? ORDER BY cycle_number ASC").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapRow(rs)) }
            }
            out
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): ReadingCycle = ReadingCycle(
        id = rs.getString("id"),
        bookId = rs.getString("book_id"),
        cycleNumber = rs.getInt("cycle_number"),
        startedAt = Instant.fromEpochMilliseconds(rs.getLong("started_at")),
        finishedAt = rs.getLong("finished_at").takeIf { !rs.wasNull() }?.let { Instant.fromEpochMilliseconds(it) },
        totalDurationMs = rs.getLong("total_duration_ms"),
        sessionCount = rs.getInt("session_count"),
        finalProgress = rs.getDouble("final_progress")
    )
}
