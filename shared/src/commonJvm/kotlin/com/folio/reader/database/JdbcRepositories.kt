package com.folio.reader.database

import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.NoteType
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.sql.SQLException
import java.sql.Connection
import java.sql.ResultSet

private val repoJson = Json { ignoreUnknownKeys = true }

// ---------- Books ----------

class JdbcBookRepository(private val db: Database) : BookRepository {
    override suspend fun insertBook(book: Book, emitSyncEvent: Boolean) {
        db.insertBook(book)
        if (emitSyncEvent) db.onEntityChanged?.invoke("book", book.id, "UPSERT", repoJson.encodeToString(Book.serializer(), book))
    }
    override suspend fun updateBook(book: Book, emitSyncEvent: Boolean) {
        db.updateBook(book)
        if (emitSyncEvent) db.onEntityChanged?.invoke("book", book.id, "UPSERT", repoJson.encodeToString(Book.serializer(), book))
    }
    override suspend fun deleteBook(bookId: String) {
        db.deleteBook(bookId)
        db.onEntityChanged?.invoke("book", bookId, "DELETE", "{}")
    }
    override suspend fun getBook(bookId: String): Book? = db.getBook(bookId)
    override suspend fun getBookByEpubHash(hash: String): Book? = db.getBookByEpubHash(hash)
    override suspend fun getBookByIsbn(isbn: String): Book? = db.getBookByIsbn(isbn)
    override suspend fun markOpened(bookId: String) {
        db.markOpened(bookId)
        db.getBook(bookId)?.let { book ->
            db.onEntityChanged?.invoke("book", book.id, "UPSERT", repoJson.encodeToString(Book.serializer(), book))
        }
    }

    override suspend fun setBookStatus(bookId: String, status: com.folio.reader.model.BookStatus) {
        db.setBookStatus(bookId, status)
        db.getBook(bookId)?.let { book ->
            db.onEntityChanged?.invoke("book", book.id, "UPSERT", repoJson.encodeToString(Book.serializer(), book))
        }
    }

    override suspend fun setCloudState(bookId: String, cloudState: CloudState) {
        db.setCloudState(bookId, cloudState)
        db.getBook(bookId)?.let { book ->
            db.onEntityChanged?.invoke("book", book.id, "UPSERT", repoJson.encodeToString(Book.serializer(), book))
        }
    }

    override suspend fun updateNormalizedProgress(bookId: String, progress: Double) {
        db.updateNormalizedProgress(bookId, progress)
    }

    override fun getAllBooks(): Flow<List<Book>> = flow { emit(db.getAllBooks()) }

    override fun getBooksByStatus(status: BookStatus): Flow<List<Book>> =
        flow { emit(db.getAllBooks().filter { it.status == status }) }

    override fun getBooksBySeries(seriesId: String): Flow<List<Book>> =
        flow { emit(db.getAllBooks().filter { it.seriesId == seriesId }) }

