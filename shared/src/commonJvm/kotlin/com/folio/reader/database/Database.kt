package com.folio.reader.database

import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.CloudState
import com.folio.reader.model.FormattingMode
import com.folio.reader.model.Highlight
import com.folio.reader.model.HighlightColor
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.io.File

/**
 * Plain JDBC SQLite database. Works identically on Android and Desktop via
 * org.xerial:sqlite-jdbc (which ships Android natives).
 */
class Database(private val dbPath: String, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    var onEntityChanged: ((entityType: String, entityId: String, operation: String, payloadJson: String) -> Unit)? = null

    /**
     * Bumped after every manga library/chapter write. The manga observe* flows
     * re-query on this so the library, unread counts and favorite state update
     * live (they were previously collected once and went stale).
     */
    val mangaDataRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun bumpMangaData() { mangaDataRevision.value += 1 }

    private val json = Json { ignoreUnknownKeys = true }
    private val writeMutex = Mutex() // Serialize ALL database access (SQLite single connection)
    private val driverDelegate = object : DatabaseDriver {
        var cachedConnection: Connection? = null
        override fun getConnection(): Connection = synchronized(this) {
            cachedConnection?.takeIf { !it.isClosed }?.let { return it }
            File(dbPath).parentFile?.mkdirs()
            // Use autoCommit=true by default to prevent Android SQLiteConnectionPool deadlock
            // Android wraps JDBC connections - a connection with pending transaction stays "unavailable"
            val newConnection = openConnection().apply { autoCommit = true }
            cachedConnection = newConnection
            newConnection
        }

        /**
         * Android: instantiate sqldroid DIRECTLY — DriverManager must be bypassed
         * because xerial's sqlite-jdbc also auto-registers for `jdbc:sqlite:` and
         * its native load (`libsqlitejdbc.so`) explodes on ARM. Desktop: sqldroid
         * is absent, so DriverManager → sqlite-jdbc as before.
         */
        private fun openConnection(): Connection {
            val sqldroid = runCatching { Class.forName("org.sqldroid.SQLDroidDriver") }.getOrNull()
            val connection = if (sqldroid != null) {
                val driver = sqldroid.getDeclaredConstructor().newInstance() as java.sql.Driver
                driver.connect("jdbc:sqlite:$dbPath", java.util.Properties())
                    ?: throw SQLException("sqldroid could not open $dbPath")
            } else {
                DriverManager.getConnection("jdbc:sqlite:$dbPath")
            }
            // WAL keeps reads from stalling behind checkpointed writes. Some
            // drivers refuse the pragma; a failure only loses the optimization.
            runCatching { connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") } }
            return connection
        }
        override fun close() = synchronized(this) {
            cachedConnection?.let { runCatching { it.close() } }
            cachedConnection = null
        }
    }

    init {
        initializeSchema()
    }

    interface DatabaseDriver {
        fun getConnection(): Connection
        fun close()
    }

    private fun Connection.createStatementExec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun initializeSchema() {
        val conn = driverDelegate.getConnection()
        try {
            // Schema operations don't need explicit transaction with autoCommit=true
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS books (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    subtitle TEXT,
                    authors TEXT NOT NULL,
                    publisher TEXT,
                    language TEXT,
                    isbn TEXT,
                    description TEXT,
                    publication_date INTEGER,
                    cover_path TEXT,
                    epub_hash TEXT NOT NULL,
                    epub_file_size INTEGER NOT NULL,
                    added_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    last_opened_at INTEGER,
                    total_characters INTEGER NOT NULL DEFAULT 0,
                    total_words INTEGER NOT NULL DEFAULT 0,
                    chapter_count INTEGER NOT NULL DEFAULT 0,
                    series_id TEXT,
                    series_number REAL,
                    status INTEGER NOT NULL DEFAULT 0,
                    cloud_state INTEGER NOT NULL DEFAULT 0,
                    formatting_mode INTEGER NOT NULL DEFAULT 1,
                    normalized_progress REAL NOT NULL DEFAULT 0.0
                )
            """.trimIndent())
            // Migration: add updated_at if missing (existing DBs)
            runCatching {
                conn.createStatement().executeQuery("SELECT updated_at FROM books LIMIT 0").close()
            }.onFailure {
                conn.createStatementExec("ALTER TABLE books ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                conn.createStatementExec("UPDATE books SET updated_at = added_at WHERE updated_at = 0")
            }
            conn.createStatementExec("CREATE INDEX IF NOT EXISTS idx_books_status ON books(status)")
            conn.createStatementExec("CREATE INDEX IF NOT EXISTS idx_books_series ON books(series_id)")
            conn.createStatementExec("CREATE INDEX IF NOT EXISTS idx_books_hash ON books(epub_hash)")
            conn.createStatementExec("CREATE INDEX IF NOT EXISTS idx_books_isbn ON books(isbn)")
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS reading_positions (
                    book_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    spine_index INTEGER NOT NULL,
                    content_locator TEXT NOT NULL,
                    character_offset INTEGER NOT NULL DEFAULT 0,
                    paragraph_index INTEGER NOT NULL DEFAULT 0,
                    normalized_progress REAL NOT NULL DEFAULT 0.0,
                    chapter_progress REAL NOT NULL DEFAULT 0.0,
                    scroll_offset REAL NOT NULL DEFAULT 0.0,
                    updated_at INTEGER NOT NULL,
                    previous_position TEXT,
                    PRIMARY KEY (book_id, device_id)
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS reading_sessions (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    cycle_id TEXT,
                    device_id TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    start_position TEXT NOT NULL,
                    end_position TEXT,
                    start_progress REAL NOT NULL DEFAULT 0.0,
                    end_progress REAL NOT NULL DEFAULT 0.0,
                    words_read INTEGER NOT NULL DEFAULT 0,
                    is_active INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS highlights (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    spine_index INTEGER NOT NULL,
                    start_locator TEXT NOT NULL,
                    end_locator TEXT NOT NULL,
                    selected_text TEXT NOT NULL,
                    color TEXT NOT NULL,
                    custom_color INTEGER,
                    note_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    is_deleted INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS notes (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    chapter_id TEXT,
                    spine_index INTEGER,
                    locator TEXT,
                    content TEXT NOT NULL,
                    type INTEGER NOT NULL DEFAULT 4,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    is_deleted INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    spine_index INTEGER NOT NULL,
                    locator TEXT NOT NULL,
                    label TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    is_deleted INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS series (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS collections (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    color INTEGER,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS book_collections (
                    book_id TEXT NOT NULL,
                    collection_id TEXT NOT NULL,
                    added_at INTEGER NOT NULL,
                    PRIMARY KEY (book_id, collection_id)
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS chapters (
                    id TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    href TEXT NOT NULL,
                    spine_index INTEGER NOT NULL,
                    level INTEGER NOT NULL DEFAULT 0,
                    character_count INTEGER NOT NULL DEFAULT 0,
                    word_count INTEGER NOT NULL DEFAULT 0,
                    start_offset INTEGER NOT NULL DEFAULT 0,
                    end_offset INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (book_id, id)
                )
            """.trimIndent())
            conn.createStatementExec(
                "CREATE INDEX IF NOT EXISTS idx_chapters_book ON chapters (book_id, spine_index)"
            )
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS sync_queue (
                    id TEXT PRIMARY KEY,
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    operation INTEGER NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    last_attempt_at INTEGER,
                    status INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            // Older builds generated a random queue id for each edit, producing
            // thousands of duplicate uploads for the same entity. Keep only the
            // newest mutation before enforcing the entity-level outbox invariant.
            // GROUP BY + MAX(rowid) instead of ROW_NUMBER(): Android 7/8 ship a
            // system SQLite older than 3.25, which has no window functions.
            conn.createStatementExec(
                """
                DELETE FROM sync_queue
                WHERE rowid NOT IN (
                    SELECT MAX(rowid) FROM sync_queue GROUP BY entity_type, entity_id
                )
                """.trimIndent()
            )
            conn.createStatementExec(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_sync_queue_entity ON sync_queue (entity_type, entity_id)"
            )
            conn.createStatementExec(
                "CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON sync_queue (status, created_at)"
            )
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    platform TEXT NOT NULL,
                    app_version TEXT NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    is_current INTEGER NOT NULL DEFAULT 0,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS book_statistics (
                    book_id TEXT PRIMARY KEY,
                    total_reading_time_ms INTEGER NOT NULL DEFAULT 0,
                    total_sessions INTEGER NOT NULL DEFAULT 0,
                    total_words_read INTEGER NOT NULL DEFAULT 0,
                    average_session_time_ms INTEGER NOT NULL DEFAULT 0,
                    longest_session_ms INTEGER NOT NULL DEFAULT 0,
                    reading_days INTEGER NOT NULL DEFAULT 0,
                    first_opened_at INTEGER,
                    last_opened_at INTEGER,
                    started_at INTEGER,
                    finished_at INTEGER,
                    completion_time_ms INTEGER,
                    average_reading_speed_wpm REAL NOT NULL DEFAULT 0.0,
                    current_cycle INTEGER NOT NULL DEFAULT 1,
                    cycle_history TEXT NOT NULL DEFAULT '[]'
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS daily_statistics (
                    device_id TEXT NOT NULL,
                    date TEXT NOT NULL,
                    reading_time_ms INTEGER NOT NULL DEFAULT 0,
                    words_read INTEGER NOT NULL DEFAULT 0,
                    session_count INTEGER NOT NULL DEFAULT 0,
                    books_read INTEGER NOT NULL DEFAULT 0,
                    sessions_json TEXT NOT NULL DEFAULT '[]',
                    PRIMARY KEY (device_id, date)
                )
            """.trimIndent())

            // Tags, quotes and revisit items (backing the sync + annotation model)
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS tags (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    color INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS book_tags (
                    book_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    PRIMARY KEY (book_id, tag_id)
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS highlight_tags (
                    highlight_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    PRIMARY KEY (highlight_id, tag_id)
                )
            """.trimIndent())
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS quotes (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    highlight_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    note TEXT,
                    created_at INTEGER NOT NULL,
                    device_id TEXT NOT NULL
                )
            """.trimIndent())
            conn.createStatementExec(
                "CREATE INDEX IF NOT EXISTS idx_quotes_book ON quotes(book_id)"
            )
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS revisit_items (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    type INTEGER NOT NULL,
                    source_id TEXT NOT NULL,
                    note TEXT,
                    created_at INTEGER NOT NULL,
                    resolved_at INTEGER,
                    device_id TEXT NOT NULL
                )
            """.trimIndent())
            conn.createStatementExec(
                "CREATE INDEX IF NOT EXISTS idx_revisit_book ON revisit_items(book_id)"
            )
            conn.createStatementExec("""
                CREATE TABLE IF NOT EXISTS reading_cycles (
                    id TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    cycle_number INTEGER NOT NULL,
                    started_at INTEGER NOT NULL,
                    finished_at INTEGER,
                    total_duration_ms INTEGER NOT NULL DEFAULT 0,
                    session_count INTEGER NOT NULL DEFAULT 0,
                    final_progress REAL NOT NULL DEFAULT 0.0
                )
            """.trimIndent())
            conn.createStatementExec(
                "CREATE INDEX IF NOT EXISTS idx_cycles_book ON reading_cycles(book_id, cycle_number)"
            )
            // Migration: watermark columns for tags/collections/series (edit sync).
            // Constant-default ALTERs run on old Android SQLite too.
            for (table in listOf("tags", "collections", "series")) {
                val present = runCatching {
                    conn.prepareStatement("SELECT updated_at FROM $table LIMIT 0").use {
                        it.executeQuery().use { }
                    }
                }.isSuccess
                if (!present) {
                    conn.createStatementExec("ALTER TABLE $table ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                }
            }
            try {
                conn.createStatementExec(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(
                        book_id,
                        chapter_id,
                        spine_index,
                        title,
                        content,
                        tokenize='unicode61'
                    )
                    """.trimIndent()
                )
            } catch (_: SQLException) {
                conn.createStatementExec(
                    """
                    CREATE TABLE IF NOT EXISTS search_index (
                        book_id TEXT NOT NULL,
                        chapter_id TEXT NOT NULL,
                        spine_index INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        PRIMARY KEY (book_id, chapter_id)
                    )
                    """.trimIndent()
                )
            }
            // Manga library tables live in the same database but are managed entirely by
            // the manga domain (separate category from books).
            MangaSchema.initialize(conn)
            // Sessions recorded before reading time was measured (rather than elapsed)
            // are nonsense and cannot be repaired; drop them once. New rows always
            // start after the cutoff, so this matches nothing after its first run.
            purgeUnmeasuredSessions(conn)
            // No commit needed with autoCommit=true
        } catch (e: SQLException) {
            // A half-built schema causes scattered "no such table" failures later;
            // surface the real error at startup instead.
            throw RuntimeException("Folio database schema initialization failed", e)
        } finally {
            // Keep the single shared connection open for reuse.
        }
    }

    private fun purgeUnmeasuredSessions(conn: Connection) {
        try {
            conn.prepareStatement(
                "DELETE FROM reading_sessions WHERE started_at < ?"
            ).use { stmt ->
                stmt.setLong(1, ReadingSession.FIRST_MEASURED_SESSION.toEpochMilliseconds())
                val removed = stmt.executeUpdate()
                if (removed > 0) {
                    println("🧹 Dropped $removed session rows recorded before reading time was measured")
                }
            }
        } catch (_: SQLException) {
            // Nothing to clean on a database that has no sessions yet.
        }
    }

    // ---------- Books ----------

    suspend fun insertBook(book: Book): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            // autoCommit=true, each statement auto-commits
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO books (id, title, subtitle, authors, publisher, language, isbn, description,
                    publication_date, cover_path, epub_hash, epub_file_size, added_at, updated_at, last_opened_at,
                    total_characters, total_words, chapter_count, series_id, series_number, status,
                    cloud_state, formatting_mode, normalized_progress)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, book.id)
                stmt.setString(2, book.title)
                stmt.setString(3, book.subtitle)
                stmt.setString(4, json.encodeToString(ListSerializer(String.serializer()), book.authors))
                stmt.setString(5, book.publisher)
                stmt.setString(6, book.language)
                stmt.setString(7, book.isbn)
                stmt.setString(8, book.description)
                stmt.setLong(9, book.publicationDate?.toEpochMilliseconds() ?: 0L)
                stmt.setString(10, book.coverPath)
                stmt.setString(11, book.epubHash)
                stmt.setLong(12, book.epubFileSize)
                stmt.setLong(13, book.addedAt.toEpochMilliseconds())
                stmt.setLong(14, book.updatedAt.toEpochMilliseconds())
                stmt.setLong(15, book.lastOpenedAt?.toEpochMilliseconds() ?: 0L)
                stmt.setLong(16, book.totalCharacters)
                stmt.setLong(17, book.totalWords)
                stmt.setInt(18, book.chapterCount)
                stmt.setString(19, book.seriesId)
                if (book.seriesNumber != null) stmt.setDouble(20, book.seriesNumber)
                else stmt.setNull(20, java.sql.Types.REAL)
                stmt.setInt(21, book.status.value)
                stmt.setInt(22, book.cloudState.value)
                stmt.setInt(23, book.formattingMode.value)
                stmt.setDouble(24, book.normalizedProgress)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun getAllBooks(): List<Book> = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            val books = mutableListOf<Book>()
            try {
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT * FROM books ORDER BY COALESCE(last_opened_at, added_at) DESC"
                    ).use { rs ->
                        while (rs.next()) books.add(mapRowToBook(rs))
                    }
                }
            } finally {
            }
            books
        }
    }

    suspend fun getBook(bookId: String): Book? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT * FROM books WHERE id = ?").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeQuery().use { rs -> if (rs.next()) mapRowToBook(rs) else null }
                }
            } finally {
            }
        }
    }

    suspend fun getBookByEpubHash(hash: String): Book? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT * FROM books WHERE epub_hash = ?").use { stmt ->
                    stmt.setString(1, hash)
                    stmt.executeQuery().use { rs -> if (rs.next()) mapRowToBook(rs) else null }
                }
            } finally {
            }
        }
    }

    suspend fun getBookByIsbn(isbn: String): Book? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT * FROM books WHERE isbn = ?").use { stmt ->
                    stmt.setString(1, isbn)
                    stmt.executeQuery().use { rs -> if (rs.next()) mapRowToBook(rs) else null }
                }
            } finally {
            }
        }
    }

    suspend fun updateBook(book: Book): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
        conn.prepareStatement(
            """
            UPDATE books SET title = ?, subtitle = ?, authors = ?, publisher = ?, language = ?,
                isbn = ?, description = ?, publication_date = ?, cover_path = ?, updated_at = ?, last_opened_at = ?,
                total_characters = ?, total_words = ?, chapter_count = ?, series_id = ?,
                series_number = ?, status = ?, cloud_state = ?, formatting_mode = ?, normalized_progress = ?
            WHERE id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, book.title)
            stmt.setString(2, book.subtitle)
            stmt.setString(3, json.encodeToString(ListSerializer(String.serializer()), book.authors))
            stmt.setString(4, book.publisher)
            stmt.setString(5, book.language)
            stmt.setString(6, book.isbn)
            stmt.setString(7, book.description)
            stmt.setLong(8, book.publicationDate?.toEpochMilliseconds() ?: 0L)
            stmt.setString(9, book.coverPath)
            stmt.setLong(10, book.updatedAt.toEpochMilliseconds())
            stmt.setLong(11, book.lastOpenedAt?.toEpochMilliseconds() ?: 0L)
            stmt.setLong(12, book.totalCharacters)
            stmt.setLong(13, book.totalWords)
            stmt.setInt(14, book.chapterCount)
            stmt.setString(15, book.seriesId)
            if (book.seriesNumber != null) stmt.setDouble(16, book.seriesNumber)
            else stmt.setNull(16, java.sql.Types.REAL)
            stmt.setInt(17, book.status.value)
            stmt.setInt(18, book.cloudState.value)
            stmt.setInt(19, book.formattingMode.value)
            stmt.setDouble(20, book.normalizedProgress)
            stmt.setString(21, book.id)
            stmt.executeUpdate()
        }
        }
    }

    suspend fun setBookStatus(bookId: String, status: BookStatus): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("UPDATE books SET status = ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, status.value)
                stmt.setString(2, bookId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun setCloudState(bookId: String, cloudState: CloudState): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("UPDATE books SET cloud_state = ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, cloudState.value)
                stmt.setString(2, bookId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun markOpened(bookId: String, at: Instant = Clock.System.now()): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("UPDATE books SET last_opened_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, at.toEpochMilliseconds())
                stmt.setString(2, bookId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun updateNormalizedProgress(bookId: String, progress: Double): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("UPDATE books SET normalized_progress = ? WHERE id = ?").use { stmt ->
                stmt.setDouble(1, progress.coerceIn(0.0, 1.0))
                stmt.setString(2, bookId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun deleteBook(bookId: String): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
        listOf(
            "DELETE FROM reading_positions WHERE book_id = ?",
            "DELETE FROM highlights WHERE book_id = ?",
            "DELETE FROM notes WHERE book_id = ?",
            "DELETE FROM bookmarks WHERE book_id = ?",
            "DELETE FROM reading_sessions WHERE book_id = ?",
            "DELETE FROM chapters WHERE book_id = ?",
            "DELETE FROM search_index WHERE book_id = ?",
            "DELETE FROM book_tags WHERE book_id = ?",
            "DELETE FROM book_collections WHERE book_id = ?",
            "DELETE FROM quotes WHERE book_id = ?",
            "DELETE FROM revisit_items WHERE book_id = ?",
            "DELETE FROM book_statistics WHERE book_id = ?",
            "DELETE FROM reading_cycles WHERE book_id = ?",
            "DELETE FROM books WHERE id = ?"
        ).forEach { sql ->
            // FTS virtual table uses different delete syntax; ignore errors
            runCatching {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeUpdate()
                }
            }
        }
        // highlight_tags orphan cleanup
        runCatching {
            conn.prepareStatement("DELETE FROM highlight_tags WHERE highlight_id NOT IN (SELECT id FROM highlights)").use { it.executeUpdate() }
        }
        // Per-book reader overrides live in the settings KV table keyed by book id,
        // so the books DELETE above would leave the row behind permanently.
        runCatching {
            conn.prepareStatement("DELETE FROM settings WHERE key = ?").use { stmt ->
                stmt.setString(1, bookSettingsKey(bookId))
                stmt.executeUpdate()
            }
        }
        }
    }

    // ---------- Reading positions ----------

    suspend fun upsertPosition(position: ReadingPosition): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reading_positions
                (book_id, device_id, chapter_id, spine_index, content_locator, character_offset,
                 paragraph_index, normalized_progress, chapter_progress, scroll_offset, updated_at, previous_position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, position.bookId)
                stmt.setString(2, position.deviceId)
                stmt.setString(3, position.chapterId)
                stmt.setInt(4, position.spineIndex)
                stmt.setString(5, position.contentLocator)
                stmt.setInt(6, position.characterOffset)
                stmt.setInt(7, position.paragraphIndex)
                stmt.setDouble(8, position.normalizedProgress)
                stmt.setDouble(9, position.chapterProgress)
                stmt.setDouble(10, position.scrollOffset)
                stmt.setLong(11, position.updatedAt.toEpochMilliseconds())
                stmt.setString(12, position.previousPosition?.let {
                    if (it.updatedAt == position.updatedAt) null
                    else json.encodeToString(ReadingPosition.serializer(), it)
                })
                stmt.executeUpdate()
            }
        }
    }

    suspend fun getPosition(bookId: String, deviceId: String): ReadingPosition? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT * FROM reading_positions WHERE book_id = ? AND device_id = ?").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.setString(2, deviceId)
                    stmt.executeQuery().use { rs -> if (rs.next()) mapRowToPosition(rs) else null }
                }
            } finally {
            }
        }
    }

    suspend fun getLatestPositionAcrossDevices(bookId: String): ReadingPosition? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT * FROM reading_positions WHERE book_id = ? ORDER BY updated_at DESC LIMIT 1").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeQuery().use { rs -> if (rs.next()) mapRowToPosition(rs) else null }
                }
            } finally {
            }
        }
    }

    suspend fun getAllPositionsForBook(bookId: String): List<ReadingPosition> = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            val result = mutableListOf<ReadingPosition>()
            try {
                conn.prepareStatement("SELECT * FROM reading_positions WHERE book_id = ?").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) result.add(mapRowToPosition(rs))
                    }
                }
            } finally {
            }
            result
        }
    }

    suspend fun deletePosition(bookId: String, deviceId: String): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("DELETE FROM reading_positions WHERE book_id = ? AND device_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, deviceId)
                stmt.executeUpdate()
            }
        }
    }

    // ---------- Highlights ----------

    suspend fun insertHighlight(highlight: Highlight): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement(
                """
            INSERT OR REPLACE INTO highlights
            (id, book_id, chapter_id, spine_index, start_locator, end_locator, selected_text,
             color, custom_color, note_id, created_at, updated_at, device_id, is_deleted, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, highlight.id)
                stmt.setString(2, highlight.bookId)
                stmt.setString(3, highlight.chapterId)
                stmt.setInt(4, highlight.spineIndex)
                stmt.setString(5, highlight.startLocator)
                stmt.setString(6, highlight.endLocator)
                stmt.setString(7, highlight.selectedText)
                stmt.setString(8, highlight.color.name)
                stmt.setInt(9, highlight.customColor ?: 0)
                stmt.setString(10, highlight.noteId)
                stmt.setLong(11, highlight.createdAt.toEpochMilliseconds())
                stmt.setLong(12, highlight.updatedAt.toEpochMilliseconds())
                stmt.setString(13, highlight.deviceId)
                stmt.setInt(14, if (highlight.isDeleted) 1 else 0)
                stmt.setLong(15, highlight.deletedAt?.toEpochMilliseconds() ?: 0L)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun updateHighlight(highlight: Highlight): Unit = insertHighlight(highlight)

    suspend fun getHighlight(highlightId: String): Highlight? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT * FROM highlights WHERE id = ?").use { stmt ->
                    stmt.setString(1, highlightId)
                    stmt.executeQuery().use { rs -> if (rs.next()) mapRowToHighlight(rs) else null }
                }
            } finally {
            }
        }
    }

    suspend fun restoreHighlight(highlightId: String): Unit = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("UPDATE highlights SET is_deleted = 0, deleted_at = NULL, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, Clock.System.now().toEpochMilliseconds())
                stmt.setString(2, highlightId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun getHighlightsForBook(bookId: String): List<Highlight> = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            val highlights = mutableListOf<Highlight>()
            try {
                conn.prepareStatement("SELECT * FROM highlights WHERE book_id = ? AND is_deleted = 0 ORDER BY spine_index, created_at").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) highlights.add(mapRowToHighlight(rs))
                    }
                }
            } finally {
            }
            highlights
        }
    }

    suspend fun getDeletedHighlights(bookId: String): List<Highlight> = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            val highlights = mutableListOf<Highlight>()
            try {
                conn.prepareStatement("SELECT * FROM highlights WHERE book_id = ? AND is_deleted = 1").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) highlights.add(mapRowToHighlight(rs))
                    }
                }
            } finally {
            }
            highlights
        }
    }

    suspend fun countHighlightsForChapter(bookId: String, chapterId: String): Int = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT COUNT(*) FROM highlights WHERE book_id = ? AND chapter_id = ? AND is_deleted = 0").use { stmt ->
                    stmt.setString(1, bookId)
                    stmt.setString(2, chapterId)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            } finally {
            }
        }
    }

    // ---------- Settings (key/value JSON) ----------

    suspend fun getSettings(key: String): String? = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            try {
                conn.prepareStatement("SELECT value FROM settings WHERE key = ?").use { stmt ->
                    stmt.setString(1, key)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
                }
            } finally {
                // Commit read to release locks
            }
        }
    }

    suspend fun setSettings(key: String, value: String) = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            conn.prepareStatement("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.setLong(3, Clock.System.now().toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
    }

    suspend fun saveReaderSettings(settings: ReaderSettings) =
        setSettings(KEY_GLOBAL_SETTINGS, json.encodeToString(ReaderSettings.serializer(), settings))

    suspend fun loadReaderSettings(): ReaderSettings? =
        getSettings(KEY_GLOBAL_SETTINGS)?.let {
            runCatching { json.decodeFromString(ReaderSettings.serializer(), it) }.getOrNull()
        }

    // ---------- Mapping ----------

    /** Escape hatch for repository classes that manage their own SQL. Serialized for SQLite single-writer. */
    suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(dispatcher) {
        writeMutex.withLock { block(driverDelegate.getConnection()) }
    }
    /**
     * Execute block within an explicit transaction. Use for multi-statement operations
     * that must be atomic (e.g., DELETE + multiple INSERTs).
     * 
     * Temporarily sets autoCommit=false, runs the block, commits on success,
     * rolls back on exception, and restores autoCommit=true.
     */
    suspend fun <T> withTransaction(block: (Connection) -> T): T = withContext(dispatcher) {
        writeMutex.withLock {
            val conn = driverDelegate.getConnection()
            val originalAutoCommit = conn.autoCommit
            try {
                conn.autoCommit = false
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }
    }



    fun mapRowToBook(rs: ResultSet): Book {
        val authorsJson = rs.getString("authors")
        val authors = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), authorsJson)
        }.getOrDefault(emptyList())
        return Book(
            id = rs.getString("id"),
            title = rs.getString("title"),
            subtitle = rs.getString("subtitle")?.takeIf { it.isNotBlank() },
            authors = authors,
            publisher = rs.getString("publisher")?.takeIf { it.isNotBlank() },
            language = rs.getString("language")?.takeIf { it.isNotBlank() },
            isbn = rs.getString("isbn")?.takeIf { it.isNotBlank() },
            description = rs.getString("description")?.takeIf { it.isNotBlank() },
            publicationDate = epochToInstant(rs.getLong("publication_date")),
            coverPath = rs.getString("cover_path")?.takeIf { it.isNotBlank() },
            epubHash = rs.getString("epub_hash"),
            epubFileSize = rs.getLong("epub_file_size"),
            addedAt = Instant.fromEpochMilliseconds(rs.getLong("added_at")),
            updatedAt = runCatching { Instant.fromEpochMilliseconds(rs.getLong("updated_at")) }.getOrElse { Instant.fromEpochMilliseconds(rs.getLong("added_at")) },
            lastOpenedAt = epochToInstant(rs.getLong("last_opened_at")),
            totalCharacters = rs.getLong("total_characters"),
            totalWords = rs.getLong("total_words"),
            chapterCount = rs.getInt("chapter_count"),
            seriesId = rs.getString("series_id")?.takeIf { it.isNotBlank() },
            seriesNumber = rs.getDouble("series_number").takeIf { !rs.wasNull() },
            status = BookStatus.fromValue(rs.getInt("status")),
            cloudState = CloudState.fromValue(rs.getInt("cloud_state")),
            formattingMode = FormattingMode.fromValue(rs.getInt("formatting_mode"))
        ).also { it.updateProgress(rs.getDouble("normalized_progress")) }
    }

    fun mapRowToPosition(rs: ResultSet): ReadingPosition {
        return ReadingPosition(
            bookId = rs.getString("book_id"),
            deviceId = rs.getString("device_id"),
            chapterId = rs.getString("chapter_id"),
            spineIndex = rs.getInt("spine_index"),
            contentLocator = rs.getString("content_locator"),
            characterOffset = rs.getInt("character_offset"),
            paragraphIndex = rs.getInt("paragraph_index"),
            normalizedProgress = rs.getDouble("normalized_progress"),
            chapterProgress = rs.getDouble("chapter_progress"),
            scrollOffset = rs.getDouble("scroll_offset"),
            updatedAt = Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
            previousPosition = rs.getString("previous_position")?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString(ReadingPosition.serializer(), it) }.getOrNull()
            }
        )
    }

    fun mapRowToHighlight(rs: ResultSet): Highlight {
        return Highlight(
            id = rs.getString("id"),
            bookId = rs.getString("book_id"),
            chapterId = rs.getString("chapter_id"),
            spineIndex = rs.getInt("spine_index"),
            startLocator = rs.getString("start_locator"),
            endLocator = rs.getString("end_locator"),
            selectedText = rs.getString("selected_text"),
            color = runCatching { HighlightColor.valueOf(rs.getString("color")) }.getOrDefault(HighlightColor.YELLOW),
            customColor = rs.getInt("custom_color").takeIf { !rs.wasNull() && it != 0 },
            noteId = rs.getString("note_id")?.takeIf { it.isNotBlank() },
            createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
            updatedAt = Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
            deviceId = rs.getString("device_id"),
            isDeleted = rs.getInt("is_deleted") == 1,
            deletedAt = epochToInstant(rs.getLong("deleted_at"))
        )
    }

    private fun epochToInstant(ms: Long): Instant? =
        if (ms > 0) Instant.fromEpochMilliseconds(ms) else null

    fun close() {
        driverDelegate.close()
    }

    companion object {
        const val KEY_GLOBAL_SETTINGS = "global_reader_settings"

        /** Settings KV row holding one book's local-only reader overrides. */
        fun bookSettingsKey(bookId: String) = "book_reader_settings:$bookId"
    }
}

