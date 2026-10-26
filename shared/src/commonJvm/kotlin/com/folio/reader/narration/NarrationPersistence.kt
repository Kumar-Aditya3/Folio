package com.folio.reader.narration

import com.folio.reader.database.Database
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import kotlinx.serialization.json.Json

/** One durable boundary for presented source, book resume and the cumulative session row. */
class NarrationPersistence(private val db: Database) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun latest(): NarrationCheckpoint? = db.withConnection { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(
            "SELECT payload FROM narration_checkpoints ORDER BY rowid DESC LIMIT 1").use { rows ->
            if (rows.next()) runCatching { json.decodeFromString<NarrationCheckpoint>(rows.getString(1)) }.getOrNull() else null
        } }
    }
    suspend fun load(bookId: String): NarrationCheckpoint? = db.withConnection { connection ->
        connection.prepareStatement("SELECT payload FROM narration_checkpoints WHERE book_id = ?").use { statement ->
            statement.setString(1, bookId); statement.executeQuery().use { rows ->
                if (rows.next()) runCatching { json.decodeFromString<NarrationCheckpoint>(rows.getString(1)) }.getOrNull() else null
            }
        }
    }
    suspend fun save(checkpoint: NarrationCheckpoint, session: ReadingSession?, sync: Boolean) {
        db.withConnection { connection ->
            connection.autoCommit = false
            try {
                val p = checkpoint.position
                // A book deleted while a worker was synthesizing must never be resurrected.
                val exists = connection.prepareStatement("SELECT 1 FROM books WHERE id = ?").use { s ->
                    s.setString(1, p.bookId); s.executeQuery().use { it.next() }
                }
                check(exists) { "This book is no longer available." }
                connection.prepareStatement("INSERT OR REPLACE INTO narration_checkpoints(book_id,payload) VALUES (?,?)").use { s ->
                    s.setString(1, p.bookId); s.setString(2, json.encodeToString(NarrationCheckpoint.serializer(), checkpoint)); s.executeUpdate()
                }
                connection.prepareStatement("""INSERT OR REPLACE INTO reading_positions
                    (book_id,device_id,chapter_id,spine_index,content_locator,character_offset,paragraph_index,
                    normalized_progress,chapter_progress,scroll_offset,updated_at,previous_position)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL)""").use { s ->
                    s.setString(1,p.bookId); s.setString(2,p.deviceId); s.setString(3,p.chapterId); s.setInt(4,p.spineIndex)
                    s.setString(5,p.contentLocator); s.setInt(6,p.characterOffset); s.setInt(7,p.paragraphIndex)
                    s.setDouble(8,p.normalizedProgress); s.setDouble(9,p.chapterProgress); s.setDouble(10,p.scrollOffset)
                    s.setLong(11,p.updatedAt.toEpochMilliseconds()); s.executeUpdate()
                }
                connection.prepareStatement("UPDATE books SET normalized_progress = ?, status = CASE WHEN status = 0 AND ? > 0 THEN 1 ELSE status END WHERE id = ?").use { s ->
                    s.setDouble(1,p.normalizedProgress); s.setDouble(2,p.normalizedProgress); s.setString(3,p.bookId); s.executeUpdate()
                }
                if (session != null && session.durationMs > 0) connection.prepareStatement("""INSERT OR REPLACE INTO reading_sessions
                    (id,book_id,cycle_id,device_id,started_at,ended_at,duration_ms,start_position,end_position,
                    start_progress,end_progress,words_read,is_active,activity_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?)""").use { s ->
                    s.setString(1,session.id); s.setString(2,session.bookId); s.setString(3,session.cycleId); s.setString(4,session.deviceId)
                    s.setLong(5,session.startedAt.toEpochMilliseconds()); s.setLong(6,session.endedAt?.toEpochMilliseconds() ?: 0)
                    s.setLong(7,session.durationMs); s.setString(8,json.encodeToString(ReadingPosition.serializer(),session.startPosition))
                    s.setString(9,session.endPosition?.let { json.encodeToString(ReadingPosition.serializer(),it) })
                    s.setDouble(10,session.startProgress); s.setDouble(11,session.endProgress); s.setLong(12,session.wordsRead)
                    s.setString(13,session.activity?.let { json.encodeToString(SessionActivity.serializer(),it) }); s.executeUpdate()
                }
                if (session?.cycleId != null) {
                    val sittings = connection.prepareStatement("SELECT id,activity_json FROM reading_sessions WHERE cycle_id = ?").use { s ->
                        s.setString(1,session.cycleId); s.executeQuery().use { rows -> buildSet {
                            while (rows.next()) add(rows.getString(2)?.let { raw -> runCatching {
                                json.decodeFromString<SessionActivity>(raw).sittingId }.getOrNull() } ?: rows.getString(1))
                        }.size }
                    }
                    connection.prepareStatement("""UPDATE reading_cycles SET
                        total_duration_ms = (SELECT COALESCE(SUM(duration_ms),0) FROM reading_sessions WHERE cycle_id = ?),
                        session_count = ?, final_progress = ? WHERE id = ?""").use { s ->
                        s.setString(1,session.cycleId); s.setInt(2,sittings)
                        s.setDouble(3,p.normalizedProgress); s.setString(4,session.cycleId); s.executeUpdate()
                    }
                }
                if (checkpoint.bookCompleted) {
                    connection.prepareStatement("UPDATE books SET status = 3, normalized_progress = 1 WHERE id = ?").use { s ->
                        s.setString(1,p.bookId); s.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE reading_cycles SET finished_at = ?, final_progress = 1 WHERE book_id = ? AND finished_at IS NULL").use { s ->
                        s.setLong(1,p.updatedAt.toEpochMilliseconds()); s.setString(2,p.bookId); s.executeUpdate()
                    }
                }
                connection.commit()
            } catch (error: Exception) { connection.rollback(); throw error }
            finally { connection.autoCommit = true }
        }
        db.bumpProgress(); db.bumpSessionData(); db.bumpBookData()
        if (sync) {
            db.getBook(checkpoint.position.bookId)?.let { book ->
                db.onEntityChanged?.invoke("book",book.id,"UPSERT",json.encodeToString(com.folio.reader.model.Book.serializer(),book))
            }
            db.onEntityChanged?.invoke("position", "${checkpoint.position.bookId}_${checkpoint.position.deviceId}", "UPSERT", json.encodeToString(ReadingPosition.serializer(),checkpoint.position))
            if (session != null && session.durationMs > 0) db.onEntityChanged?.invoke("session",session.id,"UPSERT",json.encodeToString(ReadingSession.serializer(),session))
        }
    }
}
