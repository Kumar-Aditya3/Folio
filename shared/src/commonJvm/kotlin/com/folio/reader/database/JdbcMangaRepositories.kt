package com.folio.reader.database

import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaDownload
import com.folio.reader.manga.MangaDownloadStatus
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaHistoryItem
import com.folio.reader.manga.MangaNote
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.manga.MangaStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC repositories for the manga side of Folio (completely separate from the book
 * tables). All methods are serialized through the shared [Database] connection.
 */

private val mangaJson = Json { ignoreUnknownKeys = true }
private val stringListSerializer = ListSerializer(String.serializer())

class JdbcMangaRepository(private val db: Database) : com.folio.reader.manga.MangaRepository {

    override suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO manga_library (
                    id, source_id, source_name, url, title, author, artist, description,
                    genres, status, thumbnail_url, cover_path, favorite, initialized,
                    added_at, updated_at, last_read_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, manga.id)
                stmt.setLong(2, manga.sourceId)
                stmt.setString(3, manga.sourceName)
                stmt.setString(4, manga.url)
                stmt.setString(5, manga.title)
                stmt.setString(6, manga.author)
                stmt.setString(7, manga.artist)
                stmt.setString(8, manga.description)
                stmt.setString(9, mangaJson.encodeToString(stringListSerializer, manga.genres))
                stmt.setInt(10, manga.status.value)
                stmt.setString(11, manga.thumbnailUrl)
                stmt.setString(12, manga.coverPath)
                stmt.setInt(13, if (manga.inLibrary) 1 else 0)
                stmt.setInt(14, if (manga.initialized) 1 else 0)
                stmt.setLong(15, manga.addedAt.toEpochMilliseconds())
                stmt.setLong(16, manga.updatedAt.toEpochMilliseconds())
                stmt.setObject(17, manga.lastReadAt?.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) {
            db.onEntityChanged?.invoke(
                "manga",
                manga.id,
                "UPSERT",
                mangaJson.encodeToString(com.folio.reader.firebase.FsManga.serializer(), manga.toFs())
            )
        }
        db.bumpMangaData()
    }

    override suspend fun delete(mangaId: String, emitSyncEvent: Boolean) {
        val existing = get(mangaId)
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_category_map WHERE manga_id = ?").use {
                it.setString(1, mangaId); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM manga_history WHERE manga_id = ?").use {
                it.setString(1, mangaId); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM manga_library WHERE id = ?").use {
                it.setString(1, mangaId); it.executeUpdate()
            }
        }
        if (emitSyncEvent && existing != null) {
            db.onEntityChanged?.invoke(
                "manga",
                mangaId,
                "DELETE",
                mangaJson.encodeToString(
                    com.folio.reader.firebase.FsManga.serializer(),
                    existing.toFs().copy(isDeleted = true, updatedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                )
            )
        }
        db.bumpMangaData()
    }

    override suspend fun get(mangaId: String): MangaEntry? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM manga_library WHERE id = ?").use { stmt ->
            stmt.setString(1, mangaId)
            stmt.executeQuery().use { rs -> if (rs.next()) mapManga(rs) else null }
        }
    }

    override suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM manga_library WHERE source_id = ? AND url = ?").use { stmt ->
            stmt.setLong(1, sourceId)
            stmt.setString(2, url)
            stmt.executeQuery().use { rs -> if (rs.next()) mapManga(rs) else null }
        }
    }

    /** The library screen's list: manga the user explicitly added. */
    override fun observeLibrary(): Flow<List<MangaEntry>> =
        db.mangaDataRevision.map { queryManga(favoritesOnly = true) }

    /** Every known manga, including browse-opened ones not added to the library. */
    override fun observeAll(): Flow<List<MangaEntry>> =
        db.mangaDataRevision.map { queryManga(favoritesOnly = false) }

    private suspend fun queryManga(favoritesOnly: Boolean): List<MangaEntry> =
        db.withConnection { conn ->
            val sql = "SELECT * FROM manga_library" +
                (if (favoritesOnly) " WHERE favorite = 1" else "") +
                " ORDER BY title COLLATE NOCASE"
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val list = mutableListOf<MangaEntry>()
                    while (rs.next()) list += mapManga(rs)
                    list
                }
            }
        }

    override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_library SET favorite = ? WHERE id = ?").use {
                it.setInt(1, if (inLibrary) 1 else 0)
                it.setString(2, mangaId)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    override suspend fun setCoverPath(mangaId: String, coverPath: String?) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_library SET cover_path = ? WHERE id = ?").use {
                it.setString(1, coverPath)
                it.setString(2, mangaId)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    override suspend fun touchLastRead(mangaId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_library SET last_read_at = ? WHERE id = ?").use {
                it.setLong(1, Clock.System.now().toEpochMilliseconds())
                it.setString(2, mangaId)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    private fun mapManga(rs: ResultSet): MangaEntry {
        val genres = runCatching {
            mangaJson.decodeFromString(stringListSerializer, rs.getString("genres") ?: "[]")
        }.getOrDefault(emptyList())
        return MangaEntry(
            id = rs.getString("id"),
            sourceId = rs.getLong("source_id"),
            sourceName = rs.getString("source_name"),
            url = rs.getString("url"),
            title = rs.getString("title"),
            author = rs.getString("author"),
            artist = rs.getString("artist"),
            description = rs.getString("description"),
            genres = genres,
            status = MangaStatus.fromValue(rs.getInt("status")),
            thumbnailUrl = rs.getString("thumbnail_url"),
            coverPath = rs.getString("cover_path"),
            inLibrary = rs.getInt("favorite") == 1,
            initialized = rs.getInt("initialized") == 1,
            addedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("added_at")),
            updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
            lastReadAt = rs.getLong("last_read_at").takeIf { it > 0 }
                ?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
        )
    }

    private fun MangaEntry.toFs() = com.folio.reader.firebase.FsManga(
        id = id,
        sourceId = sourceId,
        sourceName = sourceName,
        url = url,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genres = mangaJson.encodeToString(stringListSerializer, genres),
        status = status.value,
        thumbnailUrl = thumbnailUrl,
        // Wire/DB field keeps its historical name; the domain calls it inLibrary.
        favorite = inLibrary,
        initialized = initialized,
        addedAt = addedAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        deviceId = "",
    )
}

