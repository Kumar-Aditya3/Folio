package com.folio.reader.database

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One stored genre row for a book under one embedding model.
 *
 * [genre] is the [com.folio.reader.ml.BroadGenre] name (stored as a string so the database layer
 * stays free of the ml types), [source] is the [com.folio.reader.ml.GenreSource] name, and
 * [modelId] scopes the row so a model swap re-derives genres rather than reusing another model's.
 */
data class StoredGenre(
    val bookId: String,
    val genre: String,
    val confidence: Float,
    val source: String,
    val modelId: String,
    val updatedAt: Long,
)

/**
 * Persistence for per-book broad genre and the parsed OPF subjects that feed the metadata-first
 * path. Split out from the book row on purpose: genre is *derived* data (per embedding model,
 * re-computable) and subjects are import-time metadata the core `books` table never carried, so
 * neither belongs on the sync-tracked `Book`.
 */
interface GenreRepository {
    /** Upserts one book's genre for [StoredGenre.modelId]. Keyed by (book_id, model_id). */
    suspend fun upsertGenre(genre: StoredGenre)

    /** Every stored genre for [modelId], keyed by book id — the Atlas decoration's bulk read. */
    suspend fun genresForModel(modelId: String): Map<String, StoredGenre>

    suspend fun genreFor(bookId: String, modelId: String): StoredGenre?

    /**
     * Embedded books that still have no genre row for [modelId], up to [limit]. This is the genre
     * backfill's work list — the analogue of `chaptersMissingVectors`, so a run only classifies
     * books that need it and a repeat run is a no-op.
     */
    suspend fun booksMissingGenre(modelId: String, limit: Int): List<String>

    /** Records a book's parsed OPF `<dc:subject>` strings for the metadata-first classifier. */
    suspend fun setSubjects(bookId: String, subjects: List<String>)

    suspend fun subjectsFor(bookId: String): List<String>

    /**
     * Deletes every stored genre row for [modelId], returning how many were removed. Used to force a
     * full re-derivation when the classifier algorithm changes (rows are keyed by model, so without
     * this a library already classified by the previous classifier would never be revisited).
     */
    suspend fun clearForModel(modelId: String): Int

    /** How many genre rows exist for [modelId] — part of the Atlas fingerprint so a newly-classified
     *  library invalidates a genre-blind cached map. Cheap indexed COUNT. */
    suspend fun genreCount(modelId: String): Int
}

/**
 * JDBC implementation of [GenreRepository], shared by Android and desktop, mirroring
 * [JdbcChunkRepository]'s shape (raw JDBC over the single shared [Database] connection).
 */
class JdbcGenreRepository(private val db: Database) : GenreRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upsertGenre(genre: StoredGenre) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO book_genre
                    (book_id, model_id, genre, confidence, source, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, genre.bookId)
                stmt.setString(2, genre.modelId)
                stmt.setString(3, genre.genre)
                stmt.setDouble(4, genre.confidence.toDouble())
                stmt.setString(5, genre.source)
                stmt.setLong(6, genre.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun genresForModel(modelId: String): Map<String, StoredGenre> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT book_id, genre, confidence, source, updated_at FROM book_genre WHERE model_id = ?"
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.executeQuery().use { rs ->
                    val out = HashMap<String, StoredGenre>()
                    while (rs.next()) {
                        val bookId = rs.getString("book_id")
                        out[bookId] = StoredGenre(
                            bookId = bookId,
                            genre = rs.getString("genre"),
                            confidence = rs.getDouble("confidence").toFloat(),
                            source = rs.getString("source"),
                            modelId = modelId,
                            updatedAt = rs.getLong("updated_at"),
                        )
                    }
                    out
                }
            }
        }

    override suspend fun genreFor(bookId: String, modelId: String): StoredGenre? =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT genre, confidence, source, updated_at FROM book_genre WHERE book_id = ? AND model_id = ?"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, modelId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) StoredGenre(
                        bookId = bookId,
                        genre = rs.getString("genre"),
                        confidence = rs.getDouble("confidence").toFloat(),
                        source = rs.getString("source"),
                        modelId = modelId,
                        updatedAt = rs.getLong("updated_at"),
                    ) else null
                }
            }
        }

    override suspend fun booksMissingGenre(modelId: String, limit: Int): List<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT c.book_id
                FROM chapter_chunks c
                WHERE c.model_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM book_genre g
                    WHERE g.book_id = c.book_id AND g.model_id = ?
                  )
                LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setString(2, modelId)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override suspend fun setSubjects(bookId: String, subjects: List<String>) {
        val encoded = json.encodeToString(ListSerializer(String.serializer()), subjects)
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO book_subjects (book_id, subjects) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.setString(2, encoded)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun subjectsFor(bookId: String): List<String> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT subjects FROM book_subjects WHERE book_id = ?").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val raw = rs.getString(1)
                        runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
                            .getOrDefault(emptyList())
                    } else emptyList()
                }
            }
        }

    override suspend fun clearForModel(modelId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM book_genre WHERE model_id = ?").use { stmt ->
                stmt.setString(1, modelId)
                stmt.executeUpdate()
            }
        }

    override suspend fun genreCount(modelId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM book_genre WHERE model_id = ?").use { stmt ->
                stmt.setString(1, modelId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
}