    override fun getBooksByCollection(collectionId: String): Flow<List<Book>> = flow {
        val ids = db.withConnection { conn ->
            conn.prepareStatement("SELECT book_id FROM book_collections WHERE collection_id = ?").use { stmt ->
                stmt.setString(1, collectionId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
        }.toSet()
        emit(db.getAllBooks().filter { it.id in ids })
    }

    override fun getCurrentlyReading(): Flow<List<Book>> =
        flow { emit(db.getAllBooks().filter { it.status == BookStatus.READING || it.status == BookStatus.PAUSED }) }

    override fun getFinishedBooks(): Flow<List<Book>> =
        flow { emit(db.getAllBooks().filter { it.status == BookStatus.FINISHED }) }

    override fun getUnreadBooks(): Flow<List<Book>> =
        flow { emit(db.getAllBooks().filter { it.status == BookStatus.UNREAD }) }

    override fun searchBooks(query: String): Flow<List<Book>> = flow {
        val q = query.trim()
        emit(
            if (q.isEmpty()) emptyList()
            else db.getAllBooks().filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.displayAuthor.contains(q, ignoreCase = true) ||
                    (it.subtitle?.contains(q, ignoreCase = true) ?: false)
            }
        )
    }

    override suspend fun insertChapters(bookId: String, chapters: List<Chapter>) {
        // Use withTransaction for atomic DELETE + batch INSERT
        db.withTransaction { conn ->
            conn.prepareStatement("DELETE FROM chapters WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO chapters
                    (id, book_id, title, href, spine_index, level, character_count, word_count, start_offset, end_offset)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                for (c in chapters) {
                    stmt.setString(1, c.id)
                    stmt.setString(2, bookId)
                    stmt.setString(3, c.title)
                    stmt.setString(4, c.href)
                    stmt.setInt(5, c.spineIndex)
                    stmt.setInt(6, c.level)
                    stmt.setLong(7, c.characterCount)
                    stmt.setLong(8, c.wordCount)
                    stmt.setLong(9, c.startOffset)
                    stmt.setLong(10, c.endOffset)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    override suspend fun getChaptersForBook(bookId: String): List<Chapter> {
        return db.withConnection { conn ->
            val chapters = mutableListOf<Chapter>()
            conn.prepareStatement(
                "SELECT * FROM chapters WHERE book_id = ? ORDER BY spine_index"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        chapters.add(
                            Chapter(
                                id = rs.getString("id"),
                                bookId = rs.getString("book_id"),
                                title = rs.getString("title"),
                                href = rs.getString("href"),
                                spineIndex = rs.getInt("spine_index"),
                                level = rs.getInt("level"),
                                characterCount = rs.getLong("character_count"),
                                wordCount = rs.getLong("word_count"),
                                startOffset = rs.getLong("start_offset"),
                                endOffset = rs.getLong("end_offset")
                            )
                        )
                    }
                }
            }
            chapters
        }
    }
}

// ---------- Positions ----------

class JdbcReadingPositionRepository(private val db: Database) : ReadingPositionRepository {
    override suspend fun upsertPosition(position: ReadingPosition, emitSyncEvent: Boolean) {
        db.upsertPosition(position)
        if (emitSyncEvent) db.onEntityChanged?.invoke("position", "${position.bookId}_${position.deviceId}", "UPSERT", repoJson.encodeToString(ReadingPosition.serializer(), position))
    }
    override suspend fun getPosition(bookId: String, deviceId: String): ReadingPosition? =
        db.getPosition(bookId, deviceId)
    override suspend fun getLatestPositionAcrossDevices(bookId: String): ReadingPosition? =
        db.getLatestPositionAcrossDevices(bookId)
    override suspend fun getAllPositionsForBook(bookId: String): List<ReadingPosition> =
        db.getAllPositionsForBook(bookId)
    override suspend fun deletePosition(bookId: String, deviceId: String) {
        db.deletePosition(bookId, deviceId)
        db.onEntityChanged?.invoke("position", "${bookId}_${deviceId}", "DELETE", "{}")
    }
    override fun observePosition(bookId: String, deviceId: String): Flow<ReadingPosition?> =
        flow { emit(db.getPosition(bookId, deviceId)) }
}

// ---------- Sessions ----------

class JdbcReadingSessionRepository(private val db: Database) : ReadingSessionRepository {

    private fun mapRow(rs: ResultSet): ReadingSession = ReadingSession(
        id = rs.getString("id"),
        bookId = rs.getString("book_id"),
        cycleId = rs.getString("cycle_id")?.takeIf { it.isNotBlank() },
        deviceId = rs.getString("device_id"),
        startedAt = Instant.fromEpochMilliseconds(rs.getLong("started_at")),
        endedAt = rs.getLong("ended_at").takeIf { !rs.wasNull() && it > 0 }?.let { Instant.fromEpochMilliseconds(it) },
        durationMs = rs.getLong("duration_ms"),
        startPosition = decodePosition(rs.getString("start_position")),
        endPosition = rs.getString("end_position")?.takeIf { it.isNotBlank() }?.let { decodePosition(it) },
        startProgress = rs.getDouble("start_progress"),
        endProgress = rs.getDouble("end_progress"),
        wordsRead = rs.getLong("words_read"),
        isActive = rs.getInt("is_active") == 1
    )

    private fun decodePosition(jsonText: String): ReadingPosition =
        runCatching { repoJson.decodeFromString(ReadingPosition.serializer(), jsonText) }
            .getOrElse { ReadingPosition(bookId = "", deviceId = "", chapterId = "", spineIndex = 0, contentLocator = "") }

    override suspend fun insertSession(session: ReadingSession, emitSyncEvent: Boolean) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reading_sessions (id, book_id, cycle_id, device_id, started_at, ended_at,
                    duration_ms, start_position, end_position, start_progress, end_progress, words_read, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, session.id)
                stmt.setString(2, session.bookId)
                stmt.setString(3, session.cycleId)
                stmt.setString(4, session.deviceId)
                stmt.setLong(5, session.startedAt.toEpochMilliseconds())
                stmt.setLong(6, session.endedAt?.toEpochMilliseconds() ?: 0L)
                stmt.setLong(7, session.durationMs)
                stmt.setString(8, repoJson.encodeToString(ReadingPosition.serializer(), session.startPosition))
                stmt.setString(9, session.endPosition?.let { repoJson.encodeToString(ReadingPosition.serializer(), it) })
                stmt.setDouble(10, session.startProgress)
                stmt.setDouble(11, session.endProgress)
                stmt.setLong(12, session.wordsRead)
                stmt.setInt(13, if (session.isActive) 1 else 0)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.onEntityChanged?.invoke("session", session.id, "UPSERT", repoJson.encodeToString(ReadingSession.serializer(), session))
    }

    override suspend fun updateSession(session: ReadingSession, emitSyncEvent: Boolean) = insertSession(session, emitSyncEvent)

    override suspend fun getActiveSession(bookId: String): ReadingSession? {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM reading_sessions WHERE book_id = ? AND is_active = 1 ORDER BY started_at DESC LIMIT 1"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getSessionsForDateRange(start: Instant, end: Instant): List<ReadingSession> {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM reading_sessions WHERE started_at >= ? AND started_at < ? ORDER BY started_at"
            ).use { stmt ->
                stmt.setLong(1, start.toEpochMilliseconds())
                stmt.setLong(2, end.toEpochMilliseconds())
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<ReadingSession>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        }
    }

    override suspend fun getSessionsByDevice(deviceId: String): List<ReadingSession> {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM reading_sessions WHERE device_id = ? ORDER BY started_at").use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<ReadingSession>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        }
    }

    override fun observeSessionsSince(from: Instant): Flow<List<ReadingSession>> = flow {
        emit(
            db.withConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM reading_sessions WHERE started_at >= ? ORDER BY started_at"
                ).use { stmt ->
                    stmt.setLong(1, from.toEpochMilliseconds())
                    stmt.executeQuery().use { rs ->
                        val out = mutableListOf<ReadingSession>()
                        while (rs.next()) out.add(mapRow(rs))
                        out
                    }
                }
            }
        )
    }

    override suspend fun getSessionsForBook(bookId: String): Flow<List<ReadingSession>> = flow {
        emit(
            db.withConnection { conn ->
                conn.prepareStatement("SELECT * FROM reading_sessions WHERE book_id = ? ORDER BY started_at").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeQuery().use { rs ->
                        val out = mutableListOf<ReadingSession>()
                        while (rs.next()) out.add(mapRow(rs))
                        out
                    }
                }
            }
        )
    }
}