class JdbcMangaChapterRepository(private val db: Database) : com.folio.reader.manga.MangaChapterRepository {

    override suspend fun replaceChapters(mangaId: String, chapters: List<MangaChapter>) {
        db.withConnection { conn ->
            val previousRead = mutableMapOf<String, Pair<Boolean, Int>>()
            val previousUpdated = mutableMapOf<String, Long>()
            val previousTotal = mutableMapOf<String, Int>()
            val previousBookmarked = mutableMapOf<String, Boolean>()
            val previousDownloaded = mutableMapOf<String, Int>()
            conn.prepareStatement("SELECT url, read, last_page_read, bookmarked, downloaded_pages, updated_at, total_pages FROM manga_chapters WHERE manga_id = ?").use { stmt ->
                stmt.setString(1, mangaId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        previousRead[rs.getString("url")] = (rs.getInt("read") == 1) to rs.getInt("last_page_read")
                        previousUpdated[rs.getString("url")] = rs.getLong("updated_at")
                        previousTotal[rs.getString("url")] = rs.getInt("total_pages")
                        previousBookmarked[rs.getString("url")] = rs.getInt("bookmarked") == 1
                        previousDownloaded[rs.getString("url")] = rs.getInt("downloaded_pages")
                    }
                }
            }
            conn.prepareStatement("DELETE FROM manga_chapters WHERE manga_id = ?").use {
                it.setString(1, mangaId); it.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO manga_chapters (
                    id, manga_id, url, name, scanlator, chapter_number, date_upload,
                    sort_order, read, bookmarked, last_page_read, downloaded_pages, updated_at, total_pages
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                chapters.forEachIndexed { index, chapter ->
                    val prev = previousRead[chapter.url]
                    stmt.setString(1, chapter.id)
                    stmt.setString(2, mangaId)
                    stmt.setString(3, chapter.url)
                    stmt.setString(4, chapter.name)
                    stmt.setString(5, chapter.scanlator)
                    stmt.setFloat(6, chapter.chapterNumber)
                    stmt.setLong(7, chapter.dateUpload)
                    stmt.setInt(8, index)
                    stmt.setInt(9, if (prev?.first == true) 1 else 0)
                    stmt.setInt(10, if (previousBookmarked[chapter.url] == true) 1 else 0)
                    stmt.setInt(11, prev?.second ?: 0)
                    stmt.setInt(12, previousDownloaded[chapter.url] ?: chapter.downloadedPages)
                    stmt.setLong(13, previousUpdated[chapter.url] ?: now)
                    stmt.setInt(14, previousTotal[chapter.url] ?: chapter.totalPages)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        db.bumpMangaData()
    }

    override suspend fun getChapters(mangaId: String): List<MangaChapter> = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM manga_chapters WHERE manga_id = ? ORDER BY sort_order").use { stmt ->
            stmt.setString(1, mangaId)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<MangaChapter>()
                while (rs.next()) list += mapChapter(rs)
                list
            }
        }
    }

