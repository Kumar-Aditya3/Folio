package com.folio.reader.database

import com.folio.reader.model.Quote
import com.folio.reader.model.RevisitItem
import com.folio.reader.model.RevisitType
import com.folio.reader.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

private val repoJson = Json { ignoreUnknownKeys = true }

class JdbcTagRepository(private val db: Database) : TagRepository {

    override suspend fun insertTag(tag: Tag, emitSyncEvent: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO tags (id, name, color, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, tag.id)
                stmt.setString(2, tag.name)
                if (tag.color == null) stmt.setNull(3, java.sql.Types.INTEGER) else stmt.setInt(3, tag.color)
                stmt.setLong(4, tag.createdAt.toEpochMilliseconds())
                stmt.setLong(5, tag.updatedAt.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.onEntityChanged?.invoke("tag", tag.id, "UPSERT", repoJson.encodeToString(Tag.serializer(), tag))
    }

    override suspend fun updateTag(tag: Tag, emitSyncEvent: Boolean): Unit = insertTag(tag, emitSyncEvent)

    override suspend fun deleteTag(tagId: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM book_tags WHERE tag_id = ?").use {
                it.setString(1, tagId); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM highlight_tags WHERE tag_id = ?").use {
                it.setString(1, tagId); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM tags WHERE id = ?").use {
                it.setString(1, tagId); it.executeUpdate()
            }
        }
        db.onEntityChanged?.invoke("tag", tagId, "DELETE", "{}")
    }

    private fun mapTag(rs: java.sql.ResultSet) = Tag(
        id = rs.getString("id"),
        name = rs.getString("name"),
        color = (rs.getObject("color") as? Number)?.toInt(),
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        // Legacy rows carry 0 until first edited; treat that as "as old as creation".
        updatedAt = rs.getLong("updated_at").takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
            ?: Instant.fromEpochMilliseconds(rs.getLong("created_at"))
    )

    override suspend fun getAllTags(): Flow<List<Tag>> = flow {
        val tags = db.withConnection { conn ->
            val out = mutableListOf<Tag>()
            conn.prepareStatement("SELECT * FROM tags ORDER BY name").use { stmt ->
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapTag(rs)) }
            }
            out
        }
        emit(tags)
    }

    override suspend fun getTagsForBook(bookId: String): List<Tag> = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            val out = mutableListOf<Tag>()
            conn.prepareStatement(
                """
                SELECT t.* FROM tags t
                JOIN book_tags bt ON bt.tag_id = t.id
                WHERE bt.book_id = ? ORDER BY t.name
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapTag(rs)) }
            }
            out
        }
    }

    override suspend fun getTagsForHighlight(highlightId: String): List<Tag> = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            val out = mutableListOf<Tag>()
            conn.prepareStatement(
                """
                SELECT t.* FROM tags t
                JOIN highlight_tags ht ON ht.tag_id = t.id
                WHERE ht.highlight_id = ? ORDER BY t.name
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, highlightId)
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapTag(rs)) }
            }
            out
        }
    }

    override suspend fun addTagToBook(bookId: String, tagId: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("INSERT OR IGNORE INTO book_tags (book_id, tag_id) VALUES (?, ?)").use {
                it.setString(1, bookId); it.setString(2, tagId); it.executeUpdate()
            }
        }
    }

    override suspend fun removeTagFromBook(bookId: String, tagId: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM book_tags WHERE book_id = ? AND tag_id = ?").use {
                it.setString(1, bookId); it.setString(2, tagId); it.executeUpdate()
            }
        }
    }

    override suspend fun addTagToHighlight(highlightId: String, tagId: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("INSERT OR IGNORE INTO highlight_tags (highlight_id, tag_id) VALUES (?, ?)").use {
                it.setString(1, highlightId); it.setString(2, tagId); it.executeUpdate()
            }
        }
    }

    override suspend fun removeTagFromHighlight(highlightId: String, tagId: String): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM highlight_tags WHERE highlight_id = ? AND tag_id = ?").use {
                it.setString(1, highlightId); it.setString(2, tagId); it.executeUpdate()
            }
        }
    }

    override suspend fun getBooksForTag(tagId: String): List<com.folio.reader.model.Book> = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            val out = mutableListOf<com.folio.reader.model.Book>()
            conn.prepareStatement(
                """
                SELECT b.* FROM books b
                JOIN book_tags bt ON bt.book_id = b.id
                WHERE bt.tag_id = ? ORDER BY b.title
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, tagId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(db.mapRowToBook(rs))
                    }
                }
            }
            out
        }
    }

    override suspend fun getHighlightsForTag(tagId: String): List<com.folio.reader.model.Highlight> = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            val out = mutableListOf<com.folio.reader.model.Highlight>()
            conn.prepareStatement(
                """
                SELECT h.* FROM highlights h
                JOIN highlight_tags ht ON ht.highlight_id = h.id
                WHERE ht.tag_id = ? ORDER BY h.created_at
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, tagId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(db.mapRowToHighlight(rs))
                    }
                }
            }
            out
        }
    }
}