// ---------- Highlights / Notes / Bookmarks ----------

class JdbcHighlightRepository(private val db: Database) : HighlightRepository {
    override suspend fun insertHighlight(highlight: Highlight, emitSyncEvent: Boolean) {
        db.insertHighlight(highlight)
        if (emitSyncEvent) db.onEntityChanged?.invoke("highlight", highlight.id, "UPSERT", repoJson.encodeToString(Highlight.serializer(), highlight))
    }
    override suspend fun updateHighlight(highlight: Highlight, emitSyncEvent: Boolean) = insertHighlight(highlight, emitSyncEvent)

    override suspend fun deleteHighlight(highlightId: String, emitSyncEvent: Boolean) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE highlights SET is_deleted = 1, deleted_at = ?, updated_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, now)
                stmt.setLong(2, now)
                stmt.setString(3, highlightId)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.getHighlight(highlightId)?.let { highlight ->
            db.onEntityChanged?.invoke("highlight", highlight.id, "UPSERT", repoJson.encodeToString(Highlight.serializer(), highlight))
        }
    }

    override suspend fun restoreHighlight(highlightId: String) {
        db.restoreHighlight(highlightId)
        db.getHighlight(highlightId)?.let { highlight ->
            db.onEntityChanged?.invoke("highlight", highlight.id, "UPSERT", repoJson.encodeToString(Highlight.serializer(), highlight))
        }
    }

    private fun queryList(conn: Connection, sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<Highlight> {
        return conn.prepareStatement(sql).use { stmt ->
            bind(stmt)
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<Highlight>()
                while (rs.next()) out.add(db.mapRowToHighlight(rs))
                out
            }
        }
    }

    override fun getHighlightsForBook(bookId: String): Flow<List<Highlight>> = flow {
        emit(db.withConnection { conn ->
            queryList(conn, "SELECT * FROM highlights WHERE book_id = ? AND is_deleted = 0 ORDER BY created_at") {
                it.setString(1, bookId)
            }
        })
    }

    override fun getHighlightsForChapter(bookId: String, chapterId: String): Flow<List<Highlight>> = flow {
        emit(db.withConnection { conn ->
            queryList(conn, "SELECT * FROM highlights WHERE book_id = ? AND chapter_id = ? AND is_deleted = 0 ORDER BY created_at") {
                it.setString(1, bookId)
                it.setString(2, chapterId)
            }
        })
    }

    override suspend fun getHighlight(highlightId: String): Highlight? {
        return db.withConnection { conn ->
            queryList(conn, "SELECT * FROM highlights WHERE id = ?") { it.setString(1, highlightId) }
        }.firstOrNull()
    }

    override suspend fun getDeletedHighlights(bookId: String): List<Highlight> {
        return db.withConnection { conn ->
            queryList(conn, "SELECT * FROM highlights WHERE book_id = ? AND is_deleted = 1") { it.setString(1, bookId) }
        }
    }
}