    override suspend fun getChapter(chapterId: String): MangaChapter? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM manga_chapters WHERE id = ?").use { stmt ->
            stmt.setString(1, chapterId)
            stmt.executeQuery().use { rs -> if (rs.next()) mapChapter(rs) else null }
        }
    }

    override suspend fun markRead(chapterIds: List<String>, read: Boolean, emitSyncEvent: Boolean) {
        if (chapterIds.isEmpty()) return
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            val placeholders = chapterIds.joinToString(",") { "?" }
            // Unread restores never-read semantics: clear the saved page position too,
            // so badges, library progress and resume all treat the chapter as unseen.
            val sql = if (read) {
                "UPDATE manga_chapters SET read = ?, updated_at = ? WHERE id IN ($placeholders)"
            } else {
                "UPDATE manga_chapters SET read = ?, last_page_read = 0, updated_at = ? WHERE id IN ($placeholders)"
            }
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, if (read) 1 else 0)
                stmt.setLong(2, now)
                chapterIds.forEachIndexed { i, id -> stmt.setString(i + 3, id) }
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) chapterIds.forEach { emitChapterEvent(it) }
    }

    override suspend fun setBookmarked(chapterId: String, bookmarked: Boolean, emitSyncEvent: Boolean) {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_chapters SET bookmarked = ?, updated_at = ? WHERE id = ?").use {
                it.setInt(1, if (bookmarked) 1 else 0)
                it.setLong(2, now)
                it.setString(3, chapterId)
                it.executeUpdate()
            }
        }
        if (emitSyncEvent) emitChapterEvent(chapterId)
    }

    override suspend fun saveProgress(chapterId: String, lastPage: Int, totalPages: Int, emitSyncEvent: Boolean) {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE manga_chapters SET last_page_read = ?, total_pages = ?, updated_at = ? WHERE id = ?"
            ).use {
                it.setInt(1, lastPage)
                it.setInt(2, totalPages)
                it.setLong(3, now)
                it.setString(4, chapterId)
                it.executeUpdate()
            }
        }
        if (emitSyncEvent) emitChapterEvent(chapterId)
    }

    override suspend fun applyRemoteState(
        chapterId: String,
        read: Boolean,
        bookmarked: Boolean,
        lastPageRead: Int,
        updatedAt: kotlinx.datetime.Instant,
        emitSyncEvent: Boolean,
        totalPages: Int,
    ) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE manga_chapters SET read = ?, bookmarked = ?, last_page_read = ?, total_pages = ?, updated_at = ? WHERE id = ?"
            ).use {
                it.setInt(1, if (read) 1 else 0)
                it.setInt(2, if (bookmarked) 1 else 0)
                it.setInt(3, lastPageRead)
                it.setInt(4, totalPages)
                it.setLong(5, updatedAt.toEpochMilliseconds())
                it.setString(6, chapterId)
                it.executeUpdate()
            }
        }
        if (emitSyncEvent) emitChapterEvent(chapterId)
    }

    private suspend fun emitChapterEvent(chapterId: String) {
        db.bumpMangaData()
        val chapter = getChapter(chapterId) ?: return
        db.onEntityChanged?.invoke(
            "manga_chapter",
            chapterId,
            "UPSERT",
            mangaJson.encodeToString(
                com.folio.reader.firebase.FsMangaChapter.serializer(),
                com.folio.reader.firebase.FsMangaChapter(
                    id = chapter.id,
                    mangaId = chapter.mangaId,
                    url = chapter.url,
                    name = chapter.name,
                    read = chapter.read,
                    bookmarked = chapter.bookmarked,
                    lastPageRead = chapter.lastPageRead,
                    totalPages = chapter.totalPages,
                    updatedAt = chapter.updatedAt.toEpochMilliseconds(),
                    deviceId = "",
                )
            )
        )
    }

    override suspend fun setDownloadedPages(chapterId: String, pages: Int) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_chapters SET downloaded_pages = ? WHERE id = ?").use {
                it.setInt(1, pages)
                it.setString(2, chapterId)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    override fun observeUnreadCounts(): Flow<Map<String, Int>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT manga_id, COUNT(*) AS unread FROM manga_chapters WHERE read = 0 GROUP BY manga_id"
                    ).use { rs ->
                        val map = mutableMapOf<String, Int>()
                        while (rs.next()) map[rs.getString("manga_id")] = rs.getInt("unread")
                        map
                    }
                }
            }
        }

    override fun observeProgress(): Flow<Map<String, Float>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT manga_id, SUM(CASE WHEN read = 1 THEN 1 ELSE 0 END) * 1.0 / COUNT(*) AS frac " +
                            "FROM manga_chapters GROUP BY manga_id"
                    ).use { rs ->
                        val map = mutableMapOf<String, Float>()
                        while (rs.next()) map[rs.getString("manga_id")] = rs.getDouble("frac").toFloat()
                        map
                    }
                }
            }
        }

    override fun observeLastRead(): Flow<Map<String, com.folio.reader.manga.MangaLastRead>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT c.manga_id, c.name, c.last_page_read, c.total_pages FROM manga_chapters c " +
                            "JOIN (SELECT manga_id, MAX(updated_at) mu FROM manga_chapters WHERE last_page_read > 0 GROUP BY manga_id) t " +
                            "ON c.manga_id = t.manga_id AND c.updated_at = t.mu"
                    ).use { rs ->
                        val map = mutableMapOf<String, com.folio.reader.manga.MangaLastRead>()
                        while (rs.next()) {
                            map[rs.getString("manga_id")] = com.folio.reader.manga.MangaLastRead(
                                chapterName = rs.getString("name"),
                                lastPage = rs.getInt("last_page_read"),
                                totalPages = rs.getInt("total_pages"),
                            )
                        }
                        map
                    }
                }
            }
        }

    override fun observeDownloadedCounts(): Flow<Map<String, Int>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT manga_id, COUNT(*) FROM manga_chapters WHERE downloaded_pages > 0 GROUP BY manga_id"
                    ).use { rs ->
                        val map = mutableMapOf<String, Int>()
                        while (rs.next()) map[rs.getString("manga_id")] = rs.getInt(2)
                        map
                    }
                }
            }
        }

    override suspend fun markAllReadForManga(mangaId: String, read: Boolean) {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            val sql = if (read) {
                "UPDATE manga_chapters SET read = ?, updated_at = ? WHERE manga_id = ?"
            } else {
                "UPDATE manga_chapters SET read = ?, last_page_read = 0, updated_at = ? WHERE manga_id = ?"
            }
            conn.prepareStatement(sql).use {
                it.setInt(1, if (read) 1 else 0)
                it.setLong(2, now)
                it.setString(3, mangaId)
                it.executeUpdate()
            }
        }
        val ids = db.withConnection { conn ->
            val out = mutableListOf<String>()
            conn.prepareStatement("SELECT id FROM manga_chapters WHERE manga_id = ?").use { st ->
                st.setString(1, mangaId)
                st.executeQuery().use { rs -> while (rs.next()) out += rs.getString("id") }
            }
            out
        }
        ids.forEach { emitChapterEvent(it) }
    }

    private fun mapChapter(rs: ResultSet) = MangaChapter(
        id = rs.getString("id"),
        mangaId = rs.getString("manga_id"),
        url = rs.getString("url"),
        name = rs.getString("name"),
        scanlator = rs.getString("scanlator"),
        chapterNumber = rs.getFloat("chapter_number"),
        dateUpload = rs.getLong("date_upload"),
        sortOrder = rs.getInt("sort_order"),
        read = rs.getInt("read") == 1,
        bookmarked = rs.getInt("bookmarked") == 1,
        lastPageRead = rs.getInt("last_page_read"),
        downloadedPages = rs.getInt("downloaded_pages"),
        totalPages = rs.getInt("total_pages"),
        updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(
            rs.getLong("updated_at").takeIf { it > 0 } ?: kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        ),
    )
}

