package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.platform.DesktopPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards schema initialization: every table must exist after init, init must be
 * idempotent, and the sync-queue dedupe must run on SQLite engines without window
 * functions (Android 7/8 ship SQLite < 3.25 — ROW_NUMBER() OVER aborts there).
 */
class SchemaInitTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-schema-")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private val expectedTables = setOf(
        "books", "reading_positions", "reading_sessions", "highlights", "notes",
        "bookmarks", "series", "collections", "book_collections", "settings",
        "chapters", "sync_queue", "devices", "book_statistics", "daily_statistics",
        "tags", "book_tags", "highlight_tags", "quotes", "revisit_items",
        "reading_cycles", "search_index"
    )

    private fun tablesOf(dbPath: String): Set<String> {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')").use { rs ->
                    val out = mutableSetOf<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    return out
                }
            }
        }
    }

    @Test
    fun `schema init creates every table`() {
        val platform = DesktopPlatform(tempRoot)
        val database = Database(platform.fileSystem.getDatabasePath())
        try {
            val tables = tablesOf(platform.fileSystem.getDatabasePath())
            for (expected in expectedTables) {
                assertTrue(expected in tables, "missing table after init: $expected (have $tables)")
            }
        } finally {
            runCatching { database.close() }
        }
    }

    @Test
    fun `schema init is idempotent across restarts`() {
        val platform = DesktopPlatform(tempRoot)
        val path = platform.fileSystem.getDatabasePath()
        Database(path).close()
        val second = Database(path)
        try {
            val tables = tablesOf(path)
            for (expected in expectedTables) {
                assertTrue(expected in tables, "missing table after second init: $expected")
            }
        } finally {
            runCatching { second.close() }
        }
    }

    /**
     * Reproduces the legacy queue (random ids, duplicate rows per entity) and runs
     * the exact dedupe statement shipped in Database.kt — GROUP BY + MAX(rowid),
     * which SQLite 3.9 (Android 7) understands. Window functions would throw here
     * on old engines; this SQL must not need them.
     */
    @Test
    fun `legacy queue dedupe keeps newest mutation per entity without window functions`() {
        val dbFile = File(tempRoot, "legacy.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE sync_queue (
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
                    """.trimIndent()
                )
                // Old builds queued one random-id row per edit — three for one entity.
                st.execute("INSERT INTO sync_queue (id, entity_type, entity_id, operation, payload, created_at) VALUES ('r1','highlight','h1',1,'{\"v\":1}',100)")
                st.execute("INSERT INTO sync_queue (id, entity_type, entity_id, operation, payload, created_at) VALUES ('r2','highlight','h1',1,'{\"v\":2}',200)")
                st.execute("INSERT INTO sync_queue (id, entity_type, entity_id, operation, payload, created_at) VALUES ('r3','highlight','h1',1,'{\"v\":3}',300)")
                st.execute("INSERT INTO sync_queue (id, entity_type, entity_id, operation, payload, created_at) VALUES ('r4','book','b1',0,'{}',50)")
            }

            // Verbatim copy of the dedupe Database.kt runs at init.
            conn.createStatement().use { st ->
                st.execute(
                    """
                    DELETE FROM sync_queue
                    WHERE rowid NOT IN (
                        SELECT MAX(rowid) FROM sync_queue GROUP BY entity_type, entity_id
                    )
                    """.trimIndent()
                )
            }

            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM sync_queue").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1), "one row per entity must survive")
                }
                st.executeQuery("SELECT payload FROM sync_queue WHERE entity_id = 'h1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("{\"v\":3}", rs.getString(1), "the newest mutation must win")
                }
            }
        }
    }
}