class JdbcNoteRepository(private val db: Database) : NoteRepository {

    private fun mapRow(rs: ResultSet): Note = Note(
        id = rs.getString("id"),
        bookId = rs.getString("book_id"),
        chapterId = rs.getString("chapter_id")?.takeIf { it.isNotBlank() },
        spineIndex = rs.getInt("spine_index").takeIf { !rs.wasNull() },
        locator = rs.getString("locator")?.takeIf { it.isNotBlank() },
        content = rs.getString("content"),
        type = NoteType.fromValue(rs.getInt("type")),
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        updatedAt = Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
        deviceId = rs.getString("device_id"),
        isDeleted = rs.getInt("is_deleted") == 1,
        deletedAt = rs.getLong("deleted_at").takeIf { !rs.wasNull() && it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
    )

    private fun write(conn: Connection, note: Note) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO notes (id, book_id, chapter_id, spine_index, locator, content, type,
                created_at, updated_at, device_id, is_deleted, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, note.id)
            stmt.setString(2, note.bookId)
            stmt.setString(3, note.chapterId)
            stmt.setInt(4, note.spineIndex ?: -1)
            stmt.setString(5, note.locator)
            stmt.setString(6, note.content)
            stmt.setInt(7, note.type.value)
            stmt.setLong(8, note.createdAt.toEpochMilliseconds())
            stmt.setLong(9, note.updatedAt.toEpochMilliseconds())
            stmt.setString(10, note.deviceId)
            stmt.setInt(11, if (note.isDeleted) 1 else 0)
            stmt.setLong(12, note.deletedAt?.toEpochMilliseconds() ?: 0L)
            stmt.executeUpdate()
        }
    }

    override suspend fun insertNote(note: Note, emitSyncEvent: Boolean) {
        db.withConnection { write(it, note) }
        if (emitSyncEvent) db.onEntityChanged?.invoke("note", note.id, "UPSERT", repoJson.encodeToString(Note.serializer(), note))
    }
    override suspend fun updateNote(note: Note, emitSyncEvent: Boolean) {
        db.withConnection { write(it, note) }
        if (emitSyncEvent) db.onEntityChanged?.invoke("note", note.id, "UPSERT", repoJson.encodeToString(Note.serializer(), note))
    }

    override suspend fun deleteNote(noteId: String, emitSyncEvent: Boolean) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE notes SET is_deleted = 1, deleted_at = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, now)
                stmt.setLong(2, now)
                stmt.setString(3, noteId)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) getNote(noteId)?.let { note ->
            db.onEntityChanged?.invoke("note", note.id, "UPSERT", repoJson.encodeToString(Note.serializer(), note))
        }
    }

    override suspend fun restoreNote(noteId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE notes SET is_deleted = 0, deleted_at = NULL WHERE id = ?").use { stmt ->
                stmt.setString(1, noteId)
                stmt.executeUpdate()
            }
        }
        getNote(noteId)?.let { note ->
            db.onEntityChanged?.invoke("note", note.id, "UPSERT", repoJson.encodeToString(Note.serializer(), note))
        }
    }

    override fun getNotesForBook(bookId: String): Flow<List<Note>> = flow {
        emit(db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM notes WHERE book_id = ? AND is_deleted = 0 ORDER BY created_at").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Note>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        })
    }

    override suspend fun getNote(noteId: String): Note? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM notes WHERE id = ?").use { stmt ->
                stmt.setString(1, noteId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getDeletedNotes(bookId: String): List<Note> {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM notes WHERE book_id = ? AND is_deleted = 1").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Note>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        }
    }
}

class JdbcBookmarkRepository(private val db: Database) : BookmarkRepository {

    private fun mapRow(rs: ResultSet): Bookmark = Bookmark(
        id = rs.getString("id"),
        bookId = rs.getString("book_id"),
        chapterId = rs.getString("chapter_id"),
        spineIndex = rs.getInt("spine_index"),
        locator = rs.getString("locator"),
        label = rs.getString("label")?.takeIf { it.isNotBlank() },
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        updatedAt = Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
        deviceId = rs.getString("device_id"),
        isDeleted = rs.getInt("is_deleted") == 1,
        deletedAt = rs.getLong("deleted_at").takeIf { !rs.wasNull() && it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
    )

    private fun write(conn: Connection, bookmark: Bookmark) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO bookmarks (id, book_id, chapter_id, spine_index, locator, label,
                created_at, updated_at, device_id, is_deleted, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, bookmark.id)
            stmt.setString(2, bookmark.bookId)
            stmt.setString(3, bookmark.chapterId)
            stmt.setInt(4, bookmark.spineIndex)
            stmt.setString(5, bookmark.locator)
            stmt.setString(6, bookmark.label)
            stmt.setLong(7, bookmark.createdAt.toEpochMilliseconds())
            stmt.setLong(8, bookmark.updatedAt.toEpochMilliseconds())
            stmt.setString(9, bookmark.deviceId)
            stmt.setInt(10, if (bookmark.isDeleted) 1 else 0)
            stmt.setLong(11, bookmark.deletedAt?.toEpochMilliseconds() ?: 0L)
            stmt.executeUpdate()
        }
    }

    override suspend fun insertBookmark(bookmark: Bookmark, emitSyncEvent: Boolean) {
        db.withConnection { write(it, bookmark) }
        if (emitSyncEvent) db.onEntityChanged?.invoke("bookmark", bookmark.id, "UPSERT", repoJson.encodeToString(Bookmark.serializer(), bookmark))
    }
    override suspend fun updateBookmark(bookmark: Bookmark, emitSyncEvent: Boolean) {
        db.withConnection { write(it, bookmark) }
        if (emitSyncEvent) db.onEntityChanged?.invoke("bookmark", bookmark.id, "UPSERT", repoJson.encodeToString(Bookmark.serializer(), bookmark))
    }

    override suspend fun deleteBookmark(bookmarkId: String, emitSyncEvent: Boolean) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE bookmarks SET is_deleted = 1, deleted_at = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, now)
                stmt.setLong(2, now)
                stmt.setString(3, bookmarkId)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) getBookmark(bookmarkId)?.let { bookmark ->
            db.onEntityChanged?.invoke("bookmark", bookmark.id, "UPSERT", repoJson.encodeToString(Bookmark.serializer(), bookmark))
        }
    }

    override suspend fun restoreBookmark(bookmarkId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE bookmarks SET is_deleted = 0, deleted_at = NULL WHERE id = ?").use { stmt ->
                stmt.setString(1, bookmarkId)
                stmt.executeUpdate()
            }
        }
        getBookmark(bookmarkId)?.let { bookmark ->
            db.onEntityChanged?.invoke("bookmark", bookmark.id, "UPSERT", repoJson.encodeToString(Bookmark.serializer(), bookmark))
        }
    }

    override fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>> = flow {
        emit(db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM bookmarks WHERE book_id = ? AND is_deleted = 0 ORDER BY created_at").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Bookmark>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        })
    }

    override suspend fun getBookmark(bookmarkId: String): Bookmark? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM bookmarks WHERE id = ?").use { stmt ->
                stmt.setString(1, bookmarkId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getDeletedBookmarks(bookId: String): List<Bookmark> {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM bookmarks WHERE book_id = ? AND is_deleted = 1").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Bookmark>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        }
    }
}

// ---------- Collections & Series ----------

class JdbcCollectionRepository(private val db: Database) : CollectionRepository {

    private fun mapRow(rs: ResultSet) = Collection(
        id = rs.getString("id"),
        name = rs.getString("name"),
        color = rs.getInt("color").takeIf { !rs.wasNull() },
        sortOrder = rs.getInt("sort_order"),
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at"))
    )

    override suspend fun insertCollection(collection: Collection, emitSyncEvent: Boolean) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO collections (id, name, color, sort_order, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, collection.id)
                stmt.setString(2, collection.name)
                stmt.setInt(3, collection.color ?: -1)
                stmt.setInt(4, collection.sortOrder)
                stmt.setLong(5, collection.createdAt.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.onEntityChanged?.invoke("collection", collection.id, "UPSERT", repoJson.encodeToString(Collection.serializer(), collection))
    }

    override suspend fun updateCollection(collection: Collection, emitSyncEvent: Boolean) = insertCollection(collection, emitSyncEvent)

    override suspend fun deleteCollection(collectionId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM collections WHERE id = ?").use { stmt ->
                stmt.setString(1, collectionId); stmt.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM book_collections WHERE collection_id = ?").use { stmt ->
                stmt.setString(1, collectionId); stmt.executeUpdate()
            }
        }
        db.onEntityChanged?.invoke("collection", collectionId, "DELETE", "{}")
    }

    override fun getAllCollections(): Flow<List<Collection>> = flow {
        emit(db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM collections ORDER BY sort_order, name").use { rs ->
                    val out = mutableListOf<Collection>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        })
    }

    override suspend fun getCollectionByName(name: String): Collection? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM collections WHERE name = ? COLLATE NOCASE").use { stmt ->
                stmt.setString(1, name.trim())
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getCollectionsForBook(bookId: String): List<Collection> {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT c.* FROM collections c JOIN book_collections bc ON bc.collection_id = c.id WHERE bc.book_id = ? ORDER BY c.sort_order"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<Collection>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        }
    }

    override suspend fun addBookToCollection(bookId: String, collectionId: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO book_collections (book_id, collection_id, added_at) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, collectionId)
                stmt.setLong(3, Clock.System.now().toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun removeBookFromCollection(bookId: String, collectionId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM book_collections WHERE book_id = ? AND collection_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, collectionId)
                stmt.executeUpdate()
            }
        }
    }
}