class JdbcMangaCategoryRepository(private val db: Database) : com.folio.reader.manga.MangaCategoryRepository {

    override suspend fun create(name: String): MangaCategory {
        val category = MangaCategory(id = UUID.randomUUID().toString(), name = name)
        db.withConnection { conn ->
            conn.prepareStatement("INSERT INTO manga_categories (id, name, sort_order) VALUES (?, ?, ?)").use {
                it.setString(1, category.id)
                it.setString(2, category.name)
                it.setInt(3, category.sortOrder)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
        return category
    }

    override suspend fun rename(id: String, name: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_categories SET name = ? WHERE id = ?").use {
                it.setString(1, name)
                it.setString(2, id)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    override suspend fun delete(id: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_category_map WHERE category_id = ?").use {
                it.setString(1, id); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM manga_categories WHERE id = ?").use {
                it.setString(1, id); it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    override fun observeCategories(): Flow<List<MangaCategory>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM manga_categories ORDER BY sort_order, name").use { rs ->
                        val list = mutableListOf<MangaCategory>()
                        while (rs.next()) {
                            list += MangaCategory(rs.getString("id"), rs.getString("name"), rs.getInt("sort_order"))
                        }
                        list
                    }
                }
            }
        }

    override suspend fun assign(mangaId: String, categoryIds: Set<String>) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_category_map WHERE manga_id = ?").use {
                it.setString(1, mangaId); it.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO manga_category_map (manga_id, category_id) VALUES (?, ?)").use { stmt ->
                categoryIds.forEach { id ->
                    stmt.setString(1, mangaId)
                    stmt.setString(2, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        db.bumpMangaData()
    }

    override suspend fun categoriesFor(mangaId: String): Set<String> = db.withConnection { conn ->
        conn.prepareStatement("SELECT category_id FROM manga_category_map WHERE manga_id = ?").use { stmt ->
            stmt.setString(1, mangaId)
            stmt.executeQuery().use { rs ->
                val set = mutableSetOf<String>()
                while (rs.next()) set += rs.getString("category_id")
                set
            }
        }
    }

    override suspend fun mangaIdsInCategory(categoryId: String): Set<String> = db.withConnection { conn ->
        conn.prepareStatement("SELECT manga_id FROM manga_category_map WHERE category_id = ?").use { stmt ->
            stmt.setString(1, categoryId)
            stmt.executeQuery().use { rs ->
                val set = mutableSetOf<String>()
                while (rs.next()) set += rs.getString("manga_id")
                set
            }
        }
    }
}

class JdbcMangaHistoryRepository(private val db: Database) : com.folio.reader.manga.MangaHistoryRepository {