class JdbcQuoteRepository(private val db: Database) : QuoteRepository {

    override suspend fun insertQuote(quote: Quote, emitSyncEvent: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO quotes
                    (id, book_id, chapter_id, highlight_id, text, note, created_at, device_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, quote.id)
                stmt.setString(2, quote.bookId)
                stmt.setString(3, quote.chapterId)
                stmt.setString(4, quote.highlightId)
                stmt.setString(5, quote.text)
                if (quote.note == null) stmt.setNull(6, java.sql.Types.VARCHAR) else stmt.setString(6, quote.note)
                stmt.setLong(7, quote.createdAt.toEpochMilliseconds())
                stmt.setString(8, quote.deviceId)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.onEntityChanged?.invoke("quote", quote.id, "UPSERT", repoJson.encodeToString(Quote.serializer(), quote))
    }

    private fun mapQuote(rs: java.sql.ResultSet) = Quote(
        id = rs.getString("id"),
        bookId = rs.getString("book_id"),
        chapterId = rs.getString("chapter_id"),
        highlightId = rs.getString("highlight_id"),
        text = rs.getString("text"),
        note = rs.getString("note"),
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        deviceId = rs.getString("device_id")
    )

    override fun getQuotesForBook(bookId: String): Flow<List<Quote>> = flow {
        val quotes = db.withConnection { conn ->
            val out = mutableListOf<Quote>()
            conn.prepareStatement("SELECT * FROM quotes WHERE book_id = ? ORDER BY created_at").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapQuote(rs)) }
            }
            out
        }
        emit(quotes)
    }

    override fun getAllQuotes(): Flow<List<Quote>> = flow {
        val quotes = db.withConnection { conn ->
            val out = mutableListOf<Quote>()
            conn.prepareStatement("SELECT * FROM quotes ORDER BY created_at DESC").use { stmt ->
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapQuote(rs)) }
            }
            out
        }
        emit(quotes)
    }
}

class JdbcRevisitRepository(private val db: Database) : RevisitRepository {

    override suspend fun insertRevisitItem(item: RevisitItem, emitSyncEvent: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO revisit_items
                    (id, book_id, chapter_id, type, source_id, note, created_at, resolved_at, device_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, item.id)
                stmt.setString(2, item.bookId)
                stmt.setString(3, item.chapterId)
                stmt.setInt(4, item.type.value)
                stmt.setString(5, item.sourceId)
                if (item.note == null) stmt.setNull(6, java.sql.Types.VARCHAR) else stmt.setString(6, item.note)
                stmt.setLong(7, item.createdAt.toEpochMilliseconds())
                if (item.resolvedAt == null) stmt.setNull(8, java.sql.Types.INTEGER) else stmt.setLong(8, item.resolvedAt.toEpochMilliseconds())
                stmt.setString(9, item.deviceId)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) db.onEntityChanged?.invoke("revisit", item.id, "UPSERT", repoJson.encodeToString(RevisitItem.serializer(), item))
    }

    private suspend fun getRevisitItem(itemId: String): RevisitItem? = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM revisit_items WHERE id = ?").use { stmt ->
                stmt.setString(1, itemId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapItem(rs) else null }
            }
        }
    }

    override suspend fun resolveRevisitItem(itemId: String, emitSyncEvent: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE revisit_items SET resolved_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                stmt.setString(2, itemId)
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) getRevisitItem(itemId)?.let { item ->
            db.onEntityChanged?.invoke("revisit", item.id, "UPSERT", repoJson.encodeToString(RevisitItem.serializer(), item))
        }
    }

    private fun mapItem(rs: java.sql.ResultSet) = RevisitItem(
        id = rs.getString("id"),
        bookId = rs.getString("book_id"),
        chapterId = rs.getString("chapter_id"),
        type = RevisitType.fromValue(rs.getInt("type")),
        sourceId = rs.getString("source_id"),
        note = rs.getString("note"),
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        resolvedAt = rs.getLong("resolved_at").takeIf { !rs.wasNull() }?.let { Instant.fromEpochMilliseconds(it) },
        deviceId = rs.getString("device_id")
    )

    override fun getUnresolvedRevisitItems(): Flow<List<RevisitItem>> = flow {
        val items = db.withConnection { conn ->
            val out = mutableListOf<RevisitItem>()
            conn.prepareStatement("SELECT * FROM revisit_items WHERE resolved_at IS NULL ORDER BY created_at DESC").use { stmt ->
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapItem(rs)) }
            }
            out
        }
        emit(items)
    }

    override suspend fun getRevisitItemsForBook(bookId: String): List<RevisitItem> = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            val out = mutableListOf<RevisitItem>()
            conn.prepareStatement("SELECT * FROM revisit_items WHERE book_id = ? ORDER BY created_at DESC").use { stmt ->
                stmt.setString(1, bookId)
                stmt.executeQuery().use { rs -> while (rs.next()) out.add(mapItem(rs)) }
            }
            out
        }
    }

    override suspend fun getRevisitItemCount(): Int = withContext(Dispatchers.IO) {
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM revisit_items").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }
}