class JdbcSeriesRepository(private val db: Database) : SeriesRepository {

    private fun mapRow(rs: ResultSet) = Series(
        id = rs.getString("id"),
        name = rs.getString("name"),
        sortOrder = rs.getInt("sort_order")
    )

    override suspend fun insertSeries(series: Series, emitSyncEvent: Boolean) {
        db.withConnection { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO series (id, name, sort_order) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, series.id)
                stmt.setString(2, series.name)
                stmt.setInt(3, series.sortOrder)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.onEntityChanged?.invoke("series", series.id, "UPSERT", repoJson.encodeToString(Series.serializer(), series))
    }

    override suspend fun updateSeries(series: Series, emitSyncEvent: Boolean) = insertSeries(series, emitSyncEvent)

    override suspend fun deleteSeries(seriesId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM series WHERE id = ?").use { stmt ->
                stmt.setString(1, seriesId); stmt.executeUpdate()
            }
        }
        db.onEntityChanged?.invoke("series", seriesId, "DELETE", "{}")
    }

    override fun getAllSeries(): Flow<List<Series>> = flow {
        emit(db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM series ORDER BY sort_order, name").use { rs ->
                    val out = mutableListOf<Series>()
                    while (rs.next()) out.add(mapRow(rs))
                    out
                }
            }
        })
    }

    override suspend fun getSeries(seriesId: String): Series? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM series WHERE id = ?").use { stmt ->
                stmt.setString(1, seriesId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getSeriesByName(name: String): Series? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM series WHERE name = ? COLLATE NOCASE").use { stmt ->
                stmt.setString(1, name.trim())
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override suspend fun getBooksInSeries(seriesId: String): Flow<List<Book>> = flow {
        emit(db.getAllBooks().filter { it.seriesId == seriesId }.sortedBy { it.seriesNumber ?: Double.MAX_VALUE })
    }
}