    override suspend fun record(mangaId: String, chapterId: String?) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO manga_history (manga_id, chapter_id, read_at) VALUES (?, ?, ?)"
            ).use {
                it.setString(1, mangaId)
                it.setString(2, chapterId)
                it.setLong(3, Clock.System.now().toEpochMilliseconds())
                it.executeUpdate()
            }
        }
    }

    override fun observeRecent(limit: Int): Flow<List<MangaHistoryItem>> = flow {
        emit(
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT h.manga_id AS manga_id, h.chapter_id AS chapter_id, h.read_at AS read_at,
                           m.title AS manga_title, c.name AS chapter_name
                    FROM manga_history h
                    JOIN manga_library m ON m.id = h.manga_id
                    LEFT JOIN manga_chapters c ON c.id = h.chapter_id
                    ORDER BY h.read_at DESC
                    LIMIT ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setInt(1, limit)
                    stmt.executeQuery().use { rs ->
                        val list = mutableListOf<MangaHistoryItem>()
                        while (rs.next()) {
                            list += MangaHistoryItem(
                                mangaId = rs.getString("manga_id"),
                                chapterId = rs.getString("chapter_id"),
                                readAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("read_at")),
                                chapterName = rs.getString("chapter_name"),
                            )
                        }
                        list
                    }
                }
            }
        )
    }

    override suspend fun clear() {
        db.withConnection { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM manga_history") }
        }
    }
}

