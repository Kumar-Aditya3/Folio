package com.folio.reader.ui.manga

import com.folio.reader.manga.ChapterNumberParser
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDownloadManager
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaStatus
import com.folio.reader.manga.chapterId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

// ---------- Manga detail ----------

class MangaDetailViewModel(
    private val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
    private val chapterRepo: MangaChapterRepository,
    private val historyRepo: MangaHistoryRepository,
    private val downloadManager: MangaDownloadManager?,
    private val downloadRepo: com.folio.reader.manga.MangaDownloadRepository?,
    private val categoryRepo: com.folio.reader.manga.MangaCategoryRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
    private val sessionRepo: com.folio.reader.database.ReadingSessionRepository? = null,
    private val tagRepo: com.folio.reader.database.TagRepository? = null,
) {
    val scope = mangaVmScope()

    val manga = MutableStateFlow<MangaEntry?>(null)
    val chapters = MutableStateFlow<List<MangaChapter>>(emptyList())
    val sessions = MutableStateFlow<List<com.folio.reader.model.ReadingSession>>(emptyList())
    val tags = MutableStateFlow<List<com.folio.reader.model.Tag>>(emptyList())
    val allTags = MutableStateFlow<List<com.folio.reader.model.Tag>>(emptyList())
    val refreshing = MutableStateFlow(false)
    val refreshNotice = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val sortAscending = MutableStateFlow(false)
    val chapterFilter = MutableStateFlow(ChapterFilter.ALL)

    /** Live download state per chapter id (queued / downloading with progress / done). */
    val downloadStates: kotlinx.coroutines.flow.StateFlow<Map<String, com.folio.reader.manga.MangaDownload>> =
        downloadRepo?.observeQueue()
            ?.map { list -> list.associateBy { it.chapterId } }
            ?.stateIn(scope, SharingStarted.Lazily, emptyMap())
            ?: MutableStateFlow(emptyMap())

    fun applyFilter(list: List<MangaChapter>): List<MangaChapter> =
        filterChapters(list, chapterFilter.value)

    fun setChapterFilter(f: ChapterFilter) {
        chapterFilter.value = f
        val id = manga.value?.id ?: return
        scope.launch { settingsRepo.setRaw("$KEY_CHAPTER_FILTER.$id", f.name) }
    }

    fun toggleSort() {
        sortAscending.value = !sortAscending.value
        val id = manga.value?.id ?: return
        scope.launch { settingsRepo.setRaw("$KEY_CHAPTER_SORT.$id", if (sortAscending.value) "ASC" else "DESC") }
    }

    fun open(mangaId: String) {
        scope.launch {
            manga.value = mangaRepo.get(mangaId)
            chapters.value = chapterRepo.getChapters(mangaId)
            // The library-wide Downloaded mode wins over the per-manga saved filter:
            // opening a manga shows only its downloaded chapters while it is on.
            val downloadedMode = settingsRepo.getRaw(KEY_LIBRARY_DOWNLOADED_FILTER) == "true"
            val savedFilter = settingsRepo.getRaw("$KEY_CHAPTER_FILTER.$mangaId")
            chapterFilter.value = when {
                downloadedMode -> ChapterFilter.DOWNLOADED
                savedFilter != null -> ChapterFilter.entries.firstOrNull { it.name == savedFilter } ?: ChapterFilter.ALL
                else -> ChapterFilter.ALL
            }
            val savedSort = settingsRepo.getRaw("$KEY_CHAPTER_SORT.$mangaId")
            sortAscending.value = savedSort == "ASC"
            if (manga.value?.initialized != true || chapters.value.isEmpty()) {
                refresh()
            }
        }
        scope.launch { categoryRepo.observeCategoriesFor(mangaId).collect { myCategoryIds.value = it } }
        scope.launch {
            val repo = sessionRepo ?: return@launch
            repo.getSessionsForBook(mangaId).collect { sessions.value = it }
        }
        scope.launch {
            val repo = tagRepo ?: return@launch
            repo.getAllTags().collect { allTags.value = it }
        }
        scope.launch {
            val repo = tagRepo ?: return@launch
            tags.value = repo.getTagsForManga(mangaId)
        }
    }

    val allCategories = categoryRepo.observeCategories()
        .stateIn(scope, SharingStarted.Lazily, emptyList())
    val myCategoryIds = MutableStateFlow<Set<String>>(emptySet())

    fun setCategories(ids: Set<String>) {
        val id = manga.value?.id ?: return
        scope.launch {
            val target = ids.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: emptySet()
            }
            if (target.isNotEmpty()) categoryRepo.assign(id, target)
        }
    }

    suspend fun createCategory(name: String): String? =
        runCatching { categoryRepo.create(name).id }.getOrNull()

    /** Diffs the picker's selection against current links, mirroring the book detail flow. */
    fun updateMangaTags(selected: Set<String>) {
        val id = manga.value?.id ?: return
        val repo = tagRepo ?: return
        scope.launch {
            val current = repo.getTagsForManga(id).map { it.id }.toSet()
            (current - selected).forEach { repo.removeTagFromManga(id, it) }
            (selected - current).forEach { repo.addTagToManga(id, it) }
            tags.value = repo.getTagsForManga(id)
        }
    }

    fun refresh() {
        scope.launch {
            val m = manga.value ?: return@launch
            refreshing.value = true
            error.value = null
            refreshNotice.value = null
            val oldIds = chapters.value.map { it.id }.toSet()
            try {
                val detail = backend.fetchMangaDetail(m.sourceId, m.url)
                val refs = backend.fetchChapterList(m.sourceId, m.url)
                val mapped = refs.mapIndexed { index, ref ->
                    MangaChapter(
                        id = chapterId(m.id, ref.url),
                        mangaId = m.id,
                        url = ref.url,
                        name = ref.name,
                        scanlator = ref.scanlator,
                        chapterNumber =
                            if (ref.chapterNumber >= 0f) ref.chapterNumber
                            else ChapterNumberParser.parse(ref.name),
                        dateUpload = ref.dateUpload,
                        sortOrder = index,
                    )
                }
                chapterRepo.replaceChapters(m.id, mapped)
                val updated = m.copy(
                    title = detail.title.ifBlank { m.title },
                    author = detail.author ?: m.author,
                    artist = detail.artist ?: m.artist,
                    description = detail.description ?: m.description,
                    genres = detail.genres.ifEmpty { m.genres },
                    status = if (detail.status == MangaStatus.UNKNOWN) m.status else detail.status,
                    thumbnailUrl = detail.thumbnailUrl ?: m.thumbnailUrl,
                    initialized = true,
                    updatedAt = Clock.System.now(),
                )
                mangaRepo.upsert(updated)
                manga.value = updated
                chapters.value = chapterRepo.getChapters(m.id)
                val newCount = chapters.value.count { it.id !in oldIds }
                refreshNotice.value = if (newCount > 0) "$newCount new chapters" else "Already up to date"
            } catch (e: Throwable) {
                println("MangaDetail refresh failed: " + e.stackTraceToString())
                if (chapters.value.isEmpty()) {
                    error.value = e.message ?: "Failed to load manga"
                } else {
                    refreshNotice.value = "Offline — showing saved chapters"
                }
            }
            refreshing.value = false
        }
    }

    fun toggleInLibrary() {
        scope.launch {
            val m = manga.value ?: return@launch
            val updated = m.copy(inLibrary = !m.inLibrary, updatedAt = Clock.System.now())
            mangaRepo.upsert(updated)
            manga.value = updated
            // Adding to the library always lands on a real shelf (Main by default).
            if (updated.inLibrary) categoryRepo.ensureMembership(updated.id)
        }
    }

    fun toggleRead(chapter: MangaChapter) {
        scope.launch {
            chapterRepo.markRead(listOf(chapter.id), !chapter.read)
            chapters.value = chapterRepo.getChapters(chapter.mangaId)
        }
    }

    /** Marks the whole series read or unread in one action (detail overflow menu). */
    fun markAllRead(read: Boolean) {
        scope.launch {
            val id = manga.value?.id ?: return@launch
            chapterRepo.markAllReadForManga(id, read)
            chapters.value = chapterRepo.getChapters(id)
        }
    }

    /** Selects every chapter so bulk read/unread/download apply to the whole series. */
    fun selectAllChapters() {
        selectedChapterIds.value = chapters.value.map { it.id }.toSet()
        chapterSelectionMode.value = selectedChapterIds.value.isNotEmpty()
    }

    fun markPreviousAsRead(chapter: MangaChapter) {
        scope.launch {
            val all = chapterRepo.getChapters(chapter.mangaId)
            // "Previous" is story order (chapter numbers, recovered from titles when
            // the source leaves them unset); the display sort only arranges the list.
            val previous = all.filter { STORY_ORDER.compare(it, chapter) < 0 }
            chapterRepo.markRead(previous.map { it.id }, true)
            chapters.value = chapterRepo.getChapters(chapter.mangaId)
        }
    }

    fun toggleBookmark(chapter: MangaChapter) {
        scope.launch {
            chapterRepo.setBookmarked(chapter.id, !chapter.bookmarked)
            chapters.value = chapterRepo.getChapters(chapter.mangaId)
        }
    }

    fun download(chapter: MangaChapter) {
        val manager = downloadManager ?: return
        scope.launch { manager.queueChapter(chapter.mangaId, chapter) }
    }

    /** Cancels a queued or in-flight download (also removes partially written pages). */
    fun cancelDownload(chapter: MangaChapter) {
        val manager = downloadManager ?: return
        scope.launch {
            downloadStates.value[chapter.id]?.let { manager.cancel(it.id) }
        }
    }

    /** Deletes a finished download's pages from storage. */
    fun deleteDownload(chapter: MangaChapter) {
        val manager = downloadManager ?: return
        scope.launch {
            manager.deleteChapterDownload(chapter.mangaId, chapter.id)
            chapterRepo.setDownloadedPages(chapter.id, 0)
            chapters.value = chapterRepo.getChapters(chapter.mangaId)
        }
    }

    // ---------- Chapter multi-select bulk actions ----------

    val selectedChapterIds = MutableStateFlow<Set<String>>(emptySet())
    val chapterSelectionMode = MutableStateFlow(false)

    fun toggleChapterSelection(chapterId: String) {
        val next = if (chapterId in selectedChapterIds.value) selectedChapterIds.value - chapterId else selectedChapterIds.value + chapterId
        selectedChapterIds.value = next
        chapterSelectionMode.value = next.isNotEmpty()
    }

    fun clearChapterSelection() {
        selectedChapterIds.value = emptySet()
        chapterSelectionMode.value = false
    }

    private fun selectedChapters(): List<com.folio.reader.manga.MangaChapter> =
        chapters.value.filter { it.id in selectedChapterIds.value }

    fun bulkMarkRead(read: Boolean) {
        val ids = selectedChapterIds.value.toList()
        val mangaId = manga.value?.id ?: return
        scope.launch {
            chapterRepo.markRead(ids, read)
            chapters.value = chapterRepo.getChapters(mangaId)
            clearChapterSelection()
        }
    }

    fun bulkDownload() {
        val manager = downloadManager ?: return
        val sel = selectedChapters()
        scope.launch {
            sel.forEach { manager.queueChapter(it.mangaId, it) }
            clearChapterSelection()
        }
    }

    fun bulkDeleteDownloads() {
        val manager = downloadManager ?: return
        val sel = selectedChapters()
        scope.launch {
            sel.forEach { chapter ->
                manager.deleteChapterDownload(chapter.mangaId, chapter.id)
                chapterRepo.setDownloadedPages(chapter.id, 0)
            }
            chapters.value = chapterRepo.getChapters(sel.firstOrNull()?.mangaId ?: "")
            clearChapterSelection()
        }
    }

    fun downloadUnread() {
        val manager = downloadManager ?: return
        scope.launch {
            chapters.value.filter { !it.read }.forEach { chapter ->
                manager.queueChapter(chapter.mangaId, chapter)
            }
        }
    }

    /**
     * Continue = the first unread chapter in STORY order within the active chapter
     * filter (so bookmark/download filters jump to the first unread of that shelf);
     * else the first chapter of the pool. Story order comes from chapter numbers
     * (recovered from titles when the source leaves them unset), so it holds even
     * for sources that list chapters newest-first; the display sort toggle only
     * arranges the chapter list and never changes which chapter comes next.
     */
    suspend fun nextChapterToRead(): MangaChapter? {
        val id = manga.value?.id ?: return null
        val all = chapterRepo.getChapters(id)
        if (all.isEmpty()) return null
        val pool = filterChapters(all, chapterFilter.value).ifEmpty { all }
            .sortedWith(STORY_ORDER)
        return pool.firstOrNull { !it.read } ?: pool.firstOrNull()
    }

    fun recordHistory(chapterId: String?) {
        val m = manga.value ?: return
        scope.launch {
            val name = chapterId?.let { id -> chapterRepo.getChapters(m.id).firstOrNull { it.id == id }?.name }
            historyRepo.record(
                mangaId = m.id,
                chapterId = chapterId,
                title = m.title,
                coverUrl = m.thumbnailUrl,
                coverPath = m.coverPath,
                sourceName = m.sourceName,
                chapterName = name,
            )
        }
    }
}