// ---------- Search ----------

class JdbcLikeSearchRepository(private val db: Database) : SearchRepository {

    override suspend fun indexChapter(bookId: String, chapterId: String, spineIndex: Int, title: String, content: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO search_index (book_id, chapter_id, spine_index, title, content) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setInt(3, spineIndex)
                stmt.setString(4, title)
                stmt.setString(5, content)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun indexChaptersBulk(bookId: String, chapters: List<com.folio.reader.database.ChapterIndexEntry>) {
        if (chapters.isEmpty()) return
        // Use withTransaction for atomic batch insert
        db.withTransaction { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO search_index (book_id, chapter_id, spine_index, title, content) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                for (c in chapters) {
                    stmt.setString(1, bookId)
                    stmt.setString(2, c.chapterId)
                    stmt.setInt(3, c.spineIndex)
                    stmt.setString(4, c.title)
                    stmt.setString(5, c.content)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    override suspend fun deleteIndexForBook(bookId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM search_index WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId); stmt.executeUpdate()
            }
        }
    }

    private fun query(conn: Connection, sql: String, term: String?): List<SearchResult> {
        return conn.prepareStatement(sql).use { stmt ->
            if (term != null) {
                stmt.setString(1, "%$term%")
                stmt.setString(2, "%$term%")
            }
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<SearchResult>()
                while (rs.next()) {
                    val content = rs.getString("content")
                    val idx = term?.let { content.indexOf(it, ignoreCase = true) } ?: 0
                    val start = maxOf(0, idx - 40)
                    val snippet = if (idx >= 0 && term != null) content.substring(start, minOf(content.length, idx + term.length + 40)) else ""
                    out.add(
                        SearchResult(
                            bookId = rs.getString("book_id"),
                            chapterId = rs.getString("chapter_id"),
                            spineIndex = rs.getInt("spine_index"),
                            title = rs.getString("title"),
                            context = snippet
                        )
                    )
                }
                out
            }
        }
    }