class JdbcMangaDownloadRepository(private val db: Database) : com.folio.reader.manga.MangaDownloadRepository {

    override suspend fun enqueue(download: MangaDownload) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO manga_downloads (
                    id, manga_id, chapter_id, status, total_pages, downloaded_pages, queued_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, download.id)
                stmt.setString(2, download.mangaId)
                stmt.setString(3, download.chapterId)
                stmt.setInt(4, download.status.value)
                stmt.setInt(5, download.totalPages)
                stmt.setInt(6, download.downloadedPages)
                stmt.setLong(7, download.queuedAt.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun update(download: MangaDownload) = enqueue(download)

    override suspend fun remove(id: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_downloads WHERE id = ?").use {
                it.setString(1, id); it.executeUpdate()
            }
        }
    }

    override suspend fun clearFinished() {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_downloads WHERE status IN (?, ?)").use {
                it.setInt(1, MangaDownloadStatus.DOWNLOADED.value)
                it.setInt(2, MangaDownloadStatus.ERROR.value)
                it.executeUpdate()
            }
        }
    }

    override fun observeQueue(): Flow<List<MangaDownload>> = flow {
        emit(
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM manga_downloads ORDER BY queued_at").use { rs ->
                        val list = mutableListOf<MangaDownload>()
                        while (rs.next()) {
                            list += MangaDownload(
                                id = rs.getString("id"),
                                mangaId = rs.getString("manga_id"),
                                chapterId = rs.getString("chapter_id"),
                                status = MangaDownloadStatus.fromValue(rs.getInt("status")),
                                totalPages = rs.getInt("total_pages"),
                                downloadedPages = rs.getInt("downloaded_pages"),
                                queuedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("queued_at")),
                            )
                        }
                        list
                    }
                }
            }
        )
    }

    override suspend fun isChapterDownloaded(chapterId: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement("SELECT 1 FROM manga_downloads WHERE chapter_id = ? AND status = ?").use { stmt ->
            stmt.setString(1, chapterId)
            stmt.setInt(2, MangaDownloadStatus.DOWNLOADED.value)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }
}

class JdbcMangaNoteRepository(private val db: Database) : com.folio.reader.manga.MangaNoteRepository {

    override suspend fun upsert(note: MangaNote, emitSyncEvent: Boolean) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO manga_notes (
                    id, manga_id, chapter_id, page_index, content, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, note.id)
                stmt.setString(2, note.mangaId)
                stmt.setString(3, note.chapterId)
                stmt.setInt(4, note.pageIndex)
                stmt.setString(5, note.content)
                stmt.setLong(6, note.createdAt.toEpochMilliseconds())
                stmt.setLong(7, note.updatedAt.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
        if (emitSyncEvent) {
            db.onEntityChanged?.invoke(
                "manga_note",
                note.id,
                "UPSERT",
                mangaJson.encodeToString(
                    com.folio.reader.firebase.FsMangaNote.serializer(),
                    com.folio.reader.firebase.FsMangaNote(
                        id = note.id,
                        mangaId = note.mangaId,
                        chapterId = note.chapterId,
                        pageIndex = note.pageIndex,
                        content = note.content,
                        createdAt = note.createdAt.toEpochMilliseconds(),
                        updatedAt = note.updatedAt.toEpochMilliseconds(),
                        deviceId = "",
                    )
                )
            )
        }
    }

