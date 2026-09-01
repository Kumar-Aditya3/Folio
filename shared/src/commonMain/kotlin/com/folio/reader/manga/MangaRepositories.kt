package com.folio.reader.manga

import kotlinx.coroutines.flow.Flow

interface MangaRepository {
    suspend fun upsert(manga: MangaEntry, emitSyncEvent: Boolean = true)
    suspend fun delete(mangaId: String, emitSyncEvent: Boolean = true)
    suspend fun get(mangaId: String): MangaEntry?
    suspend fun findBySourceUrl(sourceId: Long, url: String): MangaEntry?
    fun observeLibrary(): Flow<List<MangaEntry>>
    fun observeAll(): Flow<List<MangaEntry>>
    suspend fun setInLibrary(mangaId: String, inLibrary: Boolean)
    suspend fun setCoverPath(mangaId: String, coverPath: String?)
    suspend fun touchLastRead(mangaId: String)
}

interface MangaChapterRepository {
    suspend fun replaceChapters(mangaId: String, chapters: List<MangaChapter>)
    suspend fun getChapters(mangaId: String): List<MangaChapter>
    suspend fun getChapter(chapterId: String): MangaChapter?
    suspend fun markRead(chapterIds: List<String>, read: Boolean, emitSyncEvent: Boolean = true)
    suspend fun setBookmarked(chapterId: String, bookmarked: Boolean, emitSyncEvent: Boolean = true)
    suspend fun saveProgress(chapterId: String, lastPage: Int, totalPages: Int = 0, emitSyncEvent: Boolean = true)
    suspend fun applyRemoteState(
        chapterId: String,
        read: Boolean,
        bookmarked: Boolean,
        lastPageRead: Int,
        updatedAt: kotlinx.datetime.Instant,
        emitSyncEvent: Boolean = false,
        totalPages: Int = 0,
    )
    suspend fun setDownloadedPages(chapterId: String, pages: Int)
    fun observeUnreadCounts(): Flow<Map<String, Int>>
    fun observeProgress(): Flow<Map<String, Float>>
    fun observeLastRead(): Flow<Map<String, MangaLastRead>>
    fun observeDownloadedCounts(): Flow<Map<String, Int>>
    suspend fun markAllReadForManga(mangaId: String, read: Boolean)
}

interface MangaStatisticsRepository {
    suspend fun getStatistics(): MangaStatistics
}

interface MangaNoteRepository {
    suspend fun upsert(note: MangaNote, emitSyncEvent: Boolean = true)
    suspend fun delete(noteId: String, emitSyncEvent: Boolean = true)
    suspend fun get(noteId: String): MangaNote?
    suspend fun notesForChapter(chapterId: String): List<MangaNote>
    fun observeNotesForChapter(chapterId: String): Flow<List<MangaNote>>
}

interface MangaCategoryRepository {
    suspend fun create(name: String, emitSyncEvent: Boolean = true): MangaCategory
    suspend fun rename(id: String, name: String, emitSyncEvent: Boolean = true)

    /** Returns false when the delete was refused (Main survives while it is the only category). */
    suspend fun delete(id: String, emitSyncEvent: Boolean = true): Boolean

    fun observeCategories(): Flow<List<MangaCategory>>
    suspend fun assign(mangaId: String, categoryIds: Set<String>, emitSyncEvent: Boolean = true)
    suspend fun categoriesFor(mangaId: String): Set<String>
    suspend fun get(id: String): MangaCategory?
    fun observeCategoriesFor(mangaId: String): Flow<Set<String>>
    fun observeMangaIdsInCategory(categoryId: String): Flow<Set<String>>
    suspend fun mangaIdsInCategory(categoryId: String): Set<String>

    /** Applies a remote category document (membership included) without re-emitting sync events. */
    suspend fun applyRemote(category: MangaCategory, mangaIds: Set<String>)

    /** Main if it exists, otherwise the first category by sort order; null when there are none. */
    suspend fun defaultCategory(): MangaCategory?

    /** Gives the manga the default category when it belongs to no category yet. */
    suspend fun ensureMembership(mangaId: String)

    /**
     * Startup repair: creates Main when the table is empty and moves every uncategorized
     * library manga into the default category, so nothing is invisible now that the
     * virtual "All" bucket is gone.
     */
    suspend fun ensureSeeded()
}

interface MangaHistoryRepository {
    suspend fun record(
        mangaId: String,
        chapterId: String?,
        title: String = "",
        coverUrl: String? = null,
        coverPath: String? = null,
        sourceName: String? = null,
        chapterName: String? = null,
    )
    fun observeRecent(limit: Int = 25): Flow<List<MangaHistoryItem>>
    fun observeHistory(): Flow<List<MangaHistoryEntry>>
    suspend fun clear()
    suspend fun clearHistory()
}

interface MangaDownloadRepository {
    suspend fun enqueue(download: MangaDownload)
    suspend fun update(download: MangaDownload)
    suspend fun remove(id: String)
    suspend fun clearFinished()
    fun observeQueue(): Flow<List<MangaDownload>>
    suspend fun isChapterDownloaded(chapterId: String): Boolean
}
