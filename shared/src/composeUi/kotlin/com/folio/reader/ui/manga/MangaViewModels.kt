package com.folio.reader.ui.manga

import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaDownloadManager
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.manga.MangaStatus
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDownloadRepository
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.chapterId
import com.folio.reader.manga.mangaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock

/** Folio ViewModels are plain Kotlin classes; this keeps their scope style consistent. */
internal fun mangaVmScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// ---------- Manga library ----------

enum class MangaViewMode { GRID, LIST, COMPACT }
enum class MangaSearchScope { LIBRARY, SOURCES }
enum class MangaSortBy(val label: String) {
    RECENT("Recently opened"),
    TITLE("Title"),
    UNREAD("Unread count"),
}
enum class MangaLibFilter(val label: String) {
    UNREAD("Unread"),
    READING("Reading"),
    COMPLETED("Completed"),
    DOWNLOADED("Downloaded"),
}

class MangaLibraryViewModel(
    private val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
    private val categoryRepo: MangaCategoryRepository,
    private val chapterRepo: MangaChapterRepository,
) {
    val scope = mangaVmScope()

    val library: StateFlow<List<MangaEntry>> = mangaRepo.observeLibrary()
        .stateIn(scope, SharingStarted.Lazily, emptyList())
    val unreadCounts: StateFlow<Map<String, Int>> = chapterRepo.observeUnreadCounts()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val progress: StateFlow<Map<String, Float>> = chapterRepo.observeProgress()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val lastRead: StateFlow<Map<String, com.folio.reader.manga.MangaLastRead>> = chapterRepo.observeLastRead()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val downloadedCounts: StateFlow<Map<String, Int>> = chapterRepo.observeDownloadedCounts()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val categories = categoryRepo.observeCategories()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val query = MutableStateFlow("")
    val searchActive = MutableStateFlow(false)
    val sortBy = MutableStateFlow(MangaSortBy.RECENT)
    val activeFilters = MutableStateFlow<Set<MangaLibFilter>>(emptySet())
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)
    private val categoryMembership = MutableStateFlow<Set<String>?>(null)
    val selectedCategoryId = MutableStateFlow<String?>(null)

    private val baseList: StateFlow<List<MangaEntry>> =
        combine(library, query, categoryMembership) { list, q, membership ->
            list.filter { manga ->
                (q.isBlank() || manga.title.contains(q, ignoreCase = true)) &&
                    (membership == null || manga.id in membership)
            }
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    private val counts = combine(unreadCounts, progress, downloadedCounts) { u, p, d ->
        Triple(u, p, d)
    }

    val visible: StateFlow<List<MangaEntry>> =
        combine(baseList, counts, activeFilters, sortBy) { list, c, filters, sort ->
            val (unread, prog, dl) = c
            val filtered = list.filter { manga ->
                filters.all { f ->
                    val p = prog[manga.id] ?: 0f
                    when (f) {
                        MangaLibFilter.UNREAD -> p == 0f && (unread[manga.id] ?: 0) > 0
                        MangaLibFilter.READING -> p > 0f && p < 1f
                        MangaLibFilter.COMPLETED -> p >= 1f
                        MangaLibFilter.DOWNLOADED -> (dl[manga.id] ?: 0) > 0
                    }
                }
            }
            when (sort) {
                MangaSortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
                MangaSortBy.UNREAD -> filtered.sortedByDescending { unread[it.id] ?: 0 }
                MangaSortBy.RECENT -> filtered.sortedByDescending { it.lastReadAt?.toEpochMilliseconds() ?: 0L }
            }
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    fun selectCategory(categoryId: String?) {
        selectedCategoryId.value = categoryId
        if (categoryId == null) categoryMembership.value = null
        else scope.launch { categoryMembership.value = categoryRepo.mangaIdsInCategory(categoryId) }
    }

    fun toggleFilter(filter: MangaLibFilter) {
        activeFilters.value = if (filter in activeFilters.value) activeFilters.value - filter else activeFilters.value + filter
    }

    fun setQuickFilter(filter: MangaLibFilter?) {
        activeFilters.value = if (filter == null) emptySet() else setOf(filter)
    }

    fun addCollection(name: String) {
        scope.launch { categoryRepo.create(name) }
    }

    fun renameCategory(id: String, name: String) {
        scope.launch { categoryRepo.rename(id, name) }
    }

    fun deleteCategory(id: String) {
        scope.launch {
            categoryRepo.delete(id)
            if (selectedCategoryId.value == id) selectCategory(null)
        }
    }

    fun toggleSelection(mangaId: String) {
        val next = if (mangaId in selectedIds.value) selectedIds.value - mangaId else selectedIds.value + mangaId
        selectedIds.value = next
        isSelectionMode.value = next.isNotEmpty()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        isSelectionMode.value = false
    }

    fun removeFromLibrary(mangaId: String) {
        scope.launch { mangaRepo.setInLibrary(mangaId, false) }
    }

    fun removeSelected() {
        val ids = selectedIds.value
        scope.launch { ids.forEach { mangaRepo.setInLibrary(it, false) }; clearSelection() }
    }

    fun markSelectedRead(read: Boolean) {
        val ids = selectedIds.value
        scope.launch { ids.forEach { chapterRepo.markAllReadForManga(it, read) }; clearSelection() }
    }

    /** Marks every chapter of one series read/unread (library item overflow menu). */
    fun markOneRead(mangaId: String, read: Boolean) {
        scope.launch { chapterRepo.markAllReadForManga(mangaId, read) }
    }

    fun toggleInLibrary(manga: MangaEntry) {
        scope.launch { mangaRepo.setInLibrary(manga.id, !manga.inLibrary) }
    }

    // ---------- Library chapter updates ----------

    val updating = MutableStateFlow(false)
    val lastNewChapters = MutableStateFlow(0)
    val recentlyUpdated = MutableStateFlow<Set<String>>(emptySet())

    init {
        // Periodic new-chapter checks while the manga library is on screen.
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                updateLibrary()
                kotlinx.coroutines.delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Polls each favourited manga's source for its chapter list, merges it with the
     * local catalog (preserving read/bookmark/progress by url) and surfaces how many
     * new chapters arrived, mirroring Mihon's library update.
     */
    fun updateLibrary() {
        if (updating.value) return
        scope.launch {
            updating.value = true
            var newTotal = 0
            val updated = mutableSetOf<String>()
            for (manga in library.value.filter { it.inLibrary }) {
                try {
                    val existing = chapterRepo.getChapters(manga.id).map { it.url }.toSet()
                    val refs = backend.fetchChapterList(manga.sourceId, manga.url)
                    val newCount = refs.count { it.url !in existing }
                    if (newCount > 0) {
                        val mapped = refs.mapIndexed { index, ref ->
                            com.folio.reader.manga.MangaChapter(
                                id = com.folio.reader.manga.chapterId(manga.id, ref.url),
                                mangaId = manga.id,
                                url = ref.url,
                                name = ref.name,
                                scanlator = ref.scanlator,
                                chapterNumber = ref.chapterNumber,
                                dateUpload = ref.dateUpload,
                                sortOrder = index,
                            )
                        }
                        chapterRepo.replaceChapters(manga.id, mapped)
                        newTotal += newCount
                        updated += manga.id
                    }
                } catch (_: Throwable) {
                    // A single dead source must not abort the whole library update.
                }
            }
            lastNewChapters.value = newTotal
            recentlyUpdated.value = updated
            updating.value = false
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 30 * 60 * 1000L
    }
}

// ---------- Browse / extensions ----------

class BrowseViewModel(
    val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
) {
    val scope = mangaVmScope()

    val supportsExtensions = backend.supportsExtensions

    val sources: StateFlow<List<MangaSourceInfo>> = backend.observeSources()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val extensions: StateFlow<List<ExtensionEntry>> = backend.observeExtensions()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val repos = MutableStateFlow<List<MangaRepoInfo>>(emptyList())
    val refreshingIndex = MutableStateFlow(false)
    val installStates = MutableStateFlow<Map<String, ExtensionInstallStep>>(emptyMap())
    val nsfw = MutableStateFlow(false)

    // ---------- Global search across all sources ----------

    data class GlobalSourceResult(
        val source: MangaSourceInfo,
        val items: List<MangaBrowseItem> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    val globalQuery = MutableStateFlow("")
    val globalResults = MutableStateFlow<List<GlobalSourceResult>>(emptyList())
    val searchActive = MutableStateFlow(false)
    private var globalJob: kotlinx.coroutines.Job? = null
    private val searchGate = kotlinx.coroutines.sync.Semaphore(5)
    private val searchArrival = MutableStateFlow<List<Long>>(emptyList())

    /**
     * Search sections in display order: sources that already found something come
     * first — ordered by who answered first — then the sources still searching.
     * Sources that finished with nothing are dropped so a real match is never
     * buried below empty results.
     */
    val globalResultsOrdered: StateFlow<List<GlobalSourceResult>> =
        combine(globalResults, searchArrival) { list, arrival ->
            list.filter { it.items.isNotEmpty() || it.loading }
                .sortedWith(
                    compareByDescending<GlobalSourceResult> { it.items.isNotEmpty() }
                        .thenBy {
                            val idx = arrival.indexOf(it.source.id)
                            if (idx < 0) Int.MAX_VALUE else idx
                        },
                )
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    fun toggleSearch() {
        val next = !searchActive.value
        searchActive.value = next
        if (!next) globalSearch("")
    }

    fun exitSearch() {
        searchActive.value = false
        globalSearch("")
    }

    fun globalSearch(query: String) {
        globalQuery.value = query
        globalJob?.cancel()
        if (query.isBlank()) {
            globalResults.value = emptyList()
            searchArrival.value = emptyList()
            return
        }
        globalJob = scope.launch {
            // Sources load asynchronously as extensions unpack; an empty snapshot would
            // silently search nothing. Await the first non-empty list instead.
            val targets = if (sources.value.isNotEmpty()) sources.value else sources.first { it.isNotEmpty() }
            globalResults.value = targets.map { GlobalSourceResult(it) }
            searchArrival.value = emptyList()
            targets.map { source ->
                launch {
                    searchGate.withPermit {
                        try {
                            val page = backend.fetchBrowse(source.id, 1, BrowseMode.POPULAR, query, null)
                            if (page.items.isNotEmpty()) {
                                searchArrival.value = searchArrival.value + source.id
                            }
                            setGlobalResult(source.id) {
                                it.copy(
                                    items = page.items,
                                    loading = false,
                                    error = if (page.items.isEmpty()) "No results" else null,
                                )
                            }
                        } catch (e: Throwable) {
                            setGlobalResult(source.id) {
                                it.copy(loading = false, error = e.message ?: "Search failed")
                            }
                        }
                    }
                }
            }.forEach { it.join() }
        }
    }

    private fun setGlobalResult(sourceId: Long, transform: (GlobalSourceResult) -> GlobalSourceResult) {
        globalResults.value = globalResults.value.map { if (it.source.id == sourceId) transform(it) else it }
    }

    /** Opens a search result: persists it for the detail screen without adding to library. */
    suspend fun ensureEntry(source: MangaSourceInfo, item: MangaBrowseItem): MangaEntry {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { return it }
        val entry = MangaEntry(
            id = mangaId(source.id, item.url),
            sourceId = source.id,
            sourceName = source.name,
            url = item.url,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            inLibrary = false,
            addedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        mangaRepo.upsert(entry)
        return entry
    }

    init {
        scope.launch { repos.value = backend.getRepos() }
        scope.launch { nsfw.value = backend.getShowNsfwSources() }
    }

    fun setNsfw(enabled: Boolean) {
        nsfw.value = enabled
        scope.launch { backend.setShowNsfwSources(enabled) }
    }

    fun refreshIndex() {
        scope.launch {
            refreshingIndex.value = true
            try {
                backend.refreshExtensionIndex()
            } finally {
                refreshingIndex.value = false
            }
        }
    }

    fun saveRepos(newRepos: List<MangaRepoInfo>) {
        scope.launch {
            backend.setRepos(newRepos)
            repos.value = backend.getRepos()
        }
    }

    fun install(pkgName: String) {
        val entry = extensions.value.firstOrNull { it.pkgName == pkgName }
        if (entry != null && entry.isNsfw && !nsfw.value) {
            setNsfw(true)
        }
        scope.launch {
            backend.installExtension(pkgName).collect { step ->
                installStates.value = installStates.value + (pkgName to step)
            }
        }
    }

    fun update(pkgName: String) {
        scope.launch {
            backend.updateExtension(pkgName).collect { step ->
                installStates.value = installStates.value + (pkgName to step)
            }
        }
    }

    fun uninstall(pkgName: String) {
        backend.uninstallExtension(pkgName)
    }

    fun trust(entry: ExtensionEntry) {
        scope.launch { backend.trustExtension(entry.pkgName, entry.versionCode, entry.signatureHash) }
    }
}

// ---------- Source browse / search ----------

class SourceBrowseViewModel(
    val backend: MangaBackend,
    val source: MangaSourceInfo,
    private val mangaRepo: MangaRepository,
    initialQuery: String = "",
) {
    val scope = mangaVmScope()

    data class BrowseState(
        val items: List<MangaBrowseItem> = emptyList(),
        val page: Int = 1,
        val hasNextPage: Boolean = false,
        val loading: Boolean = false,
        val loadingNext: Boolean = false,
        val error: String? = null,
        val mode: BrowseMode = BrowseMode.POPULAR,
        val query: String = "",
        val filters: List<MangaFilter>? = null,
    )

    val state = MutableStateFlow(BrowseState(query = initialQuery))
    val filterTemplate = MutableStateFlow<List<MangaFilter>>(emptyList())

    init {
        scope.launch { filterTemplate.value = backend.getFilterTemplate(source.id) }
        reload(BrowseMode.POPULAR, initialQuery, null)
    }

    fun reload(mode: BrowseMode, query: String, filters: List<MangaFilter>?) {
        scope.launch {
            state.value = BrowseState(mode = mode, query = query, filters = filters, loading = true)
            try {
                val result = backend.fetchBrowse(source.id, 1, mode, query, filters)
                state.value = state.value.copy(
                    items = result.items,
                    page = 1,
                    hasNextPage = result.hasNextPage,
                    loading = false,
                    error = null,
                )
            } catch (e: Throwable) {
                state.value = state.value.copy(loading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    fun loadNextPage() {
        val current = state.value
        if (current.loading || current.loadingNext || !current.hasNextPage) return
        scope.launch {
            state.value = current.copy(loadingNext = true)
            try {
                val result = backend.fetchBrowse(
                    source.id,
                    current.page + 1,
                    current.mode,
                    current.query,
                    current.filters,
                )
                state.value = state.value.copy(
                    items = state.value.items + result.items,
                    page = current.page + 1,
                    hasNextPage = result.hasNextPage,
                    loadingNext = false,
                    error = null,
                )
            } catch (e: Throwable) {
                state.value = state.value.copy(loadingNext = false, error = e.message ?: "Failed to load")
            }
        }
    }

    suspend fun isInLibrary(item: MangaBrowseItem): Boolean =
        mangaRepo.findBySourceUrl(source.id, item.url)?.inLibrary == true

    /** Persists a tapped result so the detail screen can open it, without adding to library. */
    suspend fun ensureEntry(item: MangaBrowseItem): MangaEntry {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { return it }
        val entry = MangaEntry(
            id = mangaId(source.id, item.url),
            sourceId = source.id,
            sourceName = source.name,
            url = item.url,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            inLibrary = false,
            addedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        mangaRepo.upsert(entry)
        return entry
    }

    /** Adds a browse result to the library (long-press / explicit add). */
    suspend fun addToLibrary(item: MangaBrowseItem): MangaEntry {
        val existing = mangaRepo.findBySourceUrl(source.id, item.url)
        if (existing != null) {
            mangaRepo.setInLibrary(existing.id, true)
            return existing
        }
        val entry = MangaEntry(
            id = mangaId(source.id, item.url),
            sourceId = source.id,
            sourceName = source.name,
            url = item.url,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            inLibrary = true,
            addedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        mangaRepo.upsert(entry)
        return entry
    }

    suspend fun removeFromLibrary(item: MangaBrowseItem) {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { mangaRepo.setInLibrary(it.id, false) }
    }
}

enum class ChapterFilter(val label: String) {
    ALL("All chapters"),
    HIDE_READ("Hide read"),
    UNREAD("Unread only"),
    DOWNLOADED("Downloaded only"),
    BOOKMARKED("Bookmarked only"),
}

// ---------- Manga detail ----------

class MangaDetailViewModel(
    private val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
    private val chapterRepo: MangaChapterRepository,
    private val historyRepo: MangaHistoryRepository,
    private val downloadManager: MangaDownloadManager?,
) {
    val scope = mangaVmScope()

    val manga = MutableStateFlow<MangaEntry?>(null)
    val chapters = MutableStateFlow<List<MangaChapter>>(emptyList())
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val sortAscending = MutableStateFlow(false)
    val chapterFilter = MutableStateFlow(ChapterFilter.ALL)
    val queuedChapters = MutableStateFlow<Set<String>>(emptySet())

    fun applyFilter(list: List<com.folio.reader.manga.MangaChapter>): List<com.folio.reader.manga.MangaChapter> =
        when (chapterFilter.value) {
            ChapterFilter.ALL -> list
            ChapterFilter.HIDE_READ -> list.filter { !it.read }
            ChapterFilter.UNREAD -> list.filter { !it.read }
            ChapterFilter.DOWNLOADED -> list.filter { it.downloadedPages > 0 }
            ChapterFilter.BOOKMARKED -> list.filter { it.bookmarked }
        }

    fun open(mangaId: String) {
        scope.launch {
            manga.value = mangaRepo.get(mangaId)
            chapters.value = chapterRepo.getChapters(mangaId)
            if (manga.value?.initialized != true || chapters.value.isEmpty()) {
                refresh()
            }
        }
    }

    fun refresh() {
        scope.launch {
            val m = manga.value ?: return@launch
            refreshing.value = true
            error.value = null
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
                        chapterNumber = ref.chapterNumber,
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
            } catch (e: Throwable) {
                println("MangaDetail refresh failed: " + e.stackTraceToString())
                error.value = e.message ?: "Failed to load manga"
            }
            refreshing.value = false
        }
    }

    fun toggleInLibrary() {
        scope.launch {
            val m = manga.value ?: return@launch
            val updated = m.copy(inLibrary = !m.inLibrary)
            mangaRepo.upsert(updated)
            manga.value = updated
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
            val idx = all.indexOfFirst { it.id == chapter.id }
            if (idx >= 0) {
                chapterRepo.markRead(all.take(idx).map { it.id }, true)
                chapters.value = chapterRepo.getChapters(chapter.mangaId)
            }
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
        scope.launch {
            manager.queueChapter(chapter.mangaId, chapter)
            queuedChapters.value = queuedChapters.value + chapter.id
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
        scope.launch { chapterRepo.markRead(ids, read); clearChapterSelection() }
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
                manager.chapterDir(chapter.mangaId, chapter.id)?.deleteRecursively()
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
                queuedChapters.value = queuedChapters.value + chapter.id
            }
        }
    }

    /** First unread chapter, falling back to the most recently read one. */
    suspend fun nextChapterToRead(): MangaChapter? {
        val all = chapterRepo.getChapters(manga.value?.id ?: return null)
        return all.firstOrNull { !it.read } ?: all.firstOrNull()
    }

    fun continueFrom(chapter: MangaChapter): MangaChapter? {
        val all = chapters.value
        val idx = all.indexOfFirst { it.id == chapter.id }
        return if (idx >= 0 && idx + 1 < all.size) all[idx + 1] else null
    }

    fun recordHistory(chapterId: String?) {
        val m = manga.value ?: return
        scope.launch { historyRepo.record(m.id, chapterId) }
    }

    fun observeDownloads(): kotlinx.coroutines.flow.Flow<Set<String>>? = downloadManager?.let { manager ->
        kotlinx.coroutines.flow.flow {
            // Simple polling-free view: re-emit when queued set changes; downloads screen shows live queue.
            emit(queuedChapters.value)
        }
    }
}

// ---------- Reader ----------

enum class MangaReaderMode { WEBTOON, PAGED_LTR, PAGED_RTL, PAGED_VERTICAL }

private const val KEY_READER_MODE = "manga.reader.mode"

class MangaReaderViewModel(
    private val backend: MangaBackend,
    private val downloadManager: MangaDownloadManager?,
    private val chapterRepo: MangaChapterRepository,
    private val mangaRepo: MangaRepository,
    private val historyRepo: MangaHistoryRepository,
    private val noteRepo: com.folio.reader.manga.MangaNoteRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
    private val sessionRepo: com.folio.reader.database.ReadingSessionRepository? = null,
) {
    val scope = mangaVmScope()

    val manga = MutableStateFlow<MangaEntry?>(null)
    val chapter = MutableStateFlow<MangaChapter?>(null)
    val pages = MutableStateFlow<List<MangaPageRef>>(emptyList())
    val currentIndex = MutableStateFlow(0)
    val mode = MutableStateFlow(MangaReaderMode.WEBTOON)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val showControls = MutableStateFlow(true)
    val notesRevision = MutableStateFlow(0)

    private var mangaId: String = ""
    private var sourceId: Long = 0L
    private var activeSession: com.folio.reader.model.ReadingSession? = null
    private var lastProgressSaveMs: Long = 0L

    init {
        scope.launch {
            settingsRepo.getRaw(KEY_READER_MODE)?.let { name ->
                MangaReaderMode.entries.firstOrNull { it.name == name }?.let { mode.value = it }
            }
        }
    }

    fun setMode(newMode: MangaReaderMode) {
        mode.value = newMode
        scope.launch { settingsRepo.setRaw(KEY_READER_MODE, newMode.name) }
    }

    fun toggleBookmark() {
        scope.launch {
            val c = chapter.value ?: return@launch
            chapterRepo.setBookmarked(c.id, !c.bookmarked)
            chapter.value = chapterRepo.getChapter(c.id)
        }
    }

    suspend fun chapterNotes(): List<com.folio.reader.manga.MangaNote> =
        chapter.value?.let { noteRepo.notesForChapter(it.id) } ?: emptyList()

    fun saveNote(content: String, pageIndex: Int, existing: com.folio.reader.manga.MangaNote?) {
        val c = chapter.value ?: return
        val m = manga.value ?: return
        scope.launch {
            val now = kotlinx.datetime.Clock.System.now()
            noteRepo.upsert(
                com.folio.reader.manga.MangaNote(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    mangaId = m.id,
                    chapterId = c.id,
                    pageIndex = pageIndex,
                    content = content,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            notesRevision.value++
        }
    }

    fun deleteNote(noteId: String) {
        scope.launch {
            noteRepo.delete(noteId)
            notesRevision.value++
        }
    }

    fun open(manga: MangaEntry, chapter: MangaChapter) {
        this.mangaId = manga.id
        this.sourceId = manga.sourceId
        this.manga.value = manga
        this.chapter.value = chapter
        currentIndex.value = 0
        error.value = null
        startSession(manga, chapter)
        scope.launch {
            loading.value = true
            try {
                val ref = MangaChapterRef(url = chapter.url, name = chapter.name, chapterNumber = chapter.chapterNumber)
                val result = backend.fetchPageList(sourceId, ref)
                pages.value = result
                // Resume one page past the saved position (page N means N pages already seen).
                if (chapter.lastPageRead in 1 until result.size) {
                    currentIndex.value = chapter.lastPageRead
                }
                historyRepo.record(manga.id, chapter.id)
            } catch (e: Throwable) {
                error.value = e.message ?: "Failed to load pages"
            }
            loading.value = false
        }
    }

    private fun startSession(manga: MangaEntry, chapter: MangaChapter) {
        val repo = sessionRepo ?: return
        val now = kotlinx.datetime.Clock.System.now()
        val position = com.folio.reader.model.ReadingPosition(
            bookId = manga.id,
            deviceId = "",
            chapterId = chapter.id,
            spineIndex = 0,
            contentLocator = "manga-page:0",
        )
        val session = com.folio.reader.model.ReadingSession(
            id = java.util.UUID.randomUUID().toString(),
            bookId = manga.id,
            cycleId = null,
            deviceId = "",
            startedAt = now,
            startPosition = position,
        )
        activeSession = session
        scope.launch { runCatching { repo.insertSession(session) } }
    }

    /** Ends the active reading session, recording elapsed time for statistics/sync. */
    suspend fun close() {
        saveProgress()
        val repo = sessionRepo ?: return
        val session = activeSession ?: return
        activeSession = null
        val now = kotlinx.datetime.Clock.System.now()
        val ended = session.copy(
            endedAt = now,
            durationMs = (now - session.startedAt).inWholeMilliseconds,
            isActive = false,
        )
        runCatching { repo.updateSession(ended) }
    }

    suspend fun resolvePageImage(index: Int): ByteArray? {
        val c = chapter.value ?: return null
        val page = pages.value.getOrNull(index) ?: return null
        val downloaded = downloadManager?.readDownloadedPage(mangaId, c.id, index)
        if (downloaded != null) return downloaded
        val ref = MangaChapterRef(url = c.url, name = c.name, chapterNumber = c.chapterNumber)
        return try {
            backend.fetchPageImage(sourceId, ref, page).bytes
        } catch (_: Throwable) {
            null
        }
    }

    fun onPageChanged(index: Int) {
        currentIndex.value = index
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        if (now - lastProgressSaveMs > 2000) {
            lastProgressSaveMs = now
            scope.launch { saveProgress() }
        }
    }

    fun toggleControls() {
        showControls.value = !showControls.value
    }

    suspend fun saveProgress() {
        val c = chapter.value ?: return
        val last = currentIndex.value
        val total = pages.value.size
        chapterRepo.saveProgress(c.id, last, total)
        if (total > 0 && last >= total - 1) {
            chapterRepo.markRead(listOf(c.id), true)
        }
        mangaRepo.touchLastRead(mangaId)
    }
}

// ---------- Downloads ----------

class DownloadsViewModel(
    private val downloadRepo: MangaDownloadRepository,
    private val mangaRepo: MangaRepository,
    private val chapterRepo: MangaChapterRepository,
    private val downloadManager: MangaDownloadManager,
) {
    val scope = mangaVmScope()

    val queue = downloadRepo.observeQueue()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val mangaTitles = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        scope.launch {
            mangaRepo.observeAll().collect { list ->
                mangaTitles.value = list.associate { it.id to it.title }
            }
        }
    }

    fun cancel(downloadId: String) {
        scope.launch { downloadManager.cancel(downloadId) }
    }

    fun clearFinished() {
        scope.launch { downloadRepo.clearFinished() }
    }
}