    override suspend fun delete(noteId: String, emitSyncEvent: Boolean) {
        val existing = get(noteId)
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_notes WHERE id = ?").use {
                it.setString(1, noteId); it.executeUpdate()
            }
        }
        if (emitSyncEvent && existing != null) {
            db.onEntityChanged?.invoke(
                "manga_note",
                noteId,
                "DELETE",
                mangaJson.encodeToString(
                    com.folio.reader.firebase.FsMangaNote.serializer(),
                    com.folio.reader.firebase.FsMangaNote(
                        id = existing.id,
                        mangaId = existing.mangaId,
                        chapterId = existing.chapterId,
                        pageIndex = existing.pageIndex,
                        content = existing.content,
                        createdAt = existing.createdAt.toEpochMilliseconds(),
                        updatedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                        deviceId = "",
                        isDeleted = true,
                    )
                )
            )
        }
    }

    override suspend fun get(noteId: String): MangaNote? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM manga_notes WHERE id = ?").use { stmt ->
            stmt.setString(1, noteId)
            stmt.executeQuery().use { rs -> if (rs.next()) mapNote(rs) else null }
        }
    }

    override suspend fun notesForChapter(chapterId: String): List<MangaNote> = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM manga_notes WHERE chapter_id = ? ORDER BY page_index").use { stmt ->
            stmt.setString(1, chapterId)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<MangaNote>()
                while (rs.next()) list += mapNote(rs)
                list
            }
        }
    }

    override fun observeNotesForChapter(chapterId: String): Flow<List<MangaNote>> = flow {
        emit(notesForChapter(chapterId))
    }

    private fun mapNote(rs: ResultSet) = MangaNote(
        id = rs.getString("id"),
        mangaId = rs.getString("manga_id"),
        chapterId = rs.getString("chapter_id"),
        pageIndex = rs.getInt("page_index"),
        content = rs.getString("content"),
        createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
    )
}

class JdbcMangaStatisticsRepository(private val db: Database) : com.folio.reader.manga.MangaStatisticsRepository {

    override suspend fun getStatistics(): com.folio.reader.manga.MangaStatistics = db.withConnection { conn ->
        var libraryCount = 0; var completedCount = 0
        conn.prepareStatement("SELECT COUNT(*), SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) FROM manga_library WHERE favorite = 1").use { st ->
            st.executeQuery().use { rs -> if (rs.next()) { libraryCount = rs.getInt(1); completedCount = rs.getInt(2) } }
        }
        var readChapters = 0; var unreadChapters = 0; var downloadedChapters = 0; var bookmarkedChapters = 0
        conn.createStatement().use { st ->
            st.executeQuery("SELECT SUM(CASE WHEN read=1 THEN 1 ELSE 0 END), SUM(CASE WHEN read=0 THEN 1 ELSE 0 END), SUM(CASE WHEN downloaded_pages>0 THEN 1 ELSE 0 END), SUM(CASE WHEN bookmarked=1 THEN 1 ELSE 0 END) FROM manga_chapters").use { rs ->
                if (rs.next()) { readChapters = rs.getInt(1); unreadChapters = rs.getInt(2); downloadedChapters = rs.getInt(3); bookmarkedChapters = rs.getInt(4) }
            }
        }
        var notesCount = 0
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM manga_notes").use { rs -> if (rs.next()) notesCount = rs.getInt(1) } }
        var totalReadMinutes = 0L
        conn.prepareStatement("SELECT COALESCE(SUM(s.duration_ms),0)/60000 FROM reading_sessions s JOIN manga_library m ON m.id = s.book_id").use { st ->
            st.executeQuery().use { rs -> if (rs.next()) totalReadMinutes = rs.getLong(1) }
        }
        val week = MutableList(7) { 0 }
        val labels = MutableList(7) { "" }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("E")
        for (i in 0 until 7) {
            val day = java.time.LocalDate.now().minusDays(6 - i.toLong())
            labels[i] = day.format(fmt)
            conn.prepareStatement("SELECT COUNT(*) FROM manga_chapters WHERE read = 1 AND date(updated_at/1000,'unixepoch','localtime') = ?").use { st ->
                st.setString(1, day.toString())
                st.executeQuery().use { rs -> if (rs.next()) week[i] = rs.getInt(1) }
            }
        }
        val top = mutableListOf<com.folio.reader.manga.MangaTopEntry>()
        conn.prepareStatement("SELECT c.manga_id, m.title, COUNT(*) c FROM manga_chapters c JOIN manga_library m ON m.id = c.manga_id WHERE c.read = 1 GROUP BY c.manga_id ORDER BY c DESC LIMIT 5").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) top += com.folio.reader.manga.MangaTopEntry(rs.getString(1), rs.getString(2), rs.getInt(3)) }
        }
        com.folio.reader.manga.MangaStatistics(
            libraryCount = libraryCount, completedCount = completedCount, readChapters = readChapters,
            unreadChapters = unreadChapters, downloadedChapters = downloadedChapters, bookmarkedChapters = bookmarkedChapters,
            notesCount = notesCount, totalReadMinutes = totalReadMinutes, weekReadChapters = week, weekLabels = labels, topManga = top,
        )
    }
}

