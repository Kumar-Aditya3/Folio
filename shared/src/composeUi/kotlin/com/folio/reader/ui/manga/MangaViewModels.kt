package com.folio.reader.ui.manga

import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ChapterNumberParser
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

private const val KEY_LIBRARY_CATEGORY = "manga.library.category"

/**
 * Story position of a chapter. Sources disagree on list direction (some fetch
 * newest-first, so sortOrder 0 is the LATEST chapter) and many leave
 * chapter_number unset, so the number is recovered from the title when needed.
 * Chapters without any recognizable number sort after numbered ones by source
 * list position.
 */
private fun storyNumber(chapter: MangaChapter): Float =
    if (chapter.chapterNumber >= 0f) chapter.chapterNumber else ChapterNumberParser.parse(chapter.name)

/**
 * The reading order of the story, independent of how the source lists chapters
 * or how the detail screen displays them. Continue, mark-previous-as-read and
 * reader navigation follow this; the display sort toggle only arranges the list.
 */
private val STORY_ORDER = Comparator<MangaChapter> { a, b ->
    val an = storyNumber(a)
    val bn = storyNumber(b)
    when {
        an >= 0f && bn >= 0f -> {
            val byNumber = an.compareTo(bn)
            if (byNumber != 0) byNumber else a.sortOrder.compareTo(b.sortOrder)
        }
        an >= 0f -> -1
        bn >= 0f -> 1
        else -> a.sortOrder.compareTo(b.sortOrder)
    }
}

