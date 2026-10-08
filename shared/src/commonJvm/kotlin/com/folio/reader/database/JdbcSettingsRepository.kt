package com.folio.reader.database

import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.sql.Connection

private val settingsJson = Json { ignoreUnknownKeys = true }

/**
 * JDBC settings repository, split out of JdbcRepositories.kt (§6). The global
 * row is read and written under the database's single write mutex; the merge
 * path additionally performs read + transform + write on one connection so it
 * is atomic against every other local writer.
 */
class JdbcSettingsRepository(private val db: Database) : SettingsRepository {
    companion object {
        private const val KEY_GLOBAL = "global_reader_settings"
        private fun keyForBook(bookId: String) = Database.bookSettingsKey(bookId)
    }

    override suspend fun getGlobalSettings(): ReaderSettings =
        db.getSettings(KEY_GLOBAL)?.let {
            runCatching { settingsJson.decodeFromString(ReaderSettings.serializer(), it) }.getOrNull()
        } ?: ReaderSettings()

    override suspend fun saveGlobalSettings(settings: ReaderSettings, emitSyncEvent: Boolean) {
        db.setSettings(KEY_GLOBAL, settingsJson.encodeToString(ReaderSettings.serializer(), settings))
        if (emitSyncEvent) {
            db.onEntityChanged?.invoke("settings", "global", "UPSERT", settingsJson.encodeToString(ReaderSettings.serializer(), settings))
        }
    }

    override suspend fun mergeGlobalSettings(
        emitSyncEvent: Boolean,
        transform: (ReaderSettings) -> ReaderSettings,
    ): ReaderSettings = db.withConnection { conn ->
        val current = readGlobal(conn)
        val merged = transform(current)
        if (merged != current) {
            val encoded = settingsJson.encodeToString(ReaderSettings.serializer(), merged)
            conn.prepareStatement("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, KEY_GLOBAL)
                stmt.setString(2, encoded)
                stmt.setLong(3, Clock.System.now().toEpochMilliseconds())
                stmt.executeUpdate()
            }
            if (emitSyncEvent) {
                db.onEntityChanged?.invoke("settings", "global", "UPSERT", encoded)
            }
        }
        merged
    }

    private fun readGlobal(conn: Connection): ReaderSettings =
        conn.prepareStatement("SELECT value FROM settings WHERE key = ?").use { stmt ->
            stmt.setString(1, KEY_GLOBAL)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
        }?.let { runCatching { settingsJson.decodeFromString(ReaderSettings.serializer(), it) }.getOrNull() }
            ?: ReaderSettings()

    override suspend fun getBookSettings(bookId: String): BookReaderSettings? =
        db.getSettings(keyForBook(bookId))?.let {
            runCatching { settingsJson.decodeFromString(BookReaderSettings.serializer(), it) }.getOrNull()
        }

    override suspend fun saveBookSettings(bookId: String, settings: BookReaderSettings) {
        db.setSettings(keyForBook(bookId), settingsJson.encodeToString(BookReaderSettings.serializer(), settings))
    }

    override suspend fun deleteBookSettings(bookId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM settings WHERE key = ?").use { stmt ->
                stmt.setString(1, keyForBook(bookId))
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun setRaw(key: String, value: String) {
        db.setSettings(key, value)
    }

    override suspend fun getRaw(key: String): String? =
        db.getSettings(key)?.takeIf { it.isNotEmpty() }
}