/**
 * DDL for the manga tables, invoked from [Database.initializeSchema]. Kept separate so the
 * book schema stays untouched.
 */
object MangaSchema {
    fun initialize(conn: Connection) {
        fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_library (
                id TEXT PRIMARY KEY,
                source_id INTEGER NOT NULL,
                source_name TEXT NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT,
                artist TEXT,
                description TEXT,
                genres TEXT NOT NULL DEFAULT '[]',
                status INTEGER NOT NULL DEFAULT 0,
                thumbnail_url TEXT,
                cover_path TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                initialized INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_read_at INTEGER
            )
            """.trimIndent()
        )
        exec("CREATE INDEX IF NOT EXISTS idx_manga_library_source ON manga_library(source_id, url)")
        exec("CREATE INDEX IF NOT EXISTS idx_manga_library_favorite ON manga_library(favorite)")

        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_chapters (
                id TEXT PRIMARY KEY,
                manga_id TEXT NOT NULL,
                url TEXT NOT NULL,
                name TEXT NOT NULL,
                scanlator TEXT,
                chapter_number REAL NOT NULL DEFAULT -1,
                date_upload INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                read INTEGER NOT NULL DEFAULT 0,
                bookmarked INTEGER NOT NULL DEFAULT 0,
                last_page_read INTEGER NOT NULL DEFAULT 0,
                downloaded_pages INTEGER NOT NULL DEFAULT 0,
                total_pages INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        exec("CREATE INDEX IF NOT EXISTS idx_manga_chapters_manga ON manga_chapters(manga_id)")
        runCatching {
            conn.createStatement().executeQuery("SELECT updated_at FROM manga_chapters LIMIT 0").close()
        }.onFailure {
            conn.createStatement().use {
                it.execute("ALTER TABLE manga_chapters ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            }
        }
        runCatching {
            conn.createStatement().executeQuery("SELECT total_pages FROM manga_chapters LIMIT 0").close()
        }.onFailure {
            conn.createStatement().use {
                it.execute("ALTER TABLE manga_chapters ADD COLUMN total_pages INTEGER NOT NULL DEFAULT 0")
            }
        }
        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_notes (
                id TEXT PRIMARY KEY,
                manga_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                page_index INTEGER NOT NULL DEFAULT 0,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        exec("CREATE INDEX IF NOT EXISTS idx_manga_notes_chapter ON manga_notes(chapter_id)")

        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_categories (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_category_map (
                manga_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                PRIMARY KEY (manga_id, category_id)
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_history (
                manga_id TEXT PRIMARY KEY,
                chapter_id TEXT,
                read_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE IF NOT EXISTS manga_downloads (
                id TEXT PRIMARY KEY,
                manga_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                total_pages INTEGER NOT NULL DEFAULT 0,
                downloaded_pages INTEGER NOT NULL DEFAULT 0,
                queued_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