    override fun search(queryText: String): Flow<List<SearchResult>> = flow {
        emit(db.withConnection { conn ->
            query(conn, "SELECT * FROM search_index WHERE content LIKE ? OR title LIKE ?", queryText.ifBlank { null })
        })
    }

    override fun searchInBook(bookId: String, queryText: String): Flow<List<SearchResult>> = flow {
        emit(db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM search_index WHERE book_id = ? AND (content LIKE ? OR title LIKE ?)").use { outer ->
                outer.setString(1, bookId)
                outer.setString(2, "%$queryText%")
                outer.setString(3, "%$queryText%")
                outer.executeQuery().use { rs ->
                    val out = mutableListOf<SearchResult>()
                    while (rs.next()) {
                        val content = rs.getString("content")
                        val idx = content.indexOf(queryText, ignoreCase = true)
                        val start = maxOf(0, idx - 40)
                        val snippet = if (idx >= 0) content.substring(start, minOf(content.length, idx + queryText.length + 40)) else ""
                        out.add(
                            SearchResult(
                                bookId = bookId,
                                chapterId = rs.getString("chapter_id"),
                                spineIndex = rs.getInt("spine_index"),
                                title = rs.getString("title"),
                                context = snippet
                            )
                        )
                    }
                    out
                }
            }
        })
    }
}

class JdbcFtsSearchRepository(private val db: Database) : SearchRepository {

    override suspend fun indexChapter(bookId: String, chapterId: String, spineIndex: Int, title: String, content: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO search_index (book_id, chapter_id, spine_index, title, content) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, chapterId)
                stmt.setInt(3, spineIndex)
                stmt.setString(4, title)
                stmt.setString(5, content)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun indexChaptersBulk(bookId: String, chapters: List<com.folio.reader.database.ChapterIndexEntry>) {
        if (chapters.isEmpty()) return
        // Use withTransaction for atomic batch insert
        db.withTransaction { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO search_index (book_id, chapter_id, spine_index, title, content) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                for (c in chapters) {
                    stmt.setString(1, bookId)
                    stmt.setString(2, c.chapterId)
                    stmt.setInt(3, c.spineIndex)
                    stmt.setString(4, c.title)
                    stmt.setString(5, c.content)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    override suspend fun deleteIndexForBook(bookId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM search_index WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId); stmt.executeUpdate()
            }
        }
    }

