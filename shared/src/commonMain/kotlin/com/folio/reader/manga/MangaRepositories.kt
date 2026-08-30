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
    suspend fun create(name: String): MangaCategory
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    fun observeCategories(): Flow<List<MangaCategory>>
    suspend fun assign(mangaId: String, categoryIds: Set<String>)
    suspend fun categoriesFor(mangaId: String): Set<String>
    suspend fun mangaIdsInCategory(categoryId: String): Set<String>
}

interface MangaHistoryRepository {
    suspend fun record(mangaId: String, chapterId: String?)
    fun observeRecent(limit: Int = 25): Flow<List<MangaHistoryItem>>
    suspend fun clear()
}

interface MangaDownloadRepository {
    suspend fun enqueue(download: MangaDownload)
    suspend fun update(download: MangaDownload)
    suspend fun remove(id: String)
    suspend fun clearFinished()
    fun observeQueue(): Flow<List<MangaDownload>>
    suspend fun isChapterDownloaded(chapterId: String): Boolean
}