class MangaLibraryViewModel(
    private val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
    private val categoryRepo: MangaCategoryRepository,
    private val chapterRepo: MangaChapterRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
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
    /** One query shared by both scopes so the text survives library/source switches. */
    val searchScope = MutableStateFlow(MangaSearchScope.LIBRARY)

    val sortBy = MutableStateFlow(MangaSortBy.RECENT)
    val activeFilters = MutableStateFlow<Set<MangaLibFilter>>(emptySet())
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)
    val selectedCategoryId = MutableStateFlow<String?>(null)
    /** Live membership of the selected category; a stale snapshot here is what made
     *  freshly added manga invisible until the library was re-entered. */
    private val categoryMembership: StateFlow<Set<String>?> = selectedCategoryId
        .flatMapLatest { id ->
            if (id == null) flowOf<Set<String>?>(null)
            else categoryRepo.observeMangaIdsInCategory(id).map { ids -> ids as Set<String>? }
        }
        .stateIn(scope, SharingStarted.Lazily, null)

    private val baseList: StateFlow<List<MangaEntry>> =
        combine(library, query, searchActive, categoryMembership) { list, q, searching, membership ->
            list.filter { manga ->
                ((!searching || q.isBlank()) || manga.title.contains(q, ignoreCase = true)) &&
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

    /** No virtual All bucket: the library always shows one real category. */
    fun selectCategory(categoryId: String) {
        selectedCategoryId.value = categoryId
        scope.launch { settingsRepo.setRaw(KEY_LIBRARY_CATEGORY, categoryId) }
    }

    /** Selects Main when present, otherwise the first category; used at startup and after deletes. */
    fun selectDefaultCategory() {
        scope.launch {
            val target = categoryRepo.defaultCategory() ?: return@launch
            if (selectedCategoryId.value != target.id) selectCategory(target.id)
        }
    }

    init {
        // Follow the category list so a fresh default selection lands as soon as Main
        // exists, and a deleted selection falls back to the default instead of nothing.
        scope.launch {
            categories.collect { list ->
                val current = selectedCategoryId.value
                if (list.none { it.id == current }) {
                    // First selection of the session: reopen the shelf the reader left,
                    // but only while it still exists; a gone/dirty remembered id degrades
                    // to the default rather than resurrecting a deleted category.
                    val remembered = if (current == null) settingsRepo.getRaw(KEY_LIBRARY_CATEGORY) else null
                    if (remembered != null && list.any { it.id == remembered }) selectCategory(remembered)
                    else selectDefaultCategory()
                }
            }
        }
    }

    fun toggleFilter(filter: MangaLibFilter) {
        activeFilters.value = if (filter in activeFilters.value) activeFilters.value - filter else activeFilters.value + filter
    }

    fun setQuickFilter(filter: MangaLibFilter?) {
        activeFilters.value = if (filter == null) emptySet() else setOf(filter)
    }

    suspend fun createCategory(name: String): String? =
        runCatching { categoryRepo.create(name).id }.getOrNull()

    fun renameCategory(id: String, name: String) {
        scope.launch { categoryRepo.rename(id, name) }
    }

    fun deleteCategory(id: String) {
        scope.launch {
            if (categoryRepo.delete(id) && selectedCategoryId.value == id) selectDefaultCategory()
        }
    }

    suspend fun categoriesFor(mangaId: String): Set<String> = categoryRepo.categoriesFor(mangaId)

    /** Picker apply for one manga (library item overflow menu); empty keeps the default shelf. */
    fun setCategoriesFor(mangaId: String, categoryIds: Set<String>) {
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            categoryRepo.assign(mangaId, target)
        }
    }

    /**
     * Bulk category picker, opened from the selection top bar. The initial selection is
     * the categories shared by every selected manga; saving replaces each one's set.
     */
    val bulkPickerInitial = MutableStateFlow<Set<String>?>(null)

    fun requestBulkCategories() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val lists = ids.map { categoryRepo.categoriesFor(it) }
            bulkPickerInitial.value = lists.reduceOrNull { a, b -> a.intersect(b) } ?: emptySet()
        }
    }

    fun closeBulkPicker() {
        bulkPickerInitial.value = null
    }

    /** Live-applies the picker's current set to every selected manga; selection stays. */
    fun applyBulkCategories(categoryIds: Set<String>) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            ids.forEach { categoryRepo.assign(it, target) }
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
                                chapterNumber =
                                    if (ref.chapterNumber >= 0f) ref.chapterNumber
                                    else ChapterNumberParser.parse(ref.name),
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
        /** Relevance of the section's strongest match; sections order by this, not arrival. */
        val bestScore: Int = 0,
    )

    val globalQuery = MutableStateFlow("")
    val globalResults = MutableStateFlow<List<GlobalSourceResult>>(emptyList())
    val searchActive = MutableStateFlow(false)
    /** True while the installed-source list is still resolving before a search can fan out. */
    val preparingSources = MutableStateFlow(false)
    private var globalJob: kotlinx.coroutines.Job? = null
    private val searchGate = kotlinx.coroutines.sync.Semaphore(5)
    private val searchArrival = MutableStateFlow<List<Long>>(emptyList())

    /**
     * Search sections in display order: sources that already found something come first,
     * ranked by how well their best result matches the query (an exact title match beats
     * a fuzzy one no matter which source answered first); ties keep arrival order so the
     * streaming feel survives. Still-searching sources follow, then errors.
     */
    val globalResultsOrdered: StateFlow<List<GlobalSourceResult>> =
        combine(globalResults, searchArrival) { list, arrival ->
            list.filter { it.items.isNotEmpty() || it.loading || it.error != null }
                .sortedWith(
                    compareByDescending<GlobalSourceResult> { it.items.isNotEmpty() }
                        .thenByDescending { it.bestScore }
                        .thenBy {
                            val idx = arrival.indexOf(it.source.id)
                            if (idx < 0) Int.MAX_VALUE else idx
                        },
                )
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    fun toggleSearch() {
        val next = !searchActive.value
        searchActive.value = next
        if (!next) {
            // Closing the search bar stops the fan-out but keeps the query text, so
            // reopening resumes where the user left off.
            globalJob?.cancel()
            globalResults.value = emptyList()
            searchArrival.value = emptyList()
            preparingSources.value = false
        }
    }

    fun exitSearch() {
        searchActive.value = false
        globalJob?.cancel()
        globalResults.value = emptyList()
        searchArrival.value = emptyList()
        preparingSources.value = false
    }

    fun globalSearch(query: String) {
        globalQuery.value = query
        globalJob?.cancel()
        if (query.isBlank()) {
            globalResults.value = emptyList()
            searchArrival.value = emptyList()
            preparingSources.value = false
            return
        }
        globalJob = scope.launch {
            preparingSources.value = true
            // Sources load asynchronously as extensions unpack; an empty snapshot would
            // silently search nothing. Await the first non-empty list instead.
            val targets = if (sources.value.isNotEmpty()) sources.value else sources.first { it.isNotEmpty() }
            globalResults.value = targets.map { GlobalSourceResult(it) }
            searchArrival.value = emptyList()
            preparingSources.value = false
            targets.map { source ->
                launch {
                    searchGate.withPermit {
                        try {
                            val page = backend.fetchBrowse(source.id, 1, BrowseMode.POPULAR, query, null)
                            // Sources answer in their own order; rank what they return so a
                            // stronger title match is never buried below a weak one. The sort
                            // is stable, so equal scores keep the source's own ordering.
                            val ranked = MangaSearchRanker.rank(page.items, query)
                            if (ranked.isNotEmpty()) {
                                searchArrival.value = searchArrival.value + source.id
                            }
                            setGlobalResult(source.id) {
                                it.copy(
                                    items = ranked,
                                    loading = false,
                                    error = if (ranked.isEmpty()) "No results" else null,
                                    bestScore = ranked.firstOrNull()
                                        ?.let { item -> MangaSearchRanker.score(query, item.title) }
                                        ?: 0,
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
    private val categoryRepo: com.folio.reader.manga.MangaCategoryRepository,
    initialQuery: String = "",
    private val onAddedToLibrary: (suspend (mangaId: String) -> Unit)? = null,
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
                    items = MangaSearchRanker.rank(result.items, query),
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
                    items = state.value.items + MangaSearchRanker.rank(result.items, current.query),
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
            categoryRepo.ensureMembership(existing.id)
            notifyAddedToLibrary(existing.id)
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
        categoryRepo.ensureMembership(entry.id)
        notifyAddedToLibrary(entry.id)
        return entry
    }

    private fun notifyAddedToLibrary(mangaId: String) {
        val hook = onAddedToLibrary ?: return
        scope.launch { runCatching { hook(mangaId) } }
    }

    suspend fun removeFromLibrary(item: MangaBrowseItem) {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { mangaRepo.setInLibrary(it.id, false) }
    }

    // ---------- Category prompt on add-to-library ----------

    val allCategories = categoryRepo.observeCategories()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    suspend fun categoriesFor(mangaId: String): Set<String> = categoryRepo.categoriesFor(mangaId)

    suspend fun createCategoryNamed(name: String): String? =
        runCatching { categoryRepo.create(name).id }.getOrNull()

    /** Replaces the shelf set for a manga; an empty set lands on the default shelf. */
    fun setCategoriesFor(mangaId: String, categoryIds: Set<String>) {
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            categoryRepo.assign(mangaId, target)
        }
    }
}

enum class ChapterFilter(val label: String) {
    ALL("All chapters"),
    HIDE_READ("Hide read"),
    UNREAD("Unread only"),
    DOWNLOADED("Downloaded only"),
    BOOKMARKED("Bookmarked only"),
}

fun filterChapters(list: List<MangaChapter>, filter: ChapterFilter): List<MangaChapter> =
    when (filter) {
        ChapterFilter.ALL -> list
        ChapterFilter.HIDE_READ -> list.filter { !it.read }
        ChapterFilter.UNREAD -> list.filter { !it.read }
        ChapterFilter.DOWNLOADED -> list.filter { it.downloadedPages > 0 }
        ChapterFilter.BOOKMARKED -> list.filter { it.bookmarked }
    }

private const val KEY_CHAPTER_FILTER = "manga.chapter.filter"
private const val KEY_CHAPTER_SORT = "manga.chapter.sort"

// ---------- Manga detail ----------

class MangaDetailViewModel(
    private val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
    private val chapterRepo: MangaChapterRepository,
    private val historyRepo: MangaHistoryRepository,
    private val downloadManager: MangaDownloadManager?,
    private val categoryRepo: com.folio.reader.manga.MangaCategoryRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
) {
    val scope = mangaVmScope()

    val manga = MutableStateFlow<MangaEntry?>(null)
    val chapters = MutableStateFlow<List<MangaChapter>>(emptyList())
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val sortAscending = MutableStateFlow(false)
    val chapterFilter = MutableStateFlow(ChapterFilter.ALL)
    val queuedChapters = MutableStateFlow<Set<String>>(emptySet())

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
            val savedFilter = settingsRepo.getRaw("$KEY_CHAPTER_FILTER.$mangaId")
            if (savedFilter != null) {
                chapterFilter.value = ChapterFilter.entries.firstOrNull { it.name == savedFilter } ?: ChapterFilter.ALL
            } else {
                chapterFilter.value = ChapterFilter.ALL
            }
            val savedSort = settingsRepo.getRaw("$KEY_CHAPTER_SORT.$mangaId")
            sortAscending.value = savedSort == "ASC"
            if (manga.value?.initialized != true || chapters.value.isEmpty()) {
                refresh()
            }
        }
        scope.launch { categoryRepo.observeCategoriesFor(mangaId).collect { myCategoryIds.value = it } }
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

data class ChapterSlot(
    val chapter: MangaChapter,
    val pages: List<MangaPageRef>,
    val startIndex: Int,
)

class MangaReaderViewModel(
    private val backend: MangaBackend,
    private val downloadManager: MangaDownloadManager?,
    private val chapterRepo: MangaChapterRepository,
    private val mangaRepo: MangaRepository,
    private val historyRepo: MangaHistoryRepository,
    private val noteRepo: com.folio.reader.manga.MangaNoteRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
    private val fileSystem: com.folio.reader.platform.FolioFileSystem,
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
    val localPage = MutableStateFlow(0)
    val localCount = MutableStateFlow(0)
    val extendingForward = MutableStateFlow(false)

    /**
     * Explicit one-shot seeks (resume on open, slider). [currentIndex] is the reading
     * position tracker only — driving scrolls from it feeds back into itself, because
     * visible-page tracking writes it during normal scrolling, and chapter prepends
     * shift it. That loop snapped the webtoon viewport mid-scroll (visible page jumps).
     */
    private val seekChannel = Channel<Int>(Channel.CONFLATED)
    val seekRequests = seekChannel.receiveAsFlow()

    /** Reader zoom shared by the whole flow: webtoon widens every page, paged modes scale the sheet. */
    val zoom = MutableStateFlow(1f)

    fun setZoom(level: Float) {
        // Webtoon may zoom out below fit-width for a thinner, longer stream;
        // paged modes keep the fit floor so the sheet never shrinks away.
        val min = if (mode.value == MangaReaderMode.WEBTOON) 0.5f else 1f
        zoom.value = level.coerceIn(min, 3f)
    }

    fun resetZoom() {
        zoom.value = 1f
    }

    private var mangaId: String = ""
    private var sourceId: Long = 0L
    private var activeSession: com.folio.reader.model.ReadingSession? = null
    private var lastProgressSaveMs: Long = 0L

    private var navList: List<MangaChapter> = emptyList()
    private var slots: MutableList<ChapterSlot> = mutableListOf()
    /** Slot assembly runs across background coroutines (prev/next splice in parallel);
     *  every read and write of [slots] goes through [withSlots]. */
    private val slotsLock = Any()
    private inline fun <T> withSlots(block: MutableList<ChapterSlot>.() -> T): T =
        synchronized(slotsLock) { slots.block() }
    private val failedChapters = mutableSetOf<String>()
    private var isAtEndOfNav = false
    private var extendingBackward = false
    private var openJob: Job? = null

    /** Index (in [slots]) of the slot holding the current page; forward moves past a
     *  slot finalize that chapter as read. */
    private var lastSlotIdx = 0
    private val finalizedChapters = mutableSetOf<String>()

    // ---------- Position-aware chapter loading ----------

    /** Page lists fetched ahead of use, keyed by chapter id. Bounded; eldest evicted. */
    private val pageListCache = LinkedHashMap<String, List<MangaPageRef>>()
    /** Chapter-list fetches are network round trips; keep them to a small window. */
    private val pageListGate = Semaphore(2)
    private var prefetchJob: Job? = null

    // ---------- Position-aware page image prefetch ----------

    private val imageGate = Semaphore(IMAGE_PREFETCH_CONCURRENCY)
    private var imagePrefetchJob: Job? = null
    private val pageBytes = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var pageBytesTotal = 0L
    private val inflightBytes = HashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()

    // ---------- Serialized progress persistence ----------

    /**
     * Reading position writes funnel through one channel consumed by one coroutine, so
     * snapshots always land in the order the reader saw them. The previous fire-and-forget
     * launches raced the DB mutex and a stale page could overwrite a newer one.
     */
    private data class ProgressSnapshot(
        val chapterId: String,
        val lastPage: Int,
        val totalPages: Int,
        val finished: Boolean,
    )

    private val progressQueue = Channel<ProgressSnapshot>(Channel.UNLIMITED)
    private val progressMutex = Mutex()
    private var saveWorker: Job? = null

    fun pageKey(index: Int): String {
        for (slot in withSlots { toList() }) {
            val local = index - slot.startIndex
            if (local in slot.pages.indices) return "${slot.chapter.id}:$local"
        }
        return "unknown:$index"
    }

    fun seekLocal(local: Int) {
        val activeSlot = activeSlot() ?: return
        val combined = activeSlot.startIndex + local
        onPageChanged(combined)
        seekChannel.trySend(combined)
    }

    fun previousBeyond(): MangaChapter? {
        val firstSlotChapterId = withSlots { firstOrNull()?.chapter?.id } ?: return null
        val navIdx = navList.indexOfFirst { it.id == firstSlotChapterId }
        return if (navIdx > 0) navList[navIdx - 1] else null
    }

    fun activeSlotForDisplay(): ChapterSlot? = activeSlot()

    fun isAtEndOfNavList(): Boolean = isAtEndOfNav

    private fun activeSlot(): ChapterSlot? {
        val idx = currentIndex.value
        for (slot in withSlots { toList() }) {
            if (idx in slot.startIndex until slot.startIndex + slot.pages.size) return slot
        }
        return withSlots { lastOrNull() }
    }

    private fun slotForIndex(index: Int): ChapterSlot? {
        for (slot in withSlots { toList() }) {
            if (index in slot.startIndex until slot.startIndex + slot.pages.size) return slot
        }
        return null
    }

    fun setMode(newMode: MangaReaderMode) {
        mode.value = newMode
        val id = mangaId
        scope.launch { settingsRepo.setRaw(readerModeKey(id), newMode.name) }
    }

    private fun readerModeKey(mangaId: String) = "$KEY_READER_MODE.$mangaId"

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
        openJob?.cancel()
        prefetchJob?.cancel()
        imagePrefetchJob?.cancel()
        openJob = scope.launch {
            val savedName = settingsRepo.getRaw(readerModeKey(manga.id))
                ?: settingsRepo.getRaw(KEY_READER_MODE)
            mode.value = MangaReaderMode.entries.firstOrNull { it.name == savedName }
                ?: MangaReaderMode.WEBTOON
            loading.value = true
            try {
                val allChapters = chapterRepo.getChapters(manga.id)
                val savedFilterName = settingsRepo.getRaw("$KEY_CHAPTER_FILTER.${manga.id}")
                val filter = ChapterFilter.entries.firstOrNull { it.name == savedFilterName } ?: ChapterFilter.ALL
                // Navigation always runs in story order (chapter numbers, recovered
                // from titles when unset); the detail screen's display sort and the
                // source's own list direction (some list newest-first) never change it.
                val filtered = filterChapters(allChapters, filter).sortedWith(STORY_ORDER)
                navList = if (filtered.any { it.id == chapter.id }) filtered
                    else allChapters.sortedWith(STORY_ORDER)

                val navIdx = navList.indexOfFirst { it.id == chapter.id }
                withSlots {
                    clear()
                    failedChapters.clear()
                }
                finalizedChapters.clear()
                isAtEndOfNav = false

                // Current fetches alone first so the opened chapter can never queue behind
                // a neighbour at the 2-permit gate; prev and next then share the permits —
                // prev is awaited before the first publish, next appends asynchronously
                // (appending never shifts existing indices).
                val prevCh = navList.getOrNull(navIdx - 1)
                val nextCh = navList.getOrNull(navIdx + 1)

                val currentSlot = pageListGate.withPermit { fetchSlotCached(navList[navIdx]) }
                if (currentSlot == null) {
                    error.value = "Failed to load pages"
                    loading.value = false
                    return@launch
                }
                val prevDeferred = prevCh?.let { ch -> async { pageListGate.withPermit { fetchSlotCached(ch) } } }
                val nextDeferred = nextCh?.let { ch -> async { pageListGate.withPermit { fetchSlotCached(ch) } } }
                val prevSlot = prevDeferred?.await()

                var offset = 0
                if (prevSlot != null) {
                    withSlots { add(prevSlot.copy(startIndex = 0)) }
                    offset = prevSlot.pages.size
                    lastSlotIdx = 1
                } else {
                    lastSlotIdx = 0
                }
                withSlots { add(currentSlot.copy(startIndex = offset)) }
                // Restore the saved page (offset by the prepended chapter) BEFORE publishing
                // so the first composition already points at the resume position.
                val resumeLocal =
                    if (chapter.lastPageRead in 1 until currentSlot.pages.size) chapter.lastPageRead else 0
                currentIndex.value = offset + resumeLocal
                seekChannel.trySend(offset + resumeLocal)
                publishPages()
                updateActiveChapter()
                historyRepo.record(manga.id, chapter.id)
                loading.value = false
                scheduleImagePrefetch()

                launch {
                    val slot = nextDeferred?.await()
                    if (slot != null) {
                        appendSlot(slot)
                    } else if (nextCh == null) {
                        isAtEndOfNav = true
                    }
                    scheduleChapterPrefetch()
                    scheduleImagePrefetch()
                }
            } catch (e: Throwable) {
                error.value = e.message ?: "Failed to load pages"
                loading.value = false
            }
        }
    }

    private fun prependSlot(slot: ChapterSlot) {
        withSlots {
            if (any { it.chapter.id == slot.chapter.id }) return
            val added = slot.pages.size
            for (i in indices) {
                set(i, this[i].copy(startIndex = this[i].startIndex + added))
            }
            add(0, slot.copy(startIndex = 0))
        }
        currentIndex.value = currentIndex.value + slot.pages.size
        lastSlotIdx += 1
        publishPages()
    }

    private fun appendSlot(slot: ChapterSlot) {
        withSlots {
            if (any { it.chapter.id == slot.chapter.id }) return
            val last = last()
            add(slot.copy(startIndex = last.startIndex + last.pages.size))
        }
        if (navList.lastOrNull()?.id == slot.chapter.id) isAtEndOfNav = true
        publishPages()
    }

    private suspend fun fetchSlotCached(chapter: MangaChapter): ChapterSlot? {
        if (chapter.id in failedChapters) return null
        val cached = synchronized(pageListCache) { pageListCache[chapter.id] }
        if (cached != null) return ChapterSlot(chapter, cached, 0)
        return fetchSlot(chapter)
    }

    private suspend fun fetchSlot(chapter: MangaChapter): ChapterSlot? = try {
        val ref = MangaChapterRef(url = chapter.url, name = chapter.name, chapterNumber = chapter.chapterNumber)
        val pages = backend.fetchPageList(sourceId, ref)
        synchronized(pageListCache) {
            pageListCache[chapter.id] = pages
            while (pageListCache.size > PAGE_LIST_CACHE_MAX) {
                pageListCache.keys.firstOrNull()?.let(pageListCache::remove)
            }
        }
        ChapterSlot(chapter = chapter, pages = pages, startIndex = 0)
    } catch (_: Throwable) {
        failedChapters.add(chapter.id)
        null
    }

    /**
     * Keeps a rolling window of chapter page lists warm around the reading position:
     * next, next+1, prev, next+2 — in that priority. Replaces the previous job so a
     * position change cancels fetches that are no longer nearby.
     */
    private fun scheduleChapterPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val current = activeSlot() ?: return@launch
            val navIdx = navList.indexOfFirst { it.id == current.chapter.id }
            if (navIdx < 0) return@launch
            val loaded = withSlots { mapTo(mutableSetOf()) { it.chapter.id } }
            listOf(navIdx + 1, navIdx + 2, navIdx - 1, navIdx + 3)
                .distinct()
                .filter { it in navList.indices }
                .map { navList[it] }
                .filter { it.id !in loaded && it.id !in failedChapters }
                .forEach { ch ->
                    launch {
                        pageListGate.withPermit { fetchSlotCached(ch) }
                    }
                }
        }
    }

    private fun publishPages() {
        val all = withSlots { flatMap { it.pages } }
        pages.value = all
        localCount.value = activeSlot()?.pages?.size ?: 0
        updateLocals()
    }

    private fun updateLocals() {
        val slot = activeSlot() ?: return
        val local = currentIndex.value - slot.startIndex
        localPage.value = local
        localCount.value = slot.pages.size
    }

    private fun updateActiveChapter() {
        val slot = activeSlot() ?: return
        if (chapter.value?.id != slot.chapter.id) {
            chapter.value = slot.chapter
        }
        updateLocals()
    }

    fun extendForward() {
        if (extendingForward.value || isAtEndOfNav) return
        scope.launch {
            extendingForward.value = true
            try {
                val lastSlot = withSlots { lastOrNull() } ?: return@launch
                val lastNavIdx = navList.indexOfFirst { it.id == lastSlot.chapter.id }
                val nextNavIdx = lastNavIdx + 1
                if (lastNavIdx < 0 || nextNavIdx >= navList.size) {
                    isAtEndOfNav = true
                    return@launch
                }
                val slot = fetchSlotCached(navList[nextNavIdx])
                if (slot != null) {
                    appendSlot(slot)
                    scheduleChapterPrefetch()
                    scheduleImagePrefetch()
                } else if (nextNavIdx + 1 >= navList.size) {
                    isAtEndOfNav = true
                }
            } finally {
                extendingForward.value = false
            }
        }
    }

    fun extendBackward() {
        if (mode.value != MangaReaderMode.WEBTOON) return
        if (extendingBackward) return
        val firstSlot = withSlots { firstOrNull() } ?: return
        val firstNavIdx = navList.indexOfFirst { it.id == firstSlot.chapter.id }
        if (firstNavIdx <= 0) return
        extendingBackward = true
        scope.launch {
            try {
                val slot = fetchSlotCached(navList[firstNavIdx - 1]) ?: return@launch
                prependSlot(slot)
            } finally {
                extendingBackward = false
            }
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

    suspend fun close() {
        // Authoritative final write: let any queued snapshot land first, then persist the
        // current position. Resume logic (nextChapterToRead) must never observe a stale
        // position because a throttled save was dropped or reordered.
        progressMutex.withLock {
            while (true) {
                val pending = progressQueue.tryReceive().getOrNull() ?: break
                writeSnapshot(pending)
            }
            currentSnapshot()?.let { writeSnapshot(it) }
        }
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

    private fun currentSnapshot(): ProgressSnapshot? {
        val slot = activeSlot() ?: return null
        val total = slot.pages.size
        if (total <= 0) return null
        val local = (currentIndex.value - slot.startIndex).coerceIn(0, total - 1)
        return ProgressSnapshot(slot.chapter.id, local, total, finished = local >= total - 1)
    }

    private fun ensureSaveWorker() {
        if (saveWorker?.isActive == true) return
        saveWorker = scope.launch {
            for (snapshot in progressQueue) {
                progressMutex.withLock { writeSnapshot(snapshot) }
            }
        }
    }

    private suspend fun writeSnapshot(snapshot: ProgressSnapshot) {
        chapterRepo.saveProgress(snapshot.chapterId, snapshot.lastPage, snapshot.totalPages)
        if (snapshot.finished) {
            val existing = chapterRepo.getChapter(snapshot.chapterId)
            if (existing != null && !existing.read) {
                chapterRepo.markRead(listOf(snapshot.chapterId), true)
            }
        }
        mangaRepo.touchLastRead(mangaId)
    }

    /** Queues the current position; ordering is preserved by the single save worker. */
    private fun enqueueProgress() {
        val snapshot = currentSnapshot() ?: return
        ensureSaveWorker()
        progressQueue.trySend(snapshot)
    }

    /**
     * Chapters the reader moved forward past are finished by definition: their pages all
     * scrolled by. Finalizing here (instead of only on the active slot's last page) is
     * what keeps webtoon boundary crossings from stranding a chapter as unread.
     * [from] is inclusive, [to] exclusive — only the slots actually passed.
     */
    private fun finalizePassedSlots(from: Int, to: Int) {
        val passed = withSlots { subList(from.coerceAtLeast(0), to.coerceAtMost(size)).toList() }
        for (slot in passed) {
            if (slot.pages.isEmpty() || !finalizedChapters.add(slot.chapter.id)) continue
            ensureSaveWorker()
            progressQueue.trySend(
                ProgressSnapshot(slot.chapter.id, slot.pages.size - 1, slot.pages.size, finished = true)
            )
        }
    }

    suspend fun resolvePageImage(index: Int): ByteArray? = resolvePageBytes(index)

    /** Downloaded page, prefetched bytes, or a live source fetch — deduped per page key. */
    private suspend fun resolvePageBytes(index: Int): ByteArray? {
        val slot = slotForIndex(index) ?: return null
        val local = index - slot.startIndex
        val page = slot.pages.getOrNull(local) ?: return null
        val key = pageKey(index)
        val cached = synchronized(pageBytes) { pageBytes[key] }
        if (cached != null) return cached
        val deferred = synchronized(inflightBytes) {
            inflightBytes.getOrPut(key) {
                scope.async {
                    try {
                        val bytes = downloadManager?.readDownloadedPage(mangaId, slot.chapter.id, local)
                            ?: run {
                                val ref = MangaChapterRef(
                                    url = slot.chapter.url,
                                    name = slot.chapter.name,
                                    chapterNumber = slot.chapter.chapterNumber,
                                )
                                backend.fetchPageImage(sourceId, ref, page).bytes
                            }
                        if (bytes != null) pageBytesPut(key, bytes)
                        bytes
                    } catch (_: Throwable) {
                        null
                    } finally {
                        synchronized(inflightBytes) { inflightBytes.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }

    private fun pageBytesPut(key: String, bytes: ByteArray) {
        synchronized(pageBytes) {
            pageBytes[key]?.let { pageBytesTotal -= it.size }
            pageBytes[key] = bytes
            pageBytesTotal += bytes.size
            while (pageBytesTotal > PAGE_BYTES_CACHE_MAX && pageBytes.isNotEmpty()) {
                val eldest = pageBytes.entries.firstOrNull() ?: break
                pageBytes.remove(eldest.key)
                pageBytesTotal -= eldest.value.size
            }
        }
    }

    /**
     * Position-aware image prefetch: pages ahead in the current chapter first, then the
     * opening pages of the next chapter(s), then the tail of the previous one. Bounded
     * by [imageGate]; replacing the job cancels fetches the reader has moved away from.
     */
    private fun scheduleImagePrefetch() {
        imagePrefetchJob?.cancel()
        imagePrefetchJob = scope.launch {
            val from = currentIndex.value
            val slot = slotForIndex(from) ?: return@launch
            val targets = mutableListOf<Int>()
            var i = from + 1
            var ahead = 0
            while (ahead < IMAGE_PREFETCH_AHEAD && slotForIndex(i) == slot) {
                targets += i
                i++
                ahead++
            }
            val snapshot = withSlots { toList() }
            val slotIdx = snapshot.indexOf(slot)
            for (nextIdx in slotIdx + 1..minOf(slotIdx + 2, snapshot.lastIndex)) {
                val next = snapshot[nextIdx]
                for (j in 0 until minOf(IMAGE_PREFETCH_NEXT_PAGES, next.pages.size)) {
                    targets += next.startIndex + j
                }
            }
            if (slotIdx > 0) {
                val prev = snapshot[slotIdx - 1]
                for (j in maxOf(0, prev.pages.size - 2) until prev.pages.size) {
                    targets += prev.startIndex + j
                }
            }
            targets.forEach { idx ->
                launch {
                    imageGate.withPermit {
                        val key = pageKey(idx)
                        val present = synchronized(pageBytes) { pageBytes.containsKey(key) }
                        if (!present) resolvePageBytes(idx)
                    }
                }
            }
        }
    }

    suspend fun savePage(index: Int): String? {
        val bytes = resolvePageImage(index) ?: return null
        val slot = slotForIndex(index) ?: return null
        val local = index - slot.startIndex
        val mangaTitle = manga.value?.title?.ifBlank { "manga" } ?: "manga"
        val chapterName = slot.chapter.name.ifBlank { "chapter" }
        val safe = Regex("[^A-Za-z0-9 ._()-]")
        val base = "${mangaTitle.take(60)} - ${chapterName.take(40)} - p${local + 1}"
            .replace(safe, "_").trim()
        return runCatching {
            fileSystem.exportToDownloads("$base.${imageExtensionFor(bytes)}", bytes)
        }.getOrNull()
    }

    private fun imageExtensionFor(bytes: ByteArray): String = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
        bytes.size > 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "webp"
        else -> "img"
    }

    fun onPageChanged(index: Int) {
        val slot = slotForIndex(index) ?: return
        val changed = currentIndex.value != index
        currentIndex.value = index
        val slotIdx = withSlots { indexOf(slot) }
        if (slotIdx > lastSlotIdx) {
            // Forward move past one or more chapters: everything left behind is read.
            // Only the slots actually passed are finalized — never chapters that sat
            // before the session's starting point.
            finalizePassedSlots(lastSlotIdx, slotIdx)
        }
        lastSlotIdx = slotIdx
        if (chapter.value?.id != slot.chapter.id) {
            chapter.value = slot.chapter
            scope.launch { historyRepo.record(mangaId, slot.chapter.id) }
            // A chapter crossing is authoritative state; persist it outside the throttle.
            enqueueProgress()
        } else if (changed) {
            val local = index - slot.startIndex
            val total = slot.pages.size
            val atEnd = total > 0 && local >= total - 1
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            // The final page always persists: dropping it is what stranded chapters as
            // unread and broke Continue Reading.
            if (atEnd || now - lastProgressSaveMs > PROGRESS_SAVE_INTERVAL_MS) {
                lastProgressSaveMs = now
                enqueueProgress()
            }
        }
        updateLocals()
        checkExtensions(index)
        scheduleImagePrefetch()
    }

    /**
     * The webtoon reports the topmost visible page by its stable key, so position tracks
     * the viewport — not item composition — and survives chapter prepends without races.
     */
    fun onPageKeyVisible(key: String) {
        val index = indexForPageKey(key) ?: return
        onPageChanged(index)
    }

    private fun indexForPageKey(key: String): Int? {
        val sep = key.lastIndexOf(':')
        if (sep <= 0 || sep == key.length - 1) return null
        val local = key.substring(sep + 1).toIntOrNull() ?: return null
        val chapterKey = key.substring(0, sep)
        val slot = withSlots { firstOrNull { it.chapter.id == chapterKey } } ?: return null
        if (local !in slot.pages.indices) return null
        return slot.startIndex + local
    }

    private fun checkExtensions(index: Int) {
        val lastSlot = withSlots { lastOrNull() } ?: return
        val lastLocal = index - lastSlot.startIndex
        if (lastSlot.pages.isNotEmpty() && lastLocal >= lastSlot.pages.size - EXTEND_FORWARD_WINDOW) {
            extendForward()
        }
        if (mode.value == MangaReaderMode.WEBTOON) {
            val firstSlot = withSlots { firstOrNull() } ?: return
            val firstLocal = index - firstSlot.startIndex
            if (firstLocal <= 1) {
                extendBackward()
            }
        }
    }

    fun toggleControls() {
        showControls.value = !showControls.value
    }

    companion object {
        private const val PAGE_LIST_CACHE_MAX = 8
        private const val PAGE_BYTES_CACHE_MAX = 48L * 1024 * 1024
        private const val IMAGE_PREFETCH_AHEAD = 6
        private const val IMAGE_PREFETCH_NEXT_PAGES = 4
        private const val IMAGE_PREFETCH_CONCURRENCY = 3
        private const val EXTEND_FORWARD_WINDOW = 6
        private const val PROGRESS_SAVE_INTERVAL_MS = 2000L
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

// ---------- Search relevance ----------

/**
 * Relevance ranking for manga search results. Sources return matches in their own
 * order (popularity, freshness, …), which regularly buries the exact title match
 * under fuzzy ones. This scores each result against the query and sorts stably, so
 * equal scores keep the source's own ordering (source-specific behavior preserved).
 */
internal object MangaSearchRanker {

    /** Lowercase, letters/digits only, runs of other characters collapse to one space. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else if (sb.isNotEmpty() && sb.last() != ' ') {
                sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    /** Higher = closer match. 0 means "the source returned it, nothing textual matched". */
    fun score(query: String, title: String): Int {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 100
        if (t.startsWith(q)) return 85
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val tTokens = t.split(' ').filter { it.isNotEmpty() }
        if (qTokens.isEmpty()) return 0
        if (qTokens.all { qw -> tTokens.any { it.startsWith(qw) } }) return 70
        if (qTokens.all { qw -> t.contains(qw) }) return 55
        if (qTokens.any { qw -> tTokens.any { it.startsWith(qw) } }) return 40
        if (qTokens.any { qw -> t.contains(qw) }) return 25
        return 0
    }

    /** Stable descending sort: stronger matches first, source order breaks ties. */
    fun rank(items: List<MangaBrowseItem>, query: String): List<MangaBrowseItem> {
        if (query.isBlank()) return items
        return items.sortedByDescending { score(query, it.title) }
    }
}