    private fun tokenizeForFts(query: String): String {
        return query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "${it.trim()}*" }
    }

    private fun mapRows(rs: ResultSet): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        while (rs.next()) {
            out.add(
                SearchResult(
                    bookId = rs.getString("book_id"),
                    chapterId = rs.getString("chapter_id"),
                    spineIndex = rs.getInt("spine_index"),
                    title = rs.getString("title"),
                    context = rs.getString("snippet")
                )
            )
        }
        return out
    }

    override fun search(queryText: String): Flow<List<SearchResult>> = flow {
        val q = queryText.trim()
        if (q.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val ftsQuery = tokenizeForFts(q)
        emit(db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT book_id, chapter_id, spine_index, title,
                       snippet(search_index, 4, '<<', '>>', '…', 12) AS snippet
                FROM search_index
                WHERE search_index MATCH ?
                ORDER BY bm25(search_index)
                LIMIT 200
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, ftsQuery)
                stmt.executeQuery().use { rs -> mapRows(rs) }
            }
        })
    }

    override fun searchInBook(bookId: String, queryText: String): Flow<List<SearchResult>> = flow {
        val q = queryText.trim()
        if (q.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val ftsQuery = tokenizeForFts(q)
        emit(db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT book_id, chapter_id, spine_index, title,
                       snippet(search_index, 4, '<<', '>>', '…', 12) AS snippet
                FROM search_index
                WHERE book_id = ? AND search_index MATCH ?
                ORDER BY bm25(search_index)
                LIMIT 200
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, ftsQuery)
                stmt.executeQuery().use { rs -> mapRows(rs) }
            }
        })
    }
}

class JdbcSearchRepository(private val db: Database) : SearchRepository {

    private val fts = JdbcFtsSearchRepository(db)
    private val like = JdbcLikeSearchRepository(db)

    private suspend fun <T> tryFts(block: suspend () -> T, fallback: suspend () -> T): T {
        return try {
            block()
        } catch (_: SQLException) {
            fallback()
        } catch (_: Exception) {
            fallback()
        }
    }

    override suspend fun indexChapter(bookId: String, chapterId: String, spineIndex: Int, title: String, content: String) {
        tryFts(
            block = { fts.indexChapter(bookId, chapterId, spineIndex, title, content) },
            fallback = { like.indexChapter(bookId, chapterId, spineIndex, title, content) }
        )
    }

    override suspend fun deleteIndexForBook(bookId: String) {
        tryFts(
            block = { fts.deleteIndexForBook(bookId) },
            fallback = { like.deleteIndexForBook(bookId) }
        )
    }

    override fun search(queryText: String): Flow<List<SearchResult>> {
        return fts.search(queryText).catch { _ ->
            emitAll(like.search(queryText))
        }
    }

    override fun searchInBook(bookId: String, queryText: String): Flow<List<SearchResult>> {
        return fts.searchInBook(bookId, queryText).catch { _ ->
            emitAll(like.searchInBook(bookId, queryText))
        }
    }
}

// ---------- Settings ----------

class JdbcSettingsRepository(private val db: Database) : SettingsRepository {
    companion object {
        private const val KEY_GLOBAL = "global_reader_settings"
        private fun keyForBook(bookId: String) = "book_reader_settings:$bookId"
    }

    override suspend fun getGlobalSettings(): ReaderSettings =
        db.getSettings(KEY_GLOBAL)?.let {
            runCatching { repoJson.decodeFromString(ReaderSettings.serializer(), it) }.getOrNull()
        } ?: ReaderSettings()

    override suspend fun saveGlobalSettings(settings: ReaderSettings) {
        db.setSettings(KEY_GLOBAL, repoJson.encodeToString(ReaderSettings.serializer(), settings))
        db.onEntityChanged?.invoke("settings", "global", "UPSERT", repoJson.encodeToString(ReaderSettings.serializer(), settings))
    }

    override suspend fun getBookSettings(bookId: String): BookReaderSettings? =
        db.getSettings(keyForBook(bookId))?.let {
            runCatching { repoJson.decodeFromString(BookReaderSettings.serializer(), it) }.getOrNull()
        }

    override suspend fun saveBookSettings(bookId: String, settings: BookReaderSettings) {
        db.setSettings(keyForBook(bookId), repoJson.encodeToString(BookReaderSettings.serializer(), settings))
    }

    override suspend fun deleteBookSettings(bookId: String) {
        db.setSettings(keyForBook(bookId), "")
    }

    override suspend fun setRaw(key: String, value: String) {
        db.setSettings(key, value)
    }

    override suspend fun getRaw(key: String): String? =
        db.getSettings(key)?.takeIf { it.isNotEmpty() }
}
