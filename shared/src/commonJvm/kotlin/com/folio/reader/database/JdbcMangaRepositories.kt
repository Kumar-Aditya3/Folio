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
import kotlinx.coroutines.flow.MutableStateFlow
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
        // Touch updated_at too: the favorite flag rides the manga document and
        // last-write-wins needs a fresh stamp or the change loses to stale remotes.
        val now = Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_library SET favorite = ?, updated_at = ? WHERE id = ?").use {
                it.setInt(1, if (inLibrary) 1 else 0)
                it.setLong(2, now)
                it.setString(3, mangaId)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
        get(mangaId)?.let { manga ->
            db.onEntityChanged?.invoke(
                "manga",
                mangaId,
                "UPSERT",
                mangaJson.encodeToString(com.folio.reader.firebase.FsManga.serializer(), manga.toFs())
            )
        }
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
            if (read) {
                chapterIds.forEach { cid ->
                    conn.prepareStatement("SELECT manga_id, name FROM manga_chapters WHERE id = ?").use { q ->
                        q.setString(1, cid)
                        q.executeQuery().use { rs ->
                            if (rs.next()) upsertHistoryRow(conn, rs.getString("manga_id"), rs.getString("name"))
                        }
                    }
                }
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
            if (read) {
                conn.prepareStatement("SELECT manga_id, name FROM manga_chapters WHERE id = ?").use { q ->
                    q.setString(1, chapterId)
                    q.executeQuery().use { rs ->
                        if (rs.next()) upsertHistoryRow(conn, rs.getString("manga_id"), rs.getString("name"))
                    }
                }
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
            if (read) {
                conn.prepareStatement("SELECT name FROM manga_chapters WHERE manga_id = ? ORDER BY sort_order DESC LIMIT 1").use { q ->
                    q.setString(1, mangaId)
                    q.executeQuery().use { rs ->
                        if (rs.next()) upsertHistoryRow(conn, mangaId, rs.getString("name"))
                    }
                }
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

    private fun upsertHistoryRow(conn: Connection, mangaId: String, chapterName: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        var title = ""
        var coverUrl: String? = null
        var coverPath: String? = null
        var sourceName: String? = null
        conn.prepareStatement("SELECT title, thumbnail_url, cover_path, source_name FROM manga_library WHERE id = ?").use { lib ->
            lib.setString(1, mangaId)
            lib.executeQuery().use { rs ->
                if (rs.next()) {
                    title = rs.getString("title") ?: ""
                    coverUrl = rs.getString("thumbnail_url")
                    coverPath = rs.getString("cover_path")
                    sourceName = rs.getString("source_name")
                }
            }
        }
        conn.prepareStatement(
            """
            INSERT INTO manga_history (manga_id, title, cover_url, cover_path, source_name, chapter_name, read_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(manga_id) DO UPDATE SET
                title = CASE WHEN excluded.title <> '' THEN excluded.title ELSE manga_history.title END,
                cover_url = COALESCE(NULLIF(excluded.cover_url, ''), manga_history.cover_url),
                cover_path = COALESCE(NULLIF(excluded.cover_path, ''), manga_history.cover_path),
                source_name = COALESCE(NULLIF(excluded.source_name, ''), manga_history.source_name),
                chapter_name = CASE WHEN excluded.chapter_name <> '' THEN excluded.chapter_name ELSE manga_history.chapter_name END,
                read_at = excluded.read_at,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, mangaId)
            stmt.setString(2, title)
            stmt.setString(3, coverUrl)
            stmt.setString(4, coverPath)
            stmt.setString(5, sourceName)
            stmt.setString(6, chapterName)
            stmt.setLong(7, now)
            stmt.setLong(8, now)
            stmt.executeUpdate()
        }
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

    override suspend fun create(name: String, emitSyncEvent: Boolean): MangaCategory {
        val category = MangaCategory(id = UUID.randomUUID().toString(), name = name)
        insertCategoryRow(category)
        db.bumpMangaData()
        if (emitSyncEvent) emitCategoryEvent(category, "UPSERT", isDeleted = false)
        return category
    }

    override suspend fun rename(id: String, name: String, emitSyncEvent: Boolean) {
        val now = kotlinx.datetime.Clock.System.now()
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE manga_categories SET name = ?, updated_at = ? WHERE id = ?").use {
                it.setString(1, name)
                it.setLong(2, now.toEpochMilliseconds())
                it.setString(3, id)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
        if (emitSyncEvent) get(id)?.let { emitCategoryEvent(it, "UPSERT", isDeleted = false) }
    }

    override suspend fun delete(id: String, emitSyncEvent: Boolean): Boolean {
        val existing = get(id) ?: return false
        // Main is the guaranteed home shelf: it can only be removed while another
        // category exists, so the library never loses its last bucket.
        if (id == MangaCategory.MAIN_ID && countCategories() <= 1) return false
        db.withTransaction { conn ->
            conn.prepareStatement("DELETE FROM manga_category_map WHERE category_id = ?").use {
                it.setString(1, id); it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM manga_categories WHERE id = ?").use {
                it.setString(1, id); it.executeUpdate()
            }
        }
        db.bumpMangaData()
        if (emitSyncEvent) {
            emitCategoryEvent(existing.copy(updatedAt = kotlinx.datetime.Clock.System.now()), "DELETE", isDeleted = true)
        }
        return true
    }

    override fun observeCategories(): Flow<List<MangaCategory>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT id, name, sort_order, updated_at FROM manga_categories ORDER BY sort_order, name"
                    ).use { rs ->
                        val list = mutableListOf<MangaCategory>()
                        while (rs.next()) list += mapCategoryRow(rs)
                        list
                    }
                }
            }
        }

    override fun observeCategoriesFor(mangaId: String): Flow<Set<String>> =
        db.mangaDataRevision.map { categoriesFor(mangaId) }

    override suspend fun assign(mangaId: String, categoryIds: Set<String>, emitSyncEvent: Boolean) {
        val previous = categoriesFor(mangaId)
        db.withTransaction { conn ->
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
        if (emitSyncEvent) {
            // Membership changed for every category the manga entered AND every one it
            // left; touch and re-emit all of them or removals never reach other devices.
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            val affected = (previous + categoryIds).filter { get(it) != null }
            if (affected.isNotEmpty()) {
                db.withConnection { conn ->
                    val placeholders = affected.joinToString(",") { "?" }
                    conn.prepareStatement("UPDATE manga_categories SET updated_at = ? WHERE id IN ($placeholders)").use { stmt ->
                        stmt.setLong(1, now)
                        affected.forEachIndexed { i, cid -> stmt.setString(i + 2, cid) }
                        stmt.executeUpdate()
                    }
                }
                db.bumpMangaData()
                affected.forEach { categoryId ->
                    get(categoryId)?.let { emitCategoryEvent(it, "UPSERT", isDeleted = false) }
                }
            }
        }
    }

    override suspend fun applyRemote(category: MangaCategory, mangaIds: Set<String>) {
        db.withTransaction { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO manga_categories (id, name, sort_order, updated_at) VALUES (?, ?, ?, ?)"
            ).use {
                it.setString(1, category.id)
                it.setString(2, category.name)
                it.setInt(3, category.sortOrder)
                it.setLong(4, category.updatedAt.toEpochMilliseconds())
                it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM manga_category_map WHERE category_id = ?").use {
                it.setString(1, category.id); it.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO manga_category_map (manga_id, category_id) VALUES (?, ?)").use { stmt ->
                mangaIds.forEach { mangaId ->
                    stmt.setString(1, mangaId)
                    stmt.setString(2, category.id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        db.bumpMangaData()
    }

    override suspend fun get(id: String): MangaCategory? = db.withConnection { conn ->
        conn.prepareStatement("SELECT id, name, sort_order, updated_at FROM manga_categories WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) mapCategoryRow(rs) else null }
        }
    }

    override suspend fun defaultCategory(): MangaCategory? {
        get(MangaCategory.MAIN_ID)?.let { return it }
        return db.withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT id, name, sort_order, updated_at FROM manga_categories ORDER BY sort_order, name LIMIT 1"
                ).use { rs -> if (rs.next()) mapCategoryRow(rs) else null }
            }
        }
    }

    override suspend fun ensureMembership(mangaId: String) {
        if (categoriesFor(mangaId).isNotEmpty()) return
        val target = defaultCategory() ?: return
        assign(mangaId, setOf(target.id))
    }

    override suspend fun ensureSeeded() {
        if (countCategories() == 0) {
            val main = MangaCategory(
                id = MangaCategory.MAIN_ID,
                name = MangaCategory.MAIN_NAME,
                sortOrder = 0,
            )
            insertCategoryRow(main)
            db.bumpMangaData()
            emitCategoryEvent(main, "UPSERT", isDeleted = false)
        }
        // Pre-category libraries: give every in-library manga the default shelf so
        // nothing disappears when the virtual All bucket went away.
        val orphaned = db.withConnection { conn ->
            val out = mutableListOf<String>()
            conn.prepareStatement(
                "SELECT m.id FROM manga_library m WHERE m.favorite = 1 AND NOT EXISTS " +
                    "(SELECT 1 FROM manga_category_map cm WHERE cm.manga_id = m.id)"
            ).use { stmt ->
                stmt.executeQuery().use { rs -> while (rs.next()) out += rs.getString(1) }
            }
            out
        }
        orphaned.forEach { ensureMembership(it) }
    }

    private suspend fun countCategories(): Int = db.withConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM manga_categories").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private suspend fun insertCategoryRow(category: MangaCategory) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO manga_categories (id, name, sort_order, updated_at) VALUES (?, ?, ?, ?)"
            ).use {
                it.setString(1, category.id)
                it.setString(2, category.name)
                it.setInt(3, category.sortOrder)
                it.setLong(4, category.updatedAt.toEpochMilliseconds())
                it.executeUpdate()
            }
        }
    }

    private fun mapCategoryRow(rs: java.sql.ResultSet): MangaCategory = MangaCategory(
        id = rs.getString("id"),
        name = rs.getString("name"),
        sortOrder = rs.getInt("sort_order"),
        updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(rs.getLong("updated_at")),
    )

    /** Queues the category document with its live membership for sync. */
    private suspend fun emitCategoryEvent(category: MangaCategory, operation: String, isDeleted: Boolean) {
        val hook = db.onEntityChanged ?: return
        val members = if (isDeleted) emptySet() else mangaIdsInCategory(category.id)
        val fs = com.folio.reader.firebase.FsMangaCategory(
            id = category.id,
            name = category.name,
            sortOrder = category.sortOrder,
            mangaIds = members.toList(),
            updatedAt = category.updatedAt.toEpochMilliseconds(),
            isDeleted = isDeleted,
        )
        hook(
            "manga_category",
            category.id,
            operation,
            mangaJson.encodeToString(com.folio.reader.firebase.FsMangaCategory.serializer(), fs)
        )
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

    override fun observeMangaIdsInCategory(categoryId: String): Flow<Set<String>> =
        db.mangaDataRevision.map { mangaIdsInCategory(categoryId) }

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

    override suspend fun record(
        mangaId: String,
        chapterId: String?,
        title: String,
        coverUrl: String?,
        coverPath: String?,
        sourceName: String?,
        chapterName: String?,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO manga_history (manga_id, title, cover_url, cover_path, source_name, chapter_id, chapter_name, read_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(manga_id) DO UPDATE SET
                    title = CASE WHEN excluded.title <> '' THEN excluded.title ELSE manga_history.title END,
                    cover_url = COALESCE(NULLIF(excluded.cover_url, ''), manga_history.cover_url),
                    cover_path = COALESCE(NULLIF(excluded.cover_path, ''), manga_history.cover_path),
                    source_name = COALESCE(NULLIF(excluded.source_name, ''), manga_history.source_name),
                    chapter_id = COALESCE(excluded.chapter_id, manga_history.chapter_id),
                    chapter_name = CASE WHEN excluded.chapter_name <> '' THEN excluded.chapter_name ELSE manga_history.chapter_name END,
                    read_at = excluded.read_at,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use {
                it.setString(1, mangaId)
                it.setString(2, title)
                it.setString(3, coverUrl)
                it.setString(4, coverPath)
                it.setString(5, sourceName)
                it.setString(6, chapterId)
                it.setString(7, chapterName ?: "")
                it.setLong(8, now)
                it.setLong(9, now)
                it.executeUpdate()
            }
        }
        db.bumpMangaData()
    }

    override fun observeRecent(limit: Int): Flow<List<MangaHistoryItem>> = flow {
        emit(
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT h.manga_id AS manga_id, h.chapter_id AS chapter_id, h.updated_at AS read_at,
                           m.title AS manga_title, c.name AS chapter_name
                    FROM manga_history h
                    LEFT JOIN manga_library m ON m.id = h.manga_id
                    LEFT JOIN manga_chapters c ON c.id = h.chapter_id
                    ORDER BY h.updated_at DESC
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

    override fun observeHistory(): Flow<List<com.folio.reader.manga.MangaHistoryEntry>> =
        db.mangaDataRevision.map {
            db.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        SELECT h.manga_id,
                               COALESCE(NULLIF(h.title, ''), m.title, '') AS title,
                               COALESCE(NULLIF(h.cover_url, ''), m.thumbnail_url) AS cover_url,
                               COALESCE(NULLIF(h.cover_path, ''), m.cover_path) AS cover_path,
                               COALESCE(NULLIF(h.source_name, ''), m.source_name) AS source_name,
                               h.chapter_name, h.updated_at,
                               COALESCE(m.favorite, 0) AS in_lib
                        FROM manga_history h
                        LEFT JOIN manga_library m ON m.id = h.manga_id
                        ORDER BY h.updated_at DESC
                        """.trimIndent()
                    ).use { rs ->
                        val list = mutableListOf<com.folio.reader.manga.MangaHistoryEntry>()
                        while (rs.next()) {
                            val mangaId = rs.getString("manga_id")
                            list += com.folio.reader.manga.MangaHistoryEntry(
                                mangaId = mangaId,
                                title = rs.getString("title") ?: "",
                                coverUrl = rs.getString("cover_url"),
                                coverPath = rs.getString("cover_path"),
                                sourceId = mangaId.substringBefore(':').toLongOrNull() ?: 0L,
                                sourceName = rs.getString("source_name"),
                                chapterName = rs.getString("chapter_name") ?: "",
                                updatedAt = rs.getLong("updated_at"),
                                inLibrary = rs.getInt("in_lib") == 1,
                            )
                        }
                        list
                    }
                }
            }
        }

    override suspend fun clear() {
        db.withConnection { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM manga_history") }
        }
    }

    override suspend fun clearHistory() {
        clear()
        db.bumpMangaData()
    }

    internal suspend fun upsertFromReadMark(conn: Connection, mangaId: String, chapterName: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        conn.prepareStatement(
            """
            INSERT INTO manga_history (manga_id, title, cover_url, cover_path, source_name, chapter_name, read_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(manga_id) DO UPDATE SET
                title = CASE WHEN excluded.title <> '' THEN excluded.title ELSE manga_history.title END,
                cover_url = COALESCE(NULLIF(excluded.cover_url, ''), manga_history.cover_url),
                cover_path = COALESCE(NULLIF(excluded.cover_path, ''), manga_history.cover_path),
                source_name = COALESCE(NULLIF(excluded.source_name, ''), manga_history.source_name),
                chapter_name = CASE WHEN excluded.chapter_name <> '' THEN excluded.chapter_name ELSE manga_history.chapter_name END,
                read_at = excluded.read_at,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { stmt ->
            var title = ""
            var coverUrl: String? = null
            var coverPath: String? = null
            var sourceName: String? = null
            conn.prepareStatement("SELECT title, thumbnail_url, cover_path, source_name FROM manga_library WHERE id = ?").use { lib ->
                lib.setString(1, mangaId)
                lib.executeQuery().use { rs ->
                    if (rs.next()) {
                        title = rs.getString("title") ?: ""
                        coverUrl = rs.getString("thumbnail_url")
                        coverPath = rs.getString("cover_path")
                        sourceName = rs.getString("source_name")
                    }
                }
            }
            stmt.setString(1, mangaId)
            stmt.setString(2, title)
            stmt.setString(3, coverUrl)
            stmt.setString(4, coverPath)
            stmt.setString(5, sourceName)
            stmt.setString(6, chapterName)
            stmt.setLong(7, now)
            stmt.setLong(8, now)
            stmt.executeUpdate()
        }
    }
}

class JdbcMangaDownloadRepository(private val db: Database) : com.folio.reader.manga.MangaDownloadRepository {

    /** Bumped on every queue write; collectors re-query so progress UI stays live. */
    private val queueRevision = MutableStateFlow(0L)
    private fun bumpQueue() { queueRevision.value += 1 }

    private suspend fun queryQueue(): List<MangaDownload> = db.withConnection { conn ->
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
                        error = rs.getString("error"),
                    )
                }
                list
            }
        }
    }

    override suspend fun enqueue(download: MangaDownload) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO manga_downloads (
                    id, manga_id, chapter_id, status, total_pages, downloaded_pages, queued_at, error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, download.id)
                stmt.setString(2, download.mangaId)
                stmt.setString(3, download.chapterId)
                stmt.setInt(4, download.status.value)
                stmt.setInt(5, download.totalPages)
                stmt.setInt(6, download.downloadedPages)
                stmt.setLong(7, download.queuedAt.toEpochMilliseconds())
                stmt.setString(8, download.error)
                stmt.executeUpdate()
            }
        }
        bumpQueue()
    }

    override suspend fun update(download: MangaDownload) = enqueue(download)

    override suspend fun remove(id: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_downloads WHERE id = ?").use {
                it.setString(1, id); it.executeUpdate()
            }
        }
        bumpQueue()
    }

    override suspend fun clearFinished() {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM manga_downloads WHERE status IN (?, ?)").use {
                it.setInt(1, MangaDownloadStatus.DOWNLOADED.value)
                it.setInt(2, MangaDownloadStatus.ERROR.value)
                it.executeUpdate()
            }
        }
        bumpQueue()
    }

    override fun observeQueue(): Flow<List<MangaDownload>> = flow {
        queueRevision.collect { emit(queryQueue()) }
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

    override fun observeAllNotes(): Flow<List<MangaNote>> = flow {
        val notes = db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM manga_notes ORDER BY created_at DESC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<MangaNote>()
                    while (rs.next()) list += mapNote(rs)
                    list
                }
            }
        }
        emit(notes)
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

    override suspend fun getStatistics(
        exclusions: Set<Pair<com.folio.reader.statistics.Scope, String>>
    ): com.folio.reader.manga.MangaStatistics = db.withConnection { conn ->
        // §11.2: the raw exclusion set is resolved to concrete manga ids first,
        // then every aggregate below computes with a `NOT IN` filter — the
        // exclusion is part of the query, never applied after the fact.
        val excluded = HashSet<String>()
        for ((kind, id) in exclusions) {
            when (kind) {
                com.folio.reader.statistics.Scope.MANGA -> excluded += id
                com.folio.reader.statistics.Scope.MANGA_SOURCE ->
                    conn.prepareStatement("SELECT id FROM manga_library WHERE source_id = ?").use { st ->
                        val sourceId = id.toLongOrNull() ?: return@use
                        st.setLong(1, sourceId)
                        st.executeQuery().use { rs -> while (rs.next()) excluded += rs.getString(1) }
                    }
                com.folio.reader.statistics.Scope.MANGA_CATEGORY ->
                    conn.prepareStatement("SELECT manga_id FROM manga_category_map WHERE category_id = ?").use { st ->
                        st.setString(1, id)
                        st.executeQuery().use { rs -> while (rs.next()) excluded += rs.getString(1) }
                    }
                else -> {}
            }
        }
        val ids = excluded.toList()
        fun notIn(column: String): String =
            if (ids.isEmpty()) "" else " AND $column NOT IN (${ids.joinToString(",") { "?" }})"
        fun whereNotIn(column: String): String =
            if (ids.isEmpty()) "" else " WHERE $column NOT IN (${ids.joinToString(",") { "?" }})"
        fun bindExclusions(st: java.sql.PreparedStatement, from: Int) {
            ids.forEachIndexed { i, id -> st.setString(from + i, id) }
        }

        var libraryCount = 0; var completedCount = 0
        conn.prepareStatement(
            "SELECT COUNT(*), SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) FROM manga_library WHERE favorite = 1" + notIn("id")
        ).use { st ->
            bindExclusions(st, 1)
            st.executeQuery().use { rs -> if (rs.next()) { libraryCount = rs.getInt(1); completedCount = rs.getInt(2) } }
        }
        var readChapters = 0; var unreadChapters = 0; var downloadedChapters = 0; var bookmarkedChapters = 0
        conn.prepareStatement(
            "SELECT SUM(CASE WHEN read=1 THEN 1 ELSE 0 END), SUM(CASE WHEN read=0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN downloaded_pages>0 THEN 1 ELSE 0 END), SUM(CASE WHEN bookmarked=1 THEN 1 ELSE 0 END) " +
                "FROM manga_chapters" + whereNotIn("manga_id")
        ).use { st ->
            bindExclusions(st, 1)
            st.executeQuery().use { rs ->
                if (rs.next()) { readChapters = rs.getInt(1); unreadChapters = rs.getInt(2); downloadedChapters = rs.getInt(3); bookmarkedChapters = rs.getInt(4) }
            }
        }
        var notesCount = 0
        conn.prepareStatement("SELECT COUNT(*) FROM manga_notes" + whereNotIn("manga_id")).use { st ->
            bindExclusions(st, 1)
            st.executeQuery().use { rs ->
                if (rs.next()) notesCount = rs.getInt(1)
            }
        }
        var totalReadMinutes = 0L
        conn.prepareStatement(
            "SELECT COALESCE(SUM(s.duration_ms),0)/60000 FROM reading_sessions s JOIN manga_library m ON m.id = s.book_id" +
                whereNotIn("m.id")
        ).use { st ->
            bindExclusions(st, 1)
            st.executeQuery().use { rs -> if (rs.next()) totalReadMinutes = rs.getLong(1) }
        }
        var readActiveDays = 0
        conn.prepareStatement(
            "SELECT COUNT(*) FROM (SELECT 1 FROM manga_chapters WHERE read = 1" + notIn("manga_id") +
                " GROUP BY date(updated_at/1000,'unixepoch','localtime'))"
        ).use { st ->
            bindExclusions(st, 1)
            st.executeQuery().use { rs -> if (rs.next()) readActiveDays = rs.getInt(1) }
        }
        val week = MutableList(7) { 0 }
        val labels = MutableList(7) { "" }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("E")
        for (i in 0 until 7) {
            val day = java.time.LocalDate.now().minusDays(6 - i.toLong())
            labels[i] = day.format(fmt)
            conn.prepareStatement(
                "SELECT COUNT(*) FROM manga_chapters WHERE read = 1 AND date(updated_at/1000,'unixepoch','localtime') = ?" + notIn("manga_id")
            ).use { st ->
                st.setString(1, day.toString())
                bindExclusions(st, 2)
                st.executeQuery().use { rs -> if (rs.next()) week[i] = rs.getInt(1) }
            }
        }
        val top = mutableListOf<com.folio.reader.manga.MangaTopEntry>()
        conn.prepareStatement(
            "SELECT m.id, m.title, SUM(s.duration_ms)/60000 AS minutes FROM reading_sessions s JOIN manga_library m ON m.id = s.book_id" +
                whereNotIn("m.id") + " GROUP BY m.id, m.title HAVING minutes > 0 ORDER BY minutes DESC, m.id ASC LIMIT 5"
        ).use { st ->
            bindExclusions(st, 1)
            st.executeQuery().use { rs -> while (rs.next()) top += com.folio.reader.manga.MangaTopEntry(rs.getString(1), rs.getString(2), rs.getLong(3)) }
        }
        com.folio.reader.manga.MangaStatistics(
            libraryCount = libraryCount, completedCount = completedCount, readChapters = readChapters,
            unreadChapters = unreadChapters, downloadedChapters = downloadedChapters, bookmarkedChapters = bookmarkedChapters,
            notesCount = notesCount, totalReadMinutes = totalReadMinutes, readActiveDays = readActiveDays,
            weekReadChapters = week, weekLabels = labels, topManga = top,
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
        // Local series imported before cover resolution have a blank thumbnail_url, which
        // leaves their covers unresolvable; the url doubles as the local cover key.
        exec(
            "UPDATE manga_library SET thumbnail_url = url " +
                "WHERE source_id = 0 AND (thumbnail_url IS NULL OR thumbnail_url = '')"
        )

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
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        runCatching {
            conn.createStatement().executeQuery("SELECT updated_at FROM manga_categories LIMIT 0").close()
        }.onFailure {
            conn.createStatement().use {
                it.execute("ALTER TABLE manga_categories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            }
        }
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
                title TEXT NOT NULL,
                cover_url TEXT,
                cover_path TEXT,
                source_name TEXT,
                chapter_id TEXT,
                chapter_name TEXT,
                read_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        val historyCols = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(manga_history)").use { rs ->
                while (rs.next()) historyCols += rs.getString("name")
            }
        }
        fun addHistoryCol(name: String, ddl: String): Boolean {
            if (name in historyCols) return false
            conn.createStatement().use { it.execute(ddl) }
            return true
        }
        addHistoryCol("title", "ALTER TABLE manga_history ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        addHistoryCol("cover_url", "ALTER TABLE manga_history ADD COLUMN cover_url TEXT")
        addHistoryCol("cover_path", "ALTER TABLE manga_history ADD COLUMN cover_path TEXT")
        addHistoryCol("source_name", "ALTER TABLE manga_history ADD COLUMN source_name TEXT")
        addHistoryCol("chapter_name", "ALTER TABLE manga_history ADD COLUMN chapter_name TEXT")
        addHistoryCol("chapter_id", "ALTER TABLE manga_history ADD COLUMN chapter_id TEXT")
        addHistoryCol("read_at", "ALTER TABLE manga_history ADD COLUMN read_at INTEGER NOT NULL DEFAULT 0")
        if ("updated_at" !in historyCols) {
            conn.createStatement().use {
                it.execute("ALTER TABLE manga_history ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            }
            if ("read_at" in historyCols) {
                conn.createStatement().use { it.execute("UPDATE manga_history SET updated_at = read_at") }
            }
        }
        if ("title" !in historyCols || "chapter_name" !in historyCols) {
            conn.createStatement().use {
                it.execute(
                    "UPDATE manga_history SET title = (SELECT m.title FROM manga_library m WHERE m.id = manga_id) WHERE (title IS NULL OR title = '') AND manga_id IN (SELECT id FROM manga_library)"
                )
            }
            conn.createStatement().use {
                it.execute(
                    "UPDATE manga_history SET chapter_name = (SELECT c.name FROM manga_chapters c WHERE c.id = chapter_id) WHERE (chapter_name IS NULL OR chapter_name = '') AND chapter_id IN (SELECT id FROM manga_chapters)"
                )
            }
        }
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
        // Failed downloads carry their reason so the queue UI can say why, not just that.
        runCatching {
            conn.createStatement().executeQuery("SELECT error FROM manga_downloads LIMIT 0").close()
        }.onFailure {
            conn.createStatement().use {
                it.execute("ALTER TABLE manga_downloads ADD COLUMN error TEXT")
            }
        }
    }
}
